package com.jasoncian.millenaire_rewrite.network.compat;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 原版 ByteBufCodecs 中本模组用到的部分。
 */
public final class ByteBufCodecs {

    public static final StreamCodec<FriendlyByteBuf, String> STRING_UTF8 = new StreamCodec<>() {
        @Override
        public String decode(FriendlyByteBuf buf) {
            return buf.readUtf();
        }

        @Override
        public void encode(FriendlyByteBuf buf, String value) {
            buf.writeUtf(value);
        }
    };

    private ByteBufCodecs() {
    }
}
