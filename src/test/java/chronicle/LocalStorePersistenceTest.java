/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
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
 * Pins the journal's life on disk: a flushed record reloads into the same model,
 * lifetime counters resume from the stored base instead of restarting at zero, a
 * record that cannot be read is kept intact rather than replaced by a blank one,
 * and a record missing its containers (or holding the wrong shape in them) is
 * repaired at load instead of throwing on the next event.
 */
public class LocalStorePersistenceTest
{
	private static final String RSN = "Tester";
	private static final String FILE = "tester.json";

	private File dir;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("chronicle-persist").toFile();
	}

	/** A store as a freshly started client holds it: nothing mounted yet. */
	private LocalStore newStore()
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(100);
		ItemComposition comp = Mockito.mock(ItemComposition.class);
		Mockito.when(comp.getName()).thenReturn("Rune dagger");
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenReturn(comp);
		return new LocalStore(im, new Gson());
	}

	/** A second client mounting the same account off the same directory. */
	private LocalStore mounted()
	{
		LocalStore store = newStore();
		store.load(dir, RSN);
		return store;
	}

	private void kill(LocalStore store, String source, int kc, int itemId, int qty)
	{
		JsonObject data = new JsonObject();
		data.addProperty("source", source);
		data.addProperty("killCount", kc);
		JsonArray items = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", itemId);
		it.addProperty("quantity", qty);
		items.add(it);
		data.add("items", items);
		store.record("LOOT", data, RSN);
	}

	private void pet(LocalStore store, String name)
	{
		JsonObject data = new JsonObject();
		data.addProperty("name", name);
		store.record("PET", data, RSN);
	}

	private Map<String, Integer> session(String key, int value)
	{
		Map<String, Integer> m = new HashMap<>();
		m.put(key, value);
		return m;
	}

	private void write(String name, String content) throws Exception
	{
		Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String name) throws Exception
	{
		return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
	}

	private JsonObject readJson(String name) throws Exception
	{
		return new Gson().fromJson(read(name), JsonObject.class);
	}

	/** The one file set aside under {@code prefix} (the timestamp suffix varies). */
	private String onlySidecar(String prefix)
	{
		String[] hits = dir.list((d, name) -> name.startsWith(prefix));
		assertTrue("no sidecar for " + prefix, hits != null && hits.length == 1);
		return hits[0];
	}

	private LocalStore.SourceRow source(LocalStore store, String name)
	{
		for (LocalStore.SourceRow row : store.dropSources())
		{
			if (name.equals(row.name))
			{
				return row;
			}
		}
		throw new AssertionError("no drop source " + name);
	}

	@Test
	public void aFlushedJournalReloadsIntoTheSameModel()
	{
		LocalStore first = mounted();
		kill(first, "Nechryael", 7, 4151, 2);
		pet(first, "Abyssal orphan");
		first.setTrackers(session("deaths", 3), RSN);
		first.flush(dir);

		// The file is the system of record, not the live object: everything the
		// panel reads has to come back from disk on the next client.
		LocalStore second = mounted();
		LocalStore.SourceRow row = source(second, "Nechryael");
		assertEquals(7, row.kc);
		assertEquals(1, row.loots);
		assertEquals(200L, row.value);

		List<LocalStore.BagItem> bag = second.sourceItems("Nechryael");
		assertEquals(1, bag.size());
		assertEquals(4151, bag.get(0).itemId);
		assertEquals(2L, bag.get(0).qty);
		assertEquals(200L, bag.get(0).value);
		// Names and prices are frozen at ingest, so the reload must not need an
		// ItemManager to say what was found or what it was worth that day.
		assertEquals("Rune dagger", bag.get(0).name);

		List<JsonObject> feed = second.feedNewest(10);
		assertEquals(1, feed.size());
		assertEquals("PET", feed.get(0).get("type").getAsString());
		assertEquals("Abyssal orphan",
			feed.get(0).getAsJsonObject("data").get("name").getAsString());

		assertEquals(Long.valueOf(3), second.trackersSnapshot().get("deaths"));
	}

	@Test
	public void lifetimeCountersResumeFromTheStoredBaseNotFromZero()
	{
		LocalStore first = mounted();
		// The session figure is a from-zero running total restated as it grows,
		// so restating it must not add a second time.
		first.setTrackers(session("deaths", 4), RSN);
		first.setTrackers(session("deaths", 4), RSN);
		assertEquals(Long.valueOf(4), first.trackersSnapshot().get("deaths"));
		first.flush(dir);

		LocalStore second = mounted();
		second.setTrackers(session("deaths", 4), RSN);
		assertEquals(Long.valueOf(8), second.trackersSnapshot().get("deaths"));
	}

	@Test
	public void aPeakCounterKeepsTheRecordAcrossSessionsInsteadOfSumming()
	{
		LocalStore first = mounted();
		first.setTrackers(session("highestHit", 60), RSN);
		first.flush(dir);

		LocalStore second = mounted();
		second.setTrackers(session("highestHit", 50), RSN);
		// A smaller hit this session does not raise the lifetime best, and the
		// two are never added together.
		assertEquals(Long.valueOf(60), second.trackersSnapshot().get("highestHit"));

		second.setTrackers(session("highestHit", 71), RSN);
		assertEquals(Long.valueOf(71), second.trackersSnapshot().get("highestHit"));
	}

	@Test
	public void anUnreadableRecordIsKeptAsideRatherThanOverwritten() throws Exception
	{
		// A torn write loses the tail, so the file no longer parses. It is the
		// only copy of the account's history and the next flush would land right
		// on top of it.
		String torn = "{\"schema\":1,\"rsn\":\"Tester\",\"drops\":{\"Nechryael\":{\"kc\":91";
		write(FILE, torn);

		LocalStore store = mounted();
		store.flush(dir);

		assertEquals(torn, read(onlySidecar(FILE + ".corrupt-")));
		// Play continues onto a fresh record rather than stalling.
		kill(store, "Nechryael", 1, 4151, 1);
		store.flush(dir);
		assertEquals(1, source(mounted(), "Nechryael").loots);
	}

	@Test
	public void aRecordThatIsNotAnObjectIsTreatedAsUnreadable() throws Exception
	{
		// Parseable JSON, but not a record: adopting it would hand every later
		// read a shape it cannot navigate.
		write(FILE, "[1,2,3]");
		mounted().flush(dir);
		assertEquals("[1,2,3]", read(onlySidecar(FILE + ".corrupt-")));
	}

	@Test
	public void aRecordMissingItsContainersIsRepairedOnLoad() throws Exception
	{
		write(FILE, "{\"schema\":1,\"rsn\":\"Tester\"}");
		LocalStore store = mounted();

		// Each ingest path writes straight into its container. Unrepaired, these
		// throw on the client thread and the event bus swallows it, so the loss
		// would show up only as counters that quietly stopped moving.
		kill(store, "Nechryael", 3, 4151, 1);
		pet(store, "Abyssal orphan");
		store.setTrackers(session("deaths", 1), RSN);

		assertEquals(1, source(store, "Nechryael").loots);
		assertEquals(1, store.feedNewest(10).size());
		assertEquals(Long.valueOf(1), store.trackersSnapshot().get("deaths"));
	}

	@Test
	public void containersHoldingTheWrongShapeAreReplacedNotTrusted() throws Exception
	{
		// A hand-edited record: the keys are all present, none of them usable.
		write(FILE, "{\"schema\":1,\"rsn\":\"Tester\",\"drops\":[],\"trackers\":7,\"feed\":{}}");
		LocalStore store = mounted();

		kill(store, "Nechryael", 2, 4151, 1);
		pet(store, "Abyssal orphan");
		store.setTrackers(session("deaths", 2), RSN);

		assertEquals(1, source(store, "Nechryael").loots);
		assertEquals(1, store.feedNewest(10).size());
		assertEquals(Long.valueOf(2), store.trackersSnapshot().get("deaths"));
	}

	@Test
	public void theJournalKeepsItsOriginalStartDateAcrossReloads() throws Exception
	{
		write(FILE, "{\"schema\":1,\"rsn\":\"Tester\",\"first_seen\":12345}");
		mounted().flush(dir);
		// The dateline the panel prints belongs to the account, not to whichever
		// session happened to load it last.
		assertEquals(12345L, readJson(FILE).get("first_seen").getAsLong());
	}

	@Test
	public void anUnmountedStoreWritesNothing()
	{
		// Account boundary: with nothing mounted there is no record to write, and
		// a skeleton written here would be filed under a name we do not know yet.
		newStore().flush(dir);
		assertFalse(new File(dir, FILE).isFile());
		assertEquals(0, dir.list().length);
	}

	@Test
	public void theRecordIsFiledUnderTheAccountSlug()
	{
		// load() and flush() have to agree on the path, and it is the slug the
		// rename migration moves when the account is renamed.
		LocalStore store = newStore();
		store.load(dir, "Some Name");
		store.flush(dir);
		assertTrue(new File(dir, "some-name.json").isFile());
	}

	@Test
	public void theJournalDirectoryIsCreatedOnFirstFlush()
	{
		// First run on a new install: nothing under the profile directory exists.
		File fresh = new File(dir, "nested/local");
		LocalStore store = newStore();
		store.load(fresh, RSN);
		store.flush(fresh);
		assertTrue(new File(fresh, FILE).isFile());
	}
}
