/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

/**
 * Item ids this account gathered itself. The drop tracker reads it to tell a
 * resource that came out of the world from bank junk. Membership survives logout,
 * which is why the journal a package up implements it. Client thread.
 */
public interface GatheredLedger
{
	void noteGathered(int itemId);

	// true if it was gathered in any session, not just this one
	boolean wasGathered(int itemId);
}
