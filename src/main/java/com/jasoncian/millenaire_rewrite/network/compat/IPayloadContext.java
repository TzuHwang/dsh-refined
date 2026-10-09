package com.jasoncian.millenaire_rewrite.network.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 把 Forge 的 NetworkEvent.Context 包装成 NeoForge IPayloadContext 的形式。
 */
public final class IPayloadContext {

    private final Supplier<NetworkEvent.Context> context;

    public IPayloadContext(Supplier<NetworkEvent.Context> context) {
        this.context = context;
    }

    public void enqueueWork(Runnable work) {
        context.get().enqueueWork(work);
    }

    /**
     * 服务端返回发送者；客户端返回本地玩家。
     */
    public Player player() {
        Player sender = context.get().getSender();
        return sender != null ? sender : ClientPlayerAccess.localPlayer();
    }
}
