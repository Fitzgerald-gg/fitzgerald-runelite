/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Turns game events into journal entries: LOOT, LEVEL, DEATH, COLLECTION, PET,
 * QUEST, COMBAT_ACHIEVEMENT, DIARY, SLAYER, CLUE, GROUP_STORAGE, LOOT_UNTAKEN.
 *
 * <p>Registered on the EventBus by {@link ChroniclePlugin} in startUp/shutDown
 * rather than being a plugin itself. Every tap ends at {@link #emit}: the on-disk
 * journal always gets the event, and a copy goes to the configured server only
 * when cloud sync is switched on.
 */
@Slf4j
@Singleton
public class ChronicleEventCapture
{
	// ordinary skills stop here; a reported level above it isn't a real level-up.
	private static final int MAX_LEVEL = 99;

	// Chat taps. Everything below matches AFTER Text.removeTags; no <col> wrappers.
	// The lines only print with the usual chat settings: kill-count spam filter off,
	// collection-log notification on, CA repeat completion off.

	// "Your Zulrah kill count is: 501." and the raid shape "Your completed Theatre
	// of Blood: Hard Mode count is: 40." One expression covers both: optional
	// "completed " prefix, optional tally word (raids omit it), lazy name so a mode
	// suffix's own colon stays in the name.
	static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completed )?(?<subject>.+?)"
			+ "(?: (?:kill|chest|lap|harvest|success|completion))? count is: (?<tally>[\\d,]+)\\.$");

	static final Pattern COLLECTION_ITEM = Pattern.compile(
		"^New item added to your collection log: (?<entry>.+)$");

	// the tier is one word; the challenge is the rest of the line, trailing stop optional.
	static final Pattern COMBAT_TASK = Pattern.compile(
		"^Congratulations, you've completed an? (?<grade>\\w+) combat task: (?<challenge>.+?)\\.?$");
	private static final Pattern COMBAT_TASK_POINTS = Pattern.compile("\\s*\\(\\d+ points?\\)$");

	// "You have completed 87 hard Treasure Trails." Explicit tier set, required closing
	// stop. That keeps out the singular reward-open line, "You have completed a hard
	// Treasure Trail."
	static final Pattern CLUE_COMPLETION = Pattern.compile(
		"^You have completed (?<tally>[\\d,]+) (?<rank>beginner|easy|medium|hard|elite|master)"
			+ " Treasure Trails?\\.$");

	// find(), not matches(): more text follows the area name. The region span is lazy
	// up to " area", keeping "Lumbridge & Draynor" whole.
	static final Pattern DIARY_COMPLETION = Pattern.compile(
		"Congratulations! You have completed all of the (?<grade>\\w+) tasks in the (?<region>.+?) area");

	// The finished line is NOT $-anchored: modern OSRS appends " You gained N xp."
	// after the creature, and [^.]+ already stops at the first period. The total line
	// requires a number, which keeps "…enough tasks to unlock…" out; the qualifier
	// before "task" is the game's own ("N Wilderness tasks", "…1 Mortimer task;…").
	static final Pattern SLAYER_FINISHED = Pattern.compile(
		"^You have completed your task! You killed (?<slain>[\\d,]+) (?<creature>[^.]+)\\.");
	static final Pattern SLAYER_TOTAL = Pattern.compile(
		"^You've completed (?:at least )?(?<total>[\\d,]+) (?<qual>[A-Za-z]+ )?tasks?"
			+ "(?:;| and received)");

	// spelled out in full so the near-misses the game also prints ("being watched",
	// "sneaking into your bank") can't slip through.
	static final Pattern PET_RECEIVED = Pattern.compile(
		"^(?:You have a funny feeling like you're being followed"
			+ "|You feel something weird sneaking into your backpack"
			+ "|You have a funny feeling like you would have been followed\\.\\.\\.)\\.?$");

	// No coin value, no trailing stop. That leaves out the sibling "Valuable drop:
	// …(N coins)" and the "<player> received a drop: …." clan broadcast.
	static final Pattern UNTRADEABLE_DROP = Pattern.compile("^Untradeable drop: (?<dropped>.+)$");

	// Boss timers, phrased several ways: "Fight duration: 1:26.40 (new personal
	// best)", "Duration: 36:04. Personal best: 31:12", "Subdued in 6:23". Raid lines
	// lead with their own prose ("Congratulations - your raid is complete! Duration:
	// …"), so this is a find(). Longer labels precede "Duration" in the alternation
	// so it can't shadow them, and the label is case-insensitive because the raids
	// bury theirs mid-sentence in lower case (ToA "…challenge completion time:
	// 25:33.60", ToB "…total completion time: …"). The timer line never names the
	// boss; pairing with the kill is tick adjacency against the next loot event.
	static final Pattern KILL_DURATION = Pattern.compile(
		"(?i:Fight duration|Challenge duration|Corrupted challenge duration"
			+ "|Completion time|Subdued in|Duration):? (?<time>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");
	static final Pattern PERSONAL_BEST = Pattern.compile(
		"[Pp]ersonal best[:!]? (?<pb>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");
	private static final String NEW_PB_MARK = "(new personal best)";

	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final ChronicleConfig config;
	private final ChronicleApiClient api;
	private final LocalStore localStore;

	// from RuneLite's core Slayer plugin. Stays null in a dev-mode client, or when the
	// user turns Slayer off; we skip the task stamp then.
	@com.google.inject.Inject(optional = true)
	private SlayerPluginService slayerService;

	private final Map<Skill, Integer> knownLevels = new EnumMap<>(Skill.class);
	private final Set<Skill> pendingLevels = new HashSet<>();
	private final Map<String, Integer> recentKc = new HashMap<>();
	// the most recent boss-timer chat line, held a few ticks to annotate the kill's
	// loot event. One-shot: a later unrelated kill can't inherit it.
	private static final int KILL_TIME_PAIR_TICKS = 4;
	private double lastKillTimeSec = -1;
	private double lastPbTimeSec = -1;
	private boolean lastKillPb;
	private int lastKillTimeTick = -1;
	// the last NPC seen locking onto us, for death attribution when nothing is still
	// engaged at the death tick (poison after the attacker moved on).
	private static final int ATTACKER_MEMORY_TICKS = 50;
	private String lastAttackerName;
	private int lastAttackerTick = -1;

	// ── GIM group storage ─────────────────────────────────────────────────
	// Deposits/withdrawals come from diffing the shared bank's TEMP container
	// between the first server sync after the interface opens and its state when the
	// interface closes. The game only commits the session's edits on close, so
	// per-click tracking would count changes the player backed out of.
	private boolean groupStorageOpen;
	private Map<Integer, Integer> groupStorageBaseline;
	private Map<Integer, Integer> groupStorageCurrent;

	// ── Loot reconciliation ───────────────────────────────────────────────
	// Both NpcLootReceived and ServerNpcLoot can fire for one kill. The client-side
	// ground scan mis-attributes the pile when several NPCs die on a tick; the game's
	// own loot script is right every time. Hold each client copy briefly and drop it
	// if a server event covered the same (npcId, tick).
	private static final int SERVER_LOOT_WINDOW_TICKS = 2;
	// (npcId, tick) pairs a ServerNpcLoot reported. Keyed per tick: repeated kills of
	// the same NPC mustn't cross-cover a genuinely uncovered one.
	private final Set<Long> serverLootKeys = new HashSet<>();
	private final List<PendingLoot> pendingClientLoot = new ArrayList<>();

	// an NpcLootReceived built at kill time, waiting on the server-vs-client verdict.
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
	// The player's own ground items from a kill, keyed by TileItem identity (the same
	// instance is redelivered on despawn). Tracking is armed for only a few ticks
	// after a kill so manual drops aren't counted. On despawn we ask the item's own
	// scheduled despawn tick whether it timed out (left behind) or was taken early;
	// the timed-out ones are batched into a LOOT_UNTAKEN event.
	private static final int SELF_OWNED = TileItem.OWNERSHIP_SELF;
	private static final int KILL_ARM_TICKS = 3;
	private final Map<TileItem, GroundLoot> groundLoot = new IdentityHashMap<>();
	// Self-owned items seen on recent ticks, awaiting a kill to confirm them as loot.
	// RuneLite fires ItemSpawned BEFORE the kill's NpcLootReceived, so the spawn
	// can't decide; reconcileKillLoot() matches them up at GameTick.
	private final Map<TileItem, GroundLoot> pendingSelf = new IdentityHashMap<>();
	private final List<UntakenItem> untakenBatch = new ArrayList<>();
	// The kills of the last few ticks, each carrying the source name stamped onto the
	// loot it produced so the Uncollected ledger can say where things were left.
	private final List<RecentKill> recentKills = new ArrayList<>();

	// a kill of ours, remembered long enough for its ground items to find it.
	private static final class RecentKill
	{
		private final int tick;
		private final String source;

		private RecentKill(int tick, String source)
		{
			this.tick = tick;
			this.source = source;
		}
	}

	private static final class UntakenItem
	{
		private final int id;
		private final int qty;
		private final String source;
		// the account that earned it. The batch is gathered before a logout and sent
		// after the next login, which may belong to somebody else.
		private final String owner;

		private UntakenItem(int id, int qty, String source, String owner)
		{
			this.id = id;
			this.qty = qty;
			this.source = source;
			this.owner = owner;
		}
	}

	private static final class GroundLoot
	{
		private final int id;
		private final int qty;
		private final int despawnTick;
		private final int spawnTick;
		private final boolean group;   // group-ironman ownership rather than our own
		private final String owner;    // the account it spawned for
		private final String source;   // the kill it belongs to; null until promoted

		private GroundLoot(int id, int qty, int despawnTick, int spawnTick,
			boolean group, String owner)
		{
			this(id, qty, despawnTick, spawnTick, group, owner, null);
		}

		private GroundLoot(int id, int qty, int despawnTick, int spawnTick,
			boolean group, String owner, String source)
		{
			this.id = id;
			this.qty = qty;
			this.despawnTick = despawnTick;
			this.spawnTick = spawnTick;
			this.group = group;
			this.owner = owner;
			this.source = source;
		}

		private GroundLoot withSource(String src)
		{
			return new GroundLoot(id, qty, despawnTick, spawnTick, group, owner, src);
		}
	}

	// A pet-drop message primes us; the pet NAME arrives on a following
	// collection-log / untradeable line a tick or two later. Windowed so a later
	// unrelated clog entry isn't mistaken for the pet.
	private int petPendingTicks = -1;
	// Slayer completion spans two lines ("You killed 150 X" + "You've completed N
	// tasks"); stash the task string until we emit.
	private String pendingSlayerTask;
	private String pendingSlayerMonster;
	private Integer pendingSlayerKills;
	// Ticks since a finished-task line armed a pending completion; -1 = idle. If the
	// streak line never finalises it within the window (reworded, missed, wrong chat
	// type), the finished line is itself a real completion and gets flushed. Disarmed
	// the moment the streak line processes; no double-emit.
	private int slayerPendingTicks = -1;
	// The task name seen at KILL time, via the loot stamp. RuneLite clears getTask()
	// on the completing tick, so by the streak line the live service is empty.
	private String lastSlayerTask;

	@Inject
	ChronicleEventCapture(Client client, ClientThread clientThread, ConfigManager configManager,
		ChronicleConfig config, ChronicleApiClient api, LocalStore localStore)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.config = config;
		this.api = api;
		this.localStore = localStore;
	}

	// false means the core Slayer plugin's service never bound, so on-task drop
	// tagging is inactive.
	boolean hasSlayerService()
	{
		return slayerService != null;
	}

	// clear per-login state so nothing from the last session leaks into this one.
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
		// Ground-item refs belong to the scene we're leaving, so drop them. The
		// untaken batch is kept: each item carries the account it was earned on, and
		// flushUntakenLoot only sends the ones belonging to whoever logs in next.
		groundLoot.clear();
		pendingSelf.clear();
		recentKills.clear();
		// loot reconciliation is tick-scoped to the scene we're leaving.
		pendingClientLoot.clear();
		serverLootKeys.clear();
	}

	// Emit left-behind loot as LOOT_UNTAKEN, one event per SOURCE so the Uncollected
	// ledger can say where things were left. Items swept up at a logout only go out on
	// the next login's ticks, and that login can belong to a different player, so
	// anything stamped with another account is discarded. It also waits for the
	// journal to be mounted: otherwise LocalStore.record() drops the batch while the
	// cloud push still takes it, and the two ledgers disagree about the same kill.
	private void flushUntakenLoot()
	{
		if (untakenBatch.isEmpty())
		{
			return;
		}
		String owner = localName();
		if (owner == null || !localStore.isReadyFor(owner))
		{
			return;   // nobody to attribute it to yet; the batch keeps
		}
		Map<String, JsonArray> bySource = new HashMap<>();
		for (UntakenItem it : untakenBatch)
		{
			if (!owner.equals(it.owner))
			{
				continue;   // left behind by the account before this one
			}
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

	// Promote buffered self-owned spawns to tracked kill loot when they landed within
	// KILL_ARM_TICKS of a kill. Runs at GameTick, once both the ItemSpawned and the
	// later-firing NpcLootReceived are in. A spawn that never sits near a kill is a
	// manual drop and is discarded.
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
			RecentKill kill = killFor(g);
			if (kill != null)
			{
				groundLoot.put(e.getKey(), g.withSource(kill.source));   // confirmed kill loot
				done.add(e.getKey());
			}
			else if (now - g.spawnTick > KILL_ARM_TICKS)
			{
				done.add(e.getKey());   // no kill nearby: a manual drop
			}
		}
		for (TileItem t : done)
		{
			pendingSelf.remove(t);
		}
	}

	// The kill a buffered spawn belongs to: the latest one it could have followed, so a
	// burst of kills files each pile under the monster that dropped it. A GROUP-owned
	// spawn has to match a kill on its OWN tick, because a team-mate's drops arrive
	// group-owned too and the wider window would book theirs into our ledger.
	private RecentKill killFor(GroundLoot g)
	{
		int window = g.group ? 0 : KILL_ARM_TICKS;
		RecentKill best = null;
		for (RecentKill k : recentKills)
		{
			int since = g.spawnTick - k.tick;
			if (since < 0 || since > window)
			{
				continue;
			}
			if (best == null || k.tick > best.tick)
			{
				best = k;
			}
		}
		return best;
	}

	// arm untaken tracking: ground items spawning around now are this kill's.
	private void armKill(String source)
	{
		int now = client.getTickCount();
		recentKills.removeIf(k -> now - k.tick > KILL_ARM_TICKS);
		recentKills.add(new RecentKill(now, source));
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
		// Hold it: a ServerNpcLoot for this same kill supersedes this ground-scan copy.
		// flushPendingClientLoot() emits it a couple of ticks later only if no server
		// event covered the kill.
		pendingClientLoot.add(new PendingLoot(npc.getId(), client.getTickCount(), data));
		armKill(npc.getName());
	}

	// The exact per-kill drop from the game's loottracker_add_loot script. Emits
	// straight away and marks the kill's (npcId, tick) so flushPendingClientLoot()
	// drops the matching ground-scan copy. For newer content (the Mad Angel) this is
	// the only loot event. The NPC comes from the composition because the actor has
	// usually despawned by now.
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
		armKill(comp.getName());
	}

	// Emit held ground-scan loot once its kill is old enough that the server event, if
	// there is one, has landed. A kill the loot script covered has its ground-scan copy
	// dropped here, which keeps one physical kill from being recorded twice.
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
				survivors.add(pl);   // window still open; give the server event time
				continue;
			}
			if (!serverCovered(pl.npcId, pl.tick))
			{
				// no loot-script event for this NPC; the ground scan is all we have.
				attachKillTime(pl.data);
				emit("LOOT", pl.data);
			}
			// else ServerNpcLoot already reported this kill; drop the copy.
		}
		pendingClientLoot.clear();
		pendingClientLoot.addAll(survivors);
	}

	// Drop server-loot keys past the window in which a held client copy could still
	// need them. Runs every tick, not off the flush above: content the ground scan
	// never fires for leaves pendingClientLoot empty, and a prune reached only through
	// it would hoard every kill of the session.
	private void expireServerLootKeys()
	{
		int now = client.getTickCount();
		serverLootKeys.removeIf(k -> now - (int) (k & 0xFFFFFFFFL) > SERVER_LOOT_WINDOW_TICKS + 2);
	}

	private boolean serverCovered(int npcId, int killTick)
	{
		return serverCoveredIn(serverLootKeys, npcId, killTick, SERVER_LOOT_WINDOW_TICKS);
	}

	// Looks forward from the kill's own tick only, so a later kill of the same NPC
	// can't retroactively cover an earlier uncovered one.
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
		// Only the local player's own kill loot, dropped within a few ticks of a kill,
		// so manual drops and other players' loot are ignored. GROUP ownership counts
		// alongside SELF because a group ironman's own drops arrive GROUP-stamped
		// (upstream GroundItemsPlugin accepts both as well); killFor() then holds a
		// group-owned spawn to the tick of a kill of ours so a team-mate's drops stay
		// out of the ledger.
		TileItem it = event.getItem();
		if (it == null)
		{
			return;
		}
		boolean group = it.getOwnership() == TileItem.OWNERSHIP_GROUP;
		if (!group && it.getOwnership() != SELF_OWNED)
		{
			return;
		}
		int now = client.getTickCount();
		// Buffer only: the kill's NpcLootReceived fires AFTER this, so kill loot and a
		// manual drop are still indistinguishable. reconcileKillLoot() decides at
		// GameTick. The account is read here while we're certainly logged in as it,
		// since left-behind items go out after the next login, which may be another's.
		pendingSelf.put(it, new GroundLoot(it.getId(), it.getQuantity(),
			it.getDespawnTime(), now, group, localName()));
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		pendingSelf.remove(event.getItem());   // may despawn before reconcile runs
		GroundLoot g = groundLoot.remove(event.getItem());
		if (g == null)
		{
			return;
		}
		int now = client.getTickCount();
		// Reaching the scheduled despawn tick means it timed out on the ground, so it
		// was left behind. An earlier despawn is a pickup: yours while it was still
		// private, or someone else's once it went public.
		boolean left = g.despawnTick > 0 && now >= g.despawnTick - 1;
		if (left)
		{
			untakenBatch.add(new UntakenItem(g.id, g.qty, g.source, g.owner));
		}
	}

	// the live slayer task for the panel's Home card; null when there's none.
	SlayerView slayerView()
	{
		if (slayerService == null)
		{
			return null;
		}
		try
		{
			String task = slayerService.getTask();
			if (task == null || task.isEmpty())
			{
				return null;
			}
			return new SlayerView(task, slayerService.getRemainingAmount(),
				slayerService.getInitialAmount());
		}
		catch (RuntimeException ignored)
		{
			return null;   // no service, no task
		}
	}

	// immutable slayer-task snapshot for the panel.
	static final class SlayerView
	{
		final String task;
		final int remaining;
		final int initial;

		SlayerView(String task, int remaining, int initial)
		{
			this.task = task;
			this.remaining = remaining;
			this.initial = initial;
		}
	}

	// Tag an NPC-loot event with the slayer task live at the moment of the kill, so
	// on-task loot is settled at capture instead of guessed from a time window later.
	private void stampSlayer(JsonObject data)
	{
		if (slayerService == null)
		{
			return;
		}
		try
		{
			String task = slayerService.getTask();
			if (task == null || task.isEmpty())
			{
				return;
			}
			data.addProperty("slayerTask", task);
			lastSlayerTask = task;   // the identity the completion streak line falls back to
			slayerSeenThisSession = true;
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
			// no service, no stamp
		}
	}

	// True once an on-task kill was stamped this session. Home's slayer card gates on
	// it so non-slayers never see it. Cleared at the account boundary.
	private volatile boolean slayerSeenThisSession;

	boolean slayerSeenThisSession()
	{
		return slayerSeenThisSession;
	}

	void resetSessionFlags()
	{
		slayerSeenThisSession = false;
	}

	// null when the core Slayer plugin is off or there's no task.
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
		// NPC loot arrives via onNpcLootReceived and would double-count here. PLAYER
		// loot is dropped outright: on a PK the record carries the victim's display
		// name and their inventory, and this plugin only ever records its own account.
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

	// Annotate a kill's loot with the boss-timer chat seen moments before. Pairing is
	// tick adjacency, since the timer line never names the boss, and it's one-shot: a
	// raid chest opened long after its timer printed goes unannotated.
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

	// "1:26.40" / "36:04" / "1:01:53.40" → seconds. The regex guarantees the digits.
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
			// The baseline comes from the first server sync of the temp container
			// (onItemContainerChanged); it isn't populated yet at widget load.
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

	// the game commits the session's edits on close, so that's when the diff is real.
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

	// diff baseline → final, one event carrying deposits and withdrawals.
	private void flushGroupStorage()
	{
		Map<Integer, Integer> base = groupStorageBaseline;
		Map<Integer, Integer> last = groupStorageCurrent;
		groupStorageOpen = false;
		groupStorageBaseline = null;
		groupStorageCurrent = null;
		if (base == null || last == null)
		{
			return;   // the container never synced; nothing observed
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

	// id → total quantity, stacks merged, empty slots skipped.
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
		// the title text populates a tick later; read it on the client thread.
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
		// Logging in replays every skill as a StatChanged, which against an empty map
		// reads as 23 simultaneous level-ups. So a level-up needs a previous reading
		// that could only have come from live play: one we've actually seen, and one
		// above zero, since the login pass reports zero for a skill the client hasn't
		// filled in yet.
		if (prev != null && prev > 0 && level > prev && level <= MAX_LEVEL)
		{
			pendingLevels.add(skill);
		}
	}

	// ── DEATH ─────────────────────────────────────────────────────────────

	// an NPC "interacts" with its combat target, so remember the last one on us.
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
			// no world location, leave it off
		}
		String killer = findKillerNpc(lp);
		if (killer != null)
		{
			data.addProperty("killerName", killer);
		}
		emit("DEATH", data);
	}

	// Best effort: an NPC still locked onto us at the death tick, else the last one
	// seen turning on us within ~30s, which covers poison and a lingering hit after
	// the attacker moved on. Null for an environmental death long after combat.
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
			// no world view; fall through to the remembered attacker
		}
		if (lastAttackerName != null && lastAttackerTick >= 0
			&& client.getTickCount() - lastAttackerTick <= ATTACKER_MEMORY_TICKS)
		{
			return lastAttackerName;
		}
		return null;
	}

	// ── CHAT: kc / collection / clue / combat-achievement / slayer / pet /
	//         diary ────────────────────────────────────────────────────────

	@Subscribe
	public void onChatMessage(ChatMessage message)
	{
		ChatMessageType t = message.getType();
		// Every line the game prints reaches here, public and clan chat included, and a
		// crowded world delivers hundreds a minute. Settle the type before paying for
		// the tag strip.
		if (t != ChatMessageType.MESBOX && t != ChatMessageType.GAMEMESSAGE
			&& t != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = Text.removeTags(message.getMessage());

		// Diary completion is usually a MESBOX line, but the same congratulatory line
		// can arrive as a plain GAMEMESSAGE, so check both.
		Matcher d = DIARY_COMPLETION.matcher(msg);
		if (d.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("area", d.group("region").trim());
			data.addProperty("difficulty", d.group("grade").trim().toUpperCase());
			emit("DIARY", data);
			return;
		}
		if (t == ChatMessageType.MESBOX)
		{
			return;   // MESBOX only ever carries the diary line handled above
		}

		// Kill count: annotates the next loot event for this source.
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
				// non-numeric, skip it
			}
			return;
		}

		// Boss timer: annotates the next loot event, as the kill count does.
		Matcher dur = KILL_DURATION.matcher(msg);
		if (dur.find())
		{
			lastKillTimeSec = parseDuration(dur.group("time"));
			lastKillPb = msg.contains(NEW_PB_MARK);
			// "(new personal best)" means this kill is the record; otherwise the game
			// may restate the standing record after the time.
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

		// an untradeable drop can also carry the pet name after a pet prime.
		Matcher unt = UNTRADEABLE_DROP.matcher(msg);
		if (unt.find() && petPendingTicks >= 0)
		{
			emitPet(unt.group("dropped").trim());
			return;
		}

		Matcher ca = COMBAT_TASK.matcher(msg);
		if (ca.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("tier", ca.group("grade").trim().toUpperCase());
			data.addProperty("task", COMBAT_TASK_POINTS.matcher(ca.group("challenge").trim()).replaceAll(""));
			emit("COMBAT_ACHIEVEMENT", data);
			return;
		}

		// clue casket completion: tier + the running lifetime count.
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
			// The streak line always prints on completion, so it's the trigger. Task
			// identity comes from the finished line's creature if we caught it, else
			// from the fallbacks below, so a missing or reworded finished line doesn't
			// drop the completion.
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
				task = pendingSlayerTask;   // last resort: the "N creature" blob
			}
			if (task != null && !task.isEmpty())
			{
				JsonObject data = new JsonObject();
				data.addProperty("task", task);
				data.addProperty("monster", task);
				// "You killed N <creature>" is the true size of the task. The loot
				// spine's own count undershoots when an opening kill went uncaptured
				// at the task hand-off.
				if (pendingSlayerKills != null)
				{
					data.addProperty("killCount", pendingSlayerKills);
				}
				// Only the plain "You've completed N tasks…" line carries the lifetime
				// number. A qualified one ("N Mortimer task", "N Wilderness tasks")
				// counts something narrower, so leave the badge off.
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
			slayerPendingTicks = -1;   // the streak line handled it; disarm the flush
			lastSlayerTask = null;
			return;
		}

		// Pet drop prime; the name resolves on a following clog/untradeable line.
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

	// The finished-task line was captured but the streak line never finalised it. That
	// line is a real completion, so emit it from the same identity fallbacks the
	// streak path uses, then clear every pending field (lastSlayerTask included) so a
	// late streak line finds nothing to re-emit.
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
			emit("SLAYER", data);   // no lifetime "count": the finished line has none
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
		expireServerLootKeys();
		flushUntakenLoot();

		// expire a pet prime that never got a name.
		if (petPendingTicks >= 0 && ++petPendingTicks > 3)
		{
			petPendingTicks = -1;
		}

		// A finished-task line whose streak line never finalised it gets flushed after
		// a short window. Disarmed above when the streak line handled it.
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
			// left behind, so bank it before the refs go stale. These items despawn
			// early on unload, which the tick check in onItemDespawned would read as
			// a pickup.
			for (GroundLoot g : groundLoot.values())
			{
				untakenBatch.add(new UntakenItem(g.id, g.qty, g.source, g.owner));
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

	// lowercased, trailing parenthetical stripped, to line a kill-count line's subject
	// up with the NPC name.
	private static String cleanKey(String name)
	{
		if (name == null)
		{
			return "";
		}
		return Text.removeTags(name).replaceAll("\\s*\\(.+\\)$", "").trim().toLowerCase();
	}

	// null while the name can't be read yet.
	private String localName()
	{
		Player lp = client.getLocalPlayer();
		String name = lp != null ? lp.getName() : null;
		return name == null || name.isEmpty() ? null : name;
	}

	private void emit(String type, JsonObject data)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = localName();
		if (name == null)
		{
			return;
		}
		// The journal always gets the event; the cloud push below is extra. Gating the
		// network on the opt-in as well as the token means a token left over from an
		// earlier install can't leak anything.
		localStore.record(type, data, name);
		if (!config.cloudSync() || config.serverBaseUrl().trim().isEmpty())
		{
			return;
		}
		String tokenRaw = configManager.getRSProfileConfiguration(
			ChroniclePlugin.GROUP, ChroniclePlugin.KEY_TOKEN);
		if (tokenRaw == null || tokenRaw.trim().isEmpty())
		{
			return;   // no token, nothing to push under
		}
		final String base = config.serverBaseUrl();
		final String token = tokenRaw.trim();
		final JsonObject body = new JsonObject();
		body.addProperty("playerName", name);
		// Stable account id, so an in-game rename still lands on the same account.
		// Sent as a string to dodge 64-bit JSON number precision.
		long accountHash = client.getAccountHash();
		// -1 means "no account"; any other value, negative ones included, is real.
		if (accountHash != -1L)
		{
			body.addProperty("accountHash", String.valueOf(accountHash));
		}
		body.addProperty("type", type);
		body.addProperty("eventId", UUID.randomUUID().toString());
		body.add("data", data);

		api.postEvent(base, token, body);
	}

}
