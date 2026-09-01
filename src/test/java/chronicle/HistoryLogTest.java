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
 * Read contract for the history spine. A date gets written several times over a
 * session, so the last line for it wins, and a line that won't parse costs only itself.
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

	// write a line the real writer never would: hand-edited, or cut off mid-write
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
		// the later line replaces the earlier one, so logsChopped goes with it
		assertNull(b.counters.get("logsChopped"));
	}

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

	@Test
	public void aDamagedLineDoesNotTruncateTheDaysAfterIt() throws Exception
	{
		log.appendImported(dir, RSN, "2026-01-01", map("attack", 50L));
		// one line cut short by a crash, one whose date won't parse
		rawLine(RSN, "{\"date\":\"2026-01-02\",\"skills\":{\"attack\":60", true);
		rawLine(RSN, "{\"date\":\"01/02/2026\",\"skills\":{}}", true);
		log.appendImported(dir, RSN, "2026-01-03", map("attack", 70L));

		TreeMap<LocalDate, HistoryLog.Baseline> got = log.read(dir, RSN);
		assertEquals(2, got.size());
		assertEquals(50L, (long) got.get(LocalDate.parse("2026-01-01")).skills.get("attack"));
		assertEquals(70L, (long) got.get(LocalDate.parse("2026-01-03")).skills.get("attack"));
	}

	// a value that won't read as a number drops itself; the rest of the day's baseline stays
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
		// a dated line with nothing in it still counts as a day
		assertTrue(got.get(LocalDate.parse("2026-02-03")).skills.isEmpty());
	}

	// imported past days share the file with played ones and carry skills but no counters
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
		// append() dates itself today, so the played day is always the later entry
		assertEquals(20_000L, (long) got.lastEntry().getValue().skills.get("overall"));
		// re-running an import is safe; the later line for the date wins
		log.appendImported(dir, RSN, "2024-03-01", map("overall", 12_400L));
		assertEquals(12_400L, (long) log.read(dir, RSN)
			.get(LocalDate.parse("2024-03-01")).skills.get("overall"));
	}

	// overall xp outgrew an int long ago; narrowing it here would read as a loss
	@Test
	public void figuresBeyondAnIntSurviveTheRoundTrip()
	{
		log.appendImported(dir, RSN, "2026-04-01", map("overall", 4_600_000_000L));
		assertEquals(4_600_000_000L, (long) log.read(dir, RSN)
			.get(LocalDate.parse("2026-04-01")).skills.get("overall"));
	}

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

	// the client reports "Alpha Two" where the login was alpha_two; slug() folds both to one file
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

	// with no name slug() falls back to the shared "profile" file, so append refuses to write
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

	@Test
	public void anAbsentSpineReadsEmpty()
	{
		assertTrue(log.read(dir, "Nobody").isEmpty());
	}
}
