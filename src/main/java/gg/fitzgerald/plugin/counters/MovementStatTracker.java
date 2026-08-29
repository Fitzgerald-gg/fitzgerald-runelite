/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin.counters;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

import static gg.fitzgerald.plugin.counters.StatKeys.*;

/**
 * Tallies how the player gets around: tiles covered on foot (split by run vs. walk)
 * and every teleport, attributed to its destination.
 *
 * <p><b>Why teleports are detected by position, not animation.</b> Teleport methods
 * do not share one animation — a spellbook cast, a teleport tab, a construction cape
 * and the POH portal nexus each animate differently (the nexus fires none this code
 * can key off), so keying on a fixed animation id silently drops whole methods. What
 * they DO share is the effect: the player is moved far in a single tick. So a teleport
 * is recognised as that jump, and attributed from the click that set it up — the
 * spell/tab/cape menu option, or the destination row read out of the nexus interface.
 * The jump credit is GATED on a recent teleport-initiating click, so the other things
 * that move you a long way in one tick (login, world hop, entering an instance, a
 * staircase) are never miscounted: none of them is preceded by such a click.
 *
 * <p>Fairy rings and spirit trees keep their own dedicated signals (an animation and a
 * dialog): they credit directly and never arm the click gate, so the jump path leaves
 * them alone.
 */
public class MovementStatTracker implements StatTracker
{
	// Run-energy orb, plus the sprite it shows while run is toggled on.
	private static final int RUN_ORB_WIDGET_ID = 10485793;
	private static final int RUN_ENABLED_SPRITE_ID = 1070;

	// Spirit-tree confirmation dialog: interface group and the chat-line child.
	private static final int SPIRIT_TREE_GROUP = 193;
	private static final int SPIRIT_TREE_MESSAGE_CHILD = 2;

	// Fairy rings announce themselves with a distinctive animation, so they are
	// credited on sight rather than through the click-gated jump path.
	private static final int FAIRY_RING_ANIM = 3265;

	// A pending teleport (a click that will land as a jump) stays valid this many
	// ticks: the slowest cast animates ~5 ticks before the move, so a short window
	// covers every method while keeping a failed/cancelled cast from lingering.
	private static final int TELEPORT_PENDING_WINDOW_TICKS = 10;

	// One-tick move (Chebyshev tiles) that on its own counts as a teleport landing.
	// Set above the agility-shortcut ceiling (~8-10 tiles) so a stale pending can't be
	// consumed by a grapple/dive; a region change with any real step (>=3) also counts,
	// which catches every cross-region teleport while a 1-2 tile walk over a region
	// boundary does not.
	private static final int TELEPORT_MIN_JUMP = 15;

