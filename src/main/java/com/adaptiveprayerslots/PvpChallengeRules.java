package com.adaptiveprayerslots;

import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.dbtable.DBRowConfig;

// Mirrors prayer_overrides and script9971, without running scripts from an event callback.
final class PvpChallengeRules
{
	// Current gameval names: VarPlayerID.PVPTUT_ACTIVE_TUTORIAL,
	// DBTableID.PvpTutorial.ID / COL_MODIFIED_STATS. Not yet in RuneLite 1.13.1.
	static final int ACTIVE_TUTORIAL_VARP = 5850;
	private static final int TUTORIAL_TABLE = 133;
	private static final int MODIFIED_STATS_COLUMN = 3;
	private int cachedTutorial = -1;
	private int cachedPrayerLevel = -1;

	static boolean isActive(int tutorial, int worldX)
	{
		// CS2's instance_check tests raw coordinates, not template-mapped coordinates.
		return tutorial > 0 && worldX >= 6400;
	}

	int getPrayerLevel(Client client, int tutorial)
	{
		if (tutorial == cachedTutorial)
		{
			return cachedPrayerLevel;
		}
		DBRowConfig row = client.getDBRowConfig(tutorial);
		if (row == null || row.getTableID() != TUTORIAL_TABLE)
		{
			return -1;
		}

		int level = resolvePrayerLevel(
			client.getDBTableField(tutorial, MODIFIED_STATS_COLUMN, 0),
			client.getDBTableField(tutorial, MODIFIED_STATS_COLUMN, 1),
			client.getDBTableField(tutorial, MODIFIED_STATS_COLUMN, 2));
		if (level >= 0)
		{
			cachedTutorial = tutorial;
			cachedPrayerLevel = level;
		}
		return level;
	}

	static int resolvePrayerLevel(Object[] stats, Object[] levels, Object[] operations)
	{
		if (stats == null || levels == null || operations == null
			|| stats.length != levels.length || stats.length != operations.length)
		{
			return -1;
		}
		for (int i = 0; i < stats.length; i++)
		{
			if (!(stats[i] instanceof Integer))
			{
				return -1;
			}
			if ((Integer) stats[i] != Skill.PRAYER.ordinal())
			{
				continue;
			}
			if (!(levels[i] instanceof Integer) || !(operations[i] instanceof Integer)
				|| (Integer) levels[i] < 0)
			{
				return -1;
			}
			int level = (Integer) levels[i];
			switch ((Integer) operations[i])
			{
				case 0:
					return Math.max(99, level);
				case 1:
					return Math.min(99, level);
				case 2:
					return level;
				default:
					return -1;
			}
		}
		return 99;
	}

	static int resolveDefenceLevel(int activeBuild)
	{
		// prayer_overrides uses BR_ACTIVE_BUILD_PLAYER even inside Pete's challenges.
		switch (activeBuild)
		{
			case 1:
				return 50;
			case 2:
				return 1;
			default:
				return 85;
		}
	}

	void clear()
	{
		cachedTutorial = -1;
		cachedPrayerLevel = -1;
	}
}
