package com.jasoncian.millenaire_rewrite.entity.ai.work;

import com.jasoncian.millenaire_rewrite.content.Crops;
import com.jasoncian.millenaire_rewrite.content.WorkGoalConfigs;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 收成自家耕作点上成熟的作物（原版 genericharvesting，如 harvestwheathome），并立即补种。
 */
public class GoalHarvest extends WorkGoal {

    private final WorkGoalConfigs.WorkGoalConfig config;
    @Nullable
    private final String soilType;

    public GoalHarvest(WorkGoalConfigs.WorkGoalConfig config) {
        super(config.name(), config.priority(), 20);
        this.config = config;
        this.soilType = Crops.soilForCropType(config.cropType());
    }

    @Nullable
    @Override
    protected BlockPos findTarget(MillVillager villager) {
        VillageGrowth.PlacedBuilding home = WorkSites.home(villager);
        if (home == null || soilType == null) {
            return null;
        }
        Level level = villager.level();
        BlockPos soil = WorkSites.nearest(villager, home.points(soilType), p -> {
            if (!reachable(villager, p.above())) {
                return false;
            }
            BlockState crop = level.getBlockState(p.above());
            return crop.getBlock() instanceof CropBlock cb && cb.isMaxAge(crop);
        });
        return soil == null ? null : soil.above();
    }

    @Override
    protected void work(MillVillager villager, BlockPos cropPos) {
        Level level = villager.level();
        BlockState crop = level.getBlockState(cropPos);
        if (!(crop.getBlock() instanceof CropBlock cb) || !cb.isMaxAge(crop)) {
            return;
        }
        level.levelEvent(2001, cropPos, Block.getId(crop));
        for (WorkGoalConfigs.Drop drop : config.harvestItems()) {
            if (villager.getRandom().nextInt(100) < drop.amount()) {
                villager.addToInventory(drop.item(), 1);
            }
        }
        // 补种；耕地若已变回泥土则重新翻耕
        BlockPos soil = cropPos.below();
        if (!level.getBlockState(soil).is(Blocks.FARMLAND)) {
            level.setBlock(soil, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), 3);
        }
        level.setBlock(cropPos, cb.getStateForAge(0), 3);
    }
}
