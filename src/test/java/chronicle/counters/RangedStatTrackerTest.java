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
 * Pins the quiver inference. Nothing in the client announces "a shot was fired" —
 * the tracker only ever sees the worn-ammo slot get smaller, and has to decide
 * what made it smaller. Two defences do that deciding: whatever arrived in the
 * pack that tick is subtracted (an unequip is not a volley), and a drop past the
 * per-tick ceiling is refused outright (a bank deposit is not a volley either).
 *
 * <p>Both are worth pinning because their failure mode is silent and permanent:
 * a miscount is folded into the lifetime journal on the next refresh, where
 * nothing distinguishes ten thousand phantom arrows from ten thousand real ones.
 * The numbers below are all end-of-tick verdicts, because that is the only moment
 * the tracker has enough of the tick to judge.
 */
public class RangedStatTrackerTest
{
	/** The worn-ammo slot. The tracker must read the quiver and nothing else. */
	private static final int AMMO_SLOT = 13;

	/** Two distinct ammo ids; only their being different carries any meaning. */
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

	/** Put {@code qty} of an ammo in the quiver — a negative id empties it — and
	 *  announce the equipment change the way the client does. */
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

	// ── The signal: a quiver that shrank with nothing to explain it ───────

	@Test
	public void aShotIsBookedOnlyOnceTheTickIsComplete()
	{
		quiver(ARROW_ID, 100);
		tick();

		quiver(ARROW_ID, 99);
		// Mid-tick the shrink is still unexplained; the pack has not been read yet.
		assertEquals(0, consumed());

		tick();
		assertEquals(1, consumed());
	}

	@Test
	public void shrinkageWithinOneTickIsOneVerdict()
	{
		quiver(ARROW_ID, 100);
		tick();

		// A fast weapon can empty several slots' worth before the tick closes.
		quiver(ARROW_ID, 99);
		quiver(ARROW_ID, 98);
		quiver(ARROW_ID, 97);
		tick();
		assertEquals(3, consumed());

		// ...and the verdict is spent, so a quiet tick does not book it again.
		tick();
		assertEquals(3, consumed());
	}

	// ── Defence one: ammo that came back is not ammo that was spent ──────

	@Test
	public void ammoThatLandedInThePackTheSameTickWasNotSpent()
	{
		quiver(ARROW_ID, 100);
		tick();

		// Unequipping moves the stack to the pack: the slot shrinks by 28 and the
		// pack gains 28. Nothing left the account, so nothing was consumed.
		packHolds(ARROW_ID, 28);
		quiver(ARROW_ID, 72);
		tick();
		assertEquals(0, consumed());

		// The same rule covers the ranger who picks their arrows back up: the
		// counter is ammo permanently spent, and a recovered arrow was not.
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

		// 29 left the quiver and 28 of them turned up in the pack; the odd one out
		// is a real shot and still has to be counted.
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

		// The pack reading is a per-tick baseline, not a running allowance: with the
		// 28 now simply lying in the pack, the next shot is unobstructed.
		quiver(ARROW_ID, 71);
		tick();
		assertEquals(1, consumed());
	}

	// ── Defence two: a stack leaving at once was not fired ───────────────

	@Test
	public void theCeilingAdmitsAFullTicksWorthOfFiring()
	{
		quiver(ARROW_ID, 1000);
		tick();

		// The largest drop still treated as firing, so the ceiling cannot be
		// tightened without deciding that some real volley no longer counts.
		quiver(ARROW_ID, 980);
		tick();
		assertEquals(20, consumed());
	}

	@Test
	public void aStackLeavingAtOnceIsRefused()
	{
		quiver(ARROW_ID, 1000);
		tick();

		// One past the ceiling: a deposit, a death or a drop, not a volley.
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

		// The refusal clears the slate. Were the 500 held over, the next tick would
		// fold it into a genuine shot and book the deposit after all.
		tick();
		quiver(ARROW_ID, 499);
		tick();
		assertEquals(1, consumed());
	}

	// ── Changes that are not shrinkage at all ────────────────────────────

	/**
	 * The quantities here are deliberately small enough to pass the plausibility
	 * ceiling. A quiver emptied of a thousand arrows is refused by the ceiling
	 * whatever the id logic does; only a handful of ammo proves the id itself is
	 * what stopped the count.
	 */
	@Test
	public void emptyingTheQuiverIsNotAVolley()
	{
		quiver(ARROW_ID, 15);
		tick();

		// The whole stack leaves and the slot reports empty. The id is gone, so
		// there is no "before and after" of the same ammo to compare.
		quiver(-1, 0);
		tick();
		assertEquals(0, consumed());
	}

	@Test
	public void swappingAmmoRebaselinesWithoutBooking()
	{
		quiver(ARROW_ID, 20);
		tick();

		// A different id in the slot is a swap, however much smaller the new stack.
		quiver(BOLT_ID, 5);
		tick();
		assertEquals(0, consumed());

		// ...and the swap left a usable baseline behind it.
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

	// ── The account boundary ─────────────────────────────────────────────

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
			quiver(ARROW_ID, 99);      // a shot whose verdict has not been reached

			gameState(away);
			tick();
			assertEquals(away.name(), 0, consumed());

			// What matters far more than the dropped shot: the next account to log
			// in must not have the difference between the two quivers booked to it.
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

		// LOGGED_IN arrives repeatedly during an ordinary session; only a departure
		// invalidates what the tracker has been watching.
		gameState(GameState.LOGGED_IN);
		tick();
		assertEquals(1, consumed());
	}

	// ── Only the worn equipment is the tracker's business ────────────────

	@Test
	public void containersOtherThanTheEquipmentAreIgnored()
	{
		quiver(ARROW_ID, 100);
		tick();

		// A container that happens to hold the same ammo at its thirteenth slot —
		// a bank, a deposit box, another player's trade offer. Read as the quiver,
		// it would announce five shots and then leave a false baseline behind.
		ItemContainer other = Mockito.mock(ItemContainer.class);
		Mockito.when(other.getItem(AMMO_SLOT)).thenReturn(new Item(ARROW_ID, 95));
		tracker.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.BANK.getId(), other));
		tick();
		assertEquals(0, consumed());

		// The quiver's own reading is untouched, so the next real shot is a shot.
		quiver(ARROW_ID, 99);
		tick();
		assertEquals(1, consumed());
	}
}
