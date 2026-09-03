/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.ui.FontManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The pets page prints a chase in one line: where the kills went, and the share of
 * players holding the pet by that point. The row is narrow and Swing clips the end
 * of a label, so the line is fitted before it is mounted. What is tested here is
 * the bargain that fitting strikes — the names give up letters, the figures give up
 * nothing — and that whatever comes back really does measure inside the row.
 */
public class ChaseLineFitTest
{
	@Before
	public void laf() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		// the client's own look and feel: its scrollbar is what the row's width is
		// reckoned against
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(
					new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	// Whatever mark the panel actually cuts with, so this test guards that and not
	// a copy of it.
	private static String ellipsis() throws Exception
	{
		java.lang.reflect.Field f = ChroniclePanel.class.getDeclaredField("ELLIPSIS");
		f.setAccessible(true);
		return (String) f.get(null);
	}

	private static GrindBook.PetChase boss(double pct, Object... bossKc)
	{
		java.util.List<GrindBook.PetSource> src = new java.util.ArrayList<>();
		for (int i = 0; i < bossKc.length; i += 2)
		{
			src.add(new GrindBook.PetSource((String) bossKc[i], (Long) bossKc[i + 1], 3000));
		}
		long total = 0;
		for (GrindBook.PetSource s : src)
		{
			total += s.kc;
		}
		return new GrindBook.PetChase("A pet", total, pct, src);
	}

	private static GrindBook.PetChase skilling(double pct, String activity, long kc, String unit)
	{
		return new GrindBook.PetChase("A pet", kc, pct, Collections.emptyList(),
			activity, unit, 99);
	}

	// The share the row draws on the right, exactly as the panel words it.
	private static String share(GrindBook.PetChase chase) throws Exception
	{
		java.lang.reflect.Method m =
			ChroniclePanel.class.getDeclaredMethod("holdShare", GrindBook.PetChase.class);
		m.setAccessible(true);
		return (String) m.invoke(null, chase);
	}

	// The one thing every case must satisfy: it fits the room the row actually has.
	private static void assertFits(String line, String share)
	{
		FontMetrics fm = ChroniclePanel.rowMetrics();
		int room = ChroniclePanel.chaseRoom(share, fm);
		assertTrue("[" + line + "] is " + fm.stringWidth(line) + "px in a " + room + "px row",
			fm.stringWidth(line) <= room);
	}

	// No figure may be printed part-read: every run of digits in the line has to be
	// one of the figures the chase holds, whole.
	private static void assertFiguresWhole(String line, String... figures) throws Exception
	{
		List<String> allowed = Arrays.asList(figures);
		Matcher m = Pattern.compile("[0-9][0-9,]*").matcher(line);
		while (m.find())
		{
			assertTrue("[" + line + "] prints a part-read figure: " + m.group(),
				allowed.contains(m.group()));
		}
		assertFalse("[" + line + "] cut a figure short",
			line.matches(".*[0-9]" + Pattern.quote(ellipsis()) + ".*"));
	}

	@Test
	public void aShortNameIsLeftCompletelyAlone() throws Exception
	{
		GrindBook.PetChase c = boss(0.4, "Abyssal Sire", 214L);
		String line = ChroniclePanel.fitChase(c, share(c));
		assertEquals("Abyssal Sire, kc 214", line);
		assertFalse("nothing was too wide, so nothing should be cut",
			line.contains(ellipsis()));
		assertFits(line, share(c));
	}

	@Test
	public void oneLongNameGivesLettersAndKeepsItsCount() throws Exception
	{
		GrindBook.PetChase c = boss(37, "Thermonuclear Smoke Devil", 1402L);
		String line = ChroniclePanel.fitChase(c, share(c));
		assertTrue(line + " lost its count", line.endsWith(", kc 1,402"));
		// enough of the name to know the boss on sight
		assertTrue(line + " is not recognisable as the boss",
			line.startsWith("Thermonu"));
		assertTrue(line + " should say it was cut", line.contains(ellipsis()));
		assertFiguresWhole(line, "1,402");
		assertFits(line, share(c));
	}

	@Test
	public void twoLongSourcesKeepEveryCountTheyShow() throws Exception
	{
		GrindBook.PetChase c = boss(73, "Callisto", 1500L, "Artio", 900L);
		String line = ChroniclePanel.fitChase(c, share(c));
		// the source that carried the grind is named in full, whatever else goes
		assertTrue(line + " mangled the leading source",
			line.startsWith("Callisto, kc 1,500"));
		assertFiguresWhole(line, "1,500", "900", "1");
		if (!line.contains("Artio"))
		{
			// dropped rather than mangled — and the row says so
			assertFalse(line + " kept a stub of the second source",
				line.contains("Art" + ellipsis()));
			assertTrue(line + " dropped a source without saying so", line.endsWith(" +1"));
		}
		else
		{
			assertTrue(line + " lost the second count", line.contains(", kc 900"));
		}
		assertFits(line, share(c));
	}

	/**
	 * Two names that would both fit if both were cut. They are not both cut: the
	 * source that carried the grind is named in full and the other one gives way,
	 * because a line that reads "Cha…, kc 1 · Cra…, kc 1" names nothing at all.
	 */
	@Test
	public void theLeadingSourceIsNeverCutWhileATrailingOneCanGiveWay() throws Exception
	{
		GrindBook.PetChase c = boss(0.4, "Chaos Fanatic", 1L, "Crazy archaeologist", 1L);
		String line = ChroniclePanel.fitChase(c, share(c));
		assertTrue(line + " cut into the leading source",
			line.startsWith("Chaos Fanatic, kc 1"));
		assertFiguresWhole(line, "1");
		assertFits(line, share(c));
	}

	@Test
	public void aNameSoLongNothingFitsStillPrintsTheCount() throws Exception
	{
		StringBuilder monster = new StringBuilder();
		for (int i = 0; i < 200; i++)
		{
			monster.append('W');
		}
		GrindBook.PetChase c = boss(37, monster.toString(), 1402L);
		String line = ChroniclePanel.fitChase(c, share(c));
		assertTrue(line + " lost its count", line.endsWith(", kc 1,402"));
		assertTrue(line + " kept none of the name", line.startsWith("WWW"));
		assertTrue(line + " should say it was cut", line.contains(ellipsis()));
		assertFiguresWhole(line, "1,402");
		assertFits(line, share(c));
	}

	@Test
	public void theSkillingLineKeepsItsCountToo() throws Exception
	{
		GrindBook.PetChase c = skilling(3, "Runecraft", 30112L, "essence");
		String line = ChroniclePanel.fitChase(c, share(c));
		assertTrue(line + " lost its activity", line.startsWith("Runecraft"));
		assertTrue(line + " lost its count", line.contains(", 30,112 "));
		assertFiguresWhole(line, "30,112");
		assertFits(line, share(c));
	}

	@Test
	public void aSkillingLineThatAlreadyFitsIsNotTouched() throws Exception
	{
		GrindBook.PetChase c = skilling(6, "Mining", 9923L, "ore");
		String line = ChroniclePanel.fitChase(c, share(c));
		assertEquals("Mining, 9,923 ore", line);
		assertFits(line, share(c));
	}

	/**
	 * The font the panel draws rows in is a pixel font with holes in it — an em dash
	 * is why none of these strings carry one. The single-character ellipsis is not
	 * one of the holes: it paints three pixels, where a glyph the font lacks paints
	 * the .notdef box instead.
	 */
	@Test
	public void theEllipsisIsAGlyphTheGameFontHas() throws Exception
	{
		java.awt.Font f = FontManager.getRunescapeFont();
		String mark = ellipsis();
		for (char ch : mark.toCharArray())
		{
			assertTrue("the game font has no " + ch, f.canDisplay(ch));
			int drawn = ink(f, ch);
			int missing = ink(f, '█');   // a full block: the font has no such glyph
			assertTrue(ch + " painted nothing", drawn > 0);
			assertTrue(ch + " painted the .notdef box, not a glyph", drawn < missing / 4);
		}
	}

	// Pixels a glyph actually paints at the row's own size.
	private static int ink(java.awt.Font f, char ch)
	{
		BufferedImage im = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = im.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, 40, 40);
		g.setFont(f);
		g.setColor(Color.WHITE);
		g.drawString(String.valueOf(ch), 8, 28);
		g.dispose();
		int n = 0;
		for (int y = 0; y < 40; y++)
		{
			for (int x = 0; x < 40; x++)
			{
				if ((im.getRGB(x, y) & 0xFFFFFF) != 0)
				{
					n++;
				}
			}
		}
		return n;
	}
}
