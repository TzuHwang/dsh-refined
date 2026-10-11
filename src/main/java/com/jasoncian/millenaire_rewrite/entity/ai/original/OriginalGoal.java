package com.jasoncian.millenaire_rewrite.entity.ai.original;

import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.MillGoal;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 原版 Millénaire 目标（Goal）的共同部分：
 * 到达目的地后持续 actionDuration 个 tick 再执行动作；白天/夜间限制在进行中也会检查（原版 isStillValid）；
 * 休闲目标在有非休闲目标可做时会被打断。
 */
public abstract class OriginalGoal extends MillGoal {

    /** 到达后要等多久才执行动作（tick） */
    protected int duration = 0;
    private int ticksAtDestination;

    protected OriginalGoal(String key) {
        super(key);
    }

    @Override
    public void onAccept(MillVillager villager) {
        ticksAtDestination = 0;
    }

    @Override
    public boolean isStillValid(MillVillager villager) {
        return checkDayNightRestrictions(villager) && isStillValidSpecific(villager);
    }

    protected boolean isStillValidSpecific(MillVillager villager) {
        return true;
    }

    @Override
    public final boolean performAction(MillVillager villager) {
        if (ticksAtDestination++ < duration) {
            return false;
        }
        return act(villager);
    }

    /**
     * 到达并等够时间后的动作
     *
     * @return true 表示目标完成
     */
    protected abstract boolean act(MillVillager villager);

    // ================ 村庄与建筑 ================

    @Nullable
    protected static Village village(MillVillager villager) {
        return villager.getHomeVillage();
    }

    /** 村民的家（占地包含家位置的建筑） */
    @Nullable
    protected static VillageGrowth.PlacedBuilding house(MillVillager villager) {
        Village village = village(villager);
        BlockPos home = villager.getHousePos();
        return village == null || home == null ? null : village.getGrowth().getBuildingAt(home);
    }

    @Nullable
    protected static VillageGrowth.PlacedBuilding townHall(MillVillager villager) {
        Village village = village(villager);
        if (village == null) {
            return null;
        }
        return village.getGrowth().getBuildings().stream().filter(b -> b.townHall).findFirst().orElse(null);
    }

    /**
     * 建筑的某种点位（如 sleepingPos、leisurePos），没有时返回建筑原点
     */
    protected static BlockPos pointOf(VillageGrowth.PlacedBuilding building, String type) {
        List<BlockPos> points = building.points(type);
        return points.isEmpty() ? building.origin : points.get(0);
    }
}
