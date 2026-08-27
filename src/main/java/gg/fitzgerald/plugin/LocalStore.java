/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * The local-mode data store: the on-disk record the plugin keeps for a player
 * when they've chosen Local mode, so their stats live on their own machine with
 * no server round-trip.
 *
 * <p>Two artefacts per account under {@code .runelite/fitzgerald/}:
 * <ul>
 *   <li>{@code <slug>.json} — the durable, accumulating record. Loaded on login
 *       so drops and kill counts build across sessions; rewritten as you play.</li>
 *   <li>{@code <slug>.html} — a self-contained page (the bundled template with the
 *       record spliced in inline) that "Open my page" launches. Nothing is fetched;
 *       the data travels inside the file, so it works fully offline.</li>
 * </ul>
 *
 * <p>Threading: {@link #record} and {@link #setCharacter} run on the client thread
 * (they read {@link ItemManager}); {@link #load} and {@link #flush} run on a
 * background executor. The in-memory model is guarded by {@link #lock}, and the
 * two file-writing methods only hold the lock long enough to serialise a string —
 * the disk write itself happens outside it, so the client thread never blocks on
 * I/O.
 */
@Singleton
@Slf4j
class LocalStore
{
	static final int SCHEMA = 1;
	private static final int FEED_CAP = 2000;
	// Counters that track a peak, not a running total — merged across sessions with
	// max() rather than a sum (matches CombatStatTracker's setStat semantics).
	private static final java.util.Set<String> MAX_KEYS = new java.util.HashSet<>(
		java.util.Arrays.asList("highestHit", "highestHitTaken"));
	// Notable one-off events that belong in the dated feed (LOOT is aggregated
	// into drops instead; GROUP_STORAGE / LOOT_UNTAKEN are cloud-only niceties).
	private static final java.util.Set<String> FEED_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
		"PET", "COLLECTION", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "DEATH", "SLAYER"));

	private final ItemManager itemManager;
	private final Gson gson;

	private final Object lock = new Object();
	private JsonObject root;          // the current account's model (guarded by lock)
	private JsonObject trackersBase;  // lifetime counters frozen at load; +session = lifetime
	private String currentRsn;        // whose model root holds
	private volatile boolean ready;   // true once an account's file has been loaded

	private volatile String template; // cached page template (loaded once)

	@Inject
	LocalStore(ItemManager itemManager, Gson gson)
	{
		this.itemManager = itemManager;
		this.gson = gson;
	}

	// ------------------------------------------------------------------
	// Session lifecycle
	// ------------------------------------------------------------------

	/** Background: load (or start) this account's record so recording accumulates
	 *  onto its history. Call once per login before recording is relied upon. */
	void load(File dir, String rsn)
	{
		JsonObject loaded = null;
		File f = jsonPath(dir, rsn);
		if (f.isFile())
		{
			try
			{
				String txt = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
				JsonElement el = gson.fromJson(txt, JsonElement.class);
				if (el != null && el.isJsonObject())
				{
					loaded = el.getAsJsonObject();
				}
			}
			catch (Exception e)   // noqa: a corrupt/half-written file just starts fresh
			{
				log.debug("local record unreadable, starting fresh", e);
			}
		}
		if (loaded == null)
		{
			loaded = skeleton(rsn);
		}
		normalise(loaded, rsn);
		synchronized (lock)
		{
			root = loaded;
			// Freeze the loaded lifetime counters; each setTrackers() recomputes the
			// live total as this-frozen-base + the current session, so a growing
			// session counter is never double-counted.
			trackersBase = deepCopy(loaded.getAsJsonObject("trackers"));
			currentRsn = rsn;
			ready = true;
		}
	}

	/** The account has logged out; a different one must not record onto its model. */
	void endSession()
	{
		ready = false;
	}

	boolean isReadyFor(String rsn)
	{
		return ready && rsn != null && rsn.equals(currentRsn);
	}

	// ------------------------------------------------------------------
	// Ingest (client thread)
	// ------------------------------------------------------------------

	/** Fold one captured event into the model. Runs on the client thread. */
	void record(String type, JsonObject data, String rsn)
	{
		if (!isReadyFor(rsn) || type == null || data == null)
		{
			return;
		}
		if ("LOOT".equals(type))
		{
			recordLoot(data);
			return;
		}
		if (FEED_TYPES.contains(type))
		{
			JsonObject entry = new JsonObject();
			entry.addProperty("ts", System.currentTimeMillis());
			entry.addProperty("type", type);
			entry.add("data", data);
			synchronized (lock)
			{
				JsonArray feed = root.getAsJsonArray("feed");
				feed.add(entry);
				// Keep the newest FEED_CAP entries (feed is append-order = chronological).
				while (feed.size() > FEED_CAP)
				{
					feed.remove(0);
				}
				root.addProperty("updated_at", nowSec());
			}
		}
	}

	private void recordLoot(JsonObject data)
	{
		String source = data.has("source") && !data.get("source").isJsonNull()
			? data.get("source").getAsString() : "Unknown";
		JsonArray items = data.has("items") && data.get("items").isJsonArray()
			? data.getAsJsonArray("items") : null;
		Integer kc = data.has("killCount") && !data.get("killCount").isJsonNull()
			? data.get("killCount").getAsInt() : null;

		// Price + name on the client thread via ItemManager (canonicalised so notes
		// and placeholders resolve), exactly as the counter tracker does.
		JsonArray priced = new JsonArray();
		long batchValue = 0;
		if (items != null)
		{
			for (JsonElement ie : items)
			{
				if (!ie.isJsonObject())
				{
					continue;
				}
				JsonObject it = ie.getAsJsonObject();
				if (!it.has("id"))
				{
					continue;
				}
				int id = it.get("id").getAsInt();
				int qty = it.has("quantity") ? it.get("quantity").getAsInt() : 1;
				int canon = itemManager.canonicalize(id);
				String name;
				try
				{
					name = itemManager.getItemComposition(canon).getName();
				}
				catch (Exception e)   // noqa: unknown id → fall back to the raw id
				{
					name = "Item " + id;
				}
				long each = itemManager.getItemPrice(canon);
				long value = each * qty;
				batchValue += value;
				JsonObject p = new JsonObject();
				p.addProperty("id", canon);
				p.addProperty("name", name);
				p.addProperty("qty", qty);
				p.addProperty("value", value);
				priced.add(p);
			}
		}

		synchronized (lock)
		{
			JsonObject drops = root.getAsJsonObject("drops");
			JsonObject src = drops.has(source) ? drops.getAsJsonObject(source) : new JsonObject();
			if (!drops.has(source))
			{
				src.addProperty("kc", 0);
				src.addProperty("loots", 0);
				src.addProperty("value", 0);
				src.add("items", new JsonObject());
				drops.add(source, src);
			}
			if (kc != null)
			{
				src.addProperty("kc", Math.max(src.get("kc").getAsInt(), kc));
			}
			src.addProperty("loots", src.get("loots").getAsInt() + 1);
			src.addProperty("value", src.get("value").getAsLong() + batchValue);

			JsonObject bag = src.getAsJsonObject("items");
			for (JsonElement pe : priced)
			{
				JsonObject p = pe.getAsJsonObject();
				String key = String.valueOf(p.get("id").getAsInt());
				if (bag.has(key))
				{
					JsonObject cur = bag.getAsJsonObject(key);
					cur.addProperty("qty", cur.get("qty").getAsLong() + p.get("qty").getAsLong());
					cur.addProperty("value", cur.get("value").getAsLong() + p.get("value").getAsLong());
					cur.addProperty("name", p.get("name").getAsString());
				}
				else
				{
					bag.add(key, p);
				}
			}
			root.addProperty("updated_at", nowSec());
		}
	}

	/** Refresh the always-current character sheet. Runs on the client thread.
	 *  {@code collectionLog} is the capture's raw map; it's converted to a tree here. */
	void setCharacter(String rsn, String accountType, JsonObject skills, int combatLevel,
		java.util.Map<String, Object> collectionLog, JsonObject achievements)
	{
		if (!isReadyFor(rsn))
		{
			return;
		}
		synchronized (lock)
		{
			if (accountType != null)
			{
				root.addProperty("account_type", accountType);
			}
			if (skills != null)
			{
				root.add("skills", skills);
			}
			if (combatLevel > 0)
			{
				root.addProperty("combat_level", combatLevel);
			}
			if (collectionLog != null)
			{
				root.add("collection_log", gson.toJsonTree(collectionLog));
			}
			if (achievements != null)
			{
				root.add("achievements", achievements);
			}
			root.addProperty("updated_at", nowSec());
		}
	}

	/**
	 * Refresh the lifetime tracker counters from this session's live totals. Runs on
	 * the client thread. {@code session} is the client-computed counter snapshot
	 * ({@code StatStore.pushable()}); lifetime = frozen-base + session (max for peak
	 * counters), so calling this repeatedly through a session never double-counts.
	 */
	void setTrackers(java.util.Map<String, Integer> session, String rsn)
	{
		if (!isReadyFor(rsn) || session == null)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject tr = new JsonObject();
			java.util.Set<String> keys = new java.util.HashSet<>(session.keySet());
			for (java.util.Map.Entry<String, JsonElement> e : trackersBase.entrySet())
			{
				keys.add(e.getKey());
			}
			for (String k : keys)
			{
				long base = trackersBase.has(k) && !trackersBase.get(k).isJsonNull()
					? trackersBase.get(k).getAsLong() : 0;
				long sess = session.get(k) != null ? session.get(k).longValue() : 0;
				long life = MAX_KEYS.contains(k) ? Math.max(base, sess) : base + sess;
				if (life != 0)
				{
					tr.addProperty(k, life);
				}
			}
			root.add("trackers", tr);
			root.addProperty("updated_at", nowSec());
		}
	}

	// ------------------------------------------------------------------
	// Persist + render (background executor)
	// ------------------------------------------------------------------

	/**
	 * Write the JSON record and regenerate the self-contained page. Returns the
	 * page file (for opening), or null if there's nothing to write yet. The lock
	 * is held only to serialise the model; both disk writes happen outside it.
	 */
	File flush(File dir)
	{
		String json;
		String rsn;
		synchronized (lock)
		{
			if (root == null || currentRsn == null)
			{
				return null;
			}
			json = gson.toJson(root);
			rsn = currentRsn;
		}
		try
		{
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				log.debug("could not create local dir {}", dir);
			}
			writeAtomic(jsonPath(dir, rsn), json);
			String page = renderPage(json);
			File html = htmlPath(dir, rsn);
			writeAtomic(html, page);
			return html;
		}
		catch (Exception e)   // noqa: best-effort; a failed write just retries next tick
		{
			log.debug("local flush failed", e);
			return null;
		}
	}

	/** The page file for an account, without writing anything (for opening a stale copy). */
	File pageFor(File dir, String rsn)
	{
		return htmlPath(dir, rsn);
	}

	private String renderPage(String json) throws IOException
	{
		String tpl = template;
		if (tpl == null)
		{
			try (InputStream in = LocalStore.class.getResourceAsStream("dashboard.html"))
			{
				if (in == null)
				{
					throw new IOException("dashboard.html template missing from plugin resources");
				}
				tpl = new String(readAll(in), StandardCharsets.UTF_8);
				template = tpl;
			}
		}
		// The template carries the literal token __FITZ_DATA__ where the record goes.
		// Escape '<' so a game string like "</script>" in an item/boss name can never
		// break out of the inline <script> (valid JSON: only affects string contents).
		return tpl.replace("__FITZ_DATA__", json.replace("<", "\\u003c"));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private JsonObject skeleton(String rsn)
	{
		JsonObject o = new JsonObject();
		o.addProperty("schema", SCHEMA);
		o.addProperty("rsn", rsn);
		o.addProperty("first_seen", nowSec());
		o.addProperty("updated_at", nowSec());
		o.add("skills", new JsonObject());
		o.add("collection_log", new JsonObject());
		o.add("achievements", new JsonObject());
		o.add("drops", new JsonObject());
		o.add("trackers", new JsonObject());
		o.add("feed", new JsonArray());
		return o;
	}

	private JsonObject deepCopy(JsonObject o)
	{
		if (o == null)
		{
			return new JsonObject();
		}
		JsonElement el = gson.fromJson(gson.toJson(o), JsonElement.class);
		return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
	}

	/** Make sure a loaded record has every container the ingest paths expect. */
	private void normalise(JsonObject o, String rsn)
	{
		o.addProperty("schema", SCHEMA);
		o.addProperty("rsn", rsn);
		if (!o.has("first_seen"))
		{
			o.addProperty("first_seen", nowSec());
		}
		ensureObject(o, "skills");
		ensureObject(o, "collection_log");
		ensureObject(o, "achievements");
		ensureObject(o, "drops");
		ensureObject(o, "trackers");
		if (!o.has("feed") || !o.get("feed").isJsonArray())
		{
			o.add("feed", new JsonArray());
		}
	}

	private static void ensureObject(JsonObject o, String key)
	{
		if (!o.has(key) || !o.get(key).isJsonObject())
		{
			o.add(key, new JsonObject());
		}
	}

	private static long nowSec()
	{
		return System.currentTimeMillis() / 1000L;
	}

	static String slug(String rsn)
	{
		String s = rsn == null ? "" : rsn.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
		s = s.replaceAll("(^-+|-+$)", "");
		return s.isEmpty() ? "profile" : s;
	}

	private static File jsonPath(File dir, String rsn)
	{
		return new File(dir, slug(rsn) + ".json");
	}

	private static File htmlPath(File dir, String rsn)
	{
		return new File(dir, slug(rsn) + ".html");
	}

	private static void writeAtomic(File dest, String content) throws IOException
	{
		File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
		Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
		try
		{
			Files.move(tmp.toPath(), dest.toPath(),
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException atomicUnsupported)
		{
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static byte[] readAll(InputStream in) throws IOException
	{
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1)
		{
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}
}
