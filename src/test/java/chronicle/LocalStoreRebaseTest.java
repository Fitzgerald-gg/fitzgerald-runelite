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

/**
 * A lifetime figure is the frozen base plus the live session. A plugin toggle or a cloudSync
 * toggle restarts the counters mid-session, and anything that does has to re-freeze the base
 * first through {@link LocalStore#rebase}; without that the next recompute resolves back
 * to the login values and the journal loses everything counted since.
 */
public class LocalStoreRebaseTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		// nothing here prices an item; the ItemManager mock is never called
		store = new LocalStore(Mockito.mock(ItemManager.class), new Gson());
		dir = Files.createTempDirectory("chronicle-rebase").toFile();
	}

	private static Map<String, Integer> session(String key, int n)
	{
		Map<String, Integer> m = new HashMap<>();
		m.put(key, n);
		return m;
	}

	// sessionView() drops zeroed counters: a cleared store arrives as an empty map.
	private static Map<String, Integer> clearedStore()
	{
		return new HashMap<>();
	}

	// a returning player: n already banked on disk and frozen as the base
	private void mountWithLifetime(String rsn, String key, int n)
	{
		store.load(dir, rsn);
		store.setTrackers(session(key, n), rsn);
		store.flush(dir);
		store.load(dir, rsn);
	}

	@Test
	public void rebaseBanksTheFoldedSessionSoAClearedStoreCannotTakeItBack()
	{
		mountWithLifetime("Alpha", "tilesWalked", 500);
		assertEquals(500L, store.trackerBase("tilesWalked"));

		// the session so far, folded into the journal on the way into the toggle
		store.setTrackers(session("tilesWalked", 120), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));

		store.rebase("Alpha");
		// the 120 belong to the base now; counters can restart from zero
		assertEquals(620L, store.trackerBase("tilesWalked"));

		store.setTrackers(clearedStore(), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void withoutRebaseAClearedStoreRollsTheJournalBackToTheLoginValues()
	{
		mountWithLifetime("Alpha", "tilesWalked", 500);
		store.setTrackers(session("tilesWalked", 120), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));

		// same clear with the base left where login put it: recomputes as 500 plus
		// nothing, and the next flush writes that over the larger figure
		store.setTrackers(clearedStore(), "Alpha");
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void repeatedRebasesReFreezeTheBaseRatherThanAccumulateOntoIt()
	{
		mountWithLifetime("Alpha", "tilesWalked", 500);
		store.setTrackers(session("tilesWalked", 120), "Alpha");

		// a plugin toggle can follow a cloudSync toggle with no session in between
		store.rebase("Alpha");
		store.rebase("Alpha");

		store.setTrackers(clearedStore(), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void aLifetimePeakSurvivesTheBlindWindowWithoutBeingSummed()
	{
		mountWithLifetime("Alpha", "highestHit", 90);

		// peaks merge by max: a bigger session hit replaces the record
		store.setTrackers(session("highestHit", 110), "Alpha");
		assertEquals(110L, (long) store.trackersSnapshot().get("highestHit"));

		store.rebase("Alpha");

		// MAX_KEYS survive sessionView()'s zero filter: a restarted store reports a
		// peak of 0 instead of dropping the key
		store.setTrackers(session("highestHit", 0), "Alpha");
		assertEquals(110L, (long) store.trackersSnapshot().get("highestHit"));

		store.setTrackers(session("highestHit", 100), "Alpha");
		assertEquals(110L, (long) store.trackersSnapshot().get("highestHit"));
	}

	@Test
	public void rebaseIsRefusedWhileNoAccountOwnsTheMountedModel()
	{
		store.load(dir, "Alpha");
		store.setTrackers(session("tilesWalked", 500), "Alpha");
		store.endSession();
		assertFalse(store.isReadyFor("Alpha"));

		// a rebase landing after the account has gone must not freeze its totals
		store.rebase("Beta");
		store.rebase("Alpha");
		assertEquals(0L, store.trackerBase("tilesWalked"));

		store.load(dir, "Beta");
		store.setTrackers(session("tilesWalked", 7), "Beta");
		assertEquals(7L, (long) store.trackersSnapshot().get("tilesWalked"));
	}
}
