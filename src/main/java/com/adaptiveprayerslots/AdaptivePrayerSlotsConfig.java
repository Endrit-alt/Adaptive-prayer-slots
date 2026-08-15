package com.adaptiveprayerslots;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(AdaptivePrayerSlotsConfig.GROUP)
public interface AdaptivePrayerSlotsConfig extends Config
{
	String GROUP = "adaptivePrayerSlots";

	@ConfigItem(
		keyName = "lmsOnly",
		name = "LMS only",
		description = "Only alter prayer slots while inside a Last Man Standing game",
		position = 0
	)
	default boolean lmsOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "swapMelee",
		name = "Adaptive melee slot",
		description = "Replace unavailable Piety using the selected melee fallback chain",
		position = 1
	)
	default boolean swapMelee()
	{
		return true;
	}

	@ConfigItem(
		keyName = "meleeFallbackChain",
		name = "Melee fallback chain",
		description = "Choose whether usable Chivalry is preferred before Ultimate Strength",
		position = 2
	)
	default MeleeFallbackChain meleeFallbackChain()
	{
		return MeleeFallbackChain.ULTIMATE_ONLY;
	}

	@ConfigItem(
		keyName = "swapRanged",
		name = "Adaptive ranged slot",
		description = "Replace unavailable Rigour with Eagle Eye (requires 74 Prayer, 70 Defence and its unlock)",
		position = 3
	)
	default boolean swapRanged()
	{
		return true;
	}

	@ConfigItem(
		keyName = "swapMagic",
		name = "Adaptive magic slot",
		description = "Replace unavailable Augury with Mystic Might (requires 77 Prayer, 70 Defence and its unlock)",
		position = 4
	)
	default boolean swapMagic()
	{
		return true;
	}
}
