/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin;

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
	name = "Fitzgerald.gg",
	description = "Tracks your OSRS activity — loot, levels, kill counts, collection log, "
		+ "clues, quests, diaries, combat achievements, slayer, pets, deaths and lifetime "
		+ "counters. Cloud mode (the default) sends it to your profile on fitzgerald.gg, a "
		+ "third-party server operated by the plugin author; Local mode keeps everything on your "
		+ "own computer and sends nothing. Off until you enable it; screenshots are a separate opt-in.",
	tags = {"fitzgerald", "stats", "tracker", "loot", "slayer", "external", "collection", "osrs"}
)
// The Slayer plugin's service supplies the active task so we can tag on-task
// drops at the kill. Declaring it a dependency guarantees it's loaded (and its
// service bound) before us — otherwise the on-task stamp silently no-ops.
@PluginDependency(SlayerPlugin.class)
public class FitzgeraldPlugin extends Plugin
{
	static final String GROUP = FitzgeraldConfig.GROUP; // "fitzgerald"
	static final String KEY_TOKEN = "token";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private FitzgeraldConfig config;

	@Inject
	private FitzgeraldApiClient api;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private FitzgeraldEventCapture eventCapture;

	@Inject
	private gg.fitzgerald.plugin.counters.FitzgeraldCounters counters;

	@Inject
	private gg.fitzgerald.plugin.counters.StatStore statStore;

	@Inject
	private gg.fitzgerald.plugin.counters.SkillChatBuffer skillBuffer;

	@Inject
	private ClogCapture clogCapture;

	@Inject
	private AchievementSync achievementSync;

	@Inject
	private LocalStore localStore;

	private FitzgeraldPanel panel;
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
	FitzgeraldConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FitzgeraldConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new FitzgeraldPanel(this);
		navButton = NavigationButton.builder()
			.tooltip("Fitzgerald.gg")
			.icon(buildIcon())
			.priority(9)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		refreshPanel();

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
		log.info("Fitzgerald.gg v2 started — slayer service: {}",
			eventCapture.hasSlayerService() ? "AVAILABLE" : "MISSING");

