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
		assertNull(matchDestinationKey("rub games necklace")); // jewellery, out of scope
	}
}
