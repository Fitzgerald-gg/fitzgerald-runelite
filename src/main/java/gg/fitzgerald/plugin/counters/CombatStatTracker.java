/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin.counters;

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
import net.runelite.api.events.HitsplatApplied;

import static gg.fitzgerald.plugin.counters.StatKeys.SPECIAL_ATTACKS_USED;
import static gg.fitzgerald.plugin.counters.StatKeys.HITS_BLOCKED;
import static gg.fitzgerald.plugin.counters.StatKeys.HITS_MISSED;
import static gg.fitzgerald.plugin.counters.StatKeys.HIGHEST_HIT;
import static gg.fitzgerald.plugin.counters.StatKeys.HIGHEST_HIT_TAKEN;
import static gg.fitzgerald.plugin.counters.StatKeys.DAMAGE_DEALT;
import static gg.fitzgerald.plugin.counters.StatKeys.DAMAGE_TAKEN;
import static gg.fitzgerald.plugin.counters.StatKeys.DEATHS;
import static gg.fitzgerald.plugin.counters.StatKeys.POISON_DAMAGE_TAKEN;
import static gg.fitzgerald.plugin.counters.StatKeys.VENOM_DAMAGE_TAKEN;

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
					recordDamageDealt(amount);
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

	/** Add outgoing damage to the running total and promote it if it is a new best hit. */
	private void recordDamageDealt(int amount)
	{
		store.incrementStatBy(DAMAGE_DEALT, amount);
		if (amount > store.getStat(HIGHEST_HIT))
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
