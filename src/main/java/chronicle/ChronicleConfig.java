/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ChronicleConfig.GROUP)
public interface ChronicleConfig extends Config
{
	// Settings and the enrolment token migrate one-shot from the pre-rename
	// "fitzgerald" group (see ChroniclePlugin.startUp + the login branch).
	String GROUP = "chronicle";

	@ConfigSection(
		name = "Advanced",
		description = "Cloud sync + screenshots. Everything here is OFF/blank by default — "
			+ "Chronicle is a local journal unless you point it somewhere.",
		position = 1,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	// ── Local journal ────────────────────────────────────────────────────
	// There is deliberately no master switch: installing Chronicle IS the
	// consent for a local, on-device journal (nothing leaves this computer),
	// and the RuneLite plugin toggle turns the whole thing off. Only the
	// network features below need explicit opt-in.

	// ── Advanced: cloud sync ─────────────────────────────────────────────

	@ConfigItem(
		keyName = "cloudSync",
		name = "Enable cloud sync",
		description = "ALSO send your captured activity (loot, levels, kill counts, "
			+ "collection log, clues, quests, diaries, combat achievements, slayer "
			+ "tasks, pets, deaths, group-storage movements) to the server below, so "
			+ "it can appear on a profile page there. Off by default: without this, "
			+ "Chronicle never touches the network. Requires a server URL.",
		warning = "This feature submits your IP address, and your own account's activity, "
			+ "to the 3rd-party server you configure below — a server not controlled or "
			+ "verified by the RuneLite developers",
		position = 10,
		section = advancedSection
	)
	default boolean cloudSync()
	{
		return false;
	}

	@ConfigItem(
		keyName = "serverBaseUrl",
		name = "Cloud server",
		description = "Base URL of a Chronicle-compatible server to sync to (e.g. a "
			+ "server you run yourself). Blank by default — cloud sync does nothing "
			+ "until this is set.",
		position = 11,
		section = advancedSection
	)
	default String serverBaseUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "manualToken",
		name = "Cloud token override",
		description = "Paste an existing token to use this account on the configured "
			+ "server without self-enrolling (a re-install, or a second device). "
			+ "Leave blank to enrol normally.",
		position = 12,
		section = advancedSection
	)
	default String manualToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "captureScreenshots",
		name = "Upload screenshots",
		description = "Cloud sync only: attach a screenshot to notable events "
			+ "(rare/valuable drops, pets, collection-log unlocks, level 99s, deaths, "
			+ "clues, quests, diaries, combat achievements) and upload it with the "
			+ "event. Off by default; does nothing without cloud sync.",
		warning = "This feature submits your IP address to a 3rd-party server not "
			+ "controlled or verified by the RuneLite developers",
		position = 13,
		section = advancedSection
	)
	default boolean captureScreenshots()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pushIntervalMinutes",
		name = "Push interval",
		description = "Cloud sync only: how often lifetime stat counters are pushed "
			+ "to the configured server.",
		position = 14,
		section = advancedSection
	)
	@Range(min = 1, max = 60)
	@Units(Units.MINUTES)
	default int pushIntervalMinutes()
	{
		return 5;
	}

	// Actions (Re-enrol, export) live in the side panel (see ChroniclePanel)
	// because the RuneLite config UI has no first-class button widget.
}
