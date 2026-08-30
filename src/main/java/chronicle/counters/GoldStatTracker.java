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
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;

import static chronicle.counters.StatKeys.COINS_EARNED_AT_SHOPS;
import static chronicle.counters.StatKeys.COINS_SPENT_AT_SHOPS;

/**
 * Splits coin movement at a shop into money spent and money earned.
 *
 * <p>There is no "you traded at a shop" event to subscribe to, so this reads the
 * coin stack directly. The trick is to only look while a shop is actually open:
 * the shop-inventory widget opening arms a per-tick reading of the pack's coins,
 * and each tick's change is booked as a purchase (coins fell) or a sale (coins
 * rose). Closing the widget disarms it, so coins moving for any other reason —
 * a drop, a trade with a player, a bank withdrawal — are never counted.
 *
 * <p>Watching only the pack means a shop paid for out of a rune pouch or looting
 * bag is missed, which is an acceptable blind spot: the alternative, watching
 * every container, would book unrelated movement as shop trade.
 */
public class GoldStatTracker implements StatTracker
{
	/** No reading held between shop sessions; a live count is never negative. */
	private static final int IDLE = -1;

	private final StatStore statStore;
	private final Client client;

	/** The pack's coin count at the end of the previous tick, or {@link #IDLE}. */
	private int coinsLastTick = IDLE;

	public GoldStatTracker(StatStore statStore, Client client)
	{
		this.statStore = statStore;
		this.client = client;
	}

	@Override
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.SHOP_INVENTORY)
		{
			// Arm on the coins present as the shop opens, so the first trade is
			// measured against the pre-shop total rather than against IDLE.
			coinsLastTick = packCoins();
		}
	}

	@Override
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.SHOP_INVENTORY)
		{
			coinsLastTick = IDLE;
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		if (coinsLastTick == IDLE)
		{
			return;   // no shop open; not sampling
		}

		int coins = packCoins();
		if (coins == IDLE)
		{
			return;   // pack briefly unreadable; hold the last figure and retry next tick
		}

		int change = coins - coinsLastTick;
		if (change < 0)
		{
			statStore.incrementStatBy(COINS_SPENT_AT_SHOPS, -change);
		}
		else if (change > 0)
		{
			statStore.incrementStatBy(COINS_EARNED_AT_SHOPS, change);
		}
		coinsLastTick = coins;
	}

	/** Coins in the backpack, or {@link #IDLE} if the container isn't available. */
	private int packCoins()
	{
		ItemContainer pack = client.getItemContainer(InventoryID.INVENTORY);
		return pack == null ? IDLE : pack.count(ItemID.COINS_995);
	}
}
