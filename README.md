# Chronicle — a local journal of your OSRS life

A RuneLite plugin that keeps a **journal of your Old School RuneScape account on your own
computer** — loot, levels, kill counts, collection log, clues, quests, diaries, combat
achievements, slayer tasks, pets, deaths, and a few hundred lifetime counters — presented in a
side panel. It captures everything natively; no other plugin is required.

**Local-first is the whole design.** The journal lives in plain JSON under
`.runelite/chronicle/`, every view in the panel is computed on your client, and the plugin ships
with the reference tables it needs (skill XP ladders, production rules, drop-rate book). There is
no server behind any feature: with the network unplugged you get the identical plugin.

## What it keeps

1. **Events** — loot drops (with per-source item bags, personal-best kill times, and loot you
   walked away from), level-ups, deaths (and what killed you), quests, achievement diaries, combat
   achievements, collection-log slots (counted the moment they drop), clue caskets, kill counts,
   pets, and slayer kills tagged on-task. A dated feed — the journal proper — records the
   milestones, and each session closes with its own diary line.
2. **Lifetime counters** — running totals from tiles walked to per-wood logs chopped, per-NPC
   pickpockets, per-potion doses (with what the habit cost), teleports by destination, and more.
   The typed per-resource resolution happens **on your client** from the XP drop plus the gained
   item, clicked object, consumed item or interaction target — so it survives chat being filtered,
   and works entirely offline. A few zero-XP outcomes (failed pickpockets, burnt food, seeds
   planted, agility laps) still read the game/spam chat and need those messages enabled.
3. **History** — a daily baseline of your skills and counters, so the History tab can answer any
   two dates. The panel also computes your **dry streaks** (collection-log chases against the
   wiki's drop rates) and your **task-by-task slayer journey** locally.

On first run the plugin also **inherits your existing Loot Tracker archive** (RuneLite's core
Loot Tracker stores its record in your local profile), so your history starts years deep, not at
zero.

## Optional cloud sync — upward only

Off by default, blank by default. If you point the plugin at a Chronicle-compatible server
(*Advanced → Cloud server* + a token issued by that server's operator), it will **additionally
send a copy of your journal upward** on the push interval and at logout. That is the entire
relationship:

- **One-way.** The plugin never reads anything back. Panels, counters, history and dryness are
  identical with sync on or off. If the server vanishes, you lose nothing.
- **Your own account only.** The plugin never submits other players' data.
- **What travels:** your display name, event data (raw item IDs/quantities), counter absolutes,
  per-skill XP, collection-log and achievement snapshots — and, if you separately opt in,
  screenshots of notable moments. No password, email, bank PIN, or private-message content is
  ever read or transmitted.

> **With cloud sync enabled, the plugin transmits your player data (and your IP address) to the
> server you configure — a third-party server not controlled or verified by the RuneLite
> developers.** With it disabled (the default), Chronicle never touches the network.

## The side panel

Seven tabs: **Home** (this session, adaptively — only the cards your play earned), **Drops**
(the ledger: sources, items, taken and left behind), **Slayer** (current task, the journey, kill
log), **Log** (the full collection log), **Stats** (every counter, in the same taxonomy the
journal uses everywhere), **History** (any period, any two dates), **Journal** (the dated feed).
Type any item or source into the search and press Enter to pivot between item and source views
from anywhere.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| Push interval | 5 min | How often the journal refreshes to disk (and pushes, when cloud sync is on). |
| Enable cloud sync (Advanced) | **off** | Additionally mirror the journal upward to the server below. |
| Cloud server (Advanced) | *blank* | Base URL of a Chronicle-compatible server. Blank = no network, ever. |
| Cloud token (Advanced) | *blank* | The push token for this account, issued by that server's operator. |
| Upload screenshots (Advanced) | **off** | Cloud sync only: attach a screenshot to notable events. Separate opt-in. |

## Dependencies

- Depends on RuneLite's built-in **Slayer** plugin (declared via `@PluginDependency`) so kills can
  be tagged on-task. If you disable Slayer, the side panel prompts you; the rest keeps working.
- No third-party plugins are required.

## Build & install

```sh
gradle run                # launches a dev-mode RuneLite with the plugin loaded
gradle shadowJar          # -> build/libs/*-all.jar (manual side-load only)
gradle test               # includes PanelPreviewTest: renders every panel surface to build/panel-preview/
```

## Licence

BSD 2-Clause — see [LICENSE](LICENSE).

- Not affiliated with Jagex, RuneLite, or any other plugin author.
