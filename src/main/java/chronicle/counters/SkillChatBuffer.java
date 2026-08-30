/*
 * Copyright (c) 2026, Chronicle — BSD 2-Clause.
 *
 * Accumulates skilling signals between pushes and hands them to the plugin's
 * flush loop as one idempotent batch. Two channels: ACTION tuples (chat-free
 * XP+item detection — the primary path) and residual CHAT lines (failed
 * attempts, procs, drop-on-full identity). The server does ALL interpretation,
 * so this holds no game knowledge. Frozen-batch semantics give exactly-once
 * apply: a failed flush retries under the same batchId (the server dedups),
 * while new signals pile into separate pending maps so nothing is lost mid-flight.
 *
 * Threading: add()/addAction() run on the client thread, beginFlush() on the
 * push thread, ackFlush() on an OkHttp dispatcher thread. All state is guarded
 * by the monitor; ackFlush is BATCH-SCOPED (clears only the batch it names) so a
 * stale ack can never drop a newer batch.
 */
package chronicle.counters;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.inject.Singleton;

@Singleton
public class SkillChatBuffer
{
	/** A frozen batch: its id (server dedup + scoped ack) and both channels. */
	public static final class Batch
	{
		public final String id;
		public final Map<String, Integer> chat;
		public final Map<String, Integer> actions;

		Batch(String id, Map<String, Integer> chat, Map<String, Integer> actions)
		{
			this.id = id;
			this.chat = chat;
			this.actions = actions;
		}

		public boolean isEmpty()
		{
			return chat.isEmpty() && actions.isEmpty();
		}
	}

	private final Map<String, Integer> pendingChat = new HashMap<>();
	private final Map<String, Integer> pendingActions = new HashMap<>();
	private Map<String, Integer> inflightChat = null;
	private Map<String, Integer> inflightActions = null;
	private String batchId = null;

	/** Record one occurrence of a residual skilling chat line. */
	public synchronized void add(String message)
	{
		if (message != null && !message.isEmpty())
		{
			pendingChat.merge(message, 1, Integer::sum);
		}
	}

	/** Record one occurrence of an action tuple "SKILL|xp10|objId|itemId". */
	public synchronized void addAction(String tuple)
	{
		if (tuple != null && !tuple.isEmpty())
		{
			pendingActions.merge(tuple, 1, Integer::sum);
		}
	}

	/**
	 * Freeze a batch to send. A prior unacked batch is returned unchanged (a
	 * byte-identical retry under the same id); otherwise both pending maps are
	 * frozen under a fresh id. Returns null when there is nothing to send. Id +
	 * both channels travel together so the caller can never mismatch them.
	 */
	public synchronized Batch beginFlush()
	{
		if (batchId == null)
		{
			if (pendingChat.isEmpty() && pendingActions.isEmpty())
			{
				return null;
			}
			inflightChat = new HashMap<>(pendingChat);
			inflightActions = new HashMap<>(pendingActions);
			pendingChat.clear();
			pendingActions.clear();
			batchId = UUID.randomUUID().toString();
		}
		return new Batch(batchId, new HashMap<>(inflightChat), new HashMap<>(inflightActions));
	}

	/**
	 * Retire the frozen batch — but only if {@code ackedId} is still the current
	 * one. A late ack for an already-retired batch is a no-op, so it can't drop
	 * new signals.
	 */
	public synchronized void ackFlush(String ackedId)
	{
		if (ackedId != null && ackedId.equals(batchId))
		{
			inflightChat = null;
			inflightActions = null;
			batchId = null;
		}
	}

	/**
	 * Drop everything — pending AND the in-flight batch. For logout: whatever the
	 * final flush's HTTP request already froze travels with that request, but
	 * nothing may remain here to be flushed under whichever account logs in next.
	 * The late ack for a cleared batch is a no-op by id-mismatch.
	 */
	public synchronized void clearAll()
	{
		pendingChat.clear();
		pendingActions.clear();
		inflightChat = null;
		inflightActions = null;
		batchId = null;
	}
}
