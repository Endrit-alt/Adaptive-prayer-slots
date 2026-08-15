package com.adaptiveprayerslots;

public enum MeleeFallbackChain
{
	ULTIMATE_ONLY("Ultimate Strength"),
	CHIVALRY_THEN_ULTIMATE("Chivalry, then Ultimate Strength");

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
