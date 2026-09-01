package com.apokalypse.structuralintegrity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every tunable the solver used to hard-code, in the order the mechanisms act.
 *
 * Server config: the solver runs server-side only, and these values shape world
 * state, so they load with the world and travel with it.
 *
 * Each getter falls back to the old constant while the spec is not loaded yet -
 * the datamap and the solver can be poked (gametests, early events) before the
 * server config file is read, and the answer then must be the same answer 0.5.3
 * gave, not an exception.
 */
public final class SIConfig {
    private SIConfig() {}

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // ---- what the system ignores ------------------------------------------

    private static final ModConfigSpec.ConfigValue<List<? extends String>> NON_STRUCTURAL_BLOCKS = B
            .comment("Blocks the integrity system ignores completely, as if they were air.",
                    "Air, fluids and plants are always ignored; this list adds to that.",
                    "Entries are block ids or #tags.")
            .defineListAllowEmpty("nonStructuralBlocks", List.of(),
                    () -> "", SIConfig::isBlockIdOrTag);

    private static final ModConfigSpec.BooleanValue SAME_TYPE_LEAVES_CONNECT = B
            .comment("Leaves never connect to a DIFFERENT leaf block - a tree cannot stay",
                    "anchored by hanging its canopy in a neighbouring species. true: leaves of",
                    "the same block still connect to each other, so one tree's canopy is a",
                    "single body on its trunk. false: no leaf connects to any leaf - every",
                    "leaf stands only on real blocks.")
            .define("sameTypeLeavesConnect", true);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> INTEGRITY_PATTERNS = B
            .comment("Wildcard integrity overrides, entries \"<id-pattern>=<integrity>\" where *",
                    "matches any run of characters in the full block id, e.g.",
                    "\"create:*_casing=40\" or \"mekanism:*_ore=34\". First matching entry wins.",
                    "A datamap row always beats a pattern; a pattern beats the hardness",
                    "derivation. Applies live on file save.")
            .defineListAllowEmpty("integrityPatterns", List.of(), () -> "",
                    SIConfig::isPatternEntry);

    // ---- anchors ----------------------------------------------------------

    private static final ModConfigSpec.ConfigValue<List<? extends String>> DEFAULT_ANCHOR_BLOCKS = B
            .comment("Blocks that count as ground (anchors) while untouched, even when their",
                    "datamap row says never_anchor. Untracked blocks are anchors by default",
                    "already; this list is for forcing it on loose material like sand.")
            .defineListAllowEmpty("defaultAnchorBlocks", List.of(), () -> "", SIConfig::isBlockId);

    // ---- the integrity chain ----------------------------------------------

    private static final ModConfigSpec.BooleanValue GRAVITY_FIRST_CHAIN = B
            .comment("true: the -1 chain follows gravity - DOWN first, then a random sideways",
                    "neighbour, then UP. false: the pre-0.5.2 rule - the strongest neighbour",
                    "carries the load, ties going DOWN first.")
            .define("gravityFirstChain", true);

    private static final ModConfigSpec.IntValue CLUMP_BRACING_THRESHOLD = B
            .comment("Same-type neighbours a block needs to count as clumped. A clumped",
                    "block enters tracking with its own natural integrity added on top of",
                    "its starting value - once, when its wbireg row is created; the chain's",
                    "-1 then wears the bonus away like anything else. 0 disables this.")
            .defineInRange("clumpBracingThreshold", 5, 0, 6);

    private static final ModConfigSpec.BooleanValue CLUMP_ADDS_ON_TOP = B
            .comment("true: the clump grant is the block's natural integrity ADDED on top of",
                    "its starting value (dirt: 2+2 -> 4). false: the grant sets the block to",
                    "its max natural integrity and no higher.")
            .define("clumpAddsOnTop", true);

    private static final ModConfigSpec.BooleanValue BREAK_WEAKER_BLOCK = B
            .comment("A snap has two sides: the spent block - the one whose wbireg value ran",
                    "out - and the block it was holding up. The spent one is the weaker side.",
                    "true: it breaks. This is the plain rule: a block breaks when its wbireg",
                    "value reaches failAt.")
            .define("breakWeakerBlock", true);

    private static final ModConfigSpec.BooleanValue BREAK_STRONGER_BLOCK = B
            .comment("true: the held-up side of the snap breaks too. Both true breaks both",
                    "sides; both false breaks neither - the chain stays spent but standing.")
            .define("breakStrongerBlock", false);

