/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin;

/**
 * Where the plugin sends the activity it captures.
 *
 * <p>{@link #CLOUD} is the default: events and counters are pushed to
 * fitzgerald.gg so they appear on your online profile. {@link #LOCAL} keeps
 * everything on this computer only — nothing is sent to the server — and the
 * plugin maintains a self-contained page under {@code .runelite/fitzgerald/}
 * that you open with the panel's "Open my page" button.
 */
public enum SyncMode
{
	CLOUD("Cloud"),
	LOCAL("Local");

	private final String label;

	SyncMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
