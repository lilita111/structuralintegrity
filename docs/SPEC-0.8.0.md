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
   Closed twice. 0.7.9 turned it into `sideJointNeverArrivesSpent`, and 0.7.10
   removed the fraction that made it a question at all: with nothing rounding
   away, a host holding one point hands over one point and no key decides it.
   Both the key and `sideInheritanceCap` stay declared for downgrade safety and
   neither is read. Left visible because a rewrite that reinstates any side
   fraction inherits this question along with it.

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

## 9. Side inheritance, and why 0.7.10 took it out

This section has been a proposal, then a record, and is now a record of a removal.
It is kept whole rather than deleted because the four rules it went through are the
same idea attempted four times, and the reason the last attempt is *no* rule at all
is only legible against the three that came before it. The 0.8.0 rewrite inherits
the ending, not the sequence.

### What the four rules were

Placing a block against a vertical face - hanging a shelf off a wall rather than
setting it on the floor - has always been treated as the weaker joint, and every
version up to 0.7.9 expressed that by giving the newcomer a fraction of something.
`sideInheritanceFactor`, half by default:

```
pre-0.7.8:  min( floor(guest natural * factor), host stored )
0.7.8:      min( guest natural, floor(host natural * factor), host stored )
0.7.9:      min( guest natural, floor(host stored  * factor) )
0.7.10:     min( guest natural, host stored )
```

Each step fixed a real defect in the one before it. Halving the *guest* meant the
wall it hung on made no difference, so a shelf bolted to deepslate scored the same
as one bolted to planks - 0.7.8 moved the fraction onto the host. Reading the host's
*rating* meant a wall one point from failing still handed out half of a full stone
rating, a number derived from the material where it should have come from the state,
which is the same shape as both 0.7.7 defects - 0.7.9 re-pointed it at the stored
value. 0.7.10 keeps that correction and drops the fraction itself.

### Why there is no fraction

The sideways joint is not free and never was. `chain()` bills a horizontal link at
`sidewaysLoadMultiplier` - double, by default - charged to the sturdier of the two
materials, and that charge runs on the very placement the fraction was also
punishing. Halving the newcomer on arrival was a **second penalty for one event,
taken out of a different account**: the chain debits the structure, the fraction
debited the newcomer, and nothing reconciled them. The missing half was charged to
nobody and accounted for nowhere.

What survives is the chain's charge, which is where the cost belongs and where it
was the whole time. What a face is worth stopped depending on which way it points,
so `faceCap` is one `min` over two numbers - the guest's own rating and what the
host still holds - and needs neither the level, nor the neighbour's position, nor
the direction. Both ceilings remain real and neither is negotiable: a host cannot
hand over strength it does not have, and no joint however good makes a block sounder
than the stuff it is made of.

### What this costs, said plainly

**The ledge run goes flat.** 0.7.9's headline consequence was that an iron run off
an iron wall read 40, 20, 10 - a cantilever that thinned to nothing on its own, with
no distance rule, no length limit and no config key. That compounding is gone; the
run now reads at full natural at every step. This is the one place where 0.7.10 is
a straight loss of behaviour rather than a simplification, and it is accepted
deliberately: three iron blocks off an iron wall are three ordinary placements, and
none of them is weaker than iron.

The ledge still ends, for the reason the rest of the mod already uses. Every
placement runs the load chain back down through the run into the wall and into the
foundation, and a sideways link there bills double. The wall wears, the foundation
wears, and a long enough cantilever brings itself down **from the root**, which is
where a real one fails. A cantilever that failed at its tip while its anchor sat
untouched was the wrong picture anyway.

**Build order stops mattering for the joint.** 0.7.9 made the same shelf on the same
wall worth less hung after the wall had taken load than hung first, which was a
direct consequence of taking a fraction of a moving number. Reading that number
without cutting it keeps the dependence but removes the multiplication, so a worn
wall still hands over less than a sound one - it just hands over all of what it has.

**Two config keys stop being read.** `sideInheritanceFactor` and
`sideJointNeverArrivesSpent` are both dead as of 0.7.10, and both stay **declared**.
A build that stops declaring a key does not leave it alone in an existing TOML, it
drops it, and the value silently reverts on the next downgrade - the same reason
`materialBoundaryStops` and `strongerMaterialBraces` are still declared and unread.
`sideInheritanceCap` is likewise kept as a private routine with no callers, so
restoring the old behaviour is re-pointing one call rather than reconstructing an
argument from a changelog.

The question §7 (5) was raised for goes with them. `sideInheritanceCap` floored
its result at `failAt + 1` because half of a host holding one point rounds away to
nothing; with no fraction there is no rounding, one point is handed over as one
point, and the guest arrives cracked but standing without a key deciding it. A host
holding *nothing* still grants nothing - `min(guest, 0)` is zero the same way the
skipped floor was zero.

### What 0.7.10 deliberately does not do

`faceCap` is shared with `recompute`, so a repair pass re-reads a host that may have
worn since the guest was placed, and `fresh` can come back lower than what the guest
already holds. It never lands - `recompute` takes `max(before, fresh)` and only ever
repairs upward - so a worn host repairs its guest *less* than a sound one would and
never demotes it.

Removing the fraction could not have changed this, because it changes how big
`fresh` is and not whether a smaller `fresh` is allowed to take effect. The contract
outlived the rule it was written against, which is the argument for having pinned it
in its own test. Lowering an already-placed block as its host degrades remains a
real and arguably desirable behaviour - it is what "structures come apart as they
wear" means taken to its end - but it stops the joint being settled at placement
time, which is a change in kind rather than in number. That is 0.8.0's call.

### The omen

