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
	public void anImportIntoAnUnmountedAccountIsRefused()
	{
		store.endSession();
		assertTrue(store.importJournal(export(), "Tester") == null);
	}
}
