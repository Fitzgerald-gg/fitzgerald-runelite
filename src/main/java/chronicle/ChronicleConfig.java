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
	String GROUP = "chronicle";

	@ConfigSection(
		name = "Advanced",
		description = "Cloud sync and how often the journal is written. "
			+ "Every network feature here is OFF/blank by default. Chronicle is a "
			+ "local journal unless you point it somewhere.",
		position = 1,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	// no on/off item for the journal itself; the RuneLite plugin toggle is that switch.

	@ConfigItem(
		keyName = "cloudSync",
		name = "Enable cloud sync",
		description = "ALSO send a copy of your journal UPWARD to the server below: "
			+ "loot both taken and left on the ground, levels and a per-skill "
			+ "level/xp snapshot, kill counts and the rest of your lifetime "
			+ "counters, collection log, clues, quests, diaries, combat "
			+ "achievements, slayer tasks, pets, deaths and group-storage "
			+ "movements, each stamped with your display name, your account type "
			+ "(ironman, GIM and so on) and your RuneLite account hash. One-way: "
			+ "nothing is ever read back, and every feature works identically with "
			+ "this off. Off by default: without this, Chronicle never touches the "
			+ "network. Requires a server URL and a token.",
		warning = "This feature submits your IP address, and your own account's activity, "
			+ "to the 3rd-party server you configure below: a server not controlled or "
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
			+ "server you run yourself). Blank by default. Cloud sync does nothing "
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

	@ConfigItem(
		keyName = "pushIntervalMinutes",
		name = "Journal write interval",
		description = "How often the running session is folded into the on-disk "
			+ "journal and written out (the day's history baseline lands on the same "
			+ "beat). This is the most a crash or a power cut can cost you. The "
			+ "journal is also written at logout and when the plugin stops. With "
			+ "cloud sync on, the upward push rides this same cadence; with it off, "
			+ "the setting is purely local.",
		position = 14,
		section = advancedSection
	)
	@Range(min = 1, max = 60)
	@Units(Units.MINUTES)
	default int pushIntervalMinutes()
	{
		return 5;
	}

	// import and the journal folder live in the panel's "manage" view; a RuneLite
	// config item can't be a button.
}
