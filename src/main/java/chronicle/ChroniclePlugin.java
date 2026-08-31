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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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
		+ "Chronicle-compatible server you configure; screenshots are a separate opt-in.",
	tags = {"chronicle", "journal", "stats", "tracker", "loot", "slayer", "collection", "osrs"}
)
// The Slayer plugin's service supplies the active task so we can tag on-task
// drops at the kill. Declaring it a dependency guarantees it's loaded (and its
// service bound) before us — otherwise the on-task stamp silently no-ops.
@PluginDependency(SlayerPlugin.class)
public class ChroniclePlugin extends Plugin
{
	static final String GROUP = ChronicleConfig.GROUP; // "chronicle"
	static final String KEY_TOKEN = "token";

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
	private chronicle.counters.SkillChatBuffer skillBuffer;

	@Inject
	private ClogCapture clogCapture;

	@Inject
	private AchievementSync achievementSync;

	@Inject
	private LocalStore localStore;

	private final HistoryLog historyLog = new HistoryLog();

	private ChroniclePanel panel;
	private NavigationButton navButton;

	private ScheduledFuture<?> pushTask;
	private GameState lastState;
	private boolean pendingEnrolCheck;

	// Cached from the last successful harvest while logged in, so a logout push
	// works even after the RSProfile has been cleared.
	private volatile String cachedToken;
	private volatile String cachedName;
	private volatile Map<String, Integer> cachedSnapshot;
	private volatile String cachedAccountType;

	// Counter cache lifecycle: seeded from the server once per login before we
	// push absolutes; `seeding` guards against concurrent seed fetches.
	private volatile boolean countersSeeded;
	private volatile boolean seeding;
	// A failed seed retries on its own short backoff (5s doubling to 60s) rather
	// than waiting out the multi-minute push loop — the longer we sit unseeded,
	// the more of the session rides on the final-logout push alone.
	private ScheduledFuture<?> seedRetryTask;
	private int seedRetryDelaySec = SEED_RETRY_MIN_SEC;
	// After a 409 (server totals ahead of ours — regression guard), the next seed
	// must FLOOR-merge (per-key max) instead of adding: the store already holds
	// absolutes, and adding a fresh baseline on top would double-count.
	private volatile boolean reseedFloor;
	// Server absolutes waiting to be floored into the journal (see the seed
	// callback) once the journal's own load has finished.
	private volatile Map<String, Integer> pendingServerFloor;
	// The 4th adoption: month-end xp baselines from the cloud's snapshot
	// archive, one-shot (flag cloudHistoryImported). Stash→apply like the rest.
	private volatile java.util.List<ChronicleApiClient.WomSnapshot> pendingHistoryAdopt;
	// The whole per-source item ledger, floored into the local bags each login.
	private volatile java.util.List<ChronicleApiClient.SourceItemRow> pendingBagsAdopt;
	// The uncollected ledger, floored into the untaken store each login.
	private volatile ChronicleApiClient.UntakenLedger pendingUntakenAdopt;
	// Server-priced consumable values ({key: gp}), floored each login.
	private volatile java.util.List<String[]> pendingConsumablesAdopt;
	// True while the stashed feed adoption is the one-shot DEEP import —
	// its config flag is only written once the adopt actually applies.
	private volatile boolean pendingFeedDeep;
	// The cloud ledger's per-source rollup, same lifecycle as the counter floor.
	private volatile java.util.List<ChronicleApiClient.LedgerSource> pendingDropsAdopt;
	private volatile java.util.List<ChronicleApiClient.FeedEvent> pendingFeedAdopt;

	private static final int SEED_RETRY_MIN_SEC = 5;
	private static final int SEED_RETRY_MAX_SEC = 60;

	// Panel-facing status.
	private volatile String enrolledRsn;
	// The logged-in name in local mode (no enrolment happens, but the panel and the
	// on-disk page still need to know whose account this is).
	private volatile String localName;
	private volatile String statusLine = "Not enrolled yet.";

	// Self-service profile state, mirrored from the server so the panel can show
	// it. Refreshed on enrol and after each management action.
	private volatile boolean pageLocked;
	private volatile boolean publicListed;
	private volatile Long deletePendingTs;

	@Provides
	ChronicleConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChronicleConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new ChroniclePanel(this);
		navButton = NavigationButton.builder()
			.tooltip("Chronicle")
			.icon(buildIcon())
			.priority(9)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		refreshPanel();

		// One-shot migration from the pre-Chronicle era: settings move from the
		// old "fitzgerald" config group to "chronicle", and the old enabled +
		// syncMode shape maps onto journal-always + opt-in cloud — an install
		// that was enabled in CLOUD mode keeps syncing to the same server
		// without re-consenting; everyone else lands on the local-only default.
		// NB: the client persists every config item's DEFAULT at plugin
		// registration, before startUp — so "is the new key unset?" is never a
		// valid guard here. The one-shot flag gates the whole block; inside it,
		// old-group values OVERWRITE whatever defaults were just written.
		final String legacyGroup = "fitzgerald";
		if (configManager.getConfiguration(GROUP, "migrated") == null)
		{
			for (String key : new String[]{"serverBaseUrl", "manualToken",
				"captureScreenshots", "pushIntervalMinutes"})
			{
				String v = configManager.getConfiguration(legacyGroup, key);
				if (v != null)
				{
					configManager.setConfiguration(GROUP, key, v);
				}
			}
			String oldEnabled = configManager.getConfiguration(legacyGroup, "enabled");
			String oldMode = configManager.getConfiguration(legacyGroup, "syncMode");
			boolean wasCloud = "true".equals(oldEnabled)
				&& (oldMode == null || "CLOUD".equals(oldMode));
			if (wasCloud)
			{
				configManager.setConfiguration(GROUP, "cloudSync", true);
				String base = configManager.getConfiguration(GROUP, "serverBaseUrl");
				if (base == null || base.trim().isEmpty())
				{
					configManager.setConfiguration(GROUP, "serverBaseUrl", "https://fitzgerald.gg");
				}
			}
			configManager.setConfiguration(GROUP, "migrated", true);
		}

