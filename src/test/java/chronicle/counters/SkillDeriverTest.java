/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle.counters;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Pins the LOCAL resolver against the same answers the server's
 * {@code _skill_derive_action} gives — the two derive from identical tuples
 * and must agree, or the floor-merge would see phantom drift.
 */
public class SkillDeriverTest
{
	private final Map<Integer, String> names = new HashMap<>();
	private final SkillDeriver d;

	public SkillDeriverTest()
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt()))
			.thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenAnswer(inv ->
		{
			ItemComposition c = Mockito.mock(ItemComposition.class);
			Mockito.when(c.getName()).thenReturn(names.getOrDefault(
				(Integer) inv.getArgument(0), ""));
			return c;
		});
		d = new SkillDeriver(im, new StatStore(), new Gson());
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
}
