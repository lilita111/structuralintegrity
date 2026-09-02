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
5. **Does the `failAt + 1` floor survive the side-joint change of §9?** Reading the
   half off the host's stored value means a host worn to 1 grants 0, and only the
   floor makes the attachment live at all. Either a nearly-spent wall can still carry
   a one-point shelf, or the floor is dropped for the side case and attaching to a
   spent host fails outright.

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

## 9. Side inheritance measures what the host has left

0.7.8 changed which block the side cap is read off. Before it, placing a block
against a vertical face gave the new block half of *its own* natural rating, so a
shelf was worth the same whether it was bolted to deepslate or to planks. After it,
the cap comes off the block being attached TO, through one `faceCap` helper that
`place` and `recompute` both ask so the rule cannot mean one thing on placement and
another on repair.

0.8.0 changes what is measured on that host: not its natural rating, but its stored
integrity — what it actually has left right now.

Today the assigned value is

```
min( guest natural, floor(host natural * sideInheritanceFactor), host stored )
```

and under 0.8.0 it becomes

```
min( guest natural, floor(host stored * sideInheritanceFactor) )
```

The host's stored value stops being a separate ceiling applied afterwards and
becomes the thing the half is taken of. On a fresh, unloaded host the two agree, so
nothing about a first build changes. They diverge the moment the host has taken any
load at all: a stone wall worn from 32 down to 10 hands a guest 16 today and 5
under this spec.

### Why this is the right measure

Natural integrity says what a material is capable of; stored integrity says what
this particular block still has to give. A side attachment is carried entirely by
the block it hangs off, so what it can inherit is bounded by what that block
actually has, not by what a pristine example of the same stone would have had. The
current rule lets a wall that is one point from failing still hand out half of a
full stone rating, which is the same category of mistake as the 0.7.7 defects: a
number derived from the material when it should have been read from the state.

### Two consequences worth having on the record

**Attachments decay outward along a run.** Because natural integrity is a constant
per material, the current rule does not compound — every block out along a uniform
ledge is capped at the same half of the same rating. Reading stored value instead
makes it geometric: the first block off a 32-stored wall takes 16, the next takes
8, then 4, then 2. A long cantilever thins out to nothing on its own, without a
distance rule, a length limit or a config key. This is almost certainly desirable
and is the strongest argument for the change, but it is a real behaviour shift and
should be seen in a gametest before it is believed.

**Build order starts to matter.** The same shelf on the same wall is worth less if
you hang it after the wall has taken load than if you hang it first. That follows
directly from measuring state rather than material and is consistent with the rest
of the mod, but it is the kind of thing a player notices and calls a bug, so it
belongs in the changelog rather than only in the code.

### The one sub-question

`sideInheritanceCap` floors its result at `failAt + 1`, so a side block never
arrives already dead. Against a host worn to 1, half is 0 and the floor is the only
thing that grants anything at all — a nearly-spent wall would still hand out a live,
if minimal, attachment. Either that floor stays, and a wall about to fail can still
carry a shelf worth one point, or the floor is dropped for the side case and
attaching to a spent host simply fails the placement. This is a design call, not a
mechanical consequence, and is listed with the §7 questions rather than answered
here.

There is a second-order version of the same question for `recompute`. Since
`faceCap` is shared, a repair pass would re-read a host value that moves, so an
already-placed guest can be lowered as its host wears. That reads as intended given
the direction of 0.8.0 — structures come apart as they degrade — but it means the
side joint stops being settled at placement time, which is a change in kind and not
only in number.

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
- A guest placed against a host worn to half its natural rating inherits half of the
  worn value, not half of the rating — the assertion that separates §9 from 0.7.8.
- A four-block ledge run off a full-strength wall halves at every step outward, and
  the printed run reads 16, 8, 4, 2 rather than 16, 16, 16, 16.
- The same guest placed on a fresh host and on a worn host gets different values, so
  build order is shown to matter rather than assumed to.
- `recompute` on an already-placed guest re-reads its host and lowers it as the host
  wears, and a `place` followed by its matching break still cancels exactly.

---

## Revision log

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
