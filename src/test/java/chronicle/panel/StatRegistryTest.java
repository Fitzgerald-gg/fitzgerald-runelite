/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle.panel;

import static org.junit.Assert.assertEquals;
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
		// destinations are bare names — the pixel font has no arrow glyph
		assertEquals("Varrock", StatRegistry.label("teleportsVarrock"));
		assertEquals("Teleports", StatRegistry.label("teleportsTotal"));
	}

	@Test
	public void familiesFileSensibly()
	{
		assertEquals("Combat", StatRegistry.family("damageDealt"));
		assertEquals("Combat", StatRegistry.family("ammoConsumed"));
		assertEquals("Travel", StatRegistry.family("teleportsViaSpell"));
		assertEquals("Travel", StatRegistry.family("tilesRan"));
		assertEquals("Travel", StatRegistry.family("distanceWalked"));
		assertEquals("Living", StatRegistry.family("potionDoses"));
		assertEquals("Skilling", StatRegistry.family("creaturesTrapped"));
		assertEquals("Economy", StatRegistry.family("coinsFromAlchemy"));
		assertEquals("Economy", StatRegistry.family("untakenLootCount"));
		// offerings ARE the Prayer craft — they file under Skilling now
		assertEquals("Skilling", StatRegistry.family("bonesBuried"));
		assertEquals("Prayer", StatRegistry.subgroup("bonesBuried"));
		assertEquals("Skilling", StatRegistry.family("demonicOfferingXp"));
		assertEquals("Prayer", StatRegistry.subgroup("demonicOfferingXp"));
		assertEquals("Skilling", StatRegistry.family("bloodveldHeadsReanimated"));
		assertEquals("Smithing", StatRegistry.subgroup("steelItemsSmithed"));
		assertEquals("Runecraft", StatRegistry.subgroup("wrathRunecrafted"));
		// unknown keys land visibly, not in a junk tab
		assertEquals("Living", StatRegistry.family("clueScrollsCompleted"));
		assertEquals("Elsewhere", StatRegistry.subgroup("clueScrollsCompleted"));
	}

	@Test
	public void labelsPolish()
	{
		// item-plus-action keys stutter; polish collapses the doubled word
		assertEquals("Logs chopped", StatRegistry.prettify("logsLogsChopped"));
		assertEquals("Bones buried", StatRegistry.prettify("bonesBonesBuried"));
		assertEquals("Guard (lvl 21) pickpockets",
			StatRegistry.prettify("guard(level21)Pickpockets"));
	}

	@Test
	public void diagnosticKeysHide()
	{
		assertEquals(true, StatRegistry.hidden("__probe"));
		assertEquals(false, StatRegistry.hidden("damageDealt"));
	}

	@Test
	public void gpKeysDetected()
	{
		assertEquals(true, StatRegistry.isGp("itemsDroppedValue"));
		assertEquals(false, StatRegistry.isGp("damageDealt"));
	}
}
