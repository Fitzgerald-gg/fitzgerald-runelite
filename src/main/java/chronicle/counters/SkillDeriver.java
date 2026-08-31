/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * The LOCAL skilling resolver: turns each raw action tuple
 * {@code SKILL|xpDelta|objId|itemId|qty|targetName|consumedId} into typed
 * counters at the moment of capture — a faithful port of the server's
 * {@code _skill_derive_action}, reading the same bundled reference tables
 * ({@code osrs_skill_xp.json}, {@code osrs_skill_item_rules.json},
 * {@code osrs_object_species.json}).
 *
 * <p>This is what makes "local-first" true for skilling: a player with cloud
 * sync off gets every typed counter (shafts cut, gems cut, laps run) the
 * instant they act. For cloud users the server still derives its own copy
 * from the same tuples; the two reconcile by floor-merge and, deriving from
 * identical inputs with identical rules, agree. Locally derived keys are
 * server-owned for PUSH purposes ({@link StatStore#pushable()} filters them)
 * so this derivation is never echoed onto the server's.
 *
 * <p>One local advantage: item names resolve through {@link ItemManager}
 * (every id, untradeables included) rather than the server's tradeable-only
 * mapping. Tuples this port can't resolve simply count their generic floor —
 * the server's unmapped log remains the discovery funnel for cloud users.
 */
@Slf4j
@Singleton
public class SkillDeriver
{
	private static final Map<String, String> SMITH_ORE_ALIAS = new HashMap<>();
	private static final Set<String> SMITH_METALS = new HashSet<>(Arrays.asList(
		"bronze", "iron", "steel", "silver", "gold", "mithril",
		"adamant", "adamantite", "rune", "runite", "black", "blurite"));
	private static final Set<String> MINING_ROCKS = new HashSet<>(Arrays.asList(
		"coal", "clay", "limestone", "amethyst", "pure essence", "pay-dirt", "pay dirt",
		"volcanic ash", "barronite shards", "barronite deposit",
		"basalt", "urt salt", "efh salt", "te salt", "daeyalt shard",
		"dense essence block"));
	private static final Set<String> FISH_NORAW = new HashSet<>(Arrays.asList(
		"minnow", "minnows", "karambwanji"));
	private static final Map<String, String> ITEM_ALIASES = new HashMap<>();
	private static final Set<String> PRODUCTION = new HashSet<>(Arrays.asList(
		"FLETCHING", "CRAFTING", "HERBLORE", "HUNTER"));
	// gathering: generic floor key + typed family suffix
	private static final Map<String, String[]> GATHERING = new HashMap<>();
	// net-trap species by exact catch xp (merged multi-trap deltas match ×n)
	private static final double[][] NET_TRAP_BASES = {
		{152.0, 0}, {224.0, 1}, {272.0, 2}, {319.2, 3}, {344.0, 4}};
	private static final String[] NET_TRAP_KEYS = {
		"swampLizardsTrapped", "orangeSalamandersTrapped", "redSalamandersTrapped",
		"blackSalamandersTrapped", "tecuSalamandersTrapped"};
	private static final int NET_TRAP_MAX = 6;
	private static final Map<Integer, String> ENSOULED_REANIM_XP = new HashMap<>();
	private static final Map<String, Double> PRAYER_BASE_XP = new HashMap<>();
	private static final double[] PRAYER_PASSIVE_BASES = {4.5, 10, 25, 65, 85, 110};
	private static final Map<String, String> HUNTER_ITEM_SPECIES = new HashMap<>();
	private static final Map<String, String> BUTTERFLY_TARGETS = new HashMap<>();

	static
	{
		SMITH_ORE_ALIAS.put("adamant", "adamantite");
		SMITH_ORE_ALIAS.put("rune", "runite");
		ITEM_ALIASES.put("shrimps", "shrimp");
		ITEM_ALIASES.put("minnows", "minnow");
		ITEM_ALIASES.put("harpoonfish", "harpoonFish");
		ITEM_ALIASES.put("anchovy", "anchovies");
		GATHERING.put("WOODCUTTING", new String[]{"logsChopped", "LogsChopped"});
		GATHERING.put("MINING", new String[]{"rocksMined", "Mined"});
		GATHERING.put("FISHING", new String[]{"fishCaught", "Caught"});
		GATHERING.put("COOKING", new String[]{"foodCooked", "Cooked"});
		int[][] reanim = {{130, 0}};
		String[][] reanimPairs = {
			{"130", "goblin"}, {"182", "monkey"}, {"286", "imp"}, {"364", "minotaur"},
			{"454", "scorpion"}, {"480", "bear"}, {"494", "unicorn"}, {"520", "dog"},
			{"584", "chaosDruid"}, {"650", "giant"}, {"716", "ogre"}, {"754", "elf"},
			{"780", "troll"}, {"832", "horror"}, {"884", "kalphite"}, {"936", "dagannoth"},
			{"1040", "bloodveld"}, {"1104", "tzhaar"}, {"1170", "demon"},
			{"1200", "hellhound"}, {"1234", "aviansie"}, {"1300", "abyssal"},
			{"1560", "dragon"}};
		for (String[] p : reanimPairs)
		{
			ENSOULED_REANIM_XP.put(Integer.parseInt(p[0]), p[1]);
		}
		// ashes (scatter base); "" = plain Ashes. bones (bury base).
		PRAYER_BASE_XP.put("", 10.0);
		PRAYER_BASE_XP.put("fiendish", 10.0);
		PRAYER_BASE_XP.put("vile", 25.0);
		PRAYER_BASE_XP.put("malicious", 65.0);
		PRAYER_BASE_XP.put("abyssal", 85.0);
		PRAYER_BASE_XP.put("infernal", 110.0);
		PRAYER_BASE_XP.put("normal", 4.5);
		PRAYER_BASE_XP.put("wolf", 4.5);
		PRAYER_BASE_XP.put("burnt", 4.5);
		PRAYER_BASE_XP.put("monkey", 5.0);
		PRAYER_BASE_XP.put("bat", 5.3);
		PRAYER_BASE_XP.put("big", 15.0);
		PRAYER_BASE_XP.put("jogre", 15.0);
		PRAYER_BASE_XP.put("zogre", 22.5);
		PRAYER_BASE_XP.put("shaikahan", 25.0);
		PRAYER_BASE_XP.put("babydragon", 30.0);
		PRAYER_BASE_XP.put("wyrm", 50.0);
		PRAYER_BASE_XP.put("dragon", 72.0);
		PRAYER_BASE_XP.put("wyvern", 72.0);
		PRAYER_BASE_XP.put("drake", 80.0);
		PRAYER_BASE_XP.put("fayrg", 84.0);
		PRAYER_BASE_XP.put("lavaDragon", 85.0);
		PRAYER_BASE_XP.put("raurg", 96.0);
		PRAYER_BASE_XP.put("hydra", 110.0);
		PRAYER_BASE_XP.put("dagannoth", 125.0);
		PRAYER_BASE_XP.put("ourg", 140.0);
		PRAYER_BASE_XP.put("superiorDragon", 150.0);
		String[][] species = {
			{"Chinchompa", "greyChinchompasTrapped"},
			{"Red chinchompa", "redChinchompasTrapped"},
			{"Black chinchompa", "blackChinchompasTrapped"},
			{"Ferret", "ferretsTrapped"},
			{"Jerboa tail", "embertailedJerboasTrapped"},
			{"Kebbit claws", "wildKebbitsTrapped"},
			{"Barb-tail harpoon", "barbTailedKebbitsTrapped"},
			{"Kebbit spike", "pricklyKebbitsTrapped"},
			{"Kebbit teeth", "sabreToothedKebbitsTrapped"},
			{"Fox fur", "pyreFoxesTrapped"},
			{"Damaged monkey tail", "maniacalMonkeysTrapped"},
			{"Spotted kebbit fur", "spottedKebbitsTrapped"},
			{"Dark kebbit fur", "darkKebbitsTrapped"},
			{"Dashing kebbit fur", "dashingKebbitsTrapped"},
			{"Polar kebbit fur", "polarKebbitsTrapped"},
			{"Common kebbit fur", "commonKebbitsTrapped"},
			{"Feldip weasel fur", "feldipWeaselsTrapped"},
			{"Desert devil fur", "desertDevilsTrapped"},
			{"Long kebbit spike", "razorBackedKebbitsTrapped"},
			{"Larupia fur", "spinedLarupiasTrapped"},
			{"Tatty larupia fur", "spinedLarupiasTrapped"},
			{"Graahk fur", "hornedGraahksTrapped"},
			{"Tatty graahk fur", "hornedGraahksTrapped"},
			{"Kyatt fur", "sabreToothedKyattsTrapped"},
			{"Tatty kyatt fur", "sabreToothedKyattsTrapped"},
			{"Sunlight antelope antler", "sunlightAntelopesTrapped"},
			{"Sunlight antelope fur", "sunlightAntelopesTrapped"},
			{"Raw sunlight antelope", "sunlightAntelopesTrapped"},
			{"Moonlight antelope antler", "moonlightAntelopesTrapped"},
			{"Moonlight antelope fur", "moonlightAntelopesTrapped"},
			{"Raw moonlight antelope", "moonlightAntelopesTrapped"},
			{"Red feather", "crimsonSwiftsTrapped"},
			{"Yellow feather", "goldenWarblersTrapped"},
			{"Orange feather", "copperLongtailsTrapped"},
			{"Blue feather", "ceruleanTwitchesTrapped"},
			{"Stripy feather", "tropicalWagtailsTrapped"},
			{"Ruby harvest", "rubyHarvestsTrapped"},
			{"Sapphire glacialis", "sapphireGlacialisTrapped"},
			{"Snowy knight", "snowyKnightsTrapped"},
			{"Black warlock", "blackWarlocksTrapped"},
			{"Moonlight moth", "moonlightMothsTrapped"},
			{"Sunlight moth", "sunlightMothsTrapped"}};
		for (String[] p : species)
		{
			HUNTER_ITEM_SPECIES.put(p[0], p[1]);
		}
		BUTTERFLY_TARGETS.put("ruby harvest", "rubyHarvestsTrapped");
		BUTTERFLY_TARGETS.put("sapphire glacialis", "sapphireGlacialisTrapped");
		BUTTERFLY_TARGETS.put("snowy knight", "snowyKnightsTrapped");
		BUTTERFLY_TARGETS.put("black warlock", "blackWarlocksTrapped");
	}

	private final ItemManager itemManager;
	private final StatStore statStore;
	private final Gson gson;

	private Map<String, Map<String, String>> xpTable;
	private Map<String, List<Rule>> itemRules;
	private Map<String, String> objTable;

	private static final class Rule
	{
		String match;
		String value;
		Set<String> valueSet;
		Pattern regex;
		String key;
		boolean qty;
	}

	@Inject
	SkillDeriver(ItemManager itemManager, StatStore statStore, Gson gson)
	{
		this.itemManager = itemManager;
		this.statStore = statStore;
		this.gson = gson;
	}

	/** Derive a tuple's counters and fold them into the stat store. */
	void apply(String tuple)
	{
		try
		{
			List<Map.Entry<String, Integer>> pairs = derive(tuple);
			if (pairs != null)
			{
				for (Map.Entry<String, Integer> p : pairs)
				{
					if (p.getValue() > 0)
					{
						statStore.incrementStatBy(p.getKey(), p.getValue());
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("local derive failed for {}", tuple, e);
		}
	}

	List<Map.Entry<String, Integer>> derive(String tuple)
	{
		String[] parts = (tuple == null ? "" : tuple).split("\\|", -1);
		if (parts.length < 4)
		{
			return null;
		}
		String skill = parts[0];
		String xpStr = parts[1];
		String objId = parts[2];
		String itemId = parts[3];
		int qty = 1;
		if (parts.length >= 5 && !parts[4].isEmpty())
		{
			try
			{
				qty = Math.max(1, Integer.parseInt(parts[4]));
			}
			catch (NumberFormatException ignored)
			{
				qty = 1;
			}
		}
		String target = parts.length >= 6 ? parts[5] : "";
		String consumedId = parts.length >= 7 ? parts[6] : "";

		// XP-windfall consumables (lamps/tomes): reward xp with no action behind it.
		if (consumedId.equals("2528") || consumedId.equals("13148") || consumedId.equals("34057"))
		{
			return null;
		}
		// The (Corrupted) Gauntlet's internal economy.
		if (gauntletId(itemId) || gauntletId(consumedId) || "35969".equals(objId))
		{
			return null;
		}

		// COOKING wine fermentation: one drop, n wines.
		if (skill.equals("COOKING") && (consumedId.equals("1995") || consumedId.equals("1935")
			|| consumedId.equals("1937") || (consumedId.isEmpty() && itemId.isEmpty())))
		{
			int xp = intOr(xpStr, 0);
			if (xp > 0 && xp % 200 == 0 && xp / 200 <= 56)
			{
				int n = xp / 200;
				return pairs("foodCooked", n, "jugOfWineCooked", n);
			}
		}

		if (skill.equals("SMITHING"))
		{
			return smithing(name(itemId), qty);
		}

		// HUNTER herbiboar: target when fresh, else the exact xp band bare.
		if (skill.equals("HUNTER"))
		{
			String tl = target.toLowerCase(Locale.ROOT);
			if (tl.contains("herbiboar"))
			{
				return pairs("herbiboarsHarvested", 1);
			}
			if ("50".equals(xpStr) && (isTrailTarget(tl)
				|| itemId.equals("21555") || itemId.equals("21562") || itemId.equals("21566")
				|| (tl.isEmpty() && itemId.isEmpty())))
			{
				return null;   // trail steps of an already-counted harvest
			}
			if (tl.isEmpty())
			{
				int xp = intOr(xpStr, 0);
				if (xp >= 1950 && xp <= 2461)
				{
					String gained = name(itemId);
					if (gained.isEmpty() || gained.startsWith("Grimy "))
					{
						return pairs("herbiboarsHarvested", 1);
					}
				}
			}
		}
		if (skill.equals("HERBLORE") && itemId.isEmpty())
		{
			String tl = target.toLowerCase(Locale.ROOT);
			if (tl.contains("herbiboar") || isTrailTarget(tl))
			{
				return null;
			}
		}

		if (PRODUCTION.contains(skill))
		{
			return production(skill, xpStr, itemId, qty, target, consumedId);
		}

		if (skill.equals("RUNECRAFT"))
		{
			String low = name(itemId).toLowerCase(Locale.ROOT);
			if (low.endsWith(" rune") || low.endsWith(" runes"))
			{
				String tok = stripCamel(low, new String[]{" runes", " rune"}, "");
				List<Map.Entry<String, Integer>> out = pairs("runesCrafted", qty);
				if (!tok.isEmpty())
				{
					out.add(entry(tok + "Runecrafted", qty));
				}
				return out;
			}
			if (!itemId.isEmpty())
			{
				return null;   // veneration/tiara/rewards — not runecrafting
			}
			return pairs("runesCrafted", qty);
		}

		if (skill.equals("FIREMAKING"))
		{
			String tok = consumedId.isEmpty() ? "" : itemToken("WOODCUTTING", name(consumedId));
			if (tok.isEmpty() && !xpStr.isEmpty())
			{
				tok = ladder("FIREMAKING", xpStr);
			}
			List<Map.Entry<String, Integer>> out = pairs("logsBurned", 1);
			if (!tok.isEmpty())
			{
				out.add(entry(tok + "LogsBurned", 1));
			}
			return out;
		}

		if (skill.equals("PRAYER"))
		{
			return prayer(consumedId.isEmpty() ? "" : name(consumedId), xpStr);
		}

		if (skill.equals("THIEVING"))
		{
			return thieving(target, xpStr, itemId);
		}

		if (skill.equals("AGILITY"))
		{
			List<Map.Entry<String, Integer>> out = pairs("agilityObstacles", 1);
			if (itemId.isEmpty())
			{
				String key = ladder("AGILITY", xpStr);
				if (!key.isEmpty())
				{
					out.add(entry(key, 1));
				}
			}
			return out;
		}
		if (skill.equals("CONSTRUCTION"))
		{
			return pairs("constructionBuilds", 1);
		}
		if (skill.equals("FARMING"))
		{
			List<Map.Entry<String, Integer>> out = pairs("farmingActions", 1);
			if (!itemId.isEmpty())
			{
				String nm = name(itemId);
				if (!nm.isEmpty())
				{
					out.add(entry(camel(nm) + "Harvested", 1));
				}
			}
			else
			{
				String key = ladder("FARMING", xpStr);
				if (!key.isEmpty())
				{
					out.add(entry(key, 1));
				}
			}
			return out;
		}

		// GATHERING: generic floor + typed identity (gainedItem > object > xp).
		String[] gen = GATHERING.get(skill);
		if (gen == null)
		{
			return null;
		}
		String token = "";
		int n = 1;
		if (!itemId.isEmpty())
		{
			token = itemToken(skill, name(itemId));
			if (!token.isEmpty())
			{
				n = qty;
			}
		}
		if (token.isEmpty() && !objId.isEmpty())
		{
			token = objTable().getOrDefault(objId, "");
		}
		if (token.isEmpty() && !xpStr.isEmpty())
		{
			token = ladder(skill, xpStr);
		}
		List<Map.Entry<String, Integer>> out = pairs(gen[0], n);
		if (!token.isEmpty())
		{
			out.add(entry(token + gen[1], n));
		}
		return out;
	}

	// ── skill branches ─────────────────────────────────────────────────

	private List<Map.Entry<String, Integer>> smithing(String itemName, int qty)
	{
		String low = itemName.toLowerCase(Locale.ROOT).trim();
		if (low.isEmpty())
		{
			return null;
		}
		if (low.endsWith(" bar"))
		{
			String metal = low.substring(0, low.length() - 4).trim();
			metal = SMITH_ORE_ALIAS.getOrDefault(metal, metal);
			return metal.isEmpty() ? null : pairs(camel(metal) + "BarsSmelted", qty);
		}
		if (low.equals("cannonball"))
		{
			return pairs("cannonballsSmithed", qty);
		}
		String first = low.split(" ", 2)[0];
		String metal = SMITH_ORE_ALIAS.getOrDefault(first, first);
		if (SMITH_METALS.contains(metal))
		{
			return pairs(camel(metal) + "ItemsSmithed", qty);
		}
		return pairs("itemsSmithed", qty);
	}

	private List<Map.Entry<String, Integer>> production(String skill, String xpStr,
		String itemId, int qty, String target, String consumedId)
	{
		String name = name(itemId);
		if (!name.isEmpty())
		{
			if (skill.equals("FLETCHING"))
			{
				String wood = fletchLogToken(name);
				if (!wood.isEmpty())
				{
					return pairs("logsFletched", 1, wood + "LogsFletched", 1);
				}
				if (name.equals("Arrow shaft"))
				{
					String tok = consumedId.isEmpty() ? "" : itemToken("WOODCUTTING", name(consumedId));
					List<Map.Entry<String, Integer>> out = pairs("logsFletched", 1);
					if (!tok.isEmpty())
					{
						out.add(entry(tok + "LogsFletched", 1));
					}
					out.add(entry("arrowShaftsFletched", qty));
					return out;
				}
				if (name.equals("Headless arrow"))
				{
					return pairs("headlessArrowsFletched", qty);
				}
			}
			if (skill.equals("HUNTER"))
			{
				// birdhouse dismantle, gated before the chompy branch
				String bh = ladder("HUNTER_BIRDHOUSES", xpStr);
				if (!bh.isEmpty() && (name.equals("Clockwork")
					|| name.startsWith("Bird nest") || name.equals("Feather")))
				{
					return pairs("birdhousesEmptied", 1, bh, 1);
				}
				if (name.equals("Small fishing net") || name.equals("Rope"))
				{
					List<Map.Entry<String, Integer>> typed = netTrap(xpStr);
					return typed != null ? typed : pairs("creaturesTrapped", 1);
				}
				if (name.equals("Feather") || name.equals("Raw chompy"))
				{
					int xp = intOr(xpStr, 0);
					if (xp > 0 && xp % 30 == 0 && xp / 30 <= 4)
					{
						return pairs("chompyBirdsPlucked", xp / 30);
					}
					return pairs("chompyBirdsPlucked", 1);
				}
				String sp = HUNTER_ITEM_SPECIES.get(name);
				if (sp != null)
				{
					return pairs("creaturesTrapped", 1, sp, 1);
				}
				if (name.equals("Bones") || name.equals("Big bones")
					|| name.equals("Raw bird meat") || name.equals("Raw beast meat"))
				{
					String k = ladder("HUNTER", xpStr);
					List<Map.Entry<String, Integer>> out = pairs("creaturesTrapped", 1);
					if (!k.isEmpty())
					{
						out.add(entry(k, 1));
					}
					return out;
				}
			}
			Rule match = matchProduction(skill, name);
			if (match != null)
			{
				return match.key == null ? null
					: pairs(match.key, match.qty ? qty : 1);
			}
		}
		if (skill.equals("HUNTER"))
		{
			String tl = target.toLowerCase(Locale.ROOT).trim();
			if (itemId.equals("28893"))
			{
				return pairs("creaturesTrapped", 1, "moonlightMothsTrapped", 1);
			}
			if (tl.endsWith(" moth"))
			{
				String tok = camel(tl.substring(0, tl.length() - 5).trim());
				List<Map.Entry<String, Integer>> out = pairs("creaturesTrapped", 1);
				if (!tok.isEmpty())
				{
					out.add(entry(tok + "MothsTrapped", 1));
				}
				return out;
			}
			String bf = BUTTERFLY_TARGETS.get(tl);
			if (bf != null)
			{
				return pairs("creaturesTrapped", 1, bf, 1);
			}
			if (tl.contains("impling"))
			{
				return pairs("implingsCaught", 1);
			}
			if (itemId.isEmpty() && tl.isEmpty())
			{
				int xp = intOr(xpStr, 0);
				if (xp == 84)
				{
					return pairs("creaturesTrapped", 1, "moonlightMothsTrapped", 1);
				}
				if (xp == 74)
				{
					return pairs("creaturesTrapped", 1, "sunlightMothsTrapped", 1);
				}
			}
			if (consumedId.equals("10012"))
			{
				return pairs("creaturesTrapped", 1);
			}
		}
		return null;
	}

	private List<Map.Entry<String, Integer>> prayer(String consumedName, String xpStr)
	{
		String low = consumedName.trim().toLowerCase(Locale.ROOT);
		int xp = intOr(xpStr, 0);
		if (low.isEmpty())
		{
			String tok = ENSOULED_REANIM_XP.get(xp);
			if (tok != null)
			{
				return pairs("headsReanimated", 1, tok + "HeadsReanimated", 1);
			}
			return null;   // passive lattice / unknown — nothing countable
		}
		if (low.endsWith(" rune") || low.endsWith(" runes") || low.equals("bird's egg"))
		{
			return null;   // the cast tracker owns the totals
		}
		if (low.startsWith("ensouled ") && low.endsWith(" head"))
		{
			String tok = camel(low.substring(9, low.length() - 5).trim());
			return pairs("headsReanimated", 1, tok + "HeadsReanimated", 1);
		}
		if (low.endsWith(" ashes") || low.equals("ashes"))
		{
			String tok = stripCamel(low, new String[]{" ashes"}, "");
			Double base = PRAYER_BASE_XP.get(tok);
			if (base == null && tok.isEmpty())
			{
				base = 10.0;
			}
			int[] verb = prayerVerb(xp, base);
			if (verb[0] == 2)
			{
				return tok.isEmpty() ? new ArrayList<>()
					: pairs(tok + "AshesSacrificed", verb[1]);
			}
			if (verb[0] == 1 || xp == 0)
			{
				List<Map.Entry<String, Integer>> out = pairs("ashesScattered", 1);
				if (!tok.isEmpty())
				{
					out.add(entry(tok + "AshesScattered", 1));
				}
				return out;
			}
			return null;
		}
		if (low.endsWith(" bones") || low.equals("bones"))
		{
			String tok = stripCamel(low, new String[]{" bones"}, "normal");
			int[] verb = prayerVerb(xp, PRAYER_BASE_XP.get(tok));
			if (verb[0] == 2)
			{
				return pairs(tok + "BonesSacrificed", verb[1]);
			}
			if (verb[0] == 3)
			{
				return pairs("bonesOffered", 1, tok + "BonesOffered", 1);
			}
			if (verb[0] == 1 || xp == 0)
			{
				return pairs("bonesBuried", 1, tok + "BonesBuried", 1);
			}
			return null;
		}
		return null;   // TTL noise
	}

	/** {verbCode, n}: 1=ground, 2=spell ×n, 3=altar, 0=unknown. */
	private static int[] prayerVerb(int xp, Double base)
	{
		if (base == null || xp <= 0)
		{
			return new int[]{0, 0};
		}
		if (Math.floor(base) <= xp && xp <= Math.ceil(base))
		{
			return new int[]{1, 1};
		}
		for (int n = 1; n <= 3; n++)
		{
			double v = 3 * base * n;
			if (Math.floor(v) <= xp && xp <= Math.ceil(v))
			{
				return new int[]{2, n};
			}
		}
		double v = 3.5 * base;
		if (Math.floor(v) <= xp && xp <= Math.ceil(v))
		{
			return new int[]{3, 1};
		}
		return new int[]{0, 0};
	}

	private List<Map.Entry<String, Integer>> thieving(String target, String xpStr, String itemId)
	{
		String low = target.trim().toLowerCase(Locale.ROOT);
		if (low.equals("urn"))
		{
			return pairs("pyramidPlunderUrns", 1);
		}
		if (low.isEmpty())
		{
			if ("675".equals(xpStr) || "825".equals(xpStr))
			{
				return pairs("pyramidPlunderUrns", 1);
			}
			// the ghost-target stall fallback: classify by the stolen item
			if (!itemId.isEmpty())
			{
				String nm = name(itemId).toLowerCase(Locale.ROOT);
				if (nm.endsWith("cannonball"))
				{
					return pairs("stallsThieved", 1, "cannonballStallsThieved", 1);
				}
				if (nm.endsWith(" ore") || nm.equals("coal"))
				{
					return pairs("stallsThieved", 1, "oreStallsThieved", 1);
				}
			}
			return null;
		}
		if (low.endsWith(" stall") || low.endsWith(" stalls"))
		{
			int i = low.lastIndexOf("stall");
			String base = low.substring(0, i).trim();
			if (base.isEmpty())
			{
				base = "market";
			}
			return pairs("stallsThieved", 1, camel(base) + "StallsThieved", 1);
		}
		if (low.contains("chest"))
		{
			String base = low.replace("chest", "").trim();
			if (base.isEmpty())
			{
				base = "normal";
			}
			return pairs("chestsLooted", 1, camel(base) + "ChestsLooted", 1);
		}
		if (low.contains("safe"))
		{
			return pairs("safesCracked", 1);
		}
		return pairs("pickPockets", 1, camel(low) + "Pickpockets", 1);
	}

	private List<Map.Entry<String, Integer>> netTrap(String xpStr)
	{
		double xp = intOr(xpStr, 0);
		if (xp <= 0)
		{
			return null;
		}
		for (double[] row : NET_TRAP_BASES)
		{
			double base = row[0];
			int n = (int) Math.round(xp / base);
			if (n >= 1 && n <= NET_TRAP_MAX && Math.abs(xp - n * base) < 1.0)
			{
				return pairs("creaturesTrapped", n, NET_TRAP_KEYS[(int) row[1]], n);
			}
		}
		return null;
	}

	// ── tokens + tables ────────────────────────────────────────────────

	private String itemToken(String skill, String itemName)
	{
		String low = itemName.trim().toLowerCase(Locale.ROOT);
		if (low.isEmpty())
		{
			return "";
		}
		String n;
		switch (skill)
		{
			case "WOODCUTTING":
				if (!(low.endsWith(" logs") || low.equals("logs") || low.endsWith(" log")))
				{
					return "";
				}
				n = low.equals("logs") || low.equals("log") ? "normal"
					: low.replace(" logs", "").replace(" log", "").trim();
				if (n.isEmpty())
				{
					n = "normal";
				}
				break;
			case "FISHING":
				if (low.startsWith("raw "))
				{
					n = low.substring(4).trim();
				}
				else if (FISH_NORAW.contains(low))
				{
					n = low;
				}
				else
				{
					return "";
				}
				break;
			case "MINING":
				if (low.startsWith("granite"))
				{
					n = "granite";
				}
				else if (low.startsWith("sandstone"))
				{
					n = "sandstone";
				}
				else if (low.startsWith("uncut "))
				{
					n = "gem rock";
				}
				else if (low.endsWith(" ore") || MINING_ROCKS.contains(low))
				{
					n = low;
				}
				else
				{
					return "";
				}
				break;
			default:   // COOKING
				n = low.startsWith("cooked ") ? low.substring(7).trim() : low;
		}
		String alias = ITEM_ALIASES.get(n);
		return alias != null ? alias : camel(n);
	}

	private static String fletchLogToken(String name)
	{
		String low = name.trim().toLowerCase(Locale.ROOT);
		if (low.endsWith(" (u)"))
		{
			String first = low.substring(0, low.length() - 4).trim().split(" ", 2)[0];
			if (first.equals("shortbow") || first.equals("longbow") || first.equals("bow"))
			{
				return "normal";
			}
			if (first.equals("crossbow") || low.contains("crossbow"))
			{
				return "";   // crossbows ride the item rules, not the wood family
			}
			return camel(first);
		}
		if (low.endsWith(" shield") && (low.contains("wooden") || low.split(" ").length <= 3))
		{
			String first = low.split(" ", 2)[0];
			if (!first.equals("wooden"))
			{
				return camel(first);
			}
			return "normal";
		}
		return "";
	}

	private Rule matchProduction(String skill, String itemName)
	{
		String n = itemName.trim();
		if (n.isEmpty())
		{
			return null;
		}
		String low = n.toLowerCase(Locale.ROOT);
		for (Rule r : itemRules().getOrDefault(skill, new ArrayList<>()))
		{
			boolean hit = false;
			switch (r.match == null ? "" : r.match)
			{
				case "default":
					hit = true;
					break;
				case "endswith":
					hit = low.endsWith(r.value.toLowerCase(Locale.ROOT));
					break;
				case "contains":
					hit = low.contains(r.value.toLowerCase(Locale.ROOT));
					break;
				case "regex":
					hit = r.regex != null && r.regex.matcher(n).find();
					break;
				case "in_set":
					hit = r.valueSet != null && r.valueSet.contains(n);
					break;
				default:
					break;
			}
			if (hit)
			{
				return r;
			}
		}
		return null;
	}

	private String name(String itemId)
	{
		if (itemId == null || itemId.isEmpty())
		{
			return "";
		}
		try
		{
			int id = Integer.parseInt(itemId);
			if (id <= 0)
			{
				return "";
			}
			String nm = itemManager.getItemComposition(itemManager.canonicalize(id)).getName();
			return nm == null ? "" : nm;
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	private String ladder(String skill, String xpStr)
	{
		Object v = xpTable().getOrDefault(skill, new HashMap<>()).get(xpStr);
		return v instanceof String ? (String) v : "";
	}

	private static boolean gauntletId(String v)
	{
		try
		{
			int id = Integer.parseInt(v);
			return id >= 23824 && id <= 23858;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	private static boolean isTrailTarget(String tl)
	{
		return tl.equals("muddy patch") || tl.equals("seaweed") || tl.equals("mushroom")
			|| tl.equals("smelly mushroom") || tl.equals("rock");
	}

	static String camel(String token)
	{
		String[] words = token.trim().toLowerCase(Locale.ROOT).split("[\\s\\-]+");
		StringBuilder out = new StringBuilder();
		for (String w : words)
		{
			if (w.isEmpty())
			{
				continue;
			}
			if (out.length() == 0)
			{
				out.append(w);
			}
			else
			{
				out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			}
		}
		return out.toString();
	}

	private static String stripCamel(String nameLow, String[] strips, String def)
	{
		String low = nameLow.trim();
		for (String s : strips)
		{
			if (low.equals(s.trim()))
			{
				low = "";
				break;
			}
			if (low.endsWith(s))
			{
				low = low.substring(0, low.length() - s.length()).trim();
				break;
			}
		}
		if (low.isEmpty())
		{
			low = def;
		}
		String alias = ITEM_ALIASES.get(low);
		return alias != null ? alias : camel(low);
	}

	private static List<Map.Entry<String, Integer>> pairs(Object... kv)
	{
		List<Map.Entry<String, Integer>> out = new ArrayList<>();
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			out.add(entry((String) kv[i], (Integer) kv[i + 1]));
		}
		return out;
	}

	private static Map.Entry<String, Integer> entry(String k, int v)
	{
		return new java.util.AbstractMap.SimpleEntry<>(k, v);
	}

	private static int intOr(String s, int def)
	{
		try
		{
			return Integer.parseInt(s);
		}
		catch (RuntimeException e)
		{
			return def;
		}
	}

	// ── bundled table loading (lazy, once) ─────────────────────────────

	private synchronized Map<String, Map<String, String>> xpTable()
	{
		if (xpTable == null)
		{
			xpTable = new HashMap<>();
			JsonObject o = resource("/chronicle/osrs_skill_xp.json");
			if (o != null)
			{
				for (Map.Entry<String, JsonElement> skill : o.entrySet())
				{
					if (!skill.getValue().isJsonObject())
					{
						continue;
					}
					Map<String, String> ladder = new HashMap<>();
					for (Map.Entry<String, JsonElement> e
						: skill.getValue().getAsJsonObject().entrySet())
					{
						if (e.getValue().isJsonPrimitive()
							&& e.getValue().getAsJsonPrimitive().isString())
						{
							ladder.put(e.getKey(), e.getValue().getAsString());
						}
						// {"members":[...]} collision rows are documentary — inert
					}
					xpTable.put(skill.getKey(), ladder);
				}
			}
		}
		return xpTable;
	}

	private synchronized Map<String, List<Rule>> itemRules()
	{
		if (itemRules == null)
		{
			itemRules = new HashMap<>();
			JsonObject o = resource("/chronicle/osrs_skill_item_rules.json");
			if (o != null)
			{
				for (Map.Entry<String, JsonElement> skill : o.entrySet())
				{
					if (!skill.getValue().isJsonArray())
					{
						continue;
					}
					List<Rule> rules = new ArrayList<>();
					for (JsonElement el : skill.getValue().getAsJsonArray())
					{
						if (!el.isJsonObject())
						{
							continue;
						}
						JsonObject ro = el.getAsJsonObject();
						Rule r = new Rule();
						r.match = ro.has("match") ? ro.get("match").getAsString() : null;
						r.key = ro.has("key") && !ro.get("key").isJsonNull()
							? ro.get("key").getAsString() : null;
						r.qty = ro.has("qty") && ro.get("qty").getAsBoolean();
						if (ro.has("value"))
						{
							JsonElement v = ro.get("value");
							if (v.isJsonArray())
							{
								r.valueSet = new HashSet<>();
								for (JsonElement item : v.getAsJsonArray())
								{
									r.valueSet.add(item.getAsString());
								}
							}
							else
							{
								r.value = v.getAsString();
								if ("regex".equals(r.match))
								{
									try
									{
										r.regex = Pattern.compile(r.value);
									}
									catch (RuntimeException e)
									{
										r.match = "";   // bad pattern — inert rule
									}
								}
							}
						}
						if (r.match != null)
						{
							rules.add(r);
						}
					}
					itemRules.put(skill.getKey(), rules);
				}
			}
		}
		return itemRules;
	}

	private synchronized Map<String, String> objTable()
	{
		if (objTable == null)
		{
			objTable = new HashMap<>();
			JsonObject o = resource("/chronicle/osrs_object_species.json");
			if (o != null && o.has("objects") && o.get("objects").isJsonObject())
			{
				for (Map.Entry<String, JsonElement> e
					: o.getAsJsonObject("objects").entrySet())
				{
					objTable.put(e.getKey(), e.getValue().getAsString());
				}
			}
		}
		return objTable;
	}

	private JsonObject resource(String path)
	{
		try (InputStream in = SkillDeriver.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				return null;
			}
			return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
				JsonObject.class);
		}
		catch (Exception e)
		{
			log.debug("reference table {} unreadable", path, e);
			return null;
		}
	}
}
