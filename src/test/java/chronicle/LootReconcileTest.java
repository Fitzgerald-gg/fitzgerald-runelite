/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static chronicle.ChronicleEventCapture.serverCoveredIn;
import static chronicle.ChronicleEventCapture.slKey;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A held ground-scan loot event is dropped only when a ServerNpcLoot covered the same
 * kill. Keys are per (npcId, tick), so repeated kills of one NPC can't cover each other.
 */
public class LootReconcileTest
{
	private static final int WINDOW = 2;
	private static final int DUST_DEVIL = 7249;

	@Test
	public void serverOnSameTickCoversTheKill()
	{
		Set<Long> keys = new HashSet<>();
		keys.add(slKey(DUST_DEVIL, 100));
		assertTrue(serverCoveredIn(keys, DUST_DEVIL, 100, WINDOW));
	}

	@Test
	public void serverWithinForwardWindowCoversTheKill()
	{
		// The loot script can land a tick or two after the ground-scan.
		Set<Long> keys = new HashSet<>();
		keys.add(slKey(DUST_DEVIL, 102));
		assertTrue(serverCoveredIn(keys, DUST_DEVIL, 100, WINDOW));
	}

	@Test
	public void serverBeyondWindowDoesNotCover()
	{
		Set<Long> keys = new HashSet<>();
		keys.add(slKey(DUST_DEVIL, 103));   // 3 ticks later, outside the window
		assertFalse(serverCoveredIn(keys, DUST_DEVIL, 100, WINDOW));
	}

	@Test
	public void aLaterKillOfSameNpcDoesNotCoverAnEarlierUncoveredOne()
	{
		// the kill at 100 fired only a ground scan; a later kill of the same NPC at 130
		// fired a server event. The 130 key must not suppress the earlier one.
		Set<Long> keys = new HashSet<>();
		keys.add(slKey(DUST_DEVIL, 130));
		assertFalse(serverCoveredIn(keys, DUST_DEVIL, 100, WINDOW));
		assertTrue(serverCoveredIn(keys, DUST_DEVIL, 130, WINDOW));
	}

	@Test
	public void differentNpcDoesNotCover()
	{
		Set<Long> keys = new HashSet<>();
		keys.add(slKey(DUST_DEVIL, 100));
		assertFalse(serverCoveredIn(keys, 7250, 100, WINDOW));   // choke devil, same tick
	}

	@Test
	public void keyIsUniquePerNpcAndTick()
	{
		assertTrue(slKey(7249, 100) != slKey(7250, 100));
		assertTrue(slKey(7249, 100) != slKey(7249, 101));
		assertTrue(slKey(7249, 100) == slKey(7249, 100));
	}
}
