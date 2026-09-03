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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * The on-disk journal: one {@code <slug>.json} per account under
 * {@code .runelite/chronicle/}, loaded at login and rewritten as you play. It is
 * the record the side panel reads, and it never leaves this computer.
 *
 * <p>Threading: {@link #record} and {@link #setCharacter} run on the client thread
 * (they read {@link ItemManager}); {@link #load} and {@link #flush} run on a
 * background executor. The in-memory model is guarded by {@link #lock}, and the
 * file-writing methods hold it only long enough to serialise a string, so the
 * client thread never blocks on I/O.
 */
@Singleton
@Slf4j
class LocalStore implements chronicle.counters.GatheredLedger
{
	static final int SCHEMA = 1;
	// runaway guard
	private static final int FEED_CAP = 20000;
	// peak counters: lifetime is max(base, session) rather than base + session.
	static final java.util.Set<String> MAX_KEYS = new java.util.HashSet<>(
		java.util.Arrays.asList("highestHit", "highestHitTaken"));
	// Event types kept as dated feed lines. LOOT and LOOT_UNTAKEN are recorded too,
	// into the drop and untaken ledgers; GROUP_STORAGE isn't kept at all.
	private static final java.util.Set<String> FEED_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
		"PET", "COLLECTION", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "DEATH", "SLAYER",
		"LEVEL",
		"SESSION"));   // SESSION is recorded by the plugin itself, so it stays off the network

	private final ItemManager itemManager;
	private final Gson gson;

	private final Object lock = new Object();
	private JsonObject root;          // the current account's model (guarded by lock)
	private JsonObject trackersBase;  // lifetime counters frozen at load; +session = lifetime
	private String currentRsn;        // whose model root holds
	private volatile boolean ready;   // true once an account's file has been loaded
	// Why the journal isn't reaching disk, or null. The panel shows it; a stalled
	// journal still looks alive in memory otherwise.
	private volatile String journalWarning;

	// Session tallies for the panel strip and recent-drop row. In memory only,
	// cleared at the account boundary; guarded by lock.
	private int sessionLoots;
	private long sessionLootValue;
	private int sessionUntaken;
	private long sessionUntakenValue;
	private final java.util.ArrayDeque<RecentDrop> recentDrops = new java.util.ArrayDeque<>();

	// runaway guard; every log, ore, fish and gem in the game is a few hundred ids,
	// and the journal is rewritten whole on every flush.
	private static final int GATHERED_CAP = 1024;
	// Lock-free mirror of the record's "gathered_items", read on every drop click.
	// The resolver writes it on the client thread while load() rebuilds it on the executor.
	private final java.util.Set<Integer> gatheredItems =
		java.util.concurrent.ConcurrentHashMap.newKeySet();

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

	/** Mount this account's record, or start one. Runs on the executor; call once
	 *  per login, before anything records. */
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
			catch (Exception e)   // noqa: a torn file is set aside below
			{
				log.warn("local record unreadable: {}", f, e);
			}
			if (loaded == null)
			{
				// The only copy of this account's history, and the next flush would write
				// a blank skeleton over it. Keep the bytes under a dated sidecar.
				setAside(f, "corrupt");
			}
		}
		long fileSchema = loaded != null ? asLong(loaded.get("schema")) : 0;
		if (fileSchema > SCHEMA)
		{
			// A newer schema would be stamped down by normalise() and rewritten. Mount nothing.
			log.warn("journal {} is schema {}; this build reads {}",
				f.getName(), fileSchema, SCHEMA);
			journalWarning = "This journal was written by a newer version of Chronicle. "
				+ "Update the plugin to open it. Nothing on disk has been changed.";
			synchronized (lock)
			{
				// Empty rather than null: a model going null under the panel's reads throws
				// on the EDT. No currentRsn, so flush() can't write it over the real record.
				root = skeleton(rsn);
				trackersBase = new JsonObject();
				currentRsn = null;
				ready = false;
				gatheredItems.clear();
			}
			return;
		}
		if (loaded == null)
		{
			loaded = skeleton(rsn);
		}
		normalise(loaded, rsn);
		synchronized (lock)
		{
			root = loaded;
			// Freeze the loaded lifetime counters; setTrackers() recomputes the live
			// total as this base + the current session.
			trackersBase = deepCopy(loaded.getAsJsonObject("trackers"));
			currentRsn = rsn;
			// What an earlier build, or an import, could leave inconsistent: the same
			// item twice in one source's bag, a feed line twice, by-item leavings that
			// no longer sum to the pairs.
			int healed = dedupeSourceBags() + dedupeFeed() + reconcileUntaken();
			if (healed > 0)
			{
				log.debug("repaired {} journal entries on load", healed);
			}
			// Cleared first: the mirror must describe this account only, or the previous
			// character's ore would credit this one's drops.
			gatheredItems.clear();
			if (loaded.has("gathered_items") && loaded.get("gathered_items").isJsonArray())
			{
				for (JsonElement g : loaded.getAsJsonArray("gathered_items"))
				{
					long id = asLong(g);
					if (id > 0 && gatheredItems.size() < GATHERED_CAP)
					{
						gatheredItems.add((int) id);
					}
				}
			}
			ready = true;
		}
		// Whatever was wrong belongs to the last account. A disk still refusing
		// writes re-states itself on the next flush.
		journalWarning = null;
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
			recentDrops.clear();
			// Account-scoped. load() reads it back from the record.
			gatheredItems.clear();
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
			if ("COLLECTION".equals(type))
			{
				recordClogSlot(data);
			}
			if ("SLAYER".equals(type))
			{
				recordSlayerCompletion(data);
			}
			JsonObject entry = new JsonObject();
			entry.addProperty("ts", System.currentTimeMillis());
			entry.addProperty("type", type);
			entry.add("data", data);
			synchronized (lock)
			{
				JsonArray feed = root.getAsJsonArray("feed");
				feed.add(entry);
				// index 0 is the oldest; the feed is appended in order
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
				BagItem b = price(it.get("id").getAsInt(),
					it.has("quantity") ? it.get("quantity").getAsInt() : 1);
				batchValue += b.value;
				JsonObject p = new JsonObject();
				p.addProperty("id", b.itemId);
				p.addProperty("name", b.name);
				p.addProperty("qty", b.qty);
				p.addProperty("value", b.value);
				priced.add(p);
			}
		}

		// The standing PB when the game restated it, else this kill's own time when
		// it was the record.
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

		String slayerTask = data.has("slayerTask") && !data.get("slayerTask").isJsonNull()
			? data.get("slayerTask").getAsString() : null;
		long slayerAssignment = data.has("slayerTaskInitial") && !data.get("slayerTaskInitial").isJsonNull()
			? data.get("slayerTaskInitial").getAsLong() : 0;

		synchronized (lock)
		{
			if (slayerTask != null && !slayerTask.isEmpty())
			{
				slayerLoot(slayerTask, slayerAssignment, batchValue, source, priced);
			}
			JsonObject drops = root.getAsJsonObject("drops");
			// Guarded field by field: an older journal's source entry (or a hand edit)
			// can be missing counters, and an exception here is eaten by the event bus.
			JsonObject src = drops.has(source) && drops.get(source).isJsonObject()
				? drops.getAsJsonObject(source) : null;
			if (src == null)
			{
				src = newSource();
				drops.add(source, src);
			}
			if (kc != null)
			{
				src.addProperty("kc", Math.max(asLong(src.get("kc")), kc.longValue()));
			}
			src.addProperty("loots", asLong(src.get("loots")) + 1);
			src.addProperty("value", asLong(src.get("value")) + batchValue);
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
			double bestPb = asDouble(src.get("pb"));
			if (pbCand != null && pbCand > 0 && (bestPb <= 0 || pbCand < bestPb))
			{
				src.addProperty("pb", pbCand);
			}

			if (!src.has("items") || !src.get("items").isJsonObject())
			{
				src.add("items", new JsonObject());
			}
			JsonObject bag = src.getAsJsonObject("items");
			for (JsonElement pe : priced)
			{
				JsonObject p = pe.getAsJsonObject();
				String key = String.valueOf(p.get("id").getAsInt());
				if (bag.has(key) && bag.get(key).isJsonObject())
				{
					JsonObject cur = bag.getAsJsonObject(key);
					cur.addProperty("qty", asLong(cur.get("qty")) + p.get("qty").getAsLong());
					cur.addProperty("value", asLong(cur.get("value")) + p.get("value").getAsLong());
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

	/** Refresh the character sheet. Runs on the client thread; {@code collectionLog}
	 *  is the capture's raw map, converted to a tree here. */
	void setCharacter(String rsn, JsonObject skills, int combatLevel,
		java.util.Map<String, Object> collectionLog, JsonObject achievements)
	{
		if (!isReadyFor(rsn))
		{
			return;
		}
		synchronized (lock)
		{
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
				// The session's capture is partial, covering only the pages browsed.
				// Clog data only grows; union it into the stored log.
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
	 * Re-freeze the lifetime base at the current journal values. Called before the
	 * session counter store is cleared (shutdown, or a cloudSync toggle), so the
	 * fresh from-zero session can't take back what was already folded in.
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

	/**
	 * Refresh the lifetime tracker counters from this session's live totals. Runs on
	 * the client thread. {@code session} is the from-zero session snapshot; lifetime
	 * is base + session (max for the peak counters), so repeat calls never double up.
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
	// Persist (background executor)
	// ------------------------------------------------------------------

	/** Write the JSON record, or do nothing while no account is mounted. */
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
			journalWarning = null;
		}
		catch (Exception e)   // noqa: the model is intact in memory; the next tick retries
		{
			// A full, read-only or locked directory drops every write while the panel,
			// served from memory, goes on looking live. Say so where it is read.
			log.warn("local flush failed", e);
			journalWarning = "Could not write the journal to disk: check free space and "
				+ "permissions on " + dir.getAbsolutePath() + ".";
		}
	}

	/** A fresh record: the identity fields here, the containers from normalise(),
	 *  which is the one place they are enumerated. */
	private JsonObject skeleton(String rsn)
	{
		JsonObject o = new JsonObject();
		o.addProperty("schema", SCHEMA);
		o.addProperty("rsn", rsn);
		o.addProperty("first_seen", nowSec());
		o.addProperty("updated_at", nowSec());
		normalise(o, rsn);
		return o;
	}

	/** An empty drop source, as all three merge paths start one. */
	private static JsonObject newSource()
	{
		JsonObject src = new JsonObject();
		src.addProperty("kc", 0);
		src.addProperty("loots", 0);
		src.addProperty("value", 0);
		src.add("items", new JsonObject());
		return src;
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

	/** Why the journal is not keeping the record, or null while it is. */
	String journalWarning()
	{
		return journalWarning;
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

	static final class SourceRow
	{
		final String name;
		final int kc;
		final int loots;
		final long value;
		final Double pb;
		// epoch ms, 0 = unknown; comes in from the Loot Tracker import's per-source
		// range and extends as play continues.
		final long firstMs;
		final long lastMs;

		SourceRow(String name, int kc, int loots, long value, Double pb,
			long firstMs, long lastMs)
		{
			this.name = name;
			this.kc = kc;
			this.loots = loots;
			this.value = value;
			this.pb = pb;
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

	/**
	 * Canonicalise an item id, then name it and price the stack. Notes and placeholders
	 * price as the real item. Client thread; the value is frozen into the record here
	 * and never repriced.
	 *
	 * <p>An id the ItemManager cannot compose is named after its number instead of
	 * throwing: one unknown id must not abort a caller's whole loop.
	 */
	BagItem price(int id, long qty)
	{
		int canon = itemManager.canonicalize(id);
		String name;
		try
		{
			name = itemManager.getItemComposition(canon).getName();
		}
		catch (Exception e)   // noqa: unknown id, fall back to the raw number
		{
			name = "Item " + id;
		}
		long each = Math.max(0, itemManager.getItemPrice(canon));
		return new BagItem(canon, name, qty, each * qty);
	}

	/** How an item is filed in a source's bag: its id when one is known, else
	 *  {@code n:} and the lowercased name. Both merge paths key the same way or the
	 *  same item lands on two lines. */
	private static String bagKey(int id, String name)
	{
		return id > 0 ? String.valueOf(id)
			: "n:" + (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT));
	}

	/** One source's whole record from the core Loot Tracker's local store, already
	 *  canonicalised and priced by the caller (client thread). */
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
	 * Floor this account's drops with the core Loot Tracker's own lifetime record.
	 * kc and loots take the tracker's event count as a lower bound (a higher
	 * game-reported kc survives), item qty/value match by id then by name, source
	 * value takes the priced sum, first_seen/last_seen extend as min/max. A re-run
	 * can only raise floors it already set.
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
					src = newSource();
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
						// a name-keyed entry picks up its real id here
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
						items.add(bagKey(b.itemId, b.name), hit);
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

	/** One source's item bag, unsorted. */
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

	/** Left-behind loot, priced at record like drops and aggregated per source
	 *  ({@code untaken: {source: {qty, value}}}). */
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
		java.util.List<BagItem> perItem = new java.util.ArrayList<>();
		for (JsonElement ie : items)
		{
			if (!ie.isJsonObject() || !ie.getAsJsonObject().has("id"))
			{
				continue;
			}
			JsonObject it = ie.getAsJsonObject();
			BagItem b = price(it.get("id").getAsInt(),
				it.has("quantity") ? it.get("quantity").getAsInt() : 1);
			qty += b.qty;
			value += b.value;
			perItem.add(b);
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
			// the same tally keyed by item name
			JsonObject byItem = root.has("untaken_items") && root.get("untaken_items").isJsonObject()
				? root.getAsJsonObject("untaken_items") : new JsonObject();
			for (BagItem b : perItem)
			{
				JsonObject e = byItem.has(b.name) && byItem.get(b.name).isJsonObject()
					? byItem.getAsJsonObject(b.name) : new JsonObject();
				e.addProperty("qty", (e.has("qty") ? e.get("qty").getAsLong() : 0) + b.qty);
				e.addProperty("value", (e.has("value") ? e.get("value").getAsLong() : 0) + b.value);
				byItem.add(b.name, e);
			}
			root.add("untaken_items", byItem);
			// …and the pairing, so the lens drills from either end: which items a
			// source left, and which sources left an item.
			JsonObject pairs = root.has("untaken_pairs") && root.get("untaken_pairs").isJsonObject()
				? root.getAsJsonObject("untaken_pairs") : new JsonObject();
			JsonObject bag = pairs.has(source) && pairs.get(source).isJsonObject()
				? pairs.getAsJsonObject(source) : new JsonObject();
			for (BagItem b : perItem)
			{
				JsonObject e = bag.has(b.name) && bag.get(b.name).isJsonObject()
					? bag.getAsJsonObject(b.name) : new JsonObject();
				e.addProperty("id", b.itemId);
				e.addProperty("qty", (e.has("qty") ? asLong(e.get("qty")) : 0) + b.qty);
				e.addProperty("value", (e.has("value") ? asLong(e.get("value")) : 0) + b.value);
				bag.add(b.name, e);
			}
			pairs.add(source, bag);
			root.add("untaken_pairs", pairs);
			sessionUntaken += qty;
			sessionUntakenValue += value;
			root.addProperty("updated_at", nowSec());
		}
	}

	/** A name/qty/value row: untaken sources, untaken items, task monsters. */
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

	/** Add the price of one consumption to a typed key's lifetime gp. Client thread. */
	void addConsumableValue(String key, long gp, String rsn)
	{
		if (!isReadyFor(rsn) || key == null || key.isEmpty() || gp <= 0)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject store = root.has("consumable_values") && root.get("consumable_values").isJsonObject()
				? root.getAsJsonObject("consumable_values") : new JsonObject();
			long cur = store.has(key) && !store.get(key).isJsonNull() ? store.get(key).getAsLong() : 0;
			store.addProperty(key, cur + gp);
			root.add("consumable_values", store);
			root.addProperty("updated_at", nowSec());
		}
	}

	/** Remember an item id this account gathered. Client thread, once per resolved
	 *  gathering action; it lives in the record so an ore mined last week and binned
	 *  today still reads as a resource dropped. */
	@Override
	public void noteGathered(int itemId)
	{
		if (itemId <= 0 || !ready || gatheredItems.contains(itemId)
			|| gatheredItems.size() >= GATHERED_CAP)
		{
			return;
		}
		synchronized (lock)
		{
			if (root == null || currentRsn == null || !gatheredItems.add(itemId))
			{
				return;
			}
			JsonArray ids = root.has("gathered_items") && root.get("gathered_items").isJsonArray()
				? root.getAsJsonArray("gathered_items") : new JsonArray();
			ids.add(itemId);
			root.add("gathered_items", ids);
			root.addProperty("updated_at", nowSec());
		}
	}

	@Override
	public boolean wasGathered(int itemId)
	{
		return itemId > 0 && gatheredItems.contains(itemId);
	}

	/** The lifetime base (pre-session) for one counter, or 0 when unknown. */
	long trackerBase(String key)
	{
		synchronized (lock)
		{
			return trackersBase != null && trackersBase.has(key)
				&& !trackersBase.get(key).isJsonNull()
				? trackersBase.get(key).getAsLong() : 0;
		}
	}

	// ------------------------------------------------------------------
	// The slayer journey (task-by-task, kept locally)
	// ------------------------------------------------------------------

	// runaway guard
	private static final int SLAYER_TASK_CAP = 1000;

	/** The slayer store, created on first use. Callers hold {@code lock}. */
	private JsonObject slayerRoot()
	{
		JsonObject sl = root.has("slayer") && root.get("slayer").isJsonObject()
			? root.getAsJsonObject("slayer") : new JsonObject();
		if (!root.has("slayer") || !root.get("slayer").isJsonObject())
		{
			root.add("slayer", sl);
		}
		if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
		{
			sl.add("tasks", new JsonArray());
		}
		return sl;
	}

	/** The newest task segment if it is open and names {@code task}, else null.
	 *  Callers hold {@code lock}. */
	private static JsonObject openSegment(JsonArray tasks, String task)
	{
		if (tasks.size() == 0)
		{
			return null;
		}
		JsonObject last = tasks.get(tasks.size() - 1).getAsJsonObject();
		boolean open = last.has("open") && last.get("open").getAsBoolean();
		return open && last.has("task")
			&& task.equalsIgnoreCase(last.get("task").getAsString()) ? last : null;
	}

	/** One on-task kill: extend (or open) the current task segment. Callers hold lock. */
	private void slayerLoot(String task, long assignment, long value,
		String monster, JsonArray items)
	{
		JsonObject sl = slayerRoot();
		JsonArray tasks = sl.getAsJsonArray("tasks");
		JsonObject seg = openSegment(tasks, task);
		if (seg == null)
		{
			seg = new JsonObject();
			seg.addProperty("task", task);
			seg.addProperty("kills", 0);
			seg.addProperty("assignment", 0);
			seg.addProperty("value", 0);
			seg.addProperty("open", true);
			tasks.add(seg);
			while (tasks.size() > SLAYER_TASK_CAP)
			{
				tasks.remove(0);
			}
		}
		seg.addProperty("kills", seg.get("kills").getAsLong() + 1);
		seg.addProperty("value", seg.get("value").getAsLong() + value);
		if (assignment > seg.get("assignment").getAsLong())
		{
			seg.addProperty("assignment", assignment);
		}
		seg.addProperty("ts", nowSec());
		// What the task was made of: a "blue dragons" assignment takes brutals too,
		// and its loot is a different question from the monster's lifetime bag.
		if (monster != null && !monster.isEmpty())
		{
			JsonObject mons = seg.has("monsters") && seg.get("monsters").isJsonObject()
				? seg.getAsJsonObject("monsters") : new JsonObject();
			mons.addProperty(monster, (mons.has(monster) ? asLong(mons.get(monster)) : 0) + 1);
			seg.add("monsters", mons);
		}
		if (items != null)
		{
			JsonObject bag = seg.has("items") && seg.get("items").isJsonObject()
				? seg.getAsJsonObject("items") : new JsonObject();
			for (JsonElement ie : items)
			{
				JsonObject it = ie.getAsJsonObject();
				String name = it.get("name").getAsString();
				JsonObject row = bag.has(name) && bag.get(name).isJsonObject()
					? bag.getAsJsonObject(name) : new JsonObject();
				row.addProperty("id", it.get("id").getAsInt());
				row.addProperty("qty", (row.has("qty") ? asLong(row.get("qty")) : 0)
					+ it.get("qty").getAsLong());
				row.addProperty("value", (row.has("value") ? asLong(row.get("value")) : 0)
					+ it.get("value").getAsLong());
				bag.add(name, row);
			}
			seg.add("items", bag);
		}
	}

	/**
	 * A task completion: close the open segment, opening one first if the whole task
	 * went unwitnessed. The finished line's exact kill count trues up the loot spine;
	 * kills the drops never saw surface as {@code noLootKills}.
	 */
	private void recordSlayerCompletion(JsonObject data)
	{
		String task = data.has("task") && !data.get("task").isJsonNull()
			? data.get("task").getAsString() : null;
		if (task == null || task.isEmpty())
		{
			return;
		}
		Long exact = data.has("killCount") && !data.get("killCount").isJsonNull()
			? data.get("killCount").getAsLong() : null;
		Long streak = data.has("count") && !data.get("count").isJsonNull()
			? data.get("count").getAsLong() : null;
		synchronized (lock)
		{
			JsonObject sl = slayerRoot();
			JsonArray tasks = sl.getAsJsonArray("tasks");
			JsonObject seg = openSegment(tasks, task);
			if (seg == null)
			{
				seg = new JsonObject();
				seg.addProperty("task", task);
				seg.addProperty("kills", 0);
				seg.addProperty("assignment", 0);
				seg.addProperty("value", 0);
				tasks.add(seg);
				while (tasks.size() > SLAYER_TASK_CAP)
				{
					tasks.remove(0);
				}
			}
			long kills = seg.get("kills").getAsLong();
			if (exact != null && exact > 0)
			{
				if (exact > kills)
				{
					seg.addProperty("noLootKills", exact - kills);
				}
				seg.addProperty("kills", Math.max(kills, exact));
				if (exact > seg.get("assignment").getAsLong())
				{
					seg.addProperty("assignment", exact);
				}
			}
			seg.addProperty("open", false);
			seg.addProperty("ts", nowSec());
			long done = sl.has("completed") ? asLong(sl.get("completed")) : 0;
			// the streak line's lifetime total wins when it's ahead
			sl.addProperty("completed", streak != null && streak > done ? streak : done + 1);
			root.addProperty("updated_at", nowSec());
		}
	}

	/** The journey as the journal knows it, shaped for the panel (newest first). */
	chronicle.ChronicleApiClient.SlayerJourney slayerJourney()
	{
		synchronized (lock)
		{
			if (root == null)
			{
				return null;
			}
			JsonObject sl = root.has("slayer") && root.get("slayer").isJsonObject()
				? root.getAsJsonObject("slayer") : new JsonObject();
			JsonArray tasks = sl.has("tasks") && sl.get("tasks").isJsonArray()
				? sl.getAsJsonArray("tasks") : new JsonArray();
			java.util.List<chronicle.ChronicleApiClient.SlayerTask> out =
				new java.util.ArrayList<>(tasks.size());
			long totalKills = 0;
			long totalValue = 0;
			for (int i = tasks.size() - 1; i >= 0; i--)
			{
				if (!tasks.get(i).isJsonObject())
				{
					continue;
				}
				JsonObject seg = tasks.get(i).getAsJsonObject();
				long kills = seg.has("kills") ? asLong(seg.get("kills")) : 0;
				long value = seg.has("value") ? asLong(seg.get("value")) : 0;
				totalKills += kills;
				totalValue += value;
				out.add(new chronicle.ChronicleApiClient.SlayerTask(
					seg.has("task") ? seg.get("task").getAsString() : "?",
					kills,
					seg.has("assignment") ? asLong(seg.get("assignment")) : 0,
					seg.has("noLootKills") ? asLong(seg.get("noLootKills")) : 0,
					seg.has("ts") ? asLong(seg.get("ts")) : 0,
					value,
					seg.has("open") && seg.get("open").getAsBoolean()));
			}
			return new chronicle.ChronicleApiClient.SlayerJourney(
				(int) (sl.has("completed") ? asLong(sl.get("completed")) : 0),
				totalKills, totalValue,
				sl.has("xp_est") ? asLong(sl.get("xp_est")) : 0,
				out);
		}
	}

	/** Lifetime gp per consumable counter key, accumulated at each bite or dose. */
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
						// non-numeric, skip
					}
				}
			}
		}
		return out;
	}

	/** The per-item side of the uncollected ledger. */
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

	/** A COLLECTION event lights its slot at once: the item joins clog_items and
	 *  the finished tally rises when it's new. The next full log open reconciles it. */
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

	/** {finished, available} as the journal holds them; zero when unknown. */
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

	private static double asDouble(JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsDouble() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}

	/**
	 * Merge another Chronicle journal into this account's record. Every store floors:
	 * per-key max, earliest wins on first_seen, best wins on a personal best. An
	 * import is the same account seen from somewhere else, so its history overlaps
	 * this one and summing would double every shared kill; flooring makes a repeat
	 * import a no-op. Runs off the client thread.
	 *
	 * @return a short summary of what came across.
	 */
	String importJournal(JsonObject in, String rsn)
	{
		if (!isReadyFor(rsn) || in == null)
		{
			return null;
		}
		int sources = 0;
		int events = 0;
		int counters = 0;
		synchronized (lock)
		{
			// Lifetime counters: floor the frozen base AND the shown values, so the
			// import survives the next session recompute.
			if (in.has("trackers") && in.get("trackers").isJsonObject())
			{
				JsonObject tr = root.has("trackers") && root.get("trackers").isJsonObject()
					? root.getAsJsonObject("trackers") : new JsonObject();
				for (java.util.Map.Entry<String, JsonElement> e
					: in.getAsJsonObject("trackers").entrySet())
				{
					long v = asLong(e.getValue());
					if (v <= 0)
					{
						continue;
					}
					if (v > (trackersBase.has(e.getKey()) ? asLong(trackersBase.get(e.getKey())) : 0))
					{
						trackersBase.addProperty(e.getKey(), v);
						counters++;
					}
					if (v > (tr.has(e.getKey()) ? asLong(tr.get(e.getKey())) : 0))
					{
						tr.addProperty(e.getKey(), v);
					}
				}
				root.add("trackers", tr);
			}
			// Drop ledger: per source, then per item inside it.
			if (in.has("drops") && in.get("drops").isJsonObject())
			{
				JsonObject drops = root.getAsJsonObject("drops");
				for (java.util.Map.Entry<String, JsonElement> e
					: in.getAsJsonObject("drops").entrySet())
				{
					if (!e.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject inc = e.getValue().getAsJsonObject();
					JsonObject cur = drops.has(e.getKey()) && drops.get(e.getKey()).isJsonObject()
						? drops.getAsJsonObject(e.getKey()) : newSource();
					if (!drops.has(e.getKey()))
					{
						drops.add(e.getKey(), cur);
						sources++;
					}
					floorNumber(cur, inc, "kc");
					floorNumber(cur, inc, "loots");
					floorNumber(cur, inc, "value");
					floorNumber(cur, inc, "last_seen");
					// first_seen only ever moves earlier
					if (inc.has("first_seen") && !inc.get("first_seen").isJsonNull())
					{
						long incFirst = asLong(inc.get("first_seen"));
						long curFirst = cur.has("first_seen") ? asLong(cur.get("first_seen")) : 0;
						if (incFirst > 0 && (curFirst == 0 || incFirst < curFirst))
						{
							cur.addProperty("first_seen", incFirst);
						}
					}
					// a PB is the lowest time; the compare is flipped
					if (inc.has("pb") && !inc.get("pb").isJsonNull())
					{
						double incPb = inc.get("pb").getAsDouble();
						if (incPb > 0 && (!cur.has("pb") || incPb < cur.get("pb").getAsDouble()))
						{
							cur.addProperty("pb", incPb);
						}
					}
					if (inc.has("items") && inc.get("items").isJsonObject())
					{
						JsonObject bag = cur.has("items") && cur.get("items").isJsonObject()
							? cur.getAsJsonObject("items") : new JsonObject();
						// The bag is keyed by item id and an export may know only names, so
						// match on name first; otherwise one herb ends up on two lines.
						java.util.Map<String, String> byName = new java.util.HashMap<>();
						for (java.util.Map.Entry<String, JsonElement> be : bag.entrySet())
						{
							if (be.getValue().isJsonObject())
							{
								JsonObject b = be.getValue().getAsJsonObject();
								if (b.has("name") && !b.get("name").isJsonNull())
								{
									byName.put(b.get("name").getAsString()
										.toLowerCase(java.util.Locale.ROOT), be.getKey());
								}
							}
						}
						for (java.util.Map.Entry<String, JsonElement> ie
							: inc.getAsJsonObject("items").entrySet())
						{
							if (!ie.getValue().isJsonObject())
							{
								continue;
							}
							JsonObject incItem = ie.getValue().getAsJsonObject();
							String incName = incItem.has("name") && !incItem.get("name").isJsonNull()
								? incItem.get("name").getAsString() : ie.getKey();
							String key = byName.get(incName.toLowerCase(java.util.Locale.ROOT));
							if (key == null)
							{
								// No name match: file it under its id when the entry
								// carried one, so the panel can draw the sprite, else keep
								// the incoming map key, which on an id-keyed bag is the id;
								// dedupeSourceBags below folds in the plain-name keys an
								// older export left behind
								key = incItem.has("id") && incItem.get("id").getAsInt() > 0
									? bagKey(incItem.get("id").getAsInt(), incName)
									: ie.getKey();
							}
							JsonObject curItem = bag.has(key) && bag.get(key).isJsonObject()
								? bag.getAsJsonObject(key) : new JsonObject();
							floorNumber(curItem, incItem, "qty");
							floorNumber(curItem, incItem, "value");
							if (!curItem.has("name"))
							{
								curItem.addProperty("name", incName);
							}
							if (!curItem.has("id") && incItem.has("id"))
							{
								curItem.add("id", incItem.get("id"));
							}
							bag.add(key, curItem);
							byName.put(incName.toLowerCase(java.util.Locale.ROOT), key);
						}
						cur.add("items", bag);
					}
				}
			}
			// the dated feed, deduplicated on feedKey
			if (in.has("feed") && in.get("feed").isJsonArray())
			{
				JsonArray feed = root.getAsJsonArray("feed");
				java.util.Set<String> seen = new java.util.HashSet<>();
				for (JsonElement e : feed)
				{
					if (e.isJsonObject())
					{
						seen.add(feedKey(e.getAsJsonObject()));
					}
				}
				for (JsonElement e : in.getAsJsonArray("feed"))
				{
					if (!e.isJsonObject() || !seen.add(feedKey(e.getAsJsonObject())))
					{
						continue;
					}
					feed.add(e.getAsJsonObject().deepCopy());
					events++;
				}
				// an import interleaves, and the panel reads the feed in stored order
				java.util.List<JsonObject> all = new java.util.ArrayList<>(feed.size());
				for (JsonElement e : feed)
				{
					if (e.isJsonObject())
					{
						all.add(e.getAsJsonObject());
					}
				}
				all.sort(java.util.Comparator.comparingLong(
					o -> o.has("ts") ? asLong(o.get("ts")) : 0));
				JsonArray rebuilt = new JsonArray();
				for (int i = Math.max(0, all.size() - FEED_CAP); i < all.size(); i++)
				{
					rebuilt.add(all.get(i));
				}
				root.add("feed", rebuilt);
			}
			// collection log: the same max-union used on capture
			if (in.has("collection_log") && in.get("collection_log").isJsonObject())
			{
				JsonObject cl = root.has("collection_log") && root.get("collection_log").isJsonObject()
					? root.getAsJsonObject("collection_log") : new JsonObject();
				root.add("collection_log", mergeClog(cl, in.getAsJsonObject("collection_log")));
			}
			// The gathered ledger travels too, or ore mined on the other machine goes
			// unrecognised here.
			if (in.has("gathered_items") && in.get("gathered_items").isJsonArray())
			{
				JsonArray have = root.has("gathered_items") && root.get("gathered_items").isJsonArray()
					? root.getAsJsonArray("gathered_items") : new JsonArray();
				java.util.Set<Integer> seen = new java.util.HashSet<>();
				for (JsonElement g : have)
				{
					seen.add(g.getAsInt());
				}
				for (JsonElement g : in.getAsJsonArray("gathered_items"))
				{
					try
					{
						int id = g.getAsInt();
						if (seen.add(id))
						{
							have.add(id);
							gatheredItems.add(id);
						}
					}
					catch (RuntimeException ignored)
					{
						// not an item id
					}
				}
				root.add("gathered_items", have);
			}
			for (String store : new String[]{"untaken", "untaken_items", "consumable_values"})
			{
				floorNestedStore(store, in);
			}
			// The left-behind pairing is two levels deep.
			if (in.has("untaken_pairs") && in.get("untaken_pairs").isJsonObject())
			{
				JsonObject pairs = root.has("untaken_pairs") && root.get("untaken_pairs").isJsonObject()
					? root.getAsJsonObject("untaken_pairs") : new JsonObject();
				for (java.util.Map.Entry<String, JsonElement> e
					: in.getAsJsonObject("untaken_pairs").entrySet())
				{
					if (!e.getValue().isJsonObject())
					{
						continue;
					}
					JsonObject bag = pairs.has(e.getKey()) && pairs.get(e.getKey()).isJsonObject()
						? pairs.getAsJsonObject(e.getKey()) : new JsonObject();
					for (java.util.Map.Entry<String, JsonElement> ie
						: e.getValue().getAsJsonObject().entrySet())
					{
						if (!ie.getValue().isJsonObject())
						{
							continue;
						}
						JsonObject incItem = ie.getValue().getAsJsonObject();
						JsonObject curItem = bag.has(ie.getKey()) && bag.get(ie.getKey()).isJsonObject()
							? bag.getAsJsonObject(ie.getKey()) : new JsonObject();
						floorNumber(curItem, incItem, "qty");
						floorNumber(curItem, incItem, "value");
						if (!curItem.has("id") && incItem.has("id"))
						{
							curItem.add("id", incItem.get("id"));
						}
						bag.add(ie.getKey(), curItem);
					}
					pairs.add(e.getKey(), bag);
				}
				root.add("untaken_pairs", pairs);
			}
			// The spine copies only into an empty one: two overlapping task lists
			// can't be reconciled segment by segment.
			if (in.has("slayer") && in.get("slayer").isJsonObject())
			{
				JsonObject incSl = in.getAsJsonObject("slayer");
				JsonObject sl = slayerRoot();
				JsonArray tasks = sl.getAsJsonArray("tasks");
				if (tasks.size() == 0 && incSl.has("tasks") && incSl.get("tasks").isJsonArray())
				{
					for (JsonElement t : incSl.getAsJsonArray("tasks"))
					{
						if (t.isJsonObject())
						{
							tasks.add(t.getAsJsonObject().deepCopy());
						}
					}
					// an imported spine is bounded like a grown one
					while (tasks.size() > SLAYER_TASK_CAP)
					{
						tasks.remove(0);
					}
				}
				else if (incSl.has("tasks") && incSl.get("tasks").isJsonArray())
				{
					// A spine already stands: take only detail this one lacks (monsters,
					// items). Matched on task and a nearby ts; the two sides round the
					// instant differently, so equality would match nothing.
					for (JsonElement t : incSl.getAsJsonArray("tasks"))
					{
						if (!t.isJsonObject())
						{
							continue;
						}
						JsonObject incSeg = t.getAsJsonObject();
						JsonObject seg = nearestSegment(tasks, incSeg);
						if (seg == null)
						{
							continue;
						}
						mergeSegmentDetail(seg, incSeg, "monsters");
						mergeSegmentDetail(seg, incSeg, "items");
					}
				}
				for (String k : new String[]{"completed", "xp_est"})
				{
					if (incSl.has(k) && asLong(incSl.get(k)) > (sl.has(k) ? asLong(sl.get(k)) : 0))
					{
						sl.addProperty(k, asLong(incSl.get(k)));
					}
				}
			}
			dedupeSourceBags();
			dedupeFeed();
			root.addProperty("updated_at", nowSec());
		}
		return sources + " sources · " + String.format(java.util.Locale.UK, "%,d", events)
			+ " journal entries · " + counters + " counters";
	}

	/** How far two records of the same task instant may drift and still be it. */
	private static final long SEGMENT_MATCH_SECONDS = 60;

	/** The local segment an incoming one describes, or null if none does. */
	private static JsonObject nearestSegment(JsonArray tasks, JsonObject inc)
	{
		String task = inc.has("task") ? inc.get("task").getAsString() : null;
		if (task == null)
		{
			return null;
		}
		long ts = inc.has("ts") ? asLong(inc.get("ts")) : 0;
		JsonObject best = null;
		long bestGap = Long.MAX_VALUE;
		for (JsonElement e : tasks)
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject seg = e.getAsJsonObject();
			if (!seg.has("task") || !task.equalsIgnoreCase(seg.get("task").getAsString()))
			{
				continue;
			}
			long gap = Math.abs((seg.has("ts") ? asLong(seg.get("ts")) : 0) - ts);
			if (gap <= SEGMENT_MATCH_SECONDS && gap < bestGap)
			{
				bestGap = gap;
				best = seg;
			}
		}
		return best;
	}

	/** Floor one segment's {@code monsters} or {@code items} map into another's. */
	private static void mergeSegmentDetail(JsonObject seg, JsonObject inc, String key)
	{
		if (!inc.has(key) || !inc.get(key).isJsonObject())
		{
			return;
		}
		JsonObject cur = seg.has(key) && seg.get(key).isJsonObject()
			? seg.getAsJsonObject(key) : new JsonObject();
		for (java.util.Map.Entry<String, JsonElement> e : inc.getAsJsonObject(key).entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				JsonObject incRow = e.getValue().getAsJsonObject();
				JsonObject curRow = cur.has(e.getKey()) && cur.get(e.getKey()).isJsonObject()
					? cur.getAsJsonObject(e.getKey()) : new JsonObject();
				floorNumber(curRow, incRow, "qty");
				floorNumber(curRow, incRow, "value");
				if (!curRow.has("id") && incRow.has("id"))
				{
					curRow.add("id", incRow.get("id"));
				}
				cur.add(e.getKey(), curRow);
			}
			else
			{
				long v = asLong(e.getValue());
				if (v > (cur.has(e.getKey()) ? asLong(cur.get(e.getKey())) : 0))
				{
					cur.addProperty(e.getKey(), v);
				}
			}
		}
		seg.add(key, cur);
	}

	/**
	 * Identity of a feed line: kind, the second it happened in, and subject. The exact
	 * instant is too fine: a re-imported event can land a millisecond off. The second
	 * on its own is too coarse, since a clue casket empties several slots inside it.
	 */
	private static String feedKey(JsonObject e)
	{
		long sec = (e.has("ts") ? asLong(e.get("ts")) : 0) / 1000L;
		String kind = e.has("type") && !e.get("type").isJsonNull()
			? e.get("type").getAsString() : "";
		return kind + "|" + sec + "|" + feedSubject(e);
	}

	/** What a feed line is about: the thing it names, or failing that the payload. */
	private static String feedSubject(JsonObject e)
	{
		if (!e.has("data") || !e.get("data").isJsonObject())
		{
			return "";
		}
		JsonObject d = e.getAsJsonObject("data");
		// Tried in order. questName/killerName/area are what the capture writes for
		// QUEST, DEATH and DIARY; the last four turn up only in older journals.
		for (String field : new String[]{"itemName", "petName", "questName",
			"killerName", "area", "skill", "task", "monster",
			"name", "quest", "diary", "achievement"})
		{
			if (d.has(field) && !d.get(field).isJsonNull())
			{
				return d.get(field).getAsString().toLowerCase(java.util.Locale.ROOT);
			}
		}
		// an imported line can carry only a marker; then the payload is the identity
		JsonObject bare = d.deepCopy();
		bare.remove("imported");
		bare.remove("type");
		return bare.toString();
	}

	/**
	 * Collapse feed lines that describe the same moment; the fuller line survives.
	 * Timestamps can differ by a millisecond across an import, so a record can hold
	 * one log slot twice. Callers hold {@code lock}. Returns how many were absorbed.
	 */
	private int dedupeFeed()
	{
		if (root == null || !root.has("feed") || !root.get("feed").isJsonArray())
		{
			return 0;
		}
		JsonArray feed = root.getAsJsonArray("feed");
		java.util.Map<String, JsonObject> best = new java.util.LinkedHashMap<>();
		int absorbed = 0;
		for (JsonElement e : feed)
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			String key = feedKey(o);
			JsonObject held = best.get(key);
			if (held == null)
			{
				best.put(key, o);
				continue;
			}
			absorbed++;
			// keep whichever says more; a named line beats a bare "imported" marker
			if (payloadSize(o) > payloadSize(held))
			{
				best.put(key, o);
			}
		}
		if (absorbed > 0)
		{
			java.util.List<JsonObject> kept = new java.util.ArrayList<>(best.values());
			kept.sort(java.util.Comparator.comparingLong(
				o -> o.has("ts") ? asLong(o.get("ts")) : 0));
			JsonArray rebuilt = new JsonArray();
			for (JsonObject o : kept)
			{
				rebuilt.add(o);
			}
			root.add("feed", rebuilt);
		}
		return absorbed;
	}

	private static int payloadSize(JsonObject e)
	{
		return e.has("data") && e.get("data").isJsonObject()
			? e.getAsJsonObject("data").size() : 0;
	}

	/** Raise {@code cur[key]} to {@code inc[key]} when the incoming one is higher. */
	private static void floorNumber(JsonObject cur, JsonObject inc, String key)
	{
		if (!inc.has(key) || inc.get(key).isJsonNull())
		{
			return;
		}
		long v = asLong(inc.get(key));
		if (v > (cur.has(key) ? asLong(cur.get(key)) : 0))
		{
			cur.addProperty(key, v);
		}
	}

	/** Floor a flat {name: {qty, value}} store, or a flat {key: number} one. */
	private void floorNestedStore(String name, JsonObject in)
	{
		if (!in.has(name) || !in.get(name).isJsonObject())
		{
			return;
		}
		JsonObject cur = root.has(name) && root.get(name).isJsonObject()
			? root.getAsJsonObject(name) : new JsonObject();
		for (java.util.Map.Entry<String, JsonElement> e : in.getAsJsonObject(name).entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				JsonObject incRow = e.getValue().getAsJsonObject();
				JsonObject curRow = cur.has(e.getKey()) && cur.get(e.getKey()).isJsonObject()
					? cur.getAsJsonObject(e.getKey()) : new JsonObject();
				floorNumber(curRow, incRow, "qty");
				floorNumber(curRow, incRow, "value");
				cur.add(e.getKey(), curRow);
			}
			else
			{
				long v = asLong(e.getValue());
				if (v > (cur.has(e.getKey()) ? asLong(cur.get(e.getKey())) : 0))
				{
					cur.addProperty(e.getKey(), v);
				}
			}
		}
		root.add(name, cur);
	}

	/** Items one source was left holding, richest first. */
	java.util.List<BagItem> untakenItemsOf(String source)
	{
		java.util.List<BagItem> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("untaken_pairs") || !root.get("untaken_pairs").isJsonObject())
			{
				return out;
			}
			JsonObject pairs = root.getAsJsonObject("untaken_pairs");
			if (!pairs.has(source) || !pairs.get(source).isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e : pairs.getAsJsonObject(source).entrySet())
			{
				JsonObject r = e.getValue().getAsJsonObject();
				out.add(new BagItem(r.has("id") ? r.get("id").getAsInt() : -1, e.getKey(),
					asLong(r.get("qty")), asLong(r.get("value"))));
			}
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	/** Sources that left a given item on the ground, most first. */
	java.util.List<UntakenRow> untakenSourcesOf(String item)
	{
		java.util.List<UntakenRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("untaken_pairs") || !root.get("untaken_pairs").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("untaken_pairs").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject bag = e.getValue().getAsJsonObject();
				if (bag.has(item) && bag.get(item).isJsonObject())
				{
					JsonObject r = bag.getAsJsonObject(item);
					out.add(new UntakenRow(e.getKey(), asLong(r.get("qty")), asLong(r.get("value"))));
				}
			}
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	/** One task segment's own loot, richest first. */
	java.util.List<BagItem> slayerTaskItems(int index)
	{
		java.util.List<BagItem> out = new java.util.ArrayList<>();
		JsonObject seg = segmentAt(index);
		if (seg != null && seg.has("items") && seg.get("items").isJsonObject())
		{
			for (java.util.Map.Entry<String, JsonElement> e : seg.getAsJsonObject("items").entrySet())
			{
				JsonObject r = e.getValue().getAsJsonObject();
				out.add(new BagItem(r.has("id") ? r.get("id").getAsInt() : -1, e.getKey(),
					asLong(r.get("qty")), asLong(r.get("value"))));
			}
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	/** What a task was actually made of: {monster: kills}, most killed first. */
	java.util.List<UntakenRow> slayerTaskMonsters(int index)
	{
		java.util.List<UntakenRow> out = new java.util.ArrayList<>();
		JsonObject seg = segmentAt(index);
		if (seg != null && seg.has("monsters") && seg.get("monsters").isJsonObject())
		{
			for (java.util.Map.Entry<String, JsonElement> e : seg.getAsJsonObject("monsters").entrySet())
			{
				out.add(new UntakenRow(e.getKey(), asLong(e.getValue()), 0));
			}
		}
		out.sort((a, b) -> Long.compare(b.qty, a.qty));
		return out;
	}

	/** The task segment the panel's journey list calls {@code index}. The journey
	 *  is served newest-first, the store keeps them oldest-first. */
	private JsonObject segmentAt(int index)
	{
		synchronized (lock)
		{
			if (root == null || !root.has("slayer") || !root.get("slayer").isJsonObject())
			{
				return null;
			}
			JsonObject sl = root.getAsJsonObject("slayer");
			if (!sl.has("tasks") || !sl.get("tasks").isJsonArray())
			{
				return null;
			}
			JsonArray tasks = sl.getAsJsonArray("tasks");
			int at = tasks.size() - 1 - index;
			return at >= 0 && at < tasks.size() && tasks.get(at).isJsonObject()
				? tasks.get(at).getAsJsonObject() : null;
		}
	}

	/** Pets the journal has seen drop, newest first. Source and kc survive only on
	 *  rows imported from an older record: a PET event carries the name alone. */
	java.util.List<PetRow> pets()
	{
		java.util.List<PetRow> out = new java.util.ArrayList<>();
		synchronized (lock)
		{
			if (root == null || !root.has("feed") || !root.get("feed").isJsonArray())
			{
				return out;
			}
			java.util.Set<String> seen = new java.util.HashSet<>();
			JsonArray feed = root.getAsJsonArray("feed");
			for (int i = feed.size() - 1; i >= 0; i--)
			{
				if (!feed.get(i).isJsonObject())
				{
					continue;
				}
				JsonObject e = feed.get(i).getAsJsonObject();
				if (!"PET".equals(e.has("type") ? e.get("type").getAsString() : "")
					|| !e.has("data") || !e.get("data").isJsonObject())
				{
					continue;
				}
				JsonObject d = e.getAsJsonObject("data");
				String name = d.has("petName") && !d.get("petName").isJsonNull()
					? d.get("petName").getAsString() : null;
				if (name == null || name.isEmpty() || !seen.add(name.toLowerCase(java.util.Locale.ROOT)))
				{
					continue;
				}
				out.add(new PetRow(name,
					d.has("source") && !d.get("source").isJsonNull() ? d.get("source").getAsString() : null,
					d.has("killCount") && !d.get("killCount").isJsonNull() ? asLong(d.get("killCount")) : 0,
					e.has("ts") ? asLong(e.get("ts")) : 0));
			}
		}
		out.sort((a, b) -> Long.compare(b.ts, a.ts));
		return out;
	}

	static final class PetRow
	{
		final String name;
		final String source;
		final long kc;
		final long ts;

		PetRow(String name, String source, long kc, long ts)
		{
			this.name = name;
			this.source = source;
			this.kc = kc;
			this.ts = ts;
		}
	}

	/**
	 * Rebuild the per-item untaken totals from the source-and-item pairs.
	 *
	 * <p>The same leavings are stored three ways: by source, by item, and by the pair.
	 * The pairs carry the detail and the other two are sums of them, so anything that
	 * edits one store without the others leaves the by-item view claiming more than the
	 * by-source view of the same drops. This only runs when the pairs cover every source
	 * the by-source store knows, which is what makes them safe to sum from; a journal
	 * written before pairs existed is left alone. Callers hold {@code lock}. Returns how
	 * many item rows it corrected.
	 */
	private int reconcileUntaken()
	{
		if (root == null || !root.has("untaken_pairs") || !root.get("untaken_pairs").isJsonObject()
			|| !root.has("untaken_items") || !root.get("untaken_items").isJsonObject())
		{
			return 0;
		}
		JsonObject pairs = root.getAsJsonObject("untaken_pairs");
		if (pairs.size() == 0)
		{
			return 0;
		}
		if (root.has("untaken") && root.get("untaken").isJsonObject())
		{
			for (java.util.Map.Entry<String, JsonElement> se
				: root.getAsJsonObject("untaken").entrySet())
			{
				if (!pairs.has(se.getKey()))
				{
					return 0;
				}
			}
		}
		JsonObject rebuilt = new JsonObject();
		for (java.util.Map.Entry<String, JsonElement> pe : pairs.entrySet())
		{
			if (!pe.getValue().isJsonObject())
			{
				continue;
			}
			for (java.util.Map.Entry<String, JsonElement> ie
				: pe.getValue().getAsJsonObject().entrySet())
			{
				if (!ie.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject inc = ie.getValue().getAsJsonObject();
				if (!rebuilt.has(ie.getKey()))
				{
					JsonObject fresh = new JsonObject();
					fresh.addProperty("qty", 0);
					fresh.addProperty("value", 0);
					rebuilt.add(ie.getKey(), fresh);
				}
				JsonObject cur = rebuilt.getAsJsonObject(ie.getKey());
				cur.addProperty("qty", asLong(cur.get("qty")) + asLong(inc.get("qty")));
				cur.addProperty("value", asLong(cur.get("value")) + asLong(inc.get("value")));
			}
		}
		JsonObject was = root.getAsJsonObject("untaken_items");
		int corrected = 0;
		for (java.util.Map.Entry<String, JsonElement> e : rebuilt.entrySet())
		{
			JsonElement before = was.get(e.getKey());
			if (before == null || !before.equals(e.getValue()))
			{
				corrected++;
			}
		}
		for (java.util.Map.Entry<String, JsonElement> e : was.entrySet())
		{
			if (!rebuilt.has(e.getKey()))
			{
				corrected++;
			}
		}
		if (corrected > 0)
		{
			root.add("untaken_items", rebuilt);
		}
		return corrected;
	}

	/**
	 * Collapse item entries that name the same thing within one source. The bag is
	 * keyed by item id, a merged-in record may know only names, and an earlier build
	 * filed those alongside the entry already there. The survivor takes the higher of
	 * the two and keeps the id-bearing key. Callers hold {@code lock}. Returns how
	 * many entries were absorbed.
	 */
	private int dedupeSourceBags()
	{
		if (root == null || !root.has("drops") || !root.get("drops").isJsonObject())
		{
			return 0;
		}
		int absorbed = 0;
		for (java.util.Map.Entry<String, JsonElement> se
			: root.getAsJsonObject("drops").entrySet())
		{
			if (!se.getValue().isJsonObject())
			{
				continue;
			}
			JsonObject src = se.getValue().getAsJsonObject();
			if (!src.has("items") || !src.get("items").isJsonObject())
			{
				continue;
			}
			JsonObject bag = src.getAsJsonObject("items");
			java.util.Map<String, String> keep = new java.util.HashMap<>();
			java.util.List<String> drop = new java.util.ArrayList<>();
			for (java.util.Map.Entry<String, JsonElement> ie : bag.entrySet())
			{
				if (!ie.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject it = ie.getValue().getAsJsonObject();
				String name = it.has("name") && !it.get("name").isJsonNull()
					? it.get("name").getAsString().toLowerCase(java.util.Locale.ROOT)
					: ie.getKey().toLowerCase(java.util.Locale.ROOT);
				String held = keep.get(name);
				if (held == null)
				{
					keep.put(name, ie.getKey());
					continue;
				}
				// Prefer the numeric (id) key as the survivor.
				String winner = held;
				String loser = ie.getKey();
				if (!isNumeric(held) && isNumeric(ie.getKey()))
				{
					winner = ie.getKey();
					loser = held;
					keep.put(name, winner);
				}
				JsonObject w = bag.getAsJsonObject(winner);
				JsonObject l = bag.getAsJsonObject(loser);
				floorNumber(w, l, "qty");
				floorNumber(w, l, "value");
				if (!w.has("name") && l.has("name"))
				{
					w.add("name", l.get("name"));
				}
				drop.add(loser);
			}
			for (String k : drop)
			{
				bag.remove(k);
				absorbed++;
			}
		}
		return absorbed;
	}

	private static boolean isNumeric(String s)
	{
		if (s == null || s.isEmpty())
		{
			return false;
		}
		for (int i = 0; i < s.length(); i++)
		{
			if (!Character.isDigit(s.charAt(i)))
			{
				return false;
			}
		}
		return true;
	}

	/** Combat level as last gathered, or 0. */
	int combatLevel()
	{
		synchronized (lock)
		{
			return root != null && root.has("combat_level") && !root.get("combat_level").isJsonNull()
				? (int) asLong(root.get("combat_level")) : 0;
		}
	}

	/** The character sheet's skills: {skill: [level, xp]}, as last gathered. */
	java.util.Map<String, long[]> skillSheet()
	{
		java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();
		synchronized (lock)
		{
			if (root == null || !root.has("skills") || !root.get("skills").isJsonObject())
			{
				return out;
			}
			for (java.util.Map.Entry<String, JsonElement> e
				: root.getAsJsonObject("skills").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject o = e.getValue().getAsJsonObject();
				out.put(e.getKey(), new long[]{
					o.has("level") ? asLong(o.get("level")) : 0,
					o.has("xp") ? asLong(o.get("xp")) : 0});
			}
		}
		return out;
	}

	/**
	 * The account's achievement state as last gathered: {@code quests} by name against
	 * their state, {@code diaries} by region against each tier's completion, and
	 * {@code combat} points plus per-tier status. Deep-copied for the panel.
	 *
	 * <p>Empty where the sheet has never been gathered, which is not the same as an
	 * unmet requirement but is read as one: a chase printed off an unknown unlock is a
	 * claim the journal cannot make.
	 */
	JsonObject achievements()
	{
		synchronized (lock)
		{
			if (root == null || !root.has("achievements")
				|| !root.get("achievements").isJsonObject())
			{
				return new JsonObject();
			}
			return root.getAsJsonObject("achievements").deepCopy();
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

	/**
	 * Carry an account's journal across an in-game rename: move {@code <oldslug>.json}
	 * and {@code <oldslug>.history.jsonl} onto the new name's slugs. True when the
	 * journal itself moved.
	 *
	 * <p>A file already under the new slug is set aside, not mounted: freed names get
	 * taken, so it may be a stranger's record. Nothing is deleted. Journal and spine
	 * move together.
	 */
	static boolean migrateJournalFiles(File dir, String oldName, String newName)
	{
		String oldSlug = slug(oldName);
		String newSlug = slug(newName);
		if (oldSlug.equals(newSlug))
		{
			return false;
		}
		File journal = new File(dir, oldSlug + ".json");
		if (!journal.isFile())
		{
			// nothing to carry; leave whatever is under the new name alone
			return false;
		}
		File target = new File(dir, newSlug + ".json");
		if (target.exists() && !setAside(target, "conflict"))
		{
			return false;
		}
		if (!journal.renameTo(target))
		{
			log.warn("journal rename failed: {} -> {}", journal, target);
			return false;
		}
		File history = new File(dir, oldSlug + HistoryLog.SPINE_SUFFIX);
		File historyTarget = new File(dir, newSlug + HistoryLog.SPINE_SUFFIX);
		if (history.isFile() && (!historyTarget.exists() || setAside(historyTarget, "conflict"))
			&& !history.renameTo(historyTarget))
		{
			log.warn("history rename failed: {} -> {}", history, historyTarget);
		}
		return true;
	}

	/** Move a file aside under a dated sidecar name, keeping every byte. False when
	 *  it could not be moved. */
	private static boolean setAside(File f, String tag)
	{
		File aside = new File(f.getParentFile(),
			f.getName() + "." + tag + "-" + System.currentTimeMillis());
		try
		{
			Files.move(f.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
			log.warn("kept {} as {}", f.getName(), aside.getName());
			return true;
		}
		catch (Exception e)   // noqa: best-effort; a false return tells the caller
		{
			log.warn("could not set aside {}", f, e);
			return false;
		}
	}

	static String slug(String rsn)
	{
		// ROOT: a Turkish JVM lowercases I to a dotless ı, which the strip then eats.
		String s = rsn == null ? ""
			: rsn.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
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
		try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp))
		{
			out.write(content.getBytes(StandardCharsets.UTF_8));
			// The move below is atomic over the file's name only; without forcing the
			// bytes down first, a power cut leaves that name pointing at a torn record.
			out.getFD().sync();
		}
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

}
