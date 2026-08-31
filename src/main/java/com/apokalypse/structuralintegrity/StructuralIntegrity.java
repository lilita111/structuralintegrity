package com.apokalypse.structuralintegrity;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structural Integrity - stage 1.
 *
 * Read-only. Every block place and every block break runs the load solver over the
 * connected component that was touched, and prints where the structure would fail.
 * Nothing in the world is changed by this stage.
 */
@Mod(StructuralIntegrity.MODID)
public class StructuralIntegrity {
    public static final String MODID = "structuralintegrity";
    public static final Logger LOGGER = LoggerFactory.getLogger("StructuralIntegrity");

    public StructuralIntegrity(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SIConfig.SPEC);
        // Pattern rules resolve lazily and cache per block; any config load or
        // live file edit drops the cache so integrityPatterns applies on save.
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent event) -> {
            if (event.getConfig().getSpec() == SIConfig.SPEC) {
                SIPatterns.invalidate();
            }
        });
        modBus.addListener(SIDataMaps::onRegisterDataMaps);
        modBus.addListener(SIPayloads::register);
        NeoForge.EVENT_BUS.register(SIEvents.class);
        NeoForge.EVENT_BUS.register(SIFall.class);
        LOGGER.info("[SI] integrity = stored; the placement cost lives in stored now, so hang is "
                + "reported but no longer charged; ground = no wbireg row (max+1)");
        LOGGER.info("[SI] every block has an entry: datamap row first, else derived from hardness "
                + "(defaultIntegrity * sqrt(h/1.5), unbreakable=1024), else fragile=no collision");
        LOGGER.info("[SI] limits: maxRegion={} defaultIntegrity={}",
                Integrity.MAX_REGION, Integrity.DEFAULT_INTEGRITY);
        LOGGER.info("[SI] outside the system: air, fluids and plants (BushBlock) - leaves are back in; "
                + "nonStructuralBlocks adds to that");
        LOGGER.info("[SI] placement rule: touching ground stored = natural; otherwise stored = "
                + "min(natural, strongest connection); the charge still goes down");
        LOGGER.info("[SI] one chain function: place charges -1, break relaxes +1, an explosion relaxes "
                + "by explosionShockwaveDelta; at most {} deep, charging floors at {} and can snap, "
                + "relaxing caps at natural", Integrity.MAX_LOAD_PATH, Integrity.FAIL_AT);
        LOGGER.info("[SI] material boundary: entering a higher-nireg material applies the delta once and "
                + "stops; entering a weaker one, the first block takes the previous block's whole deficit, "
                + "measured against its recorded entry value (zero deficit absorbs the walk); equal calibre passes");
        LOGGER.info("[SI] splintering: a block broken by the system wears same-type tracked neighbours "
                + "by 1; a neighbour spent by that breaks and splinters in turn; ground is exempt");
        LOGGER.info("[SI] revert anchoring: a landed sub-level's blocks become anchors only where they "
                + "touch existing ground; the rest land tracked at natural and are re-checked");
        LOGGER.info("[SI] support = gravity first: DOWN, then sideways, then UP; one rule for placement and for load");
        LOGGER.info("[SI] clumps: a block entering wbireg with clumpBracingThreshold same-type neighbours "
                + "gets its clump grant once, at init");
        LOGGER.info("[SI] snap: the chain stops at the first spent block; config picks which side of the snap breaks");
        LOGGER.info("[SI] falling: a component that cannot reach ground is assembled by sable, up to {} blocks",
                SIFall.MAX_ASSEMBLY);
        LOGGER.info("[SI] reads never write: goggle queries and region floods peek, only the solver's "
                + "write paths materialise wbireg rows; never_anchor blocks enter tracking at first write");
    }
}
