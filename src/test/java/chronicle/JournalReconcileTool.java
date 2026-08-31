/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assume.assumeTrue;

/**
 * Fold an exported journal into an on-disk one through the plugin's OWN import,
 * for an operator working directly on a machine rather than clicking the panel
 * button. Using the real {@link LocalStore#importJournal} is the whole point:
 * a merge written twice is a merge that can disagree with itself.
 *
 * <p>Inert unless pointed at something:
 * {@code -Dchronicle.dir=<journal dir> -Dchronicle.rsn=<name> -Dchronicle.import=<file>}.
 * The plugin must not be running — it holds the journal in memory and writes
 * over the file on its own interval, which would discard whatever this did.
 */
public class JournalReconcileTool
{
	@Test
	public void reconcile() throws Exception
	{
		String dirPath = System.getProperty("chronicle.dir");
		String rsn = System.getProperty("chronicle.rsn");
		String importPath = System.getProperty("chronicle.import");
		assumeTrue(dirPath != null && rsn != null && importPath != null);

		File dir = new File(dirPath);
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(i -> i.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(0);
		ItemComposition comp = Mockito.mock(ItemComposition.class);
		Mockito.when(comp.getName()).thenReturn("");
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenReturn(comp);

		Gson gson = new Gson();
		LocalStore store = new LocalStore(im, gson);
		store.load(dir, rsn);

		int sourcesBefore = store.dropSources().size();
		int feedBefore = store.feedNewest(100000).size();
		int trackersBefore = store.trackersSnapshot().size();
		int tasksBefore = store.slayerJourney().tasks.size();
		int detailedBefore = detailed(store);
		int pairsBefore = store.untakenSources().size();

		JsonObject in = gson.fromJson(new String(
			Files.readAllBytes(new File(importPath).toPath()), StandardCharsets.UTF_8),
			JsonObject.class);
		String summary = store.importJournal(in, rsn);
		store.flush(dir);

		System.out.println("=== reconcile: " + rsn + " ===");
		System.out.println("import said: " + summary);
		System.out.println("drop sources  " + sourcesBefore + " -> " + store.dropSources().size());
		System.out.println("feed entries  " + feedBefore + " -> " + store.feedNewest(100000).size());
		System.out.println("counters      " + trackersBefore + " -> " + store.trackersSnapshot().size());
		System.out.println("slayer tasks  " + tasksBefore + " -> " + store.slayerJourney().tasks.size());
		System.out.println("  with detail " + detailedBefore + " -> " + detailed(store));
		System.out.println("untaken srcs  " + pairsBefore + " -> " + store.untakenSources().size());
		int withItems = 0;
		for (LocalStore.UntakenRow r : store.untakenSources())
		{
			withItems += store.untakenItemsOf(r.name).isEmpty() ? 0 : 1;
		}
		System.out.println("  itemised    " + withItems + " of " + store.untakenSources().size());
	}

	private static int detailed(LocalStore store)
	{
		int n = 0;
		for (int i = 0; i < store.slayerJourney().tasks.size(); i++)
		{
			if (!store.slayerTaskMonsters(i).isEmpty())
			{
				n++;
			}
		}
		return n;
	}
}
