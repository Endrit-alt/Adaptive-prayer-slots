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
		MeleeFallbackChain chain,
		boolean inLms,
		int prayer,
		int defence,
		Predicate<PrayerUnlock> unlocked,
		PrayerChoice previous,
		Predicate<PrayerChoice> active)
	{
		boolean useChivalry = swap == PrayerSwap.MELEE
			&& chain == MeleeFallbackChain.CHIVALRY_THEN_ULTIMATE;
		PrayerChoice desired;
		if (isEligible(swap.getHighPrayer(), inLms, prayer, defence, unlocked))
		{
			desired = swap.getHighPrayer();
		}
		else if (useChivalry && isEligible(PrayerChoice.CHIVALRY, inLms, prayer, defence, unlocked))
		{
			desired = PrayerChoice.CHIVALRY;
		}
		else if (isEligible(swap.getDefaultFallback(), inLms, prayer, defence, unlocked))
		{
			desired = swap.getDefaultFallback();
		}
		else
		{
			return null;
		}

		if ((previous == swap.getDefaultFallback() || (useChivalry && previous == PrayerChoice.CHIVALRY))
			&& active.test(previous))
		{
			return previous;
		}
		// Reordering and startup may have no remembered selection. Inspect live prayers too.
		if (useChivalry && active.test(PrayerChoice.CHIVALRY))
		{
			return PrayerChoice.CHIVALRY;
		}
		if (active.test(swap.getDefaultFallback()))
		{
			return swap.getDefaultFallback();
		}
		return desired;
	}

	private static boolean isEligible(
		PrayerChoice choice, boolean inLms, int prayer, int defence, Predicate<PrayerUnlock> unlocked)
	{
		return choice.meetsLevels(prayer, defence)
			&& (inLms || choice.getUnlock() == PrayerUnlock.NONE || unlocked.test(choice.getUnlock()));
	}
}
