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
import javax.swing.SwingWorker;
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
 * and seven tabs — Home is the SESSION view (adaptive: cards earn their
 * place by this session's data), everything else reads the lifetime journal.
 *
 * <p>Search answers inline from anywhere; the tabs are the browsing spine; the
 * accent colour carries the state (orange = the journal, green = the live
 * session on Home). Every list mounts a bounded
 * number of rows; views rebuild on tab switch and (Home only)
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
	private static final long XP_99 = 13_034_431L;

	private enum View
	{
		HOME, DROPS, SLAYER, LOG, STATS, HISTORY, JOURNAL
	}

	private final ChroniclePlugin plugin;

	private final JPanel display = new JPanel(new BorderLayout());
	// No display panel handed to the group: view swapping is ours (rebuild()),
	// driven from onSelectEvent — handing it `display` makes the group swap in
	// each tab's content component itself, which NPEs on our contentless tabs.
	private final MaterialTabGroup tabGroup = new MaterialTabGroup();
	private final Map<View, MaterialTab> tabByView = new java.util.EnumMap<>(View.class);
	private final IconTextField searchField = new IconTextField();
	private final Timer searchDebounce;
	private final Timer homeTicker;

	private View view = View.HOME;
	// The pivot navigation: an item or a source under the glass, overlaying
	// the current tab. Click any item row anywhere → the item's view (total
	// obtained + every source of it); click any source row → the source's
	// view (kills tracked + everything it dropped). A small back-stack lets
	// item→source→item hops unwind.
	private String detailItem;
	private String detailSource;
	private final java.util.ArrayDeque<String[]> detailStack = new java.util.ArrayDeque<>();
	private String statsFamily = StatRegistry.FAMILIES[0];
	private int dropsShown = ROW_CAP;
	private String clogTab = "Bosses";
	private String clogPageSel;
	private String histGranularity = "Week";
	// The period's END date (inclusive); the stepper moves it by one granule.
	private java.time.LocalDate histCursor = java.time.LocalDate.now();
	// Exact-dates mode (the site's any-two-dates gains): non-null overrides the
	// granularity pills; set by clicking the period label, cleared by any pill.
	private java.time.LocalDate histFrom;
	private java.time.LocalDate histTo;
	// The bundled 1,921-slot taxonomy: tab -> page -> ordered slot names.
	// Parsed once on first Log open (~40KB).
	private static Map<String, Map<String, List<String>>> taxonomy;
	// Cloud item lists already fetched this session, keyed by source — the
	// drill fetches each source at most once.

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

		// ── search ────────────────────────────────────────────────────────
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 16, 28));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchDebounce = new Timer(150, e -> onSearchChanged());
		searchDebounce.setRepeats(false);
		// Enter answers the question directly: "Fire rune" opens the item's
		// view, "Dust devil" the source's — resolved against the journal.
		// Anything less certain falls back to the first result group's tab.
		searchField.addActionListener(e ->
		{
			String q = searchQuery();
			if (q.isEmpty())
			{
				return;
			}
			// exact (or singular) source name wins
			for (LocalStore.SourceRow r : plugin.dropSources())
			{
				if (r.name.equalsIgnoreCase(q)
					|| (q.endsWith("s") && r.name.equalsIgnoreCase(q.substring(0, q.length() - 1))))
				{
					openSource(r.name);
					return;
				}
			}
			// exact item name
			for (LocalStore.SourceRow r : plugin.dropSources())
			{
				for (LocalStore.BagItem b : plugin.sourceItems(r.name))
				{
					if (b.name.equalsIgnoreCase(q))
					{
						openItem(b.name);
						return;
					}
				}
			}
			// best containing item, then containing source
			String bestItem = null;
			long bestVal = -1;
			String ql = q.toLowerCase(Locale.ROOT);
			for (LocalStore.SourceRow r : plugin.dropSources())
			{
				for (LocalStore.BagItem b : plugin.sourceItems(r.name))
				{
					if (b.name.toLowerCase(Locale.ROOT).contains(ql) && b.value > bestVal)
					{
						bestVal = b.value;
						bestItem = b.name;
					}
				}
			}
			if (bestItem != null)
			{
				openItem(bestItem);
				return;
			}
			for (LocalStore.SourceRow r : plugin.dropSources())
			{
				if (r.name.toLowerCase(Locale.ROOT).contains(ql))
				{
					openSource(r.name);
					return;
				}
			}
			MaterialTab target = searchJump != null ? tabByView.get(searchJump) : null;
			if (target != null)
			{
				tabGroup.select(target);
			}
		});
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
		// ── tabs first (the mock's order), then search ──
		tabGroup.setLayout(new GridLayout(1, 7, 2, 0));
		addTab("tab_home.png", "Home", View.HOME);
		addTab("tab_drops.png", "Drops", View.DROPS);
		addTab("tab_slayer.png", "Slayer", View.SLAYER);
		addTab("tab_log.png", "Collection log", View.LOG);
		addTab("tab_stats.png", "Stats", View.STATS);
		addTab("tab_history.png", "History", View.HISTORY);
		addTab("tab_journal.png", "Journal", View.JOURNAL);
		north.add(tabGroup);
		north.add(vgap(7));
		north.add(searchField);
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

		// The History tab's reads are primed now, off the EDT, rather than on
		// the click that opens it.
		gatherHistory();
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
			dropsShown = ROW_CAP;
			slayerShown = ROW_CAP;
			drillShown.clear();
			detailItem = null;
			detailSource = null;
			detailStack.clear();
			searchField.setText("");
			rebuild();
			return true;
		});
		tabGroup.addTab(tab);
		tabByView.put(target, tab);
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
		return ACCENT_LIFETIME;
	}

	private String searchQuery()
	{
		return searchField.getText() == null ? "" : searchField.getText().trim();
	}

	private Map<String, Long> counters()
	{
		return plugin.lifetimeCounters();
	}

	/** Refresh from plugin state — safe from any thread; rebuilds the view. */
	void update()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	private void rebuild()
	{
		display.removeAll();
		JPanel body;
		if (!searchQuery().isEmpty())
		{
			body = buildSearch(searchQuery());
		}
		else if (detailItem != null)
		{
			body = buildItemDetail(detailItem);
		}
		else if (detailSource != null)
		{
			body = buildSourceDetail(detailSource);
		}
		else
		{
			switch (view)
			{
				case DROPS:
					body = buildDrops();
					break;
				case SLAYER:
					body = buildSlayer();
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
		// AS_NEEDED is safe now: row heights are width-independent (labels
		// ellipsise, notes wrap at a fixed width), so the scrollbar appearing
		// can't change content height and oscillate — the html-note era's
		// re-wrap jitter that once forced ALWAYS is gone.
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

	// Session counters the adaptive strip pins first, in reading order —
	// everything else the session stirred follows, ranked.
	private static final String[] HOME_PINNED = {
		"totalXpGained", "damageDealt", "consumedValue"
	};

	private static String homeLabel(String key)
	{
		switch (key)
		{
			case "totalXpGained":
				return "Xp gained";
			case "consumedValue":
				return "Consumed";
			default:
				return StatRegistry.label(key);
		}
	}

	/** A small filled circle used as a status pip. */
	private static javax.swing.Icon dot(Color c)
	{
		return new javax.swing.Icon()
		{
			@Override
			public void paintIcon(Component host, java.awt.Graphics g, int x, int y)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(c);
				g2.fillOval(x, y, 6, 6);
				g2.dispose();
			}

			@Override
			public int getIconWidth()
			{
				return 6;
			}

			@Override
			public int getIconHeight()
			{
				return 6;
			}
		};
	}

	private JPanel buildHome()
	{
		JPanel p = column();
		// The heartbeat, alone: a lit dot and a plain word. This is an
		// adventurer's log, not a diary — the word says what it does.
		JPanel hdr = new JPanel(new BorderLayout());
		hdr.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Everything below this line is served from memory, so a journal that has
		// stopped reaching disk looks exactly as alive as one that hasn't. The
		// heartbeat is the one place that can say otherwise.
		String stalled = plugin.journalWarning();
		Color pulse = stalled == null ? ACCENT_SESSION : ColorScheme.PROGRESS_ERROR_COLOR;
		JLabel state = new JLabel(stalled == null ? "logging" : "not saving");
		// The dot is PAINTED, not typed: the RuneScape font has no bullet glyph
		// and renders one as a tofu box.
		state.setIcon(dot(pulse));
		state.setIconTextGap(4);
		state.setForeground(pulse);
		state.setFont(FontManager.getRunescapeSmallFont());
		hdr.add(state, BorderLayout.EAST);
		hdr.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		p.add(hdr);
		p.add(vgap(4));
		if (stalled != null)
		{
			p.add(note(stalled));
			p.add(vgap(6));
		}

		// The slayer card earns its place: only when this session actually
		// produced an on-task kill — a skiller never sees it.
		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null && plugin.slayerSeenThisSession())
		{
			JPanel card = card("Slayer task");
			card.add(row(task.task, task.remaining + " left", ACCENT_SESSION));
			if (task.initial > 0)
			{
				card.add(progress(1f - (float) task.remaining / task.initial));
			}
			p.add(card);
			p.add(vgap(6));
		}

		// The adaptive strip: whatever this session stirred, and nothing
		// else. Pinned marquee rows first, then the rest ranked by size.
		JPanel strip = card("This session");
		Map<String, Integer> sess = plugin.sessionCounters();
		int mounted = 0;
		java.util.Set<String> shownKeys = new java.util.HashSet<>();
		for (String key : HOME_PINNED)
		{
			long v = sess.getOrDefault(key, 0);
			if (v > 0)
			{
				strip.add(row(homeLabel(key),
					StatRegistry.isGp(key) ? gp(v) + " gp"
						: ("totalXpGained".equals(key) ? "+" + gp(v) : fmt(v)),
					ACCENT_SESSION));
				shownKeys.add(key);
				mounted++;
			}
		}
		if (plugin.sessionLoots() > 0)
		{
			strip.add(row("Drops taken",
				plugin.sessionLoots() + " · " + gp(plugin.sessionLootValue()) + " gp",
				ACCENT_SESSION));
			mounted++;
		}
		long[] untaken = plugin.sessionUntakenTally();
		if (untaken[0] > 0)
		{
			strip.add(row("Left behind", fmt(untaken[0]) + " · " + gp(untaken[1]) + " gp", null));
			mounted++;
		}
		List<Map.Entry<String, Integer>> movers = new ArrayList<>();
		for (Map.Entry<String, Integer> e : plugin.sessionDisplayCounters().entrySet())
		{
			if (e.getValue() > 0 && !shownKeys.contains(e.getKey())
				&& !StatRegistry.hidden(e.getKey())
				&& !StatRegistry.isFloor(e.getKey()))
			{
				movers.add(e);
			}
		}
		movers.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
		int moverCap = Math.max(0, 12 - mounted);
		for (int i = 0; i < Math.min(moverCap, movers.size()); i++)
		{
			Map.Entry<String, Integer> e = movers.get(i);
			long v = e.getValue();
			strip.add(row(StatRegistry.label(e.getKey()),
				StatRegistry.isGp(e.getKey()) ? gp(v) + " gp" : fmt(v), null));
			mounted++;
		}
		if (movers.size() > moverCap)
		{
			strip.add(ghostRow("…and " + fmt(movers.size() - moverCap) + " more stirred", ""));
		}
		if (mounted == 0)
		{
			strip.add(row("A fresh page", "", null));
		}
		p.add(strip);
		p.add(vgap(6));

		List<LocalStore.RecentDrop> recent = plugin.recentDrops();
		if (!recent.isEmpty())
		{
			JPanel card = card("Recent drops");
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			// BoxLayout drifts mixed alignments: a CENTER-aligned grid beside
			// the LEFT-aligned caption pushed the caption to the right.
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
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
				slot.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String itm = d.name;
				slot.addMouseListener(clicker(() -> openItem(itm)));
				AsyncBufferedImage img = plugin.items().getImage(d.itemId, d.quantity, d.quantity > 1);
				img.addTo(slot);
				grid.add(slot);
			}
			card.add(grid);
			p.add(card);
			p.add(vgap(6));
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
			final String src = r.name;
			card.addMouseListener(clicker(() -> openSource(src)));
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
				+ "moment you declined it."));
			return p;
		}
		JPanel head = card("Walked past, lifetime");
		head.add(row(fmt(totalQty) + " items", gp(totalVal) + " gp", ACCENT_RED));
		p.add(head);
		p.add(vgap(6));
		p.add(group("By source"));
		int shown = 0;
		for (LocalStore.UntakenRow r : rows)
		{
			if (shown++ >= ROW_CAP)
			{
				break;
			}
			JPanel sr = row(r.name, fmt(r.qty) + " · " + gp(r.value) + " gp", ACCENT_RED);
			sr.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String src = r.name;
			sr.addMouseListener(clicker(() -> openSource(src)));
			p.add(sr);
		}
		List<LocalStore.UntakenRow> items = plugin.untakenItems();
		if (!items.isEmpty())
		{
			items.sort(Comparator.comparingLong((LocalStore.UntakenRow r) -> r.value).reversed());
			p.add(group("By item"));
			int mounted = 0;
			for (LocalStore.UntakenRow r : items)
			{
				if (mounted++ >= ROW_CAP)
				{
					p.add(ghostRow("+ " + fmt(items.size() - ROW_CAP) + " more items", ""));
					break;
				}
				JPanel ir = row(r.name, "×" + fmt(r.qty) + " · " + gp(r.value) + " gp", ACCENT_RED);
				ir.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String itm = r.name;
				ir.addMouseListener(clicker(() -> openItem(itm)));
				p.add(ir);
			}
		}
		return p;
	}

	// The dryness ledger, fetched once per session on first source open.
	private List<ChronicleApiClient.GrindRow> grindsCache;
	private boolean grindsFetching;

	// The journey fetches once per session on first open; null = not yet asked.
	private ChronicleApiClient.SlayerJourney journeyCache;
	private boolean journeyFetching;

	/**
	 * Drop every view built from ONE account's journal. Called when a different
	 * account's journal is mounted — without it the next player is shown the
	 * previous one's task journey and dry streaks. EDT only.
	 */
	void resetAccountCaches()
	{
		journeyCache = null;
		journeyFetching = false;
		grindsCache = null;
		grindsFetching = false;
		historySpine = null;
		historyFeed = new ArrayList<>();
		historyDay = null;
		historyFeedTs = 0;
		// Any pass reading the previous journal is disowned rather than waited
		// for, so the new one starts reading at once.
		historyEpoch++;
		historyGathering = false;
		detailItem = null;
		detailSource = null;
		detailStack.clear();
		drillShown.clear();
		statsExpanded.clear();
		// A journal has just mounted and the panel is otherwise idle: the best
		// moment to read the spine is before anyone asks for it.
		gatherHistory();
		rebuild();
	}

	/** Stop the repeating timers. Called from the plugin's shutDown. EDT-safe. */
	void shutdown()
	{
		homeTicker.stop();
		searchDebounce.stop();
	}
	private int slayerShown = ROW_CAP;
	private static final DateTimeFormatter TASK_DAY =
		DateTimeFormatter.ofPattern("d MMM yy").withZone(ZoneId.systemDefault());

	/** The Slayer chapter: current task, the cloud's task-by-task journey,
	 *  and the Kill Log the journal scraped from the in-game widget. */
	private JPanel buildSlayer()
	{
		JPanel p = column();
		ChronicleEventCapture.SlayerView task = plugin.slayerView();
		if (task != null)
		{
			JPanel card = card("Current task");
			card.add(row(task.task, task.remaining + " left", accent()));
			if (task.initial > 0)
			{
				card.add(progress(1f - (float) task.remaining / task.initial));
			}
			p.add(card);
			p.add(vgap(6));
		}

		if (journeyCache != null)
		{
			addJourney(p, journeyCache);
		}
		else if (journeyFetching)
		{
			p.add(note("Reading the task journey from the journal…"));
		}
		else
		{
			journeyFetching = true;
			plugin.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
			{
				journeyFetching = false;
				// null means the journal was not mounted yet — do NOT cache it
				// (that froze the tab on its empty state for the rest of the
				// client run) and do NOT rebuild, which would spin fetch→null→
				// rebuild→fetch. The next natural rebuild retries. A READY store
				// returns a real (possibly empty) journey, which caches.
				if (j == null)
				{
					return;
				}
				journeyCache = j;
				if (view == View.SLAYER)
				{
					rebuild();
				}
			}));
			p.add(note("Reading the task journey from the journal…"));
		}
		p.add(vgap(6));

		// The Kill Log, as last scraped from the in-game widget.
		JsonObject cl = plugin.clogSnapshot();
		if (cl.has("slayer_kcs") && cl.get("slayer_kcs").isJsonObject())
		{
			List<Map.Entry<String, Long>> kcs = new ArrayList<>();
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: cl.getAsJsonObject("slayer_kcs").entrySet())
			{
				long v = safeLong(e.getValue());
				if (v > 0)
				{
					kcs.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), v));
				}
			}
			if (!kcs.isEmpty())
			{
				kcs.sort(Map.Entry.<String, Long>comparingByValue().reversed());
				JPanel card = card("Kill log");
				int mounted = 0;
				for (Map.Entry<String, Long> e : kcs)
				{
					if (mounted++ >= 20)
					{
						break;
					}
					JPanel r = row(e.getKey(), fmt(e.getValue()), null);
					r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
					final String mob = e.getKey();
					r.addMouseListener(clicker(() -> openSourceLoose(mob)));
					card.add(r);
				}
				if (kcs.size() > 20)
				{
					card.add(ghostRow("and " + fmt(kcs.size() - 20) + " more — search finds them", ""));
				}
				p.add(card);
			}
		}
		return p;
	}

	private void addJourney(JPanel p, ChronicleApiClient.SlayerJourney j)
	{
		if (j.tasks.isEmpty() && j.completedTasks == 0)
		{
			p.add(note("No tasks in the journal yet — they collect as "
				+ "you play with the Slayer plugin on."));
			return;
		}
		JPanel head = card("The journey");
		head.add(row("Tasks done", fmt(j.completedTasks), accent()));
		head.add(row("Kills on task", fmt(j.totalKills), null));
		head.add(row("On-task loot", gp(j.totalValueGp) + " gp", null));
		head.add(row("Slayer xp (est.)", gp(j.totalXpEst), null));
		p.add(head);
		p.add(vgap(6));
		int mounted = 0;
		for (ChronicleApiClient.SlayerTask t : j.tasks)
		{
			if (mounted++ >= slayerShown)
			{
				break;
			}
			JPanel card = cardPlain();
			// In-progress is a colour cue (the accent lights the NAME too),
			// not a suffix — text ate the card's width. The card is a doorway:
			// the task's monster view holds everything it ever dropped.
			card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String taskName = t.task;
			card.addMouseListener(clicker(() -> openSourceLoose(taskName)));
			card.add(row(t.task, t.totalValue > 0 ? gp(t.totalValue) + " gp" : "",
				accent(), t.inProgress));
			String kills = t.inProgress && t.assignment > t.kills
				? fmt(t.kills) + " / " + fmt(t.assignment)
				: fmt(t.kills) + " kills";
			if (t.noLootKills > 0)
			{
				kills += " · " + fmt(t.noLootKills) + " no-drop";
			}
			card.add(row(kills, t.ts > 0
				? TASK_DAY.format(Instant.ofEpochMilli((long) (t.ts * 1000))) : "", null));
			p.add(card);
			p.add(vgap(4));
		}
		if (j.tasks.size() > slayerShown)
		{
			JButton more = new JButton("Show " + Math.min(ROW_CAP, j.tasks.size() - slayerShown)
				+ " more of " + fmt(j.tasks.size()) + " tasks");
			more.addActionListener(e ->
			{
				slayerShown += ROW_CAP;
				rebuild();
			});
			p.add(more);
			p.add(vgap(4));
		}
	}

	// Rows mounted per open source view; "Show more" raises it per source.
	private final Map<String, Integer> drillShown = new LinkedHashMap<>();

	// ------------------------------------------------------------------
	// The pivot navigation: item view ⇄ source view
	// ------------------------------------------------------------------

	void openItem(String name)
	{
		pushDetail();
		detailItem = name;
		detailSource = null;
		searchField.setText("");
		rebuild();
	}

	void openSource(String name)
	{
		pushDetail();
		detailSource = name;
		detailItem = null;
		searchField.setText("");
		rebuild();
	}

	/** Open a source by a LOOSE name (a slayer task's plural, a kill-log row):
	 *  exact match, then the singular, then containment — else the raw name,
	 *  whose view degrades to an honest empty state. */
	void openSourceLoose(String name)
	{
		openSource(resolveSource(name));
	}

	private String resolveSource(String name)
	{
		List<LocalStore.SourceRow> all = plugin.dropSources();
		for (LocalStore.SourceRow r : all)
		{
			if (r.name.equalsIgnoreCase(name))
			{
				return r.name;
			}
		}
		if (name.endsWith("s"))
		{
			String sing = name.substring(0, name.length() - 1);
			for (LocalStore.SourceRow r : all)
			{
				if (r.name.equalsIgnoreCase(sing))
				{
					return r.name;
				}
			}
		}
		String low = name.toLowerCase(Locale.ROOT);
		LocalStore.SourceRow best = null;
		for (LocalStore.SourceRow r : all)
		{
			String rl = r.name.toLowerCase(Locale.ROOT);
			if ((rl.contains(low) || low.contains(rl))
				&& (best == null || r.value > best.value))
			{
				best = r;
			}
		}
		return best != null ? best.name : name;
	}

	private void pushDetail()
	{
		if (detailItem != null)
		{
			detailStack.push(new String[]{"i", detailItem});
		}
		else if (detailSource != null)
		{
			detailStack.push(new String[]{"s", detailSource});
		}
		while (detailStack.size() > 16)
		{
			detailStack.removeLast();
		}
	}

	private void backDetail()
	{
		String[] prev = detailStack.poll();
		if (prev == null)
		{
			detailItem = null;
			detailSource = null;
		}
		else if ("i".equals(prev[0]))
		{
			detailItem = prev[1];
			detailSource = null;
		}
		else
		{
			detailSource = prev[1];
			detailItem = null;
		}
		rebuild();
	}

	private JPanel backRow()
	{
		JPanel r = row("< Back", "", null);
		JLabel l = (JLabel) ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(accent());
		r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		r.addMouseListener(clicker(this::backDetail));
		return r;
	}

	/** The item under the glass: total obtained, its worth, and every source
	 *  it has come from — each source a doorway back the other way. */
	private JPanel buildItemDetail(String name)
	{
		JPanel p = column();
		p.add(backRow());
		p.add(vgap(4));
		long qty = 0;
		long value = 0;
		int itemId = 0;
		List<Object[]> srcs = new ArrayList<>();
		for (LocalStore.SourceRow r : plugin.dropSources())
		{
			for (LocalStore.BagItem b : plugin.sourceItems(r.name))
			{
				if (b.name.equalsIgnoreCase(name))
				{
					qty += b.qty;
					value += b.value;
					if (itemId == 0 && b.itemId > 0)
					{
						itemId = b.itemId;
					}
					srcs.add(new Object[]{r.name, b.qty, b.value});
				}
			}
		}
		JPanel head = card(name);
		if (itemId > 0)
		{
			JLabel slot = new JLabel();
			slot.setPreferredSize(new Dimension(36, 32));
			slot.setAlignmentX(Component.LEFT_ALIGNMENT);
			AsyncBufferedImage img = plugin.items().getImage(itemId,
				(int) Math.min(Integer.MAX_VALUE, Math.max(1, qty)), qty > 1);
			img.addTo(slot);
			head.add(slot);
		}
		head.add(row("Obtained", "×" + fmt(qty), accent()));
		if (value > 0)
		{
			head.add(row("Worth", gp(value) + " gp", null));
		}
		p.add(head);
		p.add(vgap(6));
		if (srcs.isEmpty())
		{
			p.add(note("The journal hasn't seen this item drop yet."));
			return p;
		}
		p.add(group("From"));
		srcs.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
		int mounted = 0;
		for (Object[] s : srcs)
		{
			if (mounted++ >= 40)
			{
				p.add(ghostRow("+ " + (srcs.size() - 40) + " more sources", ""));
				break;
			}
			JPanel r = row((String) s[0], "×" + fmt((long) s[1])
				+ ((long) s[2] > 0 ? " · " + gp((long) s[2]) + " gp" : ""), null);
			r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String src = (String) s[0];
			r.addMouseListener(clicker(() -> openSource(src)));
			p.add(r);
		}
		return p;
	}

	/** The source under the glass: kills tracked, the take, and everything it
	 *  has dropped — each item a doorway back the other way. */
	private JPanel buildSourceDetail(String name)
	{
		JPanel p = column();
		p.add(backRow());
		p.add(vgap(4));
		LocalStore.SourceRow sr = null;
		for (LocalStore.SourceRow r : plugin.dropSources())
		{
			if (r.name.equalsIgnoreCase(name))
			{
				sr = r;
				break;
			}
		}
		JPanel head = card(name);
		if (sr != null)
		{
			head.add(row("Kills tracked", sr.kc > 0 ? fmt(sr.kc) : fmt(sr.loots) + " drops",
				accent()));
			head.add(row("The take", gp(sr.value) + " gp"
				+ (sr.kc > 0 ? " · " + gp(sr.value / Math.max(1, sr.kc)) + " gp/kc" : ""), null));
			if (sr.pb != null)
			{
				head.add(row("Personal best", pb(sr.pb), null));
			}
			if (sr.firstMs > 0)
			{
				head.add(row("Tracked since",
					TASK_DAY.format(Instant.ofEpochMilli(sr.firstMs)), null));
			}
			// The chase, when the dryness ledger knows one for this source.
			if (grindsCache == null && !grindsFetching)
			{
				grindsFetching = true;
				final String src = sr.name;
				plugin.fetchGrinds(rows2 -> SwingUtilities.invokeLater(() ->
				{
					grindsFetching = false;
					if (rows2 == null)
					{
						return;   // store not mounted — retry on the next rebuild
					}
					grindsCache = rows2;
					if (src.equals(detailSource))
					{
						rebuild();
					}
				}));
			}
			if (grindsCache != null)
			{
				for (ChronicleApiClient.GrindRow g : grindsCache)
				{
					if (g.boss.equalsIgnoreCase(sr.name))
					{
						head.add(row("Chasing " + g.item,
							fmt(g.kc) + " / " + fmt(g.rate) + " kc",
							g.percentileDry >= 90 ? ACCENT_RED : null));
						break;
					}
				}
			}
		}
		p.add(head);
		p.add(vgap(6));
		List<LocalStore.BagItem> bag = plugin.sourceItems(sr != null ? sr.name : name);
		bag.sort(Comparator.comparingLong((LocalStore.BagItem b) -> b.value).reversed());
		if (!bag.isEmpty())
		{
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
			int sprites = 0;
			for (LocalStore.BagItem b : bag)
			{
				if (b.itemId <= 0)
				{
					continue;
				}
				if (sprites++ >= 10)
				{
					break;
				}
				JLabel slot = new JLabel();
				slot.setPreferredSize(new Dimension(36, 32));
				slot.setHorizontalAlignment(JLabel.CENTER);
				slot.setToolTipText(b.name + (b.qty > 1 ? " ×" + fmt(b.qty) : ""));
				AsyncBufferedImage img = plugin.items().getImage(b.itemId,
					(int) Math.min(Integer.MAX_VALUE, b.qty), b.qty > 1);
				img.addTo(slot);
				grid.add(slot);
			}
			if (sprites > 0)
			{
				p.add(grid);
				p.add(vgap(5));
			}
			p.add(group("Loot"));
			int cap = drillShown.getOrDefault(name, 25);
			int mounted = 0;
			for (LocalStore.BagItem b : bag)
			{
				if (mounted++ >= cap)
				{
					break;
				}
				JPanel r = row(b.name + (b.qty > 1 ? " ×" + fmt(b.qty) : ""),
					b.value > 0 ? gp(b.value) + " gp" : "", null);
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String itm = b.name;
				r.addMouseListener(clicker(() -> openItem(itm)));
				p.add(r);
			}
			if (bag.size() > cap)
			{
				JButton more = new JButton("Show " + Math.min(30, bag.size() - cap)
					+ " more of " + fmt(bag.size()) + " items");
				final String key = name;
				final int newCap = cap + 30;
				more.addActionListener(e ->
				{
					drillShown.put(key, newCap);
					rebuild();
				});
				p.add(vgap(3));
				p.add(more);
			}
			return p;
		}
		p.add(note(sr == null
			? "The journal has no drops from this source yet."
			: "Items fill in as you play — the journal prices each drop the "
			+ "moment it lands."));
		return p;
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

		Map<String, Map<String, List<String>>> tax = taxonomy(plugin.gson());
		// Three per row: five across clipped the names AND made the column's
		// preferred width overflow the viewport (the min-size clip trigger).
		JPanel pills = new JPanel(new GridLayout(0, 3, 3, 3));
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
			boolean complete = got == slots.size() && !slots.isEmpty();
			JPanel rowP = row(page, got + "/" + slots.size()
				+ (kc != null && kc > 0 ? " · " + fmt(kc) + " kc" : ""),
				complete ? ACCENT_SESSION : null, complete);
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
	private static synchronized Map<String, Map<String, List<String>>> taxonomy(
		com.google.gson.Gson gson)
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
				// The client's Gson, handed down from the plugin: the Hub's review
				// rejects a plugin that constructs its own.
				JsonObject rootTax = gson.fromJson(
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

	// Sections the player has clicked open this session; everything foldable
	// starts folded, the clog browser's own idiom. Keyed family:section.
	private final java.util.Set<String> statsExpanded = new java.util.HashSet<>();

	// Server-priced gp per consumable key, refreshed per rebuild — Food and
	// Potions rows say what the habit cost.
	private Map<String, Long> consumVals = new LinkedHashMap<>();

	private String rowValue(Map.Entry<String, Long> e)
	{
		String base = StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
		Long cv = consumVals.get(e.getKey());
		return cv != null && cv > 0 ? base + " · " + gp(cv) + " gp" : base;
	}

	private JPanel buildStats()
	{
		JPanel p = column();
		consumVals = plugin.consumableValues();
		JPanel pills = new JPanel(new GridLayout(0, 2, 3, 3));
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

		// The site's model, ported: rows file into sections; generic floor
		// totals (logsChopped, teleportsTotal) head their section instead of
		// appearing as rows, and the unresolved remainder reconciles as a
		// ghost "Other" row. Every row shows — big sections fold like the
		// clog's pages rather than being capped.
		Map<String, Long> counters = counters();
		Map<String, List<Map.Entry<String, Long>>> rowsBySection = new LinkedHashMap<>();
		Map<String, Long> floorTotals = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : counters.entrySet())
		{
			if (e.getValue() == 0 || StatRegistry.hidden(e.getKey())
				|| !StatRegistry.family(e.getKey()).equals(statsFamily))
			{
				continue;
			}
			String sec = StatRegistry.subgroup(e.getKey());
			if (StatRegistry.isFloor(e.getKey()))
			{
				floorTotals.merge(sec, e.getValue(), Long::sum);
				continue;
			}
			rowsBySection.computeIfAbsent(sec, k -> new ArrayList<>()).add(e);
		}
		if (rowsBySection.isEmpty() && floorTotals.isEmpty())
		{
			p.add(note("Nothing tracked in this facet yet."));
			return p;
		}

		// Destinations nest INSIDE the Teleports fold, not as a sibling.
		List<Map.Entry<String, Long>> destRows = statsFamily.equals("Ledger & Roads")
			? rowsBySection.remove("Destinations") : null;
		if (destRows != null && !rowsBySection.containsKey("Teleports")
			&& !floorTotals.containsKey("Teleports"))
		{
			rowsBySection.put("Destinations", destRows);   // no host fold — stand alone
			destRows = null;
		}

		List<String> order = sectionOrder(rowsBySection, floorTotals);
		for (String sec : order)
		{
			List<Map.Entry<String, Long>> rows =
				rowsBySection.getOrDefault(sec, new ArrayList<>());
			rows.sort(StatRegistry::compareRows);
			long floor = floorTotals.getOrDefault(sec, 0L);

			if (sec.isEmpty())
			{
				for (Map.Entry<String, Long> e : rows)
				{
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
				}
				continue;
			}

			if (rows.isEmpty() && floor == 0)
			{
				continue;
			}

			long typedSum = 0;
			boolean anyTyped = false;
			long shown = 0;
			for (Map.Entry<String, Long> e : rows)
			{
				shown += e.getValue();
				if (StatRegistry.typed(e.getKey()))
				{
					anyTyped = true;
					typedSum += e.getValue();
				}
			}
			long ghost = anyTyped && floor - typedSum >= 1 ? floor - typedSum : 0;
			if (sec.equals("Teleports") && floor - shown >= 1)
			{
				// Means aren't "typed" in the craft sense, but the floor still
				// reconciles: unclassified journeys surface as "Other means".
				ghost = floor - shown;
			}
			long total = Math.max(shown + ghost, floor);

			boolean foldable = statsFamily.equals("Skilling")
				|| sec.equals("Food") || sec.equals("Potions")
				|| sec.equals("Teleports") || sec.equals("Destinations");
			if (!foldable)
			{
				p.add(group(sec));
				for (Map.Entry<String, Long> e : rows)
				{
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
				}
				continue;
			}

			String stateKey = statsFamily + ":" + sec;
			boolean open = statsExpanded.contains(stateKey);
			long secGp = 0;
			for (Map.Entry<String, Long> e : rows)
			{
				Long cv = consumVals.get(e.getKey());
				if (cv != null)
				{
					secGp += cv;
				}
			}
			JPanel head = row(sec.toUpperCase(Locale.ROOT),
				fmt(total) + (secGp > 0 ? " · " + gp(secGp) + " gp" : ""),
				open ? accent() : null);
			JLabel headName = (JLabel) ((BorderLayout) head.getLayout())
				.getLayoutComponent(BorderLayout.CENTER);
			headName.setFont(FontManager.getRunescapeSmallFont());
			headName.setForeground(open ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			head.setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
			head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			head.addMouseListener(clicker(() ->
			{
				if (!statsExpanded.remove(stateKey))
				{
					statsExpanded.add(stateKey);
				}
				rebuild();
			}));
			p.add(head);
			if (open)
			{
				boolean nested = statsFamily.equals("Skilling")
					&& addCraftNested(p, sec, rows, counters);
				if (!nested)
				{
					for (Map.Entry<String, Long> e : rows)
					{
						p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
					}
					if (ghost > 0)
					{
						p.add(ghostRow(sec.equals("Teleports") ? "Other means" : "Other",
							fmt(ghost)));
					}
					// A section with no typed rows opens to its floors themselves,
					// one row each. They are separate verbs (bones buried and
					// bones offered are different acts), so a single row carrying
					// the section's whole count under the first floor's name
					// credits one verb with another's work.
					if (rows.isEmpty() && floor > 0)
					{
						List<Map.Entry<String, Long>> floors = new ArrayList<>();
						for (String fk : StatRegistry.floorKeys(sec))
						{
							long fv = counters.getOrDefault(fk, 0L);
							if (fv > 0 && !StatRegistry.hidden(fk))
							{
								floors.add(new java.util.AbstractMap.SimpleEntry<>(fk, fv));
							}
						}
						floors.sort(StatRegistry::compareRows);
						for (Map.Entry<String, Long> fe : floors)
						{
							p.add(row(StatRegistry.label(fe.getKey()), fmt(fe.getValue()), null));
						}
					}
				}
				if (sec.equals("Teleports") && destRows != null && !destRows.isEmpty())
				{
					addDestinationsFold(p, destRows);
				}
			}
		}
		return p;
	}

	/** Sections in display order: Skilling's crafts rank by weight; the other
	 *  facets keep the site's fixed order, with strays appended. */
	private List<String> sectionOrder(Map<String, List<Map.Entry<String, Long>>> rowsBySection,
		Map<String, Long> floorTotals)
	{
		java.util.LinkedHashSet<String> present = new java.util.LinkedHashSet<>();
		present.addAll(rowsBySection.keySet());
		present.addAll(floorTotals.keySet());
		List<String> order = new ArrayList<>();
		if (statsFamily.equals("Skilling"))
		{
			List<String> crafts = new ArrayList<>(present);
			// A craft weighs its floor total when it has one (that IS the
			// headline count), otherwise the sum of its rows — site rule.
			crafts.sort(Comparator.comparingLong((String s) ->
			{
				long floor = floorTotals.getOrDefault(s, 0L);
				if (floor > 0)
				{
					return floor;
				}
				long sum = 0;
				for (Map.Entry<String, Long> e
					: rowsBySection.getOrDefault(s, new ArrayList<>()))
				{
					sum += e.getValue();
				}
				return sum;
			}).reversed());
			order.addAll(crafts);
		}
		else
		{
			for (String sec : StatRegistry.fixedSections(statsFamily))
			{
				if (present.remove(sec))
				{
					order.add(sec);
				}
			}
			order.addAll(present);
		}
		return order;
	}

	/**
	 * Multi-verb crafts drill one level deeper: Prayer's open state reads as
	 * Bones buried · Ashes scattered · Ensouled heads folds, each reconciling
	 * to its own floor, with the verbless totals (Ashes sacrificed) as flat
	 * rows above. Returns false when the craft has fewer than two verb groups
	 * — a flat list reads better then, and the caller renders it.
	 */
	private boolean addCraftNested(JPanel p, String craft,
		List<Map.Entry<String, Long>> rows, Map<String, Long> counters)
	{
		Map<String, List<Map.Entry<String, Long>>> byVerb = new LinkedHashMap<>();
		List<Map.Entry<String, Long>> leaves = new ArrayList<>();
		for (Map.Entry<String, Long> e : rows)
		{
			String suf = StatRegistry.suffixOf(e.getKey());
			if (suf == null)
			{
				leaves.add(e);
			}
			else
			{
				byVerb.computeIfAbsent(suf, k -> new ArrayList<>()).add(e);
			}
		}
		if (byVerb.size() < 2)
		{
			return false;
		}
		for (Map.Entry<String, Long> e : leaves)
		{
			p.add(row(StatRegistry.rowLabel(e.getKey()), value(e), null));
		}
		List<String> verbs = new ArrayList<>(byVerb.keySet());
		Map<String, Long> verbTotal = new LinkedHashMap<>();
		for (String verb : verbs)
		{
			String floorKey = StatRegistry.suffixFloor(craft, verb);
			long floorVal = floorKey != null ? counters.getOrDefault(floorKey, 0L) : 0L;
			long sum = 0;
			for (Map.Entry<String, Long> e : byVerb.get(verb))
			{
				sum += e.getValue();
			}
			verbTotal.put(verb, Math.max(floorVal, sum));
		}
		verbs.sort(Comparator.comparingLong(
			(String v) -> verbTotal.getOrDefault(v, 0L)).reversed());
		for (String verb : verbs)
		{
			String stateKey = "Skilling:" + craft + ":" + verb;
			boolean open = statsExpanded.contains(stateKey);
			p.add(subHead(StatRegistry.suffixLabel(verb),
				fmt(verbTotal.getOrDefault(verb, 0L)), stateKey, open));
			if (open)
			{
				long sum = 0;
				for (Map.Entry<String, Long> e : byVerb.get(verb))
				{
					p.add(row(StatRegistry.rowLabel(e.getKey()), rowValue(e), null));
					sum += e.getValue();
				}
				long verbGhost = verbTotal.get(verb) - sum;
				if (verbGhost >= 1)
				{
					p.add(ghostRow("Other", fmt(verbGhost)));
				}
			}
		}
		return true;
	}

	/** Destinations live one level under Teleports: a fold naming where the
	 *  roads actually led. */
	private void addDestinationsFold(JPanel p, List<Map.Entry<String, Long>> destRows)
	{
		destRows.sort(StatRegistry::compareRows);
		long sum = 0;
		for (Map.Entry<String, Long> e : destRows)
		{
			sum += e.getValue();
		}
		String stateKey = "Ledger & Roads:Destinations";
		boolean open = statsExpanded.contains(stateKey);
		p.add(subHead("Destinations", fmt(sum), stateKey, open));
		if (open)
		{
			for (Map.Entry<String, Long> e : destRows)
			{
				p.add(row(StatRegistry.label(e.getKey()), value(e), null));
			}
		}
	}

	/** A second-level fold header: normal case, indented, click to toggle. */
	private JPanel subHead(String label, String totalStr, String stateKey, boolean open)
	{
		JPanel head = row(label, totalStr, open ? accent() : null);
		JLabel name = (JLabel) ((BorderLayout) head.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(open ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
		head.setBorder(BorderFactory.createEmptyBorder(3, 10, 1, 2));
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addMouseListener(clicker(() ->
		{
			if (!statsExpanded.remove(stateKey))
			{
				statsExpanded.add(stateKey);
			}
			rebuild();
		}));
		return head;
	}

	private static String value(Map.Entry<String, Long> e)
	{
		return StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
	}

	/** The reconciliation remainder: present, quiet, never the headline. */
	private static JPanel ghostRow(String left, String right)
	{
		JPanel r = row(left, right, null);
		((JLabel) ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.CENTER))
			.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker().darker());
		return r;
	}

	// How deep the milestone scan reads into the feed: wide enough that a
	// year-long window still finds its own entries.
	private static final int HISTORY_FEED_SCAN = 2000;

	// The two reads this tab lives on, held between rebuilds. The calendar
	// spine is a whole parse of an append-only file that grows for the life of
	// the account, and the feed slice is handed over as deep copies while the
	// store holds its lock — on the EDT that cost lands as a stall on every
	// pill click and every push-driven refresh, and it gets worse the longer
	// the account has been journalled. Both are gathered on a worker thread
	// instead (the client's shared scheduler has no business carrying a
	// multi-megabyte parse) and served from here until the journal moves.
	private java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> historySpine;
	private List<JsonObject> historyFeed = new ArrayList<>();
	// What that pair was true of: the day it was read (baselines close at the
	// rollover) and the newest feed entry it saw. Either one moving means the
	// journal has changed underneath the cache.
	private java.time.LocalDate historyDay;
	private long historyFeedTs;
	private boolean historyGathering;
	// A gather still in flight when a different journal mounts must not land —
	// its spine belongs to the account that has gone.
	private int historyEpoch;

	/** One gathered pass over the journal's calendar spine and its feed. */
	private static final class HistoryData
	{
		final java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> spine;
		final List<JsonObject> feed;
		final java.time.LocalDate day;

		HistoryData(java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> spine,
			List<JsonObject> feed, java.time.LocalDate day)
		{
			this.spine = spine;
			this.feed = feed;
			this.day = day;
		}
	}

	/**
	 * Read the spine and the feed slice off the EDT, then mount. Primed when
	 * the panel is built and whenever a journal mounts, so the tab is normally
	 * warm before it is first opened; a failed read simply leaves the cache
	 * cold for the next rebuild to ask again. EDT only.
	 */
	private void gatherHistory()
	{
		if (historyGathering)
		{
			return;
		}
		historyGathering = true;
		final int epoch = historyEpoch;
		new SwingWorker<HistoryData, Void>()
		{
			@Override
			protected HistoryData doInBackground()
			{
				return new HistoryData(plugin.historyBaselines(),
					plugin.feedNewest(HISTORY_FEED_SCAN), java.time.LocalDate.now());
			}

			@Override
			protected void done()
			{
				if (epoch != historyEpoch)
				{
					// Another account mounted while this pass was reading; the
					// gather it started owns the cache now.
					return;
				}
				historyGathering = false;
				HistoryData d;
				try
				{
					d = get();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					return;
				}
				catch (java.util.concurrent.ExecutionException e)
				{
					// A read that failed leaves the cache cold rather than half
					// true — the next rebuild asks again.
					return;
				}
				historySpine = d.spine;
				historyFeed = d.feed;
				historyDay = d.day;
				historyFeedTs = newestTs(d.feed);
				if (view == View.HISTORY)
				{
					rebuild();
				}
			}
		}.execute();
	}

	/** The newest feed entry's stamp, or 0 — the cheap staleness probe. */
	private static long newestTs(List<JsonObject> feed)
	{
		return feed.isEmpty() ? 0 : safeLong(feed.get(0).get("ts"));
	}

	private JPanel buildHistory()
	{
		JPanel p = column();

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
			pill.setForeground(g.equals(histGranularity) && histFrom == null
				? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			pill.addMouseListener(clicker(() ->
			{
				histGranularity = g;
				histFrom = null;
				histTo = null;
				rebuild();
			}));
			pills.add(pill);
		}
		p.add(pills);
		p.add(vgap(5));

		// the period under the cursor — or the exact dates the player typed
		java.time.LocalDate end = histCursor;
		java.time.LocalDate start;
		String label;
		if (histFrom != null && histTo != null)
		{
			start = histFrom;
			end = histTo.isAfter(java.time.LocalDate.now()) ? java.time.LocalDate.now() : histTo;
			label = start.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yy"))
				+ " - " + end.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yy"));
		}
		else
		{
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
		}
		final java.time.LocalDate pStart = start;
		final java.time.LocalDate pEnd = end;

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
			if (histFrom != null && histTo != null)
			{
				long span = java.time.temporal.ChronoUnit.DAYS.between(histFrom, histTo) + 1;
				histFrom = histFrom.minusDays(span);
				histTo = histTo.minusDays(span);
			}
			else
			{
				histCursor = stepBack(histCursor);
			}
			rebuild();
		}));
		fwd.addMouseListener(clicker(() ->
		{
			if (histFrom != null && histTo != null)
			{
				long span = java.time.temporal.ChronoUnit.DAYS.between(histFrom, histTo) + 1;
				histFrom = histFrom.plusDays(span);
				histTo = histTo.plusDays(span);
			}
			else
			{
				java.time.LocalDate next = stepForward(histCursor);
				histCursor = next.isAfter(java.time.LocalDate.now()) ? java.time.LocalDate.now() : next;
			}
			rebuild();
		}));
		JLabel lbl = new JLabel(label, JLabel.CENTER);
		lbl.setFont(FontManager.getRunescapeFont());
		// The site takes any two dates; so does the panel — click the period.
		lbl.setToolTipText("Set exact dates");
		lbl.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		lbl.addMouseListener(clicker(() -> onSetExactDates(pStart, pEnd)));
		stepper.add(back, BorderLayout.WEST);
		stepper.add(lbl, BorderLayout.CENTER);
		stepper.add(fwd, BorderLayout.EAST);
		p.add(stepper);
		p.add(vgap(6));

		// Ask for a fresh pass when the day has turned or the feed has grown
		// since the last one — probing the newest entry costs a single copy,
		// where the scan below costs thousands. The stale pair still renders
		// meanwhile, so only a genuinely cold cache shows a waiting line.
		if (historySpine == null || !java.time.LocalDate.now().equals(historyDay)
			|| newestTs(plugin.feedNewest(1)) != historyFeedTs)
		{
			gatherHistory();
		}
		if (historySpine == null)
		{
			p.add(note("Reading the journal's calendar spine…"));
			return p;
		}
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> hist = historySpine;

		// baselines bounding the period: closing state the day before it began,
		// and the last close inside it
		Map.Entry<java.time.LocalDate, HistoryLog.Baseline> before =
			hist.floorEntry(pStart.minusDays(1));
		Map.Entry<java.time.LocalDate, HistoryLog.Baseline> at = hist.floorEntry(end);
		if (at == null || (before != null && at.getKey().equals(before.getKey())))
		{
			String empty;
			if (hist.isEmpty())
			{
				empty = "The record starts today — baselines close at each login, "
					+ "day rollover and logout, and a period is the distance "
					+ "between two of them.";
			}
			else if (!hist.isEmpty() && hist.firstKey().isBefore(pStart)
				&& ("Day".equals(histGranularity) || "Week".equals(histGranularity)))
			{
				// The imported past resolves by month — day and week windows
				// inside it genuinely hold no interior baseline.
				empty = "The imported past resolves by month — switch to Month "
					+ "or Year to read this era. Daily detail begins with the plugin.";
			}
			else
			{
				empty = "Nothing recorded in this period.";
			}
			p.add(note(empty));
		}
		else
		{
			// When the nearest earlier baseline sits well before the window
			// (the imported past resolves by month), say so — a month of xp
			// presented as one week's gain is the sailing bug in muted form.
			if (before != null && before.getKey().isBefore(pStart.minusDays(1)))
			{
				p.add(note("Measured since " + before.getKey().format(
					java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
					+ " — the nearest earlier baseline."));
				p.add(vgap(4));
			}
			Map<String, Long> beforeSk = before != null ? before.getValue().skills
				: new LinkedHashMap<>();
			List<Map.Entry<String, Long>> gains = new ArrayList<>();
			for (Map.Entry<String, Long> e : at.getValue().skills.entrySet())
			{
				if ("overall".equals(e.getKey()))
				{
					continue;
				}
				if (before == null || !beforeSk.containsKey(e.getKey()))
				{
					// No before-value for this skill means NO DATA, not zero:
					// imported baselines predate newer skills (Sailing), and
					// treating absence as 0 painted a lifetime's xp as one
					// week's gain. The skill shows once both ends know it.
					continue;
				}
				long d = e.getValue() - beforeSk.get(e.getKey());
				if (d > 0)
				{
					gains.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), d));
				}
			}
			gains.sort(Map.Entry.<String, Long>comparingByValue().reversed());
			if (!gains.isEmpty())
			{
				// Ranked rows, the site's Chronicle idiom: total first, skills
				// by xp desc, a lit "99" where the window crossed the line.
				long totalGained = 0;
				for (Map.Entry<String, Long> g : gains)
				{
					totalGained += g.getValue();
				}
				JPanel card = card("Xp gained");
				card.add(row("Total", "+" + gp(totalGained), accent()));
				int mounted = 0;
				for (Map.Entry<String, Long> g : gains)
				{
					if (mounted++ >= 14)
					{
						break;
					}
					long after = at.getValue().skills.getOrDefault(g.getKey(), 0L);
					boolean hit99 = after >= XP_99 && after - g.getValue() < XP_99;
					card.add(row(StatRegistry.prettify(g.getKey())
						+ (hit99 ? " · 99" : ""), "+" + gp(g.getValue()),
						hit99 ? ACCENT_SESSION : null, hit99));
				}
				if (gains.size() > 14)
				{
					card.add(ghostRow("+ " + (gains.size() - 14) + " more skills", ""));
				}
				p.add(card);
				p.add(vgap(5));
			}

			Map<String, Long> beforeCt = before != null ? before.getValue().counters
				: new LinkedHashMap<>();
			List<Map.Entry<String, Long>> movers = new ArrayList<>();
			// Imported baselines carry NO counters (the archive is xp-only), so
			// a key absent from the before-side is no-data — same rule as xp,
			// or a period bounded by an import claims a lifetime as its movers.
			if (before != null && !beforeCt.isEmpty())
			{
				for (Map.Entry<String, Long> e : at.getValue().counters.entrySet())
				{
					if (!beforeCt.containsKey(e.getKey()))
					{
						continue;
					}
					long d = e.getValue() - beforeCt.get(e.getKey());
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
			for (JsonObject e : historyFeed)
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

		return p;
	}

	/** The site's any-two-dates gains, panel edition: a small dialog, ISO or
	 *  d/M/yyyy accepted, prefilled with the visible period. */
	private void onSetExactDates(java.time.LocalDate from, java.time.LocalDate to)
	{
		javax.swing.JTextField fromField = new javax.swing.JTextField(from.toString());
		javax.swing.JTextField toField = new javax.swing.JTextField(to.toString());
		JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
		form.add(new JLabel("From (yyyy-mm-dd):"));
		form.add(fromField);
		form.add(new JLabel("To (yyyy-mm-dd):"));
		form.add(toField);
		int ok = JOptionPane.showConfirmDialog(this, form,
			"Exact dates", JOptionPane.OK_CANCEL_OPTION);
		if (ok != JOptionPane.OK_OPTION)
		{
			return;
		}
		java.time.LocalDate f = parseDate(fromField.getText());
		java.time.LocalDate t = parseDate(toField.getText());
		if (f == null || t == null)
		{
			JOptionPane.showMessageDialog(this,
				"Dates read as yyyy-mm-dd (or d/m/yyyy) — nothing changed.");
			return;
		}
		if (t.isBefore(f))
		{
			java.time.LocalDate swap = f;
			f = t;
			t = swap;
		}
		histFrom = f;
		histTo = t;
		rebuild();
	}

	private static java.time.LocalDate parseDate(String text)
	{
		String s = text == null ? "" : text.trim();
		try
		{
			return java.time.LocalDate.parse(s);
		}
		catch (RuntimeException ignored)
		{
			// fall through to d/m/yyyy
		}
		try
		{
			return java.time.LocalDate.parse(s,
				java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy"));
		}
		catch (RuntimeException ignored)
		{
			return null;
		}
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
		JButton importBtn = new JButton("Import a journal…");
		importBtn.addActionListener(ev -> onImportClicked());
		importBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		importBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		p.add(importBtn);
		p.add(vgap(4));
		p.add(note("saved beside your journal: .runelite/chronicle/ — an import "
			+ "merges another copy of THIS account's record in (a backup, another "
			+ "computer, a record kept for you elsewhere). Everything floors, so "
			+ "importing twice changes nothing."));
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

	/** Cloud sync status — upward mirror only, so status + a push button is all there is. */
	private JPanel buildCloudSection()
	{
		JPanel s = column();
		JLabel t = new JLabel("Cloud sync");
		t.setFont(FontManager.getRunescapeBoldFont());
		t.setForeground(accent());
		t.setAlignmentX(Component.LEFT_ALIGNMENT);
		s.add(t);
		s.add(vgap(4));
		String rsn = plugin.enrolledRsn();
		if (rsn != null && !rsn.isEmpty())
		{
			s.add(row("Mirroring " + rsn + " upward", "", null));
		}
		s.add(row(plugin.statusLine(), "", null));
		s.add(vgap(4));
		s.add(note("The journal on this computer is the record; the server only "
			+ "receives a copy. Nothing here depends on it."));
		s.add(vgap(6));
		JButton push = new JButton("Push stats now");
		push.addActionListener(e -> plugin.actionPushNow());
		push.setAlignmentX(Component.LEFT_ALIGNMENT);
		push.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		s.add(push);
		return s;
	}

	/** Ask for a journal file and hand it to the plugin. EDT. */
	private void onImportClicked()
	{
		javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
		fc.setDialogTitle("Import a Chronicle journal");
		fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
			"Chronicle journal (*.json)", "json"));
		if (fc.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION)
		{
			plugin.actionImport(fc.getSelectedFile());
		}
	}

	private JPanel buildSearch(String q)
	{
		JPanel p = column();
		String ql = q.toLowerCase(Locale.ROOT);
		int total = 0;
		searchJump = null;

		// Trackers — via the registry, so every counter is findable by label or key.
		List<Map.Entry<String, Long>> statHits = new ArrayList<>();
		for (Map.Entry<String, Long> e : counters().entrySet())
		{
			if (e.getValue() != 0 && !StatRegistry.hidden(e.getKey())
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
			jump(View.STATS);
			for (int i = 0; i < Math.min(4, statHits.size()); i++)
			{
				Map.Entry<String, Long> e = statHits.get(i);
				String v = StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
				p.add(row(StatRegistry.label(e.getKey()), v, null));
				total++;
			}
		}

		// Drops — the question is "how many, and from where": the item
		// aggregates across every source, and its sources follow underneath.
		Map<String, long[]> itemAgg = new LinkedHashMap<>();       // name -> {qty, value}
		Map<String, List<String>> itemSrcs = new LinkedHashMap<>();
		for (LocalStore.SourceRow src : plugin.dropSources())
		{
			for (LocalStore.BagItem b : plugin.sourceItems(src.name))
			{
				if (!b.name.toLowerCase(Locale.ROOT).contains(ql))
				{
					continue;
				}
				long[] agg = itemAgg.computeIfAbsent(b.name, k -> new long[2]);
				agg[0] += b.qty;
				agg[1] += b.value;
				itemSrcs.computeIfAbsent(b.name, k -> new ArrayList<>())
					.add(src.name + (b.qty > 1 ? " ×" + fmt(b.qty) : ""));
			}
		}
		List<String> itemNames = new ArrayList<>(itemAgg.keySet());
		itemNames.sort(Comparator.comparingLong((String n) -> itemAgg.get(n)[1]).reversed());
		List<LocalStore.SourceRow> srcHits = new ArrayList<>();
		for (LocalStore.SourceRow r : plugin.dropSources())
		{
			if (r.name.toLowerCase(Locale.ROOT).contains(ql))
			{
				srcHits.add(r);
			}
		}
		srcHits.sort(Comparator.comparingLong((LocalStore.SourceRow r) -> r.value).reversed());
		if (!itemNames.isEmpty() || !srcHits.isEmpty())
		{
			p.add(group("Drops"));
			jump(View.DROPS);
			for (int i = 0; i < Math.min(2, itemNames.size()); i++)
			{
				String name = itemNames.get(i);
				long[] agg = itemAgg.get(name);
				JPanel r = row(name + " ×" + fmt(agg[0]),
					agg[1] > 0 ? gp(agg[1]) + " gp" : "", accent());
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String itm = name;
				r.addMouseListener(clicker(() -> openItem(itm)));
				p.add(r);
				List<String> srcs = itemSrcs.get(name);
				StringBuilder fromLine = new StringBuilder("from ");
				for (int s = 0; s < Math.min(3, srcs.size()); s++)
				{
					fromLine.append(s > 0 ? " · " : "").append(srcs.get(s));
				}
				if (srcs.size() > 3)
				{
					fromLine.append(" · +").append(srcs.size() - 3);
				}
				p.add(ghostRow(fromLine.toString(), ""));
				total++;
			}
			for (int i = 0; i < Math.min(2, srcHits.size()); i++)
			{
				LocalStore.SourceRow r = srcHits.get(i);
				JPanel rr = row(r.name, (r.kc > 0 ? fmt(r.kc) + " kc · " : "") + gp(r.value) + " gp", null);
				rr.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String src = r.name;
				rr.addMouseListener(clicker(() -> openSource(src)));
				p.add(rr);
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
		// One answer per ITEM, not per page copy — obtaining one whip lights
		// every slot that holds it, so listing each slot is redundant. The
		// first page that carries the item stands in as its address.
		Map<String, String> slotFirstPage = new LinkedHashMap<>();
		clogSearch:
		for (Map.Entry<String, Map<String, List<String>>> tab : taxonomy(plugin.gson()).entrySet())
		{
			for (Map.Entry<String, List<String>> pg : tab.getValue().entrySet())
			{
				for (String slot : pg.getValue())
				{
					if (slot.toLowerCase(Locale.ROOT).contains(ql))
					{
						slotFirstPage.putIfAbsent(slot, pg.getKey());
						if (slotFirstPage.size() >= 4)
						{
							break clogSearch;
						}
					}
				}
			}
		}
		if (!slotFirstPage.isEmpty())
		{
			p.add(group("Collection log"));
			jump(View.LOG);
			for (Map.Entry<String, String> hit : slotFirstPage.entrySet())
			{
				boolean got = owned.containsKey(hit.getKey().toLowerCase(Locale.ROOT));
				p.add(row(hit.getKey(), got ? "obtained" : hit.getValue(),
					got ? ACCENT_SESSION : null));
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
			jump(View.JOURNAL);
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
		else
		{
			p.add(vgap(6));
			p.add(ghostRow("enter opens the matching view", ""));
		}
		return p;
	}

	// Where Enter lands: the first group that answered sets the view.
	private View searchJump;

	private void jump(View target)
	{
		if (searchJump == null)
		{
			searchJump = target;
		}
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
			case "SESSION":
			{
				long mins = d.has("minutes") ? d.get("minutes").getAsLong() : 0;
				long xp = d.has("xp") ? d.get("xp").getAsLong() : 0;
				long drops = d.has("drops") ? d.get("drops").getAsLong() : 0;
				long dropsGp = d.has("dropsGp") ? d.get("dropsGp").getAsLong() : 0;
				StringBuilder line = new StringBuilder("Session — ");
				line.append(mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m");
				if (xp > 0)
				{
					line.append(" · +").append(gp(xp)).append(" xp");
				}
				if (drops > 0)
				{
					line.append(" · ").append(fmt(drops)).append(" drops");
					if (dropsGp > 0)
					{
						line.append(" (").append(gp(dropsGp)).append(" gp)");
					}
				}
				return line.toString();
			}
			case "SLAYER":
			{
				// Cloud-adopted entries carry the server's field names; the
				// plugin's own chat-driven emits use the short ones.
				String t = str(d, "slayerTask", str(d, "task", ""));
				String kc = str(d, "killCount", str(d, "count", ""));
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
		outer.setMinimumSize(new Dimension(10, 4));   // see vgap(): min > pref clips rows
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

	// Wrap width that fits every context a note appears in: the panel is 242,
	// minus its 16px border, the scrollbar, and a card's own 16px insets.
	private static final int NOTE_WIDTH = 190;

	private static JPanel note(String text)
	{
		// Deterministic wrap: Swing's html JLabel measures at one width and can
		// paint at another, which clipped note tails all over the panel. A
		// greedy FontMetrics wrap into plain one-line labels reports an exact
		// preferred height by construction.
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		Font f = FontManager.getRunescapeSmallFont();
		java.awt.FontMetrics fm = p.getFontMetrics(f);
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) > NOTE_WIDTH && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		for (String l : lines)
		{
			JLabel lab = new JLabel(l);
			lab.setFont(f);
			lab.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
			lab.setAlignmentX(Component.LEFT_ALIGNMENT);
			p.add(lab);
		}
		return p;
	}

	private static Component vgap(int h)
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setPreferredSize(new Dimension(1, h));
		// Min must never exceed preferred: when any child is wider than the
		// viewport, GridBagLayout silently recomputes ROW HEIGHTS from minimum
		// sizes — and a childless panel's default minimum is 10px, inflating
		// the grid past the reported height and clipping the last row.
		p.setMinimumSize(new Dimension(1, h));
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
}
