/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * The journal's calendar spine: one JSON line per day per account, appended —
 * never rewritten — to {@code <slug>.history.jsonl} beside the journal.
 *
 * <p>Each line is a CLOSING baseline ({@code {"date","skills","counters"}});
 * a calendar period's story is the subtraction of two baselines. Multiple
 * lines for one date are fine (login, rollover, logout all append) — readers
 * take the last line per date, and a torn final line from a crash is skipped
 * on read, which is all the recovery an append-only stream needs. The History
 * tab and the year-card export both read this stream.
 */
@Slf4j
class HistoryLog
{
	private final Gson gson = new Gson();

	// The last date a baseline was appended for, per this client session —
	// enough to make the day-rollover append fire exactly once.
	private volatile String lastAppendedDate;

	/** Append today's closing baseline. Call at login-load, day rollover, logout. */
	synchronized void append(File dir, String rsn, Map<String, Integer> skills,
		Map<String, Long> counters)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return;
		}
		String today = LocalDate.now(ZoneId.systemDefault()).toString();
		JsonObject line = new JsonObject();
		line.addProperty("date", today);
		JsonObject sk = new JsonObject();
		if (skills != null)
		{
			skills.forEach(sk::addProperty);
		}
		line.add("skills", sk);
		JsonObject ct = new JsonObject();
		if (counters != null)
		{
			counters.forEach(ct::addProperty);
		}
		line.add("counters", ct);
		try
		{
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				log.debug("could not create history dir {}", dir);
				return;
			}
			File f = new File(dir, LocalStore.slug(rsn) + ".history.jsonl");
			try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))
			{
				w.write(gson.toJson(line));
				w.write('\n');
			}
			lastAppendedDate = today;
		}
		catch (Exception e)   // best-effort; the next append retries
		{
			log.debug("history append failed", e);
		}
	}

	/** One day's closing baseline, as read back from the stream. */
	static final class Baseline
	{
		final Map<String, Long> skills = new java.util.HashMap<>();
		final Map<String, Long> counters = new java.util.HashMap<>();
	}

	/**
	 * Read the stream back: last line per date wins, torn lines are skipped —
	 * the whole recovery story an append-only file needs.
	 */
	java.util.TreeMap<LocalDate, Baseline> read(File dir, String rsn)
	{
		java.util.TreeMap<LocalDate, Baseline> out = new java.util.TreeMap<>();
		File f = new File(dir, LocalStore.slug(rsn) + ".history.jsonl");
		if (!f.isFile())
		{
			return out;
		}
		try (java.io.BufferedReader r = new java.io.BufferedReader(
			new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				try
				{
					JsonObject o = gson.fromJson(line, JsonObject.class);
					if (o == null || !o.has("date"))
					{
						continue;
					}
					LocalDate date = LocalDate.parse(o.get("date").getAsString());
					Baseline b = new Baseline();
					fill(o, "skills", b.skills);
					fill(o, "counters", b.counters);
					out.put(date, b);   // later lines for a date overwrite: last wins
				}
				catch (RuntimeException torn)
				{
					// a torn tail from a crash — skip and carry on
				}
			}
		}
		catch (Exception e)
		{
			log.debug("history read failed", e);
		}
		return out;
	}

	private static void fill(JsonObject o, String key, Map<String, Long> into)
	{
		if (o.has(key) && o.get(key).isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: o.getAsJsonObject(key).entrySet())
			{
				try
				{
					into.put(e.getKey(), e.getValue().getAsLong());
				}
				catch (RuntimeException ignored)
				{
					// non-numeric — skip
				}
			}
		}
	}

	/** Append an imported (historic) baseline for a specific date — the Wise
	 *  Old Man import writes the past through the same one-line-per-day door. */
	synchronized void appendImported(File dir, String rsn, String date, Map<String, Long> skills)
	{
		if (rsn == null || rsn.isEmpty() || date == null)
		{
			return;
		}
		JsonObject line = new JsonObject();
		line.addProperty("date", date);
		JsonObject sk = new JsonObject();
		if (skills != null)
		{
			skills.forEach(sk::addProperty);
		}
		line.add("skills", sk);
		line.add("counters", new JsonObject());
		try
		{
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				return;
			}
			File f = new File(dir, LocalStore.slug(rsn) + ".history.jsonl");
			try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))
			{
				w.write(gson.toJson(line));
				w.write('\n');
			}
		}
		catch (Exception e)
		{
			log.debug("history import append failed", e);
		}
	}

	/** True once per calendar day: the caller should append a fresh baseline. */
	boolean dayRolledOver()
	{
		String today = LocalDate.now(ZoneId.systemDefault()).toString();
		return !today.equals(lastAppendedDate);
	}
}
