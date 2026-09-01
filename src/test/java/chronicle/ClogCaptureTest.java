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

/** Collection-log capture: the own-account guard, and the full-log transmit burst. */
public class ClogCaptureTest
{
	// game script and varp ids, mirrored from ClogCapture.
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

	// the player's own open: the client fires SETUP at us.
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

	// A drawn Vorkath page: kc header with colour tags and a thousands separator,
	// one plain obtained item, one obtained item the widget reports as quantity 0,
	// one greyed.
	private void stubVorkathPage()
	{
		// arrays built first; a mock created inside an unfinished
		// when(...).thenReturn(...) corrupts Mockito's stubbing state.
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

	// A log opened through a POH adventure log belongs to the house owner. The whole
	// real sequence is fired here: open, transmit, page draw, flush.
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

	// Control for the test above: same fixture with the varbit clear does get read,
	// so the guard test can't pass on a fixture that captures nothing anyway.
	@Test
	public void ownPageDrawIsScraped()
	{
		adventureLogOpen(false);
		stubVorkathPage();

		pageDrawn();

		Map<String, Integer> page = byCat().get("Vorkath");
		assertEquals(Integer.valueOf(1), page.get("Draconic visage"));
		// an obtained item whose widget reports no quantity still counts as one.
		assertEquals(Integer.valueOf(1), page.get("Vorkath's head"));
		// greyed means unobtained.
		assertNull(page.get("Jar of decay"));
		assertEquals(2, page.size());
		// kc comes off the Killcount line rather than the Obtained line above it,
		// with colour tags and the comma stripped.
		assertEquals(Integer.valueOf(1234), kcs().get("Vorkath"));
		assertTrue(capture.isDirty());
	}

	// ── the full-log transmit burst ────────────────────────────────────────

	// Entries stream in over several ticks and each one pushes the flush deadline
	// out, so a push taken mid-burst can't go out carrying a fragment of the log.
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

		// both held already, nothing publishable yet.
		assertEquals(Integer.valueOf(1), clogItems().get("Draconic visage"));
		assertEquals(Integer.valueOf(7), clogItems().get("Vorkath's head"));
		assertFalse(capture.isDirty());

		// the second entry moved the deadline out past the first one's 104.
		tickTo(104);
		assertFalse(capture.isDirty());

		tickTo(105);
		assertTrue(capture.isDirty());
		assertEquals(2, clogItems().size());
	}

	// The init script we run to reset the view re-fires SETUP; the re-entry guard
	// keeps that from wiping the entries or asking for a second transmit.
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

	// A log that transmits nothing still has to release the guard on the fallback
	// deadline, or nothing captures for the rest of the session.
	@Test
	public void emptyLogStillClearsTheRetrieveGuard()
	{
		adventureLogOpen(false);

		logOpened();
		tickTo(105);

		assertFalse(capture.isDirty());
		assertTrue(clogItems().isEmpty());

		// a genuine second open still gets its Search op.
		logOpened();
		Mockito.verify(client, Mockito.times(2)).menuAction(
			Mockito.anyInt(), Mockito.anyInt(), Mockito.any(MenuAction.class),
			Mockito.anyInt(), Mockito.anyInt(), Mockito.any(), Mockito.any());
	}

	// the transmit args come straight from the game, so they can be short or missing.
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

	// ── snapshot shape ─────────────────────────────────────────────────────

	// LocalStore.mergeClog floor-merges these keys by name and the panel reads them
	// back, so a rename here drops the data silently.
	@Test
	public void snapshotCarriesTheServersMergeKeys()
	{
		Set<String> expected = new HashSet<>(Arrays.asList(
			"by_cat", "kcs", "slayer_kcs", "cat_counts", "clog_items",
			"finished", "available"));
		assertEquals(expected, capture.snapshot().keySet());
	}

	// ── account boundary ───────────────────────────────────────────────────

	// The login screen ends the account: nothing captured for one player may still
	// be sitting there to go out under the next one's name.
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

	// ── login-synced counts, no interface needed ───────────────────────────

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

	// The varps read 0 until the game syncs them, so a LOGGED_IN taken early has to
	// leave the counts alone instead of recording an account that collected nothing.
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
