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
			controllingLayout = false;
			suspendedForReordering = false;
			displayedChoices.clear();
			restoreQuickPrayerBaseline();
			invalidateAllCaches();
			redrawPrayerBook();
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
				restoreQuickPrayerBaseline();
				invalidateAllCaches();
				redrawPrayerBook();
				applyCurrentSwaps();
			});
		}
	}

	private void applyCurrentSwaps()
	{
		if (!running || client.getGameState() != GameState.LOGGED_IN)
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

		int effectivePrayerLevel = resolveEffectiveLevel(
			inLms,
			client.getRealSkillLevel(Skill.PRAYER),
			client.getBoostedSkillLevel(Skill.PRAYER));
		int effectiveDefenceLevel = resolveEffectiveLevel(
			inLms,
			client.getRealSkillLevel(Skill.DEFENCE),
			client.getBoostedSkillLevel(Skill.DEFENCE));
		applySwaps(inLms, effectivePrayerLevel, effectiveDefenceLevel);
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
		redrawPrayerBook();
	}

	private void applySwaps(boolean inLms, int effectivePrayerLevel, int effectiveDefenceLevel)
	{
		EnumComposition prayerBook = getNormalPrayerBook();
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
		controllingLayout = true;
		for (PrayerSwap swap : PrayerSwap.values())
		{
			if (!isEnabled(swap))
			{
				displayedChoices.remove(swap);
				continue;
			}

			Set<PrayerChoice> controlledChoices = getControlledChoices(swap);
			PrayerChoice desired = choosePrayer(swap, inLms, effectivePrayerLevel, effectiveDefenceLevel);
			PrayerChoice selected = preserveActiveFallback(swap, desired, controlledChoices);
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

	private PrayerChoice choosePrayer(
		PrayerSwap swap,
		boolean inLms,
		int effectivePrayerLevel,
		int effectiveDefenceLevel)
	{
		if (isChoiceEligible(swap.getHighPrayer(), inLms, effectivePrayerLevel, effectiveDefenceLevel))
		{
			return swap.getHighPrayer();
		}

		if (swap == PrayerSwap.MELEE
			&& config.meleeFallbackChain() == MeleeFallbackChain.CHIVALRY_THEN_ULTIMATE
			&& isChoiceEligible(PrayerChoice.CHIVALRY, inLms, effectivePrayerLevel, effectiveDefenceLevel))
		{
			return PrayerChoice.CHIVALRY;
		}

		return swap.getDefaultFallback();
	}

	private PrayerChoice preserveActiveFallback(
		PrayerSwap swap,
		PrayerChoice desired,
		Set<PrayerChoice> controlledChoices)
	{
		PrayerChoice current = displayedChoices.get(swap);
		if (current != null
			&& current != desired
			&& current != swap.getHighPrayer()
			&& controlledChoices.contains(current)
			&& client.isPrayerActive(current.getPrayer()))
		{
			return current;
		}
		return desired;
	}

	private Set<PrayerChoice> getControlledChoices(PrayerSwap swap)
	{
		EnumSet<PrayerChoice> choices = EnumSet.of(swap.getHighPrayer(), swap.getDefaultFallback());
		if (swap == PrayerSwap.MELEE
			&& config.meleeFallbackChain() == MeleeFallbackChain.CHIVALRY_THEN_ULTIMATE)
		{
			choices.add(PrayerChoice.CHIVALRY);
		}
		return choices;
	}

	private boolean isChoiceEligible(PrayerChoice choice, boolean inLms, int prayer, int defence)
	{
		if (!choice.meetsLevels(prayer, defence))
		{
			return false;
		}
		if (inLms || choice.getUnlock() == PrayerUnlock.NONE)
		{
			return true;
		}

		switch (choice.getUnlock())
		{
			case PIETY:
				return client.getVarbitValue(VarbitID.KR_KNIGHTWAVES_STATE) >= 8
					|| client.getVarbitValue(VarbitID.HUMBLE_PIETY) != 0;
			case RIGOUR:
				return client.getVarbitValue(VarbitID.PRAYER_RIGOUR_UNLOCKED) != 0;
			case AUGURY:
				return client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED) != 0;
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
		Widget highPrayer = findPrayerWidget(prayerBook, highChoice);
		Widget selectedPrayer = findPrayerWidget(prayerBook, selectedChoice);
		if (highPrayer == null || selectedPrayer == null)
		{
			return;
		}

		for (PrayerChoice choice : controlledChoices)
		{
			if (choice != selectedChoice)
			{
				Widget widget = findPrayerWidget(prayerBook, choice);
				if (widget != null)
				{
					setHidden(widget, true);
				}
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
		QuickPrayerWidgets highPrayer = findQuickPrayerWidgets(prayerBook, highChoice);
		QuickPrayerWidgets selectedPrayer = findQuickPrayerWidgets(prayerBook, selectedChoice);
		if (highPrayer == null || selectedPrayer == null)
		{
			return;
		}

		for (PrayerChoice choice : controlledChoices)
		{
			QuickPrayerWidgets widgets = findQuickPrayerWidgets(prayerBook, choice);
			if (widgets != null)
			{
				captureQuickPrayerBaseline(widgets);
				if (choice != selectedChoice)
				{
					setHidden(widgets, true);
				}
			}
		}
		moveToBaselineSlots(selectedPrayer, highPrayer);
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
		QuickPrayerWidgets cached = quickPrayerWidgetCache.get(choice);
		if (cached != null)
		{
			return cached;
		}

		Widget container = client.getWidget(InterfaceID.Quickprayer.BUTTONS);
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

		int prayerId = -1;
		int sortedIndex = 0;
		int[] keys = prayerBook.getKeys();
		int[] values = prayerBook.getIntVals();
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
		if (prayerId < 0 || prayerId >= prayerCount || spriteIndex + 1 >= children.length)
		{
			return null;
		}

		QuickPrayerWidgets widgets = new QuickPrayerWidgets(
			children[prayerId], children[spriteIndex], children[spriteIndex + 1]);
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
		for (Map.Entry<Widget, WidgetState> entry : quickPrayerBaseline.entrySet())
		{
			Widget widget = entry.getKey();
			WidgetState state = entry.getValue();
			setPosition(widget, state.x, state.y);
			setHidden(widget, state.hidden);
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
		prayerWidgetCache.clear();
		quickPrayerWidgetCache.clear();
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

		private QuickPrayerWidgets(Widget target, Widget sprite, Widget toggle)
		{
			this.target = target;
			this.sprite = sprite;
			this.toggle = toggle;
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
