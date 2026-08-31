/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * The panel's own eyes: renders every ChroniclePanel surface — each tab, both
 * scopes, drilled and empty states — to PNGs under {@code build/panel-preview/}
 * without a client, a login, or a screenshot round-trip.
 *
 * <p>The stub plugin feeds the panel fixture data shaped like a real account
 * (long source names, wrapped notes, sparse and dense lists — the states that
 * have historically clipped). When a real journal exists on this machine
 * ({@code ~/.runelite/chronicle/}) a second set renders from it through the
 * real {@link LocalStore} parsing, so the previews match the game exactly.
 *
 * <p>Doubles as a regression test: every surface must BUILD headless without
 * throwing — the class of startup NPE that once kept the whole plugin from
 * loading fails here first.
 */
public class PanelPreviewTest
{
	private static final int PANEL_W = 242;   // non-wrapped PluginPanel width
	private static final int MAX_H = 2600;

	@Test
	public void renderAllSurfaces() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		// The client's own look-and-feel — scrollbars, viewport backgrounds and
		// text metrics all match the real sidebar, so a preview IS the panel.
		edt(() -> javax.swing.UIManager.setLookAndFeel(
			new net.runelite.client.ui.laf.RuneLiteLAF()));
		File out = new File("build/panel-preview");
		//noinspection ResultOfMethodCallIgnored
		out.mkdirs();

		renderSet(out, "fix", fixturePlugin());

