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
 * Rename-follow file semantics: journal and history spine move to the new slug
 * together, anything already under that slug is set aside, same slug does nothing.
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

	// match on prefix; the sidecar carries a timestamp suffix
	private String onlySidecar(String prefix)
	{
		String[] hits = dir.list((d, name) -> name.startsWith(prefix));
		assertTrue("no sidecar for " + prefix, hits != null && hits.length == 1);
		return hits[0];
	}

	@Test
	public void journalAndHistoryFollowTogether() throws Exception
	{
		write("alpha.json", "{\"journal\":true}");
		write("alpha.history.jsonl", "{\"day\":1}");
		assertTrue(LocalStore.migrateJournalFiles(dir, "Alpha", "New Name"));
		assertEquals("{\"journal\":true}", read("new-name.json"));
		assertEquals("{\"day\":1}", read("new-name.history.jsonl"));
		assertFalse(new File(dir, "alpha.json").exists());
		assertFalse(new File(dir, "alpha.history.jsonl").exists());
	}

	@Test
	public void aRecordAlreadyFiledUnderTheNewNameIsSetAsideNotAdopted() throws Exception
	{
		write("alpha.json", "{\"mine\":true}");
		// a freed rsn gets taken, so this could be a stranger's record
		write("beta.json", "{\"stranger\":true}");
		assertTrue(LocalStore.migrateJournalFiles(dir, "Alpha", "Beta"));
		assertEquals("{\"mine\":true}", read("beta.json"));
		assertFalse(new File(dir, "alpha.json").exists());
		assertEquals("{\"stranger\":true}", read(onlySidecar("beta.json.conflict-")));
	}

	@Test
	public void theSpineIsSetAsideWithItsRecord() throws Exception
	{
		write("alpha.json", "{\"mine\":true}");
		write("alpha.history.jsonl", "{\"day\":1}");
		write("beta.json", "{\"stranger\":true}");
		write("beta.history.jsonl", "{\"day\":99}");
		assertTrue(LocalStore.migrateJournalFiles(dir, "Alpha", "Beta"));
		assertEquals("{\"day\":1}", read("beta.history.jsonl"));
		assertEquals("{\"day\":99}", read(onlySidecar("beta.history.jsonl.conflict-")));
	}

	@Test
	public void aSpineWithoutItsRecordStaysPut() throws Exception
	{
		// moving the spine alone would splice this account's days onto whatever
		// record already sits under the new name
		write("alpha.history.jsonl", "{\"day\":1}");
		write("beta.json", "{\"stranger\":true}");
		assertFalse(LocalStore.migrateJournalFiles(dir, "Alpha", "Beta"));
		assertEquals("{\"stranger\":true}", read("beta.json"));
		assertEquals("{\"day\":1}", read("alpha.history.jsonl"));
		assertFalse(new File(dir, "beta.history.jsonl").exists());
	}

	@Test
	public void sameSlugIsANoOp() throws Exception
	{
		write("alpha.json", "{\"journal\":true}");
		assertFalse(LocalStore.migrateJournalFiles(dir, "Alpha", "ALPHA"));
		assertEquals("{\"journal\":true}", read("alpha.json"));
	}

	@Test
	public void missingSourceIsANoOp()
	{
		assertFalse(LocalStore.migrateJournalFiles(dir, "Ghost", "New Name"));
		assertFalse(new File(dir, "new-name.json").exists());
	}
}
