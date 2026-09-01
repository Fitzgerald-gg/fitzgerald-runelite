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
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Async wrapper over the configured Chronicle server's HTTP API. Everything here
 * is a send; nothing is read back, because the journal on disk is the record.
 * Calls run on OkHttp's dispatcher threads and report through a
 * {@link Consumer}, so callers hop back to the client or EDT thread themselves.
 */
@Slf4j
@Singleton
public class ChronicleApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	// The base URL is the player's own setting, and a reply from it is not assumed
	// to be well behaved. The only reply read here is a push receipt, a few dozen
	// bytes of JSON, so a megabyte is a wide margin. Anything past it is dropped
	// unread instead of buffered into heap.
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

	// The account's stable hash (RuneLite client.getAccountHash()), set by the
	// plugin whenever the local player resolves. It lets the server read an
	// in-game rename as the same account rather than an alt sharing the token,
	// and adopt the new display name on its own. String-encoded to sidestep
	// 64-bit JSON number precision.
	private volatile long accountHash = -1L;

	public void setAccountHash(long h)
	{
		this.accountHash = h;
	}

	private void addAccountHash(JsonObject payload)
	{
		long h = accountHash;
		// -1 is RuneLite's no-account sentinel. Every other value is valid,
		// negatives included: the 64-bit hash often has its sign bit set.
		if (h != -1L)
		{
			payload.addProperty("accountHash", String.valueOf(h));
		}
	}

	/**
	 * POST {base}/api/counters/{token} with {"playerName": name, "stats": {..absolute ints..}}.
	 * The token is the auth. A playerName that does not match one of the token's
	 * known RSNs is rejected silently with a 204, so {@code name} has to be the
	 * exact in-game display name.
	 */
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
		// Account variant (ironman/gim/…) as the game reports it. Keeps the
		// profile's account_type in step without hand-tagging.
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
		// Per-skill level and XP, which spares the profile the wait for the daily
		// hiscores pull. Null on the logout flush, where the client can no longer
		// be read.
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

	/**
	 * Fire one event at POST /api/events/{token} and forget it. The plugin sends
	 * raw fields only (item ids, quantities, context); naming, GE value and
	 * rarity are the server's job. {@code event} is the full body:
	 * {@code playerName, type, data, eventId}. Never throws on the caller thread,
	 * and failures go to the debug log.
	 */
	public void postEvent(String baseUrl, String token, JsonObject event)
	{
		HttpUrl url = resolve(baseUrl, "api/events/" + token);
		if (url == null)
		{
			log.debug("postEvent: bad server URL {}", baseUrl);
			return;
		}
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(event)))
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
					if (!r.isSuccessful())
					{
						log.debug("event push HTTP {}", r.code());
					}
				}
			}
		});
	}

	// Sends the collection log as {by_cat, kcs, slayer_kcs, cat_counts,
	// clog_items, finished, available}. clog_items is every obtained item from a
	// full-log read; slayer_kcs is per-monster lifetime kills. The server
	// floor-merges partial snapshots; sending whatever has been scraped is fine.
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

	/** One dry chase: the journal's own kc weighed against the bundled wiki rate book. */
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

	/** The slayer journey the journal computes for the panel, from its on-disk task array. */
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

	/** The reply as text, or null once it runs past {@link #MAX_BODY_CHARS}. */
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
