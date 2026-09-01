/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Chronicle",
	description = "A local journal of your OSRS activity — loot, levels, kill counts, "
		+ "collection log, clues, quests, diaries, combat achievements, slayer, pets, "
		+ "deaths and lifetime counters — kept on your own computer. Optional cloud sync "
		+ "(off by default, server field blank) can additionally send it to a "
		+ "Chronicle-compatible server you configure.",
	tags = {"chronicle", "journal", "stats", "tracker", "loot", "slayer", "collection", "osrs"}
)
// The Slayer plugin's service supplies the active task for on-task drop tagging.
// The dependency guarantees it's loaded, and its service bound, before us.
@PluginDependency(SlayerPlugin.class)
// Chest, casket and every other non-NPC pickup reaches us only as the core Loot
// Tracker's LootReceived, and its archive is what a late install inherits from.
@PluginDependency(LootTrackerPlugin.class)
public class ChroniclePlugin extends Plugin
{
	static final String GROUP = ChronicleConfig.GROUP; // "chronicle"
	static final String KEY_TOKEN = "token";
	// The name this account's journal is filed under. RSProfile-scoped: keyed on the
	// account hash, which survives an in-game rename.
	static final String KEY_JOURNAL_NAME = "journalName";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChronicleConfig config;

	@Inject
	private ChronicleApiClient api;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private ChronicleEventCapture eventCapture;

	@Inject
	private chronicle.counters.ChronicleCounters counters;

	@Inject
	private chronicle.counters.StatStore statStore;

	@Inject
	private ClogCapture clogCapture;

	@Inject
	private AchievementSync achievementSync;

	@Inject
	private LocalStore localStore;

	@Inject
	private net.runelite.client.game.SkillIconManager skillIcons;

	@Inject
	private net.runelite.client.game.SpriteManager sprites;

	// Injected: Hub review rejects a plugin that builds its own Gson.
	@Inject
	private com.google.gson.Gson gson;

	private HistoryLog historyLog;

	// The calendar spine, parsed off the EDT and published whole. The History tab
	// re-reads it on every pill, stepper and date click, and the file is unbounded.
	private volatile String historyCacheRsn;
	private volatile java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> historyCache;
	private volatile boolean historyLoading;

	private ChroniclePanel panel;
	private NavigationButton navButton;

	private ScheduledFuture<?> pushTask;
	private boolean pendingEnrolCheck;
	// True from the moment an account is in-game until its session is torn down. It
	// can't key off the prior state: a dropped connection arrives via CONNECTION_LOST.
	private boolean wasLoggedIn;

	// Kept from the last harvest so a logout push still works once the RSProfile is gone.
	private volatile String cachedToken;
	private volatile String cachedName;
	private volatile Map<String, Integer> cachedSnapshot;
	private volatile String cachedAccountType;

	// Set while the Loot Tracker adoption is between its off-thread read and the
	// client-thread apply; a refresh in that window must not start it over.
	private volatile boolean lootImportRunning;

	// Keys the upward push withholds: the server re-derives all three at read time and
	// sending them as counters double-presents them. Only resourcesGatheredValue is
	// still written by this build; the untakenLoot pair reaches us only in a journal
	// imported from an older record.
	private static final java.util.Set<String> PUSH_EXCLUDE = new java.util.HashSet<>(
		java.util.Arrays.asList("untakenLootValue", "untakenLootCount", "resourcesGatheredValue"));

	// The wiki drop-rate book behind the dryness ledger.
	private GrindBook grindBook;

	// Panel-facing status.
	private volatile String enrolledRsn;
	// The logged-in name: the journal's identity, and the push name when cloud is on.
	private volatile String localName;
	private volatile String statusLine = "Waiting for a login.";

