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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
		Map<String, Long> kcByNorm = killCounts(clog, dropSources);
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

	// kc per normalised boss key: clog page kcs, raised by the drop ledger's own counts.
	private static Map<String, Long> killCounts(JsonObject clog,
		List<LocalStore.SourceRow> dropSources)
	{
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
		return kcByNorm;
	}

	// ------------------------------------------------------------------
	// Pets
	// ------------------------------------------------------------------

	// No player has killed anything this many times; past it the odds are already
	// pinned at certainty and the figure is a corrupt count, not a grind.
	private static final long MAX_KC = 100_000_000L;

	/** One place a pet drops from, and how far the journal has gone there. */
	static final class PetSource
	{
		final String boss;
		final long kc;
		final long rate;

		PetSource(String boss, long kc, long rate)
		{
			this.boss = boss;
			this.kc = kc;
			this.rate = rate;
		}
	}

	/**
	 * An unearned pet weighed against every source that drops it. {@code percentileDry}
	 * is the share of players who would hold the pet by this point:
	 * 1 - product over sources of (1 - 1/N_i)^kc_i. Sources with no kills are left
	 * out; a pet with none of them is not a chase and gets no row at all.
	 */
	static final class PetChase
	{
		final String pet;
		final long kc;                    // kills across the sources that contributed
		final double percentileDry;
		final List<PetSource> sources;    // heaviest first

		PetChase(String pet, long kc, double percentileDry, List<PetSource> sources)
		{
			this.pet = pet;
			this.kc = kc;
			this.percentileDry = percentileDry;
			this.sources = sources;
		}
	}

	/**
	 * The chase behind each pet a collection log page lists, keyed by lower-cased pet
	 * name. Only pets the log has not lit, that the rate book prices, and that have a
	 * kill count somewhere. Everything else is absent from the map: the page renders
	 * those slots exactly as it did before. Off the client thread or on; reads nothing
	 * but its arguments.
	 */
	Map<String, PetChase> petChases(JsonObject clog, List<LocalStore.SourceRow> dropSources,
		Collection<String> pets)
	{
		Map<String, PetChase> out = new LinkedHashMap<>();
		if (pets == null || pets.isEmpty())
		{
			return out;
		}
		Map<String, Map<String, Integer>> rates = book();
		if (rates.isEmpty())
		{
			return out;
		}
		Map<String, Long> kcByNorm = killCounts(clog, dropSources);
		Set<String> obtained = allObtained(clog);
		// pet name (lower-cased) → every boss whose table holds it. A pet with two
		// sources (Callisto and Artio, Chaos Elemental and Chaos Fanatic) is one
		// chase fed from both, never the better-looking half of the pair.
		Map<String, List<PetSource>> bySource = new HashMap<>();
		for (Map.Entry<String, Map<String, Integer>> boss : rates.entrySet())
		{
			Long kc = kcByNorm.get(norm(boss.getKey()));
			if (kc == null || kc <= 0)
			{
				continue;
			}
			for (Map.Entry<String, Integer> item : boss.getValue().entrySet())
			{
				long rate = item.getValue() != null ? item.getValue() : 0;
				if (rate <= 0)
				{
					continue;
				}
				bySource.computeIfAbsent(item.getKey().toLowerCase(Locale.ROOT),
					k -> new ArrayList<>())
					.add(new PetSource(boss.getKey(), Math.min(kc, MAX_KC), rate));
			}
		}
		for (String pet : pets)
		{
			if (pet == null)
			{
				continue;
			}
			String key = pet.toLowerCase(Locale.ROOT);
			List<PetSource> src = bySource.get(key);
			if (src == null || obtained.contains(key))
			{
				continue;
			}
			List<PetSource> sorted = new ArrayList<>(src);
			sorted.sort((a, b) -> Long.compare(b.kc, a.kc));
			double miss = 1.0;
			long kc = 0;
			for (PetSource s : sorted)
			{
				// the chance of missing it every kill at this source; the pet is a
				// chase across all of them, so the misses multiply.
				miss *= Math.pow(1.0 - 1.0 / s.rate, s.kc);
				kc += s.kc;
			}
			double pct = Math.max(0.0, Math.min(100.0, (1.0 - miss) * 100.0));
			out.put(key, new PetChase(pet, kc, Math.round(pct * 10.0) / 10.0, sorted));
		}
		return out;
	}

	// Every item the stored log holds, whole-log set and each page's own capture
	// folded together: a pet is owned wherever the log says so.
	private static Set<String> allObtained(JsonObject clog)
	{
		Set<String> out = new HashSet<>();
		if (clog == null)
		{
			return out;
		}
		if (clog.has("clog_items") && clog.get("clog_items").isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : clog.getAsJsonObject("clog_items").entrySet())
			{
				out.add(e.getKey().toLowerCase(Locale.ROOT));
			}
		}
		if (clog.has("by_cat") && clog.get("by_cat").isJsonObject())
		{
			for (Map.Entry<String, JsonElement> cat : clog.getAsJsonObject("by_cat").entrySet())
			{
				if (!cat.getValue().isJsonObject())
				{
					continue;
				}
				for (Map.Entry<String, JsonElement> it : cat.getValue().getAsJsonObject().entrySet())
				{
					out.add(it.getKey().toLowerCase(Locale.ROOT));
				}
			}
		}
		return out;
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
