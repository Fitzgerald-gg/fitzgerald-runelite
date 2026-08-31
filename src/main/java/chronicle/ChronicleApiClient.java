/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin async wrapper over the configured Chronicle server's HTTP API. Every call is fired on
 * OkHttp's own dispatcher threads (never the client thread) and reports back
 * through a {@link Consumer} callback. Callbacks may run on any thread; the
 * caller is responsible for hopping back to the client/EDT thread as needed.
 */
@Slf4j
@Singleton
public class ChronicleApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.get("image/png");

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	ChronicleApiClient(OkHttpClient http, Gson gson)
	{
		// Give ourselves shorter timeouts than the RuneLite default so a slow
		// server never wedges a background push.
		this.http = http.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS)
			.writeTimeout(15, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
	}

	/** Result of an enrolment attempt. {@code ok} is true only on HTTP 200. */
	public static final class EnrollResult
	{
		public final boolean ok;
		public final int code;
		@Nullable
		public final String token;
		@Nullable
		public final String rsn;
		@Nullable
		public final String ustUrl;
		@Nullable
		public final String error;

		EnrollResult(boolean ok, int code, @Nullable String token, @Nullable String rsn,
			@Nullable String ustUrl, @Nullable String error)
		{
			this.ok = ok;
			this.code = code;
			this.token = token;
			this.rsn = rsn;
			this.ustUrl = ustUrl;
			this.error = error;
		}

		static EnrollResult failure(int code, @Nullable String error)
		{
			return new EnrollResult(false, code, null, null, null, error);
		}
	}

	/** Result of a stat push. */
	public static final class PushResult
	{
		public final boolean ok;
		public final int code;
		public final int accepted;
		public final int changed;
		@Nullable
		public final String error;

		PushResult(boolean ok, int code, int accepted, int changed, @Nullable String error)
		{
			this.ok = ok;
			this.code = code;
			this.accepted = accepted;
			this.changed = changed;
			this.error = error;
		}
	}

	/**
	 * POST {base}/api/plugin/enroll {"rsn": name}. On 200 the response carries a
	 * freshly minted token + the pipe URLs; 409 means the RSN is already tracked
	 * (an admin must reissue); 403 means the RSN is blocked.
	 */
	public void enroll(String baseUrl, String rsn, Consumer<EnrollResult> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/plugin/enroll");
		if (url == null)
		{
			onDone.accept(EnrollResult.failure(-1, "bad server URL"));
			return;
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("enroll failed", e);
				onDone.accept(EnrollResult.failure(-1, e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					int code = r.code();
					JsonObject body = parse(r);
					if (code == 200 && body != null)
					{
						onDone.accept(new EnrollResult(
							true, code,
							optString(body, "token"),
							optString(body, "rsn"),
							optString(body, "counters_url"),
							null));
					}
					else
					{
						String err = body != null ? optString(body, "error") : null;
						onDone.accept(EnrollResult.failure(code, err));
					}
				}
				catch (Exception ex)
				{
					log.debug("enroll parse error", ex);
					onDone.accept(EnrollResult.failure(-1, ex.getMessage()));
				}
			}
		});
	}

	/**
	 * POST {base}/api/counters/{token} {"playerName": name, "stats": {..absolute ints..}}.
	 * The token is the auth; the server rejects (204, silently) a playerName that
	 * does not match the token's known RSNs, so {@code name} must be the exact
	 * in-game display name.
	 */
	// The account's stable hash (RuneLite client.getAccountHash()), set by the
	// plugin whenever the local player resolves. Sent on token-authed fires so
	// the server can verify an in-game rename as the SAME account (not an alt
	// sharing the token) and adopt the new display name automatically. Sent as
	// a string to sidestep any 64-bit JSON-number precision worries.
	private volatile long accountHash = -1L;

	public void setAccountHash(long h)
	{
		this.accountHash = h;
	}

	private void addAccountHash(JsonObject payload)
	{
		long h = accountHash;
		// -1 is RuneLite's "no account" sentinel; every other value is a valid
		// hash — including negatives, since the 64-bit hash often has its sign
		// bit set. Guarding on `> 0` (wrongly) dropped real hashes.
		if (h != -1L)
		{
			payload.addProperty("accountHash", String.valueOf(h));
		}
	}

	public void pushStats(String baseUrl, String token, String name,
		Map<String, Integer> stats, @Nullable String accountType,
		@Nullable JsonObject skills, @Nullable Consumer<PushResult> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/counters/" + token);
		if (url == null)
		{
			if (onDone != null)
			{
				onDone.accept(new PushResult(false, -1, 0, 0, "bad server URL"));
			}
			return;
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("playerName", name);
		addAccountHash(payload);
		// The account variant (ironman/gim/…) read from the game, so the server can
		// keep the profile's account_type in sync without anyone tagging by hand.
		if (accountType != null && !accountType.isEmpty())
		{
			payload.addProperty("accountType", accountType);
		}
		JsonObject statsObj = new JsonObject();
		for (Map.Entry<String, Integer> e : stats.entrySet())
		{
			if (e.getValue() != null)
			{
				statsObj.addProperty(e.getKey(), e.getValue());
			}
		}
		payload.add("stats", statsObj);
		// Per-skill level + XP snapshot, so the profile updates live rather than
		// waiting for the daily hiscores pull. Live-push only (null on the logout
		// flush, where the client can't be read).
		if (skills != null && skills.size() > 0)
		{
			payload.add("skills", skills);
		}

		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("stat push failed", e);
				if (onDone != null)
				{
					onDone.accept(new PushResult(false, -1, 0, 0, e.getMessage()));
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					int code = r.code();
					JsonObject body = parse(r);
					if (onDone == null)
					{
						return;
					}
					if (code == 200)
					{
						int accepted = body != null ? optInt(body, "accepted") : 0;
						int changed = body != null ? optInt(body, "changed") : 0;
						onDone.accept(new PushResult(true, code, accepted, changed, null));
					}
					else
					{
						String err = body != null ? optString(body, "error") : null;
						onDone.accept(new PushResult(false, code, 0, 0, err));
					}
				}
				catch (Exception ex)
				{
					log.debug("stat push parse error", ex);
					if (onDone != null)
					{
						onDone.accept(new PushResult(false, -1, 0, 0, ex.getMessage()));
					}
				}
			}
		});
	}

	private static JsonObject countMapToJson(Map<String, Integer> m)
	{
		JsonObject o = new JsonObject();
		if (m != null)
		{
			for (Map.Entry<String, Integer> e : m.entrySet())
			{
				if (e.getValue() != null && e.getValue() > 0)
				{
					o.addProperty(e.getKey(), e.getValue());
				}
			}
		}
		return o;
	}

	/**
	 * Forward a batch of skilling signals (action tuples + residual chat) to
	 * POST /api/skill/{token}. The
	 * server derives the counters (so new game content is a server change, not a
	 * plugin release). {@code deltas} maps raw chat line → occurrence count.
	 * {@code onDone} is called with true only on HTTP 200 so the caller can ack
	 * (and clear) the frozen batch; on any failure it is called with false so the
	 * caller retries the same batch (the server dedups by batchId).
	 */
	public void forwardSkillChat(String baseUrl, String token, String name, String batchId,
		Map<String, Integer> chat, Map<String, Integer> actions, @Nullable Consumer<Integer> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/skill/" + token);
		if (url == null)
		{
			if (onDone != null)
			{
				onDone.accept(-1);   // no send; retryable (config may be fixed)
			}
			return;
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("playerName", name);
		if (batchId != null)
		{
			payload.addProperty("batchId", batchId);
		}
		payload.add("actions", countMapToJson(actions));   // chat-free XP+item tuples (primary)
		payload.add("chat", countMapToJson(chat));          // residual chat lines

		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("skill forward failed", e);
				if (onDone != null)
				{
					onDone.accept(-1);   // network failure → retry
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (onDone != null)
					{
						onDone.accept(r.code());
					}
				}
			}
		});
	}

	/**
	 * Fire a single RAW event at POST /api/events/{token}. Fire-and-forget: the
	 * plugin sends only raw fields (item ids + quantity, raw context) and the
	 * server enriches (name / GE value / rarity) + stores. {@code event} is the
	 * full body ({@code playerName, type, data, eventId}). Never throws on the
	 * caller thread; logs failures at debug.
	 */
	public void postEvent(String baseUrl, String token, JsonObject event)
	{
		postEvent(baseUrl, token, event, null);
	}

	/**
	 * Seed the plugin's in-memory counter cache from the server (the store) on
	 * login: GET the token-owner's current absolute counters. Calls back with the
	 * map on success, or {@code null} on any failure (the caller must NOT push
	 * absolutes it couldn't seed, or it would look like a wholesale regression).
	 */
	/**
	 * Push a first-party collection-log snapshot (fire-and-forget). The snapshot
	 * is the server's clog shape — {by_cat:{page:{item:count}}, kcs:{page:kc},
	 * finished, available} — accreted by the passive capture; the server merges it.
	 */
	public void pushClog(String baseUrl, String token, String name, Map<String, Object> snapshot)
	{
		HttpUrl url = resolve(baseUrl, "api/clog/" + token);
		if (url == null)
		{
			return;
		}
		JsonObject payload = gson.toJsonTree(snapshot).getAsJsonObject();
		payload.addProperty("playerName", name);
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("clog push failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	/**
	 * Push the whole achievement-state snapshot (quests / diaries / combat
	 * tasks). The ack callback lets the caller retire its change gate only once
	 * the server has actually stored the copy.
	 */
	public void pushAchievements(String baseUrl, String token, String name,
		JsonObject achievements, Consumer<Boolean> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/achievements/" + token);
		if (url == null)
		{
			if (onDone != null)
			{
				onDone.accept(false);
			}
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("playerName", name);
		payload.add("achievements", achievements);
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("achievement push failed", e);
				if (onDone != null)
				{
					onDone.accept(false);
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				boolean ok = response.isSuccessful();
				response.close();
				if (onDone != null)
				{
					onDone.accept(ok);
				}
			}
		});
	}

	/** One adopted drop source from the cloud ledger (read-only snapshot). */
	public static final class LedgerSource
	{
		public final String source;
		public final int kc;
		public final int loots;
		public final long value;
		public final int itemsCount;

		LedgerSource(String source, int kc, int loots, long value, int itemsCount)
		{
			this.source = source;
			this.kc = kc;
			this.loots = loots;
			this.value = value;
			this.itemsCount = itemsCount;
		}
	}

	/** One item row from the cloud ledger (name-keyed; the server holds no ids). */
	public static final class LedgerItem
	{
		public final String name;
		public final long qty;
		public final long value;

		LedgerItem(String name, long qty, long value)
		{
			this.name = name;
			this.qty = qty;
			this.value = value;
		}
	}

	/**
	 * Fetch the cloud ledger's per-source rollup for a player (the public
	 * drops endpoint's {@code by_source} lens). Used once per login to floor
	 * the journal's drop history at what the server already knows.
	 */
	public void fetchDropLedger(String baseUrl, String rsn, Consumer<java.util.List<LedgerSource>> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/activity/drops");
		if (url == null)
		{
			if (onDone != null)
			{
				onDone.accept(null);
			}
			return;
		}
		url = url.newBuilder().addQueryParameter("player", rsn)
			.addQueryParameter("limit", "1").build();
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("fetchDropLedger failed", e);
				if (onDone != null)
				{
					onDone.accept(null);
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				java.util.List<LedgerSource> out = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						JsonObject body;
						body = parse(r);
						if (body != null && body.has("by_source") && body.get("by_source").isJsonArray())
						{
							out = new java.util.ArrayList<>();
							for (JsonElement el : body.getAsJsonArray("by_source"))
							{
								if (!el.isJsonObject())
								{
									continue;
								}
								JsonObject o = el.getAsJsonObject();
								out.add(new LedgerSource(
									str(o, "source"),
									num(o, "kc").intValue(),
									num(o, "count").intValue(),
									num(o, "total_value").longValue(),
									num(o, "distinct_items").intValue()));
							}
						}
					}
				}
				catch (RuntimeException | IOException e)
				{
					log.debug("fetchDropLedger parse failed", e);
				}
				if (onDone != null)
				{
					onDone.accept(out);
				}
			}
		});
	}

	/** One Wise Old Man snapshot: a dated skill-xp baseline. */
	public static final class WomSnapshot
	{
		public final String date;                       // yyyy-mm-dd
		public final Map<String, Long> skills;          // our skill keys -> xp

		WomSnapshot(String date, Map<String, Long> skills)
		{
			this.date = date;
			this.skills = skills;
		}
	}

	/**
	 * The cloud's whole daily xp series in ONE response — the server's raw
	 * snapshot archive via /api/osrs/snapshots. The plugin token unlocks full
	 * depth (anonymous callers are clamped to a year, the account-age rule).
	 * Day and Week history work everywhere the archive has days.
	 */
	public void fetchServerHistoryDaily(String baseUrl, String rsn, String token,
		Consumer<java.util.List<WomSnapshot>> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/osrs/snapshots/" + rsn);
		if (url == null)
		{
			onDone.accept(null);
			return;
		}
		Request.Builder rb = new Request.Builder().url(url).get();
		if (token != null && !token.isEmpty())
		{
			rb.header("X-Plugin-Token", token);
		}
		http.newCall(rb.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("snapshot series fetch failed", e);
				onDone.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				java.util.List<WomSnapshot> out = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						JsonObject o = gson.fromJson(r.body().charStream(), JsonObject.class);
						out = new java.util.ArrayList<>();
						if (o != null && o.has("series") && o.get("series").isJsonArray())
						{
							for (JsonElement el : o.getAsJsonArray("series"))
							{
								if (!el.isJsonObject())
								{
									continue;
								}
								JsonObject day = el.getAsJsonObject();
								String date = str(day, "date");
								if (date.length() < 10 || !day.has("skills")
									|| !day.get("skills").isJsonObject())
								{
									continue;
								}
								Map<String, Long> xp = new HashMap<>();
								for (java.util.Map.Entry<String, JsonElement> sk
									: day.getAsJsonObject("skills").entrySet())
								{
									try
									{
										long v = sk.getValue().getAsLong();
										if (v > 0)
										{
											String key = "runecrafting".equals(sk.getKey())
												? "runecraft" : sk.getKey();
											xp.put(key, v);
										}
									}
									catch (RuntimeException ignored)
									{
										// non-numeric — skip
									}
								}
								if (!xp.isEmpty())
								{
									out.add(new WomSnapshot(date.substring(0, 10), xp));
								}
							}
						}
					}
				}
				catch (RuntimeException e)
				{
					log.debug("snapshot series parse failed", e);
				}
				onDone.accept(out);
			}
		});
	}

	/**
	 * One-shot import of the cloud's own snapshot archive: the server keeps a
	 * daily xp file per player back to its first sync (2023 for the reference
	 * instance) but serves only window DIFFS — so this walks the archive month
	 * by month through the public gains endpoint and returns each month's
	 * closing absolutes as {@link WomSnapshot}s, ready for the history stream.
	 * One probe (period=all) finds the true first month; empty months skip.
	 * Superseded by {@link #fetchServerHistoryDaily} where the server has the
	 * snapshots endpoint; kept as the BYO-server fallback.
	 */
	public void fetchServerHistory(String baseUrl, String rsn,
		Consumer<java.util.List<WomSnapshot>> onDone)
	{
		HttpUrl probe = resolve(baseUrl, "api/osrs/gains/" + rsn);
		if (probe == null)
		{
			onDone.accept(null);
			return;
		}
		HttpUrl url = probe.newBuilder().addQueryParameter("period", "all").build();
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("server history probe failed", e);
				onDone.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				String from = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						JsonObject o = gson.fromJson(r.body().charStream(), JsonObject.class);
						from = o != null ? str(o, "from") : null;
					}
				}
				catch (RuntimeException e)
				{
					log.debug("server history probe parse failed", e);
				}
				if (from == null || from.length() < 7)
				{
					onDone.accept(new java.util.ArrayList<>());   // no archive: empty, not error
					return;
				}
				// month windows from the first snapshot's month to now
				java.util.List<String[]> windows = new java.util.ArrayList<>();
				java.time.YearMonth start = java.time.YearMonth.parse(from.substring(0, 7));
				java.time.YearMonth now = java.time.YearMonth.now();
				for (java.time.YearMonth m = start; !m.isAfter(now); m = m.plusMonths(1))
				{
					windows.add(new String[]{
						m.atDay(1).toString(), m.atEndOfMonth().toString()});
				}
				fetchServerHistoryWindow(baseUrl, rsn, windows, 0,
					new java.util.ArrayList<>(), onDone);
			}
		});
	}

	private void fetchServerHistoryWindow(String baseUrl, String rsn,
		java.util.List<String[]> windows, int idx, java.util.List<WomSnapshot> acc,
		Consumer<java.util.List<WomSnapshot>> onDone)
	{
		if (idx >= windows.size())
		{
			onDone.accept(acc);
			return;
		}
		HttpUrl base = resolve(baseUrl, "api/osrs/gains/" + rsn);
		if (base == null)
		{
			onDone.accept(acc);
			return;
		}
		String[] w = windows.get(idx);
		HttpUrl url = base.newBuilder().addQueryParameter("from", w[0])
			.addQueryParameter("to", w[1]).build();
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("server history window {} failed", w[0], e);
				// a mid-walk failure still delivers what was gathered
				onDone.accept(acc);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						JsonObject o = gson.fromJson(r.body().charStream(), JsonObject.class);
						Map<String, Long> xp = new HashMap<>();
						if (o != null && o.has("skills") && o.get("skills").isJsonArray())
						{
							for (JsonElement el : o.getAsJsonArray("skills"))
							{
								if (!el.isJsonObject())
								{
									continue;
								}
								JsonObject sk = el.getAsJsonObject();
								String name = str(sk, "skill")
									.toLowerCase(java.util.Locale.ROOT);
								long end = num(sk, "end_xp").longValue();
								if (!name.isEmpty() && end > 0)
								{
									xp.put("runecrafting".equals(name) ? "runecraft" : name, end);
								}
							}
						}
						if (!xp.isEmpty())
						{
							String today = java.time.LocalDate.now().toString();
							String date = w[1].compareTo(today) > 0 ? today : w[1];
							acc.add(new WomSnapshot(date, xp));
						}
					}
				}
				catch (RuntimeException e)
				{
					log.debug("server history parse failed", e);
				}
				fetchServerHistoryWindow(baseUrl, rsn, windows, idx + 1, acc, onDone);
			}
		});
	}

	/** One (source, item, qty, value) ledger row for the whole-bag adoption. */
	public static final class SourceItemRow
	{
		public final String source;
		public final String name;
		public final long qty;
		public final long value;

		SourceItemRow(String source, String name, long qty, long value)
		{
			this.source = source;
			this.name = name;
			this.qty = qty;
			this.value = value;
		}
	}

	/** Every per-source item row of the cloud ledger in one call — the journal
	 *  adopts these so item questions answer locally, no drill-time fetches. */
	public void fetchAllSourceItems(String baseUrl, String rsn,
		Consumer<java.util.List<SourceItemRow>> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/activity/source-items");
		if (url == null)
		{
			onDone.accept(null);
			return;
		}
		url = url.newBuilder().addQueryParameter("player", rsn).build();
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("source-items fetch failed", e);
				onDone.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				java.util.List<SourceItemRow> out = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						JsonObject o = gson.fromJson(r.body().charStream(), JsonObject.class);
						out = new java.util.ArrayList<>();
						if (o != null && o.has("rows") && o.get("rows").isJsonArray())
						{
							for (JsonElement el : o.getAsJsonArray("rows"))
							{
								if (!el.isJsonArray() || el.getAsJsonArray().size() < 4)
								{
									continue;
								}
								com.google.gson.JsonArray a = el.getAsJsonArray();
								try
								{
									out.add(new SourceItemRow(a.get(0).getAsString(),
										a.get(1).getAsString(), a.get(2).getAsLong(),
										a.get(3).getAsLong()));
								}
								catch (RuntimeException ignored)
								{
									// malformed row — skip
								}
							}
						}
					}
				}
				catch (RuntimeException e)
				{
					log.debug("source-items parse failed", e);
				}
				onDone.accept(out);
			}
		});
	}

	/** The cloud's uncollected-loot ledger: per-source and per-item rows. */
	public static final class UntakenLedger
	{
		public final java.util.List<String[]> bySource;   // {name, qty, value}
		public final java.util.List<String[]> byItem;

		UntakenLedger(java.util.List<String[]> bySource, java.util.List<String[]> byItem)
		{
			this.bySource = bySource;
			this.byItem = byItem;
		}
	}

	/** Fetch the uncollected ledger the server has kept since the untaken
	 *  capture first shipped — the panel's Left behind lens adopts it. */
	public void fetchUntaken(String baseUrl, String rsn, Consumer<UntakenLedger> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/untaken/" + rsn);
		if (url == null)
		{
			onDone.accept(null);
			return;
		}
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("untaken fetch failed", e);
				onDone.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				UntakenLedger out = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						JsonObject o = gson.fromJson(r.body().charStream(), JsonObject.class);
						out = new UntakenLedger(untakenRows(o, "by_source", "source"),
							untakenRows(o, "by_item", "name"));
					}
				}
				catch (RuntimeException e)
				{
					log.debug("untaken parse failed", e);
				}
				onDone.accept(out);
			}
		});
	}

	private static java.util.List<String[]> untakenRows(JsonObject o, String field, String nameKey)
	{
		java.util.List<String[]> rows = new java.util.ArrayList<>();
		if (o != null && o.has(field) && o.get(field).isJsonArray())
		{
			for (JsonElement el : o.getAsJsonArray(field))
			{
				if (!el.isJsonObject())
				{
					continue;
				}
				JsonObject row = el.getAsJsonObject();
				String name = str(row, nameKey);
				if (!name.isEmpty())
				{
					rows.add(new String[]{name,
						String.valueOf(num(row, "qty").longValue()),
						String.valueOf(num(row, "value").longValue())});
				}
			}
		}
		return rows;
	}

	/** The cloud's slayer journey, as the site's Slayer chapter reads it. */
	public static final class SlayerJourney
	{
		public final int completedTasks;
		public final long totalKills;
		public final long totalValueGp;
		public final long totalXpEst;
		public final java.util.List<SlayerTask> tasks;

		SlayerJourney(int completedTasks, long totalKills, long totalValueGp,
			long totalXpEst, java.util.List<SlayerTask> tasks)
		{
			this.completedTasks = completedTasks;
			this.totalKills = totalKills;
			this.totalValueGp = totalValueGp;
			this.totalXpEst = totalXpEst;
			this.tasks = tasks;
		}
	}

	/** One task segment of the journey, newest first. */
	public static final class SlayerTask
	{
		public final String task;
		public final long kills;
		public final long assignment;
		public final long noLootKills;
		public final double ts;          // epoch seconds
		public final long totalValue;
		public final boolean inProgress;

		SlayerTask(String task, long kills, long assignment, long noLootKills,
			double ts, long totalValue, boolean inProgress)
		{
			this.task = task;
			this.kills = kills;
			this.assignment = assignment;
			this.noLootKills = noLootKills;
			this.ts = ts;
			this.totalValue = totalValue;
			this.inProgress = inProgress;
		}
	}

	/** Fetch the slayer journey the server derives from on-task loot. */
	public void fetchSlayerJourney(String baseUrl, String rsn,
		Consumer<SlayerJourney> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/slayer/journey/" + rsn);
		if (url == null)
		{
			onDone.accept(null);
			return;
		}
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("slayer journey fetch failed", e);
				onDone.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				SlayerJourney out = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						JsonObject o = gson.fromJson(r.body().charStream(), JsonObject.class);
						java.util.List<SlayerTask> tasks = new java.util.ArrayList<>();
						if (o.has("tasks") && o.get("tasks").isJsonArray())
						{
							for (JsonElement el : o.getAsJsonArray("tasks"))
							{
								if (!el.isJsonObject())
								{
									continue;
								}
								JsonObject t = el.getAsJsonObject();
								// the site's kill figure: total_kills, else the target
								long kills = t.has("total_kills") && !t.get("total_kills").isJsonNull()
									? num(t, "total_kills").longValue()
									: num(t, "count_target").longValue();
								tasks.add(new SlayerTask(str(t, "task"), kills,
									num(t, "assignment").longValue(),
									num(t, "no_loot_kills").longValue(),
									num(t, "ts").doubleValue(),
									num(t, "total_value").longValue(),
									t.has("in_progress") && !t.get("in_progress").isJsonNull()
										&& t.get("in_progress").getAsBoolean()));
							}
						}
						out = new SlayerJourney(num(o, "completed_tasks_count").intValue(),
							num(o, "total_kills").longValue(),
							num(o, "total_value_gp").longValue(),
							num(o, "total_xp_est").longValue(), tasks);
					}
				}
				catch (RuntimeException e)
				{
					log.debug("slayer journey parse failed", e);
				}
				onDone.accept(out);
			}
		});
	}

	/** One adopted milestone from the cloud feed. */
	public static final class FeedEvent
	{
		public final double ts;      // epoch seconds (server clock)
		public final String type;    // normalised: PET, COLLECTION, SLAYER, ...
		public final JsonObject data;

		FeedEvent(double ts, String type, JsonObject data)
		{
			this.ts = ts;
			this.type = type;
			this.data = data;
		}
	}

	private static final String[] FEED_TYPES = {
		"PET", "COLLECTION", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "DEATH", "SLAYER"
	};

	/** Fetch the cloud feed's milestones for one-shot journal adoption. */
	public void fetchFeed(String baseUrl, String rsn, Consumer<java.util.List<FeedEvent>> onDone)
	{
		fetchFeed(baseUrl, rsn, 300, onDone);
	}

	/** As above with an explicit window — the deep one-shot import asks for
	 *  the server's maximum so the whole recorded past reaches the journal. */
	public void fetchFeed(String baseUrl, String rsn, int limit,
		Consumer<java.util.List<FeedEvent>> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/activity/events");
		if (url == null)
		{
			if (onDone != null)
			{
				onDone.accept(null);
			}
			return;
		}
		url = url.newBuilder().addQueryParameter("player", rsn)
			.addQueryParameter("limit", String.valueOf(limit)).build();
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("fetchFeed failed", e);
				if (onDone != null)
				{
					onDone.accept(null);
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				java.util.List<FeedEvent> out = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						com.google.gson.JsonArray arr = gson.fromJson(r.body().charStream(),
							com.google.gson.JsonArray.class);
						out = new java.util.ArrayList<>();
						for (JsonElement el : arr)
						{
							if (!el.isJsonObject())
							{
								continue;
							}
							JsonObject o = el.getAsJsonObject();
							String raw = str(o, "type").toUpperCase(java.util.Locale.ROOT)
								.replace(' ', '_');
							String norm = null;
							for (String t : FEED_TYPES)
							{
								if (raw.startsWith(t))
								{
									norm = t;
									break;
								}
							}
							if (norm == null)
							{
								continue;   // loot and misc types stay out of the feed
							}
							JsonObject data = new JsonObject();
							if (o.has("payload") && o.get("payload").isJsonObject())
							{
								JsonObject pl = o.getAsJsonObject("payload");
								if (pl.has("extra") && pl.get("extra").isJsonObject())
								{
									for (java.util.Map.Entry<String, JsonElement> e
										: pl.getAsJsonObject("extra").entrySet())
									{
										if (!e.getValue().isJsonNull() && !"type".equals(e.getKey()))
										{
											data.add(e.getKey(), e.getValue());
										}
									}
								}
							}
							out.add(new FeedEvent(num(o, "ts").doubleValue(), norm, data));
						}
					}
				}
				catch (RuntimeException e)
				{
					log.debug("fetchFeed parse failed", e);
				}
				if (onDone != null)
				{
					onDone.accept(out);
				}
			}
		});
	}

	/** Fetch one source's item rows from the cloud ledger (drill-on-demand). */
	public void fetchSourceItems(String baseUrl, String rsn, String source,
		Consumer<java.util.List<LedgerItem>> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/activity/drops");
		if (url == null)
		{
			if (onDone != null)
			{
				onDone.accept(null);
			}
			return;
		}
		url = url.newBuilder().addQueryParameter("player", rsn)
			.addQueryParameter("source", source).addQueryParameter("limit", "1").build();
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("fetchSourceItems failed", e);
				if (onDone != null)
				{
					onDone.accept(null);
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				java.util.List<LedgerItem> out = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						JsonObject body = parse(r);
						if (body != null && body.has("by_item") && body.get("by_item").isJsonArray())
						{
							out = new java.util.ArrayList<>();
							for (JsonElement el : body.getAsJsonArray("by_item"))
							{
								if (!el.isJsonObject())
								{
									continue;
								}
								JsonObject o = el.getAsJsonObject();
								out.add(new LedgerItem(str(o, "name"),
									num(o, "qty").longValue(), num(o, "value").longValue()));
							}
						}
					}
				}
				catch (RuntimeException | IOException e)
				{
					log.debug("fetchSourceItems parse failed", e);
				}
				if (onDone != null)
				{
					onDone.accept(out);
				}
			}
		});
	}

	private static String str(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static Number num(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsNumber() : 0;
	}

	public void fetchCounters(String baseUrl, String token, Consumer<Map<String, Integer>> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/counters/seed/" + token);
		if (url == null)
		{
			if (onDone != null)
			{
				onDone.accept(null);
			}
			return;
		}
		Request request = new Request.Builder().url(url).get().build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("fetchCounters failed", e);
				if (onDone != null)
				{
					onDone.accept(null);
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				Map<String, Integer> out = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						JsonObject body = parse(r);
						if (body != null && body.has("stats") && body.get("stats").isJsonObject())
						{
							out = new HashMap<>();
							for (Map.Entry<String, JsonElement> e : body.getAsJsonObject("stats").entrySet())
							{
								try
								{
									out.put(e.getKey(), e.getValue().getAsInt());
								}
								catch (RuntimeException ignored)
								{
									// non-int value — skip that key
								}
							}
						}
					}
				}
				catch (Exception e)
				{
					log.debug("fetchCounters parse error", e);
				}
				if (onDone != null)
				{
					onDone.accept(out);
				}
			}
		});
	}

	/** As above, but attaches a PNG screenshot as multipart (the server keeps or
	 *  prunes it per its per-event screenshot policy). Null/empty png → JSON body. */
	public void postEvent(String baseUrl, String token, JsonObject event, @Nullable byte[] png)
	{
		HttpUrl url = resolve(baseUrl, "api/events/" + token);
		if (url == null)
		{
			log.debug("postEvent: bad server URL {}", baseUrl);
			return;
		}
		RequestBody body;
		if (png != null && png.length > 0)
		{
			body = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("event", gson.toJson(event))
				.addFormDataPart("file", "screenshot.png", RequestBody.create(PNG, png))
				.build();
		}
		else
		{
			body = RequestBody.create(JSON, gson.toJson(event));
		}
		Request request = new Request.Builder()
			.url(url)
			.post(body)
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("event push failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() && r.code() != 204)
					{
						log.debug("event push returned http {}", r.code());
					}
				}
			}
		});
	}

	// ── Self-service profile management (token-authed) ─────────────────────
	// The token proves ownership; the in-game name must belong to it, which the
	// server re-checks. Each returns the parsed JSON reply (or null on any
	// failure) so the caller can read the resulting state.

	/** Set a page-view password ({@code password} non-empty) or clear it (empty). */
	public void setLock(String baseUrl, String token, String name, String password,
		Consumer<JsonObject> onDone)
	{
		JsonObject body = new JsonObject();
		body.addProperty("playerName", name);
		body.addProperty("password", password == null ? "" : password);
		manage(baseUrl, "api/plugin/lock/" + token, body, onDone);
	}

	/** List the profile in the public directory/search, or unlist it. */
	public void setVisibility(String baseUrl, String token, String name, boolean listed,
		Consumer<JsonObject> onDone)
	{
		JsonObject body = new JsonObject();
		body.addProperty("playerName", name);
		body.addProperty("public", listed);
		manage(baseUrl, "api/plugin/visibility/" + token, body, onDone);
	}

	/** Schedule deletion of the profile + data, or cancel a pending one. */
	public void scheduleDelete(String baseUrl, String token, String name, boolean cancel,
		Consumer<JsonObject> onDone)
	{
		JsonObject body = new JsonObject();
		body.addProperty("playerName", name);
		body.addProperty("action", cancel ? "cancel" : "schedule");
		manage(baseUrl, "api/plugin/delete/" + token, body, onDone);
	}

	/** Read the current self-service state (locked / listed / deletion pending). */
	public void fetchState(String baseUrl, String token, Consumer<JsonObject> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/plugin/setup");
		if (url == null)
		{
			onDone.accept(null);
			return;
		}
		url = url.newBuilder().addQueryParameter("token", token).build();
		http.newCall(new Request.Builder().url(url).get().build()).enqueue(jsonCallback(onDone));
	}

	/**
	 * Download the caller's full data export as raw JSON text. Handed the body
	 * string (or null on failure) so the plugin can write it to a local file —
	 * deliberately not opened in a browser, which would leave the token in
	 * browser history.
	 */
	public void exportData(String baseUrl, String token, String name, Consumer<String> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/plugin/export/" + token);
		if (url == null)
		{
			onDone.accept(null);
			return;
		}
		url = url.newBuilder().addQueryParameter("playerName", name == null ? "" : name).build();
		http.newCall(new Request.Builder().url(url).get().build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("export failed", e);
				onDone.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				String out = null;
				try (Response r = response)
				{
					if (r.code() == 200 && r.body() != null)
					{
						out = r.body().string();
					}
				}
				catch (Exception e)
				{
					log.debug("export read error", e);
				}
				onDone.accept(out);
			}
		});
	}

	private void manage(String baseUrl, String path, JsonObject body, Consumer<JsonObject> onDone)
	{
		HttpUrl url = resolve(baseUrl, path);
		if (url == null)
		{
			onDone.accept(null);
			return;
		}
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		http.newCall(request).enqueue(jsonCallback(onDone));
	}

	private Callback jsonCallback(Consumer<JsonObject> onDone)
	{
		return new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("manage call failed", e);
				onDone.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				JsonObject out = null;
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						out = parse(r);
					}
				}
				catch (Exception e)
				{
					log.debug("manage parse error", e);
				}
				onDone.accept(out);
			}
		};
	}

	@Nullable
	private HttpUrl resolve(String baseUrl, String path)
	{
		if (baseUrl == null || baseUrl.trim().isEmpty())
		{
			return null;
		}
		HttpUrl base = HttpUrl.parse(baseUrl.trim().replaceAll("/+$", ""));
		if (base == null)
		{
			return null;
		}
		HttpUrl.Builder b = base.newBuilder();
		for (String seg : path.split("/"))
		{
			if (!seg.isEmpty())
			{
				b.addPathSegment(seg);
			}
		}
		return b.build();
	}

	@Nullable
	private JsonObject parse(Response r) throws IOException
	{
		if (r.body() == null)
		{
			return null;
		}
		String s = r.body().string();
		if (s == null || s.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(s, JsonObject.class);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	@Nullable
	private static String optString(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static int optInt(JsonObject o, String key)
	{
		try
		{
			return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
		}
		catch (Exception e)
		{
			return 0;
		}
	}
}
