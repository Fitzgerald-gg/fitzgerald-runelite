/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.JsonObject;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.gameval.VarbitID;

/**
 * Point-in-time snapshot of the account's achievement state — every quest's
 * progress, each achievement-diary tier's completion, and combat-achievement
 * points and per-tier status — pushed whole on the counter loop whenever it
 * differs from the last server-acknowledged copy. Nothing is diffed or
 * interpreted here: raw enum names and varbit values travel as-is and the
 * server derives what it wants (thin client, fat server), so a change in how
 * CA tiers are graded needs no plugin release.
 *
 * <p>All reads happen on the client thread via {@link #snapshot()}; the
 * change gate compares canonical JSON, so an untouched account costs one
 * string comparison per push interval. A snapshot is built at most once per
 * game tick: the journal refresh and the cloud push harvest in the same
 * client-thread pass, and the quest sweep runs a clientscript per quest.
 */
@Singleton
public class AchievementSync
{
	private static final String[] DIARY_TIERS = {"easy", "medium", "hard", "elite"};

	// Every diary whose four tiers all have completion varbits, easy→elite per
	// row. Karamja is the game's oldest diary: its easy/medium/hard have NO
	// <TIER>_COMPLETE varbit (only elite does), so it's handled separately in
	// snapshot() from its task-count varbits (a tier is done when its count hits
	// the tier total, per the game's [proc,diary_completion_info] script).
	private static final String[] DIARY_REGIONS = {
		"ardougne", "desert", "falador", "fremennik", "kandarin",
		"kourend", "lumbridge", "morytania", "varrock", "western", "wilderness",
	};
	private static final int[][] DIARY_VARBITS = {
		{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};

	private static final String[] CA_TIERS = {
		"easy", "medium", "hard", "elite", "master", "grandmaster",
	};
	private static final int[] CA_TIER_STATUS = {
		VarbitID.CA_TIER_STATUS_EASY, VarbitID.CA_TIER_STATUS_MEDIUM,
		VarbitID.CA_TIER_STATUS_HARD, VarbitID.CA_TIER_STATUS_ELITE,
		VarbitID.CA_TIER_STATUS_MASTER, VarbitID.CA_TIER_STATUS_GRANDMASTER,
	};

	private final Client client;

	// Canonical JSON of the last snapshot the server acknowledged; the fields
	// are built in a fixed order, so string equality is a reliable change gate.
	// The ack lands on an HTTP callback thread and the gate is read on the
	// client thread, so the reference has to be published safely.
	private volatile String lastSynced;

	// The tick's snapshot, reused by every caller within that tick. Written on
	// the client thread and cleared by reset(); the snapshot is stored before
	// the tick that validates it, so seeing the tick means seeing its copy.
	private volatile JsonObject cached;
	private volatile int cachedTick = -1;

	@Inject
	public AchievementSync(Client client)
	{
		this.client = client;
	}

	/** The full state snapshot. Client thread only (scripts + varbits). The
	 *  same copy is handed to every caller for the rest of the tick, so callers
	 *  must treat it as read-only. */
	JsonObject snapshot()
	{
		int tick = client.getTickCount();
		JsonObject hit = cached;
		if (hit != null && cachedTick == tick)
		{
			return hit;
		}
		JsonObject quests = new JsonObject();
		for (Quest quest : Quest.values())
		{
			quests.addProperty(quest.getName(), quest.getState(client).name());
		}
		JsonObject diaries = new JsonObject();
		for (int r = 0; r < DIARY_REGIONS.length; r++)
		{
			JsonObject region = new JsonObject();
			for (int t = 0; t < DIARY_TIERS.length; t++)
			{
				region.addProperty(DIARY_TIERS[t], client.getVarbitValue(DIARY_VARBITS[r][t]) != 0);
			}
			diaries.add(DIARY_REGIONS[r], region);
		}
		// Karamja easy/medium/hard have no completion varbit — mark a tier done when
		// its task-completed count reaches that tier's total (10 / 19 / 10, from the
		// game's diary_completion_info script). Elite alone got a real complete varbit.
		JsonObject karamja = new JsonObject();
		karamja.addProperty("easy", client.getVarbitValue(VarbitID.KARAMJA_EASY_COUNT) >= 10);
		karamja.addProperty("medium", client.getVarbitValue(VarbitID.KARAMJA_MED_COUNT) >= 19);
		karamja.addProperty("hard", client.getVarbitValue(VarbitID.KARAMJA_HARD_COUNT) >= 10);
		karamja.addProperty("elite", client.getVarbitValue(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE) != 0);
		diaries.add("karamja", karamja);

		JsonObject combat = new JsonObject();
		combat.addProperty("points", client.getVarbitValue(VarbitID.CA_POINTS));
		JsonObject tiers = new JsonObject();
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			tiers.addProperty(CA_TIERS[i], client.getVarbitValue(CA_TIER_STATUS[i]));
		}
		combat.add("tiers", tiers);

		JsonObject root = new JsonObject();
		root.add("quests", quests);
		root.add("diaries", diaries);
		root.add("combat", combat);
		cached = root;
		cachedTick = tick;
		return root;
	}

	boolean changedSince(JsonObject snap)
	{
		return !snap.toString().equals(lastSynced);
	}

	/** Record the server's ack, so identical state is not re-sent. */
	void markSynced(JsonObject snap)
	{
		lastSynced = snap.toString();
	}

	/** Account boundary: the next login must sync afresh under its own name. */
	void reset()
	{
		lastSynced = null;
		cachedTick = -1;
		cached = null;
	}
}
