/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The dryness ledger, computed entirely from the journal: the bundled wiki
 * rate book (per-kill 1/N denominators for collection-log uniques) against the
 * journal's own kill counts and stored collection log. The maths is the site's
 * pet/collection engine verbatim — percentile dry = (1 − (1 − 1/rate)^kc) × 100
 * — so the panel and a synced web page agree on every chase they both know.
 *
 * <p>Only unobtained items rarer than 1/{@link #MIN_DRY_RATE} count as a chase:
 * commons aren't a grind, and forward-only capture can't prove the player
 * doesn't already own them.
 */
@Slf4j
class GrindBook
{
	private static final int MIN_DRY_RATE = 100;
	private static final int MAX_ROWS = 20;

	// boss display name → {item name → rate denominator}; loaded once, lazily.
	private volatile Map<String, Map<String, Integer>> drops;

	private Map<String, Map<String, Integer>> book()
	{
		Map<String, Map<String, Integer>> loaded = drops;
		if (loaded != null)
		{
			return loaded;
		}
		Map<String, Map<String, Integer>> out = new HashMap<>();
		try (InputStream in = GrindBook.class.getResourceAsStream("/chronicle/osrs_clog_rates.json"))
		{
			if (in != null)
			{
				JsonObject root = new Gson().fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
				JsonObject bosses = root.has("drops") && root.get("drops").isJsonObject()
					? root.getAsJsonObject("drops") : new JsonObject();
				for (Map.Entry<String, JsonElement> b : bosses.entrySet())
				{
					if (!b.getValue().isJsonObject())
					{
						continue;
					}
					Map<String, Integer> items = new HashMap<>();
					for (Map.Entry<String, JsonElement> it : b.getValue().getAsJsonObject().entrySet())
					{
						try
						{
							items.put(it.getKey(), it.getValue().getAsInt());
						}
						catch (RuntimeException ignored)
						{
							// non-numeric rate — skip the row
						}
					}
					out.put(b.getKey(), items);
				}
			}
		}
		catch (Exception e)
		{
			log.debug("rate book load failed", e);
		}
		drops = out;
		return out;
	}

	/** "Abyssal Sire" / "abyssal_sire" → "abyssalsire" — bridges snapshot keys. */
	private static String norm(String s)
	{
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toLowerCase(Locale.ROOT).toCharArray())
		{
			if (Character.isLetterOrDigit(c))
			{
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * Compute the active grinds from the journal's stored collection log
	 * ({@code kcs}, {@code clog_items}, {@code by_cat}) and its drop sources
	 * (a second KC signal). Runs off the client thread; pure function of its
	 * inputs.
	 */
	List<ChronicleApiClient.GrindRow> grinds(JsonObject clog,
		List<LocalStore.SourceRow> dropSources)
	{
		Map<String, Map<String, Integer>> rates = book();
		if (rates.isEmpty())
		{
			return new ArrayList<>();
		}
		// KC per normalised boss key: the clog's own page KCs, floored up by the
		// drop ledger's per-source kill counts.
		Map<String, Long> kcByNorm = new HashMap<>();
		if (clog != null && clog.has("kcs") && clog.get("kcs").isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : clog.getAsJsonObject("kcs").entrySet())
			{
				long v = safeLong(e.getValue());
				if (v > 0)
				{
					kcByNorm.merge(norm(e.getKey()), v, Math::max);
				}
			}
		}
		if (dropSources != null)
		{
			for (LocalStore.SourceRow sr : dropSources)
			{
				if (sr.kc > 0)
				{
					kcByNorm.merge(norm(sr.name), (long) sr.kc, Math::max);
				}
			}
		}
		// Obtained detection: the global clog-item set (full-log capture makes
		// this comprehensive once the player opens their log) plus each boss's
		// own captured page.
		Set<String> obtained = new HashSet<>();
		if (clog != null && clog.has("clog_items") && clog.get("clog_items").isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : clog.getAsJsonObject("clog_items").entrySet())
			{
				obtained.add(e.getKey().toLowerCase(Locale.ROOT));
			}
		}
		Map<String, Set<String>> pageItems = new HashMap<>();
		if (clog != null && clog.has("by_cat") && clog.get("by_cat").isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : clog.getAsJsonObject("by_cat").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				Set<String> names = new HashSet<>();
				for (Map.Entry<String, JsonElement> it : e.getValue().getAsJsonObject().entrySet())
				{
					names.add(it.getKey().toLowerCase(Locale.ROOT));
				}
				pageItems.put(norm(e.getKey()), names);
			}
		}

		List<ChronicleApiClient.GrindRow> out = new ArrayList<>();
		for (Map.Entry<String, Map<String, Integer>> boss : rates.entrySet())
		{
			Long kc = kcByNorm.get(norm(boss.getKey()));
			if (kc == null || kc <= 0)
			{
				continue;
			}
			Set<String> page = pageItems.get(norm(boss.getKey()));
			for (Map.Entry<String, Integer> item : boss.getValue().entrySet())
			{
				int rate = item.getValue() != null ? item.getValue() : 0;
				String li = item.getKey().toLowerCase(Locale.ROOT);
				boolean got = obtained.contains(li) || (page != null && page.contains(li));
				if (got || rate < MIN_DRY_RATE)
				{
					continue;
				}
				double pct = (1.0 - Math.pow(1.0 - 1.0 / rate, kc)) * 100.0;
				out.add(new ChronicleApiClient.GrindRow(boss.getKey(), item.getKey(),
					kc, rate, Math.round(pct * 10.0) / 10.0));
			}
		}
		out.sort((a, b) ->
		{
			int byDry = Double.compare(b.percentileDry, a.percentileDry);
			return byDry != 0 ? byDry : Long.compare(b.rate, a.rate);
		});
		return out.size() > MAX_ROWS ? new ArrayList<>(out.subList(0, MAX_ROWS)) : out;
	}

	private static long safeLong(JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}
}
