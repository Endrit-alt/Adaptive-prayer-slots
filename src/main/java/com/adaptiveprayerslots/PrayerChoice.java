package com.adaptiveprayerslots;

import net.runelite.api.Prayer;

enum PrayerChoice
{
	ULTIMATE_STRENGTH("Ultimate Strength", Prayer.ULTIMATE_STRENGTH.getVarbit()),
	CHIVALRY("Chivalry", Prayer.CHIVALRY.getVarbit()),
	PIETY("Piety", Prayer.PIETY.getVarbit()),
	// VarbitID.PRAYER_ZEAL; numeric alias also builds with RuneLite 1.13.1.
	ZEAL("Zeal", 16577),
	EAGLE_EYE("Eagle Eye", Prayer.EAGLE_EYE.getVarbit()),
	DEADEYE("Deadeye", Prayer.DEADEYE.getVarbit()),
	RIGOUR("Rigour", Prayer.RIGOUR.getVarbit()),
	MYSTIC_MIGHT("Mystic Might", Prayer.MYSTIC_MIGHT.getVarbit()),
	MYSTIC_VIGOUR("Mystic Vigour", Prayer.MYSTIC_VIGOUR.getVarbit()),
	AUGURY("Augury", Prayer.AUGURY.getVarbit());

	private final String displayName;
	private final int activeVarbit;

	PrayerChoice(String displayName, int activeVarbit)
	{
		this.displayName = displayName;
		this.activeVarbit = activeVarbit;
	}

	int getActiveVarbit()
	{
		return activeVarbit;
	}

	static PrayerChoice fromDisplayName(String name)
	{
		for (PrayerChoice choice : values())
		{
			if (choice.displayName.equalsIgnoreCase(name))
			{
				return choice;
			}
		}
		return null;
	}
}