		// The raw-event capture taps (loot/level/kc → /api/events) live in their
		// own eventbus-registered object so this class stays focused on enrol +
		// the counter push loop.
		eventCapture.reset();
		eventBus.register(eventCapture);
		// Toggling the plugin fires no GameStateChanged, so the trackers' own
		// inference state (inventory snapshots, in-flight clicks) would survive the
		// blind window with nothing to invalidate it. Clear it here instead.
		counters.reset();
		// Native lifetime-counter trackers feed the in-memory StatStore, which the
		// push loop below flushes to the server.
		eventBus.register(counters);
		// Passive collection-log capture — reads the completion fraction on login
		// and scrapes whatever clog page the player opens themselves; the push loop
		// flushes it. Never opens the log or prompts (Jagex/Hub automation rules).
		clogCapture.reset();
		eventBus.register(clogCapture);
		// Achievement-state change gate starts empty, so the first push after a
		// (re)start always sends a full snapshot.
		achievementSync.reset();

		reschedulePushLoop();
		warnIfSlayerDisabled();
		log.debug("Chronicle started — slayer service: {}",
			eventCapture.hasSlayerService() ? "AVAILABLE" : "MISSING");

		// If the plugin is toggled on mid-session, catch the already-logged-in case.
		lastState = client.getGameState();
		if (lastState == GameState.LOGGED_IN)
		{
			pendingEnrolCheck = true;
			// The clog fraction varps normally arrive with the LOGGED_IN
			// transition, which has already happened — read them now.
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
		resetSeedBackoff();
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
		pendingEnrolCheck = false;
		// While we are unregistered no events reach the trackers, so the store stops
		// tracking reality — and the player may switch accounts before toggling us
		// back on. Forget both the totals and the fact that we ever seeded, so the
		// next login must re-seed from the server before it can push anything.
		countersSeeded = false;
		reseedFloor = false;   // a cleared store is zero-based again
		resetSeedBackoff();
		statStore.clear();
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

	/**
	 * On-task drop tagging needs RuneLite's Slayer plugin ENABLED. The
	 * {@code @PluginDependency} guarantees it's loaded (so its service binds),
	 * but a user could still toggle it off — in which case there's no active
	 * task to read, so nudge them via the side-panel status.
	 */
	private void warnIfSlayerDisabled()
	{
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (p instanceof SlayerPlugin)
				{
					if (!pluginManager.isPluginEnabled(p))
					{
						statusLine = "Turn on the Slayer plugin for on-task drop tagging.";
						refreshPanel();
						log.debug("Slayer plugin is disabled — on-task drop tagging is inactive.");
					}
					return;
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("slayer-enabled check failed", e);
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
		}
		else if (state == GameState.LOGIN_SCREEN && lastState == GameState.LOGGED_IN)
		{
			// Best-effort final push on logout. A world HOP is deliberately NOT
			// treated as a logout: it is the same account with the same totals, and
			// invalidating here would fire a re-seed GET that races the final POST —
			// the reader sees the last committed row, so a still-in-flight push means
			// the re-seed reads stale and then overwrites the session's counters.
			onLogout();
		}
		lastState = state;
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
		Player lp = client.getLocalPlayer();
		String name = lp != null ? lp.getName() : null;
		if (name == null || name.isEmpty())
		{
			return; // wait for the name to populate
		}
		pendingEnrolCheck = false;
		// RSProfile-scoped values only migrate with a profile active: adopt the
		// pre-rename group's enrolment token so cloud installs stay enrolled.
		if (configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN) == null)
		{
			String legacyTok = configManager.getRSProfileConfiguration("fitzgerald", KEY_TOKEN);
			if (legacyTok != null)
			{
				configManager.setRSProfileConfiguration(GROUP, KEY_TOKEN, legacyTok);
			}
		}
		// The journal always runs: remember whose account this is and load its
		// on-disk record so the record keeps accumulating across sessions. No
		// enrolment, no network — cloud sync below is additive when configured.
		localName = name;
		sessionStartMs = System.currentTimeMillis();
		if (!cloudActive())
		{
			statusLine = "Journaling locally — nothing leaves this computer.";
		}
		refreshPanel();
		final String who = name;
		executor.submit(() ->
		{
			localStore.load(localDir(), who);
			clientThread.invoke(this::refreshLocal);
		});
		if (cloudActive())
		{
			ensureEnrolled(name);
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
			if ("cloudSync".equals(key) || "serverBaseUrl".equals(key))
			{
				// Fold the running session into the journal and re-freeze its base
				// BEFORE clearing the counter store — the journal is always-on, so a
				// cloud toggle must never cost it the increments since the last flush.
				if (localName != null)
				{
					localStore.setTrackers(sessionView(), localName);
					localStore.rebase(localName);
					executor.submit(() -> localStore.flush(localDir()));
				}
				// The counter baseline means different things with cloud on or off (a
				// cloud session is seeded to server absolutes; a purely local one
				// counts from zero). Reset so a toggle can't carry one into the other.
				countersSeeded = false;
				statStore.clear();
				counters.reset();
			}
			// Turning cloud on re-runs the per-login branch so enrolment happens now.
			if (cloudActive() && client.getGameState() == GameState.LOGGED_IN)
			{
				pendingEnrolCheck = true;
			}
			refreshPanel();
		}
	}

	// ------------------------------------------------------------------
	// Enrolment
	// ------------------------------------------------------------------

	/** Runs on the client thread (from onGameTick). */
	private void ensureEnrolled(String name)
	{
		// Token override: paste an existing token to skip self-enrolment
		// (site owner / re-install / second device). It seeds the RSProfile
		// token slot so the rest of the flow is identical to a normal enrol.
		String override = trimToNull(config.manualToken());
		String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
		if (override != null && !override.equals(token))
		{
			configManager.setRSProfileConfiguration(GROUP, KEY_TOKEN, override);
			token = override;
		}
		if (token != null)
		{
			enrolledRsn = name;
			cachedToken = token;
			cachedName = name;
			statusLine = "Enrolled. Pushing on the next interval.";
			refreshPanel();
			// Pull the current lock / listing / pending-deletion state for the panel.
			refreshSelfServiceState();
			// Push straight away so a freshly-launched client isn't stale.
			pushCurrent();
			return;
		}

		statusLine = "Enrolling " + name + "…";
		refreshPanel();
		final String rsn = name;
		api.enroll(config.serverBaseUrl(), rsn, result -> handleEnrollResult(rsn, result));
	}

