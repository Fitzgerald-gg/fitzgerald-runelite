/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This session's counter increments, held in memory.
 *
 * <p>Counts from zero at each account boundary; the on-disk journal ({@code LocalStore})
 * holds the lifetime record and folds these increments in on every refresh. The trackers
 * and {@link SkillDeriver} are the only writers of counts; on top of that the plugin
 * clears the whole store at an account boundary or a settings toggle. Nothing touches
 * RuneLite's config — a busy skilling tick costs no disk I/O.
 *
 * <p>Counters saturate at {@link Integer#MAX_VALUE}; they never wrap negative.
 *
 * <p>Hence the concurrent map and the detached copy out of {@link #snapshotAll()}.
 * Trackers write from the client thread, the journal's refresh reads from a scheduler
 * thread, and a clear arrives on either of those or on the EDT at shutdown.
 */
@Singleton
public class StatStore
{
	private final Map<String, Integer> totals = new ConcurrentHashMap<>();

	@Inject
	public StatStore()
	{
	}

	public void clear()
	{
		totals.clear();
	}

	public int getStat(String key)
	{
		return totals.getOrDefault(key, 0);
	}

	public void incrementStat(String key)
	{
		incrementStatBy(key, 1);
	}

	public void incrementStatBy(String key, int amount)
	{
		totals.merge(key, amount, StatStore::saturatingSum);
	}

	// overwrite, for the high-water-mark counters like highest hit
	public void setStat(String key, int value)
	{
		totals.put(key, value);
	}

	public Map<String, Integer> snapshotAll()
	{
		return new HashMap<>(totals);
	}

	private static int saturatingSum(int current, int addend)
	{
		long sum = (long) current + addend;
		if (sum > Integer.MAX_VALUE)
		{
			return Integer.MAX_VALUE;
		}
		if (sum < Integer.MIN_VALUE)
		{
			return Integer.MIN_VALUE;
		}
		return (int) sum;
	}
}
