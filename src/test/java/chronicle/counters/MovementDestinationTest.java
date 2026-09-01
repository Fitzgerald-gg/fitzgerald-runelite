/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import org.junit.Test;

import static chronicle.counters.MovementStatTracker.matchDestinationKey;
import static chronicle.counters.StatKeys.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Destination attribution for MovementStatTracker. A nexus row, a spell and a tablet to
 * one place all have to land on the same counter, and the DESTINATIONS order has to keep
 * every contained name above the shorter one inside it. Inputs are the real menu strings:
 * a nexus row is keybind-prefixed ("5 :  Camelot"), a spell or tab arrives as option and
 * target joined ("cast varrock teleport").
 */
public class MovementDestinationTest
{
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

	@Test
	public void longerContainedNameWinsOverShorter()
	{
		// "ape atoll dungeon" sits above "ape atoll"
		assertEquals(TELEPORTS_APE_ATOLL_DUNGEON, matchDestinationKey("v :  Ape Atoll Dungeon"));
		assertEquals(TELEPORTS_APE_ATOLL, matchDestinationKey("g :  Ape Atoll"));
		// "west ardougne" sits above "ardougne"
		assertEquals(TELEPORTS_WEST_ARDOUGNE, matchDestinationKey("w :  West Ardougne"));
		assertEquals(TELEPORTS_ARDOUGNE, matchDestinationKey("cast ardougne teleport"));
	}

	@Test
	public void diarySwitchBeatsBaseTown()
	{
		// a switched cast carries both names, so the switch destination has to win
		assertEquals(TELEPORTS_GRAND_EXCHANGE, matchDestinationKey("grand exchange varrock teleport"));
		assertEquals(TELEPORTS_SEERS_VILLAGE, matchDestinationKey("seers' village camelot teleport"));
		assertEquals(TELEPORTS_YANILLE, matchDestinationKey("yanille watchtower teleport"));
		// plain casts still land on the base town
		assertEquals(TELEPORTS_VARROCK, matchDestinationKey("cast varrock teleport"));
		assertEquals(TELEPORTS_CAMELOT, matchDestinationKey("cast camelot teleport"));
		assertEquals(TELEPORTS_WATCHTOWER, matchDestinationKey("cast watchtower teleport"));
	}

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

	@Test
	public void houseFromCapeAndSpell()
	{
		assertEquals(TELEPORTS_HOUSE, matchDestinationKey("tele to poh construct. cape(t)"));
		assertEquals(TELEPORTS_HOUSE, matchDestinationKey("cast teleport to house"));
	}

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

	@Test
	public void skillcapesMatchByCapeName()
	{
		// a cape's bare "Teleport" names no place, so the match comes off the cape name
		assertEquals(TELEPORTS_WARRIORS_GUILD, matchDestinationKey("teleport strength cape(t)"));
		assertEquals(TELEPORTS_CRAFTING_GUILD, matchDestinationKey("teleport crafting cape(t)"));
		assertEquals(TELEPORTS_FARMING_GUILD, matchDestinationKey("teleport farming cape"));
		assertEquals(TELEPORTS_HUNTER_GUILD, matchDestinationKey("teleport hunter cape(t)"));
		assertEquals(TELEPORTS_LEGENDS_GUILD, matchDestinationKey("teleport quest point cape(t)"));
		assertEquals(TELEPORTS_DIARY_REGION, matchDestinationKey("teleport achievement diary cape(t)"));
		assertEquals(TELEPORTS_FALO, matchDestinationKey("teleport music cape(t)"));
		assertEquals(TELEPORTS_PANDEMONIUM, matchDestinationKey("teleport sailing cape(t)"));
		// the Fishing cape names its place in the option text
		assertEquals(TELEPORTS_OTTOS_GROTTO, matchDestinationKey("otto's grotto fishing cape(t)"));
		assertEquals(TELEPORTS_FISHING_GUILD, matchDestinationKey("fishing guild fishing cape(t)"));
	}

	@Test
	public void sailingCapeBoatOptionResolvesToBoat()
	{
		// "boat" is ordered above "sailing cape", so the cape's boat option credits Boat
		assertEquals(TELEPORTS_BOAT, matchDestinationKey("boat teleport sailing cape(t)"));
	}

