/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Chat-free skilling detection for every non-combat skill. Each positive XP drop
 * (StatChanged) becomes one raw tuple that SkillDeriver turns into typed counters:
 *
 *     SKILL | xpDelta | objectId | gainedItemId | gainedQty | targetName | consumedItemId
 *
 * Which field names the action varies by skill: gathering and production go by the
 * gained item, thieving and agility by the interaction target, firemaking and prayer
 * by the item consumed. XP gates success and breaks ties, and none of those signals
 * shift with an XP boost (Lumberjack, Kandarin, Raiments). Chat is read only for the
 * 0-XP outcomes no XP drop can see: failed pickpockets, burnt food, planting.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.util.Text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static chronicle.counters.StatKeys.*;

public class SkillingStatTracker implements StatTracker
{
	private final StatStore statStore;
	private final Client client;
	private final SkillDeriver deriver;

	// Combat skills and Slayer are counted from kills and loot, so they stay out.
	private static final Set<Skill> DERIVABLE = EnumSet.of(
		Skill.WOODCUTTING, Skill.MINING, Skill.FISHING, Skill.COOKING,
		Skill.SMITHING, Skill.FLETCHING, Skill.CRAFTING, Skill.HERBLORE,
		Skill.HUNTER, Skill.RUNECRAFT, Skill.FIREMAKING, Skill.THIEVING,
		Skill.AGILITY, Skill.PRAYER, Skill.FARMING, Skill.CONSTRUCTION,
		Skill.SAILING);

	private static final int TTL_TICKS = 6;   // forget a clicked target after ~3.6s
	private final EnumMap<Skill, Integer> xpCache = new EnumMap<>(Skill.class);
	private final EnumMap<Skill, List<Integer>> tickDrops = new EnumMap<>(Skill.class);
	private int lastObjectId = -1;
	private int objectTtl = 0;
	private String lastTargetName = "";
	private int targetTtl = 0;
	private int tickGainedItem = -1;
	private int tickGainedQty = 0;
	// the firemaking/prayer xp drop can land a tick or two after the item leaves the
	// pack, so this one carries a TTL while the gained item stays same-tick.
	private int lastConsumedItem = -1;
	private int consumedTtl = 0;
	private Map<Integer, Integer> invSnapshot = null;

	// Raking is 0 xp, so weeds landing in the pack are the only evidence a patch was
	// raked. They're also tradeable and bankable, so the count is gated on a recent
	// "Rake" click: totals only ever climb, and a bank withdrawal would inflate one
	// for good.
	private static final int RAKE_TTL_TICKS = 30;   // covers the walk to the patch
	// a patch holds three weeds; a bigger jump is a stack arriving from elsewhere
	private static final int RAKE_MAX_PER_EVENT = 3;
	private int rakeTtl = 0;

	// 0-xp outcomes only. Gather and produce lines stay out because the xp tuples
	// already count those, and chat on top would double them.
	private static final String[] SKILL_PREFIXES = {
		"You accidentally burn",   // cooking burns
		"You fail to pick",        // pickpocket failure; success rides the target-name tuple
		"You plant ",              // farming seeds planted
		"Rooftop lap", "lap count" // agility laps, which aren't 1:1 with obstacle xp
	};

	public SkillingStatTracker(StatStore statStore, Client client, SkillDeriver deriver)
	{
		this.statStore = statStore;
		this.client = client;
		this.deriver = deriver;
	}

