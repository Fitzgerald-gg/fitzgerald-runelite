/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Dryness for the skilling pets whose attempts the journal can honestly count.
 * The unit is the activity's own: a log received, an ore, a completed lap, a
 * successful theft, an essence, a patch. Rolls are priced 1 / (base - level * 25)
 * off the level held now, which is not the level each attempt was made at.
 */
public class SkillingPetChaseTest
{
	private static final List<String> PETS = Arrays.asList(
		"Beaver", "Rock golem", "Rocky", "Giant Squirrel", "Baby chinchompa",
		"Herbi", "Rift guardian", "Tangleroot", "Heron", "Soup", "Smolcano");

	private final Map<String, Long> counters = new HashMap<>();
	private final Map<String, long[]> skills = new HashMap<>();
	private final JsonObject clog = new JsonObject();
	private final List<LocalStore.SourceRow> ledger = new ArrayList<>();

	private void count(String key, long n)
	{
		counters.put(key, n);
	}

	private void level(String skill, long lvl)
	{
		skills.put(skill, new long[]{lvl, 0});
	}

	private void kc(String boss, long n)
	{
		JsonObject kcs = clog.has("kcs") ? clog.getAsJsonObject("kcs") : new JsonObject();
		kcs.addProperty(boss, n);
		clog.add("kcs", kcs);
	}

	// a drop ledger source and its count, which is a kill count like any other
	private void ledger(String source, int n)
	{
		ledger.add(new LocalStore.SourceRow(source, n, n, 0L, null, 0L, 0L));
	}

	private GrindBook.PetChase chase(String pet)
	{
		Map<String, GrindBook.PetChase> out = new GrindBook(new Gson())
			.petChases(clog, ledger, counters, skills, new JsonObject(), PETS);
		return out.get(pet.toLowerCase(java.util.Locale.ROOT));
	}

	// The Beaver fixture, used by several rows below: 22,501 logs off three trees.
	private void woodcutting()
	{
		level("woodcutting", 92);
		count("yewLogsChopped", 14_204);
		count("willowLogsChopped", 5_185);
		count("magicLogsChopped", 3_112);
	}

	// Three species, three bases, one roll each. 1 - (1-1/80758)^8204 * (1-1/96373)^1112.
	@Test
	public void eachChinchompaRollsAgainstItsOwnSpecies()
	{
		level("hunter", 80);
		count("blackChinchompasTrapped", 8_204);
		count("redChinchompasTrapped", 1_112);
		GrindBook.PetChase c = chase("Baby chinchompa");
		assertEquals(10.7, c.percentileDry, 0.05);
		assertEquals(9_316, c.kc);
		assertEquals(2, c.sources.size());
		// heaviest first, and the level term has come off the base
		assertEquals("Black chinchompas", c.sources.get(0).boss);
		assertEquals(82_758 - 25 * 80, c.sources.get(0).rate);
		assertEquals(98_373 - 25 * 80, c.sources.get(1).rate);
	}

	// Herbi is flat 1/6,500. The wiki states no Hunter-level term, so none is applied
	// and the same harvests read the same at 1 as at 99.
	@Test
	public void herbiIsFlatAndTheHunterLevelDoesNotMoveIt()
	{
		count("herbiboarsHarvested", 812);
		level("hunter", 1);
		GrindBook.PetChase low = chase("Herbi");
		level("hunter", 99);
		GrindBook.PetChase high = chase("Herbi");
		assertEquals(11.7, low.percentileDry, 0.05);
		assertEquals(low.percentileDry, high.percentileDry, 0.0001);
		assertEquals(6_500, low.sources.get(0).rate);
		// nothing was read off a level, so the page has no caveat to print for it
		assertEquals(0, low.level);
	}

	// A skilling pet with no level to read gets no figure at all: the rate is a
	// function of the level, and a guessed level is a guessed percentage.
	@Test
	public void aLevelScaledPetWithoutALevelSaysNothing()
	{
		count("yewLogsChopped", 14_204);
		count("herbiboarsHarvested", 812);
		assertNull(chase("Beaver"));
		assertNotNull(chase("Herbi"));
	}

	// Bloodwood is rolled per Chop swing, not per log received, and hollow trees and
	// sulliusceps hand over no log at all. None of them may reach the denominator.
	// logsChopped is the untyped floor over the three rows and would double them.
	@Test
	public void theTreesThatCannotBeCountedAreNotCounted()
	{
		woodcutting();
		GrindBook.PetChase bare = chase("Beaver");
		count("bloodwoodLogsChopped", 4_002);
		count("logsChopped", 22_501);
		count("barkChopped", 900);
		GrindBook.PetChase with = chase("Beaver");
		assertEquals(15.0, bare.percentileDry, 0.05);
		assertEquals(bare.percentileDry, with.percentileDry, 0.0001);
		assertEquals(22_501, with.kc);
		assertEquals(3, with.sources.size());
	}

