package com.adaptiveprayerslots;

public enum MeleeFallbackChain
{
	ULTIMATE_ONLY("Zeal/Piety → Ultimate Strength"),
	CHIVALRY_THEN_ULTIMATE("Zeal/Piety → Chivalry → Ultimate Strength");

	private final String displayName;

	MeleeFallbackChain(String displayName)
	{
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
