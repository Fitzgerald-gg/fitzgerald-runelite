/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the calendar spine's READ contract — the half of the history file that
 * the panel and the year-card export actually depend on.
 *
 * <p>The spine is append-only and deliberately writes a day several times over
 * (login, rollover, logout all append a closing baseline), so the file alone is
 * ambiguous: what makes it a calendar is the rule that the last line for a date
 * is the one that counts, and that a line the reader cannot parse costs only
 * itself. Every figure the History tab shows is the subtraction of two of these
 * baselines, so a reader that merged duplicate days, or that abandoned the file
 * at the first torn line, would not fail loudly — it would quietly report the
 * wrong numbers for the wrong days.
 */
public class HistoryLogTest
{
	private static final String RSN = "Tester";

	private HistoryLog log;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		log = new HistoryLog(new Gson());
		dir = Files.createTempDirectory("chronicle-history").toFile();
	}

	private static Map<String, Long> map(String key, long value)
	{
		Map<String, Long> m = new HashMap<>();
		m.put(key, value);
		return m;
	}

	private File spine(String rsn)
	{
		return new File(dir, LocalStore.slug(rsn) + ".history.jsonl");
	}

	/** Append a line the writer never would — a hand-edited or half-written one. */
	private void rawLine(String rsn, String text, boolean terminated) throws Exception
	{
		try (Writer w = new OutputStreamWriter(
			new FileOutputStream(spine(rsn), true), StandardCharsets.UTF_8))
		{
			w.write(text);
			if (terminated)
			{
				w.write('\n');
			}
		}
	}

	/**
	 * Three appends in a day is the normal shape of a session, not a fault. The
	 * closing figure is the one the calendar wants, and it REPLACES its
	 * predecessors rather than merging with them — a merge would resurrect
	 * counters the player's own day had already moved past.
	 */
	@Test
	public void theLastLineForADateIsTheOneThatCounts()
	{
		Map<String, Long> opening = new HashMap<>();
		opening.put("tilesWalked", 10L);
		opening.put("logsChopped", 7L);
		log.append(dir, RSN, map("attack", 100L), opening, java.util.Collections.emptyMap());
		log.append(dir, RSN, map("attack", 140L), map("tilesWalked", 90L), java.util.Collections.emptyMap());

		TreeMap<LocalDate, HistoryLog.Baseline> got = log.read(dir, RSN);
		assertEquals(1, got.size());
		HistoryLog.Baseline b = got.firstEntry().getValue();
		assertEquals(140L, (long) b.skills.get("attack"));
		assertEquals(90L, (long) b.counters.get("tilesWalked"));
		// The earlier line is gone entirely, not folded in underneath.
		assertNull(b.counters.get("logsChopped"));
	}

	/**
	 * A crash or a full disk leaves a half-written final line. It costs that line
	 * and nothing else: the days already closed are the record, and abandoning
	 * the file at the first parse failure would lose a player's whole history to
	 * a few stray bytes at the end of it.
	 */
	@Test
	public void aTornFinalLineCostsOnlyItself() throws Exception
	{
		log.appendImported(dir, RSN, "2026-01-01", map("attack", 50L));
		log.appendImported(dir, RSN, "2026-01-02", map("attack", 60L));
		rawLine(RSN, "", true);                                    // an empty line
		rawLine(RSN, "{\"date\":\"2026-01-03\",\"skills\":{", false);   // torn mid-write

		TreeMap<LocalDate, HistoryLog.Baseline> got = log.read(dir, RSN);
		assertEquals(2, got.size());
		assertEquals(50L, (long) got.get(LocalDate.parse("2026-01-01")).skills.get("attack"));
		assertEquals(60L, (long) got.get(LocalDate.parse("2026-01-02")).skills.get("attack"));
		assertFalse(got.containsKey(LocalDate.parse("2026-01-03")));
	}

	/**
	 * The same resilience in the middle of the stream rather than at the end of
	 * it. This is the one that matters for a long-lived spine: a day damaged
	 * years ago must not truncate the calendar at the point of damage and hide
	 * every day recorded since.
	 */
	@Test
	public void aDamagedLineDoesNotTruncateTheDaysAfterIt() throws Exception
	{
		log.appendImported(dir, RSN, "2026-01-01", map("attack", 50L));
		// A line cut short by a crash, and one whose date no longer reads as one.
		rawLine(RSN, "{\"date\":\"2026-01-02\",\"skills\":{\"attack\":60", true);
		rawLine(RSN, "{\"date\":\"01/02/2026\",\"skills\":{}}", true);
		log.appendImported(dir, RSN, "2026-01-03", map("attack", 70L));

		TreeMap<LocalDate, HistoryLog.Baseline> got = log.read(dir, RSN);
		assertEquals(2, got.size());
		assertEquals(50L, (long) got.get(LocalDate.parse("2026-01-01")).skills.get("attack"));
		assertEquals(70L, (long) got.get(LocalDate.parse("2026-01-03")).skills.get("attack"));
	}

	/**
	 * A day whose line is intact but whose contents are not: the numbers that
	 * survive are kept and the rest is dropped, because a single unreadable
	 * counter must not cost the whole day's baseline.
	 */
	@Test
	public void aLineSurvivesTheValuesInsideItThatDoNot() throws Exception
	{
		rawLine(RSN, "{\"date\":\"2026-02-02\",\"skills\":{\"attack\":\"lots\",\"defence\":70},"
			+ "\"counters\":{}}", true);
		rawLine(RSN, "{\"date\":\"2026-02-03\"}", true);   // no containers at all

		TreeMap<LocalDate, HistoryLog.Baseline> got = log.read(dir, RSN);
		assertEquals(2, got.size());
		HistoryLog.Baseline partial = got.get(LocalDate.parse("2026-02-02"));
		assertEquals(70L, (long) partial.skills.get("defence"));
		assertNull(partial.skills.get("attack"));
		// A dated line with nothing in it is still a day the calendar knows about.
		assertTrue(got.get(LocalDate.parse("2026-02-03")).skills.isEmpty());
	}

	/**
	 * The Wise Old Man import writes the past through the same one-line-per-day
	 * door as a live session, into the same file — so an imported day and a
	 * played day are indistinguishable to the reader, and the panel can subtract
	 * across the join. Imported days carry no counters, only skills.
	 */
	@Test
	public void anImportedDayIsJustAnotherLineInTheSameStream()
	{
		log.appendImported(dir, RSN, "2024-03-01", map("overall", 12_345L));
		log.append(dir, RSN, map("overall", 20_000L), map("tilesWalked", 5L), java.util.Collections.emptyMap());

		TreeMap<LocalDate, HistoryLog.Baseline> got = log.read(dir, RSN);
		assertEquals(2, got.size());
		HistoryLog.Baseline imported = got.get(LocalDate.parse("2024-03-01"));
		assertEquals(12_345L, (long) imported.skills.get("overall"));
		assertTrue(imported.counters.isEmpty());
		// The played day is the later of the two, whatever today happens to be.
		assertEquals(20_000L, (long) got.lastEntry().getValue().skills.get("overall"));
		// A history import can be re-run; the later line for that date simply wins.
		log.appendImported(dir, RSN, "2024-03-01", map("overall", 12_400L));
		assertEquals(12_400L, (long) log.read(dir, RSN)
			.get(LocalDate.parse("2024-03-01")).skills.get("overall"));
	}

	/**
	 * Total experience outgrew an int long ago; the whole stream is long-valued
	 * for that reason. A silent narrowing here would cap a maxed account's
	 * overall xp and make every period containing it read as a loss.
	 */
	@Test
	public void figuresBeyondAnIntSurviveTheRoundTrip()
	{
		log.appendImported(dir, RSN, "2026-04-01", map("overall", 4_600_000_000L));
		assertEquals(4_600_000_000L, (long) log.read(dir, RSN)
			.get(LocalDate.parse("2026-04-01")).skills.get("overall"));
	}

	/**
	 * The spine is filed per account. Two accounts played on one client must not
	 * splice their baselines into a single calendar — the subtraction that builds
	 * a period would then read one account's totals against the other's.
	 */
	@Test
	public void eachAccountKeepsItsOwnSpine()
	{
		log.appendImported(dir, "Alpha", "2026-05-01", map("overall", 1_000L));
		log.appendImported(dir, "Beta", "2026-05-01", map("overall", 9_000L));

		assertEquals(1_000L, (long) log.read(dir, "Alpha")
			.get(LocalDate.parse("2026-05-01")).skills.get("overall"));
		assertEquals(9_000L, (long) log.read(dir, "Beta")
			.get(LocalDate.parse("2026-05-01")).skills.get("overall"));
		assertTrue(spine("Alpha").isFile());
		assertTrue(spine("Beta").isFile());
	}

	/**
	 * ...but one account is one spine however the client spells its name. The
	 * game reports a display name with spaces where the login had underscores,
	 * and case travels freely; a spine that forked on any of that would strand
	 * half a player's history under a name they never chose.
	 */
	@Test
	public void oneAccountIsOneSpineHoweverItsNameIsSpelt()
	{
		log.appendImported(dir, "Alpha Two", "2026-06-01", map("overall", 100L));
		log.appendImported(dir, "alpha_two", "2026-06-02", map("overall", 200L));

		TreeMap<LocalDate, HistoryLog.Baseline> got = log.read(dir, "ALPHA TWO");
		assertEquals(2, got.size());
		assertEquals(100L, (long) got.get(LocalDate.parse("2026-06-01")).skills.get("overall"));
		assertEquals(200L, (long) got.get(LocalDate.parse("2026-06-02")).skills.get("overall"));
	}

	/**
	 * Without a name there is no account to file under, and the slug would fall
	 * back to a shared default — so an append that arrives before the player's
	 * name is known must write nothing at all, rather than start a spine that a
	 * real account could later be handed.
	 */
	@Test
	public void anAppendWithoutAnAccountWritesNothing()
	{
		log.append(dir, null, map("attack", 1L), map("tilesWalked", 1L), java.util.Collections.emptyMap());
		log.append(dir, "", map("attack", 1L), map("tilesWalked", 1L), java.util.Collections.emptyMap());
		log.appendImported(dir, null, "2026-07-01", map("attack", 1L));
		log.appendImported(dir, RSN, null, map("attack", 1L));

		String[] written = dir.list();
		assertEquals(0, written == null ? 0 : written.length);
	}

	/** A spine that was never written reads as an empty calendar, not a failure. */
	@Test
	public void anAbsentSpineReadsEmpty()
	{
		assertTrue(log.read(dir, "Nobody").isEmpty());
	}
}
