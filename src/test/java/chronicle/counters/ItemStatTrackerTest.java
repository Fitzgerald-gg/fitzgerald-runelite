/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static chronicle.counters.StatKeys.ITEMS_DROPPED_VALUE;
import static chronicle.counters.StatKeys.RESOURCES_DROPPED_VALUE;
import static org.junit.Assert.assertEquals;

/**
 * Pins the drop side of the resource pair.
 *
 * <p>{@code itemsDroppedValue} is the whole bin: every stack ever binned, the
 * junk cleared after a bank trip included. Read beside what the gathering skills
 * produced it would flatter the dropped side badly — one afternoon emptying a
 * bank of old rewards would read as ore abandoned on the ground. The
 * resource-scoped figure exists so the pair compares like with like, and what
 * separates them is the ledger of ids this account has actually gathered.
 */
public class ItemStatTrackerTest
{
	private static final int YEW_LOGS = 1515;
	private static final int RUNE_SCIMITAR = 1333;
	private static final int SLOT = 3;

	private StatStore store;
	private ItemContainer pack;
	private Set<Integer> gathered;
	private ItemStatTracker tracker;

	@Before
	public void setUp()
	{
		store = new StatStore();
		Client client = Mockito.mock(Client.class);
		pack = Mockito.mock(ItemContainer.class);
		Mockito.when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(pack);

		net.runelite.client.game.ItemManager items =
			Mockito.mock(net.runelite.client.game.ItemManager.class);
		Mockito.when(items.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(items.getItemPrice(YEW_LOGS)).thenReturn(240);
		Mockito.when(items.getItemPrice(RUNE_SCIMITAR)).thenReturn(15_000);

		gathered = new HashSet<>();
		tracker = new ItemStatTracker(store, client, items, new GatheredLedger()
		{
			@Override
			public void noteGathered(int itemId)
			{
				gathered.add(itemId);
			}

			@Override
			public boolean wasGathered(int itemId)
			{
				return gathered.contains(itemId);
			}
		});
	}

	/** Bin {@code qty} of an item the way the client announces it: an item-op
	 *  "Drop" click carrying the id, with the quantity read from the pack slot. */
	private void drop(int itemId, int qty)
	{
		Mockito.when(pack.getItem(SLOT)).thenReturn(new Item(itemId, qty));
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		Mockito.when(entry.getOption()).thenReturn("Drop");
		Mockito.when(entry.isItemOp()).thenReturn(true);
		Mockito.when(entry.getItemId()).thenReturn(itemId);
		Mockito.when(entry.getParam0()).thenReturn(SLOT);
		tracker.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private int dropped()
	{
		return store.getStat(ITEMS_DROPPED_VALUE);
	}

	private int resourcesDropped()
	{
		return store.getStat(RESOURCES_DROPPED_VALUE);
	}

	@Test
	public void droppingSomethingThisAccountGatheredCountsToBothFigures()
	{
		gathered.add(YEW_LOGS);
		drop(YEW_LOGS, 27);
		assertEquals(27 * 240, dropped());
		assertEquals(27 * 240, resourcesDropped());
	}

	@Test
	public void droppingSomethingNeverGatheredCountsOnlyToTheWholeBin()
	{
		// A scimitar came from a kill or a shop, not out of the world by hand.
		// Left in the resource figure it would answer a question about time spent
		// gathering with a fact about time spent killing.
		drop(RUNE_SCIMITAR, 1);
		assertEquals(15_000, dropped());
		assertEquals(0, resourcesDropped());
	}

	@Test
	public void theSameItemGatheredLaterDoesNotBackdateEarlierDrops()
	{
		// Bought logs binned before this account ever cut one: the ledger cannot
		// say they were gathered, and a later gather must not reach backwards and
		// re-file them.
		drop(YEW_LOGS, 10);
		assertEquals(0, resourcesDropped());

		gathered.add(YEW_LOGS);
		drop(YEW_LOGS, 10);
		assertEquals(10 * 240, resourcesDropped());
		assertEquals(20 * 240, dropped());
	}

	@Test
	public void aTrackerWithNoLedgerStillCountsTheWholeBin()
	{
		// The journal is not mounted until an account logs in; the plain drop
		// figure must not depend on it.
		Client client = Mockito.mock(Client.class);
		Mockito.when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(pack);
		net.runelite.client.game.ItemManager items =
			Mockito.mock(net.runelite.client.game.ItemManager.class);
		Mockito.when(items.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(items.getItemPrice(YEW_LOGS)).thenReturn(240);
		tracker = new ItemStatTracker(store, client, items);

		drop(YEW_LOGS, 5);
		assertEquals(5 * 240, dropped());
		assertEquals(0, resourcesDropped());
	}
}
