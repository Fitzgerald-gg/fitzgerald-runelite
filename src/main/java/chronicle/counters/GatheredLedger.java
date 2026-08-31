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
 * The item ids this account has actually pulled out of the world.
 *
 * <p>It exists for one question, asked at the moment of a "Drop" click: is this
 * a resource I gathered, or bank junk I am clearing out? {@code itemsDroppedValue}
 * counts both, so pairing it with what the gathering skills produced would flatter
 * the dropped side — a single bank trip binning old clue rewards would read as ore
 * left on the ground. Only ids this ledger has seen gathered count toward the
 * resource-scoped figure.
 *
 * <p>Membership has to outlive the session: an ore mined last week and binned
 * today is still a resource dropped where it fell. The implementation therefore
 * lives in the journal, not in the session store — this interface is only how the
 * trackers reach it, since they sit in another package and must not care where the
 * record is kept.
 *
 * <p>Both methods are called on the client thread, {@link #noteGathered} once per
 * resolved gathering action, so the membership test must stay cheap.
 */
public interface GatheredLedger
{
	/** Remember that this item id came out of the world. Ids only — a drop click carries nothing else. */
	void noteGathered(int itemId);

	/** True once this account has gathered the item, in this session or any earlier one. */
	boolean wasGathered(int itemId);
}
