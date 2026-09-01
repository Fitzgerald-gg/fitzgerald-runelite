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
 * Item ids this account has gathered itself.
 *
 * <p>Tells the drop tracker whether a binned item is a resource that came out of
 * the world or bank junk. Membership survives logout, so the implementation lives
 * in the journal a package up; this is how the trackers reach it.
 *
 * <p>Both methods run on the client thread, so keep the membership test cheap.
 */
public interface GatheredLedger
{
	void noteGathered(int itemId);

	// true if it was gathered in any session, not just this one
	boolean wasGathered(int itemId);
}
