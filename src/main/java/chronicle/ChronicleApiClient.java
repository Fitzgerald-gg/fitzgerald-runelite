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
import java.io.Reader;
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
import okhttp3.ResponseBody;

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

	// The server base URL is the player's own setting, so a reply is never
	// assumed to be well behaved. Anything past this ceiling is dropped unread
	// rather than buffered, which stops a hostile or broken host from streaming
	// the client out of heap; the largest reply the API has is a full slayer
	// journey, a few hundred KB at the very worst.
	private static final int MAX_BODY_CHARS = 1024 * 1024;

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

	/** One dry chase from the cloud's grinds ledger. */
	public static final class GrindRow
	{
		public final String boss;
		public final String item;
		public final long kc;
		public final long rate;
		public final double percentileDry;

		GrindRow(String boss, String item, long kc, long rate, double percentileDry)
		{
			this.boss = boss;
			this.item = item;
			this.kc = kc;
			this.rate = rate;
			this.percentileDry = percentileDry;
		}
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

	// Ceilings for the one downward read. What comes back is inherited wholesale
	// into the journal, which is the system of record, so every figure is bounded
	// before it leaves this class: an absurd count or a far-future timestamp would
	// otherwise be persisted to disk and skew the panel's totals for good. The
	// task ceiling matches the journal's own runaway guard, and the reply arrives
	// newest-first, so trimming at the ceiling keeps the recent history.
	private static final int MAX_JOURNEY_TASKS = 1000;
	private static final int MAX_TASK_NAME = 64;
	private static final long MAX_COUNT = 10_000_000L;
	private static final long MAX_GP = 1_000_000_000_000L;
	private static final double MAX_TS = 4_102_444_800.0;   // 2100-01-01

	private static long clamp(long v, long max)
	{
		return v < 0 ? 0 : Math.min(v, max);
	}

	private static double clampTs(double ts)
	{
		// Written as a negated test so a NaN reading falls through to zero.
		return !(ts > 0) ? 0 : Math.min(ts, MAX_TS);
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
						String raw = readCapped(r);
						JsonObject o = raw != null && !raw.isEmpty()
							? gson.fromJson(raw, JsonObject.class) : null;
						if (o != null)
						{
							java.util.List<SlayerTask> tasks = new java.util.ArrayList<>();
							if (o.has("tasks") && o.get("tasks").isJsonArray())
							{
								for (JsonElement el : o.getAsJsonArray("tasks"))
								{
									if (tasks.size() >= MAX_JOURNEY_TASKS)
									{
										break;
									}
									if (!el.isJsonObject())
									{
										continue;
									}
									JsonObject t = el.getAsJsonObject();
									String name = str(t, "task");
									if (name.length() > MAX_TASK_NAME)
									{
										name = name.substring(0, MAX_TASK_NAME);
									}
									// the site's kill figure: total_kills, else the target
									long kills = t.has("total_kills") && !t.get("total_kills").isJsonNull()
										? num(t, "total_kills").longValue()
										: num(t, "count_target").longValue();
									tasks.add(new SlayerTask(name, clamp(kills, MAX_COUNT),
										clamp(num(t, "assignment").longValue(), MAX_COUNT),
										clamp(num(t, "no_loot_kills").longValue(), MAX_COUNT),
										clampTs(num(t, "ts").doubleValue()),
										clamp(num(t, "total_value").longValue(), MAX_GP),
										t.has("in_progress") && !t.get("in_progress").isJsonNull()
											&& t.get("in_progress").getAsBoolean()));
								}
							}
							out = new SlayerJourney(
								(int) clamp(num(o, "completed_tasks_count").longValue(), MAX_COUNT),
								clamp(num(o, "total_kills").longValue(), MAX_COUNT),
								clamp(num(o, "total_value_gp").longValue(), MAX_GP),
								clamp(num(o, "total_xp_est").longValue(), MAX_GP), tasks);
						}
					}
				}
				catch (RuntimeException | IOException e)
				{
					log.debug("slayer journey parse failed", e);
				}
				onDone.accept(out);
			}
		});
	}

	private static final String[] FEED_TYPES = {
		"PET", "COLLECTION", "COMBAT_ACHIEVEMENT", "QUEST", "DIARY", "CLUE", "DEATH", "SLAYER"
	};

	private static String str(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static Number num(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsNumber() : 0;
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

	/**
	 * The whole reply as text, or null once it runs past {@link #MAX_BODY_CHARS}.
	 * Reading through a fixed ceiling — rather than taking the body whole — means
	 * a reply of any advertised (or unadvertised, chunked) length costs a fixed
	 * amount of heap, and an over-long one abandons the connection mid-stream.
	 */
	@Nullable
	private static String readCapped(Response r) throws IOException
	{
		ResponseBody body = r.body();
		if (body == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		char[] buf = new char[8192];
		try (Reader in = body.charStream())
		{
			int n;
			while ((n = in.read(buf)) != -1)
			{
				if (sb.length() + n > MAX_BODY_CHARS)
				{
					log.debug("server reply over {} chars; discarded", MAX_BODY_CHARS);
					return null;
				}
				sb.append(buf, 0, n);
			}
		}
		return sb.toString();
	}

	@Nullable
	private JsonObject parse(Response r) throws IOException
	{
		String s = readCapped(r);
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
