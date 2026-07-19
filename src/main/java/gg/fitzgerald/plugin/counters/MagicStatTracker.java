/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin.counters;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;

import static gg.fitzgerald.plugin.counters.StatKeys.ASHES_SACRIFICED;
import static gg.fitzgerald.plugin.counters.StatKeys.BONES_SACRIFICED;
import static gg.fitzgerald.plugin.counters.StatKeys.COINS_FROM_ALCHEMY;
import static gg.fitzgerald.plugin.counters.StatKeys.DEMONIC_OFFERING_XP;
import static gg.fitzgerald.plugin.counters.StatKeys.DEMONIC_OFFERINGS_CAST;
import static gg.fitzgerald.plugin.counters.StatKeys.SINISTER_OFFERING_XP;
import static gg.fitzgerald.plugin.counters.StatKeys.OFFENSIVE_SPELLS_CAST;
import static gg.fitzgerald.plugin.counters.StatKeys.SINISTER_OFFERINGS_CAST;

/**
 * Two magic counters, both read off the local player's cast animation.
 *
 * <p><b>Alchemy.</b> High and Low Alchemy each play a fixed cast pose. While that
 * pose is up, a rise in the coin stack is the alch payout — read as the exact
 * inventory delta, never a value table, so a game rebalance can't drift it. The
 * animation is the gate; whether it's high or low doesn't matter to the coin total.
 * Because the pose is held for a whole cast and the animation event fires only
 * once, the pose is re-checked every tick a coin change lands rather than relying
 * on the event; and because the coin change and the animation can arrive in either
 * order within a tick, an unattributed coin gain is held to the end of the tick
 * before it's judged.
 *
 * <p><b>Offensive spells.</b> The player plays the same cast animation whether the
 * spell lands or splashes, once per cast even for a multi-target barrage — so the
 * animation is the one signal that counts exactly one per cast, unlike hitsplats,
 * projectiles, or XP. Counted against a set of known offensive cast animations;
 * alch, teleport, enchant and charge poses are deliberately not in it, and powered
 * staves (trident, sang, shadow) aren't spells so their attack poses aren't either.
 */
public class MagicStatTracker implements StatTracker
{
	private static final int HIGH_ALCH_ANIM = 713;
	private static final int LOW_ALCH_ANIM = 712;

	/**
	 * Arceuus offering spells. Demonic and Sinister Offering play the SAME cast
	 * animation (8975) and differ only by their spotanim ("colour"), so the graphic
	 * is the discriminator — which also excludes the reanimation spells, whose own
	 * colours aren't in the map. GFX verified live 2026-07-25.
	 */
	private static final int OFFERING_CAST_ANIM = 8975;
	// The two cast colours (spotanims). Named so the fixed-order fallback in
	// activeOfferingColour() never leans on Map.of's unspecified key order.
	private static final int GFX_DEMONIC = 1871;   // ashes (soul + wrath runes)
	private static final int GFX_SINISTER = 1872;  // bones (blood + wrath runes)
	// Spell NAME per colour (Cameron's call). Demonic = ashes (soul+wrath), Sinister =
	// bones (blood+wrath). Only THIS map moves if the naming is ever corrected again.
	private static final Map<Integer, String> OFFERING_GFX = Map.of(
		GFX_DEMONIC, DEMONIC_OFFERINGS_CAST,
		GFX_SINISTER, SINISTER_OFFERINGS_CAST);
	// Which sacrifice each colour ACTUALLY consumes — a fixed, probed fact (1871 ate
	// ashes, 1872 ate bones), independent of the naming above, so the bones/ashes
	// totals stay correct no matter what the spells are called.
	private static final Map<Integer, String> OFFERING_SAC = Map.of(
		GFX_DEMONIC, ASHES_SACRIFICED,
		GFX_SINISTER, BONES_SACRIFICED);
	// An offering costs runes + 1-3 bones/ashes. Excluding just its runes leaves the
	// bones/ashes consumed, so the sacrifice count needs no per-item bone/ash list.
	private static final Set<Integer> OFFERING_RUNES = Set.of(565, 566, 21880);   // blood, soul, wrath