		// If the plugin is toggled on mid-session, catch the already-logged-in case.
		lastState = client.getGameState();
		if (lastState == GameState.LOGGED_IN)
		{
			pendingEnrolCheck = true;
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
		if (!config.enabled())
		{
			return;
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
						log.info("Slayer plugin is disabled — on-task drop tagging is inactive.");
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
		if (!config.enabled() || !pendingEnrolCheck)
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
		if (cloudActive())
		{
			ensureEnrolled(name);
		}
		else if (localMode())
		{
			// No enrolment, no network. Remember whose account this is, load their
			// on-disk record so drops keep accumulating across sessions, then write
			// the first page for this login.
			localName = name;
			statusLine = "Local mode — your data stays on this computer.";
			refreshPanel();
			final String who = name;
			executor.submit(() ->
			{
				localStore.load(localDir(), who);
				clientThread.invoke(this::refreshLocal);
			});
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
		if ("pushIntervalMinutes".equals(key) || "enabled".equals(key) || "syncMode".equals(key))
		{
			reschedulePushLoop();
			if ("syncMode".equals(key))
			{
				// The session-counter baseline means different things per mode (a cloud
				// session is seeded to server absolutes; a local one counts from zero).
				// Reset it so a switch can't carry one mode's baseline into the other.
				countersSeeded = false;
				statStore.clear();
				counters.reset();
			}
			// Turning on, or switching mode, re-runs the per-login branch (cloud enrol
			// vs. local name-capture) for the newly-selected mode.
			if (("enabled".equals(key) || "syncMode".equals(key)) && config.enabled()
				&& client.getGameState() == GameState.LOGGED_IN)
			{
				pendingEnrolCheck = true;
			}
			// Reflect the change immediately: the off prompt, the cloud UI, and the
			// pared-back local UI are all driven by the panel's mode read.
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
	private void handleEnrollResult(String rsn, FitzgeraldApiClient.EnrollResult result)
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
				chat("Fitzgerald.gg: enrolled " + rsn + " — your page is unlisted (reachable by "
					+ "its link, not in the public directory). Manage privacy in the side panel.");
				refreshPanel();
				refreshSelfServiceState();
				pushCurrent();
			});
		}
		else if (result.code == 409)
		{
			statusLine = "Already enrolled elsewhere — ask an admin to reissue the token.";
			chat("Fitzgerald.gg: " + rsn + " is already enrolled. Ask an admin to reissue the token to move it here.");
			refreshPanel();
		}
		else if (result.code == 403)
		{
			statusLine = "Blocked from enrolment.";
			chat("Fitzgerald.gg: enrolment for " + rsn + " was blocked.");
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
		if (cloudActive())
		{
			clientThread.invoke(this::pushCurrent);
		}
		else if (localMode())
		{
			// Same cadence, different sink: refresh the on-disk page instead of pushing.
			clientThread.invoke(this::refreshLocal);
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
			if (reseedFloor)
			{
				statStore.seedFloor(base);
				reseedFloor = false;
			}
			else
			{
				statStore.seedAdditive(base);
			}
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
		if (localMode())
		{
			// Capture this session's final counters (statStore is cleared further down),
			// write, then close the session so a different account logging in next can't
			// record onto this model.
			if (localName != null)
			{
				localStore.setTrackers(harvest(), localName);
			}
			executor.submit(() -> localStore.flush(localDir()));
			localStore.endSession();
			// Fall through: the cloud bookkeeping below clears the (unused-in-local)
			// counter stores too, and its final push is already gated on cloudActive.
		}
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
		gg.fitzgerald.plugin.counters.SkillChatBuffer.Batch batch = skillBuffer.beginFlush();
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

	private void onPushResult(FitzgeraldApiClient.PushResult result)
	{
		if (result.ok)
		{
			statusLine = "Last push OK (" + result.changed + " changed) at " + nowClock() + ".";
			log.debug("push ok: {} accepted, {} changed", result.accepted, result.changed);
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
	 * trackers ({@link gg.fitzgerald.plugin.counters.FitzgeraldCounters}). No
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
				chat("Fitzgerald.gg: log in first, then re-enrol.");
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
				chat("Fitzgerald.gg: " + name + " already has a token on this client. "
					+ "Ask an admin to reissue if you need to move it.");
				return;
			}
			pendingEnrolCheck = false;
			ensureEnrolled(name);
		});
	}

	void actionPushNow()
	{
		// The config text promises that nothing is pushed while the master switch is
		// off. A panel button that transmits anyway would make that a lie.
		if (!config.enabled())
		{
			chat("Fitzgerald.gg: turn on Enabled in the plugin settings first.");
			return;
		}
		clientThread.invoke(this::pushCurrent);
	}

	String serverBaseUrl()
	{
		return config.serverBaseUrl().replaceAll("/+$", "");
	}

	String enrolledRsn()
	{
		return enrolledRsn;
	}

	/** Whether the master "Enabled" switch is on — nothing enrols or pushes until it is. */
	boolean syncEnabled()
	{
		return config.enabled();
	}

	/** Cloud mode active: enabled AND configured to push to the server. */
	boolean cloudActive()
	{
		return config.enabled() && config.syncMode() == SyncMode.CLOUD;
	}

	/** Local mode active: enabled AND keeping everything on this computer. */
	boolean localMode()
	{
		return config.enabled() && config.syncMode() == SyncMode.LOCAL;
	}

	/** RSN to show in the panel: the enrolled name in cloud mode, the logged-in name in local. */
	String displayRsn()
	{
		return localMode() ? localName : enrolledRsn;
	}

	/**
	 * Panel "Open my page" in local mode: open the self-contained page the plugin
	 * maintains under {@code .runelite/fitzgerald/}. Nothing is fetched from the
	 * network — the page carries its own data inline. When logged in we freshen the
	 * page first; otherwise we open the last copy written.
	 */
	void openLocalPage()
	{
		final String rsn = localName;
		if (rsn == null || rsn.isEmpty())
		{
			chat("Fitzgerald.gg: log in on the account you want to view, then open your page.");
			return;
		}
		if (client.getGameState() == GameState.LOGGED_IN && localStore.isReadyFor(rsn))
		{
			clientThread.invoke(() ->
			{
				gatherCharacter();
				executor.submit(() ->
				{
					File page = localStore.flush(localDir());
					openInBrowser(page != null ? page : localStore.pageFor(localDir(), rsn));
				});
			});
			return;
		}
		openInBrowser(localStore.pageFor(localDir(), rsn));
	}

	private void openInBrowser(File page)
	{
		if (page == null || !page.isFile())
		{
			chat("Fitzgerald.gg: your local page is still being built — play for a moment, then try again.");
			return;
		}
		try
		{
			if (java.awt.Desktop.isDesktopSupported())
			{
				java.awt.Desktop.getDesktop().browse(page.toURI());
			}
			else
			{
				chat("Fitzgerald.gg: open " + page.getAbsolutePath() + " in your browser.");
			}
		}
		catch (Exception ex)   // noqa: browse can throw a range of IO/security exceptions
		{
			log.debug("open local page failed", ex);
			chat("Fitzgerald.gg: couldn't open the page automatically — it's at " + page.getAbsolutePath());
		}
	}

	private static File localDir()
	{
		return new File(net.runelite.client.RuneLite.RUNELITE_DIR, "fitzgerald");
	}

	/** Copy the always-current character sheet into the local store. Client thread. */
	private void gatherCharacter()
	{
		if (!localMode() || client.getGameState() != GameState.LOGGED_IN)
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
		// The client-computed counters (this session's totals) fold into the lifetime
		// trackers. The server-derived per-resource skilling counters aren't available
		// offline, so they don't appear in local mode.
		localStore.setTrackers(harvest(), name);
	}

	/** Local-mode equivalent of a push: refresh the sheet, then rewrite the page. */
	private void refreshLocal()
	{
		gatherCharacter();
		executor.submit(() -> localStore.flush(localDir()));
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
				chat("Fitzgerald.gg: log in first.");
				return;
			}
			Player lp = client.getLocalPlayer();
			String name = lp != null ? lp.getName() : null;
			String token = trimToNull(configManager.getRSProfileConfiguration(GROUP, KEY_TOKEN));
			if (name == null || name.isEmpty() || token == null)
			{
				chat("Fitzgerald.gg: this account isn't enrolled yet.");
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
					chat("Fitzgerald.gg: couldn't reach the server — lock unchanged.");
					return;
				}
				pageLocked = reply.has("locked") && reply.get("locked").getAsBoolean();
				chat(pageLocked
					? "Fitzgerald.gg: your page is now locked — viewers need the password."
					: "Fitzgerald.gg: page lock removed.");
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
					chat("Fitzgerald.gg: couldn't reach the server — listing unchanged.");
					return;
				}
				publicListed = reply.has("public") && reply.get("public").getAsBoolean();
				chat(publicListed
					? "Fitzgerald.gg: your page is now listed in the public directory."
					: "Fitzgerald.gg: your page is unlisted (reachable by direct link only).");
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
					chat("Fitzgerald.gg: couldn't reach the server — nothing changed.");
					return;
				}
				boolean scheduled = reply.has("scheduled") && reply.get("scheduled").getAsBoolean();
				deletePendingTs = scheduled && reply.has("delete_ts") ? reply.get("delete_ts").getAsLong() : null;
				chat(scheduled
					? "Fitzgerald.gg: deletion scheduled. Your data is removed in 7 days unless you cancel."
					: "Fitzgerald.gg: scheduled deletion cancelled — your data stays.");
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
				chat("Fitzgerald.gg: export failed — couldn't reach the server.");
				return;
			}
			try
			{
				// Hub rule: all plugin file I/O stays inside a plugin-specific
				// subdirectory of .runelite — never the .runelite root.
				File dir = new File(net.runelite.client.RuneLite.RUNELITE_DIR, "fitzgerald");
				dir.mkdirs();
				File out = new File(dir,
					"export-" + name.replaceAll("[^A-Za-z0-9]", "_")
						+ "-" + System.currentTimeMillis() + ".json");
				Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
				chat("Fitzgerald.gg: data exported to " + out.getAbsolutePath());
			}
			catch (Exception e)
			{
				log.debug("export write failed", e);
				chat("Fitzgerald.gg: export downloaded but couldn't be saved to disk.");
			}
		}));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private void refreshPanel()
	{
		FitzgeraldPanel p = panel;
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
		BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x1E, 0x1B, 0x16));
		g.fillRoundRect(1, 1, 22, 22, 6, 6);
		g.setColor(new Color(0xC8, 0xA2, 0x5A)); // brass/gold "F"
		g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 16f));
		g.drawString("F", 8, 18);
		g.dispose();
		return img;
	}
}
