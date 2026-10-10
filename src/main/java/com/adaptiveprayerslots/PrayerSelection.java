package com.adaptiveprayerslots;

import java.util.function.Predicate;

final class PrayerSelection
{
	private PrayerSelection()
	{
	}

	// A null result means the core Prayer plugin should retain this slot.
	static PrayerChoice select(
		PrayerSwap swap,
		PrayerChoice fallback,
		MeleeFallbackChain chain,
		boolean temporaryUnlocks,
		int prayer,
		int defence,
		Predicate<PrayerUnlock> unlocked,
		PrayerChoice previous,
		Predicate<PrayerChoice> active)
	{
		boolean useChivalry = swap == PrayerSwap.MELEE
			&& chain == MeleeFallbackChain.CHIVALRY_THEN_ULTIMATE;
		PrayerChoice desired;
		if (isEligible(swap.getHighPrayer(), temporaryUnlocks, prayer, defence, unlocked))
		{
			if (fallback != swap.getDefaultFallback())
			{
				// Keep the core layout when the Titan prayer and high prayer coexist.
				// Only delay restoration for an already-selected active Titan fallback.
				return previous == fallback && active.test(fallback) ? fallback : null;
			}
			desired = swap.getHighPrayer();
		}
		else if (useChivalry && isEligible(PrayerChoice.CHIVALRY, temporaryUnlocks, prayer, defence, unlocked))
		{
			desired = PrayerChoice.CHIVALRY;
		}
		else if (isEligible(fallback, temporaryUnlocks, prayer, defence, unlocked))
		{
			desired = fallback;
		}
		else
		{
			return null;
		}

		if ((previous == fallback || (useChivalry && previous == PrayerChoice.CHIVALRY))
			&& active.test(previous))
		{
			return previous;
		}
		// Reordering and startup may have no remembered selection. Inspect live prayers too.
		if (useChivalry && active.test(PrayerChoice.CHIVALRY))
		{
			return PrayerChoice.CHIVALRY;
		}
		if (active.test(fallback))
		{
			return fallback;
		}
		return desired;
	}

	private static boolean isEligible(
		PrayerChoice choice, boolean temporaryUnlocks, int prayer, int defence, Predicate<PrayerUnlock> unlocked)
	{
		return choice.meetsLevels(prayer, defence)
			&& (temporaryUnlocks || choice.getUnlock() == PrayerUnlock.NONE || unlocked.test(choice.getUnlock()));
	}
}
