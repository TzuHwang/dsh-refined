package com.jasoncian.millenaire_rewrite.entity.ai.work;

import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 工作目标的公共工具：村民的家（自家建筑）、工作点附近可站立的位置。
 */
public final class WorkSites {

    private WorkSites() {
    }

    /**
     * 村民的家：占地包含其睡觉位置的建筑
     */
    @Nullable
    public static VillageGrowth.PlacedBuilding home(MillVillager villager) {
        BlockPos house = villager.getHousePos();
        Village village = villager.getHomeVillage();
        if (house == null || village == null) {
            return null;
        }
        return village.getGrowth().getBuildingAt(house);
    }

    /**
     * 自家建筑中满足条件、离村民最近的点位
     */
    @Nullable
    public static BlockPos nearest(MillVillager villager, List<BlockPos> points, Predicate<BlockPos> condition) {
        return points.stream()
            .filter(condition)
            .min(Comparator.comparingDouble(p -> p.distSqr(villager.blockPosition())))
            .orElse(null);
    }

    /**
     * 工作点旁边可以站立的位置（脚下实心、身体两格为空）；找不到时返回上方一格
     */
    public static BlockPos standNear(Level level, BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && dy <= 0) {
                        continue;
                    }
                    BlockPos p = target.offset(dx, dy, dz);
                    if (canStand(level, p)) {
                        double d = Math.abs(dx) + Math.abs(dz) + Math.abs(dy) * 0.5;
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = p;
                        }
                    }
                }
            }
        }
        return best != null ? best : target.above();
    }

    private static boolean canStand(Level level, BlockPos feet) {
        return level.getBlockState(feet.below()).isSolid()
            && !level.getBlockState(feet).isSolid()
            && !level.getBlockState(feet.above()).isSolid();
    }
}
