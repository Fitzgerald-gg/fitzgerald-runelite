/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin.counters;

/**
 * Wire names for the counters this client owns.
 *
 * <p>Only counters the CLIENT computes live here. The great majority of what the
 * profile displays — every per-resource gathering, production and thieving total —
 * is derived server-side from the skill tuples this plugin forwards, and so has no
 * constant in this file: the server mints {@code willowLogsChopped} or
 * {@code lobsterEaten} structurally the first time it sees one, which is what lets a
 * new resource be tracked the day it launches with no plugin update. Adding a name
 * here is therefore the exception, reserved for things no experience drop can express.
 *
 * <p>Naming follows two rules. Counters that belong to a family share a <i>prefix</i>
 * rather than a suffix ({@code teleportsVarrock}, not {@code varrockTeleport}) so the
 * family stays contiguous when keys are sorted — which is how they arrive in the API
 * response and how the profile lists them. Everything else is a plain past-tense noun
 * phrase counting the thing that happened.
 *
 * <p>These strings are a durable contract: they are the column names of a player's
 * lifetime history, so renaming one orphans that history unless the stored rows are
 * migrated with it.
 */
public final class StatKeys
{
	private StatKeys()
	{
	}

	// ── Movement ──────────────────────────────────────────────────────────
	// Measured in tiles. Walking and running are counted apart because the
	// interesting number is the ratio between them, not the sum.

	public static final String DISTANCE_WALKED = "distanceWalked";
	public static final String DISTANCE_RAN = "distanceRan";

	// ── Teleports ─────────────────────────────────────────────────────────
	// Prefix-grouped so every destination sorts together. TOTAL is incremented
	// alongside each specific one, so it also covers destinations with no key
	// of their own.

	public static final String TELEPORTS_TOTAL = "teleportsTotal";

	// Standard spellbook + their teleport tablets, each also a portal-nexus row.
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
	// A "Teleport to Boat" hop (a portal-nexus row / Sailing spell). Its arrival is
	// the boat's current mooring — a moving target — so this counts the act of going
	// to the boat, not a fixed place.
	public static final String TELEPORTS_BOAT = "teleportsBoat";

	// Ancient Magicks (also portal-nexus rows).
	public static final String TELEPORTS_PADDEWWA = "teleportsPaddewwa";
	public static final String TELEPORTS_SENNTISTEN = "teleportsSenntisten";
	public static final String TELEPORTS_KHARYRLL = "teleportsKharyrll";
	public static final String TELEPORTS_LASSAR = "teleportsLassar";
	public static final String TELEPORTS_DAREEYAK = "teleportsDareeyak";
	public static final String TELEPORTS_CARRALLANGER = "teleportsCarrallanger";
	public static final String TELEPORTS_ANNAKARL = "teleportsAnnakarl";
	public static final String TELEPORTS_GHORROCK = "teleportsGhorrock";

	// Lunar (also portal-nexus rows).
	public static final String TELEPORTS_MOONCLAN = "teleportsMoonclan";
	public static final String TELEPORTS_OURANIA = "teleportsOurania";
	public static final String TELEPORTS_WATERBIRTH = "teleportsWaterbirth";
	public static final String TELEPORTS_BARBARIAN_OUTPOST = "teleportsBarbarianOutpost";
	public static final String TELEPORTS_KHAZARD = "teleportsKhazard";
	public static final String TELEPORTS_FISHING_GUILD = "teleportsFishingGuild";
	public static final String TELEPORTS_CATHERBY = "teleportsCatherby";
	public static final String TELEPORTS_ICE_PLATEAU = "teleportsIcePlateau";

	// Arceuus (also portal-nexus rows).
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

	// Tablet / basalt / scroll destinations that also sit on the nexus (no spell).
	public static final String TELEPORTS_POLLNIVNEACH = "teleportsPollnivneach";
	public static final String TELEPORTS_TROLL_STRONGHOLD = "teleportsTrollStronghold";
	public static final String TELEPORTS_WEISS = "teleportsWeiss";

