package com.jasoncian.millenaire_rewrite.network.compat;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Forge 1.20.1 下对 1.20.5+ 原版 StreamCodec 的最小移植，
 * 让从 1.21 版反向移植的网络包可以保持原有写法。
 */
public interface StreamCodec<B, T> {

    T decode(B buf);

    void encode(B buf, T value);

    static <B, C, T1, T2> StreamCodec<B, C> composite(
        StreamCodec<? super B, T1> codec1, Function<C, T1> getter1,
        StreamCodec<? super B, T2> codec2, Function<C, T2> getter2,
        BiFunction<T1, T2, C> factory
    ) {
        return new StreamCodec<>() {
            @Override
            public C decode(B buf) {
                T1 v1 = codec1.decode(buf);
                T2 v2 = codec2.decode(buf);
                return factory.apply(v1, v2);
            }

            @Override
            public void encode(B buf, C value) {
                codec1.encode(buf, getter1.apply(value));
                codec2.encode(buf, getter2.apply(value));
            }
        };
    }
}
