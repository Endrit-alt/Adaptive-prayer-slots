package com.adaptiveprayerslots;

import java.util.function.Consumer;

// All methods are called on the client thread; the supplied executor must defer execution.
final class PrayerRedrawScheduler
{
	private final Consumer<Runnable> invokeLater;
	private final Runnable redraw;
	private final Runnable afterRedraw;
	private boolean pending;
	private long generation;

	PrayerRedrawScheduler(Consumer<Runnable> invokeLater, Runnable redraw, Runnable afterRedraw)
	{
		this.invokeLater = invokeLater;
		this.redraw = redraw;
		this.afterRedraw = afterRedraw;
	}

	boolean isPending()
	{
		return pending;
	}

	void request()
	{
		if (pending)
		{
			return;
		}
		pending = true;
		long requestedGeneration = ++generation;
		invokeLater.accept(() ->
		{
			if (generation != requestedGeneration)
			{
				return;
			}
			try
			{
				// Keep pending set while runScript fires nested event callbacks.
				redraw.run();
			}
			finally
			{
				if (generation == requestedGeneration)
				{
					pending = false;
				}
			}
			if (generation == requestedGeneration)
			{
				afterRedraw.run();
			}
		});
	}

	void cancel()
	{
		generation++;
		pending = false;
	}
}