	// The wiki's own cannot-drop list, plus the two counters that measure items
	// handed over rather than rocks broken. 6,204 coal, 2,113 iron, 1,204 amethyst
	// and 402 runite is the whole of it.
	@Test
	public void theRocksThatDropNothingAreNotCounted()
	{
		level("mining", 85);
		count("coalMined", 6_204);
		count("ironOreMined", 2_113);
		count("amethystMined", 1_204);
		count("runiteOreMined", 402);
		GrindBook.PetChase bare = chase("Rock golem");
		count("denseEssenceBlockMined", 12_000);
		count("daeyaltShardMined", 9_000);
		count("teSaltMined", 4_000);
		count("pureEssenceMined", 30_000);
		count("barroniteShardsMined", 8_000);
		count("volcanicAshMined", 5_000);
		count("rocksMined", 9_923);
		GrindBook.PetChase with = chase("Rock golem");
		assertEquals(6.0, bare.percentileDry, 0.05);
		assertEquals(bare.percentileDry, with.percentileDry, 0.0001);
		assertEquals(9_923, with.kc);
	}

	// A failed pickpocket rolls nothing, and its key is spelled like a successful one.
	// The dearer marks keep their own rate rather than falling into the common tier.
	@Test
	public void failedPickpocketsRollNothingAndTheDearMarksKeepTheirRate()
	{
		level("thieving", 78);
		count("masterFarmerPickpockets", 9_204);
		count("masterFarmerFailedPickpockets", 4_112);
		count("elfPickpockets", 2_100);
		count("gemStallsThieved", 3_012);
		count("pickPockets", 11_304);
		count("stallsThieved", 3_012);
		GrindBook.PetChase c = chase("Rocky");
		assertEquals(13.5, c.percentileDry, 0.05);
		assertEquals(14_316, c.kc);
		assertEquals(3, c.sources.size());
		long elf = 0;
		for (GrindBook.PetSource s : c.sources)
		{
			if (s.boss.equals("Elves"))
			{
				elf = s.rate;
			}
		}
		assertEquals(99_175 - 25 * 78, elf);
	}

	// The roll is per completed lap. agilityObstacles counts obstacles, the two
	// chat-driven aggregates count the same laps a second time, the Colossal Wyrm
	// courses have no published rate, and a Sepulchre run bumps one counter per floor
	// cleared where it rolls once.
	@Test
	public void theSquirrelRollsPerLapNotPerObstacle()
	{
		level("agility", 88);
		count("seersLaps", 2_204);
		count("ardougneLaps", 1_113);
		count("canifisLaps", 402);
		GrindBook.PetChase bare = chase("Giant Squirrel");
		count("agilityObstacles", 41_002);
		count("rooftopAgilityLaps", 3_719);
		count("normalAgilityLaps", 900);
		count("colossalWyrmBasicLaps", 2_000);
		count("sepulchreFloor3Cleared", 400);
		GrindBook.PetChase with = chase("Giant Squirrel");
		assertEquals(10.7, bare.percentileDry, 0.05);
		assertEquals(bare.percentileDry, with.percentileDry, 0.0001);
		assertEquals(3_719, with.kc);
	}

	// Blood and soul runes are one to an essence and have altars of their own, so
	// they come out of the essence total and are priced separately. The remainder is
	// every other altar. 16,008 + 12,004 + 2,100 is the 30,112 that were crafted.
	@Test
	public void bloodAndSoulComeOutOfTheEssenceTotal()
	{
		level("runecraft", 91);
		count("essenceCrafted", 30_112);
		count("bloodRunecrafted", 12_004);
		count("soulRunecrafted", 2_100);
		count("natureRunecrafted", 88_000);   // runes, not essence: not an attempt
		GrindBook.PetChase c = chase("Rift guardian");
		assertEquals(2.6, c.percentileDry, 0.05);
		assertEquals(30_112, c.kc);
		assertEquals(3, c.sources.size());
		Map<String, long[]> by = new HashMap<>();
		for (GrindBook.PetSource s : c.sources)
		{
			by.put(s.boss, new long[]{s.kc, s.rate});
		}
		assertEquals(16_008, by.get("Every other altar")[0]);
		assertEquals(1_795_758 - 25 * 91, by.get("Every other altar")[1]);
		assertEquals(12_004, by.get("Blood altar")[0]);
		assertEquals(804_984 - 25 * 91, by.get("Blood altar")[1]);
	}

