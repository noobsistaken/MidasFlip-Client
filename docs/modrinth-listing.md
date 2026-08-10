# Modrinth listing — ready to paste

Everything below is copy-paste ready. Facts verified against `gradle.properties`,
`fabric.mod.json` and `LICENSE` on 2026-08-09.

**Read the "Before you click publish" section at the bottom first.** Two items
there can get a project taken down, and one is specific to Hypixel mods.

---

## Project settings

| Field | Value |
|---|---|
| Name | `MidasFlip` |
| Slug | `midasflip` |
| Project type | Mod |
| Client/server | **Client-side only** (`environment: client`) |
| Categories | `utility`, `social` |
| License | **GPL-3.0-only** |
| Source | `https://github.com/noobsistaken/MidasFlip-Client` |
| Issues | `https://github.com/noobsistaken/MidasFlip-Client/issues` |
| Website | `https://midasflip.com` |
| Icon | `midasflip-icon-512.png` (on your Desktop) |

Environments: **Client required, Server unsupported.** It never talks to the
Minecraft server except by the commands you fire yourself.

---

## Summary (short description, 256 char limit)

```
Auction house and bazaar flip finder for Hypixel SkyBlock. Shows what an item is worth, how it got that number, and how long it takes to sell. Read-only: nothing is automated, every action is your own click.
```

204 characters.

---

## Description (long, markdown)

```markdown
# MidasFlip

Flip finder and item valuation for Hypixel SkyBlock. It prices what it can
price, says so plainly when it cannot, and shows the working behind every
number.

## It never plays the game for you

This is the part to read before anything else.

MidasFlip does not automate gameplay. No macros, no auto-buy, no synthetic
clicks, no auto-opened GUIs, no moving your mouse. The only game input that
ever leaves this mod is the one you physically made:

- **STRICT mode (the default) sends nothing at all.** It copies to your
  clipboard and you paste.
- **ASSISTED mode**, which is off until you turn it on, sends `/viewauction`
  when you press the open key. One keypress, one command.
- **Recipe leg search** is off by default behind its own separate toggle. A
  click on a craft ingredient sends one `/bz <item>` or `/ahs <item>`, chosen
  from the same field that drew the row so the hint and the command cannot
  disagree. A leg we cannot classify sends nothing. It shares one cooldown
  with assisted open.
- The **purchase confirm overlay** is off by default and only appears over an
  auction GUI this mod itself opened. A physical click in the buy zone
  forwards as exactly one slot interaction. It never auto-confirms and never
  times out into a purchase.

One physical input is at most one game action. That rule has no exceptions.

**This is lower-risk by architecture. It is not a guarantee.** Nobody can
promise you how a server will treat any mod, and anyone who tells you
otherwise is selling something. You are responsible for your own account.
Read the source, decide for yourself. It is all here.

## What it actually does

**Finds flips.** A live board of auction-house buys worth looking at, with net
profit after the real fee model, expected hold time, and a confidence score.
The feed is the same feed at the same moment for everyone. There is no paid
speed tier and there never will be.

**Prices anything you hover.** Point at an item anywhere (inventory, chest,
auction house) and get an estimate, how confident it is, and how many
comparable sales it rests on.

**Breaks that price down by modifier.** The tooltip shows what the enchants,
gems and hot potato books actually add, learned from real sales rather than
book prices. Hold SHIFT to expand the full list, including modifiers it
recognises but has not priced yet — it says "no value learned yet" rather than
printing a zero it cannot stand behind.

**Shows its working.** Every number comes with the evidence: comp count,
confidence, how many outliers were rejected, whether the bucket looks
manipulated, whether the price is falling. When there is not enough evidence,
it says "not enough comparables" instead of guessing.

**Tracks what happened.** Purchases are attributed from chat, positions are
recorded with the exits recommended at buy time, and your realised P&L is
computed from public sale records. The ledger is a local file on your machine.

**Sell side.** Lowest-BIN depth (the floor and the next one up, because the
next-lowest is your real competition), undercut alerts, and reprice
suggestions.

## Honest limits

- Estimates are estimates. Markets move, thin buckets are noisy, and
  manipulated listings exist. The confidence number is there so you can
  discount accordingly.
- Prices are computed from **public** auction records. What leaves your machine
  is the item you hover, so it can be priced, and your Minecraft name and UUID
  once you link an account. That is the whole list: your purse and bank are
  never read, nothing enumerates your inventory, and there is no bulk upload.
  Full detail at [midasflip.com/privacy](https://midasflip.com/privacy).
- A free account at midasflip.com is required, because that is how API keys
  are issued and abusers are banned. We never ask for your Minecraft password.

## Setup

1. Install Fabric Loader and Fabric API for 26.1.2
2. Drop the jar in `mods/`
3. Launch, press **J**, and follow the connect screen. It shows a six
   character code you enter at midasflip.com/dashboard.

## Source

GPL-3.0. The entire client is at
[github.com/noobsistaken/MidasFlip-Client](https://github.com/noobsistaken/MidasFlip-Client),
built from a public tag you can build yourself. Found something wrong? File it
publicly on the repo.
```

