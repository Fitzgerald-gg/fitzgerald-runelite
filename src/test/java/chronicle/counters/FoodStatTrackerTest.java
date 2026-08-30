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
 * Covers the structural per-food key derivation. The counting itself is driven by
 * client events and is exercised in-game; what is pinned here is the naming, because
 * a bad key is invisible at runtime — it just quietly mints a second counter that
 * never aggregates with the real one.
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
		// These two predate the structural derivation; the rule has to keep matching
		// them or the existing server-side totals would fork.
		assertEquals("troutEaten", FoodStatTracker.perFoodKey("Trout"));
		assertEquals("cabbageEaten", FoodStatTracker.perFoodKey("Cabbage"));
	}

	@Test
	public void punctuationAndApostrophesCollapse()
	{
		assertEquals("chefsDelightEaten", FoodStatTracker.perFoodKey("Chef's delight"));
		assertEquals("admiralPieEaten", FoodStatTracker.perFoodKey("Admiral pie"));
	}

	/**
	 * The regression this file was written for: digits survive the key builder, so a
	 * part-eaten food used to mint a fresh junk key per bite ("2/3 cake" ->
	 * 23CakeEaten) instead of aggregating onto the whole item.
	 */
	@Test
	public void partEatenFoodsFoldOntoTheWholeItem()
	{
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
		// A whole item is already canonical and must pass through untouched.
		assertEquals("Chocolate cake", FoodStatTracker.baseFoodName("Chocolate cake"));
		assertEquals("Cooked karambwan", FoodStatTracker.baseFoodName("Cooked karambwan"));
		// Only a LEADING qualifier is a portion marker; "half" elsewhere is the name.
		assertEquals("Half moon", FoodStatTracker.baseFoodName("Half moon"));
	}

	@Test
	public void unnamedItemsProduceNoKey()
	{
		// itemName() returns "" for an unresolvable id; that must not mint "Eaten".
		assertEquals("", FoodStatTracker.perFoodKey(""));
		assertEquals("", FoodStatTracker.perFoodKey("   "));
	}

	@Test
	public void potionNameFromDrinkMessage()
	{
		// The dose tally arrives as a second sentence; the first stop ends the name.
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