    private static final ModConfigSpec.BooleanValue BREAK_SHOCKWAVE = B
            .comment("true: a block breaking at failAt sends a shockwave through the whole",
                    "connected structure - every tracked block in it takes -1, any type,",
                    "stopping at ground and at the maxRegion cap. Blocks whose natural",
                    "integrity is 1 conduct the wave but are not worn by it. A block the",
                    "wave itself breaks does NOT emit a wave of its own - one wave per",
                    "original break, no chain reaction. A break that detaches part of the",
                    "structure into a sub-level emits no wave either - the falling piece",
                    "carried the energy away; only a break that detaches nothing shocks.",
                    "false: the pre-0.5.x splinter rule - a breaking block wears same-type",
                    "neighbours only, cascading.")
            .define("breakShockwave", true);

    private static final ModConfigSpec.IntValue HOLD_SPENT_UP_TO_NATURAL = B
            .comment("What becomes of a block that integrity loss drives down to failAt,",
                    "decided per material instead of world-wide. Replaces the old",
                    "breakOnIntegrityLoss switch in 0.7.1.",
                    "A block whose natural integrity - its naturalintegrityreg entry, looked",
                    "up by block id, not its current worn value - is at or below this number",
                    "is HELD. It stays exactly where it is, pinned at failAt, and stops",
                    "carrying load. It becomes a hole in the support graph, so whatever it",
                    "was holding is disconnected and detaches into a sub-level around it. The",
                    "structure still comes down; the block that gave way is simply still",
                    "there, spent, instead of being consumed. Nothing splinters and",
                    "breakShockwave never fires for these, because no break happened to",
                    "propagate from.",
                    "Anything ABOVE this number is destroyed outright, which is what every",
                    "block did through 0.6.5. The same collapse follows, but the block is",
                    "lost and its break wears same-type neighbours.",
                    "Mind the scale before changing this - natural integrity is not a 0-10",
                    "rating. The shipped data map spans 0 to 160 - stone is 32, cobblestone 20 -",
                    "and anything with no row derives from hardness, up to a ceiling of 1024.",
                    "The default 2 therefore holds soil and nothing else: dirt is 1, sand and",
                    "gravel are 2, and the next material up is grass_block at 4. Soil slumps",
                    "into rubble and everything structural still shatters.",
                    "Two values are the old switch. 0 holds nothing at all, since anything",
                    "structural is at least natural 1, and is the old true. 1024 holds",
                    "everything including unbreakable blocks, and is the old false.",
                    "Two vetoes sit above this number since 0.7.2 and both win when they",
                    "fire, because this key answers how much load a material carries and",
                    "that is not the same question as whether it slumps or shatters. Soil",
                    "answers both the same way, which is what made one number look like",
                    "enough. Leaves do not: natural 1, below any useful threshold, so a",
                    "canopy left by a felled trunk hung in the air instead of dropping.",
                    "First veto: a block in #structuralintegrity:breaks_when_spent always",
                    "breaks - leaves, glass, carpets, cake, ladders. Datapack-overridable.",
                    "Second veto: breakFragileWhenSpent, below, for anything with no",
                    "collision shape - which defaultFragileIntegrity puts at natural 1 and",
                    "so inside any band of 1 or more.",
                    "So 1024 no longer holds literally everything: it holds everything not",
                    "vetoed. Clear the tag and turn that key off to get the old behaviour.")
            .defineInRange("holdSpentUpToNatural", Integrity.HOLD_SPENT_UP_TO_NATURAL, 0, 1024);

    private static final ModConfigSpec.BooleanValue BREAK_FRAGILE_WHEN_SPENT = B
            .comment("true: a spent block with no collision shape breaks whatever",
                    "holdSpentUpToNatural says. These are torches, rails, levers, buttons,",
                    "flowers, redstone dust - anything you walk through. None of them carry",
                    "a data map row, so naturalOf falls back to defaultFragileIntegrity,",
                    "which is 1 and therefore inside any hold band of 1 or more. Held, they",
                    "hang in the air where the wall they were on used to be.",
                    "This is the same veto #structuralintegrity:breaks_when_spent applies,",
                    "written as a rule instead of a list so it also covers modded decoration",
                    "nobody has tagged.",
                    "false: they obey holdSpentUpToNatural like everything else. Raising",
                    "defaultFragileIntegrity is the other way to do it, but that also changes",
                    "how much load they carry, which is the conflation this key exists to",
                    "avoid.")
            .define("breakFragileWhenSpent", true);