---

## Version upload

| Field | Value |
|---|---|
| Version number | `0.1.0` |
| Version title | `0.1.0 — first public release` |
| Channel | **Beta** (see note below) |
| Loaders | `Fabric` |
| Game versions | `26.1.2` |
| Dependencies | `Fabric API` — **required** |

**Use Beta, not Release, for 0.1.0.** It is the honest label for a first
public build with no field history, and it sets expectations you can raise
later. Moving Beta → Release later costs nothing; the reverse is a bad look.

### Changelog

```markdown
First public release.

**Finding flips**
- Live flip board with net profit after the verified AH fee model, expected
  hold time, sell-through and a confidence score
- Manipulation and falling-knife warnings on every flip, free for everyone
- Bazaar spreads, NPC flips, auction bid flips and craft/forge EV

**Valuing items**
- Hover any item for its estimate, confidence and comparable count
- Per-modifier breakdown on the tooltip, learned from real sales rather than
  book prices; hold SHIFT to expand it
- Bucket state on the header, so you can see your stars, recomb and scrolls
  were priced in
- Lowest-BIN depth: the floor and the next one up

**Your trades**
- Purchases attributed from chat, positions recorded with the exits
  recommended at buy time
- Local trade ledger, written atomically so a crash cannot truncate it
- Realised P&L on the dashboard, computed from public sale records

**Safety**
- STRICT mode by default: sends nothing, copies to clipboard
- ASSISTED mode is opt-in and sends `/viewauction` only on your keypress
- Recipe leg search, its own separate toggle, sends one `/bz` or `/ahs` when
  you click a craft ingredient; it shares assisted open's cooldown
- Purchase confirm overlay off by default, ASSISTED only, one physical click
  forwards exactly one slot interaction

Requires a free account at midasflip.com. Gold features are free during early
access and become paid in September.
```

---

## Gallery

You have no screenshots yet, and this is the single biggest gap in the
listing. Modrinth pages without images convert badly, and for a mod that makes
claims about honesty, showing the actual UI is the argument.

Shoot these five, in this order of value:

1. **The flip board** with real rows — the product in one image
2. **A tooltip on a heavily modified item**, breakdown visible — this is the
   differentiator nobody else has. Two things decide whether this shot works:

   - **Shoot it from your own INVENTORY, not the auction house.** Inside an AH
     menu the mod can only read the item's lore text, so it counts enchants but
     cannot see gems or hot potato books — it prints "menu view · enchants
     counted, gems/HPB not visible" and you get far fewer rows. From your
     inventory it reads the full NBT and prices everything.
   - **Pick an item with MORE than four priced modifiers**, then hold SHIFT.
     Under four, everything already fits on screen and SHIFT changes nothing,
     so the screenshot shows no benefit
3. **The comps peek** (hold TAB on a row) showing recent sales and the
   rejected-outlier count — "shows its working" made concrete
4. **The Plan tab** — the free/Gold table with `0s / 0s` on the speed row
5. **The Safety pane** with STRICT selected — the claim, visible in the product

Caption each one. An uncaptioned screenshot of a dark UI reads as noise.

---

## Before you click publish

**1. Check Modrinth's rules on external accounts and paid features.** MidasFlip
requires a free account and will have paid tiers in September. Modrinth allows
this, but it must be **clearly disclosed on the page** — the description above
does that in two places. Do not remove either. A listing that hides a paid
dependency is the most common takedown reason for tools like this.

**2. Do not soften the safety language.** The "lower-risk, not a guarantee"
paragraph is deliberate and it is project law. Every temptation to write
"undetectable", "safe", "allowed" or "tolerated" is a temptation to make a
claim you cannot back, in the one place where being wrong costs somebody their
account. It also reads as more trustworthy, not less — the SkyBlock community
has watched a lot of mods overclaim.

**3. The jar you upload must be the one you tested.** Upload
**`~/Desktop/midasflip-0.1.0.jar`**, sha256

```
0e2ad413e9fbfccb428ab7ad23f3fbe6c67eafa241148fe11682dddf9a9fdc10
```

Verify with `shasum -a 256` before uploading. This is the same hash as the
`build/libs` output and the asset already attached to the GitHub draft release,
so all three channels serve identical bytes.

**Do not upload `midasflip-smoketest.jar` or `midasflip-smoketest-0.1.0.jar`.**
Both are on your Desktop, both are older test builds, and neither has the
final fixes. The smoketest jar is `0dcb240a…`; if `shasum` prints anything
starting with `0dcb` or `962b`, you have the wrong file.

**4. The backend must be deployed before the jar is public.** The mod pairs
against midasflip.com and there is no fallback path if the API does not
answer. A user who installs before the server is ready sees a mod that cannot
authenticate, and that is what they will write in the comments.

**5. CurseForge is a separate submission** with its own review queue, which can
take days. If you want both live on day one, submit CurseForge first because
it is the slower one.
