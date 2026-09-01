/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.Skill;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;

import static chronicle.counters.StatKeys.SPECIAL_ATTACKS_USED;
import static chronicle.counters.StatKeys.HITS_BLOCKED;
import static chronicle.counters.StatKeys.HITS_MISSED;
import static chronicle.counters.StatKeys.HIGHEST_HIT;
import static chronicle.counters.StatKeys.HIGHEST_HIT_TAKEN;
import static chronicle.counters.StatKeys.DAMAGE_DEALT;
import static chronicle.counters.StatKeys.DAMAGE_DEALT_MELEE;
import static chronicle.counters.StatKeys.DAMAGE_DEALT_RANGED;
import static chronicle.counters.StatKeys.DAMAGE_DEALT_MAGIC;
import static chronicle.counters.StatKeys.DAMAGE_TAKEN;
import static chronicle.counters.StatKeys.DEATHS;
import static chronicle.counters.StatKeys.POISON_DAMAGE_TAKEN;
import static chronicle.counters.StatKeys.VENOM_DAMAGE_TAKEN;

/**
 * Lifetime combat counters: damage dealt and taken, biggest hits, blocks, misses, deaths.
 * Damage comes off the hitsplat stream; deaths off the chat death notice.
 */
public class CombatStatTracker implements StatTracker
{
	// Every hitsplat colour that is real HP loss on the player, plain and max-hit forms of
	// each. Poison and venom are counted separately; the rest aren't HP.
	private static final Set<Integer> DAMAGE_TO_SELF = Set.of(
		HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_ME_CYAN, HitsplatID.DAMAGE_ME_ORANGE,
		HitsplatID.DAMAGE_ME_YELLOW, HitsplatID.DAMAGE_ME_WHITE,
		HitsplatID.DAMAGE_MAX_ME, HitsplatID.DAMAGE_MAX_ME_CYAN, HitsplatID.DAMAGE_MAX_ME_ORANGE,
		HitsplatID.DAMAGE_MAX_ME_YELLOW, HitsplatID.DAMAGE_MAX_ME_WHITE);

	private final StatStore store;
	private final Client client;

	// Special attack energy last tick (0-1000). A drop means a spec was used; regen and
	// death charge only ever raise it. -1 = unprimed.
	private int prevSpecEnergy = -1;

	public CombatStatTracker(StatStore store, Client client)
	{
		this.store = store;
		this.client = client;
	}

	@Override
	public void onGameTick(GameTick tick)
	{
		int cur = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT);
		if (prevSpecEnergy >= 0 && cur < prevSpecEnergy)
		{
			store.incrementStat(SPECIAL_ATTACKS_USED);
		}
		prevSpecEnergy = cur;
	}

	@Override
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN || e.getGameState() == GameState.LOGIN_SCREEN)
		{
			prevSpecEnergy = -1;   // re-prime across logins/hops
		}
	}

	@Override
	public void onHitsplatApplied(HitsplatApplied event)
	{
		final Hitsplat splat = event.getHitsplat();
		final int type = splat.getHitsplatType();
		final int amount = splat.getAmount();
		// splat on us is damage received, on anything else it's damage we dealt
		final boolean landedOnSelf = isLocalPlayer(event.getActor());

		if (landedOnSelf)
		{
			recordDamageToSelf(type, amount);
		}

		switch (type)
		{
			case HitsplatID.DAMAGE_ME:
				if (landedOnSelf)
				{
					store.incrementStatBy(DAMAGE_TAKEN, amount);
				}
				else
				{
					recordDamageDealt(event.getActor(), amount);
				}
				break;

			case HitsplatID.BLOCK_ME:
				// a block on us is one we blocked, a block on the target is one we missed
				store.incrementStat(landedOnSelf ? HITS_BLOCKED : HITS_MISSED);
				break;

			default:
				break;
		}
	}

	// DAMAGE_TAKEN stays DAMAGE_ME-only so its running total still lines up with history.
	// The hit-taken record and the poison/venom bleed are tallied here instead.
	private void recordDamageToSelf(int type, int amount)
	{
		if (type == HitsplatID.POISON)
		{
			store.incrementStatBy(POISON_DAMAGE_TAKEN, amount);
		}
		else if (type == HitsplatID.VENOM)
		{
			store.incrementStatBy(VENOM_DAMAGE_TAKEN, amount);
		}
		if (DAMAGE_TO_SELF.contains(type) && amount > store.getStat(HIGHEST_HIT_TAKEN))
		{
			store.setStat(HIGHEST_HIT_TAKEN, amount);
		}
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		// the death notice only ever arrives on these three channels
		if (!isTrackedChannel(event.getType()))
		{
			return;
		}

		if (event.getMessage().contains("Oh dear, you are dead!"))
		{
			store.incrementStat(DEATHS);
		}
	}

	// Style behind the current damage. Combat XP lands on the same tick as the hit, give or
	// take one, so the freshest attack/strength/ranged/magic drop names the style.
	private String lastStyleKey;
	private int lastStyleTick = -1;

	@Override
	public void onStatChanged(StatChanged e)
	{
		final Skill sk = e.getSkill();
		String style = null;
		if (sk == Skill.ATTACK || sk == Skill.STRENGTH)
		{
			style = DAMAGE_DEALT_MELEE;
		}
		else if (sk == Skill.RANGED)
		{
			style = DAMAGE_DEALT_RANGED;
		}
		else if (sk == Skill.MAGIC)
		{
			style = DAMAGE_DEALT_MAGIC;
		}
		if (style != null)
		{
			lastStyleKey = style;
			lastStyleTick = client.getTickCount();
		}
	}

	private void recordDamageDealt(Actor target, int amount)
	{
		store.incrementStatBy(DAMAGE_DEALT, amount);
		// only attribute when the style's XP drop is within 2 ticks. better to undercount
		// the per-style breakdown (the first hit of a session) than to guess at it
		if (lastStyleKey != null && client.getTickCount() - lastStyleTick <= 2)
		{
			store.incrementStatBy(lastStyleKey, amount);
		}
		// combat level 0 skips raid puzzle props. Het's Seal in ToA credits multi-thousand
		// hitsplats and they are not hits.
		if (amount > store.getStat(HIGHEST_HIT) && target != null && target.getCombatLevel() > 0)
		{
			store.setStat(HIGHEST_HIT, amount);
		}
	}

	private boolean isLocalPlayer(Actor actor)
	{
		return actor == client.getLocalPlayer();
	}

	private static boolean isTrackedChannel(ChatMessageType type)
	{
		return type == ChatMessageType.SPAM
			|| type == ChatMessageType.GAMEMESSAGE
			|| type == ChatMessageType.MESBOX;
	}
}
