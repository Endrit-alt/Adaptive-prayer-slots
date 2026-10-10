package com.adaptiveprayerslots;

import com.google.inject.Provides;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.ParamID;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Adaptive Prayer Slots",
	description = "Keeps prayer slots consistent across different LMS builds",
	tags = {"lms", "last man standing", "prayer", "prayers", "pvp", "minigame"}
)
public class AdaptivePrayerSlotsPlugin extends Plugin
{
	private static final String REORDER_WARNING =
		"Adaptive Prayer Slots: temporarily paused while prayer reordering is enabled.";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private AdaptivePrayerSlotsConfig config;

	private boolean running;
	private boolean controllingLayout;
	private boolean suspendedForReordering;
	private boolean reorderWarningShown;
	private int cachedPrayerBookVariant = -1;
	private EnumComposition cachedNormalPrayerBook;
	private final Map<PrayerChoice, Widget> prayerWidgetCache = new EnumMap<>(PrayerChoice.class);
	private final Map<PrayerChoice, QuickPrayerWidgets> quickPrayerWidgetCache = new EnumMap<>(PrayerChoice.class);
	private final Map<PrayerSwap, PrayerChoice> displayedChoices = new EnumMap<>(PrayerSwap.class);
	private final Map<Widget, WidgetState> quickPrayerBaseline = new IdentityHashMap<>();
	private final PeteKayerChallengeRules peteKayerChallengeRules = new PeteKayerChallengeRules();
	private final PrayerRedrawScheduler redrawScheduler = new PrayerRedrawScheduler(
		task -> clientThread.invokeLater(task), this::redrawPrayerBook, this::applyCurrentSwaps);
	private Widget cachedQuickPrayerContainer;

