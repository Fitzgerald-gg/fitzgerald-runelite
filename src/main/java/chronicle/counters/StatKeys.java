/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

/**
 * Journal key names for the counters the trackers write.
 *
 * <p>Per-resource skilling keys ({@code willowLogsChopped}, {@code lobsterEaten}) are
 * minted from the action itself by {@link SkillDeriver} and the food tracker, so they
 * have no constant here. A name lands in this file when no experience drop or item
 * name can express it.
 *
 * <p>Families share a prefix: {@code teleportsVarrock}, {@code teleportsCamelot}. The
 * panel groups them with {@code startsWith}, so a family member named the other way
 * round sits outside its group.
 *
 * <p>Renaming a key orphans everything the journal already stored under the old name.
 */
public final class StatKeys
{
	private StatKeys()
	{
	}

	// ── Movement ──────────────────────────────────────────────────────────
	// Counted in tiles, with walking and running kept apart.

	public static final String DISTANCE_WALKED = "distanceWalked";
	public static final String DISTANCE_RAN = "distanceRan";

	// ── Teleports ─────────────────────────────────────────────────────────
	// TOTAL bumps on every teleport, including ones whose destination has no
	// key of its own.

	public static final String TELEPORTS_TOTAL = "teleportsTotal";

	// The means of travel, ticked alongside the destination: a jewellery hop to
	// Castle Wars bumps both.
	public static final String TELEPORTS_VIA_JEWELLERY = "teleportsViaJewellery";
	public static final String TELEPORTS_VIA_TABLET = "teleportsViaTablet";
	public static final String TELEPORTS_VIA_SCROLL = "teleportsViaScroll";
	public static final String TELEPORTS_VIA_SPELL = "teleportsViaSpell";
	public static final String TELEPORTS_VIA_CAPE = "teleportsViaCape";

	// Jewellery destinations: worn, rubbed, or via the POH jewellery box.
	public static final String TELEPORTS_CASTLE_WARS = "teleportsCastleWars";
	public static final String TELEPORTS_FEROX_ENCLAVE = "teleportsFeroxEnclave";
	public static final String TELEPORTS_EMIRS_ARENA = "teleportsEmirsArena";
	public static final String TELEPORTS_COLOSSEUM = "teleportsColosseum";
	public static final String TELEPORTS_EDGEVILLE = "teleportsEdgeville";
	public static final String TELEPORTS_KARAMJA = "teleportsKaramja";
	public static final String TELEPORTS_DRAYNOR = "teleportsDraynor";
	public static final String TELEPORTS_AL_KHARID = "teleportsAlKharid";
	public static final String TELEPORTS_BURTHORPE = "teleportsBurthorpe";
	public static final String TELEPORTS_CORPOREAL_BEAST = "teleportsCorporealBeast";
	public static final String TELEPORTS_TEARS_OF_GUTHIX = "teleportsTearsOfGuthix";
	public static final String TELEPORTS_WINTERTODT_CAMP = "teleportsWintertodtCamp";
	public static final String TELEPORTS_CHAMPIONS_GUILD = "teleportsChampionsGuild";
	public static final String TELEPORTS_MONASTERY = "teleportsMonastery";
	public static final String TELEPORTS_RANGING_GUILD = "teleportsRangingGuild";
	public static final String TELEPORTS_MINING_GUILD = "teleportsMiningGuild";
	public static final String TELEPORTS_WOODCUTTING_GUILD = "teleportsWoodcuttingGuild";
	public static final String TELEPORTS_COOKING_GUILD = "teleportsCookingGuild";
	public static final String TELEPORTS_MISCELLANIA = "teleportsMiscellania";
	public static final String TELEPORTS_CHAOS_TEMPLE = "teleportsChaosTemple";
	public static final String TELEPORTS_BANDIT_CAMP = "teleportsBanditCamp";
	public static final String TELEPORTS_LAVA_MAZE = "teleportsLavaMaze";
	public static final String TELEPORTS_WIZARDS_TOWER = "teleportsWizardsTower";
	public static final String TELEPORTS_THE_OUTPOST = "teleportsTheOutpost";
	public static final String TELEPORTS_EAGLES_EYRIE = "teleportsEaglesEyrie";
	public static final String TELEPORTS_DONDAKANS_ROCK = "teleportsDondakansRock";
	public static final String TELEPORTS_VER_SINHAZA = "teleportsVerSinhaza";
	public static final String TELEPORTS_DARKMEYER = "teleportsDarkmeyer";
	public static final String TELEPORTS_SLEPE = "teleportsSlepe";
	public static final String TELEPORTS_DIGSITE = "teleportsDigsite";
	public static final String TELEPORTS_FOSSIL_ISLAND = "teleportsFossilIsland";
	public static final String TELEPORTS_LITHKREN = "teleportsLithkren";
	public static final String TELEPORTS_SLAYER_DUNGEONS = "teleportsSlayerDungeons";
	// Redirected house tabs, then the everyday teleport items.
	public static final String TELEPORTS_TAVERLEY = "teleportsTaverley";
	public static final String TELEPORTS_RIMMINGTON = "teleportsRimmington";
	public static final String TELEPORTS_RELLEKKA = "teleportsRellekka";
	public static final String TELEPORTS_BRIMHAVEN = "teleportsBrimhaven";
	public static final String TELEPORTS_HOSIDIUS = "teleportsHosidius";
	public static final String TELEPORTS_PRIFDDINAS = "teleportsPrifddinas";
	public static final String TELEPORTS_ALDARIN = "teleportsAldarin";
	public static final String TELEPORTS_ECTOFUNTUS = "teleportsEctofuntus";
	public static final String TELEPORTS_GRAND_TREE = "teleportsGrandTree";
	public static final String TELEPORTS_OBELISK = "teleportsObelisk";
	public static final String TELEPORTS_ELEMENTAL_ALTARS = "teleportsElementalAltars";
	public static final String TELEPORTS_GIANTS_FOUNDRY = "teleportsGiantsFoundry";

