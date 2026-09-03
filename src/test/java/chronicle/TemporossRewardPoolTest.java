/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Every Tempoross unique is handed over by a search of the reward pool, and a subdue
 * buys several searches. The rate block is keyed to the searches the ledger counted,
 * not to the subdues the collection log counted, or every one of these rows reads
 * kinder than the truth.
 */
public class TemporossRewardPoolTest
{
	private static final String POOL = "Reward pool (Tempoross)";

	// the eight, with the wiki's denominators: 40/160, 8/160, 149/8000, then the rest
	private static final String[] UNIQUES = {
		"Spirit flakes", "Casket", "Soaked page", "Fish barrel", "Tackle box",
		"Big harpoonfish", "Tome of water (empty)", "Dragon harpoon",
	};

	private static List<ChronicleApiClient.GrindRow> rows(JsonObject clog,
		List<LocalStore.SourceRow> sources)
	{
		return new GrindBook(new Gson()).grinds(clog, sources);
	}

	private static ChronicleApiClient.GrindRow find(List<ChronicleApiClient.GrindRow> rows,
		String item)
	{
		for (ChronicleApiClient.GrindRow g : rows)
		{
			if (g.item.equals(item))
			{
				return g;
			}
		}
		return null;
	}

	// the shape of the owner's own record: 46 subdues in the log, 114 searches and
	// 25 caskets in the ledger, no bare Tempoross source anywhere.
	private static JsonObject clog(String... obtained)
	{
		JsonObject clog = new JsonObject();
		JsonObject kcs = new JsonObject();
		kcs.addProperty("Tempoross", 46);
		clog.add("kcs", kcs);
		JsonObject items = new JsonObject();
		for (String o : obtained)
		{
			items.addProperty(o, 1);
		}
		clog.add("clog_items", items);
		return clog;
	}

	private static List<LocalStore.SourceRow> ledger()
	{
		List<LocalStore.SourceRow> out = new ArrayList<>();
		out.add(new LocalStore.SourceRow(POOL, 114, 114, 2_269_132L, null, 0, 0));
		out.add(new LocalStore.SourceRow("Casket (Tempoross)", 25, 25, 230_772L, null, 0, 0));
		return out;
	}

	// 114 searches, not 46 subdues: 1 - (1 - 1/8000)^114 = 1.4%, where the subdue
	// count printed 0.6%. Every row on the block carries the same correction.
	@Test
	public void uniquesArePricedOnSearchesNotSubdues()
	{
		List<ChronicleApiClient.GrindRow> rows = rows(clog(), ledger());
		ChronicleApiClient.GrindRow harpoon = find(rows, "Dragon harpoon");
		assertNotNull("Dragon harpoon has no row", harpoon);
		assertEquals(POOL, harpoon.boss);
		assertEquals(114, harpoon.kc);
		assertEquals(8000, harpoon.rate);
		assertEquals(1.4, harpoon.percentileDry, 0.05);
		for (String item : new String[]{"Fish barrel", "Tackle box", "Big harpoonfish",
			"Tome of water (empty)"})
		{
			ChronicleApiClient.GrindRow g = find(rows, item);
			assertNotNull(item + " has no row", g);
			assertEquals(item + " is not priced on searches", 114, g.kc);
			assertEquals(item + " is off the pool's card", POOL, g.boss);
		}
	}

	// The casket is one of the things a search hands over. Counting the caskets, or
	// the subdues, would price these rows off a number that is not the roll.
	@Test
	public void neitherSubduesNorCasketsPriceTheBlock()
	{
		for (ChronicleApiClient.GrindRow g : rows(clog(), ledger()))
		{
			if (g.boss.toLowerCase(java.util.Locale.ROOT).contains("tempoross"))
			{
				assertTrue(g.item + " priced on " + g.kc, g.kc == 114);
			}
		}
	}

	// The rate block must name the ledger source the searches are counted under, or
	// it resolves to no kill count at all and the whole block goes dark.
	@Test
	public void blockIsKeyedToTheLedgerSourceAndNotTheBoss()
	{
		// nothing but the log's subdue count: the block has no count to read
		JsonObject clog = clog();
		assertTrue("subdues alone must not price the block",
			rows(clog, new ArrayList<>()).isEmpty());
		// the ledger's searches bring it to life
		assertTrue("the ledger source must price the block",
			!rows(clog, ledger()).isEmpty());
	}

