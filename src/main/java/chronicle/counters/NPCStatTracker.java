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
 * Pet-petting, counted off nearby NPCs.
 */
public class NPCStatTracker implements StatTracker
{
	private final StatStore statStore;

	private boolean evenTick = false;
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
		// the pet animation runs over two ticks, so bank on the even one or a held click credits twice
		if (pendingPet && evenTick)
		{
			statStore.incrementStat(ANIMALS_PETTED);
			pendingPet = false;
		}

		evenTick = !evenTick;
	}
}
