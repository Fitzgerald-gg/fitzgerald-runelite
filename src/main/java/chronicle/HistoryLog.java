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
 * The journal's calendar spine: one JSON line per day per account, appended to
 * {@code <slug>.history.jsonl} beside the journal and never rewritten. A line is
 * that day's closing baseline ({@code {"date","skills","counters","kcs"}}). A
 * period's gain is one baseline minus another. Several lines for one date are
 * normal (login, rollover and logout all append); readers take the last line for
 * a date and skip a torn one. The History tab and PaceBook read it back.
 */
@Slf4j
class HistoryLog
{
	// The spine sits beside the journal's own <slug>.json.
	static final String SPINE_SUFFIX = ".history.jsonl";

	private final Gson gson;

	HistoryLog(Gson gson)
	{
		this.gson = gson;
	}

	// Last date appended, per account, for this session; gates the rollover append.
	// Keyed per account: two characters played on the same day each still get a line.
	private final Map<String, String> lastAppendedDate = new java.util.concurrent.ConcurrentHashMap<>();

	/** Append today's closing baseline. Called at login-load, day rollover and logout. */
	synchronized void append(File dir, String rsn, Map<String, Long> skills,
		Map<String, Long> counters, Map<String, Long> kcs)
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
		// kcs arrived after the rest. Older lines on the stream carry none.
		JsonObject kc = new JsonObject();
		if (kcs != null)
		{
			kcs.forEach(kc::addProperty);
		}
		line.add("kcs", kc);
		try
		{
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				log.debug("could not create history dir {}", dir);
				return;
			}
			File f = new File(dir, LocalStore.slug(rsn) + SPINE_SUFFIX);
			try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))
			{
				w.write(gson.toJson(line));
				w.write('\n');
			}
			lastAppendedDate.put(LocalStore.slug(rsn), today);
		}
		catch (Exception e)   // best-effort; the next append retries
		{
			log.debug("history append failed", e);
		}
	}

	static final class Baseline
	{
		final Map<String, Long> skills = new java.util.HashMap<>();
		final Map<String, Long> counters = new java.util.HashMap<>();
		final Map<String, Long> kcs = new java.util.HashMap<>();
	}

	java.util.TreeMap<LocalDate, Baseline> read(File dir, String rsn)
	{
		java.util.TreeMap<LocalDate, Baseline> out = new java.util.TreeMap<>();
		File f = new File(dir, LocalStore.slug(rsn) + SPINE_SUFFIX);
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
					fill(o, "kcs", b.kcs);
					out.put(date, b);   // later lines for a date overwrite: last wins
				}
				catch (RuntimeException torn)
				{
					// torn tail from a crash, skip it
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
					// non-numeric value, skip
				}
			}
		}
	}

	/**
	 * Fold another spine file into this account's, keeping only dates not already
	 * on record. Readers take the last line for a date, and an import must never
	 * sit on top of a day this client measured itself.
	 *
	 * @return how many days came across.
	 */
	synchronized int importSpine(File dir, String rsn, File source)
	{
		if (rsn == null || rsn.isEmpty() || source == null || !source.isFile())
		{
			return 0;
		}
		java.util.Set<String> have = read(dir, rsn).keySet().stream()
			.map(java.time.LocalDate::toString)
			.collect(java.util.stream.Collectors.toSet());
		int added = 0;
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
			new java.io.FileInputStream(source), StandardCharsets.UTF_8)))
		{
			File out = new File(dir, LocalStore.slug(rsn) + SPINE_SUFFIX);
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				return 0;
			}
			try (Writer w = new OutputStreamWriter(new FileOutputStream(out, true), StandardCharsets.UTF_8))
			{
				String line;
				while ((line = r.readLine()) != null)
				{
					if (line.trim().isEmpty())
					{
						continue;
					}
					String date;
					try
					{
						JsonObject o = gson.fromJson(line, JsonObject.class);
						date = o != null && o.has("date") ? o.get("date").getAsString() : null;
					}
					catch (RuntimeException torn)
					{
						continue;   // half-written source line, skipped as on read
					}
					if (date == null || !have.add(date))
					{
						continue;
					}
					w.write(line);
					w.write('\n');
					added++;
				}
			}
		}
		catch (Exception e)
		{
			log.debug("history import failed", e);
		}
		if (added > 0)
		{
			lastAppendedDate.remove(LocalStore.slug(rsn));
		}
		return added;
	}

	/**
	 * True while no baseline has been appended for this account today. A predicate,
	 * not a latch: it keeps saying true until append() records today's date. Two
	 * callers ahead of an append both see it.
	 */
	boolean dayRolledOver(String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return false;   // nothing to key on; append() refuses a nameless account too
		}
		String today = LocalDate.now(ZoneId.systemDefault()).toString();
		return !today.equals(lastAppendedDate.get(LocalStore.slug(rsn)));
	}
}
