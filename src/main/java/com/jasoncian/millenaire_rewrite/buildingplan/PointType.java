package com.jasoncian.millenaire_rewrite.buildingplan;

import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 建筑图纸中一个像素颜色代表的内容：要么是具体方块，要么是特殊标记
 * （空气、保留地面、箱子、睡觉位置、农田等）。
 *
 * @param label      blocklist.txt 中的名称
 * @param special    特殊标记类型；普通方块为 null
 * @param state      要放置的方块；特殊标记为 null
 * @param secondStep 是否需要在第二轮放置（火把、门、告示牌等需要依附其他方块）
 */
public record PointType(String label, @Nullable String special, @Nullable BlockState state, boolean secondStep) {

    public boolean isType(String type) {
        return type.equalsIgnoreCase(special);
    }

    public boolean isSubType(String prefix) {
        return special != null && special.startsWith(prefix);
    }
}