	// Ordered destination table covering every portal-nexus row and its spellbook /
	// tablet equivalent: a teleport is attributed to the FIRST substring that occurs
	// in the click label (option + target) or the nexus row text. Substring — not
	// exact — because a nexus row is keybind-prefixed ("5 :  Camelot") and a diary
	// switch carries a place word ("Grand Exchange"). ORDER IS LOAD-BEARING: any
	// substring that contains another must come first, and a secondary diary
	// destination (Grand Exchange, Seers', Yanille) must precede its base town, so a
	// switched cast lands on the switch rather than the base. Several places carry a
	// second alias because the nexus row and the spell/item name differ.
	private static final String[][] DESTINATIONS = {
		// contained-substring pairs: the longer name first
		{"ape atoll dungeon", TELEPORTS_APE_ATOLL_DUNGEON},
		{"ape atoll", TELEPORTS_APE_ATOLL},
		{"west ardougne", TELEPORTS_WEST_ARDOUGNE},
		{"ardougne", TELEPORTS_ARDOUGNE},
		// diary secondary-destination switches: before their base town
		{"grand exchange", TELEPORTS_GRAND_EXCHANGE},   // Varrock medium-diary switch
		{"seers", TELEPORTS_SEERS_VILLAGE},             // Camelot right-click option
		{"yanille", TELEPORTS_YANILLE},                 // Watchtower hard-diary switch
		// standard towns
		{"varrock", TELEPORTS_VARROCK},
		{"camelot", TELEPORTS_CAMELOT},
		{"watchtower", TELEPORTS_WATCHTOWER},
		{"lumbridge", TELEPORTS_LUMBRIDGE},
		{"falador", TELEPORTS_FALADOR},
		{"kourend", TELEPORTS_KOUREND},
		{"civitas", TELEPORTS_FORTIS},
		{"fortis", TELEPORTS_FORTIS},
		{"trollheim", TELEPORTS_TROLLHEIM},
		{"boat", TELEPORTS_BOAT},
		// Ancient Magicks
		{"paddewwa", TELEPORTS_PADDEWWA},
		{"senntisten", TELEPORTS_SENNTISTEN},
		{"kharyrll", TELEPORTS_KHARYRLL},
		{"lassar", TELEPORTS_LASSAR},
		{"dareeyak", TELEPORTS_DAREEYAK},
		{"carrallanger", TELEPORTS_CARRALLANGER},
		{"annakarl", TELEPORTS_ANNAKARL},
		{"ghorrock", TELEPORTS_GHORROCK},
		// Lunar
		{"moonclan", TELEPORTS_MOONCLAN},
		{"lunar isle", TELEPORTS_MOONCLAN},             // nexus row name
		{"ourania", TELEPORTS_OURANIA},
		{"waterbirth", TELEPORTS_WATERBIRTH},
		{"barbarian", TELEPORTS_BARBARIAN_OUTPOST},
		{"khazard", TELEPORTS_KHAZARD},
		{"fishing guild", TELEPORTS_FISHING_GUILD},
		{"catherby", TELEPORTS_CATHERBY},
		{"ice plateau", TELEPORTS_ICE_PLATEAU},
		// Arceuus
		{"arceuus library", TELEPORTS_ARCEUUS_LIBRARY},
		{"draynor manor", TELEPORTS_DRAYNOR_MANOR},
		{"battlefront", TELEPORTS_BATTLEFRONT},
		{"mind altar", TELEPORTS_MIND_ALTAR},
		{"salve graveyard", TELEPORTS_SALVE_GRAVEYARD},
		{"fenkenstrain", TELEPORTS_FENKENSTRAIN},
		{"harmony island", TELEPORTS_HARMONY_ISLAND},
		{"cemetery", TELEPORTS_CEMETERY},
		{"barrows", TELEPORTS_BARROWS},
		{"respawn", TELEPORTS_RESPAWN},
		// tablet / basalt / scroll destinations (nexus rows; the item names differ)
		{"pollnivneach", TELEPORTS_POLLNIVNEACH},
		{"troll stronghold", TELEPORTS_TROLL_STRONGHOLD},
		{"stony basalt", TELEPORTS_TROLL_STRONGHOLD},
		{"weiss", TELEPORTS_WEISS},
		{"icy basalt", TELEPORTS_WEISS},
		// house: spell "Teleport to House" + construction/max cape "Tele to POH"
		{"house", TELEPORTS_HOUSE},
		{"poh", TELEPORTS_HOUSE},
		// Skillcapes. The Fishing cape names its place in the option ("Otto's Grotto",
		// or "Fishing Guild" handled above); the rest fire a bare "Teleport", so match
		// the cape NAME in the target. No cape name is contained in another, and none
		// collides with a place substring, so this block's internal order is free.
		{"otto's grotto", TELEPORTS_OTTOS_GROTTO},
		{"strength cape", TELEPORTS_WARRIORS_GUILD},
		{"crafting cape", TELEPORTS_CRAFTING_GUILD},
		{"farming cape", TELEPORTS_FARMING_GUILD},
		{"hunter cape", TELEPORTS_HUNTER_GUILD},
		{"quest point cape", TELEPORTS_LEGENDS_GUILD},
		{"achievement diary cape", TELEPORTS_DIARY_REGION},
		{"music cape", TELEPORTS_FALO},
		{"sailing cape", TELEPORTS_PANDEMONIUM},
	};

