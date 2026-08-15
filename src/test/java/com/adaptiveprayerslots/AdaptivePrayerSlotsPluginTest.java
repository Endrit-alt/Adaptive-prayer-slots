package com.adaptiveprayerslots;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AdaptivePrayerSlotsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AdaptivePrayerSlotsPlugin.class);
		RuneLite.main(args);
	}
}
