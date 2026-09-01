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

import static org.junit.Assert.assertEquals;

/**
 * Key derivation for the per-food and per-potion counters. A wrong key fails silently:
 * it splits one tally across two counters.
 */
public class FoodStatTrackerTest
{
	@Test
	public void plainFoodsBecomeCamelCaseKeys()
	{
		assertEquals("lobsterEaten", FoodStatTracker.perFoodKey("Lobster"));
		assertEquals("sharkEaten", FoodStatTracker.perFoodKey("Shark"));
		assertEquals("mantaRayEaten", FoodStatTracker.perFoodKey("Manta ray"));
		assertEquals("cookedKarambwanEaten", FoodStatTracker.perFoodKey("Cooked karambwan"));
	}

	@Test
	public void historicalKeyNamesAreReproduced()
	{
		// These names predate the derivation rule. If it stops matching them the
		// journal totals fork.
		assertEquals("troutEaten", FoodStatTracker.perFoodKey("Trout"));
		assertEquals("cabbageEaten", FoodStatTracker.perFoodKey("Cabbage"));
	}

	@Test
	public void punctuationAndApostrophesCollapse()
	{
		assertEquals("chefsDelightEaten", FoodStatTracker.perFoodKey("Chef's delight"));
		assertEquals("admiralPieEaten", FoodStatTracker.perFoodKey("Admiral pie"));
	}

	@Test
	public void partEatenFoodsFoldOntoTheWholeItem()
	{
		// Digits survive the key builder, so leaving the portion prefix on would give
		// a fresh 23CakeEaten per bite.
		assertEquals("cakeEaten", FoodStatTracker.perFoodKey("2/3 cake"));
		assertEquals("cakeEaten", FoodStatTracker.perFoodKey("1/3 cake"));
		assertEquals("cakeEaten", FoodStatTracker.perFoodKey("Slice of cake"));
		assertEquals("plainPizzaEaten", FoodStatTracker.perFoodKey("1/2 plain pizza"));
		assertEquals("pineapplePizzaEaten", FoodStatTracker.perFoodKey("Half a pineapple pizza"));
		assertEquals("admiralPieEaten", FoodStatTracker.perFoodKey("Half an admiral pie"));
	}

	@Test
	public void baseNameStripsOnlyTheLeadingQualifier()
	{
		assertEquals("cake", FoodStatTracker.baseFoodName("2/3 cake"));
		assertEquals("Chocolate cake", FoodStatTracker.baseFoodName("Chocolate cake"));
		assertEquals("Cooked karambwan", FoodStatTracker.baseFoodName("Cooked karambwan"));
		// "half" only marks a portion when an "a"/"an" follows it. Half moon is an item.
		assertEquals("Half moon", FoodStatTracker.baseFoodName("Half moon"));
	}

	@Test
	public void unnamedItemsProduceNoKey()
	{
		// itemName() returns "" for an unresolvable id; that must not become "Eaten".
		assertEquals("", FoodStatTracker.perFoodKey(""));
		assertEquals("", FoodStatTracker.perFoodKey("   "));
	}

	@Test
	public void potionNameFromDrinkMessage()
	{
		// The doses-left tally is a second sentence, so the first full stop ends the name.
		assertEquals("prayer potion", FoodStatTracker.potionName(
			"You drink some of your prayer potion. You have 2 doses of potion left."));
		assertEquals("divine super combat potion", FoodStatTracker.potionName(
			"You drink some of your divine super combat potion."));
		assertEquals("Saradomin brew", FoodStatTracker.potionName(
			"You drink some of the Saradomin brew."));
		assertEquals("", FoodStatTracker.potionName("You drink the wine."));
	}

	@Test
	public void perPotionKeyMintsLikePerFoodKey()
	{
		assertEquals("prayerPotionDoses", FoodStatTracker.perPotionKey("prayer potion"));
		assertEquals("divineSuperCombatPotionDoses",
			FoodStatTracker.perPotionKey("divine super combat potion"));
		assertEquals("ranarrWeedTeaDoses", FoodStatTracker.perPotionKey("Ranarr weed tea"));
		assertEquals("", FoodStatTracker.perPotionKey(""));
	}
}
