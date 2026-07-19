/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin.counters;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;

import static gg.fitzgerald.plugin.counters.StatKeys.TOTAL_XP_GAINED;

/**
 * Sums experience gained across every skill while the plugin is tracking.
 *
 * <p>{@link StatChanged} reports a skill's career total, so a gain is the rise
 * from the previous reading. Logging in replays every skill's total against an
 * empty baseline, which would count a whole account's XP as "gained" on the spot;
 * so a skill's first reading only seeds the baseline and is never counted, and the
 * baseline is dropped on logout to reseed cleanly on the next login (the server
 * holds the running total between sessions, as with every other counter).
 *
 * <p>Unlike the skilling tracker, this watches ALL skills, combat included — it is
 * a single lifetime total, not a per-skill breakdown, which the hiscores already
 * give.
 */
public class ExperienceStatTracker implements StatTracker
{
	private final StatStore store;

	/** Last career total seen per skill; absent until the first reading seeds it. */
	private final Map<Skill, Integer> xpSeen = new EnumMap<>(Skill.class);

	public ExperienceStatTracker(StatStore store)
	{
		this.store = store;
	}

	@Override
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null)
		{
			return;
		}
		int xp = event.getXp();
		Integer prev = xpSeen.put(skill, xp);
		if (prev == null)
		{
			return;   // first reading this session: seed only, never counted
		}
		int gained = xp - prev;
		if (gained > 0)
		{
			store.incrementStatBy(TOTAL_XP_GAINED, gained);
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		// The baseline only means anything within one login. Drop it when leaving
		// so the next login's first readings reseed rather than counting a jump.
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			xpSeen.clear();
		}
	}
}
