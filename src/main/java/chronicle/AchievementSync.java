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
 * Snapshot of the account's achievement state: every quest's progress, each
 * diary tier's completion, and combat-achievement points plus per-tier status.
 * The journal's character sheet reads it, and the push loop sends it whole
 * whenever it differs from the last copy the server acked. Raw enum names and
 * varbit values go in as-is, nothing is graded here.
 *
 * <p>All reads are on the client thread, and the quest sweep runs a clientscript
 * per quest, so one snapshot is built per game tick and shared by both callers.
 */
@Singleton
public class AchievementSync
{
	private static final String[] DIARY_TIERS = {"easy", "medium", "hard", "elite"};

	// Diaries whose four tiers each have a completion varbit, easy to elite per row.
	// Karamja is missing three of those varbits, so it's built in snapshot() instead.
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

	// JSON of the last snapshot the server acked. Fields are built in a fixed order,
	// so string equality holds as the change gate. Written on an HTTP callback thread.
	private volatile String lastSynced;

	// The tick's snapshot, shared by every caller in that tick. cached is stored
	// before cachedTick, so a matching tick means the object is visible.
	private volatile JsonObject cached;
	private volatile int cachedTick = -1;

	@Inject
	public AchievementSync(Client client)
	{
		this.client = client;
	}

	// Client thread only. Every caller in a tick gets the same object, so treat it
	// as read-only.
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
		// Karamja easy/medium/hard have no completion varbit. A tier is done once its
		// task count hits the tier total (10 / 19 / 10, per diary_completion_info).
		// Only elite got a real complete varbit.
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

	// Call on the server's ack only.
	void markSynced(JsonObject snap)
	{
		lastSynced = snap.toString();
	}

	// Account boundary: the next login syncs afresh under its own name.
	void reset()
	{
		lastSynced = null;
		cachedTick = -1;
		cached = null;
	}
}