	// Skillcape teleports. Most fire a bare "Teleport" whose destination is not in the
	// menu text, so they are attributed by the cape name in the target instead. Only
	// the Strength cape among the melee capes teleports (to the Warriors' Guild); the
	// Magic and Max capes are features, not places, and are left untracked.
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
	// POH portal nexus: it picks its destination inside a dialog, so the specific
	// place never reaches a menu/widget event — bucket it by method instead.
	public static final String TELEPORTS_NEXUS = "teleportsNexus";

	// ── Combat ────────────────────────────────────────────────────────────
	// Damage is summed from hitsplats, so it counts what actually landed
	// rather than what was rolled.

	public static final String DAMAGE_DEALT = "damageDealt";
	public static final String DAMAGE_TAKEN = "damageTaken";
	public static final String HIGHEST_HIT = "highestHit";
	/** Biggest single hit ever received (all damage colours + max-hit variants). */
	public static final String HIGHEST_HIT_TAKEN = "highestHitTaken";
	public static final String HITS_MISSED = "hitsMissed";
	public static final String HITS_BLOCKED = "hitsBlocked";
	public static final String DEATHS = "deaths";
	/** HP bled to poison and venom — their own hitsplat types, so apart from damageTaken. */
	public static final String POISON_DAMAGE_TAKEN = "poisonDamageTaken";
	public static final String VENOM_DAMAGE_TAKEN = "venomDamageTaken";

	// ── Consumables ───────────────────────────────────────────────────────
	// FOOD_EATEN is the generic floor beneath the server-derived <food>Eaten
	// family, so it deliberately shares that family's wording.

	public static final String FOOD_EATEN = "foodEaten";
	public static final String POTION_DOSES = "potionDoses";
	public static final String BEERS_DRUNK = "beersDrunk";
	public static final String VIALS_SHATTERED = "vialsShattered";
	public static final String DIVINE_POTION_DAMAGE = "divinePotionDamage";
	public static final String SPECIAL_ATTACKS_USED = "specialAttacksUsed";
	public static final String HITPOINTS_REGENERATED = "hitpointsRegenerated";

	// ── Inventory and trade ───────────────────────────────────────────────

	public static final String ITEMS_DISCARDED = "itemsDiscarded";
	/** Live GE value, at the moment of dropping, of everything binned via the Drop menu. */
	public static final String ITEMS_DROPPED_VALUE = "itemsDroppedValue";
	public static final String EXAMINES = "examines";
	public static final String COINS_SPENT_AT_SHOPS = "coinsSpentAtShops";
	public static final String COINS_EARNED_AT_SHOPS = "coinsEarnedAtShops";

	// ── Gathered by hand ──────────────────────────────────────────────────
	// These award no experience, so nothing in the skill pipeline can see
	// them; they are counted from the inventory instead.

	public static final String CABBAGES_PICKED = "cabbagesPicked";
	public static final String FLAX_GATHERED = "flaxGathered";
	public static final String PATCHES_RAKED = "patchesRaked";
	public static final String ANIMALS_PETTED = "animalsPetted";

	// ── Magic and ranged ──────────────────────────────────────────────────

	/** Coins the player has minted via High/Low Alchemy. */
	public static final String COINS_FROM_ALCHEMY = "coinsFromAlchemy";
	/** Offensive spell casts (standard/Ancient/Arceuus), splashes included. */
	public static final String OFFENSIVE_SPELLS_CAST = "offensiveSpellsCast";
	public static final String DEMONIC_OFFERINGS_CAST = "demonicOfferingsCast";
	public static final String SINISTER_OFFERINGS_CAST = "sinisterOfferingsCast";
	public static final String BONES_SACRIFICED = "bonesSacrificed";
	public static final String ASHES_SACRIFICED = "ashesSacrificed";
	public static final String DEMONIC_OFFERING_XP = "demonicOfferingXp";
	public static final String SINISTER_OFFERING_XP = "sinisterOfferingXp";
	/** Ranged ammunition that has left the quiver (consumed or dropped). */
	public static final String AMMO_CONSUMED = "ammoConsumed";

	// ── Experience ────────────────────────────────────────────────────────

	/** Total experience gained across every skill while the plugin was tracking. */
	public static final String TOTAL_XP_GAINED = "totalXpGained";
}