    private static final ModConfigSpec.BooleanValue REVERSE_INTEGRITY_ON_BREAK = B
            .comment("true: destroying a block runs the chain in reverse - the same walk from",
                    "its support toward ground, +1 per block instead of -1, capped at each",
                    "block's own natural integrity. Values above natural (clump grants) are",
                    "left alone, never cut down.")
            .define("reverseIntegrityOnBreak", true);

    private static final ModConfigSpec.IntValue EXPLOSION_SHOCKWAVE_DELTA = B
            .comment("How much integrity an explosion gives back along each removed block's",
                    "chain - the shockwave. A mined block always relaxes its chain by +1;",
                    "TNT hits harder. Still capped at each block's own natural integrity.",
                    "0 disables it.")
            .defineInRange("explosionShockwaveDelta", 8, 0, 64);

    // ---- force on sub-levels ----------------------------------------------

    private static final ModConfigSpec.BooleanValue FORCE_SCALES_WITH_MASS = B
            .comment("true: the magnitudes below are read as a velocity change in m/s and are",
                    "multiplied by the body's mass before being applied, so one number means",
                    "the same shove for a four-block chunk and a four-hundred-block wall.",
                    "false: they are raw impulses in N s, and a heavy body will barely notice",
                    "a number that launches a light one.")
            .define("forceScalesWithMass", true);

    private static final ModConfigSpec.DoubleValue EXPLOSION_FORCE = B
            .comment("How hard an explosion pushes a sub-level that is standing in it, at the",
                    "blast centre. Falls off linearly to nothing at explosionForceRadius.",
                    "0 disables it.")
            .defineInRange("explosionForce", 8.0, 0.0, 1024.0);

    private static final ModConfigSpec.DoubleValue EXPLOSION_FORCE_RADIUS = B
            .comment("How far from the blast centre explosionForce still reaches, as a multiple",
                    "of the explosion's own radius. TNT is radius 4, so the default reaches",
                    "12 blocks.")
            .defineInRange("explosionForceRadius", 3.0, 0.0, 32.0);

    private static final ModConfigSpec.DoubleValue COLLAPSE_FORCE = B
            .comment("How hard a piece is pushed the moment integrity loss detaches it, away",
                    "from the block whose support gave way and flattened to horizontal - gravity",
                    "already supplies the downward part. Smaller than explosionForce: this is a",
                    "topple, not a launch. 0 disables it.")
            .defineInRange("collapseForce", 1.5, 0.0, 1024.0);

    private static final ModConfigSpec.DoubleValue COLLAPSE_TORQUE = B
            .comment("How fast a piece is set spinning the moment integrity loss detaches it,",
                    "about the axis that makes its top lead in the direction it is already",
                    "toppling.",
                    "Radians per second, applied directly to the body. It is NOT a torque",
                    "impulse despite the name: a sub-level microseconds old has no mass",
                    "properties yet, so an impulse is divided by an inverse mass rapier still",
                    "has cached as zero and arrives as exactly nothing - which is what every",
                    "collapse in this mod did before 0.6.4. Setting the velocity is how sable",
                    "itself moves a newborn sub-level, and the trade is that mass and inertia",
                    "are genuinely ignored here: a cathedral wall and a single block both take",
                    "this number, where a real torque would have spun the small one faster.",
                    "For scale, 2.5 is roughly a full rotation every two and a half",
                    "seconds and reads as violent - that was the shipped value through 0.6.4",
                    "and it spun every collapse hard enough that no piece ever landed square",
                    "again. The default 0.05 is about three degrees a second, so a piece",
                    "falling for two seconds turns some six degrees: enough to read as debris",
                    "rather than a sliding block, and well inside snapOrientationEpsilon, so",
                    "a piece that lands flat still re-aligns and reverts to blocks. That",
                    "interaction is the real constraint on this number - anything fast enough",
                    "to tumble a piece past the snap tolerance also stops it ever reverting.",
                    "0 disables the roll outright; the collapse still shoves pieces sideways",
                    "under collapseForce, which is independent.")
            .defineInRange("collapseTorque", 0.05, 0.0, 1024.0);

    private static final ModConfigSpec.BooleanValue PLAYER_IMPACT_ENABLED = B
            .comment("Whether a player standing on a loose piece can move it - landing on one",
                    "drives it down, jumping off it kicks it away underfoot. Off, a player is",
                    "weightless to a sub-level and can walk across a falling roof unnoticed.")
            .define("playerImpactEnabled", true);

