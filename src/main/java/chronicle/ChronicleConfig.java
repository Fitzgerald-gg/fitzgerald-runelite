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
		description = "Cloud sync, screenshots, and how often the journal is written. "
			+ "Every network feature here is OFF/blank by default — Chronicle is a "
			+ "local journal unless you point it somewhere.",
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
		description = "ALSO send a copy of your journal (loot, levels, kill counts, "
			+ "collection log, clues, quests, diaries, combat achievements, slayer "
			+ "tasks, pets, deaths, group-storage movements) UPWARD to the server "
			+ "below. The only thing ever read back is a one-time import, once per "
			+ "account, of the slayer-task history that server already holds for "
			+ "you; nothing else is downloaded, and every feature works identically "
			+ "with this off. Off by default: without this, Chronicle never touches "
			+ "the network. Requires a server URL and a token.",
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
		name = "Cloud token",
		description = "The push token for this account on the configured server "
			+ "(issued by that server's operator). Cloud sync sends nothing "
			+ "without one.",
		position = 12,
		section = advancedSection
	)
	default String manualToken()
	{
		return "";
	}

	// The scheduled cycle harvests once and feeds both sinks: it always folds the
	// running session into the journal and writes it out, and mirrors that upward
	// only when cloud sync is on. So this interval is a LOCAL durability setting
	// first — the window a crash can cost you — and a network cadence second; it
	// must never read as a cloud-only knob, or a local-only install lengthens it
	// to quieten network traffic it does not have and widens its own write window.
	@ConfigItem(
		keyName = "pushIntervalMinutes",
		name = "Journal write interval",
		description = "How often the running session is folded into the on-disk "
			+ "journal and written out (the day's history baseline lands on the same "
			+ "beat). This is the most a crash or a power cut can cost you, so "
			+ "shorter is safer; the journal is also written at logout and when the "
			+ "plugin stops. With cloud sync on, the upward push rides this same "
			+ "cadence — with it off, the setting is purely local.",
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