	private final StatStore statStore;
	private final Client client;

	// Player tile from the previous tick, used to measure this tick's step.
	private WorldPoint lastPlayerPos;

	// The click that set up a teleport that has not yet landed: the destination label
	// (spell/tab/cape option, or nexus row text), the tick it was armed, and whether
	// it came from the nexus (so an unrecognised nexus place still counts as Nexus).
	private String pendingLabel;
	private int pendingTick = -1;
	private boolean pendingFromNexus;
	// The method family behind the pending teleport (jewellery/tablet/scroll/
	// spell/cape), or null when the means isn't identifiable from the click.
	private String pendingMethod;

	public MovementStatTracker(StatStore statStore, Client client)
	{
		this.statStore = statStore;
		this.client = client;
	}

	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption() == null ? "" : event.getMenuOption();
		String target = event.getMenuTarget() == null ? "" : event.getMenuTarget();
		String optLow = option.toLowerCase();
		String tgtLow = Text.removeTags(target).toLowerCase();

		// A click on the nexus teleport-list interface (ROWS1/ROWS2 are click hitboxes
		// with no text; the destination NAME sits at the same index in the parallel
		// TEXT1 list, prefixed with its keybind e.g. "5 :  Camelot"), so read
		// TEXT1[param0] — the DESTINATIONS substring match ignores the prefix.
		if ((event.getWidgetId() >> 16) == InterfaceID.TELENEXUS_TELEPORT)
		{
			armTeleport(nexusRowText(event.getParam0()), true);
			return;
		}

		// "Teleport To" / "Teleport Menu" only OPEN the nexus list; the row click above
		// is the real destination, so don't let an opener arm a teleport of its own.
		if (optLow.equals("teleport to") || optLow.equals("teleport menu"))
		{
			return;
		}

		// A POH "Teleport Platform" is stairs by another name — internal house transport,
		// not a teleport to anywhere — so it never counts, despite its "teleport" label.
		if (tgtLow.contains("teleport platform"))
		{
			return;
		}

		// Handling a teleport item in the bank or inventory (its name carries "teleport")
		// is not using it — don't arm on Withdraw/Deposit/Examine and the like.
		if (isInventoryManagement(optLow))
		{
			return;
		}

		// A left-click on the portal nexus with a primary destination set teleports
		// DIRECTLY — no list interface opens — so the OPTION text is the place itself
		// (e.g. "Last Boat", "Great Kourend", "Senntisten"), which the destination
		// table matches by substring. The openers and Examine already returned above;
		// "Configuration" opens the primary-destination editor, not a teleport, so it
		// is the one nexus option to skip. Any other non-teleport click just expires.
		if (tgtLow.contains("portal nexus"))
		{
			if (!optLow.contains("configuration"))
			{
				armTeleport(optLow, true);
			}
			return;
		}

		// Any spell / tab / cape / item teleport. The destination can sit on EITHER the
		// option (a spell's "Cast <place>", the cape's "Tele to POH") or the target (a
		// tab's "<place> teleport", a cape's name), so arm with both joined and let the
		// destination table find the place in whichever half carries it.
		if (optLow.contains("tele") || tgtLow.contains("tele"))
		{
			armTeleport(optLow + " " + tgtLow, false);
			pendingMethod = methodOf(optLow, tgtLow);
			return;
		}