	// Standard spellbook and its tablets.
	public static final String TELEPORTS_VARROCK = "teleportsVarrock";
	public static final String TELEPORTS_GRAND_EXCHANGE = "teleportsGrandExchange";
	public static final String TELEPORTS_LUMBRIDGE = "teleportsLumbridge";
	public static final String TELEPORTS_FALADOR = "teleportsFalador";
	public static final String TELEPORTS_CAMELOT = "teleportsCamelot";
	public static final String TELEPORTS_SEERS_VILLAGE = "teleportsSeersVillage";
	public static final String TELEPORTS_ARDOUGNE = "teleportsArdougne";
	public static final String TELEPORTS_WATCHTOWER = "teleportsWatchtower";
	public static final String TELEPORTS_YANILLE = "teleportsYanille";
	public static final String TELEPORTS_TROLLHEIM = "teleportsTrollheim";
	public static final String TELEPORTS_APE_ATOLL = "teleportsApeAtoll";
	public static final String TELEPORTS_KOUREND = "teleportsKourend";
	public static final String TELEPORTS_FORTIS = "teleportsFortis";
	public static final String TELEPORTS_HOUSE = "teleportsHouse";
	// Teleport to Boat. The arrival follows the boat's mooring, so this counts the
	// hop rather than a place.
	public static final String TELEPORTS_BOAT = "teleportsBoat";

	// Ancient Magicks.
	public static final String TELEPORTS_PADDEWWA = "teleportsPaddewwa";
	public static final String TELEPORTS_SENNTISTEN = "teleportsSenntisten";
	public static final String TELEPORTS_KHARYRLL = "teleportsKharyrll";
	public static final String TELEPORTS_LASSAR = "teleportsLassar";
	public static final String TELEPORTS_DAREEYAK = "teleportsDareeyak";
	public static final String TELEPORTS_CARRALLANGER = "teleportsCarrallanger";
	public static final String TELEPORTS_ANNAKARL = "teleportsAnnakarl";
	public static final String TELEPORTS_GHORROCK = "teleportsGhorrock";

