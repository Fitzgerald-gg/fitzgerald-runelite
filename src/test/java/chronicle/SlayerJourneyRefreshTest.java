/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Slayer tab's journey block has to follow the journal while the client is
 * still running. Finish a task and "Tasks done" moves without a restart; leave
 * the journal alone and the block settles instead of rebuilding forever.
 */
public class SlayerJourneyRefreshTest
{
	@Test
	public void theJourneyBlockShowsTheNewFiguresWhenATaskCompletes() throws Exception
	{
		CountingStub stub = newStub();
		stub.journey = journeyOf(90, 12_345, task("Nechryael", 167), task("Gargoyles", 143));
		ChroniclePanel panel = mountSlayerPanel(stub);

		rebuild(panel);
		pump(4);
		assertEquals("90", valueAfter(panel, "Tasks done"));
		assertEquals("12,345", valueAfter(panel, "Kills on task"));

		// One more task finishes while the panel is still up.
		stub.journey = journeyOf(91, 12_600,
			task("Abyssal demons", 255), task("Nechryael", 167), task("Gargoyles", 143));
		rebuild(panel);
		pump(4);

		assertEquals("the journey block is still painting the old task count",
			"91", valueAfter(panel, "Tasks done"));
		assertEquals("the journey block is still painting the old kill count",
			"12,600", valueAfter(panel, "Kills on task"));
		assertTrue("the task that just finished never reached the list",
			labels(panel).contains("Abyssal demons"));
	}

	@Test
	public void anUnchangedJourneyDoesNotKeepRebuildingTheTab() throws Exception
	{
		CountingStub stub = newStub();
		stub.journey = journeyOf(90, 12_345, task("Nechryael", 167));
		ChroniclePanel panel = mountSlayerPanel(stub);

		rebuild(panel);
		pump(8);
		int settled = stub.fetches;

		// Nothing has moved in the journal, and nothing should move on screen.
		pump(8);
		assertEquals("the journey read is rebuilding the tab in a loop",
			settled, stub.fetches);
		// The journey lands and the tab repaints once to show it. A couple of
		// reads is that settling; a climbing tally is the loop.
		assertTrue("the journey read never settled, it took " + settled + " reads",
			settled <= 3);
	}

	// ------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------

	private static ChronicleApiClient.SlayerJourney journeyOf(int completed, long kills,
		ChronicleApiClient.SlayerTask... tasks)
	{
		List<ChronicleApiClient.SlayerTask> list = new ArrayList<>();
		java.util.Collections.addAll(list, tasks);
		return new ChronicleApiClient.SlayerJourney(completed, kills, 5_000_000L, 0, list);
	}

	// ts 0 keeps the dateline off the card, which keeps the label list short
	private static ChronicleApiClient.SlayerTask task(String name, long kills)
	{
		return new ChronicleApiClient.SlayerTask(name, kills, 0, 0, 0, 1_000L, false);
	}

	private CountingStub newStub()
	{
		CountingStub s = new CountingStub(mockItems());
		s.rsn = "Fixture";
		return s;
	}

	/** {@link PanelPreviewTest.StubPlugin} with a tally of the journey reads. */
	private static final class CountingStub extends PanelPreviewTest.StubPlugin
	{
		int fetches;

		CountingStub(ItemManager im)
		{
			super(im);
		}

		@Override
		void fetchSlayerJourney(
			java.util.function.Consumer<ChronicleApiClient.SlayerJourney> onDone)
		{
			fetches++;
			super.fetchSlayerJourney(onDone);
		}
	}

	// same throwaway icon PanelPreviewTest draws, since headless has no sprites
	private ItemManager mockItems()
	{
		ClientThread ct = Mockito.mock(ClientThread.class);
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.getImage(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
			.thenAnswer(inv ->
			{
				AsyncBufferedImage img =
					new AsyncBufferedImage(ct, 36, 32, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				g.dispose();
				img.loaded();
				return img;
			});
		return im;
	}

	// ------------------------------------------------------------------
	// Driving the panel
	// ------------------------------------------------------------------

	private ChroniclePanel mountSlayerPanel(CountingStub stub) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() ->
		{
			javax.swing.UIManager.setLookAndFeel(new net.runelite.client.ui.laf.RuneLiteLAF());
			holder[0] = new ChroniclePanel(stub);
		});
		setEnum(holder[0], "view", "chronicle.ChroniclePanel$View", "SLAYER");
		return holder[0];
	}

	private static void rebuild(ChroniclePanel panel) throws Exception
	{
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("rebuild");
			m.setAccessible(true);
			m.invoke(panel);
		});
	}

	// The panel answers a journey read on an invokeLater, and that callback can
	// post a rebuild of its own, which is why one turn of the queue is not enough.
	private static void pump(int turns) throws Exception
	{
		for (int i = 0; i < turns; i++)
		{
			SwingUtilities.invokeAndWait(() ->
			{
			});
		}
	}

	// ------------------------------------------------------------------
	// Reading what is on screen
	// ------------------------------------------------------------------

	// The row builder adds the caption first and its figure second, so the text
	// straight after a caption is the figure the panel is showing for it.
	private static String valueAfter(ChroniclePanel panel, String caption)
	{
		List<String> texts = labels(panel);
		int at = texts.indexOf(caption);
		assertTrue("the panel is not showing a \"" + caption + "\" row at all", at >= 0);
		assertTrue("\"" + caption + "\" has no figure beside it", at + 1 < texts.size());
		return texts.get(at + 1);
	}

	private static List<String> labels(Component c)
	{
		List<String> out = new ArrayList<>();
		collect(c, out);
		return out;
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel)
		{
			String t = ((JLabel) c).getText();
			if (t != null && !t.isEmpty())
			{
				out.add(t);
			}
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, out);
			}
		}
	}

	// ------------------------------------------------------------------
	// Reflection, borrowed from PanelPreviewTest
	// ------------------------------------------------------------------

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setEnum(ChroniclePanel panel, String field, String enumClass,
		String constant) throws Exception
	{
		Class<?> cls = Class.forName(enumClass);
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(panel, Enum.valueOf((Class<Enum>) cls, constant));
	}

	private static void edt(ThrowingRunnable r) throws Exception
	{
		final Exception[] err = {null};
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				r.run();
			}
			catch (Exception e)
			{
				err[0] = e;
			}
		});
		if (err[0] != null)
		{
			throw err[0];
		}
	}

	private interface ThrowingRunnable
	{
		void run() throws Exception;
	}
}
