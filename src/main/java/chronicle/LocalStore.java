/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

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
 * <p>Two artefacts per account under {@code .runelite/chronicle/}:
 * <ul>
 *   <li>{@code <slug>.json} — the durable, accumulating record. Loaded on login
 *       so drops and kill counts build across sessions; rewritten as you play.</li>
 *   The record is what the Chronicle side panel reads and presents — it never
 *   leaves this computer.
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
	static final java.util.Set<String> MAX_KEYS = new java.util.HashSet<>(
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

	// Session-scope tallies for the panel's strip + recent-drop icon row —
	// in-memory only, reset at the account boundary. Guarded by lock.
	private int sessionLoots;
	private long sessionLootValue;
	private final java.util.ArrayDeque<RecentDrop> recentDrops = new java.util.ArrayDeque<>();

	/** One recent drop, panel-facing (immutable copy). */
	static final class RecentDrop
	{
		final int itemId;
		final int quantity;
		final String name;

		RecentDrop(int itemId, int quantity, String name)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.name = name;
		}
	}


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
		synchronized (lock)
		{
			sessionLoots = 0;
			sessionLootValue = 0;
			recentDrops.clear();
		}
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

		// Best kill time: the standing PB when the game restated it, else this
		// kill's own time when it WAS the record. Same rule the site uses, so
		// the local page and the web page agree on every PB they both know.
		Double pbCand = null;
		if (data.has("personalBestTime") && !data.get("personalBestTime").isJsonNull())
		{
			pbCand = data.get("personalBestTime").getAsDouble();
		}
		else if (data.has("personalBest") && !data.get("personalBest").isJsonNull()
			&& data.get("personalBest").getAsBoolean()
			&& data.has("killTime") && data.get("killTime").getAsDouble() >= 0)
		{
			pbCand = data.get("killTime").getAsDouble();
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
			sessionLoots++;
			sessionLootValue += batchValue;
			for (JsonElement pe : priced)
			{
				JsonObject p = pe.getAsJsonObject();
				recentDrops.addFirst(new RecentDrop(p.get("id").getAsInt(),
					p.get("qty").getAsInt(), p.get("name").getAsString()));
			}
			while (recentDrops.size() > 10)
			{
				recentDrops.removeLast();
			}
			if (pbCand != null && pbCand > 0
				&& (!src.has("pb") || pbCand < src.get("pb").getAsDouble()))
			{
				src.addProperty("pb", pbCand);
			}

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
	/**
	 * Raise the journal's lifetime base to AT LEAST the given absolutes, per key
	 * (max keys included — max of absolutes is their natural semantics). Called
	 * with the server's counters on a cloud login: the journal adopts history it
	 * never witnessed (an install newer than the account's cloud record, another
	 * computer's play) without ever double-counting — a base already higher, from
	 * local play the server hasn't seen, is kept.
	 */
	void floorTrackers(java.util.Map<String, Integer> absolutes, String rsn)
	{
		if (!isReadyFor(rsn) || absolutes == null)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject tr = root.has("trackers") && root.get("trackers").isJsonObject()
				? root.getAsJsonObject("trackers") : new JsonObject();
			for (java.util.Map.Entry<String, Integer> e : absolutes.entrySet())
			{
				long have = trackersBase.has(e.getKey()) && !trackersBase.get(e.getKey()).isJsonNull()
					? trackersBase.get(e.getKey()).getAsLong() : 0;
				long floor = e.getValue() != null ? e.getValue().longValue() : 0;
				if (floor > have)
				{
					trackersBase.addProperty(e.getKey(), floor);
				}
				long shown = tr.has(e.getKey()) && !tr.get(e.getKey()).isJsonNull()
					? tr.get(e.getKey()).getAsLong() : 0;
				if (floor > shown)
				{
					tr.addProperty(e.getKey(), floor);
				}
			}
			root.add("trackers", tr);
			root.addProperty("updated_at", nowSec());
		}
	}

	/**
	 * Re-freeze the lifetime base at the CURRENT journal values. Called before
	 * the session counter store is cleared mid-session (a cloud toggle), so the
	 * increments already folded in are owned by the base and the fresh
	 * from-zero session can't erase them on the next recompute.
	 */
	void rebase(String rsn)
	{
		if (!isReadyFor(rsn))
		{
			return;
		}
		synchronized (lock)
		{
			if (root.has("trackers") && root.get("trackers").isJsonObject())
			{
				trackersBase = deepCopy(root.getAsJsonObject("trackers"));
			}
		}
	}

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
	void flush(File dir)
	{
		String json;
		String rsn;
		synchronized (lock)
		{
			if (root == null || currentRsn == null)
			{
				return;
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
		}
		catch (Exception e)   // noqa: best-effort; a failed write just retries next tick
		{
			log.debug("local flush failed", e);
		}
	}

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

	// ------------------------------------------------------------------
	// Panel-facing reads (copies only; safe to call from the EDT)
	// ------------------------------------------------------------------

	net.runelite.client.game.ItemManager items()
	{
		return itemManager;
	}

	/** Lifetime counters as the journal knows them (base + this session). */
	java.util.Map<String, Long> trackersSnapshot()
	{
		java.util.Map<String, Long> out = new java.util.HashMap<>();
		synchronized (lock)
		{
			if (root != null && root.has("trackers") && root.get("trackers").isJsonObject())
			{
				for (java.util.Map.Entry<String, JsonElement> e
					: root.getAsJsonObject("trackers").entrySet())
				{
					if (!e.getValue().isJsonNull())
					{
						out.put(e.getKey(), e.getValue().getAsLong());
					}
				}
			}
		}
		return out;
	}

	/** One ranked drop source. */
	static final class SourceRow
	{
		final String name;
		final int kc;
		final int loots;
		final long value;
		final Double pb;
		final int cloudItems;

		SourceRow(String name, int kc, int loots, long value, Double pb, int cloudItems)
		{
			this.name = name;
			this.kc = kc;
			this.loots = loots;
			this.value = value;
			this.pb = pb;
			this.cloudItems = cloudItems;
		}
	}

	/** Every drop source, unsorted (the panel ranks). */
	java.util.List<SourceRow> dropSources()
	{
		java.util.List<SourceRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("drops"))
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("drops").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject src = e.getValue().getAsJsonObject();
				out.add(new SourceRow(e.getKey(),
					src.has("kc") ? src.get("kc").getAsInt() : 0,
					src.has("loots") ? src.get("loots").getAsInt() : 0,
					src.has("value") ? src.get("value").getAsLong() : 0,
					src.has("pb") ? src.get("pb").getAsDouble() : null,
					src.has("cloud_items") ? src.get("cloud_items").getAsInt() : 0));
			}
		}
		return out;
	}

	/** Newest feed entries, newest first (deep copies). */
	java.util.List<JsonObject> feedNewest(int n)
	{
		java.util.List<JsonObject> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("feed") || !root.get("feed").isJsonArray())
			{
				return out;
			}
			com.google.gson.JsonArray feed = root.getAsJsonArray("feed");
			for (int i = feed.size() - 1; i >= 0 && out.size() < n; i--)
			{
				if (feed.get(i).isJsonObject())
				{
					out.add(feed.get(i).getAsJsonObject().deepCopy());
				}
			}
		}
		return out;
	}

	int sessionLoots()
	{
		synchronized (lock)
		{
			return sessionLoots;
		}
	}

	long sessionLootValue()
	{
		synchronized (lock)
		{
			return sessionLootValue;
		}
	}

	java.util.List<RecentDrop> recentDrops()
	{
		synchronized (lock)
		{
			return new java.util.ArrayList<>(recentDrops);
		}
	}

	/**
	 * Floor the journal's drop sources at the cloud ledger's rollup, per source
	 * (kc / loot count / value all take the max — the same union philosophy as
	 * the counter floor, so a journal that predates cloud sync is never wiped
	 * and a fresh install adopts the server's history whole). Item bags stay
	 * locally-witnessed: the server's rollup is name-keyed with no item ids,
	 * so adopted items would render iconless — the drill fetches those on
	 * demand instead. cloud_items records how many distinct items the cloud
	 * ledger holds, so the panel can say what the bag is missing.
	 */
	void floorDropSources(java.util.List<chronicle.ChronicleApiClient.LedgerSource> ledger, String rsn)
	{
		if (!isReadyFor(rsn) || ledger == null)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject drops = root.has("drops") && root.get("drops").isJsonObject()
				? root.getAsJsonObject("drops") : new JsonObject();
			for (chronicle.ChronicleApiClient.LedgerSource ls : ledger)
			{
				if (ls.source == null || ls.source.isEmpty())
				{
					continue;
				}
				JsonObject src = drops.has(ls.source) && drops.get(ls.source).isJsonObject()
					? drops.getAsJsonObject(ls.source) : new JsonObject();
				if (!drops.has(ls.source))
				{
					src.addProperty("kc", 0);
					src.addProperty("loots", 0);
					src.addProperty("value", 0);
					src.add("items", new JsonObject());
					drops.add(ls.source, src);
				}
				if (ls.kc > src.get("kc").getAsInt())
				{
					src.addProperty("kc", ls.kc);
				}
				if (ls.loots > src.get("loots").getAsInt())
				{
					src.addProperty("loots", ls.loots);
				}
				if (ls.value > src.get("value").getAsLong())
				{
					src.addProperty("value", ls.value);
				}
				int haveItems = src.has("cloud_items") ? src.get("cloud_items").getAsInt() : 0;
				if (ls.itemsCount > haveItems)
				{
					src.addProperty("cloud_items", ls.itemsCount);
				}
			}
			root.add("drops", drops);
			root.addProperty("updated_at", nowSec());
		}
	}

	/** One item held in a source's local bag. */
	static final class BagItem
	{
		final int itemId;
		final String name;
		final long qty;
		final long value;

		BagItem(int itemId, String name, long qty, long value)
		{
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
			this.value = value;
		}
	}

	/** The locally-witnessed item bag for one source, unsorted. */
	java.util.List<BagItem> sourceItems(String source)
	{
		java.util.List<BagItem> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("drops") || !root.get("drops").isJsonObject())
			{
				return out;
			}
			JsonObject drops = root.getAsJsonObject("drops");
			if (!drops.has(source) || !drops.get(source).isJsonObject())
			{
				return out;
			}
			JsonObject src = drops.getAsJsonObject(source);
			if (!src.has("items") || !src.get("items").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: src.getAsJsonObject("items").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject it = e.getValue().getAsJsonObject();
				int id;
				try
				{
					id = Integer.parseInt(e.getKey());
				}
				catch (NumberFormatException ex)
				{
					id = -1;
				}
				out.add(new BagItem(id,
					it.has("name") ? it.get("name").getAsString() : e.getKey(),
					it.has("qty") ? it.get("qty").getAsLong() : 0,
					it.has("value") ? it.get("value").getAsLong() : 0));
			}
		}
		return out;
	}

	/** One collection-log page the journal holds. */
	static final class ClogPage
	{
		final String page;
		final int held;
		final Integer kc;

		ClogPage(String page, int held, Integer kc)
		{
			this.page = page;
			this.held = held;
			this.kc = kc;
		}
	}

	/** Pages of the stored collection log (this session's capture), unsorted. */
	java.util.List<ClogPage> clogPages()
	{
		java.util.List<ClogPage> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("collection_log")
				|| !root.get("collection_log").isJsonObject())
			{
				return out;
			}
			JsonObject cl = root.getAsJsonObject("collection_log");
			JsonObject byCat = cl.has("by_cat") && cl.get("by_cat").isJsonObject()
				? cl.getAsJsonObject("by_cat") : new JsonObject();
			JsonObject kcs = cl.has("kcs") && cl.get("kcs").isJsonObject()
				? cl.getAsJsonObject("kcs") : new JsonObject();
			for (java.util.Map.Entry<String, JsonElement> e : byCat.entrySet())
			{
				int held = e.getValue().isJsonObject()
					? e.getValue().getAsJsonObject().size() : 0;
				Integer kc = kcs.has(e.getKey()) && !kcs.get(e.getKey()).isJsonNull()
					? kcs.get(e.getKey()).getAsInt() : null;
				out.add(new ClogPage(e.getKey(), held, kc));
			}
		}
		return out;
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
