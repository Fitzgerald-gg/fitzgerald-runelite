/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.util.Text;

import static chronicle.counters.StatKeys.BEERS_DRUNK;
import static chronicle.counters.StatKeys.DIVINE_POTION_DAMAGE;
import static chronicle.counters.StatKeys.CONSUMED_VALUE;
import static chronicle.counters.StatKeys.FOOD_EATEN;
import static chronicle.counters.StatKeys.HITPOINTS_REGENERATED;
import static chronicle.counters.StatKeys.POTION_DOSES;
import static chronicle.counters.StatKeys.VIALS_SHATTERED;

/**
 * Counts eating, drinking and passive hitpoint regeneration.
 *
 * <p>An eat is scored off the "Eat" menu click, once that item's stack is seen
 * shrinking in the inventory; hitpoints play no part, since dropping food on a natural
 * regen tick looks identical. Drinks and vial-smashing read off the chat box. Regen has
 * no signal of its own, so a +1 hitpoint step no recent Eat/Drink explains is regen.
 */
@Slf4j
public class FoodStatTracker implements StatTracker
{
	// Consumables that heal exactly one hitpoint. A +1 tick straight after one of these
	// is that heal, not regen. Matched as a substring of the menu target.
	private static final List<String> SINGLE_HP_HEALS = List.of(
		"Anchovies",
		"Cabbage",
		"Chopped onion",
		"Equa leaves",
		"Fresh monkfish",
		"Nettle-water",
		"Onion",
		"Potato",
		"Pot of cream",
		"Asgarnian ale",
		"Axeman's folly",
		"Bandit's brew",
		"Beer",
		"Chef's delight",
		"Cider",
		"Dragon bitter",
		"Dwarven stout",
		"Elven dawn",
		"Greenman's ale",
		"Kovac's grog",
		"Slayer's respite",
		"Wizard's mind bomb");

	// How long a pending Eat waits for the item to leave the pack.
	private static final int EAT_CONFIRM_TICKS = 3;

	private final StatStore store;
	private final Client client;
	private final ItemManager itemManager;

	// Menu target of the latest Eat/Drink click; null once reconciled.
	private String lastConsumed;

	// Boosted hitpoints from the previous tick; -1 before the first reading.
	private int previousHitpoints = -1;

	// Foods clicked "Eat" but not yet seen leaving the pack. A queue because
	// combo-eating (food plus karambwan on one tick) puts several in flight at once.
	private final List<PendingEat> pendingEats = new ArrayList<>();

	// Cap so clicks that never resolve can't grow the queue unbounded.
	private static final int MAX_PENDING_EATS = 8;

	private static final class PendingEat
	{
		// Keyed by id, because noted and unnoted forms share a name.
		private final int itemId;
		private int ticksLeft;

		private PendingEat(int itemId, int ticksLeft)
		{
			this.itemId = itemId;
			this.ticksLeft = ticksLeft;
		}
	}

	// Previous inventory contents, for spotting the eaten stack shrink.
	private Map<Integer, Integer> inventorySnapshot;

	// Dose price per potion name, held for the session. The lookup behind it walks
	// every tradeable item name on the client thread, and a 4-dose price won't move
	// underneath a session.
	private final Map<String, Integer> dosePrices = new HashMap<>();

	// Journal sink for per-consumable gp (typed key -> price at use). Null in tests, and
	// until ChronicleCounters builds this tracker, which it defers until the plugin has
	// wired the sink.
	private final java.util.function.BiConsumer<String, Integer> consumableSink;

