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
class LocalStore implements chronicle.counters.GatheredLedger
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
	// Why the journal is not currently keeping the record — a write that failed,
	// or a file this build is too old to open. Null while all is well. The panel
	// reads it: a journal that stopped reaching disk still looks alive in memory,
	// and the loss would otherwise surface only at the next login.
	private volatile String journalWarning;

	// Session-scope tallies for the panel's strip + recent-drop icon row —
	// in-memory only, reset at the account boundary. Guarded by lock.
	private int sessionLoots;
	private long sessionLootValue;
	private int sessionUntaken;
	private long sessionUntakenValue;
	private final java.util.ArrayDeque<RecentDrop> recentDrops = new java.util.ArrayDeque<>();

	// A runaway guard, not a retention policy: every log, ore, fish and gem in the
	// game together is a few hundred ids, so a set past this is evidence something
	// other than a gather is minting entries — and the journal is written whole on
	// every flush, so an unbounded list would grow the file forever.
	private static final int GATHERED_CAP = 1024;
	// The gathered-item ledger's fast half: an in-memory mirror of the record's
	// "gathered_items", so the membership test a drop click asks costs no lock and
	// no scan. Concurrent because the resolver writes it from the client thread
	// while load() rebuilds it from the executor. The array on disk is the record;
	// this is only how it is read.
	private final java.util.Set<Integer> gatheredItems =
		java.util.concurrent.ConcurrentHashMap.newKeySet();

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
			catch (Exception e)   // noqa: a torn or hand-broken file starts fresh, aside
			{
				log.warn("local record unreadable: {}", f, e);
			}
			if (loaded == null)
			{
				// This file is the only copy of the account's history, and the very
				// next flush would write a blank skeleton straight over it. A torn
				// write usually loses only the tail, so keep the bytes: set them
				// aside under a dated sidecar the player can repair or hand back.
				setAside(f, "corrupt");
			}
		}
		long fileSchema = loaded != null ? asLong(loaded.get("schema")) : 0;
		if (fileSchema > SCHEMA)
		{
			// The stamp normalise() writes is only worth writing if it is also
			// read. A record from a later build carries shapes this one has never
			// heard of; mounting it would read them under today's assumptions,
			// stamp the version back down and rewrite the file on the next flush —
			// a client rollback would quietly eat the record. So mount nothing and
			// write nothing: the file stays exactly as the build that wrote it left
			// it, and the panel says why there is nothing to show. A LOWER stamp is
			// where a migration would run; there has not been one yet.
			log.warn("journal {} is schema {}; this build reads {}",
				f.getName(), fileSchema, SCHEMA);
			journalWarning = "This journal was written by a newer version of Chronicle. "
				+ "Update the plugin to open it — nothing on disk has been changed.";
			synchronized (lock)
			{
				// Empty rather than null: the panel's reads only test for a model,
				// and one going null underneath them would throw on the EDT. With
				// no currentRsn, flush() can never write this placeholder over the
				// record it stands in for.
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
			// Freeze the loaded lifetime counters; each setTrackers() recomputes the
			// live total as this-frozen-base + the current session, so a growing
			// session counter is never double-counted.
			trackersBase = deepCopy(loaded.getAsJsonObject("trackers"));
			currentRsn = rsn;
			// A record written by an earlier build can carry the same item twice
			// in one source's bag; heal it on the way in rather than making the
			// player re-import to be rid of it.
			int healed = dedupeSourceBags() + dedupeFeed();
			if (healed > 0)
			{
				log.debug("collapsed {} duplicate item entries", healed);
			}
			// Rebuilt, not merged: the mirror must describe THIS account only, or
			// an ore the previous character mined would credit this one's drops.
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
		// This account's record opened, so whatever was wrong before belongs to
		// the last one. A disk still refusing writes re-states itself on the very
		// next flush.
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
			// Not session scratch, but account scope: the next login may be a
			// different character, and this one's ore must not vouch for theirs.
			// load() reads it back from the record it was written to.
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
			// A new log slot counts the moment it drops — the stored log gains
			// the item and the finished tally, instead of waiting for the
			// player to next open their collection log in game.
			if ("COLLECTION".equals(type))
			{
				recordClogSlot(data);
			}
			// A completion closes the open task segment in the local journey.
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
			// Every read below is field-by-field guarded, the way the seeding path
			// is: a source entry written by an older era of the journal (or edited
			// by hand) can be missing its counters or not be an object at all, and
			// an exception thrown here on the client thread is swallowed by the
			// event bus — this source's drops would then stop accruing for good.
			JsonObject src = drops.has(source) && drops.get(source).isJsonObject()
				? drops.getAsJsonObject(source) : null;
			if (src == null)
			{
				src = new JsonObject();
				src.addProperty("kc", 0);
				src.addProperty("loots", 0);
				src.addProperty("value", 0);
				src.add("items", new JsonObject());
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

	/**
	 * Refresh the lifetime tracker counters from this session's live totals. Runs on
	 * the client thread. {@code session} is the trackers' from-zero session snapshot;
	 * lifetime = frozen-base + session (max for peak counters), so calling this
	 * repeatedly through a session never double-counts.
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
	 * Write the JSON record, or do nothing while no account is mounted. The lock
	 * is held only to serialise the model; the disk write happens outside it, so
	 * the client thread never waits on I/O.
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
			journalWarning = null;
		}
		catch (Exception e)   // noqa: the model is intact in memory; the next tick retries
		{
			// A full, read-only or locked directory drops every write while the
			// panel — served from memory — goes on looking live, and the loss only
			// shows at the next login, when the record rolls back to the last write
			// that landed. The journal is the system of record, so a journal that
			// is no longer being written has to say so where it is read.
			log.warn("local flush failed", e);
			journalWarning = "Could not write the journal to disk — check free space and "
				+ "permissions on " + dir.getAbsolutePath() + ".";
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
		java.util.List<Object[]> perItem = new java.util.ArrayList<>();
		for (JsonElement ie : items)
		{
			if (!ie.isJsonObject() || !ie.getAsJsonObject().has("id"))
			{
				continue;
			}
			JsonObject it = ie.getAsJsonObject();
			int id = it.get("id").getAsInt();
			int n = it.has("quantity") ? it.get("quantity").getAsInt() : 1;
			int canon = itemManager.canonicalize(id);
			long v = (long) itemManager.getItemPrice(canon) * n;
			qty += n;
			value += v;
			String name;
			try
			{
				name = itemManager.getItemComposition(canon).getName();
			}
			catch (Exception e)
			{
				name = "Item " + id;
			}
			perItem.add(new Object[]{name, (long) n, v, canon});
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
			// The item lens accumulates beside the source lens, name-keyed.
			JsonObject byItem = root.has("untaken_items") && root.get("untaken_items").isJsonObject()
				? root.getAsJsonObject("untaken_items") : new JsonObject();
			for (Object[] row : perItem)
			{
				JsonObject e = byItem.has((String) row[0]) && byItem.get((String) row[0]).isJsonObject()
					? byItem.getAsJsonObject((String) row[0]) : new JsonObject();
				e.addProperty("qty", (e.has("qty") ? e.get("qty").getAsLong() : 0) + (Long) row[1]);
				e.addProperty("value", (e.has("value") ? e.get("value").getAsLong() : 0) + (Long) row[2]);
				byItem.add((String) row[0], e);
			}
			root.add("untaken_items", byItem);
			// …and the PAIRING, so the lens can be drilled from either end: which
			// items a source was left holding, and which sources left an item
			// behind. Two flat aggregates can answer neither question.
			JsonObject pairs = root.has("untaken_pairs") && root.get("untaken_pairs").isJsonObject()
				? root.getAsJsonObject("untaken_pairs") : new JsonObject();
			JsonObject bag = pairs.has(source) && pairs.get(source).isJsonObject()
				? pairs.getAsJsonObject(source) : new JsonObject();
			for (Object[] row : perItem)
			{
				String nm = (String) row[0];
				JsonObject e = bag.has(nm) && bag.get(nm).isJsonObject()
					? bag.getAsJsonObject(nm) : new JsonObject();
				e.addProperty("id", (Integer) row[3]);
				e.addProperty("qty", (e.has("qty") ? asLong(e.get("qty")) : 0) + (Long) row[1]);
				e.addProperty("value", (e.has("value") ? asLong(e.get("value")) : 0) + (Long) row[2]);
				bag.add(nm, e);
			}
			pairs.add(source, bag);
			root.add("untaken_pairs", pairs);
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

	/**
	 * Remember an item id this account gathered. Client thread, once per resolved
	 * gathering action — so the already-known case, which is all but the first few
	 * of a career, takes the lock-free mirror and returns.
	 *
	 * <p>The record, not the session store, because the question it answers spans
	 * sessions: an ore mined last week and binned today is still a resource
	 * dropped, and a set that emptied at logout would call it bank junk.
	 */
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

	/** The lifetime base (pre-session) for one counter — 0 when unknown. */
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

	// A runaway guard far above any realistic task history, not a retention policy.
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
		// What the task was actually made of. A "blue dragons" assignment is
		// rarely one creature — brutals and a Vorkath detour count toward it too —
		// and the task's own loot is a different question from that monster's
		// lifetime bag, so both live here rather than being folded into `drops`.
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
	 * A task completion: close the open segment (opening one first if the whole
	 * task somehow went unwitnessed). The finished line's exact kill count trues
	 * up the loot spine — kills the drops never captured surface as
	 * {@code noLootKills}, the same reconciliation the site performs.
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
			// The streak line's lifetime total is authoritative when it is ahead;
			// otherwise this completion simply increments what we hold.
			sl.addProperty("completed", streak != null && streak > done ? streak : done + 1);
			root.addProperty("updated_at", nowSec());
		}
	}

	/**
	 * One-shot inheritance of the cloud's task history: totals floor, and the
	 * task list adopts wholesale only while the local list is still empty —
	 * merging two overlapping spines would double tasks, and by the time a
	 * local spine exists it is the authoritative one.
	 */
	void adoptSlayerJourney(chronicle.ChronicleApiClient.SlayerJourney j, String rsn)
	{
		if (!isReadyFor(rsn) || j == null)
		{
			return;
		}
		synchronized (lock)
		{
			JsonObject sl = slayerRoot();
			JsonArray tasks = sl.getAsJsonArray("tasks");
			if (tasks.size() == 0 && j.tasks != null)
			{
				// The cloud sends newest-first; the store keeps oldest-first.
				for (int i = j.tasks.size() - 1; i >= 0; i--)
				{
					chronicle.ChronicleApiClient.SlayerTask t = j.tasks.get(i);
					JsonObject seg = new JsonObject();
					seg.addProperty("task", t.task);
					seg.addProperty("kills", t.kills);
					seg.addProperty("assignment", t.assignment);
					seg.addProperty("value", t.totalValue);
					if (t.noLootKills > 0)
					{
						seg.addProperty("noLootKills", t.noLootKills);
					}
					seg.addProperty("ts", (long) t.ts);
					seg.addProperty("open", t.inProgress);
					tasks.add(seg);
				}
			}
			if (j.completedTasks > (sl.has("completed") ? asLong(sl.get("completed")) : 0))
			{
				sl.addProperty("completed", j.completedTasks);
			}
			if (j.totalXpEst > (sl.has("xp_est") ? asLong(sl.get("xp_est")) : 0))
			{
				sl.addProperty("xp_est", j.totalXpEst);
			}
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

	/** Lifetime gp per consumable counter key — accumulated at each bite/dose
	 *  (plus whatever an old cloud adoption already banked). */
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
	 * Merge another Chronicle journal into this account's record.
	 *
	 * <p>Every store merges as a FLOOR — per-key max, oldest-wins on first-seen,
	 * best-wins on a personal best — never a sum. An import is a record of the
	 * same account from somewhere else (a server export, another computer, a
	 * backup), so its history OVERLAPS this one; adding would double every
	 * shared kill. Flooring makes the operation idempotent: importing the same
	 * file twice, or importing an older export after a newer one, changes
	 * nothing. Runs off the client thread.
	 *
	 * @return a short human-readable summary of what came across.
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
						? drops.getAsJsonObject(e.getKey()) : new JsonObject();
					if (!drops.has(e.getKey()))
					{
						cur.addProperty("kc", 0);
						cur.addProperty("loots", 0);
						cur.addProperty("value", 0);
						cur.add("items", new JsonObject());
						drops.add(e.getKey(), cur);
						sources++;
					}
					floorNumber(cur, inc, "kc");
					floorNumber(cur, inc, "loots");
					floorNumber(cur, inc, "value");
					floorNumber(cur, inc, "last_seen");
					// The earliest sighting is the true one: an import can only ever
					// push "tracked since" further back.
					if (inc.has("first_seen") && !inc.get("first_seen").isJsonNull())
					{
						long incFirst = asLong(inc.get("first_seen"));
						long curFirst = cur.has("first_seen") ? asLong(cur.get("first_seen")) : 0;
						if (incFirst > 0 && (curFirst == 0 || incFirst < curFirst))
						{
							cur.addProperty("first_seen", incFirst);
						}
					}
					// A personal best is a minimum, not a maximum.
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
						// The bag is keyed by ITEM ID; an export that knows only
						// names is not. Matching on the name first is what stops
						// an import filing a second, parallel entry for something
						// already here — two lines for one herb, one holding what
						// this client saw and one holding the other record's total.
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
								// Nothing here by that name: file it under its id
								// when the export carried one, so the reader can
								// draw its sprite.
								key = incItem.has("id") && incItem.get("id").getAsInt() > 0
									? String.valueOf(incItem.get("id").getAsInt()) : ie.getKey();
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
			// The dated feed, deduplicated on (timestamp, type) — the same rule
			// that makes re-importing a no-op.
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
				// Re-sort: an import interleaves with what is already here, and the
				// panel reads the feed in stored order.
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
			// Collection log: the existing max-union, which is exactly right here.
			if (in.has("collection_log") && in.get("collection_log").isJsonObject())
			{
				JsonObject cl = root.has("collection_log") && root.get("collection_log").isJsonObject()
					? root.getAsJsonObject("collection_log") : new JsonObject();
				root.add("collection_log", mergeClog(cl, in.getAsJsonObject("collection_log")));
			}
			// The gathered-item ledger travels too: without it, ore mined on the
			// other machine is unrecognised here and binning it later reads as
			// clearing bank junk rather than throwing back what the world gave.
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
			// The slayer spine adopts only into an empty one: two overlapping
			// task lists cannot be reconciled segment by segment, and the local
			// one is the authoritative account of what this client watched.
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
				}
				else if (incSl.has("tasks") && incSl.get("tasks").isJsonArray())
				{
					// A spine already stands here, so the incoming one is the SAME
					// history seen from somewhere else — its segments must not be
					// appended. What it can carry that this one lacks is detail:
					// which monsters an assignment was made of and what it dropped,
					// which older records never kept. Matched on the task and its
					// moment rather than an exact timestamp: the two sides round
					// that instant differently, so equality would match nothing.
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
	 * Identity of a feed line: its kind, the SECOND it happened in, and what it
	 * was about.
	 *
	 * <p>Not the exact instant. One side of an import keeps milliseconds and the
	 * other keeps seconds as a float, so the same moment arrives a millisecond
	 * apart and an exact match lets the same log slot be written twice. Nor the
	 * second alone: a clue casket empties several slots inside one second, and
	 * those are genuinely different lines.
	 */
	private static String feedKey(JsonObject e)
	{
		long sec = (e.has("ts") ? asLong(e.get("ts")) : 0) / 1000L;
		String kind = e.has("type") && !e.get("type").isJsonNull()
			? e.get("type").getAsString() : "";
		return kind + "|" + sec + "|" + feedSubject(e);
	}

	/** What a feed line is ABOUT, for identity: the thing it names, or failing
	 *  that the whole payload. */
	private static String feedSubject(JsonObject e)
	{
		if (!e.has("data") || !e.get("data").isJsonObject())
		{
			return "";
		}
		JsonObject d = e.getAsJsonObject("data");
		for (String field : new String[]{"itemName", "petName", "task", "monster",
			"name", "quest", "diary", "achievement"})
		{
			if (d.has(field) && !d.get(field).isJsonNull())
			{
				return d.get(field).getAsString().toLowerCase(java.util.Locale.ROOT);
			}
		}
		// An imported line can carry nothing but a marker; the payload itself is
		// then the only identity there is, minus the marker.
		JsonObject bare = d.deepCopy();
		bare.remove("imported");
		bare.remove("type");
		return bare.toString();
	}

	/**
	 * Collapse feed lines that describe the same moment. The keys the entries
	 * were written under can differ by a millisecond across an import, so a
	 * record can hold one log slot twice; the fuller line survives.
	 * Callers hold {@code lock}. Returns how many were absorbed.
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
			// Keep whichever says more: a line naming its item beats a bare
			// "imported" marker for the same instant.
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

	/** Items a single source was left holding, richest first (the Left behind
	 *  lens drilled from the source end). */
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

	/** Sources that left a given item on the ground, most first (the same lens
	 *  drilled from the item end). */
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

	/** The task segment the panel's journey list calls {@code index} — the
	 *  journey is served newest-first, the store keeps them oldest-first. */
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

	/** Pets the journal has seen drop: name, source and the kc at the moment. */
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

	/** One pet as the journal remembers it. */
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
	 * Collapse item entries that name the same thing within one source.
	 *
	 * <p>The bag is keyed by item id; a record merged in from elsewhere may know
	 * only names, and an earlier build filed those alongside rather than into
	 * the entry already there — so a source could list one herb twice, each line
	 * holding a different partial count. Both describe the same history, so the
	 * survivor takes the HIGHER of the two (the same floor rule the rest of the
	 * merge uses) and keeps the id-bearing key, which is what draws the sprite.
	 * Callers hold {@code lock}. Returns how many entries were absorbed.
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
	 * Carry an account's journal across an in-game rename: move
	 * {@code <oldslug>.json} and {@code <oldslug>.history.jsonl} to the new
	 * name's slugs. Returns true when the journal file itself moved.
	 *
	 * <p>A file already filed under the new slug is set aside rather than left
	 * to be adopted. The carried journal is the one THIS account has been
	 * writing to; the resident one is a record nobody here has touched since,
	 * and freed names get taken — it may well belong to another account that
	 * once held this name. Loading that record would let this account
	 * accumulate on top of a stranger's lifetime and, once enrolled, push those
	 * totals upward under its own token, where the server's floor-merge makes
	 * them permanent. Nothing is deleted: the resident record keeps every byte
	 * under a dated sidecar name.
	 *
	 * <p>The record and its history spine move together or not at all, for the
	 * same reason — a spine filed under a slug whose journal belongs to someone
	 * else would splice two accounts' baselines into one calendar.
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
			// Nothing of this account's to carry, so nothing here earns the right
			// to disturb whatever is filed under the new name.
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
		File history = new File(dir, oldSlug + ".history.jsonl");
		File historyTarget = new File(dir, newSlug + ".history.jsonl");
		if (history.isFile() && (!historyTarget.exists() || setAside(historyTarget, "conflict"))
			&& !history.renameTo(historyTarget))
		{
			log.warn("history rename failed: {} -> {}", history, historyTarget);
		}
		return true;
	}

	/**
	 * Move a file out of the way under a dated sidecar name, keeping every byte.
	 * False when it could not be moved, so a caller can leave things as they are
	 * instead of acting on a clearance that never happened.
	 */
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
		catch (Exception e)   // noqa: best-effort; the caller decides what that costs
		{
			log.warn("could not set aside {}", f, e);
			return false;
		}
	}

	static String slug(String rsn)
	{
		// The slug is a file identity — the journal, its history spine and the
		// rename comparison are all filed under it — so it has to be a function of
		// the name alone. The default locale is not: under a Turkish one, lowering
		// 'I' yields a dotless 'ı' that the ASCII class below then strips, and the
		// same account would mount a blank record beside its real one.
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
			// The move below is atomic over the file's NAME, not over its contents:
			// without forcing the bytes down first, a power cut between the write
			// and the flush leaves the new name pointing at a zero-length or
			// half-written record — the very file the load path has to abandon.
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