	/**
	 * Cast animations that count as an offensive spell. Standard-spellbook casts are
	 * staff-dependent, and a whole tier (strike/bolt/blast, or the wave pair) shares one
	 * animation — the projectile differs, the pose doesn't — so both the with-staff and
	 * without-staff pose are listed for each. New ones are one line to add.
	 *
	 * <p>Deliberately excludes the utility poses (alch 712/713, enchant 719-721/931/4462,
	 * charge-orb 726, teleports) and powered-staff attack poses. One known overlap we
	 * accept: 811 is the god-spell cast but also the non-offensive Charge self-buff, so a
	 * god-spell user's occasional Charge is counted — gating it on a target would risk
	 * dropping real casts, a worse trade for a counter that should reflect casts made.
	 */
	private static final Set<Integer> OFFENSIVE_CAST_ANIMS = Set.of(
		711,    // strike / bolt / blast, cast without a magic staff
		1162,   // strike / bolt / blast, cast with a staff (split is staff vs no-staff, not tier)
		727,    // wave, cast without a staff
		1167,   // wave, cast with a staff (the common case — most waves are staff-cast)
		7855,   // surge (all four elements)
		1978,   // Ancient single-target (rush / blitz)
		1979,   // Ancient multi-target (burst / barrage)
		8977,   // Arceuus Demonbane
		811,    // god spells (Saradomin Strike / Claws of Guthix / Flames of Zamorak)
		708,    // Iban Blast
		1576,   // Magic Dart
		724);   // Crumble Undead

	private final StatStore store;
	private final Client client;

	/** Coins seen last inventory event; -1 until primed, so the stack isn't miscounted. */
	private int lastCoins = -1;
	/** A coin gain this tick still waiting to see if an alch pose explains it. */
	private int bufferedCoinGain;
	/** Tick an alch pose was observed, so a late-arriving coin gain can still match. */
	private int alchSeenTick = -1;
	/** (animation, tick) of the last counted cast, to swallow a same-tick re-fire. */
	private int lastCastAnim = -1;
	private int lastCastTick = -1;
	/** Previous inventory, to read what an offering cast consumes (see onGameTick). */
	private Map<Integer, Integer> invSnap = null;
	/** Non-rune items that left the pack this tick (the sacrificed bones/ashes). */
	private int sacrificeDecThisTick = 0;
	/** Tick an offering was cast; onGameTick resolves which spell it was and credits it. */
	private int offeringCastTick = -1;
	/** Prayer career XP last seen, to derive the XP an offering awards (-1 = unprimed). */
	private int prevPrayerXp = -1;
	private int offeringXpThisTick = 0;

	public MagicStatTracker(StatStore store, Client client)
	{
		this.store = store;
		this.client = client;
	}

	@Override
	public void onAnimationChanged(AnimationChanged event)
	{
		Player me = client.getLocalPlayer();
		if (me == null || event.getActor() != me)
		{
			return;
		}
		int anim = me.getAnimation();
		if (anim == HIGH_ALCH_ANIM || anim == LOW_ALCH_ANIM)
		{
			alchSeenTick = client.getTickCount();
			return;
		}
		if (OFFENSIVE_CAST_ANIMS.contains(anim))
		{
			int tick = client.getTickCount();
			if (anim == lastCastAnim && tick == lastCastTick)
			{
				return;   // same cast redelivered within the tick
			}
			lastCastAnim = anim;
			lastCastTick = tick;
			store.incrementStat(OFFENSIVE_SPELLS_CAST);
			return;
		}
		if (anim == OFFERING_CAST_ANIM)
		{
			// The colour (spotanim) that tells Demonic from Sinister is often NOT applied yet at
			// the instant this animation event fires, so reading it here dropped the odd cast
			// whole (no count, no sacrifice, no XP). Just mark the tick; onGameTick resolves the
			// colour once the whole tick's updates have settled. Re-firing this tick is harmless
			// (the tick is only tallied once, at tick-end).
			offeringCastTick = client.getTickCount();
		}
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getItemContainer() != client.getItemContainer(InventoryID.INVENTORY))
		{
			return;
		}
		// Tally non-rune items leaving the pack this tick; if an offering was cast this
		// tick, onGameTick credits them as bones/ashes sacrificed (runes are the spell
		// cost, not the sacrifice).
		Map<Integer, Integer> now = new HashMap<>();
		for (Item it : event.getItemContainer().getItems())
		{
			if (it != null && it.getId() >= 0)
			{
				now.merge(it.getId(), it.getQuantity(), Integer::sum);
			}
		}
		if (invSnap != null)
		{
			for (Map.Entry<Integer, Integer> e : invSnap.entrySet())
			{
				int dec = e.getValue() - now.getOrDefault(e.getKey(), 0);
				if (dec > 0 && !OFFERING_RUNES.contains(e.getKey()))
				{
					sacrificeDecThisTick += dec;
				}
			}
		}
		invSnap = now;
		int coins = event.getItemContainer().count(ItemID.COINS_995);
		if (lastCoins < 0)
		{
			lastCoins = coins;       // prime; never count the opening stack
			return;
		}
		int gain = coins - lastCoins;
		lastCoins = coins;
		if (gain <= 0)
		{
			return;
		}
		if (isAlchActiveNow())
		{
			store.incrementStatBy(COINS_FROM_ALCHEMY, gain);
		}
		else
		{
			// The coin event may have beaten the alch pose this tick; hold it and
			// let onGameTick decide once the whole tick's events are in.
			bufferedCoinGain += gain;
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		// Credit the bones/ashes an offering consumed AND the Prayer XP it awarded this
		// tick (cast, inventory change and XP drop all land before this tick-end event).
		if (offeringCastTick == client.getTickCount())
		{
			// Resolve Demonic vs Sinister from the colour on the caster, read at tick-end once the
			// spotanim has settled — the old mid-cast read often ran before the colour was applied
			// and dropped the whole cast. See activeOfferingColour().
			int gfx = activeOfferingColour();
			String castKey = gfx > 0 ? OFFERING_GFX.get(gfx) : null;
			if (castKey != null)
			{
				store.incrementStat(castKey);
				if (sacrificeDecThisTick > 0)
				{
					store.incrementStatBy(OFFERING_SAC.get(gfx), sacrificeDecThisTick);
				}
				if (offeringXpThisTick > 0)
				{
					store.incrementStatBy(
						castKey.equals(DEMONIC_OFFERINGS_CAST) ? DEMONIC_OFFERING_XP : SINISTER_OFFERING_XP,
						offeringXpThisTick);
				}
			}
		}
		sacrificeDecThisTick = 0;
		offeringXpThisTick = 0;
		offeringCastTick = -1;
		if (bufferedCoinGain > 0)
		{
			if (alchSeenTick == client.getTickCount())
			{
				store.incrementStatBy(COINS_FROM_ALCHEMY, bufferedCoinGain);
			}
			bufferedCoinGain = 0;   // unmatched gain was some other income; drop it
		}
	}

