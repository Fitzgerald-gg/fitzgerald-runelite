/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.game.ItemManager;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A collection log pets page is a list of names and nothing else. Each name that has
 * something under it — where an owned pet came from, or how far the chase for an
 * unearned one has run — gives it up on a click and takes it back on the next. A name
 * with nothing under it is not a fold at all.
 *
 * <p>The state is the panel's one fold register, the same Set every other fold in the
 * panel keys into, so it outlives the rebuilds the home ticker fires and is dropped
 * whole when the account changes.
 */
public class PetPageFoldTest
{
	private static final String NOTE =
		"Click pet to see odds. Skilling odds are based on current level.";
	// what the note wraps to, since a note is drawn as one label a line
	private static final String NOTE_HEAD = "Click pet to see odds. Skilling odds are";

	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
	}

	// ------------------------------------------------------------------
	// A panel over a journal with one owned pet and two chases
	// ------------------------------------------------------------------

	private PanelPreviewTest.StubPlugin stub()
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		s.rsn = "Folder";
		JsonObject clog = new JsonObject();
		JsonObject kcs = new JsonObject();
		// a boss chase, whose odds are not read off any level
		kcs.addProperty("abyssal sire", 214);
		// and the Tempoross record three ways over, for the source test below
		kcs.addProperty("tempoross", 46);
		clog.add("kcs", kcs);
		JsonObject items = new JsonObject();
		items.addProperty("pet kraken", 1);
		clog.add("clog_items", items);
		s.clog = clog;
		s.sources.add(new LocalStore.SourceRow("Reward pool (Tempoross)", 114, 114,
			4_112_005L, null, 0, 0));
		s.sources.add(new LocalStore.SourceRow("Casket (Tempoross)", 25, 25,
			812_400L, null, 0, 0));
		// an owned pet, with the provenance line the page folds away
		s.petRows.add(new LocalStore.PetRow("Pet kraken", "Kraken", 2_147,
			java.time.Instant.parse("2024-11-08T20:14:00Z").toEpochMilli()));
		return s;
	}

	private ChroniclePanel petsPage(PanelPreviewTest.StubPlugin s) throws Exception
	{
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() ->
		{
			holder[0] = new ChroniclePanel(s);
			setView(holder[0], "LOG");
			set(holder[0], "clogTab", "Other");
			set(holder[0], "clogPageSel", "All Pets");
		});
		rebuild(holder[0]);
		return holder[0];
	}

	// ------------------------------------------------------------------
	// The fold
	// ------------------------------------------------------------------

	@Test
	public void everyPetStartsFoldedSoThePageIsJustAList() throws Exception
	{
		ChroniclePanel p = petsPage(stub());
		List<String> text = labels(p);
		// the names are all there
		assertTrue(text.contains("Abyssal orphan"));
		assertTrue(text.contains("Pet kraken"));
		assertTrue(text.contains("Tiny tempor"));
		// and not one of them has said anything else
		assertNull(detailUnder(p, "Abyssal orphan"));
		assertNull(detailUnder(p, "Pet kraken"));
		assertNull(detailUnder(p, "Tiny tempor"));
	}

	@Test
	public void aClickOpensOnePetAndTheNextShutsIt() throws Exception
	{
		ChroniclePanel p = petsPage(stub());
		click(p, "Abyssal orphan");
		assertEquals("Abyssal Sire, kc 214", detailUnder(p, "Abyssal orphan"));
		// its neighbours are untouched: one click opens one pet
		assertNull(detailUnder(p, "Pet kraken"));
		click(p, "Abyssal orphan");
		assertNull(detailUnder(p, "Abyssal orphan"));
	}

	@Test
	public void anOwnedPetFoldsAwayItsProvenanceTheSameWay() throws Exception
	{
		ChroniclePanel p = petsPage(stub());
		assertNull(detailUnder(p, "Pet kraken"));
		click(p, "Pet kraken");
		assertEquals("Kraken, kc 2,147", detailUnder(p, "Pet kraken"));
	}

	@Test
	public void twoPetsCanStandOpenAtOnce() throws Exception
	{
		ChroniclePanel p = petsPage(stub());
		click(p, "Abyssal orphan");
		click(p, "Pet kraken");
		assertNotNull(detailUnder(p, "Abyssal orphan"));
		assertNotNull(detailUnder(p, "Pet kraken"));
	}

	// The home ticker rebuilds the panel every three seconds. A fold that did not
	// survive that would shut on the reader between one glance and the next.
	@Test
	public void anOpenFoldSurvivesARebuild() throws Exception
	{
		ChroniclePanel p = petsPage(stub());
		click(p, "Abyssal orphan");
		rebuild(p);
		rebuild(p);
		assertEquals("Abyssal Sire, kc 214", detailUnder(p, "Abyssal orphan"));
	}

	// Every other view built from the last account's journal is dropped on a switch,
	// and a fold opened over one account's numbers is no different.
	@Test
	public void theFoldsCloseWhenTheAccountChanges() throws Exception
	{
		ChroniclePanel p = petsPage(stub());
		click(p, "Abyssal orphan");
		click(p, "Pet kraken");
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("resetAccountCaches");
			m.setAccessible(true);
			m.invoke(p);
			setView(p, "LOG");
			set(p, "clogTab", "Other");
			set(p, "clogPageSel", "All Pets");
		});
		rebuild(p);
		assertTrue(register(p).isEmpty());
		assertNull(detailUnder(p, "Abyssal orphan"));
		assertNull(detailUnder(p, "Pet kraken"));
	}

	// A row with nothing behind it must not pretend otherwise: no hand cursor, no
	// hover, and a click that does nothing because there is nothing listening.
	@Test
	public void aPetWithNothingToSayIsNotAFold() throws Exception
	{
		ChroniclePanel p = petsPage(stub());
		// Dom has a rate the wiki prints and no counter that can ask for it, so the
		// page has nothing to put under it whatever the journal holds
		JPanel row = rowFor(p, "Dom");
		assertNotNull(row);
		assertEquals(0, row.getMouseListeners().length);
		assertEquals(Cursor.getDefaultCursor(), row.getCursor());
		assertNull(row.getToolTipText());
		// clicking it is not an action, and the register stays empty
		assertTrue(register(p).isEmpty());
	}

	// ------------------------------------------------------------------
	// The note
	// ------------------------------------------------------------------

	@Test
	public void theNoteReadsExactlyAsSpecified() throws Exception
	{
		ChroniclePanel p = petsPage(stub());
		assertEquals(NOTE, note(p));
	}

	// The note is the page telling the reader the rows open. It belongs on any page
	// where one of them does, not only on a page carrying a level-scaled chase.
	@Test
	public void theNoteRidesAnyPageWithAFoldOnIt() throws Exception
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		JsonObject clog = new JsonObject();
		JsonObject kcs = new JsonObject();
		kcs.addProperty("abyssal sire", 214);
		clog.add("kcs", kcs);
		s.clog = clog;
		ChroniclePanel p = petsPage(s);
		// the one chase on the page is a boss chase, read off no level at all
		click(p, "Abyssal orphan");
		assertEquals("Abyssal Sire, kc 214", detailUnder(p, "Abyssal orphan"));
		assertEquals(NOTE, note(p));
	}

	@Test
	public void aPageWhereNothingCanOpenCarriesNoNote() throws Exception
	{
		PanelPreviewTest.StubPlugin s =
			new PanelPreviewTest.StubPlugin(Mockito.mock(ItemManager.class));
		ChroniclePanel p = petsPage(s);
		assertTrue(labels(p).contains("Abyssal orphan"));
		assertNull(note(p));
	}

	// ------------------------------------------------------------------
	// One mechanism, not two
	// ------------------------------------------------------------------

	// Home's xp breakdown was a boolean of its own. It is a key in the same register
	// now, which is what lets the next fold be a key and not a third field.
	@Test
	public void homesXpFoldIsAKeyInTheSameRegister() throws Exception
	{
		PanelPreviewTest.StubPlugin s = stub();
		s.session.put("totalXpGained", 412_004);
		final ChroniclePanel[] holder = new ChroniclePanel[1];
		edt(() -> holder[0] = new ChroniclePanel(s));
		ChroniclePanel p = holder[0];
		setView(p, "HOME");
		rebuild(p);
		assertTrue(register(p).isEmpty());
		click(p, "Xp gained");
		assertTrue(register(p).contains("home:xp"));
		click(p, "Xp gained");
		assertFalse(register(p).contains("home:xp"));
	}

	// ------------------------------------------------------------------
	// Walking the built panel
	// ------------------------------------------------------------------

	// The ghost line drawn directly under one pet's name row, or null where the page
	// drew none. Read off the mounted components, not off the model, so it says what
	// a reader would actually see.
	private String detailUnder(ChroniclePanel p, String pet)
	{
		List<Component> flat = new ArrayList<>();
		flatten(p, flat);
		for (int i = 0; i < flat.size(); i++)
		{
			if (!(flat.get(i) instanceof JPanel))
			{
				continue;
			}
			JPanel r = (JPanel) flat.get(i);
			if (!pet.equals(rowName(r)))
			{
				continue;
			}
			// the very next row panel is the detail, unless it is another pet's name
			for (int j = i + 1; j < flat.size(); j++)
			{
				if (!(flat.get(j) instanceof JPanel))
				{
					continue;
				}
				JPanel next = (JPanel) flat.get(j);
				String name = rowName(next);
				if (name == null)
				{
					continue;
				}
				return isPetName(p, name) ? null : name;
			}
			return null;
		}
		return null;
	}

	// True where the string names a slot on the page under the glass, which is how a
	// detail line is told from the next pet down.
	private boolean isPetName(ChroniclePanel p, String s)
	{
		try
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("taxonomy",
				com.google.gson.Gson.class);
			m.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.Map<String, java.util.Map<String, List<String>>> tax =
				(java.util.Map<String, java.util.Map<String, List<String>>>)
					m.invoke(null, new com.google.gson.Gson());
			return tax.get("Other").get("All Pets").contains(s);
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	private JPanel rowFor(ChroniclePanel p, String name)
	{
		List<Component> flat = new ArrayList<>();
		flatten(p, flat);
		for (Component c : flat)
		{
			if (c instanceof JPanel && name.equals(rowName((JPanel) c)))
			{
				return (JPanel) c;
			}
		}
		return null;
	}

	// Press a row the way a reader does. clicker() listens on mousePressed, and the
	// toggle rebuilds the panel under us, so nothing held from before the press is
	// still mounted after it.
	private void click(ChroniclePanel p, String name) throws Exception
	{
		JPanel row = rowFor(p, name);
		assertNotNull("no row named " + name, row);
		MouseListener[] ls = row.getMouseListeners();
		assertTrue("row " + name + " is not clickable", ls.length > 0);
		edt(() ->
		{
			for (MouseListener l : ls)
			{
				l.mousePressed(new MouseEvent(row, MouseEvent.MOUSE_PRESSED,
					System.currentTimeMillis(), 0, 1, 1, 1, false));
			}
		});
	}

	// The wrapped page note, joined back into one sentence, or null where the page
	// drew none. A note is a stack of labels, one a line, inside its own panel.
	private String note(ChroniclePanel p)
	{
		List<Component> flat = new ArrayList<>();
		flatten(p, flat);
		StringBuilder sb = new StringBuilder();
		for (Component c : flat)
		{
			if (!(c instanceof JLabel))
			{
				continue;
			}
			String t = ((JLabel) c).getText();
			if (t == null)
			{
				continue;
			}
			if (t.startsWith(NOTE_HEAD.substring(0, 12)) || sb.length() > 0)
			{
				if (sb.length() > 0)
				{
					sb.append(' ');
				}
				sb.append(t);
				if (t.endsWith("."))
				{
					return sb.toString();
				}
			}
		}
		return sb.length() > 0 ? sb.toString() : null;
	}

	private static String rowName(JPanel r)
	{
		if (!(r.getLayout() instanceof BorderLayout))
		{
			return null;
		}
		Component c = ((BorderLayout) r.getLayout())
			.getLayoutComponent(BorderLayout.CENTER);
		return c instanceof JLabel ? ((JLabel) c).getText() : null;
	}

	private static List<String> labels(ChroniclePanel p)
	{
		List<Component> flat = new ArrayList<>();
		flatten(p, flat);
		List<String> out = new ArrayList<>();
		for (Component c : flat)
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				out.add(((JLabel) c).getText());
			}
		}
		return out;
	}

	private static void flatten(Component c, List<Component> out)
	{
		out.add(c);
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				flatten(k, out);
			}
		}
	}

	// ------------------------------------------------------------------
	// Reflection into the panel
	// ------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static java.util.Set<String> register(ChroniclePanel p) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField("statsExpanded");
		f.setAccessible(true);
		return (java.util.Set<String>) f.get(p);
	}

	private static void rebuild(ChroniclePanel p) throws Exception
	{
		edt(() ->
		{
			Method m = ChroniclePanel.class.getDeclaredMethod("rebuild");
			m.setAccessible(true);
			m.invoke(p);
			p.setSize(242, 4000);
			p.doLayout();
		});
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setView(ChroniclePanel p, String view) throws Exception
	{
		Class<?> cls = Class.forName("chronicle.ChroniclePanel$View");
		set(p, "view", Enum.valueOf((Class<Enum>) cls, view));
	}

	private static void set(ChroniclePanel p, String field, Object val) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(p, val);
	}

	private interface ThrowingRunnable
	{
		void run() throws Exception;
	}

	private static void edt(ThrowingRunnable r) throws Exception
	{
		final Exception[] err = {null};
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				r.run();
			}
			catch (Exception e)
			{
				err[0] = e;
			}
		});
		if (err[0] != null)
		{
			throw err[0];
		}
	}
}
