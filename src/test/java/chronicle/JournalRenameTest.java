/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the rename-follow file semantics: the journal and its history spine
 * move to the new name's slugs together, an existing target is never
 * clobbered, and a same-slug "rename" is a no-op.
 */
public class JournalRenameTest
{
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("chronicle-rename").toFile();
	}

	private void write(String name, String content) throws Exception
	{
		Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String name) throws Exception
	{
		return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
	}

	@Test
	public void journalAndHistoryFollowTogether() throws Exception
	{
		write("oxli.json", "{\"journal\":true}");
		write("oxli.history.jsonl", "{\"day\":1}");
		assertTrue(LocalStore.migrateJournalFiles(dir, "Oxli", "New Name"));
		assertEquals("{\"journal\":true}", read("new-name.json"));
		assertEquals("{\"day\":1}", read("new-name.history.jsonl"));
		assertFalse(new File(dir, "oxli.json").exists());
		assertFalse(new File(dir, "oxli.history.jsonl").exists());
	}

	@Test
	public void neverClobbersAnExistingTarget() throws Exception
	{
		write("oxli.json", "{\"old\":true}");
		write("counterfitz.json", "{\"kept\":true}");
		// Swap back to a name that already journaled here: its record loads,
		// the untargeted file stays put, nothing is overwritten.
		assertFalse(LocalStore.migrateJournalFiles(dir, "Oxli", "Counterfitz"));
		assertEquals("{\"kept\":true}", read("counterfitz.json"));
		assertEquals("{\"old\":true}", read("oxli.json"));
	}

	@Test
	public void historyStillMovesWhenOnlyJournalBlocked() throws Exception
	{
		write("oxli.json", "{\"old\":true}");
		write("oxli.history.jsonl", "{\"day\":1}");
		write("counterfitz.json", "{\"kept\":true}");
		assertFalse(LocalStore.migrateJournalFiles(dir, "Oxli", "Counterfitz"));
		// The history spine had no conflict, so it followed.
		assertEquals("{\"day\":1}", read("counterfitz.history.jsonl"));
	}

	@Test
	public void sameSlugIsANoOp() throws Exception
	{
		write("oxli.json", "{\"journal\":true}");
		assertFalse(LocalStore.migrateJournalFiles(dir, "Oxli", "OXLI"));
		assertEquals("{\"journal\":true}", read("oxli.json"));
	}

	@Test
	public void missingSourceIsANoOp()
	{
		assertFalse(LocalStore.migrateJournalFiles(dir, "Ghost", "New Name"));
		assertFalse(new File(dir, "new-name.json").exists());
	}
}
