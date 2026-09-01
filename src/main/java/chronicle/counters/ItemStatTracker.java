/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.game.ItemManager;

import static chronicle.counters.StatKeys.CABBAGES_PICKED;
import static chronicle.counters.StatKeys.EXAMINES;
import static chronicle.counters.StatKeys.FLAX_GATHERED;
import static chronicle.counters.StatKeys.ITEMS_DISCARDED;
import static chronicle.counters.StatKeys.ITEMS_DROPPED_VALUE;
import static chronicle.counters.StatKeys.RESOURCES_DROPPED_VALUE;

/**
 * Item interactions with no attempt count of their own: examines, drops (and the
 * value binned), cabbage and flax picks. All of them arrive as a menu click or a
 * line of chat, so those are the only two hooks implemented here.
 */
public class ItemStatTracker implements StatTracker
{
	private final StatStore statStore;
	private final Client client;
	private final ItemManager itemManager;

	// tells a gathered resource apart from bank junk at the click. nullable: without
	// one, a drop only feeds the plain dropped-value stat.
	private final GatheredLedger gatheredLedger;

	public ItemStatTracker(StatStore statStore, Client client, ItemManager itemManager)
	{
		this(statStore, client, itemManager, null);
	}

	public ItemStatTracker(StatStore statStore, Client client, ItemManager itemManager,
		GatheredLedger gatheredLedger)
	{
		this.statStore = statStore;
		this.client = client;
		this.itemManager = itemManager;
		this.gatheredLedger = gatheredLedger;
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// constant first: a menu entry can carry no option text.
		final String option = event.getMenuOption();
		if ("Examine".equals(option))
		{
			statStore.incrementStat(EXAMINES);
		}
		else if ("Drop".equals(option))
		{
			statStore.incrementStat(ITEMS_DISCARDED);
			recordDroppedValue(event);
		}
	}

	// the click carries the item id; the stack size comes from the inventory slot in
	// param0. priced at drop time via ItemManager, canonicalised so notes and
	// placeholders resolve.
	private void recordDroppedValue(MenuOptionClicked event)
	{
		if (!event.isItemOp())
		{
			return;   // a non-inventory "Drop" has no item to price
		}
		final int itemId = event.getItemId();
		if (itemId <= 0)
		{
			return;
		}
		int qty = 1;
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv != null)
		{
			final Item slotItem = inv.getItem(event.getParam0());
			if (slotItem != null && slotItem.getId() == itemId)
			{
				qty = Math.max(1, slotItem.getQuantity());
			}
		}
		final int canonical = itemManager.canonicalize(itemId);
		final int each = itemManager.getItemPrice(canonical);
		if (each <= 0)
		{
			return;
		}
		// clamp the multiply so a big stack can't overflow the int StatStore takes.
		final long value = (long) each * qty;
		final int banked = value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
		statStore.incrementStatBy(ITEMS_DROPPED_VALUE, banked);
		// the total above counts every bin, bank clear-outs included. this second
		// figure is read beside gathered value, so only items this account pulled
		// out of the world count toward it.
		if (gatheredLedger != null && gatheredLedger.wasGathered(canonical))
		{
			statStore.incrementStatBy(RESOURCES_DROPPED_VALUE, banked);
		}
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		switch (event.getType())
		{
			case SPAM:
			case GAMEMESSAGE:
			case MESBOX:
				break;
			default:
				return;
		}

		final String message = event.getMessage();

		if (message.contains("You pick a") || message.contains("You pick some"))
		{
			// "You pick a cabbage." / "You pick some flax." take the trailing word:
			// last space up to the period after it. no period there, not a pick.
			final int from = message.lastIndexOf(' ') + 1;
			final int dot = message.indexOf('.', from);
			if (dot < 0)
			{
				return;
			}
			final String picked = message.substring(from, dot);
			if ("cabbage".equals(picked))
			{
				statStore.incrementStat(CABBAGES_PICKED);
			}
			else if ("flax".equals(picked))
			{
				statStore.incrementStat(FLAX_GATHERED);
			}
		}
	}
}
