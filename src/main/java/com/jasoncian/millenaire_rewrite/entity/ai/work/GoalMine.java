package com.jasoncian.millenaire_rewrite.entity.ai.work;

import com.jasoncian.millenaire_rewrite.content.Crops;
import com.jasoncian.millenaire_rewrite.content.WorkGoalConfigs;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 在自家采石场的资源点采矿（原版 genericmining，如 minestone）。
 * 资源点视为取之不尽：方块保留原样，只产出资源，避免采石场越挖越深。
 */
public class GoalMine extends WorkGoal {

    private final WorkGoalConfigs.WorkGoalConfig config;
    @Nullable
    private final String sourcePoint;
    private final Block sourceBlock;

    public GoalMine(WorkGoalConfigs.WorkGoalConfig config) {
        super(config.name(), config.priority(), 40);
        this.config = config;
        this.sourcePoint = Crops.sourcePointFor(config.sourceBlock());
        String id = "minecraft:snow_layer".equals(config.sourceBlock()) ? "minecraft:snow_block" : config.sourceBlock();
        this.sourceBlock = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(new ResourceLocation(id));
    }

    @Nullable
    @Override
    protected BlockPos findTarget(MillVillager villager) {
        VillageGrowth.PlacedBuilding home = WorkSites.home(villager);
        if (home == null || sourcePoint == null || sourceBlock == Blocks.AIR) {
            return null;
        }
        Level level = villager.level();
        return WorkSites.nearest(villager, home.points(sourcePoint),
            p -> reachable(villager, p) && level.getBlockState(p).is(sourceBlock));
    }

    @Override
    protected void work(MillVillager villager, BlockPos source) {
        Level level = villager.level();
        BlockState state = level.getBlockState(source);
        if (!state.is(sourceBlock)) {
            return;
        }
        level.levelEvent(2001, source, Block.getId(state));
        if (config.loot().isEmpty()) {
            Item item = sourceBlock.asItem();
            villager.addToInventory(item, 1);
        } else {
            for (WorkGoalConfigs.Drop drop : config.loot()) {
                villager.addToInventory(drop.item(), drop.amount());
            }
        }
    }
}
