package com.jasoncian.millenaire_rewrite.network;

import com.jasoncian.millenaire_rewrite.menu.VillageTradeMenu;
import com.jasoncian.millenaire_rewrite.network.compat.CustomPacketPayload;
import com.jasoncian.millenaire_rewrite.network.compat.IPayloadContext;
import com.jasoncian.millenaire_rewrite.network.compat.PacketDistributor;
import com.jasoncian.millenaire_rewrite.network.compat.StreamCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 村庄商店交易的网络包：客户端点击买卖 -> 服务端成交 -> 服务端发回新的清单。
 */
public final class VillageTradePackets {

    private VillageTradePackets() {
    }

    /**
     * 客户端 -> 服务端：买卖一项商品
     *
     * @param amount 单击 1、右键 8、Shift 64（原版 ContainerTrade）
     */
    public record Action(int containerId, boolean villageSells, String key, int amount) implements CustomPacketPayload {

        public static final Type<Action> TYPE = new Type<>(ModNetworking.id("village_trade_action"));

        public static final StreamCodec<FriendlyByteBuf, Action> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Action decode(FriendlyByteBuf buf) {
                return new Action(buf.readVarInt(), buf.readBoolean(), buf.readUtf(), buf.readVarInt());
            }

            @Override
            public void encode(FriendlyByteBuf buf, Action value) {
                buf.writeVarInt(value.containerId);
                buf.writeBoolean(value.villageSells);
                buf.writeUtf(value.key);
                buf.writeVarInt(value.amount);
            }
        };

        @Override
        public Type<Action> type() {
            return TYPE;
        }

        public static void handle(Action packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof VillageTradeMenu menu
                    && menu.containerId == packet.containerId) {
                    int amount = Math.max(1, Math.min(64, packet.amount));
                    menu.trade(player, packet.villageSells, packet.key, amount);
                    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                    menu.writeState(buf);
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    PacketDistributor.sendToPlayer(player, new State(menu.containerId, bytes));
                }
            });
        }
    }

    /**
     * 服务端 -> 客户端：新的清单、金钱、声望和上次交易的问题
     */
    public record State(int containerId, byte[] data) implements CustomPacketPayload {

        public static final Type<State> TYPE = new Type<>(ModNetworking.id("village_trade_state"));

        public static final StreamCodec<FriendlyByteBuf, State> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public State decode(FriendlyByteBuf buf) {
                return new State(buf.readVarInt(), buf.readByteArray());
            }

            @Override
            public void encode(FriendlyByteBuf buf, State value) {
                buf.writeVarInt(value.containerId);
                buf.writeByteArray(value.data);
            }
        };

        @Override
        public Type<State> type() {
            return TYPE;
        }

        public static void handle(State packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                Player player = context.player();
                if (player != null && player.containerMenu instanceof VillageTradeMenu menu
                    && menu.containerId == packet.containerId) {
                    menu.readState(new FriendlyByteBuf(Unpooled.wrappedBuffer(packet.data)));
                }
            });
        }
    }
}
