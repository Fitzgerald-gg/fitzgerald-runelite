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
 * <p><b>Eating</b> is driven by the explicit "Eat" menu click, scored only once that
 * item's stack is seen shrinking in the inventory. Hitpoints deliberately play no
 * part in that decision: keying off HP would miscount dropping food on the same tick
 * as a natural regen step. Every food counts, plus a per-food key, with no list to
 * maintain. Drinks and vial-smashing still read off the chat box, which is reliable
 * for those. Natural regeneration has no signal of its own, so it is inferred on each
 * tick: a +1 hitpoint step the most-recent Eat/Drink click can't account for is regen.
 */
@Slf4j
public class FoodStatTracker implements StatTracker
{
	/**
	 * Consumables that restore exactly one hitpoint, so a +1 tick right after using
	 * one is that heal rather than natural regen and must not be counted as both.
	 * Matched as a substring of the menu target.
	 *
	 * <p>Grouped by what they are — the low-heal foods, then the brewed drinks — and
	 * alphabetical within each group, purely so it stays easy to scan when a new one
	 * is added. The membership is a fact of the game; the arrangement is just for
	 * reading.
	 */
	private static final List<String> SINGLE_HP_HEALS = List.of(
		// Foods and non-alcoholic sips that heal one.
		"Anchovies",
		"Cabbage",
		"Chopped onion",
		"Equa leaves",
		"Fresh monkfish",
		"Nettle-water",
		"Onion",
		"Potato",
		"Pot of cream",
		// Ales, ciders and brews — all heal one and self-inflict their stat drain.
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

	/** How long a pending Eat stays open waiting for the item to leave the pack. */
	private static final int EAT_CONFIRM_TICKS = 3;

	private final StatStore store;
	private final Client client;
	private final ItemManager itemManager;

	/** Menu target of the latest Eat/Drink click; null once it has been reconciled. */
	private String lastConsumed;

	/** Boosted hitpoints observed on the previous tick; -1 before the first reading. */
	private int previousHitpoints = -1;

	/**
	 * Foods clicked "Eat" but not yet proven to have left the pack. This is a QUEUE,
	 * not a single slot: combo-eating (a food plus a karambwan on the same tick) and
	 * fast successive clicks both produce several in flight at once, and a single slot
	 * would drop all but the last.
	 */
	private final List<PendingEat> pendingEats = new ArrayList<>();

	/** Safety cap so a stream of clicks that never resolve can't grow unbounded. */
	private static final int MAX_PENDING_EATS = 8;

	private static final class PendingEat
	{
		/** Item id, not name: noted and unnoted forms share a name but never an id. */
		private final int itemId;
		private int ticksLeft;

		private PendingEat(int itemId, int ticksLeft)
		{
			this.itemId = itemId;
			this.ticksLeft = ticksLeft;
		}
	}

	/** Previous inventory contents, used to spot the eaten stack shrinking. */
	private Map<Integer, Integer> inventorySnapshot;

	public FoodStatTracker(StatStore statStore, Client client, ItemManager itemManager)
	{
		this.itemManager = itemManager;
		this.store = statStore;
		this.client = client;
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Stash what the player just chose to consume so the tick handler can
		// distinguish a 1 HP heal from a tick of natural regen. Eat targets arrive
		// with colour tags, so strip them now; Drink targets are kept as-is and
		// cleaned later only if we need to inspect them.
		if ("Eat".equals(event.getMenuOption()))
		{
			lastConsumed = Text.removeTags(event.getMenuTarget());
			// Queue a confirmation. The eat is only scored once this exact item id
			// leaves the pack, so an interrupted click never counts — and because the
			// trigger is the "Eat" option (never hitpoints), dropping food while a
			// natural regen tick lands cannot be mistaken for eating.
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

		// Only a single-point rise can be regen. If we can't pin that rise on a 1 HP
		// food, count it as passive regeneration.
		//
		// Known imperfection inherited from the heuristic: eating a multi-point food
		// while sitting exactly one below the HP cap yields a +1 step that is
		// indistinguishable from regen, so it is occasionally miscounted. Rare enough
		// to leave as-is.
		if (previousHitpoints != -1 && hitpoints == previousHitpoints + 1)
		{
			if (lastConsumed == null || !isSingleHpHeal(lastConsumed))
			{
				store.incrementStat(HITPOINTS_REGENERATED);
			}
			else
			{
				// The rise was the 1 HP food itself. Drop the reference so its heal is
				// swallowed at most once; worst case, a single regen tick is lost per eat.
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
		// Any gap in event delivery makes both the snapshot and the queue lies: the
		// pack can change unobserved while we are away, and onGameTick stops firing
		// so pending eats never age out. Coming back, a stale snapshot would read
		// those unobserved departures as consumption. Drop everything and rebuild
		// from the first inventory event after we are back in-game.
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			reset();
		}
	}

	/** Drop all inferred state. Safe to call at any time; the next tick rebuilds it. */
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
		// portion foods (pizzas, cakes) still shrink the whole-item stack, so they
		// count once per bite as expected.
		if (!pendingEats.isEmpty() && inventorySnapshot != null)
		{
			for (Map.Entry<Integer, Integer> before : inventorySnapshot.entrySet())
			{
				int consumed = before.getValue() - current.getOrDefault(before.getKey(), 0);
				if (consumed <= 0)
				{
					continue;   // that stack didn't shrink
				}
				// No eat removes two units of one item id at once — the game's eat delay
				// is several ticks — so a multi-unit shrink is a drop, a bank deposit,
				// a trade or a death, never a meal. Scoring it would invent counts, so
				// only a shrink of exactly one can redeem a pending eat. Never break out
				// of the outer loop: a combo-eat shrinks TWO stacks in a single event,
				// and each is a separate item id shrinking by one.
				if (consumed != 1 || !takePendingEat(before.getKey()))
				{
					continue;
				}
				store.incrementStat(FOOD_EATEN);
				// Consumed-gp: priced at the moment of the bite from the client's
				// own GE feed — the same price-at-record pattern drops use.
				int price = itemManager.getItemPrice(itemManager.canonicalize(before.getKey()));
				if (price > 0)
				{
					store.incrementStatBy(CONSUMED_VALUE, price);
				}
				String typed = perFoodKey(itemName(before.getKey()));
				if (!typed.isEmpty())
				{
					store.incrementStat(typed);
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
		try
		{
			for (ItemPrice p : itemManager.search(potion + "(4)"))
			{
				if (p.getName().equalsIgnoreCase(potion + "(4)"))
				{
					return p.getPrice() / 4;
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// price cache unavailable — the dose goes unpriced, never guessed
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
		StringBuilder key = new StringBuilder();
		// Drop apostrophes before splitting so a possessive collapses into the word it
		// belongs to: "Chef's delight" is chefsDelight, not chefSDelight.
		String cleaned = baseFoodName(foodName).trim().toLowerCase().replace("'", "").replace("’", "");
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
		return key.length() == 0 ? "" : key.append("Eaten").toString();
	}

	/**
	 * Fold a part-eaten food back onto the whole item it came from, so the bites of
	 * one cake all land on cakeEaten rather than scattering across cakeEaten,
	 * 23CakeEaten and 13CakeEaten. Digits survive the key builder, so leaving the
	 * portion prefix in place would mint a junk key per bite.
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

		// Eating is NOT counted here. The "You eat the ..." line only fires for a
		// handful of foods, so most meals went uncounted; it is now driven by the
		// "Eat" click confirmed against the inventory (see onItemContainerChanged).
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
				// Consumed-gp for a dose: a quarter of the 4-dose GE price — an
				// estimate (chat carries no item id), erring honest-low for
				// anything unpriced.
				int dose = dosePrice(potionName(message));
				if (dose > 0)
				{
					store.incrementStatBy(CONSUMED_VALUE, dose);
				}
				// Per-potion tally beside the aggregate — the same rule that
				// mints per-food keys, so "Prayer potion" -> prayerPotionDoses
				// with no list to maintain.
				String typed = perPotionKey(potionName(message));
				if (!typed.isEmpty())
				{
					store.incrementStat(typed);
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

	/** True when the (tag-free) menu target names one of the single-hitpoint heals. */
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

	/** Extract the item name from a consume line: the text between "the " and the trailing dot. */
	private static String consumableName(String message)
	{
		int from = message.indexOf("the ") + "the ".length();
		int to = message.indexOf(".");
		return message.substring(from, to).trim();
	}

	/**
	 * Extract the potion name from "You drink some of the/your &lt;x&gt;." —
	 * handled separately from {@link #consumableName} because the "your" form
	 * has no "the " to split on.
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

	/**
	 * "Prayer potion" -&gt; "prayerPotionDoses" — the same minting rule as
	 * {@link #perFoodKey}, so new potions need no config either.
	 */
	static String perPotionKey(String potionName)
	{
		StringBuilder key = new StringBuilder();
		String cleaned = potionName.trim().toLowerCase().replace("'", "").replace("’", "");
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
		return key.length() == 0 ? "" : key.append("Doses").toString();
	}
}
