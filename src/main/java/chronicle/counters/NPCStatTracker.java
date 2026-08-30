/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;

import static chronicle.counters.StatKeys.ANIMALS_PETTED;

/**
 * Counters that hang off nearby NPCs rather than off the local player's own actions.
 *
 * <p>Petting is the only one: the "Pet" menu action plays over two game ticks, so a
 * click is armed on the menu option and banked on the next even tick. That throttle is
 * the whole point — without it a held click would credit twice for one animation.
 */
public class NPCStatTracker implements StatTracker
{
	private final StatStore statStore;

	/** Flips every game tick; petting only banks on the even side of that flip. */
	private boolean evenTick = false;

	/** Set when a "Pet" click is seen, cleared once the credit lands. */
	private boolean pendingPet = false;

	public NPCStatTracker(StatStore statStore)
	{
		this.statStore = statStore;
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if ("Pet".equals(event.getMenuOption()))
		{
			pendingPet = true;
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		if (pendingPet && evenTick)
		{
			statStore.incrementStat(ANIMALS_PETTED);
			pendingPet = false;
		}

		evenTick = !evenTick;
	}
}
