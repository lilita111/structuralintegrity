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
                    "Air, fluids and plants are always ignored; this list adds to that.")
            .defineListAllowEmpty("nonStructuralBlocks", List.of(), () -> "", SIConfig::isBlockId);

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
}
