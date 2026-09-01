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
 * session; the last line for it wins, and a line that won't parse costs only itself.
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
		return new File(dir, LocalStore.slug(rsn) + HistoryLog.SPINE_SUFFIX);
	}

	// Put a line on the stream directly: past days the plugin never played through,
	// and lines the real writer never produces (hand-edited, or cut off mid-write).
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
		rawLine(RSN, "{\"date\":\"2026-01-01\",\"skills\":{\"attack\":50},\"counters\":{}}", true);
		rawLine(RSN, "{\"date\":\"2026-01-02\",\"skills\":{\"attack\":60},\"counters\":{}}", true);
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
		rawLine(RSN, "{\"date\":\"2026-01-01\",\"skills\":{\"attack\":50},\"counters\":{}}", true);
		// one line cut short by a crash, one whose date won't parse
		rawLine(RSN, "{\"date\":\"2026-01-02\",\"skills\":{\"attack\":60", true);
		rawLine(RSN, "{\"date\":\"01/02/2026\",\"skills\":{}}", true);
		rawLine(RSN, "{\"date\":\"2026-01-03\",\"skills\":{\"attack\":70},\"counters\":{}}", true);

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

	// overall xp outgrew an int long ago; a narrowing read turns the total into a loss
	@Test
	public void figuresBeyondAnIntSurviveTheRoundTrip()
	{
		log.append(dir, RSN, map("overall", 4_600_000_000L),
			java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
		assertEquals(4_600_000_000L, (long) log.read(dir, RSN)
			.lastEntry().getValue().skills.get("overall"));
	}

	@Test
	public void eachAccountKeepsItsOwnSpine() throws Exception
	{
		rawLine("Alpha", "{\"date\":\"2026-05-01\",\"skills\":{\"overall\":1000},\"counters\":{}}", true);
		rawLine("Beta", "{\"date\":\"2026-05-01\",\"skills\":{\"overall\":9000},\"counters\":{}}", true);

		assertEquals(1_000L, (long) log.read(dir, "Alpha")
			.get(LocalDate.parse("2026-05-01")).skills.get("overall"));
		assertEquals(9_000L, (long) log.read(dir, "Beta")
			.get(LocalDate.parse("2026-05-01")).skills.get("overall"));
		assertTrue(spine("Alpha").isFile());
		assertTrue(spine("Beta").isFile());
	}

	// the client reports "Alpha Two" where the login was alpha_two; slug() folds both to one file
	@Test
	public void oneAccountIsOneSpineHoweverItsNameIsSpelt() throws Exception
	{
		rawLine("Alpha Two", "{\"date\":\"2026-06-01\",\"skills\":{\"overall\":100},\"counters\":{}}", true);
		rawLine("alpha_two", "{\"date\":\"2026-06-02\",\"skills\":{\"overall\":200},\"counters\":{}}", true);

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

		String[] written = dir.list();
		assertEquals(0, written == null ? 0 : written.length);
	}

	@Test
	public void anAbsentSpineReadsEmpty()
	{
		assertTrue(log.read(dir, "Nobody").isEmpty());
	}

	private int lineCount() throws Exception
	{
		return (int) java.nio.file.Files.readAllLines(spine(RSN).toPath()).stream()
			.filter(l -> !l.trim().isEmpty()).count();
	}

	private String dayLine(String date, long attack)
	{
		return "{\"date\":\"" + date + "\",\"skills\":{\"attack\":" + attack
			+ "},\"counters\":{},\"kcs\":{}}";
	}

	@Test
	public void severalAppendsInOneDayLeaveOneLine() throws Exception
	{
		log.append(dir, RSN, map("attack", 100L), map("tilesWalked", 1L),
			java.util.Collections.emptyMap());
		log.append(dir, RSN, map("attack", 140L), map("tilesWalked", 2L),
			java.util.Collections.emptyMap());
		log.append(dir, RSN, map("attack", 180L), map("tilesWalked", 3L),
			java.util.Collections.emptyMap());
		assertEquals(1, lineCount());
		assertEquals(180L, (long) log.read(dir, RSN).firstEntry().getValue().skills.get("attack"));
	}

	@Test
	public void anEarlierDaySurvivesTodaysAppend() throws Exception
	{
		rawLine(RSN, dayLine("2020-01-01", 5L), true);
		log.append(dir, RSN, map("attack", 200L), map("tilesWalked", 1L),
			java.util.Collections.emptyMap());
		assertEquals(2, lineCount());
		assertEquals(2, log.read(dir, RSN).size());
		assertEquals(5L, (long) log.read(dir, RSN)
			.get(LocalDate.parse("2020-01-01")).skills.get("attack"));
	}

	@Test
	public void compactionFoldsARepeatedDayAndKeepsTheLast() throws Exception
	{
		rawLine(RSN, dayLine("2026-01-01", 1L), true);
		rawLine(RSN, dayLine("2026-01-01", 2L), true);
		rawLine(RSN, dayLine("2026-01-01", 3L), true);
		rawLine(RSN, dayLine("2026-01-02", 9L), true);
		assertEquals(4, lineCount());
		assertEquals(2, log.compact(dir, RSN));
		assertEquals(2, lineCount());
		TreeMap<LocalDate, HistoryLog.Baseline> got = log.read(dir, RSN);
		assertEquals(3L, (long) got.get(LocalDate.parse("2026-01-01")).skills.get("attack"));
		assertEquals(9L, (long) got.get(LocalDate.parse("2026-01-02")).skills.get("attack"));
	}

	@Test
	public void compactionLeavesAFileWithoutRepeatsAlone() throws Exception
	{
		rawLine(RSN, dayLine("2026-01-01", 1L), true);
		rawLine(RSN, dayLine("2026-01-02", 2L), true);
		byte[] before = java.nio.file.Files.readAllBytes(spine(RSN).toPath());
		assertEquals(0, log.compact(dir, RSN));
		org.junit.Assert.assertArrayEquals(before,
			java.nio.file.Files.readAllBytes(spine(RSN).toPath()));
	}
}
