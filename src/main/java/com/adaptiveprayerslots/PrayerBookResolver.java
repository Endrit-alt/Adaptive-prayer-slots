package com.adaptiveprayerslots;

import java.util.EnumSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ItemComposition;
import net.runelite.api.ParamID;

// Must only run from a deferred ClientThread callback, never inside a script event.
final class PrayerBookResolver
{
	// Current game scripts: alternate-prayer resolution and prayer availability.
	private static final int RESOLVE_ALTERNATE = 10190;
	private static final int IS_AVAILABLE = 10154;
	private static final int PRAYER_NAME_PARAM = 1752;

	PrayerBookSnapshot resolve(Client client)
	{
		EnumComposition book = client.getEnum(EnumID.PRAYERS_NORMAL);
		if (book == null)
		{
			return null;
		}
		int[] keys = book.getKeys();
		int[] values = book.getIntVals();
		int count = book.size();
		if (count == 0 || keys.length != count || values.length != count)
		{
			return null;
		}
		int[] levels = new int[count];
		int[] objects = new int[count];
		int[] components = new int[count];
		PrayerChoice[] choices = new PrayerChoice[count];
		boolean[] seen = new boolean[count];
		int[] order = new int[count];
		int visible = 0;
		Set<PrayerChoice> available = EnumSet.noneOf(PrayerChoice.class);
		Set<PrayerChoice> resolvedChoices = EnumSet.noneOf(PrayerChoice.class);
		for (int i = 0; i < count; i++)
		{
			int key = keys[i];
			if (key < 0 || key >= count || seen[key])
			{
				return null;
			}
			seen[key] = true;
			if (values[i] <= 0)
			{
				// The game retains a target index for entries without a prayer component.
				continue;
			}
			ItemComposition base = client.getItemDefinition(values[i]);
			// Quick Prayer sorting deliberately uses base levels, even for upgrades.
			levels[key] = base.getIntValue(ParamID.OC_PRAYER_LEVEL);
			int object = runIntScript(client, RESOLVE_ALTERNATE, values[i]);
			if (object <= 0)
			{
				return null;
			}
			ItemComposition prayer = client.getItemDefinition(object);
			int component = prayer.getIntValue(ParamID.OC_PRAYER_COMPONENT);
			if (component <= 0)
			{
				continue;
			}
			objects[key] = object;
			components[key] = component;
			PrayerChoice choice = PrayerChoice.fromDisplayName(prayer.getStringValue(PRAYER_NAME_PARAM));
			choices[key] = choice;
			visible++;
			if (choice != null)
			{
				if (!resolvedChoices.add(choice))
				{
					return null;
				}
				if (runIntScript(client, IS_AVAILABLE, object) == 1)
				{
					available.add(choice);
				}
			}
		}
		if (visible == 0)
		{
			return null;
		}
		int orderIndex = 0;
		for (int key = 0; key < count; key++)
		{
			if (components[key] > 0)
			{
				order[orderIndex++] = key;
			}
		}
		// Match CS2 prayer_quicksort, including its ordering for equal base levels.
		sort(order, levels, 0, visible - 1);
		PrayerBookSnapshot.Entry[] entries = new PrayerBookSnapshot.Entry[visible];
		for (int i = 0; i < visible; i++)
		{
			int key = order[i];
			entries[i] = new PrayerBookSnapshot.Entry(choices[key], objects[key], components[key], key, count + 2 * i);
		}
		return new PrayerBookSnapshot(count, entries, available);
	}

	private static int runIntScript(Client client, int script, int object)
	{
		client.runScript(script, object);
		return client.getIntStack()[0];
	}

	static void sort(int[] order, int[] levels, int low, int high)
	{
		int middle = (low + high) / 2;
		int pivot = order[middle];
		order[middle] = order[high];
		order[high] = pivot;
		int target = low;
		for (int i = low; i < high; i++)
		{
			if (levels[order[i]] <= levels[pivot])
			{
				int temp = order[i];
				order[i] = order[target];
				order[target++] = temp;
			}
		}
		order[high] = order[target];
		order[target] = pivot;
		if (low < target - 1)
		{
			sort(order, levels, low, target - 1);
		}
		if (target + 1 < high)
		{
			sort(order, levels, target + 1, high);
		}
	}
}
