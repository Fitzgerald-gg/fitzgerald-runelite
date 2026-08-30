/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the event chat patterns to the game's actual message wording.
 *
 * <p>Each corpus is drawn from the messages Old School RuneScape itself prints
 * (established from the OSRS Wiki's message documentation, not from any other
 * plugin's implementation). The positives must match; the negatives — adjacent
 * lines, near-miss wording, and the same text arriving as someone else's public
 * chat — must not. A pattern that drifts breaks a test rather than silently
 * mis-reporting an event, and a new game wording shows up here as a failing
 * positive to add.
 *
 * <p>Strings are compared after {@code Text.removeTags}, exactly as the live
 * handler sees them, so the red colour wrapper around counts is already stripped.
 * These patterns are copied verbatim from {@link ChronicleEventCapture}; keep the
 * two in step.
 */
public class ChatPatternTest
{
	private static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completed )?(?<subject>.+?)"
			+ "(?: (?:kill|chest|lap|harvest|success|completion))? count is: (?<tally>[\\d,]+)\\.$");
	private static final Pattern COLLECTION_ITEM = Pattern.compile(
		"^New item added to your collection log: (?<entry>.+)$");
	private static final Pattern COMBAT_TASK = Pattern.compile(
		"^Congratulations, you've completed an? (?<grade>\\w+) combat task: (?<challenge>.+?)\\.?$");
	private static final Pattern CLUE_COMPLETION = Pattern.compile(
		"^You have completed (?<tally>[\\d,]+) (?<rank>beginner|easy|medium|hard|elite|master)"
			+ " Treasure Trails?\\.$");
	private static final Pattern DIARY_COMPLETION = Pattern.compile(
		"Congratulations! You have completed all of the (?<grade>\\w+) tasks in the (?<region>.+?) area");
	// Kept in sync with ChronicleEventCapture (2026-08-02 fix): FINISHED is not
	// $-anchored so the modern " You gained N xp." suffix is ignored, and TOTAL
	// absorbs any master-name qualifier ("N Mortimer task", "N Wilderness tasks").
	private static final Pattern SLAYER_FINISHED = Pattern.compile(
		"^You have completed your task! You killed (?<slain>[\\d,]+) (?<creature>[^.]+)\\.");
	private static final Pattern SLAYER_TOTAL = Pattern.compile(
		"^You've completed (?:at least )?(?<total>[\\d,]+) (?<qual>[A-Za-z]+ )?tasks?"
			+ "(?:;| and received)");
	private static final Pattern PET_RECEIVED = Pattern.compile(
		"^(?:You have a funny feeling like you're being followed"
			+ "|You feel something weird sneaking into your backpack"
			+ "|You have a funny feeling like you would have been followed\\.\\.\\.)\\.?$");
	private static final Pattern UNTRADEABLE_DROP = Pattern.compile("^Untradeable drop: (?<dropped>.+)$");
	private static final Pattern KILL_DURATION = Pattern.compile(
		"(?:Fight duration|Challenge duration|Corrupted challenge duration"
			+ "|Completion time|Subdued in|Duration):? (?<time>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");
	private static final Pattern PERSONAL_BEST = Pattern.compile(
		"[Pp]ersonal best[:!]? (?<pb>\\d+(?::\\d{2})+(?:\\.\\d{1,2})?)");

	private static void matches(Pattern p, String... lines)
	{
		for (String line : lines)
		{
			assertTrue("expected to match: " + line, p.matcher(line).find());
		}
	}

	private static void rejects(Pattern p, String... lines)
	{
		for (String line : lines)
		{
			assertFalse("expected NOT to match: " + line, p.matcher(line).find());
		}
	}

	@Test
	public void killCount()
	{
		// Tags are stripped upstream, so counts arrive as bare integers.
		matches(KILL_COUNT,
			"Your Zulrah kill count is: 1.",
			"Your Zulrah kill count is: 500.",
			"Your Vorkath kill count is: 1000.",
			"Your General Graardor kill count is: 250.",
			"Your Barrows chest count is: 512.",
			"Your Ape Atoll Agility lap count is: 1337.",
			"Your completed Chambers of Xeric count is: 75.",
			"Your completed Chambers of Xeric Challenge Mode count is: 3.",
			"Your completed Theatre of Blood count is: 40.",
			"Your completed Theatre of Blood: Hard Mode count is: 40.",
			"Your completed Tombs of Amascut: Expert Mode count is: 128.",
			"Your Yama success count is: 10.");
		rejects(KILL_COUNT,
			"Congratulations - your raid is complete!",
			"Total points: 28160, Personal points: 9500 (33.74%)",
			"My Zulrah KC is: 500",
			"Your Barrows loot is worth 384,000 coins.",
			"Kill count: 512",
			"You have killed 40 monsters since entering the dungeon.");
	}

	@Test
	public void killCountCapturesNameAndNumber()
	{
		Matcher raid = KILL_COUNT.matcher("Your completed Theatre of Blood: Hard Mode count is: 40.");
		assertTrue(raid.find());
		assertEquals("Theatre of Blood: Hard Mode", raid.group("subject"));
		assertEquals("40", raid.group("tally"));

		Matcher boss = KILL_COUNT.matcher("Your Zulrah kill count is: 500.");
		assertTrue(boss.find());
		assertEquals("Zulrah", boss.group("subject"));
	}

	@Test
	public void collectionLog()
	{
		matches(COLLECTION_ITEM,
			"New item added to your collection log: Twisted bow",
			"New item added to your collection log: Elidinis' ward",
			"New item added to your collection log: Guthix d'hide body",
			"New item added to your collection log: 3rd age full helmet",
			"New item added to your collection log: Chompy bird hat (ogre bowman)",
			"New item added to your collection log: Rune scimitar ornament kit (guthix)",
			"New item added to your collection log: Amy's saw");
		rejects(COLLECTION_ITEM,
			"Your collection log has already accounted for that item.",
			"New item added to your collection log.",
			"New item added to your collection log:",
			"Collection log: 1234/1602");
	}

	@Test
	public void combatAchievement()
	{
		matches(COMBAT_TASK,
			"Congratulations, you've completed an Easy combat task: Noxious Foe.",
			"Congratulations, you've completed a Medium combat task: Barrows Champion.",
			"Congratulations, you've completed a Hard combat task: Whack-a-Mole.",
			"Congratulations, you've completed a Hard combat task: Kree'arra Adept.",
			"Congratulations, you've completed an Elite combat task: Kill It with Fire.",
			"Congratulations, you've completed a Master combat task: Perfect Zulrah.",
			"Congratulations, you've completed a Grandmaster combat task: Insanity.");
		rejects(COMBAT_TASK,
			"Retrofitz has completed a Grandmaster combat task: Insanity.",
			"You have failed a combat task: Perfect Zulrah.",
			"Congratulations, you've completed a Slayer task and received 15,000 XP.",
			"New item added to your collection log: Dragon claws.",
			"Congratulations, you've unlocked the Elite tier rewards! Speak to Ghommal to claim them.",
			"Congratulations, you've just advanced your Attack level. You are now level 80.");
	}

	@Test
	public void combatAchievementCaptures()
	{
		Matcher m = COMBAT_TASK.matcher("Congratulations, you've completed a Grandmaster combat task: Insanity.");
		assertTrue(m.find());
		assertEquals("Grandmaster", m.group("grade"));
		assertEquals("Insanity", m.group("challenge"));
	}

	@Test
	public void clueScroll()
	{
		matches(CLUE_COMPLETION,
			"You have completed 87 hard Treasure Trails.",
			"You have completed 132 easy Treasure Trails.",
			"You have completed 2 medium Treasure Trails.",
			"You have completed 400 elite Treasure Trails.",
			"You have completed 63 master Treasure Trails.",
			"You have completed 5 beginner Treasure Trails.",
			"You have completed 1 beginner Treasure Trail.");
		rejects(CLUE_COMPLETION,
			"Well done, you've completed the Treasure Trail!",
			"Your treasure is worth around 217,997 coins!",
			"You have a sneaking suspicion that you would have received a hard clue scroll.",
			"You have completed a hard Treasure Trail.",
			"You have completed 87 hard Treasure Trails");
	}

	@Test
	public void achievementDiary()
	{
		matches(DIARY_COMPLETION,
			"Congratulations! You have completed all of the easy tasks in the Ardougne area. "
				+ "Your Achievement Diary has been updated.",
			"Congratulations! You have completed all of the hard tasks in the Lumbridge & Draynor area. "
				+ "Your Achievement Diary has been updated.",
			"Congratulations! You have completed all of the elite tasks in the Kourend & Kebos area. "
				+ "Your Achievement Diary has been updated.",
			"Congratulations! You have completed all of the easy tasks in the Western Provinces area. "
				+ "Your Achievement Diary has been updated.");
		rejects(DIARY_COMPLETION,
			"You have completed the Karamja Elite Diary!",
			"Well done! You have completed all of the easy tasks in the tutorial.",
			"You have completed 150 tasks in the Combat Achievements list.",
			"Congratulations, you've completed an elite combat task: Peach Conjurer.");
	}

	@Test
	public void diaryCapturesAreaAndDifficulty()
	{
		Matcher m = DIARY_COMPLETION.matcher(
			"Congratulations! You have completed all of the hard tasks in the Lumbridge & Draynor area. "
				+ "Your Achievement Diary has been updated.");
		assertTrue(m.find());
		assertEquals("hard", m.group("grade"));
		assertEquals("Lumbridge & Draynor", m.group("region"));
	}

	@Test
	public void slayerStreak()
	{
		matches(SLAYER_TOTAL,
			"You've completed 5 tasks and received 12 points, giving you a total of 12; return to a Slayer master.",
			"You've completed 100 tasks and received 375 points, giving you a total of 4,175; return to a Slayer master.",
			"You've completed 1,000 tasks and received 750 points, giving you a total of 45,231; return to a Slayer master.",
			"You've completed 3 tasks; return to a Slayer master.",
			"You've completed 15 Wilderness tasks and received 125 points, giving you a total of 1,875; return to a Slayer master.",
			"You've completed 4 Wilderness tasks; return to a Slayer master.",
			// A master-name qualifier can sit between the count and "task" (e.g.
			// "1 Mortimer task") — the optional (?<qual>...) group absorbs it so
			// the streak line still triggers (regression: broke the trigger).
			"You've completed 1 Mortimer task; return to a Slayer master.");
		rejects(SLAYER_TOTAL,
			"You're assigned to kill aberrant spectres; only 134 more to go.",
			"You have completed enough tasks to unlock your next area!",
			"You have completed a quest!",
			"A superior foe has appeared...",
			"Congratulations, you've completed an easy combat task: Whack-a-Mole.");
	}

	@Test
	public void slayerFinished()
	{
		matches(SLAYER_FINISHED,
			"You have completed your task! You killed 30 aberrant spectres.",
			"You have completed your task! You killed 145 greater demons.",
			"You have completed your task! You killed 1,000 hellhounds.",
			// Modern OSRS appends " You gained N xp." after the creature — the
			// pattern is NOT $-anchored, so the suffix is ignored (regression:
			// this exact wording was silently dropping every completion).
			"You have completed your task! You killed 306 Dust Devils. You gained 39,715 xp.",
			"You have completed your task! You killed 245 Cave kraken. You gained 62,475 xp.");
		rejects(SLAYER_FINISHED,
			"You need something new to hunt.",
			"You're assigned to kill aberrant spectres; only 134 more to go.",
			"You have completed your task! You killed some monsters.");
	}

	@Test
	public void slayerFinishedSplitsCountAndCreature()
	{
		Matcher m = SLAYER_FINISHED.matcher("You have completed your task! You killed 1,000 hellhounds.");
		assertTrue(m.find());
		assertEquals("1,000", m.group("slain"));
		assertEquals("hellhounds", m.group("creature"));
	}

	@Test
	public void pet()
	{
		matches(PET_RECEIVED,
			"You have a funny feeling like you're being followed.",
			"You feel something weird sneaking into your backpack.",
			"You have a funny feeling like you would have been followed...");
		rejects(PET_RECEIVED,
			"Your pet is scared into your backpack.",
			"You have a funny feeling like you're being watched.",
			"You feel something weird sneaking into your bank.",
			"[Clan] Retrofitz: You feel something weird sneaking into your backpack.",
			"You have a funny feeling like you are being followed.");
	}

	@Test
	public void untradeableDrop()
	{
		matches(UNTRADEABLE_DROP,
			"Untradeable drop: Clue scroll (hard)",
			"Untradeable drop: Ancient shard",
			"Untradeable drop: Larran's key",
			"Untradeable drop: Ultor vestige",
			"Untradeable drop: 2 x Ancient shard");
		rejects(UNTRADEABLE_DROP,
			"Valuable drop: Rune platebody (37,000 coins)",
			"Valuable drop: Imbued heart (94,409,296 coins)",
			"Zezima received a drop: Abyssal whip (1,600,000 coins).",
			"Untradeables are not lost on death here.",
			"You have completed 42 Hard Treasure Trails.");
	}

	@Test
	public void killDuration()
	{
		matches(KILL_DURATION,
			"Fight duration: 1:26.40 (new personal best)",
			"Fight duration: 1:26. Personal best: 1:19",
			"Congratulations - your raid is complete! Duration: 36:04",
			"Challenge duration: 7:42. Personal best: 6:58",
			"Corrupted challenge duration: 10:01.80 (new personal best)",
			"Subdued in 6:23.60 (new personal best).",
			"Duration: 1:01:53.40. Personal best: 58:04.20");
		rejects(KILL_DURATION,
			"Your Zulrah kill count is: 501.",
			"You have completed your task! You killed 285 Gargoyles.",
			"Duration of your ban: permanent",
			"Fight duration unknown.");
	}

	@Test
	public void killDurationCapturesTime()
	{
		Matcher m = KILL_DURATION.matcher("Fight duration: 1:26.40 (new personal best)");
		assertTrue(m.find());
		assertEquals("1:26.40", m.group("time"));
		m = KILL_DURATION.matcher("Congratulations - your raid is complete! Duration: 36:04");
		assertTrue(m.find());
		assertEquals("36:04", m.group("time"));
	}

	@Test
	public void personalBestRestated()
	{
		Matcher m = PERSONAL_BEST.matcher("Fight duration: 1:26. Personal best: 1:19.80");
		assertTrue(m.find());
		assertEquals("1:19.80", m.group("pb"));
		assertFalse(PERSONAL_BEST.matcher("Fight duration: 1:26.40 (new personal best)").find());
	}

	@Test
	public void durationParsing()
	{
		assertEquals(86.4, ChronicleEventCapture.parseDuration("1:26.40"), 1e-9);
		assertEquals(2164.0, ChronicleEventCapture.parseDuration("36:04"), 1e-9);
		assertEquals(3713.4, ChronicleEventCapture.parseDuration("1:01:53.40"), 1e-9);
	}
}
