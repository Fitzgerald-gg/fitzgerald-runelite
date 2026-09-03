/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Dryness for the pets whose attempts were already counted for them, by a collection
 * log page or by the drop ledger, in the unit the roll is asked in: a search of the
 * Rewards Guardian, a master casket opened, a high gamble, a crate, a loot sack, a
 * kill. None of these is a skilling counter and none is priced off a level; they are
 * read exactly as Hespori always was.
 */
public class KillSourcePetChaseTest
{
	private static final List<String> PETS = Arrays.asList(
		"Abyssal protector", "Chompy chick", "Bloodhound", "Pet penance queen",
		"Lil' creator", "Quetzin", "Yami", "Gull", "Beef", "Aggy",
		"Dom", "Maggot marquess", "Mr McGroot", "Smolcano");

	private final Map<String, Long> counters = new HashMap<>();
	private final Map<String, long[]> skills = new HashMap<>();
	private final JsonObject clog = new JsonObject();
	private final List<LocalStore.SourceRow> ledger = new ArrayList<>();
	private final JsonObject achievements = new JsonObject();

	// a collection log page and its count
	private void kc(String page, long n)
	{
		JsonObject kcs = clog.has("kcs") ? clog.getAsJsonObject("kcs") : new JsonObject();
		kcs.addProperty(page, n);
		clog.add("kcs", kcs);
	}

	// a drop ledger source and its count
	private void ledger(String source, int n)
	{
		ledger.add(new LocalStore.SourceRow(source, n, n, 0L, null, 0L, 0L));
	}

	// a diary tier the character sheet says is done, spelled as AchievementSync spells it
	private void diary(String region, String tier, boolean done)
	{
		JsonObject diaries = achievements.has("diaries")
			? achievements.getAsJsonObject("diaries") : new JsonObject();
		JsonObject r = diaries.has(region)
			? diaries.getAsJsonObject(region) : new JsonObject();
		r.addProperty(tier, done);
		diaries.add(region, r);
		achievements.add("diaries", diaries);
	}

	private GrindBook.PetChase chase(String pet)
	{
		Map<String, GrindBook.PetChase> out = new GrindBook(new Gson())
			.petChases(clog, ledger, counters, skills, achievements, PETS);
		return out.get(pet.toLowerCase(java.util.Locale.ROOT));
	}

	// 1/4,000 a search. The roll is on searching the Rewards Guardian, never on the
	// game that paid for it, so the count the journal keeps for the minigame is the
	// denominator and nothing else is added to it.
	@Test
	public void theProtectorIsReadOffTheRiftSearches()
	{
		kc("Guardians of the Rift", 5_218);
		ledger("Guardians of the Rift", 4_955);
		GrindBook.PetChase c = chase("Abyssal protector");
		assertEquals(72.9, c.percentileDry, 0.05);
		// one record kept two ways, so the fuller of the two stands for it
		assertEquals(5_218, c.kc);
		assertEquals(1, c.sources.size());
		assertEquals(4_000, c.sources.get(0).rate);
		assertEquals("Guardians of the Rift", c.activity);
		assertEquals("searches", c.unit);
		// nothing here was read off a level, so the page has no caveat to print
		assertEquals(0, c.level);
	}

	// A chompy is rolled twice, on the kill and on the pluck, each at 1/500. Only the
	// kill is counted, and the rate is not quietly halved to cover the pluck: that
	// would overstate every bird left where it fell.
	@Test
	public void theChompyIsPricedOnTheKillItCountsNotThePluckItCannot()
	{
		// the chase only exists past the elite diary; PetUnlockGateTest holds that
		diary("western", "elite", true);
		ledger("Chompy bird", 295);
		GrindBook.PetChase c = chase("Chompy chick");
		assertEquals(44.6, c.percentileDry, 0.05);
		assertEquals(295, c.kc);
		assertEquals(500, c.sources.get(0).rate);
		// what a doubled roll would have printed, and deliberately does not
		double plucked = (1.0 - Math.pow(1.0 - 1.0 / 500, 590)) * 100.0;
		assertEquals(69.3, plucked, 0.05);
	}

	// Master caskets alone. The other trail tiers are counted by the same ledger under
	// names one character apart and roll nothing for this pet.
	@Test
	public void onlyMasterCasketsRollTheBloodhound()
	{
		ledger("Clue Scroll (Elite)", 6);
		ledger("Clue Scroll (Hard)", 11);
		assertNull(chase("Bloodhound"));

		ledger("Clue Scroll (Master)", 300);
		GrindBook.PetChase c = chase("Bloodhound");
		assertEquals(25.9, c.percentileDry, 0.05);
		assertEquals(300, c.kc);
		assertEquals("Master clues", c.activity);
		assertEquals("caskets", c.unit);
	}

