/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle.panel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The one table that makes every counter presentable: key → (label, family).
 *
 * <p>This registry drives the Stats tab, the search index and Home's strip at
 * once — a tracker that exists in the model appears on every surface with no
 * panel code, which is what keeps "comprehensive" from rotting as counters are
 * added. Labels fall back to a camelCase→words prettifier, so even a key this
 * class has never heard of renders acceptably; explicit entries exist only
 * where the prettifier would read wrong.
 */
public final class StatRegistry
{
	/** Display families, in tab-pill order. */
	public static final String[] FAMILIES = {
		"Combat", "Travel", "Living", "Skilling", "Economy", "Offerings", "Other"
	};

	private static final Map<String, String> LABELS = new HashMap<>();
	private static final Set<String> COMBAT = new HashSet<>(Arrays.asList(
		"damageDealt", "damageDealtMelee", "damageDealtRanged", "damageDealtMagic",
		"damageTaken", "highestHit", "highestHitTaken", "deaths", "hitsMissed",
		"hitsBlocked", "specialAttacksUsed", "poisonDamageTaken", "venomDamageTaken"));
	private static final Set<String> ECONOMY = new HashSet<>(Arrays.asList(
		"coinsFromAlchemy", "coinsSpentAtShops", "coinsEarnedAtShops",
		"itemsDroppedValue", "highAlchemyCasts", "lowAlchemyCasts"));
	private static final Set<String> OFFERINGS = new HashSet<>(Arrays.asList(
		"bonesBuried", "bonesOffered", "ashesScattered", "prayersActivated",
		"headsReanimated"));

	static
	{
		// Only where the prettifier misleads; everything else falls through.
		LABELS.put("damageDealtMelee", "— by melee");
		LABELS.put("damageDealtRanged", "— by ranged");
		LABELS.put("damageDealtMagic", "— by magic");
		LABELS.put("highestHit", "Highest hit");
		LABELS.put("highestHitTaken", "Highest hit taken");
		LABELS.put("tilesWalked", "Tiles walked");
		LABELS.put("tilesRan", "Tiles run");
		LABELS.put("coinsFromAlchemy", "Coins from alchemy");
		LABELS.put("specialAttacksUsed", "Specials spent");
	}

	private StatRegistry()
	{
	}

	/** Human label for a counter key. Never null; unknown keys prettify. */
	public static String label(String key)
	{
		String explicit = LABELS.get(key);
		if (explicit != null)
		{
			return explicit;
		}
		if (key.startsWith("teleportsVia"))
		{
			return "— by " + key.substring("teleportsVia".length()).toLowerCase();
		}
		if (key.equals("teleportsTotal") || key.equals("teleports"))
		{
			return "Teleports";
		}
		if (key.startsWith("teleports") && key.length() > "teleports".length())
		{
			// Destination name, bare — the client's pixel font has no arrow
			// glyph, and the Travel family already supplies the context.
			return prettify(key.substring("teleports".length()));
		}
		return prettify(key);
	}

	/** The family a key files under, one of {@link #FAMILIES}. */
	public static String family(String key)
	{
		if (COMBAT.contains(key))
		{
			return "Combat";
		}
		if (ECONOMY.contains(key) || key.endsWith("Value") || key.startsWith("coins"))
		{
			return "Economy";
		}
		if (OFFERINGS.contains(key))
		{
			return "Offerings";
		}
		if (key.startsWith("teleports") || key.startsWith("tiles")
			|| key.contains("Fairy") || key.contains("Spirit"))
		{
			return "Travel";
		}
		if (key.endsWith("Doses") || key.startsWith("food") || key.startsWith("potion")
			|| key.startsWith("vials") || key.contains("Eaten") || key.contains("Drunk"))
		{
			return "Living";
		}
		if (key.endsWith("Chopped") || key.endsWith("Caught") || key.endsWith("Cooked")
			|| key.endsWith("Mined") || key.endsWith("Fished") || key.endsWith("Crafted")
			|| key.endsWith("Burned") || key.endsWith("Trapped") || key.endsWith("Plucked")
			|| key.endsWith("Harvested") || key.endsWith("Pickpockets") || key.endsWith("Thieved"))
		{
			return "Skilling";
		}
		return "Other";
	}

	/**
	 * A sub-header within a family — the craft or the method — so big families
	 * read as clustered sections rather than one interleaved list. Empty means
	 * "no sub-header" (small families render flat).
	 */
	public static String subgroup(String key)
	{
		String fam = family(key);
		if (fam.equals("Skilling"))
		{
			String kl = key.toLowerCase(java.util.Locale.ROOT);
			if (kl.contains("impling") || kl.contains("moth") || kl.contains("salamander")
				|| kl.contains("chompy") || key.endsWith("Trapped") || key.endsWith("Plucked")
				|| kl.contains("lizard"))
			{
				return "Hunter";
			}
			if (key.endsWith("Cooked"))
			{
				return "Cooking";
			}
			if (key.endsWith("Chopped"))
			{
				return "Woodcutting";
			}
			if (key.endsWith("Burned"))
			{
				return "Firemaking";
			}
			if (key.endsWith("Mined"))
			{
				return "Mining";
			}
			if (key.endsWith("Caught") || key.endsWith("Fished"))
			{
				return "Fishing";
			}
			if (key.endsWith("Pickpockets") || key.endsWith("Thieved") || kl.contains("pickpocket"))
			{
				return "Thieving";
			}
			if (key.endsWith("Harvested"))
			{
				return "Farming";
			}
			if (key.endsWith("Crafted"))
			{
				return "Runecraft";
			}
			return "Elsewhere";
		}
		if (fam.equals("Travel"))
		{
			if (key.startsWith("tiles"))
			{
				return "On foot";
			}
			if (key.startsWith("teleportsVia") || key.equals("teleportsTotal") || key.equals("teleports"))
			{
				return "Teleports";
			}
			if (key.startsWith("teleports"))
			{
				return "Destinations";
			}
			return "";
		}
		return "";
	}

	/** Whether a value renders as gp. */
	public static boolean isGp(String key)
	{
		return key.endsWith("Value") || key.startsWith("coins");
	}

	/** camelCase → "Sentence case words"; digits kept, acronyms tolerated. */
	public static String prettify(String key)
	{
		StringBuilder out = new StringBuilder(key.length() + 8);
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			if (i == 0)
			{
				out.append(Character.toUpperCase(c));
			}
			else if (Character.isUpperCase(c))
			{
				out.append(' ').append(Character.toLowerCase(c));
			}
			else
			{
				out.append(c);
			}
		}
		return out.toString();
	}
}
