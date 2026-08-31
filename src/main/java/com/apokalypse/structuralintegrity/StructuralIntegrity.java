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
        modBus.addListener(SIDataMaps::onRegisterDataMaps);
        modBus.addListener(SIPayloads::register);
        NeoForge.EVENT_BUS.register(SIEvents.class);
        NeoForge.EVENT_BUS.register(SIFall.class);
        LOGGER.info("[SI] integrity = stored; the placement cost lives in stored now, so hang is "
                + "reported but no longer charged; ground = no wbireg row (max+1)");
        LOGGER.info("[SI] limits: maxRegion={} defaultIntegrity={}",
                Integrity.MAX_REGION, Integrity.DEFAULT_INTEGRITY);
        LOGGER.info("[SI] placement rule: touching ground stored = natural; otherwise stored = "
                + "min(natural, strongest connection); the charge still goes down");
        LOGGER.info("[SI] one chain function: place charges -1, break relaxes +1, an explosion relaxes "
                + "by explosionShockwaveDelta; at most {} deep, charging floors at {} and can snap, "
                + "relaxing caps at natural", Integrity.MAX_LOAD_PATH, Integrity.FAIL_AT);
        LOGGER.info("[SI] support = gravity first: DOWN, then sideways, then UP; one rule for placement and for load");
        LOGGER.info("[SI] clumps: a block entering wbireg with clumpBracingThreshold same-type neighbours "
                + "gets its clump grant once, at init");
        LOGGER.info("[SI] snap: the chain stops at the first spent block; config picks which side of the snap breaks");
        LOGGER.info("[SI] falling: a component that cannot reach ground is assembled by sable, up to {} blocks",
                SIFall.MAX_ASSEMBLY);
        LOGGER.info("[SI] never_anchor blocks get a wbireg row on sight - loose material and growth are never ground");
    }
}
