/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle.panel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one table that makes every counter presentable: key → (label, family,
 * section) — a direct port of the site's settled tracker taxonomy (the
 * trackers page's COMBAT/LIVING_FLAT/LEDGER sets and SKILLS specs), so the
 * panel and any server page built from the same journal file, label and
 * sort every key the same way.
 *
 * <p>Four facets, the site's own: Living · Combat · Skilling · Ledger &amp;
 * Roads. Within Skilling each craft owns its keys by explicit claim first,
 * then typed suffix (longest first, so "FailedPickpockets" beats
 * "Pickpockets"). Generic totals ("floors" — logsChopped, bonesBuried) stay
 * OUT of the rows: they head their section, and the unresolved remainder
 * surfaces as a ghost "Other" row so rows always reconcile to the headline.
 */
public final class StatRegistry
{
	/** Display facets, in the site's tab order. */
	public static final String[] FAMILIES = {
		"Living", "Combat", "Skilling", "Ledger & Roads"
	};

	// ── ownership sets, ported verbatim from the site ──────────────────
	private static final Set<String> COMBAT = new HashSet<>(Arrays.asList(
		"damageDealt", "damageTaken", "highestHit", "highestHitTaken",
		"hitsMissed", "hitsBlocked", "deaths", "poisonDamageTaken", "venomDamageTaken",
		"specialAttacksUsed", "damageDealtMelee", "damageDealtRanged", "damageDealtMagic"));
	private static final Set<String> LIVING_FLAT = new HashSet<>(Arrays.asList(
		"foodEaten", "potionDoses", "beersDrunk", "vialsShattered",
		"hitpointsRegenerated", "divinePotionDamage", "potionsConsumedValue",
		"foodConsumedValue", "consumedValue"));
	private static final Set<String> LEDGER = new HashSet<>(Arrays.asList(
		"resourcesGatheredValue", "coinsFromAlchemy", "itemsDroppedValue",
		"itemsDiscarded", "examines", "coinsSpentAtShops", "coinsEarnedAtShops",
		"untakenLootValue", "untakenLootCount", "distanceWalked", "distanceRan",
		"ammoConsumed", "offensiveSpellsCast", "cabbagesPicked", "flaxGathered",
		"animalsPetted", "patchesRaked", "highAlchemyCasts", "lowAlchemyCasts"));
	// The site hides these outright: totals whose story other surfaces tell
	// (History owns xp; the offering-xp figures double-count real Prayer xp).
	// resourcesDroppedValue is kept out of the rows for the opposite reason — it
	// is not suppressed but promoted, read as the margin on the row
	// resourcesGatheredValue heads, where the two together say what neither says
	// alone. As its own row it would be an orphan gp figure with nothing to be
	// measured against, and would read as a second "Value dropped".
	private static final Set<String> HIDE = new HashSet<>(Arrays.asList(
		"totalXpGained", "bowsFletched", "demonicOfferingXp", "sinisterOfferingXp",
		"resourcesDroppedValue"));

	/** One craft's claim: explicit keys and floors first, then typed suffixes. */
	private static final class SkillSpec
	{
		final String name;
		final String[] suffixes;
		final String[] floors;
		final String[] keys;

		SkillSpec(String name, String[] suffixes, String[] floors, String[] keys)
		{
			this.name = name;
			this.suffixes = suffixes;
			this.floors = floors;
			this.keys = keys;
		}
	}

