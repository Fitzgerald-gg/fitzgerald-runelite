/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;

/**
 * The tick that carries a craft to the deriver. What left the pack is half the
 * story; how much of it left is the other half, and an altar eats a whole pack
 * of essence on one click.
 */
public class SkillingStatTrackerTest
{
	private static final int PURE_ESSENCE = 7936;
	private static final int GUARDIAN_ESSENCE = 26879;
	private static final int NATURE_RUNE = 561;

	private final Map<Integer, String> names = new HashMap<>();
	private StatStore store;
	private ItemContainer pack;
	private SkillingStatTracker tracker;
	private int career;

	@Before
	public void setUp()
	{
		names.put(PURE_ESSENCE, "Pure essence");
		names.put(GUARDIAN_ESSENCE, "Guardian essence");
		names.put(NATURE_RUNE, "Nature rune");

		store = new StatStore();
		Client client = Mockito.mock(Client.class);
		pack = Mockito.mock(ItemContainer.class);
		Mockito.when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(pack);

		ItemManager items = Mockito.mock(ItemManager.class);
		Mockito.when(items.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(items.getItemComposition(Mockito.anyInt())).thenAnswer(inv ->
		{
			ItemComposition c = Mockito.mock(ItemComposition.class);
			Mockito.when(c.getName()).thenReturn(names.getOrDefault(
				(Integer) inv.getArgument(0), ""));
			return c;
		});

		career = 1_000_000;
		tracker = new SkillingStatTracker(store, client, new SkillDeriver(items, store, new Gson()));
	}

	private void holds(Item... contents)
	{
		Mockito.when(pack.getItems()).thenReturn(contents);
		tracker.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), pack));
	}

	// the drop is a career total, so the tracker needs a reading to measure from
	private void xp(int delta)
	{
		tracker.onStatChanged(new StatChanged(Skill.RUNECRAFT, career, 1, 1));
		career += delta;
		tracker.onStatChanged(new StatChanged(Skill.RUNECRAFT, career, 1, 1));
	}

	@Test
	public void theEssenceThatLeftThePackIsWhatTheCraftIsCountedIn()
	{
		holds(new Item(PURE_ESSENCE, 27));
		xp(243);
		// 27 essence in, 54 nature runes out
		holds(new Item(NATURE_RUNE, 54));
		tracker.onGameTick(new GameTick());

		assertEquals(54, store.getStat("runesCrafted"));
		assertEquals(54, store.getStat("natureRunecrafted"));
		assertEquals(27, store.getStat("essenceCrafted"));
	}

	@Test
	public void theRiftsOwnEssenceIsCarriedButNeverCounted()
	{
		holds(new Item(GUARDIAN_ESSENCE, 25));
		xp(50);
		holds(new Item(NATURE_RUNE, 50));
		tracker.onGameTick(new GameTick());

		assertEquals(50, store.getStat("runesCrafted"));
		assertEquals(0, store.getStat("essenceCrafted"));
	}

	@Test
	public void aCraftWithNothingSeenLeavingThePackCountsNoEssence()
	{
		// no inventory reading at all: the tuple's consumed fields go out empty
		xp(243);
		tracker.onGameTick(new GameTick());

		assertEquals(1, store.getStat("runesCrafted"));
		assertEquals(0, store.getStat("essenceCrafted"));
	}
}
