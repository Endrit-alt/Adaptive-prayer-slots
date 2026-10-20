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
		PrayerChoice high,
		PrayerChoice fallback,
		MeleeFallbackChain chain,
		Predicate<PrayerChoice> available,
		PrayerChoice previous,
		Predicate<PrayerChoice> active)
	{
		boolean useChivalry = swap == PrayerSwap.MELEE
			&& chain == MeleeFallbackChain.CHIVALRY_THEN_ULTIMATE;
		PrayerChoice desired;
		if (available.test(high))
		{
			if (fallback != swap.getDefaultFallback())
			{
				// Keep the core layout when the Titan prayer and high prayer coexist.
				// Only delay restoration for an already-selected active Titan fallback.
				return previous == fallback && active.test(fallback) ? fallback : null;
			}
			desired = high;
		}
		else if (useChivalry && available.test(PrayerChoice.CHIVALRY))
		{
			desired = PrayerChoice.CHIVALRY;
		}
		else if (available.test(fallback))
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
}
