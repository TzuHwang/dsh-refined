package com.jasoncian.millenaire_rewrite.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * 村民导航：关着的栅栏门和木门一样可以通过（由 DoorOpener 开关）。
 * 原版把关着的栅栏门当作栅栏，伐木工进不了围起来的树林、农夫进不了围栏。
 */
public class VillagerNavigation extends GroundPathNavigation {

    public VillagerNavigation(Mob mob, Level level) {
        super(mob, level);
        setCanOpenDoors(true);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new GateNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    private static class GateNodeEvaluator extends WalkNodeEvaluator {
        @Override
        public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (canOpenDoors() && state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
                return BlockPathTypes.WALKABLE_DOOR;
            }
            return super.getBlockPathType(level, x, y, z);
        }
    }
}
