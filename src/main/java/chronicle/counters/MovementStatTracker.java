/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

import static chronicle.counters.StatKeys.*;

/**
 * Tallies tiles covered on foot (split run vs walk) and every teleport, attributed to
 * its destination.
 *
 * <p>Teleport methods share no common animation (the portal nexus fires none this code
 * can key off), so a teleport is spotted by its effect: the player moves far in a single
 * tick. Credit for that jump is gated on a recent teleport-initiating click, which keeps
 * logins, world hops, instance entries and staircases out of the count and supplies the
 * destination label. Fairy rings have their own animation and credit straight off it.
 */
public class MovementStatTracker implements StatTracker
{
	// Run-energy orb, and the sprite it shows while run is on.
	private static final int RUN_ORB_WIDGET_ID = 10485793;
	private static final int RUN_ENABLED_SPRITE_ID = 1070;

	private static final int FAIRY_RING_ANIM = 3265;

	// How long a teleport click stays armed. The slowest cast animates ~5 ticks
	// before the move.
	private static final int TELEPORT_PENDING_WINDOW_TICKS = 10;

	// One-tick move (Chebyshev tiles) that counts as a teleport landing on its own.
	// Sits above the agility-shortcut ceiling (~8-10 tiles) so a grapple or dive can't
	// consume a stale pending.
	private static final int TELEPORT_MIN_JUMP = 15;

