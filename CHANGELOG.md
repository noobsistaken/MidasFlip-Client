# Changelog

## 0.1.3 — 2026-08-14
- Fixed pet prices in auction menus again, properly this time. 0.1.2 only fixed
  max-level pets; any pet whose level the mod had to guess from could still land
  in the wrong bucket. A Lvl 88 legendary Wolf priced at 5.0M against a real
  16.9M. The mod now derives the bucket from Hypixel's actual pet XP table, and
  says "value unverified" when the level genuinely cannot decide it instead of
  guessing.
- The sell panel now uses your recorded buy key even when you hold two of the
  same pet, as long as both agree on it.
- The menu header shows the real running version instead of a hardcoded one.

## 0.1.2 — 2026-08-13
- Fixed pet prices in auction menus. Max-level pets fed past 30M EXP used the
  wrong bucket, so the sell target could read ~35% under value. Flip board was
  never affected.

## 0.1.1 — 2026-08-11
- Fixed the sell overlay underpricing recombobulated and reforged items.

## 0.1.0 — 2026-08-10
- First release.
