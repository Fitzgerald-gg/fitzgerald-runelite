/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.List;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import org.junit.Before;
import org.junit.Test;

import static chronicle.counters.StatKeys.TOTAL_XP_GAINED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The session's xp split by skill, and the rate Home quotes beside each skill.
 *
 * <p>The split has to add up to the total it sits under, and the rate has to be
 * measured over the same window the xp was counted in. A rate divided by somebody
 * else's clock is wrong in a way that reads perfectly plausibly.
 */
public class ExperienceStatTrackerTest
{
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 3_600_000L;

	private StatStore store;
	private ExperienceStatTracker tracker;
	private long now;

	@Before
	public void setUp()
	{
		store = new StatStore();
		now = 1_700_000_000_000L;
		tracker = new ExperienceStatTracker(store, () -> now);
	}

	// StatChanged carries a career total, so a skill's first reading is only a baseline.
	private void xp(Skill skill, int careerTotal)
	{
		tracker.onStatChanged(new StatChanged(skill, careerTotal, 1, 1));
	}

	private void loginScreen()
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(GameState.LOGIN_SCREEN);
		tracker.onGameStateChanged(e);
	}

	private ExperienceStatTracker.SkillGain gain(Skill skill)
	{
		for (ExperienceStatTracker.SkillGain g : tracker.sessionGains())
		{
			if (g.skill == skill)
			{
				return g;
			}
		}
		return null;
	}

	// the split

	@Test
	public void eachSkillKeepsItsOwnShareOfTheSessionTotal()
	{
		xp(Skill.MINING, 100_000);
		xp(Skill.SMITHING, 50_000);
		xp(Skill.MINING, 110_000);
		xp(Skill.SMITHING, 54_000);
		xp(Skill.MINING, 115_000);

		assertEquals(15_000, gain(Skill.MINING).xp);
		assertEquals(4_000, gain(Skill.SMITHING).xp);
	}

	@Test
	public void theSplitAddsUpToTheTotalItSitsUnder()
	{
		// Home hangs these rows under the "Xp gained" total. If the two ever part
		// company the card contradicts itself.
		xp(Skill.ATTACK, 1_000);
		xp(Skill.HITPOINTS, 2_000);
		xp(Skill.ATTACK, 1_400);
		xp(Skill.HITPOINTS, 2_133);

		long summed = 0;
		for (ExperienceStatTracker.SkillGain g : tracker.sessionGains())
		{
			summed += g.xp;
		}
		assertEquals(store.getStat(TOTAL_XP_GAINED), summed);
		assertEquals(533, summed);
	}

	@Test
	public void aSkillsFirstReadingIsOnlyABaseline()
	{
		// Login sends the whole account's career totals. Counting those would report
		// a fresh session as tens of millions of xp.
		xp(Skill.WOODCUTTING, 13_034_431);

		assertTrue(tracker.sessionGains().isEmpty());
		assertEquals(0, store.getStat(TOTAL_XP_GAINED));
	}

	@Test
	public void aSkillThatDidNotMoveGetsNoRow()
	{
		xp(Skill.FISHING, 8_000);
		xp(Skill.COOKING, 9_000);
		xp(Skill.FARMING, 400);
		// Fishing gains; the other two are read again at the same total.
		xp(Skill.FISHING, 8_500);
		xp(Skill.COOKING, 9_000);
		xp(Skill.FARMING, 400);

		List<ExperienceStatTracker.SkillGain> gains = tracker.sessionGains();
		assertEquals(1, gains.size());
		assertEquals(Skill.FISHING, gains.get(0).skill);
		assertEquals(500, gains.get(0).xp);
	}

	@Test
	public void gainersRankBiggestFirst()
	{
		xp(Skill.AGILITY, 0);
		xp(Skill.THIEVING, 0);
		xp(Skill.SLAYER, 0);
		xp(Skill.AGILITY, 300);
		xp(Skill.THIEVING, 900);
		xp(Skill.SLAYER, 600);

		List<ExperienceStatTracker.SkillGain> gains = tracker.sessionGains();
		assertEquals(Skill.THIEVING, gains.get(0).skill);
		assertEquals(Skill.SLAYER, gains.get(1).skill);
		assertEquals(Skill.AGILITY, gains.get(2).skill);
	}

	// the rate

	@Test
	public void theRateIsMeasuredFromTheFirstXpNotFromTheLogin()
	{
		// Twenty minutes at the bank before the first swing. Charging those to the
		// rate would report two thirds of the xp per hour actually being made.
		xp(Skill.RUNECRAFT, 0);
		now += 20 * MINUTE;
		xp(Skill.RUNECRAFT, 10_000);
		now += HOUR;
		xp(Skill.RUNECRAFT, 40_000);

		assertEquals(40_000, gain(Skill.RUNECRAFT).perHour);
	}

	@Test
	public void eachSkillsRateIsItsOwnShareOverTheOneWindow()
	{
		xp(Skill.MINING, 0);
		xp(Skill.SMITHING, 0);
		xp(Skill.MINING, 30_000);
		xp(Skill.SMITHING, 10_000);
		now += 30 * MINUTE;

		assertEquals(60_000, gain(Skill.MINING).perHour);
		assertEquals(20_000, gain(Skill.SMITHING).perHour);
	}

	@Test
	public void noRateUntilTheWindowIsWorthDividingBy()
	{
		// Ten seconds in, the xp stands on its own and the rate is left off. A
		// negative perHour is the panel's signal to print the xp alone.
		xp(Skill.HERBLORE, 0);
		xp(Skill.HERBLORE, 5_000);
		now += 10_000L;

		assertEquals(5_000, gain(Skill.HERBLORE).xp);
		assertTrue(gain(Skill.HERBLORE).perHour < 0);
	}

	@Test
	public void theRateArrivesAsTheWindowReachesAMinute()
	{
		xp(Skill.HUNTER, 0);
		xp(Skill.HUNTER, 1_000);
		now += MINUTE - 1;
		assertTrue(gain(Skill.HUNTER).perHour < 0);

		now += 1;
		assertEquals(60_000, gain(Skill.HUNTER).perHour);
	}

	@Test
	public void aClockThatRunsBackwardsQuotesNoRate()
	{
		// An NTP correction can put the window's start ahead of now. Dividing by a
		// negative span would print a negative xp per hour.
		xp(Skill.CRAFTING, 0);
		xp(Skill.CRAFTING, 20_000);
		now -= 5 * MINUTE;

		assertEquals(20_000, gain(Skill.CRAFTING).xp);
		assertTrue(gain(Skill.CRAFTING).perHour < 0);
	}

	// the session boundary

	@Test
	public void theLoginScreenClearsTheSplitAndRestartsItsClock()
	{
		xp(Skill.MAGIC, 0);
		xp(Skill.MAGIC, 100_000);
		now += HOUR;

		loginScreen();
		assertTrue(tracker.sessionGains().isEmpty());

		// An hour idle at the login screen, then a new session. The old window must
		// not still be running: it would halve the new session's rate.
		now += HOUR;
		xp(Skill.MAGIC, 100_000);
		xp(Skill.MAGIC, 105_000);
		now += HOUR;

		assertEquals(5_000, gain(Skill.MAGIC).xp);
		assertEquals(5_000, gain(Skill.MAGIC).perHour);
	}

	@Test
	public void aWorldHopKeepsTheSessionRunning()
	{
		// HOPPING and LOADING keep the same character and the same career totals.
		// Clearing on those would restart the rate every hop.
		xp(Skill.FIREMAKING, 0);
		xp(Skill.FIREMAKING, 30_000);
		now += 30 * MINUTE;
		GameStateChanged hop = new GameStateChanged();
		hop.setGameState(GameState.HOPPING);
		tracker.onGameStateChanged(hop);

		assertEquals(30_000, gain(Skill.FIREMAKING).xp);
		assertEquals(60_000, gain(Skill.FIREMAKING).perHour);
	}
}
