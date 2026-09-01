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
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static chronicle.counters.StatKeys.AMMO_CONSUMED;
import static org.junit.Assert.assertEquals;

/**
 * Covers the ammo inference in RangedStatTracker. Nothing in the client says a shot was
 * fired, so a shrinking worn-ammo slot is judged at the tick boundary: minus whatever
 * turned up in the pack, and refused if it is bigger than MAX_PER_TICK.
 */
public class RangedStatTrackerTest
{
	// EquipmentInventorySlot.AMMO
	private static final int AMMO_SLOT = 13;

	private static final int ARROW_ID = 892;
	private static final int BOLT_ID = 9144;

	private StatStore store;
	private Client client;
	private ItemContainer equipment;
	private ItemContainer pack;
	private RangedStatTracker tracker;

	@Before
	public void setUp()
	{
		store = new StatStore();
		client = Mockito.mock(Client.class);
		equipment = Mockito.mock(ItemContainer.class);
		pack = Mockito.mock(ItemContainer.class);
		Mockito.when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(equipment);
		Mockito.when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(pack);
		tracker = new RangedStatTracker(store, client);
	}

	// set the quiver and fire the equipment change; a negative id empties the slot
	private void quiver(int id, int qty)
	{
		Mockito.when(equipment.getItem(AMMO_SLOT)).thenReturn(id < 0 ? null : new Item(id, qty));
		tracker.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.EQUIPMENT.getId(), equipment));
	}

	private void packHolds(int id, int qty)
	{
		Mockito.when(pack.count(id)).thenReturn(qty);
	}

	private void tick()
	{
		tracker.onGameTick(new GameTick());
	}

	private void gameState(GameState state)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(state);
		tracker.onGameStateChanged(e);
	}

	private int consumed()
	{
		return store.getStat(AMMO_CONSUMED);
	}

	// shrinkage

	@Test
	public void aShotIsBookedOnlyOnceTheTickIsComplete()
	{
		quiver(ARROW_ID, 100);
		tick();

		quiver(ARROW_ID, 99);
		// the pack isn't read until the tick closes
		assertEquals(0, consumed());

		tick();
		assertEquals(1, consumed());
	}

	@Test
	public void shrinkageWithinOneTickIsOneVerdict()
	{
		quiver(ARROW_ID, 100);
		tick();

		// fast weapons shrink the slot more than once before the tick closes
		quiver(ARROW_ID, 99);
		quiver(ARROW_ID, 98);
		quiver(ARROW_ID, 97);
		tick();
		assertEquals(3, consumed());

		// pendingConsume was cleared, so a quiet tick books nothing
		tick();
		assertEquals(3, consumed());
	}

	// unequips and pickups

	@Test
	public void ammoThatLandedInThePackTheSameTickWasNotSpent()
	{
		quiver(ARROW_ID, 100);
		tick();

		// unequip: slot -28, pack +28
		packHolds(ARROW_ID, 28);
		quiver(ARROW_ID, 72);
		tick();
		assertEquals(0, consumed());

		// an arrow picked back up looks the same to the tracker
		packHolds(ARROW_ID, 48);
		quiver(ARROW_ID, 71);
		tick();
		assertEquals(0, consumed());
	}

	@Test
	public void thePackArrivalIsSubtractedRatherThanVetoing()
	{
		quiver(ARROW_ID, 100);
		tick();

		// 29 left the quiver, 28 turned up in the pack; the odd one is a shot
		packHolds(ARROW_ID, 28);
		quiver(ARROW_ID, 71);
		tick();
		assertEquals(1, consumed());
	}

	@Test
	public void ammoSittingInThePackDoesNotSuppressLaterShots()
	{
		quiver(ARROW_ID, 100);
		tick();

		packHolds(ARROW_ID, 28);
		quiver(ARROW_ID, 72);
		tick();
		assertEquals(0, consumed());

		// packAmmoAtTickStart rebaselines every tick, so the 28 now lying in the
		// pack offsets nothing
		quiver(ARROW_ID, 71);
		tick();
		assertEquals(1, consumed());
	}

	// the per-tick ceiling

	@Test
	public void theCeilingAdmitsAFullTicksWorthOfFiring()
	{
		quiver(ARROW_ID, 1000);
		tick();

		// 20 is MAX_PER_TICK, the largest drop still booked as firing
		quiver(ARROW_ID, 980);
		tick();
		assertEquals(20, consumed());
	}

	@Test
	public void aStackLeavingAtOnceIsRefused()
	{
		quiver(ARROW_ID, 1000);
		tick();

		// 21, one past the ceiling
		quiver(ARROW_ID, 979);
		tick();
		assertEquals(0, consumed());

		quiver(ARROW_ID, 479);
		tick();
		assertEquals(0, consumed());
	}

	@Test
	public void aRefusedDropDoesNotLingerIntoTheNextTick()
	{
		quiver(ARROW_ID, 1000);
		tick();

		quiver(ARROW_ID, 500);
		tick();
		assertEquals(0, consumed());

		// a refusal still clears pendingConsume; held over, the 500 would ride
		// along with the next real shot
		tick();
		quiver(ARROW_ID, 499);
		tick();
		assertEquals(1, consumed());
	}

	// changes that aren't shrinkage

	// quantities stay under the ceiling so it's the id check that stops the count here
	@Test
	public void emptyingTheQuiverIsNotAVolley()
	{
		quiver(ARROW_ID, 15);
		tick();

		// the slot empties and the id goes to -1, so there's no same-id shrink to read
		quiver(-1, 0);
		tick();
		assertEquals(0, consumed());
	}

	@Test
	public void swappingAmmoRebaselinesWithoutBooking()
	{
		quiver(ARROW_ID, 20);
		tick();

		// a different id is a swap, however much smaller the new stack
		quiver(BOLT_ID, 5);
		tick();
		assertEquals(0, consumed());

		// the swap leaves a usable baseline behind
		quiver(BOLT_ID, 4);
		tick();
		assertEquals(1, consumed());
	}

	@Test
	public void restockingTheQuiverBooksNothing()
	{
		quiver(ARROW_ID, 100);
		tick();

		quiver(ARROW_ID, 500);
		tick();
		assertEquals(0, consumed());
	}

	// logging out

	@Test
	public void leavingTheWorldForgetsTheQuiverEntirely()
	{
		for (GameState away : new GameState[]{
			GameState.LOGIN_SCREEN, GameState.CONNECTION_LOST,
			GameState.HOPPING, GameState.LOADING})
		{
			setUp();

			quiver(ARROW_ID, 100);
			tick();
			quiver(ARROW_ID, 99);      // a shot that hasn't been settled yet

			gameState(away);
			tick();
			assertEquals(away.name(), 0, consumed());

			// the next account to log in must not get the gap between the two
			// quivers booked to it
			quiver(ARROW_ID, 40);
			tick();
			assertEquals(away.name(), 0, consumed());
		}
	}

	@Test
	public void stayingInTheWorldKeepsTheBaseline()
	{
		quiver(ARROW_ID, 100);
		tick();
		quiver(ARROW_ID, 99);

		// LOGGED_IN arrives repeatedly during a session and must not reset anything
		gameState(GameState.LOGGED_IN);
		tick();
		assertEquals(1, consumed());
	}

	// container filter

	@Test
	public void containersOtherThanTheEquipmentAreIgnored()
	{
		quiver(ARROW_ID, 100);
		tick();

		// a bank or trade window holding the same ammo at slot 13; read as the
		// quiver it would book five shots and leave a false baseline
		ItemContainer other = Mockito.mock(ItemContainer.class);
		Mockito.when(other.getItem(AMMO_SLOT)).thenReturn(new Item(ARROW_ID, 95));
		tracker.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.BANK.getId(), other));
		tick();
		assertEquals(0, consumed());

		// the quiver's own baseline is untouched
		quiver(ARROW_ID, 99);
		tick();
		assertEquals(1, consumed());
	}
}