    private static final ModConfigSpec.DoubleValue PLAYER_IMPACT_MASS = B
            .comment("What a player weighs when they hit a sub-level, on the same scale the",
                    "physics engine masses blocks - and it masses them at about one apiece, so",
                    "the default lands a player like a five-block lump rather than like a real",
                    "human against real stone. The impulse handed over is this times the speed",
                    "the player arrived at, so it is the one number that decides how much a",
                    "person can shove. 0 makes them weightless without disabling the detection.")
            .defineInRange("playerImpactMass", 5.0, 0.0, 1024.0);

    private static final ModConfigSpec.DoubleValue PLAYER_IMPACT_MIN_SPEED = B
            .comment("How fast a player must be travelling, in blocks per tick, for the landing",
                    "to count. Stepping down off a stair is about 0.08 and should not visibly",
                    "rock a building; a jump lands at roughly 0.5 and should.")
            .defineInRange("playerImpactMinSpeed", 0.15, 0.0, 4.0);

    // ---- the weight of a person on a floor ---------------------------------

    private static final ModConfigSpec.BooleanValue JUMP_SHOCK_ENABLED = B
            .comment("Whether landing on a structure loads it. A player who jumps onto a tracked",
                    "block sends a charge down whatever carries it, exactly as if a block had",
                    "been placed there, and then the charge is handed back a moment later. A",
                    "sound floor never notices; a floor that was already one point from failing",
                    "gives way underfoot, which is the whole point of the mechanism. Off, a",
                    "player weighs nothing to the world and only ever loads it by building.")
            .define("jumpShockEnabled", true);

    private static final ModConfigSpec.IntValue JUMP_SHOCK_LOAD = B
            .comment("How many points of integrity a landing spends while the player is standing",
                    "there. 1 is a person; raising it makes a floor that survives being built",
                    "fail the moment anyone walks onto it, which is a different game. The charge",
                    "travels the same support chain a placement does, so a span still pays the",
                    "sideways doubling and a pillar still pays once per block.")
            .defineInRange("jumpShockLoad", 1, 0, 64);

    private static final ModConfigSpec.IntValue JUMP_SHOCK_RECOVERY_TICKS = B
            .comment("How long, in ticks, the landing load stays on the structure before it is",
                    "handed back. 20 is one second: long enough that a building already at its",
                    "limit has a real window in which to come down, short enough that walking",
                    "about does not accumulate. Every point taken is given back exactly, so this",
                    "is a window and not a cost - a structure that survives the second is in the",
                    "state it started in. 0 restores on the very next tick.")
            .defineInRange("jumpShockRecoveryTicks", 20, 0, 12000);

    private static final ModConfigSpec.DoubleValue JUMP_SHOCK_MIN_FALL_DISTANCE = B
            .comment("How far a player must have fallen, in blocks, for the landing to load the",
                    "structure. A standing jump peaks at about 1.25, stepping down off a slab is",
                    "about 0.5, and simply walking is 0. The default lets a step down count and",
                    "ignores flat ground; raise it past 1.3 to make only real falls load a",
                    "floor, or drop it to 0 to have every landing of any kind count.")
            .defineInRange("jumpShockMinFallDistance", 0.5, 0.0, 256.0);

    // ---- sable ------------------------------------------------------------

    private static final ModConfigSpec.BooleanValue FIX_SUB_LEVEL_SKY_LIGHT = B
            .comment("Whether to force sky light on for freshly created sable sub-level chunks.",
                    "Sable builds a plot chunk with its light-correct flag cleared and then asks",
                    "the light engine to honour that flag, so a sub-level that has just been",
                    "assembled is created with sky light DISABLED and ships all-zero sky data to",
                    "every client tracking it - which is why a falling roof renders under a",
                    "permanent night no matter what time it actually is. Sable's own reload path",
                    "does the opposite: a saved sub-level restores its flag from NBT as true",
                    "before lighting, so reloaded pieces light correctly and only fresh ones are",
                    "dark. This makes a fresh plot behave the way a reloaded one already does.",
                    "Only has any effect with sable installed.")
            .define("fixSubLevelSkyLight", true);

    // ---- ground that stopped being ground ----------------------------------

