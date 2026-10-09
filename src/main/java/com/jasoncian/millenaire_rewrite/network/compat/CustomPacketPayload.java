package com.jasoncian.millenaire_rewrite.network.compat;

import net.minecraft.resources.ResourceLocation;

/**
 * 原版 1.20.5+ CustomPacketPayload 的替代：只保留包类型标识。
 */
public interface CustomPacketPayload {

    Type<? extends CustomPacketPayload> type();

    record Type<T extends CustomPacketPayload>(ResourceLocation id) {
    }
}
