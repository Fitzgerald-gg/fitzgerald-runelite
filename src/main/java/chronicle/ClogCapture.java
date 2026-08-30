/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Passive collection-log capture.
 *
 * <p>Jagex forbid a plugin from OPENING the collection log itself (a synthetic
 * open fired without a click is an automation flag), so this never opens it,
 * prompts, or injects a control. It reads the account-wide completion fraction on
 * login (a plain varp, always available). Then, the moment the player opens their
 * OWN log, it asks the server to transmit every entry — the log's own "Search" op,
 * exactly what WikiSync / TempleOSRS / RuneProfile do — so a single open captures
 * the WHOLE log (id + quantity per obtained item), not just the page in view. The
 * per-page header scrape still runs on the player's own page draws to pick up kill
 * counts. Everything is driven by the player's own open; nothing is auto-opened.
 * The POH adventure-log varbit guards against ever harvesting another player's log
 * (own account only).
 *
 * <p>The accumulated model is the server's clog shape; the plugin's push loop
 * flushes it when dirty and the server MERGES partial pushes (clog data only
 * grows), so a session that viewed three pages simply adds to what's stored.
 */
@Singleton
@Slf4j
public class ClogCapture
{
	// Account-wide unique clog slots — synced on login without opening anything.
	private static final int VARP_CLOG_OBTAINED = 2943;
	private static final int VARP_CLOG_TOTAL = 2944;
	// Per-tab obtained/total — also login-synced varps (no open needed), so the
	// site can show a Bosses/Raids/Clues/Minigames/Other breakdown for the whole
	// account even before the player opens their log this session.
	private static final String[] CAT_NAMES = {"bosses", "raids", "clues", "minigames", "other"};
	private static final int[][] VARP_CAT = {
		{4613, 4614}, {4615, 4616}, {4617, 4618}, {4619, 4620}, {4621, 4622},
	};
	// The kill/completion count in a "<label>: 1,234" header line.
	private static final Pattern COUNT_LINE = Pattern.compile(":\\s*([\\d,]+)\\s*$");

	// Full-log capture (WikiSync/TempleOSRS/RuneProfile approach). When the player
	// OPENS the collection log themselves, COLLECTION_LOG_SETUP fires; we then ask
	// the server to transmit every entry (the log's own "Search" op), and each
	// obtained item arrives as a COLLECTION_DELAYED_TRANSMIT pre-fire carrying its
	// id + quantity. So one open captures the WHOLE log — no page-by-page browsing.
	// This never opens the log itself (only reacts to the player's open), matching
	// the automation rules the WikiSync/collection-log plugins established.
	private static final int COLLECTION_LOG_SETUP = 7797;
	private static final int COLLECTION_DELAYED_TRANSMIT = 4100;
	private static final int COLLECTION_INIT_SCRIPT = 2240;

	private final Client client;
	private final ItemManager itemManager;

	// First-party model. by_cat: page -> {itemName: obtainedCount}; kcs: page -> kc.
	private final Map<String, Map<String, Integer>> byCat = new HashMap<>();
	private final Map<String, Integer> kcs = new HashMap<>();
	// Slayer Log per-species lifetime kills (the whole log is one scrollable list,
	// so opening it once captures every monster). Feeds the imbued-heart planner.
	private final Map<String, Integer> slayerKcs = new HashMap<>();
	private int finished;
	private int available;

	int finishedCount()
	{
		return finished;
	}

	int availableCount()
	{
		return available;
	}
	private volatile boolean dirty;
	// Ticks since the kill log opened; -1 = idle. The row widgets can be built a
	// tick or two after WidgetLoaded, so we retry the scrape briefly once open.
	private int killLogTicks = -1;

	// Per-tab obtained/total, keyed "<tab>_obtained"/"<tab>_total" (login varps).
	private final Map<String, Integer> catCounts = new HashMap<>();
	// The COMPLETE obtained set from a full-log transmit: itemName -> quantity.
	// Populated by the COLLECTION_DELAYED_TRANSMIT capture; the server folds it
	// into obtained-detection so every page reads correctly, not just viewed ones.
	private final Map<String, Integer> clogItems = new HashMap<>();
	// Guard: the init script we run to reset the view re-fires SETUP, so ignore
	// our own re-trigger. Cleared once the transmit burst settles.
	private boolean clogRetrieving;
	// Tick to flush on, a short buffer after the last transmit item (large logs
	// stream over a few ticks); -1 = idle.
	private int clogFlushTick = -1;