	private static final String[] NONE = {};
	// Canonical order, ported from the site's SKILLS array.
	private static final List<SkillSpec> SKILLS = Arrays.asList(
		new SkillSpec("Woodcutting", new String[]{"LogsChopped"}, new String[]{"logsChopped"}, NONE),
		new SkillSpec("Fishing", new String[]{"Caught"}, new String[]{"fishCaught"}, NONE),
		new SkillSpec("Cooking", new String[]{"Cooked"}, new String[]{"foodCooked"},
			new String[]{"foodBurned"}),
		new SkillSpec("Firemaking", new String[]{"LogsBurned"}, new String[]{"logsBurned"}, NONE),
		new SkillSpec("Mining", new String[]{"OreMined", "Mined"}, new String[]{"rocksMined"}, NONE),
		new SkillSpec("Smithing", new String[]{"BarsSmelted", "ItemsSmithed"}, NONE,
			new String[]{"itemsSmithed", "cannonballsSmithed"}),
		new SkillSpec("Herblore", NONE, NONE,
			new String[]{"herbsCleaned", "unfinishedPotionsMade", "potionsMade",
				"herbTarsMade", "weaponPoisonsMade"}),
		new SkillSpec("Fletching", new String[]{"LogsFletched"}, NONE,
			new String[]{"dartsFletched", "arrowsFletched", "boltsFletched", "boltsUnfinished",
				"javelinsFletched", "boltTips", "crossbowsStrung", "crossbowsUnstrung",
				"crossbowStocksCut", "bowsStrung", "logsFletched",
				"arrowShaftsFletched", "headlessArrowsFletched",
				"javelinShaftsFletched", "ballistaeFletched", "blowpipesFletched"}),
		new SkillSpec("Crafting", NONE, NONE,
			new String[]{"gemsCut", "glassBlown", "leatherCrafted", "dhideCrafted",
				"jewelleryCrafted", "potteryMade", "battlestavesCrafted", "itemsSpun",
				"moltenGlassMade", "snakeskinCrafted", "xericianCrafted", "silverCrafted",
				"amuletsStrung", "potteryFired", "birdHousesCrafted", "itemsWoven",
				"amethystCut"}),
		new SkillSpec("Runecraft", new String[]{"Runecrafted"}, new String[]{"runesCrafted"}, NONE),
		new SkillSpec("Agility", new String[]{"Laps", "Cleared"},
			new String[]{"agilityObstacles"},
			new String[]{"rooftopAgilityLaps", "normalAgilityLaps"}),
		new SkillSpec("Thieving",
			new String[]{"FailedPickpockets", "Pickpockets", "StallsThieved", "ChestsLooted"},
			new String[]{"pickPockets", "stallsThieved", "chestsLooted"},
			new String[]{"failedPickPockets", "safesCracked", "pyramidPlunderUrns"}),
		new SkillSpec("Farming", new String[]{"Harvested", "Checked"},
			new String[]{"farmingActions"},
			new String[]{"seedsPlanted"}),
		new SkillSpec("Hunter", new String[]{"Trapped", "BirdhousesEmptied"},
			new String[]{"creaturesTrapped", "birdhousesEmptied"},
			// herbiboarsHarvested is claimed explicitly so Farming's broad
			// "Harvested" sweep can't steal it — the implingsCaught precedent.
			new String[]{"implingsCaught", "chompyBirdsPlucked", "herbiboarsHarvested"}),
		new SkillSpec("Prayer",
			new String[]{"BonesBuried", "AshesScattered", "HeadsReanimated",
				"BonesSacrificed", "AshesSacrificed", "BonesOffered"},
			new String[]{"bonesBuried", "ashesScattered", "headsReanimated",
				"bonesOffered", "bonesSacrificed", "ashesSacrificed"},
			NONE),
		new SkillSpec("Construction", NONE, NONE, new String[]{"constructionBuilds"}));

	// Explicit claims resolve before any suffix sweep — a broad suffix
	// (Fishing "Caught") can never steal another craft's key (implingsCaught).
	private static final Map<String, String> KEY_SKILL = new HashMap<>();
	private static final Set<String> FLOORS = new HashSet<>();

	static
	{
		for (SkillSpec s : SKILLS)
		{
			for (String k : s.keys)
			{
				KEY_SKILL.put(k, s.name);
			}
			for (String k : s.floors)
			{
				KEY_SKILL.put(k, s.name);
				FLOORS.add(k);
			}
		}
		FLOORS.add("teleportsTotal");
		FLOORS.add("teleports");
	}

	private static final Map<String, String> LABELS = new HashMap<>();
	private static final Map<String, String> TELE_NAMES = new HashMap<>();