	// Low and medium gambles cannot give the queen, so only the high gamble count is
	// a denominator here.
	@Test
	public void onlyTheHighGambleRollsTheQueen()
	{
		ledger("Barbarian Assault low gamble", 4_000);
		ledger("Barbarian Assault high gamble", 611);
		GrindBook.PetChase c = chase("Pet penance queen");
		assertEquals(45.7, c.percentileDry, 0.05);
		assertEquals(611, c.kc);
		assertEquals(1_000, c.sources.get(0).rate);
	}

	// The log page counts Spoils of War opened and the ledger counts the same crates
	// under its own spelling. They are one record: the fuller stands for it, and the
	// two are never added.
	@Test
	public void theCreatorTakesTheFullerOfTwoSpellingsNotTheirSum()
	{
		kc("Soul Wars", 346);
		GrindBook.PetChase log = chase("Lil' creator");
		assertEquals(346, log.kc);
		assertEquals(57.9, log.percentileDry, 0.05);
		assertEquals(400, log.sources.get(0).rate);

		ledger("Spoils of war", 402);
		GrindBook.PetChase both = chase("Lil' creator");
		assertEquals(402, both.kc);
		assertEquals(1, both.sources.size());
	}

	// Expert and master sacks roll; basic and adept carry no Quetzin line at all, and
	// the guild's own rumour count mixes all four tiers, so it is not the denominator.
	@Test
	public void quetzinCountsExpertAndMasterSacksAndNotTheGuildCount()
	{
		kc("Hunter Guild", 4_000);
		ledger("Hunters' loot sack (basic)", 900);
		ledger("Hunters' loot sack (adept)", 700);
		ledger("Hunters' loot sack (expert)", 502);
		ledger("Hunters' loot sack (master)", 110);
		GrindBook.PetChase c = chase("Quetzin");
		assertEquals(612, c.kc);
		assertEquals(2, c.sources.size());
		assertEquals(45.8, c.percentileDry, 0.05);
		assertEquals("Expert sacks", c.sources.get(0).boss);
	}

	// The ordinary kill is 1/2,500. A contract kill is 1/100 and nothing tells the two
	// apart, so every kill is priced at the ordinary rate rather than guessed at.
	@Test
	public void yamiIsPricedAtTheOrdinaryKill()
	{
		kc("Yama", 1_204);
		GrindBook.PetChase c = chase("Yami");
		assertEquals(2_500, c.sources.get(0).rate);
		assertEquals(38.2, c.percentileDry, 0.05);
		assertEquals(1_204, c.kc);
	}

	// The log capitalises the gryphon and the ledger does not. One source either way.
	@Test
	public void theGryphonIsOneSourceHoweverItIsSpelled()
	{
		kc("Shellbane Gryphon", 88);
		ledger("Shellbane gryphon", 214);
		GrindBook.PetChase c = chase("Gull");
		assertEquals(1, c.sources.size());
		assertEquals(214, c.kc);
		assertEquals(3_000, c.sources.get(0).rate);
	}

	// The log is only as fresh as the last time it was opened; the ledger keeps
	// counting. The fuller record wins, and they are not added.
	@Test
	public void beefAndAggyReadTheFullerRecordNotTheStalerPage()
	{
		kc("Brutus", 2);
		ledger("Brutus", 75);
		assertEquals(75, chase("Beef").kc);
		assertEquals(7.2, chase("Beef").percentileDry, 0.05);

		kc("The Mad Angel", 6);
		ledger("Mad Angel", 124);
		GrindBook.PetChase aggy = chase("Aggy");
		assertEquals(124, aggy.kc);
		assertEquals(1, aggy.sources.size());
		assertEquals(6.0, aggy.percentileDry, 0.05);
	}

	// Three pets have a rate the wiki prints and no counter that can ask for it in the
	// unit the roll is made in. A row for them would be a guess, so they get none, and
	// the boss pets around them are untouched by any of this.
	@Test
	public void thePetsNoCounterCanAskForGetNoRow()
	{
		kc("Doom of Mokhaiotl", 400);
		kc("Maggot King", 900);
		kc("Wyrmscraig Goat", 1_500);
		kc("Zalcano", 2_023);
		assertNull(chase("Dom"));
		assertNull(chase("Maggot marquess"));
		assertNull(chase("Mr McGroot"));
		assertNotNull(chase("Smolcano"));
		assertNull(chase("Smolcano").activity);
	}
}
