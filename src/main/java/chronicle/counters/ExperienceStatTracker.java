/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;

import static chronicle.counters.StatKeys.TOTAL_XP_GAINED;

/**
 * One running total of XP gained, across every skill including combat. The journal holds
 * the lifetime figure between sessions.
 *
 * <p>The same gains are also kept split by skill, in memory only, for the Home card's
 * per-skill breakdown. That tally never reaches {@link StatStore}: it would then be
 * folded into the journal, pushed upward and given rows in the Stats tab, which is a
 * great deal of record for one panel affordance.
 */
public class ExperienceStatTracker implements StatTracker
{
	/**
	 * How long the tally must have been running before its rate is worth quoting. A
	 * handful of seconds extrapolates one lucky drop into an hour nobody played.
	 */
	static final long RATE_FLOOR_MS = 60_000L;

	/** One skill's gain this session, and what that comes to per hour. */
	public static final class SkillGain
	{
		public final Skill skill;
		public final long xp;
		/** XP per hour, or negative while the window is too short to divide by. */
		public final long perHour;

		SkillGain(Skill skill, long xp, long perHour)
		{
			this.skill = skill;
			this.xp = xp;
			this.perHour = perHour;
		}
	}

	private final StatStore store;
	private final LongSupplier clock;

	private final Map<Skill, Integer> xpSeen = new EnumMap<>(Skill.class);

	// This session's gain per skill, and the wall clock the tally started at. Guarded
	// by this monitor: the client thread counts into it, the panel reads it on the EDT.
	private final Map<Skill, Long> sessionXp = new EnumMap<>(Skill.class);
	private long windowStartMs;

	public ExperienceStatTracker(StatStore store)
	{
		this(store, System::currentTimeMillis);
	}

	// Visible for testing: a rate is only as testable as its clock.
	ExperienceStatTracker(StatStore store, LongSupplier clock)
	{
		this.store = store;
		this.clock = clock;
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
			// StatChanged carries a career total, not a gain. the first reading of a skill
			// this session is the baseline; count it and login banks the whole account.
			return;
		}
		int gained = xp - prev;
		if (gained > 0)
		{
			store.incrementStatBy(TOTAL_XP_GAINED, gained);
			count(skill, gained);
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		// LOGIN_SCREEN only. LOADING and HOPPING keep the same character and the same
		// career totals; clearing the baseline on those eats the next XP drop.
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			xpSeen.clear();
			clearSession();
		}
	}

	/**
	 * This session's gainers, biggest first, each with its rate over the window the
	 * tally has been running. Skills that gained nothing this session are absent.
	 */
	public synchronized List<SkillGain> sessionGains()
	{
		long elapsed = windowStartMs > 0 ? clock.getAsLong() - windowStartMs : 0;
		// Wall clock: an NTP correction or a resumed VM can put the start ahead of now.
		// A short or negative window quotes no rate rather than a made-up one.
		boolean rateable = elapsed >= RATE_FLOOR_MS;
		List<SkillGain> out = new ArrayList<>(sessionXp.size());
		for (Map.Entry<Skill, Long> e : sessionXp.entrySet())
		{
			long xp = e.getValue();
			out.add(new SkillGain(e.getKey(), xp,
				rateable ? xp * 3_600_000L / elapsed : -1L));
		}
		// Biggest first; skill order breaks a tie so the rows do not shuffle each tick.
		out.sort(Comparator.comparingLong((SkillGain g) -> g.xp).reversed()
			.thenComparingInt(g -> g.skill.ordinal()));
		return out;
	}

	// The window opens with the first XP counted, not with the login: the minutes
	// spent at the bank before starting are not minutes of training. Tally and clock
	// start together and are cleared together, so the two can never measure different
	// spans — which is the whole of what makes the quoted rate true.
	private synchronized void count(Skill skill, int gained)
	{
		if (sessionXp.isEmpty())
		{
			windowStartMs = clock.getAsLong();
		}
		sessionXp.merge(skill, (long) gained, Long::sum);
	}

	// Cleared on the same event that clears TOTAL_XP_GAINED (the plugin's own logout
	// handler runs off this same arrival at the login screen), so the per-skill rows
	// always add up to the total they sit under.
	private synchronized void clearSession()
	{
		sessionXp.clear();
		windowStartMs = 0;
	}
}
