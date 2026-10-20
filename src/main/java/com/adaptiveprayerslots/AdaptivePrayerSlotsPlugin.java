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
import net.runelite.api.GameState;
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
	private PrayerBookSnapshot prayerBook;
	private final PrayerBookResolver prayerBookResolver = new PrayerBookResolver();
	private boolean resolutionPending;
	private long resolutionGeneration;
	private final Map<PrayerChoice, Widget> prayerWidgetCache = new EnumMap<>(PrayerChoice.class);
	private final Map<PrayerChoice, QuickPrayerWidgets> quickPrayerWidgetCache = new EnumMap<>(PrayerChoice.class);
	private final Map<PrayerSwap, PrayerChoice> displayedChoices = new EnumMap<>(PrayerSwap.class);
	private final Map<Widget, WidgetState> quickPrayerBaseline = new IdentityHashMap<>();
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
			requestPrayerRefresh();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (running && (event.getSkill() == Skill.PRAYER || event.getSkill() == Skill.DEFENCE
			|| event.getSkill() == Skill.ATTACK || event.getSkill() == Skill.STRENGTH))
		{
			requestPrayerRefresh();
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
			requestPrayerRefresh();
		}
		else if (scriptId == ScriptID.PRAYER_REDRAW)
		{
			prayerWidgetCache.clear();
			requestPrayerRefresh();
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

	private boolean isInScope()
	{
		if (!running || client.getGameState() != GameState.LOGGED_IN
			|| client.getVarbitValue(VarbitID.PRAYERBOOK) != 0)
		{
			return false;
		}
		if (!config.lmsOnly())
		{
			return true;
		}
		Player player = client.getLocalPlayer();
		return client.getVarbitValue(VarbitID.BR_INGAME) == 1 && player != null
			&& player.getWorldLocation().getX() >= 6400;
	}

	private void requestPrayerRefresh()
	{
		if (!isInScope())
		{
			stopControllingLayout();
			return;
		}
		if (resolutionPending || redrawScheduler.isPending())
		{
			return;
		}
		resolutionPending = true;
		long generation = resolutionGeneration;
		clientThread.invokeLater(() ->
		{
			if (generation != resolutionGeneration)
			{
				return;
			}
			try
			{
				if (!isInScope())
				{
					stopControllingLayout();
					return;
				}
				if (redrawScheduler.isPending())
				{
					return;
				}
				// These read-only game scripts must execute after the current script returns.
				PrayerBookSnapshot resolved = prayerBookResolver.resolve(client);
				if (generation != resolutionGeneration)
				{
					return;
				}
				if (resolved == null)
				{
					prayerBook = null;
					stopControllingLayout();
					return;
				}
				if (!resolved.hasSameLayout(prayerBook))
				{
					if (prayerBook != null && (controllingLayout || suspendedForReordering))
					{
						stopControllingLayout();
						return;
					}
					prayerWidgetCache.clear();
					quickPrayerWidgetCache.clear();
				}
				prayerBook = resolved;
			}
			catch (RuntimeException ex)
			{
				prayerBook = null;
				stopControllingLayout();
				throw ex;
			}
			finally
			{
				if (generation == resolutionGeneration)
				{
					resolutionPending = false;
				}
			}
			if (generation == resolutionGeneration)
			{
				applyCurrentSwaps();
			}
		});
	}

	private void applyCurrentSwaps()
	{
		if (!running || client.getGameState() != GameState.LOGGED_IN
			|| redrawScheduler.isPending() || resolutionPending)
		{
			return;
		}
		if (!isInScope())
		{
			stopControllingLayout();
			return;
		}
		if (prayerBook == null)
		{
			requestPrayerRefresh();
			return;
		}
		applySwaps(prayerBook);
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

	private void applySwaps(PrayerBookSnapshot prayerBook)
	{
		if (redrawScheduler.isPending())
		{
			return;
		}
		Set<PrayerChoice> allControlledChoices = EnumSet.noneOf(PrayerChoice.class);
		for (PrayerSwap swap : PrayerSwap.values())
		{
			if (isEnabled(swap))
			{
				allControlledChoices.addAll(getControlledChoices(swap, prayerBook));
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
				swap, prayerBook.getHighPrayer(swap), prayerBook.getFallback(swap), config.meleeFallbackChain(),
				prayerBook::isAvailable, displayedChoices.get(swap),
				choice -> client.getVarbitValue(choice.getActiveVarbit()) != 0);
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
			Set<PrayerChoice> controlledChoices = getControlledChoices(swap, prayerBook);
			applySwap(prayerBook, prayerBook.getHighPrayer(swap), selected, controlledChoices);
			applyQuickPrayerSwap(prayerBook, prayerBook.getHighPrayer(swap), selected, controlledChoices);
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

	private Set<PrayerChoice> getControlledChoices(PrayerSwap swap, PrayerBookSnapshot prayerBook)
	{
		EnumSet<PrayerChoice> choices = EnumSet.of(prayerBook.getHighPrayer(swap), prayerBook.getFallback(swap));
		if (swap == PrayerSwap.MELEE
			&& config.meleeFallbackChain() == MeleeFallbackChain.CHIVALRY_THEN_ULTIMATE)
		{
			choices.add(PrayerChoice.CHIVALRY);
		}
		return choices;
	}

	private void applySwap(
		PrayerBookSnapshot prayerBook,
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
		PrayerBookSnapshot prayerBook,
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

	private Widget findPrayerWidget(PrayerBookSnapshot prayerBook, PrayerChoice choice)
	{
		PrayerBookSnapshot.Entry entry = prayerBook.get(choice);
		if (entry == null)
		{
			return null;
		}
		Widget widget = client.getWidget(entry.componentId);
		Widget cached = prayerWidgetCache.get(choice);
		if (cached != null && cached == widget)
		{
			return cached;
		}
		if (widget != null)
		{
			prayerWidgetCache.put(choice, widget);
		}
		else
		{
			prayerWidgetCache.remove(choice);
		}
		return widget;
	}

	private QuickPrayerWidgets findQuickPrayerWidgets(PrayerBookSnapshot prayerBook, PrayerChoice choice)
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
		PrayerBookSnapshot.Entry entry = prayerBook.get(choice);
		if (entry == null || children == null || children.length != prayerBook.getChildCount())
		{
			return null;
		}

		QuickPrayerWidgets cached = quickPrayerWidgetCache.get(choice);
		if (cached != null && cached.isCurrent(children))
		{
			return cached;
		}
		quickPrayerWidgetCache.remove(choice);

		int prayerId = entry.targetIndex;
		int spriteIndex = entry.spriteIndex;
		if (prayerId < 0 || spriteIndex < 0 || prayerId >= children.length || spriteIndex + 1 >= children.length
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

	private boolean isReorderingUnlocked(PrayerBookSnapshot prayerBook, Set<PrayerChoice> controlledChoices)
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

	private void invalidateAllCaches()
	{
		resolutionGeneration++;
		resolutionPending = false;
		prayerBook = null;
		prayerWidgetCache.clear();
		quickPrayerWidgetCache.clear();
		cachedQuickPrayerContainer = null;
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
