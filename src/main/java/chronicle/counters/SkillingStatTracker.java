/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Chat-free skilling detection for EVERY non-combat skill. On each positive XP
 * drop (StatChanged) we forward one raw tuple and let the server resolve it (cloud sync only):
 *
 *     SKILL | xpDelta | objectId | gainedItemId | gainedQty | targetName | consumedItemId
 *
 * The server picks the best identity signal per skill (audit 2026-07-19):
 *   - gathering (WC/Mine/Fish/Cook): gained ITEM  (oak logs -> oakLogsChopped)
 *   - production (Smith/Fletch/Craft/Herb/Hunter/RC): gained ITEM x qty
 *   - thieving/agility: the interaction TARGET NAME (Master Farmer, Gnome course)
 *   - firemaking/prayer: the CONSUMED item (log burned, bone buried)
 * XP is the success-gate + tiebreak; CHAT (below) is kept ONLY for 0-XP events
 * (failed pickpockets, burnt food, planting) which no XP drop can see. Signals are
 * robust to XP boosts (Lumberjack/Kandarin/Raiments) because the item/target name
 * doesn't move with the multiplier.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.plugins.xptracker.XpTrackerService;
import net.runelite.client.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static chronicle.counters.StatKeys.*;

@Slf4j
public class SkillingStatTracker implements StatTracker
{
	private final StatStore statStore;
	private final Client client;
	private final XpTrackerService xpService;
	private final SkillChatBuffer skillBuffer;

	// Every non-combat skill rides the XP tuple. Combat (Att/Str/Def/Range/Magic/HP)
	// + Slayer are handled by the kill/loot subsystem, not here.
	private static final Set<Skill> DERIVABLE = EnumSet.of(
		Skill.WOODCUTTING, Skill.MINING, Skill.FISHING, Skill.COOKING,
		Skill.SMITHING, Skill.FLETCHING, Skill.CRAFTING, Skill.HERBLORE,
		Skill.HUNTER, Skill.RUNECRAFT, Skill.FIREMAKING, Skill.THIEVING,
		Skill.AGILITY, Skill.PRAYER, Skill.FARMING, Skill.CONSTRUCTION);

	// --- chat-free XP/object/item/target detection state ---
	private static final int TTL_TICKS = 6;   // forget a clicked target after ~3.6s
	private final EnumMap<Skill, Integer> xpCache = new EnumMap<>(Skill.class);
	private final EnumMap<Skill, List<Integer>> tickDrops = new EnumMap<>(Skill.class);
	private int lastObjectId = -1;
	private int objectTtl = 0;
	private String lastTargetName = "";
	private int targetTtl = 0;
	private int tickGainedItem = -1;
	private int tickGainedQty = 0;
	// Consumed identity (Firemaking log / Prayer bone) is detected on the
	// inventory-change tick, but the XP drop can land a tick or two LATER, so it
	// persists on a short TTL (unlike the gained item, which is same-tick).
	private int lastConsumedItem = -1;
	private int consumedTtl = 0;
	private Map<Integer, Integer> invSnapshot = null;

	// --- content-proof weeds counter (Farming raking is 0 XP → no tuple) ---

	// Residual chat — ONLY things NO XP drop can see: 0-XP outcomes. Gather/produce
	// verbs are DELIBERATELY absent (the XP tuples count those; forwarding chat too
	// would double-count). "'s pocket" catches pickpocket success+fail incl. named
	// NPCs ("You pick Martin's pocket", no "the"); the server keeps only the failure
	// (success now rides the target-name tuple).
	private static final String[] SKILL_PREFIXES = {
		"You accidentally burn",   // Cooking burns (0 XP)
		"You fail to pick",        // Thieving pickpocket FAILURE (success rides the target-name tuple)
		"You plant ",              // Farming seeds-planted milestone
		"Rooftop lap", "lap count" // Agility laps (not 1:1 with obstacle XP)
	};

	public SkillingStatTracker(StatStore statStore, Client client,
	                           XpTrackerService xpTrackerService, SkillChatBuffer skillBuffer)
	{
		this.statStore = statStore;
		this.client = client;
		this.xpService = xpTrackerService;
		this.skillBuffer = skillBuffer;
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
			return;   // login-init: the first drop carries the career total — cache only
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
		// Skip obviously non-skilling interactions so a "Talk-to"/"Attack"/"Examine"
		// right before a skilling XP drop can't mis-tag it. Everything else is
		// captured broadly; the target is only ever USED when an XP drop pairs with
		// it, so over-capture is harmless and new content needs no new verb here.
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
			lastObjectId = event.getId();   // live object id (not the stump) — legacy WC/Mining signal
			objectTtl = TTL_TICKS;
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
					if (e.getKey() == ItemID.WEEDS)
					{
						// Raking awards no experience, so no tuple is ever forwarded for
						// it and the server cannot derive it. Weeds arriving in the pack
						// is the only evidence a patch was raked — and this loop has
						// already computed that delta, so it costs nothing to read here.
						statStore.incrementStatBy(PATCHES_RAKED, d);
					}
				}
			}
			// Consumed identity (Firemaking log, Prayer bone): the item that LEFT
			// the pack this tick. Ignore stacks that merely dropped below their old
			// size but are still present in bulk (coins/ammo) by taking the biggest
			// single decrease of a now-absent-or-reduced consumable.
			for (Map.Entry<Integer, Integer> e : invSnapshot.entrySet())
			{
				int d = e.getValue() - now.getOrDefault(e.getKey(), 0);
				if (d > 0)
				{
					lastConsumedItem = e.getKey();
					consumedTtl = TTL_TICKS;   // persist: the FM/Prayer XP drop lands a beat later
				}
			}
		}
		invSnapshot = now;
	}

	@Override
	public void onGameTick(GameTick event)
	{
		// One tuple per XP drop this tick. All correlated signals are known now
		// (tick end) regardless of intra-tick event order.
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
					// 7-field tuple; targetName may contain spaces but never '|'.
					skillBuffer.addAction(skill.name() + "|" + delta + "|" + objStr
						+ "|" + gainStr + "|" + qtyStr + "|" + target + "|" + consStr);
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
				skillBuffer.add(msg);
				return;
			}
		}
	}

	@Override
	public void onWidgetLoaded(WidgetLoaded event) {}

	@Override
	public void onWidgetClosed(WidgetClosed event) {}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		// The XP baseline (xpCache) + inventory snapshot are per-CHARACTER, so they
		// survive a region load (LOADING — fires constantly while moving) and a world
		// hop (HOPPING — same account). Only a real logout can be followed by a
		// DIFFERENT account, so LOGIN_SCREEN is the ONLY state that invalidates them.
		// (Resetting on every non-LOGGED_IN state used to drop one gather action per
		// region-cross — live-caught: 7 rosewood + 3 maple recorded as 6 + 2.)
		if (state == GameState.LOGIN_SCREEN)
		{
			xpCache.clear();
			invSnapshot = null;
		}
		// Clicked-target + per-tick scratch are region/tick-local: safe to drop on any
		// transition out of LOGGED_IN so a stale tree/NPC can't mis-tag the next action.
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
		}
	}

	@Override
	public void onHitsplatApplied(HitsplatApplied event) {}

	@Override
	public void onAnimationChanged(AnimationChanged event) {}
}