	// A teleport is attributed to the FIRST substring that occurs in the click label
	// (option + target) or the nexus row text. Substring rather than exact, since a
	// nexus row is keybind-prefixed ("5 :  Camelot"). ORDER IS LOAD-BEARING: any
	// substring that contains another comes first, and a diary switch destination
	// (Grand Exchange, Seers', Yanille) comes before its base town. Some places carry
	// a second alias where the nexus row and the spell/item name differ.
	private static final String[][] DESTINATIONS = {
		// contained-substring pairs: the longer name first
		{"ape atoll dungeon", TELEPORTS_APE_ATOLL_DUNGEON},
		{"ape atoll", TELEPORTS_APE_ATOLL},
		{"marim", TELEPORTS_APE_ATOLL},                 // the POH portal-chamber name for Ape Atoll
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
		{"fortis colosseum", TELEPORTS_COLOSSEUM},   // before "fortis" (the city)
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
		// scroll-of-redirection house tabs, which break as "break <place> teleport"
		{"rimmington", TELEPORTS_RIMMINGTON},
		{"taverley", TELEPORTS_TAVERLEY},
		{"rellekka", TELEPORTS_RELLEKKA},
		{"brimhaven", TELEPORTS_BRIMHAVEN},
		{"hosidius", TELEPORTS_HOSIDIUS},
		{"prifddinas", TELEPORTS_PRIFDDINAS},
		{"teleport crystal", TELEPORTS_PRIFDDINAS},   // "Activate" names no place; the item name does
		// house: the spell says "Teleport to House", the construction/max cape "Tele to POH"
		{"house on the hill", TELEPORTS_FOSSIL_ISLAND},   // before "house" (the POH)
		{"house", TELEPORTS_HOUSE},
		{"poh", TELEPORTS_HOUSE},
		{"spirit tree", TELEPORTS_SPIRIT_TREE},
		{"otto's grotto", TELEPORTS_OTTOS_GROTTO},      // the Fishing cape's other option

		// jewellery destinations: worn or rubbed items, and the POH jewellery box
		{"castle wars", TELEPORTS_CASTLE_WARS},
		{"ferox", TELEPORTS_FEROX_ENCLAVE},
		{"emir", TELEPORTS_EMIRS_ARENA},
		{"duel arena", TELEPORTS_EMIRS_ARENA},         // older wording of the same place
		{"edgeville", TELEPORTS_EDGEVILLE},
		{"karamja", TELEPORTS_KARAMJA},
		{"draynor", TELEPORTS_DRAYNOR},                // "draynor manor" matched above
		{"al kharid", TELEPORTS_AL_KHARID},
		{"burthorpe", TELEPORTS_BURTHORPE},
		{"corporeal", TELEPORTS_CORPOREAL_BEAST},
		{"tears of guthix", TELEPORTS_TEARS_OF_GUTHIX},
		{"wintertodt", TELEPORTS_WINTERTODT_CAMP},
		{"warriors' guild", TELEPORTS_WARRIORS_GUILD},
		{"champions' guild", TELEPORTS_CHAMPIONS_GUILD},
		{"monastery", TELEPORTS_MONASTERY},
		{"ranging guild", TELEPORTS_RANGING_GUILD},
		{"mining guild", TELEPORTS_MINING_GUILD},
		{"woodcutting guild", TELEPORTS_WOODCUTTING_GUILD},
		{"cooking guild", TELEPORTS_COOKING_GUILD},
		{"crafting guild", TELEPORTS_CRAFTING_GUILD},
		{"farming guild", TELEPORTS_FARMING_GUILD},
		{"miscellania", TELEPORTS_MISCELLANIA},
		{"dondakan", TELEPORTS_DONDAKANS_ROCK},        // ring of wealth's Between a Rock option
		{"chaos temple", TELEPORTS_CHAOS_TEMPLE},
		{"bandit camp", TELEPORTS_BANDIT_CAMP},
		{"lava maze", TELEPORTS_LAVA_MAZE},
		{"wizards' tower", TELEPORTS_WIZARDS_TOWER},
		{"outpost", TELEPORTS_THE_OUTPOST},            // "barbarian outpost" matched above
		{"eyrie", TELEPORTS_EAGLES_EYRIE},             // Eagle's Eyrie, necklace of passage
		{"ver sinhaza", TELEPORTS_VER_SINHAZA},
		{"darkmeyer", TELEPORTS_DARKMEYER},
		{"slepe", TELEPORTS_SLEPE},                    // Drakan's medallion's third option
		{"lithkren", TELEPORTS_LITHKREN},              // before "digsite", which the pendant's name carries
		{"fossil island", TELEPORTS_FOSSIL_ISLAND},    // its "house on the hill" alias sits in the house block above
		{"digsite", TELEPORTS_DIGSITE},                // after lithkren and fossil island
		{"xeric", TELEPORTS_KOUREND},
		{"slayer ring", TELEPORTS_SLAYER_DUNGEONS},
		{"pvp arena", TELEPORTS_EMIRS_ARENA},
		{"ring of returning", TELEPORTS_HOUSE},        // its only destination
		{"aldarin", TELEPORTS_ALDARIN},                // another redirected house tab
		// everyday teleport items
		{"ectophial", TELEPORTS_ECTOFUNTUS},
		{"seed pod", TELEPORTS_GRAND_TREE},
		{"chronicle", TELEPORTS_CHAMPIONS_GUILD},      // lands at the guild's door
		{"kharedst", TELEPORTS_KOUREND},               // district tokens above win when present
		{"book of the dead", TELEPORTS_KOUREND},
		{"air altar", TELEPORTS_ELEMENTAL_ALTARS},
		{"water altar", TELEPORTS_ELEMENTAL_ALTARS},
		{"earth altar", TELEPORTS_ELEMENTAL_ALTARS},
		{"fire altar", TELEPORTS_ELEMENTAL_ALTARS},
		{"foundry", TELEPORTS_GIANTS_FOUNDRY},
		{"obelisk", TELEPORTS_OBELISK},
		// capes and items whose own name carries the destination
		{"royal seed pod", TELEPORTS_GRAND_TREE},      // option "Commune"
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

	// player tile last tick, for measuring this tick's step
	private WorldPoint lastPlayerPos;

	// the click behind a teleport that hasn't landed yet: its destination label, the
	// tick it was armed, and whether it came from the nexus (an unrecognised nexus
	// place still counts as Nexus)
	private String pendingLabel;
	private int pendingTick = -1;
	private boolean pendingFromNexus;
	// jewellery/tablet/scroll/spell/cape, or null when the click doesn't reveal it
	private String pendingMethod;
	// tick of the last "Rub" on teleport jewellery; the destination arrives as a
	// chat-menu row click shortly after. -1 = idle
	private int rubTick = -1;
	private static final int RUB_MENU_WINDOW_TICKS = 25;

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

		// walking drops the pending. Left armed, a cancelled cast gets consumed by
		// whatever region hop comes next and credits the wrong method.
		if (optLow.equals("walk here"))
		{
			clearPending();
			return;
		}

		// a row click in the nexus teleport list
		if ((event.getWidgetId() >> 16) == InterfaceID.TELENEXUS_TELEPORT)
		{
			armTeleport(nexusRowText(event.getParam0()), true);
			return;
		}

		// the POH jewellery box lists its destinations as text rows. Only the six
		// destination sections arm; the FRAME child is the close button and scroll
		// furniture, and arming there would phantom-credit the next house exit.
		if ((event.getWidgetId() >> 16) == InterfaceID.POH_JEWELLERY_BOX)
		{
			if (event.getWidgetId() >= InterfaceID.PohJewelleryBox.DUELING
				&& event.getWidgetId() <= InterfaceID.PohJewelleryBox.GLORY)
			{
				String row = widgetChildText(event.getWidgetId(), event.getParam0());
				armTeleport((row == null || row.isEmpty()) ? optLow + " " + tgtLow : row, false);
				pendingMethod = TELEPORTS_VIA_JEWELLERY;
			}
			return;
		}

		// the chat menu a rubbed item opens, its rows being the destinations. Gated on
		// a recent rub so ordinary chat menus don't arm anything.
		if ((event.getWidgetId() >> 16) == InterfaceID.MENU && rubTick >= 0
			&& client.getTickCount() - rubTick <= RUB_MENU_WINDOW_TICKS)
		{
			String row = widgetChildText(event.getWidgetId(), event.getParam0());
			armTeleport((row == null || row.isEmpty()) ? optLow + " " + tgtLow : row, false);
			pendingMethod = TELEPORTS_VIA_JEWELLERY;
			rubTick = -1;
			return;
		}

		// these only open the nexus list; the row click above carries the destination
		if (optLow.equals("teleport to") || optLow.equals("teleport menu"))
		{
			return;
		}

		// a POH "Teleport Platform" is house stairs under a teleport-sounding label
		if (tgtLow.contains("teleport platform"))
		{
			return;
		}

		// bank/inventory handling of an item whose name carries "teleport"
		if (isInventoryManagement(optLow))
		{
			return;
		}

		// a left-click on the nexus with a primary destination set teleports straight
		// away, no list interface, so the option text is the place itself ("Last Boat",
		// "Great Kourend"). "Configuration" opens the destination editor, so it's the
		// one nexus option to skip; the openers and Examine returned above.
		if (tgtLow.contains("portal nexus"))
		{
			if (!optLow.contains("configuration"))
			{
				armTeleport(optLow, true);
			}
			return;
		}

		// a POH portal-chamber portal: option "Enter", target "<Place> Portal", with no
		// "tele" anywhere for the generic arming below to catch. Requiring the table to
		// know the place excludes the bare house exit and Clan Wars' "Free-for-all
		// portal". No method family fits a house portal, so it stays null.
		if (optLow.equals("enter") && tgtLow.endsWith("portal") && matchDestinationKey(tgtLow) != null)
		{
			armTeleport(tgtLow, false);
			return;
		}

		// the world's house portal names no place at all (target just "Portal"), so the
		// gate above can't see it. Outside only, since the same click inside the house
		// is the exit.
		if (tgtLow.equals("portal")
			&& (optLow.equals("enter") || optLow.equals("home")
			|| optLow.equals("build mode") || optLow.equals("friend's house"))
			&& !client.isInInstancedRegion())
		{
			armTeleport("house", false);
			return;
		}

		// spirit trees say "tele" nowhere; the option is "Travel"
		if ((tgtLow.contains("spirit tree")
			&& (optLow.startsWith("travel") || optLow.startsWith("last-destination")))
			|| (tgtLow.contains("spiritual fairy tree") && optLow.startsWith("travel")))
		{
			armTeleport("spirit tree", false);
			return;
		}

		// any spell/tab/cape/item teleport. The destination can sit on either half (a
		// spell's "Cast <place>", a tab's "<place> teleport"), so arm with both joined
		// and let the table find it.
		if (optLow.contains("tele") || tgtLow.contains("tele"))
		{
			armTeleport(optLow + " " + tgtLow, false);
			pendingMethod = methodOf(optLow, tgtLow);
			return;
		}

		// worn or rubbed teleport jewellery never says "tele" either; the option is the
		// destination itself ("Castle Wars" on a ring of dueling). Arm off the item name.
		if (isTeleportJewellery(tgtLow) && !isWearHandling(optLow))
		{
			if (optLow.startsWith("rub"))
			{
				// remember the rub so the destination row click just after is
				// recognised. The arm below stands in if that click is missed.
				rubTick = client.getTickCount();
			}
			armTeleport(optLow + " " + tgtLow, false);
			pendingMethod = TELEPORTS_VIA_JEWELLERY;
			return;
		}

		// a few items say "tele" nowhere at all (the Ectophial's option is "Empty", the
		// Royal seed pod's is "Commune"), so arm off the item name. No method family.
		if (isNamedTeleportItem(tgtLow) && !isWearHandling(optLow))
		{
			armTeleport(optLow + " " + tgtLow, false);
		}
	}

