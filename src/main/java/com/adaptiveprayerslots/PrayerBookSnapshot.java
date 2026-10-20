package com.adaptiveprayerslots;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

// Immutable metadata and availability for one resolved standard prayer book; no widgets.
final class PrayerBookSnapshot
{
	private final int targetCount;
	private final int visibleCount;
	private final int[] layout;
	private final Map<PrayerChoice, Entry> entries;
	private final Set<PrayerChoice> available;

	PrayerBookSnapshot(int targetCount, Entry[] visible, Set<PrayerChoice> available)
	{
		this.targetCount = targetCount;
		this.visibleCount = visible.length;
		this.entries = new EnumMap<>(PrayerChoice.class);
		this.available = EnumSet.noneOf(PrayerChoice.class);
		this.available.addAll(available);
		this.layout = new int[1 + visible.length * 4];
		layout[0] = targetCount;
		for (int i = 0; i < visible.length; i++)
		{
			Entry entry = visible[i];
			layout[1 + i * 4] = entry.targetIndex;
			layout[2 + i * 4] = entry.objectId;
			layout[3 + i * 4] = entry.componentId;
			layout[4 + i * 4] = entry.spriteIndex;
			if (entry.choice != null && entries.put(entry.choice, entry) != null)
			{
				throw new IllegalArgumentException("Duplicate resolved prayer: " + entry.choice);
			}
		}
	}

	Entry get(PrayerChoice choice)
	{
		return entries.get(choice);
	}

	boolean isAvailable(PrayerChoice choice)
	{
		return entries.containsKey(choice) && available.contains(choice);
	}

	int getChildCount()
	{
		return targetCount + 2 * visibleCount;
	}

	boolean hasSameLayout(PrayerBookSnapshot other)
	{
		return other != null && Arrays.equals(layout, other.layout);
	}

	PrayerChoice getHighPrayer(PrayerSwap swap)
	{
		return swap == PrayerSwap.MELEE && entries.containsKey(PrayerChoice.ZEAL)
			? PrayerChoice.ZEAL : swap.getHighPrayer();
	}

	PrayerChoice getFallback(PrayerSwap swap)
	{
		if (swap == PrayerSwap.RANGED && entries.containsKey(PrayerChoice.DEADEYE))
		{
			return PrayerChoice.DEADEYE;
		}
		if (swap == PrayerSwap.MAGIC && entries.containsKey(PrayerChoice.MYSTIC_VIGOUR))
		{
			return PrayerChoice.MYSTIC_VIGOUR;
		}
		return swap.getDefaultFallback();
	}

	static final class Entry
	{
		final PrayerChoice choice;
		final int objectId;
		final int componentId;
		final int targetIndex;
		final int spriteIndex;

		Entry(PrayerChoice choice, int objectId, int componentId, int targetIndex, int spriteIndex)
		{
			this.choice = choice;
			this.objectId = objectId;
			this.componentId = componentId;
			this.targetIndex = targetIndex;
			this.spriteIndex = spriteIndex;
		}
	}
}
