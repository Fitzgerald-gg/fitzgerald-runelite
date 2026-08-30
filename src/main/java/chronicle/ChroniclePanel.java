/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

/**
 * The journal's face: a persistent search field, a global Lifetime/Session
 * scope chip, and six tabs — Home · Drops · Log · Stats · History · Journal.
 *
 * <p>Search answers inline from anywhere; the tabs are the browsing spine; the
 * scope chip re-answers every tab at once, with the accent colour carrying the
 * state (orange = lifetime, green = session). Every list mounts a bounded
 * number of rows; views rebuild on tab switch, scope switch, and (Home only)
 * a slow timer — never per game tick.
 */
class ChroniclePanel extends PluginPanel
{
	private static final DateTimeFormatter DAY =
		DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter WHEN =
		DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());
	private static final Color ACCENT_LIFETIME = ColorScheme.BRAND_ORANGE;
	private static final Color ACCENT_SESSION = new Color(85, 163, 90);
	private static final int ROW_CAP = 30;

	private enum Scope
	{
		LIFETIME, SESSION
	}

	private enum View
	{
		HOME, DROPS, LOG, STATS, HISTORY, JOURNAL
	}

	private final ChroniclePlugin plugin;

	private final JPanel display = new JPanel(new BorderLayout());
	// No display panel handed to the group: view swapping is ours (rebuild()),
	// driven from onSelectEvent — handing it `display` makes the group swap in
	// each tab's content component itself, which NPEs on our contentless tabs.
	private final MaterialTabGroup tabGroup = new MaterialTabGroup();
	private final IconTextField searchField = new IconTextField();
	private final JLabel scopeLifetime = new JLabel("Lifetime", JLabel.CENTER);
	private final JLabel scopeSession = new JLabel("Session", JLabel.CENTER);
	private final Timer searchDebounce;
	private final Timer homeTicker;

	private Scope scope = Scope.LIFETIME;
	private View view = View.HOME;
	private String statsFamily = StatRegistry.FAMILIES[0];

	ChroniclePanel(ChroniclePlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// ── scope chip ────────────────────────────────────────────────────
		JPanel chip = new JPanel(new GridLayout(1, 2, 4, 0));
		chip.setBackground(ColorScheme.DARK_GRAY_COLOR);
		chip.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		styleScopeHalf(scopeLifetime);
		styleScopeHalf(scopeSession);
		scopeLifetime.addMouseListener(clicker(() -> setScope(Scope.LIFETIME)));
		scopeSession.addMouseListener(clicker(() -> setScope(Scope.SESSION)));
		chip.add(scopeLifetime);
		chip.add(scopeSession);
		north.add(chip);
		north.add(vgap(6));

		// ── search ────────────────────────────────────────────────────────
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 28));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchDebounce = new Timer(150, e -> onSearchChanged());
		searchDebounce.setRepeats(false);
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}
		});
		north.add(searchField);
		north.add(vgap(6));

		// ── tabs ──────────────────────────────────────────────────────────
		tabGroup.setLayout(new GridLayout(1, 6, 2, 0));
		addTab("tab_home.png", "Home", View.HOME);
		addTab("tab_drops.png", "Drops", View.DROPS);
		addTab("tab_log.png", "Collection log", View.LOG);
		addTab("tab_stats.png", "Stats", View.STATS);
		addTab("tab_history.png", "History", View.HISTORY);
		addTab("tab_journal.png", "Journal", View.JOURNAL);
		north.add(tabGroup);
		north.add(vgap(8));

		add(north, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		// Home refreshes on a slow tick while it is the visible view — enough
		// for the session strip and recent drops to feel live without any
		// per-game-tick Swing work.
		homeTicker = new Timer(3000, e ->
		{
			if (view == View.HOME && searchQuery().isEmpty())
			{
				rebuild();
			}
		});
		homeTicker.start();

		rebuild();
	}

	private void addTab(String icon, String tooltip, View target)
	{
		MaterialTab tab = new MaterialTab(
			new ImageIcon(ImageUtil.loadImageResource(ChroniclePanel.class, icon)),
			tabGroup, new JPanel());
		tab.setToolTipText(tooltip);
		tab.setOnSelectEvent(() ->
		{
			view = target;
			searchField.setText("");
			rebuild();
			return true;
		});
		tabGroup.addTab(tab);
		if (target == View.HOME)
		{
			tabGroup.select(tab);
		}
	}

	// ------------------------------------------------------------------
	// State + shared bits
	// ------------------------------------------------------------------

	private Color accent()
	{
		return scope == Scope.SESSION ? ACCENT_SESSION : ACCENT_LIFETIME;
	}

	private void setScope(Scope s)
	{
		if (scope != s)
		{
			scope = s;
			rebuild();
		}
	}

	private String searchQuery()
	{
		return searchField.getText() == null ? "" : searchField.getText().trim();
	}

	private Map<String, Long> counters()
	{
		if (scope == Scope.SESSION)
		{
			Map<String, Long> out = new LinkedHashMap<>();
			plugin.sessionCounters().forEach((k, v) -> out.put(k, v.longValue()));
			return out;
		}
		return plugin.lifetimeCounters();
	}

	/** Refresh from plugin state — safe from any thread; rebuilds the view. */
	void update()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	private void rebuild()
	{
		styleScopeState();
		display.removeAll();
		JPanel body;
		if (!searchQuery().isEmpty())
		{
			body = buildSearch(searchQuery());
		}
		else
		{
			switch (view)
			{
				case DROPS:
					body = buildDrops();
					break;
				case LOG:
					body = buildLog();
					break;
				case STATS:
					body = buildStats();
					break;
				case HISTORY:
					body = buildHistory();
					break;
				case JOURNAL:
					body = buildJournal();
					break;
				case HOME:
				default:
					body = buildHome();
					break;
			}
		}
		JScrollPane scroll = new JScrollPane(wrapTop(body),
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(14);
		display.add(scroll, BorderLayout.CENTER);
		display.revalidate();
		display.repaint();
	}

	private void onSearchChanged()
	{
		rebuild();
	}

	// ------------------------------------------------------------------
	// Views
	// ------------------------------------------------------------------

	private JPanel buildHome()
	{
		JPanel p = column();
		String rsn = plugin.displayRsn();
		JPanel hdr = new JPanel(new BorderLayout());
		hdr.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel name = new JLabel(rsn != null && !rsn.isEmpty() ? rsn : "Chronicle");
		name.setFont(name.getFont().deriveFont(Font.BOLD, 14f));
		JLabel state = new JLabel(scope == Scope.SESSION ? "session" : "journaling");
		state.setForeground(accent());
		state.setFont(state.getFont().deriveFont(10f));
		hdr.add(name, BorderLayout.WEST);
		hdr.add(state, BorderLayout.EAST);
		hdr.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		p.add(hdr);
		p.add(vgap(6));

		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null)
		{
			JPanel card = card("Slayer task");
			card.add(row(task.task, task.remaining + " left", accent()));
			if (task.initial > 0)
			{
				card.add(progress(1f - (float) task.remaining / task.initial));
			}
			p.add(card);
			p.add(vgap(6));
		}

		JPanel strip = card("This session");
		Map<String, Integer> sess = plugin.sessionCounters();
		strip.add(row("Damage dealt", fmt(sess.getOrDefault("damageDealt", 0)), null));
		strip.add(row("Drops taken",
			plugin.sessionLoots() + " · " + gp(plugin.sessionLootValue()) + " gp", null));
		strip.add(row("Tiles run", fmt(sess.getOrDefault("tilesRan", 0)), null));
		p.add(strip);
		p.add(vgap(6));

		List<LocalStore.RecentDrop> recent = plugin.recentDrops();
		if (!recent.isEmpty())
		{
			JPanel card = card("Recent drops");
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			int shown = 0;
			for (LocalStore.RecentDrop d : recent)
			{
				if (shown++ >= 10)
				{
					break;
				}
				JLabel slot = new JLabel();
				slot.setPreferredSize(new Dimension(36, 32));
				slot.setHorizontalAlignment(JLabel.CENTER);
				slot.setToolTipText(d.name + (d.quantity > 1 ? " ×" + fmt(d.quantity) : ""));
				AsyncBufferedImage img = plugin.items().getImage(d.itemId, d.quantity, d.quantity > 1);
				img.addTo(slot);
				grid.add(slot);
			}
			card.add(grid);
			p.add(card);
			p.add(vgap(6));
		}

		int avail = plugin.clogAvailable();
		if (avail > 0)
		{
			JPanel card = card("Collection log");
			int fin = plugin.clogFinished();
			card.add(row(fmt(fin) + " / " + fmt(avail),
				Math.round(100f * fin / avail) + "%", null));
			card.add(progress((float) fin / avail));
			p.add(card);
		}
		return p;
	}

	private JPanel buildDrops()
	{
		JPanel p = column();
		if (scope == Scope.SESSION)
		{
			JPanel card = card("This session");
			card.add(row("Drops taken",
				plugin.sessionLoots() + " · " + gp(plugin.sessionLootValue()) + " gp", accent()));
			p.add(card);
			p.add(vgap(6));
		}
		List<LocalStore.SourceRow> sources = plugin.dropSources();
		sources.sort(Comparator.comparingLong((LocalStore.SourceRow r) -> r.value).reversed());
		if (sources.isEmpty())
		{
			p.add(note("Drops appear here as you play — every kill, priced as it lands."));
			return p;
		}
		int shown = 0;
		for (LocalStore.SourceRow r : sources)
		{
			if (shown++ >= ROW_CAP)
			{
				break;
			}
			JPanel card = cardPlain();
			card.add(row(r.name, gp(r.value) + " gp", accent()));
			String sub = (r.kc > 0 ? fmt(r.kc) + " kc" : fmt(r.loots) + " drops")
				+ (r.pb != null ? " · PB " + pb(r.pb) : "");
			card.add(row(sub, r.kc > 0 ? gp(r.value / Math.max(1, r.kc)) + " gp/kc" : "", null));
			p.add(card);
			p.add(vgap(4));
		}
		if (sources.size() > ROW_CAP)
		{
			p.add(note(fmt(sources.size() - ROW_CAP) + " more sources — search to find one"));
		}
		return p;
	}

	private JPanel buildLog()
	{
		JPanel p = column();
		int avail = plugin.clogAvailable();
		JPanel head = card("Collection log");
		if (avail > 0)
		{
			int fin = plugin.clogFinished();
			head.add(row(fmt(fin) + " / " + fmt(avail),
				Math.round(100f * fin / avail) + "%", accent()));
			head.add(progress((float) fin / avail));
		}
		else
		{
			head.add(row("Log in to read your log", "", null));
		}
		p.add(head);
		p.add(vgap(6));
		p.add(note("Open your collection log in game once and every page fills in; "
			+ "page kill counts arrive as you browse."));
		p.add(vgap(6));

		List<LocalStore.ClogPage> pages = plugin.clogPages();
		pages.sort(Comparator.comparing(pg -> pg.page.toLowerCase(Locale.ROOT)));
		int shown = 0;
		for (LocalStore.ClogPage pg : pages)
		{
			if (shown++ >= ROW_CAP)
			{
				break;
			}
			p.add(row(pg.page, pg.held + " held" + (pg.kc != null ? " · " + fmt(pg.kc) + " kc" : ""), null));
		}
		if (pages.size() > ROW_CAP)
		{
			p.add(note(fmt(pages.size() - ROW_CAP) + " more pages"));
		}
		return p;
	}

	private JPanel buildStats()
	{
		JPanel p = column();
		JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
		pills.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String fam : StatRegistry.FAMILIES)
		{
			JLabel pill = new JLabel(fam);
			pill.setOpaque(true);
			pill.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
			pill.setFont(pill.getFont().deriveFont(10f));
			boolean on = fam.equals(statsFamily);
			pill.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			pill.setForeground(on ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			pill.addMouseListener(clicker(() ->
			{
				statsFamily = fam;
				rebuild();
			}));
			pills.add(pill);
		}
		pills.setAlignmentX(Component.LEFT_ALIGNMENT);
		pills.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
		p.add(pills);
		p.add(vgap(4));

		Map<String, Long> counters = counters();
		List<Map.Entry<String, Long>> rows = new ArrayList<>();
		for (Map.Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() != 0 && StatRegistry.family(e.getKey()).equals(statsFamily))
			{
				rows.add(e);
			}
		}
		rows.sort(Map.Entry.<String, Long>comparingByValue().reversed());
		if (rows.isEmpty())
		{
			p.add(note(scope == Scope.SESSION
				? "Nothing in this family yet this session."
				: "Nothing tracked in this family yet."));
			return p;
		}
		int shown = 0;
		for (Map.Entry<String, Long> e : rows)
		{
			if (shown++ >= ROW_CAP)
			{
				break;
			}
			String v = StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
			p.add(row(StatRegistry.label(e.getKey()), v, null));
		}
		if (rows.size() > ROW_CAP)
		{
			p.add(note(fmt(rows.size() - ROW_CAP) + " more — search to find one"));
		}
		return p;
	}

	private JPanel buildHistory()
	{
		JPanel p = column();
		JPanel card = card("History");
		card.add(row("Recording began", "today", accent()));
		p.add(card);
		p.add(vgap(6));
		p.add(note("Chronicle closes a daily baseline of your skills and counters from "
			+ "now on — day, week, month and year views arrive here as the record "
			+ "grows, along with a one-time Wise Old Man import for your deeper past."));
		return p;
	}

	private JPanel buildJournal()
	{
		JPanel p = column();
		List<JsonObject> feed = plugin.feedNewest(50);
		if (feed.isEmpty())
		{
			p.add(note("Milestones — pets, log slots, tasks, quests, deaths — are "
				+ "noted here as they happen."));
		}
		String lastDay = null;
		for (JsonObject e : feed)
		{
			long ts = e.has("ts") ? e.get("ts").getAsLong() : 0;
			String day = ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "";
			if (!day.equals(lastDay))
			{
				lastDay = day;
				JLabel g = new JLabel(day.toUpperCase(Locale.ROOT));
				g.setForeground(accent());
				g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
				g.setAlignmentX(Component.LEFT_ALIGNMENT);
				g.setBorder(BorderFactory.createEmptyBorder(7, 2, 3, 0));
				p.add(g);
			}
			p.add(row(feedLine(e), "", null));
		}

		p.add(vgap(10));
		JButton export = new JButton("Export journal data (JSON)");
		export.addActionListener(ev -> plugin.actionExport());
		export.setAlignmentX(Component.LEFT_ALIGNMENT);
		export.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		p.add(export);
		p.add(vgap(4));
		p.add(note("saved beside your journal: .runelite/chronicle/"));
		p.add(vgap(8));

		if (plugin.cloudActive())
		{
			p.add(buildCloudSection());
		}
		else
		{
			p.add(note("Journaling locally — nothing leaves this computer. "
				+ "Cloud sync lives under Advanced in the plugin settings."));
		}
		return p;
	}

	/** Cloud self-service: status, sync actions, privacy — shown only when syncing. */
	private JPanel buildCloudSection()
	{
		JPanel s = column();
		JLabel t = new JLabel("Cloud");
		t.setFont(t.getFont().deriveFont(Font.BOLD, 12f));
		t.setForeground(accent());
		t.setAlignmentX(Component.LEFT_ALIGNMENT);
		s.add(t);
		s.add(vgap(4));
		String rsn = plugin.enrolledRsn();
		boolean enrolled = rsn != null && !rsn.isEmpty();
		s.add(row(enrolled ? "Enrolled: " + rsn : "Not enrolled", "", null));
		s.add(row(plugin.statusLine(), "", null));
		s.add(vgap(6));

		JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 5));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton push = new JButton("Push stats now");
		push.addActionListener(e -> plugin.actionPushNow());
		buttons.add(push);
		JButton reEnrol = new JButton("Re-enrol this account");
		reEnrol.addActionListener(e -> plugin.actionReEnrol());
		buttons.add(reEnrol);
		JButton open = new JButton("Open my page");
		open.setEnabled(enrolled);
		open.addActionListener(e ->
		{
			if (rsn != null)
			{
				LinkBrowser.browse(plugin.serverBaseUrl() + "/osrs/"
					+ rsn.trim().toLowerCase(Locale.ROOT).replace(' ', '-'));
			}
		});
		buttons.add(open);
		JButton lock = new JButton(plugin.pageLocked() ? "Unlock page" : "Lock page (set passphrase)");
		lock.setEnabled(enrolled);
		lock.addActionListener(e -> onLockClicked());
		buttons.add(lock);
		JButton list = new JButton(plugin.publicListed() ? "Unlist from directory" : "List in public directory");
		list.setEnabled(enrolled);
		list.addActionListener(e -> plugin.actionSetPublic(!plugin.publicListed()));
		buttons.add(list);
		Long pending = plugin.deletePendingTs();
		JButton delete = new JButton(pending != null ? "Cancel deletion" : "Delete my cloud data");
		delete.setEnabled(enrolled);
		delete.addActionListener(e -> onDeleteClicked());
		buttons.add(delete);
		s.add(buttons);
		if (pending != null)
		{
			s.add(vgap(4));
			s.add(note("Deletion scheduled for " + WHEN.format(Instant.ofEpochSecond(pending))
				+ " — cancel above to keep your data."));
		}
		return s;
	}

	private JPanel buildSearch(String q)
	{
		JPanel p = column();
		String ql = q.toLowerCase(Locale.ROOT);
		int total = 0;

		// Trackers — via the registry, so every counter is findable by label or key.
		List<Map.Entry<String, Long>> statHits = new ArrayList<>();
		for (Map.Entry<String, Long> e : counters().entrySet())
		{
			if (e.getValue() != 0
				&& (e.getKey().toLowerCase(Locale.ROOT).contains(ql)
				|| StatRegistry.label(e.getKey()).toLowerCase(Locale.ROOT).contains(ql)))
			{
				statHits.add(e);
			}
		}
		statHits.sort(Map.Entry.<String, Long>comparingByValue().reversed());
		if (!statHits.isEmpty())
		{
			p.add(group("Trackers"));
			for (int i = 0; i < Math.min(4, statHits.size()); i++)
			{
				Map.Entry<String, Long> e = statHits.get(i);
				String v = StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
				p.add(row(StatRegistry.label(e.getKey()), v, null));
				total++;
			}
		}

		// Drops — source names and item names from the journal.
		List<LocalStore.SourceRow> srcHits = new ArrayList<>();
		for (LocalStore.SourceRow r : plugin.dropSources())
		{
			if (r.name.toLowerCase(Locale.ROOT).contains(ql))
			{
				srcHits.add(r);
			}
		}
		srcHits.sort(Comparator.comparingLong((LocalStore.SourceRow r) -> r.value).reversed());
		if (!srcHits.isEmpty())
		{
			p.add(group("Drops"));
			for (int i = 0; i < Math.min(4, srcHits.size()); i++)
			{
				LocalStore.SourceRow r = srcHits.get(i);
				p.add(row(r.name, (r.kc > 0 ? fmt(r.kc) + " kc · " : "") + gp(r.value) + " gp", null));
				total++;
			}
		}

		// Collection log — pages the journal holds.
		List<LocalStore.ClogPage> pageHits = new ArrayList<>();
		for (LocalStore.ClogPage pg : plugin.clogPages())
		{
			if (pg.page.toLowerCase(Locale.ROOT).contains(ql))
			{
				pageHits.add(pg);
			}
		}
		if (!pageHits.isEmpty())
		{
			p.add(group("Collection log"));
			for (int i = 0; i < Math.min(4, pageHits.size()); i++)
			{
				LocalStore.ClogPage pg = pageHits.get(i);
				p.add(row(pg.page, pg.held + " held"
					+ (pg.kc != null ? " · " + fmt(pg.kc) + " kc" : ""), null));
				total++;
			}
		}

		// Journal — milestone lines.
		List<JsonObject> feedHits = new ArrayList<>();
		for (JsonObject e : plugin.feedNewest(500))
		{
			if (feedLine(e).toLowerCase(Locale.ROOT).contains(ql))
			{
				feedHits.add(e);
				if (feedHits.size() >= 4)
				{
					break;
				}
			}
		}
		if (!feedHits.isEmpty())
		{
			p.add(group("Journal"));
			for (JsonObject e : feedHits)
			{
				long ts = e.has("ts") ? e.get("ts").getAsLong() : 0;
				p.add(row(feedLine(e), ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "", null));
				total++;
			}
		}

		if (total == 0)
		{
			p.add(note("Nothing matches \"" + q + "\" yet."));
		}
		return p;
	}

	// ------------------------------------------------------------------
	// Feed rendering
	// ------------------------------------------------------------------

	private static String feedLine(JsonObject e)
	{
		String type = e.has("type") ? e.get("type").getAsString() : "";
		JsonObject d = e.has("data") && e.get("data").isJsonObject()
			? e.getAsJsonObject("data") : new JsonObject();
		switch (type)
		{
			case "PET":
				return "Pet — " + str(d, "petName", "a new companion");
			case "COLLECTION":
				return "Log slot — " + str(d, "itemName", "new item");
			case "COMBAT_ACHIEVEMENT":
				return "CA " + str(d, "tier", "") + " — " + str(d, "task", "task");
			case "QUEST":
				return "Quest — " + str(d, "questName", str(d, "quest", "complete"));
			case "DIARY":
				return "Diary — " + str(d, "area", "") + " " + str(d, "difficulty", "");
			case "CLUE":
				return "Clue — " + str(d, "clueType", "casket opened");
			case "DEATH":
			{
				String k = str(d, "killerName", "");
				return k.isEmpty() ? "Died" : "Died to " + k;
			}
			case "SLAYER":
			{
				String t = str(d, "slayerTask", "");
				String kc = str(d, "killCount", "");
				return "Task complete" + (t.isEmpty() ? "" : " — " + t)
					+ (kc.isEmpty() ? "" : ", " + kc + " killed");
			}
			default:
				return type.isEmpty() ? "Milestone" : StatRegistry.prettify(type.toLowerCase(Locale.ROOT));
		}
	}

	private static String str(JsonObject o, String key, String fallback)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	// ------------------------------------------------------------------
	// Small Swing helpers
	// ------------------------------------------------------------------

	private void styleScopeHalf(JLabel l)
	{
		l.setOpaque(true);
		l.setFont(l.getFont().deriveFont(10f));
		l.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		l.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
	}

	private void styleScopeState()
	{
		scopeLifetime.setForeground(scope == Scope.LIFETIME
			? ACCENT_LIFETIME : ColorScheme.LIGHT_GRAY_COLOR.darker());
		scopeSession.setForeground(scope == Scope.SESSION
			? ACCENT_SESSION : ColorScheme.LIGHT_GRAY_COLOR.darker());
	}

	private static JPanel column()
	{
		// A vertical stack whose children ALWAYS span the full column width.
		// BoxLayout can't be trusted with that (it widens children to the
		// widest sibling's preferred width and drifts mixed alignments), so
		// this is a single-column GridBag that applies the constraint to every
		// child as it is added — call sites just add().
		JPanel p = new JPanel(new java.awt.GridBagLayout())
		{
			private final java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();

			{
				gbc.gridx = 0;
				gbc.gridwidth = java.awt.GridBagConstraints.REMAINDER;
				gbc.weightx = 1;
				gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
			}

			@Override
			protected void addImpl(Component comp, Object constraints, int index)
			{
				super.addImpl(comp, constraints == null ? gbc : constraints, index);
			}
		};
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return p;
	}

	private static JPanel wrapTop(JPanel body)
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.add(body, BorderLayout.NORTH);
		return wrap;
	}

	private static JPanel card(String caption)
	{
		JPanel c = cardPlain();
		JLabel cap = new JLabel(caption.toUpperCase(Locale.ROOT));
		cap.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		cap.setFont(cap.getFont().deriveFont(9.5f));
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.add(cap);
		c.add(vgap(3));
		return c;
	}

	private static JPanel cardPlain()
	{
		// Max width unbounded so BoxLayout stretches the card to the column
		// instead of centring it at preferred width; height stays preferred
		// because the column sits in a NORTH slot.
		JPanel c = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		c.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	private static JPanel row(String left, String right, Color rightColor)
	{
		JPanel r = new JPanel(new BorderLayout(8, 0));
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		JLabel l = new JLabel(left);
		l.setFont(l.getFont().deriveFont(11.5f));
		r.add(l, BorderLayout.CENTER);
		if (right != null && !right.isEmpty())
		{
			JLabel v = new JLabel(right);
			v.setFont(v.getFont().deriveFont(11.5f));
			v.setForeground(rightColor != null ? rightColor : ColorScheme.LIGHT_GRAY_COLOR.darker());
			r.add(v, BorderLayout.EAST);
		}
		return r;
	}

	private JPanel progress(float frac)
	{
		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(ColorScheme.SCROLL_TRACK_COLOR);
		outer.setPreferredSize(new Dimension(10, 4));
		outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
		outer.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel inner = new JPanel();
		inner.setBackground(accent());
		inner.setPreferredSize(new Dimension(
			Math.max(1, Math.round(frac * (PluginPanel.PANEL_WIDTH - 40))), 4));
		JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		holder.setOpaque(false);
		holder.add(inner);
		outer.add(holder, BorderLayout.WEST);
		return outer;
	}

	private JLabel group(String name)
	{
		JLabel g = new JLabel(name.toUpperCase(Locale.ROOT));
		g.setForeground(accent());
		g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
		g.setAlignmentX(Component.LEFT_ALIGNMENT);
		g.setBorder(BorderFactory.createEmptyBorder(8, 2, 3, 0));
		return g;
	}

	private static JLabel note(String text)
	{
		JLabel n = new JLabel("<html><i>" + escape(text) + "</i></html>")
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		n.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		n.setFont(n.getFont().deriveFont(10.5f));
		n.setAlignmentX(Component.LEFT_ALIGNMENT);
		return n;
	}

	private static Component vgap(int h)
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setPreferredSize(new Dimension(1, h));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static MouseAdapter clicker(Runnable r)
	{
		return new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				r.run();
			}
		};
	}

	private void onLockClicked()
	{
		if (plugin.pageLocked())
		{
			int ok = JOptionPane.showConfirmDialog(this,
				"Remove the password lock from your page?\nAnyone with the link will be able to view it again.",
				"Unlock page", JOptionPane.OK_CANCEL_OPTION);
			if (ok == JOptionPane.OK_OPTION)
			{
				plugin.actionSetLock("");
			}
			return;
		}
		JPasswordField field = new JPasswordField();
		int ok = JOptionPane.showConfirmDialog(this, field,
			"Set a passphrase — viewers of your page will need it", JOptionPane.OK_CANCEL_OPTION);
		if (ok != JOptionPane.OK_OPTION)
		{
			return;
		}
		String pw = new String(field.getPassword());
		if (pw.trim().isEmpty())
		{
			JOptionPane.showMessageDialog(this, "Empty passphrase — nothing changed.");
			return;
		}
		plugin.actionSetLock(pw);
	}

	private void onDeleteClicked()
	{
		if (plugin.deletePendingTs() != null)
		{
			plugin.actionScheduleDelete(true);   // cancel
			return;
		}
		int ok = JOptionPane.showConfirmDialog(this,
			"Schedule deletion of your cloud profile and all its data?\n"
				+ "It is removed in 7 days. You can cancel any time before then.",
			"Delete my data", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (ok == JOptionPane.OK_OPTION)
		{
			plugin.actionScheduleDelete(false);
		}
	}

	// ------------------------------------------------------------------
	// Formatting
	// ------------------------------------------------------------------

	private static String fmt(long n)
	{
		return String.format(Locale.UK, "%,d", n);
	}

	private static String gp(long n)
	{
		if (Math.abs(n) >= 1_000_000_000L)
		{
			return String.format(Locale.UK, "%.2fB", n / 1_000_000_000.0);
		}
		if (Math.abs(n) >= 1_000_000L)
		{
			return String.format(Locale.UK, "%.1fM", n / 1_000_000.0);
		}
		if (Math.abs(n) >= 10_000L)
		{
			return String.format(Locale.UK, "%dk", n / 1_000);
		}
		return fmt(n);
	}

	private static String pb(double seconds)
	{
		long s = Math.round(seconds);
		long h = s / 3600;
		long m = (s % 3600) / 60;
		long sec = s % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
