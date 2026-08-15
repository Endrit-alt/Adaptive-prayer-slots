package com.adaptiveprayerslots;

import net.runelite.api.Prayer;

enum PrayerChoice
{
	ULTIMATE_STRENGTH("Ultimate Strength", Prayer.ULTIMATE_STRENGTH, 31, 1, PrayerUnlock.NONE),
	CHIVALRY("Chivalry", Prayer.CHIVALRY, 60, 65, PrayerUnlock.PIETY),
	PIETY("Piety", Prayer.PIETY, 70, 70, PrayerUnlock.PIETY),
	EAGLE_EYE("Eagle Eye", Prayer.EAGLE_EYE, 44, 1, PrayerUnlock.NONE),
	RIGOUR("Rigour", Prayer.RIGOUR, 74, 70, PrayerUnlock.RIGOUR),
	MYSTIC_MIGHT("Mystic Might", Prayer.MYSTIC_MIGHT, 45, 1, PrayerUnlock.NONE),
	AUGURY("Augury", Prayer.AUGURY, 77, 70, PrayerUnlock.AUGURY);

	private final String displayName;
	private final Prayer prayer;
	private final int prayerLevel;
	private final int defenceLevel;
	private final PrayerUnlock unlock;

	PrayerChoice(String displayName, Prayer prayer, int prayerLevel, int defenceLevel, PrayerUnlock unlock)
	{
		this.displayName = displayName;
		this.prayer = prayer;
		this.prayerLevel = prayerLevel;
		this.defenceLevel = defenceLevel;
		this.unlock = unlock;
	}

	String getDisplayName()
	{
		return displayName;
	}

	Prayer getPrayer()
	{
		return prayer;
	}

	int getPrayerLevel()
	{
		return prayerLevel;
	}

	int getDefenceLevel()
	{
		return defenceLevel;
	}

	PrayerUnlock getUnlock()
	{
		return unlock;
	}

	boolean meetsLevels(int prayer, int defence)
	{
		return prayer >= prayerLevel && defence >= defenceLevel;
	}
}
