/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(FitzgeraldConfig.GROUP)
public interface FitzgeraldConfig extends Config
{
	String GROUP = "fitzgerald";

	@ConfigSection(
		name = "General",
		description = "Core Fitzgerald.gg sync settings",
		position = 0
	)
	String generalSection = "general";

	@ConfigSection(
		name = "Advanced",
		description = "Server + integration settings (change only if you know why)",
		position = 1,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "enabled",
		name = "Enabled",
		description = "Master switch. When on, this plugin captures your in-game activity "
			+ "(loot, levels, kill counts, collection log, clues, quests, achievement "
			+ "diaries, combat achievements, slayer tasks, pets, deaths, group-storage "
			+ "movements, and — for notable moments — a screenshot). In Cloud mode (the default) "
			+ "it sends this to the Fitzgerald.gg server (fitzgerald.gg), a third-party server not "
			+ "operated by RuneLite, so it can appear on your profile; in Local mode it keeps "
			+ "everything on your own computer and sends nothing. When off, nothing is enrolled, "
			+ "captured, pushed, or written.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by the RuneLite developers",
		position = 0,
		section = generalSection
	)
	default boolean enabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "syncMode",
		name = "Mode",
		description = "Cloud sends your captured activity to your fitzgerald.gg profile so it "
			+ "appears online. Local keeps everything on this computer only — nothing is sent "
			+ "to the server — and builds a self-contained page you open from the side panel "
			+ "(\"Open my page\").",
		position = 1,
		section = generalSection
	)
	default SyncMode syncMode()
	{
		return SyncMode.CLOUD;
	}

	@ConfigItem(
		keyName = "captureScreenshots",
		name = "Capture screenshots",
		description = "Attach a screenshot to notable events (rare/valuable drops, pets, "
			+ "collection-log unlocks, level 99s, deaths, clues, quests, diaries, combat "
			+ "achievements). The image is uploaded to Fitzgerald.gg with the event. Turn "
			+ "off to send metadata only.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by the RuneLite developers",
		position = 3,
		section = generalSection
	)
	default boolean captureScreenshots()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pushIntervalMinutes",
		name = "Push interval",
		description = "Cloud mode: how often your lifetime stat counters are pushed to Fitzgerald.gg. "
			+ "Local mode: how often the on-disk page is refreshed.",
		position = 2,
		section = generalSection
	)
	@Range(min = 1, max = 60)
	@Units(Units.MINUTES)
	default int pushIntervalMinutes()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "serverBaseUrl",
		name = "Cloud base URL",
		description = "Cloud mode only: base URL of the Fitzgerald.gg server. Leave as the default "
			+ "unless you self-host. Has no effect in Local mode.",
		position = 10,
		section = advancedSection
	)
	default String serverBaseUrl()
	{
		return "https://fitzgerald.gg";
	}

	@ConfigItem(
		keyName = "manualToken",
		name = "Cloud token override",
		description = "Cloud mode only: paste an existing Fitzgerald token to use this account without "
			+ "self-enrolling (for the site owner, a re-install, or a second device). Leave blank to "
			+ "enrol normally. Has no effect in Local mode.",
		position = 11,
		section = advancedSection
	)
	default String manualToken()
	{
		return "";
	}

	// Actions (Re-enrol, Open my page) live in the side panel
	// (see FitzgeraldPanel) because the RuneLite config UI has no first-class
	// button widget. The panel also shows live status: enrolled RSN + last push.
}