    private static final ModConfigSpec.IntValue ENCLOSURE_CHECK_CHANCE = B
            .comment("One in this many broken blocks - mined by a player or crushed by integrity",
                    "- triggers the enclosure walk: are the untouched blocks around the break",
                    "walled in entirely by tracked blocks, and therefore not ground any more?",
                    "1 runs it on every break, 0 disables it. Raising it makes mined-out terrain",
                    "take longer to notice it is floating; lowering it costs a walk per break.")
            .defineInRange("enclosureCheckChance", 100, 0, 100000);

    private static final ModConfigSpec.IntValue ENCLOSURE_MAX_REGION = B
            .comment("How many untouched blocks the enclosure walk may cover before deciding the",
                    "region is open terrain and leaving it as ground. Open terrain is what hits",
                    "this; an enclosed lump closes long before it.")
            .defineInRange("enclosureMaxRegion", 4096, 8, 65536);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ENCLOSURE_GROUND_BLOCKS = B
            .comment("Blocks that are ground no matter what has been built around them. The",
                    "enclosure walk stops and leaves the region alone the moment it reaches one,",
                    "so deep rock cannot be made to fall by walling it in.")
            .defineListAllowEmpty("enclosureGroundBlocks", List.of("minecraft:deepslate"),
                    () -> "", SIConfig::isBlockId);

    private static final ModConfigSpec.BooleanValue MATERIAL_BOUNDARY_STOPS = B
            .comment("true: material crossings shape the chain. Into a sturdier material the",
                    "delta lands once on the first block and stops there, absorbed. Into a",
                    "weaker material the first block takes the previous block's whole",
                    "remaining deficit (entry value - wbireg) instead of the plain delta; zero",
                    "deficit means nothing crosses and the walk stops. Equal calibre passes",
                    "untouched. Charge and relax walk the same way.")
            .define("materialBoundaryStops", true);

    private static final ModConfigSpec.IntValue MAX_LOAD_PATH = B
            .comment("How many blocks the placement charge may descend before giving up.")
            .defineInRange("maxLoadPath", Integrity.MAX_LOAD_PATH, 1, 4096);

    private static final ModConfigSpec.IntValue FAIL_AT = B
            .comment("Integrity at which a block fails outright. One above this is the",
                    "cracked state - loaded to its last point, still standing.")
            .defineInRange("failAt", Integrity.FAIL_AT, 0, 64);

    private static final ModConfigSpec.DoubleValue SIDEWAYS_LOAD_MULTIPLIER = B
            .comment("What a sideways link in the load path costs. Load handed straight",
                    "down costs the plain amount; load handed sideways costs this much,",
                    "and it is charged to whichever of the two blocks is the sturdier",
                    "MATERIAL by natural integrity - so spanning forces a builder to reach",
                    "for a better block for floors and roofs. Pillaring is therefore",
                    "cheaper than ledging out: at 2.0 a player builds out about half as",
                    "far as they can build up in the same material. 1.0 disables the rule.")
            .defineInRange("sidewaysLoadMultiplier", 2.0, 1.0, 8.0);

    private static final ModConfigSpec.BooleanValue SIDEWAYS_MULTIPLIER_ON_RESTORE = B
            .comment("true: the sideways multiplier applies to integrity being GIVEN BACK",
                    "as well as spent, so breaking a block returns exactly what placing it",
                    "cost and a bridge edited over and over does not quietly erode.",
                    "false: only debuffs are multiplied - the literal reading of 'sideways",
                    "costs double', at the price of losing a point on every edit.")
            .define("sidewaysMultiplierOnRestore", true);

    private static final ModConfigSpec.DoubleValue SIDE_INHERITANCE_FACTOR = B
            .comment("What fraction of its own natural integrity a block gets when it is",
                    "placed against the SIDE of its support instead of on top of it. The",
                    "cap is on the placed block's own material, not on the value it",
                    "inherits, so a ledge is uniformly half-strength rather than halving",
                    "again at every block out. Ground is exempt - founding on rock is free",
                    "whichever face touches it. 1.0 disables the rule.")
            .defineInRange("sideInheritanceFactor", 0.5, 0.0, 1.0);

    // ---- base integrity values --------------------------------------------

    private static final ModConfigSpec.BooleanValue DERIVE_FROM_HARDNESS = B
            .comment("true: a solid block with no datamap row derives its natural integrity",
                    "from its own hardness - defaultIntegrity * sqrt(hardness / 1.5), so a",
                    "stone-hardness block lands on defaultIntegrity exactly; unbreakable",
                    "blocks derive 1024. false: every row-less block gets defaultIntegrity.")
            .define("deriveIntegrityFromHardness", true);

