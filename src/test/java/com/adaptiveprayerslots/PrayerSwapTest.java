package com.adaptiveprayerslots;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrayerSwapTest
{
	@Test
	public void meleeFallsBackOnlyBelow70()
	{
		assertFalse(PrayerChoice.PIETY.meetsLevels(69, 70));
		assertTrue(PrayerChoice.PIETY.meetsLevels(70, 70));
	}

	@Test
	public void rangedFallsBackOnlyBelow74()
	{
		assertFalse(PrayerChoice.RIGOUR.meetsLevels(73, 70));
		assertTrue(PrayerChoice.RIGOUR.meetsLevels(74, 70));
	}

	@Test
	public void magicFallsBackOnlyBelow77()
	{
		assertFalse(PrayerChoice.AUGURY.meetsLevels(76, 70));
		assertTrue(PrayerChoice.AUGURY.meetsLevels(77, 70));
	}

	@Test
	public void lmsUsesTheOverriddenCharacterLevel()
	{
		assertFalse(PrayerChoice.PIETY.meetsLevels(
			AdaptivePrayerSlotsPlugin.resolveEffectiveLevel(true, 99, 63),
			AdaptivePrayerSlotsPlugin.resolveEffectiveLevel(true, 99, 50)));
	}

	@Test
	public void normalGameIgnoresCurrentPrayerPoints()
	{
		assertTrue(PrayerChoice.AUGURY.meetsLevels(
			AdaptivePrayerSlotsPlugin.resolveEffectiveLevel(false, 99, 20),
			AdaptivePrayerSlotsPlugin.resolveEffectiveLevel(false, 99, 10)));
	}

	@Test
	public void defenceRestrictedBuildUsesFallbackEvenWith99Prayer()
	{
		assertFalse(PrayerChoice.PIETY.meetsLevels(99, 50));
		assertFalse(PrayerChoice.RIGOUR.meetsLevels(99, 50));
		assertFalse(PrayerChoice.AUGURY.meetsLevels(99, 50));
	}

	@Test
	public void chivalryUsesItsOwnLevelRequirements()
	{
		assertFalse(PrayerChoice.CHIVALRY.meetsLevels(59, 65));
		assertFalse(PrayerChoice.CHIVALRY.meetsLevels(60, 64));
		assertTrue(PrayerChoice.CHIVALRY.meetsLevels(60, 65));
	}
}
