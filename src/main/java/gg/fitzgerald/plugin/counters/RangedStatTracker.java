/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin.counters;

import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;

import static gg.fitzgerald.plugin.counters.StatKeys.AMMO_CONSUMED;

/**
 * Counts ranged ammunition that leaves the quiver — arrows, bolts and javelins in
 * the worn-ammo slot.
 *
 * <p>A shot drops the ammo-slot quantity by one (or a few, at fast speeds). That is
 * the whole signal, but two other things also shrink the slot and must not count:
 * unequipping ammo (it moves to the pack) and depositing it at a bank (it moves to
 * the bank). So a decrease is only booked once the tick is complete: whatever moved
 * into the pack that tick is subtracted (that's an unequip), and an implausibly
 * large drop is ignored (that's a deposit or a death, not a volley). Ava's-recovered
 * shots never shrink the slot, so this is "ammo consumed" — permanently spent — not
 * "shots fired", which is the more useful figure anyway.
 *
 * <p>The blowpipe stores its darts in a var, not the ammo slot, so its shots are not
 * seen here; that's a known gap rather than a miscount.
 */
public class RangedStatTracker implements StatTracker
{
	/** A single tick can't legitimately spend more than a handful of ammo; anything
	 *  larger is a stack leaving for the bank or on death, not firing. */
	private static final int MAX_PER_TICK = 20;

	private final StatStore store;
	private final Client client;

	private int wornAmmoId = -1;
	private int wornAmmoQty;
	/** Ammo-slot shrinkage seen so far this tick, awaiting the end-of-tick verdict. */
	private int pendingConsume;
	/** Pack count of the worn ammo id at the last tick boundary, for the unequip check. */
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
		// An id change or a rise is an equip/swap — nothing spent; it just rebaselines.
		wornAmmoId = id;
		wornAmmoQty = qty;
	}

	@Override
	public void onGameTick(GameTick event)
	{
		int packAmmoNow = wornAmmoId != -1 ? packCount(wornAmmoId) : 0;
		if (pendingConsume > 0)
		{
			// Ammo that arrived in the pack this tick came from an unequip, not a shot.
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
			// The quiver can change unseen while away; rebaseline instead of counting.
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
