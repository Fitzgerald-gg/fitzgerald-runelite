/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileItem;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Captures RAW game events and fires them at {@code POST /api/events/<token>}.
 *
 * <p>This is the thin half of the thin-client/fat-server split: it sends only
 * raw fields (item ids + quantity, boss name, skill/level) and NEVER computes
 * GE prices, drop rarity, or item names — the server enriches all of that from
 * its nightly price/drop cache. So new game content (bosses, drops, prices)
 * needs no plugin release; only a RuneLite client-API change would.
 *
 * <p>Registered on the EventBus by {@link FitzgeraldPlugin} (register/unregister
 * in startUp/shutDown) rather than being a plugin itself. Backbone taps: LOOT
 * (npc + event loot) and LEVEL (real level-ups, warmed up + tick-batched to
 * avoid login false positives), with a kill-count chat tap that annotates loot.
 * DEATH / COLLECTION / PET / QUEST / COMBAT_ACHIEVEMENT / DIARY / SLAYER / CLUE
 * taps are the next increment (see plugin/REBUILD_V2.md).
 */
@Slf4j
@Singleton
public class FitzgeraldEventCapture
{
	static final String GROUP = FitzgeraldConfig.GROUP;   // "fitzgerald"
	static final String KEY_TOKEN = "token";

	/** Ordinary skills cap here; a reported level above it is not a real level-up. */
	private static final int MAX_LEVEL = 99;

	// Chat-driven detection. The literal message text below is what the game itself
	// prints — those strings are established from the OSRS Wiki, and being the game's
	// own words they are the same for anyone who reads them. The way each line is
	// taken apart — which spans are captured, their names, how the numbers are read —
	// is this plugin's own; group names are the fields the server ingest expects, and
	// every pattern is pinned by a positive/negative corpus in ChatPatternTest. All
	// matching runs AFTER Text.removeTags, so the red <col> wrapper is already gone.
	// The lines only appear with the usual chat settings on: kill-count spam-filter
	// off, collection-log "new addition" notification on, CA "repeat completion" off.

