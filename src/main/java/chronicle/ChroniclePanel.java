/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.counters.ExperienceStatTracker;
import chronicle.panel.StatRegistry;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
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

/**
 * The journal's face: a search field, seven tabs, and a detail overlay over
 * whichever tab is open. Home reads the live session; the rest read the
 * lifetime journal. Lists mount a bounded number of rows, and views rebuild
 * on tab switch or, on Home, a slow timer.
 */
class ChroniclePanel extends PluginPanel
{
	// Every date the panel prints, in one locale: the numbers next to them are
	// already forced to Locale.UK, and a JVM-default month name beside them reads
	// as two different clocks.
	private static final DateTimeFormatter DAY =
		DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter TASK_DAY =
		DateTimeFormatter.ofPattern("d MMM yy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter FULL_DAY =
		DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter MONTH_YEAR =
		DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());
	private static final Color ACCENT_LIFETIME = ColorScheme.BRAND_ORANGE;
	private static final Color ACCENT_SESSION = new Color(85, 163, 90);
	private static final Color ACCENT_RED = new Color(196, 84, 74);
	// Rows mounted per list before a "Show more" button.
	private static final int ROW_CAP = 30;
	// Every inset between the sidebar's own width and a row's label, named where it
	// is applied so a line that measures itself against them cannot drift out of
	// step with the layout. See chaseRoom().
	private static final int PANEL_INSET = 8;   // this panel's border, a side
	private static final int CARD_INSET = 8;    // a card's border, a side
	private static final int ROW_INSET = 2;     // a row's border, a side
	private static final int ROW_GAP = 8;       // a row's gap, name to value

	private enum View
	{
		HOME, DROPS, SLAYER, LOG, STATS, HISTORY, JOURNAL, MANAGE
	}

	private final ChroniclePlugin plugin;

	private final JPanel display = new JPanel(new BorderLayout());
	// The group gets no display panel: it swaps in each tab's own content
	// component, and ours are empty. rebuild() does the swapping.
	private final MaterialTabGroup tabGroup = new MaterialTabGroup();
	private final Map<View, MaterialTab> tabByView = new java.util.EnumMap<>(View.class);
	private final IconTextField searchField = new IconTextField();
	private final Timer searchDebounce;
	private final Timer homeTicker;

	private View view = View.HOME;
	// An item or a source under the glass, overlaying the current tab. Any item
	// or source row anywhere opens one; the back-stack unwinds the hops.
	private String detailItem;
	private String detailSource;
	private final java.util.ArrayDeque<String[]> detailStack = new java.util.ArrayDeque<>();
	private String statsFamily = StatRegistry.FAMILIES[0];
	private int dropsShown = ROW_CAP;
	private String clogTab = "Bosses";
	private String clogPageSel;
	// History's Skills/Bosses lens. Both read the period the stepper is on.
	private boolean histBosses;

	private JLabel manage;

	// Whether the journal is reaching disk. Nothing else in the panel shows it:
	// the views are served from memory and look the same either way.
	private final JLabel heartbeat = new JLabel();
	private String histGranularity = "Week";
	// The period's END date (inclusive); the stepper moves it by one granule.
	private java.time.LocalDate histCursor = java.time.LocalDate.now();
	// Exact dates: non-null overrides the granularity pills. Set by clicking the
	// period label, cleared by any pill.
	private java.time.LocalDate histFrom;
	private java.time.LocalDate histTo;
	// The bundled taxonomy: tab -> page -> ordered slot names. Parsed on the
	// first Log open.
	private static Map<String, Map<String, List<String>>> taxonomy;

	ChroniclePanel(ChroniclePlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(
			PANEL_INSET, PANEL_INSET, PANEL_INSET, PANEL_INSET));
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
		// Enter opens whatever the query resolves to, else the first result
		// group's tab.
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
				// select() returns early on the tab already showing, so its
				// onSelectEvent — the only place the query is cleared and the
				// panel rebuilt — never fires. Do that work here instead.
				if (target.isSelected())
				{
					applyTab(searchJump);
				}
				else
				{
					tabGroup.select(target);
				}
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
		// ── tabs, then search ──
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

		// Home refreshes on a slow tick while it is the visible view. Nothing in
		// the panel rebuilds per game tick.
		homeTicker = new Timer(3000, e ->
		{
			// A detail opened from Home leaves the view on HOME, and a tick
			// rebuilds the whole display — which would throw the reader back to
			// the top of a fresh scroll pane every three seconds.
			if (view == View.HOME && searchQuery().isEmpty()
				&& detailItem == null && detailSource == null && detailTask < 0
				&& leftBehindSource == null && leftBehindItem == null)
			{
				// Same view, same content: the reader stays where they were reading.
				keepScroll = true;
				try
				{
					rebuild();
				}
				finally
				{
					keepScroll = false;
				}
			}
		});
		homeTicker.start();

