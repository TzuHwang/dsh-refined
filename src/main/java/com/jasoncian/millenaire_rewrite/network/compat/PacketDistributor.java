package com.jasoncian.millenaire_rewrite.network.compat;

import com.jasoncian.millenaire_rewrite.network.ModNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * NeoForge PacketDistributor 静态方法的 Forge SimpleChannel 实现。
 */
public final class PacketDistributor {

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ModNetworking.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player), payload);
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        ModNetworking.CHANNEL.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), payload);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        ModNetworking.CHANNEL.sendToServer(payload);
    }

    private PacketDistributor() {
    }
}