		// Equipped/rubbed teleport jewellery never says "tele" — the option IS the
		// destination ("Castle Wars" on a Ring of dueling) — so these teleports were
		// invisible to the arming above. Arm them off the item name; the option text
		// doubles as the destination label where the table knows it.
		if (isTeleportJewellery(tgtLow) && !isWearHandling(optLow))
		{
			armTeleport(optLow + " " + tgtLow, false);
			pendingMethod = TELEPORTS_VIA_JEWELLERY;
		}
	}

	/** The teleport-jewellery family, matched on the worn/rubbed item's name. */
	private static boolean isTeleportJewellery(String tgtLow)
	{
		return tgtLow.contains("ring of dueling") || tgtLow.contains("games necklace")
			|| tgtLow.contains("amulet of glory") || tgtLow.contains("amulet of eternal glory")
			|| tgtLow.contains("combat bracelet") || tgtLow.contains("skills necklace")
			|| tgtLow.contains("ring of wealth") || tgtLow.contains("burning amulet")
			|| tgtLow.contains("necklace of passage") || tgtLow.contains("digsite pendant")
			|| tgtLow.contains("xeric's talisman") || tgtLow.contains("slayer ring")
			|| tgtLow.contains("ring of returning") || tgtLow.contains("drakan's medallion");
	}

	/** Wearing/removing/checking jewellery is not teleporting with it. */
	private static boolean isWearHandling(String option)
	{
		return option.startsWith("wear") || option.startsWith("wield")
			|| option.startsWith("remove") || option.startsWith("check")
			|| option.startsWith("destroy") || isInventoryManagement(option);
	}

	/** The method family a teleport click reveals, or null when it doesn't. */
	private static String methodOf(String optLow, String tgtLow)
	{
		if (optLow.startsWith("break"))
		{
			return TELEPORTS_VIA_TABLET;
		}
		if (optLow.startsWith("cast"))
		{
			return TELEPORTS_VIA_SPELL;
		}
		if (tgtLow.contains("scroll"))
		{
			return TELEPORTS_VIA_SCROLL;
		}
		if (tgtLow.contains("cape") || tgtLow.contains("max hood") || optLow.contains("tele to poh"))
		{
			return TELEPORTS_VIA_CAPE;
		}
		if (isTeleportJewellery(tgtLow))
		{
			return TELEPORTS_VIA_JEWELLERY;
		}
		return null;
	}

	/** Bank/inventory verbs that name a teleport item without activating it. */
	private static boolean isInventoryManagement(String option)
	{
		return option.startsWith("withdraw") || option.startsWith("deposit")
			|| option.startsWith("examine") || option.startsWith("drop")
			|| option.startsWith("value") || option.startsWith("take")
			|| option.startsWith("bank") || option.startsWith("sell")
			|| option.startsWith("buy") || option.startsWith("use");
	}

	private void armTeleport(String label, boolean fromNexus)
	{
		pendingLabel = label == null ? "" : label.toLowerCase();
		pendingFromNexus = fromNexus;
		pendingTick = client.getTickCount();
		pendingMethod = null;   // callers that know the means set it after arming
	}

	/**
	 * The destination name for a clicked nexus row. The clicked ROWS widget is a bare
	 * hitbox; the label sits at the same index in the parallel TEXT1 list, so read
	 * TEXT1's child at {@code index}.
	 */
	private String nexusRowText(int index)
	{
		Widget textList = client.getWidget(InterfaceID.TelenexusTeleport.TEXT1);
		if (textList == null || index < 0)
		{
			return "";
		}
		Widget[] cells = textList.getChildren();
		if (cells == null || index >= cells.length)
		{
			return "";
		}
		Widget cell = cells[index];
		if (cell == null || cell.getText() == null)
		{
			return "";
		}
		return Text.removeTags(cell.getText()).trim();
	}

	@Override
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != SPIRIT_TREE_GROUP)
		{
			return;
		}

		// The spirit-tree menu shares its group with other dialogs, so confirm via the
		// flavour text before crediting the travel.
		Widget message = client.getWidget(SPIRIT_TREE_GROUP, SPIRIT_TREE_MESSAGE_CHILD);
		if (message != null && message.getText().contains("place your hands on the dry"))
		{
			statStore.incrementStat(TELEPORTS_TOTAL);   // every teleport bumps the total
			statStore.incrementStat(TELEPORTS_SPIRIT_TREE);
		}
	}

	@Override
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		WorldPoint current = local.getWorldLocation();
		if (lastPlayerPos != null && current != null)
		{
			// Chebyshev distance: a normal step covers 1-2 tiles.
			int step = Math.max(
				Math.abs(current.getX() - lastPlayerPos.getX()),
				Math.abs(current.getY() - lastPlayerPos.getY()));
			boolean regionChanged = current.getRegionID() != lastPlayerPos.getRegionID();
			boolean jumped = step >= TELEPORT_MIN_JUMP || (regionChanged && step >= 3);

			if (step > 0 && step < 3)
			{
				boolean running = isRunOrbEnabled();
				statStore.incrementStatBy(running ? DISTANCE_RAN : DISTANCE_WALKED, step);
			}
			else if (jumped && teleportPending())
			{
				creditPendingTeleport();
			}
		}

		lastPlayerPos = current;

		// A pending teleport that never produced a landing (a failed/cancelled cast, or
		// a non-teleport nexus click) is let go once its window lapses, so it can't be
		// attached to a later movement.
		if (pendingTick >= 0 && client.getTickCount() - pendingTick > TELEPORT_PENDING_WINDOW_TICKS)
		{
			pendingLabel = null;
			pendingTick = -1;
			pendingFromNexus = false;
			pendingMethod = null;
		}
	}

	private boolean teleportPending()
	{
		return pendingTick >= 0 && client.getTickCount() - pendingTick <= TELEPORT_PENDING_WINDOW_TICKS;
	}

	/** Credit the pending teleport to its place (or the Nexus catch-all), then clear it. */
	private void creditPendingTeleport()
	{
		statStore.incrementStat(TELEPORTS_TOTAL);
		String key = matchDestinationKey(pendingLabel);
		String credited;
		if (key != null)
		{
			credited = key;
		}
		else if (pendingFromNexus)
		{
			credited = TELEPORTS_NEXUS;   // a nexus place with no key of its own
		}
		else
		{
			credited = null;              // an unrecognised teleport: total only
		}
		if (credited != null)
		{
			statStore.incrementStat(credited);
		}
		if (pendingMethod != null)
		{
			statStore.incrementStat(pendingMethod);   // the means, beside the place
		}
		pendingLabel = null;
		pendingTick = -1;
		pendingFromNexus = false;
		pendingMethod = null;
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		// A scene rebuild is the surest sign a teleport has landed: it fires for every
		// cross-region and instance hop, including the boat, whose instanced arrival the
		// position jump can't see. Gated on a pending teleport, so a login / world hop /
		// instance entry with no teleport click behind it is ignored. Whichever of this
		// and the position jump comes first credits and clears the pending, so a normal
		// teleport that triggers both is still counted exactly once.
		if (event.getGameState() == GameState.LOADING && teleportPending())
		{
			creditPendingTeleport();
		}
	}

	@Override
	public void onAnimationChanged(AnimationChanged event)
	{
		// Fires for every actor, so without the filter a stranger's teleport beside you
		// would credit your counters.
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}
		if (event.getActor().getAnimation() == FAIRY_RING_ANIM)
		{
			statStore.incrementStat(TELEPORTS_TOTAL);   // every teleport bumps the total
			statStore.incrementStat(TELEPORTS_FAIRY_RING);
		}
	}

	/** True when the run-energy orb is showing and toggled to the run sprite. */
	private boolean isRunOrbEnabled()
	{
		Widget orb = client.getWidget(RUN_ORB_WIDGET_ID);
		return orb != null && !orb.isHidden() && orb.getSpriteId() == RUN_ENABLED_SPRITE_ID;
	}

	/**
	 * The teleport counter key a label maps to, or {@code null} if it names no tracked
	 * place. Matches the first {@link #DESTINATIONS} substring that occurs, so a nexus
	 * row's keybind prefix ("5 :  Camelot") is ignored and a nexus hop lands on the
	 * same key as the spell or tab to that place.
	 */
	static String matchDestinationKey(String label)
	{
		if (label == null)
		{
			return null;
		}
		String clean = label.toLowerCase();
		for (String[] destination : DESTINATIONS)
		{
			if (clean.contains(destination[0]))
			{
				return destination[1];
			}
		}
		return null;
	}
}
