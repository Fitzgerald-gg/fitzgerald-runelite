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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
	// RSProfile-scoped (account-hash-bound, so it survives renames — the same
	// slot the token lives in): the display name this account's journal is
	// filed under. Lets the journal FOLLOW an in-game rename instead of the
	// new name starting a blank record.
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

	// Injected rather than constructed: the Plugin Hub's review rejects a plugin
	// that builds its own Gson/OkHttp instead of taking the client's.
	@Inject
	private com.google.gson.Gson gson;

	private HistoryLog historyLog;

	private ChroniclePanel panel;
	private NavigationButton navButton;

	private ScheduledFuture<?> pushTask;
	private GameState lastState;
	private boolean pendingEnrolCheck;
	// True from the moment an account is in-game until its session is torn down.
	// The teardown trigger can NOT be "the previous state was LOGGED_IN": a
	// dropped connection arrives at the login screen via CONNECTION_LOST, and
	// comparing only the immediately-prior state let that path skip the whole
	// account boundary (session counters, caches and push identity survived into
	// whichever account logged in next).
	private boolean wasLoggedIn;

	// Cached from the last successful harvest while logged in, so a logout push
	// works even after the RSProfile has been cleared.
	private volatile String cachedToken;
	private volatile String cachedName;
	private volatile Map<String, Integer> cachedSnapshot;
	private volatile String cachedAccountType;

	// One inheritance fetch per session at most — a failed attempt waits for the
	// next login rather than refiring on every refresh cycle.
	private volatile boolean slayerImportTried;

	// Counters the server computes from OTHER data at read time (untaken loot from
	// forwarded events; resource value priced from the gathering counters). They can
	// appear in the journal via old adoptions, but pushing them as counters would
	// double-present them — the only keys the upward sync withholds.
	private static final java.util.Set<String> PUSH_EXCLUDE = new java.util.HashSet<>(
		java.util.Arrays.asList("untakenLootValue", "untakenLootCount", "resourcesGatheredValue"));

	// The prayer-verb forensic correction (see refreshLocal): absolute values
	// reconciled against the account's Prayer xp ledger, identical to the map the
	// server migration applied. null = delete the contaminated key outright.
	private static final Map<String, Long> PRAYER_VERB_FIX = new java.util.HashMap<>();
	static
	{
		PRAYER_VERB_FIX.put("ashesScattered", 4L);
		PRAYER_VERB_FIX.put("vileAshesScattered", 4L);
		PRAYER_VERB_FIX.put("abyssalAshesScattered", null);
		PRAYER_VERB_FIX.put("maliciousAshesScattered", null);
		PRAYER_VERB_FIX.put("fiendishAshesScattered", null);
		PRAYER_VERB_FIX.put("abyssalAshesSacrificed", 1093L);
		PRAYER_VERB_FIX.put("vileAshesSacrificed", 905L);
		PRAYER_VERB_FIX.put("maliciousAshesSacrificed", 425L);
		PRAYER_VERB_FIX.put("fiendishAshesSacrificed", 157L);
		PRAYER_VERB_FIX.put("bonesBuried", 23L);
		PRAYER_VERB_FIX.put("bigBonesBuried", 8L);
		PRAYER_VERB_FIX.put("dragonBonesBuried", 4L);
		PRAYER_VERB_FIX.put("normalBonesBuried", 9L);
		PRAYER_VERB_FIX.put("lavaDragonBonesBuried", 1L);
		PRAYER_VERB_FIX.put("bonesBonesBuried", null);
		PRAYER_VERB_FIX.put("wyrmBonesSacrificed", 258L);
		PRAYER_VERB_FIX.put("bigBonesSacrificed", 253L);
		PRAYER_VERB_FIX.put("superiorDragonBonesSacrificed", 201L);
		PRAYER_VERB_FIX.put("dragonBonesSacrificed", 112L);
		PRAYER_VERB_FIX.put("frostDragonBonesSacrificed", 105L);
		PRAYER_VERB_FIX.put("normalBonesSacrificed", 2L);
	}

	// The wiki drop-rate book for the local dryness ledger (lazy-loaded).
	private GrindBook grindBook;

	// Panel-facing status.
	private volatile String enrolledRsn;
	// The logged-in name (the journal's account identity; also the cloud push's
	// name when sync is on).
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
		// Built here, not in a field initializer: field injection has run by now,
		// so both take the client's Gson.
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
		// Native lifetime-counter trackers feed the in-memory StatStore; the
		// refresh loop folds it into the journal (and the push loop mirrors the
		// journal upward when cloud sync is on).
		counters.setConsumableSink((key, gp) ->
		{
			String who = localName;
			if (who != null)
			{
				localStore.addConsumableValue(key, gp, who);
			}
		});
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
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		// The panel's repeating timers hold a reference to it (and to us): left
		// running they rebuild a detached panel every few seconds forever, and
		// each plugin toggle leaks another one.
		final ChroniclePanel dying = panel;
		if (dying != null)
		{
			javax.swing.SwingUtilities.invokeLater(dying::shutdown);
		}
		panel = null;
		pendingEnrolCheck = false;
		// Bank the session before dropping it. Toggling the plugin off (or closing
		// the client, which shuts plugins down) used to discard everything counted
		// since the last five-minute fold — the journal is the system of record,
		// so it must be written on the way out, not just on the interval.
		if (localName != null && localStore.isReadyFor(localName))
		{
			localStore.setTrackers(sessionView(), localName);
			localStore.flush(localDir());
		}
		// While we are unregistered no events reach the trackers, so the store stops
		// tracking reality — and the player may switch accounts before toggling us
		// back on. Forget the totals so the next login counts its own session.
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
			wasLoggedIn = true;
		}
		else if (state == GameState.LOGIN_SCREEN && wasLoggedIn)
		{
			// Any arrival at the login screen from an in-game session closes it —
			// including the CONNECTION_LOST route a dropped connection takes. A
			// world HOP is still deliberately NOT a logout: it never reaches the
			// login screen, and it is the same account with the same totals.
			wasLoggedIn = false;
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
		// LOGGED_IN fires again on every world hop and every region load, for the
		// SAME account and the SAME session. Re-running the load below would
		// re-freeze the journal's lifetime base at values that ALREADY contain
		// this session (the base is frozen from disk, and the session store is
		// deliberately not cleared on a hop) — every counter banked before the
		// hop would be counted twice, and a journal-absolute push would make that
		// permanent server-side. It would also discard any records folded in
		// since the last flush. So: an already-loaded account only refreshes.
		if (name.equals(localName) && localStore.isReadyFor(name))
		{
			refreshPanel();
			if (cloudActive())
			{
				adoptToken(name);
			}
			return;
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
		// The account's RSProfile remembers which name its journal is filed
		// under. A different name at login means an in-game rename — move the
		// journal (and its history spine) to the new slug BEFORE loading, so
		// the record continues instead of restarting blank. The pointer is
		// read here (client thread) and updated after the load settles.
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
			// A different account's journal is now mounted: drop the panel views
			// built from the previous one (task journey, dryness, open drill-downs).
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
				// The session store restarts from zero either side of the toggle;
				// the rebase above already banked its increments in the journal.
				statStore.clear();
				counters.reset();
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
	// Cloud identity (upward sync only — no enrolment, no downward calls)
	// ------------------------------------------------------------------

	/**
	 * Adopt this account's push token: a pasted override from the settings, or
	 * whatever an earlier install left on the RSProfile. No token means no cloud —
	 * the journal carries on identically either way. Runs on the client thread.
	 */
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
		// Flush any collection-log pages viewed since the last push (the server
		// merges partial snapshots).
		if (clogCapture.isDirty())
		{
			api.pushClog(config.serverBaseUrl(), token, name, clogCapture.snapshot());
			clogCapture.clearDirty();
		}
		// Achievement state (quests / diaries / combat tasks) — whole-snapshot
		// sync, sent only when it differs from the last copy the server acked.
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
		// The JOURNAL is the system of record. Fold the running session into it,
		// then mirror its lifetime absolutes upward — the server is a passive copy
		// (the discord bot's feed), never a source. The one-shot correction runs
		// first from here too, so no push can beat it to a freshly-loaded journal.
		maybeMigratePrayerVerbs();
		// The journal must be the one belonging to the account we are about to
		// push AS. Between an account switch and the completion of the new
		// journal's async load, the store still holds the previous account's
		// model — pushing then would file one player's lifetime under another's
		// token, permanently (the server floor-merges).
		if (!name.equals(localName) || !localStore.isReadyFor(name))
		{
			return;
		}
		localStore.setTrackers(sessionView(), localName);
		Map<String, Integer> snapshot = journalAbsolutes(name);
		if (snapshot.isEmpty())
		{
			return;   // nothing journaled yet on this account — nothing to push
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
	 * One-shot repair for the reference account: early builds minted Demonic
	 * Offering casts as per-ash "Scattered" rows. The server's copy was
	 * forensically reconciled from the xp ledger (2026-08-30); this applies the
	 * identical correction to the local journal so the two records agree — and
	 * so journal-absolute pushes can't resurrect the contaminated rows. Called
	 * from both the refresh and the push path (whichever reaches a ready store
	 * first applies it; the flag makes the second call a no-op).
	 */
	private void maybeMigratePrayerVerbs()
	{
		if (localName != null && localStore.isReadyFor(localName)
			&& "oxli".equals(localName.trim().toLowerCase(java.util.Locale.ROOT))
			&& !"true".equals(configManager.getConfiguration(GROUP, "prayerVerbMigrated")))
		{
			localStore.correctTrackers(PRAYER_VERB_FIX, localName);
			configManager.setConfiguration(GROUP, "prayerVerbMigrated", true);
		}
	}

	/**
	 * The journal's lifetime counters, clamped to the wire's int shape. This is
	 * the ONLY thing the counter push sends — what the journal says is what the
	 * server gets, so a fresh server (or a wiped one) rebuilds entirely from the
	 * client and "server on or off" makes no difference to what the player sees.
	 */
	private Map<String, Integer> journalAbsolutes(String rsn)
	{
		Map<String, Integer> out = new java.util.HashMap<>();
		// The store keeps the PREVIOUS account's model mounted until the next
		// load completes, so an unguarded read during the login gap would hand
		// one account's lifetime totals to another's push.
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

	/** Runs on the client thread (GameStateChanged). Best-effort final push. */
	private void onLogout()
	{
		// The journal always closes out the session: fold the final counters in,
		// write, end the session so a different account logging in next can't
		// record onto this model.
		// Guarded on the store's own identity: if this account's journal never
		// finished loading (a logout inside the login gap), the mounted model
		// belongs to somebody else and must not be written or frozen here.
		if (localName != null && localStore.isReadyFor(localName))
		{
			localStore.setTrackers(sessionView(), localName);
			appendHistoryBaseline();
			recordSessionLine();
			// Freeze the journal's totals for the final push BEFORE the stores
			// reset — the logout flush sends exactly what the journal will say
			// on the next login.
			Map<String, Integer> fresh = journalAbsolutes(localName);
			if (!fresh.isEmpty())
			{
				cachedSnapshot = fresh;
			}
		}
		executor.submit(() -> localStore.flush(localDir()));
		localStore.endSession();
		eventCapture.resetSessionFlags();
		slayerImportTried = false;
		// Account boundary for the achievement gate too: the next login must
		// sync its own snapshot even if it happens to serialize identically.
		achievementSync.reset();
		// These totals belong to the account that just logged out. Drop them here
		// so a DIFFERENT account logging in next can never inherit them.
		statStore.clear();
		// Take the push identity by value, then CLEAR it. The cache is what makes
		// a logout flush possible after the RSProfile has gone; left standing, it
		// becomes the identity of whichever account logs in next — a token-less
		// alt would flush ITS journal under this account's token and name, which
		// the server accepts and floor-merges permanently. (localName is kept:
		// the panel still browses the closed session's journal.)
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
			// The server holds higher totals than this journal — usually another
			// computer's journal is ahead. The client is authoritative for its own
			// record, so surface it rather than silently rewriting either side.
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

	/**
	 * The current in-memory counter snapshot — this session's increments,
	 * counted from zero by the trackers ({@link chronicle.counters.ChronicleCounters})
	 * and the local resolver. No RuneLite config is read or written for counters.
	 */
	Map<String, Integer> harvest()
	{
		return statStore.snapshotAll();
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

	/** The journal's task-by-task slayer journey (panel fetches on first open). */
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

	/** The dryness ledger, computed from the journal's own collection log +
	 *  kill counts against the bundled wiki rate book (panel fetches once per
	 *  session on demand; the maths is the site's: 1 − (1 − 1/rate)^kc). */
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
	 * only appear when this session actually beat the journal's lifetime record — the
	 * journal-write path keeps its absolutes, but the panel must never show a
	 * lifetime peak as a session feat.
	 */
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

	/** The client's Gson, shared with the panel so nothing constructs its own. */
	com.google.gson.Gson gson()
	{
		return gson;
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
	 * This session's own counter increments, straight from the trackers — the
	 * store counts from zero at each account boundary, so the harvest IS the
	 * session. Max-type keys pass through as absolutes (the journal takes their
	 * max); everything else drops non-positive noise.
	 */
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
		// The session's counters fold into the lifetime trackers through the
		// SESSION view (max-keys as absolutes, noise dropped) — the journal's
		// additive base does the lifetime arithmetic.
		localStore.setTrackers(sessionView(), name);
	}

	/** The journal's refresh: gather the sheet, fold the session in, rewrite the page. */
	private void refreshLocal()
	{
		gatherCharacter();
		maybeMigratePrayerVerbs();
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
		// First run PER ACCOUNT: adopt the core Loot Tracker's lifetime record —
		// the one LOCAL archive predating any server, so an install late in an
		// account's life starts years deep. Reading the ACTIVE RS profile's keys
		// keeps it own-account by construction (league and alt profiles have
		// different keys); the one-shot flag is RSProfile-scoped for the same
		// reason — each account inherits its own archive, not just whichever
		// logged in first. Purely local, no network; the floors are idempotent,
		// so a re-run can never double anything.
		if (localName != null && localStore.isReadyFor(localName)
			&& !"true".equals(configManager.getRSProfileConfiguration(GROUP, "lootTrackerImported")))
		{
			importLootTracker();
		}
		// One-shot inheritance of the cloud's task-by-task slayer journey — the
		// only downward call left, and it no-ops without a server URL, so a
		// local-only install never notices it exists. From here the journey is
		// kept locally (LOOT slayer stamps open segments; SLAYER events close them).
		if (cloudActive() && !slayerImportTried
			&& localName != null && localStore.isReadyFor(localName)
			&& !"true".equals(configManager.getRSProfileConfiguration(GROUP, "slayerJourneyImported")))
		{
			slayerImportTried = true;
			final String who = localName;
			api.fetchSlayerJourney(config.serverBaseUrl(), who, j -> clientThread.invoke(() ->
			{
				if (j != null && localStore.isReadyFor(who))
				{
					localStore.adoptSlayerJourney(j, who);
					configManager.setRSProfileConfiguration(GROUP, "slayerJourneyImported", true);
					refreshPanel();
				}
			}));
		}
		executor.submit(() -> localStore.flush(localDir()));
	}

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
				JsonObject o = gson.fromJson(raw, JsonObject.class);
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
		configManager.setRSProfileConfiguration(GROUP, "lootTrackerImported", true);
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

	/** Point the player at their own journal on disk — the export IS the file. */
	void actionExport()
	{
		final String rsn = localName != null ? localName : enrolledRsn;
		if (rsn == null)
		{
			chat("Chronicle: log in first — the journal opens with an account.");
			return;
		}
		executor.submit(() ->
		{
			localStore.flush(localDir());
			chat("Chronicle: your journal lives in " + localDir().getAbsolutePath()
				+ " — plain JSON, every byte of it yours.");
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
