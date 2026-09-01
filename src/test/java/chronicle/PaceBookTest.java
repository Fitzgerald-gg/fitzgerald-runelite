/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.time.LocalDate;
import java.util.TreeMap;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the pace divisor: days the skill gained xp. Idle days in between stay out
 * of it. Also covers the horizon rules and the xp curve.
 */
public class PaceBookTest
{
	private static final String SKILL = "fletching";
	private static final LocalDate TODAY = LocalDate.of(2026, 8, 31);

	private static TreeMap<LocalDate, HistoryLog.Baseline> spine()
	{
		return new TreeMap<>();
	}

	private static void put(TreeMap<LocalDate, HistoryLog.Baseline> spine, LocalDate date, long xp)
	{
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		b.skills.put(SKILL, xp);
		spine.put(date, b);
	}

	// a baseline holding other skills but no key for SKILL, like an imported one
	private static void putBlind(TreeMap<LocalDate, HistoryLog.Baseline> spine, LocalDate date)
	{
		HistoryLog.Baseline b = new HistoryLog.Baseline();
		b.skills.put("attack", 4_000_000L);
		spine.put(date, b);
	}

	// flat baselines for the `days` days before `until`
	private static void idle(TreeMap<LocalDate, HistoryLog.Baseline> spine, LocalDate until,
		int days, long xp)
	{
		for (int i = days; i >= 1; i--)
		{
			put(spine, until.minusDays(i), xp);
		}
	}

	@Test
	public void oneTrainingDayAfterThreeHundredIdleOnesIsNotSpreadAcrossThem()
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = spine();
		idle(spine, TODAY, 300, 500_000L);
		put(spine, TODAY, 600_000L);

		PaceBook.Pace p = PaceBook.forSkill(spine, SKILL, 600_000L, TODAY);

