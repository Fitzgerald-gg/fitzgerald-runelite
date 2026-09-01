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
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;

import static chronicle.counters.StatKeys.COINS_EARNED_AT_SHOPS;
import static chronicle.counters.StatKeys.COINS_SPENT_AT_SHOPS;

/**
 * Splits coin movement at a shop into money spent and money earned.
 *
 * <p>There's no shop-trade event to subscribe to, so this samples the pack's coin
 * stack each tick and only while the shop widget is open. Coins moving for any
 * other reason (a drop, a player trade, a bank withdrawal) fall outside that window
 * and aren't counted.
 *
 * <p>Only the pack is watched, so a shop paid for out of a rune pouch or looting
 * bag is missed.
 */
public class GoldStatTracker implements StatTracker
{
	// no shop open. a real coin count is never negative, so -1 is safe as a sentinel
	private static final int IDLE = -1;

	private final StatStore statStore;
	private final Client client;

	// pack coins at the end of the previous tick, or IDLE
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
			// prime with the pre-shop total so the first trade has something to measure against
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
			return;
		}

		int coins = packCoins();
		if (coins == IDLE)
		{
			return;   // pack unreadable this tick; keep the last figure and retry
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

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			// a dropped connection tears down the shop with no WidgetClosed, and the pack
			// moves unobserved while away, so the stale reading can't be allowed to survive
			coinsLastTick = IDLE;
		}
	}

	// coins in the pack, or IDLE if the container isn't loaded
	private int packCoins()
	{
		ItemContainer pack = client.getItemContainer(InventoryID.INVENTORY);
		return pack == null ? IDLE : pack.count(ItemID.COINS_995);
	}
}
