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

	// A skilling roll stops improving here; the wiki's level term is flat past 99.
	private static final int LEVEL_CAP = 99;
	// Below this a denominator is not a grind any more, and a level term that ate
	// the whole base would print certainty off a handful of actions.
	private static final long MIN_RATE = 2;

	/**
	 * One place a pet rolls from, and how far the journal has gone there. For a boss
	 * that is a kill count; for a skilling pet it is the activity's own unit, and
	 * {@code rate} is the denominator after the level term has been taken off.
	 */
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
		// A skilling pet answers to a craft, not a monster: the activity it was
		// worked at, the noun its attempts are counted in, and the level the odds
		// were read at. All null/0 on a boss chase, which reads as it always did.
		final String activity;
		final String unit;
		final long level;

		PetChase(String pet, long kc, double percentileDry, List<PetSource> sources)
		{
			this(pet, kc, percentileDry, sources, null, null, 0);
		}

		PetChase(String pet, long kc, double percentileDry, List<PetSource> sources,
			String activity, String unit, long level)
		{
			this.pet = pet;
			this.kc = kc;
			this.percentileDry = percentileDry;
			this.sources = sources;
			this.activity = activity;
			this.unit = unit;
			this.level = level;
		}
	}

	/**
	 * The chase behind each pet a collection log page lists, keyed by lower-cased pet
	 * name. Only pets the log has not lit, that one of the two rate books prices, and
	 * that the journal has counted something for. Everything else is absent from the
	 * map: the page renders those slots exactly as it did before. Off the client
	 * thread or on; reads nothing but its arguments.
	 *
	 * <p>A skilling pet is weighed the same way, off {@code counters} rather than kill
	 * counts, and off {@code skills} for the level its odds are read at. Where a pet
	 * is in both books the skilling book wins outright: Tangleroot is the one that is,
	 * and the boss table's Hespori denominator is this same formula frozen at Farming
	 * 65, so admitting both would print two numbers for one event.
	 */
	Map<String, PetChase> petChases(JsonObject clog, List<LocalStore.SourceRow> dropSources,
		Map<String, Long> counters, Map<String, long[]> skills, Collection<String> pets)
	{
		Map<String, PetChase> out = new LinkedHashMap<>();
		if (pets == null || pets.isEmpty())
		{
			return out;
		}
		Map<String, Map<String, Integer>> rates = book();
		Map<String, SkillPet> skilling = skillBook();
		if (rates.isEmpty() && skilling.isEmpty())
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
			if (obtained.contains(key))
			{
				continue;
			}
			SkillPet spec = skilling.get(key);
			if (spec != null)
			{
				PetChase chase = skillingChase(pet, spec, counters, skills, kcByNorm);
				if (chase != null)
				{
					out.put(key, chase);
				}
				continue;
			}
			List<PetSource> src = bySource.get(key);
			if (src == null)
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

	// ------------------------------------------------------------------
	// Skilling pets
	// ------------------------------------------------------------------

	/**
	 * One way a skilling pet is rolled for, and the counter that counts it. Either
	 * {@code counter} names a key outright, or {@code suffix} takes a whole family of
	 * them ({@code manPickpockets}, {@code guardPickpockets}) less the ones
	 * {@code notSuffix} holds back and the ones this pet prices by name. {@code minus}
	 * takes another counter off the first, which is how the altars that have a rate of
	 * their own come out of the essence total.
	 */
	private static final class SkillSource
	{
		final String counter;
		final String suffix;
		final String notSuffix;
		final List<String> minus;
		final String name;
		final long base;

		SkillSource(String counter, String suffix, String notSuffix, List<String> minus,
			String name, long base)
		{
			this.counter = counter;
			this.suffix = suffix;
			this.notSuffix = notSuffix;
			this.minus = minus;
			this.name = name;
			this.base = base;
		}
	}

	/**
	 * A source counted in kills rather than actions. Hespori is one; so is every pet
	 * whose attempts a collection log page or the drop ledger already counted for it,
	 * a search of the Rewards Guardian or a master casket opened as readily as a boss
	 * killed. {@code orKc} names the same event again where the log and the ledger
	 * spell it differently ("Mad Angel" against "The Mad Angel"): the larger of the
	 * two is the count, never the sum, because they are one record read twice.
	 * {@code flat} holds the level term off a source that does not take one, which is
	 * Fishing Trawler alone among the fishing rows.
	 */
	private static final class SkillKill
	{
		final String kc;
		final List<String> orKc;
		final String name;
		final long base;
		final boolean flat;

		SkillKill(String kc, List<String> orKc, String name, long base, boolean flat)
		{
			this.kc = kc;
			this.orKc = orKc;
			this.name = name;
			this.base = base;
			this.flat = flat;
		}
	}

	/** A skilling pet: the craft it answers to, and every way it is rolled for. */
	private static final class SkillPet
	{
		final String skill;          // skillSheet key, lower-cased Skill name
		final String activity;       // what the row calls the grind
		final String unit;           // the noun its attempts are counted in
		final boolean levelScaled;
		final List<SkillSource> sources;
		final List<SkillKill> kills;
		final Set<String> named;     // counters this pet prices by name

		SkillPet(String skill, String activity, String unit, boolean levelScaled,
			List<SkillSource> sources, List<SkillKill> kills)
		{
			this.skill = skill;
			this.activity = activity;
			this.unit = unit;
			this.levelScaled = levelScaled;
			this.sources = sources;
			this.kills = kills;
			this.named = new HashSet<>();
			for (SkillSource s : sources)
			{
				if (s.counter != null)
				{
					this.named.add(s.counter);
				}
			}
		}
	}

	// lower-cased pet name -> its book entry, loaded on first use.
	private volatile Map<String, SkillPet> skillPets;

	private Map<String, SkillPet> skillBook()
	{
		Map<String, SkillPet> loaded = skillPets;
		if (loaded != null)
		{
			return loaded;
		}
		Map<String, SkillPet> out = new HashMap<>();
		try (InputStream in = GrindBook.class.getResourceAsStream(
			"/chronicle/osrs_skilling_pet_rates.json"))
		{
			if (in != null)
			{
				JsonObject root = gson.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
				JsonObject pets = root != null && root.has("pets")
					&& root.get("pets").isJsonObject()
					? root.getAsJsonObject("pets") : new JsonObject();
				for (Map.Entry<String, JsonElement> e : pets.entrySet())
				{
					if (!e.getValue().isJsonObject())
					{
						continue;
					}
					SkillPet pet = readSkillPet(e.getValue().getAsJsonObject());
					if (pet != null)
					{
						out.put(e.getKey().toLowerCase(Locale.ROOT), pet);
					}
				}
			}
		}
		catch (Exception e)
		{
			log.debug("skilling pet rate book load failed", e);
		}
		skillPets = out;
		return out;
	}

	private static SkillPet readSkillPet(JsonObject o)
	{
		List<SkillSource> sources = new ArrayList<>();
		if (o.has("sources") && o.get("sources").isJsonArray())
		{
			for (JsonElement el : o.getAsJsonArray("sources"))
			{
				if (!el.isJsonObject())
				{
					continue;
				}
				JsonObject s = el.getAsJsonObject();
				long base = safeLong(s.get("base"));
				if (base <= 0)
				{
					continue;   // an unpriced row is left unpriced, never guessed
				}
				List<String> minus = new ArrayList<>();
				if (s.has("minus") && s.get("minus").isJsonArray())
				{
					for (JsonElement m : s.getAsJsonArray("minus"))
					{
						minus.add(m.getAsString());
					}
				}
				sources.add(new SkillSource(str(s, "counter"), str(s, "suffix"),
					str(s, "notSuffix"), minus, str(s, "name"), base));
			}
		}
		List<SkillKill> kills = new ArrayList<>();
		if (o.has("kills") && o.get("kills").isJsonArray())
		{
			for (JsonElement el : o.getAsJsonArray("kills"))
			{
				if (!el.isJsonObject())
				{
					continue;
				}
				JsonObject k = el.getAsJsonObject();
				long base = safeLong(k.get("base"));
				if (base <= 0 || str(k, "kc") == null)
				{
					continue;   // an unpriced row is left unpriced, never guessed
				}
				List<String> orKc = new ArrayList<>();
				if (k.has("orKc") && k.get("orKc").isJsonArray())
				{
					for (JsonElement a : k.getAsJsonArray("orKc"))
					{
						orKc.add(a.getAsString());
					}
				}
				kills.add(new SkillKill(str(k, "kc"), orKc, str(k, "name"), base,
					k.has("flat") && k.get("flat").getAsBoolean()));
			}
		}
		// A pet rolled only off kill counts is priced the same way; what it may not
		// be is priced off nothing at all.
		if (sources.isEmpty() && kills.isEmpty())
		{
			return null;
		}
		return new SkillPet(str(o, "skill"), str(o, "activity"), str(o, "unit"),
			o.has("levelScaled") && o.get("levelScaled").getAsBoolean(), sources, kills);
	}

	private static String str(JsonObject o, String field)
	{
		return o.has(field) && o.get(field).isJsonPrimitive()
			? o.get(field).getAsString() : null;
	}

	/**
	 * A skilling pet weighed against its own activity. The odds are read at the level
	 * the player holds NOW, which is not the level each past attempt was made at: an
	 * account that levelled along the way rolled worse odds early than this prices
	 * them, so the figure runs a little dry. The panel says so where it prints it.
	 * Null when nothing has been counted, or when a level-scaled pet has no level to
	 * read: a guessed level is a guessed percentage.
	 */
	private PetChase skillingChase(String pet, SkillPet spec, Map<String, Long> counters,
		Map<String, long[]> skills, Map<String, Long> kcByNorm)
	{
		long level = 0;
		if (spec.levelScaled)
		{
			long[] sheet = skills != null && spec.skill != null
				? skills.get(spec.skill) : null;
			level = sheet != null && sheet.length > 0 ? sheet[0] : 0;
			if (level <= 0)
			{
				return null;
			}
			level = Math.min(level, LEVEL_CAP);
		}
		List<PetSource> sources = new ArrayList<>();
		long total = 0;
		double miss = 1.0;
		for (SkillSource s : spec.sources)
		{
			long n = Math.min(count(s, spec, counters), MAX_KC);
			long rate = spec.levelScaled ? s.base - 25L * level : s.base;
			if (n <= 0 || rate < MIN_RATE)
			{
				continue;
			}
			sources.add(new PetSource(s.name, n, rate));
			total += n;
			miss *= Math.pow(1.0 - 1.0 / rate, n);
		}
		for (SkillKill k : spec.kills)
		{
			long n = Math.min(killCount(k, kcByNorm), MAX_KC);
			long rate = spec.levelScaled && !k.flat ? k.base - 25L * level : k.base;
			if (n <= 0 || rate < MIN_RATE)
			{
				continue;
			}
			sources.add(new PetSource(k.name, n, rate));
			total += n;
			miss *= Math.pow(1.0 - 1.0 / rate, n);
		}
		if (sources.isEmpty())
		{
			return null;
		}
		sources.sort((a, b) -> Long.compare(b.kc, a.kc));
		double pct = Math.max(0.0, Math.min(100.0, (1.0 - miss) * 100.0));
		return new PetChase(pet, total, Math.round(pct * 10.0) / 10.0, sources,
			spec.activity, spec.unit, level);
	}

	// The kills behind one source. Where the log and the ledger spell the same event
	// two ways, the fuller record stands for it: the log is only as fresh as the last
	// time it was opened, and the two added together would count every kill twice.
	private static long killCount(SkillKill k, Map<String, Long> kcByNorm)
	{
		Long kc = kcByNorm.get(norm(k.kc));
		long n = kc != null ? kc : 0;
		for (String alt : k.orKc)
		{
			Long other = kcByNorm.get(norm(alt));
			if (other != null)
			{
				n = Math.max(n, other);
			}
		}
		return n;
	}

	// What the journal has counted for one source. A family sweep takes every key in
	// it except the ones held back by name: a failed pickpocket is spelled like a
	// pickpocket and rolls nothing.
	private static long count(SkillSource s, SkillPet spec, Map<String, Long> counters)
	{
		if (counters == null || counters.isEmpty())
		{
			return 0;
		}
		if (s.suffix != null)
		{
			long n = 0;
			for (Map.Entry<String, Long> e : counters.entrySet())
			{
				String k = e.getKey();
				if (!k.endsWith(s.suffix)
					|| (s.notSuffix != null && k.endsWith(s.notSuffix))
					|| spec.named.contains(k))
				{
					continue;
				}
				n += Math.max(0L, e.getValue() != null ? e.getValue() : 0L);
			}
			return n;
		}
		if (s.counter == null)
		{
			return 0;
		}
		long n = counters.getOrDefault(s.counter, 0L);
		for (String m : s.minus)
		{
			n -= counters.getOrDefault(m, 0L);
		}
		return Math.max(0L, n);
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
