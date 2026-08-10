# CurseForge listing — ready to paste

Companion to `modrinth-listing.md`. Same product, different rules: CurseForge
review is **slower and stricter**, and its editor is rich-text rather than
markdown, so the description below is written to survive being pasted into a
WYSIWYG box.

**Submit CurseForge FIRST.** It is the slow one. Modrinth is often same-day;
CurseForge has historically taken days. If you submit both this morning, the
realistic outcome is Modrinth live near launch and CurseForge landing after.

---

## Project settings

| Field | Value |
|---|---|
| Name | `MidasFlip` |
| Summary | see below |
| Category | **Addons** (primary), plus `Server Utility` if a second is allowed |
| Game versions | `26.1.2` |
| Mod loader | `Fabric` |
| License | **GNU General Public License version 3 (GPLv3)** |
| Source URL | `https://github.com/noobsistaken/MidasFlip-Client` |
| Issues URL | `https://github.com/noobsistaken/MidasFlip-Client/issues` |
| Website | `https://midasflip.com` |
| Client/Server | **Client only** |
| Logo | `midasflip-icon-512.png` |

---

## Summary (CurseForge caps this shorter than Modrinth)

```
Flip finder and item valuation for Hypixel SkyBlock. Shows what an item is worth, how it got that number, and how long it takes to sell. Nothing is automated.
```

---

## Description

Paste as plain paragraphs. CurseForge's editor mangles nested markdown, so this
version uses headings, short paragraphs and simple bullets only. No tables, no
code fences.

```
MidasFlip

Flip finder and item valuation for Hypixel SkyBlock. It prices what it can
price, says so plainly when it cannot, and shows the working behind every
number.


IT NEVER PLAYS THE GAME FOR YOU

Read this part before anything else.

MidasFlip does not automate gameplay. There are no macros, no auto-buy, no
synthetic clicks, no auto-opened menus, and nothing moves your mouse. The only
game input that ever leaves this mod is one you physically made.

- STRICT mode, the default, sends nothing at all. It copies to your clipboard
  and you paste.
- ASSISTED mode is off until you turn it on. It sends /viewauction when you
  press the open key. One keypress, one command.
- Recipe leg search is off by default behind its own separate toggle. A click
  on a craft ingredient sends one /bz or /ahs for that ingredient, chosen from
  the same field that drew the row so the hint and the command cannot
  disagree. A leg we cannot classify sends nothing. It shares one cooldown
  with assisted open.
- The purchase confirm overlay is off by default and only appears over an
  auction menu this mod itself opened. A physical click in the buy zone
  forwards as exactly one slot interaction. It never auto-confirms and never
  times out into a purchase.

One physical input is at most one game action. That rule has no exceptions.

This is lower-risk by architecture. It is not a guarantee. Nobody can promise
you how a server will treat any mod, and anyone who tells you otherwise is
selling something. You are responsible for your own account. The entire client
is open source, so read it and decide for yourself.


WHAT IT DOES

Finds flips. A live board of auction house buys worth looking at, with net
profit after the real fee model, expected hold time, and a confidence score.
The feed is the same feed at the same moment for everyone. There is no paid
speed tier and there never will be.

Prices anything you hover. Point at an item anywhere, in your inventory, a
chest, or the auction house, and get an estimate, how confident it is, and how
many comparable sales it rests on.

Breaks that price down by modifier. The tooltip shows what the enchants, gems
and hot potato books actually add, learned from real sales rather than book
prices. Hold SHIFT to expand the full list, including modifiers it recognises
but has not priced yet.

Shows its working. Every number comes with its evidence: comp count,
confidence, how many outliers were rejected, whether the bucket looks
manipulated, whether the price is falling. When there is not enough evidence it
says so instead of guessing.

Tracks what happened. Purchases are read from chat, positions are recorded with
the exits recommended at buy time, and your realised profit and loss is
computed from public sale records. The ledger is a local file on your machine.

Sell side. Lowest bin depth, so you see the floor and the next one up, because
the next lowest is your real competition. Undercut alerts and reprice
suggestions.


HONEST LIMITS

Estimates are estimates. Markets move, thin buckets are noisy, and manipulated
listings exist. The confidence number is there so you can discount accordingly.

Everything is computed from public auction records. What leaves your machine is
the item you hover, so it can be priced, and your Minecraft name and UUID once
you link an account. That is the whole list: your purse and bank are never
read, nothing enumerates your inventory, and there is no bulk upload. Full
detail at midasflip.com/privacy.

A free account at midasflip.com is required, because that is how API keys are
issued and abusers are banned. We never ask for your Minecraft password.

Some features are marked Gold. During early access they are free for everyone,
labelled as such in the mod, and become paid in September.


SETUP

1. Install Fabric Loader and Fabric API for Minecraft 26.1.2
2. Put the jar in your mods folder
3. Launch, press J, and follow the connect screen. It shows a six character
   code that you enter at midasflip.com/dashboard


SOURCE

GPL-3.0. The whole client is at github.com/noobsistaken/MidasFlip-Client, built
from a public tag you can build yourself. If you find something wrong, file it
publicly on the repository.
```

---

## File upload

| Field | Value |
|---|---|
| Display name | `MidasFlip 0.1.0` |
| Release type | **Beta** |
| Game version | `26.1.2` |
| Loader | `Fabric` |
| Dependency | `Fabric API` — **Required** |

Changelog: reuse the one in `modrinth-listing.md` verbatim.

---

## The approval-timing problem, and the answer

**You do not need either store to launch.**

There are currently **zero GitHub releases** on the repo, and the website links
to the repository rather than a download. That, not store approval, is what
would actually stop someone installing the mod on day one.

So the launch channel is **GitHub Releases**:

- It is instant and entirely under your control
- It is a completely normal way to ship a Fabric mod
- The jar is already reproducible from a public tag
- The stores become an *additional* channel as each one approves

Order of operations:

1. **Now:** submit CurseForge (slowest queue), then Modrinth
2. **Now:** cut the GitHub release, point the website's download at it
3. **Launch day:** ship from GitHub regardless of store status
4. **As each approves:** add the badge and link to the site

Say in the launch post that store listings are pending. "Available now on
GitHub, coming to Modrinth and CurseForge" reads as organised. Silence followed
by a broken download link reads as unfinished.

**One thing to get right:** both stores must serve the *same* jar you release
on GitHub, with the same SHA-256. If a reviewer downloads from one channel and
a user from another and the bytes differ, that is the kind of thing that gets a
project flagged. Verify with `shasum -a 256` before every upload.