	// Hespori is in both rate books. The boss table's 5,375 is this same formula
	// frozen at Farming 65, so the two agree there by construction; above it the
	// live figure moves and the frozen one cannot. Either way Hespori is one source,
	// never two.
	@Test
	public void hesporiIsOneSourceAndAgreesWithTheBossTable()
	{
		kc("Hespori", 61);
		level("farming", 65);
		GrindBook.PetChase at65 = chase("Tangleroot");
		assertEquals(1, at65.sources.size());
		assertEquals("Hespori", at65.sources.get(0).boss);
		assertEquals(bundledHesporiRate(), at65.sources.get(0).rate);
		assertEquals(61, at65.kc);

		level("farming", 99);
		GrindBook.PetChase at99 = chase("Tangleroot");
		assertEquals(4_525, at99.sources.get(0).rate);
		assertTrue(at99.percentileDry > at65.percentileDry);
	}

	// The patches join the same chase, and Hespori still appears once.
	@Test
	public void patchesAndHesporiAreOneChase()
	{
		kc("Hespori", 61);
		level("farming", 84);
		count("ranarrPlanted", 1_204);
		count("guamPlanted", 402);
		count("torstolPlanted", 120);
		count("oakPlanted", 88);
		count("yewPlanted", 44);
		count("hesporiPlanted", 61);        // the same event as the kill count
		count("ranarrHarvested", 9_400);    // items out of a patch, not patches
		count("farmingActions", 20_000);    // the untyped floor
		GrindBook.PetChase c = chase("Tangleroot");
		assertEquals(3.9, c.percentileDry, 0.05);
		assertEquals(1_919, c.kc);
		int hespori = 0;
		for (GrindBook.PetSource s : c.sources)
		{
			hespori += s.boss.equals("Hespori") ? 1 : 0;
		}
		assertEquals(1, hespori);
	}

	// The odds are read at the level held now: the denominator is base - level * 25,
	// it stops moving at 99, and the same work reads drier at a higher level.
	@Test
	public void theOddsAreReadAtTheLevelHeldNow()
	{
		woodcutting();
		GrindBook.PetChase at92 = chase("Beaver");
		assertEquals(145_013 - 25 * 92, at92.sources.get(0).rate);
		assertEquals(92, at92.level);

		level("woodcutting", 99);
		GrindBook.PetChase at99 = chase("Beaver");
		assertEquals(145_013 - 25 * 99, at99.sources.get(0).rate);

		// past 99 the level term stops helping
		level("woodcutting", 126);
		assertEquals(at99.sources.get(0).rate, chase("Beaver").sources.get(0).rate);
		assertEquals(99, chase("Beaver").level);

		// and this is the approximation the page warns about: the very same 22,501
		// logs read 14.7% off a career spent at level 1 and 15.0% off this one,
		// because every one of them is priced at the level held now.
		level("woodcutting", 1);
		assertEquals(14.7, chase("Beaver").percentileDry, 0.05);
		assertTrue(at99.percentileDry > chase("Beaver").percentileDry);
	}

	// One composed line: the craft and its total, not a list of the twenty tree types
	// underneath it. The sources are still all there for the hover.
	@Test
	public void theActivityCarriesTheLineNotItsSources()
	{
		woodcutting();
		GrindBook.PetChase c = chase("Beaver");
		assertEquals("Woodcutting", c.activity);
		assertEquals("logs", c.unit);
		assertEquals(3, c.sources.size());
		assertEquals("Yew trees", c.sources.get(0).boss);
	}

	// A pet the log already holds is finished business, however much work stands
	// behind it, and a boss pet is untouched by any of this.
	@Test
	public void anOwnedPetHasNoChaseAndTheBossPetsAreUnchanged()
	{
		woodcutting();
		kc("Zalcano", 2_023);
		assertNotNull(chase("Beaver"));
		assertEquals(59.3, chase("Smolcano").percentileDry, 0.05);
		assertNull(chase("Smolcano").activity);

		JsonObject items = new JsonObject();
		items.addProperty("beaver", 1);
		clog.add("clog_items", items);
		assertNull(chase("Beaver"));
		assertNotNull(chase("Smolcano"));
	}

	// A counter nothing has touched is not a chase: "0 attempts, 0% dry" is noise.
	@Test
	public void noAttemptsNoChase()
	{
		level("woodcutting", 92);
		count("yewLogsChopped", 0);
		assertFalse(new GrindBook(new Gson())
			.petChases(clog, new ArrayList<>(), counters, skills, new JsonObject(), PETS)
			.containsKey("beaver"));
	}

