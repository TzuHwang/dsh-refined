package com.jasoncian.millenaire_rewrite.network;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.network.compat.CustomPacketPayload;
import com.jasoncian.millenaire_rewrite.network.compat.IPayloadContext;
import com.jasoncian.millenaire_rewrite.network.compat.StreamCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * 网络包注册 - 注册所有客户端-服务端同步包
 *
 * 包类型：
 * - VillageSyncPacket: 村庄数据同步
 * - VillagerSyncPacket: 村民数据同步
 * - QuestSyncPacket: 任务进度同步
 * - EconomySyncPacket: 经济数据同步
 * - ReputationSyncPacket: 声望数据同步
 *
 * @author Based on NeoForge networking patterns
 * @version 1.0.0
 */
public class ModNetworking {

    /** 网络协议版本 */
    public static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        id("main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static int nextId = 0;

    // ================ 包注册 ================

    /** 在 FMLCommonSetupEvent 中调用 */
    public static void register() {
        // 服务端 -> 客户端 包
        playToClient(VillageSyncPacket.class, VillageSyncPacket.STREAM_CODEC, VillageSyncPacket::handle);
        playToClient(VillagerSyncPacket.class, VillagerSyncPacket.STREAM_CODEC, VillagerSyncPacket::handle);
        playToClient(QuestSyncPacket.class, QuestSyncPacket.STREAM_CODEC, QuestSyncPacket::handle);
        playToClient(EconomySyncPacket.class, EconomySyncPacket.STREAM_CODEC, EconomySyncPacket::handle);
        playToClient(ReputationSyncPacket.class, ReputationSyncPacket.STREAM_CODEC, ReputationSyncPacket::handle);
        playToClient(VillageListSyncPacket.class, VillageListSyncPacket.STREAM_CODEC, VillageListSyncPacket::handle);

        // 客户端 -> 服务端 包
        playToServer(RequestVillageDataPacket.class, RequestVillageDataPacket.STREAM_CODEC, RequestVillageDataPacket::handle);
        playToServer(QuestActionPacket.class, QuestActionPacket.STREAM_CODEC, QuestActionPacket::handle);

        MillenaireRewrite.LOGGER.info("Millenaire network packets registered");
    }

    private static <T extends CustomPacketPayload> void playToClient(
        Class<T> type, StreamCodec<FriendlyByteBuf, T> codec, BiConsumer<T, IPayloadContext> handler) {
        register(type, codec, handler, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static <T extends CustomPacketPayload> void playToServer(
        Class<T> type, StreamCodec<FriendlyByteBuf, T> codec, BiConsumer<T, IPayloadContext> handler) {
        register(type, codec, handler, NetworkDirection.PLAY_TO_SERVER);
    }

    private static <T extends CustomPacketPayload> void register(
        Class<T> type, StreamCodec<FriendlyByteBuf, T> codec,
        BiConsumer<T, IPayloadContext> handler, NetworkDirection direction) {
        CHANNEL.registerMessage(
            nextId++,
            type,
            (packet, buf) -> codec.encode(buf, packet),
            codec::decode,
            (packet, ctx) -> {
                handler.accept(packet, new IPayloadContext(ctx));
                ctx.get().setPacketHandled(true);
            },
            Optional.of(direction)
        );
    }

    // ================ 工具方法 ================

    /**
     * 创建资源位置
     */
    public static ResourceLocation id(String path) {
        return new ResourceLocation(MillenaireRewrite.MOD_ID, path);
    }
}
