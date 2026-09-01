/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause (see LICENSE).
 */
package chronicle;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

// Dev launcher for `gradle run`, and the shadowJar Main-Class. Boots a
// developer-mode client with Chronicle side-loaded. No JUnit tests here.
public class ChronicleDevLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ChroniclePlugin.class);
		RuneLite.main(args);
	}
}