	// items whose activating option names neither "tele" nor a jewellery family
	private static boolean isNamedTeleportItem(String tgtLow)
	{
		return tgtLow.contains("ectophial") || tgtLow.contains("royal seed pod");
	}

	// the teleport-jewellery family, by item name
	private static boolean isTeleportJewellery(String tgtLow)
	{
		return tgtLow.contains("ring of dueling") || tgtLow.contains("games necklace")
			|| tgtLow.contains("amulet of glory") || tgtLow.contains("amulet of eternal glory")
			|| tgtLow.contains("combat bracelet") || tgtLow.contains("skills necklace")
			|| tgtLow.contains("ring of wealth") || tgtLow.contains("burning amulet")
			|| tgtLow.contains("necklace of passage") || tgtLow.contains("digsite pendant")
			|| tgtLow.contains("xeric's talisman") || tgtLow.contains("slayer ring")
			|| tgtLow.contains("ring of returning") || tgtLow.contains("drakan's medallion")
			|| tgtLow.contains("ring of the elements") || tgtLow.contains("giantsoul amulet");
	}

	// wearing, removing or checking jewellery isn't teleporting with it
	private static boolean isWearHandling(String option)
	{
		return option.startsWith("wear") || option.startsWith("wield")
			|| option.startsWith("remove") || option.startsWith("check")
			|| option.startsWith("destroy") || isInventoryManagement(option);
	}

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

