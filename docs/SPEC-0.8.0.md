# 0.8.0 — disconnect by default

Status: **draft, not implemented.** Written before code, revised under review, and
expected to ship as 1.0.0 once it has been through oversight. Nothing in 0.8.0
starts until this document is agreed.

Baseline: 0.7.7-gamma (`4f73d91`).

---

## 1. What is wrong today

Build a pillar and 0.7 behaves well. Build anything more complex than a pillar and
it shatters. A wall, a floor, an arch — the pieces vanish one at a time and leave a
scatter of dropped items where a structure used to be, instead of a structure that
comes away and falls over.

The reason is in the plumbing rather than in the physics. When `Integrity.chain`
walks a load down and a block runs out of stored value, it calls `snap`, which adds
that position to a `failed` list. Two places route that list, and both do the same
thing with it:

- `SIEvents.java:70` — the place handler
- `SIJumpShock.java:104` — the jump shock

```java
SIFall.queueDestroy(level, failed);
```

Two further sites destroy without going through `failed` at all: the recompute path
at `SIEvents.java:228`, and the shockwave's own wear loop at `SIFall.java:393`.

And there is a third reader worth calling out on its own, because it is the more
serious problem. `SIEvents.java:335–358` builds the player-facing report line, and
to say whether each failed block was held or destroyed it calls
`Integrity.holdsWhenSpent` and counts. That is the outcome being **re-derived** by a
routine that did not make the decision — the exact defect shape 0.7.7 fixed twice,
sitting in the logging. The comment above it even records a previous instance of the
same drift: the line promised a destruction `SIFall` never performed, twenty-four
times in one session. Under 0.8.0 that report must read the verdict it is handed,
not work it out again.

So every failure is a destruction, everywhere, unconditionally. In a pillar that is
fine, because a pillar is one block wide and losing its base is indistinguishable
from the pillar detaching. In a wall, the chain reaches failure at several
positions in a single pass, each one is destroyed independently, and the wall is
eaten rather than toppled.

The mod already has the better outcome built and working. `SIFall.queueFall` seeds
the fall pass, which runs `Integrity.collect` from the seed, gathers the connected
mass, assembles it into a sable `ServerSubLevel` and hands it to the physics. That
is what happens today when a player mines a support out from under a structure, and
it is what should happen when integrity runs out too. 0.8.0 does not build a new
mechanism. It changes which of two existing outcomes a failure routes to.

## 2. The rule

At the moment a block arrives at or below `failAt` during a charge, compare the
natural integrity of the two blocks involved in that step:

- **FROM** is the block handing the load over — `prevNatural` in `chain`, the
  previous block on the walk, or the placed/removed block itself on the first step.
- **TO** is the block receiving it — `natural` of `cur`, the block that just ran out.

Then:

| comparison | outcome for TO |
|---|---|
| FROM **>** TO | **break** |
| FROM **=** TO | **disconnect** |
| FROM **<** TO | **disconnect** |

Only TO is ever affected. FROM is never touched by the verdict.

The comparison is on **natural** integrity — the material's rating — not on stored
value. Stored value is what ran out; natural is what the block is made of. Load
crossing from a stronger material into a weaker one is the case where the weaker
material should give way and shatter, and it is the only such case.

### Why equality disconnects

This is the single most consequential line in the spec, so it is worth stating
plainly: same-material is the majority of every real build. Stone wall on stone
foundation, plank floor on plank joists, a brick tower on brick. If `FROM = TO`
broke, then 0.8.0 would behave exactly like 0.7 for almost everything a player
actually builds, the change would be invisible in play, and the complaint that
started this — structures shattering instead of falling — would be untouched.

Disconnect on equality is what makes a uniform structure come away as one body.

### Why one disconnect is enough

A pass can drive several positions to failure at once. Under the current
destroy-everything routing that produces several independent destructions. Under
0.8.0 it does not need to produce several independent sub-levels, because the fall
pass already works from seeds: `queueFall` adds a position to a `LinkedHashSet`,
and `runFall` runs `collect` from the seeds to gather the connected component. Two
seeds inside one connected mass gather the same mass.

This must be **verified rather than assumed** during implementation — a gametest
that fails a wall at three positions in one pass and asserts exactly one sub-level
is assembled.

## 3. The structural change

