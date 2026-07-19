/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Passive collection-log capture.
 *
 * <p>The client only exposes a clog page's items, obtained counts and kill count
 * once the player OPENS that page in-game, and Jagex forbid a plugin from opening
 * it automatically (a synthetic action fired without a click is an automation
 * flag). So this never opens the log, never prompts, and never injects a control:
 * it reads the account-wide completion fraction on login (a plain varp, always
 * available) and quietly scrapes whatever page the player views themselves,
 * letting the picture complete over sessions. Everything read here is drawn by the
 * player's own action — no synthetic menu/script calls — which keeps it inside the
 * automation rules (WikiSync / Collection Log plugins set the read+upload
 * precedent).
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
	// The kill/completion count in a "<label>: 1,234" header line.
	private static final Pattern COUNT_LINE = Pattern.compile(":\\s*([\\d,]+)\\s*$");

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
	private volatile boolean dirty;
	// Ticks since the kill log opened; -1 = idle. The row widgets can be built a
	// tick or two after WidgetLoaded, so we retry the scrape briefly once open.
	private int killLogTicks = -1;

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
		finished = 0;
		available = 0;
		killLogTicks = -1;
		dirty = false;
	}

	/** A JSON-ready snapshot in the server's clog shape (merged server-side). */
	Map<String, Object> snapshot()
	{
		Map<String, Object> out = new HashMap<>();
		out.put("by_cat", byCat);
		out.put("kcs", kcs);
		out.put("slayer_kcs", slayerKcs);
		out.put("finished", finished);
		out.put("available", available);
		return out;
	}
}