	// Lunar.
	public static final String TELEPORTS_MOONCLAN = "teleportsMoonclan";
	public static final String TELEPORTS_OURANIA = "teleportsOurania";
	public static final String TELEPORTS_WATERBIRTH = "teleportsWaterbirth";
	public static final String TELEPORTS_BARBARIAN_OUTPOST = "teleportsBarbarianOutpost";
	public static final String TELEPORTS_KHAZARD = "teleportsKhazard";
	public static final String TELEPORTS_FISHING_GUILD = "teleportsFishingGuild";
	public static final String TELEPORTS_CATHERBY = "teleportsCatherby";
	public static final String TELEPORTS_ICE_PLATEAU = "teleportsIcePlateau";

	// Arceuus.
	public static final String TELEPORTS_ARCEUUS_LIBRARY = "teleportsArceuusLibrary";
	public static final String TELEPORTS_DRAYNOR_MANOR = "teleportsDraynorManor";
	public static final String TELEPORTS_BATTLEFRONT = "teleportsBattlefront";
	public static final String TELEPORTS_MIND_ALTAR = "teleportsMindAltar";
	public static final String TELEPORTS_RESPAWN = "teleportsRespawn";
	public static final String TELEPORTS_SALVE_GRAVEYARD = "teleportsSalveGraveyard";
	public static final String TELEPORTS_FENKENSTRAIN = "teleportsFenkenstrain";
	public static final String TELEPORTS_WEST_ARDOUGNE = "teleportsWestArdougne";
	public static final String TELEPORTS_HARMONY_ISLAND = "teleportsHarmonyIsland";
	public static final String TELEPORTS_CEMETERY = "teleportsCemetery";
	public static final String TELEPORTS_BARROWS = "teleportsBarrows";
	public static final String TELEPORTS_APE_ATOLL_DUNGEON = "teleportsApeAtollDungeon";

	// Tablet, basalt and scroll destinations with no spell behind them.
	public static final String TELEPORTS_POLLNIVNEACH = "teleportsPollnivneach";
	public static final String TELEPORTS_TROLL_STRONGHOLD = "teleportsTrollStronghold";
	public static final String TELEPORTS_WEISS = "teleportsWeiss";

	// Skillcapes. Most fire a bare "Teleport" with no place in the menu text, so
	// they are matched on the cape name in the target instead.
	public static final String TELEPORTS_WARRIORS_GUILD = "teleportsWarriorsGuild";
	public static final String TELEPORTS_CRAFTING_GUILD = "teleportsCraftingGuild";
	public static final String TELEPORTS_FARMING_GUILD = "teleportsFarmingGuild";
	public static final String TELEPORTS_HUNTER_GUILD = "teleportsHunterGuild";
	public static final String TELEPORTS_OTTOS_GROTTO = "teleportsOttosGrotto";
	public static final String TELEPORTS_LEGENDS_GUILD = "teleportsLegendsGuild";
	public static final String TELEPORTS_DIARY_REGION = "teleportsDiaryRegion";
	public static final String TELEPORTS_FALO = "teleportsFalo";
	public static final String TELEPORTS_PANDEMONIUM = "teleportsPandemonium";

	public static final String TELEPORTS_SPIRIT_TREE = "teleportsSpiritTree";
	public static final String TELEPORTS_FAIRY_RING = "teleportsFairyRing";
	// Catch-all for a portal nexus row whose destination has no key of its own.
	public static final String TELEPORTS_NEXUS = "teleportsNexus";

	// ── Combat ────────────────────────────────────────────────────────────
	// Damage totals come off hitsplats, so they count what landed.