The FROM/TO comparison has nowhere to live today. By the time the `failed` list
reaches a consumer, `prevNatural` is long out of scope — it is a local in `chain`'s
loop. That is why the outcome is currently decided at the consumer, and why every
consumer decides the same thing.

So the change is: **`chain` carries the verdict out with the position.**

`failed` stops being a `List<BlockPos>` and becomes a list of verdicts — position
plus outcome. `snap` stops asking config which *side* gives way and instead records
what happens to the one block that is affected. The two routers stop choosing and
route on the verdict they were handed; the report line at `SIEvents.java:335` stops
re-deriving the outcome and prints the verdict; and the two sites that destroy
without consulting `failed` at all — `SIEvents.java:228` and `SIFall.java:393` —
need deciding on explicitly rather than being left as the last places in the mod
that still destroy unconditionally.

One decision, made at the point where the information exists, carried to the edge.
This is the same shape as both 0.7.7 fixes: the bug in each case was a second
routine re-deriving something the walk already knew, and then drifting from it.

## 4. What is removed

Four things exist only to answer "what happens to a spent block", from the block's
own natural rating and a tag. 0.8.0 answers that question from the FROM/TO
comparison instead, so they go:

- `Integrity.SpentDisposition` (`HELD`, `ABOVE_THRESHOLD`, `TAGGED_BREAKS`, `FRAGILE`)
- `Integrity.dispositionWhenSpent`
- `Integrity.holdsWhenSpent`
- `SIConfig.holdSpentUpToNatural`
- `SIConfig.breakWeakerBlock` and `SIConfig.breakStrongerBlock`

Keeping any of them alongside the new rule would leave two definitions of the same
decision sitting next to each other, free to drift apart. That is precisely the
defect 0.7.7 fixed twice in one patch, and it is not worth re-introducing for
backwards compatibility with a behaviour the spec is deliberately replacing.

`SIFall.java` (around lines 266–293), `SIEvents.java` (around 343–349) and the
gametests at `SIGameTests.java:737`, `:798`, `:876` all read these and will need
rewriting rather than adjusting.

## 5. What is kept, and what it is demoted to

**`SITags.BREAKS_WHEN_SPENT` stays**, re-purposed as a pure exception list. A block
in this tag breaks regardless of the comparison. Glass, ice and leaves have no
sensible "detaches and topples as a body" reading, and an exception list is the
honest way to say so — one lever, with the exceptions in data rather than in code.

**`materialBoundaryStops` is already spent — 0.7.8 got there first.** This section
used to say the key would stay but lose its say in the outcome, on the grounds that
*where the walk stops* and *break-vs-disconnect* are two different questions
entangled through one flag. 0.7.8 settled it more simply: a material crossing no
longer stops the walk at all, in either direction, so there is nothing left for the
flag to gate. The key is still declared, so that upgrading does not drop it out of
an existing config file, and is no longer read. 0.8.0 inherits a walk that always
runs to ground or to a snap, which is a cleaner starting point for the
break-vs-disconnect rewrite than the one this section was written against.

**`prevDeficit` is gone, and the brace, the clump grant and the hold band's
transfer arithmetic stay untouched.** 0.7.8 removed `prevDeficit` because it was
lifetime wear that charging never discharged, so the same debt was re-billed on
every pass that crossed a material joint; that was 28 of 36 failures in a live
0.7.7 session. The survivors govern how much load moves and where it goes. None of
them gets a vote on what happens when it runs out.

The one exception is the side joint, which 0.8.0 does change — see §9. It changes
in the same direction as everything else here, though: what a face passes on, not
what happens when the receiving block runs out.

**The brace is no longer optional.** `strongerMaterialBraces` is likewise declared
and unread since 0.7.8. A crossing into sturdier material moves one point off the
weaker block onto the sturdier one — weaker ends at +0, sturdier at -2 — and the
walk carries on past it, every block below taking the ordinary -1. That is the rule
rather than one of two ways to score a rise, so 0.8.0 should not reintroduce a
switch for it.

**`snap`'s locals get renamed.** `weaker` and `stronger` assert a material
relationship the code never checks — `spent` is simply whichever block ran out of
stored value first, which is a function of accumulated wear. They become
`receiving` (TO) and `handing` (FROM), which is what they have always actually been.

## 6. Re-anchoring the consequences

`breakShockwave` and the splinter rule currently fire off the destruction path.
With most failures becoming disconnects, both must re-anchor on the **failure
event** rather than on destruction. Left alone, a structure that detaches would
produce no shockwave and no splintering, and the mod would go quiet at exactly the
moment it should be loudest.