	@Override
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (!DERIVABLE.contains(skill))
		{
			return;
		}
		int xp = event.getXp();
		Integer prev = xpCache.put(skill, xp);
		if (prev == null)
		{
			return;   // first drop after login carries the career total, so just cache it
		}
		int delta = xp - prev;
		if (delta > 0)
		{
			tickDrops.computeIfAbsent(skill, k -> new ArrayList<>()).add(delta);
		}
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuAction a = event.getMenuAction();
		boolean object = a == MenuAction.GAME_OBJECT_FIRST_OPTION || a == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| a == MenuAction.GAME_OBJECT_THIRD_OPTION || a == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| a == MenuAction.GAME_OBJECT_FIFTH_OPTION;
		boolean npc = a == MenuAction.NPC_FIRST_OPTION || a == MenuAction.NPC_SECOND_OPTION
			|| a == MenuAction.NPC_THIRD_OPTION || a == MenuAction.NPC_FOURTH_OPTION
			|| a == MenuAction.NPC_FIFTH_OPTION;
		if (!object && !npc)
		{
			return;
		}
		String opt = event.getMenuOption();
		if (opt == null)
		{
			return;
		}
		String o = opt.toLowerCase();
		// Skip the obvious non-skilling verbs so one landing just before an xp drop
		// can't mis-tag it. Anything else is captured, since a target is only read
		// when an xp drop pairs with it.
		if (o.equals("examine") || o.equals("walk here") || o.equals("cancel")
			|| o.startsWith("talk") || o.equals("attack") || o.startsWith("trade")
			|| o.startsWith("follow") || o.startsWith("pay") || o.startsWith("collect"))
		{
			return;
		}
		lastTargetName = Text.removeTags(event.getMenuTarget());   // "Master Farmer", "Oak", "Gnome"
		targetTtl = TTL_TICKS;
		if (object && (o.contains("chop") || o.contains("mine")))
		{
			lastObjectId = event.getId();   // the live tree/rock, caught before it becomes a stump
			objectTtl = TTL_TICKS;
		}
		if (object && o.equals("rake"))
		{
			rakeTtl = RAKE_TTL_TICKS;
		}
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getItemContainer() != client.getItemContainer(InventoryID.INVENTORY))
		{
			return;
		}
		Map<Integer, Integer> now = new HashMap<>();
		for (Item it : event.getItemContainer().getItems())
		{
			if (it != null && it.getId() >= 0)
			{
				now.merge(it.getId(), it.getQuantity(), Integer::sum);
			}
		}
		if (invSnapshot != null)
		{
			for (Map.Entry<Integer, Integer> e : now.entrySet())
			{
				int d = e.getValue() - invSnapshot.getOrDefault(e.getKey(), 0);
				if (d > 0)
				{
					tickGainedItem = e.getKey();
					tickGainedQty = d;      // +10 darts, +N runes, +1 bar/bow/gem/log/fish
					if (e.getKey() == ItemID.WEEDS && rakeTtl > 0)
					{
						statStore.incrementStatBy(PATCHES_RAKED, Math.min(d, RAKE_MAX_PER_EVENT));
						// one click clears a patch over several swings, so the weed that
						// just landed keeps the window open for the ones behind it
						rakeTtl = RAKE_TTL_TICKS;
					}
				}
			}
			// The item that left the pack this tick: the burnt log, the buried bone.
			// If several shrank, the last one seen wins.
			for (Map.Entry<Integer, Integer> e : invSnapshot.entrySet())
			{
				int d = e.getValue() - now.getOrDefault(e.getKey(), 0);
				if (d > 0)
				{
					lastConsumedItem = e.getKey();
					consumedTtl = TTL_TICKS;
				}
			}
		}
		invSnapshot = now;
	}

	@Override
	public void onGameTick(GameTick event)
	{
		// One tuple per xp drop. By tick end every correlated signal has landed,
		// whatever order the events arrived in.
		if (!tickDrops.isEmpty())
		{
			String gainStr = tickGainedItem > 0 ? Integer.toString(tickGainedItem) : "";
			String qtyStr = tickGainedItem > 0 ? Integer.toString(tickGainedQty) : "";
			String consStr = (consumedTtl > 0 && lastConsumedItem > 0) ? Integer.toString(lastConsumedItem) : "";
			String target = targetTtl > 0 ? lastTargetName : "";
			for (Map.Entry<Skill, List<Integer>> e : tickDrops.entrySet())
			{
				Skill skill = e.getKey();
				boolean useObj = (skill == Skill.WOODCUTTING || skill == Skill.MINING)
					&& objectTtl > 0 && lastObjectId > 0;
				String objStr = useObj ? Integer.toString(lastObjectId) : "";
				for (int delta : e.getValue())
				{
					// 7 fields; targetName may contain spaces but never '|'.
					String tuple = skill.name() + "|" + delta + "|" + objStr
						+ "|" + gainStr + "|" + qtyStr + "|" + target + "|" + consStr;
					deriver.apply(tuple);
				}
			}
			tickDrops.clear();
		}
		tickGainedItem = -1;
		tickGainedQty = 0;
		if (objectTtl > 0)
		{
			objectTtl--;
		}
		if (targetTtl > 0)
		{
			targetTtl--;
		}
		if (consumedTtl > 0)
		{
			consumedTtl--;
		}
		if (rakeTtl > 0)
		{
			rakeTtl--;
		}
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.SPAM
			&& event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.MESBOX)
		{
			return;
		}
		final String msg = event.getMessage();
		if (msg == null || msg.isEmpty())
		{
			return;
		}
		for (String prefix : SKILL_PREFIXES)
		{
			if (msg.contains(prefix))
			{
				deriver.applyChat(msg);
				return;
			}
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		// xpCache and the inventory snapshot are per-character, so they have to survive
		// LOADING (fires constantly while moving) and world hops. Resetting them on
		// every non-LOGGED_IN state lost one gather action per region cross.
		if (state == GameState.LOGIN_SCREEN)
		{
			xpCache.clear();
			invSnapshot = null;
		}
		// clicked target and per-tick scratch are tick-local, so drop them and a stale
		// tree or NPC can't mis-tag the next action
		if (state != GameState.LOGGED_IN)
		{
			tickDrops.clear();
			tickGainedItem = -1;
			tickGainedQty = 0;
			lastConsumedItem = -1;
			consumedTtl = 0;
			lastObjectId = -1;
			objectTtl = 0;
			lastTargetName = "";
			targetTtl = 0;
			rakeTtl = 0;
		}
	}
}
