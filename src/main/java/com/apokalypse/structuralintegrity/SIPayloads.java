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

    public record Info(BlockPos pos, int natural, int stored, int hang, int flags)
            implements CustomPacketPayload {
        public static final Type<Info> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(StructuralIntegrity.MODID, "info"));

        public static final StreamCodec<ByteBuf, Info> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Info::pos,
                ByteBufCodecs.VAR_INT, Info::natural,
                ByteBufCodecs.VAR_INT, Info::stored,
                ByteBufCodecs.VAR_INT, Info::hang,
                ByteBufCodecs.VAR_INT, Info::flags,
                Info::new);

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
        PayloadRegistrar registrar = event.registrar("1");
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
            Integrity.Result r = Integrity.compute(level, WbiReg.of(level), pos);

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
                    new Info(pos, r.natural(), r.stored(), r.hangMax(), flags));
        });
    }

    private static void onInfo(Info info, IPayloadContext ctx) {
        // Client only. Deferred, so SIClientCache is never loaded on a server.
        ctx.enqueueWork(() -> SIClientCache.accept(info));
    }
}