`SIFall.protectFromSplinter`, called from `Integrity.java:546` when
`breakStrongerBlock` is off, loses its config trigger along with that key. Whether
the shield is still needed once only TO is ever affected is an open question —
see below.

## 7. Open questions for oversight

These are raised, not decided. Each one changes behaviour and none should be
guessed at.

1. **Does a disconnect splinter its neighbours?** Splinter wear follows destruction
   today. A detaching mass arguably should stress what it tears away from, but that
   is a design call, not a mechanical consequence.
2. **What happens when a disconnected sub-level immediately lands on solid ground?**
   If it reverts to blocks still holding their spent values, the next pass
   disconnects it again, and the structure oscillates. Either landing restores
   integrity, or reverted blocks need a grace state. This is the most likely source
   of a loop in the whole design.
3. **Does `BREAKS_WHEN_SPENT` override every disconnect, or only the equality case?**
   Glass under a stone lintel (FROM > TO) breaks either way. Glass under glass is
   the case that needs an answer.
4. **Is `protectFromSplinter` still needed?** It exists because the splinter rule
   would wear and break the stronger side even when config said only the weaker side
   gives way. With FROM never affected by the verdict, the shield may be redundant —
   or may still be doing real work via splinter wear specifically.
5. ~~**Does the `failAt + 1` floor survive the side-joint change of §9?**~~
   Answered in 0.7.9 and no longer open: it became `sideJointNeverArrivesSpent`,
   defaulting to true. Left in the list because the *default* is still a design
   call 0.8.0 may want to revisit once disconnect-by-default changes what a
   one-point block means.

## 8. Relationship to the two smaller specs

**Spec #2 (25% disconnect when TO lands on one point) survives and is not made
redundant by this.** It fires at `failAt + 1` — one point of integrity remaining,
before the block is spent — so it is an *early* disconnect, ahead of failure. 0.8.0
governs what happens *at* failure. They are different moments. #2 does get cheaper
to build once #1 exists, because the disconnect routing it needs will already be in
place.

**Spec #3 (normalise above four connections to the highest-integrity connection) is
independent of both** and can land before or after. It is a connection count above
the `down != null` return in `supportOf`, delegating to the existing
`strongestSupportOf`. Its one open sub-question — whether the block *above* may win
the strength vote, which would let load travel upward through a mass — is unrelated
to anything in this document.

## 9. Side inheritance is already spent - 0.7.9 got there first

This section was drafted as a 0.8.0 proposal and implemented the same day, so it is
kept as a record of what the rule now is rather than as work outstanding. The
0.8.0 rewrite inherits it.

0.7.8 had already moved the side cap onto the host block: placing against a
vertical face gives the guest a fraction - `sideInheritanceFactor`, half by default
- of what it was stuck TO rather than of its own rating, so a shelf bolted to
deepslate beats the same shelf bolted to planks. What it read on that host was the
host's *natural rating*, which let a wall one point from failing hand out half of a
full stone rating.

0.7.9 re-points the same rule at the host's *stored* value:

```
0.7.8:  min( guest natural, floor(host natural * factor), host stored )
0.7.9:  min( guest natural, floor(host stored  * factor) )
```

The host's remaining strength stops being a ceiling applied after the fraction and
becomes the thing the fraction is taken of, so `faceCap` returns the complete
ceiling and neither caller clamps what it hands back. The two forms agree exactly
on a fresh host and diverge as it wears - a stone wall worn 32 to 10 gives a guest
16 under the old form and 5 under this one.

**Ledges now decay outward, and that is the point.** Natural integrity is constant
per material, so the 0.7.8 form capped every block along a uniform run at the same
half of the same rating - an iron run off an iron wall read 40, 40, 40 indefinitely.
Stored value is not constant, so the run halves at every step: 40, 20, 10. A
cantilever thins to nothing on its own, with no distance rule, no length limit and
no config key - the general mechanism the old form needed a special case to fake.

**Build order starts to matter.** The same shelf on the same wall is worth less hung
after the wall has taken load than hung first. That follows directly from measuring
state rather than material, and belongs in the changelog because a player will read
it as a bug.

