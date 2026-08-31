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
 * Pins {@link LocalStore#rebase}, the guard over the blind window between
 * folding a session into the journal and clearing the counter store that
 * produced it.
 *
 * <p>A lifetime figure is always frozen-base + the live session, so the base
 * and the session store have to move in step. Anything that restarts the
 * counters mid-session — a cloud toggle, a plugin toggle — hands the next
 * recompute a session of zero. Unless the base has first been re-frozen at
 * what was just folded in, that recompute resolves back to the login values
 * and the journal quietly gives up everything counted since.
 */
public class LocalStoreRebaseTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		// Nothing here prices an item; the tracker figures are plain arithmetic.
		store = new LocalStore(Mockito.mock(ItemManager.class), new Gson());
		dir = Files.createTempDirectory("chronicle-rebase").toFile();
	}

	private static Map<String, Integer> session(String key, int n)
	{
		Map<String, Integer> m = new HashMap<>();
		m.put(key, n);
		return m;
	}

	/** What the trackers report once their store has been cleared under them. */
	private static Map<String, Integer> clearedStore()
	{
		return new HashMap<>();
	}

	/** A returning player: {@code n} already banked on disk and frozen as the base. */
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

		// The session so far, folded into the journal on the way into the toggle.
		store.setTrackers(session("tilesWalked", 120), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));

		store.rebase("Alpha");
		// Those 120 belong to the base now, which is what frees the counters to
		// restart from zero without the journal noticing.
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

		// The same clear with the base left where login put it. This is the
		// regression the call exists to prevent: the journal recomputes as 500
		// plus nothing, and the next flush writes that over the larger figure.
		store.setTrackers(clearedStore(), "Alpha");
		assertEquals(500L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void repeatedRebasesReFreezeTheBaseRatherThanAccumulateOntoIt()
	{
		mountWithLifetime("Alpha", "tilesWalked", 500);
		store.setTrackers(session("tilesWalked", 120), "Alpha");

		// Both callers run the same fold-then-rebase pair, and a plugin toggle
		// can follow a cloud toggle with no session in between. Re-freezing is a
		// snapshot of the journal as it stands, never an addition to the base.
		store.rebase("Alpha");
		store.rebase("Alpha");

		store.setTrackers(clearedStore(), "Alpha");
		assertEquals(620L, (long) store.trackersSnapshot().get("tilesWalked"));
	}

	@Test
	public void aLifetimePeakSurvivesTheBlindWindowWithoutBeingSummed()
	{
		mountWithLifetime("Alpha", "highestHit", 90);

		// A peak merges by max rather than by addition, so a bigger session hit
		// replaces the record instead of stacking on top of it.
		store.setTrackers(session("highestHit", 110), "Alpha");
		assertEquals(110L, (long) store.trackersSnapshot().get("highestHit"));

		store.rebase("Alpha");

		// A restarted store reports a peak of zero rather than dropping the key,
		// and the banked record has to outrank it instead of resetting to it.
		store.setTrackers(session("highestHit", 0), "Alpha");
		assertEquals(110L, (long) store.trackersSnapshot().get("highestHit"));

		// Later hits are measured against the banked record, so a smaller one
		// leaves it standing — a rebase must not cost the player their best.
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

		// Both callers capture the name and hop threads before rebasing, so one
		// can land after the account it was raised for has gone. Alpha's model
		// stays mounted for the panel to browse, and freezing its totals as the
		// base would hand Alpha's whole lifetime to whoever logs in next.
		store.rebase("Beta");
		store.rebase("Alpha");
		assertEquals(0L, store.trackerBase("tilesWalked"));

		store.load(dir, "Beta");
		store.setTrackers(session("tilesWalked", 7), "Beta");
		assertEquals(7L, (long) store.trackersSnapshot().get("tilesWalked"));
	}
}
