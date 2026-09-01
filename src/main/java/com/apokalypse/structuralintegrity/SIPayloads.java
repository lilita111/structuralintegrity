package com.apokalypse.structuralintegrity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The goggle read-out is a client feature over server-only state: wbireg lives on
 * the server and is not derivable from the block in front of you. So the client
 * asks about the one position it is looking at, and the server answers.
 *
 * Query is sent only while wearing goggles, only when the hovered position changes
 * or once a second, and is answered only within {@link #MAX_QUERY_DISTANCE} blocks
 * of the asking player. Everything else is one solve, the same one a place or break
 * already runs.
 */
public final class SIPayloads {
    private SIPayloads() {}

    /** A client cannot ask about arbitrary coordinates, only what it can see. */
    public static final double MAX_QUERY_DISTANCE = 64.0;

    public static final int FLAG_ANCHOR = 1;
    public static final int FLAG_GROUNDED = 2;
    public static final int FLAG_STRUCTURAL = 4;

    // -- client asks -------------------------------------------------------

    public record Query(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(StructuralIntegrity.MODID, "query"));

        public static final StreamCodec<ByteBuf, Query> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Query::pos,
                Query::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // -- server answers ----------------------------------------------------

    public record Info(BlockPos pos, int natural, int stored, int hang, int flags,
                       int chainStored, int chainNatural, int chainDepth)
            implements CustomPacketPayload {
        public static final Type<Info> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(StructuralIntegrity.MODID, "info"));

        // Written out by hand because StreamCodec.composite tops out at six
        // component pairs and this record has eight. The alternative - folding the
        // three footing fields into a nested record - would put a second shape on
        // the wire to work around a helper's argument list, which is a worse trade
        // than eight explicit lines. Field order here IS the wire format: read in
        // the same order it is written, and change both together.
        public static final StreamCodec<ByteBuf, Info> CODEC = StreamCodec.of(
                (buf, info) -> {
                    BlockPos.STREAM_CODEC.encode(buf, info.pos());
                    ByteBufCodecs.VAR_INT.encode(buf, info.natural());
                    ByteBufCodecs.VAR_INT.encode(buf, info.stored());
                    ByteBufCodecs.VAR_INT.encode(buf, info.hang());
                    ByteBufCodecs.VAR_INT.encode(buf, info.flags());
                    ByteBufCodecs.VAR_INT.encode(buf, info.chainStored());
                    ByteBufCodecs.VAR_INT.encode(buf, info.chainNatural());
                    ByteBufCodecs.VAR_INT.encode(buf, info.chainDepth());
                },
                buf -> new Info(
                        BlockPos.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public boolean anchor() {
            return (flags & FLAG_ANCHOR) != 0;
        }

        public boolean grounded() {
            return (flags & FLAG_GROUNDED) != 0;
        }

        public boolean structural() {
            return (flags & FLAG_STRUCTURAL) != 0;
        }

        /**
         * Whether the server found a support chain under this block at all. An
         * anchor has none - it IS the ground - and neither does a block the walk
         * could not start from.
         */
        public boolean hasChain() {
            return chainDepth > 0 && chainNatural > 0;
        }

        /**
         * What the block has left, and the only thing that decides whether it stands:
         * the same number {@link Integrity.Result#integrity()} returns, the same one
         * the server logs, and the same one it breaks on at
         * {@code stored <= failAt}.
         *
         * Deliberately NOT hang-adjusted. It used to be, matching a server-side
         * formula that subtracted the hanging weight here as well; when that double
         * charge was removed from {@link Integrity.Result#integrity()} this copy was
         * missed, and the goggles went on subtracting {@link #hang} - which is a
         * COUNT OF BLOCKS, not integrity, and unbounded. The read-out ran tens below
         * zero on a block in no danger whatsoever, never reached zero when a block
         * actually failed, and printed it all as "x / natural" as though it were a
         * fraction of the material's own rating.
         *
         * {@link #hang} is still sent and still shown - on its own line, as the block
         * count it is.
         */
        public int integrity() {
            return stored;
        }
    }

    // -- registration ------------------------------------------------------

    public static void register(RegisterPayloadHandlersEvent event) {
        // Bumped from "1" when Info grew the footing fields. A client on the old
        // shape and a server on the new one would otherwise decode three numbers
        // that are not there.
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(Query.TYPE, Query.CODEC, SIPayloads::onQuery);
        registrar.playToClient(Info.TYPE, Info.CODEC, SIPayloads::onInfo);
        StructuralIntegrity.LOGGER.info("[SI] registered goggle payloads (query, info)");
    }

    private static void onQuery(Query query, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockPos pos = query.pos();
            if (!pos.closerToCenterThan(player.position(), MAX_QUERY_DISTANCE) || !level.isLoaded(pos)) {
                return;
            }
            WbiReg reg = WbiReg.of(level);
            Integrity.Result r = Integrity.compute(level, reg, pos);
            // Where this block's load actually ends up. One dry walk down the same
            // support chain a placement charges - it writes nothing, and it is the
            // same code, so the read-out cannot disagree with the physics.
            Integrity.Probe probe = r.structural() && !r.anchor()
                    ? Integrity.probe(level, reg, pos) : null;

            int flags = 0;
            if (r.anchor()) {
                flags |= FLAG_ANCHOR;
            }
            if (r.grounded()) {
                flags |= FLAG_GROUNDED;
            }
            if (r.structural()) {
                flags |= FLAG_STRUCTURAL;
            }
            PacketDistributor.sendToPlayer(player,
                    new Info(pos, r.natural(), r.stored(), r.hangMax(), flags,
                            probe == null ? 0 : probe.stored(),
                            probe == null ? 0 : probe.natural(),
                            probe == null ? 0 : probe.depth()));
        });
    }

    private static void onInfo(Info info, IPayloadContext ctx) {
        // Client only. Deferred, so SIClientCache is never loaded on a server.
        ctx.enqueueWork(() -> SIClientCache.accept(info));
    }
}
