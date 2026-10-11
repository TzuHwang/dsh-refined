package com.jasoncian.millenaire_rewrite.economy;

import net.minecraft.world.entity.player.Player;

/**
 * 测试用：重新计算交易清单（正式流程在打开界面时计算）
 */
public final class VillageMerchantAccess {

    private VillageMerchantAccess() {
    }

    public static void rebuild(VillageMerchant merchant, Player player) {
        merchant.rebuildOffers(player);
    }
}
