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
 * move to the new name's slugs together, a record already filed under the new
 * name is set aside intact rather than adopted, and a same-slug "rename" is a
 * no-op.
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

	/** The one file set aside under {@code prefix} (the timestamp suffix varies). */
	private String onlySidecar(String prefix)
	{
		String[] hits = dir.list((d, name) -> name.startsWith(prefix));
		assertTrue("no sidecar for " + prefix, hits != null && hits.length == 1);
		return hits[0];
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
	public void aRecordAlreadyFiledUnderTheNewNameIsSetAsideNotAdopted() throws Exception
	{
		write("oxli.json", "{\"mine\":true}");
		// A freed name gets taken: what sits under it may be another account's
		// record, and this account must never accumulate on top of it.
		write("counterfitz.json", "{\"stranger\":true}");
		assertTrue(LocalStore.migrateJournalFiles(dir, "Oxli", "Counterfitz"));
		assertEquals("{\"mine\":true}", read("counterfitz.json"));
		assertFalse(new File(dir, "oxli.json").exists());
		// Nothing is deleted — the displaced record keeps every byte.
		assertEquals("{\"stranger\":true}", read(onlySidecar("counterfitz.json.conflict-")));
	}

	@Test
	public void theSpineIsSetAsideWithItsRecord() throws Exception
	{
		write("oxli.json", "{\"mine\":true}");
		write("oxli.history.jsonl", "{\"day\":1}");
		write("counterfitz.json", "{\"stranger\":true}");
		write("counterfitz.history.jsonl", "{\"day\":99}");
		assertTrue(LocalStore.migrateJournalFiles(dir, "Oxli", "Counterfitz"));
		assertEquals("{\"day\":1}", read("counterfitz.history.jsonl"));
		assertEquals("{\"day\":99}", read(onlySidecar("counterfitz.history.jsonl.conflict-")));
	}

	@Test
	public void aSpineWithoutItsRecordStaysPut() throws Exception
	{
		// No journal to carry, so the rename has no business touching the
		// record filed under the new name — splicing this account's history
		// onto it would join two baselines into one calendar.
		write("oxli.history.jsonl", "{\"day\":1}");
		write("counterfitz.json", "{\"stranger\":true}");
		assertFalse(LocalStore.migrateJournalFiles(dir, "Oxli", "Counterfitz"));
		assertEquals("{\"stranger\":true}", read("counterfitz.json"));
		assertEquals("{\"day\":1}", read("oxli.history.jsonl"));
		assertFalse(new File(dir, "counterfitz.history.jsonl").exists());
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