	// The log files these items under the boss, not under the pool. A record captured
	// page by page, with no whole-log set behind it, must still read them as owned.
	@Test
	public void obtainedIsReadOffTheBossPage()
	{
		JsonObject clog = clog();
		JsonObject byCat = new JsonObject();
		JsonObject page = new JsonObject();
		for (String u : UNIQUES)
		{
			page.addProperty(u, 1);
		}
		byCat.add("Tempoross", page);
		clog.add("by_cat", byCat);
		List<ChronicleApiClient.GrindRow> rows = rows(clog, ledger());
		for (ChronicleApiClient.GrindRow g : rows)
		{
			assertFalse("an owned item is still being chased: " + g.item,
				g.boss.equals(POOL));
		}
	}

	// The whole-log set does the same, under the log's own spelling of the tome.
	@Test
	public void obtainedIsReadOffTheWholeLogSet()
	{
		List<ChronicleApiClient.GrindRow> rows = rows(
			clog("Fish barrel", "Tackle box", "Big harpoonfish", "Tome of Water (empty)",
				"Dragon harpoon"),
			ledger());
		for (ChronicleApiClient.GrindRow g : rows)
		{
			assertFalse("an owned item is still being chased: " + g.item,
				g.boss.equals(POOL));
		}
	}

	// The book itself: all eight sit under the pool, none under the boss.
	@Test
	public void theBookKeysTheBlockToThePool()
	{
		JsonObject book;
		try (java.io.InputStreamReader r = new java.io.InputStreamReader(
			GrindBook.class.getResourceAsStream("/chronicle/osrs_clog_rates.json"),
			java.nio.charset.StandardCharsets.UTF_8))
		{
			book = new Gson().fromJson(r, JsonObject.class).getAsJsonObject("drops");
		}
		catch (java.io.IOException e)
		{
			throw new AssertionError(e);
		}
		assertFalse("the block is still keyed to the boss", book.has("Tempoross"));
		assertTrue("the pool block is missing", book.has(POOL));
		JsonObject pool = book.getAsJsonObject(POOL);
		assertEquals(UNIQUES.length, pool.size());
		for (String u : UNIQUES)
		{
			assertTrue(u + " is off the block", pool.has(u));
		}
		assertEquals(4, pool.get("Spirit flakes").getAsInt());
		assertEquals(20, pool.get("Casket").getAsInt());
		assertEquals(54, pool.get("Soaked page").getAsInt());
		assertEquals(8000, pool.get("Dragon harpoon").getAsInt());
	}

	// And the owner's own record, when this machine has one: the rows sit on a card
	// that exists, and none of them is priced on the 46.
	@Test
	public void theRealJournalPricesThePoolCard()
	{
		File dir = new File(System.getProperty("user.home"), ".runelite/chronicle");
		if (!dir.isDirectory())
		{
			return;
		}
		File[] found = dir.listFiles((d, n) -> n.endsWith(".json"));
		if (found == null || found.length == 0)
		{
			return;
		}
		File newest = found[0];
		for (File f : found)
		{
			if (f.lastModified() > newest.lastModified())
			{
				newest = f;
			}
		}
		String rsn = newest.getName().substring(0, newest.getName().length() - 5);
		ItemManager im = mock(ItemManager.class);
		LocalStore store = new LocalStore(im, new Gson());
		store.load(dir, rsn);
		List<LocalStore.SourceRow> sources = store.dropSources();
		for (ChronicleApiClient.GrindRow g : rows(store.clogSnapshot(), sources))
		{
			boolean carded = false;
			for (LocalStore.SourceRow sr : sources)
			{
				carded |= sr.name.equalsIgnoreCase(g.boss);
			}
			if (g.boss.equals(POOL))
			{
				assertTrue("the pool row has no card to sit on", carded);
				assertEquals("priced on subdues", 114, g.kc);
			}
		}
	}
}
