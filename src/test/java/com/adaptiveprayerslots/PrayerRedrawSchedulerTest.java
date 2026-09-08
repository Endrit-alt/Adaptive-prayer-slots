package com.adaptiveprayerslots;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PrayerRedrawSchedulerTest
{
	private final Queue<Runnable> tasks = new ArrayDeque<>();
	private final List<String> events = new ArrayList<>();
	private PrayerRedrawScheduler scheduler;

	@Test
	public void requestDefersRedrawAndCoalescesRepeatedRequests()
	{
		scheduler = new PrayerRedrawScheduler(tasks::add, () -> events.add("redraw"), () ->
		{
			assertFalse(scheduler.isPending());
			events.add("apply");
		});
		scheduler.request();
		scheduler.request();
		assertTrue(scheduler.isPending());
		assertTrue(events.isEmpty());
		assertEquals(1, tasks.size());
		tasks.remove().run();
		assertEquals(Arrays.asList("redraw", "apply"), events);
		assertFalse(scheduler.isPending());
	}

	@Test
	public void callbacksInsideRedrawCannotReenterOrQueueAnotherRedraw()
	{
		scheduler = new PrayerRedrawScheduler(tasks::add, () ->
		{
			assertTrue(scheduler.isPending());
			events.add("redraw");
			scheduler.request();
		}, () -> events.add("apply"));
		scheduler.request();
		tasks.remove().run();
		assertTrue(tasks.isEmpty());
		assertEquals(Arrays.asList("redraw", "apply"), events);
	}

	@Test
	public void cancellationDiscardsQueuedWorkAfterGameStateChange()
	{
		scheduler = new PrayerRedrawScheduler(tasks::add, () -> events.add("redraw"), () -> events.add("apply"));
		scheduler.request();
		scheduler.cancel();
		tasks.remove().run();
		assertTrue(events.isEmpty());
		assertFalse(scheduler.isPending());
	}

	@Test
	public void obsoleteRequestCannotClearNewPendingRequest()
	{
		scheduler = new PrayerRedrawScheduler(tasks::add, () -> events.add("redraw"), () -> events.add("apply"));
		scheduler.request();
		scheduler.cancel();
		scheduler.request();
		tasks.remove().run();
		assertTrue(scheduler.isPending());
		assertTrue(events.isEmpty());
		tasks.remove().run();
		assertFalse(scheduler.isPending());
		assertEquals(Arrays.asList("redraw", "apply"), events);
	}

	@Test
	public void cancellationDuringRedrawSkipsReapplication()
	{
		scheduler = new PrayerRedrawScheduler(tasks::add, () -> scheduler.cancel(), () -> events.add("apply"));
		scheduler.request();
		tasks.remove().run();
		assertTrue(events.isEmpty());
		assertFalse(scheduler.isPending());
	}

	@Test
	public void failedRedrawReleasesPendingFlagAndCanBeRetried()
	{
		scheduler = new PrayerRedrawScheduler(tasks::add, () ->
		{
			if (events.isEmpty())
			{
				events.add("failure");
				throw new IllegalStateException("script failed");
			}
			events.add("redraw");
		}, () -> events.add("apply"));
		scheduler.request();
		try
		{
			tasks.remove().run();
			fail("Expected redraw failure");
		}
		catch (IllegalStateException expected)
		{
			assertEquals("script failed", expected.getMessage());
		}
		assertFalse(scheduler.isPending());
		assertEquals(Arrays.asList("failure"), events);
		scheduler.request();
		tasks.remove().run();
		assertEquals(Arrays.asList("failure", "redraw", "apply"), events);
	}
}
