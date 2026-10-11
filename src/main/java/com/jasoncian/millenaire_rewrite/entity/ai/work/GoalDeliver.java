package com.jasoncian.millenaire_rewrite.entity.ai.work;

import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.item.InvItem;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把背包里的产出送回自家建筑的箱子（原版 bringBackResourcesHome）。
 */
public class GoalDeliver extends WorkGoal {

    /** 背包里的物品达到这个数量才送货 */
    public static final int DELIVER_THRESHOLD = 16;

    public GoalDeliver() {
        super("bringbackresourceshome", 60, 10);
    }

    @Nullable
    @Override
    protected BlockPos findTarget(MillVillager villager) {
        int carried = villager.getInventoryCopy().values().stream().mapToInt(Integer::intValue).sum();
        if (carried < DELIVER_THRESHOLD) {
            return null;
        }
        VillageGrowth.PlacedBuilding home = WorkSites.home(villager);
        if (home == null) {
            return null;
        }
        List<BlockPos> chests = new ArrayList<>(home.points("lockedchest"));
        chests.addAll(home.points("mainchest"));
        return WorkSites.nearest(villager, chests,
            p -> reachable(villager, p) && villager.level().getBlockEntity(p) instanceof Container);
    }

    @Override
    protected void work(MillVillager villager, BlockPos chestPos) {
        BlockEntity be = villager.level().getBlockEntity(chestPos);
        if (!(be instanceof Container chest)) {
            return;
        }
        for (Map.Entry<InvItem, Integer> entry : villager.getInventoryCopy().entrySet()) {
            ItemStack stack = entry.getKey().toItemStack(entry.getValue());
            ItemStack remainder = HopperBlockEntity.addItem(null, chest, stack, null);
            int stored = entry.getValue() - remainder.getCount();
            if (stored > 0) {
                villager.takeFromInventory(entry.getKey(), stored);
            }
        }
        chest.setChanged();
    }
}
