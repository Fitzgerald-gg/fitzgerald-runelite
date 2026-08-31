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
	// The journal keeps milestones indefinitely by design; this cap is a
	// runaway guard, sized far above a decade of play (and above the deep
	// cloud import's 5,000-event window), not a retention policy.
	private static final int FEED_CAP = 20000;
	// Counters that track a peak, not a running total — merged across sessions with
	// max() rather than a sum (matches CombatStatTracker's setStat semantics).
	static final java.util.Set<String> MAX_KEYS = new java.util.HashSet<>(
		java.util.Arrays.asList("highestHit", "highestHitTaken"));
	// Notable one-off events that belong in the dated feed (LOOT aggregates
	// into drops, LOOT_UNTAKEN into the untaken ledger — both recorded locally,
	// just not as feed lines; GROUP_STORAGE is a cloud-only nicety).
	private static final java.util.Set<String> FEED_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
		"PET", "COLLECTION", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "DEATH", "SLAYER",
		"SESSION"));   // the logout diary line — local-only, never pushed

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
	private int sessionUntaken;
	private long sessionUntakenValue;
	private final java.util.LinkedHashMap<String, long[]> sessionSources = new java.util.LinkedHashMap<>();
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
			sessionUntaken = 0;
			sessionUntakenValue = 0;
			sessionSources.clear();
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
		if ("LOOT_UNTAKEN".equals(type))
		{
			recordUntaken(data);
			return;
		}
		if (FEED_TYPES.contains(type))
		{
			// A new log slot counts the moment it drops — the stored log gains
			// the item and the finished tally, instead of waiting for the
			// player to next open their collection log in game.
			if ("COLLECTION".equals(type))
			{
				recordClogSlot(data);
			}
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
			long[] tally = sessionSources.computeIfAbsent(source, k -> new long[2]);
			tally[0]++;
			tally[1] += batchValue;
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
				// Max-union into the STORED log rather than replacing it — the
				// session's capture is partial (pages browsed, varps read), and
				// clog data only grows, so the union is the whole truth. Port of
				// the server's _clog_merge.
				root.add("collection_log", mergeClog(
					root.has("collection_log") && root.get("collection_log").isJsonObject()
						? root.getAsJsonObject("collection_log") : new JsonObject(),
					gson.toJsonTree(collectionLog).getAsJsonObject()));
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
		// Tracked-since / last-seen (epoch ms; 0 = unknown) — seeded by the
		// Loot Tracker import's per-source range, extended as play continues.
		final long firstMs;
		final long lastMs;

		SourceRow(String name, int kc, int loots, long value, Double pb, int cloudItems)
		{
			this(name, kc, loots, value, pb, cloudItems, 0, 0);
		}

		SourceRow(String name, int kc, int loots, long value, Double pb, int cloudItems,
			long firstMs, long lastMs)
		{
			this.name = name;
			this.kc = kc;
			this.loots = loots;
			this.value = value;
			this.pb = pb;
			this.cloudItems = cloudItems;
			this.firstMs = firstMs;
			this.lastMs = lastMs;
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
					src.has("cloud_items") ? src.get("cloud_items").getAsInt() : 0,
					src.has("first_seen") ? src.get("first_seen").getAsLong() : 0,
					src.has("last_seen") ? src.get("last_seen").getAsLong() : 0));
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
	/**
	 * Adopt the cloud's whole per-source item ledger into the local bags, so
	 * item questions ("how many whips, from where") answer locally. Matched
	 * by NAME (the server has no item ids): an existing local entry keeps its
	 * id (sprites) and floors its qty/value at the cloud's; a cloud-only item
	 * lands as an id-less entry keyed "n:&lt;name&gt;". Idempotent — re-adoption
	 * is a floor, never a sum.
	 */
	void floorSourceBags(java.util.List<chronicle.ChronicleApiClient.SourceItemRow> rows, String rsn)
	{
		if (!isReadyFor(rsn) || rows == null)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject drops = root.has("drops") && root.get("drops").isJsonObject()
				? root.getAsJsonObject("drops") : new JsonObject();
			root.add("drops", drops);
			for (chronicle.ChronicleApiClient.SourceItemRow row : rows)
			{
				if (row.source == null || row.source.isEmpty()
					|| row.name == null || row.name.isEmpty())
				{
					continue;
				}
				JsonObject src = drops.has(row.source) && drops.get(row.source).isJsonObject()
					? drops.getAsJsonObject(row.source) : null;
				if (src == null)
				{
					src = new JsonObject();
					src.addProperty("kc", 0);
					src.addProperty("loots", 0);
					src.addProperty("value", 0);
					src.add("items", new JsonObject());
					drops.add(row.source, src);
				}
				if (!src.has("items") || !src.get("items").isJsonObject())
				{
					src.add("items", new JsonObject());
				}
				JsonObject items = src.getAsJsonObject("items");
				// find an existing entry with this name (id-keyed or adopted)
				JsonObject hit = null;
				for (java.util.Map.Entry<String, JsonElement> e : items.entrySet())
				{
					if (e.getValue().isJsonObject())
					{
						JsonObject it = e.getValue().getAsJsonObject();
						if (it.has("name") && !it.get("name").isJsonNull()
							&& row.name.equalsIgnoreCase(it.get("name").getAsString()))
						{
							hit = it;
							break;
						}
					}
				}
				if (hit == null)
				{
					hit = new JsonObject();
					hit.addProperty("id", 0);
					hit.addProperty("name", row.name);
					hit.addProperty("qty", 0);
					hit.addProperty("value", 0);
					items.add("n:" + row.name.toLowerCase(java.util.Locale.ROOT), hit);
				}
				long q = hit.has("qty") ? hit.get("qty").getAsLong() : 0;
				long v = hit.has("value") ? hit.get("value").getAsLong() : 0;
				if (row.qty > q)
				{
					hit.addProperty("qty", row.qty);
				}
				if (row.value > v)
				{
					hit.addProperty("value", row.value);
				}
			}
		}
	}

	/** One source's whole record from the core Loot Tracker's local store,
	 *  already canonicalised and priced by the caller (client thread). */
	static final class LootSeed
	{
		final String source;
		final int kills;
		final long firstMs;
		final long lastMs;
		final java.util.List<BagItem> items;

		LootSeed(String source, int kills, long firstMs, long lastMs,
			java.util.List<BagItem> items)
		{
			this.source = source;
			this.kills = kills;
			this.firstMs = firstMs;
			this.lastMs = lastMs;
			this.items = items;
		}
	}

	/**
	 * Adopt the core Loot Tracker's lifetime record — the one local archive
	 * that predates any server (years of witnessed loot events). Everything
	 * floors: kc and loots at the tracker's event count (a lower bound of
	 * true KC; a higher game-reported kc survives), item qty/value by
	 * id-then-name (a name-keyed cloud row gains its real id), source value
	 * at the priced sum. first_seen/last_seen extend as min/max. Idempotent —
	 * a re-run can only raise floors it already set.
	 */
	void floorLootTracker(java.util.List<LootSeed> seeds, String rsn)
	{
		if (!isReadyFor(rsn) || seeds == null)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject drops = root.has("drops") && root.get("drops").isJsonObject()
				? root.getAsJsonObject("drops") : new JsonObject();
			root.add("drops", drops);
			for (LootSeed seed : seeds)
			{
				if (seed.source == null || seed.source.isEmpty())
				{
					continue;
				}
				JsonObject src = drops.has(seed.source) && drops.get(seed.source).isJsonObject()
					? drops.getAsJsonObject(seed.source) : null;
				if (src == null)
				{
					src = new JsonObject();
					src.addProperty("kc", 0);
					src.addProperty("loots", 0);
					src.addProperty("value", 0);
					src.add("items", new JsonObject());
					drops.add(seed.source, src);
				}
				if (seed.kills > (src.has("kc") ? src.get("kc").getAsInt() : 0))
				{
					src.addProperty("kc", seed.kills);
				}
				if (seed.kills > (src.has("loots") ? src.get("loots").getAsInt() : 0))
				{
					src.addProperty("loots", seed.kills);
				}
				if (seed.firstMs > 0)
				{
					long cur = src.has("first_seen") ? src.get("first_seen").getAsLong() : Long.MAX_VALUE;
					if (seed.firstMs < cur)
					{
						src.addProperty("first_seen", seed.firstMs);
					}
				}
				if (seed.lastMs > 0)
				{
					long cur = src.has("last_seen") ? src.get("last_seen").getAsLong() : 0;
					if (seed.lastMs > cur)
					{
						src.addProperty("last_seen", seed.lastMs);
					}
				}
				if (!src.has("items") || !src.get("items").isJsonObject())
				{
					src.add("items", new JsonObject());
				}
				JsonObject items = src.getAsJsonObject("items");
				long total = 0;
				for (BagItem b : seed.items)
				{
					JsonObject hit = null;
					if (b.itemId > 0 && items.has(String.valueOf(b.itemId))
						&& items.get(String.valueOf(b.itemId)).isJsonObject())
					{
						hit = items.getAsJsonObject(String.valueOf(b.itemId));
					}
					if (hit == null)
					{
						// a name-keyed cloud adoption gains its real id here
						for (java.util.Map.Entry<String, JsonElement> e : items.entrySet())
						{
							if (e.getValue().isJsonObject())
							{
								JsonObject it = e.getValue().getAsJsonObject();
								if (it.has("name") && !it.get("name").isJsonNull()
									&& b.name.equalsIgnoreCase(it.get("name").getAsString()))
								{
									hit = it;
									if (b.itemId > 0 && e.getKey().startsWith("n:"))
									{
										items.remove(e.getKey());
										hit.addProperty("id", b.itemId);
										items.add(String.valueOf(b.itemId), hit);
									}
									break;
								}
							}
						}
					}
					if (hit == null)
					{
						hit = new JsonObject();
						hit.addProperty("id", b.itemId);
						hit.addProperty("name", b.name);
						hit.addProperty("qty", 0);
						hit.addProperty("value", 0);
						items.add(b.itemId > 0 ? String.valueOf(b.itemId)
							: "n:" + b.name.toLowerCase(java.util.Locale.ROOT), hit);
					}
					if (b.qty > (hit.has("qty") ? hit.get("qty").getAsLong() : 0))
					{
						hit.addProperty("qty", b.qty);
					}
					if (b.value > (hit.has("value") ? hit.get("value").getAsLong() : 0))
					{
						hit.addProperty("value", b.value);
					}
					total += hit.get("value").getAsLong();
				}
				if (total > (src.has("value") ? src.get("value").getAsLong() : 0))
				{
					src.addProperty("value", total);
				}
			}
		}
	}

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

	/**
	 * One-shot adoption of the cloud feed's milestones into the journal's own
	 * feed, deduplicated by (millisecond timestamp, type) — the server's floats
	 * are stable, so re-adoption on every login is a no-op. Entries land in
	 * timestamp order; the cap trims oldest, exactly like live recording.
	 */
	void adoptFeed(java.util.List<chronicle.ChronicleApiClient.FeedEvent> events, String rsn)
	{
		if (!isReadyFor(rsn) || events == null || events.isEmpty())
		{
			return;
		}
		synchronized (lock)
		{
			JsonArray feed = root.has("feed") && root.get("feed").isJsonArray()
				? root.getAsJsonArray("feed") : new JsonArray();
			java.util.Set<String> have = new java.util.HashSet<>();
			for (JsonElement el : feed)
			{
				if (el.isJsonObject())
				{
					JsonObject o = el.getAsJsonObject();
					have.add((o.has("ts") ? o.get("ts").getAsLong() : 0)
						+ "|" + (o.has("type") ? o.get("type").getAsString() : ""));
				}
			}
			java.util.List<JsonObject> merged = new java.util.ArrayList<>();
			for (JsonElement el : feed)
			{
				if (el.isJsonObject())
				{
					merged.add(el.getAsJsonObject());
				}
			}
			int added = 0;
			for (chronicle.ChronicleApiClient.FeedEvent ev : events)
			{
				long tsMs = Math.round(ev.ts * 1000.0);
				if (!have.add(tsMs + "|" + ev.type))
				{
					continue;
				}
				JsonObject entry = new JsonObject();
				entry.addProperty("ts", tsMs);
				entry.addProperty("type", ev.type);
				entry.add("data", ev.data != null ? ev.data : new JsonObject());
				merged.add(entry);
				added++;
			}
			if (added == 0)
			{
				return;
			}
			merged.sort(java.util.Comparator.comparingLong(
				o -> o.has("ts") ? o.get("ts").getAsLong() : 0));
			while (merged.size() > FEED_CAP)
			{
				merged.remove(0);
			}
			JsonArray next = new JsonArray();
			for (JsonObject o : merged)
			{
				next.add(o);
			}
			root.add("feed", next);
			root.addProperty("updated_at", nowSec());
		}
	}

	/**
	 * Left-behind loot: the same price-at-record pattern as drops, aggregated
	 * per source ({@code untaken: {source: {qty, value}}}). Forward-only and
	 * local — the morbid ledger of what was declined.
	 */
	private void recordUntaken(JsonObject data)
	{
		String source = data.has("source") && !data.get("source").isJsonNull()
			? data.get("source").getAsString() : "Unknown";
		JsonArray items = data.has("items") && data.get("items").isJsonArray()
			? data.getAsJsonArray("items") : null;
		if (items == null)
		{
			return;
		}
		long qty = 0;
		long value = 0;
		for (JsonElement ie : items)
		{
			if (!ie.isJsonObject() || !ie.getAsJsonObject().has("id"))
			{
				continue;
			}
			JsonObject it = ie.getAsJsonObject();
			int id = it.get("id").getAsInt();
			int n = it.has("quantity") ? it.get("quantity").getAsInt() : 1;
			qty += n;
			value += (long) itemManager.getItemPrice(itemManager.canonicalize(id)) * n;
		}
		synchronized (lock)
		{
			JsonObject untaken = root.has("untaken") && root.get("untaken").isJsonObject()
				? root.getAsJsonObject("untaken") : new JsonObject();
			JsonObject src = untaken.has(source) && untaken.get(source).isJsonObject()
				? untaken.getAsJsonObject(source) : new JsonObject();
			src.addProperty("qty", (src.has("qty") ? src.get("qty").getAsLong() : 0) + qty);
			src.addProperty("value", (src.has("value") ? src.get("value").getAsLong() : 0) + value);
			untaken.add(source, src);
			root.add("untaken", untaken);
			sessionUntaken += qty;
			sessionUntakenValue += value;
			root.addProperty("updated_at", nowSec());
		}
	}

	/** One left-behind source (lifetime aggregate). */
	static final class UntakenRow
	{
		final String name;
		final long qty;
		final long value;

		UntakenRow(String name, long qty, long value)
		{
			this.name = name;
			this.qty = qty;
			this.value = value;
		}
	}

	/**
	 * Adopt the cloud's uncollected ledger: per-source rows floor into the
	 * same {@code untaken} store live capture feeds (max, never add — the
	 * server re-includes what this client pushed), per-item rows into a
	 * sibling {@code untaken_items} map for the lens's item section.
	 */
	void floorUntaken(java.util.List<String[]> bySource, java.util.List<String[]> byItem,
		String rsn)
	{
		if (!isReadyFor(rsn))
		{
			return;
		}
		synchronized (lock)
		{
			if (bySource != null)
			{
				JsonObject untaken = root.has("untaken") && root.get("untaken").isJsonObject()
					? root.getAsJsonObject("untaken") : new JsonObject();
				root.add("untaken", untaken);
				floorUntakenRows(untaken, bySource);
			}
			if (byItem != null)
			{
				JsonObject items = root.has("untaken_items") && root.get("untaken_items").isJsonObject()
					? root.getAsJsonObject("untaken_items") : new JsonObject();
				root.add("untaken_items", items);
				floorUntakenRows(items, byItem);
			}
		}
	}

	private static void floorUntakenRows(JsonObject store, java.util.List<String[]> rows)
	{
		for (String[] row : rows)
		{
			JsonObject e = store.has(row[0]) && store.get(row[0]).isJsonObject()
				? store.getAsJsonObject(row[0]) : new JsonObject();
			if (!store.has(row[0]))
			{
				e.addProperty("qty", 0);
				e.addProperty("value", 0);
				store.add(row[0], e);
			}
			long qty = Long.parseLong(row[1]);
			long value = Long.parseLong(row[2]);
			if (qty > (e.has("qty") ? e.get("qty").getAsLong() : 0))
			{
				e.addProperty("qty", qty);
			}
			if (value > (e.has("value") ? e.get("value").getAsLong() : 0))
			{
				e.addProperty("value", value);
			}
		}
	}

	/** Floor the server-priced per-key consumable values ({key: gp}). */
	void floorConsumableValues(java.util.List<String[]> rows, String rsn)
	{
		if (!isReadyFor(rsn) || rows == null)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject store = root.has("consumable_values") && root.get("consumable_values").isJsonObject()
				? root.getAsJsonObject("consumable_values") : new JsonObject();
			root.add("consumable_values", store);
			for (String[] row : rows)
			{
				long v = Long.parseLong(row[1]);
				long cur = store.has(row[0]) ? store.get(row[0]).getAsLong() : 0;
				if (v > cur)
				{
					store.addProperty(row[0], v);
				}
			}
		}
	}

	/** Server-priced gp per consumable counter key (empty when never adopted). */
	java.util.Map<String, Long> consumableValues()
	{
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (root != null && root.has("consumable_values")
				&& root.get("consumable_values").isJsonObject())
			{
				for (java.util.Map.Entry<String, JsonElement> e
					: root.getAsJsonObject("consumable_values").entrySet())
				{
					try
					{
						out.put(e.getKey(), e.getValue().getAsLong());
					}
					catch (RuntimeException ignored)
					{
						// non-numeric — skip
					}
				}
			}
		}
		return out;
	}

	/** The per-item side of the uncollected ledger (adopted + any local adds). */
	java.util.List<UntakenRow> untakenItems()
	{
		java.util.List<UntakenRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("untaken_items")
				|| !root.get("untaken_items").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("untaken_items").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject it = e.getValue().getAsJsonObject();
				out.add(new UntakenRow(e.getKey(),
					it.has("qty") ? it.get("qty").getAsLong() : 0,
					it.has("value") ? it.get("value").getAsLong() : 0));
			}
		}
		return out;
	}

	java.util.List<UntakenRow> untakenSources()
	{
		java.util.List<UntakenRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("untaken") || !root.get("untaken").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("untaken").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject src = e.getValue().getAsJsonObject();
				out.add(new UntakenRow(e.getKey(),
					src.has("qty") ? src.get("qty").getAsLong() : 0,
					src.has("value") ? src.get("value").getAsLong() : 0));
			}
		}
		return out;
	}

	long[] sessionUntakenTally()
	{
		synchronized (lock)
		{
			return new long[]{sessionUntaken, sessionUntakenValue};
		}
	}

	/** This session's drop sources only (loots, value per source). */
	java.util.List<SourceRow> sessionSourceRows()
	{
		java.util.List<SourceRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			for (java.util.Map.Entry<String, long[]> e : sessionSources.entrySet())
			{
				out.add(new SourceRow(e.getKey(), 0, (int) e.getValue()[0],
					e.getValue()[1], null, 0));
			}
		}
		return out;
	}

	/** The journal-held clog fraction: {finished, available}, zero when unknown. */
	/** A fresh COLLECTION event lights its slot immediately: the item joins
	 *  clog_items and the finished tally rises when it's genuinely new. The
	 *  next full in-game log open reconciles everything via mergeClog. */
	private void recordClogSlot(JsonObject data)
	{
		String name = data.has("itemName") && !data.get("itemName").isJsonNull()
			? data.get("itemName").getAsString() : null;
		if (name == null || name.isEmpty())
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject cl = root.has("collection_log") && root.get("collection_log").isJsonObject()
				? root.getAsJsonObject("collection_log") : new JsonObject();
			root.add("collection_log", cl);
			JsonObject items = cl.has("clog_items") && cl.get("clog_items").isJsonObject()
				? cl.getAsJsonObject("clog_items") : new JsonObject();
			cl.add("clog_items", items);
			boolean known = false;
			for (java.util.Map.Entry<String, JsonElement> e : items.entrySet())
			{
				if (e.getKey().equalsIgnoreCase(name))
				{
					known = true;
					break;
				}
			}
			if (!known)
			{
				items.addProperty(name, 1);
				long fin = cl.has("finished") ? cl.get("finished").getAsLong() : 0;
				cl.addProperty("finished", fin + 1);
			}
			root.addProperty("updated_at", nowSec());
		}
	}

	int[] clogFraction()
	{
		synchronized (lock)
		{
			if (root == null || !root.has("collection_log")
				|| !root.get("collection_log").isJsonObject())
			{
				return new int[]{0, 0};
			}
			JsonObject cl = root.getAsJsonObject("collection_log");
			return new int[]{
				cl.has("finished") ? (int) asLong(cl.get("finished")) : 0,
				cl.has("available") ? (int) asLong(cl.get("available")) : 0};
		}
	}

	private static JsonObject mergeClog(JsonObject base, JsonObject inc)
	{
		JsonObject out = new JsonObject();
		JsonObject byCat = new JsonObject();
		for (JsonObject src : new JsonObject[]{base, inc})
		{
			if (src.has("by_cat") && src.get("by_cat").isJsonObject())
			{
				for (java.util.Map.Entry<String, JsonElement> pg
					: src.getAsJsonObject("by_cat").entrySet())
				{
					if (!pg.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject tgt = byCat.has(pg.getKey())
						? byCat.getAsJsonObject(pg.getKey()) : new JsonObject();
					for (java.util.Map.Entry<String, JsonElement> it
						: pg.getValue().getAsJsonObject().entrySet())
					{
						long n = asLong(it.getValue());
						if (n > (tgt.has(it.getKey()) ? asLong(tgt.get(it.getKey())) : 0))
						{
							tgt.addProperty(it.getKey(), n);
						}
					}
					byCat.add(pg.getKey(), tgt);
				}
			}
		}
		out.add("by_cat", byCat);
		for (String mapKey : new String[]{"kcs", "clog_items", "cat_counts", "slayer_kcs"})
		{
			JsonObject merged = new JsonObject();
			for (JsonObject src : new JsonObject[]{base, inc})
			{
				if (src.has(mapKey) && src.get(mapKey).isJsonObject())
				{
					for (java.util.Map.Entry<String, JsonElement> e
						: src.getAsJsonObject(mapKey).entrySet())
					{
						long n = asLong(e.getValue());
						if (n > (merged.has(e.getKey()) ? asLong(merged.get(e.getKey())) : 0))
						{
							merged.addProperty(e.getKey(), n);
						}
					}
				}
			}
			if (merged.size() > 0)
			{
				out.add(mapKey, merged);
			}
		}
		for (String numKey : new String[]{"finished", "available"})
		{
			long a = base.has(numKey) ? asLong(base.get(numKey)) : 0;
			long b = inc.has(numKey) ? asLong(inc.get(numKey)) : 0;
			out.addProperty(numKey, Math.max(a, b));
		}
		return out;
	}

	private static long asLong(JsonElement e)
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

	/** The journal's stored collection log, deep-copied for the panel. */
	JsonObject clogSnapshot()
	{
		synchronized (lock)
		{
			if (root == null || !root.has("collection_log")
				|| !root.get("collection_log").isJsonObject())
			{
				return new JsonObject();
			}
			return root.getAsJsonObject("collection_log").deepCopy();
		}
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
