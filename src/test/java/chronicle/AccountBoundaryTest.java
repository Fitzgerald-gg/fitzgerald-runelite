/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the store-level facts the plugin's account-identity guards rest on.
 *
 * <p>The journal deliberately keeps the last account's model mounted after its
 * session ends (the panel still browses it), so "is this model mine?" can only
 * be answered by {@link LocalStore#isReadyFor}. Every read that feeds a cloud
 * push or a history write has to ask. These tests fail if that contract is
 * quietly changed, which is what let one account's lifetime totals be pushed
 * under another's token.
 */
public class AccountBoundaryTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(10);
		ItemComposition comp = Mockito.mock(ItemComposition.class);
		Mockito.when(comp.getName()).thenReturn("Rune dagger");
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenReturn(comp);
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
		// The model is still readable — the panel browses the closed session —
		// so a reader that only checks "is there a model?" sees Alpha's data.
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));
		// …but nobody owns it any more. This is the guard's whole basis.
		assertFalse(store.isReadyFor("Alpha"));
		assertFalse(store.isReadyFor("Beta"));
	}

	@Test
	public void anotherAccountsWritesAreRefusedUntilItsJournalIsMounted()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.endSession();
		// Beta is logged in but its journal has not loaded yet: every write path
		// must no-op rather than fold Beta's session into Alpha's model.
		store.setTrackers(session("tilesWalked", 7), "Beta");
		store.record("PET", new com.google.gson.JsonObject(), "Beta");
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));
		store.load(dir, "Beta");
		assertTrue(store.isReadyFor("Beta"));
		assertFalse(store.isReadyFor("Alpha"));
		// Beta's own journal is its own: none of Alpha's totals came across.
		assertTrue(store.trackersSnapshot().isEmpty());
	}

	@Test
	public void reloadingALiveAccountWouldDoubleCountItsSession()
	{
		// Why the login path must never re-load an already-mounted account (a
		// world hop and every region load re-fire LOGGED_IN): the lifetime base
		// is frozen FROM DISK, and the flushed file already contains the session
		// folded so far. Re-freezing counts it twice — and a journal-absolute
		// push would make that permanent server-side.
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.flush(dir);
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));

		store.load(dir, "Alpha");                          // the re-load a hop used to cause
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
		// base(0) + the CURRENT session, not the sum of every refresh.
		assertEquals(400L, (long) store.trackersSnapshot().get("tilesWalked"));
	}
}