    private static final ModConfigSpec.IntValue DEFAULT_INTEGRITY = B
            .comment("Natural integrity for a solid block with no datamap row; with",
                    "deriveIntegrityFromHardness it is the scale anchor at stone hardness.")
            .defineInRange("defaultIntegrity", Integrity.DEFAULT_INTEGRITY, 1, 1024);

    private static final ModConfigSpec.IntValue DEFAULT_FRAGILE = B
            .comment("Natural integrity for a block with no collision shape - a torch, a rail.")
            .defineInRange("defaultFragileIntegrity", Integrity.DEFAULT_FRAGILE, 1, 1024);

    // ---- collapse / sub-level assembly ------------------------------------

    private static final ModConfigSpec.IntValue MAX_REGION = B
            .comment("Hard cap on one region flood fill during evaluation.")
            .defineInRange("maxRegion", Integrity.MAX_REGION, 64, 65536);

    private static final ModConfigSpec.IntValue MAX_ASSEMBLY = B
            .comment("Largest detached component that becomes a physics sub-level.",
                    "Anything bigger is load-bearing terrain and stays where it is.")
            .defineInRange("maxAssemblySize", SIFall.MAX_ASSEMBLY, 1, 8192);

    private static final ModConfigSpec.IntValue ASSEMBLY_COOLDOWN_TICKS = B
            .comment("Ticks an anchor is held off after an assembly attempt.")
            .defineInRange("assemblyCooldownTicks", SIFall.ASSEMBLY_COOLDOWN_TICKS, 1, 1200);

    // ---- the landing ------------------------------------------------------

    private static final ModConfigSpec.DoubleValue SNAP_POSITION_EPSILON = B
            .comment("How far a resting sub-level's anchor may sit from a block centre and",
                    "still snap back into regular blocks. In blocks.",
                    "0.08 since 0.7.0, down from 0.2 - a fifth of a block is a visible",
                    "offset, and a piece that snapped from there jumped noticeably as it",
                    "reverted. Tighter means a piece has to come to rest closer to square,",
                    "so the revert is invisible; the cost is that a piece which settles",
                    "outside the tolerance never reverts at all and stays a physics body,",
                    "because a resting body does not move again to drift back into range.",
                    "Raise it if collapses leave debris lying around that should have",
                    "become blocks.")
            .defineInRange("snapPositionEpsilon", 0.08, 0.001, 0.5);

    private static final ModConfigSpec.DoubleValue SNAP_ORIENTATION_EPSILON = B
            .comment("How far a resting sub-level's rotated axes may miss the grid and still",
                    "snap back into regular blocks.",
                    "A chord distance between unit vectors, not an angle: eps = 2*sin(t/2),",
                    "so 0.2 was 11.5 degrees and 0.12, the default since 0.7.0, is 6.9.",
                    "This number and collapseTorque are one setting in two halves. A piece",
                    "spun at collapseTorque radians per second for a two second fall arrives",
                    "0.1 radians (5.7 degrees, chord 0.100) out of true at the default 0.05,",
                    "so 0.12 clears a typical collapse with a little room and nothing more.",
                    "Tighten this below what the torque produces and pieces stop reverting",
                    "entirely - that is exactly what the 2.5 torque shipped through 0.6.4",
                    "did, 38 collapses and not one revert. If you want this near 0.035",
                    "(2 degrees) then collapseTorque has to come down to about 0.015 in the",
                    "same edit.")
            .defineInRange("snapOrientationEpsilon", 0.12, 0.001, 0.5);

    private static final ModConfigSpec.IntValue REST_CHECK_TICKS = B
            .comment("How long after coming to rest a sub-level stays eligible to revert.")
            .defineInRange("restCheckTicks", 20, 1, 1200);

    private static final ModConfigSpec.BooleanValue SUBLEVELS_FLOAT_WHEN_RECONVERTED = B
            .comment("Whether a landing is allowed to mint new ground.",
                    "false, the default since 0.7.0: it never is. Every block a landed",
                    "sub-level converts back re-enters the world tracked at its own natural",
                    "integrity, and the landing is re-checked next tick - a pile that cannot",
                    "reach real ground from where it stopped simply falls again. Debris stays",
                    "debris, and nothing a collapse drops turns into terrain.",
                    "true: a landed block becomes an anchor wherever a neighbour outside the",
                    "landed set is pre-existing structure - its row is cleared, and an",
                    "untracked position reads as ground. The rest land tracked at natural.",
                    "That was the behaviour through 0.6.6, and because an anchor never fails",
                    "it meant a collapse could leave a heap of freshly minted, permanently",
                    "unbreakable-by-integrity ground lying against whatever it fell on. The",
                    "gametest suite watched it happen: 4/4 landed blocks touch the standing",
                    "world, so every block of that reverted piece became ground.")
            .define("subLevelsFloatWhenReconverted", false);

