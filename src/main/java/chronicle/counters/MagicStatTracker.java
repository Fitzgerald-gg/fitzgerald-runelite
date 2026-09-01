/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

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

import static chronicle.counters.StatKeys.ASHES_SACRIFICED;
import static chronicle.counters.StatKeys.BONES_SACRIFICED;
import static chronicle.counters.StatKeys.COINS_FROM_ALCHEMY;
import static chronicle.counters.StatKeys.DEMONIC_OFFERING_XP;
import static chronicle.counters.StatKeys.DEMONIC_OFFERINGS_CAST;
import static chronicle.counters.StatKeys.SINISTER_OFFERING_XP;
import static chronicle.counters.StatKeys.OFFENSIVE_SPELLS_CAST;
import static chronicle.counters.StatKeys.SINISTER_OFFERINGS_CAST;

/**
 * Magic counters read off the local player's cast animation: alchemy coins, offensive
 * casts, and the two Arceuus offerings.
 */
public class MagicStatTracker implements StatTracker
{
	private static final int HIGH_ALCH_ANIM = 713;
	private static final int LOW_ALCH_ANIM = 712;

	// Demonic and Sinister Offering share this cast animation and differ only by their
	// spotanim, so the colour below is what tells them apart. Reanimation uses other colours.
	private static final int OFFERING_CAST_ANIM = 8975;
	private static final int GFX_DEMONIC = 1871;   // ashes (soul + wrath runes)
	private static final int GFX_SINISTER = 1872;  // bones (blood + wrath runes)
	// Colour to spell name. If the names ever swap, this map is the only edit.
	private static final Map<Integer, String> OFFERING_GFX = Map.of(
		GFX_DEMONIC, DEMONIC_OFFERINGS_CAST,
		GFX_SINISTER, SINISTER_OFFERINGS_CAST);
	// What each colour actually eats, probed in game.
	private static final Map<Integer, String> OFFERING_SAC = Map.of(
		GFX_DEMONIC, ASHES_SACRIFICED,
		GFX_SINISTER, BONES_SACRIFICED);
	// An offering costs runes plus 1-3 bones/ashes. Exclude the runes and what's left is
	// the sacrifice, which saves keeping a list of every bone and ash.
	private static final Set<Integer> OFFERING_RUNES = Set.of(565, 566, 21880);   // blood, soul, wrath

	// Cast poses that count as an offensive spell. A whole tier shares one pose; standard casts
	// have separate with-staff and without-staff poses. Alch, enchant, charge-orb, teleport and
	// powered-staff poses are out. 811 covers the god spells and the Charge self-buff, so a god
	// spell user's occasional Charge is counted.
	private static final Set<Integer> OFFENSIVE_CAST_ANIMS = Set.of(
		711,    // strike / bolt / blast, no staff
		1162,   // strike / bolt / blast, with a staff
		727,    // wave, no staff
		1167,   // wave, with a staff
		7855,   // surge (all four elements)
		1978,   // Ancient single target (rush / blitz)
		1979,   // Ancient multi target (burst / barrage)
		8977,   // Arceuus Demonbane
		811,    // god spells (Saradomin Strike / Claws of Guthix / Flames of Zamorak)
		708,    // Iban Blast
		1576,   // Magic Dart
		724);   // Crumble Undead

	private final StatStore store;
	private final Client client;

	// Coins at the last inventory event. -1 until primed — the stack already in the pack
	// at login is not income.
	private int lastCoins = -1;
	// A coin gain this tick still waiting to see if an alch pose explains it.
	private int bufferedCoinGain;
	// Tick an alch pose was seen. A coin gain arriving after it still matches.
	private int alchSeenTick = -1;
	// Last counted cast, to swallow a same-tick re-fire.
	private int lastCastAnim = -1;
	private int lastCastTick = -1;
	// Previous inventory, to work out what an offering cast consumed.
	private Map<Integer, Integer> invSnap = null;
	// Non-rune items that left the pack this tick (the sacrificed bones/ashes).
	private int sacrificeDecThisTick = 0;
	// Tick an offering was cast; onGameTick resolves which spell it was and credits it.
	private int offeringCastTick = -1;
	// Prayer career XP last seen, to derive what an offering awarded. -1 = unprimed.
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
			// The colour that separates Demonic from Sinister often isn't applied yet when this
			// event fires, so mark the tick and let onGameTick read it once the tick has settled.
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
		// Tally non-rune items leaving the pack; if an offering was cast this tick, onGameTick
		// credits them as bones/ashes sacrificed. Runes are the spell cost.
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
			lastCoins = coins;       // prime; don't count the opening stack
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
			// The coin event may have beaten the alch pose; let onGameTick decide at tick end.
			bufferedCoinGain += gain;
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		// An offering's cast, inventory change and XP drop all land before this tick-end event.
		if (offeringCastTick == client.getTickCount())
		{
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
		// Prayer XP delta for the tick. onGameTick credits it to an offering cast on the same
		// tick; burying and scattering land on other ticks.
		if (event.getSkill() != Skill.PRAYER)
		{
			return;
		}
		int xp = event.getXp();
		if (prevPrayerXp < 0)
		{
			prevPrayerXp = xp;   // prime; don't count the login initialisation
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
			// Coins, XP and inventory all move unobserved while away. Reprime instead of counting
			// the jump, and drop any half-formed offering cast before it resolves across the gap.
			lastCoins = -1;
			bufferedCoinGain = 0;
			prevPrayerXp = -1;
			invSnap = null;
			sacrificeDecThisTick = 0;
			offeringXpThisTick = 0;
			offeringCastTick = -1;
		}
	}

	// The offering colour on the local player (1871 or 1872), or -1 if none. Called at tick end,
	// where the spotanim has reliably been applied.
	private int activeOfferingColour()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return -1;
		}
		// Every cast overwrites the single legacy graphic slot. At tick end it holds this
		// cast's colour and nothing earlier.
		int g = me.getGraphic();
		if (OFFERING_GFX.containsKey(g))
		{
			return g;
		}
		// Fallback for a client that only ever applies the colour to a non-legacy slot. Fixed
		// order, since Map.of gives no key order; one cast per tick means only one can be fresh.
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