	static
	{
		// The site's LABELS table, plus the panel's own price-at-use counter.
		LABELS.put("hitpointsRegenerated", "Hitpoints regained");
		LABELS.put("divinePotionDamage", "Divine potion self-damage");
		LABELS.put("distanceWalked", "Distance walked");
		LABELS.put("distanceRan", "Distance run");
		LABELS.put("tilesWalked", "Tiles walked");
		LABELS.put("tilesRan", "Tiles run");
		LABELS.put("coinsFromAlchemy", "Coins from alchemy");
		LABELS.put("itemsDroppedValue", "Value dropped");
		// The site's own label for this key is "Resources gathered". The panel's
		// row carries the PAIR — what the hours produced and what was left where
		// it fell — and at 214px the sentence only fits if the label keeps the
		// verb and hands the rest of the line to the two figures.
		LABELS.put("resourcesGatheredValue", "Gathered");
		LABELS.put("untakenLootValue", "Uncollected loot");
		LABELS.put("untakenLootCount", "Loot left behind");
		LABELS.put("coinsSpentAtShops", "Spent at shops");
		LABELS.put("coinsEarnedAtShops", "Earned at shops");
		LABELS.put("offensiveSpellsCast", "Offensive casts");
		LABELS.put("ammoConsumed", "Ammunition spent");
		LABELS.put("foodEaten", "Meals eaten");
		LABELS.put("potionDoses", "Doses drunk");
		LABELS.put("highestHit", "Highest hit");
		LABELS.put("highestHitTaken", "Highest hit taken");
		LABELS.put("specialAttacksUsed", "Specials spent");
		LABELS.put("consumedValue", "Consumed value");
		LABELS.put("damageDealtMelee", "— by melee");
		LABELS.put("damageDealtRanged", "— by ranged");
		LABELS.put("damageDealtMagic", "— by magic");
		LABELS.put("teleportsFairyRing", "— by fairy ring");
		LABELS.put("teleportsSpiritTree", "— by spirit tree");

		// Destinations are place names: Title Case, with the punctuation the
		// camelCase split can't recover — the site's TELE_NAMES, verbatim.
		TELE_NAMES.put("teleportsSeersVillage", "Seers' Village");
		TELE_NAMES.put("teleportsFenkenstrain", "Fenkenstrain's Castle");
		TELE_NAMES.put("teleportsFortis", "Civitas illa Fortis");
		TELE_NAMES.put("teleportsGrandExchange", "Grand Exchange");
		TELE_NAMES.put("teleportsWarriorsGuild", "Warriors' Guild");
		TELE_NAMES.put("teleportsLegendsGuild", "Legends' Guild");
		TELE_NAMES.put("teleportsOttosGrotto", "Otto's Grotto");
		TELE_NAMES.put("teleportsDiaryRegion", "Achievement Diary");
		TELE_NAMES.put("teleportsFalo", "Falo the Bard");
		TELE_NAMES.put("teleportsPandemonium", "The Pandemonium");
		TELE_NAMES.put("teleportsEmirsArena", "Emir's Arena");
		TELE_NAMES.put("teleportsChampionsGuild", "Champions' Guild");
		TELE_NAMES.put("teleportsWizardsTower", "Wizards' Tower");
		TELE_NAMES.put("teleportsTearsOfGuthix", "Tears of Guthix");
		TELE_NAMES.put("teleportsTheOutpost", "The Outpost");
		TELE_NAMES.put("teleportsSlayerDungeons", "Slayer dungeons");
		TELE_NAMES.put("teleportsColosseum", "Fortis Colosseum");
		TELE_NAMES.put("teleportsDondakansRock", "Dondakan's Rock");
		TELE_NAMES.put("teleportsEaglesEyrie", "Eagle's Eyrie");
		TELE_NAMES.put("teleportsGiantsFoundry", "Giants' Foundry");
		TELE_NAMES.put("teleportsKharedst", "Kourend (Memoirs)");
	}

	private StatRegistry()
	{
	}

	/** Keys the panel never shows: diagnostics, plus the site's HIDE set. */
	public static boolean hidden(String key)
	{
		return key.startsWith("_") || HIDE.contains(key);
	}

	/**
	 * Floors are generic totals (logsChopped, bonesBuried, teleportsTotal):
	 * they head their section rather than appearing as rows, and their
	 * unresolved remainder reconciles as the ghost "Other" row.
	 */
	public static boolean isFloor(String key)
	{
		return FLOORS.contains(key);
	}

	/** The craft that owns a key, or null: explicit claim, then suffix. */
	public static String skillOf(String key)
	{
		String claimed = KEY_SKILL.get(key);
		if (claimed != null)
		{
			return claimed;
		}
		if (key.endsWith("Value") || COMBAT.contains(key))
		{
			return null;   // value keys and damage are never actions
		}
		for (SkillSpec s : SKILLS)
		{
			for (String suf : s.suffixes)
			{
				if (key.endsWith(suf) && !key.equals(suf))
				{
					return s.name;
				}
			}
		}
		return null;
	}