		// dividing by the 301 calendar days would say 332 xp a day
		assertEquals(1, p.activeDays);
		assertEquals(100_000.0, p.xpPerActiveDay, 0.001);
		assertEquals(1, p.spanDays);
		assertEquals(TODAY, p.lastActive);
		// one active day gives a pace but no horizon
		assertFalse(p.hasHorizon());
		assertEquals(0, p.daysOfPlay);
		assertTrue(p.dormant());
	}

	@Test
	public void aSecondTrainingDayAveragesTwoDaysNotThreeHundredAndTwo()
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = spine();
		idle(spine, TODAY.minusDays(1), 301, 500_000L);
		put(spine, TODAY.minusDays(1), 600_000L);   // +100,000
		put(spine, TODAY, 650_000L);                // +50,000

		PaceBook.Pace p = PaceBook.forSkill(spine, SKILL, 650_000L, TODAY);

		// by calendar days it would be 150,000 over 302, or 497 a day
		assertEquals(2, p.activeDays);
		assertEquals(75_000.0, p.xpPerActiveDay, 0.001);
		assertEquals(2, p.spanDays);
		assertEquals(TODAY, p.lastActive);
		assertTrue(p.hasHorizon());
	}

	@Test
	public void daysOfPlayIsWhatIsOwedOverThePaceRoundedUp()
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = spine();
		put(spine, TODAY.minusDays(3), 99_000L);
		put(spine, TODAY.minusDays(1), 99_500L);    // +500
		put(spine, TODAY, 100_000L);                // +500

		PaceBook.Pace p = PaceBook.forSkill(spine, SKILL, 100_000L, TODAY);

		assertEquals(2, p.activeDays);
		assertEquals(500.0, p.xpPerActiveDay, 0.001);
		assertEquals(Integer.valueOf(50), p.targetLevel);
		assertEquals(101_333L, p.targetXp);
		assertEquals(1_333L, p.xpRemaining);
		// 1,333 at 500 a day rounds up
		assertEquals(3, p.daysOfPlay);
		assertTrue(p.hasHorizon());
		assertFalse(p.dormant());
	}

	@Test
	public void aHorizonSaysHowScatteredItsDaysWere()
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = spine();
		put(spine, TODAY.minusDays(21), 99_000L);
		put(spine, TODAY.minusDays(20), 99_500L);   // +500, three weeks back
		put(spine, TODAY, 100_000L);                // +500, today

		PaceBook.Pace p = PaceBook.forSkill(spine, SKILL, 100_000L, TODAY);

		assertEquals(2, p.activeDays);
		// two active days spread over three weeks, and spanDays says so
		assertEquals(21, p.spanDays);
		assertTrue(p.hasHorizon());
	}

	@Test
	public void dormantBeyondTheRecencyWindowHasNoHorizonButNamesTheDay()
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = spine();
		LocalDate moved = TODAY.minusDays(60);
		put(spine, moved.minusDays(1), 500_000L);
		put(spine, moved, 900_000L);                // a big day, two months ago
		idle(spine, TODAY, 59, 900_000L);
		put(spine, TODAY, 900_000L);

		PaceBook.Pace p = PaceBook.forSkill(spine, SKILL, 900_000L, TODAY);

		assertEquals(0, p.activeDays);
		assertEquals(0.0, p.xpPerActiveDay, 0.001);
		assertEquals(0, p.spanDays);
		assertFalse(p.hasHorizon());
		assertTrue(p.dormant());
		// lastActive looks past the 30-day window
		assertEquals(moved, p.lastActive);
	}

	@Test
	public void aSkillTheRecordHasNeverSeenMoveHasNoLastActive()
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = spine();
		idle(spine, TODAY, 40, 500_000L);
		put(spine, TODAY, 500_000L);

		PaceBook.Pace p = PaceBook.forSkill(spine, SKILL, 500_000L, TODAY);

		assertEquals(0, p.activeDays);
		assertNull(p.lastActive);
		assertFalse(p.hasHorizon());
		assertTrue(p.dormant());
	}

	@Test
	public void anEmptySpineIsDormantNotAnError()
	{
		PaceBook.Pace p = PaceBook.forSkill(spine(), SKILL, 500_000L, TODAY);

		assertEquals(0, p.activeDays);
		assertNull(p.lastActive);
		assertFalse(p.hasHorizon());
	}

	@Test
	public void onlyTheSevenMostRecentActiveDaysCount()
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = spine();
		long xp = 0;
		put(spine, TODAY.minusDays(11), xp);
		// four slow days, then seven at ten times the rate. only the newest
		// seven feed the pace
		for (int i = 10; i >= 7; i--)
		{
			xp += 1_000;
			put(spine, TODAY.minusDays(i), xp);
		}
		for (int i = 6; i >= 0; i--)
		{
			xp += 10_000;
			put(spine, TODAY.minusDays(i), xp);
		}

		PaceBook.Pace p = PaceBook.forSkill(spine, SKILL, xp, TODAY);

		assertEquals(7, p.activeDays);
		assertEquals(10_000.0, p.xpPerActiveDay, 0.001);
		assertEquals(7, p.spanDays);
	}

	@Test
	public void aMissingSkillKeyIsNoDataNotZero()
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = spine();
		put(spine, TODAY.minusDays(2), 0L);
		putBlind(spine, TODAY.minusDays(1));
		put(spine, TODAY, 5_000_000L);

		PaceBook.Pace p = PaceBook.forSkill(spine, SKILL, 5_000_000L, TODAY);

		// reading the blind baseline as 0 would post a five-million-xp day
		assertEquals(0, p.activeDays);
		assertNull(p.lastActive);
		assertFalse(p.hasHorizon());
	}

	@Test
	public void ninetyNineTurnsTheChaseIntoTheCeilingAndTwoHundredMillionEndsIt()
	{
		TreeMap<LocalDate, HistoryLog.Baseline> spine = spine();
		put(spine, TODAY.minusDays(2), 13_000_000L);
		put(spine, TODAY.minusDays(1), 13_017_431L);
		put(spine, TODAY, 13_034_431L);

		PaceBook.Pace maxed = PaceBook.forSkill(spine, SKILL, 13_034_431L, TODAY);
		assertNull(maxed.targetLevel);
		assertEquals(200_000_000L, maxed.targetXp);
		assertTrue(maxed.hasHorizon());

		PaceBook.Pace done = PaceBook.forSkill(spine, SKILL, 200_000_000L, TODAY);
		assertNull(done.targetLevel);
		assertEquals(0L, done.targetXp);
		assertEquals(0L, done.xpRemaining);
		assertFalse(done.hasHorizon());
		// nothing left ahead, so not dormant
		assertFalse(done.dormant());
	}

	@Test
	public void theCurveIsTheGamesOwn()
	{
		assertEquals(0L, PaceBook.xpForLevel(1));
		assertEquals(83L, PaceBook.xpForLevel(2));
		assertEquals(101_333L, PaceBook.xpForLevel(50));
		assertEquals(13_034_431L, PaceBook.xpForLevel(99));
		assertEquals(1, PaceBook.levelAt(0));
		assertEquals(1, PaceBook.levelAt(82));
		assertEquals(2, PaceBook.levelAt(83));
		assertEquals(49, PaceBook.levelAt(100_000));
		assertEquals(99, PaceBook.levelAt(200_000_000L));
	}
}