	/** Callback fires on an OkHttp thread. */
	private void handleEnrollResult(String rsn, ChronicleApiClient.EnrollResult result)
	{
		if (result.ok && result.token != null)
		{
			final String token = result.token;
			clientThread.invoke(() ->
			{
				configManager.setRSProfileConfiguration(GROUP, KEY_TOKEN, token);
				enrolledRsn = rsn;
				cachedToken = token;
				cachedName = rsn;
				statusLine = "Enrolled as " + rsn + ".";
				chat("Chronicle: enrolled " + rsn + " — your page is unlisted (reachable by "
					+ "its link, not in the public directory). Manage privacy in the side panel.");
				refreshPanel();
				refreshSelfServiceState();
				pushCurrent();
			});
		}
		else if (result.code == 409)
		{
			statusLine = "Already enrolled elsewhere — ask an admin to reissue the token.";
			chat("Chronicle: " + rsn + " is already enrolled. Ask an admin to reissue the token to move it here.");
			refreshPanel();
		}
		else if (result.code == 403)
		{
			statusLine = "Blocked from enrolment.";
			chat("Chronicle: enrolment for " + rsn + " was blocked.");
			refreshPanel();
		}
		else
		{
			statusLine = "Enrolment failed (" + result.code + ")"
				+ (result.error != null ? ": " + result.error : "") + ".";
			log.debug("enroll failed code={} err={}", result.code, result.error);
			refreshPanel();
		}
	}

	// ------------------------------------------------------------------
	// Harvest + push
	// ------------------------------------------------------------------

	/** Scheduled on the executor; hops to the client thread to read config. */
	private void scheduledPush()
	{
		// The journal refreshes every cycle; cloud pushes ride the same cadence
		// when configured. Order matters not — different sinks, same harvest.
		clientThread.invoke(this::refreshLocal);
		if (cloudActive())
		{
			clientThread.invoke(this::pushCurrent);
		}
	}