	/** The facet a key files under, one of {@link #FAMILIES}. */
	public static String family(String key)
	{
		if (LIVING_FLAT.contains(key))
		{
			return "Living";
		}
		if (COMBAT.contains(key))
		{
			return "Combat";
		}
		if (LEDGER.contains(key))
		{
			return "Ledger & Roads";
		}
		if (key.endsWith("Value") || key.startsWith("coins"))
		{
			return "Ledger & Roads";
		}
		if (skillOf(key) != null)
		{
			return "Skilling";
		}
		if (key.endsWith("Eaten") || key.endsWith("Doses") || key.contains("Drunk")
			|| key.startsWith("food") || key.startsWith("potion") || key.startsWith("vials"))
		{
			return "Living";
		}
		if (key.startsWith("teleports") || key.startsWith("tiles") || key.startsWith("distance"))
		{
			return "Ledger & Roads";
		}
		// Everything unclaimed is an odd or an end — visible, never a junk tab.
		return "Ledger & Roads";
	}

	/**
	 * The section within a facet. Skilling → the craft; Living → Food /
	 * Potions / "" (flat); Ledger &amp; Roads → The purse / On foot /
	 * Teleports / Destinations / Odds &amp; ends. Empty string = the facet's
	 * flat top list.
	 */
	public static String subgroup(String key)
	{
		String fam = family(key);
		if (fam.equals("Skilling"))
		{
			String skill = skillOf(key);
			return skill != null ? skill : "";
		}
		if (fam.equals("Living"))
		{
			if (LIVING_FLAT.contains(key))
			{
				return "";
			}
			if (key.endsWith("Eaten"))
			{
				return "Food";
			}
			if (key.endsWith("Doses"))
			{
				return "Potions";
			}
			return "";
		}
		if (fam.equals("Ledger & Roads"))
		{
			// Fairy rings and spirit trees are transport NETWORKS — a means,
			// not a place — so they sit with the means, not the destinations.
			if (key.startsWith("teleportsVia") || key.equals("teleportsTotal")
				|| key.equals("teleports") || key.equals("teleportsFairyRing")
				|| key.equals("teleportsSpiritTree"))
			{
				return "Teleports";
			}
			if (key.startsWith("teleports"))
			{
				return "Destinations";
			}
			if (key.startsWith("tiles") || key.startsWith("distance"))
			{
				return "On foot";
			}
			if (isGp(key))
			{
				return "The purse";
			}
			return "Odds & ends";
		}
		return "";
	}

