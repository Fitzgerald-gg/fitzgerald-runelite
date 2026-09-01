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
		// destinations read as place names, punctuation and all
		assertEquals("Varrock", StatRegistry.label("teleportsVarrock"));
		assertEquals("Seers' Village", StatRegistry.label("teleportsSeersVillage"));
		assertEquals("Kourend (Memoirs)", StatRegistry.label("teleportsKharedst"));
	}

	@Test
	public void facetsMatchTheSite()
	{
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
		// a key no rule claims still shows up, under odds & ends
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
		// a floor is the generic total; it heads its section instead of listing as a row
		assertTrue(StatRegistry.isFloor("logsChopped"));
		assertTrue(StatRegistry.isFloor("bonesBuried"));
		assertTrue(StatRegistry.isFloor("teleportsTotal"));
		assertFalse(StatRegistry.isFloor("willowLogsChopped"));
		assertFalse(StatRegistry.isFloor("foodEaten"));   // flat Living row
		// typed rows reconcile against the floor; explicit extras do not
		assertTrue(StatRegistry.typed("willowLogsChopped"));
		assertTrue(StatRegistry.typed("sharkEaten"));
		assertFalse(StatRegistry.typed("foodBurned"));
		assertFalse(StatRegistry.typed("implingsCaught"));
	}

	@Test
	public void typedRowsShedTheirVerb()
	{
		// the section header carries the craft, so the row keeps just the item
		assertEquals("Willow", StatRegistry.rowLabel("willowLogsChopped"));
		assertEquals("Wyrm", StatRegistry.rowLabel("wyrmBonesBuried"));
		assertEquals("Shark", StatRegistry.rowLabel("sharkEaten"));
		assertEquals("Wrath", StatRegistry.rowLabel("wrathRunecrafted"));
		// explicit keys keep their full label
		assertEquals("Herbs cleaned", StatRegistry.rowLabel("herbsCleaned"));
	}

	@Test
	public void sailingFilesUnderSkilling()
	{
		assertEquals("Sailing", StatRegistry.skillOf("salvagePulled"));
		assertEquals("Sailing", StatRegistry.skillOf("opulentSalvageSorted"));
		assertEquals("Sailing", StatRegistry.skillOf("gwenithGlideTrialsCompleted"));
		assertEquals("Sailing", StatRegistry.skillOf("portTasksCompleted"));
		assertEquals("Skilling", StatRegistry.family("smallSalvagePulled"));
		assertEquals("Sailing", StatRegistry.subgroup("smallSalvagePulled"));
		assertTrue(StatRegistry.isFloor("salvagePulled"));
		assertTrue(StatRegistry.isFloor("barracudaTrialsCompleted"));
		assertFalse(StatRegistry.isFloor("temporTantrumTrialsCompleted"));
		assertEquals("barracudaTrialsCompleted",
			StatRegistry.suffixFloor("Sailing", "TrialsCompleted"));
		assertEquals("salvageSorted", StatRegistry.suffixFloor("Sailing", "SalvageSorted"));
		assertEquals("Fremennik", StatRegistry.rowLabel("fremennikSalvagePulled"));
		assertEquals("Tempor tantrum", StatRegistry.rowLabel("temporTantrumTrialsCompleted"));
		assertEquals("Port tasks", StatRegistry.rowLabel("portTasksCompleted"));
	}

	@Test
	public void labelsPolish()
	{
		// keys like logsLogsChopped stutter; polish collapses the doubled word
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