		manage = new JLabel("manage");
		manage.setFont(FontManager.getRunescapeSmallFont());
		manage.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		manage.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		manage.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		manage.setToolTipText("Import, and where your journal lives");
		manage.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				view = view == View.MANAGE ? View.JOURNAL : View.MANAGE;
				rebuild();
			}

			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				manage.setForeground(accent());
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				manage.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
			}
		});
		JPanel manageRow = new JPanel();
		manageRow.setLayout(new BoxLayout(manageRow, BoxLayout.X_AXIS));
		manageRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Match the siblings, or BoxLayout centres this row and shunts it half a
		// panel right.
		manageRow.setAlignmentX(Component.CENTER_ALIGNMENT);
		manageRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		manageRow.add(manage);
		manageRow.add(javax.swing.Box.createHorizontalGlue());
		manageRow.add(heartbeat);
		north.add(manageRow);

		// Prime the History tab's reads off the EDT, before anyone opens it.
		gatherHistory();
		rebuild();
	}

	private static ImageIcon tabIcon(String name)
	{
		return new ImageIcon(ImageUtil.loadImageResource(ChroniclePanel.class, name));
	}

	private void addTab(String icon, String tooltip, View target)
	{
		MaterialTab tab = new MaterialTab(tabIcon(icon), tabGroup, new JPanel());
		tab.setToolTipText(tooltip);
		tab.setOnSelectEvent(() ->
		{
			applyTab(target);
			return true;
		});
		tabGroup.addTab(tab);
		tabByView.put(target, tab);
		if (target == View.HOME)
		{
			tabGroup.select(tab);
		}
	}

	/** Show a tab from scratch: no drilled detail, no query, nothing paged out.
	 *  Every open detail is dropped, or rebuild() would paint it over the tab. */
	private void applyTab(View target)
	{
		view = target;
		dropsShown = ROW_CAP;
		slayerShown = ROW_CAP;
		drillShown.clear();
		detailItem = null;
		detailSource = null;
		detailTask = -1;
		leftBehindSource = null;
		leftBehindItem = null;
		detailStack.clear();
		searchField.setText("");
		rebuild();
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

	/** Rebuild the panel from plugin state. Safe to call from any thread. */
	void update()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	// Set only for the home ticker's own rebuild. Every other rebuild is a move to
	// somewhere new — a tab, a search, an opened detail — and lands at the top.
	private boolean keepScroll;

	private void rebuild()
	{
		// rebuild() throws the whole scroll pane away and hangs a fresh one, which
		// starts at the top. Expanded, Home is longer than the panel.
		int priorScroll = 0;
		if (keepScroll && display.getComponentCount() > 0
			&& display.getComponent(0) instanceof JScrollPane)
		{
			priorScroll = ((JScrollPane) display.getComponent(0))
				.getVerticalScrollBar().getValue();
		}
		String stalled = plugin.journalWarning();
		Color pulse = stalled == null ? ACCENT_SESSION : ColorScheme.PROGRESS_ERROR_COLOR;
		heartbeat.setText(stalled == null ? "logging" : "not saving");
		heartbeat.setIcon(dot(pulse));
		heartbeat.setIconTextGap(4);
		heartbeat.setForeground(pulse);
		heartbeat.setFont(FontManager.getRunescapeSmallFont());
		if (manage != null)
		{
			// Selecting the first tab rebuilds before the header is built.
			manage.setVisible(view == View.JOURNAL || view == View.MANAGE);
		}
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
		else if (detailTask >= 0)
		{
			body = buildTaskDetail(detailTask);
		}
		else if (leftBehindSource != null || leftBehindItem != null)
		{
			body = buildLeftBehindDetail();
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
				case MANAGE:
					body = buildManage();
					break;
				case HOME:
				default:
					body = buildHome();
					break;
			}
		}
		// Row heights are width-independent. The bar can't oscillate.
		JScrollPane scroll = new JScrollPane(wrapTop(body),
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(14);
		display.add(scroll, BorderLayout.CENTER);
		display.revalidate();
		display.repaint();
		if (priorScroll > 0)
		{
			// A scrollbar with no extent yet clamps every value to zero, and the layout
			// revalidate() asks for is queued behind us. Lay the new pane out now so the
			// restore takes, rather than letting the reader see a frame at the top.
			display.validate();
			scroll.getVerticalScrollBar().setValue(priorScroll);
		}
	}

	private void onSearchChanged()
	{
		rebuild();
	}

	// ------------------------------------------------------------------
	// Views
	// ------------------------------------------------------------------

	// Pinned to the top of the session strip, in this order.
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

	// A small filled circle, used as the heartbeat pip.
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

	// The pinned xp total doubles as a fold head. Closed it is the row it has always
	// been; open, the name takes the accent this panel's other fold heads use. The
	// state is a key in the panel's one fold register, same as every other fold: the
	// home ticker rebuilds every three seconds and would otherwise shut the fold on
	// the reader between one glance and the next.
	private void xpFoldHead(JPanel head)
	{
		JLabel name = (JLabel) ((BorderLayout) head.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		if (foldOpen(FOLD_HOME_XP))
		{
			name.setForeground(accent());
		}
		head.setToolTipText("Each skill's xp and xp per hour this session");
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addMouseListener(clicker(() -> toggleFold(FOLD_HOME_XP)));
	}

	// One row per skill that moved this session, biggest first: the xp it gained and
	// what that comes to per hour. No icons; the strip above is a column of names and
	// figures, and a sprite gutter on these rows alone would break it. Indented under
	// the total they add up to.
	private void addXpBySkill(JPanel strip)
	{
		List<ExperienceStatTracker.SkillGain> gains = plugin.sessionSkillXp();
		if (gains.isEmpty())
		{
			// The split lives in the experience tracker alone, and that is built on
			// the session's first event. Without this the fold opens onto nothing.
			strip.add(ghostRow("no skill breakdown yet", ""));
			return;
		}
		for (ExperienceStatTracker.SkillGain g : gains)
		{
			// The rate is left off until the tally has a minute behind it; a few
			// seconds of play extrapolates to a figure nobody earned.
			String right = "+" + gp(g.xp)
				+ (g.perHour >= 0 ? " · " + gp(g.perHour) + "/h" : "");
			JPanel r = row(g.skill.getName(), right, null);
			r.setBorder(BorderFactory.createEmptyBorder(1, 10, 1, 2));
			strip.add(r);
		}
	}

	private JPanel buildHome()
	{
		JPanel p = column();
		// The pip says the journal has stopped reaching disk; Home says why.
		String stalled = plugin.journalWarning();
		if (stalled != null)
		{
			p.add(note(stalled));
			p.add(vgap(6));
		}

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

		// The session strip: pinned rows, then whatever else moved, ranked.
		JPanel strip = card("This session");
		Map<String, Integer> sess = plugin.sessionCounters();
		int mounted = 0;
		java.util.Set<String> shownKeys = new java.util.HashSet<>();
		for (String key : HOME_PINNED)
		{
			long v = sess.getOrDefault(key, 0);
			if (v > 0)
			{
				boolean isXp = "totalXpGained".equals(key);
				JPanel r = row(homeLabel(key),
					StatRegistry.isGp(key) ? gp(v) + " gp"
						: (isXp ? "+" + gp(v) : fmt(v)),
					ACCENT_SESSION);
				if (isXp)
				{
					xpFoldHead(r);
				}
				strip.add(r);
				if (isXp && foldOpen(FOLD_HOME_XP))
				{
					addXpBySkill(strip);
				}
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
		// Everything the session moved, biggest first. The tab has the room and a
		// session only counts what was actually done, so there is nothing to trim to.
		for (Map.Entry<String, Integer> e : movers)
		{
			long v = e.getValue();
			strip.add(row(StatRegistry.label(e.getKey()),
				StatRegistry.isGp(e.getKey()) ? gp(v) + " gp" : fmt(v), null));
			mounted++;
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
			// LEFT, to match the caption beside it.
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
			p.add(note("Drops appear here as you play: every kill, priced as it lands."));
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

	// The uncollected ledger: what was walked past, by source and by item.
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
			p.add(note("What you walk past gets counted here, priced at the "
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
			sr.addMouseListener(clicker(() ->
			{
				leftBehindSource = src;
				leftBehindItem = null;
				rebuild();
			}));
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
				ir.addMouseListener(clicker(() ->
				{
					leftBehindItem = itm;
					leftBehindSource = null;
					rebuild();
				}));
				p.add(ir);
			}
		}
		return p;
	}

	// The dryness ledger, read once per session off the first source opened.
	private List<ChronicleApiClient.GrindRow> grindsCache;
	private boolean grindsFetching;

	// The journey fetches once per session on first open; null = not yet asked.
	private ChronicleApiClient.SlayerJourney journeyCache;
	private boolean journeyFetching;
	// Index into the journey (newest-first) of the task under the glass, or -1.
	private int detailTask = -1;
	// The Left behind lens drilled from one end or the other; both null = the list.
	private String leftBehindSource;
	private String leftBehindItem;

	/** Drop every view built from the last account's journal. EDT only. */
	void resetAccountCaches()
	{
		journeyCache = null;
		journeyFetching = false;
		detailTask = -1;
		leftBehindSource = null;
		leftBehindItem = null;
		grindsCache = null;
		grindsFetching = false;
		historySpine = null;
		historyFeed = new ArrayList<>();
		historyDay = null;
		historyFeedTs = 0;
		// disown any gather still reading the old journal
		historyEpoch++;
		historyGathering = false;
		detailItem = null;
		detailSource = null;
		detailStack.clear();
		drillShown.clear();
		openFolds.clear();
		gatherHistory();
		rebuild();
	}

	/** Stop the repeating timers. Called from the plugin's shutDown. */
	void shutdown()
	{
		homeTicker.stop();
		searchDebounce.stop();
	}
	private int slayerShown = ROW_CAP;

	// Current task, the journal's task-by-task journey, and the kill log
	// scraped from the in-game widget.
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

		// Paint the cached journey at once — no flicker — and re-read the journal
		// behind it. The read rebuilds only when the journey has actually moved,
		// so an unchanged journal cannot start a loop.
		if (journeyCache != null)
		{
			addJourney(p, journeyCache);
		}
		else
		{
			p.add(note("Reading the task journey from the journal…"));
		}
		if (!journeyFetching)
		{
			journeyFetching = true;
			plugin.fetchSlayerJourney(j -> SwingUtilities.invokeLater(() ->
			{
				journeyFetching = false;
				// null = store not mounted. Don't cache it and don't rebuild
				// here; the next rebuild retries.
				if (j == null)
				{
					return;
				}
				boolean moved = journeyMoved(journeyCache, j);
				journeyCache = j;
				if (moved && view == View.SLAYER)
				{
					rebuild();
				}
			}));
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
					card.add(ghostRow("and " + fmt(kcs.size() - 20) + " more. Search finds them", ""));
				}
				p.add(card);
			}
		}
		return p;
	}

	// Has the journey moved since the copy on screen? A finished task, a new
	// one, or another kill on the newest one is everything the block shows.
	private static boolean journeyMoved(ChronicleApiClient.SlayerJourney was,
		ChronicleApiClient.SlayerJourney now)
	{
		if (was == null)
		{
			return true;
		}
		if (was.completedTasks != now.completedTasks
			|| was.totalKills != now.totalKills
			|| was.tasks.size() != now.tasks.size())
		{
			return true;
		}
		return !was.tasks.isEmpty()
			&& was.tasks.get(0).kills != now.tasks.get(0).kills;
	}

	// One task on its own: what it was made of and what it dropped. The
	// monster's whole lifetime bag is a button away at the bottom.
	private JPanel buildTaskDetail(int index)
	{
		JPanel p = column();
		JPanel back = row("< Back", "", null);
		JLabel bl = (JLabel) ((BorderLayout) back.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		bl.setFont(FontManager.getRunescapeSmallFont());
		bl.setForeground(accent());
		back.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		back.addMouseListener(clicker(() ->
		{
			detailTask = -1;
			rebuild();
		}));
		p.add(back);
		p.add(vgap(4));
		ChronicleApiClient.SlayerJourney j = journeyCache;
		ChronicleApiClient.SlayerTask t = j != null && index >= 0 && index < j.tasks.size()
			? j.tasks.get(index) : null;
		if (t == null)
		{
			p.add(note("That task is no longer in the journal."));
			return p;
		}
		JPanel head = card(t.task.toUpperCase(Locale.ROOT));
		String kills = t.inProgress && t.assignment > t.kills
			? fmt(t.kills) + " / " + fmt(t.assignment) : fmt(t.kills);
		head.add(row("Kills logged", kills, accent()));
		if (t.noLootKills > 0)
		{
			head.add(row("Killed without loot", fmt(t.noLootKills), null));
		}
		head.add(row("The take", gp(t.totalValue) + " gp", null));
		if (t.ts > 0)
		{
			head.add(row(t.inProgress ? "Started" : "Finished",
				TASK_DAY.format(Instant.ofEpochMilli((long) (t.ts * 1000))), null));
		}
		p.add(head);
		p.add(vgap(6));

		// What the assignment was made of: brutals, superiors and a boss detour
		// all count toward one task.
		List<LocalStore.UntakenRow> monsters = plugin.slayerTaskMonsters(index);
		if (!monsters.isEmpty())
		{
			p.add(group("Killed"));
			for (LocalStore.UntakenRow m : monsters)
			{
				JPanel r = row(m.name, "×" + fmt(m.qty), null);
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String who = m.name;
				r.addMouseListener(clicker(() -> openSourceLoose(who)));
				p.add(r);
			}
			p.add(vgap(6));
		}

		List<LocalStore.BagItem> bag = plugin.slayerTaskItems(index);
		if (bag.isEmpty())
		{
			p.add(note("No loot recorded against this task."));
		}
		else
		{
			p.add(group("Loot from this task"));
			for (LocalStore.BagItem it : bag)
			{
				JPanel r = row(it.name + (it.qty > 1 ? " ×" + fmt(it.qty) : ""),
					gp(it.value) + " gp", null);
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String item = it.name;
				r.addMouseListener(clicker(() -> openItem(item)));
				p.add(r);
			}
		}
		p.add(vgap(8));
		JButton all = new JButton("All kills of " + t.task);
		all.setAlignmentX(Component.LEFT_ALIGNMENT);
		all.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		final String taskName = t.task;
		all.addActionListener(e ->
		{
			detailTask = -1;
			openSourceLoose(taskName);
		});
		p.add(all);
		return p;
	}

	// The Left behind lens drilled from either end: what was left at a source,
	// or where an item was left.
	private JPanel buildLeftBehindDetail()
	{
		JPanel p = column();
		JPanel back = row("< Back", "", null);
		JLabel bl = (JLabel) ((BorderLayout) back.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		bl.setFont(FontManager.getRunescapeSmallFont());
		bl.setForeground(accent());
		back.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		back.addMouseListener(clicker(() ->
		{
			leftBehindSource = null;
			leftBehindItem = null;
			rebuild();
		}));
		p.add(back);
		p.add(vgap(4));

		if (leftBehindSource != null)
		{
			List<LocalStore.BagItem> bag = plugin.untakenItemsOf(leftBehindSource);
			// The headline is the source's own tally. The rows below start later,
			// so they don't sum to it.
			long qty = 0;
			long val = 0;
			for (LocalStore.UntakenRow r : plugin.untakenSources())
			{
				if (r.name.equals(leftBehindSource))
				{
					qty = r.qty;
					val = r.value;
					break;
				}
			}
			JPanel head = card(leftBehindSource.toUpperCase(Locale.ROOT));
			head.add(row("Left on the floor", fmt(qty) + " items", ACCENT_RED));
			head.add(row("Worth", gp(val) + " gp", null));
			p.add(head);
			p.add(vgap(6));
			if (bag.isEmpty())
			{
				p.add(note("The count above is older than the itemised record. "
					+ "What this source leaves behind is listed here from now on."));
				return p;
			}
			p.add(group("Declined"));
			for (LocalStore.BagItem b : bag)
			{
				JPanel r = row(b.name + (b.qty > 1 ? " ×" + fmt(b.qty) : ""),
					gp(b.value) + " gp", ACCENT_RED);
				r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				final String itm = b.name;
				r.addMouseListener(clicker(() ->
				{
					leftBehindItem = itm;
					leftBehindSource = null;
					rebuild();
				}));
				p.add(r);
			}
			return p;
		}

		List<LocalStore.UntakenRow> sources = plugin.untakenSourcesOf(leftBehindItem);
		long qty = 0;
		long val = 0;
		for (LocalStore.UntakenRow r : plugin.untakenItems())
		{
			if (r.name.equals(leftBehindItem))
			{
				qty = r.qty;
				val = r.value;
				break;
			}
		}
		JPanel head = card(leftBehindItem.toUpperCase(Locale.ROOT));
		head.add(row("Left behind", "×" + fmt(qty), ACCENT_RED));
		head.add(row("Worth", gp(val) + " gp", null));
		p.add(head);
		p.add(vgap(6));
		if (sources.isEmpty())
		{
			p.add(note("No source itemised for this yet."));
			return p;
		}
		p.add(group("Left where"));
		for (LocalStore.UntakenRow r : sources)
		{
			JPanel row = row(r.name, "×" + fmt(r.qty) + " · " + gp(r.value) + " gp", ACCENT_RED);
			row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final String src = r.name;
			row.addMouseListener(clicker(() ->
			{
				leftBehindSource = src;
				leftBehindItem = null;
				rebuild();
			}));
			p.add(row);
		}
		return p;
	}

	private void addJourney(JPanel p, ChronicleApiClient.SlayerJourney j)
	{
		if (j.tasks.isEmpty() && j.completedTasks == 0)
		{
			p.add(note("No tasks in the journal yet. They collect as "
				+ "you play with the Slayer plugin on."));
			return;
		}
		JPanel head = card("The journey");
		head.add(row("Tasks done", fmt(j.completedTasks), accent()));
		head.add(row("Kills on task", fmt(j.totalKills), null));
		head.add(row("On-task loot", gp(j.totalValueGp) + " gp", null));
		// Only an imported legacy journal carries this; nothing writes it now.
		if (j.totalXpEst > 0)
		{
			head.add(row("Slayer xp (est.)", gp(j.totalXpEst), null));
		}
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
			// Lit name, no suffix: the card has no room for one.
			card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			final int at = mounted - 1;
			card.addMouseListener(clicker(() ->
			{
				detailTask = at;
				rebuild();
			}));
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

	/** Open a source by a loose name (a task's plural, a kill-log row): exact,
	 *  then singular, then containment, else the raw name and an empty view. */
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

	// The item under the glass: total obtained, worth, and every source of it.
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

	// The source under the glass: kills tracked, the take, and its whole bag.
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
						return;   // store not mounted; retry on the next rebuild
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
			: "Items fill in as you play. The journal prices each drop the "
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
		// Three per row; five clips the names.
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

		JsonObject cl = plugin.clogSnapshot();
		Obtained ob = obtained(cl);
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
			boolean[] lit = lightSlots(slots, ob.byPage.get(page.toLowerCase(Locale.ROOT)), ob.all);
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
				// Pets pages have a dated line to put under a slot. Source and
				// count only appear on rows an older record supplied; the plugin's
				// own pet emit carries the name alone.
				boolean petPage = page.toLowerCase(Locale.ROOT).contains("pet");
				Map<String, LocalStore.PetRow> known = petPage
					? petsByName() : java.util.Collections.emptyMap();
				// And a pet still out there gets the same line read the other way:
				// what has been killed for it, and how much of the field holds it
				// by that point. Only where the rate book prices the pet and the
				// journal has a kill count; the rest of the page is untouched.
				Map<String, GrindBook.PetChase> chases = petPage
					? plugin.petChases(slots) : java.util.Collections.emptyMap();
				// What each slot has to say for itself, settled before a row is
				// mounted: seventy of these lines at once is a wall, so the page is
				// a list of names and each one gives its line up only when asked.
				// The note above them has to know whether any of them has one.
				List<List<JPanel>> detail = new ArrayList<>();
				boolean anyDetail = false;
				for (int i = 0; i < slots.size(); i++)
				{
					String key = slots.get(i).toLowerCase(Locale.ROOT);
					List<JPanel> d = petDetail(lit[i], known.get(key), chases.get(key));
					detail.add(d);
					anyDetail |= !d.isEmpty();
				}
				// One note doing two jobs: that the rows open, and the caveat on
				// what opening one shows. A skilling pet's odds move with the level,
				// and the journal knows the level held now, not the one each log was
				// cut at, so those figures run a little dry.
				if (anyDetail)
				{
					drill.add(note("Click pet to see odds. Skilling odds are based "
						+ "on current level."));
					drill.add(vgap(3));
				}
				for (int i = 0; i < slots.size(); i++)
				{
					String slot = slots.get(i);
					// A pet the journal recorded lights even if its page was never
					// opened. Green owned, red missing, as in game.
					JPanel r = row(slot, "",
						lit[i] || known.get(slot.toLowerCase(Locale.ROOT)) != null
							? ACCENT_SESSION : ACCENT_RED, true);
					drill.add(r);
					List<JPanel> d = detail.get(i);
					if (d.isEmpty())
					{
						// Nothing under it to uncover, so it is not a fold: no hand
						// cursor promising one, and no click that does nothing.
						continue;
					}
					String foldKey = "pets:" + page + ":" + slot.toLowerCase(Locale.ROOT);
					r.setCursor(java.awt.Cursor.getPredefinedCursor(
						java.awt.Cursor.HAND_CURSOR));
					r.addMouseListener(clicker(() -> toggleFold(foldKey)));
					if (foldOpen(foldKey))
					{
						for (JPanel line : d)
						{
							drill.add(line);
						}
					}
				}
				p.add(drill);
				p.add(vgap(3));
			}
		}
		return p;
	}

	// Where the kills went, in the same breath an owned pet uses for its own
	// provenance. Heaviest source first, which is what lets fitChase() give up the
	// tail of the line and keep the source that matters.
	private static String chaseSources(GrindBook.PetChase chase)
	{
		// A skilling pet's sources are the twenty tree types behind one grind, not
		// twenty grinds. Naming them all would spend the whole line on a list nobody
		// reads; the activity and its total say what was done, and which tree carried
		// it is a detail, which is where details go.
		if (chase.activity != null)
		{
			return chase.activity + ", " + fmt(chase.kc) + " " + chase.unit;
		}
		StringBuilder sb = new StringBuilder();
		for (GrindBook.PetSource s : chase.sources)
		{
			if (sb.length() > 0)
			{
				sb.append(" · ");
			}
			sb.append(s.boss).append(", kc ").append(fmt(s.kc));
		}
		return sb.toString();
	}

	// ── fitting the chase line ──────────────────────────────────────────
	//
	// A JLabel clips its own end, so a long source name walks the kill count off
	// the row and leaves "kc" standing over nothing. A name read half way still
	// names the boss; a figure read half way is worth less than no figure at all.
	// So the letters give way here and every digit stays. The hover keeps the
	// sentence whole either way.

	// Three dots as one glyph. The game font draws it as three single pixels, and
	// it is 7px wide where "..." is 9 — worth two more letters of a name. Checked
	// against the .notdef box the font falls back to for a glyph it lacks, which
	// is why an em dash appears nowhere in these strings.
	private static final String ELLIPSIS = "…";
	// Letters kept in front of the ellipsis before a name stops being a name. Only
	// the last source standing is cut below this, and then only to save its count.
	private static final int NAME_FLOOR = 3;
	// Rows are fitted as they are built, before any of them has a graphics context
	// of its own, so the measure comes off a label that is never shown.
	private static final JLabel MEASURE = new JLabel();

	static FontMetrics rowMetrics()
	{
		return MEASURE.getFontMetrics(FontManager.getRunescapeFont());
	}

	// What the look and feel actually draws a vertical scrollbar at. RuneLite's
	// SCROLLBAR_WIDTH is the sidebar's allowance for one, not the width of one;
	// under the client's own LAF the bar is far slimmer, and eight pixels is a
	// whole letter here.
	private static int scrollbarWidth()
	{
		return Math.max(1, new javax.swing.JScrollBar(
			javax.swing.JScrollBar.VERTICAL).getPreferredSize().width);
	}

	// The pixels a chase row's name has, worked out from the layout rather than
	// read off a picture. The sidebar is a fixed width and every inset between it
	// and the label is one of ours:
	//
	//     225   PluginPanel.PANEL_WIDTH       the sidebar's content
	//   +  17   PluginPanel.SCROLLBAR_WIDTH   and its allowance for a bar; this
	//                                         panel is unwrapped (super(false)),
	//                                         so it is the whole 242 itself
	//   -  16   this panel's border, PANEL_INSET a side (the constructor)
	//   -   9   the bar rebuild()'s own scroll pane raises, which a pets page is
	//           always long enough to need, measured off the LAF above
	//   -  16   the drill card's border, CARD_INSET a side (cardPlain)
	//   -   4   the row's border, ROW_INSET a side (row)
	//   -   8   ROW_GAP, between the name and the share
	//   = 189   less the share, which BorderLayout draws at its preferred width.
	static int chaseRoom(String share, FontMetrics fm)
	{
		return PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
			- 2 * PANEL_INSET - scrollbarWidth() - 2 * CARD_INSET
			- 2 * ROW_INSET - ROW_GAP - fm.stringWidth(share);
	}

	// A line under construction: the pieces in order, and which of them are names
	// and may be shortened. Everything else is a figure and is not negotiable.
	private static final class Line
	{
		final List<String> pieces = new ArrayList<>();
		final List<Integer> names = new ArrayList<>();

		void fixed(String s)
		{
			pieces.add(s);
		}

		void name(String s)
		{
			names.add(pieces.size());
			pieces.add(s);
		}

		String whole()
		{
			StringBuilder sb = new StringBuilder();
			for (String s : pieces)
			{
				sb.append(s);
			}
			return sb.toString();
		}
	}

	// A name cut to its first letters. Any trailing space goes with them:
	// "Commander …" is a ragged thing to print.
	private static String stub(String name, int keep)
	{
		if (name.length() <= keep)
		{
			return name;
		}
		int end = keep;
		while (end > 0 && name.charAt(end - 1) == ' ')
		{
			end--;
		}
		return name.substring(0, end) + ELLIPSIS;
	}

	/**
	 * Shortens the named pieces of a line, in the order given, until the whole of it
	 * measures no wider than {@code avail}. Pieces outside {@code order} are left
	 * exactly as they are, which is where the figures live. Returns null when even
	 * every one of those names cut to {@code floor} letters is still too wide: the
	 * caller's cue to give up a whole source rather than cut any further.
	 */
	private static String fitLine(Line line, List<Integer> order, int floor,
		FontMetrics fm, int avail)
	{
		Line work = new Line();
		work.pieces.addAll(line.pieces);
		if (fm.stringWidth(work.whole()) <= avail)
		{
			return work.whole();
		}
		for (int idx : order)
		{
			String name = line.pieces.get(idx);
			for (int keep = name.length() - 1; keep >= floor; keep--)
			{
				work.pieces.set(idx, stub(name, keep));
				String s = work.whole();
				if (fm.stringWidth(s) <= avail)
				{
					return s;
				}
			}
			// As short as this one goes. Keep whichever form is the narrower — a
			// stub of a short name can cost more than the name — and move along to
			// the next name in the order.
			String shortest = stub(name, floor);
			work.pieces.set(idx,
				fm.stringWidth(shortest) < fm.stringWidth(name) ? shortest : name);
		}
		return null;
	}

	// The first {@code kept} sources, heaviest first, and a count of the ones left
	// off the end so the reader knows they are there. The hover names them.
	private static Line sourceLine(List<GrindBook.PetSource> src, int kept, String mark)
	{
		Line l = new Line();
		for (int i = 0; i < kept; i++)
		{
			if (i > 0)
			{
				l.fixed(" · ");
			}
			l.name(src.get(i).boss);
			l.fixed(", kc " + fmt(src.get(i).kc));
		}
		if (kept < src.size())
		{
			l.fixed(mark + (src.size() - kept));
		}
		return l;
	}

	// How the sources left off are marked. The first form sets them off with the
	// same separator the kept ones use, so "kc 1 · +1" cannot be read as a count of
	// two; the second is for a row too tight to afford that, where a bare mark
	// still beats giving up another name.
	private static final String[] DROP_MARKS = {" · +", " +"};

	// The names of a line, last first: a line gives up its tail before its head,
	// because the sources are sorted with the one that carried the grind in front.
	private static List<Integer> tailFirst(Line l, int from)
	{
		List<Integer> order = new ArrayList<>(l.names.subList(from, l.names.size()));
		java.util.Collections.reverse(order);
		return order;
	}

	// The chase line as the row will print it: the words chaseSources() gives the
	// hover, cut to what the row can hold.
	static String fitChase(GrindBook.PetChase chase, String share)
	{
		FontMetrics fm = rowMetrics();
		int avail = chaseRoom(share, fm);
		if (chase.activity != null)
		{
			// A skilling chase is one activity and the noun its attempts are
			// counted in, with the count between them. The noun gives way first,
			// then the activity; the count gives way to nothing.
			Line l = new Line();
			l.name(chase.activity);
			l.fixed(", " + fmt(chase.kc) + " ");
			l.name(chase.unit);
			String s = fitLine(l, tailFirst(l, 0), NAME_FLOOR, fm, avail);
			if (s == null)
			{
				s = fitLine(l, tailFirst(l, 0), 1, fm, avail);
			}
			return s != null ? s : l.whole();
		}
		List<GrindBook.PetSource> src = chase.sources;
		if (src.isEmpty())
		{
			return "";
		}
		for (int kept = src.size(); kept >= 1; kept--)
		{
			for (String mark : kept < src.size() ? DROP_MARKS : new String[]{""})
			{
				Line l = sourceLine(src, kept, mark);
				// The trailing names give way first, and the leading one is not cut
				// into at all while there is still a whole source to give up.
				String s = fitLine(l, tailFirst(l, 1), NAME_FLOOR, fm, avail);
				if (s != null)
				{
					return s;
				}
			}
		}
		// One source left and its name is still too long for the row. Now it has to
		// give: a boss half-named is still that boss, and "kc" over nothing is not
		// a kill count.
		Line l = sourceLine(src, 1, DROP_MARKS[DROP_MARKS.length - 1]);
		String s = fitLine(l, l.names, 1, fm, avail);
		return s != null ? s : l.whole();
	}

	// The whole sentence, kept for the hover: the row itself has about 135px (see
	// chaseRoom()), and two sources with long names run well past it.
	private static String chaseTip(GrindBook.PetChase chase)
	{
		double pct = chase.percentileDry;
		String share = pct < 1 ? "Under 1%" : pct > 99 ? "Over 99%" : Math.round(pct) + "%";
		StringBuilder sb = new StringBuilder(share + " of players have " + chase.pet
			+ " by this point. " + chaseSources(chase));
		if (chase.activity != null && chase.sources.size() > 1)
		{
			// the line spent itself on the activity, so the hover names the one
			// source that carried it. Only where there were others to carry it
			// instead: "Mad Angel, 124 kills, mostly mad angel" says nothing twice.
			sb.append(", mostly ").append(chase.sources.get(0).boss.toLowerCase(Locale.ROOT));
		}
		if (chase.level > 0)
		{
			sb.append(". Priced at ").append(chase.level)
				.append(", the level you hold now, not the level each one was rolled at");
		}
		return sb.append(".").toString();
	}

	// Everything a pets page has to say under one slot, built whether or not the
	// slot is open: an owned pet's provenance and date, or how far the chase for an
	// unearned one has run. Empty where the journal has neither, and an empty list
	// is what makes a row inert rather than a fold with nothing behind it.
	private static List<JPanel> petDetail(boolean lit, LocalStore.PetRow pet,
		GrindBook.PetChase chase)
	{
		List<JPanel> out = new ArrayList<>();
		if (pet != null)
		{
			StringBuilder line = new StringBuilder();
			if (pet.source != null && !pet.source.isEmpty())
			{
				line.append(pet.source);
				if (pet.kc > 0)
				{
					// A skilling pet has no kill count; what was recorded is the
					// xp behind it.
					line.append(isSkill(pet.source)
						? ", " + fmt(pet.kc) + " xp"
						: ", kc " + fmt(pet.kc));
				}
			}
			// No provenance, no line: the plugin's own pet emit carries the name
			// alone, and an empty label with a date adrift at the right margin
			// reads as a fault.
			if (line.length() > 0)
			{
				out.add(ghostRow(line.toString(), pet.ts > 0
					? TASK_DAY.format(Instant.ofEpochMilli(pet.ts)) : ""));
			}
		}
		else if (!lit && chase != null)
		{
			// The share is drawn at its own width, so it is what the name has left
			// to fit inside.
			String share = holdShare(chase);
			JPanel r = ghostRow(fitChase(chase, share), share,
				chase.percentileDry >= 90 ? ACCENT_RED : null);
			out.add(tipped(r, chaseTip(chase)));
		}
		return out;
	}

	// One tooltip over a row and everything in it: a panel's own tip never fires,
	// the label under the pointer swallows it.
	private static JPanel tipped(JPanel r, String tip)
	{
		r.setToolTipText(tip);
		for (Component c : r.getComponents())
		{
			if (c instanceof javax.swing.JComponent)
			{
				((javax.swing.JComponent) c).setToolTipText(tip);
			}
		}
		return r;
	}

	// The share of players who hold the pet by this point. Clipped at both ends
	// rather than rounded to them: "0% have" under a kill count that exists reads
	// as a fault, and nothing short of certainty should print as certainty. Terse
	// because the row is barely 190px and this is measured out of the source list's
	// share of it, not added beside it.
	private static String holdShare(GrindBook.PetChase chase)
	{
		double pct = chase.percentileDry;
		if (pct < 1)
		{
			return "<1% have";
		}
		if (pct > 99)
		{
			return ">99% have";
		}
		return Math.round(pct) + "% have";
	}

	// True when a pet's source names a skill, not a monster.
	private static boolean isSkill(String source)
	{
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			if (sk.name().equalsIgnoreCase(source))
			{
				return true;
			}
		}
		return false;
	}

	// The journal's pet record, keyed by lower-cased name for slot lookup.
	private Map<String, LocalStore.PetRow> petsByName()
	{
		Map<String, LocalStore.PetRow> out = new LinkedHashMap<>();
		for (LocalStore.PetRow r : plugin.pets())
		{
			out.putIfAbsent(r.name.toLowerCase(Locale.ROOT), r);
		}
		return out;
	}

	// The two halves of the journal's stored log: the whole-log obtained set, and
	// each page's own capture. Names lower-cased for slot lookup.
	private static final class Obtained
	{
		final Map<String, Long> all = new LinkedHashMap<>();
		final Map<String, Map<String, Long>> byPage = new LinkedHashMap<>();
	}

	private static Obtained obtained(JsonObject cl)
	{
		Obtained o = new Obtained();
		if (cl.has("clog_items") && cl.get("clog_items").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: cl.getAsJsonObject("clog_items").entrySet())
			{
				o.all.merge(e.getKey().toLowerCase(Locale.ROOT), safeLong(e.getValue()), Math::max);
			}
		}
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
				o.byPage.put(pg.getKey().toLowerCase(Locale.ROOT), items);
			}
		}
		return o;
	}

	/**
	 * Which slots of a page the player holds. A slot lights when its name is in
	 * the page's own capture or the whole-log obtained set; duplicate-named slots
	 * (My Notes' 26 "Ancient page" entries) light positionally, k copies lighting
	 * the first k, as the game does.
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

	// One named slot on one page, by the same rule the Log tab lights it with. A
	// duplicate-named slot counts as held when any of its copies is lit.
	private static boolean slotHeld(String slot, String page, List<String> pageSlots, Obtained ob)
	{
		boolean[] lit = lightSlots(pageSlots, ob.byPage.get(page.toLowerCase(Locale.ROOT)), ob.all);
		for (int i = 0; i < pageSlots.size(); i++)
		{
			if (lit[i] && pageSlots.get(i).equalsIgnoreCase(slot))
			{
				return true;
			}
		}
		return false;
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

	// Parse the bundled taxonomy once. Order is preserved, tabs and slots.
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
			// A missing or corrupt resource just leaves the browser empty.
		}
		taxonomy = out;
		return out;
	}

	// One line of pace for a skill, or the date it last moved. Days of PLAY, not
	// calendar days: an idle stretch doesn't dilute it.
	private void addPaceLine(JPanel p, String section)
	{
		PaceBook.Pace pace;
		try
		{
			pace = plugin.pace(section);
		}
		catch (RuntimeException e)
		{
			// pace() throws on an unknown skill name; a real fault in there hides
			// here too, as a section that simply prints no pace line.
			return;
		}
		if (pace == null)
		{
			return;
		}
		if (pace.hasHorizon())
		{
			String target = pace.targetLevel != null
				? String.valueOf(pace.targetLevel) : "200m";
			p.add(ghostRow(target + " in " + fmt(pace.daysOfPlay)
				+ (pace.daysOfPlay == 1 ? " day of play" : " days of play"),
				gp((long) pace.xpPerActiveDay) + "/day"));
			if (pace.activeDays < 3)
			{
				p.add(ghostRow("measured over " + pace.activeDays
					+ (pace.activeDays == 1 ? " day" : " days"), ""));
			}
		}
		else if (pace.dormant() && pace.lastActive != null)
		{
			p.add(ghostRow("last moved " + pace.lastActive.format(TASK_DAY), ""));
		}
	}

	// Every fold in the panel that stands open this session, one key apiece:
	// family:section on Stats, family:craft:verb a level under it, pets:page:name
	// on a collection log pets page, and FOLD_HOME_XP for the one on Home. A field,
	// not a local, because rebuild() throws the whole panel away several times a
	// minute and a reader's fold has to outlive that. Everything foldable starts
	// folded, and the register is dropped whole when the account changes. The preview
	// harness reaches this by name, so a rename here has to be made there too.
	private final java.util.Set<String> openFolds = new java.util.HashSet<>();

	// Home's xp total, broken out per skill.
	private static final String FOLD_HOME_XP = "home:xp";

	/** True while the fold under this key stands open. */
	private boolean foldOpen(String key)
	{
		return openFolds.contains(key);
	}

	/** Open a shut fold or shut an open one, and redraw. Every fold comes here. */
	private void toggleFold(String key)
	{
		if (!openFolds.remove(key))
		{
			openFolds.add(key);
		}
		rebuild();
	}

	// gp per consumable key, refreshed per rebuild. What the Food and Potions
	// rows put beside the count.
	private Map<String, Long> consumVals = new LinkedHashMap<>();

	// Resource-scoped drops, refreshed per rebuild. Rides the gathered row as a
	// margin. Subtract one from the other and a miner's career reads as zero.
	private long resourcesDropped;

	private String rowValue(Map.Entry<String, Long> e)
	{
		String base = StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
		if (e.getKey().equals("resourcesGatheredValue") && resourcesDropped > 0)
		{
			return base + " · " + gp(resourcesDropped) + " dropped";
		}
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

		// Rows file into sections. Generic floor totals (logsChopped,
		// teleportsTotal) head their section instead of listing as a row, and the
		// unresolved remainder reconciles as a ghost "Other".
		Map<String, Long> counters = counters();
		resourcesDropped = counters.getOrDefault("resourcesDroppedValue", 0L);
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
			p.add(note("Nothing tracked here yet."));
			return p;
		}

		// Destinations nest inside the Teleports fold.
		List<Map.Entry<String, Long>> destRows = statsFamily.equals("Ledger & Roads")
			? rowsBySection.remove("Destinations") : null;
		if (destRows != null && !rowsBySection.containsKey("Teleports")
			&& !floorTotals.containsKey("Teleports"))
		{
			rowsBySection.put("Destinations", destRows);   // no host fold, stand alone
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
			boolean open = foldOpen(stateKey);
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
			head.addMouseListener(clicker(() -> toggleFold(stateKey)));
			p.add(head);
			if (open)
			{
				if (statsFamily.equals("Skilling"))
				{
					addPaceLine(p, sec);
				}
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
					// A section with no typed rows opens to its floors, one row
					// each: bones buried and bones offered are separate verbs and
					// can't share a row.
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

	// Sections in display order: Skilling's crafts rank by weight, the other
	// families keep the registry's fixed order with strays appended.
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
			// A craft weighs its floor total when it has one; that is the headline
			// count. Otherwise the sum of its rows.
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
	 * Multi-verb crafts drill one level deeper: Prayer opens to Bones buried ·
	 * Ashes scattered · Ensouled heads, each fold reconciling to its own floor,
	 * with the verbless totals (Ashes sacrificed) as flat rows above. Returns
	 * false under two verb groups, and the caller renders the flat list instead.
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
			boolean open = foldOpen(stateKey);
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

	// Destinations sit one level under Teleports: where the roads led.
	private void addDestinationsFold(JPanel p, List<Map.Entry<String, Long>> destRows)
	{
		destRows.sort(StatRegistry::compareRows);
		long sum = 0;
		for (Map.Entry<String, Long> e : destRows)
		{
			sum += e.getValue();
		}
		String stateKey = "Ledger & Roads:Destinations";
		boolean open = foldOpen(stateKey);
		p.add(subHead("Destinations", fmt(sum), stateKey, open));
		if (open)
		{
			for (Map.Entry<String, Long> e : destRows)
			{
				p.add(row(StatRegistry.label(e.getKey()), value(e), null));
			}
		}
	}

	// A second-level fold header: normal case, indented, click to toggle.
	private JPanel subHead(String label, String totalStr, String stateKey, boolean open)
	{
		JPanel head = row(label, totalStr, open ? accent() : null);
		JLabel name = (JLabel) ((BorderLayout) head.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(open ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
		head.setBorder(BorderFactory.createEmptyBorder(3, 10, 1, 2));
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addMouseListener(clicker(() -> toggleFold(stateKey)));
		return head;
	}

	private static String value(Map.Entry<String, Long> e)
	{
		return StatRegistry.isGp(e.getKey()) ? gp(e.getValue()) + " gp" : fmt(e.getValue());
	}

	// A quiet row for a remainder or an aside.
	private static JPanel ghostRow(String left, String right)
	{
		return ghostRow(left, right, null);
	}

	// The same aside, with a value allowed its own colour: the line still reads as
	// an aside, but a drought can flare.
	private static JPanel ghostRow(String left, String right, Color rightColor)
	{
		JPanel r = row(left, right, rightColor);
		((JLabel) ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.CENTER))
			.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker().darker());
		return r;
	}

	// How deep the milestone scan reads into the feed; a year-long window still
	// has to find its own entries.
	private static final int HISTORY_FEED_SCAN = 2000;

	// The two reads the History tab lives on, held between rebuilds. The spine is
	// a whole parse of an append-only file and the feed slice is deep-copied under
	// the store's lock. Both are gathered on a worker thread; on the EDT that cost
	// lands as a stall on every pill click.
	private java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> historySpine;
	private List<JsonObject> historyFeed = new ArrayList<>();
	// What that pair was true of: the day it was read and the newest feed entry
	// it saw. Either one moving means the cache is stale.
	private java.time.LocalDate historyDay;
	private long historyFeedTs;
	private boolean historyGathering;
	// A gather in flight when a different journal mounts must not land; its
	// spine belongs to the account that has gone.
	private int historyEpoch;

	// One gathered pass over the journal's calendar spine and its feed.
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
	 * Read the spine and the feed slice off the EDT, then mount them. Primed when
	 * the panel is built and whenever a journal mounts; the tab is usually warm
	 * before it is opened. EDT only.
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
					// Another account mounted mid-read; its own gather owns the
					// cache now.
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
					// leave the cache cold; the next rebuild asks again
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

	// The newest feed entry's stamp, or 0. The cheap staleness probe.
	private static long newestTs(List<JsonObject> feed)
	{
		return feed.isEmpty() ? 0 : safeLong(feed.get(0).get("ts"));
	}

	/**
	 * What died, and what the period added. The collection log's tally is the
	 * spine, floored by the drop ledger: the list reads as bosses and activities,
	 * not every creature ever killed.
	 *
	 * <p>Kill counts only entered the daily baseline later, so a period bounded
	 * by an older line reports the standing count and no gain.
	 */
	private void addKillCounts(JPanel p, Map<String, Long> beforeKc, Map<String, Long> nowKc)
	{
		Map<String, Long> standing = plugin.killCounts();
		if (standing.isEmpty())
		{
			p.add(note("No kill counts recorded yet: they come from the "
				+ "collection log and from what the drop ledger witnesses."));
			return;
		}
		Map<String, Long> gained = new LinkedHashMap<>();
		if (beforeKc != null && !beforeKc.isEmpty() && nowKc != null)
		{
			for (Map.Entry<String, Long> e : nowKc.entrySet())
			{
				Long was = beforeKc.get(e.getKey());
				if (was != null && e.getValue() - was > 0)
				{
					gained.put(e.getKey(), e.getValue() - was);
				}
			}
		}
		if (!gained.isEmpty())
		{
			long total = 0;
			for (long v : gained.values())
			{
				total += v;
			}
			JPanel head = card("The period");
			head.add(row("Kills", "+" + fmt(total), accent()));
			p.add(head);
			p.add(vgap(5));
		}
		else if (beforeKc == null || beforeKc.isEmpty())
		{
			p.add(note("Kill counts begin their record now. This period has no "
				+ "earlier count to measure against."));
			p.add(vgap(4));
		}

		// What moved this period first, then what stands highest.
		Comparator<Map.Entry<String, Long>> byGainThenTotal = (a, b) ->
		{
			long ga = gained.getOrDefault(a.getKey(), 0L);
			long gb = gained.getOrDefault(b.getKey(), 0L);
			return ga != gb ? Long.compare(gb, ga) : Long.compare(b.getValue(), a.getValue());
		};

		List<Map.Entry<String, Long>> rows = new ArrayList<>(standing.entrySet());
		rows.sort(byGainThenTotal);
		JPanel card = card("Bosses and activities");
		for (Map.Entry<String, Long> e : rows)
		{
			card.add(kcRow(e.getKey(), e.getValue(), gained.get(e.getKey())));
		}
		p.add(card);
		p.add(vgap(6));

		// Everything else the drop ledger counted. The collection log knows what
		// counts as a boss; the ledger doesn't.
		List<Map.Entry<String, Long>> rest = new ArrayList<>(plugin.ledgerKills().entrySet());
		rest.sort(byGainThenTotal);
		if (!rest.isEmpty())
		{
			JPanel other = card("Everything else counted");
			int mounted = 0;
			for (Map.Entry<String, Long> e : rest)
			{
				if (mounted++ >= histKcShown)
				{
					break;
				}
				other.add(kcRow(e.getKey(), e.getValue(), gained.get(e.getKey())));
			}
			p.add(other);
			if (rest.size() > histKcShown)
			{
				p.add(vgap(3));
				JButton more = new JButton("Show " + Math.min(ROW_CAP, rest.size() - histKcShown)
					+ " more of " + fmt(rest.size()));
				more.addActionListener(e ->
				{
					histKcShown += ROW_CAP;
					rebuild();
				});
				p.add(more);
			}
			p.add(vgap(6));
		}
	}

	private int histKcShown = ROW_CAP;

	// One counted thing: what it stands at, and what the period added.
	private JPanel kcRow(String name, long standing, Long gained)
	{
		JPanel r = row(name, fmt(standing) + (gained != null ? "  +" + fmt(gained) : ""),
			gained != null ? accent() : null);
		r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		r.addMouseListener(clicker(() -> openSourceLoose(name)));
		return r;
	}

	// Hiscores order. An ORDER only. The grid is built from the client's own
	// skill list, so a skill Jagex adds shows up without an edit here. Overall is
	// drawn separately, as its own headline.
	private static final String[] SKILL_ORDER_NAMES = {
		"ATTACK", "HITPOINTS", "MINING", "STRENGTH", "AGILITY", "SMITHING",
		"DEFENCE", "HERBLORE", "FISHING", "RANGED", "THIEVING", "COOKING",
		"PRAYER", "CRAFTING", "FIREMAKING", "MAGIC", "FLETCHING", "WOODCUTTING",
		"RUNECRAFT", "SLAYER", "FARMING", "CONSTRUCTION", "HUNTER",
	};

	// Every skill the client knows, in the order a player reads them.
	private static List<net.runelite.api.Skill> skillOrder()
	{
		List<net.runelite.api.Skill> out = new ArrayList<>();
		for (String name : SKILL_ORDER_NAMES)
		{
			try
			{
				out.add(net.runelite.api.Skill.valueOf(name));
			}
			catch (IllegalArgumentException dropped)
			{
				// a skill this client no longer has, simply not drawn
			}
		}
		for (net.runelite.api.Skill sk : net.runelite.api.Skill.values())
		{
			if (sk != net.runelite.api.Skill.OVERALL && !out.contains(sk))
			{
				out.add(sk);
			}
		}
		return out;
	}

	// The hiscores grid: every skill's standing level and the period's gain. A
	// skill that didn't move keeps its place and says nothing.
	private void addSkillGrid(JPanel p, List<Map.Entry<String, Long>> gains,
		Map<String, Long> closingXp)
	{
		Map<String, Long> gain = new LinkedHashMap<>();
		long totalGained = 0;
		for (Map.Entry<String, Long> g : gains)
		{
			gain.put(g.getKey(), g.getValue());
			totalGained += g.getValue();
		}
		Map<String, long[]> sheet = plugin.skillSheet();

		JPanel head = card("The period");
		long[] ov = sheet.get("overall");
		head.add(row(ov != null && ov[0] > 0 ? "Total level " + fmt(ov[0]) : "Experience",
			totalGained > 0 ? "+" + gp(totalGained) : "nothing gained",
			totalGained > 0 ? accent() : null));
		if (!gains.isEmpty())
		{
			Map.Entry<String, Long> top = gains.get(0);
			head.add(row("Biggest gain", StatRegistry.prettify(top.getKey())
				+ " +" + gp(top.getValue()), null));
		}
		p.add(head);
		p.add(vgap(5));

		JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (net.runelite.api.Skill sk : skillOrder())
		{
			String key = sk.name().toLowerCase(Locale.ROOT);
			long[] cur = sheet.get(key);
			long level = cur != null ? cur[0] : 0;
			// No sheet yet: read the level off the spine's xp.
			if (level <= 0)
			{
				level = PaceBook.levelAt(closingXp.getOrDefault(key, 0L));
			}
			grid.add(skillCell(sk, level, gain.get(key)));
		}
		p.add(grid);
		p.add(vgap(6));
	}

	// One skill: its icon, the level it stands at, and the period's gain.
	private JPanel skillCell(net.runelite.api.Skill sk, long level, Long gained)
	{
		JPanel cell = new JPanel(new BorderLayout(3, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		cell.setToolTipText(StatRegistry.prettify(sk.name().toLowerCase(Locale.ROOT))
			+ (gained != null ? ", +" + gp(gained) + " this period" : ""));

		JLabel icon = new JLabel();
		java.awt.image.BufferedImage img = skillIcon(sk);
		if (img != null)
		{
			icon.setIcon(new javax.swing.ImageIcon(img));
		}
		else
		{
			// No sprite cache: the skill's first letters, or the grid is nameless
			// numbers.
			icon.setText(sk.name().substring(0, Math.min(3, sk.name().length())));
			icon.setFont(FontManager.getRunescapeSmallFont());
			icon.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		}
		cell.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel(new GridLayout(gained != null ? 2 : 1, 1));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel lvl = new JLabel(level > 0 ? String.valueOf(level) : "-");
		lvl.setFont(FontManager.getRunescapeSmallFont());
		// A skill that moved is lit; the rest stay quiet.
		lvl.setForeground(gained != null ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR.darker());
		text.add(lvl);
		if (gained != null)
		{
			JLabel g = new JLabel("+" + gp(gained));
			g.setFont(FontManager.getRunescapeSmallFont());
			g.setForeground(accent());
			text.add(g);
		}
		cell.add(text, BorderLayout.CENTER);
		return cell;
	}

	private static final Map<net.runelite.api.Skill, java.awt.image.BufferedImage> SKILL_ICONS =
		new java.util.EnumMap<>(net.runelite.api.Skill.class);

	// The game's own skill icon, loaded once and shared.
	private java.awt.image.BufferedImage skillIcon(net.runelite.api.Skill sk)
	{
		return SKILL_ICONS.computeIfAbsent(sk, s ->
		{
			try
			{
				java.awt.image.BufferedImage img = plugin.skillIcons().getSkillImage(s, true);
				return img;
			}
			catch (RuntimeException e)
			{
				return null;   // a dev client without the sprite cache
			}
		});
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
		p.add(vgap(3));

		JPanel lens = new JPanel(new GridLayout(1, 2, 3, 3));
		lens.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String which : new String[]{"Skills", "Bosses"})
		{
			boolean on = "Bosses".equals(which) == histBosses;
			JLabel t = new JLabel(which, JLabel.CENTER);
			t.setOpaque(true);
			t.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			t.setFont(FontManager.getRunescapeSmallFont());
			t.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			t.setForeground(on ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			t.addMouseListener(clicker(() ->
			{
				histBosses = "Bosses".equals(which);
				rebuild();
			}));
			lens.add(t);
		}
		p.add(lens);
		p.add(vgap(5));

		// the period under the cursor — or the exact dates the player typed
		java.time.LocalDate end = histCursor;
		java.time.LocalDate start;
		String label;
		if (histFrom != null && histTo != null)
		{
			start = histFrom;
			end = histTo.isAfter(java.time.LocalDate.now()) ? java.time.LocalDate.now() : histTo;
			label = start.format(TASK_DAY) + " - " + end.format(TASK_DAY);
		}
		else
		{
			switch (histGranularity)
			{
				case "Day":
					start = end;
					label = end.format(FULL_DAY);
					break;
				case "Month":
					start = end.withDayOfMonth(1);
					end = start.plusMonths(1).minusDays(1);
					label = start.format(MONTH_YEAR);
					break;
				case "Year":
					start = end.withDayOfYear(1);
					end = start.plusYears(1).minusDays(1);
					label = String.valueOf(start.getYear());
					break;
				case "Week":
				default:
					start = end.minusDays(6);
					label = start.format(DAY) + " - " + end.format(FULL_DAY);
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
		lbl.setToolTipText("Set exact dates");
		lbl.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		lbl.addMouseListener(clicker(() -> onSetExactDates(pStart, pEnd)));
		stepper.add(back, BorderLayout.WEST);
		stepper.add(lbl, BorderLayout.CENTER);
		stepper.add(fwd, BorderLayout.EAST);
		p.add(stepper);
		p.add(vgap(6));

		// Ask for a fresh pass when the day has turned or the feed has grown.
		// Probing the newest entry costs one copy; the gather costs thousands,
		// and the stale pair still renders while it runs.
		if (historySpine == null || !java.time.LocalDate.now().equals(historyDay)
			|| newestTs(plugin.feedNewest(1)) != historyFeedTs)
		{
			gatherHistory();
		}
		if (historySpine == null)
		{
			p.add(note("Reading your history…"));
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
				empty = "The record starts today: baselines close at each login, "
					+ "day rollover and logout, and a period is the distance "
					+ "between two of them.";
			}
			else if (!hist.isEmpty() && hist.firstKey().isBefore(pStart)
				&& ("Day".equals(histGranularity) || "Week".equals(histGranularity)))
			{
				// The imported past resolves by month; day and week windows inside
				// it hold no interior baseline.
				empty = "The imported past resolves by month. Switch to Month "
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
			// Say so when the nearest earlier baseline sits well before the
			// window, or a month of xp reads as one week's gain.
			if (before != null && before.getKey().isBefore(pStart.minusDays(1)))
			{
				p.add(note("Measured since " + before.getKey().format(FULL_DAY)
					+ ", the nearest earlier baseline."));
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
					// A skill missing from the before-side has nothing to measure
					// against: imported baselines predate newer skills, and
					// absence-as-zero painted a lifetime as one week's gain.
					continue;
				}
				long d = e.getValue() - beforeSk.get(e.getKey());
				if (d > 0)
				{
					gains.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), d));
				}
			}
			gains.sort(Map.Entry.<String, Long>comparingByValue().reversed());
			if (histBosses)
			{
				addKillCounts(p, before != null ? before.getValue().kcs : null,
					at.getValue().kcs);
			}
			else
			{
				addSkillGrid(p, gains, at.getValue().skills);
			}

			Map<String, Long> beforeCt = before != null ? before.getValue().counters
				: new LinkedHashMap<>();
			List<Map.Entry<String, Long>> movers = new ArrayList<>();
			// Imported baselines carry no counters: a key absent from the
			// before-side is no data, not a zero. Same rule as the xp above.
			if (before != null && !beforeCt.isEmpty())
			{
				for (Map.Entry<String, Long> e : at.getValue().counters.entrySet())
				{
					if (!beforeCt.containsKey(e.getKey()))
					{
						continue;
					}
					long d = e.getValue() - beforeCt.get(e.getKey());
					if (d > 0 && !LocalStore.MAX_KEYS.contains(e.getKey())
						&& !StatRegistry.hidden(e.getKey())
						&& !StatRegistry.isFloor(e.getKey()))
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

	// Any two dates: a small dialog, ISO or d/M/yyyy, prefilled with the
	// visible period.
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
				"Dates read as yyyy-mm-dd (or d/m/yyyy). Nothing changed.");
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

	// The kinds a reader looks for, and the feed types behind each. Kept few. A
	// row per type reads as a database query.
	private static final String[][] JOURNAL_LENSES = {
		{"All"},
		{"Log", "COLLECTION"},
		{"Slayer", "SLAYER"},
		{"Feats", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "PET", "LEVEL"},
		{"Deaths", "DEATH"},
		{"Sessions", "SESSION"},
	};
	private String journalLens = "All";
	private int journalShown = 60;

	private JPanel buildJournal()
	{
		JPanel p = column();
		addFrontispiece(p);

		JPanel lenses = new JPanel(new GridLayout(0, 3, 3, 3));
		lenses.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (String[] lens : JOURNAL_LENSES)
		{
			boolean on = lens[0].equals(journalLens);
			JLabel t = new JLabel(lens[0], JLabel.CENTER);
			t.setOpaque(true);
			t.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			t.setFont(FontManager.getRunescapeSmallFont());
			t.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			t.setForeground(on ? accent() : ColorScheme.LIGHT_GRAY_COLOR.darker());
			t.addMouseListener(clicker(() ->
			{
				journalLens = lens[0];
				journalShown = 60;
				rebuild();
			}));
			lenses.add(t);
		}
		p.add(lenses);
		p.add(vgap(6));

		java.util.Set<String> wanted = new java.util.HashSet<>();
		for (String[] lens : JOURNAL_LENSES)
		{
			if (lens[0].equals(journalLens))
			{
				wanted.addAll(java.util.Arrays.asList(lens).subList(1, lens.length));
			}
		}
		// Read deep: a lens over the newest fifty finds nothing rare.
		List<JsonObject> all = plugin.feedNewest(4000);
		List<JsonObject> feed = new ArrayList<>();
		for (JsonObject e : all)
		{
			if (wanted.isEmpty() || (e.has("type")
				&& wanted.contains(e.get("type").getAsString())))
			{
				feed.add(e);
			}
		}
		if (feed.isEmpty())
		{
			p.add(note("All".equals(journalLens)
				? "Milestones (pets, log slots, tasks, quests, deaths) are noted "
					+ "here as they happen."
				: "Nothing of that kind on the record yet."));
			return p;
		}

		String lastDay = null;
		int mounted = 0;
		for (JsonObject e : feed)
		{
			if (mounted++ >= journalShown)
			{
				break;
			}
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
		if (feed.size() > journalShown)
		{
			p.add(vgap(6));
			JButton more = new JButton("Read further back");
			more.setAlignmentX(Component.LEFT_ALIGNMENT);
			more.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
			more.addActionListener(ev ->
			{
				journalShown += 60;
				rebuild();
			});
			p.add(more);
		}
		return p;
	}

	// The nameplate at the top of the Journal tab: the account's particulars,
	// stated once.
	private void addFrontispiece(JPanel p)
	{
		String rsn = plugin.displayRsn();
		JPanel plate = card(rsn != null && !rsn.isEmpty()
			? "The journal of " + rsn : "The journal");

		long since = plugin.keptSince();
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> spine = historySpine;
		if (since > 0)
		{
			plate.add(row("Kept since",
				TASK_DAY.format(Instant.ofEpochMilli(since)), accent()));
		}
		if (spine != null && !spine.isEmpty())
		{
			plate.add(row("Days written", fmt(spine.size()), null));
		}
		Map<String, long[]> sheet = plugin.skillSheet();
		long[] overall = sheet.get("overall");
		int combat = plugin.combatLevel();
		if (overall != null && overall[0] > 0)
		{
			plate.add(row("Total level", fmt(overall[0])
				+ (combat > 0 ? " · combat " + combat : ""), null));
		}
		int fin = plugin.clogFinished();
		if (fin > 0)
		{
			plate.add(row("Collection log", fmt(fin) + " / "
				+ fmt(Math.max(plugin.clogAvailable(), fin)), null));
		}
		p.add(plate);

		// One margin note, when the record has something to remark on.
		String note = frontispieceNote();
		if (note != null)
		{
			p.add(ghostRow(note, ""));
		}
		p.add(vgap(6));
	}

	// The remark under the plate: the longest chase still owing, or the gap the
	// record just came back from.
	private String frontispieceNote()
	{
		if (grindsCache != null)
		{
			for (ChronicleApiClient.GrindRow g : grindsCache)
			{
				if (g.percentileDry >= 90)
				{
					return "still owed a " + g.item.toLowerCase(Locale.ROOT)
						+ " at " + fmt(g.kc) + " " + g.boss.toLowerCase(Locale.ROOT);
				}
			}
		}
		List<JsonObject> recent = plugin.feedNewest(2);
		if (recent.size() == 2)
		{
			long a = recent.get(0).has("ts") ? recent.get(0).get("ts").getAsLong() : 0;
			long b = recent.get(1).has("ts") ? recent.get(1).get("ts").getAsLong() : 0;
			long days = (a - b) / 86_400_000L;
			if (days >= 30)
			{
				return "resumed after " + days + " days away";
			}
		}
		return null;
	}

	// Everything administrative, kept off the reading surface.
	private JPanel buildManage()
	{
		JPanel p = column();
		JButton importBtn = new JButton("Import a journal…");
		importBtn.addActionListener(ev -> onImportClicked());
		importBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		importBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		p.add(importBtn);
		p.add(vgap(4));
		p.add(note("your journal is plain JSON in .runelite/chronicle/. An import "
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
			p.add(note("Journaling locally: nothing leaves this computer. "
				+ "Cloud sync lives under Advanced in the plugin settings."));
		}
		return p;
	}

	// Cloud sync is an upward mirror: status and a push button is all there is.
	private JPanel buildCloudSection()
	{
		JPanel s = column();
		JLabel t = new JLabel("Cloud sync");
		t.setFont(FontManager.getRunescapeBoldFont());
		t.setForeground(accent());
		t.setAlignmentX(Component.LEFT_ALIGNMENT);
		s.add(t);
		s.add(vgap(4));
		String rsn = plugin.syncedRsn();
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

	// Ask for a journal file and hand it to the plugin.
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

		// Trackers, via the registry: every counter is findable by label or key.
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

		// Drops: the item aggregates across every source, with its own sources
		// listed underneath it.
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

		// Collection log: the whole taxonomy, with your obtained state.
		Obtained ob = obtained(plugin.clogSnapshot());
		// One hit per item: obtaining one whip lights every slot that holds it,
		// so the first page carrying it stands in as its address.
		Map<String, String> slotFirstPage = new LinkedHashMap<>();
		Map<String, Boolean> slotGot = new LinkedHashMap<>();
		clogSearch:
		for (Map.Entry<String, Map<String, List<String>>> tab : taxonomy(plugin.gson()).entrySet())
		{
			for (Map.Entry<String, List<String>> pg : tab.getValue().entrySet())
			{
				for (String slot : pg.getValue())
				{
					if (slot.toLowerCase(Locale.ROOT).contains(ql))
					{
						if (slotFirstPage.putIfAbsent(slot, pg.getKey()) == null)
						{
							// Same rule as the Log tab, or a slot known only from a
							// page scrape reads as obtained there and missing here.
							slotGot.put(slot, slotHeld(slot, pg.getKey(), pg.getValue(), ob));
						}
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
				boolean got = Boolean.TRUE.equals(slotGot.get(hit.getKey()));
				p.add(row(hit.getKey(), got ? "obtained" : hit.getValue(),
					got ? ACCENT_SESSION : null));
				total++;
			}
		}

		// Journal milestone lines.
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
				return "Pet: " + str(d, "petName", "a new companion");
			case "COLLECTION":
				return "Log slot: " + str(d, "itemName", "new item");
			case "COMBAT_ACHIEVEMENT":
				return "CA " + str(d, "tier", "") + ": " + str(d, "task", "task");
			case "QUEST":
				return "Quest: " + str(d, "questName", str(d, "quest", "complete"));
			case "DIARY":
				return "Diary: " + str(d, "area", "") + " " + str(d, "difficulty", "");
			case "CLUE":
				return "Clue: " + str(d, "clueType", "casket opened");
			case "LEVEL":
				return "Level: " + str(d, "skill", "a skill") + " " + str(d, "level", "");
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
				StringBuilder line = new StringBuilder("Session: ");
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
				// killCount is this task's kills; older entries name the task in
				// slayerTask. Don't fall back to "count": that is the lifetime
				// tasks-completed streak, and it prints here as a kill count.
				String t = str(d, "slayerTask", str(d, "task", ""));
				String kc = str(d, "killCount", "");
				return "Task complete" + (t.isEmpty() ? "" : ": " + t)
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
		// Single-column GridBag: BoxLayout drifts mixed alignments. The constraint
		// is applied to every child as it is added.
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
		// Scrollable that tracks the viewport width. A long label can't widen the
		// view past the panel. Height stays free for vertical scrolling.
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
		// Unbounded width so the card fills the column.
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
		c.setBorder(BorderFactory.createEmptyBorder(6, CARD_INSET, 6, CARD_INSET));
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
		JPanel r = new JPanel(new BorderLayout(ROW_GAP, 0));
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setBorder(BorderFactory.createEmptyBorder(1, ROW_INSET, 1, ROW_INSET));
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
		// Swing's html JLabel measures at one width and paints at another, which
		// clipped note tails all over the panel. A greedy FontMetrics wrap into
		// plain labels reports an exact preferred height.
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
		// Min must not exceed preferred: when a child is wider than the viewport
		// GridBagLayout recomputes row heights from minimum sizes, and a childless
		// panel's default minimum is 10px, which clips the last row.
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
