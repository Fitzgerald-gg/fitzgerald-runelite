/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pace and horizon for one skill, computed from the journal's own calendar
 * spine and nothing else.
 *
 * <p>Pace is measured in ACTIVE DAYS — days that skill actually gained xp.
 * Dividing by elapsed days is the trap the whole class exists to avoid: three
 * hundred days of never touching Fletching followed by one day of it is not a
 * pace of a three-hundred-and-first of that day's xp, and training it again
 * tomorrow does not make it two three-hundred-and-seconds. An idle day is not
 * a slow day, so idle days never reach the divisor and the figure means what a
 * player means by it — what a day at this skill is worth.
 *
 * <p>The horizon is spent in DAYS OF PLAY, never a calendar date. "Lands on
 * the 22nd" quietly assumes the player trains every day between here and
 * there, which is the same lie wearing a date; "nine days of play" is a claim
 * the record can actually support.
 *
 * <p>A pace also has to admit how thin it is, so every result carries its own
 * basis — how many active days it stands on and the span they are scattered
 * across, for the panel to print. At most seven active days count, none older
 * than thirty days counts at all (a stale pace is not a pace), and under two
 * of them there is no horizon: a line through a single point is a guess with a
 * number on it. The panel says when the skill last moved instead.
 *
 * <p>Pure function of its arguments — the spine is handed in, so this computes
 * identically off the client thread, in a test, or over an imported past.
 */
class PaceBook
{
	/** The last level the curve holds; past it the only mark left is the 200m ceiling. */
	private static final int MAX_LEVEL = 99;
	private static final long MAX_XP = 200_000_000L;

	/** A pace stands on at most this many active days: an older week of a skill
	 *  says less about today than the last week of it did. */
	private static final int MAX_ACTIVE_DAYS = 7;

	/** …and none of them may be older than this. A month-old burst is history,
	 *  not a rate, and projecting from it dresses a memory up as a forecast. */
	private static final int RECENCY_DAYS = 30;

	/** Two days is the floor for a horizon — one day is an anecdote. */
	private static final int MIN_ACTIVE_DAYS = 2;

	private PaceBook()
	{
	}

	/**
	 * What a day at this skill is worth, and how many such days the next mark
	 * is away. Carries its own basis so the caller can say how thin it is.
	 */
	static final class Pace
	{
		/** Mean xp across the active days below — never divided by idle days. */
		final double xpPerActiveDay;

		/** How many active days that mean stands on. Under two of them there is
		 *  no horizon, but the figure is still true of the days it did see. */
		final int activeDays;

		/** Calendar days those active days are scattered across, inclusive — the
		 *  honest half of the sentence: four days of training across three weeks. */
		final long spanDays;

		/** The level being chased, or null when the mark ahead is the 200m
		 *  ceiling — or when the skill has arrived and nothing is ahead. The
		 *  mark stands whether or not the record can project a way to it. */
		final Integer targetLevel;

		/** Xp at that mark; 0 when there is nothing left ahead. */
		final long targetXp;

		/** Xp still owed at that mark. */
		final long xpRemaining;

		/** Active days of play away — NOT a date on a calendar. 0 = no horizon. */
		final long daysOfPlay;

		/** The last day this skill moved ANYWHERE in the spine, past the recency
		 *  window included; null if the record has never seen it move. */
		final LocalDate lastActive;

		private Pace(double xpPerActiveDay, int activeDays, long spanDays,
			Integer targetLevel, long targetXp, long xpRemaining, long daysOfPlay,
			LocalDate lastActive)
		{
			this.xpPerActiveDay = xpPerActiveDay;
			this.activeDays = activeDays;
			this.spanDays = spanDays;
			this.targetLevel = targetLevel;
			this.targetXp = targetXp;
			this.xpRemaining = xpRemaining;
			this.daysOfPlay = daysOfPlay;
			this.lastActive = lastActive;
		}

		/** True when the record supports a projection at all. */
		boolean hasHorizon()
		{
			return daysOfPlay > 0;
		}

		/** There is a mark ahead but too little recent movement to aim at it —
		 *  the case where the panel prints {@link #lastActive} instead. */
		boolean dormant()
		{
			return daysOfPlay <= 0 && targetXp > 0;
		}
	}

	/**
	 * Pace and horizon for {@code skill} as of today. {@code currentXp} is the
	 * live figure — the spine supplies the rate, the live xp supplies what is
	 * still owed, so a horizon shortens as the day is played.
	 */
	static Pace forSkill(TreeMap<LocalDate, HistoryLog.Baseline> spine, String skill,
		long currentXp)
	{
		return forSkill(spine, skill, currentXp, LocalDate.now());
	}

