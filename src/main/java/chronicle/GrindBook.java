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
 * Dryness ledger. Weighs the bundled wiki rate book (per-kill 1/N denominators
 * for collection-log uniques) against the journal's own kill counts and stored
 * collection log. Dry percentile is (1 - (1 - 1/rate)^kc) * 100: the share of
 * players who have the drop by this kill count.
 */
@Slf4j
class GrindBook
{
	// rate denominator floor for a row to count as a chase; commoner drops aren't a grind.
	private static final int MIN_DRY_RATE = 100;
	private static final int MAX_ROWS = 20;

	private final Gson gson;

	GrindBook(Gson gson)
	{
		this.gson = gson;
	}

	// boss display name → {item name → rate denominator}, loaded on first use.
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
				JsonObject root = gson.fromJson(
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
							// non-numeric rate, skip it
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

	// "Abyssal Sire" and "abyssal_sire" both normalise to "abyssalsire". Plurals
	// survive, so a ledger source "Tormented Demon" will not join the clog page
	// "Tormented Demons". Other joins in the plugin strip the trailing s and do.
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

	// Reads the stored clog (kcs, clog_items, by_cat) plus the drop sources for a second
	// kc signal. Called off the client thread; nothing in here may touch the client.
	List<ChronicleApiClient.GrindRow> grinds(JsonObject clog,
		List<LocalStore.SourceRow> dropSources)
	{
		Map<String, Map<String, Integer>> rates = book();
		if (rates.isEmpty())
		{
			return new ArrayList<>();
		}
		// kc per normalised boss key: clog page kcs, raised by the drop ledger's own counts.
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
		// an item counts as obtained from either the global clog set or the boss's own page.
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