	public FoodStatTracker(StatStore statStore, Client client, ItemManager itemManager,
		java.util.function.BiConsumer<String, Integer> consumableSink)
	{
		this.itemManager = itemManager;
		this.store = statStore;
		this.client = client;
		this.consumableSink = consumableSink;
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Stash what was just consumed so the tick handler can tell a 1 HP heal from a
		// regen tick. Eat targets arrive with colour tags; Drink targets are cleaned
		// later, only if we need to look at them.
		if ("Eat".equals(event.getMenuOption()))
		{
			lastConsumed = Text.removeTags(event.getMenuTarget());
			// Scored only once this exact item id leaves the pack, so an interrupted
			// click never counts.
			if (pendingEats.size() < MAX_PENDING_EATS)
			{
				pendingEats.add(new PendingEat(event.getItemId(), EAT_CONFIRM_TICKS));
			}
		}
		else if ("Drink".equals(event.getMenuOption()))
		{
			lastConsumed = event.getMenuTarget();
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		int hitpoints = client.getBoostedSkillLevel(Skill.HITPOINTS);

		// Only a single-point rise can be regen. If it can't be pinned on a 1 HP food,
		// call it passive regeneration.
		//
		// Imperfection in the heuristic: eating a multi-point food while sitting one
		// below the HP cap gives a +1 step indistinguishable from regen. Rare enough to
		// leave alone.
		if (previousHitpoints != -1 && hitpoints == previousHitpoints + 1)
		{
			if (lastConsumed == null || !isSingleHpHeal(lastConsumed))
			{
				store.incrementStat(HITPOINTS_REGENERATED);
			}
			else
			{
				// The rise was the 1 HP food. Drop the reference so its heal swallows at
				// most one regen tick.
				lastConsumed = null;
			}
		}

		previousHitpoints = hitpoints;

		// Age the queue; a click that never produced a consumed item just expires.
		for (Iterator<PendingEat> it = pendingEats.iterator(); it.hasNext(); )
		{
			if (--it.next().ticksLeft <= 0)
			{
				it.remove();
			}
		}
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		// Away from LOGGED_IN the pack changes unobserved and ticks stop firing. The
		// snapshot and the queue both go stale. Rebuild from the first inventory event
		// after we're back in-game.
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			reset();
		}
		// Remembered dose prices are world-wide GE figures carrying nothing personal,
		// but the login screen is the one transition where nothing at all survives. A
		// region cross keeps them.
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			dosePrices.clear();
		}
	}

	// Safe to call any time; the next tick rebuilds.
	private void reset()
	{
		pendingEats.clear();
		inventorySnapshot = null;
		lastConsumed = null;
		previousHitpoints = -1;
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getItemContainer() != client.getItemContainer(InventoryID.INVENTORY))
		{
			return;
		}

		Map<Integer, Integer> current = new HashMap<>();
		for (Item item : event.getItemContainer().getItems())
		{
			if (item != null && item.getId() >= 0)
			{
				current.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}

		// Confirm a pending Eat: the clicked food's own stack must have shrunk. Multi
		// portion foods (pizzas, cakes) shrink the whole-item stack and count once per
		// bite.
		if (!pendingEats.isEmpty() && inventorySnapshot != null)
		{
			for (Map.Entry<Integer, Integer> before : inventorySnapshot.entrySet())
			{
				int consumed = before.getValue() - current.getOrDefault(before.getKey(), 0);
				if (consumed <= 0)
				{
					continue;   // that stack didn't shrink
				}
				// The eat delay is several ticks, so no eat removes two units of one item
				// id at once: a multi-unit shrink is a drop, a deposit, a trade or a
				// death. Don't break out of the loop either: a combo-eat shrinks two
				// stacks in one event.
				if (consumed != 1 || !takePendingEat(before.getKey()))
				{
					continue;
				}
				store.incrementStat(FOOD_EATEN);
				// Priced at the bite off the client's own GE feed, like drops at the kill.
				int price = itemManager.getItemPrice(itemManager.canonicalize(before.getKey()));
				if (price > 0)
				{
					store.incrementStatBy(CONSUMED_VALUE, price);
				}
				String typed = perFoodKey(itemName(before.getKey()));
				if (!typed.isEmpty())
				{
					store.incrementStat(typed);
					// Cost filed under the same typed key as the count.
					if (price > 0 && consumableSink != null)
					{
						consumableSink.accept(typed, price);
					}
				}
			}
		}

		inventorySnapshot = current;
	}

	/** A dose's worth: the 4-dose item's GE price over four, or 0 unknown. */
	private int dosePrice(String potion)
	{
		if (potion == null || potion.isEmpty())
		{
			return 0;
		}
		Integer known = dosePrices.get(potion);
		if (known != null)
		{
			return known;
		}
		String fourDose = potion + "(4)";
		try
		{
			List<ItemPrice> matches = itemManager.search(fourDose);
			for (ItemPrice p : matches)
			{
				if (p.getName().equalsIgnoreCase(fourDose))
				{
					int dose = p.getPrice() / 4;
					dosePrices.put(potion, dose);
					return dose;
				}
			}
			// An empty result can just mean the client's price list hasn't loaded, and
			// caching a zero from that would leave the potion unpriced all session. Only
			// a search that came back with something proves there's no 4-dose form.
			if (!matches.isEmpty())
			{
				dosePrices.put(potion, 0);
			}
		}
		catch (RuntimeException ignored)
		{
			// price cache unavailable; the dose goes unpriced rather than guessed
		}
		return 0;
	}

	private String itemName(int itemId)
	{
		ItemComposition definition = client.getItemDefinition(itemId);
		return definition == null ? "" : definition.getName();
	}

	/** Remove one queued eat matching this item; false when none is waiting on it. */
	private boolean takePendingEat(int itemId)
	{
		for (Iterator<PendingEat> it = pendingEats.iterator(); it.hasNext(); )
		{
			if (it.next().itemId == itemId)
			{
				it.remove();
				return true;
			}
		}
		return false;
	}

	/**
	 * "Lobster" -&gt; "lobsterEaten". New foods need no config, and the historical
	 * troutEaten / cabbageEaten names fall out of the same rule.
	 */
	static String perFoodKey(String foodName)
	{
		return consumableKey(baseFoodName(foodName), "Eaten");
	}

	/**
	 * Camel-case a consumable's name and stamp the suffix on: "Prayer potion" + "Doses"
	 * -&gt; prayerPotionDoses. Empty in, empty out.
	 *
	 * <p>Locale.ROOT because these keys are written into the journal and read back on
	 * whatever machine opens it. A Turkish JVM lowercases I to a dotless i, which the
	 * a-z split below then throws away, minting a key no other client would agree with.
	 */
	private static String consumableKey(String name, String suffix)
	{
		StringBuilder key = new StringBuilder();
		// Apostrophes go before the split so a possessive collapses into its word:
		// "Chef's delight" is chefsDelight, not chefSDelight.
		String cleaned = name.trim().toLowerCase(Locale.ROOT).replace("'", "").replace("’", "");
		for (String word : cleaned.split("[^a-z0-9]+"))
		{
			if (word.isEmpty())
			{
				continue;
			}
			if (key.length() == 0)
			{
				key.append(word);
			}
			else
			{
				key.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return key.length() == 0 ? "" : key.append(suffix).toString();
	}

	/**
	 * Fold a part-eaten food back onto the whole item: every bite of one cake lands on
	 * cakeEaten. Digits survive the key builder: leave the portion prefix on and you
	 * get 23CakeEaten and friends.
	 */
	static String baseFoodName(String foodName)
	{
		String name = foodName.trim();
		// "1/2 plain pizza", "2/3 cake" -> drop the portion prefix.
		name = name.replaceFirst("^\\d+\\s*/\\s*\\d+\\s+", "");
		// "Half a pineapple pizza", "Half an admiral pie" -> drop the qualifier.
		name = name.replaceFirst("(?i)^half\\s+an?\\s+", "");
		// "Slice of cake" is the last bite of a Cake; "Part <x> pie" likewise.
		name = name.replaceFirst("(?i)^slice\\s+of\\s+", "");
		name = name.replaceFirst("(?i)^part\\s+", "");
		return name.isEmpty() ? foodName.trim() : name;
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.SPAM
			&& type != ChatMessageType.GAMEMESSAGE
			&& type != ChatMessageType.MESBOX)
		{
			return;
		}

		String message = event.getMessage();

		// Eating isn't counted here. The "You eat the ..." line fires for only a handful
		// of foods; most meals never produced one. See onItemContainerChanged.
		if (message.contains("You drink"))
		{
			// Drinks read "You drink the <x>.", potions read "You drink some of
			// the/your <x>.".
			String drunk = consumableName(message);
			log.debug("Parsed drunk item: {}", drunk);

			if (drunk.equals("beer"))
			{
				store.incrementStat(BEERS_DRUNK);
			}

			if (message.contains("You drink some of the") || message.contains("You drink some of your"))
			{
				store.incrementStat(POTION_DOSES);
				// A quarter of the 4-dose price. Chat carries no item id, so this is an
				// estimate, and anything unpriced errs low.
				int dose = dosePrice(potionName(message));
				if (dose > 0)
				{
					store.incrementStatBy(CONSUMED_VALUE, dose);
				}
				// Per-potion tally beside the aggregate: "Prayer potion" -> prayerPotionDoses.
				String typed = perPotionKey(potionName(message));
				if (!typed.isEmpty())
				{
					store.incrementStat(typed);
					if (dose > 0 && consumableSink != null)
					{
						consumableSink.accept(typed, dose);
					}
				}

				if (message.contains("divine"))
				{
					// Divine potions always self-inflict a flat 10 damage.
					store.incrementStatBy(DIVINE_POTION_DAMAGE, 10);
				}
			}
		}

		if (message.contains("You quickly smash the empty vial"))
		{
			store.incrementStat(VIALS_SHATTERED);
		}
	}

	private static boolean isSingleHpHeal(String menuTarget)
	{
		String cleaned = Text.removeTags(menuTarget);
		for (String heal : SINGLE_HP_HEALS)
		{
			if (cleaned.contains(heal))
			{
				return true;
			}
		}
		return false;
	}

	/** The item name out of a consume line: the text between "the " and the trailing dot. */
	private static String consumableName(String message)
	{
		int from = message.indexOf("the ") + "the ".length();
		int to = message.indexOf(".");
		return message.substring(from, to).trim();
	}

	/**
	 * The potion name out of "You drink some of the/your &lt;x&gt;." Separate from
	 * {@link #consumableName} because the "your" form has no "the " to split on.
	 */
	static String potionName(String message)
	{
		int from;
		int the = message.indexOf(" of the ");
		int your = message.indexOf(" of your ");
		if (the >= 0)
		{
			from = the + " of the ".length();
		}
		else if (your >= 0)
		{
			from = your + " of your ".length();
		}
		else
		{
			return "";
		}
		int to = message.indexOf('.', from);
		return to > from ? message.substring(from, to).trim() : "";
	}

	/** "Prayer potion" -&gt; "prayerPotionDoses". No portion folding; a chat line names the whole potion. */
	static String perPotionKey(String potionName)
	{
		return consumableKey(potionName, "Doses");
	}
}