	@Provides
	AdaptivePrayerSlotsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AdaptivePrayerSlotsConfig.class);
	}

	@Override
	protected void startUp()
	{
		running = true;
		clientThread.invokeLater(this::applyCurrentSwaps);
	}

	@Override
	protected void shutDown()
	{
		running = false;
		clientThread.invokeLater(() ->
		{
			if (running)
			{
				return;
			}
			controllingLayout = false;
			suspendedForReordering = false;
			displayedChoices.clear();
			restoreQuickPrayerBaseline();
			invalidateAllCaches();
			redrawScheduler.request();
		});
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (running && client.getGameState() == GameState.LOGGED_IN)
		{
			applyCurrentSwaps();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (running && (event.getSkill() == Skill.PRAYER || event.getSkill() == Skill.DEFENCE))
		{
			applyCurrentSwaps();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		redrawScheduler.cancel();
		invalidateAllCaches();
		reorderWarningShown = false;
		if (running && event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::applyCurrentSwaps);
		}
		else
		{
			quickPrayerBaseline.clear();
		}
	}

	@Subscribe(priority = -100)
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (!running)
		{
			return;
		}

		int scriptId = event.getScriptId();
		if (scriptId == ScriptID.QUICKPRAYER_INIT)
		{
			quickPrayerBaseline.clear();
			quickPrayerWidgetCache.clear();
			cachedQuickPrayerContainer = null;
			applyCurrentSwaps();
		}
		else if (scriptId == ScriptID.PRAYER_REDRAW)
		{
			prayerWidgetCache.clear();
			applyCurrentSwaps();
		}
		else if (scriptId == ScriptID.PRAYER_UPDATEBUTTON)
		{
			applyCurrentSwaps();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (running && AdaptivePrayerSlotsConfig.GROUP.equals(event.getGroup()))
		{
			clientThread.invokeLater(() ->
			{
				if (!running)
				{
					return;
				}
				controllingLayout = false;
				suspendedForReordering = false;
				displayedChoices.clear();
				restoreQuickPrayerBaseline();
				invalidateAllCaches();
				redrawScheduler.request();
			});
		}
	}

	private void applyCurrentSwaps()
	{
		if (!running || client.getGameState() != GameState.LOGGED_IN || redrawScheduler.isPending())
		{
			return;
		}

		boolean inLms = client.getVarbitValue(VarbitID.BR_INGAME) != 0;
		if (config.lmsOnly() && !inLms)
		{
			stopControllingLayout();
			return;
		}

		if (client.getVarbitValue(VarbitID.PRAYERBOOK) != 0)
		{
			return;
		}

		Player player = client.getLocalPlayer();
		int[] varps = client.getVarps();
		int tutorial = varps.length > PeteKayerChallengeRules.ACTIVE_TUTORIAL_VARP
			? varps[PeteKayerChallengeRules.ACTIVE_TUTORIAL_VARP] : -1;
		boolean inPeteKayerChallenge = !inLms && player != null
			&& PeteKayerChallengeRules.isActive(tutorial, player.getWorldLocation().getX());
		int effectivePrayerLevel;
		int effectiveDefenceLevel;
		if (inPeteKayerChallenge)
		{
			// The challenge grants prayer access independently of base stats and current
			// Prayer points. Follow its availability overrides, not boosted/drained stats.
			effectivePrayerLevel = peteKayerChallengeRules.getPrayerLevel(client, tutorial);
			if (effectivePrayerLevel < 0)
			{
				// Unknown challenge data: leave the game's layout alone rather than guess.
				stopControllingLayout();
				return;
			}
			effectiveDefenceLevel = PeteKayerChallengeRules.resolveDefenceLevel(
				client.getVarbitValue(VarbitID.BR_ACTIVE_BUILD_PLAYER));
		}
		else
		{
			peteKayerChallengeRules.clear();
			effectivePrayerLevel = resolveEffectiveLevel(
				inLms,
				client.getRealSkillLevel(Skill.PRAYER),
				client.getBoostedSkillLevel(Skill.PRAYER));
			effectiveDefenceLevel = resolveEffectiveLevel(
				inLms,
				client.getRealSkillLevel(Skill.DEFENCE),
				client.getBoostedSkillLevel(Skill.DEFENCE));
		}
		applySwaps(inLms || inPeteKayerChallenge, effectivePrayerLevel, effectiveDefenceLevel);
	}

	private void stopControllingLayout()
	{
		reorderWarningShown = false;
		if (!controllingLayout && !suspendedForReordering)
		{
			return;
		}

		controllingLayout = false;
		suspendedForReordering = false;
		displayedChoices.clear();
		restoreQuickPrayerBaseline();
		invalidateAllCaches();
		redrawScheduler.request();
	}

	private void applySwaps(boolean temporaryUnlocks, int effectivePrayerLevel, int effectiveDefenceLevel)
	{
		EnumComposition prayerBook = getNormalPrayerBook();
		if (redrawScheduler.isPending())
		{
			return;
		}
		Set<PrayerChoice> allControlledChoices = EnumSet.noneOf(PrayerChoice.class);
		for (PrayerSwap swap : PrayerSwap.values())
		{
			if (isEnabled(swap))
			{
				allControlledChoices.addAll(getControlledChoices(swap));
			}
		}

		// RuneLite's unlocked reorder mode needs every prayer widget visible so each
		// position can be selected and dragged. Let the core plugin own the layout
		// until reordering is locked again.
		if (isReorderingUnlocked(prayerBook, allControlledChoices))
		{
			suspendForReordering();
			return;
		}

		suspendedForReordering = false;
		updateReorderWarning(false);
		Map<PrayerSwap, PrayerChoice> selections = new EnumMap<>(PrayerSwap.class);
		for (PrayerSwap swap : PrayerSwap.values())
		{
			if (!isEnabled(swap))
			{
				continue;
			}

			PrayerChoice selected = PrayerSelection.select(
				swap, getFallback(swap), config.meleeFallbackChain(), temporaryUnlocks, effectivePrayerLevel, effectiveDefenceLevel,
				this::isUnlocked, displayedChoices.get(swap), choice -> client.isPrayerActive(choice.getPrayer()));
			if (selected != null)
			{
				selections.put(swap, selected);
			}
		}

		for (PrayerSwap previous : displayedChoices.keySet())
		{
			if (!selections.containsKey(previous))
			{
				// Restore slots with no usable choice or a Titan/high pair left to the core layout.
				stopControllingLayout();
				return;
			}
		}

		controllingLayout = !selections.isEmpty();
		for (Map.Entry<PrayerSwap, PrayerChoice> entry : selections.entrySet())
		{
			PrayerSwap swap = entry.getKey();
			PrayerChoice selected = entry.getValue();
			Set<PrayerChoice> controlledChoices = getControlledChoices(swap);
			applySwap(prayerBook, swap.getHighPrayer(), selected, controlledChoices);
			applyQuickPrayerSwap(prayerBook, swap.getHighPrayer(), selected, controlledChoices);
			displayedChoices.put(swap, selected);
		}
	}

	private void suspendForReordering()
	{
		updateReorderWarning(true);
		if (suspendedForReordering)
		{
			return;
		}

		suspendedForReordering = true;
		controllingLayout = false;
		displayedChoices.clear();
		restoreQuickPrayerBaseline();
		invalidateAllCaches();
	}

	static int resolveEffectiveLevel(boolean inLms, int realLevel, int boostedLevel)
	{
		return inLms ? boostedLevel : realLevel;
	}

	private PrayerChoice getFallback(PrayerSwap swap)
	{
		// Use the same variant as widget lookup: Titan prayers replace, rather than
		// coexist with, Eagle Eye/Mystic Might in the prayer-book enum.
		if (swap == PrayerSwap.RANGED && (cachedPrayerBookVariant & 1) != 0)
		{
			return PrayerChoice.DEADEYE;
		}
		if (swap == PrayerSwap.MAGIC && (cachedPrayerBookVariant & 2) != 0)
		{
			return PrayerChoice.MYSTIC_VIGOUR;
		}
		return swap.getDefaultFallback();
	}

	private Set<PrayerChoice> getControlledChoices(PrayerSwap swap)
	{
		EnumSet<PrayerChoice> choices = EnumSet.of(swap.getHighPrayer(), getFallback(swap));
		if (swap == PrayerSwap.MELEE
			&& config.meleeFallbackChain() == MeleeFallbackChain.CHIVALRY_THEN_ULTIMATE)
		{
			choices.add(PrayerChoice.CHIVALRY);
		}
		return choices;
	}

	private boolean isUnlocked(PrayerUnlock unlock)
	{
		switch (unlock)
		{
			case PIETY:
				return client.getVarbitValue(VarbitID.KR_KNIGHTWAVES_STATE) >= 8
					|| client.getVarbitValue(VarbitID.HUMBLE_PIETY) != 0;
			case RIGOUR:
				return client.getVarbitValue(VarbitID.PRAYER_RIGOUR_UNLOCKED) != 0;
			case AUGURY:
				return client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED) != 0;
			case DEADEYE:
				return client.getVarbitValue(VarbitID.PRAYER_DEADEYE_UNLOCKED) != 0;
			case MYSTIC_VIGOUR:
				return client.getVarbitValue(VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED) != 0;
			default:
				return true;
		}
	}

	private void applySwap(
		EnumComposition prayerBook,
		PrayerChoice highChoice,
		PrayerChoice selectedChoice,
		Set<PrayerChoice> controlledChoices)
	{
		Map<PrayerChoice, Widget> resolved = new EnumMap<>(PrayerChoice.class);
		for (PrayerChoice choice : controlledChoices)
		{
			Widget widget = findPrayerWidget(prayerBook, choice);
			if (widget == null)
			{
				return;
			}
			resolved.put(choice, widget);
		}
		Widget highPrayer = resolved.get(highChoice);
		Widget selectedPrayer = resolved.get(selectedChoice);

		for (PrayerChoice choice : controlledChoices)
		{
			if (choice != selectedChoice)
			{
				setHidden(resolved.get(choice), true);
			}
		}
		setPosition(selectedPrayer, highPrayer.getOriginalX(), highPrayer.getOriginalY());
		setHidden(selectedPrayer, false);
	}

	private void applyQuickPrayerSwap(
		EnumComposition prayerBook,
		PrayerChoice highChoice,
		PrayerChoice selectedChoice,
		Set<PrayerChoice> controlledChoices)
	{
		Map<PrayerChoice, QuickPrayerWidgets> resolved = new EnumMap<>(PrayerChoice.class);
		for (PrayerChoice choice : controlledChoices)
		{
			QuickPrayerWidgets widgets = findQuickPrayerWidgets(prayerBook, choice);
			if (widgets == null)
			{
				return;
			}
			resolved.put(choice, widgets);
		}

		// Resolve and snapshot the entire slot before changing any visibility or position.
		for (QuickPrayerWidgets widgets : resolved.values())
		{
			captureQuickPrayerBaseline(widgets);
		}
		for (PrayerChoice choice : controlledChoices)
		{
			if (choice != selectedChoice)
			{
				setHidden(resolved.get(choice), true);
			}
		}
		QuickPrayerWidgets selectedPrayer = resolved.get(selectedChoice);
		moveToBaselineSlots(selectedPrayer, resolved.get(highChoice));
		setHidden(selectedPrayer, false);
	}

	private Widget findPrayerWidget(EnumComposition prayerBook, PrayerChoice choice)
	{
		Widget cached = prayerWidgetCache.get(choice);
		if (cached != null)
		{
			return cached;
		}

		for (int prayerObjectId : prayerBook.getIntVals())
		{
			ItemComposition prayer = client.getItemDefinition(prayerObjectId);
			if (prayer.getIntValue(ParamID.OC_PRAYER_LEVEL) == choice.getPrayerLevel())
			{
				Widget widget = client.getWidget(prayer.getIntValue(ParamID.OC_PRAYER_COMPONENT));
				if (widget != null)
				{
					prayerWidgetCache.put(choice, widget);
				}
				return widget;
			}
		}
		return null;
	}

	private QuickPrayerWidgets findQuickPrayerWidgets(EnumComposition prayerBook, PrayerChoice choice)
	{
		Widget container = client.getWidget(InterfaceID.Quickprayer.BUTTONS);
		if (container != cachedQuickPrayerContainer)
		{
			quickPrayerWidgetCache.clear();
			quickPrayerBaseline.clear();
			cachedQuickPrayerContainer = container;
		}
		if (container == null)
		{
			return null;
		}

		Widget[] children = container.getDynamicChildren();
		int prayerCount = prayerBook.size();
		if (children == null || children.length != prayerCount * 3)
		{
			return null;
		}

		QuickPrayerWidgets cached = quickPrayerWidgetCache.get(choice);
		if (cached != null && cached.isCurrent(children))
		{
			return cached;
		}
		quickPrayerWidgetCache.remove(choice);

		int prayerId = -1;
		int sortedIndex = 0;
		int[] keys = prayerBook.getKeys();
		int[] values = prayerBook.getIntVals();
		if (keys.length != values.length)
		{
			return null;
		}
		for (int i = 0; i < values.length; i++)
		{
			int level = client.getItemDefinition(values[i]).getIntValue(ParamID.OC_PRAYER_LEVEL);
			if (level == choice.getPrayerLevel())
			{
				prayerId = keys[i];
			}
			else if (level < choice.getPrayerLevel())
			{
				sortedIndex++;
			}
		}

		int spriteIndex = prayerCount + 2 * sortedIndex;
		if (prayerId < 0 || prayerId >= prayerCount || spriteIndex + 1 >= children.length
			|| children[prayerId] == null || children[spriteIndex] == null || children[spriteIndex + 1] == null)
		{
			return null;
		}

		QuickPrayerWidgets widgets = new QuickPrayerWidgets(children, prayerId, spriteIndex);
		quickPrayerWidgetCache.put(choice, widgets);
		return widgets;
	}

	private void captureQuickPrayerBaseline(QuickPrayerWidgets widgets)
	{
		captureQuickPrayerBaseline(widgets.target);
		captureQuickPrayerBaseline(widgets.sprite);
		captureQuickPrayerBaseline(widgets.toggle);
	}

	private void captureQuickPrayerBaseline(Widget widget)
	{
		quickPrayerBaseline.computeIfAbsent(widget, WidgetState::new);
	}

	private void moveToBaselineSlots(QuickPrayerWidgets widgets, QuickPrayerWidgets slots)
	{
		moveToBaselineSlot(widgets.target, slots.target);
		moveToBaselineSlot(widgets.sprite, slots.sprite);
		moveToBaselineSlot(widgets.toggle, slots.toggle);
	}

	private void moveToBaselineSlot(Widget widget, Widget slot)
	{
		WidgetState slotState = quickPrayerBaseline.get(slot);
		if (slotState != null)
		{
			setPosition(widget, slotState.x, slotState.y);
		}
	}

	private boolean isReorderingUnlocked(EnumComposition prayerBook, Set<PrayerChoice> controlledChoices)
	{
		for (PrayerChoice choice : controlledChoices)
		{
			Widget widget = findPrayerWidget(prayerBook, choice);
			if (widget == null || widget.getActions() == null)
			{
				continue;
			}
			for (String action : widget.getActions())
			{
				if ("Hide".equals(action) || "Unhide".equals(action))
				{
					return true;
				}
			}
		}
		return false;
	}

	private void updateReorderWarning(boolean reorderingUnlocked)
	{
		if (reorderingUnlocked && !reorderWarningShown)
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(REORDER_WARNING)
				.build());
		}
		reorderWarningShown = reorderingUnlocked;
	}

	private void setHidden(QuickPrayerWidgets widgets, boolean hidden)
	{
		setHidden(widgets.target, hidden);
		setHidden(widgets.sprite, hidden);
		setHidden(widgets.toggle, hidden);
	}

	private void setHidden(Widget widget, boolean hidden)
	{
		if (widget.isSelfHidden() != hidden)
		{
			widget.setHidden(hidden);
			widget.revalidate();
		}
	}

	private void setPosition(Widget widget, int x, int y)
	{
		if (widget.getOriginalX() != x || widget.getOriginalY() != y)
		{
			widget.setPos(x, y);
			widget.revalidate();
		}
	}

	private void restoreQuickPrayerBaseline()
	{
		Widget container = client.getWidget(InterfaceID.Quickprayer.BUTTONS);
		Widget[] children = container == null ? null : container.getDynamicChildren();
		if (children != null)
		{
			for (Widget widget : children)
			{
				WidgetState state = quickPrayerBaseline.get(widget);
				if (state != null)
				{
					setPosition(widget, state.x, state.y);
					setHidden(widget, state.hidden);
				}
			}
		}
		quickPrayerBaseline.clear();
		quickPrayerWidgetCache.clear();
	}

	private EnumComposition getNormalPrayerBook()
	{
		int variant = (client.getVarbitValue(VarbitID.PRAYER_DEADEYE_UNLOCKED) != 0 ? 1 : 0)
			| (client.getVarbitValue(VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED) != 0 ? 2 : 0);
		if (cachedNormalPrayerBook != null && cachedPrayerBookVariant == variant)
		{
			return cachedNormalPrayerBook;
		}

		if (cachedPrayerBookVariant != -1 && cachedPrayerBookVariant != variant)
		{
			// Unlocks can replace/re-sort fallback widgets while the interface is open.
			// Restore the old layout before capturing or moving the new variant.
			stopControllingLayout();
		}
		cachedPrayerBookVariant = variant;
		prayerWidgetCache.clear();
		quickPrayerWidgetCache.clear();
		switch (variant)
		{
			case 3:
				cachedNormalPrayerBook = client.getEnum(EnumID.PRAYERS_NORMAL_DEADEYE_MYSTIC_VIGOUR);
				break;
			case 1:
				cachedNormalPrayerBook = client.getEnum(EnumID.PRAYERS_NORMAL_DEADEYE);
				break;
			case 2:
				cachedNormalPrayerBook = client.getEnum(EnumID.PRAYERS_NORMAL_MYSTIC_VIGOUR);
				break;
			default:
				cachedNormalPrayerBook = client.getEnum(EnumID.PRAYERS_NORMAL);
		}
		return cachedNormalPrayerBook;
	}

	private void invalidateAllCaches()
	{
		peteKayerChallengeRules.clear();
		prayerWidgetCache.clear();
		quickPrayerWidgetCache.clear();
		cachedQuickPrayerContainer = null;
		cachedNormalPrayerBook = null;
		cachedPrayerBookVariant = -1;
	}

	private boolean isEnabled(PrayerSwap swap)
	{
		switch (swap)
		{
			case MELEE:
				return config.swapMelee();
			case RANGED:
				return config.swapRanged();
			case MAGIC:
				return config.swapMagic();
			default:
				return false;
		}
	}

	private void redrawPrayerBook()
	{
		// Only PrayerRedrawScheduler may call this, after the current script has returned.
		if (client.getGameState() != GameState.LOGGED_IN
			|| client.getVarbitValue(VarbitID.PRAYERBOOK) != 0)
		{
			return;
		}
		Widget prayerBook = client.getWidget(InterfaceID.PRAYERBOOK, 0);
		if (prayerBook != null && prayerBook.getOnVarTransmitListener() != null)
		{
			client.runScript(prayerBook.getOnVarTransmitListener());
		}
	}

	private static final class QuickPrayerWidgets
	{
		private final Widget target;
		private final Widget sprite;
		private final Widget toggle;
		private final int targetIndex;
		private final int spriteIndex;

		private QuickPrayerWidgets(Widget[] children, int targetIndex, int spriteIndex)
		{
			this.target = children[targetIndex];
			this.sprite = children[spriteIndex];
			this.toggle = children[spriteIndex + 1];
			this.targetIndex = targetIndex;
			this.spriteIndex = spriteIndex;
		}

		private boolean isCurrent(Widget[] children)
		{
			return targetIndex < children.length && spriteIndex + 1 < children.length
				&& children[targetIndex] == target && children[spriteIndex] == sprite
				&& children[spriteIndex + 1] == toggle;
		}
	}

	private static final class WidgetState
	{
		private final int x;
		private final int y;
		private final boolean hidden;

		private WidgetState(Widget widget)
		{
			this.x = widget.getOriginalX();
			this.y = widget.getOriginalY();
			this.hidden = widget.isSelfHidden();
		}
	}
}
