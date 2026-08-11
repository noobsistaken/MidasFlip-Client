# Known issues in 0.1.0 — fix list for 0.1.1

Found during live testing on 2026-08-10/11, after the jar was frozen and
published. Nothing here blocks launch; each entry says why.

---

## 1. Ledger key ignores recombobulation → overstated valuation (HIGH)

**Symptom.** In an auction menu, the tooltip prices a listing using the comp
key of a DIFFERENT item you own. Owner hit it on a Juju Shortbow: the sell
panel said list at 15.5M, the tooltip said 35.0M for the same bow on screen.

**Cause.** `ItemTooltip.ledgerKeyFor` matches a hovered stack to an open
ledger position on normalized NAME + STAR COUNT, then reuses that position's
exact comp key. It never compares recombobulation. Holding a 5✪ **recombed**
Juju and hovering a 5✪ **non-recombed** one satisfies both checks, so the
listing is priced from the recombed bucket — `v1|JUJU_SHORTBOW|s5|r1` instead
of the r0 bucket.

Confirmed from the access log: the tooltip requested
`/value/v1|JUJU_SHORTBOW|s5|r1` while the sell overlay requested
`/price/by-name/Hasty+Juju+Shortbow+✪✪✪✪✪?stars=5&recomb=false`. The overlay
was right; the item's lore reads LEGENDARY DUNGEON BOW, and a recombed Juju
reads MYTHIC.

**Why it is exactly the bug that was already fixed once.** The comment
directly above the star guard documents the identical failure for stars,
caught in review on 2026-08-05: "holding one 5-star and hovering a clean one
priced the clean item from the 5-star bucket". Stars got `starsMatch`. Recomb
is the same hole, one field over.

**Fix (needs a new jar — cannot be done server-side).** Add a recomb guard
beside `starsMatch`, using `SellOverlay.loreRecombed(stack)` — which is
already proven to work, since the sell overlay derived `recomb=false`
correctly for this same item. Refuse the ledger key when it disagrees, exactly
as the star guard does. Consider extending to any other comp-key segment
readable from lore.

**Why it does not block launch.** `ledgerKeyFor` only matches OPEN POSITIONS.
A fresh install has an empty ledger, so the path is unreachable for a new
user. It requires already owning a near-identical item. The error direction is
overstatement, so the risk is overpaying, and the sell panel — the surface you
list from — stayed correct.

---

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
