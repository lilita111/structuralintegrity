package com.apokalypse.structuralintegrity;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
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

    public StructuralIntegrity(IEventBus modBus) {
        modBus.addListener(SIDataMaps::onRegisterDataMaps);
        modBus.addListener(SIPayloads::register);
        NeoForge.EVENT_BUS.register(SIEvents.class);
        NeoForge.EVENT_BUS.register(SIFall.class);
        LOGGER.info("[SI] stage 2 loaded - integrity = stored - hang; ground = no wbireg row (max+1)");
        LOGGER.info("[SI] limits: maxRegion={} defaultIntegrity={}",
                Integrity.MAX_REGION, Integrity.DEFAULT_INTEGRITY);
        LOGGER.info("[SI] placement rule: on ground stored = natural; otherwise stored = min(natural, support - 1)");
        LOGGER.info("[SI] the support is never reduced; a support at {} is crushed instead", Integrity.CRUSH_AT);
        LOGGER.info("[SI] falling: a component that cannot reach ground is assembled by sable, up to {} blocks",
                SIFall.MAX_ASSEMBLY);
    }
}
