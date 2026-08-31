/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Pins the change gate — the thing that decides whether a whole achievement
 * snapshot goes over the wire — and the per-tick reuse that stops the snapshot
 * being built twice in one client-thread pass.
 *
 * <p>The gate is the entire economy of this push: a snapshot is several hundred
 * quest states and varbits, and an account that has not touched a diary in a
 * month must cost one string comparison per interval rather than an upload. The
 * failure modes sit on either side of it and are both quiet. Too slack and the
 * plugin re-sends an unchanged snapshot for ever; too tight and a genuine
 * completion never reaches the server, which is worse — the player sees a quest
 * finish in game and their journal never learns of it.
 *
 * <p>{@link AchievementSync#reset()} is the third obligation: it is called at
 * the account boundary, and it has to make the NEXT account sync afresh even
 * when its state is byte-identical to the last one's.
 */
public class AchievementSyncTest
{
	private Client client;
	private AchievementSync sync;

	private final Map<Integer, Integer> varbits = new HashMap<>();
	private int tick;
	/** What the quest-status script leaves on the stack: 2 = finished, 1 = not started. */
	private int[] questStack;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		questStack = new int[]{1};
		tick = 100;
		Mockito.when(client.getTickCount()).thenAnswer(inv -> tick);
		Mockito.when(client.getIntStack()).thenAnswer(inv -> questStack);
		Mockito.when(client.getVarbitValue(Mockito.anyInt()))
			.thenAnswer(inv -> varbits.getOrDefault((Integer) inv.getArgument(0), 0));
		sync = new AchievementSync(client);
	}

	/** Move to the next tick, which is what invalidates the cached snapshot. */
	private JsonObject nextTick()
	{
		tick++;
		return sync.snapshot();
	}

	// ── The per-tick snapshot ────────────────────────────────────────────

	/**
	 * The journal refresh and the cloud push both harvest in the same
	 * client-thread pass. Building the snapshot twice would run the quest sweep
	 * — a clientscript per quest — twice for one tick's worth of information.
	 */
	@Test
	public void oneTickBuildsOneSnapshotHoweverManyCallersAskForIt()
	{
		JsonObject first = sync.snapshot();
		assertSame(first, sync.snapshot());
		assertSame(first, sync.snapshot());

		assertNotSame(first, nextTick());
	}

	/**
	 * The reuse is a cache keyed on the tick, so a change made mid-tick is not
	 * visible until the tick turns. That is the correct reading of the game
	 * state — varbits settle within a tick — and it is what makes the cheap
	 * string gate safe rather than racy.
	 */
	@Test
	public void aChangeWithinTheTickIsSeenOnTheNextOne()
	{
		JsonObject before = sync.snapshot();
		assertFalse(before.getAsJsonObject("diaries")
			.getAsJsonObject("varrock").get("easy").getAsBoolean());

		varbits.put(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1);
		assertSame(before, sync.snapshot());

		assertTrue(nextTick().getAsJsonObject("diaries")
			.getAsJsonObject("varrock").get("easy").getAsBoolean());
	}

	// ── The gate ─────────────────────────────────────────────────────────

	/**
	 * Nothing has been acknowledged yet, so the first snapshot of a session is
	 * always worth sending — this is what carries a fresh install's whole state
	 * up on its first push.
	 */
	@Test
	public void anUnacknowledgedSnapshotAlwaysGoesUp()
	{
		assertTrue(sync.changedSince(sync.snapshot()));
	}

	/**
	 * The gate closes only on the server's ack, and stays closed while the
	 * account sits still. The cost of an untouched account is one comparison.
	 */
	@Test
	public void identicalStateIsNotResentOnceAcknowledged()
	{
		JsonObject snap = sync.snapshot();
		sync.markSynced(snap);
		assertFalse(sync.changedSince(snap));
		// A fresh object built on a later tick from unchanged state is still equal
		// as far as the gate is concerned — it compares content, not identity.
		JsonObject later = nextTick();
		assertNotSame(snap, later);
		assertFalse(sync.changedSince(later));
	}

	/**
	 * A diary tier completing is exactly the event the push exists to carry.
	 */
	@Test
	public void aDiaryCompletionReopensTheGate()
	{
		sync.markSynced(sync.snapshot());
		varbits.put(VarbitID.KANDARIN_DIARY_HARD_COMPLETE, 1);
		assertTrue(sync.changedSince(nextTick()));
	}

	/**
	 * ...as is a combat-achievement tier, and the points figure that moves
	 * between tiers.
	 */
	@Test
	public void aCombatAchievementChangeReopensTheGate()
	{
		sync.markSynced(sync.snapshot());
		varbits.put(VarbitID.CA_POINTS, 40);
		assertTrue(sync.changedSince(nextTick()));

		sync.markSynced(nextTick());
		varbits.put(VarbitID.CA_TIER_STATUS_HARD, 2);
		assertTrue(sync.changedSince(nextTick()));
	}

	/**
	 * Quests are in the gate too. They are read through a clientscript rather
	 * than a varbit, so a gate built only from varbits would compare equal
	 * across a quest completion and never send it.
	 */
	@Test
	public void aQuestCompletionReopensTheGate()
	{
		sync.markSynced(sync.snapshot());
		questStack = new int[]{2};   // every quest now reads as finished
		assertTrue(sync.changedSince(nextTick()));
	}

	/**
	 * Karamja is the one diary whose easy, medium and hard tiers have no
	 * completion varbit — they are derived from the task counts against the
	 * game's own totals. A tier is done at its total and not one task before it.
	 */
	@Test
	public void karamjasDerivedTiersTurnOverAtTheirTaskTotals()
	{
		varbits.put(VarbitID.KARAMJA_EASY_COUNT, 9);
		varbits.put(VarbitID.KARAMJA_MED_COUNT, 19);
		varbits.put(VarbitID.KARAMJA_HARD_COUNT, 10);
		JsonObject karamja = sync.snapshot().getAsJsonObject("diaries").getAsJsonObject("karamja");
		assertFalse(karamja.get("easy").getAsBoolean());
		assertTrue(karamja.get("medium").getAsBoolean());
		assertTrue(karamja.get("hard").getAsBoolean());
		// Elite is the one tier Jagex did give a completion varbit.
		assertFalse(karamja.get("elite").getAsBoolean());

		varbits.put(VarbitID.KARAMJA_EASY_COUNT, 10);
		varbits.put(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 1);
		JsonObject done = nextTick().getAsJsonObject("diaries").getAsJsonObject("karamja");
		assertTrue(done.get("easy").getAsBoolean());
		assertTrue(done.get("elite").getAsBoolean());
	}

	/**
	 * A tier count that climbs past its total stays complete — the derivation is
	 * a threshold, not an equality, so a Jagex task addition cannot un-finish a
	 * diary the player has already done.
	 */
	@Test
	public void aDerivedTierStaysCompletePastItsTotal()
	{
		varbits.put(VarbitID.KARAMJA_MED_COUNT, 25);
		assertTrue(sync.snapshot().getAsJsonObject("diaries")
			.getAsJsonObject("karamja").get("medium").getAsBoolean());
	}

	// ── The account boundary ─────────────────────────────────────────────

	/**
	 * The obligation reset() carries: the next account must sync afresh under
	 * its own name. Two accounts can easily hold byte-identical achievement
	 * state — a pair of fresh irons, most obviously — and without the clear the
	 * second one's snapshot would match the first's ack and never be sent, so
	 * that account's journal would simply never receive its achievements.
	 */
	@Test
	public void resetForcesTheNextAccountToSyncEvenIfItLooksTheSame()
	{
		JsonObject alpha = sync.snapshot();
		sync.markSynced(alpha);
		assertFalse(sync.changedSince(alpha));

		sync.reset();

		JsonObject beta = nextTick();
		assertEquals(alpha.toString(), beta.toString());   // byte-identical state
		assertTrue(sync.changedSince(beta));               // and still sent
	}

	/**
	 * reset() also drops the tick cache. The snapshot is built from whatever the
	 * client currently holds, so one taken under the outgoing account must never
	 * be handed to a caller after the boundary — even inside the same tick.
	 */
	@Test
	public void resetDropsTheCachedSnapshotWithinTheSameTick()
	{
		JsonObject before = sync.snapshot();
		sync.reset();
		assertNotSame(before, sync.snapshot());
	}

	// ── The wire shape ───────────────────────────────────────────────────

	/**
	 * The server derives everything from raw names and values, so the shape is
	 * the contract: three sections, every diary region present with four named
	 * tiers, and combat carrying points alongside its six tier statuses. Nothing
	 * here is interpreted plugin-side, which is what lets the grading change
	 * server-side without a plugin release.
	 */
	@Test
	public void theSnapshotCarriesTheSectionsTheServerDerivesFrom()
	{
		JsonObject snap = sync.snapshot();
		assertTrue(snap.has("quests"));
		assertTrue(snap.has("diaries"));
		assertTrue(snap.has("combat"));

		JsonObject diaries = snap.getAsJsonObject("diaries");
		// The eleven fully-varbitted diaries plus Karamja.
		assertEquals(12, diaries.size());
		for (Map.Entry<String, com.google.gson.JsonElement> e : diaries.entrySet())
		{
			JsonObject region = e.getValue().getAsJsonObject();
			assertEquals(e.getKey(), 4, region.size());
			assertTrue(e.getKey(), region.has("easy"));
			assertTrue(e.getKey(), region.has("medium"));
			assertTrue(e.getKey(), region.has("hard"));
			assertTrue(e.getKey(), region.has("elite"));
		}

		JsonObject combat = snap.getAsJsonObject("combat");
		assertTrue(combat.has("points"));
		assertEquals(6, combat.getAsJsonObject("tiers").size());
		assertTrue(combat.getAsJsonObject("tiers").has("grandmaster"));

		// Quests travel as raw enum-state names for the server to grade.
		assertTrue(snap.getAsJsonObject("quests").size() > 100);
		assertEquals("NOT_STARTED", snap.getAsJsonObject("quests")
			.entrySet().iterator().next().getValue().getAsString());
	}
}
