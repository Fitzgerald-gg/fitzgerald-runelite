# Fitzgerald.gg — RuneLite companion plugin

A RuneLite plugin that wires your account into **[Fitzgerald.gg](https://fitzgerald.gg)**, a
personal OSRS profile site. It captures your gameplay natively and syncs it to *your* profile — you
do **not** need any other plugin installed for it to work.

It records two kinds of thing:

1. **Events** — loot drops, level-ups, deaths, quests completed, collection-log entries, combat
   achievements, achievement diaries, clue casket completions, kill-counts, pets, and slayer kills (tagged on-task). If you
   opt in, a screenshot is captured for notable events (a big drop, a 99, a pet, a quest) and
   uploaded with them.
2. **Lifetime stat counters** — running totals (tiles walked, pickpockets, damage done, food eaten,
   logs chopped, ore mined, fish caught, food cooked, and more), pushed on a timer and on logout.
   The main counters for all sixteen non-combat skills are detected from the XP drop plus the gained
   item, clicked object, consumed item or interaction target (so they survive chat being filtered).
   A few zero-XP outcomes — failed pickpockets, burnt food, seeds planted — still read the game/spam
   chat and need those messages enabled. The
   per-type resolution is server-side, so a new resource — a new log, ore, fish or rune — is tracked
   the day it launches with no plugin update.

The counters are seeded from the server on login and held in memory, so they continue your existing
totals rather than resetting.

## Turning it on

Sync is **off** when you install it. Nothing is enrolled, captured or transmitted
until you tick **Enabled** in the plugin's settings, and screenshot upload is a
second, separate opt-in. Until then the plugin sits inert in the sidebar.

## What gets sent, and to whom

> **This plugin transmits your player data and screenshots, along with your IP address, to
> `fitzgerald.gg` — a server operated by the plugin author, not controlled or verified by the
> RuneLite developers.** This is disclosed on the Plugin Hub install prompt and here. Nothing is sent
> anywhere else.

- **Destination:** the **Server base URL** (default `https://fitzgerald.gg`, hard-coded; editable
  under *Advanced* only if you self-host). Every request is a plain `POST` to a fixed
  `https://fitzgerald.gg/api/...` path — no dynamic or remotely-fetched endpoints.
- **Events** → `POST {server}/api/events/{token}` (multipart when a screenshot is attached), carrying
  the event type, the raw item IDs / quantities / chat text that triggered it, and — for slayer
  kills — the current task. The server enriches item IDs into names and prices; the plugin sends raw
  data only.
- **Counters** → `POST {server}/api/counters/{token}` as `{"playerName": "...", "stats": {…absolute ints…}}`.
- The plugin reads only your display name, in-game events, and integer stat counters. **No password,
  email, bank PIN, or private-message content is ever read or transmitted.** Uploaded screenshots are
  ordinary game-view captures and may incidentally include other players' names or public chat.

## Finding your profile

Once **Enabled** is ticked, the plugin enrols your account automatically on your next login.
Events (drops, levels, pets, quests, diaries…) are sent as they happen; lifetime counters push
every 5 minutes and on logout, and your existing totals load on login so nothing resets. Your
profile lives at **`https://fitzgerald.gg/osrs/<your-rsn>`** — click **Open my page** in the side
panel to jump there. It works as soon as your first data arrives, and the link is shareable.

## Privacy & enrolment model

- **Self-enrol, trust-on-first-use.** On first login the plugin calls `POST {server}/api/plugin/enroll`
  with your RSN. The server mints a per-account token and returns it; the plugin stores that token
  **per OSRS account** (RuneLite RSProfile-scoped), so multi-account users get one token each.
- **Unlisted, not hidden.** A freshly enrolled page is live and viewable by its direct link the
  moment your first data arrives — it simply isn't shown in the public player directory or site
  search. You control all of this yourself from the side panel (below): lock the page behind a
  passphrase, list/unlist it, export your data, or delete it. Those controls are authenticated by
  your account's token, so no website login is needed.
- **Already-enrolled RSNs are refused.** If your RSN is already tracked, enrolment returns `409` and
  the existing token is never leaked, so no one can claim your account by curling the endpoint.
- **Admin-blockable.** An admin can block an RSN; enrolment and ingest are both refused for it.
- **Master off-switch.** Disabling the plugin stops all enrol / capture / push immediately.

## The side panel

Open the Fitzgerald.gg icon in the RuneLite sidebar:

- shows the **enrolled RSN** and the **last-push status**,
- **Push stats now** — force an immediate counter push,
- **Re-enrol this account** — retry enrolment if it hadn't succeeded yet,
- **Open my page** — opens `https://fitzgerald.gg/osrs/<your-rsn>`.

### Privacy & data (self-service)

All authenticated by your account's token — no website account required:

- **Lock page** — set a passphrase; viewers then need it to see your page. One-shot dialog; the
  passphrase is sent once to the server (hashed there) and never stored by the plugin.
- **List / unlist in public directory** — choose whether your page appears in public search and the
  directory. Unlisting never hides the page from its direct link — that's what the lock is for.
- **Export my data** — downloads everything the server holds for you as a JSON file into your
  RuneLite folder (your token and password are never included in the export).
- **Delete my data** — schedules removal of your profile and all its data after a **7-day grace
  period**. A **Cancel deletion** button appears while it's pending; nothing is removed if you cancel.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| Enabled | **off** | Master switch. Off means no enrol / capture / push — you turn it on. |
| Capture screenshots | **off** | Attach a screenshot to notable events. Separate opt-in. |
| Push interval | 5 min | How often lifetime counters are pushed. |
| Server base URL (Advanced) | `https://fitzgerald.gg` | Only change if self-hosting. |

## Dependencies

- Depends on RuneLite's built-in **Slayer** plugin (declared via `@PluginDependency`) so slayer kills
  can be tagged on-task. If you disable Slayer, the side panel prompts you to turn it back on; the rest
  of the plugin keeps working.
- No third-party plugins are required.

## Build & install

Local dev / side-load:

```sh
gradle run                # launches a dev-mode RuneLite with the plugin loaded
gradle shadowJar          # -> build/libs/fitzgerald-1.0.0-all.jar (manual side-load only)
```

## Licence

BSD 2-Clause — see [LICENSE](LICENSE).

- Not affiliated with Jagex, RuneLite, or any other plugin author.