	/** Runs on the client thread. Harvests the current counters and pushes them. */
	private void pushCurrent()
	{
		// Hard stop for local mode: nothing in this method may touch the network.
		if (!cloudActive())
		{
			return;
		}
		// Two writers on one account corrupt the counters (each seeds on top
		// of the other's pushes) — refuse to be the second one.
		if (legacyPluginRunning())
		{
			statusLine = "The old Fitzgerald plugin is still enabled — disable it; "
				+ "two writers corrupt the counters.";
			refreshPanel();
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player lp = client.getLocalPlayer();
		String name = lp != null ? lp.getName() : null;
		if (name == null || name.isEmpty())
		{
			return;
		}
		// Stamp fires with the account's stable hash so the server can adopt an
		// in-game rename automatically (verified as the same account, not an alt).
		api.setAccountHash(client.getAccountHash());
		String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
		if (token == null)
		{
			return; // not enrolled yet
		}
		// Forward any accumulated skilling chat every tick (independent of the
		// counter seed — the server derives + owns these counters).
		flushSkillChat(token, name);
		// Flush any collection-log pages viewed since the last push (independent of
		// the counter seed; the server merges partial snapshots).
		if (clogCapture.isDirty())
		{
			api.pushClog(config.serverBaseUrl(), token, name, clogCapture.snapshot());
			clogCapture.clearDirty();
		}
		// Achievement state (quests / diaries / combat tasks) — whole-snapshot
		// sync, sent only when it differs from the last copy the server acked
		// (also independent of the counter seed; the server just keeps latest).
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
		// The server is the counter store. Seed our in-memory cache from it once
		// per login before pushing absolutes — otherwise we'd push a base of zero
		// and the server's regression guard would (correctly) reject it.
		if (!countersSeeded)
		{
			seedCounters(token, name);
			return;
		}
		Map<String, Integer> snapshot = harvest();
		if (snapshot.isEmpty())
		{
			return;   // nothing tracked yet on this account — nothing to push
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

	/**
	 * Maps the in-game account-type varbit (IRONMAN, 1777) to the server's account
	 * tag, so the profile's account_type stays in sync with the game with no manual
	 * tagging. Empty for a normal account — nothing to tag.
	 */
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

	/**
	 * Seed the in-memory counter cache from the server (once per login), then
	 * push. On failure we stay unseeded and retry on the next push tick, so a
	 * transient network blip never causes us to push an all-zero baseline.
	 */
	private void seedCounters(String token, String name)
	{
		if (seeding)
		{
			return;   // a seed fetch is already in flight
		}
		seeding = true;
		statusLine = "Syncing counters…";
		refreshPanel();
		api.fetchCounters(config.serverBaseUrl(), token, base -> clientThread.invoke(() ->
		{
			seeding = false;
			if (base == null)
			{
				scheduleSeedRetry();
				statusLine = "Counter sync failed — retrying shortly.";
				refreshPanel();
				return;   // stay unseeded; the backoff retry re-enters here
			}
			// Floor, never add: with a second writer on the same account (a
			// stray second install, another computer mid-session) additive
			// seeding stacks the server's copy of events this client also
			// counted — a one-way ratchet minting phantom increments every
			// seed/push interleaving. Flooring costs at most the few seconds
			// counted before this callback returned.
			statStore.seedFloor(base);
			reseedFloor = false;
			// The journal adopts the server's history too (per-key floor): a fresh
			// install on an account with a cloud record starts complete instead of
			// from zero, and two computers converge through the server's union.
			// Stashed rather than applied — the journal's own async load may still
			// be in flight; refreshLocal (which always runs after the load, and on
			// every push interval) applies it once the store is ready.
			pendingServerFloor = base;
			// The drop ledger and the feed adopt the same way. Each callback
			// nudges refreshLocal immediately — a stash must never sit waiting
			// for the next five-minute interval to become visible.
			api.fetchDropLedger(config.serverBaseUrl(), name, ledger ->
			{
				pendingDropsAdopt = ledger;
				clientThread.invoke(this::refreshLocal);
			});
			// The feed's first cloud adoption goes DEEP — the server holds
			// milestones back to its first sync (2023 on the reference
			// instance) and one 300-row window would orphan the early years.
			boolean deepFeed = !"true".equals(
				configManager.getConfiguration(GROUP, "cloudFeedDeepImported"));
			api.fetchFeed(config.serverBaseUrl(), name, deepFeed ? 5000 : 300, events ->
			{
				pendingFeedDeep = deepFeed && events != null;
				pendingFeedAdopt = events;
				clientThread.invoke(this::refreshLocal);
			});
			// The xp archive adopts once too — v2 pulls the server's DAILY
			// series in one call (the snapshots endpoint, token-authed for
			// full depth), so Day and Week history answer everywhere the
			// archive has days, not just month ends.
			if (!"true".equals(configManager.getConfiguration(GROUP, "cloudHistoryImported2")))
			{
				api.fetchServerHistoryDaily(config.serverBaseUrl(), name, token, snaps ->
				{
					if (snaps != null && !snaps.isEmpty())
					{
						pendingHistoryAdopt = snaps;
						clientThread.invoke(this::refreshLocal);
					}
				});
			}
			// The whole per-source item ledger floors into the local bags each
			// login — item questions answer locally, no drill-time fetches.
			api.fetchAllSourceItems(config.serverBaseUrl(), name, rows ->
			{
				pendingBagsAdopt = rows;
				clientThread.invoke(this::refreshLocal);
			});
			// The uncollected ledger the server has kept since untaken capture
			// first shipped — the Left behind lens presents it at last.
			api.fetchUntaken(config.serverBaseUrl(), name, ledger ->
			{
				pendingUntakenAdopt = ledger;
				clientThread.invoke(this::refreshLocal);
			});
			// Server-priced consumables, so Food and Potions rows can say what
			// the habit cost.
			api.fetchConsumables(config.serverBaseUrl(), name, rows ->
			{
				pendingConsumablesAdopt = rows;
				clientThread.invoke(this::refreshLocal);
			});
			clientThread.invoke(this::refreshLocal);   // the counter floor, likewise
			countersSeeded = true;
			resetSeedBackoff();
			enrolledRsn = name;
			statusLine = "Enrolled. Pushing on the next interval.";
			refreshPanel();
			pushCurrent();
		}));
	}

	/** Re-run the push path (which re-enters the seed) after a short backoff. */
	private synchronized void scheduleSeedRetry()
	{
		if (seedRetryTask != null && !seedRetryTask.isDone())
		{
			return;   // a retry is already queued
		}
		seedRetryTask = executor.schedule(this::scheduledPush, seedRetryDelaySec, TimeUnit.SECONDS);
		seedRetryDelaySec = Math.min(SEED_RETRY_MAX_SEC, seedRetryDelaySec * 2);
	}

	private synchronized void resetSeedBackoff()
	{
		seedRetryDelaySec = SEED_RETRY_MIN_SEC;
		if (seedRetryTask != null)
		{
			seedRetryTask.cancel(false);
			seedRetryTask = null;
		}
	}

	/** Runs on the client thread (GameStateChanged). Best-effort final push. */
	private void onLogout()
	{
		// The journal always closes out the session: capture the final counters
		// (session view — the cloud-seeded share is subtracted so the journal's
		// additive lifetime base can't double-count), write, end the session so a
		// different account logging in next can't record onto this model.
		if (localName != null)
		{
			localStore.setTrackers(sessionView(), localName);
			appendHistoryBaseline();
			recordSessionLine();
		}
		executor.submit(() -> localStore.flush(localDir()));
		localStore.endSession();
		eventCapture.resetSessionFlags();
		pendingServerFloor = null;   // account boundary — never floor the next login's journal
		pendingDropsAdopt = null;
		pendingFeedAdopt = null;
		pendingHistoryAdopt = null;
		pendingBagsAdopt = null;
		pendingUntakenAdopt = null;
		pendingConsumablesAdopt = null;
		pendingFeedDeep = false;
		// Re-seed from the server on the next login (another device may have
		// advanced the totals). The final push below still uses the in-memory
		// snapshot we accumulated this session.
		//
		// Only a SEEDED session holds true absolutes. An unseeded one holds this
		// session's increments counted up from nothing, so pushing it would rewrite
		// the server's totals downward — which the regression guard would (rightly)
		// reject with a 409 and freeze the stream pending admin review.
		boolean pushable = countersSeeded;
		countersSeeded = false;
		reseedFloor = false;   // the store is cleared below — zero-based again
		resetSeedBackoff();
		// Best-effort final flush of skilling chat before we lose the session buffer.
		if (cachedToken != null && cachedName != null)
		{
			flushSkillChat(cachedToken, cachedName);
		}
		// Same rule as statStore.clear() below: these chat lines belong to the
		// account that just logged out. The flush above froze its own copies, so
		// clearing here can't lose that request — it only stops a failed flush
		// from retrying the batch under the NEXT account's name.
		skillBuffer.clearAll();
		// Account boundary for the achievement gate too: the next login must
		// sync its own snapshot even if it happens to serialize identically.
		achievementSync.reset();
		Map<String, Integer> fresh = harvest();
		if (!fresh.isEmpty())
		{
			cachedSnapshot = fresh;
		}
		// These totals belong to the account that just logged out. Drop them here so
		// a DIFFERENT account logging in next can never inherit them: without this
		// the store survives, and any path that reaches a push before re-seeding
		// would file one player's lifetime counters under another's name.
		statStore.clear();
		if (!cloudActive() || !pushable || cachedToken == null || cachedName == null
			|| cachedSnapshot == null || cachedSnapshot.isEmpty())
		{
			return;
		}
		api.pushStats(config.serverBaseUrl(), cachedToken, cachedName, cachedSnapshot, cachedAccountType,
			null, this::onPushResult);   // logout flush: client unreadable, skip skills
	}

	/**
	 * Flush accumulated skilling chat lines to the server (which derives the
	 * counters). Frozen-batch + server dedup = exactly-once apply: on ack we clear
	 * the batch; on failure we keep it and retry the identical batch next tick.
	 */
	private void flushSkillChat(String token, String name)
	{
		chronicle.counters.SkillChatBuffer.Batch batch = skillBuffer.beginFlush();
		if (batch == null || batch.isEmpty())
		{
			return;
		}
		api.forwardSkillChat(config.serverBaseUrl(), token, name, batch.id, batch.chat, batch.actions,
			code -> {
				// Retire the batch on success (200) or a permanent rejection
				// (204 name-mismatch, or any 4xx except 429) so the pipeline
				// advances and pending can't grow unbounded; keep it to retry
				// only on network failure (-1), 5xx, or 429.
				boolean terminal = code == 200 || code == 204
					|| (code >= 400 && code < 500 && code != 429);
				if (terminal)
				{
					skillBuffer.ackFlush(batch.id);
				}
			});
	}

	private void onPushResult(ChronicleApiClient.PushResult result)
	{
		if (result.ok)
		{
			statusLine = "Last push OK (" + result.changed + " changed) at " + nowClock() + ".";
			log.debug("push ok: {} accepted, {} changed", result.accepted, result.changed);
			// Server-MINTED counters (typed skilling: shafts cut, logs typed by
			// wood…) only exist server-side — without this, they sat frozen in
			// the panel until the next login's floor. Re-fetch after each push
			// so they flow at the push cadence.
			String tok = cachedToken;
			String who = cachedName;
			if (tok != null && who != null)
			{
				api.fetchCounters(config.serverBaseUrl(), tok, base ->
				{
					if (base != null)
					{
						pendingServerFloor = base;
						clientThread.invoke(this::refreshLocal);
					}
				});
			}
		}
		else if (result.code == 409)
		{
			// Regression guard: the server holds higher totals than we pushed
			// (usually a second device advancing them). Re-seed with a floor merge
			// so our next push can never regress the server again — self-healing
			// once an admin unfreezes a flagged stream, and preventive before one.
			countersSeeded = false;
			reseedFloor = true;
			resetSeedBackoff();
			scheduleSeedRetry();
			statusLine = "Server totals are ahead — resyncing at " + nowClock() + ".";
			log.debug("push 409 — scheduling floor re-seed");
		}
		else
		{
			statusLine = "Last push failed (" + result.code + ")"
				+ (result.error != null ? ": " + result.error : "") + " at " + nowClock() + ".";
			log.debug("push failed code={} err={}", result.code, result.error);
		}
		refreshPanel();
	}

	/**
	 * The current in-memory counter snapshot — absolute totals for this session,
	 * seeded from the server on login and incremented natively by the counter
	 * trackers ({@link chronicle.counters.ChronicleCounters}). No
	 * RuneLite config is read or written for counters.
	 */
	Map<String, Integer> harvest()
	{
		return statStore.pushable();
	}

	/**
	 * Per-skill level + XP for the live push, so the site profile reflects current
	 * stats between daily hiscores snapshots. Keyed by the lowercase skill name to
	 * match the server's snapshot shape, plus an "overall" total. Client thread only
	 * (called from pushCurrent) — the skill accessors require it.
	 */
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
			skills.add(s.name().toLowerCase(), o);
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

	void actionReEnrol()
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				chat("Chronicle: log in first, then re-enrol.");
				return;
			}
			Player lp = client.getLocalPlayer();
			String name = lp != null ? lp.getName() : null;
			if (name == null || name.isEmpty())
			{
				return;
			}
			String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
			if (token != null)
			{
				chat("Chronicle: " + name + " already has a token on this client. "
					+ "Ask an admin to reissue if you need to move it.");
				return;
			}
			pendingEnrolCheck = false;
			ensureEnrolled(name);
		});
	}

	void actionPushNow()
	{
		if (!cloudActive())
		{
			chat("Chronicle: enable cloud sync (and set a server) under Advanced in the plugin settings first.");
			return;
		}
		clientThread.invoke(this::pushCurrent);
	}

	/** True while the Plugin Hub ancestor of this plugin is ALSO enabled —
	 *  the dual-writer state that ratchets the cloud counters upward. */
	private boolean legacyPluginRunning()
	{
		for (net.runelite.client.plugins.Plugin p : pluginManager.getPlugins())
		{
			if (p != this && p.getClass().getName().startsWith("gg.fitzgerald.")
				&& pluginManager.isPluginEnabled(p))
			{
				return true;
			}
		}
		return false;
	}

	String serverBaseUrl()
	{
		return config.serverBaseUrl().replaceAll("/+$", "");
	}

	String enrolledRsn()
	{
		return enrolledRsn;
	}

	/** Cloud sync active: opted in AND pointed at a server. The journal itself
	 *  always runs while the plugin is on — only the network needs a gate. */
	boolean cloudActive()
	{
		return config.cloudSync() && !config.serverBaseUrl().trim().isEmpty();
	}

	// ── Panel-facing reads ─────────────────────────────────────────────

	/** Lifetime counters as the journal knows them (base + session, floored). */
	Map<String, Long> lifetimeCounters()
	{
		return localStore.trackersSnapshot();
	}

	/** This session's own increments (max-type keys as absolutes). */
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

	void fetchSourceItems(String source,
		java.util.function.Consumer<java.util.List<ChronicleApiClient.LedgerItem>> onDone)
	{
		String rsn = displayRsn();
		if (!cloudActive() || rsn == null || rsn.isEmpty())
		{
			onDone.accept(null);
			return;
		}
		api.fetchSourceItems(config.serverBaseUrl(), rsn, source, onDone);
	}

	/** The cloud's task-by-task slayer journey (panel fetches on first open). */
	void fetchSlayerJourney(
		java.util.function.Consumer<ChronicleApiClient.SlayerJourney> onDone)
	{
		String rsn = displayRsn();
		if (!cloudActive() || rsn == null || rsn.isEmpty())
		{
			onDone.accept(null);
			return;
		}
		api.fetchSlayerJourney(config.serverBaseUrl(), rsn, onDone);
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

	java.util.List<LocalStore.ClogPage> clogPages()
	{
		return localStore.clogPages();
	}

	java.util.TreeMap<java.time.LocalDate, HistoryLog.Baseline> historyBaselines()
	{
		String rsn = localName;
		return rsn != null ? historyLog.read(localDir(), rsn)
			: new java.util.TreeMap<>();
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

	/** The dryness ledger (panel fetches once per session on demand). */
	void fetchGrinds(java.util.function.Consumer<java.util.List<ChronicleApiClient.GrindRow>> onDone)
	{
		String rsn = displayRsn();
		if (!cloudActive() || rsn == null || rsn.isEmpty())
		{
			onDone.accept(null);
			return;
		}
		api.fetchGrinds(config.serverBaseUrl(), rsn, onDone);
	}

	/** True once this session produced an on-task slayer kill. */
	boolean slayerSeenThisSession()
	{
		return eventCapture.slayerSeenThisSession();
	}

	private volatile long sessionStartMs;

	/**
	 * The logout diary line: one dated feed entry closing the session — the
	 * habit of a journal without the labour of one. Local-only, never pushed;
	 * skipped when the session was too slight to be worth a line.
	 */
	private void recordSessionLine()
	{
		long mins = sessionStartMs > 0
			? (System.currentTimeMillis() - sessionStartMs) / 60_000 : 0;
		Map<String, Integer> sess = sessionView();
		long xp = sess.getOrDefault("totalXpGained", 0);
		int drops = localStore.sessionLoots();
		long dropsGp = localStore.sessionLootValue();
		long consumedGp = sess.getOrDefault("consumedValue", 0);
		if (mins < 5 && xp == 0 && drops == 0)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("minutes", mins);
		data.addProperty("xp", xp);
		data.addProperty("drops", drops);
		data.addProperty("dropsGp", dropsGp);
		data.addProperty("consumedGp", consumedGp);
		localStore.record("SESSION", data, localName);
	}

	long[] sessionUntakenTally()
	{
		return localStore.sessionUntakenTally();
	}

	java.util.List<LocalStore.SourceRow> sessionSourceRows()
	{
		return localStore.sessionSourceRows();
	}

	/**
	 * Session counters shaped for DISPLAY: peak keys (highest hit and friends)
	 * only appear when this session actually beat the seeded baseline — the
	 * journal-write path keeps its absolutes, but the panel must never show a
	 * lifetime peak as a session feat.
	 */
	Map<String, Integer> sessionDisplayCounters()
	{
		Map<String, Integer> out = new java.util.HashMap<>(sessionView());
		Map<String, Integer> seeded = statStore.seededBaseline();
		for (String key : LocalStore.MAX_KEYS)
		{
			Integer val = out.get(key);
			if (val != null && val <= seeded.getOrDefault(key, 0))
			{
				out.remove(key);
			}
		}
		return out;
	}

	net.runelite.client.game.ItemManager items()
	{
		return localStore.items();
	}

	/** RSN to show in the panel: the enrolled name when syncing, else the local one. */
	String displayRsn()
	{
		return cloudActive() && enrolledRsn != null && !enrolledRsn.isEmpty() ? enrolledRsn : localName;
	}

	/**
	 * This session's own counter increments — the totals snapshot minus the
	 * server-seeded share — for the local journal's additive lifetime base.
	 * Max-type keys pass through as absolutes (the journal takes their max).
	 * With cloud off nothing is ever seeded, so this IS the plain harvest.
	 */
	Map<String, Integer> sessionView()
	{
		Map<String, Integer> abs = harvest();
		Map<String, Integer> seeded = statStore.seededBaseline();
		Map<String, Integer> out = new java.util.HashMap<>(abs.size());
		for (Map.Entry<String, Integer> en : abs.entrySet())
		{
			if (LocalStore.MAX_KEYS.contains(en.getKey()))
			{
				out.put(en.getKey(), en.getValue());
				continue;
			}
			int sess = en.getValue() - seeded.getOrDefault(en.getKey(), 0);
			if (sess > 0)
			{
				out.put(en.getKey(), sess);
			}
		}
		return out;
	}

	private static File localDir()
	{
		File dir = new File(net.runelite.client.RuneLite.RUNELITE_DIR, "chronicle");
		// One-shot: adopt the pre-rename journal directory. Falls back to the
		// old directory if the rename fails (locked file, odd filesystem) so an
		// existing journal is never abandoned mid-session.
		File legacy = new File(net.runelite.client.RuneLite.RUNELITE_DIR, "fitzgerald");
		if (legacy.isDirectory() && !dir.exists() && !legacy.renameTo(dir))
		{
			return legacy;
		}
		return dir;
	}

	/** Copy the always-current character sheet into the local store. Client thread. */
	private void gatherCharacter()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player lp = client.getLocalPlayer();
		String name = lp != null ? lp.getName() : null;
		if (name == null || name.isEmpty())
		{
			return;
		}
		localStore.setCharacter(name, accountTypeTag(client.getVarbitValue(VarbitID.IRONMAN)),
			harvestSkills(), lp.getCombatLevel(), clogCapture.snapshot(), achievementSync.snapshot());
		// The client-computed counters fold into the lifetime trackers — as the
		// SESSION view, never raw absolutes: harvest() still contains the seeded
		// server share, and writing it here stacked base+seeded+delta into the
		// journal until the later setTrackers pass corrected it (a transient
		// doubling any read in between could see). The server-derived
		// per-resource skilling counters aren't available offline, so they
		// don't appear in local mode.
		localStore.setTrackers(sessionView(), name);
	}

	/** Local-mode equivalent of a push: refresh the sheet, then rewrite the page. */
	private void refreshLocal()
	{
		gatherCharacter();
		Map<String, Integer> floor = pendingServerFloor;
		if (floor != null && localName != null && localStore.isReadyFor(localName))
		{
			pendingServerFloor = null;
			localStore.floorTrackers(floor, localName);
		}
		java.util.List<ChronicleApiClient.LedgerSource> adopt = pendingDropsAdopt;
		if (adopt != null && localName != null && localStore.isReadyFor(localName))
		{
			pendingDropsAdopt = null;
			localStore.floorDropSources(adopt, localName);
		}
		java.util.List<ChronicleApiClient.SourceItemRow> bags = pendingBagsAdopt;
		if (bags != null && localName != null && localStore.isReadyFor(localName))
		{
			pendingBagsAdopt = null;
			localStore.floorSourceBags(bags, localName);
		}
		ChronicleApiClient.UntakenLedger untaken = pendingUntakenAdopt;
		if (untaken != null && localName != null && localStore.isReadyFor(localName))
		{
			pendingUntakenAdopt = null;
			localStore.floorUntaken(untaken.bySource, untaken.byItem, localName);
			refreshPanel();
		}
		java.util.List<String[]> consum = pendingConsumablesAdopt;
		if (consum != null && localName != null && localStore.isReadyFor(localName))
		{
			pendingConsumablesAdopt = null;
			localStore.floorConsumableValues(consum, localName);
		}
		java.util.List<ChronicleApiClient.FeedEvent> feed = pendingFeedAdopt;
		if (feed != null && localName != null && localStore.isReadyFor(localName))
		{
			pendingFeedAdopt = null;
			localStore.adoptFeed(feed, localName);
			if (pendingFeedDeep)
			{
				pendingFeedDeep = false;
				configManager.setConfiguration(GROUP, "cloudFeedDeepImported", true);
			}
			refreshPanel();
		}
		java.util.List<ChronicleApiClient.WomSnapshot> histAdopt = pendingHistoryAdopt;
		if (histAdopt != null && localName != null)
		{
			pendingHistoryAdopt = null;
			final String rsn = localName;
			executor.submit(() ->
			{
				// The archive walk returns month-end absolutes oldest-first;
				// collapse to one line per date through the same door the WOM
				// import uses (readers take last-per-date, so the streams merge).
				java.util.TreeMap<String, Map<String, Long>> byDate = new java.util.TreeMap<>();
				for (ChronicleApiClient.WomSnapshot snap : histAdopt)
				{
					byDate.put(snap.date, snap.skills);
				}
				for (Map.Entry<String, Map<String, Long>> e : byDate.entrySet())
				{
					historyLog.appendImported(localDir(), rsn, e.getKey(), e.getValue());
				}
				// the flag must match what the gate READS — a mismatch here once
				// made this one-shot re-run (and re-append) every login
				configManager.setConfiguration(GROUP, "cloudHistoryImported2", true);
				if (!byDate.isEmpty())
				{
					chat("Chronicle: adopted " + byDate.size()
						+ " months of history from your cloud record.");
				}
				clientThread.invoke(this::refreshPanel);
			});
		}
		if (localName != null)
		{
			localStore.setTrackers(sessionView(), localName);
			// The calendar spine: one closing baseline per day, appended — the
			// History tab and the year cards are subtractions over this stream.
			if (historyLog.dayRolledOver())
			{
				appendHistoryBaseline();
			}
		}
		// First run only: adopt the core Loot Tracker's lifetime record — the
		// one LOCAL archive predating any server. Reading the ACTIVE RS
		// profile's keys keeps it own-account by construction (league and alt
		// profiles have different keys). Purely local, no network.
		if (localName != null && localStore.isReadyFor(localName)
			&& !"true".equals(configManager.getConfiguration(GROUP, "lootTrackerImported")))
		{
			importLootTracker();
		}
		executor.submit(() -> localStore.flush(localDir()));
	}

	private static final com.google.gson.Gson LOOT_GSON = new com.google.gson.Gson();

	/** Client thread (ItemManager pricing). One-shot; flag set on success. */
	private void importLootTracker()
	{
		java.util.List<String> keys;
		try
		{
			keys = configManager.getRSProfileConfigurationKeys(
				"loottracker", configManager.getRSProfileKey(), "drops_");
		}
		catch (RuntimeException e)
		{
			log.debug("loot tracker key scan failed", e);
			return;
		}
		if (keys == null)
		{
			return;
		}
		java.util.List<LocalStore.LootSeed> seeds = new java.util.ArrayList<>();
		long events = 0;
		for (String key : keys)
		{
			String raw = configManager.getConfiguration(
				"loottracker", configManager.getRSProfileKey(), key);
			if (raw == null || raw.isEmpty())
			{
				continue;
			}
			try
			{
				JsonObject o = LOOT_GSON.fromJson(raw, JsonObject.class);
				String source = o.has("name") ? o.get("name").getAsString() : null;
				if (source == null || source.isEmpty())
				{
					continue;
				}
				int kills = o.has("kills") ? o.get("kills").getAsInt() : 0;
				long first = o.has("first") ? o.get("first").getAsLong() : 0;
				long last = o.has("last") ? o.get("last").getAsLong() : 0;
				java.util.List<LocalStore.BagItem> items = new java.util.ArrayList<>();
				if (o.has("drops") && o.get("drops").isJsonArray())
				{
					com.google.gson.JsonArray arr = o.getAsJsonArray("drops");
					for (int i = 0; i + 1 < arr.size(); i += 2)
					{
						int id = arr.get(i).getAsInt();
						long qty = arr.get(i + 1).getAsLong();
						if (id <= 0 || qty <= 0)
						{
							continue;
						}
						int canon = localStore.items().canonicalize(id);
						String name = localStore.items().getItemComposition(canon).getName();
						long each = localStore.items().getItemPrice(canon);
						items.add(new LocalStore.BagItem(canon, name, qty,
							Math.max(0, each) * qty));
					}
				}
				seeds.add(new LocalStore.LootSeed(source, kills, first, last, items));
				events += kills;
			}
			catch (RuntimeException e)
			{
				log.debug("loot tracker record parse failed: {}", key, e);
			}
		}
		if (!seeds.isEmpty())
		{
			localStore.floorLootTracker(seeds, localName);
			chat("Chronicle: adopted " + seeds.size() + " sources · "
				+ String.format(java.util.Locale.UK, "%,d", events)
				+ " loot events from your Loot Tracker.");
			refreshPanel();
		}
		configManager.setConfiguration(GROUP, "lootTrackerImported", true);
	}

	/** Client thread. Appends today's closing skills+counters baseline. */
	private void appendHistoryBaseline()
	{
		final String rsn = localName;
		if (rsn == null)
		{
			return;
		}
		final Map<String, Integer> skills = new java.util.HashMap<>();
		JsonObject sk = harvestSkills();
		if (sk != null)
		{
			for (Map.Entry<String, com.google.gson.JsonElement> e : sk.entrySet())
			{
				if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("xp"))
				{
					skills.put(e.getKey(), e.getValue().getAsJsonObject().get("xp").getAsInt());
				}
			}
		}
		final Map<String, Long> counters = localStore.trackersSnapshot();
		executor.submit(() -> historyLog.append(localDir(), rsn, skills, counters));
	}

	String statusLine()
	{
		return statusLine;
	}

	boolean pageLocked()
	{
		return pageLocked;
	}

	boolean publicListed()
	{
		return publicListed;
	}

	/** Epoch-seconds a scheduled deletion will fire, or null if none is pending. */
	Long deletePendingTs()
	{
		return deletePendingTs;
	}

	// ------------------------------------------------------------------
	// Self-service profile management (panel-invoked; token-authed)
	// ------------------------------------------------------------------

	/** Resolve this account's token + in-game name on the client thread, then run
	 *  the action off it. Chats a nudge and does nothing if not logged in / enrolled. */
	private void withOwner(BiConsumer<String, String> action)
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				chat("Chronicle: log in first.");
				return;
			}
			Player lp = client.getLocalPlayer();
			String name = lp != null ? lp.getName() : null;
			String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
			if (name == null || name.isEmpty() || token == null)
			{
				chat("Chronicle: this account isn't enrolled yet.");
				return;
			}
			action.accept(token, name);
		});
	}

	/** Pull the current lock / listing / pending-deletion state into the panel. */
	void refreshSelfServiceState()
	{
		withOwner((token, name) -> api.fetchState(config.serverBaseUrl(), token, state ->
			clientThread.invoke(() ->
			{
				if (state != null)
				{
					applyState(state);
					refreshPanel();
				}
			})));
	}

	private void applyState(JsonObject state)
	{
		if (state.has("page_locked"))
		{
			pageLocked = state.get("page_locked").getAsBoolean();
		}
		if (state.has("public_listed"))
		{
			publicListed = state.get("public_listed").getAsBoolean();
		}
		deletePendingTs = state.has("delete_scheduled_ts") && !state.get("delete_scheduled_ts").isJsonNull()
			? state.get("delete_scheduled_ts").getAsLong() : null;
	}

	/** Set ({@code password} non-empty) or clear (empty) the page-view password. */
	void actionSetLock(String password)
	{
		withOwner((token, name) -> api.setLock(config.serverBaseUrl(), token, name, password, reply ->
			clientThread.invoke(() ->
			{
				if (reply == null)
				{
					chat("Chronicle: couldn't reach the server — lock unchanged.");
					return;
				}
				pageLocked = reply.has("locked") && reply.get("locked").getAsBoolean();
				chat(pageLocked
					? "Chronicle: your page is now locked — viewers need the password."
					: "Chronicle: page lock removed.");
				refreshPanel();
			})));
	}

	void actionSetPublic(boolean listed)
	{
		withOwner((token, name) -> api.setVisibility(config.serverBaseUrl(), token, name, listed, reply ->
			clientThread.invoke(() ->
			{
				if (reply == null)
				{
					chat("Chronicle: couldn't reach the server — listing unchanged.");
					return;
				}
				publicListed = reply.has("public") && reply.get("public").getAsBoolean();
				chat(publicListed
					? "Chronicle: your page is now listed in the public directory."
					: "Chronicle: your page is unlisted (reachable by direct link only).");
				refreshPanel();
			})));
	}

	void actionScheduleDelete(boolean cancel)
	{
		withOwner((token, name) -> api.scheduleDelete(config.serverBaseUrl(), token, name, cancel, reply ->
			clientThread.invoke(() ->
			{
				if (reply == null)
				{
					chat("Chronicle: couldn't reach the server — nothing changed.");
					return;
				}
				boolean scheduled = reply.has("scheduled") && reply.get("scheduled").getAsBoolean();
				deletePendingTs = scheduled && reply.has("delete_ts") ? reply.get("delete_ts").getAsLong() : null;
				chat(scheduled
					? "Chronicle: deletion scheduled. Your data is removed in 7 days unless you cancel."
					: "Chronicle: scheduled deletion cancelled — your data stays.");
				refreshPanel();
			})));
	}

	/** Download the account's full export and write it to a file in the RuneLite dir. */
	void actionExport()
	{
		withOwner((token, name) -> api.exportData(config.serverBaseUrl(), token, name, json ->
		{
			// Runs on the OkHttp callback thread: keep the (potentially large)
			// disk write OFF the client/game thread so it can't stutter a frame.
			if (json == null)
			{
				chat("Chronicle: export failed — couldn't reach the server.");
				return;
			}
			try
			{
				// Hub rule: all plugin file I/O stays inside a plugin-specific
				// subdirectory of .runelite — never the .runelite root.
				File dir = localDir();
				dir.mkdirs();
				File out = new File(dir,
					"export-" + name.replaceAll("[^A-Za-z0-9]", "_")
						+ "-" + System.currentTimeMillis() + ".json");
				Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
				chat("Chronicle: data exported to " + out.getAbsolutePath());
			}
			catch (Exception e)
			{
				log.debug("export write failed", e);
				chat("Chronicle: export downloaded but couldn't be saved to disk.");
			}
		}));
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
				// Chat unavailable (e.g. not logged in) — panel still shows status.
			}
		});
	}

	private static String nowClock()
	{
		return java.time.LocalTime.now().withNano(0).toString();
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

	/** Small programmatic icon so the repo ships no binary assets. */
	private static BufferedImage buildIcon()
	{
		// A small open book — the Chronicle. Distinct from every text-badge
		// icon on the rail (and from the old plugin's "F", so a dev client
		// running both is never ambiguous).
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