	@Override
	public void onStatChanged(StatChanged event)
	{
		// Prayer XP delta this tick; if an offering was cast this tick, onGameTick credits
		// it to the offering (burying/scattering happen on other ticks, so aren't caught).
		if (event.getSkill() != Skill.PRAYER)
		{
			return;
		}
		int xp = event.getXp();
		if (prevPrayerXp < 0)
		{
			prevPrayerXp = xp;   // prime; never count the login initialisation
			return;
		}
		int delta = xp - prevPrayerXp;
		prevPrayerXp = xp;
		if (delta > 0)
		{
			offeringXpThisTick += delta;
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			// Coins/XP/inventory move unobserved while away; reprime rather than count the jump,
			// and drop any half-formed offering cast so it can't be resolved after the gap.
			lastCoins = -1;
			bufferedCoinGain = 0;
			prevPrayerXp = -1;
			invSnap = null;
			sacrificeDecThisTick = 0;
			offeringXpThisTick = 0;
			offeringCastTick = -1;
		}
	}

	/**
	 * The offering colour currently on the local player (a key of {@link #OFFERING_GFX}, i.e.
	 * 1871 or 1872), or -1 if none is present. Checked at tick-end, where the spotanim is
	 * reliably applied. Reads the single legacy {@code getGraphic} slot first — each cast
	 * overwrites it, so it is the freshest, unambiguous colour — and falls back to a fixed-order
	 * {@code hasSpotAnim} scan only if that slot ever stops carrying the colour.
	 */
	private int activeOfferingColour()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return -1;
		}
		// The cast colour occupies the single legacy graphic slot and is overwritten by every new
		// cast, so at tick-end getGraphic() is unambiguously THIS cast's colour — never a previous
		// cast's spotanim still lingering in another slot. Prefer it.
		int g = me.getGraphic();
		if (OFFERING_GFX.containsKey(g))
		{
			return g;
		}
		// Defensive net for a hypothetical client that only ever applies the colour to a non-legacy
		// slot: scan in a FIXED order (not Map.of's unspecified key order). One cast per tick plus
		// the cast cooldown means both colours can't be freshly cast together, so a hit is safe.
		if (me.hasSpotAnim(GFX_DEMONIC))
		{
			return GFX_DEMONIC;
		}
		if (me.hasSpotAnim(GFX_SINISTER))
		{
			return GFX_SINISTER;
		}
		return -1;
	}

	private boolean isAlchActiveNow()
	{
		if (alchSeenTick == client.getTickCount())
		{
			return true;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return false;
		}
		int anim = me.getAnimation();
		return anim == HIGH_ALCH_ANIM || anim == LOW_ALCH_ANIM;
	}
}
