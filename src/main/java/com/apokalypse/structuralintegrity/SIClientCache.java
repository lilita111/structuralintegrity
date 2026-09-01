package com.apokalypse.structuralintegrity;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The last answer the server gave, and nothing else. Deliberately free of client
 * classes so the payload handler can reference it without dragging Minecraft's
 * client into a dedicated server's class loader.
 */
public final class SIClientCache {
    private SIClientCache() {}

    @Nullable
    private static volatile SIPayloads.Info latest;

    public static void accept(SIPayloads.Info info) {
        latest = info;
    }

    /** The answer for exactly this position, or null if we have not been told yet. */
    @Nullable
    public static SIPayloads.Info get(BlockPos pos) {
        SIPayloads.Info info = latest;
        return info != null && info.pos().equals(pos) ? info : null;
    }

    public static void clear() {
        latest = null;
    }
}
