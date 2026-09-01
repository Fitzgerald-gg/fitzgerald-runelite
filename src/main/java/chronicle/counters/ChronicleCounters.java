/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Coordinator for the lifetime-counter trackers. ChroniclePlugin registers this on the
 * RuneLite EventBus; it hands each subscribed event to every tracker, and they tally
 * into the shared in-memory {@link StatStore} that the plugin folds into the journal.
 */
@Slf4j
@Singleton
public class ChronicleCounters
{
	private final Client client;
	private final StatStore store;
	private final ItemManager itemManager;
	private final SkillDeriver skillDeriver;

	// Per-consumable gp lands here; the plugin points it at the journal's lifetime
	// consumable map. Volatile: set at startUp, read on the client thread in trackers().
	private volatile java.util.function.BiConsumer<String, Integer> consumableSink;

	// The account's gathered-item ledger, wired at startUp and read on the client
	// thread in trackers(). Volatile on the same grounds as the sink.
	private volatile GatheredLedger gatheredLedger;

	// Built on the first event so the sink and ledger above (wired at startUp, after
	// this constructor runs) are in hand by then. Volatile because reset() can arrive
	// on the EDT from a settings toggle while the client thread is fanning out here.
	private volatile StatTracker[] trackers;

	@Inject
	ChronicleCounters(Client client, StatStore store, ItemManager itemManager,
		SkillDeriver skillDeriver)
	{
		this.client = client;
		this.store = store;
		this.itemManager = itemManager;
		this.skillDeriver = skillDeriver;
	}

	public void setConsumableSink(java.util.function.BiConsumer<String, Integer> sink)
	{
		this.consumableSink = sink;
	}

	// SkillDeriver notes each gather into the ledger; the drop tracker reads it back
	// when an item is binned. It is a Guice singleton, not one of the lazily built
	// trackers, hence the second hand-off here.
	public void setGatheredLedger(GatheredLedger ledger)
	{
		this.gatheredLedger = ledger;
		skillDeriver.setGatheredLedger(ledger);
	}

	private StatTracker[] trackers()
	{
		// Read the field once. A reset() landing between the null check and the return
		// hands the caller a null array to iterate.
		StatTracker[] built = trackers;
		if (built == null)
		{
			built = new StatTracker[]{
				new GoldStatTracker(store, client),
				new ItemStatTracker(store, client, itemManager, gatheredLedger),
				new MovementStatTracker(store, client),
				new SkillingStatTracker(store, client, skillDeriver),
				new FoodStatTracker(store, client, itemManager, consumableSink),
				new NPCStatTracker(store),
				new ExperienceStatTracker(store),
				new MagicStatTracker(store, client),
				new RangedStatTracker(store, client),
				new CombatStatTracker(store, client),
			};
			trackers = built;
		}
		return built;
	}

	/**
	 * Drop every tracker's inferred state. Trackers work from deltas: an inventory
	 * snapshot, the last hitpoint reading, a click awaiting confirmation. After a
	 * blind window the next delta is measured against a world that has since moved.
	 * Nulling the array is all there is to it; trackers() rebuilds on the next event.
	 */
	public void reset()
	{
		trackers = null;
	}

	// Catch per tracker. One throwing on a line it did not expect otherwise robs every
	// tracker after it of the event, with nothing to say why the counters stalled.
	// Errors propagate; only a tracker's own bad reasoning is ours to swallow.
	private void fanOut(java.util.function.Consumer<StatTracker> delivery)
	{
		for (StatTracker t : trackers())
		{
			try
			{
				delivery.accept(t);
			}
			catch (RuntimeException ex)
			{
				log.debug("{} failed on an event", t.getClass().getSimpleName(), ex);
			}
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		fanOut(t -> t.onMenuOptionClicked(e));
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		fanOut(t -> t.onWidgetLoaded(e));
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		fanOut(t -> t.onWidgetClosed(e));
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		fanOut(t -> t.onGameTick(e));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		fanOut(t -> t.onGameStateChanged(e));
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		fanOut(t -> t.onChatMessage(e));
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		fanOut(t -> t.onHitsplatApplied(e));
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		fanOut(t -> t.onAnimationChanged(e));
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		fanOut(t -> t.onStatChanged(e));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		fanOut(t -> t.onItemContainerChanged(e));
	}
}
