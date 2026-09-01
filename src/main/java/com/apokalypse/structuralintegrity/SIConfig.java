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

    private static final ModConfigSpec.BooleanValue BREAK_ON_INTEGRITY_LOSS = B
            .comment("true: a block scheduled to break at failAt is destroyed (the current",
                    "behaviour). false: the spent block stays in the world, pinned at failAt,",
                    "and stops carrying load - whatever it was holding detaches into a",
                    "sub-level around it. No block is lost and no splintering happens.")
            .define("breakOnIntegrityLoss", true);

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
            .comment("How hard a piece is set spinning the moment integrity loss detaches it,",
                    "about the axis that makes its top lead in the direction it is already",
                    "toppling. Deliberately larger than collapseForce: a piece that shears off",
                    "a wall rolls away, it does not slide off flat.",
                    "Not an angular speed. Rapier divides a torque impulse by the body's own",
                    "moment of inertia, which grows faster than its mass, so one number spins a",
                    "small lump briskly and a wide slab barely - which is the point. 0 disables",
                    "it, independently of collapseForce.")
            .defineInRange("collapseTorque", 2.5, 0.0, 1024.0);

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
                    "still snap back into regular blocks.")
            .defineInRange("snapPositionEpsilon", 0.2, 0.001, 0.5);

    private static final ModConfigSpec.DoubleValue SNAP_ORIENTATION_EPSILON = B
            .comment("How far a resting sub-level's rotated axes may miss the grid and still",
                    "snap back into regular blocks.")
            .defineInRange("snapOrientationEpsilon", 0.2, 0.001, 0.5);

    private static final ModConfigSpec.IntValue REST_CHECK_TICKS = B
            .comment("How long after coming to rest a sub-level stays eligible to revert.")
            .defineInRange("restCheckTicks", 20, 1, 1200);

    private static final ModConfigSpec.BooleanValue SUBLEVELS_FLOAT_WHEN_RECONVERTED = B
            .comment("true: blocks a landed sub-level converts back become anchors (ground),",
                    "but only where they touch an existing anchor; the rest land tracked",
                    "at natural and the landing is re-checked.",
                    "false: they re-enter the world at their natural integrity, tracked, and",
                    "are immediately re-checked - an ungrounded landing falls again.")
            .define("subLevelsFloatWhenReconverted", true);

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

    public static boolean breakOnIntegrityLoss() {
        return !SPEC.isLoaded() || BREAK_ON_INTEGRITY_LOSS.get();
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
        return SPEC.isLoaded() ? SNAP_POSITION_EPSILON.get() : 0.2;
    }

    public static double snapOrientationEpsilon() {
        return SPEC.isLoaded() ? SNAP_ORIENTATION_EPSILON.get() : 0.2;
    }

    public static int restCheckTicks() {
        return SPEC.isLoaded() ? REST_CHECK_TICKS.get() : 20;
    }

    public static boolean subLevelsFloatWhenReconverted() {
        return !SPEC.isLoaded() || SUBLEVELS_FLOAT_WHEN_RECONVERTED.get();
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