	/**
	 * As above, against a named day. The recency window has to be measured from
	 * some day, and taking it from the spine's own newest line would let a
	 * journal that has not run since spring call spring's pace current.
	 */
	static Pace forSkill(TreeMap<LocalDate, HistoryLog.Baseline> spine, String skill,
		long currentXp, LocalDate asOf)
	{
		long xp = Math.max(0, currentXp);
		long targetXp = nextMark(xp);
		Integer targetLevel = targetXp > 0 && targetXp <= xpForLevel(MAX_LEVEL)
			? levelAt(targetXp) : null;
		long remaining = targetXp > 0 ? targetXp - xp : 0;

		List<Long> gains = new ArrayList<>();
		LocalDate newestActive = null;
		LocalDate oldestActive = null;
		LocalDate lastActive = null;

		if (spine != null && skill != null && asOf != null)
		{
			LocalDate cutoff = asOf.minusDays(RECENCY_DAYS);
			// Newest first, one pair at a time: a day's gain is its baseline minus
			// the nearest EARLIER one, which is the same subtraction the History
			// tab does — so pace and period can never disagree about a day.
			Long later = null;
			LocalDate laterDate = null;
			for (Map.Entry<LocalDate, HistoryLog.Baseline> e : spine.descendingMap().entrySet())
			{
				Long value = e.getValue() != null ? e.getValue().skills.get(skill) : null;
				if (value == null)
				{
					// A missing key is NO DATA, not zero — imported baselines predate
					// newer skills, and reading absence as 0 would invent a lifetime's
					// xp as one day's gain. Break the pair rather than span the hole.
					later = null;
					laterDate = null;
					continue;
				}
				if (later != null)
				{
					long gain = later - value;
					if (gain > 0)
					{
						if (lastActive == null)
						{
							lastActive = laterDate;
						}
						if (gains.size() < MAX_ACTIVE_DAYS && !laterDate.isBefore(cutoff))
						{
							gains.add(gain);
							if (newestActive == null)
							{
								newestActive = laterDate;
							}
							oldestActive = laterDate;
						}
					}
				}
				later = value;
				laterDate = e.getKey();
				// Once the quota is full (or the walk has left the window) the only
				// thing still owed is the dormancy date, and that is already found.
				if (lastActive != null
					&& (gains.size() >= MAX_ACTIVE_DAYS || laterDate.isBefore(cutoff)))
				{
					break;
				}
			}
		}

		long total = 0;
		for (long g : gains)
		{
			total += g;
		}
		int activeDays = gains.size();
		double pace = activeDays > 0 ? (double) total / activeDays : 0.0;
		long span = activeDays > 0
			? ChronoUnit.DAYS.between(oldestActive, newestActive) + 1 : 0;

		if (activeDays < MIN_ACTIVE_DAYS || pace <= 0 || remaining <= 0)
		{
			// The pace still reports what the days it saw were worth, and the mark
			// ahead is still named; only the projection is withheld, which the
			// caller reads off daysOfPlay.
			return new Pace(pace, activeDays, span, targetLevel, targetXp,
				Math.max(0, remaining), 0, lastActive);
		}
		long daysOfPlay = (long) Math.ceil(remaining / pace);
		return new Pace(pace, activeDays, span, targetLevel, targetXp, remaining,
			daysOfPlay, lastActive);
	}

	/**
	 * The next mark ahead of {@code xp}: the next level while one remains, then
	 * the 200m ceiling, then nothing. 0 means the skill has arrived.
	 */
	private static long nextMark(long xp)
	{
		if (xp >= MAX_XP)
		{
			return 0;
		}
		if (xp >= xpForLevel(MAX_LEVEL))
		{
			return MAX_XP;
		}
		return xpForLevel(levelAt(xp) + 1);
	}

	// The xp curve, built once from the game's own formula rather than typed out
	// as ninety-nine rows: a table transcribed by hand is a table with a typo in
	// it, and every horizon here is a subtraction against one of these numbers.
	private static final long[] XP_FOR_LEVEL = curve();

	private static long[] curve()
	{
		long[] table = new long[MAX_LEVEL + 1];
		double points = 0;
		for (int level = 1; level < MAX_LEVEL; level++)
		{
			points += Math.floor(level + 300.0 * Math.pow(2.0, level / 7.0));
			table[level + 1] = (long) Math.floor(points / 4.0);
		}
		return table;   // table[1] stays 0: level 1 is where everyone starts
	}

	/** Xp at {@code level}, clamped to the 1–99 the curve covers. */
	static long xpForLevel(int level)
	{
		return XP_FOR_LEVEL[Math.max(1, Math.min(MAX_LEVEL, level))];
	}

	/** The level {@code xp} has reached, 1–99; anything past 99 is still 99. */
	static int levelAt(long xp)
	{
		for (int level = MAX_LEVEL; level > 1; level--)
		{
			if (xp >= XP_FOR_LEVEL[level])
			{
				return level;
			}
		}
		return 1;
	}
}