	// "Your Zulrah kill count is: 501.", plus the raid shape "Your completed Theatre
	// of Blood: Hard Mode count is: 40." One expression covers both: the optional
	// "completed " prefix, the optional tally word (which raids omit), and a lazy
	// name so a mode suffix's own colon stays part of the name. Ends in a full stop.
	private static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completed )?(?<subject>.+?)"
			+ "(?: (?:kill|chest|lap|harvest|success|completion))? count is: (?<tally>[\\d,]+)\\.$");

	private static final Pattern COLLECTION_ITEM = Pattern.compile(
		"^New item added to your collection log: (?<entry>.+)$");

	// Tier is a single word (Grandmaster among them); the challenge name is the rest
	// of the line, trimmed of an optional trailing stop.
	private static final Pattern COMBAT_TASK = Pattern.compile(
		"^Congratulations, you've completed an? (?<grade>\\w+) combat task: (?<challenge>.+?)\\.?$");
	private static final Pattern COMBAT_TASK_POINTS = Pattern.compile("\\s*\\(\\d+ points?\\)$");

	// "You have completed 87 hard Treasure Trails." The tier is spelled out as an
	// explicit set rather than a wildcard, and the closing full stop is required —
	// which is what separates the running tally from the singular reward-open line
	// "You have completed a hard Treasure Trail." (an "a", no stop-anchored count).
	private static final Pattern CLUE_COMPLETION = Pattern.compile(
		"^You have completed (?<tally>[\\d,]+) (?<rank>beginner|easy|medium|hard|elite|master)"
			+ " Treasure Trails?\\.$");

	// MESBOX. More text follows the area name, so this is a find, not a full match,
	// and the region span is lazy up to " area" so "Lumbridge & Draynor" stays whole.
	private static final Pattern DIARY_COMPLETION = Pattern.compile(
		"Congratulations! You have completed all of the (?<grade>\\w+) tasks in the (?<region>.+?) area");

	// Slayer prints the finished-task line and, separately, a running total. The
	// finished line's count and creature are pulled apart into their own spans here
	// rather than captured as one blob, so no second split step is needed. The
	// finished line is NOT $-anchored: modern OSRS appends " You gained N xp." after
	// the creature, and creature's [^.]+ already stops at the first period. On the
	// total line an optional qualifier word before "task" is the game's (e.g. a
	// "Wilderness" task, or a master name as in "…1 Mortimer task;…"); "at least" is
	// also optional, and a numeric total is required so "…enough tasks to unlock…"
	// is ignored.
	private static final Pattern SLAYER_FINISHED = Pattern.compile(
		"^You have completed your task! You killed (?<slain>[\\d,]+) (?<creature>[^.]+)\\.");
	private static final Pattern SLAYER_TOTAL = Pattern.compile(
		"^You've completed (?:at least )?(?<total>[\\d,]+) (?<qual>[A-Za-z]+ )?tasks?"
			+ "(?:;| and received)");

	// The two real pet lines are spelled out in full, so a near-miss the game also
	// prints — "being watched", "into your bank" — cannot slip through.
	private static final Pattern PET_RECEIVED = Pattern.compile(
		"^(?:You have a funny feeling like you're being followed"
			+ "|You feel something weird sneaking into your backpack"
			+ "|You have a funny feeling like you would have been followed\\.\\.\\.)\\.?$");

	// Carries no coin value and no trailing stop, unlike the sibling "Valuable drop:
	// …(N coins)" line and the "<player> received a drop: …." clan broadcast.
	private static final Pattern UNTRADEABLE_DROP = Pattern.compile("^Untradeable drop: (?<dropped>.+)$");

	// Boss timers. The game phrases them several ways — "Fight duration: 1:26.40
	// (new personal best)", "Duration: 36:04. Personal best: 31:12", "Subdued in
	// 6:23" — and raid lines lead with their own prose ("Congratulations - your
	// raid is complete! Duration: …"), so this is a find, not a full match. Longer
	// labels precede "Duration" in the alternation so it can't shadow them. The
	// timer line never names the boss; pairing with the kill is done by tick
	// adjacency against the next loot event.
	private static final Pattern KILL_DURATION = Pattern.compile(
		"(?:Fight duration|Challenge duration|Corrupted challenge duration"
			+ "|Completion time|Subdued in|Duration):? (?<time>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");
	private static final Pattern PERSONAL_BEST = Pattern.compile(
		"[Pp]ersonal best[:!]? (?<pb>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");
	private static final String NEW_PB_MARK = "(new personal best)";

	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final FitzgeraldConfig config;
	private final FitzgeraldApiClient api;
	private final DrawManager drawManager;
	private final LocalStore localStore;

	// Optional: provided by RuneLite's core Slayer plugin. Absent in a dev-mode
	// client (or if the user disables Slayer) — stays null and we skip the stamp.
	@com.google.inject.Inject(optional = true)
	private SlayerPluginService slayerService;

	private final Map<Skill, Integer> knownLevels = new EnumMap<>(Skill.class);
	private final Set<Skill> pendingLevels = new HashSet<>();
	private final Map<String, Integer> recentKc = new HashMap<>();
	// The most recent boss-timer chat line, held a few ticks to annotate the
	// kill's loot event — one-shot, so a later unrelated kill can't inherit it.
	private static final int KILL_TIME_PAIR_TICKS = 4;
	private double lastKillTimeSec = -1;
	private double lastPbTimeSec = -1;
	private boolean lastKillPb;
	private int lastKillTimeTick = -1;
	// The last NPC seen locking onto us, for death attribution when nothing is
	// still engaged at the death tick (poison after the attacker moved on).
	private static final int ATTACKER_MEMORY_TICKS = 50;
	private String lastAttackerName;
	private int lastAttackerTick = -1;

	// ── GIM group storage ─────────────────────────────────────────────────
	// Deposits/withdrawals are derived by diffing the shared bank's TEMP
	// container between the first server sync after the interface opens (the
	// baseline) and its state when the interface closes — the game only commits
	// the session's edits on close, so per-click tracking would count changes
	// the player backed out of.
	private boolean groupStorageOpen;
	private Map<Integer, Integer> groupStorageBaseline;
	private Map<Integer, Integer> groupStorageCurrent;
	// A kill's loot can arrive via BOTH NpcLootReceived and ServerNpcLoot in
	// current RuneLite (they are NOT mutually exclusive, and RuneLite applies no
	// suppression). NpcLootReceived is a client-side GROUND-ITEM SCAN — it guesses
	// which floor items belong to the corpse — so when several NPCs die on one tick
	// (bursting/barrage) it mis-attributes the pile, handing one kill's coins to
	// another and leaving that one with just its bones. ServerNpcLoot comes from the
	// game's own loottracker_add_loot script and is exact per kill. So we PREFER the
	// server event: each NpcLootReceived is held briefly and dropped if a
	// ServerNpcLoot covered the same NPC on the same kill's tick; the ground-scan is
	// emitted only as a FALLBACK for NPCs the loot script doesn't fire for.
	private static final int SERVER_LOOT_WINDOW_TICKS = 2;
	// (npcId, tick) pairs a ServerNpcLoot reported, so a held client copy can tell
	// whether its own kill was covered — keyed per-tick so repeated kills of the
	// same NPC don't cross-cover a genuinely uncovered one.
	private final Set<Long> serverLootKeys = new HashSet<>();
	private final List<PendingLoot> pendingClientLoot = new ArrayList<>();

	/** A NpcLootReceived built at kill time, awaiting the server-vs-client verdict. */
	private static final class PendingLoot
	{
		private final int npcId;
		private final int tick;
		private final JsonObject data;

		private PendingLoot(int npcId, int tick, JsonObject data)
		{
			this.npcId = npcId;
			this.tick = tick;
			this.data = data;
		}
	}

	// ── Untaken loot ───────────────────────────────────────────────────────
	// The player's own ground items from a kill, keyed by TileItem identity (the
	// same instance is redelivered on despawn). We only arm tracking for a few
	// ticks after a kill so manual drops aren't counted. On despawn we ask the
	// item's own scheduled despawn tick whether it timed out (left behind) or was
	// taken early; the timed-out ones are batched and forwarded for the server to
	// price into the untakenLoot* counters.
	private static final int SELF_OWNED = TileItem.OWNERSHIP_SELF;
	private static final int KILL_ARM_TICKS = 3;
	private final Map<TileItem, GroundLoot> groundLoot = new IdentityHashMap<>();
	// Self-owned items seen this-and-recent ticks, awaiting a kill to confirm them
	// as loot. RuneLite fires ItemSpawned BEFORE the kill's NpcLootReceived, so we
	// can't decide on the spawn — reconcileKillLoot() at GameTick matches them up.
	private final Map<TileItem, GroundLoot> pendingSelf = new IdentityHashMap<>();
	private final List<UntakenItem> untakenBatch = new ArrayList<>();
	private int lastKillTick = -1;
	// The kill that armed untaken tracking — stamped onto promoted ground loot
	// so the server's Uncollected ledger can say WHERE things were left behind.
	private String lastKillSource;

	private static final class UntakenItem
	{
		private final int id;
		private final int qty;
		private final String source;

		private UntakenItem(int id, int qty, String source)
		{
			this.id = id;
			this.qty = qty;
			this.source = source;
		}
	}

	private static final class GroundLoot
	{
		private final int id;
		private final int qty;
		private final int despawnTick;
		private final int visibleTick;
		private final int spawnTick;
		private final String source;   // the kill it belongs to; null until promoted

		private GroundLoot(int id, int qty, int despawnTick, int visibleTick, int spawnTick)
		{
			this(id, qty, despawnTick, visibleTick, spawnTick, null);
		}

		private GroundLoot(int id, int qty, int despawnTick, int visibleTick, int spawnTick, String source)
		{
			this.id = id;
			this.qty = qty;
			this.despawnTick = despawnTick;
			this.visibleTick = visibleTick;
			this.spawnTick = spawnTick;
			this.source = source;
		}

		private GroundLoot withSource(String src)
		{
			return new GroundLoot(id, qty, despawnTick, visibleTick, spawnTick, src);
		}
	}

	// A pet-drop message primes us; the pet NAME then arrives on a following
	// collection-log / untradeable line within a tick or two. Window it so a
	// later unrelated clog entry isn't mistaken for the pet.
	private int petPendingTicks = -1;
	// Slayer completion spans two lines ("You killed 150 X" + "You've completed
	// N tasks"); stash the task string until we emit.
	private String pendingSlayerTask;
	private String pendingSlayerMonster;
	private Integer pendingSlayerKills;
	// Ticks since a finished-task line armed a pending completion; -1 = idle. If the
	// "You've completed N tasks" streak line never finalises it within the window
	// (reworded/missed/wrong chat type), the finished line IS itself a real
	// completion, so we flush it rather than silently drop it. Disarmed the moment
	// the streak line processes, so this never double-emits.
	private int slayerPendingTicks = -1;
	// The task name seen at KILL time (via the loot stamp, when getTask() is still
	// valid). RuneLite clears getTask() on the completing tick, so at the streak
	// line the live service is empty — this is the authoritative fallback identity.
	private String lastSlayerTask;

	@Inject
	FitzgeraldEventCapture(Client client, ClientThread clientThread, ConfigManager configManager,
		FitzgeraldConfig config, FitzgeraldApiClient api, DrawManager drawManager, LocalStore localStore)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.config = config;
		this.api = api;
		this.drawManager = drawManager;
		this.localStore = localStore;
	}

	/** True once the core Slayer plugin's service is wired (via @PluginDependency).
	 *  If false, on-task drop tagging is inactive. */
	boolean hasSlayerService()
	{
		return slayerService != null;
	}

	/** Reset per-login state so we don't emit stale level-ups after a hop/relog. */
	void reset()
	{
		knownLevels.clear();
		pendingLevels.clear();
		recentKc.clear();
		lastKillTimeTick = -1;
		lastAttackerName = null;
		lastAttackerTick = -1;
		groupStorageOpen = false;
		groupStorageBaseline = null;
		groupStorageCurrent = null;
		petPendingTicks = -1;
		pendingSlayerTask = null;
		pendingSlayerMonster = null;
		pendingSlayerKills = null;
		slayerPendingTicks = -1;
		lastSlayerTask = null;
		// Ground-item refs belong to the scene we're leaving; drop them. The
		// untaken batch is pending data to send, so it is deliberately kept.
		groundLoot.clear();
		pendingSelf.clear();
		lastKillTick = -1;
		lastKillSource = null;
		// Loot reconciliation state is tick-scoped to the scene we're leaving.
		pendingClientLoot.clear();
		serverLootKeys.clear();
	}

	/** Forward any left-behind loot for the server to price into untakenLoot*.
	 *  Batched per SOURCE (the kill that armed tracking), so the Uncollected
	 *  ledger can say where things were left, not just what. */
	private void flushUntakenLoot()
	{
		if (untakenBatch.isEmpty())
		{
			return;
		}
		Map<String, JsonArray> bySource = new HashMap<>();
		for (UntakenItem it : untakenBatch)
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", it.id);
			o.addProperty("quantity", it.qty);
			bySource.computeIfAbsent(it.source == null ? "" : it.source,
				k -> new JsonArray()).add(o);
		}
		untakenBatch.clear();
		for (Map.Entry<String, JsonArray> e : bySource.entrySet())
		{
			JsonObject data = new JsonObject();
			if (!e.getKey().isEmpty())
			{
				data.addProperty("source", e.getKey());
			}
			data.add("items", e.getValue());
			emit("LOOT_UNTAKEN", data);
		}
	}

	/**
	 * Promote buffered self-owned spawns to tracked kill loot once we can see they
	 * landed within {@link #KILL_ARM_TICKS} of a kill — run at GameTick, after both
	 * the ItemSpawned and the (later-firing) NpcLootReceived have been processed.
	 * Spawns that never sit near a kill are manual drops and are dropped.
	 */
	private void reconcileKillLoot()
	{
		if (pendingSelf.isEmpty())
		{
			return;
		}
		int now = client.getTickCount();
		List<TileItem> done = new ArrayList<>();
		for (Map.Entry<TileItem, GroundLoot> e : pendingSelf.entrySet())
		{
			GroundLoot g = e.getValue();
			int sinceKill = g.spawnTick - lastKillTick;
			if (lastKillTick >= 0 && sinceKill >= 0 && sinceKill <= KILL_ARM_TICKS)
			{
				groundLoot.put(e.getKey(), g.withSource(lastKillSource));   // confirmed kill loot
				done.add(e.getKey());
			}
			else if (now - g.spawnTick > KILL_ARM_TICKS)
			{
				done.add(e.getKey());   // no kill nearby — a manual drop, discard
			}
		}
		for (TileItem t : done)
		{
			pendingSelf.remove(t);
		}
	}

	// ── LOOT ──────────────────────────────────────────────────────────────

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		NPC npc = event.getNpc();
		if (npc == null)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("source", npc.getName());
		data.addProperty("npcId", npc.getId());
		data.addProperty("category", "NPC");
		data.addProperty("lootSource", "client");   // ground-scan; superseded by server
		Integer kc = recentKc.get(cleanKey(npc.getName()));
		if (kc != null)
		{
			data.addProperty("killCount", kc);
		}
		data.add("items", itemsToJson(event.getItems()));
		stampSlayer(data);
		// Hold it: a ServerNpcLoot for this same kill (this tick) is authoritative
		// and will supersede this ground-scan copy. flushPendingClientLoot() emits
		// it a couple of ticks later only if no server event covered the kill.
		pendingClientLoot.add(new PendingLoot(npc.getId(), client.getTickCount(), data));
		lastKillTick = client.getTickCount();   // arm untaken-loot tracking
		lastKillSource = npc.getName();
	}

	/**
	 * Server-authoritative NPC loot from the game's {@code loottracker_add_loot}
	 * script. This is the exact per-kill drop, so it is the preferred source: it
	 * emits immediately and marks the kill's (npcId, tick) so the matching
	 * {@link NpcLootReceived} ground-scan copy is dropped in
	 * {@link #flushPendingClientLoot()}. For newer content (e.g. the Mad Angel) it
	 * is the ONLY loot event; for traditional monsters both fire and this one wins.
	 * Reads the NPC from the composition (the actor has usually despawned by now).
	 */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		NPCComposition comp = event.getComposition();
		if (comp == null)
		{
			return;
		}
		serverLootKeys.add(slKey(comp.getId(), client.getTickCount()));
		JsonObject data = new JsonObject();
		data.addProperty("source", comp.getName());
		data.addProperty("npcId", comp.getId());
		data.addProperty("category", "NPC");
		data.addProperty("lootSource", "server");
		Integer kc = recentKc.get(cleanKey(comp.getName()));
		if (kc != null)
		{
			data.addProperty("killCount", kc);
		}
		attachKillTime(data);
		data.add("items", itemsToJson(event.getItems()));
		stampSlayer(data);
		emit("LOOT", data);
		lastKillTick = client.getTickCount();   // arm untaken-loot tracking
		lastKillSource = comp.getName();
	}

	/**
	 * Emit held ground-scan loot once its kill is old enough that the authoritative
	 * server event (if any) has landed. A kill the loot script covered has its
	 * ground-scan copy dropped here — that copy mis-attributes items across an AoE
	 * stack, and dropping it is what stops one physical kill being recorded twice.
	 */
	private void flushPendingClientLoot()
	{
		if (pendingClientLoot.isEmpty())
		{
			return;
		}
		int now = client.getTickCount();
		List<PendingLoot> survivors = new ArrayList<>();
		for (PendingLoot pl : pendingClientLoot)
		{
			// A relog rewinds getTickCount(); if now < pl.tick, decide immediately
			// rather than holding forever.
			if (now >= pl.tick && now - pl.tick < SERVER_LOOT_WINDOW_TICKS)
			{
				survivors.add(pl);   // window still open — give the server event time
				continue;
			}
			if (!serverCovered(pl.npcId, pl.tick))
			{
				// No loot-script event for this NPC — the ground-scan is all we have.
				attachKillTime(pl.data);
				emit("LOOT", pl.data);
			}
			// else: ServerNpcLoot already reported this kill exactly — drop the copy.
		}
		pendingClientLoot.clear();
		pendingClientLoot.addAll(survivors);
		serverLootKeys.removeIf(k -> now - (int) (k & 0xFFFFFFFFL) > SERVER_LOOT_WINDOW_TICKS + 2);
	}

	/** Did a ServerNpcLoot report this NPC on the kill's tick (or a tick or two after)? */
	private boolean serverCovered(int npcId, int killTick)
	{
		return serverCoveredIn(serverLootKeys, npcId, killTick, SERVER_LOOT_WINDOW_TICKS);
	}

	/**
	 * Pure decision: was (npcId, killTick) covered by a server-loot key within the
	 * forward window? Matches on the kill's OWN tick range, so a later kill of the
	 * same NPC never retroactively covers an earlier, genuinely uncovered one.
	 */
	static boolean serverCoveredIn(Set<Long> keys, int npcId, int killTick, int window)
	{
		for (int t = killTick; t <= killTick + window; t++)
		{
			if (keys.contains(slKey(npcId, t)))
			{
				return true;
			}
		}
		return false;
	}

	static long slKey(int npcId, int tick)
	{
		return ((long) npcId << 32) | (tick & 0xFFFFFFFFL);
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		// Only the local player's own kill loot: OWNERSHIP_SELF, dropped within a
		// few ticks of a kill (so manual drops and other players' loot are ignored).
		TileItem it = event.getItem();
		if (it == null || it.getOwnership() != SELF_OWNED)
		{
			return;
		}
		int now = client.getTickCount();
		// Buffer only — the kill's NpcLootReceived fires AFTER this, so we can't yet
		// tell kill loot from a manual drop. reconcileKillLoot() decides at GameTick.
		pendingSelf.put(it, new GroundLoot(it.getId(), it.getQuantity(),
			it.getDespawnTime(), it.getVisibleTime(), now));
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		pendingSelf.remove(event.getItem());   // may despawn before reconcile — tidy up
		GroundLoot g = groundLoot.remove(event.getItem());
		if (g == null)
		{
			return;
		}
		int now = client.getTickCount();
		// Reaching the scheduled despawn tick means it timed out on the ground —
		// left behind. An earlier despawn is a pickup (yours while still private,
		// or someone else's after it went public); either way, not left behind.
		boolean left = g.despawnTick > 0 && now >= g.despawnTick - 1;
		if (left)
		{
			untakenBatch.add(new UntakenItem(g.id, g.qty, g.source));
		}
	}

	/**
	 * Stamp an NPC-loot event with the slayer task active at the moment of the
	 * kill, so the server can tag on-task loot EXACTLY at ingest instead of
	 * guessing from a post-completion time window. Reads RuneLite's core Slayer
	 * plugin service; {@code getTask()} is empty when the plugin is off or there
	 * is no task, in which case nothing is attached. The server decides on-task
	 * from the task + npc id via its authoritative matcher.
	 */
	private void stampSlayer(JsonObject data)
	{
		if (slayerService == null)
		{
			return;   // core Slayer plugin unavailable — no stamp
		}
		try
		{
			String task = slayerService.getTask();
			if (task == null || task.isEmpty())
			{
				return;
			}
			data.addProperty("slayerTask", task);
			lastSlayerTask = task;   // authoritative identity for the completion streak line
			data.addProperty("slayerTaskRemaining", slayerService.getRemainingAmount());
			data.addProperty("slayerTaskInitial", slayerService.getInitialAmount());
			String loc = slayerService.getTaskLocation();
			if (loc != null && !loc.isEmpty())
			{
				data.addProperty("slayerTaskLocation", loc);
			}
		}
		catch (RuntimeException ignored)
		{
			// Slayer service unavailable — skip the stamp.
		}
	}

	/** The core Slayer plugin's current task name, or null if unavailable/none. */
	private String slayerTaskFromService()
	{
		if (slayerService == null)
		{
			return null;
		}
		try
		{
			String t = slayerService.getTask();
			return (t == null || t.isEmpty()) ? null : t;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// NPC loot arrives via onNpcLootReceived, so it would double-count here.
		// PLAYER loot is dropped outright: on a PK the record carries the victim's
		// display name and the contents of their inventory, and forwarding another
		// player's data to a third-party server is something this plugin does not do.
		if (event.getType() == LootRecordType.NPC || event.getType() == LootRecordType.PLAYER)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("source", event.getName());
		data.addProperty("category", event.getType() != null ? event.getType().name() : "EVENT");
		Integer kc = recentKc.get(cleanKey(event.getName()));
		if (kc != null)
		{
			data.addProperty("killCount", kc);
		}
		attachKillTime(data);
		data.add("items", itemsToJson(event.getItems()));
		emit("LOOT", data);
	}

	/**
	 * Annotate a kill's loot with the boss-timer chat seen moments before. The
	 * pairing is tick adjacency (the timer line never names the boss), and it is
	 * one-shot: a raid whose chest is opened minutes after its timer printed
	 * simply goes unannotated rather than mis-annotating a later kill.
	 */
	private void attachKillTime(JsonObject data)
	{
		if (lastKillTimeTick < 0 || client.getTickCount() - lastKillTimeTick > KILL_TIME_PAIR_TICKS)
		{
			return;
		}
		data.addProperty("killTime", lastKillTimeSec);
		data.addProperty("personalBest", lastKillPb);
		if (lastPbTimeSec >= 0)
		{
			data.addProperty("personalBestTime", lastPbTimeSec);
		}
		lastKillTimeTick = -1;
	}

	/** "1:26.40" / "36:04" / "1:01:53.40" → seconds. Digits guaranteed by the regex. */
	static double parseDuration(String text)
	{
		double sec = 0;
		for (String part : text.split(":"))
		{
			sec = sec * 60 + Double.parseDouble(part);
		}
		return sec;
	}

	// ── QUEST / GROUP STORAGE ─────────────────────────────────────────────

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.SHARED_BANK)
		{
			// GIM shared bank opening: the baseline is taken from the FIRST
			// server sync of the temp container (below), not here — the
			// container is not populated yet at widget load.
			groupStorageOpen = true;
			groupStorageBaseline = null;
			groupStorageCurrent = null;
			return;
		}
		if (event.getGroupId() != InterfaceID.QUESTSCROLL)
		{
			return;
		}
		emitQuestFromScroll();
	}

	/** Session edits are committed on close — that is when the diff is real. */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.SHARED_BANK && groupStorageOpen)
		{
			flushGroupStorage();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!groupStorageOpen || event.getContainerId() != InventoryID.INV_GROUP_TEMP)
		{
			return;
		}
		Map<Integer, Integer> counts = containerCounts(event.getItemContainer());
		if (groupStorageBaseline == null)
		{
			groupStorageBaseline = counts;   // the opening server sync
		}
		groupStorageCurrent = counts;
	}

	/** Diff baseline → final and report one event of deposits + withdrawals. */
	private void flushGroupStorage()
	{
		Map<Integer, Integer> base = groupStorageBaseline;
		Map<Integer, Integer> last = groupStorageCurrent;
		groupStorageOpen = false;
		groupStorageBaseline = null;
		groupStorageCurrent = null;
		if (base == null || last == null)
		{
			return;   // the container never synced — nothing was observed
		}
		JsonArray deposits = new JsonArray();
		JsonArray withdrawals = new JsonArray();
		Set<Integer> ids = new HashSet<>(base.keySet());
		ids.addAll(last.keySet());
		for (int id : ids)
		{
			int delta = last.getOrDefault(id, 0) - base.getOrDefault(id, 0);
			if (delta == 0)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("quantity", Math.abs(delta));
			(delta > 0 ? deposits : withdrawals).add(o);
		}
		if (deposits.size() == 0 && withdrawals.size() == 0)
		{
			return;   // opened, looked, closed
		}
		JsonObject data = new JsonObject();
		data.add("deposits", deposits);
		data.add("withdrawals", withdrawals);
		emit("GROUP_STORAGE", data);
	}

	/** Aggregate a container to id → total quantity, skipping empty slots. */
	private static Map<Integer, Integer> containerCounts(ItemContainer container)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		if (container == null)
		{
			return counts;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
			{
				counts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	private void emitQuestFromScroll()
	{
		// Title text populates a tick later; read it on the client thread.
		clientThread.invokeLater(() ->
		{
			Widget title = client.getWidget(InterfaceID.Questscroll.QUEST_TITLE);
			if (title == null)
			{
				return;
			}
			String text = title.getText();
			text = text == null ? "" : Text.removeTags(text).trim();
			if (text.isEmpty())
			{
				return;
			}
			JsonObject data = new JsonObject();
			data.addProperty("questName", text);
			emit("QUEST", data);
		});
	}

	// ── LEVEL ─────────────────────────────────────────────────────────────

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null)
		{
			return;
		}
		int level = event.getLevel();
		Integer prev = knownLevels.put(skill, level);
		// Logging in replays every skill's level as a StatChanged, which against an
		// empty map would read as 23 simultaneous level-ups. Rather than suppress
		// everything for a fixed warm-up — which is a guess that both hides real
		// early level-ups and breaks if login runs slow — require a previous reading
		// that could only have come from live play: one we have actually seen, and
		// one above zero, since the pre-population pass reports zero for a skill the
		// client has not filled in yet.
		if (prev != null && prev > 0 && level > prev && level <= MAX_LEVEL)
		{
			pendingLevels.add(skill);
		}
	}

	// ── DEATH ─────────────────────────────────────────────────────────────

	/** An NPC "interacts" with its combat target — remember the last one on us. */
	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!(event.getSource() instanceof NPC) || event.getTarget() == null
			|| event.getTarget() != client.getLocalPlayer())
		{
			return;
		}
		String name = ((NPC) event.getSource()).getName();
		if (name != null && !name.isEmpty())
		{
			lastAttackerName = name;
			lastAttackerTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Player lp = client.getLocalPlayer();
		if (lp == null || event.getActor() != lp)
		{
			return;   // only our own death
		}
		JsonObject data = new JsonObject();
		try
		{
			data.addProperty("regionId", lp.getWorldLocation().getRegionID());
		}
		catch (RuntimeException ignored)
		{
			// no world location — omit
		}
		String killer = findKillerNpc(lp);
		if (killer != null)
		{
			// "killerName" is the wire name the server's DEATH ingest already reads.
			data.addProperty("killerName", killer);
		}
		// Value / items-kept valuation is deferred: it needs inventory +
		// skull/prayer snapshots sent raw for the server to value. For now the
		// death itself is recorded; the screenshot policy keys on valueLost when
		// present. (See REBUILD_V2.md.)
		emit("DEATH", data);
	}

	/**
	 * Best-effort name of the NPC that killed us: preferably one still locked
	 * onto us at the death tick, else the last one seen turning on us within
	 * ~30s — which covers a killing blow from poison or a lingering hit after
	 * the attacker despawned or moved on. Null when neither applies (e.g. an
	 * environmental death long after combat).
	 */
	private String findKillerNpc(Player lp)
	{
		try
		{
			for (NPC npc : client.getTopLevelWorldView().npcs())
			{
				if (npc != null && npc.getInteracting() == lp && npc.getName() != null
					&& !npc.getName().isEmpty())
				{
					return npc.getName();
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// world view unavailable — fall through to the remembered attacker
		}
		if (lastAttackerName != null && lastAttackerTick >= 0
			&& client.getTickCount() - lastAttackerTick <= ATTACKER_MEMORY_TICKS)
		{
			return lastAttackerName;
		}
		return null;
	}

	// ── CHAT: kc / collection / clue / combat-achievement / slayer / pet /
	//         diary (all raw; server interprets) ────────────────────────────

	@Subscribe
	public void onChatMessage(ChatMessage message)
	{
		ChatMessageType t = message.getType();
		String msg = Text.removeTags(message.getMessage());

		// Diary completion is usually a MESBOX line, but the same congratulatory
		// line can also arrive as a plain GAMEMESSAGE — check both so it isn't
		// silently dropped when the game classifies it as a game message.
		if (t == ChatMessageType.MESBOX
			|| t == ChatMessageType.GAMEMESSAGE || t == ChatMessageType.SPAM)
		{
			Matcher d = DIARY_COMPLETION.matcher(msg);
			if (d.find())
			{
				JsonObject data = new JsonObject();
				data.addProperty("area", d.group("region").trim());
				data.addProperty("difficulty", d.group("grade").trim().toUpperCase());
				emit("DIARY", data);
				return;
			}
		}
		if (t == ChatMessageType.MESBOX)
		{
			return;   // MESBOX only ever carries the diary line handled above
		}

		if (t != ChatMessageType.GAMEMESSAGE && t != ChatMessageType.SPAM)
		{
			return;
		}

		// Kill count — annotates the next loot event for this source.
		Matcher kc = KILL_COUNT.matcher(msg);
		if (kc.find())
		{
			try
			{
				recentKc.put(cleanKey(kc.group("subject")),
					Integer.parseInt(kc.group("tally").replace(",", "")));
			}
			catch (NumberFormatException ignored)
			{
				// non-numeric — skip
			}
			return;
		}

		// Boss timer — annotates the next loot event, as the kill count does.
		Matcher dur = KILL_DURATION.matcher(msg);
		if (dur.find())
		{
			lastKillTimeSec = parseDuration(dur.group("time"));
			lastKillPb = msg.contains(NEW_PB_MARK);
			// "(new personal best)" means this kill IS the record; otherwise the
			// game may restate the standing record after the time.
			Matcher pb = PERSONAL_BEST.matcher(msg);
			lastPbTimeSec = lastKillPb ? lastKillTimeSec
				: (pb.find() ? parseDuration(pb.group("pb")) : -1);
			lastKillTimeTick = client.getTickCount();
			return;
		}

		// Collection log new item (also resolves a pending pet's name).
		Matcher col = COLLECTION_ITEM.matcher(msg);
		if (col.find())
		{
			String item = col.group("entry").trim();
			JsonObject data = new JsonObject();
			data.addProperty("itemName", item);
			emit("COLLECTION", data);
			if (petPendingTicks >= 0)
			{
				emitPet(item);
			}
			return;
		}

		// Untradeable drop can also carry the pet name after a pet prime.
		Matcher unt = UNTRADEABLE_DROP.matcher(msg);
		if (unt.find() && petPendingTicks >= 0)
		{
			emitPet(unt.group("dropped").trim());
			return;
		}

		// Combat achievement.
		Matcher ca = COMBAT_TASK.matcher(msg);
		if (ca.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("tier", ca.group("grade").trim().toUpperCase());
			data.addProperty("task", COMBAT_TASK_POINTS.matcher(ca.group("challenge").trim()).replaceAll(""));
			emit("COMBAT_ACHIEVEMENT", data);
			return;
		}

		// Clue casket completion (tier + cumulative count; items come from the
		// reward widget later — deferred).
		Matcher clue = CLUE_COMPLETION.matcher(msg);
		if (clue.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("clueType", clue.group("rank").trim().toUpperCase());
			try
			{
				data.addProperty("clueCount", Integer.parseInt(clue.group("tally").replace(",", "")));
			}
			catch (NumberFormatException ignored)
			{
				// leave count off
			}
			emit("CLUE", data);
			return;
		}

		// Slayer: task-kill line stashes the task; the completed-count line then
		// finalises. Either can arrive first within the reconciliation window.
		Matcher sk = SLAYER_FINISHED.matcher(msg);
		if (sk.find())
		{
			pendingSlayerMonster = sk.group("creature").trim();
			pendingSlayerTask = sk.group("slain").trim() + " " + pendingSlayerMonster;
			try
			{
				pendingSlayerKills = Integer.parseInt(sk.group("slain").replace(",", ""));
			}
			catch (NumberFormatException ignored)
			{
				pendingSlayerKills = null;
			}
			slayerPendingTicks = 0;   // arm the flush; the streak line normally disarms it
			return;
		}
		Matcher sd = SLAYER_TOTAL.matcher(msg);
		if (sd.find())
		{
			// The "You've completed N tasks" streak line always prints on completion,
			// so it is the reliable trigger. Identify the task from the finished-line
			// creature if we caught it, else the core Slayer plugin's active task —
			// so a missing/reworded finished line, or a streak-line-first order, no
			// longer drops the completion (the old code required the finished line).
			String task = pendingSlayerMonster;
			if (task == null || task.isEmpty())
			{
				task = slayerTaskFromService();   // usually empty: cleared this tick
			}
			if (task == null || task.isEmpty())
			{
				task = lastSlayerTask;   // captured at kill time, before the clear
			}
			if (task == null || task.isEmpty())
			{
				task = pendingSlayerTask;   // last resort — the "N creature" blob
			}
			if (task != null && !task.isEmpty())
			{
				JsonObject data = new JsonObject();
				data.addProperty("task", task);
				data.addProperty("monster", task);
				// The finished line's "You killed N <creature>" is the EXACT size of
				// the task just completed — the ground truth a finished task should
				// report, above the loot spine's counter estimate (which undershoots
				// when an opening kill went uncaptured at the task hand-off).
				if (pendingSlayerKills != null)
				{
					data.addProperty("killCount", pendingSlayerKills);
				}
				// The streak count is the lifetime task number only for the plain
				// "You've completed N tasks…" line. A qualifier ("N Mortimer task",
				// "N Wilderness tasks") is NOT the lifetime count, so skip it there
				// to avoid a misleading task-number badge.
				if (sd.group("qual") == null)
				{
					try
					{
						data.addProperty("count", Integer.parseInt(sd.group("total").replace(",", "")));
					}
					catch (NumberFormatException ignored)
					{
						// leave count off
					}
				}
				emit("SLAYER", data);
			}
			else
			{
				log.debug("slayer streak line but no task identity — dropped: '{}'", msg);
			}
			pendingSlayerTask = null;
			pendingSlayerMonster = null;
			pendingSlayerKills = null;
			slayerPendingTicks = -1;   // streak line handled it — disarm the flush
			lastSlayerTask = null;
			return;
		}

		// Pet drop prime — the name resolves on a following clog/untradeable line.
		if (PET_RECEIVED.matcher(msg).matches())
		{
			petPendingTicks = 0;
		}
	}

	private void emitPet(String petName)
	{
		petPendingTicks = -1;
		if (petName == null || petName.isEmpty())
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("petName", petName);
		emit("PET", data);
	}

	/**
	 * The finished-task line ("You have completed your task! You killed N X.") was
	 * captured, but the streak line never finalised the completion. That finished
	 * line IS a real completion, so emit it from what we have — the same task-identity
	 * fallbacks the streak-line path uses — then clear ALL pending state (including
	 * lastSlayerTask) so a late streak line finds nothing and can't re-emit.
	 */
	private void flushPendingSlayer()
	{
		String task = pendingSlayerMonster;
		if (task == null || task.isEmpty())
		{
			task = lastSlayerTask;
		}
		if (task == null || task.isEmpty())
		{
			task = pendingSlayerTask;
		}
		if (task != null && !task.isEmpty())
		{
			JsonObject data = new JsonObject();
			data.addProperty("task", task);
			data.addProperty("monster", task);
			if (pendingSlayerKills != null)
			{
				data.addProperty("killCount", pendingSlayerKills);
			}
			emit("SLAYER", data);   // no lifetime "count" — the finished line has none
		}
		pendingSlayerTask = null;
		pendingSlayerMonster = null;
		pendingSlayerKills = null;
		lastSlayerTask = null;
	}

	// ── tick / state ──────────────────────────────────────────────────────

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		reconcileKillLoot();
		flushPendingClientLoot();
		flushUntakenLoot();

		// Expire a pet prime that never got a name (3-tick window).
		if (petPendingTicks >= 0 && ++petPendingTicks > 3)
		{
			petPendingTicks = -1;
		}

		// A finished-task line whose streak line never finalised it: flush it after a
		// short window (the finished line is itself a real completion). Disarmed above
		// if the streak line already handled it, so this can't double-emit.
		if (slayerPendingTicks >= 0 && ++slayerPendingTicks > 4)
		{
			flushPendingSlayer();
			slayerPendingTicks = -1;
		}

		if (pendingLevels.isEmpty())
		{
			return;
		}
		for (Skill skill : pendingLevels)
		{
			JsonObject data = new JsonObject();
			data.addProperty("skill", skill.getName());
			data.addProperty("level", knownLevels.getOrDefault(skill, 1));
			emit("LEVEL", data);
		}
		pendingLevels.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOADING || state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
		{
			// Leaving the scene with our kill loot still on the ground means it was
			// left behind — bank it as untaken before the refs go stale. (These
			// items despawn early on unload, so the tick-based classifier would
			// otherwise misread them as pickups.)
			for (GroundLoot g : groundLoot.values())
			{
				untakenBatch.add(new UntakenItem(g.id, g.qty, g.source));
			}
			groundLoot.clear();
		}
		if (state == GameState.LOGGING_IN || state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
		{
			reset();
		}
	}

	// ── helpers ───────────────────────────────────────────────────────────

	private JsonArray itemsToJson(Collection<ItemStack> items)
	{
		JsonArray arr = new JsonArray();
		if (items != null)
		{
			for (ItemStack is : items)
			{
				if (is == null)
				{
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("id", is.getId());
				o.addProperty("quantity", is.getQuantity());
				arr.add(o);
			}
		}
		return arr;
	}

	/** Lowercased name with a trailing parenthetical stripped, for matching a
	 *  kill-count line to an NPC name (server re-cleans authoritatively). */
	private static String cleanKey(String name)
	{
		if (name == null)
		{
			return "";
		}
		return Text.removeTags(name).replaceAll("\\s*\\(.+\\)$", "").trim().toLowerCase();
	}

	private void emit(String type, JsonObject data)
	{
		if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player lp = client.getLocalPlayer();
		String name = lp != null ? lp.getName() : null;
		if (name == null || name.isEmpty())
		{
			return;
		}
		// Local mode: fold the same captured event into the on-disk record and stop.
		// Nothing reaches the network — and gating on the MODE (not just token
		// presence) means a leftover token from a prior cloud enrolment can't leak.
		if (config.syncMode() == SyncMode.LOCAL)
		{
			localStore.record(type, data, name);
			return;
		}
		String tokenRaw = configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN);
		if (tokenRaw == null || tokenRaw.trim().isEmpty())
		{
			return;   // not enrolled yet — nothing to push under
		}
		final String base = config.serverBaseUrl();
		final String token = tokenRaw.trim();
		final JsonObject body = new JsonObject();
		body.addProperty("playerName", name);
		// Stable account hash → lets the server adopt an in-game rename as the
		// same account automatically (sent as a string to avoid 64-bit JSON
		// number precision worries). Omitted when unavailable (logged out).
		long accountHash = client.getAccountHash();
		// -1 = "no account"; any other value (incl. negative, sign bit set) is real.
		if (accountHash != -1L)
		{
			body.addProperty("accountHash", String.valueOf(accountHash));
		}
		body.addProperty("type", type);
		body.addProperty("eventId", UUID.randomUUID().toString());
		body.add("data", data);

		if (config.captureScreenshots() && screenshotWorthy(type, data))
		{
			try
			{
				// Capture the next rendered frame, then send event + PNG together.
				drawManager.requestNextFrameListener(image ->
					api.postEvent(base, token, body, toPng(image)));
				return;
			}
			catch (RuntimeException e)
			{
				log.debug("screenshot request failed; sending metadata only", e);
			}
		}
		api.postEvent(base, token, body);
	}

	/** Client-side pre-filter so we don't screenshot every trivial event. The
	 *  server still enforces the authoritative per-event policy (and prunes
	 *  e.g. loot below its gp/rarity thresholds). */
	private static boolean screenshotWorthy(String type, JsonObject data)
	{
		// Narrow client-side pre-filter matching the server's keep-policy: only a
		// max-level milestone, a death, a pet, or a KC'd (boss/notable) drop is worth
		// a frame. The server still prunes loot below its gp floor — the plugin can't
		// price, so it captures KC'd loot as the candidate and lets the server decide.
		switch (type)
		{
			case "PET":
			case "DEATH":
				return true;
			case "LEVEL":
				return data.has("level") && data.get("level").getAsInt() >= 99;
			case "LOOT":
				// A boss/KC drop is always a candidate; so is any drop valuable
				// enough on its own. The plugin already knows GE prices, so a
				// slayer unique or an on-task boss kill (which carries no
				// killCount) still earns a frame. The server stays authoritative
				// and prunes anything below its own gp floor.
				return data.has("killCount") || lootValueWorthy(data);
			default:
				return false;   // collection / quest / diary / CA / clue → metadata only
		}
	}

	/** Client-side value floor for screenshotting a drop that carries no KC.
	 *  Matches the server's default `dink_drop_screenshot_min_gp`; the server
	 *  stays authoritative and prunes anything below its own configured floor. */
	private static final long SCREENSHOT_LOOT_MIN_GP = 1_000_000L;

	/** True when the drop's own GE value (Σ priceEach × quantity) clears the
	 *  floor — so a valuable non-boss drop still earns a frame even with no KC. */
	private static boolean lootValueWorthy(JsonObject data)
	{
		if (!data.has("items") || !data.get("items").isJsonArray())
		{
			return false;
		}
		JsonArray items = data.getAsJsonArray("items");
		long total = 0L;
		for (int i = 0; i < items.size(); i++)
		{
			if (!items.get(i).isJsonObject())
			{
				continue;
			}
			JsonObject it = items.get(i).getAsJsonObject();
			long price = it.has("priceEach") && !it.get("priceEach").isJsonNull()
				? it.get("priceEach").getAsLong() : 0L;
			long qty = it.has("quantity") && !it.get("quantity").isJsonNull()
				? it.get("quantity").getAsLong() : 0L;
			total += price * qty;
			if (total >= SCREENSHOT_LOOT_MIN_GP)
			{
				return true;
			}
		}
		return false;
	}

	private static byte[] toPng(Image image)
	{
		try
		{
			BufferedImage bi;
			if (image instanceof BufferedImage)
			{
				bi = (BufferedImage) image;
			}
			else
			{
				bi = new BufferedImage(image.getWidth(null), image.getHeight(null),
					BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics g = bi.getGraphics();
				g.drawImage(image, 0, 0, null);
				g.dispose();
			}
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(bi, "png", baos);
			return baos.toByteArray();
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
