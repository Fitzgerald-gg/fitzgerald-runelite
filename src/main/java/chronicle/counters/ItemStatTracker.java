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
 * Records small, one-off item interactions that never carry their own attempt
 * count: examining or dropping an item, gathering cabbage or flax by hand, and
 * scattering ashes. Each of these reaches the client either as a menu click or as
 * a line of in-game chat, so those are the only two feeds this tracker listens to.
 * Tallies land in the shared {@link StatStore}; the other event hooks stay as the
 * interface's default no-ops.
 */
public class ItemStatTracker implements StatTracker
{
	private final StatStore statStore;
	private final Client client;
	private final ItemManager itemManager;

	// Tells a gathered resource apart from bank junk at the moment of the click.
	// Nullable — tests build the tracker bare and the plain drop figure still flows.
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
		// Comparing the constant first keeps this null-safe when a menu entry
		// carries no option text.
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

	/**
	 * Add the live GE value of a just-dropped item to {@code itemsDroppedValue}. The
	 * "Drop" click carries the item id; its quantity is read from the inventory slot
	 * (param0), so a dropped stack counts its whole worth. Priced at drop time via
	 * RuneLite's own {@link ItemManager} (canonicalised so notes/placeholders resolve),
	 * so no price table lives in the plugin and untradeables simply add nothing.
	 */
	private void recordDroppedValue(MenuOptionClicked event)
	{
		if (!event.isItemOp())
		{
			return;   // a non-inventory "Drop" (rare) carries no item to value
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
		// Clamp the multiply so a huge stack can't overflow int before StatStore
		// (which then saturates the running total).
		final long value = (long) each * qty;
		final int banked = value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
		statStore.incrementStatBy(ITEMS_DROPPED_VALUE, banked);
		// The resource-scoped half of the gathered/dropped pair. This figure is
		// read beside what the gathering skills produced, and the total above
		// counts every bin — a bank trip clearing old clue rewards included — so
		// pairing that total with gathered value would flatter the dropped side.
		// Only what this account pulled out of the world itself counts here.
		if (gatheredLedger != null && gatheredLedger.wasGathered(canonical))
		{
			statStore.incrementStatBy(RESOURCES_DROPPED_VALUE, banked);
		}
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		// Only the plain-text feeds (overhead spam, game messages, message boxes)
		// carry the lines we care about; ignore everything else.
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
			// "You pick a cabbage." / "You pick some flax." — the produce is the
			// trailing word, i.e. from the last space up to the closing period.
			// Every spam, game and message-box line carrying the phrase arrives
			// here, including sentences that run on past the pick, so the period
			// is looked for after that space rather than assumed to be the first
			// one on the line. A line offering no such period is not a pick.
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
