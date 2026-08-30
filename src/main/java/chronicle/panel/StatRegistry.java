/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle.panel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
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
 *
 * <p>Five families, everything filed: offerings live under Skilling as the
 * Prayer craft (that is what they are in game), travel takes the distance
 * counters, economy takes every gp figure, and anything unrecognised lands
 * visibly in Living → Elsewhere rather than in a dumping-ground tab.
 */
public final class StatRegistry
{
	/** Display families, in tab-pill order. */
	public static final String[] FAMILIES = {
		"Combat", "Skilling", "Travel", "Living", "Economy"
	};

	private static final Map<String, String> LABELS = new HashMap<>();
	private static final Set<String> COMBAT = new HashSet<>(Arrays.asList(
		"damageDealt", "damageDealtMelee", "damageDealtRanged", "damageDealtMagic",
		"damageTaken", "highestHit", "highestHitTaken", "deaths", "hitsMissed",
		"hitsBlocked", "specialAttacksUsed", "poisonDamageTaken", "venomDamageTaken",
		"ammoConsumed", "hitpointsRegenerated", "divinePotionDamage"));
	private static final Set<String> ECONOMY = new HashSet<>(Arrays.asList(
		"coinsFromAlchemy", "coinsSpentAtShops", "coinsEarnedAtShops",
		"itemsDroppedValue", "highAlchemyCasts", "lowAlchemyCasts",
		"itemsDiscarded", "untakenLootCount"));

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
		LABELS.put("distanceRan", "Distance run");
		LABELS.put("coinsFromAlchemy", "Coins from alchemy");
		LABELS.put("specialAttacksUsed", "Specials spent");
		LABELS.put("consumedValue", "Consumed value");
		LABELS.put("totalXpGained", "Total xp gained");
		LABELS.put("untakenLootCount", "Loot left behind");
	}

	private StatRegistry()
	{
	}

	/** Diagnostic keys the panel never shows (leading underscore convention). */
	public static boolean hidden(String key)
	{
		return key.startsWith("_");
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

	/** True when a key belongs to the Prayer craft: offerings in every form. */
	private static boolean isPrayer(String key)
	{
		return key.contains("Buried") || key.contains("Offered")
			|| key.contains("Offering") || key.contains("offering")
			|| key.contains("Scattered") || key.contains("Sacrificed")
			|| key.contains("Reanimated") || key.equals("prayersActivated")
			|| key.equals("headsReanimated");
	}

	/** The family a key files under, one of {@link #FAMILIES}. */
	public static String family(String key)
	{
		if (key.equals("consumedValue"))
		{
			return "Living";
		}
		if (COMBAT.contains(key))
		{
			return "Combat";
		}
		if (ECONOMY.contains(key) || key.endsWith("Value") || key.startsWith("coins"))
		{
			return "Economy";
		}
		if (isPrayer(key) || key.equals("totalXpGained"))
		{
			return "Skilling";
		}
		if (key.startsWith("teleports") || key.startsWith("tiles")
			|| key.startsWith("distance")
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
			|| key.endsWith("Harvested") || key.endsWith("Thieved")
			|| key.endsWith("Smithed") || key.endsWith("Fletched") || key.endsWith("Runecrafted")
			|| key.toLowerCase(Locale.ROOT).contains("pickpocket")
			|| key.startsWith("agility") || key.endsWith("Smelted"))
		{
			return "Skilling";
		}
		// Unrecognised keys land somewhere visible, not in a junk tab.
		return "Living";
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
			if (key.equals("totalXpGained"))
			{
				return "";   // headerless, tops the tab by value
			}
			if (isPrayer(key))
			{
				return "Prayer";
			}
			String kl = key.toLowerCase(Locale.ROOT);
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
			if (key.endsWith("Thieved") || kl.contains("pickpocket"))
			{
				return "Thieving";
			}
			if (key.endsWith("Harvested"))
			{
				return "Farming";
			}
			if (key.endsWith("Smithed") || key.endsWith("Smelted"))
			{
				return "Smithing";
			}
			if (key.endsWith("Fletched"))
			{
				return "Fletching";
			}
			if (key.startsWith("agility"))
			{
				return "Agility";
			}
			if (key.endsWith("Crafted") || key.endsWith("Runecrafted"))
			{
				return "Runecraft";
			}
			return "Elsewhere";
		}
		if (fam.equals("Travel"))
		{
			if (key.startsWith("tiles") || key.startsWith("distance"))
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
		if (fam.equals("Living"))
		{
			if (key.contains("Eaten") || key.startsWith("food"))
			{
				return "Food";
			}
			if (key.endsWith("Doses") || key.startsWith("potion") || key.startsWith("vials")
				|| key.contains("Drunk"))
			{
				return "Potions";
			}
			if (key.equals("consumedValue"))
			{
				return "";
			}
			return "Elsewhere";
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
		return polish(out.toString());
	}

	/**
	 * Reads-well pass over a prettified label: collapse stuttered words
	 * ("Logs logs chopped" — item-plus-action keys double up), space out
	 * parenthesised levels from NPC-name keys ("Guard(level21)" reads as
	 * "Guard (lvl 21)").
	 */
	private static String polish(String label)
	{
		String s = label.replaceAll("(?<=\\S)\\(", " (")
			.replaceAll("\\(level ?(\\d+)\\)", "(lvl $1)");
		String[] words = s.split(" ");
		StringBuilder out = new StringBuilder(s.length());
		String prev = null;
		for (String w : words)
		{
			if (prev != null && w.equalsIgnoreCase(prev))
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append(' ');
			}
			out.append(w);
			prev = w;
		}
		return out.toString();
	}
}