	// The heron is rolled per catch, and only on a catch the counter can name. Aerial
	// fishing, the leaping fish, infernal eels and leechfin mint no typed key at all;
	// harpoonfish mints one and cannot give the pet; fishCaught is the untyped floor
	// over the rows that can, and would double every one of them.
	@Test
	public void theHeronCountsTheCatchesItCanNameAndNoneItCannot()
	{
		level("fishing", 92);
		count("sharkCaught", 40_000);
		count("anglerfishCaught", 12_000);
		count("minnowCaught", 30_000);
		GrindBook.PetChase bare = chase("Heron");
		count("harpoonFishCaught", 9_000);      // cannot give the heron, and says so
		count("fishCaught", 82_000);            // the untyped floor over the three
		count("leapingTroutCaught", 4_100);     // no rate this journal can ask for
		count("seaTurtleCaught", 500);          // a Trawler reward, not a catch here
		count("mantaRayCaught", 400);
		GrindBook.PetChase with = chase("Heron");
		assertEquals(49.8, bare.percentileDry, 0.05);
		assertEquals(bare.percentileDry, with.percentileDry, 0.0001);
		assertEquals(82_000, with.kc);
		assertEquals(3, with.sources.size());
		assertEquals("Fishing", with.activity);
		assertEquals("catches", with.unit);
		// the level term has come off each base, heaviest source first
		assertEquals("Sharks", with.sources.get(0).boss);
		assertEquals(82_243 - 25 * 92, with.sources.get(0).rate);
		assertEquals("Minnows", with.sources.get(1).boss);
		assertEquals(977_778 - 25 * 92, with.sources.get(1).rate);
	}

	// The Trawler is the one fishing row the level does not touch, and the wiki calls
	// it the one exception. It joins the same chase as the fish that do scale.
	@Test
	public void theTrawlerIsTheOneFishingRowTheLevelDoesNotTouch()
	{
		ledger("Fishing Trawler", 410);
		level("fishing", 1);
		GrindBook.PetChase low = chase("Heron");
		level("fishing", 99);
		GrindBook.PetChase high = chase("Heron");
		assertEquals(7.9, low.percentileDry, 0.05);
		assertEquals(low.percentileDry, high.percentileDry, 0.0001);
		assertEquals(5_000, low.sources.get(0).rate);
		assertEquals(5_000, high.sources.get(0).rate);
		assertEquals(410, high.kc);

		// and the fish beside it do move with the level, in the same chase
		level("fishing", 92);
		count("sharkCaught", 40_000);
		count("anglerfishCaught", 12_000);
		count("minnowCaught", 30_000);
		GrindBook.PetChase both = chase("Heron");
		assertEquals(53.7, both.percentileDry, 0.05);
		assertEquals(4, both.sources.size());
		assertEquals(82_410, both.kc);
	}

	// Soup is flat: no Sailing level enters any of it. Salvage is counted per wreck,
	// because the rate differs by wreck; sorting is one rate however the salvage was
	// pulled, so the total sorted is the row and the per-wreck sorted keys would
	// double it. salvagePulled is the untyped floor, and a Barracuda trial is priced
	// anywhere from 1/16,000 to 1/3,000 by a trial and a fish the counter never names.
	@Test
	public void soupCountsTheSalvageItCanTierAndNothingItCannot()
	{
		count("smallSalvagePulled", 4_000);
		count("opulentSalvagePulled", 1_200);
		count("salvageSorted", 3_000);
		count("portTasksCompleted", 800);
		GrindBook.PetChase bare = chase("Soup");
		count("salvagePulled", 10_000);         // the untyped floor over the wrecks
		count("opulentSalvageSorted", 900);     // already inside salvageSorted
		count("barracudaTrialsCompleted", 220); // no rate without the trial and fish
		level("sailing", 99);
		GrindBook.PetChase with = chase("Soup");
		assertEquals(13.9, bare.percentileDry, 0.05);
		assertEquals(bare.percentileDry, with.percentileDry, 0.0001);
		assertEquals(9_000, with.kc);
		assertEquals(4, with.sources.size());
		// flat: the bases are printed as the wiki prints them, no level term taken off
		Map<String, long[]> by = new HashMap<>();
		for (GrindBook.PetSource s : with.sources)
		{
			by.put(s.boss, new long[]{s.kc, s.rate});
		}
		assertEquals(800_000, by.get("Small shipwreck")[1]);
		assertEquals(160_000, by.get("Merchant shipwreck")[1]);
		assertEquals(800_000, by.get("Salvage sorting")[1]);
		assertEquals(6_000, by.get("Port tasks")[1]);
		assertEquals(0, with.level);
	}

	// Hespori -> Tangleroot as the bundled boss rate book prints it.
	private static long bundledHesporiRate() throws RuntimeException
	{
		try (InputStream in = GrindBook.class
			.getResourceAsStream("/chronicle/osrs_clog_rates.json"))
		{
			JsonObject root = new Gson().fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			return root.getAsJsonObject("drops").getAsJsonObject("Hespori")
				.get("Tangleroot").getAsLong();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
}