	public static final String DAMAGE_DEALT = "damageDealt";
	// The style split of damageDealt, taken from the combat xp drop on the hit. It
	// skips a hit whose drop is late, so the three won't always add up to the total.
	public static final String DAMAGE_DEALT_MELEE = "damageDealtMelee";
	public static final String DAMAGE_DEALT_RANGED = "damageDealtRanged";
	public static final String DAMAGE_DEALT_MAGIC = "damageDealtMagic";
	public static final String DAMAGE_TAKEN = "damageTaken";
	public static final String CONSUMED_VALUE = "consumedValue";
	public static final String HIGHEST_HIT = "highestHit";
	// Biggest single hit taken, across every damage colour and the max-hit variants.
	public static final String HIGHEST_HIT_TAKEN = "highestHitTaken";
	public static final String HITS_MISSED = "hitsMissed";
	public static final String HITS_BLOCKED = "hitsBlocked";
	public static final String DEATHS = "deaths";
	// Poison and venom carry their own hitsplat types, which damageTaken excludes.
	public static final String POISON_DAMAGE_TAKEN = "poisonDamageTaken";
	public static final String VENOM_DAMAGE_TAKEN = "venomDamageTaken";

	// ── Consumables ───────────────────────────────────────────────────────
	// foodEaten is the floor under the typed <food>Eaten keys the food tracker
	// mints beside it, so it shares their wording.

	public static final String FOOD_EATEN = "foodEaten";
	public static final String POTION_DOSES = "potionDoses";
	public static final String BEERS_DRUNK = "beersDrunk";
	public static final String VIALS_SHATTERED = "vialsShattered";
	public static final String DIVINE_POTION_DAMAGE = "divinePotionDamage";
	public static final String SPECIAL_ATTACKS_USED = "specialAttacksUsed";
	public static final String HITPOINTS_REGENERATED = "hitpointsRegenerated";

	// ── Inventory and trade ───────────────────────────────────────────────

	public static final String ITEMS_DISCARDED = "itemsDiscarded";
	// GE value at the moment of dropping, for everything binned via the Drop menu.
	public static final String ITEMS_DROPPED_VALUE = "itemsDroppedValue";
	public static final String EXAMINES = "examines";
	public static final String COINS_SPENT_AT_SHOPS = "coinsSpentAtShops";
	public static final String COINS_EARNED_AT_SHOPS = "coinsEarnedAtShops";

	// ── The resource pair ─────────────────────────────────────────────────
	// Two figures read side by side. Netting one against the other reads a
	// powerminer's whole career as roughly zero.

	// GE value at the moment of gathering, for what woodcutting, mining and fishing
	// produced. Banked at the event, so a later crash can't re-price a career.
	public static final String RESOURCES_GATHERED_VALUE = "resourcesGatheredValue";
	// The slice of itemsDroppedValue this account gathered itself, so it compares
	// like with like against the figure above.
	public static final String RESOURCES_DROPPED_VALUE = "resourcesDroppedValue";

	// ── Gathered by hand ──────────────────────────────────────────────────
	// No experience drop behind these, so they're counted off the click or the
	// inventory instead.

	public static final String CABBAGES_PICKED = "cabbagesPicked";
	public static final String FLAX_GATHERED = "flaxGathered";
	public static final String PATCHES_RAKED = "patchesRaked";
	public static final String ANIMALS_PETTED = "animalsPetted";

	// ── Magic and ranged ──────────────────────────────────────────────────

	public static final String COINS_FROM_ALCHEMY = "coinsFromAlchemy";
	// Counted off the cast animation, so a splash still counts.
	public static final String OFFENSIVE_SPELLS_CAST = "offensiveSpellsCast";
	public static final String DEMONIC_OFFERINGS_CAST = "demonicOfferingsCast";
	public static final String SINISTER_OFFERINGS_CAST = "sinisterOfferingsCast";
	public static final String BONES_SACRIFICED = "bonesSacrificed";
	public static final String ASHES_SACRIFICED = "ashesSacrificed";
	public static final String DEMONIC_OFFERING_XP = "demonicOfferingXp";
	public static final String SINISTER_OFFERING_XP = "sinisterOfferingXp";
	// Ammo that left the quiver. Unequipping it into the pack doesn't count.
	public static final String AMMO_CONSUMED = "ammoConsumed";

	// ── Experience ────────────────────────────────────────────────────────

	// Every skill's gains added up, from the point the plugin started watching.
	public static final String TOTAL_XP_GAINED = "totalXpGained";
}
