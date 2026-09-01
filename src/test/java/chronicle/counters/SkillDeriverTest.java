/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the LOCAL resolver against the same answers the server's
 * {@code _skill_derive_action} gives — the two derive from identical tuples
 * and must agree, or the floor-merge would see phantom drift.
 */
public class SkillDeriverTest
{
	private final Map<Integer, String> names = new HashMap<>();
	// The GE as it stands at the moment of each derive; a test moves it to prove
	// the value is banked at the gather rather than looked up afterwards.
	private final Map<Integer, Integer> prices = new HashMap<>();
	// Stands in for the journal's gathered-item ledger.
	private final Set<Integer> gathered = new HashSet<>();
	private final SkillDeriver d;

	public SkillDeriverTest()
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt()))
			.thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt()))
			.thenAnswer(inv -> prices.getOrDefault((Integer) inv.getArgument(0), 0));
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenAnswer(inv ->
		{
			ItemComposition c = Mockito.mock(ItemComposition.class);
			Mockito.when(c.getName()).thenReturn(names.getOrDefault(
				(Integer) inv.getArgument(0), ""));
			return c;
		});
		d = new SkillDeriver(im, new StatStore(), new Gson());
		d.setGatheredLedger(new GatheredLedger()
		{
			@Override
			public void noteGathered(int itemId)
			{
				gathered.add(itemId);
			}

			@Override
			public boolean wasGathered(int itemId)
			{
				return gathered.contains(itemId);
			}
		});
	}

	private Map<String, Integer> derive(String tuple)
	{
		List<Map.Entry<String, Integer>> pairs = d.derive(tuple);
		if (pairs == null)
		{
			return null;
		}
		Map<String, Integer> out = new HashMap<>();
		for (Map.Entry<String, Integer> p : pairs)
		{
			out.merge(p.getKey(), p.getValue(), Integer::sum);
		}
		return out;
	}

	@Test
	public void arrowShaftsTypeByConsumedLog()
	{
		names.put(52, "Arrow shaft");
		names.put(1517, "Maple logs");
		Map<String, Integer> got = derive("FLETCHING|20||52|60||1517");
		assertEquals((Integer) 1, got.get("logsFletched"));
		assertEquals((Integer) 1, got.get("mapleLogsFletched"));
		assertEquals((Integer) 60, got.get("arrowShaftsFletched"));
	}

	@Test
	public void headlessArrows()
	{
		names.put(53, "Headless arrow");
		assertEquals((Integer) 15, derive("FLETCHING|15||53|15||").get("headlessArrowsFletched"));
	}

	@Test
	public void gemsCutIncludingFailures()
	{
		names.put(1623, "Sapphire");
		assertEquals((Integer) 1, derive("CRAFTING|50||1623|1||").get("gemsCut"));
		names.put(1633, "Crushed gem");
		assertEquals((Integer) 1, derive("CRAFTING|1||1633|1||").get("gemsCut"));
	}

	@Test
	public void prayerVerbsSplitByRatio()
	{
		names.put(90, "Abyssal ashes");
		assertEquals((Integer) 1, derive("PRAYER|85|||||90").get("abyssalAshesScattered"));
		assertEquals((Integer) 1, derive("PRAYER|255|||||90").get("abyssalAshesSacrificed"));
		assertEquals((Integer) 2, derive("PRAYER|510|||||90").get("abyssalAshesSacrificed"));
		names.put(536, "Dragon bones");
		assertEquals((Integer) 1, derive("PRAYER|72|||||536").get("dragonBonesBuried"));
		assertEquals((Integer) 1, derive("PRAYER|216|||||536").get("dragonBonesSacrificed"));
		assertEquals((Integer) 1, derive("PRAYER|252|||||536").get("bonesOffered"));
	}

	@Test
	public void ensouledReanimations()
	{
		assertEquals((Integer) 1, derive("PRAYER|1300|||||").get("abyssalHeadsReanimated"));
		names.put(13508, "Ensouled abyssal head");
		assertEquals((Integer) 1, derive("PRAYER|1300|||||13508").get("abyssalHeadsReanimated"));
	}

	@Test
	public void agilityLaps()
	{
		Map<String, Integer> got = derive("AGILITY|625|||||");
		assertEquals((Integer) 1, got.get("agilityObstacles"));
		assertEquals((Integer) 1, got.get("ardougneLaps"));
		assertNull(derive("AGILITY|39|||||").get("ardougneLaps"));
	}

	@Test
	public void hunterSpeciesAndBirdhouses()
	{
		names.put(10033, "Chinchompa");
		assertEquals((Integer) 1, derive("HUNTER|198||10033|1||").get("greyChinchompasTrapped"));
		names.put(53, "Feather");
		assertEquals((Integer) 1, derive("HUNTER|612||53|30||").get("yewBirdhousesEmptied"));
		assertEquals((Integer) 1, derive("HUNTER|1950|||||").get("herbiboarsHarvested"));
	}

	@Test
	public void thievingClassifier()
	{
		assertEquals((Integer) 1, derive("THIEVING|43||||Master Farmer|").get("masterFarmerPickpockets"));
		assertEquals((Integer) 1, derive("THIEVING|16||||Tea stall|").get("teaStallsThieved"));
		assertEquals((Integer) 1, derive("THIEVING|675|||||").get("pyramidPlunderUrns"));
	}

	@Test
	public void smithingPerMetalAndCannonballs()
	{
		names.put(2353, "Steel bar");
		assertEquals((Integer) 1, derive("SMITHING|17||2353|1||").get("steelBarsSmelted"));
		names.put(2, "Cannonball");
		assertEquals((Integer) 4, derive("SMITHING|25||2|4||").get("cannonballsSmithed"));
	}

	@Test
	public void gatheringTypedByItem()
	{
		names.put(1515, "Yew logs");
		Map<String, Integer> got = derive("WOODCUTTING|175||1515|1||");
		assertEquals((Integer) 1, got.get("logsChopped"));
		assertEquals((Integer) 1, got.get("yewLogsChopped"));
		names.put(21622, "Volcanic ash");
		assertEquals((Integer) 6, derive("MINING|10||21622|6||").get("volcanicAshMined"));
		names.put(1625, "Uncut opal");
		assertEquals((Integer) 1, derive("MINING|65||1625|1||").get("gemRockMined"));
	}

	@Test
	public void salvageRidesTheItemNotTheXp()
	{
		names.put(32847, "Small salvage");
		Map<String, Integer> pulled = derive("SAILING|10||32847|1||");
		assertEquals((Integer) 1, pulled.get("salvagePulled"));
		assertEquals((Integer) 1, pulled.get("smallSalvagePulled"));
		// Opulent salvage is 200 xp flat, 205 under a keg of horizons lure. The
		// number moves, the item does not.
		names.put(32861, "Opulent salvage");
		assertEquals((Integer) 1, derive("SAILING|205||32861|1||").get("opulentSalvagePulled"));
		// Sorting: loot comes in, the salvage goes out, and the salvage names the row.
		names.put(1625, "Uncut opal");
		Map<String, Integer> sorted = derive("SAILING|95||1625|1||32861");
		assertEquals((Integer) 1, sorted.get("salvageSorted"));
		assertEquals((Integer) 1, sorted.get("opulentSalvageSorted"));
		assertNull(sorted.get("salvagePulled"));
	}

	@Test
	public void barracudaTrialsRideTheBareCompletionLump()
	{
		Map<String, Integer> marlin = derive("SAILING|1250|||||");
		assertEquals((Integer) 1, marlin.get("barracudaTrialsCompleted"));
		assertEquals((Integer) 1, marlin.get("temporTantrumTrialsCompleted"));
		assertEquals((Integer) 1, derive("SAILING|6200|||||").get("jubblyJiveTrialsCompleted"));
		assertEquals((Integer) 1, derive("SAILING|16050|||||").get("gwenithGlideTrialsCompleted"));
		// 150 is a lost teak crate and a medium lost casket as often as it is a
		// Tempor Tantrum, so it is off the ladder.
		assertNull(derive("SAILING|150|||||"));
		// A courier delivery pays 385 too. The bag every port task hands over is
		// what tells the two apart.
		names.put(32950, "Medium port coin bag");
		Map<String, Integer> port = derive("SAILING|385||32950|1||");
		assertEquals((Integer) 1, port.get("portTasksCompleted"));
		assertNull(port.get("barracudaTrialsCompleted"));
		assertEquals((Integer) 1, derive("SAILING|385|||||").get("temporTantrumTrialsCompleted"));
		// Trawling, sail trimming and cannon fire all drop bare xp that is nobody's
		// completion.
		assertNull(derive("SAILING|9|||||"));
	}

	@Test
	public void chatChannelResiduals()
	{
		StatStore store = new StatStore();
		ItemManager im = Mockito.mock(ItemManager.class);
		SkillDeriver cd = new SkillDeriver(im, store, new Gson());
		cd.applyChat("You fail to pick the Master Farmer's pocket.");
		assertEquals(1, store.getStat("failedPickPockets"));
		assertEquals(1, store.getStat("masterFarmerFailedPickpockets"));
		cd.applyChat("You accidentally burn the shark.");
		assertEquals(1, store.getStat("foodBurned"));
		cd.applyChat("You plant 3 potato seeds.");
		assertEquals(1, store.getStat("seedsPlanted"));
		cd.applyChat("Rooftop lap count: 42.");
		assertEquals(1, store.getStat("rooftopAgilityLaps"));
		cd.applyChat("Your Ardougne lap count is: 100.");
		assertEquals(1, store.getStat("normalAgilityLaps"));
	}

	@Test
	public void absorbsAndFloors()
	{
		// lamps and gauntlet internals derive nothing
		assertNull(derive("RUNECRAFT|500|||||2528"));
		assertNull(derive("WOODCUTTING|10||23838|1||"));
		// runecraft veneration (non-rune gain) derives nothing
		names.put(13446, "Dark essence block");
		assertNull(derive("RUNECRAFT|2||13446|1||"));
		// firemaking with a missed consumed falls to the bare-xp ladder
		assertEquals((Integer) 1, derive("FIREMAKING|303|||||").get("magicLogsBurned"));
	}

	// ── The resource pair ─────────────────────────────────────────────────
	// These have no server answer to agree with. The server could only ever
	// multiply a lifetime count by today's market; the value below is minted
	// here, at the price that stood when the resource came out of the world.

	@Test
	public void aGatheredResourceIsValuedAtTheMomentItIsGathered()
	{
		names.put(1515, "Yew logs");
		prices.put(1515, 240);
		Map<String, Integer> got = derive("WOODCUTTING|175||1515|1||");
		assertEquals((Integer) 1, got.get("logsChopped"));
		assertEquals((Integer) 1, got.get("yewLogsChopped"));
		assertEquals((Integer) 240, got.get("resourcesGatheredValue"));

		// The market moves; the next log is worth what the market now says, and
		// the 240 already banked is not revisited. That is the whole reason this
		// is priced at the gather instead of multiplied out at read time — a
		// crash would otherwise erase work that was worth something when it was done.
		prices.put(1515, 5);
		assertEquals((Integer) 5,
			derive("WOODCUTTING|175||1515|1||").get("resourcesGatheredValue"));
	}

	@Test
	public void theWholeCatchIsValuedNotTheAction()
	{
		names.put(3150, "Karambwanji");
		prices.put(3150, 30);
		// Some spots hand over several at once; the value follows the quantity the
		// typed counter already trusts, not the number of clicks.
		Map<String, Integer> got = derive("FISHING|5||3150|4||");
		assertEquals((Integer) 4, got.get("karambwanjiCaught"));
		assertEquals((Integer) 120, got.get("resourcesGatheredValue"));
	}

	@Test
	public void anUnpricedGatherAddsNothingButIsStillRemembered()
	{
		names.put(434, "Clay");
		Map<String, Integer> got = derive("MINING|5||434|1||");
		assertEquals((Integer) 1, got.get("rocksMined"));
		assertNull(got.get("resourcesGatheredValue"));
		// The ledger records provenance, not worth: an item the GE cannot price is
		// still one this account pulled out of the ground.
		assertTrue(gathered.contains(434));
	}

	@Test
	public void cookingIsNotAGather()
	{
		names.put(385, "Shark");
		prices.put(385, 800);
		Map<String, Integer> got = derive("COOKING|210||385|1||");
		assertEquals((Integer) 1, got.get("foodCooked"));
		assertEquals((Integer) 1, got.get("sharkCooked"));
		// The fish was valued when it was caught. Valuing it again on the range
		// would count one catch twice, and would make a cooked shark binned after
		// a burn read as a resource dropped where it fell.
		assertNull(got.get("resourcesGatheredValue"));
		assertFalse(gathered.contains(385));
	}

	@Test
	public void aGatherTheResolverCannotNameIsNotValued()
	{
		// The xp ladder still names the tree, but the ARRIVAL is unnamed: nothing
		// says this nest is the resource rather than something else that happened
		// to land in the pack on the same tick, so it is neither valued nor
		// remembered as gathered.
		names.put(11941, "Bird nest");
		prices.put(11941, 4000);
		Map<String, Integer> got = derive("WOODCUTTING|175||11941|1||");
		assertEquals((Integer) 1, got.get("logsChopped"));
		assertNull(got.get("resourcesGatheredValue"));
		assertFalse(gathered.contains(11941));
	}
}
