/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pet dryness against the bundled rate book: the odds a pet would have landed by
 * the kill count the journal holds. A pet with several sources is one chase fed
 * from all of them, never the flattering half of the pair.
 */
public class GrindBookTest
{
	private static final List<String> PETS = Arrays.asList(
		"Callisto cub", "Smolcano", "Pet chaos elemental", "Bloodhound", "Baby mole");

	private static JsonObject clog(String... kcPairs)
	{
		JsonObject clog = new JsonObject();
		JsonObject kcs = new JsonObject();
		for (int i = 0; i < kcPairs.length; i += 2)
		{
			kcs.addProperty(kcPairs[i], Long.parseLong(kcPairs[i + 1]));
		}
		clog.add("kcs", kcs);
		return clog;
	}

	private static Map<String, GrindBook.PetChase> chases(JsonObject clog)
	{
		return chases(clog, new ArrayList<>());
	}

	private static Map<String, GrindBook.PetChase> chases(JsonObject clog,
		List<LocalStore.SourceRow> sources)
	{
		return new GrindBook(new Gson()).petChases(clog, sources,
			new java.util.HashMap<>(), new java.util.HashMap<>(), new JsonObject(), PETS);
	}

	// 2,023 Zalcano against Smolcano's 1/2,250: 1 - (1 - 1/2250)^2023 = 59.3%.
	// Not 2023/2250 = 89.9%, which is the mistake this row exists to avoid.
	@Test
	public void singleSourceCompoundsPerKill()
	{
		GrindBook.PetChase c = chases(clog("Zalcano", "2023")).get("smolcano");
		assertEquals(59.3, c.percentileDry, 0.05);
		assertEquals(2023, c.kc);
		assertEquals(1, c.sources.size());
		assertEquals("Zalcano", c.sources.get(0).boss);
		assertEquals(2250, c.sources.get(0).rate);
	}

	// The drop ledger's own kill count feeds the same chase when the log has no
	// page for the boss.
	@Test
	public void ledgerKillsCount()
	{
		List<LocalStore.SourceRow> ledger = new ArrayList<>();
		ledger.add(new LocalStore.SourceRow("Zalcano", 2023, 2000, 1L, null, 0, 0));
		GrindBook.PetChase c = chases(new JsonObject(), ledger).get("smolcano");
		assertEquals(59.3, c.percentileDry, 0.05);
	}

	// Callisto cub drops at Callisto (1/1,500) and Artio (1/2,800). 1,500 kills at
	// the first alone is 63.2%, 900 at the second alone is 27.5%; together the
	// misses multiply and the chase is 73.3%. Picking one source would report a
	// player far drier than they are.
	@Test
	public void severalSourcesMultiplyTheirMisses()
	{
		GrindBook.PetChase c = chases(clog("Callisto", "1500", "Artio", "900"))
			.get("callisto cub");
		assertEquals(73.3, c.percentileDry, 0.05);
		assertTrue(c.percentileDry > 63.2);
		assertEquals(2400, c.kc);
		assertEquals(2, c.sources.size());
		// heaviest first, so a clipped column keeps the source that carried it
		assertEquals("Callisto", c.sources.get(0).boss);
		assertEquals(1500, c.sources.get(0).kc);
		assertEquals("Artio", c.sources.get(1).boss);
		assertEquals(900, c.sources.get(1).kc);
	}

	// The heaviest source leads, whichever way round the rate book holds them: the
	// column clips, and what survives the clip has to be the one that carried it.
	@Test
	public void theHeaviestSourceLeads()
	{
		GrindBook.PetChase c = chases(clog("Callisto", "900", "Artio", "1500"))
			.get("callisto cub");
		assertEquals(67.9, c.percentileDry, 0.05);
		assertEquals("Artio", c.sources.get(0).boss);
		assertEquals("Callisto", c.sources.get(1).boss);
	}

	// A source with no kills is not part of the chase and is not listed as one.
	@Test
	public void aSourceWithoutKillsIsLeftOut()
	{
		GrindBook.PetChase c = chases(clog("Callisto", "1500")).get("callisto cub");
		assertEquals(63.2, c.percentileDry, 0.05);
		assertEquals(1, c.sources.size());
		assertEquals("Callisto", c.sources.get(0).boss);
	}

	// Nothing killed anywhere: no row at all. "0 kills, 0% dry" is noise.
	@Test
	public void noKillsNoChase()
	{
		Map<String, GrindBook.PetChase> out = chases(clog("Zulrah", "0"));
		assertTrue(out.isEmpty());
		assertNull(out.get("smolcano"));
	}

	// A pet the log holds is finished business, however many kills stand behind it.
	@Test
	public void anOwnedPetHasNoChase()
	{
		JsonObject clog = clog("Zalcano", "2023");
		JsonObject items = new JsonObject();
		items.addProperty("smolcano", 1);
		clog.add("clog_items", items);
		assertFalse(chases(clog).containsKey("smolcano"));

		// and the same when only the pet's own log page recorded it
		JsonObject byPage = clog("Zalcano", "2023");
		JsonObject page = new JsonObject();
		page.addProperty("Smolcano", 1);
		JsonObject cat = new JsonObject();
		cat.add("All Pets", page);
		byPage.add("by_cat", cat);
		assertFalse(chases(byPage).containsKey("smolcano"));
	}

	// Bloodhound has no rate in the book: no row, no placeholder, however much
	// clue-hunting the journal holds.
	@Test
	public void aPetWithoutARateHasNoChase()
	{
		Map<String, GrindBook.PetChase> out =
			chases(clog("Zalcano", "2023", "Master Treasure Trail", "1200"));
		assertFalse(out.containsKey("bloodhound"));
		assertTrue(out.containsKey("smolcano"));
	}

	// A corrupt or absurd kill count is held to a number a row can print, and the
	// odds never run past certainty.
	@Test
	public void absurdKillCountsAreHeld()
	{
		GrindBook.PetChase c = chases(clog("Zalcano", "999999999999")).get("smolcano");
		assertEquals(100.0, c.percentileDry, 0.0001);
		assertEquals(100_000_000L, c.kc);
		assertEquals(100_000_000L, c.sources.get(0).kc);
	}
}
