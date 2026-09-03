# Chronicle

A RuneLite side panel that keeps a journal of your Old School RuneScape account on your own
computer: loot, levels, kill counts, the collection log, clues, quests, diaries, combat
achievements, slayer tasks, pets, deaths, and a few hundred lifetime counters.

The journal is plain JSON under `.runelite/chronicle/`. Every view in the panel is computed on
your machine, and the plugin ships the reference tables it needs, so with the network unplugged
you get the same plugin.

## What it records

**Events.** Loot drops with per-source item bags, personal-best kill times, and loot left on the
ground. Level-ups, deaths and what killed you, quests, diaries, combat achievements, clue
caskets, kill counts, pets, and slayer kills tagged on-task. Milestones land in a dated feed, and
each session closes with a line of its own.

**Lifetime counters.** From tiles walked to per-wood logs chopped, per-NPC pickpockets, potion
doses and what they cost, teleports by destination. Per-resource identity is worked out on your
client from the XP drop plus the item gained, object clicked, item consumed or target
interacted with, so it survives filtered chat. A few zero-XP outcomes read the game and spam chat
instead: failed pickpockets, burnt food, seeds planted, agility laps.

**History.** A daily baseline of skills, counters and kill counts, so any two dates can be
compared. Dry streaks are computed against a bundled drop-rate table, and the slayer journey is
rebuilt from your own on-task kills.

On first run it also reads RuneLite's core Loot Tracker archive, which is already on your disk,
so the record starts years back rather than empty.

## How the collection log is read

Two ways, both driven by you. Logging in gives the completion fraction from varps. Opening the
log yourself gives the pages you look at — and, on that same open, Chronicle fires the log's own
Search operation so the server transmits every page at once, which is how one open records the
whole log instead of only the tab you happened to click.

WikiSync and TempleOSRS reach the same data with the same interface operation. They ask for it
differently: WikiSync adds a button to the log and syncs when you press it, and TempleOSRS has
an automatic mode you switch on yourself. Chronicle does it on your own log open, without asking
and without a line of chat.

It never opens the log for you, and it does nothing while you are viewing someone else's log
through a POH adventure log. No log, no read.

## Import

The Journal tab can merge another copy of the same account's record: a backup, another computer,
or one kept for you elsewhere. Every store merges as a floor — per-key maximum, earliest
first-sighting, best personal best — so importing twice changes nothing and an older file can
never lower what you already hold. A `<name>.history.jsonl` sitting beside the journal comes
across too.

There is nothing to export: the record is already a JSON file you own, so the tab copies the
folder's path to your clipboard instead.

**Upgrading from Fitzgerald.gg.** This plugin was called Fitzgerald.gg and kept its journal in
`.runelite/fitzgerald`. Chronicle reads `.runelite/chronicle` and stores its settings under a new
key, so an older record is not picked up on its own and the server URL and token need entering
again. Nothing was deleted: point Import at `.runelite/fitzgerald/<name>.json` and the whole
record merges in, history spine included.

## Optional cloud sync

Off by default, with the server field blank. Point it at a Chronicle-compatible server and it
additionally sends a copy of the journal upward on the write interval and at logout.

- One-way. Nothing is ever read back, and every feature works the same with it off.
- Your own account only. Another player's stats, drops or activity are never submitted.
- What travels: your display name, your account type, and your RuneLite account hash, alongside
  event data (raw item ids and quantities), counter totals, per-skill XP, collection-log and
  achievement snapshots, and — on a group ironman — shared-storage movements. The account hash is
  what lets a server follow an in-game rename without re-keying an alt that shares a token.
- No images. Chronicle takes no screenshots, so nothing it sends can carry another player's name
  or a line of chat.

> With cloud sync enabled the plugin transmits your player data, and your IP address, to the
> server you configure — a third-party server not controlled or verified by the RuneLite
> developers. With it off, Chronicle never touches the network.

## The panel

Seven tabs. **Home** shows the current session, with only the cards your play has earned.
**Drops** is the ledger of sources and items, taken and left behind. **Slayer** holds the current
task, the task-by-task journey and the kill log. **Log** is the collection log. **Stats** is every
counter. **History** compares any two periods. **Journal** is the dated feed.

Type any item or source into the search box and press Enter to pivot between the item's view and
the source's view from anywhere.

## Dependencies

Two of RuneLite's built-in plugins, declared with `@PluginDependency`: **Slayer**, so kills can be
tagged on-task, and **Loot Tracker**, whose event carries chest and casket loot and whose stored
archive a late install inherits. Disable either and only that slice stops — on-task tagging
without Slayer, chest and casket loot without Loot Tracker — while everything else keeps
working. The panel does not flag it, so RuneLite's own plugin list is where to check. No
third-party plugins are needed.

## Build

```sh
gradle run          # dev-mode RuneLite with the plugin side-loaded
gradle shadowJar    # fat jar for manual side-loading
gradle test         # includes PanelPreviewTest, which renders every panel surface to build/panel-preview/
```

## Licence

BSD 2-Clause, see [LICENSE](LICENSE). Not affiliated with Jagex or RuneLite.
