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
 * The drop side of ItemStatTracker: itemsDroppedValue counts every bin, while
 * resourcesDroppedValue only takes ids the gathered ledger knows.
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

	// fake a "Drop" item-op click. the tracker takes the id off the entry and the
	// stack size out of the pack slot named by param0.
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
		// a scimitar is kill or shop loot, so the ledger has no gather for it.
		drop(RUNE_SCIMITAR, 1);
		assertEquals(15_000, dropped());
		assertEquals(0, resourcesDropped());
	}

	@Test
	public void theSameItemGatheredLaterDoesNotBackdateEarlierDrops()
	{
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
		// with no ledger the plain dropped figure still has to count.
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