		StubPlugin real = realJournalPlugin();
		if (real != null)
		{
			renderSet(out, "real", real);
		}
	}

	/** Run on the EDT — the panel asserts it, same as in the client. */
	private static void edt(ThrowingRunnable r) throws Exception
	{
		final Exception[] err = {null};
		javax.swing.SwingUtilities.invokeAndWait(() ->
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

	private void renderSet(File out, String prefix, StubPlugin stub) throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(stub));
		ChroniclePanel panel = holder[0];

		shoot(panel, out, prefix + "-home", "HOME");
		shoot(panel, out, prefix + "-drops", "DROPS");
		// slayer's journey lands via invokeLater after the first paint — the
		// second shot renders the settled view over the same file
		shoot(panel, out, prefix + "-slayer", "SLAYER");
		shoot(panel, out, prefix + "-slayer", "SLAYER");

		// the pivot navigation: a source under the glass, then an item
		List<LocalStore.SourceRow> src = stub.dropSources();
		if (!src.isEmpty())
		{
			src.sort((a, b) -> Long.compare(b.value, a.value));
			set(panel, "detailSource", src.get(0).name);
			shoot(panel, out, prefix + "-source-detail", "DROPS");
			set(panel, "detailSource", null);
		}
		for (LocalStore.SourceRow sr : src)
		{
			List<LocalStore.BagItem> bag = stub.sourceItems(sr.name);
			if (!bag.isEmpty())
			{
				set(panel, "detailItem", bag.get(0).name);
				shoot(panel, out, prefix + "-item-detail", "DROPS");
				set(panel, "detailItem", null);
				break;
			}
		}

		set(panel, "dropsLeftBehind", true);
		shoot(panel, out, prefix + "-drops-leftbehind", "DROPS");
		set(panel, "dropsLeftBehind", false);

		shoot(panel, out, prefix + "-log", "LOG");
		set(panel, "clogPageSel", firstClogPage(panel));
		shoot(panel, out, prefix + "-log-drill", "LOG");
		set(panel, "clogPageSel", null);

		for (String fam : chronicle.panel.StatRegistry.FAMILIES)
		{
			set(panel, "statsFamily", fam);
			String slug = fam.toLowerCase().replaceAll("[^a-z]+", "-");
			shoot(panel, out, prefix + "-stats-" + slug, "STATS");
		}
		// one craft opened, to see rows + the ghost "Other" reconciliation —
		// Prayer additionally opens its verb folds (the second drill level)
		set(panel, "statsFamily", "Skilling");
		expandSection(panel, "Skilling:Cooking");
		expandSection(panel, "Skilling:Prayer");
		expandSection(panel, "Skilling:Prayer:AshesScattered");
		expandSection(panel, "Skilling:Prayer:BonesBuried");
		shoot(panel, out, prefix + "-stats-skilling-open", "STATS");
		collapseAll(panel);
		// the roads: Teleports fold open with Destinations nested inside
		set(panel, "statsFamily", "Ledger & Roads");
		expandSection(panel, "Ledger & Roads:Teleports");
		expandSection(panel, "Ledger & Roads:Destinations");
		shoot(panel, out, prefix + "-stats-roads-open", "STATS");
		collapseAll(panel);
		set(panel, "statsFamily", chronicle.panel.StatRegistry.FAMILIES[0]);

		for (String g : new String[]{"Day", "Week", "Month", "Year"})
		{
			set(panel, "histGranularity", g);
			shoot(panel, out, prefix + "-history-" + g.toLowerCase(), "HISTORY");
		}

		shoot(panel, out, prefix + "-journal", "JOURNAL");

		// search results view
		setSearch(panel, "dragon");
		shoot(panel, out, prefix + "-search", "HOME");
		setSearch(panel, "");
	}

	// ------------------------------------------------------------------
	// Fixture data — shaped to stress the layouts that have clipped
	// ------------------------------------------------------------------

	private StubPlugin fixturePlugin()
	{
		StubPlugin s = new StubPlugin(mockItems());
		s.rsn = "Oxli";
		s.slayer = new ChronicleEventCapture.SlayerView("Abyssal demons", 63, 184);

		s.lifetime.put("damageDealt", 1_842_337L);
		s.lifetime.put("damageDealtMelee", 1_204_818L);
		s.lifetime.put("damageDealtRanged", 402_113L);
		s.lifetime.put("damageDealtMagic", 235_406L);
		s.lifetime.put("highestHit", 73L);
		s.lifetime.put("tilesWalked", 402_551L);
		s.lifetime.put("tilesRan", 1_113_207L);
		s.lifetime.put("teleportsTotal", 4_882L);
		s.lifetime.put("teleportsViaJewellery", 1_212L);
		s.lifetime.put("teleportsViaSpell", 2_105L);
		s.lifetime.put("teleportsVarrock", 311L);
		s.lifetime.put("teleportsGrandExchange", 899L);
		s.lifetime.put("coinsFromAlchemy", 12_400_310L);
		s.lifetime.put("coinsSpentAtShops", 1_002_113L);
		s.lifetime.put("itemsDroppedValue", 88_120L);
		s.lifetime.put("consumedValue", 3_204_112L);
		s.lifetime.put("sharksEaten", 2_113L);
		s.lifetime.put("potionDoses", 8_442L);
		s.lifetime.put("vialsShattered", 1_204L);
		s.lifetime.put("bonesBuried", 3_112L);
		s.lifetime.put("bonesOffered", 12_078L);
		s.lifetime.put("prayersActivated", 44_120L);
		s.lifetime.put("logsChopped", 22_501L);
		s.lifetime.put("fishCaught", 18_112L);
		s.lifetime.put("oresMined", 9_313L);
		s.lifetime.put("chompyBirdsPlucked", 302L);
		s.lifetime.put("implingsCaught", 511L);
		s.lifetime.put("pickpockets", 12_113L);
		s.lifetime.put("essenceCrafted", 30_112L);
		s.lifetime.put("clueScrollsCompleted", 213L);
		s.lifetime.put("deaths", 148L);

		s.session.put("damageDealt", 24_113);
		s.session.put("tilesRan", 8_442);
		s.session.put("consumedValue", 112_400);
		s.session.put("sharksEaten", 42);
		s.session.put("teleportsTotal", 12);

		s.sessionLoots = 37;
		s.sessionLootValue = 1_204_113L;
		s.sessionUntaken = new long[]{9, 44_120L};

		s.sources.add(new LocalStore.SourceRow("Abyssal demons", 4_112, 3_890, 61_204_113L, null, 34));
		s.sources.add(new LocalStore.SourceRow("Nechryael", 2_204, 2_090, 24_113_005L, null, 21));
		s.sources.add(new LocalStore.SourceRow("Commander Zilyana", 214, 214, 88_204_113L, 74.2, 40));
		s.sources.add(new LocalStore.SourceRow("Crazy archaeologist", 88, 88, 1_204_113L, 31.8, 12));
		s.sources.add(new LocalStore.SourceRow("Thermonuclear smoke devil", 1_402, 1_390, 19_113_205L, 22.2, 28));
		s.sources.add(new LocalStore.SourceRow("Brutal black dragon", 950, 921, 15_204_113L, null, 25));
		s.sessionSources.add(new LocalStore.SourceRow("Abyssal demons", 121, 118, 1_112_400L, null, 0));
		s.sessionSources.add(new LocalStore.SourceRow("Nechryael", 14, 13, 91_713L, null, 0));

		List<LocalStore.BagItem> bag = new ArrayList<>();
		bag.add(new LocalStore.BagItem(4151, "Abyssal whip", 3, 5_406_000L));
		bag.add(new LocalStore.BagItem(592, "Ashes", 3_881, 8_412L));
		bag.add(new LocalStore.BagItem(1747, "Black dragonhide", 1_204, 3_204_113L));
		bag.add(new LocalStore.BagItem(560, "Death rune", 8_112, 1_402_113L));
		s.bags.put("Abyssal demons", bag);

		s.untaken.add(new LocalStore.UntakenRow("Abyssal demons", 412, 512_113L));
		s.untaken.add(new LocalStore.UntakenRow("Nechryael", 1_204, 204_113L));
		s.untaken.add(new LocalStore.UntakenRow("Thermonuclear smoke devil", 88, 41_205L));
		s.untakenItems.add(new LocalStore.UntakenRow("Bones", 3_121, 97_435L));
		s.untakenItems.add(new LocalStore.UntakenRow("Rune javelin heads", 44, 38_210L));
		s.untakenItems.add(new LocalStore.UntakenRow("Air rune", 8_350, 41_750L));

		for (int i = 0; i < 8; i++)
		{
			s.recent.add(new LocalStore.RecentDrop(4151 + i, i == 2 ? 340 : 1, "Drop " + i));
		}

		// clog: a plausible partial log
		JsonObject clog = new JsonObject();
		clog.addProperty("finished", 412);
		clog.addProperty("available", 1_568);
		JsonObject items = new JsonObject();
		items.addProperty("abyssal whip", 3);
		items.addProperty("abyssal head", 1);
		items.addProperty("pet kraken", 1);
		clog.add("clog_items", items);
		JsonObject kcs = new JsonObject();
		kcs.addProperty("abyssal sire", 214);
		kcs.addProperty("zulrah", 502);
		clog.add("kcs", kcs);
		JsonObject skcs = new JsonObject();
		skcs.addProperty("Abyssal demon", 4112);
		skcs.addProperty("Nechryael", 2204);
		skcs.addProperty("Dust devil", 928);
		skcs.addProperty("Gargoyle", 661);
		clog.add("slayer_kcs", skcs);
		s.clog = clog;

		// a cloud journey, so the Slayer tab renders its full dress
		s.cloud = true;
		List<ChronicleApiClient.SlayerTask> tasks = new ArrayList<>();
		tasks.add(new ChronicleApiClient.SlayerTask("Abyssal demons", 121, 184, 4,
			System.currentTimeMillis() / 1000.0, 1_112_400L, true));
		tasks.add(new ChronicleApiClient.SlayerTask("Nechryael", 167, 0, 0,
			System.currentTimeMillis() / 1000.0 - 400_000, 812_113L, false));
		tasks.add(new ChronicleApiClient.SlayerTask("Thermonuclear smoke devils", 233, 0, 12,
			System.currentTimeMillis() / 1000.0 - 900_000, 2_012_113L, false));
		s.journey = new ChronicleApiClient.SlayerJourney(214, 48_231, 61_204_113L,
			8_204_113L, tasks);
		s.consumVals.put("sharksEaten", 1_985_000L);
		s.consumVals.put("potionDoses", 3_204_000L);
		s.grinds.add(new ChronicleApiClient.GrindRow("Abyssal demons", "Abyssal head",
			4_112, 6_000, 51.0));
		s.clogFinished = 412;
		s.clogAvailable = 1_568;

		// feed: a few days of milestones
		long now = System.currentTimeMillis();
		s.feed.add(feedEntry(now - 3_600_000L, "PET", "petName", "Abyssal orphan"));
		s.feed.add(feedEntry(now - 7_200_000L, "COLLECTION", "itemName", "Abyssal head"));
		s.feed.add(feedEntry(now - 90_000_000L, "QUEST", "questName", "Dragon Slayer II"));
		s.feed.add(feedEntry(now - 95_000_000L, "DIARY", "area", "Karamja"));
		s.feed.add(feedEntry(now - 180_000_000L, "COMBAT_ACHIEVEMENT", "task", "Perfect Zulrah"));
		s.feed.add(feedEntry(now - 190_000_000L, "DEATH", "killerName", "Commander Zilyana"));

		// history: five weeks of daily baselines with drifting xp
		LocalDate d = LocalDate.now();
		long base = 13_204_113L;
		for (int i = 35; i >= 0; i--)
		{
			HistoryLog.Baseline b = new HistoryLog.Baseline();
			b.skills.put("attack", base + (35 - i) * 21_204L);
			b.skills.put("slayer", base / 2 + (35 - i) * 44_113L);
			b.skills.put("runecraft", 1_204_113L + (35 - i) * 8_402L);
			b.counters.put("damageDealt", 1_500_000L + (35 - i) * 9_113L);
			b.counters.put("tilesRan", 900_000L + (35 - i) * 5_204L);
			s.history.put(d.minusDays(i), b);
		}
		return s;
	}

	private static JsonObject feedEntry(long ts, String type, String key, String val)
	{
		JsonObject e = new JsonObject();
		e.addProperty("ts", ts);
		e.addProperty("type", type);
		JsonObject data = new JsonObject();
		data.addProperty(key, val);
		e.add("data", data);
		return e;
	}

	/** A second stub fed from the REAL journal on this machine, when present. */
	private StubPlugin realJournalPlugin()
	{
		File dir = new File(System.getProperty("user.home"), ".runelite/chronicle");
		File journal = new File(dir, "oxli.json");
		if (!journal.isFile())
		{
			return null;
		}
		ItemManager im = mockItems();
		LocalStore store = new LocalStore(im, new Gson());
		store.load(dir, "Oxli");

		StubPlugin s = new StubPlugin(im);
		s.rsn = "Oxli";
		s.sources = store.dropSources();
		s.untaken = store.untakenSources();
		s.recent = store.recentDrops();
		s.clog = store.clogSnapshot();
		s.clogFinished = store.clogFraction()[0];
		s.clogAvailable = store.clogFraction()[1];
		s.lifetime = store.trackersSnapshot();
		s.feed = store.feedNewest(2000);
		s.store = store;
		s.history = new HistoryLog(new Gson()).read(dir, "Oxli");
		// The real journey + dryness, through the real local engines.
		s.journey = store.slayerJourney();
		s.consumVals = store.consumableValues();
		s.grinds = new GrindBook(new Gson()).grinds(store.clogSnapshot(), store.dropSources());
		return s;
	}

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
				g.setColor(new java.awt.Color(96, 88, 68));
				g.fillRoundRect(6, 4, 24, 24, 6, 6);
				g.dispose();
				img.loaded();
				return img;
			});
		return im;
	}

	// ------------------------------------------------------------------
	// The stub: every panel-facing read overridden with plain data
	// ------------------------------------------------------------------

	static class StubPlugin extends ChroniclePlugin
	{
		String rsn;
		ChronicleEventCapture.SlayerView slayer;
		Map<String, Long> lifetime = new LinkedHashMap<>();
		Map<String, Integer> session = new LinkedHashMap<>();
		int sessionLoots;
		long sessionLootValue;
		long[] sessionUntaken = {0, 0};
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		List<LocalStore.SourceRow> sessionSources = new ArrayList<>();
		Map<String, List<LocalStore.BagItem>> bags = new LinkedHashMap<>();
		List<LocalStore.UntakenRow> untaken = new ArrayList<>();
		List<LocalStore.UntakenRow> untakenItems = new ArrayList<>();
		List<LocalStore.RecentDrop> recent = new ArrayList<>();
		List<JsonObject> feed = new ArrayList<>();
		JsonObject clog = new JsonObject();
		int clogFinished;
		int clogAvailable;
		TreeMap<LocalDate, HistoryLog.Baseline> history = new TreeMap<>();
		LocalStore store;   // set for the real-journal variant
		boolean cloud;
		ChronicleApiClient.SlayerJourney journey;
		Map<String, Long> consumVals = new LinkedHashMap<>();
		List<ChronicleApiClient.GrindRow> grinds = new ArrayList<>();
		private final ItemManager itemManager;

		StubPlugin(ItemManager im)
		{
			this.itemManager = im;
		}

		@Override
		String displayRsn()
		{
			return rsn;
		}

		@Override
		ChronicleEventCapture.SlayerView slayerView()
		{
			return slayer;
		}

		@Override
		Map<String, Long> lifetimeCounters()
		{
			return lifetime;
		}

		@Override
		Map<String, Integer> sessionCounters()
		{
			return session;
		}

		@Override
		Map<String, Integer> sessionDisplayCounters()
		{
			return session;
		}

		@Override
		int sessionLoots()
		{
			return sessionLoots;
		}

		@Override
		long sessionLootValue()
		{
			return sessionLootValue;
		}

		@Override
		long[] sessionUntakenTally()
		{
			return sessionUntaken;
		}

		@Override
		java.util.List<LocalStore.SourceRow> dropSources()
		{
			return new ArrayList<>(sources);
		}

		@Override
		java.util.List<LocalStore.SourceRow> sessionSourceRows()
		{
			return new ArrayList<>(sessionSources);
		}

		@Override
		java.util.List<LocalStore.BagItem> sourceItems(String source)
		{
			if (store != null)
			{
				return store.sourceItems(source);
			}
			return new ArrayList<>(bags.getOrDefault(source, new ArrayList<>()));
		}

		@Override
		java.util.List<LocalStore.UntakenRow> untakenSources()
		{
			return new ArrayList<>(untaken);
		}

		@Override
		java.util.List<LocalStore.UntakenRow> untakenItems()
		{
			return new ArrayList<>(untakenItems);
		}

		@Override
		java.util.List<LocalStore.RecentDrop> recentDrops()
		{
			return new ArrayList<>(recent);
		}

		@Override
		java.util.List<JsonObject> feedNewest(int n)
		{
			return feed.subList(0, Math.min(n, feed.size()));
		}

		@Override
		JsonObject clogSnapshot()
		{
			return clog;
		}

		@Override
		int clogFinished()
		{
			return clogFinished;
		}

		@Override
		int clogAvailable()
		{
			return clogAvailable;
		}

		@Override
		TreeMap<LocalDate, HistoryLog.Baseline> historyBaselines()
		{
			return history;
		}

		@Override
		boolean cloudActive()
		{
			return cloud;
		}

		@Override
		String serverBaseUrl()
		{
			return cloud ? "https://example.invalid" : "";
		}

		@Override
		void fetchSlayerJourney(
			java.util.function.Consumer<ChronicleApiClient.SlayerJourney> onDone)
		{
			onDone.accept(journey);
		}

		@Override
		boolean slayerSeenThisSession()
		{
			return slayer != null;
		}

		@Override
		java.util.Map<String, Long> consumableValues()
		{
			return new LinkedHashMap<>(consumVals);
		}

		@Override
		void fetchGrinds(
			java.util.function.Consumer<java.util.List<ChronicleApiClient.GrindRow>> onDone)
		{
			onDone.accept(new ArrayList<>(grinds));
		}

		@Override
		String enrolledRsn()
		{
			return rsn;
		}

		@Override
		String statusLine()
		{
			return "Journaling locally — nothing leaves this computer.";
		}

		@Override
		com.google.gson.Gson gson()
		{
			return new Gson();   // the stub has no injector
		}

		@Override
		net.runelite.client.game.ItemManager items()
		{
			return itemManager;
		}

		@Override
		void actionExport()
		{
		}

		@Override
		void actionPushNow()
		{
		}

	}

	// ------------------------------------------------------------------
	// Driving + rendering
	// ------------------------------------------------------------------

	private String firstClogPage(ChroniclePanel panel) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("taxonomy", com.google.gson.Gson.class);
		m.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Map<String, List<String>>> tax =
			(Map<String, Map<String, List<String>>>) m.invoke(null, new Gson());
		Map<String, List<String>> bosses = tax.get("Bosses");
		return bosses == null || bosses.isEmpty() ? null : bosses.keySet().iterator().next();
	}

	private void shoot(ChroniclePanel panel, File out, String name, String view)
		throws Exception
	{
		edt(() ->
		{
			setEnum(panel, "view", "chronicle.ChroniclePanel$View", view);
			Method rebuild = ChroniclePanel.class.getDeclaredMethod("rebuild");
			rebuild.setAccessible(true);
			rebuild.invoke(panel);

			panel.setSize(PANEL_W, 1200);
			layoutTree(panel);
			int h = Math.min(MAX_H, panel.getPreferredSize().height + 44);
			panel.setSize(PANEL_W, Math.max(h, 300));
			layoutTree(panel);

			BufferedImage img = new BufferedImage(PANEL_W, panel.getHeight(),
				BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(0, 0, img.getWidth(), img.getHeight());
			panel.paint(g);
			g.dispose();
			ImageIO.write(img, "png", new File(out, name + ".png"));
		});
	}

	private static void setSearch(ChroniclePanel panel, String q) throws Exception
	{
		edt(() ->
		{
			Field f = ChroniclePanel.class.getDeclaredField("searchField");
			f.setAccessible(true);
			((net.runelite.client.ui.components.IconTextField) f.get(panel)).setText(q);
		});
	}

	private static void set(ChroniclePanel panel, String field, Object val) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(panel, val);
	}

	@SuppressWarnings("unchecked")
	private static java.util.Set<String> expandedSet(ChroniclePanel panel) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("statsExpanded");
		f.setAccessible(true);
		return (java.util.Set<String>) f.get(panel);
	}

	private static void expandSection(ChroniclePanel panel, String key) throws Exception
	{
		expandedSet(panel).add(key);
	}

	private static void collapseAll(ChroniclePanel panel) throws Exception
	{
		expandedSet(panel).clear();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setEnum(ChroniclePanel panel, String field, String enumClass, String constant)
		throws Exception
	{
		Class<?> cls = Class.forName(enumClass);
		set(panel, field, Enum.valueOf((Class<Enum>) cls, constant));
	}

	private static void layoutTree(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				layoutTree(k);
			}
		}
	}
}
