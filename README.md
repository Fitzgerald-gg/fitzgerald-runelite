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

## Import

The Journal tab can merge another copy of the same account's record: a backup, another computer,
or one kept for you elsewhere. Every store merges as a floor — per-key maximum, earliest
first-sighting, best personal best — so importing twice changes nothing and an older file can
never lower what you already hold. A `<name>.history.jsonl` sitting beside the journal comes
across too.

There is nothing to export: the record is already a JSON file you own, so the tab opens the
folder instead.

## Optional cloud sync

Off by default, with the server field blank. Point it at a Chronicle-compatible server and it
additionally sends a copy of the journal upward on the write interval and at logout.

- One-way. Nothing is ever read back, and every feature works the same with it off.
- Your own account only. Another player's stats, drops or activity are never submitted.
- What travels: your display name, event data (raw item ids and quantities), counter totals,
  per-skill XP, and collection-log and achievement snapshots.
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
archive a late install inherits. Disable either and the panel says so; everything else keeps
working. No third-party plugins are needed.

## Build

```sh
gradle run          # dev-mode RuneLite with the plugin side-loaded
gradle shadowJar    # fat jar for manual side-loading
gradle test         # includes PanelPreviewTest, which renders every panel surface to build/panel-preview/
```

## Licence

BSD 2-Clause, see [LICENSE](LICENSE). Not affiliated with Jagex or RuneLite.
