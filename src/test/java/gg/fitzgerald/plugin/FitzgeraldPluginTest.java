/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev launcher: starts a RuneLite client in developer mode with the Fitzgerald
 * plugin side-loaded. Run with {@code gradle run}. Not a JUnit test.
 */
public class FitzgeraldPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FitzgeraldPlugin.class);
		RuneLite.main(args);
	}
}
