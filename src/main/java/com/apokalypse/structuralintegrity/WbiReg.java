package com.apokalypse.structuralintegrity;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * worldblockintegrityreg - position to current integrity, per dimension.
 *
 * Sparse and empty at worldgen. A position with no row reads as {@link #ANCHOR},
 * which is maximum + 1: immovable, cannot be reduced, cannot fall, and terminates
 * any fill that reaches it. That is the whole definition of ground - untouched.
 *
 * A row appears when a player disturbs the position: placing there, or mining a
 * neighbour. Rows never go back to absent, so disturbed ground stays disturbed.
 *
 * Not derivable from the world, so it is persisted. Stored as two parallel arrays
 * rather than a compound per entry - 12 bytes an entry instead of ~40.
 */
public final class WbiReg extends SavedData {
    public static final String FILE = StructuralIntegrity.MODID + "_wbireg";

    /** No row. Reads as maximum + 1: the block is ground. */
    public static final int ANCHOR = Integer.MAX_VALUE;

    private final Long2IntOpenHashMap map = new Long2IntOpenHashMap();

    /**
     * What each row was worth when it entered tracking - written by the entry
     * paths (placement, materialisation, disturb, revert landing), never by the
     * chain's wear. The deficit a material crossing hands down is measured
     * against this, so a support-limited entry is not mistaken for damage.
     * Rows from saves predating this map have no entry and fall back to natural.
     */
    private final Long2IntOpenHashMap entryMap = new Long2IntOpenHashMap();

    public WbiReg() {
        map.defaultReturnValue(ANCHOR);
        entryMap.defaultReturnValue(-1);
    }

    public static WbiReg of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WbiReg::new, WbiReg::load), FILE);
    }

    // -- state ------------------------------------------------------------

    /** Current integrity, or {@link #ANCHOR} if this position has never been disturbed. */
    public int get(BlockPos pos) {
        return map.get(pos.asLong());
    }

    public boolean isAnchor(BlockPos pos) {
        return !map.containsKey(pos.asLong());
    }

    public void set(BlockPos pos, int integrity) {
        map.put(pos.asLong(), integrity);
        setDirty();
    }

    /** An entry write: sets the row AND records the value as the entry baseline. */
    public void setEntry(BlockPos pos, int integrity) {
        map.put(pos.asLong(), integrity);
        entryMap.put(pos.asLong(), integrity);
        setDirty();
    }

    /** The value this row entered tracking with, or -1 if unrecorded (legacy row). */
    public int entryOf(BlockPos pos) {
        return entryMap.get(pos.asLong());
    }

    /** Drop the row for a position whose block is gone. */
    public void clear(BlockPos pos) {
        entryMap.remove(pos.asLong());
        if (map.remove(pos.asLong()) != ANCHOR) {
            setDirty();
        }
    }

    public int size() {
        return map.size();
    }

    // -- persistence ------------------------------------------------------

    public static WbiReg load(CompoundTag tag, HolderLookup.Provider lookup) {
        WbiReg out = new WbiReg();
        long[] keys = tag.getLongArray("pos");
        int[] vals = tag.getIntArray("val");
        int n = Math.min(keys.length, vals.length);
        for (int i = 0; i < n; i++) {
            out.map.put(keys[i], vals[i]);
        }
        long[] ekeys = tag.getLongArray("entpos");
        int[] evals = tag.getIntArray("entval");
        int en = Math.min(ekeys.length, evals.length);
        for (int i = 0; i < en; i++) {
            out.entryMap.put(ekeys[i], evals[i]);
        }
        StructuralIntegrity.LOGGER.info("[SI] wbireg loaded {} entries (pos={} val={})",
                n, keys.length, vals.length);
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        long[] keys = new long[map.size()];
        int[] vals = new int[map.size()];
        int i = 0;
        for (Long2IntMap.Entry e : map.long2IntEntrySet()) {
            keys[i] = e.getLongKey();
            vals[i] = e.getIntValue();
            i++;
        }
        tag.putLongArray("pos", keys);
        tag.putIntArray("val", vals);
        long[] ekeys = new long[entryMap.size()];
        int[] evals = new int[entryMap.size()];
        int ei = 0;
        for (Long2IntMap.Entry e : entryMap.long2IntEntrySet()) {
            ekeys[ei] = e.getLongKey();
            evals[ei] = e.getIntValue();
            ei++;
        }
        tag.putLongArray("entpos", ekeys);
        tag.putIntArray("entval", evals);
        StructuralIntegrity.LOGGER.info("[SI] wbireg saved {} entries", i);
        return tag;
    }
}
