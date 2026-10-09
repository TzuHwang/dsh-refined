package com.jasoncian.millenaire_rewrite.network.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * 仅在客户端加载，隔离对 Minecraft 客户端类的引用。
 */
final class ClientPlayerAccess {

    static Player localPlayer() {
        return Minecraft.getInstance().player;
    }

    private ClientPlayerAccess() {
    }
}