**The floor question went to config, not to a guess.** `sideInheritanceCap` floored
its result at `failAt + 1` so a side block never arrived already spent. Against a
host worn to one point, half rounds away to nothing and only that floor grants
anything at all - floored, a failing wall still carries a cracked one-point shelf;
unfloored, attaching to it fails the placement outright. Both are defensible, so it
is `sideJointNeverArrivesSpent`, defaulting true because that is what 0.7.8 did. A
host that is *already* spent is not a config question: there is no fraction of
nothing, the floor is skipped, and the guest gets zero either way.

**One thing 0.7.9 deliberately did not do, and 0.8.0 may want to.** Because
`faceCap` is shared with `recompute`, a repair pass now re-reads a host that may
have worn since the guest was placed, so `fresh` can come back lower than what the
guest already holds. It never lands - `recompute` takes `max(before, fresh)` and
only ever repairs upward - so a worn host repairs its guest *less* than a sound one
would and never demotes it. The draft of this section claimed the guest would be
lowered; that was wrong about the existing code, and `recomputeNeverLowersAGuestAsItsHostWears`
now pins the actual contract. Lowering an already-placed block as its host degrades
is a real and arguably desirable behaviour - it is what "structures come apart as
they wear" means taken to its end - but it stops the side joint being settled at
placement time, which is a change in kind rather than in number. That is 0.8.0's
call to make, and the test above is what will tell it the old contract was
deliberate rather than accidental.

## 10. Test plan

Every item below is a gametest asserting from the program's own printed output, not
a manual reproduction.

- A uniform stone wall driven to failure detaches as **one** sub-level, with zero
  blocks destroyed.
- A stone block over a plank block (FROM > TO) **breaks** the plank and detaches
  nothing.
- A plank block over a stone block (FROM < TO) **disconnects** the stone.
- Same-material at every ratio in the natural table disconnects, never breaks.
- A wall failing at three positions in one pass assembles exactly one sub-level.
- A block in `BREAKS_WHEN_SPENT` breaks under equality.
- A pillar still behaves exactly as it does in 0.7.7 — this is the regression guard
  that proves the change is confined to the complex case.
- The dry/real drift probe still agrees, as it does after the 0.7.7 brace rewrite.
The four §9 tests below already exist and pass as of 0.7.9; they are listed so the
rewrite knows what it must not break.

- ✅ `sideJointHalvesWhatTheHostHasLeftNotItsRating` — a guest on an iron host worn
  to 10 inherits 5, not the 10 the 0.7.8 rule gave. The assertion that separates the
  two forms, and the fixture programs the wear rather than reproducing it.
- ✅ `aLedgeRunHalvesAtEveryStepOutward` — an iron run off an iron wall reads
  40, 20, 10 where the old rule held it flat at 40.
- ✅ `sideJointAgainstANearlySpentHost` — both sides of
  `sideJointNeverArrivesSpent`, plus the spent host that grants nothing either way.
- ✅ `recomputeNeverLowersAGuestAsItsHostWears` — pins the monotonic-repair contract
  the paragraph above describes, so 0.8.0 changes it on purpose or not at all.
- Still owed by 0.8.0: a `place` followed by its matching break cancels exactly on a
  worn host, not only on a fresh one.

---

## Revision log

- **2026-09-02** — §9 shipped as 0.7.9-gamma, hours after being written as a
  proposal, on "actually build this to 0.7.9 immediately". Rewritten from a proposal
  into a record. The floor question became `sideJointNeverArrivesSpent` rather than
  a decision, so §7 (5) is closed. One correction to the draft: it claimed `recompute`
  would lower a guest as its host wears, which was wrong — `max(before, fresh)` makes
  repair monotonic, and that contract is now pinned by a test rather than assumed.
- **2026-09-02** — §9 added, from "you missed the tweak where placing blocks on the
  side gets half the integrity of the block placed on" plus "save this for 0.8.0".
  0.7.8 had already moved the side cap onto the host, but it reads the host's
  *natural rating*; this is the same rule re-pointed at the host's *stored* value.
  §5's promise that the sideways rule stays untouched was amended to match, and the
  floor question added to §7 as (5). Not implemented — 0.7.8 ships the natural-rating
  form.
- **2026-09-02** — first draft, from the 0.8.0 refinement: "structures will fall
  apart (when they are more complex than just pillars) rather than becoming
  disconnected / the TO/FROM logic is strict here regarding whether TO was > or <
  FROM". Grounded against 0.7.7-gamma (`4f73d91`).
