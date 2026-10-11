package com.jasoncian.millenaire_rewrite.economy;

import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 建筑的库存：建筑里所有上锁箱子（图纸的 lockedchest / mainchest 点位）的内容。
 * 原版 Building.countGoods / takeGoods / storeGoods / nbGoodAvailable。
 */
public final class BuildingStock {

    private BuildingStock() {
    }

    public static List<Container> chests(ServerLevel level, VillageGrowth.PlacedBuilding building) {
        List<Container> chests = new ArrayList<>();
        List<BlockPos> points = new ArrayList<>(building.points("mainchest"));
        points.addAll(building.points("lockedchest"));
        for (BlockPos pos : points) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof Container container) {
                chests.add(container);
            }
        }
        return chests;
    }

    public static int count(ServerLevel level, VillageGrowth.PlacedBuilding building, Goods.Good good) {
        int count = 0;
        for (Container chest : chests(level, building)) {
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (good.matches(stack)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    /**
     * 从箱子取出最多 n 个
     *
     * @return 实际取出的数量
     */
    public static int take(ServerLevel level, VillageGrowth.PlacedBuilding building, Goods.Good good, int n) {
        int taken = 0;
        for (Container chest : chests(level, building)) {
            for (int i = 0; i < chest.getContainerSize() && taken < n; i++) {
                ItemStack stack = chest.getItem(i);
                if (good.matches(stack)) {
                    int t = Math.min(stack.getCount(), n - taken);
                    stack.shrink(t);
                    taken += t;
                    chest.setChanged();
                }
            }
        }
        return taken;
    }

    /**
     * 把 n 个 good 放进箱子（先叠到同类上，再放空格）
     *
     * @return 实际放进去的数量（箱子满了时少于 n）
     */
    public static int store(ServerLevel level, VillageGrowth.PlacedBuilding building, Goods.Good good, int n) {
        return store(chests(level, building), good.create(1), n);
    }

    public static int store(ServerLevel level, VillageGrowth.PlacedBuilding building, ItemStack template, int n) {
        return store(chests(level, building), template, n);
    }

    private static int store(List<Container> chests, ItemStack template, int n) {
        int stored = 0;
        int max = template.getMaxStackSize();
        // 先叠到已有的同类物品上
        for (Container chest : chests) {
            for (int i = 0; i < chest.getContainerSize() && stored < n; i++) {
                ItemStack stack = chest.getItem(i);
                if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, template) && stack.getCount() < max) {
                    int add = Math.min(max - stack.getCount(), n - stored);
                    stack.grow(add);
                    stored += add;
                    chest.setChanged();
                }
            }
        }
        for (Container chest : chests) {
            for (int i = 0; i < chest.getContainerSize() && stored < n; i++) {
                if (chest.getItem(i).isEmpty()) {
                    int add = Math.min(max, n - stored);
                    chest.setItem(i, template.copyWithCount(add));
                    stored += add;
                    chest.setChanged();
                }
            }
        }
        return stored;
    }

    /**
     * 可以卖给玩家的数量（原版 nbGoodAvailable）：市政厅或经营这种商品的商店要留下保留数量
     *
     * @param keepReserve 是否扣除保留数量
     */
    public static int available(ServerLevel level, VillageGrowth.PlacedBuilding building, CultureEconomy.TradeGood good,
                                boolean keepReserve) {
        int count = count(level, building, good.good());
        if (keepReserve) {
            count -= good.reserved();
        }
        return Math.max(count, 0);
    }
}
