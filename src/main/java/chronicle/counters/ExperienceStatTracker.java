/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;

import static chronicle.counters.StatKeys.TOTAL_XP_GAINED;

/**
 * One running total of XP gained, across every skill including combat. The journal holds
 * the lifetime figure between sessions.
 */
public class ExperienceStatTracker implements StatTracker
{
	private final StatStore store;

	private final Map<Skill, Integer> xpSeen = new EnumMap<>(Skill.class);

	public ExperienceStatTracker(StatStore store)
	{
		this.store = store;
	}

	@Override
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null)
		{
			return;
		}
		int xp = event.getXp();
		Integer prev = xpSeen.put(skill, xp);
		if (prev == null)
		{
			// StatChanged carries a career total, not a gain. the first reading of a skill
			// this session is the baseline; count it and login banks the whole account.
			return;
		}
		int gained = xp - prev;
		if (gained > 0)
		{
			store.incrementStatBy(TOTAL_XP_GAINED, gained);
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		// LOGIN_SCREEN only. LOADING and HOPPING keep the same character and the same
		// career totals; clearing the baseline on those eats the next XP drop.
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			xpSeen.clear();
		}
	}
}
