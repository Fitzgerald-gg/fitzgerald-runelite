/*
 * Copyright (c) 2026, Fitzgerald.gg — BSD 2-Clause (see LICENSE).
 *
 * Coordinator for the native lifetime-counter trackers. Registered on the RuneLite
 * EventBus by FitzgeraldPlugin; fans each subscribed event out to every tracker,
 * which tallies into the shared in-memory StatStore. The plugin's harvest/push loop
 * then ships those counters to fitzgerald.gg.
 */
package gg.fitzgerald.plugin.counters;

import gg.fitzgerald.plugin.FitzgeraldConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
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
import net.runelite.client.plugins.xptracker.XpTrackerService;

@Singleton
public class FitzgeraldCounters
{
	private final Client client;
	private final FitzgeraldConfig config;
	private final StatStore store;
	private final SkillChatBuffer skillBuffer;
	private final ItemManager itemManager;

	// Optional: RuneLite's core XP Tracker plugin. Absent in a dev-mode client
	// (or if the user disables XP Tracker) — SkillingStatTracker null-guards its
	// action-count use, so those specific counters just go quiet.
	@com.google.inject.Inject(optional = true)
	private XpTrackerService xpTrackerService;

	// Built lazily on the first event so the optional xpTrackerService above —
	// populated by field injection AFTER this constructor — is available when we
	// wire the tracker that needs it.
	private StatTracker[] trackers;

	@Inject
	FitzgeraldCounters(Client client, FitzgeraldConfig config, StatStore store,
		SkillChatBuffer skillBuffer, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.store = store;
		this.skillBuffer = skillBuffer;
		this.itemManager = itemManager;
	}

	private StatTracker[] trackers()
	{
		if (trackers == null)
		{
			trackers = new StatTracker[]{
				new GoldStatTracker(store, client),
				new ItemStatTracker(store, client, itemManager),
				new MovementStatTracker(store, client),
				new SkillingStatTracker(store, client, xpTrackerService, skillBuffer),
				new FoodStatTracker(store, client),
				new NPCStatTracker(store),
				new ExperienceStatTracker(store),
				new MagicStatTracker(store, client),
				new RangedStatTracker(store, client),
				new CombatStatTracker(store, client),
			};
		}
		return trackers;
	}

	private boolean active()
	{
		// The journal always counts while the plugin is on (local-first).
		return true;
	}

	/**
	 * Discard every tracker's inferred state.
	 *
	 * <p>Trackers infer from deltas — an inventory snapshot, the previous hitpoint
	 * reading, clicks awaiting confirmation. All of that is only meaningful if we saw
	 * every event in between. After a blind window (the plugin toggled off, or
	 * disabled via config while the game carried on) it is not merely stale but
	 * actively wrong: the next delta would be measured against a world that has since
	 * moved. Dropping the array is the whole reset — they are lazily rebuilt on the
	 * next event, so no tracker needs its own teardown path.
	 */
	public void reset()
	{
		trackers = null;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onMenuOptionClicked(e);
			}
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onWidgetLoaded(e);
			}
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onWidgetClosed(e);
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onGameTick(e);
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onGameStateChanged(e);
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onChatMessage(e);
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onHitsplatApplied(e);
			}
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onAnimationChanged(e);
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onStatChanged(e);
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (active())
		{
			for (StatTracker t : trackers())
			{
				t.onItemContainerChanged(e);
			}
		}
	}
}