	/** The floor keys whose sum heads a section (empty for floorless ones). */
	public static List<String> floorKeys(String subgroup)
	{
		for (SkillSpec s : SKILLS)
		{
			if (s.name.equals(subgroup))
			{
				return Arrays.asList(s.floors);
			}
		}
		switch (subgroup)
		{
			case "Teleports":
				return Arrays.asList("teleportsTotal", "teleports");
			case "Food":
				return java.util.Collections.singletonList("foodEaten");
			case "Potions":
				return java.util.Collections.singletonList("potionDoses");
			default:
				return java.util.Collections.emptyList();
		}
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
			return teleName(key);
		}
		return prettify(key);
	}

	/**
	 * The label a key takes as a ROW inside its section: typed keys shed
	 * their verb ("willowLogsChopped" reads "Willow logs" under WOODCUTTING),
	 * destinations read as place names, everything else falls to label().
	 */
	public static String rowLabel(String key)
	{
		String skill = skillOf(key);
		if (skill != null && !KEY_SKILL.containsKey(key))
		{
			// matched by suffix — find it (longest wins within the owning craft)
			for (SkillSpec s : SKILLS)
			{
				if (!s.name.equals(skill))
				{
					continue;
				}
				for (String suf : s.suffixes)
				{
					if (key.endsWith(suf) && !key.equals(suf))
					{
						return typedName(key, suf);
					}
				}
			}
		}
		if (family(key).equals("Living") && !LIVING_FLAT.contains(key))
		{
			if (key.endsWith("Eaten"))
			{
				return typedName(key, "Eaten");
			}
			if (key.endsWith("Doses"))
			{
				return typedName(key, "Doses");
			}
		}
		return label(key);
	}

	/** Title-Case place name for a destination key, punctuation restored. */
	private static String teleName(String key)
	{
		String fixed = TELE_NAMES.get(key);
		if (fixed != null)
		{
			return fixed;
		}
		String s = prettify(key.substring("teleports".length()));
		// Title Case each word — destinations are proper nouns.
		StringBuilder out = new StringBuilder(s.length());
		boolean cap = true;
		for (char c : s.toCharArray())
		{
			out.append(cap && Character.isLetter(c) ? Character.toUpperCase(c) : c);
			cap = c == ' ';
		}
		return out.toString();
	}

	/** Strip a typed key's verb suffix: "willowLogsChopped" → "Willow logs". */
	private static String typedName(String key, String suffix)
	{
		String base = key.substring(0, key.length() - suffix.length());
		String s = prettify(base).replaceAll("(?i)\\(?level\\s*\\d+\\)?", " ")
			.replaceAll("[()]", " ").replaceAll("\\s+", " ").trim();
		return s.isEmpty() ? label(key) : s;
	}

	/**
	 * The typed suffix a key matched within its craft, or null for explicit
	 * claims and non-typed keys. Drives the second drill level: a craft whose
	 * typed rows span SEVERAL verbs (Prayer: buried · scattered · ensouled)
	 * folds each verb into its own sub-section.
	 */
	public static String suffixOf(String key)
	{
		if (KEY_SKILL.containsKey(key))
		{
			return null;
		}
		String skill = skillOf(key);
		if (skill == null)
		{
			return null;
		}
		for (SkillSpec s : SKILLS)
		{
			if (!s.name.equals(skill))
			{
				continue;
			}
			for (String suf : s.suffixes)
			{
				if (key.endsWith(suf) && !key.equals(suf))
				{
					return suf;
				}
			}
		}
		return null;
	}

	/** Reading label for a suffix group: "BonesBuried" → "Bones buried". */
	public static String suffixLabel(String suffix)
	{
		switch (suffix)
		{
			case "HeadsReanimated":
				return "Ensouled heads";
			case "OreMined":
				return "Ores mined";
			default:
				return prettify(Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1));
		}
	}

	/** The floor key heading one suffix group, or null when the craft doesn't
	 *  declare one for it. Convention is the decapitalised suffix; the
	 *  irregulars are mapped. */
	public static String suffixFloor(String craft, String suffix)
	{
		String cand;
		switch (suffix)
		{
			case "Caught":
				cand = "fishCaught";
				break;
			case "Mined":
			case "OreMined":
				cand = "rocksMined";
				break;
			case "Runecrafted":
				cand = "runesCrafted";
				break;
			case "Trapped":
				cand = "creaturesTrapped";
				break;
			case "Pickpockets":
				cand = "pickPockets";
				break;
			case "Cooked":
				cand = "foodCooked";
				break;
			default:
				cand = Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
		}
		for (SkillSpec s : SKILLS)
		{
			if (!s.name.equals(craft))
			{
				continue;
			}
			for (String f : s.floors)
			{
				if (f.equals(cand))
				{
					return f;
				}
			}
		}
		return null;
	}

	/**
	 * Typed keys are the per-resource ones matched by suffix ("willowLogsChopped",
	 * "sharkEaten") — the rows a floor reconciles against. Explicit extras
	 * (foodBurned, implingsCaught) ride in their section but stay out of the
	 * ghost-"Other" arithmetic, exactly as on the site.
	 */
	public static boolean typed(String key)
	{
		if (KEY_SKILL.containsKey(key))
		{
			return false;
		}
		if (skillOf(key) != null)
		{
			return true;
		}
		return family(key).equals("Living") && !LIVING_FLAT.contains(key)
			&& (key.endsWith("Eaten") || key.endsWith("Doses"));
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

	/** The site's row order: count desc, then name — deterministic ties. */
	public static int compareRows(Map.Entry<String, Long> a, Map.Entry<String, Long> b)
	{
		int byValue = Long.compare(b.getValue(), a.getValue());
		return byValue != 0 ? byValue
			: rowLabel(a.getKey()).compareToIgnoreCase(rowLabel(b.getKey()));
	}

	/** Convenience: all section names a facet renders, in display order.
	 *  Skilling's crafts order dynamically by total; the rest are fixed. */
	public static List<String> fixedSections(String family)
	{
		switch (family)
		{
			case "Living":
				return Arrays.asList("", "Food", "Potions");
			case "Ledger & Roads":
				return Arrays.asList("The purse", "On foot", "Teleports", "Destinations", "Odds & ends");
			default:
				return new ArrayList<>(java.util.Collections.singletonList(""));
		}
	}

	/** Skill sections in canonical order (panel ranks by total at render). */
	public static List<String> skillNames()
	{
		List<String> out = new ArrayList<>(SKILLS.size());
		for (SkillSpec s : SKILLS)
		{
			out.add(s.name);
		}
		return out;
	}
}
