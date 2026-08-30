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
		assertEquals("→ Varrock", StatRegistry.label("teleportsVarrock"));
	}

	@Test
	public void familiesFileSensibly()
	{
		assertEquals("Combat", StatRegistry.family("damageDealt"));
		assertEquals("Travel", StatRegistry.family("teleportsViaSpell"));
		assertEquals("Travel", StatRegistry.family("tilesRan"));
		assertEquals("Living", StatRegistry.family("potionDoses"));
		assertEquals("Skilling", StatRegistry.family("creaturesTrapped"));
		assertEquals("Economy", StatRegistry.family("coinsFromAlchemy"));
		assertEquals("Offerings", StatRegistry.family("bonesBuried"));
		assertEquals("Other", StatRegistry.family("clueScrollsCompleted"));
	}

	@Test
	public void gpKeysDetected()
	{
		assertEquals(true, StatRegistry.isGp("itemsDroppedValue"));
		assertEquals(false, StatRegistry.isGp("damageDealt"));
	}
}
