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
 * Tallies combat-flavoured lifetime counters: how much damage flows through the
 * local player in either direction, the largest single hit ever landed, how
 * often attacks are shrugged off on both sides, and how many times the player
 * has died.
 *
 * <p>Damage and block totals are read straight off the hitsplat stream; deaths
 * are recognised from the game's own death notice in the chat log.
 */
public class CombatStatTracker implements StatTracker
{
	/**
	 * Every hitsplat colour that represents real HP loss on the player — the plain and
	 * max-hit forms in each source colour — so a max hit shown in a source colour still
	 * sets a record. Poison/venom/other DoTs and the "poise" splats are excluded: they are
	 * counted apart or are not HP at all.
	 */
	private static final Set<Integer> DAMAGE_TO_SELF = Set.of(
		HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_ME_CYAN, HitsplatID.DAMAGE_ME_ORANGE,
		HitsplatID.DAMAGE_ME_YELLOW, HitsplatID.DAMAGE_ME_WHITE,
		HitsplatID.DAMAGE_MAX_ME, HitsplatID.DAMAGE_MAX_ME_CYAN, HitsplatID.DAMAGE_MAX_ME_ORANGE,
		HitsplatID.DAMAGE_MAX_ME_YELLOW, HitsplatID.DAMAGE_MAX_ME_WHITE);

	private final StatStore store;
	private final Client client;

	// Special-attack energy last tick (0–1000). A DECREASE is a spec used —
	// regeneration and death-charge restores only ever raise it. -1 = unprimed
	// (fresh login), so the first observation never counts as a spec.
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
		// A hitsplat on the local player is one we received; on anything else it
		// is one we dealt. This split drives every branch below.
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
				// A block on us is a defence; a block on the target is our miss.
				store.incrementStat(landedOnSelf ? HITS_BLOCKED : HITS_MISSED);
				break;

			default:
				break;
		}
	}

	/**
	 * Counters read off hitsplats that land on us and that {@code damageTaken} (kept
	 * DAMAGE_ME-only for history) does not cover: the biggest single hit ever received —
	 * across every damage colour and the max-hit variants — and the HP bled to poison and
	 * venom, which are their own hitsplat types.
	 */
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
		// The death notice only ever arrives on these three channels; ignore the rest.
		if (!isTrackedChannel(event.getType()))
		{
			return;
		}

		if (event.getMessage().contains("Oh dear, you are dead!"))
		{
			store.incrementStat(DEATHS);
		}
	}

	// The style behind the current damage: combat XP rides every hit on the same
	// tick (or one either side), so the freshest combat-XP skill names the style.
	// Defensive/shared drops don't overwrite a fresher primary read.
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

	/** Add outgoing damage to the running total and promote it if it is a new best hit. */
	private void recordDamageDealt(Actor target, int amount)
	{
		store.incrementStatBy(DAMAGE_DEALT, amount);
		// Attribute to the style whose XP drop is fresh (within 2 ticks) — a
		// breakdown of the total, deliberately allowed to undercount rather than
		// ever guess wrong (the first hit of a session may go unattributed).
		if (lastStyleKey != null && client.getTickCount() - lastStyleTick <= 2)
		{
			store.incrementStatBy(lastStyleKey, amount);
		}
		// The heaviest blow only counts against something that fights back: raid
		// puzzle props have no combat level but can credit the player with
		// multi-thousand hitsplats (ToA's Het's Seal light beam).
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
