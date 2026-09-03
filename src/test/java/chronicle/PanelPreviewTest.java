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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 * Renders every ChroniclePanel surface to a PNG under {@code build/panel-preview/}
 * with no client and no login: a fixture set first, then a second set read from the
 * real journal in {@code ~/.runelite/chronicle/} when there is one. Also a
 * regression test, since a surface that throws while building fails here.
 */
public class PanelPreviewTest
{
	private static final int PANEL_W = 242;   // non-wrapped PluginPanel width
	private static final int MAX_H = 2600;

	@Test
	public void renderAllSurfaces() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		// the client's own LAF, so scrollbars and text metrics match the sidebar
		edt(() -> javax.swing.UIManager.setLookAndFeel(
			new net.runelite.client.ui.laf.RuneLiteLAF()));
		File out = new File("build/panel-preview");
		//noinspection ResultOfMethodCallIgnored
		out.mkdirs();
		// wipe the last run's PNGs, or a surface that stopped rendering keeps
		// showing its old picture
		File[] stale = out.listFiles((d, n) -> n.endsWith(".png"));
		if (stale != null)
		{
			for (File f : stale)
			{
				//noinspection ResultOfMethodCallIgnored
				f.delete();
			}
		}

		renderSet(out, "fix", fixturePlugin());

