/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin.counters;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The session's counter totals, held in memory.
 *
 * <p>fitzgerald.gg is the system of record; this is a working copy. On login the
 * server's totals are loaded as a baseline, the trackers add to them as you play,
 * and the push loop sends the whole set back. Values travel as absolutes rather
 * than deltas, which makes a retried or duplicated push harmless — the server
 * simply overwrites with the same number. Nothing is written to RuneLite's config,
 * so a busy skilling tick costs no disk I/O.
 *
 * <p>Counters saturate at {@link Integer#MAX_VALUE} instead of wrapping. A counter
 * that silently went negative would look like a regression to the server's guard
 * and freeze the whole stream for review, which is a far worse outcome than one
 * stuck total.
 *
 * <p>Trackers call in from the client thread while the push loop reads from a
 * scheduler thread, so the map is concurrent and {@link #pushable()} hands back a
 * detached copy.
 */
@Singleton
public class StatStore
{
	private final Map<String, Integer> totals = new ConcurrentHashMap<>();

	/**
	 * Counters the SERVER derives and owns, which this client must never send back.
	 *
	 * <p>Per-resource skilling totals are worked out server-side from the tuples the
	 * skilling tracker forwards, because only the server knows that "oak logs" rolls
	 * up into oakLogsChopped. If we also pushed our own value for one of those keys
	 * it would land on top of the server's and undo the derivation. Two shapes are
	 * excluded: an exact name, or anything ending in a family suffix — the suffix
	 * form is what lets a resource that did not exist when this shipped
	 * (rosewoodLogsChopped, say) be excluded without a plugin update.
	 *
	 * <p>This list mirrors {@code _is_skill_derived_key} in server.py. The server's
	 * copy is authoritative and drops these regardless; this one saves the bytes and
	 * keeps the two ends honest.
	 */
	private static final Set<String> SERVER_OWNED_NAMES = new HashSet<>(Arrays.asList(
		// gathering + cooking floors
		"logsChopped", "fishCaught", "foodCooked", "rocksMined", "foodBurned",
		// thieving
		"pickPockets", "failedPickPockets", "stallsThieved", "chestsLooted", "safesCracked",
		// firemaking + runecraft
		"logsBurned", "runesCrafted",
		// smithing, fletching, crafting
		"barsSmelted", "itemsSmithed", "bowsFletched", "bowsStrung", "dartsFletched",
		"arrowsFletched", "boltsFletched", "javelinsFletched", "boltsUnfinished",
		"boltTips", "crossbowsUnstrung", "crossbowsStrung",
		"gemsCut", "glassBlown", "leatherCrafted", "dhideCrafted", "jewelleryCrafted",
		"potteryMade", "battlestavesCrafted", "itemsSpun",
		// herblore
		"herbsCleaned", "unfinishedPotionsMade", "potionsMade",
		// hunter
		"creaturesTrapped", "implingsCaught",
		// prayer, agility, farming, construction action floors
		"bonesBuried", "ashesScattered", "agilityObstacles", "farmingActions",
		"constructionBuilds", "seedsPlanted", "rooftopAgilityLaps", "normalAgilityLaps",
		// derived server-side (untaken loot from forwarded events; resourcesGatheredValue
		// priced from the gathering counters at read time — never a client counter):
		"untakenLootValue", "untakenLootCount", "resourcesGatheredValue"));

	/**
	 * Suffixes marking a per-resource counter minted by the server.
	 *
	 * <p>Note that raking is deliberately absent: it awards no experience, so no
	 * tuple ever reaches the server for it and the client stays its only source.
	 */
	private static final String[] SERVER_OWNED_FAMILIES = {
		"LogsChopped", "Caught", "Cooked", "Mined", "Pickpockets", "FailedPickpockets",
		"LogsBurned", "Runecrafted", "BonesBuried", "AshesScattered", "StallsThieved",
		"ChestsLooted", "Harvested"};

	static boolean isServerOwned(String key)
	{
		if (SERVER_OWNED_NAMES.contains(key))
		{
			return true;
		}
		for (String family : SERVER_OWNED_FAMILIES)
		{
			if (key.endsWith(family))
			{
				return true;
			}
		}
		return false;
	}

	@Inject
	public StatStore()
	{
	}

	/**
	 * Adopt the server's totals as the starting point for this session.
	 *
	 * <p>Replaces rather than merges: whatever is here belongs to a previous login,
	 * and continuing to count on top of it would file one account's totals under
	 * another's name.
	 */
	/**
	 * Adopt the server baseline ON TOP of whatever has accumulated locally. Used
	 * for the once-per-login seed: the store starts from zero at each account
	 * boundary, so anything in it now is this session's increments — adding the
	 * baseline underneath preserves them instead of discarding kills/doses that
	 * happened while the seed fetch was in flight (or retrying).
	 */
	public void seedAdditive(Map<String, Integer> baseline)
	{
		if (baseline != null)
		{
			baseline.forEach((k, v) -> totals.merge(k, v, StatStore::saturatingSum));
		}
	}

	/**
	 * Raise each counter to AT LEAST the server baseline, never lowering one we
	 * hold higher. Used to recover from a regression 409: the store already holds
	 * absolutes, so adding would double-count — the safe reconciliation is the
	 * per-key max of what we witnessed and what the server has committed.
	 */
	public void seedFloor(Map<String, Integer> baseline)
	{
		if (baseline != null)
		{
			baseline.forEach((k, v) -> totals.merge(k, v, Math::max));
		}
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

	/**
	 * A detached copy of everything this client owns, ready to send.
	 *
	 * <p>Server-derived counters are filtered out here rather than at the call site,
	 * so there is exactly one place that decides what leaves the client.
	 */
	public Map<String, Integer> pushable()
	{
		Map<String, Integer> out = new HashMap<>();
		totals.forEach((key, value) ->
		{
			if (!isServerOwned(key))
			{
				out.put(key, value);
			}
		});
		return out;
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
