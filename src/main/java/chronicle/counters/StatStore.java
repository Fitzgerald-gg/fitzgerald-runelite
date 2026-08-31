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
 * <p>The store counts from ZERO at each account boundary; the on-disk journal
 * ({@code LocalStore}) owns the lifetime record and folds these increments in on
 * every refresh. Nothing seeds this store from anywhere — the trackers (and the
 * local {@link SkillDeriver}) are its only writers, so its contents are exactly
 * what this client witnessed this session. Nothing is written to RuneLite's
 * config, so a busy skilling tick costs no disk I/O.
 *
 * <p>Counters saturate at {@link Integer#MAX_VALUE} instead of wrapping. A counter
 * that silently went negative would read as a regression everywhere downstream,
 * which is a far worse outcome than one stuck total.
 *
 * <p>Trackers call in from the client thread while the journal's refresh reads from
 * a scheduler thread, so the map is concurrent and {@link #snapshotAll()} hands
 * back a detached copy.
 */
@Singleton
public class StatStore
{
	private final Map<String, Integer> totals = new ConcurrentHashMap<>();

	@Inject
	public StatStore()
	{
	}

	/** Forget everything. Used at an account boundary, where the totals stop applying. */
	public void clear()
	{
		totals.clear();
	}

	/** Current value, or zero for a counter nothing has touched yet. */
	public int getStat(String key)
	{
		return totals.getOrDefault(key, 0);
	}

	/** Add one. */
	public void incrementStat(String key)
	{
		incrementStatBy(key, 1);
	}

	/** Add {@code amount}, saturating rather than overflowing. */
	public void incrementStatBy(String key, int amount)
	{
		totals.merge(key, amount, StatStore::saturatingSum);
	}

	/** Overwrite outright. For counters that are a high-water mark, not a tally. */
	public void setStat(String key, int value)
	{
		totals.put(key, value);
	}

	/** Every total, detached — the journal and session views read this. */
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