	@Inject
	ClogCapture(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState state = e.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			// Completion fraction — free every login, no interface needed.
			int obtained = client.getVarpValue(VARP_CLOG_OBTAINED);
			int total = client.getVarpValue(VARP_CLOG_TOTAL);
			if (total > 0 && (obtained != finished || total != available))
			{
				finished = obtained;
				available = total;
				dirty = true;
			}
			if (readCategoryCounts())
			{
				dirty = true;
			}
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			reset();
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() == ScriptID.COLLECTION_DRAW_LIST)
		{
			scrapeOpenPage();
			return;
		}
		if (e.getScriptId() == COLLECTION_LOG_SETUP)
		{
			// The player just opened their own collection log. Never harvest another
			// player's log viewed through a POH adventure log (also honours our "own
			// account only" rule) — and don't recurse on the reset we run below.
			if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1)
			{
				clogItems.clear();
				return;
			}
			if (clogRetrieving)
			{
				return;
			}
			clogRetrieving = true;
			clogItems.clear();
			// Ask the server to transmit every entry (the log's own "Search" op),
			// then re-run the init script to reset the view (closing the search).
			// Each entry then arrives as a COLLECTION_DELAYED_TRANSMIT pre-fire.
			client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP,
				1, -1, "Search", null);
			client.runScript(COLLECTION_INIT_SCRIPT);
			// Fallback deadline so the guard always clears — even for an empty log
			// that never fires a transmit; each captured item pushes this out by 3.
			clogFlushTick = client.getTickCount() + 5;
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired e)
	{
		if (e.getScriptId() != COLLECTION_DELAYED_TRANSMIT)
		{
			return;
		}
		// Belt and braces: never capture while an adventure-log view is open.
		if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1)
		{
			return;
		}
		Object[] args = e.getScriptEvent().getArguments();
		if (args == null || args.length < 3)
		{
			return;
		}
		try
		{
			int itemId = (int) args[1];
			int quantity = (int) args[2];
			String name = itemName(itemId);
			if (name != null)
			{
				clogItems.put(name, Math.max(1, quantity));
				// Flush a few ticks after the last item (big logs stream over ticks).
				clogFlushTick = client.getTickCount() + 3;
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("clog transmit read failed", ex);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.KILL_LOG)
		{
			killLogTicks = 0;   // arm the retry; the scrape runs on the next ticks
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		// Full-log transmit finished (no new item for a few ticks) → publish it.
		if (clogFlushTick > 0 && client.getTickCount() >= clogFlushTick)
		{
			clogFlushTick = -1;
			clogRetrieving = false;
			if (!clogItems.isEmpty())
			{
				dirty = true;
			}
		}

		// The Slayer-log rows are built a tick or two after the interface loads, so
		// we retry the scrape briefly after it opens rather than reading it empty.
		if (killLogTicks < 0)
		{
			return;
		}
		int before = slayerKcs.size();
		scrapeSlayerLog();
		if (slayerKcs.size() > before || killLogTicks >= 5)
		{
			killLogTicks = -1;   // captured, or gave up after ~5 ticks
		}
		else
		{
			killLogTicks++;
		}
	}

	/**
	 * Read the Slayer (kill) log the player just opened — the whole list at once.
	 * Two parallel columns of dynamic children: names and their kill counts.
	 */
	private void scrapeSlayerLog()
	{
		try
		{
			Widget names = client.getWidget(InterfaceID.KillLog.NAME);
			Widget kills = client.getWidget(InterfaceID.KillLog.KILL);
			Widget[] nameKids = childrenOf(names);
			Widget[] killKids = childrenOf(kills);
			if (nameKids == null || killKids == null)
			{
				return;
			}
			int n = Math.min(nameKids.length, killKids.length);
			int captured = 0;
			for (int i = 0; i < n; i++)
			{
				String mob = text(nameKids[i]);
				String kcText = text(killKids[i]);
				if (mob == null || mob.isEmpty() || kcText == null)
				{
					continue;
				}
				String digits = kcText.replaceAll("[^0-9]", "");
				if (digits.isEmpty())
				{
					continue;   // "Lots!" (>65,535) or a header row — skip
				}
				try
				{
					slayerKcs.put(mob, Integer.parseInt(digits));
					captured++;
					dirty = true;
				}
				catch (NumberFormatException ignored)
				{
					// skip a malformed row
				}
			}
			if (captured > 0)
			{
				log.debug("slayer-log captured {} species", captured);
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("slayer-log scrape failed", ex);
		}
	}

	/** The kill-log rows could be dynamic, loader, or static children — try each. */
	private static Widget[] childrenOf(Widget w)
	{
		if (w == null)
		{
			return null;
		}
		Widget[] d = w.getDynamicChildren();
		if (d != null && d.length > 0)
		{
			return d;
		}
		Widget[] c = w.getChildren();
		if (c != null && c.length > 0)
		{
			return c;
		}
		return w.getStaticChildren();
	}

	/** Read the collection-log page the player just drew (their own action). */
	private void scrapeOpenPage()
	{
		// Never harvest a page drawn while viewing another player's log through a
		// POH adventure log — same guard the full-transmit paths use (own account only).
		if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1)
		{
			return;
		}
		try
		{
			Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
			Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
			if (header == null || items == null)
			{
				return;
			}
			Widget[] head = header.getDynamicChildren();
			if (head == null || head.length == 0)
			{
				return;
			}
			String page = text(head[0]);   // the page title, e.g. "Vorkath"
			if (page == null || page.isEmpty())
			{
				return;
			}
			// Kill count: the first header line "<label>: N" that isn't "Obtained".
			for (int i = 1; i < head.length; i++)
			{
				String line = text(head[i]);
				if (line == null || line.toLowerCase().startsWith("obtained"))
				{
					continue;
				}
				Matcher m = COUNT_LINE.matcher(line);
				if (m.find())
				{
					try
					{
						kcs.put(page, Integer.parseInt(m.group(1).replace(",", "")));
						break;
					}
					catch (NumberFormatException ignored)
					{
						// keep scanning
					}
				}
			}
			// Obtained items: opacity 0 = obtained (greyed = not), count via quantity.
			Map<String, Integer> pageItems = byCat.computeIfAbsent(page, k -> new HashMap<>());
			Widget[] kids = items.getDynamicChildren();
			if (kids != null)
			{
				for (Widget it : kids)
				{
					if (it == null || it.getItemId() <= 0 || it.getOpacity() != 0)
					{
						continue;   // placeholder or not-obtained → skip
					}
					String name = itemName(it.getItemId());
					if (name != null)
					{
						pageItems.put(name, Math.max(1, it.getItemQuantity()));
					}
				}
			}
			dirty = true;
		}
		catch (RuntimeException ex)
		{
			log.debug("clog scrape failed", ex);
		}
	}

	/** Read the five per-tab obtained/total varps; returns true if anything changed. */
	private boolean readCategoryCounts()
	{
		boolean changed = false;
		for (int i = 0; i < CAT_NAMES.length; i++)
		{
			int tot = client.getVarpValue(VARP_CAT[i][1]);
			if (tot <= 0)
			{
				continue;   // not synced yet — leave any prior value in place
			}
			int obt = client.getVarpValue(VARP_CAT[i][0]);
			String ok = CAT_NAMES[i] + "_obtained", tk = CAT_NAMES[i] + "_total";
			if (!Integer.valueOf(obt).equals(catCounts.get(ok)) || !Integer.valueOf(tot).equals(catCounts.get(tk)))
			{
				catCounts.put(ok, obt);
				catCounts.put(tk, tot);
				changed = true;
			}
		}
		return changed;
	}

	private String itemName(int id)
	{
		try
		{
			String n = itemManager.getItemComposition(id).getName();
			return (n == null || n.isEmpty() || "null".equalsIgnoreCase(n)) ? null : n;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** Widget text with colour tags stripped, or null. */
	private static String text(Widget w)
	{
		if (w == null)
		{
			return null;
		}
		String t = w.getText();
		return t == null ? null : t.replaceAll("<[^>]*>", "").trim();
	}

	// ── Push interface (the plugin's push loop flushes when dirty) ──────────

	boolean isDirty()
	{
		return dirty;
	}

	void clearDirty()
	{
		dirty = false;
	}

	void reset()
	{
		byCat.clear();
		kcs.clear();
		slayerKcs.clear();
		clogItems.clear();
		catCounts.clear();
		finished = 0;
		available = 0;
		killLogTicks = -1;
		clogFlushTick = -1;
		clogRetrieving = false;
		dirty = false;
	}

	/** A JSON-ready snapshot in the server's clog shape (merged server-side). */
	Map<String, Object> snapshot()
	{
		Map<String, Object> out = new HashMap<>();
		out.put("by_cat", byCat);
		out.put("kcs", kcs);
		out.put("slayer_kcs", slayerKcs);
		// Per-tab obtained/total from the login varps (whole account, no open needed).
		out.put("cat_counts", catCounts);
		// The complete obtained set from a full-log open (empty until the player
		// opens their log once this session); server folds it into obtained-detection.
		out.put("clog_items", clogItems);
		out.put("finished", finished);
		out.put("available", available);
		return out;
	}
}
