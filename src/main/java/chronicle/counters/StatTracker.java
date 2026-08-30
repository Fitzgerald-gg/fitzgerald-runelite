/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;

/**
 * A lifetime-counter tracker. {@link ChronicleCounters} fans the RuneLite event
 * bus out to every registered tracker, so each one implements only the handlers it
 * cares about — the rest are no-ops via the tracker implementations in this package. Trackers write
 * their tallies into the shared {@link StatStore}; they hold no game knowledge the
 * store needs and never talk to the network directly.
 */
public interface StatTracker
{
	default void onMenuOptionClicked(MenuOptionClicked event) {}

	default void onWidgetLoaded(WidgetLoaded event) {}

	default void onWidgetClosed(WidgetClosed event) {}

	default void onGameTick(GameTick event) {}

	default void onGameStateChanged(GameStateChanged event) {}

	default void onChatMessage(ChatMessage event) {}

	default void onHitsplatApplied(HitsplatApplied event) {}

	default void onAnimationChanged(AnimationChanged event) {}

	default void onStatChanged(StatChanged event) {}

	default void onItemContainerChanged(ItemContainerChanged event) {}
}