	// bank/inventory verbs that name a teleport item without activating it
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

	// tag-stripped text of a clicked component's child, falling back to the
	// component's own text
	private String widgetChildText(int compositeId, int index)
	{
		Widget w = client.getWidget(compositeId);
		if (w == null)
		{
			return "";
		}
		Widget[] kids = w.getChildren();
		if (kids != null && index >= 0 && index < kids.length
			&& kids[index] != null && kids[index].getText() != null)
		{
			return Text.removeTags(kids[index].getText()).trim();
		}
		return w.getText() == null ? "" : Text.removeTags(w.getText()).trim();
	}

	// destination name for a clicked nexus row. The ROWS widget you click is a bare
	// hitbox; the label sits at the same index in the parallel TEXT1 list, keybind
	// prefixed ("5 :  Camelot"), which the substring match ignores.
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
			// Chebyshev distance. A normal step covers 1-2 tiles.
			int step = Math.max(
				Math.abs(current.getX() - lastPlayerPos.getX()),
				Math.abs(current.getY() - lastPlayerPos.getY()));
			boolean regionChanged = current.getRegionID() != lastPlayerPos.getRegionID();
			// a region change with any real step counts too, which catches cross-region
			// teleports while leaving a 1-2 tile walk over a boundary alone
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

		// a pending that never landed (a cancelled cast, a non-teleport nexus click)
		// expires, so it can't attach itself to some later movement
		if (pendingTick >= 0 && client.getTickCount() - pendingTick > TELEPORT_PENDING_WINDOW_TICKS)
		{
			clearPending();
		}
	}

	private void clearPending()
	{
		pendingLabel = null;
		pendingTick = -1;
		pendingFromNexus = false;
		pendingMethod = null;
	}

	private boolean teleportPending()
	{
		return pendingTick >= 0 && client.getTickCount() - pendingTick <= TELEPORT_PENDING_WINDOW_TICKS;
	}

	// credit the pending teleport to its place, or to the Nexus catch-all
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
		clearPending();
	}

	@Override
	public void onGameStateChanged(GameStateChanged event)
	{
		// a scene rebuild fires for every cross-region and instance hop, including the
		// boat, whose instanced arrival the position jump can't see. Whichever of the
		// two fires first credits and clears the pending, so a teleport that triggers
		// both still counts once.
		if (event.getGameState() == GameState.LOADING && teleportPending())
		{
			creditPendingTeleport();
		}
	}

	@Override
	public void onAnimationChanged(AnimationChanged event)
	{
		// fires for every actor, so a stranger teleporting beside you would otherwise
		// credit your counters
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}
		if (event.getActor().getAnimation() == FAIRY_RING_ANIM)
		{
			statStore.incrementStat(TELEPORTS_TOTAL);
			statStore.incrementStat(TELEPORTS_FAIRY_RING);
			// the ring is the journey, so drop any stale pending rather than let it
			// ride this landing too
			clearPending();
		}
	}

	private boolean isRunOrbEnabled()
	{
		Widget orb = client.getWidget(RUN_ORB_WIDGET_ID);
		return orb != null && !orb.isHidden() && orb.getSpriteId() == RUN_ENABLED_SPRITE_ID;
	}

	/**
	 * The teleport counter key a label maps to, or null if it names no tracked place.
	 * First matching DESTINATIONS substring wins, so a nexus hop lands on the same key
	 * as the spell or tab to that place.
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
