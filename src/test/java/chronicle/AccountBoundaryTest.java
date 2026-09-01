/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The store-level facts the account-identity guards rest on. A closed session leaves
 * its model mounted so the panel can still browse it, so {@link LocalStore#isReadyFor}
 * is the only thing that answers whether a model belongs to the account logged in now.
 */
public class AccountBoundaryTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		store = new LocalStore(im, new Gson());
		dir = Files.createTempDirectory("chronicle-boundary").toFile();
	}

	private static Map<String, Integer> session(String key, int n)
	{
		Map<String, Integer> m = new HashMap<>();
		m.put(key, n);
		return m;
	}

	@Test
	public void endSessionStopsOwnershipButKeepsTheModelMounted()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.endSession();
		// the panel still browses the closed session, so the model stays readable
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));
		assertFalse(store.isReadyFor("Alpha"));
		assertFalse(store.isReadyFor("Beta"));
	}

	@Test
	public void anotherAccountsWritesAreRefusedUntilItsJournalIsMounted()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.endSession();
		// Beta is logged in but its journal isn't mounted yet, so its writes must no-op.
		store.setTrackers(session("tilesWalked", 7), "Beta");
		store.record("PET", new com.google.gson.JsonObject(), "Beta");
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));
		store.load(dir, "Beta");
		assertTrue(store.isReadyFor("Beta"));
		assertFalse(store.isReadyFor("Alpha"));
		// none of Alpha's 500 came across
		assertTrue(store.trackersSnapshot().isEmpty());
	}

	@Test
	public void reloadingALiveAccountWouldDoubleCountItsSession()
	{
		// The lifetime base is frozen from disk and the flushed file already holds the
		// session so far, so a re-load counts that session twice.
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.flush(dir);
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));

		store.load(dir, "Alpha");                          // what an unguarded world hop does
		store.setTrackers(session("tilesWalked", 500), "Alpha");   // same session, still counting
		assertEquals(1000L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void aMountedAccountFoldsItsSessionOnceHoweverOftenItIsRecomputed()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 100), "Alpha");
		store.setTrackers(session("tilesWalked", 250), "Alpha");
		store.setTrackers(session("tilesWalked", 400), "Alpha");
		// base(0) + the current session, so 400 rather than 100+250+400
		assertEquals(400L, (long) store.trackersSnapshot().get("tilesWalked"));
	}
}
