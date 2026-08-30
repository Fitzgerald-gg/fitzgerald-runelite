/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class StatRegistryTest
{
	@Test
	public void prettifyReadsWell()
	{
		assertEquals("Tiles walked", StatRegistry.label("tilesWalked"));
		assertEquals("Vials shattered", StatRegistry.prettify("vialsShattered"));
		assertEquals("Chompy birds plucked", StatRegistry.prettify("chompyBirdsPlucked"));
	}

	@Test
	public void teleportFamiliesLabelAsAnnotations()
	{
		assertEquals("— by jewellery", StatRegistry.label("teleportsViaJewellery"));
		assertEquals("Teleports", StatRegistry.label("teleportsTotal"));
		// destinations read as place names, punctuation restored
		assertEquals("Varrock", StatRegistry.label("teleportsVarrock"));
		assertEquals("Seers' Village", StatRegistry.label("teleportsSeersVillage"));
		assertEquals("Kourend (Memoirs)", StatRegistry.label("teleportsKharedst"));
	}

	@Test
	public void facetsMatchTheSite()
	{
		// the site's four facets, its ownership sets ported verbatim
		assertEquals("Combat", StatRegistry.family("damageDealt"));
		assertEquals("Living", StatRegistry.family("hitpointsRegenerated"));
		assertEquals("Living", StatRegistry.family("divinePotionDamage"));
		assertEquals("Living", StatRegistry.family("foodEaten"));
		assertEquals("Living", StatRegistry.family("sharkEaten"));
		assertEquals("Ledger & Roads", StatRegistry.family("ammoConsumed"));
		assertEquals("Ledger & Roads", StatRegistry.family("distanceWalked"));
		assertEquals("Ledger & Roads", StatRegistry.family("teleportsVarrock"));
		assertEquals("Ledger & Roads", StatRegistry.family("coinsFromAlchemy"));
		assertEquals("Ledger & Roads", StatRegistry.family("untakenLootCount"));
		assertEquals("Skilling", StatRegistry.family("willowLogsChopped"));
		assertEquals("Skilling", StatRegistry.family("bonesBuried"));
		assertEquals("Skilling", StatRegistry.family("wyrmBonesBuried"));
		assertEquals("Skilling", StatRegistry.family("creaturesTrapped"));
		// unclaimed keys land visibly among the odds & ends
		assertEquals("Ledger & Roads", StatRegistry.family("clueScrollsCompleted"));
		assertEquals("Odds & ends", StatRegistry.subgroup("clueScrollsCompleted"));
	}

	@Test
	public void skillOwnershipClaimsBeforeSuffixes()
	{
		// Hunter's explicit claim beats Fishing's broad "Caught" suffix
		assertEquals("Hunter", StatRegistry.skillOf("implingsCaught"));
		assertEquals("Fishing", StatRegistry.skillOf("anglerfishCaught"));
		assertEquals("Cooking", StatRegistry.skillOf("foodBurned"));
		assertEquals("Firemaking", StatRegistry.skillOf("willowLogsBurned"));
		assertEquals("Thieving", StatRegistry.skillOf("guardPickpockets"));
		assertEquals("Thieving", StatRegistry.skillOf("guardFailedPickpockets"));
		assertEquals("Prayer", StatRegistry.skillOf("trollHeadsReanimated"));
		assertEquals("Runecraft", StatRegistry.skillOf("wrathRunecrafted"));
		assertEquals("Smithing", StatRegistry.skillOf("steelBarsSmelted"));
	}

	@Test
	public void floorsHeadSectionsNotRows()
	{
		// generic totals stay out of the rows and head their section
		assertTrue(StatRegistry.isFloor("logsChopped"));
		assertTrue(StatRegistry.isFloor("bonesBuried"));
		assertTrue(StatRegistry.isFloor("teleportsTotal"));
		assertFalse(StatRegistry.isFloor("willowLogsChopped"));
		assertFalse(StatRegistry.isFloor("foodEaten"));   // a flat Living row too
		// typed rows reconcile against the floor; explicit extras do not
		assertTrue(StatRegistry.typed("willowLogsChopped"));
		assertTrue(StatRegistry.typed("sharkEaten"));
		assertFalse(StatRegistry.typed("foodBurned"));
		assertFalse(StatRegistry.typed("implingsCaught"));
	}

	@Test
	public void typedRowsShedTheirVerb()
	{
		// the whole suffix sheds — the section header carries the craft
		assertEquals("Willow", StatRegistry.rowLabel("willowLogsChopped"));
		assertEquals("Wyrm", StatRegistry.rowLabel("wyrmBonesBuried"));
		assertEquals("Shark", StatRegistry.rowLabel("sharkEaten"));
		assertEquals("Wrath", StatRegistry.rowLabel("wrathRunecrafted"));
		// explicit keys keep their full label
		assertEquals("Herbs cleaned", StatRegistry.rowLabel("herbsCleaned"));
	}

	@Test
	public void labelsPolish()
	{
		// item-plus-action keys stutter; polish collapses the doubled word
		assertEquals("Logs chopped", StatRegistry.prettify("logsLogsChopped"));
		assertEquals("Guard (lvl 21) pickpockets",
			StatRegistry.prettify("guard(level21)Pickpockets"));
	}

	@Test
	public void hiddenKeys()
	{
		assertTrue(StatRegistry.hidden("__probe"));
		assertTrue(StatRegistry.hidden("totalXpGained"));
		assertTrue(StatRegistry.hidden("demonicOfferingXp"));
		assertFalse(StatRegistry.hidden("damageDealt"));
	}

	@Test
	public void gpKeysDetected()
	{
		assertTrue(StatRegistry.isGp("itemsDroppedValue"));
		assertFalse(StatRegistry.isGp("damageDealt"));
		assertEquals("The purse", StatRegistry.subgroup("itemsDroppedValue"));
	}
}
