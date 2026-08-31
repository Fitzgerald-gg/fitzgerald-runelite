/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 *
 * Coordinator for the native lifetime-counter trackers. Registered on the RuneLite
 * EventBus by ChroniclePlugin; fans each subscribed event out to every tracker,
 * which tallies into the shared in-memory StatStore. The plugin's harvest/push loop
 * then feeds those counters to the journal (and, when cloud sync is on, the configured server).
 */
package chronicle.counters;

import chronicle.ChronicleConfig;
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

@Singleton
public class ChronicleCounters
{
	private final Client client;
	private final ChronicleConfig config;
	private final StatStore store;
	private final ItemManager itemManager;
	private final SkillDeriver skillDeriver;

	// Where per-consumable gp lands (the plugin wires this to the journal's
	// lifetime consumable-value map). Volatile: set at startUp, read on the
	// client thread when the tracker array is lazily built.
	private volatile java.util.function.BiConsumer<String, Integer> consumableSink;

	// Built lazily on the first event so the consumable sink above — wired at
	// startUp, AFTER this constructor — is in hand when we build the tracker that
	// needs it. reset() leans on the same laziness to rebuild after a blind window.
	// Volatile because reset() arrives from whatever thread stopped or started the
	// plugin (the EDT for a settings-panel toggle) while the client thread is fanning
	// events out here: a plain field could leave that thread reading a stale array
	// forever, or never seeing the rebuild the reset asked for.
	private volatile StatTracker[] trackers;

	@Inject
	ChronicleCounters(Client client, ChronicleConfig config, StatStore store,
		ItemManager itemManager, SkillDeriver skillDeriver)
	{
		this.client = client;
		this.config = config;
		this.store = store;
		this.itemManager = itemManager;
		this.skillDeriver = skillDeriver;
	}

	public void setConsumableSink(java.util.function.BiConsumer<String, Integer> sink)
	{
		this.consumableSink = sink;
	}

	private StatTracker[] trackers()
	{
		// Read the field once. A reset() landing between the null check and the return
		// would otherwise hand the caller a null array to iterate, and every caller is
		// an event handler on the client thread with no defence against that.
		StatTracker[] built = trackers;
		if (built == null)
		{
			built = new StatTracker[]{
				new GoldStatTracker(store, client),
				new ItemStatTracker(store, client, itemManager),
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
