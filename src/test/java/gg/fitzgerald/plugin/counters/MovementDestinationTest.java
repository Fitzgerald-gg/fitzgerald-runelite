/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin.counters;

import org.junit.Test;

import static gg.fitzgerald.plugin.counters.MovementStatTracker.matchDestinationKey;
import static gg.fitzgerald.plugin.counters.StatKeys.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Pins teleport-destination attribution across the three ways to reach a place — a
 * portal-nexus row, a spellbook cast, and a teleport tablet — so they all land on the
 * SAME counter, and so the load-bearing ORDER of the destination table can't silently
 * regress. Inputs here are the real menu strings: a nexus row arrives keybind-prefixed
 * ("5 :  Camelot"); a spell/tab arrives as option + target joined ("cast varrock
 * teleport", "break varrock teleport"). Every string is what the client actually emits.
 */
public class MovementDestinationTest
{
	// ── The three routes to one place agree ───────────────────────────────

	@Test
	public void nexusRowSpellAndTabAllAgree()
	{
		assertEquals(TELEPORTS_VARROCK, matchDestinationKey("8 :  Varrock"));       // nexus row
		assertEquals(TELEPORTS_VARROCK, matchDestinationKey("cast varrock teleport")); // spell
		assertEquals(TELEPORTS_VARROCK, matchDestinationKey("break varrock teleport")); // tab
		assertEquals(TELEPORTS_CAMELOT, matchDestinationKey("5 :  Camelot"));
		assertEquals(TELEPORTS_FORTIS, matchDestinationKey("8 :  Civitas illa Fortis"));
		assertEquals(TELEPORTS_BOAT, matchDestinationKey("1 :  Boat"));
	}

	// ── Load-bearing order: contained substrings ──────────────────────────

	@Test
	public void longerContainedNameWinsOverShorter()
	{
		// "ape atoll dungeon" must be tested before "ape atoll"
		assertEquals(TELEPORTS_APE_ATOLL_DUNGEON, matchDestinationKey("v :  Ape Atoll Dungeon"));
		assertEquals(TELEPORTS_APE_ATOLL, matchDestinationKey("g :  Ape Atoll"));
		// "west ardougne" must be tested before "ardougne"
		assertEquals(TELEPORTS_WEST_ARDOUGNE, matchDestinationKey("w :  West Ardougne"));
		assertEquals(TELEPORTS_ARDOUGNE, matchDestinationKey("cast ardougne teleport"));
	}

	// ── Load-bearing order: diary secondary destinations ──────────────────

	@Test
	public void diarySwitchBeatsBaseTown()
	{
		// A switched cast carries BOTH names; the switch destination must win.
		assertEquals(TELEPORTS_GRAND_EXCHANGE, matchDestinationKey("grand exchange varrock teleport"));
		assertEquals(TELEPORTS_SEERS_VILLAGE, matchDestinationKey("seers' village camelot teleport"));
		assertEquals(TELEPORTS_YANILLE, matchDestinationKey("yanille watchtower teleport"));
		// ...and the plain cast still falls through to the base town.
		assertEquals(TELEPORTS_VARROCK, matchDestinationKey("cast varrock teleport"));
		assertEquals(TELEPORTS_CAMELOT, matchDestinationKey("cast camelot teleport"));
		assertEquals(TELEPORTS_WATCHTOWER, matchDestinationKey("cast watchtower teleport"));
	}

	// ── Aliases: nexus row name vs spell/item name ────────────────────────

	@Test
	public void aliasesReachTheSameKey()
	{
		assertEquals(TELEPORTS_MOONCLAN, matchDestinationKey("moonclan teleport"));
		assertEquals(TELEPORTS_MOONCLAN, matchDestinationKey("p :  Lunar Isle"));      // nexus row
		assertEquals(TELEPORTS_TROLL_STRONGHOLD, matchDestinationKey("t :  Troll Stronghold"));
		assertEquals(TELEPORTS_TROLL_STRONGHOLD, matchDestinationKey("break stony basalt"));
		assertEquals(TELEPORTS_WEISS, matchDestinationKey("w :  Weiss"));
		assertEquals(TELEPORTS_WEISS, matchDestinationKey("break icy basalt"));
	}

	// ── Skillcape + spell both reach House ────────────────────────────────

	@Test
	public void houseFromCapeAndSpell()
	{
		assertEquals(TELEPORTS_HOUSE, matchDestinationKey("tele to poh construct. cape(t)"));
		assertEquals(TELEPORTS_HOUSE, matchDestinationKey("cast teleport to house"));
	}

	// ── Coverage across the spellbooks ────────────────────────────────────

	@Test
	public void spellbookDestinationsResolve()
	{
		assertEquals(TELEPORTS_SENNTISTEN, matchDestinationKey("cast senntisten teleport"));
		assertEquals(TELEPORTS_KHARYRLL, matchDestinationKey("c :  Kharyrll"));
		assertEquals(TELEPORTS_ARCEUUS_LIBRARY, matchDestinationKey("cast arceuus library teleport"));
		assertEquals(TELEPORTS_BARROWS, matchDestinationKey("cast barrows teleport"));
		assertEquals(TELEPORTS_FISHING_GUILD, matchDestinationKey("cast fishing guild teleport"));
		assertEquals(TELEPORTS_POLLNIVNEACH, matchDestinationKey("break pollnivneach teleport"));
	}

	// ── Skillcapes: matched by cape name (bare "Teleport" carries no place) ──

	@Test
	public void skillcapesMatchByCapeName()
	{
		assertEquals(TELEPORTS_WARRIORS_GUILD, matchDestinationKey("teleport strength cape(t)"));
		assertEquals(TELEPORTS_CRAFTING_GUILD, matchDestinationKey("teleport crafting cape(t)"));
		assertEquals(TELEPORTS_FARMING_GUILD, matchDestinationKey("teleport farming cape"));
		assertEquals(TELEPORTS_HUNTER_GUILD, matchDestinationKey("teleport hunter cape(t)"));
		assertEquals(TELEPORTS_LEGENDS_GUILD, matchDestinationKey("teleport quest point cape(t)"));
		assertEquals(TELEPORTS_DIARY_REGION, matchDestinationKey("teleport achievement diary cape(t)"));
		assertEquals(TELEPORTS_FALO, matchDestinationKey("teleport music cape(t)"));
		assertEquals(TELEPORTS_PANDEMONIUM, matchDestinationKey("teleport sailing cape(t)"));
		// Fishing cape names its place in the option text
		assertEquals(TELEPORTS_OTTOS_GROTTO, matchDestinationKey("otto's grotto fishing cape(t)"));
		assertEquals(TELEPORTS_FISHING_GUILD, matchDestinationKey("fishing guild fishing cape(t)"));
	}

	@Test
	public void sailingCapeBoatOptionResolvesToBoat()
	{
		// The cape's separate "Boat Teleport" moves the boat; 'boat' (ordered earlier)
		// wins over 'sailing cape', and a boat-move produces no player jump so it
		// self-excludes at runtime regardless.
		assertEquals(TELEPORTS_BOAT, matchDestinationKey("boat teleport sailing cape(t)"));
	}

	// ── Nothing tracked → null (falls to the Nexus catch-all when fromNexus) ──

	@Test
	public void untrackedAndNullAreSafe()
	{
		assertNull(matchDestinationKey(null));
		assertNull(matchDestinationKey("break teleport to bounty target"));
		assertNull(matchDestinationKey("rub games necklace")); // a rub names no place yet
	}

	// ── Jewellery places — substring order keeps compounds ahead of their words ──

	@Test
	public void jewelleryDestinationsResolve()
	{
		assertEquals(TELEPORTS_CASTLE_WARS, matchDestinationKey("castle wars ring of dueling(8)"));
		assertEquals(TELEPORTS_FEROX_ENCLAVE, matchDestinationKey("ferox enclave ring of dueling(8)"));
		assertEquals(TELEPORTS_EDGEVILLE, matchDestinationKey("edgeville amulet of glory(6)"));
		assertEquals(TELEPORTS_WARRIORS_GUILD, matchDestinationKey("warriors' guild combat bracelet(4)"));
		assertEquals(TELEPORTS_MINING_GUILD, matchDestinationKey("mining guild skills necklace(4)"));
		assertEquals(TELEPORTS_WINTERTODT_CAMP, matchDestinationKey("wintertodt camp games necklace(8)"));
	}

	@Test
	public void colosseumBeatsTheFortisCity()
	{
		assertEquals(TELEPORTS_COLOSSEUM, matchDestinationKey("fortis colosseum ring of dueling(8)"));
	}

	@Test
	public void fossilIslandBeatsTheDigsitePendantsOwnName()
	{
		// The pendant's item name carries "digsite", so a fossil-island hop must
		// resolve on the destination, not the jewellery's name.
		assertEquals(TELEPORTS_FOSSIL_ISLAND, matchDestinationKey("fossil island digsite pendant(5)"));
		assertEquals(TELEPORTS_DIGSITE, matchDestinationKey("digsite digsite pendant(5)"));
	}

	@Test
	public void houseOnTheHillBeatsThePoh()
	{
		// "house on the hill" contains "house" — the Fossil Island row must sit
		// above the POH row or every hop there credits the player's house.
		assertEquals(TELEPORTS_FOSSIL_ISLAND, matchDestinationKey("house on the hill digsite pendant(5)"));
		// ...and the plain POH routes still resolve to House.
		assertEquals(TELEPORTS_HOUSE, matchDestinationKey("cast teleport to house"));
		assertEquals(TELEPORTS_HOUSE, matchDestinationKey("tele to poh construct. cape(t)"));
	}

	@Test
	public void barbarianOutpostBeatsTheOutpost()
	{
		assertEquals(TELEPORTS_BARBARIAN_OUTPOST, matchDestinationKey("barbarian outpost games necklace(8)"));
		assertEquals(TELEPORTS_THE_OUTPOST, matchDestinationKey("the outpost necklace of passage(5)"));
	}

	@Test
	public void everyAllowlistedJewelleryOptionLandsOnAPlace()
	{
		// Each allowlisted item's LAST destination — the ones that fell to
		// total-only before their rows existed.
		assertEquals(TELEPORTS_SLEPE, matchDestinationKey("slepe drakan's medallion"));
		assertEquals(TELEPORTS_EAGLES_EYRIE, matchDestinationKey("eagle's eyrie necklace of passage(5)"));
		assertEquals(TELEPORTS_DONDAKANS_ROCK, matchDestinationKey("dondakan's rock ring of wealth(5)"));
		// The ring of returning goes to the POH but its label never says "house".
		assertEquals(TELEPORTS_HOUSE, matchDestinationKey("teleport ring of returning(8)"));
	}

	@Test
	public void lithkrenBeatsTheDigsitePendantsOwnName()
	{
		// Without its own row this label falls through to "digsite" (the pendant's
		// name) and MIS-attributes the hop, so "lithkren" must sit above "digsite".
		assertEquals(TELEPORTS_LITHKREN, matchDestinationKey("lithkren digsite pendant(5)"));
	}

	@Test
	public void chronicleResolvesToChampionsGuild()
	{
		// The Chronicle's bare "Teleport" arms via the tele branch but names no
		// place; the book's own name is the destination label.
		assertEquals(TELEPORTS_CHAMPIONS_GUILD, matchDestinationKey("teleport chronicle"));
	}

	// ── POH portal-chamber portals: option "Enter", target "<Place> Portal" ──

	@Test
	public void pohPortalTargetsResolveToTheirPlace()
	{
		assertEquals(TELEPORTS_VARROCK, matchDestinationKey("varrock portal"));
		assertEquals(TELEPORTS_TROLL_STRONGHOLD, matchDestinationKey("troll stronghold portal"));
		assertEquals(TELEPORTS_FORTIS, matchDestinationKey("civitas illa fortis portal"));
		// Ape Atoll's portal is named for the town, not the spell.
		assertEquals(TELEPORTS_APE_ATOLL, matchDestinationKey("marim portal"));
	}

	@Test
	public void unnamedPortalsStayExcluded()
	{
		// The match-guard on the "Enter … Portal" branch: the bare house exit and
		// minigame portals name no tracked place, so they never arm a teleport.
		assertNull(matchDestinationKey("portal"));
		assertNull(matchDestinationKey("free-for-all portal"));
	}

	// ── Named items: option carries no "tele", the item name is the place ──

	@Test
	public void namedItemsResolveByTheirOwnName()
	{
		assertEquals(TELEPORTS_ECTOFUNTUS, matchDestinationKey("empty ectophial"));
		assertEquals(TELEPORTS_GRAND_TREE, matchDestinationKey("commune royal seed pod"));
	}



	@Test
	public void everydayItemsResolve()
	{
		assertEquals(TELEPORTS_ECTOFUNTUS, matchDestinationKey("empty ectophial"));
		assertEquals(TELEPORTS_GRAND_TREE, matchDestinationKey("commune royal seed pod"));
		assertEquals(TELEPORTS_CHAMPIONS_GUILD, matchDestinationKey("teleport chronicle"));
		assertEquals(TELEPORTS_KOUREND, matchDestinationKey("the fisher's flute kharedst's memoirs"));
		assertEquals(TELEPORTS_HOSIDIUS, matchDestinationKey("lunch by the lancalliums (hosidius) kharedst's memoirs"));
		assertEquals(TELEPORTS_OBELISK, matchDestinationKey("activate obelisk"));
		assertEquals(TELEPORTS_ELEMENTAL_ALTARS, matchDestinationKey("fire altar ring of the elements(4)"));
		assertEquals(TELEPORTS_GIANTS_FOUNDRY, matchDestinationKey("giants' foundry giantsoul amulet(6)"));
		assertEquals(TELEPORTS_DONDAKANS_ROCK, matchDestinationKey("dondakan's rock ring of wealth(5)"));
		assertEquals(TELEPORTS_EAGLES_EYRIE, matchDestinationKey("eagle's eyrie necklace of passage(5)"));
		assertEquals(TELEPORTS_SLEPE, matchDestinationKey("slepe drakan's medallion"));
		assertEquals(TELEPORTS_TAVERLEY, matchDestinationKey("break taverley teleport"));
	}

	@Test
	public void pohPortalRoomsResolve()
	{
		assertEquals(TELEPORTS_VARROCK, matchDestinationKey("varrock portal"));
		assertEquals(TELEPORTS_LUMBRIDGE, matchDestinationKey("lumbridge portal"));
	}

	@Test
	public void draynorManorStillBeatsDraynorVillage()
	{
		assertEquals(TELEPORTS_DRAYNOR_MANOR, matchDestinationKey("cast draynor manor teleport"));
		assertEquals(TELEPORTS_DRAYNOR, matchDestinationKey("draynor village amulet of glory(6)"));
	}

	// ── Scroll-of-redirection house tabs: all eight redirects have a name ──

	@Test
	public void redirectedHouseTabsAllResolve()
	{
		assertEquals(TELEPORTS_RIMMINGTON, matchDestinationKey("break rimmington teleport"));
		assertEquals(TELEPORTS_TAVERLEY, matchDestinationKey("break taverley teleport"));
		assertEquals(TELEPORTS_RELLEKKA, matchDestinationKey("break rellekka teleport"));
		assertEquals(TELEPORTS_BRIMHAVEN, matchDestinationKey("break brimhaven teleport"));
		assertEquals(TELEPORTS_HOSIDIUS, matchDestinationKey("break hosidius teleport"));
		assertEquals(TELEPORTS_PRIFDDINAS, matchDestinationKey("break prifddinas teleport"));
		// ...and the two that already had keys keep them.
		assertEquals(TELEPORTS_YANILLE, matchDestinationKey("break yanille teleport"));
		assertEquals(TELEPORTS_POLLNIVNEACH, matchDestinationKey("break pollnivneach teleport"));
		// The teleport crystal's "Activate" names no place; the item name is Prif.
		assertEquals(TELEPORTS_PRIFDDINAS, matchDestinationKey("activate teleport crystal (4)"));
		assertEquals(TELEPORTS_PRIFDDINAS, matchDestinationKey("activate eternal teleport crystal"));
	}
}
