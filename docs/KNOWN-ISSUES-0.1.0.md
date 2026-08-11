# Known issues in 0.1.0 — fix list for 0.1.1

Found during live testing on 2026-08-10/11, after the jar was frozen and
published. Nothing here blocks launch; each entry says why.

---

## 1. loreRecombed misses recombobulation → sell overlay UNDERPRICES (HIGH)

**Corrected 2026-08-11.** An earlier version of this entry blamed the tooltip
and had the direction backwards. The tooltip is RIGHT; the sell overlay is
wrong, and the money risk is underpricing your own item, not overpaying for
someone else's.

**Symptom.** On a 5✪ recombed Juju Shortbow in Create BIN Auction, the sell
overlay says "list at 15.5M · starred bucket · 150 comps" while the tooltip on
the same item says "5✪ recombed · list at 35.0M · 146 comps". Following the
overlay would list a 35M bow at 15.5M.

**What the access log proves.** Two requests, same item, same second:

    /value/v1|JUJU_SHORTBOW|s5|r1                                    -> tooltip
    /price/by-name/Hasty+Juju+Shortbow+✪✪✪✪✪?stars=5&recomb=false    -> overlay

`/value/<comp_key>` is only reachable from `ItemTooltip.ledgerKeyFor`, which
only runs when NBT is stripped — so Hypixel strips NBT even in the auction
CREATE menu, and the tooltip correctly fell back to the owner's own ledger
key, which carries `r1`. The overlay derived `recomb=false` from lore for the
same stack. The item is genuinely recombed, so `SellOverlay.loreRecombed`
returned a false negative.

**Do NOT "fix" this by guarding the ledger key on loreRecombed.** That was
attempted and reverted the same night. Because loreRecombed is the unreliable
half, using it as a guard rejects a CORRECT ledger key and drops the tooltip
from 35.0M to the coarse 15.5M — inflicting the bug on the one surface that
was still accurate. The dead `recombMatch` helper in ItemTooltip carries the
same warning.

**Fix.** Work out why loreRecombed returns false here first. Its rule is
"decoration glyphs before the tier word on the bottom-most rarity line", and
the item renders as `⊙ LEGENDARY DUNGEON BOW ✦` — a glyph IS present, so
either the bottom-most non-empty lore line is not the one being matched, or
dungeon items lay this line out differently from the gear it was written
against. Needs in-game inspection of the raw lore lines, not more reasoning
from screenshots.

**FIXED in 0.1.1** (2026-08-11), two changes:

1. `SellOverlay.findPosition` now strips the reforge word before matching the
   ledger row, so "Hasty Juju Shortbow" finds its "Juju Shortbow" position and
   uses the exact comp key the finder recorded with full NBT. Exact match is
   tried first, so "Strong Dragon Helmet" still matches itself.
2. `loreRecombed` scans upward for the rarity line instead of giving up when
   the bottom lore line is not one. Every auction view appends seller, price
   and "Click to inspect!" below it, so recombobulation had been undetectable
   in ANY menu — for items you own and items you do not.

Verified live: panel and tooltip now agree at 35.0M / 39.3M / 45.0M, the
bucket reads "recomb bucket", and the "you paid 23.7M" cost basis is back
(it came from the same ledger match).

Owner's note, and correct: for items you do NOT own this matters much less
anyway, because the flip finder values from the full auction feed server-side.
The tooltip is a browsing convenience; the board was never affected.

Nine tests added. Neither path had any coverage before, which is how both
reached a public build.

## 2. A 404 hides the tooltip block for five minutes (MEDIUM)

**Symptom.** The MidasFlip block vanishes entirely from a tooltip and does not
come back for several minutes, with no indication why.

**Cause.** `MidasflipApi.get` caches a 404 as `JsonNull` for up to 5 minutes
(deliberately — "a definitive no-data" so panes need not spin forever), and
`ItemTooltip` returns early on anything that is not a JSON object. The two
together mean one transient miss blanks the block for the full TTL.

This is what made issue 3 so confusing to diagnose: fixing the server did not
appear to fix anything until the client cache expired or the game restarted.

**Fix.** Distinguish "no data for this item" from "not loaded yet" in the
render path, and show a single quiet line for the former rather than nothing
at all. Silence is indistinguishable from a broken feature.

---

## 3. Reforged items 404'd in auction menus — FIXED SERVER-SIDE 2026-08-11

**Symptom.** No MidasFlip block on any reforged item viewed in an auction
menu. Unreforged items of the same type worked.

**Cause.** Hypixel strips ExtraAttributes in its menus, so the tooltip falls
back to `/price/by-name/<display name>` — which carries the reforge ("Heroic
Aspect of the Void"). No such name exists in the item table, so it 404'd, and
issue 2 then hid the block for five minutes.

`SellOverlay.stripReforge` already exists for precisely this, with the correct
"only after an exact miss" semantics, and both `ItemTooltip:384` and
`PurchaseOverlay:233` use it. The tooltip's by-name path did not.

**Fixed on the server** so no new jar was needed: `/price/by-name` now retries
once with a leading reforge word removed, AFTER an exact match fails — so
items whose real name starts with a reforge word (Strong Dragon Helmet) still
resolve to themselves.

**Trap worth remembering.** The first version of that fix split on a space and
looked correct against hand-typed `%20` URLs, but did nothing in production:
Java's `URLEncoder.encode` is form encoding and writes a space as `+`, and the
client uses it on a path segment. Every real request arrived as
`Heroic+Aspect+of+the+Void`. The access log settled it; the assumption did not.

---

## 4. SHIFT appears to do nothing — NOT A BUG

The per-modifier breakdown renders by DEFAULT, up to `MAX_CONTRIB_LINES = 4`.
SHIFT only expands past that cap or reveals recognised-but-unpriced atoms, so
on an item with four or fewer priced modifiers it correctly changes nothing.
Additionally, in an auction menu the mod reads lore only and cannot see gems
or hot potato books, which further reduces the row count — it says so on the
tooltip ("menu view · enchants counted, gems/HPB not visible").

The listing copy was corrected to describe this accurately rather than
implying SHIFT is required.
