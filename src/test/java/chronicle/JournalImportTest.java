/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the import's central promise: every store merges as a floor, so folding
 * the same record in twice — or folding an older copy in after a newer one —
 * changes nothing. An import is another copy of the SAME account's history, so
 * anything that added would double every kill the two copies share.
 */
public class JournalImportTest
{
	private LocalStore store;
	private File dir;
	private final Gson gson = new Gson();

	@Before
	public void setUp() throws Exception
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(10);
		ItemComposition comp = Mockito.mock(ItemComposition.class);
		Mockito.when(comp.getName()).thenReturn("Rune dagger");
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenReturn(comp);
		store = new LocalStore(im, gson);
		dir = Files.createTempDirectory("chronicle-import").toFile();
		store.load(dir, "Tester");
	}

	private JsonObject export()
	{
		return gson.fromJson("{"
			+ "\"trackers\":{\"tilesWalked\":5000,\"fishCaught\":120},"
			+ "\"drops\":{\"Nechryael\":{\"kc\":300,\"loots\":280,\"value\":900000,"
			+ "  \"pb\":42.5,\"first_seen\":1000,\"last_seen\":9000,"
			+ "  \"items\":{\"Rune dagger\":{\"id\":1215,\"qty\":7,\"value\":70000}}}},"
			+ "\"feed\":[{\"ts\":1700000000000,\"type\":\"PET\",\"data\":{\"petName\":\"Phoenix\"}},"
			+ "         {\"ts\":1700000001000,\"type\":\"QUEST\",\"data\":{}}],"
			+ "\"untaken\":{\"Nechryael\":{\"qty\":40,\"value\":4000}},"
			+ "\"consumable_values\":{\"sharksEaten\":50000},"
			+ "\"slayer\":{\"completed\":214,\"tasks\":[{\"task\":\"Nechryael\",\"kills\":180}]}"
			+ "}", JsonObject.class);
	}

	@Test
	public void importingTwiceIsTheSameAsImportingOnce()
	{
		assertNotNull(store.importJournal(export(), "Tester"));
		JsonObject after = snapshot();
		store.importJournal(export(), "Tester");
		assertEquals(after.toString(), snapshot().toString());
	}

	private JsonObject snapshot()
	{
		JsonObject o = new JsonObject();
		o.add("trackers", gson.toJsonTree(store.trackersSnapshot()));
		o.addProperty("feed", store.feedNewest(500).size());
		o.addProperty("sources", store.dropSources().size());
		for (LocalStore.SourceRow r : store.dropSources())
		{
			o.addProperty(r.name, r.kc + "/" + r.loots + "/" + r.value);
		}
		o.addProperty("journeyTasks", store.slayerJourney().tasks.size());
		o.addProperty("completed", store.slayerJourney().completedTasks);
		return o;
	}

	@Test
	public void anOlderExportNeverLowersWhatIsAlreadyHeld()
	{
		store.importJournal(export(), "Tester");
		JsonObject older = gson.fromJson("{\"trackers\":{\"tilesWalked\":10},"
			+ "\"drops\":{\"Nechryael\":{\"kc\":3,\"loots\":2,\"value\":5,\"pb\":99.0,"
			+ "  \"first_seen\":50,\"items\":{\"Rune dagger\":{\"qty\":1,\"value\":1}}}},"
			+ "\"slayer\":{\"completed\":2,\"tasks\":[]}}", JsonObject.class);
		store.importJournal(older, "Tester");
		assertEquals(5000L, (long) store.trackersSnapshot().get("tilesWalked"));
		LocalStore.SourceRow n = store.dropSources().get(0);
		assertEquals(300, n.kc);
		assertEquals(900000L, n.value);
		// A personal best is a MINIMUM, so the older file's better time does win.
		assertEquals(42.5, n.pb, 0.001);
		// …and its earlier first-sighting pushes "tracked since" further back.
		assertEquals(50L, n.firstMs);
		assertEquals(214, store.slayerJourney().completedTasks);
	}

	@Test
	public void localPlayIsNeverOverwrittenByAnImport()
	{
		// This client witnessed more than the exported copy holds.
		store.setTrackers(java.util.Collections.singletonMap("tilesWalked", 9999), "Tester");
		store.importJournal(export(), "Tester");
		assertEquals(9999L, (long) store.trackersSnapshot().get("tilesWalked"));
		// …while a counter only the export knows about still comes across.
		assertEquals(120L, (long) store.trackersSnapshot().get("fishCaught"));
	}

	@Test
	public void detailBackfillsIntoSegmentsThatAlreadyExist()
	{
		// A spine adopted from an older record: totals, no composition.
		store.importJournal(gson.fromJson("{\"slayer\":{\"completed\":2,\"tasks\":["
			+ "{\"task\":\"Bloodveld\",\"kills\":120,\"value\":50000,\"ts\":1787775856},"
			+ "{\"task\":\"Jellies\",\"kills\":90,\"value\":9000,\"ts\":1787778099}]}}",
			JsonObject.class), "Tester");
		assertEquals(2, store.slayerJourney().tasks.size());

		// The rebuilt export describes the SAME two tasks — its instants round
		// differently, which is why the match is a window and not equality.
		store.importJournal(gson.fromJson("{\"slayer\":{\"completed\":2,\"tasks\":["
			+ "{\"task\":\"Bloodveld\",\"ts\":1787775854,"
			+ "  \"monsters\":{\"Bloodveld\":104,\"Mutated Bloodveld\":16},"
			+ "  \"items\":{\"Dragon boots\":{\"id\":11840,\"qty\":2,\"value\":200000}}},"
			+ "{\"task\":\"Jellies\",\"ts\":1787778098,\"monsters\":{\"Jelly\":90}}]}}",
			JsonObject.class), "Tester");

		// No segments were appended — the same history seen twice.
		assertEquals(2, store.slayerJourney().tasks.size());
		int bloodveld = indexOf("Bloodveld");
		java.util.List<LocalStore.UntakenRow> mons = store.slayerTaskMonsters(bloodveld);
		assertEquals(2, mons.size());
		assertEquals("Bloodveld", mons.get(0).name);
		assertEquals(104, mons.get(0).qty);
		java.util.List<LocalStore.BagItem> bag = store.slayerTaskItems(bloodveld);
		assertEquals(1, bag.size());
		assertEquals("Dragon boots", bag.get(0).name);
		assertEquals(200000L, bag.get(0).value);
	}

	private int indexOf(String task)
	{
		java.util.List<ChronicleApiClient.SlayerTask> t = store.slayerJourney().tasks;
		for (int i = 0; i < t.size(); i++)
		{
			if (t.get(i).task.equals(task))
			{
				return i;
			}
		}
		throw new AssertionError("no segment for " + task);
	}

	@Test
	public void aTaskFarFromAnyLocalSegmentIsNotInvented()
	{
		store.importJournal(gson.fromJson("{\"slayer\":{\"tasks\":["
			+ "{\"task\":\"Bloodveld\",\"kills\":1,\"ts\":1787775856}]}}", JsonObject.class), "Tester");
		// Same task name, months away: a different assignment entirely, so its
		// detail must not be folded into this one.
		store.importJournal(gson.fromJson("{\"slayer\":{\"tasks\":["
			+ "{\"task\":\"Bloodveld\",\"ts\":1700000000,\"monsters\":{\"Bloodveld\":99}}]}}",
			JsonObject.class), "Tester");
		assertEquals(1, store.slayerJourney().tasks.size());
		assertTrue(store.slayerTaskMonsters(0).isEmpty());
	}

	@Test
	public void anExportThatKnowsOnlyNamesMergesIntoWhatIsAlreadyHere()
	{
		// The bag is keyed by item id; the export is keyed by name. Filing the
		// incoming one alongside listed a source's herb twice, each line holding
		// a different partial count.
		store.record("LOOT", gson.fromJson("{\"source\":\"Herbiboar\","
			+ "\"items\":[{\"id\":207,\"quantity\":6}]}", JsonObject.class), "Tester");
		assertEquals(1, store.sourceItems("Herbiboar").size());

		store.importJournal(gson.fromJson("{\"drops\":{\"Herbiboar\":{"
			+ "\"kc\":27,\"loots\":27,\"value\":121000,"
			+ "\"items\":{\"Rune dagger\":{\"qty\":9,\"value\":900}}}}}",
			JsonObject.class), "Tester");

		java.util.List<LocalStore.BagItem> bag = store.sourceItems("Herbiboar");
		assertEquals(1, bag.size());
		assertEquals("Rune dagger", bag.get(0).name);
		assertEquals(9, bag.get(0).qty);     // floored to the higher of the two
	}

	@Test
	public void aRecordAlreadyCarryingDuplicatesHealsOnLoad() throws Exception
	{
		// What an earlier build wrote: the same item under an id key and a name
		// key, each holding part of the count.
		java.io.File f = new java.io.File(dir, "healme.json");
		java.nio.file.Files.write(f.toPath(), ("{\"schema\":1,\"rsn\":\"Healme\","
			+ "\"drops\":{\"Herbiboar\":{\"kc\":27,\"loots\":27,\"value\":1,"
			+ "\"items\":{"
			+ "\"207\":{\"id\":207,\"name\":\"Grimy ranarr weed\",\"qty\":6,\"value\":34000},"
			+ "\"Grimy ranarr weed\":{\"qty\":6,\"value\":35000}}}}}")
			.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		LocalStore healed = new LocalStore(store.items(), gson);
		healed.load(dir, "Healme");
		java.util.List<LocalStore.BagItem> bag = healed.sourceItems("Herbiboar");
		assertEquals(1, bag.size());
		assertEquals("Grimy ranarr weed", bag.get(0).name);
		assertEquals(207, bag.get(0).itemId);   // the id-bearing key survives
		assertEquals(35000L, bag.get(0).value); // and takes the higher figure
	}

	@Test
	public void anImportIntoAnUnmountedAccountIsRefused()
	{
		store.endSession();
		assertTrue(store.importJournal(export(), "Tester") == null);
	}
}