Four attempts at one rule, three of them shipped as point releases inside a day, and
the fourth is a deletion. The rule was never the hard part - the final form is one
line and every intermediate form was one line. What made each attempt wrong is
recorded in section 11, which this sequence is the evidence for.

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
The six §9 tests below already exist and pass as of 0.7.10; they are listed so the
rewrite knows what it must not break. Four of them were written to pin the fraction
and now pin its absence, so each one carries the number the old rule would have
produced in its own failure message — a rewrite that quietly reinstates a side
fraction fails with the two answers printed side by side rather than with a bare
mismatch.

- ✅ `sideJointTakesWhatAWornHostHasLeftWhole` — a stone guest on an iron host worn
  to 10 inherits the whole 10, where 0.7.9 halved it to 5. The fixture programs the
  wear rather than reproducing it, and asserts the guest arrives holding exactly what
  the host held.
- ✅ `sideJointToAWeakHostGivesWhatTheHostHolds` — an iron guest on a stone wall
  gets the wall's whole 32, not the 16 that 0.7.8 and 0.7.9 both gave. The host still
  binds; only the second cut is gone.
- ✅ `sideJointDoesNotPenaliseAWeakGuestOnAStrongHost` — the case no rule ever
  changed, kept as the guard that the removal did not disturb it.
- ✅ `aLedgeRunNoLongerThinsOutOnItsOwn` — an iron run off an iron wall now reads
  flat where 0.7.9 read 40, 20, 10, and asserts the flatness directly. This is the
  behaviour 0.7.10 gives up, so it is asserted rather than merely no longer tested.
- ✅ `sideJointAgainstANearlySpentHost` — a host holding one point hands it over
  whole, and a spent host still grants nothing, both now reached without
  `sideJointNeverArrivesSpent` participating.
- ✅ `recomputeNeverLowersAGuestAsItsHostWears` — pins the monotonic-repair contract
  §9 describes, unchanged by the removal, so 0.8.0 changes it on purpose or not at
  all.
- Still owed by 0.8.0: a `place` followed by its matching break cancels exactly on a
  worn host, not only on a fresh one.

## 11. Architecture: separate fetching from deciding

Raised, not decided. This section exists because the 0.7.10 sequence was read as an
omen rather than as a bug — "if that is >1 then in 0.8.0 we take it as a sign of a
lack of robustness" — and because the target named for 0.8.0 was byte-size and
speed rather than behaviour.

### The measurement that prompted it

Changing which number the side rule keys off should have been one line. It was six
lines written and three deleted across five sites in three methods, four of them
plumbing. The reason is not that the rule is complicated:

> `faceCap` took a level and a position and fetched the host's stored value for
> itself, while both of its callers were separately already holding that value and
> clamping with it afterwards. The host was represented **twice, in two forms, at
> two call-stack levels**, so which representation was authoritative was an emergent
> property of where in the call stack you were standing rather than a stated fact.

Three defects of that exact shape shipped in three consecutive releases: two in
0.7.7, one in 0.7.9.

### The proposal

One layer turns a position into a small immutable record — natural, stored,
structural, anchor — and is the only code that touches `ServerLevel` or `BlockPos`.
Every rule function above it takes numbers and returns numbers.

Consequences, in the order they matter:

1. **"Which number is keyed off" becomes genuinely one line**, because there is only
   one representation of a block and it is named in one place.
2. **Rule functions become testable without a world.** `SIGameTests.class` is
   **71,775 bytes, 21.2% of all shipped class data**, and it ships to players. Rules
   that take numbers are unit-testable, which shrinks that file rather than merely
   relocating it — though relocating it to its own source set is a free 21% cut
   available today, independent of any rewrite.
3. **The fetch layer is the natural home for both caches.** `naturalOf` is a pure
   function of `BlockState` — data map, then pattern lookup, then
   `getCollisionShape`, then `getDestroySpeed`, then a square root — recomputed at
   18+ call sites and never memoized. `isStructural` fetches a state and discards it,
   so every neighbour scan double-fetches.
4. **Rule functions lose their `level` parameters**, which is bytecode removed rather
   than moved.

### Byte-size, measured

39 classes, 338,025 bytes of class data.

| Class | Bytes | Share |
|---|---:|---:|
| `SIGameTests` | 71,775 | 21.2% |
| `SIConfig` | 45,609 | 13.5% |
| `Integrity` | 29,498 | 8.7% |

`SIConfig` is 51 keys and 54 accessors, and its config comment prose *is*
constant-pool strings — that text has a byte cost where javadoc has none. Javadoc
costs zero jar bytes, so the 41% source comment ratio is not a target and should not
be treated as one. The 81 `SIConfig.` reads in production code are a speed item
rather than a size one; several sit inside loops.

### Open, and owed to oversight before any of it starts

The user's framing was: *"for 0.8.0 we first list the classes and their functions;
sort by most essential to least essential / see what can be merged into more
generalised methods by least essential first, checking for redundancies."* That
inventory has not been produced. This section is the argument for a shape, not
permission to adopt it, and no rewrite should begin before the inventory exists and
has been ruled on.

---

## Revision log

- **2026-09-02** — §9 rewritten again for 0.7.10-gamma, which removes the side
  fraction outright on "the logic was fine as it was, and the change to make side
  blocks lower integrity was an unnecessary punishment to the structure". The
  section keeps all four historical rules because the argument for having none
  only reads against them. §7 (5) closed a second time — the question needed a
  fraction to exist. §10's bullets renamed and re-aimed: four tests written to pin
  the fraction now pin its absence and print the old answer on failure. The ledge
  run going flat is recorded as an accepted loss, not as a simplification. §11
  added, raised not decided, from "0.8.0+ should be improvements in the
  architecture of the program" and the line-count omen that prompted it.
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