	@Test
	public void untrackedAndNullAreSafe()
	{
		// a null key still credits Nexus when the click came from the nexus list
		assertNull(matchDestinationKey(null));
		assertNull(matchDestinationKey("break teleport to bounty target"));
		assertNull(matchDestinationKey("rub games necklace")); // the rub names no place; the row click after does
	}

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
		// the pendant's item name carries "digsite", so "fossil island" has to match first
		assertEquals(TELEPORTS_FOSSIL_ISLAND, matchDestinationKey("fossil island digsite pendant(5)"));
		assertEquals(TELEPORTS_DIGSITE, matchDestinationKey("digsite digsite pendant(5)"));
	}

	@Test
	public void houseOnTheHillBeatsThePoh()
	{
		// "house on the hill" contains "house", so its row sits above the POH row
		assertEquals(TELEPORTS_FOSSIL_ISLAND, matchDestinationKey("house on the hill digsite pendant(5)"));
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
		assertEquals(TELEPORTS_SLEPE, matchDestinationKey("slepe drakan's medallion"));
		assertEquals(TELEPORTS_EAGLES_EYRIE, matchDestinationKey("eagle's eyrie necklace of passage(5)"));
		assertEquals(TELEPORTS_DONDAKANS_ROCK, matchDestinationKey("dondakan's rock ring of wealth(5)"));
		// the ring of returning goes to the POH, but its label never says "house"
		assertEquals(TELEPORTS_HOUSE, matchDestinationKey("teleport ring of returning(8)"));
	}

	@Test
	public void lithkrenBeatsTheDigsitePendantsOwnName()
	{
		// without its own row this label falls through to "digsite", the pendant's name
		assertEquals(TELEPORTS_LITHKREN, matchDestinationKey("lithkren digsite pendant(5)"));
	}

	@Test
	public void pohPortalTargetsResolveToTheirPlace()
	{
		// the portal chamber's option is "Enter" and its target "<Place> Portal"
		assertEquals(TELEPORTS_VARROCK, matchDestinationKey("varrock portal"));
		assertEquals(TELEPORTS_TROLL_STRONGHOLD, matchDestinationKey("troll stronghold portal"));
		assertEquals(TELEPORTS_FORTIS, matchDestinationKey("civitas illa fortis portal"));
		assertEquals(TELEPORTS_APE_ATOLL, matchDestinationKey("marim portal")); // Ape Atoll's portal carries the town name
	}

	@Test
	public void unnamedPortalsStayExcluded()
	{
		// the bare house exit and the minigame portals name no tracked place, so the
		// "Enter ... Portal" branch never arms on them
		assertNull(matchDestinationKey("portal"));
		assertNull(matchDestinationKey("free-for-all portal"));
	}

	@Test
	public void everydayItemsResolve()
	{
		// "Empty" and "Commune" say tele nowhere, so the item name is the place
		assertEquals(TELEPORTS_ECTOFUNTUS, matchDestinationKey("empty ectophial"));
		assertEquals(TELEPORTS_GRAND_TREE, matchDestinationKey("commune royal seed pod"));
		assertEquals(TELEPORTS_CHAMPIONS_GUILD, matchDestinationKey("teleport chronicle")); // bare "Teleport"; the book's name is the place
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

	@Test
	public void redirectedHouseTabsAllResolve()
	{
		assertEquals(TELEPORTS_RIMMINGTON, matchDestinationKey("break rimmington teleport"));
		assertEquals(TELEPORTS_TAVERLEY, matchDestinationKey("break taverley teleport"));
		assertEquals(TELEPORTS_RELLEKKA, matchDestinationKey("break rellekka teleport"));
		assertEquals(TELEPORTS_BRIMHAVEN, matchDestinationKey("break brimhaven teleport"));
		assertEquals(TELEPORTS_HOSIDIUS, matchDestinationKey("break hosidius teleport"));
		assertEquals(TELEPORTS_PRIFDDINAS, matchDestinationKey("break prifddinas teleport"));
		assertEquals(TELEPORTS_YANILLE, matchDestinationKey("break yanille teleport"));
		assertEquals(TELEPORTS_POLLNIVNEACH, matchDestinationKey("break pollnivneach teleport"));
		// the teleport crystal's "Activate" names no place; the item name is Prif
		assertEquals(TELEPORTS_PRIFDDINAS, matchDestinationKey("activate teleport crystal (4)"));
		assertEquals(TELEPORTS_PRIFDDINAS, matchDestinationKey("activate eternal teleport crystal"));
	}
}