	@Provides
	ChronicleConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChronicleConfig.class);
	}

	@Override
	protected void startUp()
	{
		// Built here so field injection has already supplied the client's Gson.
		historyLog = new HistoryLog(gson);
		grindBook = new GrindBook(gson);
		panel = new ChroniclePanel(this);
		navButton = NavigationButton.builder()
			.tooltip("Chronicle")
			.icon(buildIcon())
			.priority(9)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		refreshPanel();

		eventCapture.reset();
		eventBus.register(eventCapture);
		// Toggling the plugin fires no GameStateChanged. Nothing else clears the trackers'
		// inference state (inventory snapshots, in-flight clicks).
		counters.reset();
		// Consumables are priced as they're used and folded straight into the journal.
		counters.setConsumableSink((key, gp) ->
		{
			String who = localName;
			if (who != null)
			{
				localStore.addConsumableValue(key, gp, who);
			}
		});
		// Gathers are noted in the journal and read back later, so an ore mined a month
		// ago still counts as gathered when it's finally binned.
		counters.setGatheredLedger(localStore);
		eventBus.register(counters);
		// Clog capture: the completion fraction comes off the login varps. When the player
		// opens the log themselves, the capture fires the log's own Search op to make the
		// server transmit every page in one go. It never opens the log itself.
		clogCapture.reset();
		eventBus.register(clogCapture);
		// Empty change gate: the first push after a restart sends everything.
		achievementSync.reset();

		reschedulePushLoop();
		warnIfDisabled(SlayerPlugin.class,
			"Turn on the Slayer plugin for on-task drop tagging.",
			"Slayer plugin is disabled — on-task drop tagging is inactive.");
		warnIfDisabled(LootTrackerPlugin.class,
			"Turn on the Loot Tracker plugin — chest and casket loot reaches Chronicle "
				+ "through it.",
			"Loot Tracker is disabled — non-NPC loot is not being captured.");
		log.debug("Chronicle started — slayer service: {}",
			eventCapture.hasSlayerService() ? "AVAILABLE" : "MISSING");

		// If the plugin is toggled on mid-session, catch the already-logged-in case.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			pendingEnrolCheck = true;
			// No LOGGED_IN transition will arrive; arm the teardown flag by hand.
			wasLoggedIn = true;
			// The clog fraction varps arrived with a LOGGED_IN we missed; read them now.
			clientThread.invoke(() -> clogCapture.primeFromVarps(client));
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(eventCapture);
		eventBus.unregister(counters);
		eventBus.unregister(clogCapture);
		if (pushTask != null)
		{
			pushTask.cancel(false);
			pushTask = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		// The panel's repeating timers keep it (and us) alive; left running, every
		// plugin toggle leaks another detached panel rebuilding itself forever.
		final ChroniclePanel dying = panel;
		if (dying != null)
		{
			javax.swing.SwingUtilities.invokeLater(dying::shutdown);
		}
		panel = null;
		pendingEnrolCheck = false;
		// Bank the session on the way out. A plugin toggle, or a client exit that shuts
		// plugins down, would otherwise drop everything counted since the last fold.
		if (localName != null && localStore.isReadyFor(localName))
		{
			localStore.setTrackers(sessionView(), localName);
			localStore.rebase(localName);
			// A settings toggle stops the plugin on the EDT and the flush is an fsync plus
			// a move; the executor outlives a toggle, the client-exit path does not.
			if (javax.swing.SwingUtilities.isEventDispatchThread())
			{
				executor.submit(() -> localStore.flush(localDir()));
			}
			else
			{
				localStore.flush(localDir());
			}
		}
		// No events reach the trackers while we're unregistered, and the player may
		// switch accounts before toggling us back on. Drop the totals.
		statStore.clear();
		wasLoggedIn = false;
	}

	private void reschedulePushLoop()
	{
		if (pushTask != null)
		{
			pushTask.cancel(false);
			pushTask = null;
		}
		long minutes = Math.max(1, config.pushIntervalMinutes());
		pushTask = executor.scheduleWithFixedDelay(
			this::scheduledPush, minutes, minutes, TimeUnit.MINUTES);
	}

	// Both of the plugins we lean on can be switched off by the player: chest and casket
	// loot only reaches us through the core Loot Tracker, and on-task tagging needs the
	// Slayer plugin's service. The dependency guarantees they are loaded, not enabled.
	private void warnIfDisabled(Class<? extends Plugin> type, String status, String logLine)
	{
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (type.isInstance(p))
				{
					if (!pluginManager.isPluginEnabled(p))
					{
						statusLine = status;
						refreshPanel();
						log.debug(logLine);
					}
					return;
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("plugin-enabled check failed for {}", type.getSimpleName(), e);
		}
	}

	// ------------------------------------------------------------------
	// Event handlers
	// ------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState state = e.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			pendingEnrolCheck = true;
			wasLoggedIn = true;
		}
		else if (state == GameState.LOGIN_SCREEN && wasLoggedIn)
		{
			// Any arrival at the login screen from an in-game session closes it,
			// CONNECTION_LOST included. A world hop never reaches the login screen.
			wasLoggedIn = false;
			onLogout();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!pendingEnrolCheck)
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = localPlayerName();
		if (name == null)
		{
			return; // wait for the name to populate
		}
		pendingEnrolCheck = false;
		// LOGGED_IN fires again on every world hop and region load for the same session;
		// reloading would re-freeze the lifetime base over totals it already holds.
		if (name.equals(localName) && localStore.isReadyFor(name))
		{
			refreshPanel();
			if (cloudActive())
			{
				adoptToken(name);
			}
			return;
		}
		// A new account for this session: mount its journal. Nothing here touches the network.
		localName = name;
		sessionStartMs = System.currentTimeMillis();
		if (!cloudActive())
		{
			statusLine = "Journaling locally — nothing leaves this computer.";
		}
		refreshPanel();
		final String who = name;
		// A different name on the RSProfile pointer means an in-game rename: move the
		// journal and its spine to the new slug before loading so the record continues.
		final String priorName = trimToNull(
			configManager.getRSProfileConfiguration(GROUP, KEY_JOURNAL_NAME));
		executor.submit(() ->
		{
			if (priorName != null && !LocalStore.slug(priorName).equals(LocalStore.slug(who))
				&& LocalStore.migrateJournalFiles(localDir(), priorName, who))
			{
				chat("Chronicle: your journal followed the rename — "
					+ priorName + " is now " + who + ".");
			}
			configManager.setRSProfileConfiguration(GROUP, KEY_JOURNAL_NAME, who);
			localStore.load(localDir(), who);
			// Same off-thread mount as the journal, so the History tab opens from memory.
			reloadHistory(who);
			// A different journal is mounted now; drop the panel views built on the last one.
			ChroniclePanel p = panel;
			if (p != null)
			{
				javax.swing.SwingUtilities.invokeLater(p::resetAccountCaches);
			}
			clientThread.invoke(this::refreshLocal);
		});
		if (cloudActive())
		{
			adoptToken(name);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!GROUP.equals(e.getGroup()))
		{
			return;
		}
		String key = e.getKey();
		if ("pushIntervalMinutes".equals(key) || "cloudSync".equals(key)
			|| "serverBaseUrl".equals(key) || "manualToken".equals(key))
		{
			reschedulePushLoop();
			if ("serverBaseUrl".equals(key))
			{
				// A token is issued by one server and means nothing to another, so
				// repointing the URL drops it. A pasted one re-adopts on the next login.
				clientThread.invoke(() ->
				{
					if (trimToNull(config.manualToken()) == null)
					{
						configManager.unsetRSProfileConfiguration(GROUP, KEY_TOKEN);
					}
					cachedToken = null;
					cachedName = null;
					enrolledRsn = null;
				});
			}
			if ("cloudSync".equals(key) || "serverBaseUrl".equals(key))
			{
				// A settings write arrives on whatever thread made it: the EDT, for the
				// settings panel. The stores below belong to the client thread.
				clientThread.invoke(() ->
				{
					// A cloud or server-URL change restarts the session: fold and re-freeze first.
					final String who = localName;
					if (who != null)
					{
						localStore.setTrackers(sessionView(), who);
						localStore.rebase(who);
						executor.submit(() -> localStore.flush(localDir()));
					}
					// The session restarts from zero; the rebase above already banked its increments.
					statStore.clear();
					counters.reset();
				});
			}
			// Turning cloud on re-runs the per-login branch so the token adopts now.
			if (cloudActive() && client.getGameState() == GameState.LOGGED_IN)
			{
				pendingEnrolCheck = true;
			}
			refreshPanel();
		}
	}

	// ------------------------------------------------------------------
	// Cloud identity (upward only)
	// ------------------------------------------------------------------

	// Takes this account's push token: a pasted override, or whatever an earlier
	// install left on the RSProfile. No token means no cloud. Client thread.
	private void adoptToken(String name)
	{
		String override = trimToNull(config.manualToken());
		String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
		if (override != null && !override.equals(token))
		{
			configManager.setRSProfileConfiguration(GROUP, KEY_TOKEN, override);
			token = override;
		}
		if (token == null)
		{
			statusLine = "Cloud sync is on but this account has no token — paste one "
				+ "under Advanced in the plugin settings.";
			refreshPanel();
			return;
		}
		enrolledRsn = name;
		cachedToken = token;
		cachedName = name;
		statusLine = "Cloud sync on. Pushing on the next interval.";
		refreshPanel();
		// Push straight away so a freshly-launched client isn't stale.
		pushCurrent();
	}

	// ------------------------------------------------------------------
	// Harvest + push
	// ------------------------------------------------------------------

	// Scheduled on the executor; hops to the client thread to read config.
	private void scheduledPush()
	{
		// The journal refreshes every cycle; the cloud push rides the same cadence.
		clientThread.invoke(this::refreshLocal);
		if (cloudActive())
		{
			clientThread.invoke(this::pushCurrent);
		}
	}

	// Client thread. Harvests the current counters and pushes them.
	private void pushCurrent()
	{
		// Nothing below may touch the network without a configured server.
		if (!cloudActive())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = localPlayerName();
		if (name == null)
		{
			return;
		}
		// The account hash lets the server follow a rename instead of reading it as an alt.
		api.setAccountHash(client.getAccountHash());
		String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
		if (token == null)
		{
			return;   // no token, no cloud
		}
		// Flush any clog pages viewed since the last push; the server merges partials.
		if (clogCapture.isDirty())
		{
			api.pushClog(config.serverBaseUrl(), token, name, clogCapture.snapshot());
			clogCapture.clearDirty();
		}
		// Quests, diaries and combat tasks: whole snapshot, sent only when it changed.
		final JsonObject achievements = achievementSync.snapshot();
		if (achievementSync.changedSince(achievements))
		{
			api.pushAchievements(config.serverBaseUrl(), token, name, achievements,
				ok ->
				{
					if (ok)
					{
						achievementSync.markSynced(achievements);
					}
				});
		}
		// The journal is the record; the push mirrors its absolutes upward. Guarded on
		// the store's identity: a switch mid-load would push another player's lifetime.
		if (!name.equals(localName) || !localStore.isReadyFor(name))
		{
			return;
		}
		localStore.setTrackers(sessionView(), localName);
		Map<String, Integer> snapshot = journalAbsolutes(name);
		if (snapshot.isEmpty())
		{
			return;   // nothing journaled yet on this account
		}
		cachedToken = token;
		cachedName = name;
		cachedSnapshot = snapshot;
		cachedAccountType = accountTypeTag(client.getVarbitValue(VarbitID.IRONMAN));
		enrolledRsn = name;

		log.debug("pushing {} counters for {}", snapshot.size(), name);
		api.pushStats(config.serverBaseUrl(), token, name, snapshot, cachedAccountType,
			harvestSkills(), this::onPushResult);
	}

	// The IRONMAN varbit's values as the server's account tags; empty for a normal account.
	private static String accountTypeTag(int varbit)
	{
		switch (varbit)
		{
			case 1: return "ironman";
			case 2: return "uim";
			case 3: return "hcim";
			case 4: return "gim";
			case 5: return "hcgim";
			case 6: return "ugim";
			default: return "";
		}
	}

	// The journal's lifetime counters, clamped to the wire's int shape.
	private Map<String, Integer> journalAbsolutes(String rsn)
	{
		Map<String, Integer> out = new java.util.HashMap<>();
		// The previous account's model stays mounted until the next load lands. Unguarded,
		// this read hands its totals to another account's push.
		if (rsn == null || !localStore.isReadyFor(rsn))
		{
			return out;
		}
		for (Map.Entry<String, Long> e : localStore.trackersSnapshot().entrySet())
		{
			long v = e.getValue() != null ? e.getValue() : 0;
			if (v > 0 && !PUSH_EXCLUDE.contains(e.getKey()))
			{
				out.put(e.getKey(), (int) Math.min(Integer.MAX_VALUE, v));
			}
		}
		return out;
	}

	// Client thread (GameStateChanged). Best-effort final push.
	private void onLogout()
	{
		// Fold, write and end the session so a different account logging in next can't
		// record onto this model. Guarded in case the load never finished.
		if (localName != null && localStore.isReadyFor(localName))
		{
			localStore.setTrackers(sessionView(), localName);
			appendHistoryBaseline();
			recordSessionLine();
			// Freeze the totals for the final push before the stores reset.
			Map<String, Integer> fresh = journalAbsolutes(localName);
			if (!fresh.isEmpty())
			{
				cachedSnapshot = fresh;
			}
		}
		executor.submit(() -> localStore.flush(localDir()));
		localStore.endSession();
		eventCapture.resetSessionFlags();
		// Reset the gate so the next login syncs its own snapshot even if identical.
		achievementSync.reset();
		// These totals belong to the account that just left; the next must not inherit them.
		statStore.clear();
		// Take the push identity by value, then clear it: left standing it becomes the
		// identity of whoever logs in next. localName stays so the panel can browse.
		final String token = cachedToken;
		final String who = cachedName;
		final String type = cachedAccountType;
		final Map<String, Integer> snapshot = cachedSnapshot;
		cachedToken = null;
		cachedName = null;
		cachedSnapshot = null;
		cachedAccountType = null;
		enrolledRsn = null;
		if (!cloudActive() || token == null || who == null
			|| snapshot == null || snapshot.isEmpty())
		{
			return;
		}
		api.pushStats(config.serverBaseUrl(), token, who, snapshot, type,
			null, this::onPushResult);   // logout flush: client unreadable, skip skills
	}

	private void onPushResult(ChronicleApiClient.PushResult result)
	{
		if (result.ok)
		{
			statusLine = "Last push OK (" + result.changed + " changed) at " + nowClock() + ".";
			log.debug("push ok: {} accepted, {} changed", result.accepted, result.changed);
		}
		else if (result.code == 409)
		{
			// The server holds higher totals than this journal, usually another computer.
			// The client stays authoritative for its own record; just surface it.
			statusLine = "Server totals are ahead of this journal (another computer?) at "
				+ nowClock() + ".";
			log.debug("push 409 — server ahead; journal stays authoritative");
		}
		else
		{
			statusLine = "Last push failed (" + result.code + ")"
				+ (result.error != null ? ": " + result.error : "") + " at " + nowClock() + ".";
			log.debug("push failed code={} err={}", result.code, result.error);
		}
		refreshPanel();
	}

	// This session's increments, counted from zero by the trackers.
	Map<String, Integer> harvest()
	{
		return statStore.snapshotAll();
	}

	// Per-skill level and xp for the push, keyed by lowercase skill name, plus an
	// "overall" total. Client thread only, the skill accessors require it.
	private JsonObject harvestSkills()
	{
		JsonObject skills = new JsonObject();
		for (Skill s : Skill.values())
		{
			if (s == Skill.OVERALL)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("level", client.getRealSkillLevel(s));
			o.addProperty("xp", client.getSkillExperience(s));
			// ROOT locale: a Turkish JVM lowercases MINING to "mınıng".
			skills.add(s.name().toLowerCase(java.util.Locale.ROOT), o);
		}
		JsonObject overall = new JsonObject();
		overall.addProperty("level", client.getTotalLevel());
		overall.addProperty("xp", client.getOverallExperience());
		skills.add("overall", overall);
		return skills;
	}

	// ------------------------------------------------------------------
	// Panel-invoked actions (may be called off the client thread)
	// ------------------------------------------------------------------

	void actionPushNow()
	{
		if (!cloudActive())
		{
			chat("Chronicle: enable cloud sync (and set a server) under Advanced in the plugin settings first.");
			return;
		}
		clientThread.invoke(this::pushCurrent);
	}

	String enrolledRsn()
	{
		return enrolledRsn;
	}

	// Cloud sync active: opted in and pointed at a server. Only the network is
	// gated; the journal runs whenever the plugin is on.
	boolean cloudActive()
	{
		return config.cloudSync() && !config.serverBaseUrl().trim().isEmpty();
	}

	// ── Panel-facing reads ─────────────────────────────────────────────

	// Lifetime counters as the journal knows them (base + session, floored).
	Map<String, Long> lifetimeCounters()
	{
		return localStore.trackersSnapshot();
	}

	// This session's own increments (max-type keys as absolutes).
	Map<String, Integer> sessionCounters()
	{
		return sessionView();
	}

	java.util.List<LocalStore.SourceRow> dropSources()
	{
		return localStore.dropSources();
	}

	java.util.List<LocalStore.BagItem> sourceItems(String source)
	{
		return localStore.sourceItems(source);
	}

	// The journal's task-by-task slayer journey; the panel fetches on first open.
	void fetchSlayerJourney(
		java.util.function.Consumer<ChronicleApiClient.SlayerJourney> onDone)
	{
		final String rsn = localName;
		if (rsn == null || !localStore.isReadyFor(rsn))
		{
			onDone.accept(null);
			return;
		}
		executor.submit(() -> onDone.accept(localStore.slayerJourney()));
	}

	java.util.List<JsonObject> feedNewest(int n)
	{
		return localStore.feedNewest(n);
	}

	int sessionLoots()
	{
		return localStore.sessionLoots();
	}

	long sessionLootValue()
	{
		return localStore.sessionLootValue();
	}

	java.util.List<LocalStore.RecentDrop> recentDrops()
	{
		return localStore.recentDrops();
	}

	ChronicleEventCapture.SlayerView slayerView()
	{
		return eventCapture.slayerView();
	}

	// The spine as last parsed. The panel's rebuild calls this on the EDT: never read
	// disk here. A cold cache asks the executor and the panel rebuilds when it lands.
	java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> historyBaselines()
	{
		String rsn = localName;
		if (rsn == null)
		{
			return new java.util.TreeMap<>();
		}
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> cached = historyCache;
		if (cached != null && rsn.equals(historyCacheRsn))
		{
			return cached;
		}
		if (!historyLoading)
		{
			historyLoading = true;
			executor.submit(() ->
			{
				try
				{
					reloadHistory(rsn);
				}
				finally
				{
					historyLoading = false;
				}
			});
		}
		return new java.util.TreeMap<>();
	}

	// Executor only: the spine read the EDT must not do, published to the panel.
	private void reloadHistory(String rsn)
	{
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> read =
			historyLog.read(localDir(), rsn);
		// An account switch can overtake the read; don't hand the panel the previous
		// player's calendar under the current player's name.
		if (rsn.equals(localName))
		{
			historyCache = read;
			historyCacheRsn = rsn;
			refreshPanel();
		}
	}

	// The earliest date the record knows about, which the Loot Tracker inheritance
	// often puts years before the file. Epoch millis, 0 when nothing is dated.
	long keptSince()
	{
		long earliest = Long.MAX_VALUE;
		for (LocalStore.SourceRow r : localStore.dropSources())
		{
			if (r.firstMs > 0)
			{
				earliest = Math.min(earliest, r.firstMs);
			}
		}
		for (JsonObject e : localStore.feedNewest(4000))
		{
			if (e.has("ts"))
			{
				long ts = e.get("ts").getAsLong();
				if (ts > 0)
				{
					earliest = Math.min(earliest, ts);
				}
			}
		}
		return earliest == Long.MAX_VALUE ? 0 : earliest;
	}

	// The combat level as the journal last saw it, or 0.
	int combatLevel()
	{
		return localStore.combatLevel();
	}

	net.runelite.client.game.SpriteManager sprites()
	{
		return sprites;
	}

	// Skill sprites for the History grid.
	net.runelite.client.game.SkillIconManager skillIcons()
	{
		return skillIcons;
	}

	// Bosses and activities by kill count, as the collection log lists them, floored
	// by the drop ledger where it has watched more kills than the log has recorded.
	Map<String, Long> killCounts()
	{
		Map<String, Long> out = new java.util.LinkedHashMap<>();
		JsonObject cl = localStore.clogSnapshot();
		if (cl.has("kcs") && cl.get("kcs").isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e
				: cl.getAsJsonObject("kcs").entrySet())
			{
				try
				{
					long v = e.getValue().getAsLong();
					if (v > 0)
					{
						out.put(e.getKey(), v);
					}
				}
				catch (RuntimeException ignored)
				{
					// a non-numeric entry is not a kill count
				}
			}
		}
		java.util.Map<String, String> byKind = new java.util.HashMap<>();
		for (String name : out.keySet())
		{
			byKind.put(sameThing(name), name);
		}
		for (LocalStore.SourceRow r : localStore.dropSources())
		{
			String known = byKind.get(sameThing(r.name));
			if (known != null && r.kc > 0)
			{
				out.merge(known, (long) r.kc, Math::max);
			}
		}
		return out;
	}

	// Everything else the ledger counted, which the collection log has no page for.
	// Kept apart: with no record of a source's kind, arrowtips would sit among bosses.
	Map<String, Long> ledgerKills()
	{
		Map<String, Long> bosses = killCounts();
		java.util.Set<String> known = new java.util.HashSet<>();
		for (String name : bosses.keySet())
		{
			known.add(sameThing(name));
		}
		Map<String, Long> out = new java.util.LinkedHashMap<>();
		for (LocalStore.SourceRow r : localStore.dropSources())
		{
			if (r.kc > 0 && !known.contains(sameThing(r.name)))
			{
				out.merge(r.name, (long) r.kc, Math::max);
			}
		}
		return out;
	}

	// Loose identity for a source: the collection log says "Tormented Demons" where
	// the ledger says "Tormented Demon", and they are one thing.
	private static String sameThing(String name)
	{
		String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
		return n.endsWith("s") ? n.substring(0, n.length() - 1) : n;
	}

	// Level + xp per skill, as the journal last saw them.
	java.util.Map<String, long[]> skillSheet()
	{
		return localStore.skillSheet();
	}

	JsonObject clogSnapshot()
	{
		return localStore.clogSnapshot();
	}

	int clogFinished()
	{
		return Math.max(clogCapture.finishedCount(), localStore.clogFraction()[0]);
	}

	int clogAvailable()
	{
		return Math.max(clogCapture.availableCount(), localStore.clogFraction()[1]);
	}

	java.util.List<LocalStore.BagItem> untakenItemsOf(String source)
	{
		return localStore.untakenItemsOf(source);
	}

	java.util.List<LocalStore.UntakenRow> untakenSourcesOf(String item)
	{
		return localStore.untakenSourcesOf(item);
	}

	java.util.List<LocalStore.BagItem> slayerTaskItems(int index)
	{
		return localStore.slayerTaskItems(index);
	}

	java.util.List<LocalStore.UntakenRow> slayerTaskMonsters(int index)
	{
		return localStore.slayerTaskMonsters(index);
	}

	java.util.List<LocalStore.PetRow> pets()
	{
		return localStore.pets();
	}

	// The pace of a skill, measured over the days it actually moved.
	PaceBook.Pace pace(String skill)
	{
		java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> spine = historyBaselines();
		long xp = 0;
		try
		{
			xp = client.getSkillExperience(Skill.valueOf(skill.toUpperCase(java.util.Locale.ROOT)));
		}
		catch (RuntimeException ignored)
		{
			// not a real skill name, or the client is unreadable. No pace.
		}
		// The spine files skills lowercase (harvestSkills writes them that way); the panel
		// asks with its own capitalisation. Normalise, or nothing ever matches.
		return PaceBook.forSkill(spine, skill.toLowerCase(java.util.Locale.ROOT), xp);
	}

	java.util.List<LocalStore.UntakenRow> untakenSources()
	{
		return localStore.untakenSources();
	}

	java.util.List<LocalStore.UntakenRow> untakenItems()
	{
		return localStore.untakenItems();
	}

	java.util.Map<String, Long> consumableValues()
	{
		return localStore.consumableValues();
	}

	// Dryness, computed from the journal's own collection log and kill counts against
	// the bundled wiki rate book. The panel fetches once per session.
	void fetchGrinds(java.util.function.Consumer<java.util.List<ChronicleApiClient.GrindRow>> onDone)
	{
		final String rsn = localName;
		if (rsn == null || !localStore.isReadyFor(rsn))
		{
			onDone.accept(null);
			return;
		}
		final JsonObject clog = localStore.clogSnapshot();
		final java.util.List<LocalStore.SourceRow> sources = localStore.dropSources();
		executor.submit(() -> onDone.accept(grindBook.grinds(clog, sources)));
	}

	// True once this session produced an on-task slayer kill.
	boolean slayerSeenThisSession()
	{
		return eventCapture.slayerSeenThisSession();
	}

	private volatile long sessionStartMs;

	// The logout diary line: one dated feed entry closing the session. Local only,
	// skipped when the session was too slight to be worth a line.
	private void recordSessionLine()
	{
		// Wall-clock: an NTP correction or a resumed VM can put the start ahead of now.
		// Floored at zero, in place of a line of negative minutes.
		long mins = sessionStartMs > 0
			? Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 60_000) : 0;
		Map<String, Integer> sess = sessionView();
		long xp = sess.getOrDefault("totalXpGained", 0);
		int drops = localStore.sessionLoots();
		long dropsGp = localStore.sessionLootValue();
		if (mins < 5 && xp == 0 && drops == 0)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("minutes", mins);
		data.addProperty("xp", xp);
		data.addProperty("drops", drops);
		data.addProperty("dropsGp", dropsGp);
		localStore.record("SESSION", data, localName);
	}

	long[] sessionUntakenTally()
	{
		return localStore.sessionUntakenTally();
	}

	// Session counters shaped for display: a peak key survives only when this session
	// beat the journal's lifetime record. Otherwise an old peak reads as a session feat.
	Map<String, Integer> sessionDisplayCounters()
	{
		Map<String, Integer> out = new java.util.HashMap<>(sessionView());
		for (String key : LocalStore.MAX_KEYS)
		{
			Integer val = out.get(key);
			if (val != null && val <= localStore.trackerBase(key))
			{
				out.remove(key);
			}
		}
		return out;
	}

	// The client's Gson, shared with the panel.
	com.google.gson.Gson gson()
	{
		return gson;
	}

	net.runelite.client.game.ItemManager items()
	{
		return localStore.items();
	}

	// Name to show in the panel: the synced name when cloud is on, else the local one.
	String displayRsn()
	{
		return cloudActive() && enrolledRsn != null && !enrolledRsn.isEmpty() ? enrolledRsn : localName;
	}

	// This session's increments, straight from the trackers. Max-type keys pass
	// through as absolutes (the journal takes their max); the rest drops noise.
	Map<String, Integer> sessionView()
	{
		Map<String, Integer> abs = harvest();
		Map<String, Integer> out = new java.util.HashMap<>(abs.size());
		for (Map.Entry<String, Integer> en : abs.entrySet())
		{
			if (LocalStore.MAX_KEYS.contains(en.getKey()) || en.getValue() > 0)
			{
				out.put(en.getKey(), en.getValue());
			}
		}
		return out;
	}

	// Cached once per client run; every flush, load and history append asks for it.
	private static volatile File journalDir;

	private static File localDir()
	{
		File cached = journalDir;
		if (cached != null)
		{
			return cached;
		}
		File dir = new File(net.runelite.client.RuneLite.RUNELITE_DIR, "chronicle");
		journalDir = dir;
		return dir;
	}

	// Client thread. Copies the current character sheet into the journal.
	private void gatherCharacter()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String name = localPlayerName();
		if (name == null)
		{
			return;
		}
		Player lp = client.getLocalPlayer();
		localStore.setCharacter(name, harvestSkills(), lp != null ? lp.getCombatLevel() : 0,
			clogCapture.snapshot(), achievementSync.snapshot());
		// The journal's additive base does the lifetime arithmetic from the session view.
		localStore.setTrackers(sessionView(), name);
	}

	private void refreshLocal()
	{
		gatherCharacter();
		// Only with the journal mounted: a baseline's counters come from it. A day closed
		// mid-load appends a line of zeroes, and every later subtraction reads that as a collapse.
		if (localName != null && localStore.isReadyFor(localName))
		{
			localStore.setTrackers(sessionView(), localName);
			// One closing baseline per day; the History tab and year cards subtract over it.
			if (historyLog.dayRolledOver(localName))
			{
				appendHistoryBaseline();
			}
		}
		// First run per account: adopt the core Loot Tracker's archive so a late install
		// starts years deep. Own-account by the RSProfile keys, and the floors are idempotent.
		if (localName != null && localStore.isReadyFor(localName)
			&& !"true".equals(configManager.getRSProfileConfiguration(GROUP, "lootTrackerImported")))
		{
			importLootTracker();
		}
		executor.submit(() -> localStore.flush(localDir()));
	}

	/** One Loot Tracker source as parsed off the client thread: raw ids and quantities. */
	private static final class RawSource
	{
		final String source;
		final int kills;
		final long firstMs;
		final long lastMs;
		// {item id, quantity} pairs, in the order the tracker stored them.
		final java.util.List<long[]> items = new java.util.ArrayList<>();

		RawSource(String source, int kills, long firstMs, long lastMs)
		{
			this.source = source;
			this.kills = kills;
			this.firstMs = firstMs;
			this.lastMs = lastMs;
		}
	}

	// Client thread. The config scan and JSON parse read no game state and are the
	// bulk of the work, so they go to the executor; inline they stalled the login.
	private void importLootTracker()
	{
		// The RSProfile flag is only written once the adoption lands. Without this, the next
		// refresh starts a second read over the same archive.
		if (lootImportRunning)
		{
			return;
		}
		lootImportRunning = true;
		final String who = localName;
		// Read on the account being imported for, so a switch mid-read can't cross accounts.
		final String profileKey = configManager.getRSProfileKey();
		executor.submit(() ->
		{
			final java.util.List<RawSource> parsed;
			try
			{
				parsed = readLootTrackerArchive(profileKey);
			}
			catch (RuntimeException e)
			{
				lootImportRunning = false;
				log.debug("loot tracker archive read failed", e);
				return;
			}
			clientThread.invoke(() -> adoptLootTrackerArchive(who, parsed));
		});
	}

	// Executor: the config-archive scan and its JSON parse, no game state.
	private java.util.List<RawSource> readLootTrackerArchive(String profileKey)
	{
		java.util.List<RawSource> out = new java.util.ArrayList<>();
		java.util.List<String> keys;
		try
		{
			keys = configManager.getRSProfileConfigurationKeys(
				"loottracker", profileKey, "drops_");
		}
		catch (RuntimeException e)
		{
			log.debug("loot tracker key scan failed", e);
			return out;
		}
		if (keys == null)
		{
			return out;
		}
		for (String key : keys)
		{
			String raw = configManager.getConfiguration("loottracker", profileKey, key);
			if (raw == null || raw.isEmpty())
			{
				continue;
			}
			try
			{
				JsonObject o = gson.fromJson(raw, JsonObject.class);
				String source = o.has("name") ? o.get("name").getAsString() : null;
				if (source == null || source.isEmpty())
				{
					continue;
				}
				RawSource src = new RawSource(source,
					o.has("kills") ? o.get("kills").getAsInt() : 0,
					o.has("first") ? o.get("first").getAsLong() : 0,
					o.has("last") ? o.get("last").getAsLong() : 0);
				if (o.has("drops") && o.get("drops").isJsonArray())
				{
					com.google.gson.JsonArray arr = o.getAsJsonArray("drops");
					for (int i = 0; i + 1 < arr.size(); i += 2)
					{
						long id = arr.get(i).getAsLong();
						long qty = arr.get(i + 1).getAsLong();
						if (id <= 0 || qty <= 0)
						{
							continue;
						}
						src.items.add(new long[]{id, qty});
					}
				}
				out.add(src);
			}
			catch (RuntimeException e)
			{
				log.debug("loot tracker record parse failed: {}", key, e);
			}
		}
		return out;
	}

	// Client thread, for the ItemManager naming and pricing. Flag set on success.
	private void adoptLootTrackerArchive(String rsn, java.util.List<RawSource> parsed)
	{
		try
		{
			// A switch can land mid-read; don't floor one account's archive into another's.
			if (rsn == null || !rsn.equals(localName) || !localStore.isReadyFor(rsn))
			{
				return;
			}
			java.util.List<LocalStore.LootSeed> seeds = new java.util.ArrayList<>(parsed.size());
			long events = 0;
			for (RawSource src : parsed)
			{
				java.util.List<LocalStore.BagItem> items =
					new java.util.ArrayList<>(src.items.size());
				for (long[] drop : src.items)
				{
					int canon = localStore.items().canonicalize((int) drop[0]);
					String name;
					try
					{
						name = localStore.items().getItemComposition(canon).getName();
					}
					catch (Exception e)   // an id this client can't compose
					{
						// Name it by number. Thrown, it escapes the loop with the imported flag
						// unwritten, and every later refresh starts the whole archive again.
						name = "Item " + canon;
					}
					long each = localStore.items().getItemPrice(canon);
					items.add(new LocalStore.BagItem(canon, name, drop[1],
						Math.max(0, each) * drop[1]));
				}
				seeds.add(new LocalStore.LootSeed(src.source, src.kills, src.firstMs,
					src.lastMs, items));
				events += src.kills;
			}
			if (!seeds.isEmpty())
			{
				localStore.floorLootTracker(seeds, rsn);
				chat("Chronicle: adopted " + seeds.size() + " sources · "
					+ String.format(java.util.Locale.UK, "%,d", events)
					+ " loot events from your Loot Tracker.");
				refreshPanel();
			}
			configManager.setRSProfileConfiguration(GROUP, "lootTrackerImported", true);
		}
		finally
		{
			lootImportRunning = false;
		}
	}

	// Client thread. Appends today's closing skills+counters baseline.
	private void appendHistoryBaseline()
	{
		final String rsn = localName;
		if (rsn == null)
		{
			return;
		}
		// Long: the "overall" entry is total xp, past Integer.MAX_VALUE well before a
		// maxed account, and it would wrap negative into a stream nothing rewrites.
		final Map<String, Long> skills = new java.util.HashMap<>();
		JsonObject sk = harvestSkills();
		if (sk != null)
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e : sk.entrySet())
			{
				if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("xp"))
				{
					skills.put(e.getKey(), e.getValue().getAsJsonObject().get("xp").getAsLong());
				}
			}
		}
		final Map<String, Long> counters = localStore.trackersSnapshot();
		final Map<String, Long> kcs = killCounts();
		executor.submit(() ->
		{
			historyLog.append(localDir(), rsn, skills, counters, kcs);
			// The panel reads the spine from memory, so the line just written has to reach
			// the cache or the closed day stays invisible until the next mount.
			reloadHistory(rsn);
		});
	}

	String statusLine()
	{
		return statusLine;
	}

	// Why the journal isn't keeping the record (a failed write, or a file a newer
	// build wrote), or null while it is.
	String journalWarning()
	{
		return localStore.journalWarning();
	}

	/**
	 * Merge a Chronicle journal file into this account's record. {@link
	 * LocalStore#importJournal} covers why the merge floors. A sibling
	 * {@code .history.jsonl} brings its calendar spine across too.
	 */
	void actionImport(File file)
	{
		final String rsn = localName;
		if (rsn == null || !localStore.isReadyFor(rsn))
		{
			chat("Chronicle: log in first — an import lands in the logged-in account's journal.");
			return;
		}
		if (file == null || !file.isFile())
		{
			return;
		}
		executor.submit(() ->
		{
			JsonObject in;
			try
			{
				String txt = new String(java.nio.file.Files.readAllBytes(file.toPath()),
					java.nio.charset.StandardCharsets.UTF_8);
				com.google.gson.JsonElement el = gson.fromJson(txt, com.google.gson.JsonElement.class);
				in = el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
			}
			catch (Exception e)
			{
				log.debug("import read failed", e);
				chat("Chronicle: couldn't read that file — it doesn't look like a journal.");
				return;
			}
			if (in == null || !(in.has("trackers") || in.has("drops") || in.has("feed")))
			{
				chat("Chronicle: that file isn't a Chronicle journal.");
				return;
			}
			String summary = localStore.importJournal(in, rsn);
			if (summary == null)
			{
				return;
			}
			// The spine travels beside the journal in its own file.
			File spine = new File(file.getParentFile(),
				file.getName().replaceAll("\\.json$", "") + ".history.jsonl");
			int days = spine.isFile() ? historyLog.importSpine(localDir(), rsn, spine) : 0;
			localStore.flush(localDir());
			reloadHistory(rsn);
			chat("Chronicle: imported " + summary
				+ (days > 0 ? " · " + days + " days of history" : "") + ".");
			clientThread.invoke(this::refreshLocal);
		});
	}

	// There's nothing to export: the record is already a plain JSON file on the
	// player's disk, so open the folder rather than write a second copy.
	void actionOpenJournalFolder()
	{
		executor.submit(() ->
		{
			if (localName != null && localStore.isReadyFor(localName))
			{
				localStore.flush(localDir());   // flush so the folder shows it current
			}
			File dir = localDir();
			dir.mkdirs();
			net.runelite.client.util.LinkBrowser.open(dir.getAbsolutePath());
		});
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private void refreshPanel()
	{
		ChroniclePanel p = panel;
		if (p != null)
		{
			p.update();
		}
	}

	private void chat(String message)
	{
		// addChatMessage must run on the client thread. invoke() runs inline when
		// already on it, and queues when called from an OkHttp callback thread.
		clientThread.invoke(() ->
		{
			try
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "", message, null);
			}
			catch (Exception ignored)
			{
				// Chat unavailable (e.g. not logged in). The panel still shows status.
			}
		});
	}

	private static String nowClock()
	{
		return java.time.LocalTime.now().withNano(0).toString();
	}

	// The logged-in player's name, or null while it's still populating.
	private String localPlayerName()
	{
		Player lp = client.getLocalPlayer();
		String name = lp != null ? lp.getName() : null;
		return name == null || name.isEmpty() ? null : name;
	}

	private static String trimToNull(String s)
	{
		if (s == null)
		{
			return null;
		}
		s = s.trim();
		return s.isEmpty() ? null : s;
	}

	private static BufferedImage buildIcon()
	{
		// A small open book, distinct from the text-badge icons on the rail.
		BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x1E, 0x1B, 0x16));
		g.fillRoundRect(1, 1, 22, 22, 6, 6);
		Color gold = new Color(0xC8, 0xA2, 0x5A);
		g.setColor(gold);
		// two page leaves meeting at a spine
		g.fillPolygon(new int[]{4, 11, 11, 4}, new int[]{7, 5, 17, 19}, 4);
		g.fillPolygon(new int[]{20, 13, 13, 20}, new int[]{7, 5, 17, 19}, 4);
		g.setColor(new Color(0x1E, 0x1B, 0x16));
		// page lines
		g.drawLine(6, 9, 10, 8);
		g.drawLine(6, 12, 10, 11);
		g.drawLine(14, 8, 18, 9);
		g.drawLine(14, 11, 18, 12);
		g.setColor(gold);
		g.drawLine(12, 5, 12, 18);   // spine
		g.dispose();
		return img;
	}
}
