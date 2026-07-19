/*
 * Copyright (c) 2026, Fitzgerald.gg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package gg.fitzgerald.plugin;

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
 * Thin async wrapper over the Fitzgerald.gg HTTP API. Every call is fired on
 * OkHttp's own dispatcher threads (never the client thread) and reports back
 * through a {@link Consumer} callback. Callbacks may run on any thread; the
 * caller is responsible for hopping back to the client/EDT thread as needed.
 */
@Slf4j
@Singleton
public class FitzgeraldApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.get("image/png");

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	FitzgeraldApiClient(OkHttpClient http, Gson gson)
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
		Map<String, Integer> stats, @Nullable String accountType, @Nullable Consumer<PushResult> onDone)
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
