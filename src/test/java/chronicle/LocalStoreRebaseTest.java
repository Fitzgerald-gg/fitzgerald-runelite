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
 * Pins {@link LocalStore#rebase}. A lifetime figure is the frozen base plus the live
 * session, so anything that restarts the counters mid-session (plugin toggle, cloudSync
 * toggle) has to re-freeze the base first, or the next recompute resolves back to the
 * login values and the journal loses everything counted since.
 */
public class LocalStoreRebaseTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		// nothing here prices an item, so the ItemManager mock never gets called
		store = new LocalStore(Mockito.mock(ItemManager.class), new Gson());
		dir = Files.createTempDirectory("chronicle-rebase").toFile();
	}

	private static Map<String, Integer> session(String key, int n)
	{
		Map<String, Integer> m = new HashMap<>();
		m.put(key, n);
		return m;
	}

	// sessionView() drops zeroed counters, so a cleared store arrives as an empty map.
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
		// those 120 belong to the base now, so the counters can restart from zero
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

		// a peak merges by max, so a bigger session hit replaces the record
		store.setTrackers(session("highestHit", 110), "Alpha");
		assertEquals(110L, (long) store.trackersSnapshot().get("highestHit"));

		store.rebase("Alpha");

		// MAX_KEYS survive sessionView()'s zero filter, so a restarted store reports a
		// peak of 0 rather than dropping the key
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

		// both callers capture the name and hop threads before rebasing, so one can land
		// after the account has gone. Alpha's model stays mounted for the panel to
		// browse, and freezing its totals would hand its lifetime to the next login.
		store.rebase("Beta");
		store.rebase("Alpha");
		assertEquals(0L, store.trackerBase("tilesWalked"));

		store.load(dir, "Beta");
		store.setTrackers(session("tilesWalked", 7), "Beta");
		assertEquals(7L, (long) store.trackersSnapshot().get("tilesWalked"));
	}
}
