/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptEvent;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the collection-log capture at the two points where it is load-bearing.
 *
 * <p>First, the own-account rule. A collection log reached through a POH
 * adventure log belongs to whoever owns the house, not to the player running the
 * client, and the plugin must never harvest it — so the guard is asserted on the
 * whole realistic sequence (open, transmit burst, page draw, flush) rather than
 * one call at a time, and {@link #ownPageDrawIsScraped()} runs the identical
 * fixture with the varbit clear so the guard test cannot pass vacuously.
 *
 * <p>Second, the transmit burst. A full log streams over several ticks, so the
 * capture is published as one whole on a settling deadline; the deadline and the
 * re-entry guard are the difference between one open capturing the log and one
 * open capturing a fragment, and neither is visible at runtime when it breaks.
 */
public class ClogCaptureTest
{
	// The game's own script and varp numbers — the protocol this capture reacts
	// to, not choices the plugin gets to make.
	private static final int COLLECTION_LOG_SETUP = 7797;
	private static final int COLLECTION_DELAYED_TRANSMIT = 4100;
	private static final int COLLECTION_INIT_SCRIPT = 2240;
	private static final int VARP_CLOG_OBTAINED = 2943;
	private static final int VARP_CLOG_TOTAL = 2944;
	private static final int VARP_BOSSES_OBTAINED = 4613;
	private static final int VARP_BOSSES_TOTAL = 4614;

	private static final int DRACONIC_VISAGE = 11286;
	private static final int VORKATHS_HEAD = 21907;
	private static final int JAR_OF_DECAY = 21813;

	private Client client;
	private ClogCapture capture;
	private final Map<Integer, String> itemNames = new HashMap<>();
	private int tick = 100;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenAnswer(inv ->
		{
			ItemComposition c = Mockito.mock(ItemComposition.class);
			Mockito.when(c.getName()).thenReturn(itemNames.getOrDefault(
				(Integer) inv.getArgument(0), ""));
			return c;
		});
		Mockito.when(client.getTickCount()).thenAnswer(inv -> tick);
		itemNames.put(DRACONIC_VISAGE, "Draconic visage");
		itemNames.put(VORKATHS_HEAD, "Vorkath's head");
		itemNames.put(JAR_OF_DECAY, "Jar of decay");
		capture = new ClogCapture(client, im);
	}

	// ── fixture ────────────────────────────────────────────────────────────

	private void adventureLogOpen(boolean open)
	{
		Mockito.when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN))
			.thenReturn(open ? 1 : 0);
	}

	/** The player opening their own log: the client fires SETUP at us. */
	private void logOpened()
	{
		capture.onScriptPostFired(new ScriptPostFired(COLLECTION_LOG_SETUP));
	}

	private void transmit(int itemId, int quantity)
	{
		ScriptEvent se = Mockito.mock(ScriptEvent.class);
		Mockito.when(se.getArguments())
			.thenReturn(new Object[]{COLLECTION_DELAYED_TRANSMIT, itemId, quantity});
		ScriptPreFired e = new ScriptPreFired(COLLECTION_DELAYED_TRANSMIT);
		e.setScriptEvent(se);
		capture.onScriptPreFired(e);
	}

	private void pageDrawn()
	{
		capture.onScriptPostFired(new ScriptPostFired(ScriptID.COLLECTION_DRAW_LIST));
	}

	private void tickTo(int target)
	{
		while (tick < target)
		{
			tick++;
			capture.onGameTick(new GameTick());
		}
	}

	private void gameState(GameState state)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(state);
		capture.onGameStateChanged(e);
	}

	private Widget textWidget(String s)
	{
		Widget w = Mockito.mock(Widget.class);
		Mockito.when(w.getText()).thenReturn(s);
		return w;
	}

	private Widget itemWidget(int id, int quantity, int opacity)
	{
		Widget w = Mockito.mock(Widget.class);
		Mockito.when(w.getItemId()).thenReturn(id);
		Mockito.when(w.getItemQuantity()).thenReturn(quantity);
		Mockito.when(w.getOpacity()).thenReturn(opacity);
		return w;
	}

	/**
	 * A drawn Vorkath page: a kill-count header carrying colour tags and a
	 * thousands separator, one plain obtained item, one obtained item the widget
	 * reports as quantity 0, and one greyed (unobtained) item.
	 */
	private void stubVorkathPage()
	{
		// Built before the stubbing call: mocks created inside an unfinished
		// when(...).thenReturn(...) corrupt Mockito's stubbing state.
		Widget[] head = {
			textWidget("Vorkath"),
			textWidget("Obtained: 12/33"),
			textWidget("<col=ff9040>Killcount:</col> 1,234"),
		};
		Widget[] kids = {
			itemWidget(DRACONIC_VISAGE, 1, 0),
			itemWidget(VORKATHS_HEAD, 0, 0),
			itemWidget(JAR_OF_DECAY, 1, 100),
		};
		Widget header = Mockito.mock(Widget.class);
		Mockito.when(header.getDynamicChildren()).thenReturn(head);
		Widget items = Mockito.mock(Widget.class);
		Mockito.when(items.getDynamicChildren()).thenReturn(kids);
		Mockito.when(client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER))
			.thenReturn(header);
		Mockito.when(client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS))
			.thenReturn(items);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Integer> clogItems()
	{
		return (Map<String, Integer>) capture.snapshot().get("clog_items");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Map<String, Integer>> byCat()
	{
		return (Map<String, Map<String, Integer>>) capture.snapshot().get("by_cat");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Integer> kcs()
	{
		return (Map<String, Integer>) capture.snapshot().get("kcs");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Integer> catCounts()
	{
		return (Map<String, Integer>) capture.snapshot().get("cat_counts");
	}

	private void verifyNoTransmitRequested()
	{
		Mockito.verify(client, Mockito.never()).menuAction(
			Mockito.anyInt(), Mockito.anyInt(), Mockito.any(MenuAction.class),
			Mockito.anyInt(), Mockito.anyInt(), Mockito.any(), Mockito.any());
		Mockito.verify(client, Mockito.never()).runScript(COLLECTION_INIT_SCRIPT);
	}

	// ── own account only ───────────────────────────────────────────────────

	/**
	 * Another player's log, opened through their POH adventure log, must leave no
	 * trace anywhere in the snapshot — and must not even ask the server to
	 * transmit it. Everything a real session would fire is fired here.
	 */
	@Test
	public void adventureLogOpenYieldsNothingAnywhere()
	{
		adventureLogOpen(true);
		stubVorkathPage();

		logOpened();
		transmit(DRACONIC_VISAGE, 1);
		transmit(VORKATHS_HEAD, 1);
		pageDrawn();
		tickTo(110);

		assertTrue(clogItems().isEmpty());
		assertTrue(byCat().isEmpty());
		assertTrue(kcs().isEmpty());
		assertFalse(capture.isDirty());
		verifyNoTransmitRequested();
	}

	/**
	 * The control for the test above: the identical page fixture, drawn by the
	 * player on their own account, is read. Without this the guard could be
	 * passing because nothing was ever wired up to be captured.
	 */
	@Test
	public void ownPageDrawIsScraped()
	{
		adventureLogOpen(false);
		stubVorkathPage();

		pageDrawn();

		Map<String, Integer> page = byCat().get("Vorkath");
		assertEquals(Integer.valueOf(1), page.get("Draconic visage"));
		// An obtained item whose widget reports no quantity still counts as one.
		assertEquals(Integer.valueOf(1), page.get("Vorkath's head"));
		// Greyed items are the ones still to come; they are not holdings.
		assertNull(page.get("Jar of decay"));
		assertEquals(2, page.size());
		// The count comes off the kill-count line, not the "Obtained" line above
		// it, and survives both its colour tags and its thousands separator.
		assertEquals(Integer.valueOf(1234), kcs().get("Vorkath"));
		assertTrue(capture.isDirty());
	}

	// ── the full-log transmit burst ────────────────────────────────────────

	/**
	 * One open captures the whole log. The entries stream in over several ticks,
	 * so each arrival pushes the settling deadline out and the capture is only
	 * published once it stops growing — a push taken mid-burst would send a
	 * fragment of the log as though it were all of it.
	 */
	@Test
	public void transmitBurstAccretesAndPublishesOnlyOnTheFlushTick()
	{
		adventureLogOpen(false);

		logOpened();
		Mockito.verify(client).menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE,
			MenuAction.CC_OP, 1, -1, "Search", null);
		Mockito.verify(client).runScript(COLLECTION_INIT_SCRIPT);

		tickTo(101);
		transmit(DRACONIC_VISAGE, 1);
		tickTo(102);
		transmit(VORKATHS_HEAD, 7);

		// Both are already held, but the burst is not publishable yet.
		assertEquals(Integer.valueOf(1), clogItems().get("Draconic visage"));
		assertEquals(Integer.valueOf(7), clogItems().get("Vorkath's head"));
		assertFalse(capture.isDirty());

		// The second entry moved the deadline; a log that keeps streaming is not
		// cut off at the deadline the first entry set.
		tickTo(104);
		assertFalse(capture.isDirty());

		tickTo(105);
		assertTrue(capture.isDirty());
		assertEquals(2, clogItems().size());
	}

	/**
	 * Resetting the view re-fires SETUP at us. That is our own doing, and it must
	 * not clear the entries already captured or ask the server for a second
	 * transmit — the re-entry guard is what makes a single open idempotent.
	 */
	@Test
	public void ourOwnReTriggerDoesNotWipeTheCapture()
	{
		adventureLogOpen(false);

		logOpened();
		tickTo(101);
		transmit(DRACONIC_VISAGE, 1);

		logOpened();

		assertEquals(Integer.valueOf(1), clogItems().get("Draconic visage"));
		Mockito.verify(client, Mockito.times(1)).menuAction(
			Mockito.anyInt(), Mockito.anyInt(), Mockito.any(MenuAction.class),
			Mockito.anyInt(), Mockito.anyInt(), Mockito.any(), Mockito.any());
	}

	/**
	 * A log that transmits nothing at all still has to release the guard, or the
	 * account would be unable to capture for the rest of the session.
	 */
	@Test
	public void emptyLogStillClearsTheRetrieveGuard()
	{
		adventureLogOpen(false);

		logOpened();
		tickTo(105);

		// Nothing was captured, so there is nothing to publish.
		assertFalse(capture.isDirty());
		assertTrue(clogItems().isEmpty());

		// A genuine second open is served.
		logOpened();
		Mockito.verify(client, Mockito.times(2)).menuAction(
			Mockito.anyInt(), Mockito.anyInt(), Mockito.any(MenuAction.class),
			Mockito.anyInt(), Mockito.anyInt(), Mockito.any(), Mockito.any());
	}

	/**
	 * The transmit arguments come straight from the game. A short or absent
	 * argument list is skipped rather than thrown, because this runs on the
	 * shared event fan-out where a throw costs every later subscriber its event.
	 */
	@Test
	public void malformedTransmitIsIgnored()
	{
		adventureLogOpen(false);
		logOpened();

		ScriptEvent shortArgs = Mockito.mock(ScriptEvent.class);
		Mockito.when(shortArgs.getArguments()).thenReturn(new Object[]{COLLECTION_DELAYED_TRANSMIT});
		ScriptPreFired a = new ScriptPreFired(COLLECTION_DELAYED_TRANSMIT);
		a.setScriptEvent(shortArgs);
		capture.onScriptPreFired(a);

		ScriptEvent noArgs = Mockito.mock(ScriptEvent.class);
		Mockito.when(noArgs.getArguments()).thenReturn(null);
		ScriptPreFired b = new ScriptPreFired(COLLECTION_DELAYED_TRANSMIT);
		b.setScriptEvent(noArgs);
		capture.onScriptPreFired(b);

		assertTrue(clogItems().isEmpty());
	}

	// ── the shape the server merges on ─────────────────────────────────────

	/**
	 * The server merges pushes key by key, so the snapshot's key set is a wire
	 * contract: a key renamed or added here is silently ignored upstream until
	 * the server learns it.
	 */
	@Test
	public void snapshotCarriesTheServersMergeKeys()
	{
		Set<String> expected = new HashSet<>(Arrays.asList(
			"by_cat", "kcs", "slayer_kcs", "cat_counts", "clog_items",
			"finished", "available"));
		assertEquals(expected, capture.snapshot().keySet());
	}

	// ── account boundary ───────────────────────────────────────────────────

	/**
	 * Reaching the login screen ends the account. Nothing captured for one
	 * player may still be present to be pushed under the next one's name.
	 */
	@Test
	public void loginScreenClearsEverything()
	{
		adventureLogOpen(false);
		stubVorkathPage();
		logOpened();
		tickTo(101);
		transmit(DRACONIC_VISAGE, 1);
		pageDrawn();
		tickTo(105);
		assertTrue(capture.isDirty());

		gameState(GameState.LOGIN_SCREEN);

		assertTrue(clogItems().isEmpty());
		assertTrue(byCat().isEmpty());
		assertTrue(kcs().isEmpty());
		assertTrue(catCounts().isEmpty());
		assertEquals(0, capture.finishedCount());
		assertEquals(0, capture.availableCount());
		assertFalse(capture.isDirty());
	}

	// ── the login-synced counts (no interface needed) ──────────────────────

	@Test
	public void loginVarpsFillTheAccountWideCounts()
	{
		Mockito.when(client.getVarpValue(VARP_CLOG_OBTAINED)).thenReturn(742);
		Mockito.when(client.getVarpValue(VARP_CLOG_TOTAL)).thenReturn(1920);
		Mockito.when(client.getVarpValue(VARP_BOSSES_OBTAINED)).thenReturn(250);
		Mockito.when(client.getVarpValue(VARP_BOSSES_TOTAL)).thenReturn(600);

		gameState(GameState.LOGGED_IN);

		assertEquals(742, capture.finishedCount());
		assertEquals(1920, capture.availableCount());
		assertEquals(Integer.valueOf(250), catCounts().get("bosses_obtained"));
		assertEquals(Integer.valueOf(600), catCounts().get("bosses_total"));
		assertTrue(capture.isDirty());
	}

	/**
	 * The varps read as zero until the server has synced them. A login taken
	 * before that must leave the counts alone rather than record the account as
	 * having collected nothing.
	 */
	@Test
	public void loginBeforeTheVarpsSyncDoesNotZeroTheCounts()
	{
		Mockito.when(client.getVarpValue(VARP_CLOG_OBTAINED)).thenReturn(742);
		Mockito.when(client.getVarpValue(VARP_CLOG_TOTAL)).thenReturn(1920);
		Mockito.when(client.getVarpValue(VARP_BOSSES_OBTAINED)).thenReturn(250);
		Mockito.when(client.getVarpValue(VARP_BOSSES_TOTAL)).thenReturn(600);
		gameState(GameState.LOGGED_IN);
		capture.clearDirty();

		Mockito.when(client.getVarpValue(Mockito.anyInt())).thenReturn(0);
		gameState(GameState.LOGGED_IN);

		assertEquals(742, capture.finishedCount());
		assertEquals(1920, capture.availableCount());
		assertEquals(Integer.valueOf(250), catCounts().get("bosses_obtained"));
		assertFalse(capture.isDirty());
	}
}
