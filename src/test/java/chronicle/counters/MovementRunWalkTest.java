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
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static chronicle.counters.StatKeys.DISTANCE_RAN;
import static chronicle.counters.StatKeys.DISTANCE_WALKED;
import static org.junit.Assert.assertEquals;

/**
 * The run/walk split in MovementStatTracker. The step itself is the evidence: a player
 * covers two tiles in a tick only by running, so a two-tile step is a run whatever the
 * run toggle happens to read. One tile is ambiguous - a walk, the tail of a run path, or
 * running on an empty bar - and falls back on the toggle plus energy.
 *
 * <p>This replaces a read of the run orb's sprite, whose every failure mode booked a run
 * as a walk. The last test here holds that line: the tracker asks for no widget at all.
 */
public class MovementRunWalkTest
{
	private StatStore store;
	private Client client;
	private Player local;
	private MovementStatTracker tracker;

	@Before
	public void setUp()
	{
		store = new StatStore();
		client = Mockito.mock(Client.class);
		local = Mockito.mock(Player.class);
		Mockito.when(client.getLocalPlayer()).thenReturn(local);
		tracker = new MovementStatTracker(store, client);
	}

	// the run toggle, and a bar with something in it
	private void runToggle(boolean on)
	{
		Mockito.when(client.getVarpValue(VarPlayerID.OPTION_RUN)).thenReturn(on ? 1 : 0);
		Mockito.when(client.getEnergy()).thenReturn(10000);
	}

	private void energy(int hundredthsOfAPercent)
	{
		Mockito.when(client.getEnergy()).thenReturn(hundredthsOfAPercent);
	}

	// stand the player on a tile and let the tick land
	private void standAt(int x, int y)
	{
		Mockito.when(local.getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
		tracker.onGameTick(new GameTick());
	}

	private int ran()
	{
		return store.getStat(DISTANCE_RAN);
	}

	private int walked()
	{
		return store.getStat(DISTANCE_WALKED);
	}

	@Test
	public void twoTilesInATickIsARunEvenWhenTheToggleReadsOff()
	{
		// the reported bug: the toggle (or, before this, the orb sprite) reads off, yet
		// the player is plainly running. The step overrules it.
		runToggle(false);
		standAt(3200, 3200);
		standAt(3202, 3200);

		assertEquals(2, ran());
		assertEquals(0, walked());
	}

	@Test
	public void twoTilesIsARunOnAnEmptyBarToo()
	{
		// nothing about a reading of the game's state gets to contradict two tiles
		runToggle(false);
		energy(0);
		standAt(3200, 3200);
		standAt(3200, 3202);

		assertEquals(2, ran());
		assertEquals(0, walked());
	}

	@Test
	public void oneTileWithRunOnIsARun()
	{
		// the last tile of a run path: the toggle is still on, so it books as run
		runToggle(true);
		standAt(3200, 3200);
		standAt(3201, 3200);

		assertEquals(1, ran());
		assertEquals(0, walked());
	}

	@Test
	public void oneTileWithRunOffIsAWalk()
	{
		runToggle(false);
		standAt(3200, 3200);
		standAt(3201, 3201);   // a diagonal step is still one tile of Chebyshev

		assertEquals(0, ran());
		assertEquals(1, walked());
	}

	@Test
	public void oneTileWithRunOnButNoEnergyIsAWalk()
	{
		// run switched on with an empty bar still walks
		runToggle(true);
		energy(0);
		standAt(3200, 3200);
		standAt(3201, 3200);

		assertEquals(0, ran());
		assertEquals(1, walked());
	}

	@Test
	public void aRunPathBooksItsTailWithTheRestOfIt()
	{
		// three ticks of a five-tile run: 2, 2, then the odd tile out
		runToggle(true);
		standAt(3200, 3200);
		standAt(3202, 3200);
		standAt(3204, 3200);
		standAt(3205, 3200);

		assertEquals(5, ran());
		assertEquals(0, walked());
	}

	@Test
	public void noWidgetIsEverConsulted()
	{
		// the orb read is gone: no lookup by composite id, none by parent and child
		runToggle(true);
		standAt(3200, 3200);
		standAt(3202, 3200);
		standAt(3203, 3200);

		Mockito.verify(client, Mockito.never()).getWidget(Mockito.anyInt());
		Mockito.verify(client, Mockito.never()).getWidget(Mockito.anyInt(), Mockito.anyInt());
	}
}
