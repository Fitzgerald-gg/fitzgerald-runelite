/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the LOCAL slayer journey semantics: on-task loot opens/extends the
 * newest segment, a completion closes it and trues the kill count up with
 * {@code noLootKills}, the streak line floors the lifetime total, and the
 * one-shot cloud inheritance only adopts while the local spine is empty.
 */
public class LocalSlayerJourneyTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(100);
		ItemComposition comp = Mockito.mock(ItemComposition.class);
		Mockito.when(comp.getName()).thenReturn("Rune dagger");
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenReturn(comp);
		store = new LocalStore(im, new Gson());
		dir = Files.createTempDirectory("chronicle-test").toFile();
		store.load(dir, "Tester");
	}

	private void onTaskKill(String task, int initial, int itemId, int qty)
	{
		JsonObject data = new JsonObject();
		data.addProperty("source", task);
		data.addProperty("slayerTask", task);
		data.addProperty("slayerTaskInitial", initial);
		JsonArray items = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", itemId);
		it.addProperty("quantity", qty);
		items.add(it);
		data.add("items", items);
		store.record("LOOT", data, "Tester");
	}

	private void completion(String task, Integer exactKills, Integer streak)
	{
		JsonObject data = new JsonObject();
		data.addProperty("task", task);
		if (exactKills != null)
		{
			data.addProperty("killCount", exactKills);
		}
		if (streak != null)
		{
			data.addProperty("count", streak);
		}
		store.record("SLAYER", data, "Tester");
	}

	@Test
	public void lootOpensAndExtendsOneSegment()
	{
		onTaskKill("Dust devils", 120, 1, 1);
		onTaskKill("Dust devils", 120, 1, 2);
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(1, j.tasks.size());
		ChronicleApiClient.SlayerTask t = j.tasks.get(0);
		assertEquals("Dust devils", t.task);
		assertEquals(2, t.kills);
		assertEquals(120, t.assignment);
		assertTrue(t.inProgress);
		assertEquals(300, t.totalValue);   // 100gp × (1+2)
	}

	@Test
	public void completionClosesAndTruesUp()
	{
		onTaskKill("Nechryael", 150, 1, 1);
		onTaskKill("Nechryael", 150, 1, 1);
		completion("Nechryael", 150, 214);
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(1, j.tasks.size());
		ChronicleApiClient.SlayerTask t = j.tasks.get(0);
		assertFalse(t.inProgress);
		assertEquals(150, t.kills);          // trued up to the finished line
		assertEquals(148, t.noLootKills);    // 150 exact − 2 witnessed
		assertEquals(214, j.completedTasks); // streak line is authoritative
		// The next on-task kill opens a NEW segment (the old one is closed).
		onTaskKill("Nechryael", 130, 1, 1);
		j = store.slayerJourney();
		assertEquals(2, j.tasks.size());
		assertTrue(j.tasks.get(0).inProgress);   // newest first
		assertEquals(1, j.tasks.get(0).kills);
	}

	@Test
	public void completionWithoutStreakIncrements()
	{
		completion("Kalphite", 90, null);
		completion("Kalphite", 80, null);
		assertEquals(2, store.slayerJourney().completedTasks);
	}

	@Test
	public void adoptionOnlyFillsAnEmptySpine()
	{
		java.util.List<ChronicleApiClient.SlayerTask> cloud = new java.util.ArrayList<>();
		cloud.add(new ChronicleApiClient.SlayerTask("Abyssal demons", 180, 180, 4,
			1_700_000_000, 900_000, false));
		ChronicleApiClient.SlayerJourney inherited =
			new ChronicleApiClient.SlayerJourney(200, 48_000, 61_000_000L, 8_000_000L, cloud);
		store.adoptSlayerJourney(inherited, "Tester");
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(1, j.tasks.size());
		assertEquals(200, j.completedTasks);
		assertEquals(8_000_000L, j.totalXpEst);
		// Re-adoption (or a second server) must not double the spine.
		store.adoptSlayerJourney(inherited, "Tester");
		assertEquals(1, store.slayerJourney().tasks.size());
		// Totals still floor upward.
		store.adoptSlayerJourney(new ChronicleApiClient.SlayerJourney(
			205, 0, 0, 8_500_000L, new java.util.ArrayList<>()), "Tester");
		assertEquals(205, store.slayerJourney().completedTasks);
	}
}
