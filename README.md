# MidasFlip client mod

A Fabric mod for Hypixel SkyBlock that shows you auction-house and bazaar
flips, what your items are worth, and how your past flips actually went.
This repository is the complete client. You can read every line of code
that runs inside your game.

## What this mod does

- Shows a live feed of flip candidates found by our server.
- Prices the item under your cursor: estimate, confidence, the recent
  sales behind it, and the number of outliers we rejected to get there.
- Tracks your listings and purchases into a local profit ledger, fee-true.
- Warns you when someone undercuts you, and what repricing would cost.

## What this mod does NOT do

- It never generates game input on its own. No mouse movement, no
  auto-clicks, no timed actions. One physical input from you produces at
  most one game action, always.
- It does not send your purse, chat, session, or inventory contents to
  anyone. Pricing lookups send the hovered item's identity only — its
  SkyBlock id, stars, modifiers, pet data — because that is what a price
  is looked up by. Your profit ledger is stored locally.
- It does not decide prices. Valuation runs server-side against the full
  sold-auction history. The client displays results and shows the work.

## The three opt-in actions, disclosed plainly

Everything above holds in every mode. Three OPT-IN features, all off by
default, go one deliberate step further, and you should know exactly what
they do before enabling them:

- **Assisted open**: when you press the open keybind yourself, the mod
  sends one `/viewauction` command for the flip you are looking at. Your
  physical keypress, one command, nothing else. In STRICT mode (the
  default posture) this never happens — STRICT sends no command and no
  game action, ever.
- **Purchase confirm overlay** (assisted mode only): renders only over
  auction screens the mod itself opened, and forwards your one physical
  click as exactly one click on the real purchase slot. It never
  auto-confirms, never times out into a purchase, and never overlays
  Hypixel's own final confirmation screen. Every forwarded click is
  written to a local audit log with the physical input that caused it.
- **Recipe leg search** (assisted mode only, and its own separate toggle):
  clicking an ingredient on the craft board sends one `/bz <item>` for a
  bazaar leg or `/ahs <item>` for an auction one, chosen from the same field
  that drew the row so the hint and the command cannot disagree. A leg we
  cannot classify sends nothing. The quantity goes to your clipboard; the mod
  never types into Hypixel's order sign. It shares one cooldown with assisted
  open, so the two together still cannot exceed one action per cooldown.

We call this design lower-risk, not "safe" — that word would be a lie in
a rules-are-theirs-to-interpret game. Read the code and decide for
yourself; that is why it is public.

## What stays server-side, and why

The valuation engine (comparable-sale selection, outlier rejection,
modifier pricing, manipulation detection) is the product and stays
closed. Two honest reasons: it is how this project pays for its servers,
and keeping it server-side means the client contains no paid-feature
logic to crack — the client you can read is the whole client everyone
gets. Every estimate the server returns carries its inputs (comp count,
confidence, source), so you can audit what it claimed after the fact.

## Your API token

Your account token is stored in your OS keychain when one is available
(macOS Keychain, Linux libsecret). Where there is no keychain, it falls
back to a file with owner-only permissions and machine-keyed wrapping.
Plain words about that fallback: it is obfuscation, not encryption-grade
secrecy. Anything running as your OS user could recover it. What it
protects against is the common accident — pasting your config file into
a Discord channel. The config file (`midasflip.json`) no longer
contains the token at all. If you think a token leaked, revoke it on
your account page; it dies within seconds, server-side.

## Building

Requirements: JDK 25.

```
./gradlew build
```

The jar lands in `build/libs/`. `./gradlew test` runs the unit tests
(pet-identity parsing, config round-trip — the pieces where a silent
mistake would misprice your items).

## License

GPL-3.0. You can fork it, modify it, and ship it — but your fork stays
open source too.