		// Only when asked. A stranger running the suite should not have their own
		// journal read, and the fixture set covers every surface anyway.
		StubPlugin real = System.getProperty("chronicle.realJournal") != null
			? realJournalPlugin() : null;
		if (real != null)
		{
			renderSet(out, "real", real);
		}
	}

	// all panel work goes through the EDT, same as in the client
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

	// SkillGain's constructor is package-private to counters, so build one reflectively.
	private static chronicle.counters.ExperienceStatTracker.SkillGain gain(
		net.runelite.api.Skill skill, long xp, long perHour) throws Exception
	{
		java.lang.reflect.Constructor<chronicle.counters.ExperienceStatTracker.SkillGain> c =
			chronicle.counters.ExperienceStatTracker.SkillGain.class.getDeclaredConstructor(
				net.runelite.api.Skill.class, long.class, long.class);
		c.setAccessible(true);
		return c.newInstance(skill, xp, perHour);
	}

	private void renderSet(File out, String prefix, StubPlugin stub) throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(stub));
		ChroniclePanel panel = holder[0];

		shoot(panel, out, prefix + "-home", "HOME");
		// the xp fold open: the rows only exist in this state, so the closed shot
		// above cannot tell anyone whether they still render
		expandSection(panel, "home:xp");
		shoot(panel, out, prefix + "-home-xp", "HOME");
		collapseAll(panel);
		shoot(panel, out, prefix + "-drops", "DROPS");
		// the journey lands via invokeLater after the first paint, so shoot twice
		// and let the settled view overwrite the file
		shoot(panel, out, prefix + "-slayer", "SLAYER");
		shoot(panel, out, prefix + "-slayer", "SLAYER");

		// drilled: a source, then an item inside one
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

		// a task drilled, then left-behind by source and by item
		set(panel, "detailTask", 0);
		shoot(panel, out, prefix + "-slayer-task", "SLAYER");
		set(panel, "detailTask", -1);
		List<LocalStore.UntakenRow> un = stub.untakenSources();
		if (!un.isEmpty())
		{
			set(panel, "leftBehindSource", un.get(0).name);
			shoot(panel, out, prefix + "-leftbehind-source", "DROPS");
			set(panel, "leftBehindSource", null);
		}
		List<LocalStore.UntakenRow> ui = stub.untakenItems();
		if (!ui.isEmpty())
		{
			set(panel, "leftBehindItem", ui.get(0).name);
			shoot(panel, out, prefix + "-leftbehind-item", "DROPS");
			set(panel, "leftBehindItem", null);
		}
		shoot(panel, out, prefix + "-manage", "MANAGE");
		set(panel, "journalLens", "Slayer");
		shoot(panel, out, prefix + "-journal-slayer", "JOURNAL");
		set(panel, "journalLens", "All");

		set(panel, "histBosses", true);
		shoot(panel, out, prefix + "-history-bosses", "HISTORY");
		set(panel, "histBosses", false);

		set(panel, "clogTab", "Other");
		set(panel, "clogPageSel", "All Pets");
		shoot(panel, out, prefix + "-log-pets", "LOG");
		// two pets open on the same page: the detail only exists in this state, so
		// the folded shot above cannot say whether it still renders, and two at once
		// is the keyed register doing what one boolean could not
		expandSection(panel, "pets:All Pets:" + firstFoldablePet(stub, "All Pets"));
		expandSection(panel, "pets:All Pets:tiny tempor");
		shoot(panel, out, prefix + "-log-pets-open", "LOG");
		collapseAll(panel);
		set(panel, "clogPageSel", "Skilling Pets");
		shoot(panel, out, prefix + "-log-pets-skilling", "LOG");
		expandSection(panel, "pets:Skilling Pets:"
			+ firstFoldablePet(stub, "Skilling Pets"));
		shoot(panel, out, prefix + "-log-pets-skilling-open", "LOG");
		collapseAll(panel);
		set(panel, "clogPageSel", null);
		set(panel, "clogTab", "Bosses");

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
		// one craft open for its rows and the leftover "Other"; Prayer goes a
		// level deeper into its verb folds
		set(panel, "statsFamily", "Skilling");
		expandSection(panel, "Skilling:Cooking");
		expandSection(panel, "Skilling:Prayer");
		expandSection(panel, "Skilling:Prayer:AshesScattered");
		expandSection(panel, "Skilling:Prayer:BonesBuried");
		shoot(panel, out, prefix + "-stats-skilling-open", "STATS");
		collapseAll(panel);
		// Teleports open with Destinations nested inside it
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

		setSearch(panel, "dragon");
		shoot(panel, out, prefix + "-search", "HOME");
		setSearch(panel, "");
	}

	// ------------------------------------------------------------------
	// Fixture data: long names and dense lists, the states that clip
	// ------------------------------------------------------------------

	private StubPlugin fixturePlugin() throws Exception
	{
		StubPlugin s = new StubPlugin(mockItems());
		s.rsn = "Fixture";
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
		// resourcesDropped is a slice of itemsDropped, so keep it under or the
		// row shows a part bigger than its whole
		s.lifetime.put("itemsDroppedValue", 1_488_120L);
		s.lifetime.put("resourcesGatheredValue", 4_233_800L);
		s.lifetime.put("resourcesDroppedValue", 1_142_600L);
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

		// The skilling pets' own attempts, and the levels their odds are read at.
		// Between them these light every skilling row the pets page can draw, and
		// three counters that must stay out of it: agilityObstacles is obstacles
		// where the roll is laps, the failed pickpockets never rolled, and
		// bloodwood is rolled per swing rather than per log.
		s.skills.put("woodcutting", new long[]{92, 6_517_253L});
		s.skills.put("mining", new long[]{85, 3_258_594L});
		s.skills.put("thieving", new long[]{78, 1_629_200L});
		s.skills.put("agility", new long[]{88, 4_470_000L});
		s.skills.put("hunter", new long[]{80, 1_986_068L});
		s.skills.put("runecraft", new long[]{91, 5_902_831L});
		s.skills.put("farming", new long[]{84, 3_000_000L});
		s.lifetime.put("yewLogsChopped", 14_204L);
		s.lifetime.put("willowLogsChopped", 5_185L);
		s.lifetime.put("magicLogsChopped", 3_112L);
		s.lifetime.put("bloodwoodLogsChopped", 4_002L);
		s.lifetime.put("coalMined", 6_204L);
		s.lifetime.put("ironOreMined", 2_113L);
		s.lifetime.put("amethystMined", 1_204L);
		s.lifetime.put("runiteOreMined", 402L);
		s.lifetime.put("masterFarmerPickpockets", 9_204L);
		s.lifetime.put("masterFarmerFailedPickpockets", 4_112L);
		s.lifetime.put("elfPickpockets", 2_100L);
		s.lifetime.put("gemStallsThieved", 3_012L);
		s.lifetime.put("seersLaps", 2_204L);
		s.lifetime.put("ardougneLaps", 1_113L);
		s.lifetime.put("canifisLaps", 402L);
		s.lifetime.put("agilityObstacles", 41_002L);
		s.lifetime.put("blackChinchompasTrapped", 8_204L);
		s.lifetime.put("redChinchompasTrapped", 1_112L);
		s.lifetime.put("herbiboarsHarvested", 812L);
		s.lifetime.put("bloodRunecrafted", 12_004L);
		s.lifetime.put("soulRunecrafted", 2_100L);
		s.lifetime.put("ranarrPlanted", 1_204L);
		s.lifetime.put("guamPlanted", 402L);
		s.lifetime.put("torstolPlanted", 120L);
		s.lifetime.put("oakPlanted", 88L);
		s.lifetime.put("yewPlanted", 44L);
		// Heron off three fish the counter can name, beside a Trawler count the
		// level never touches; Soup off the salvage the ledger can tier.
		s.skills.put("fishing", new long[]{96, 10_692_629L});
		s.lifetime.put("sharkCaught", 21_204L);
		s.lifetime.put("anglerfishCaught", 8_112L);
		s.lifetime.put("minnowCaught", 4_002L);
		s.lifetime.put("harpoonFishCaught", 9_000L);
		s.lifetime.put("opulentSalvagePulled", 1_204L);
		s.lifetime.put("smallSalvagePulled", 4_002L);
		s.lifetime.put("salvagePulled", 5_206L);
		s.lifetime.put("salvageSorted", 3_112L);
		s.lifetime.put("portTasksCompleted", 802L);
		s.lifetime.put("barracudaTrialsCompleted", 220L);

		s.session.put("damageDealt", 24_113);
		s.session.put("tilesRan", 8_442);
		s.session.put("consumedValue", 112_400);
		s.session.put("sharksEaten", 42);
		s.session.put("teleportsTotal", 12);
		// the fold header is a pinned row, so it needs the total to exist at all;
		// this is the sum of the four skills below it
		s.session.put("totalXpGained", 533_100);
		s.skillXp.add(gain(net.runelite.api.Skill.RUNECRAFT, 400_000, 250_000));
		s.skillXp.add(gain(net.runelite.api.Skill.SLAYER, 96_400, 60_250));
		s.skillXp.add(gain(net.runelite.api.Skill.HITPOINTS, 32_100, 20_060));
		s.skillXp.add(gain(net.runelite.api.Skill.FLETCHING, 4_600, 2_875));
		// past the dozen the Home card used to stop at, so the render proves it does not
		s.session.put("headlessArrowsFletched", 26_955);
		s.session.put("distanceRan", 5_739);
		s.session.put("distanceWalked", 3_786);
		s.session.put("hitsBlocked", 135);
		s.session.put("cabbagesPicked", 83);
		s.session.put("flaxGathered", 61);
		s.session.put("examines", 44);
		s.session.put("animalsPetted", 19);
		s.session.put("patchesRaked", 17);
		s.session.put("itemsDiscarded", 14);
		s.session.put("coinsSpentAtShops", 9_100);
		s.session.put("highestHit", 71);

		s.sessionLoots = 37;
		s.sessionLootValue = 1_204_113L;
		s.sessionUntaken = new long[]{9, 44_120L};

		s.sources.add(new LocalStore.SourceRow("Abyssal demons", 4_112, 3_890, 61_204_113L, null, 0, 0));
		s.sources.add(new LocalStore.SourceRow("Nechryael", 2_204, 2_090, 24_113_005L, null, 0, 0));
		s.sources.add(new LocalStore.SourceRow("Commander Zilyana", 214, 214, 88_204_113L, 74.2, 0, 0));
		s.sources.add(new LocalStore.SourceRow("Crazy archaeologist", 88, 88, 1_204_113L, 31.8, 0, 0));
		s.sources.add(new LocalStore.SourceRow("Thermonuclear smoke devil", 1_402, 1_390, 19_113_205L, 22.2, 0, 0));
		s.sources.add(new LocalStore.SourceRow("Brutal black dragon", 950, 921, 15_204_113L, null, 0, 0));
		// Tempoross three ways: the subdue count, the reward pool searches those
		// permits bought, and the caskets one of those searches handed over. Tiny
		// tempor is priced off the middle one alone.
		s.sources.add(new LocalStore.SourceRow("Reward pool (Tempoross)", 114, 114, 4_112_005L, null, 0, 0));
		s.sources.add(new LocalStore.SourceRow("Casket (Tempoross)", 25, 25, 812_400L, null, 0, 0));

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
		// the pets page reads these: Smolcano out of one source, Callisto cub out of
		// two, Baby mole past the drought line, and a Kraken kc under a pet the log
		// already holds, which must stay silent
		kcs.addProperty("zalcano", 2_023);
		kcs.addProperty("callisto", 1_500);
		kcs.addProperty("artio", 900);
		kcs.addProperty("giant mole", 12_000);
		kcs.addProperty("kraken", 3_000);
		// Tangleroot's other half: the Hespori kill count, which the skilling book
		// prices off the same formula as the patches
		kcs.addProperty("hespori", 61);
		// And the pets counted the same way a boss is, in the unit their roll is
		// asked in: a search, a crate, a casket, a high gamble, a loot sack, a trip,
		// a kill. Two of them are spelled the way the log spells them and two the
		// way the ledger does, which is the join the book has to make.
		kcs.addProperty("guardians of the rift", 5_218);
		kcs.addProperty("soul wars", 346);
		kcs.addProperty("clue scroll (master)", 300);
		kcs.addProperty("barbarian assault high gamble", 611);
		kcs.addProperty("hunters' loot sack (expert)", 502);
		kcs.addProperty("hunters' loot sack (master)", 110);
		kcs.addProperty("hunter guild", 4_000);
		kcs.addProperty("chompy bird", 295);
		kcs.addProperty("yama", 1_204);
		kcs.addProperty("shellbane gryphon", 214);
		kcs.addProperty("brutus", 75);
		kcs.addProperty("the mad angel", 124);
		kcs.addProperty("fishing trawler", 410);
		kcs.addProperty("tempoross", 46);
		clog.add("kcs", kcs);
		// The chompy chick waits on the elite Western Provinces diary and rolls
		// nothing before it. The fixture holds it, so the row is drawn; the
		// real-journal set does not, and draws none.
		JsonObject diaries = new JsonObject();
		JsonObject western = new JsonObject();
		western.addProperty("easy", true);
		western.addProperty("medium", true);
		western.addProperty("hard", true);
		western.addProperty("elite", true);
		diaries.add("western", western);
		s.achievements.add("diaries", diaries);
		JsonObject skcs = new JsonObject();
		skcs.addProperty("Abyssal demon", 4112);
		skcs.addProperty("Nechryael", 2204);
		skcs.addProperty("Dust devil", 928);
		skcs.addProperty("Gargoyle", 661);
		clog.add("slayer_kcs", skcs);
		s.clog = clog;

		// cloud on so Manage draws its sync section; the journey fills Slayer
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
		// One pet the journal holds, so a pets page has a provenance line to fold
		// away beside the chases: the log already lights this one.
		s.petRows.add(new LocalStore.PetRow("Pet kraken", "Kraken", 2_147,
			java.time.Instant.parse("2024-11-08T20:14:00Z").toEpochMilli()));
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

	// a second stub fed from the real journal on this machine, when there is one
	private StubPlugin realJournalPlugin()
	{
		File dir = new File(System.getProperty("user.home"), ".runelite/chronicle");
		File journal = newestJournal(dir);
		if (journal == null)
		{
			return null;
		}
		String rsn = journalRsn(journal);
		ItemManager im = mockItems();
		LocalStore store = new LocalStore(im, new Gson());
		store.load(dir, rsn);

		StubPlugin s = new StubPlugin(im);
		s.rsn = rsn;
		s.sources = store.dropSources();
		s.untaken = store.untakenSources();
		s.recent = store.recentDrops();
		s.clog = store.clogSnapshot();
		s.clogFinished = store.clogFraction()[0];
		s.clogAvailable = store.clogFraction()[1];
		s.lifetime = store.trackersSnapshot();
		s.feed = store.feedNewest(2000);
		s.store = store;
		s.history = new HistoryLog(new Gson()).read(dir, rsn);
		s.journey = store.slayerJourney();
		s.consumVals = store.consumableValues();
		s.grinds = new GrindBook(new Gson()).grinds(store.clogSnapshot(), store.dropSources());
		JsonObject clKc = store.clogSnapshot();
		if (clKc.has("kcs") && clKc.get("kcs").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: clKc.getAsJsonObject("kcs").entrySet())
			{
				s.kcs.put(e.getKey(), e.getValue().getAsLong());
			}
		}
		// same fold as ChroniclePlugin.killCounts: a ledger source the clog already
		// lists collapses into the log's spelling instead of listing twice
		Map<String, String> byKind = new LinkedHashMap<>();
		for (String n : s.kcs.keySet())
		{
			byKind.put(n.toLowerCase(Locale.ROOT).replaceAll("s$", ""), n);
		}
		for (LocalStore.SourceRow r : store.dropSources())
		{
			if (r.kc <= 0)
			{
				continue;
			}
			String known = byKind.get(r.name.toLowerCase(Locale.ROOT).replaceAll("s$", ""));
			if (known != null)
			{
				s.kcs.merge(known, (long) r.kc, Math::max);
			}
			else
			{
				s.ledgerKcs.merge(r.name, (long) r.kc, Math::max);
			}
		}
		return s;
	}

	// newest journal in the folder; a rename files the record under a new slug, so
	// we can't name one. .json only: the history spine is .jsonl, temp writes .tmp
	private static File newestJournal(File dir)
	{
		File[] found = dir.listFiles((d, n) -> n.endsWith(".json"));
		File newest = null;
		if (found != null)
		{
			for (File f : found)
			{
				if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified()))
				{
					newest = f;
				}
			}
		}
		return newest;
	}

	// LocalStore reaches a file by slugging the name it's given, so the rsn inside
	// is only usable when it slugs back to this file. the stem always does.
	private static String journalRsn(File journal)
	{
		String name = journal.getName();
		String stem = name.substring(0, name.length() - ".json".length());
		try
		{
			String txt = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8);
			JsonObject o = new Gson().fromJson(txt, JsonObject.class);
			if (o != null && o.has("rsn") && o.get("rsn").isJsonPrimitive())
			{
				String rsn = o.get("rsn").getAsString();
				if (LocalStore.slug(rsn).equals(stem))
				{
					return rsn;
				}
			}
		}
		catch (Exception ignored)
		{
			// unreadable record still renders under the stem it's filed by
		}
		return stem;
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
	// The stub: every panel-facing read answered with plain data
	// ------------------------------------------------------------------

	static class StubPlugin extends ChroniclePlugin
	{
		String rsn;
		ChronicleEventCapture.SlayerView slayer;
		Map<String, Long> lifetime = new LinkedHashMap<>();
		Map<String, Integer> session = new LinkedHashMap<>();
		// standing levels, in the shape LocalStore keeps them: {level, xp}
		Map<String, long[]> skills = new LinkedHashMap<>();
		final java.util.List<chronicle.counters.ExperienceStatTracker.SkillGain> skillXp =
			new java.util.ArrayList<>();
		int sessionLoots;
		long sessionLootValue;
		long[] sessionUntaken = {0, 0};
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		Map<String, List<LocalStore.BagItem>> bags = new LinkedHashMap<>();
		List<LocalStore.UntakenRow> untaken = new ArrayList<>();
		List<LocalStore.UntakenRow> untakenItems = new ArrayList<>();
		List<LocalStore.RecentDrop> recent = new ArrayList<>();
		List<JsonObject> feed = new ArrayList<>();
		JsonObject clog = new JsonObject();
		JsonObject achievements = new JsonObject();
		int clogFinished;
		int clogAvailable;
		TreeMap<LocalDate, HistoryLog.Baseline> history = new TreeMap<>();
		LocalStore store;   // set for the real-journal variant
		boolean cloud;
		ChronicleApiClient.SlayerJourney journey;
		Map<String, Long> consumVals = new LinkedHashMap<>();
		List<ChronicleApiClient.GrindRow> grinds = new ArrayList<>();
		List<LocalStore.PetRow> petRows = new ArrayList<>();
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
		java.util.List<chronicle.counters.ExperienceStatTracker.SkillGain> sessionSkillXp()
		{
			return skillXp;
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
		java.util.Map<String, GrindBook.PetChase> petChases(java.util.Collection<String> pets)
		{
			return new GrindBook(new Gson()).petChases(clog, sources, lifetime,
				skillSheet(), store != null ? store.achievements() : achievements, pets);
		}

		@Override
		String syncedRsn()
		{
			return rsn;
		}

		@Override
		String statusLine()
		{
			return "Journaling locally — nothing leaves this computer.";
		}

		@Override
		java.util.List<LocalStore.PetRow> pets()
		{
			return store != null ? store.pets() : new ArrayList<>(petRows);
		}

		@Override
		java.util.List<LocalStore.BagItem> slayerTaskItems(int index)
		{
			return store != null ? store.slayerTaskItems(index) : new ArrayList<>();
		}

		@Override
		java.util.List<LocalStore.UntakenRow> slayerTaskMonsters(int index)
		{
			return store != null ? store.slayerTaskMonsters(index) : taskMonsters;
		}

		@Override
		java.util.List<LocalStore.BagItem> untakenItemsOf(String source)
		{
			return store != null ? store.untakenItemsOf(source) : untakenBag;
		}

		@Override
		java.util.List<LocalStore.UntakenRow> untakenSourcesOf(String item)
		{
			return store != null ? store.untakenSourcesOf(item) : new ArrayList<>();
		}

		@Override
		PaceBook.Pace pace(String skill)
		{
			// run the real PaceBook over the stub's history rather than faking a pace
			long xp = 0;
			if (!history.isEmpty())
			{
				Long v = history.lastEntry().getValue().skills.get(skill.toLowerCase(Locale.ROOT));
				xp = v != null ? v : 0;
			}
			return PaceBook.forSkill(history, skill.toLowerCase(Locale.ROOT), xp);
		}

		java.util.List<LocalStore.UntakenRow> taskMonsters = new ArrayList<>();
		java.util.List<LocalStore.BagItem> untakenBag = new ArrayList<>();

		@Override
		String journalWarning()
		{
			return null;   // fixture data, so there's no file to warn about
		}

		@Override
		long keptSince()
		{
			long earliest = Long.MAX_VALUE;
			for (LocalStore.SourceRow r : sources)
			{
				if (r.firstMs > 0)
				{
					earliest = Math.min(earliest, r.firstMs);
				}
			}
			for (JsonObject e : feed)
			{
				if (e.has("ts") && e.get("ts").getAsLong() > 0)
				{
					earliest = Math.min(earliest, e.get("ts").getAsLong());
				}
			}
			return earliest == Long.MAX_VALUE ? 0 : earliest;
		}

		@Override
		int combatLevel()
		{
			return 125;
		}

		@Override
		java.util.Map<String, Long> killCounts()
		{
			return kcs;
		}

		@Override
		java.util.Map<String, Long> ledgerKills()
		{
			return ledgerKcs;
		}

		final Map<String, Long> ledgerKcs = new LinkedHashMap<>();

		final Map<String, Long> kcs = new LinkedHashMap<>();

		@Override
		net.runelite.client.game.SkillIconManager skillIcons()
		{
			return null;   // headless: skillIcon() catches the NPE, grid shows a bare level
		}

		@Override
		java.util.Map<String, long[]> skillSheet()
		{
			return store != null ? store.skillSheet() : skills;
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
		void actionOpenJournalFolder()
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

	// The first slot on a pets page the panel would draw a fold on: one the journal
	// owns with a provenance line, or one it prices a chase for. Falls back to the
	// first slot, which renders an inert row and says so in the picture.
	private String firstFoldablePet(StubPlugin stub, String page) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("taxonomy", com.google.gson.Gson.class);
		m.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Map<String, List<String>>> tax =
			(Map<String, Map<String, List<String>>>) m.invoke(null, new Gson());
		List<String> slots = tax.get("Other").get(page);
		for (String slot : slots)
		{
			if (foldablePet(stub, slot))
			{
				return slot.toLowerCase(Locale.ROOT);
			}
		}
		return slots.get(0).toLowerCase(Locale.ROOT);
	}

	// Whether one slot has anything under it, asked of the panel's own petDetail so
	// the harness cannot disagree with what the page draws.
	private boolean foldablePet(StubPlugin stub, String slot) throws Exception
	{
		LocalStore.PetRow own = null;
		for (LocalStore.PetRow r : stub.pets())
		{
			if (r.name.equalsIgnoreCase(slot))
			{
				own = r;
				break;
			}
		}
		Map<String, GrindBook.PetChase> chases =
			stub.petChases(java.util.Collections.singletonList(slot));
		Method det = ChroniclePanel.class.getDeclaredMethod("petDetail", boolean.class,
			LocalStore.PetRow.class, GrindBook.PetChase.class);
		det.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<javax.swing.JPanel> d = (List<javax.swing.JPanel>) det.invoke(null, false, own,
			chases.get(slot.toLowerCase(Locale.ROOT)));
		return !d.isEmpty();
	}

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
	private static java.util.Set<String> openFolds(ChroniclePanel panel) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("openFolds");
		f.setAccessible(true);
		return (java.util.Set<String>) f.get(panel);
	}

	private static void expandSection(ChroniclePanel panel, String key) throws Exception
	{
		openFolds(panel).add(key);
	}

	private static void collapseAll(ChroniclePanel panel) throws Exception
	{
		openFolds(panel).clear();
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
