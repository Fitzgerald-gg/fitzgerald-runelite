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
import net.runelite.client.ui.FontManager;
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
	private static final Color ACCENT_RED = new Color(196, 84, 74);
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
	private String expandedSource;
	private int dropsShown = ROW_CAP;
	private String clogTab = "Bosses";
	private String clogPageSel;
	private String histGranularity = "Week";
	// The period's END date (inclusive); the stepper moves it by one granule.
	private java.time.LocalDate histCursor = java.time.LocalDate.now();
	private boolean womImportRunning;
	// The bundled 1,921-slot taxonomy: tab -> page -> ordered slot names.
	// Parsed once on first Log open (~40KB).
	private static Map<String, Map<String, List<String>>> taxonomy;
	// Cloud item lists already fetched this session, keyed by source — the
	// drill fetches each source at most once.
	private final Map<String, List<ChronicleApiClient.LedgerItem>> cloudBagCache = new LinkedHashMap<>();

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

		// ── scope chip (built here, added after the tabs — mock order) ────
		JPanel chip = new JPanel(new GridLayout(1, 2, 4, 0));
		chip.setBackground(ColorScheme.DARK_GRAY_COLOR);
		chip.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		styleScopeHalf(scopeLifetime);
		styleScopeHalf(scopeSession);
		scopeLifetime.addMouseListener(clicker(() -> setScope(Scope.LIFETIME)));
		scopeSession.addMouseListener(clicker(() -> setScope(Scope.SESSION)));
		chip.add(scopeLifetime);
		chip.add(scopeSession);

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
		// ── tabs first (the mock's order), then search, then the scope chip ──
		tabGroup.setLayout(new GridLayout(1, 6, 2, 0));
		addTab("tab_home.png", "Home", View.HOME);
		addTab("tab_drops.png", "Drops", View.DROPS);
		addTab("tab_log.png", "Collection log", View.LOG);
		addTab("tab_stats.png", "Stats", View.STATS);
		addTab("tab_history.png", "History", View.HISTORY);
		addTab("tab_journal.png", "Journal", View.JOURNAL);
		north.add(tabGroup);
		north.add(vgap(7));
		north.add(searchField);
		north.add(vgap(6));
		north.add(chip);
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
			expandedSource = null;
			dropsShown = ROW_CAP;
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
			plugin.sessionDisplayCounters().forEach((k, v) -> out.put(k, v.longValue()));
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
			ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
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
		name.setFont(FontManager.getRunescapeBoldFont());
		JLabel state = new JLabel(scope == Scope.SESSION ? "session" : "journaling");
		state.setForeground(accent());
		state.setFont(FontManager.getRunescapeSmallFont());
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
		long[] untaken = plugin.sessionUntakenTally();
		strip.add(row("Left behind", fmt(untaken[0]) + " · " + gp(untaken[1]) + " gp", null));
		strip.add(row("Consumed", gp(sess.getOrDefault("consumedValue", 0)) + " gp", null));
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

	private boolean dropsLeftBehind;

	private JPanel buildDrops()
	{
		JPanel p = column();
		JPanel lens = new JPanel(new GridLayout(1, 2, 3, 3));
		lens.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String l : new String[]{"Taken", "Left behind"})
		{
			boolean on = l.equals("Left behind") == dropsLeftBehind;
			JLabel pill = new JLabel(l, JLabel.CENTER);
			pill.setOpaque(true);
			pill.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			pill.setFont(FontManager.getRunescapeSmallFont());
			pill.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			pill.setForeground(on ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			pill.addMouseListener(clicker(() ->
			{
				dropsLeftBehind = l.equals("Left behind");
				rebuild();
			}));
			lens.add(pill);
		}
		p.add(lens);
		p.add(vgap(6));

		if (dropsLeftBehind)
		{
			return buildLeftBehind(p);
		}
		if (scope == Scope.SESSION)
		{
			JPanel card = card("This session");
			card.add(row("Drops taken",
				plugin.sessionLoots() + " · " + gp(plugin.sessionLootValue()) + " gp", accent()));
			p.add(card);
			p.add(vgap(6));
		}
		// Session scope ranks only this session's take; lifetime is the journal.
		List<LocalStore.SourceRow> sources = scope == Scope.SESSION
			? plugin.sessionSourceRows() : plugin.dropSources();
		sources.sort(Comparator.comparingLong((LocalStore.SourceRow r) -> r.value).reversed());
		if (sources.isEmpty())
		{
			p.add(note(scope == Scope.SESSION
				? "Nothing taken yet this session."
				: "Drops appear here as you play — every kill, priced as it lands."));
			return p;
		}
		int shown = 0;
		for (LocalStore.SourceRow r : sources)
		{
			if (shown++ >= dropsShown)
			{
				break;
			}
			JPanel card = cardPlain();
			card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			card.add(row(r.name, gp(r.value) + " gp", accent()));
			String sub = (r.kc > 0 ? fmt(r.kc) + " kc" : fmt(r.loots) + " drops")
				+ (r.pb != null ? " · PB " + pb(r.pb) : "");
			card.add(row(sub, r.kc > 0 ? gp(r.value / Math.max(1, r.kc)) + " gp/kc" : "", null));
			if (r.name.equals(expandedSource))
			{
				addSourceDrill(card, r);
			}
			final String src = r.name;
			card.addMouseListener(clicker(() ->
			{
				expandedSource = src.equals(expandedSource) ? null : src;
				rebuild();
			}));
			p.add(card);
			p.add(vgap(4));
		}
		if (sources.size() > dropsShown)
		{
			JButton more = new JButton("Show " + Math.min(ROW_CAP, sources.size() - dropsShown)
				+ " more of " + fmt(sources.size()) + " sources");
			more.addActionListener(e ->
			{
				dropsShown += ROW_CAP;
				rebuild();
			});
			p.add(more);
		}
		return p;
	}

	/** The uncollected ledger: ghost economics, looked at by choice. */
	private JPanel buildLeftBehind(JPanel p)
	{
		long[] sess = plugin.sessionUntakenTally();
		if (scope == Scope.SESSION)
		{
			JPanel card = card("This session");
			card.add(row("Left behind", fmt(sess[0]) + " items · " + gp(sess[1]) + " gp", accent()));
			p.add(card);
			p.add(vgap(6));
		}
		List<LocalStore.UntakenRow> rows = plugin.untakenSources();
		rows.sort(Comparator.comparingLong((LocalStore.UntakenRow r) -> r.value).reversed());
		long totalQty = 0;
		long totalVal = 0;
		for (LocalStore.UntakenRow r : rows)
		{
			totalQty += r.qty;
			totalVal += r.value;
		}
		if (rows.isEmpty())
		{
			p.add(note("What you walk past gets counted here — priced at the "
				+ "moment you declined it. The record starts with this build."));
			return p;
		}
		JPanel head = card("Walked past, lifetime");
		head.add(row(fmt(totalQty) + " items", gp(totalVal) + " gp", ACCENT_RED));
		p.add(head);
		p.add(vgap(6));
		int shown = 0;
		for (LocalStore.UntakenRow r : rows)
		{
			if (shown++ >= ROW_CAP)
			{
				break;
			}
			p.add(row(r.name, fmt(r.qty) + " · " + gp(r.value) + " gp", ACCENT_RED));
		}
		return p;
	}

	/** The per-source drill: local bag as sprites; the cloud ledger's item
	 *  rows fetched on first expand when the bag holds less than the cloud. */
	private void addSourceDrill(JPanel card, LocalStore.SourceRow r)
	{
		card.add(vgap(5));
		List<LocalStore.BagItem> bag = plugin.sourceItems(r.name);
		bag.sort(Comparator.comparingLong((LocalStore.BagItem b) -> b.value).reversed());
		if (!bag.isEmpty())
		{
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			int mounted = 0;
			for (LocalStore.BagItem b : bag)
			{
				if (mounted++ >= 25)
				{
					break;
				}
				JLabel slot = new JLabel();
				slot.setPreferredSize(new Dimension(36, 32));
				slot.setHorizontalAlignment(JLabel.CENTER);
				slot.setToolTipText(b.name + (b.qty > 1 ? " ×" + fmt(b.qty) : "")
					+ " · " + gp(b.value) + " gp");
				if (b.itemId > 0)
				{
					AsyncBufferedImage img = plugin.items().getImage(b.itemId,
						(int) Math.min(Integer.MAX_VALUE, b.qty), b.qty > 1);
					img.addTo(slot);
				}
				grid.add(slot);
			}
			card.add(grid);
		}
		// Cache protocol: absent = never asked; null value = fetch in flight;
		// empty list = fetch failed; non-empty = the cloud ledger's rows.
		List<ChronicleApiClient.LedgerItem> cloud = cloudBagCache.get(r.name);
		if (cloud != null && !cloud.isEmpty())
		{
			card.add(vgap(4));
			int mounted = 0;
			for (ChronicleApiClient.LedgerItem it : cloud)
			{
				if (mounted++ >= 15)
				{
					break;
				}
				card.add(row(it.name + (it.qty > 1 ? " ×" + fmt(it.qty) : ""),
					gp(it.value) + " gp", null));
			}
			if (cloud.size() > 15)
			{
				card.add(note(fmt(cloud.size() - 15) + " more on your cloud ledger"));
			}
		}
		else if (r.cloudItems > bag.size() && plugin.cloudActive())
		{
			if (cloud != null)
			{
				card.add(note("Couldn't reach the cloud ledger — items the journal "
					+ "witnessed are shown above."));
			}
			else if (cloudBagCache.containsKey(r.name))
			{
				card.add(note("Fetching the full item list…"));
			}
			else
			{
				card.add(note("Fetching the full item list…"));
				cloudBagCache.put(r.name, null);
				final String src = r.name;
				plugin.fetchSourceItems(src, items ->
				{
					cloudBagCache.put(src, items != null ? items : java.util.Collections.emptyList());
					SwingUtilities.invokeLater(() ->
					{
						if (src.equals(expandedSource) && view == View.DROPS)
						{
							rebuild();
						}
					});
				});
			}
		}
		else if (bag.isEmpty())
		{
			card.add(note("Items fill in as you play — the journal prices each "
				+ "drop the moment it lands."));
		}
	}

	private JPanel buildLog()
	{
		JPanel p = column();
		int avail = Math.max(plugin.clogAvailable(), 1712);
		int fin = plugin.clogFinished();
		JPanel head = card("Collection log");
		if (fin > 0)
		{
			head.add(row(fmt(fin) + " / " + fmt(avail),
				Math.round(100f * fin / avail) + "%", accent()));
			head.add(progress((float) fin / avail));
		}
		else
		{
			head.add(row("Open your log in game once to fill this in", "", null));
		}
		p.add(head);
		p.add(vgap(6));

		Map<String, Map<String, List<String>>> tax = taxonomy();
		JPanel pills = new JPanel(new GridLayout(1, tax.size(), 3, 3));
		pills.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String tab : tax.keySet())
		{
			JLabel pill = new JLabel(tab, JLabel.CENTER);
			pill.setOpaque(true);
			pill.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			pill.setFont(FontManager.getRunescapeSmallFont());
			pill.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			pill.setForeground(tab.equals(clogTab) ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			pill.addMouseListener(clicker(() ->
			{
				clogTab = tab;
				clogPageSel = null;
				rebuild();
			}));
			pills.add(pill);
		}
		p.add(pills);
		p.add(vgap(6));

		// The journal's stored log, lower-cased once for the overlay.
		JsonObject cl = plugin.clogSnapshot();
		Map<String, Long> owned = new LinkedHashMap<>();
		if (cl.has("clog_items") && cl.get("clog_items").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: cl.getAsJsonObject("clog_items").entrySet())
			{
				owned.merge(e.getKey().toLowerCase(Locale.ROOT), safeLong(e.getValue()), Math::max);
			}
		}
		Map<String, Map<String, Long>> byCat = new LinkedHashMap<>();
		if (cl.has("by_cat") && cl.get("by_cat").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> pg
				: cl.getAsJsonObject("by_cat").entrySet())
			{
				if (!pg.getValue().isJsonObject())
				{
					continue;
				}
				Map<String, Long> items = new LinkedHashMap<>();
				for (Map.Entry<String, com.google.gson.JsonElement> it
					: pg.getValue().getAsJsonObject().entrySet())
				{
					items.merge(it.getKey().toLowerCase(Locale.ROOT), safeLong(it.getValue()), Math::max);
				}
				byCat.put(pg.getKey().toLowerCase(Locale.ROOT), items);
			}
		}
		Map<String, Long> kcs = new LinkedHashMap<>();
		if (cl.has("kcs") && cl.get("kcs").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: cl.getAsJsonObject("kcs").entrySet())
			{
				kcs.merge(e.getKey().toLowerCase(Locale.ROOT), safeLong(e.getValue()), Math::max);
			}
		}

		Map<String, List<String>> pages = tax.getOrDefault(clogTab, new LinkedHashMap<>());
		for (Map.Entry<String, List<String>> pg : pages.entrySet())
		{
			String page = pg.getKey();
			List<String> slots = pg.getValue();
			boolean[] lit = lightSlots(slots, byCat.get(page.toLowerCase(Locale.ROOT)), owned);
			int got = 0;
			for (boolean b : lit)
			{
				got += b ? 1 : 0;
			}
			Long kc = kcs.get(page.toLowerCase(Locale.ROOT));
			boolean open = page.equals(clogPageSel);
			JPanel rowP = row(page, got + "/" + slots.size()
				+ (kc != null && kc > 0 ? " · " + fmt(kc) + " kc" : ""),
				got == slots.size() && !slots.isEmpty() ? ACCENT_SESSION : null);
			rowP.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			rowP.addMouseListener(clicker(() ->
			{
				clogPageSel = open ? null : page;
				rebuild();
			}));
			p.add(rowP);
			if (open)
			{
				JPanel drill = cardPlain();
				for (int i = 0; i < slots.size(); i++)
				{
					// the in-game log's own idiom: green owned, red missing
					drill.add(row(slots.get(i), "",
						lit[i] ? ACCENT_SESSION : ACCENT_RED, true));
				}
				p.add(drill);
				p.add(vgap(3));
			}
		}
		return p;
	}

	/**
	 * Which slots of a page the player holds: a slot lights when its name is in
	 * the page's own capture or the whole-log obtained set. Duplicate-named
	 * slots (My Notes' 26 "Ancient page" entries) light positionally — k copies
	 * lights the first k — matching the game and the site.
	 */
	private static boolean[] lightSlots(List<String> slots, Map<String, Long> pageItems,
		Map<String, Long> owned)
	{
		boolean[] lit = new boolean[slots.size()];
		Map<String, Integer> dupes = new LinkedHashMap<>();
		for (String slot : slots)
		{
			dupes.merge(slot.toLowerCase(Locale.ROOT), 1, Integer::sum);
		}
		Map<String, Integer> seen = new LinkedHashMap<>();
		for (int i = 0; i < slots.size(); i++)
		{
			String key = slots.get(i).toLowerCase(Locale.ROOT);
			long have = Math.max(pageItems != null ? pageItems.getOrDefault(key, 0L) : 0L,
				owned.getOrDefault(key, 0L));
			if (dupes.get(key) > 1)
			{
				int idx = seen.merge(key, 1, Integer::sum) - 1;
				lit[i] = idx < have;
			}
			else
			{
				lit[i] = have > 0
					|| (pageItems != null && pageItems.containsKey(key))
					|| owned.containsKey(key);
			}
		}
		return lit;
	}

	private static long safeLong(com.google.gson.JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}

	/** Parse the bundled taxonomy once; order preserved (tabs and slots). */
	private static synchronized Map<String, Map<String, List<String>>> taxonomy()
	{
		if (taxonomy != null)
		{
			return taxonomy;
		}
		Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
		try (java.io.InputStream in = ChroniclePanel.class.getResourceAsStream("clog_taxonomy.json"))
		{
			if (in != null)
			{
				JsonObject rootTax = new com.google.gson.Gson().fromJson(
					new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
					JsonObject.class);
				for (Map.Entry<String, com.google.gson.JsonElement> tab : rootTax.entrySet())
				{
					Map<String, List<String>> pages = new LinkedHashMap<>();
					for (Map.Entry<String, com.google.gson.JsonElement> pg
						: tab.getValue().getAsJsonObject().entrySet())
					{
						List<String> slots = new ArrayList<>();
						for (com.google.gson.JsonElement it : pg.getValue().getAsJsonArray())
						{
							slots.add(it.getAsString());
						}
						pages.put(pg.getKey(), slots);
					}
					out.put(tab.getKey(), pages);
				}
			}
		}
		catch (Exception e)
		{
			// A missing/corrupt resource leaves an empty browser, not a crash.
		}
		taxonomy = out;
		return out;
	}

	private JPanel buildStats()
	{
		JPanel p = column();
		JPanel pills = new JPanel(new GridLayout(0, 4, 3, 3));
		pills.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String fam : StatRegistry.FAMILIES)
		{
			JLabel pill = new JLabel(fam, JLabel.CENTER);
			pill.setOpaque(true);
			pill.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
			pill.setFont(FontManager.getRunescapeSmallFont());
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
		p.add(pills);
		p.add(vgap(4));

		Map<String, Long> counters = counters();
		// Cluster the family's rows under sub-headers (the craft, the method)
		// instead of one value-sorted free-for-all; sub-groups rank by their
		// totals, rows rank within their group.
		Map<String, List<Map.Entry<String, Long>>> groups = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() != 0 && StatRegistry.family(e.getKey()).equals(statsFamily))
			{
				groups.computeIfAbsent(StatRegistry.subgroup(e.getKey()), k -> new ArrayList<>()).add(e);
			}
		}
		if (groups.isEmpty())
		{
			p.add(note(scope == Scope.SESSION
				? "Nothing in this family yet this session."
				: "Nothing tracked in this family yet."));
			return p;
		}
		List<Map.Entry<String, List<Map.Entry<String, Long>>>> ordered = new ArrayList<>(groups.entrySet());
		ordered.sort(Comparator.comparingLong((Map.Entry<String, List<Map.Entry<String, Long>>> g)
			-> g.getValue().stream().mapToLong(Map.Entry::getValue).max().orElse(0)).reversed());
		int shown = 0;
		outer:
		for (Map.Entry<String, List<Map.Entry<String, Long>>> g : ordered)
		{
			if (!g.getKey().isEmpty() && ordered.size() > 1)
			{
				p.add(group(g.getKey()));
			}
			g.getValue().sort(Map.Entry.<String, Long>comparingByValue().reversed());
			for (Map.Entry<String, Long> e : g.getValue())
			{
				if (shown++ >= ROW_CAP * 2)
				{
					p.add(note("more — search to find one"));
					break outer;
				}
				String v = StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
				p.add(row(StatRegistry.label(e.getKey()), v, null));
			}
		}
		return p;
	}

	private JPanel buildHistory()
	{
		JPanel p = column();
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> hist = plugin.historyBaselines();

		// granularity pills
		JPanel pills = new JPanel(new GridLayout(1, 4, 3, 3));
		pills.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String g : new String[]{"Day", "Week", "Month", "Year"})
		{
			JLabel pill = new JLabel(g, JLabel.CENTER);
			pill.setOpaque(true);
			pill.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			pill.setFont(FontManager.getRunescapeSmallFont());
			pill.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			pill.setForeground(g.equals(histGranularity) ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			pill.addMouseListener(clicker(() ->
			{
				histGranularity = g;
				rebuild();
			}));
			pills.add(pill);
		}
		p.add(pills);
		p.add(vgap(5));

		// the period under the cursor
		java.time.LocalDate end = histCursor;
		java.time.LocalDate start;
		String label;
		switch (histGranularity)
		{
			case "Day":
				start = end;
				label = end.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
				break;
			case "Month":
				start = end.withDayOfMonth(1);
				end = start.plusMonths(1).minusDays(1);
				label = start.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));
				break;
			case "Year":
				start = end.withDayOfYear(1);
				end = start.plusYears(1).minusDays(1);
				label = String.valueOf(start.getYear());
				break;
			case "Week":
			default:
				start = end.minusDays(6);
				label = start.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
					+ " - " + end.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
				break;
		}
		final java.time.LocalDate pStart = start;

		JPanel stepper = new JPanel(new BorderLayout());
		stepper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		stepper.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
		JLabel back = new JLabel("<");
		JLabel fwd = new JLabel(">");
		for (JLabel arrow : new JLabel[]{back, fwd})
		{
			arrow.setForeground(accent());
			arrow.setFont(FontManager.getRunescapeBoldFont());
			arrow.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			arrow.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
		}
		back.addMouseListener(clicker(() ->
		{
			histCursor = stepBack(histCursor);
			rebuild();
		}));
		fwd.addMouseListener(clicker(() ->
		{
			java.time.LocalDate next = stepForward(histCursor);
			histCursor = next.isAfter(java.time.LocalDate.now()) ? java.time.LocalDate.now() : next;
			rebuild();
		}));
		JLabel lbl = new JLabel(label, JLabel.CENTER);
		lbl.setFont(FontManager.getRunescapeFont());
		stepper.add(back, BorderLayout.WEST);
		stepper.add(lbl, BorderLayout.CENTER);
		stepper.add(fwd, BorderLayout.EAST);
		p.add(stepper);
		p.add(vgap(6));

		// baselines bounding the period: closing state the day before it began,
		// and the last close inside it
		Map.Entry<java.time.LocalDate, HistoryLog.Baseline> before =
			hist.floorEntry(pStart.minusDays(1));
		Map.Entry<java.time.LocalDate, HistoryLog.Baseline> at = hist.floorEntry(end);
		if (at == null || (before != null && at.getKey().equals(before.getKey())))
		{
			p.add(note(hist.isEmpty()
				? "The record starts today — baselines close at each login, day "
				+ "rollover and logout. Import your deeper past below."
				: "Nothing recorded in this period."));
		}
		else
		{
			Map<String, Long> beforeSk = before != null ? before.getValue().skills
				: new LinkedHashMap<>();
			List<Map.Entry<String, Long>> gains = new ArrayList<>();
			for (Map.Entry<String, Long> e : at.getValue().skills.entrySet())
			{
				if ("overall".equals(e.getKey()))
				{
					continue;
				}
				long d = e.getValue() - beforeSk.getOrDefault(e.getKey(), before == null ? e.getValue() : 0L);
				if (before == null)
				{
					d = 0;   // a single baseline has no delta story
				}
				if (d > 0)
				{
					gains.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), d));
				}
			}
			gains.sort(Map.Entry.<String, Long>comparingByValue().reversed());
			if (!gains.isEmpty())
			{
				JPanel card = card("XP gained");
				JPanel grid = new JPanel(new GridLayout(0, 2, 3, 3));
				grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				int mounted = 0;
				for (Map.Entry<String, Long> g : gains)
				{
					if (mounted++ >= 8)
					{
						break;
					}
					JPanel cell = new JPanel(new BorderLayout());
					cell.setOpaque(false);
					JLabel nm = new JLabel(StatRegistry.prettify(g.getKey()));
					nm.setFont(FontManager.getRunescapeSmallFont());
					nm.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
					JLabel xp = new JLabel("+" + gp(g.getValue()));
					xp.setFont(FontManager.getRunescapeFont());
					xp.setForeground(ACCENT_SESSION);
					cell.add(nm, BorderLayout.NORTH);
					cell.add(xp, BorderLayout.CENTER);
					grid.add(cell);
				}
				card.add(grid);
				p.add(card);
				p.add(vgap(5));
			}

			Map<String, Long> beforeCt = before != null ? before.getValue().counters
				: new LinkedHashMap<>();
			List<Map.Entry<String, Long>> movers = new ArrayList<>();
			if (before != null)
			{
				for (Map.Entry<String, Long> e : at.getValue().counters.entrySet())
				{
					long d = e.getValue() - beforeCt.getOrDefault(e.getKey(), 0L);
					if (d > 0 && !LocalStore.MAX_KEYS.contains(e.getKey()))
					{
						movers.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), d));
					}
				}
			}
			movers.sort(Map.Entry.<String, Long>comparingByValue().reversed());
			if (!movers.isEmpty())
			{
				JPanel card = card("The period's movers");
				int mounted = 0;
				for (Map.Entry<String, Long> m : movers)
				{
					if (mounted++ >= 8)
					{
						break;
					}
					String v = "+" + (StatRegistry.isGp(m.getKey())
						? gp(m.getValue()) + " gp" : fmt(m.getValue()));
					card.add(row(StatRegistry.label(m.getKey()), v, null));
				}
				p.add(card);
				p.add(vgap(5));
			}

			// milestones inside the window
			long fromMs = pStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			long toMs = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			List<JsonObject> milestones = new ArrayList<>();
			for (JsonObject e : plugin.feedNewest(2000))
			{
				long ts = e.has("ts") ? e.get("ts").getAsLong() : 0;
				if (ts >= fromMs && ts < toMs)
				{
					milestones.add(e);
				}
			}
			if (!milestones.isEmpty())
			{
				JPanel card = card("Milestones · " + fmt(milestones.size()));
				int mounted = 0;
				for (JsonObject e : milestones)
				{
					if (mounted++ >= 6)
					{
						break;
					}
					long ts = e.has("ts") ? e.get("ts").getAsLong() : 0;
					card.add(row(feedLine(e), ts > 0 ? DAY.format(Instant.ofEpochMilli(ts)) : "", null));
				}
				p.add(card);
				p.add(vgap(5));
			}
		}

		if (!plugin.womImported())
		{
			JButton importBtn = new JButton(womImportRunning
				? "Importing…" : "Import your past — Wise Old Man");
			importBtn.setEnabled(!womImportRunning);
			importBtn.addActionListener(e ->
			{
				int ok = JOptionPane.showConfirmDialog(this,
					"Fetch your public Wise Old Man snapshots (one request, one time)\n"
						+ "and write them into your local history?",
					"Import your past", JOptionPane.OK_CANCEL_OPTION);
				if (ok == JOptionPane.OK_OPTION)
				{
					womImportRunning = true;
					rebuild();
					plugin.actionImportWom(() -> SwingUtilities.invokeLater(() ->
					{
						womImportRunning = false;
						rebuild();
					}));
				}
			});
			p.add(importBtn);
		}
		return p;
	}

	private java.time.LocalDate stepBack(java.time.LocalDate d)
	{
		switch (histGranularity)
		{
			case "Day":
				return d.minusDays(1);
			case "Month":
				return d.withDayOfMonth(1).minusDays(1);
			case "Year":
				return d.withDayOfYear(1).minusDays(1);
			case "Week":
			default:
				return d.minusDays(7);
		}
	}

	private java.time.LocalDate stepForward(java.time.LocalDate d)
	{
		switch (histGranularity)
		{
			case "Day":
				return d.plusDays(1);
			case "Month":
				return d.withDayOfMonth(1).plusMonths(1).plusMonths(1).minusDays(1);
			case "Year":
				return d.withDayOfYear(1).plusYears(2).minusDays(1);
			case "Week":
			default:
				return d.plusDays(7);
		}
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
				g.setFont(FontManager.getRunescapeSmallFont());
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
		t.setFont(FontManager.getRunescapeBoldFont());
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

		// Drop items — every bag the journal holds, by item name.
		List<String[]> itemHits = new ArrayList<>();
		outerItems:
		for (LocalStore.SourceRow src : plugin.dropSources())
		{
			for (LocalStore.BagItem b : plugin.sourceItems(src.name))
			{
				if (b.name.toLowerCase(Locale.ROOT).contains(ql))
				{
					itemHits.add(new String[]{b.name + (b.qty > 1 ? " ×" + fmt(b.qty) : ""), src.name});
					if (itemHits.size() >= 4)
					{
						break outerItems;
					}
				}
			}
		}
		if (!itemHits.isEmpty())
		{
			p.add(group("Drop items"));
			for (String[] hit : itemHits)
			{
				p.add(row(hit[0], hit[1], null));
				total++;
			}
		}

		// Collection log — the whole taxonomy, with your obtained state.
		JsonObject cl = plugin.clogSnapshot();
		Map<String, Long> owned = new LinkedHashMap<>();
		if (cl.has("clog_items") && cl.get("clog_items").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: cl.getAsJsonObject("clog_items").entrySet())
			{
				owned.merge(e.getKey().toLowerCase(Locale.ROOT), safeLong(e.getValue()), Math::max);
			}
		}
		int slotHits = 0;
		clogSearch:
		for (Map.Entry<String, Map<String, List<String>>> tab : taxonomy().entrySet())
		{
			for (Map.Entry<String, List<String>> pg : tab.getValue().entrySet())
			{
				for (String slot : pg.getValue())
				{
					if (slot.toLowerCase(Locale.ROOT).contains(ql))
					{
						if (slotHits == 0)
						{
							p.add(group("Collection log"));
						}
						boolean got = owned.containsKey(slot.toLowerCase(Locale.ROOT));
						p.add(row(slot, got ? "obtained" : pg.getKey(),
							got ? ACCENT_SESSION : null));
						total++;
						if (++slotHits >= 4)
						{
							break clogSearch;
						}
					}
				}
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
		l.setFont(FontManager.getRunescapeSmallFont());
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
		// Scrollable that tracks the viewport width: long labels and html notes
		// can never widen the view past the panel — labels ellipsise, html
		// wraps — while height stays free for vertical scrolling.
		JPanel wrap = new ScrollColumn();
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.add(body, BorderLayout.NORTH);
		return wrap;
	}

	private static final class ScrollColumn extends JPanel implements javax.swing.Scrollable
	{
		private ScrollColumn()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d)
		{
			return 80;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private static JPanel card(String caption)
	{
		JPanel c = cardPlain();
		JLabel cap = new JLabel(caption.toUpperCase(Locale.ROOT));
		cap.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		cap.setFont(FontManager.getRunescapeSmallFont());
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

	private static JPanel row(String left, String right, Color color, boolean colorName)
	{
		JPanel r = row(left, right, color);
		if (colorName && color != null)
		{
			((JLabel) ((BorderLayout) r.getLayout())
				.getLayoutComponent(BorderLayout.CENTER)).setForeground(color);
		}
		return r;
	}

	private static JPanel row(String left, String right, Color rightColor)
	{
		JPanel r = new JPanel(new BorderLayout(8, 0));
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
		JLabel l = new JLabel(left);
		l.setFont(FontManager.getRunescapeFont());
		r.add(l, BorderLayout.CENTER);
		if (right != null && !right.isEmpty())
		{
			JLabel v = new JLabel(right);
			v.setFont(FontManager.getRunescapeFont());
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
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setAlignmentX(Component.LEFT_ALIGNMENT);
		g.setBorder(BorderFactory.createEmptyBorder(8, 2, 3, 0));
		return g;
	}

	private static JLabel note(String text)
	{
		// The fixed width inside the html is what makes the label REPORT its
		// wrapped height — a bare html JLabel measures single-line and the
		// last lines clip when the layout squeezes it to panel width.
		JLabel n = new JLabel("<html><div style='width:190px'><i>"
			+ escape(text) + "</i></div></html>");
		n.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		n.setFont(FontManager.getRunescapeSmallFont());
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