    public static final ModConfigSpec SPEC = B.build();

    // ---- getters, safe before the config file loads -----------------------

    private static boolean isBlockId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }

    /**
     * A block id, or a #tag naming a set of them. Validation only - resolving a tag
     * to actual blocks is a separate job from deciding the entry is well-formed.
     */
    private static boolean isBlockIdOrTag(Object o) {
        if (!(o instanceof String s) || s.isEmpty()) {
            return false;
        }
        return ResourceLocation.tryParse(s.startsWith("#") ? s.substring(1) : s) != null;
    }

    private static Set<Block> resolve(List<? extends String> ids) {
        Set<Block> out = new HashSet<>();
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
                out.add(BuiltInRegistries.BLOCK.get(rl));
            }
        }
        return out;
    }

    // The two lists are resolved per call; both are tiny and read on the block-event
    // path only. Cache here if a profile ever says otherwise.
    public static Set<Block> nonStructuralBlocks() {
        return SPEC.isLoaded() ? resolve(NON_STRUCTURAL_BLOCKS.get()) : Set.of();
    }

    public static Set<Block> defaultAnchorBlocks() {
        return SPEC.isLoaded() ? resolve(DEFAULT_ANCHOR_BLOCKS.get()) : Set.of();
    }

    public static boolean gravityFirstChain() {
        return !SPEC.isLoaded() || GRAVITY_FIRST_CHAIN.get();
    }

    public static int clumpBracingThreshold() {
        return SPEC.isLoaded() ? CLUMP_BRACING_THRESHOLD.get() : 5;
    }

    public static boolean clumpAddsOnTop() {
        return !SPEC.isLoaded() || CLUMP_ADDS_ON_TOP.get();
    }

    public static boolean breakWeakerBlock() {
        return !SPEC.isLoaded() || BREAK_WEAKER_BLOCK.get();
    }

    public static boolean breakStrongerBlock() {
        return SPEC.isLoaded() && BREAK_STRONGER_BLOCK.get();
    }

    public static boolean reverseIntegrityOnBreak() {
        return SPEC.isLoaded() && REVERSE_INTEGRITY_ON_BREAK.get();
    }

    public static int explosionShockwaveDelta() {
        return SPEC.isLoaded() ? EXPLOSION_SHOCKWAVE_DELTA.get() : 0;
    }

    public static boolean materialBoundaryStops() {
        return !SPEC.isLoaded() || MATERIAL_BOUNDARY_STOPS.get();
    }

    public static int maxLoadPath() {
        return SPEC.isLoaded() ? MAX_LOAD_PATH.get() : Integrity.MAX_LOAD_PATH;
    }

    public static double sidewaysLoadMultiplier() {
        return SPEC.isLoaded() ? SIDEWAYS_LOAD_MULTIPLIER.get() : 2.0;
    }

    public static boolean sidewaysMultiplierOnRestore() {
        return !SPEC.isLoaded() || SIDEWAYS_MULTIPLIER_ON_RESTORE.get();
    }

    public static double sideInheritanceFactor() {
        return SPEC.isLoaded() ? SIDE_INHERITANCE_FACTOR.get() : 0.5;
    }

    public static boolean breakShockwave() {
        return !SPEC.isLoaded() || BREAK_SHOCKWAVE.get();
    }

    public static int holdSpentUpToNatural() {
        return SPEC.isLoaded() ? HOLD_SPENT_UP_TO_NATURAL.get()
                : Integrity.HOLD_SPENT_UP_TO_NATURAL;
    }

    public static boolean breakFragileWhenSpent() {
        return !SPEC.isLoaded() || BREAK_FRAGILE_WHEN_SPENT.get();
    }

    public static int failAt() {
        return SPEC.isLoaded() ? FAIL_AT.get() : Integrity.FAIL_AT;
    }

    @SuppressWarnings("unchecked")
    public static List<String> integrityPatterns() {
        return SPEC.isLoaded() ? (List<String>) INTEGRITY_PATTERNS.get() : List.of();
    }

    private static boolean isPatternEntry(Object o) {
        if (!(o instanceof String s)) {
            return false;
        }
        int eq = s.lastIndexOf('=');
        if (eq <= 0 || eq == s.length() - 1) {
            return false;
        }
        try {
            int v = Integer.parseInt(s.substring(eq + 1).trim());
            return v >= 1 && v <= 1024;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean deriveIntegrityFromHardness() {
        return !SPEC.isLoaded() || DERIVE_FROM_HARDNESS.get();
    }

    public static int defaultIntegrity() {
        return SPEC.isLoaded() ? DEFAULT_INTEGRITY.get() : Integrity.DEFAULT_INTEGRITY;
    }

    public static int defaultFragileIntegrity() {
        return SPEC.isLoaded() ? DEFAULT_FRAGILE.get() : Integrity.DEFAULT_FRAGILE;
    }

    public static int maxRegion() {
        return SPEC.isLoaded() ? MAX_REGION.get() : Integrity.MAX_REGION;
    }

    public static int maxAssemblySize() {
        return SPEC.isLoaded() ? MAX_ASSEMBLY.get() : SIFall.MAX_ASSEMBLY;
    }

    public static int assemblyCooldownTicks() {
        return SPEC.isLoaded() ? ASSEMBLY_COOLDOWN_TICKS.get() : SIFall.ASSEMBLY_COOLDOWN_TICKS;
    }

    public static double snapPositionEpsilon() {
        return SPEC.isLoaded() ? SNAP_POSITION_EPSILON.get() : 0.08;
    }

    public static double snapOrientationEpsilon() {
        return SPEC.isLoaded() ? SNAP_ORIENTATION_EPSILON.get() : 0.12;
    }

    public static int restCheckTicks() {
        return SPEC.isLoaded() ? REST_CHECK_TICKS.get() : 20;
    }

    public static boolean subLevelsFloatWhenReconverted() {
        return SPEC.isLoaded() && SUBLEVELS_FLOAT_WHEN_RECONVERTED.get();
    }

    public static boolean forceScalesWithMass() {
        return !SPEC.isLoaded() || FORCE_SCALES_WITH_MASS.get();
    }

    public static double explosionForce() {
        return SPEC.isLoaded() ? EXPLOSION_FORCE.get() : 8.0;
    }

    public static double explosionForceRadius() {
        return SPEC.isLoaded() ? EXPLOSION_FORCE_RADIUS.get() : 3.0;
    }

    public static double collapseForce() {
        return SPEC.isLoaded() ? COLLAPSE_FORCE.get() : 1.5;
    }

    public static double collapseTorque() {
        return SPEC.isLoaded() ? COLLAPSE_TORQUE.get() : 2.5;
    }

    public static boolean playerImpactEnabled() {
        return !SPEC.isLoaded() || PLAYER_IMPACT_ENABLED.get();
    }

    public static double playerImpactMass() {
        return SPEC.isLoaded() ? PLAYER_IMPACT_MASS.get() : 5.0;
    }

    public static double playerImpactMinSpeed() {
        return SPEC.isLoaded() ? PLAYER_IMPACT_MIN_SPEED.get() : 0.15;
    }

    public static boolean jumpShockEnabled() {
        return !SPEC.isLoaded() || JUMP_SHOCK_ENABLED.get();
    }

    public static int jumpShockLoad() {
        return SPEC.isLoaded() ? JUMP_SHOCK_LOAD.get() : 1;
    }

    public static int jumpShockRecoveryTicks() {
        return SPEC.isLoaded() ? JUMP_SHOCK_RECOVERY_TICKS.get() : 20;
    }

    public static double jumpShockMinFallDistance() {
        return SPEC.isLoaded() ? JUMP_SHOCK_MIN_FALL_DISTANCE.get() : 0.5;
    }

    public static boolean fixSubLevelSkyLight() {
        return !SPEC.isLoaded() || FIX_SUB_LEVEL_SKY_LIGHT.get();
    }

    public static int enclosureCheckChance() {
        return SPEC.isLoaded() ? ENCLOSURE_CHECK_CHANCE.get() : 100;
    }

    public static int enclosureMaxRegion() {
        return SPEC.isLoaded() ? ENCLOSURE_MAX_REGION.get() : 4096;
    }

    public static Set<Block> enclosureGroundBlocks() {
        return SPEC.isLoaded() ? resolve(ENCLOSURE_GROUND_BLOCKS.get())
                : Set.of(net.minecraft.world.level.block.Blocks.DEEPSLATE);
    }
}
