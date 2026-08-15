package com.adaptiveprayerslots;

enum PrayerSwap
{
	MELEE(PrayerChoice.PIETY, PrayerChoice.ULTIMATE_STRENGTH),
	RANGED(PrayerChoice.RIGOUR, PrayerChoice.EAGLE_EYE),
	MAGIC(PrayerChoice.AUGURY, PrayerChoice.MYSTIC_MIGHT);

	private final PrayerChoice highPrayer;
	private final PrayerChoice defaultFallback;

	PrayerSwap(PrayerChoice highPrayer, PrayerChoice defaultFallback)
	{
		this.highPrayer = highPrayer;
		this.defaultFallback = defaultFallback;
	}

	PrayerChoice getHighPrayer()
	{
		return highPrayer;
	}

	PrayerChoice getDefaultFallback()
	{
		return defaultFallback;
	}
}
