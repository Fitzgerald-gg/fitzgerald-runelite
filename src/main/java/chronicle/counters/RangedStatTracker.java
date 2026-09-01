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
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;

import static chronicle.counters.StatKeys.AMMO_CONSUMED;

/**
 * Counts ranged ammo spent out of the worn-ammo slot: arrows, bolts, javelins.
 *
 * <p>A shot shrinks the slot by one, or a few at fast attack speeds. Unequipping and
 * banking shrink it the same way, so a drop is only booked at the end of the tick, minus
 * whatever landed in the pack, and only if it is small enough to be a volley. Ava's
 * recoveries never reach the slot: the tally is ammo gone for good.
 */
public class RangedStatTracker implements StatTracker
{
	// a one-tick drop bigger than this is a bank deposit or a death, not shooting
	private static final int MAX_PER_TICK = 20;

	private final StatStore store;
	private final Client client;

	// the worn ammo slot as of the last container change. blowpipe darts live in a var,
	// not this slot, and never reach any of these counters
	private int wornAmmoId = -1;
	private int wornAmmoQty;
	// slot shrinkage so far this tick, settled at the tick boundary
	private int pendingConsume;
	// baseline for the unequip check
	private int packAmmoAtTickStart;

	public RangedStatTracker(StatStore store, Client client)
	{
		this.store = store;
		this.client = client;
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getItemContainer() != client.getItemContainer(InventoryID.EQUIPMENT))
		{
			return;
		}
		Item ammo = event.getItemContainer().getItem(EquipmentInventorySlot.AMMO.getSlotIdx());
		int id = ammo != null ? ammo.getId() : -1;
		int qty = ammo != null ? ammo.getQuantity() : 0;

		if (id == wornAmmoId && id != -1 && qty < wornAmmoQty)
		{
			pendingConsume += wornAmmoQty - qty;   // slot shrank: a shot, an unequip, or a deposit
		}
		// an id change or a rise is an equip or a swap. rebaseline, count nothing
		wornAmmoId = id;
		wornAmmoQty = qty;
	}

	@Override
	public void onGameTick(GameTick event)
	{
		int packAmmoNow = wornAmmoId != -1 ? packCount(wornAmmoId) : 0;
		if (pendingConsume > 0)
		{
			// ammo that landed in the pack this tick was unequipped, not fired
			int movedToPack = Math.max(0, packAmmoNow - packAmmoAtTickStart);
			int consumed = pendingConsume - movedToPack;
			if (consumed > 0 && consumed <= MAX_PER_TICK)
			{
				store.incrementStatBy(AMMO_CONSUMED, consumed);
			}
			pendingConsume = 0;
		}
		packAmmoAtTickStart = packAmmoNow;
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			// the quiver can change while we're logged out. rebaseline on the way back in
			wornAmmoId = -1;
			wornAmmoQty = 0;
			pendingConsume = 0;
			packAmmoAtTickStart = 0;
		}
	}

	private int packCount(int itemId)
	{
		ItemContainer pack = client.getItemContainer(InventoryID.INVENTORY);
		return pack == null ? 0 : pack.count(itemId);
	}
}
