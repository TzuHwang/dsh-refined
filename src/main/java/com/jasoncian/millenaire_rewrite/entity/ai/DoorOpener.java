package com.jasoncian.millenaire_rewrite.entity.ai;

import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 村民开关门和栅栏门。原版 OpenDoorGoal 要求生物先撞上门才会触发，村民常在门前停下而卡住；
 * 这里在移动时直接检查脚下和路径上接下来的节点，靠近关着的木门就打开，走过后再关上。
 */
public final class DoorOpener {

    /** 离门多近（平方距离）时开门，与原版 DoorInteractGoal 相同 */
    private static final double OPEN_DISTANCE_SQR = 2.25;
    /** 开门后至少保持多久，并且村民离开 2 格以上才关门 */
    private static final int CLOSE_DELAY = 30;
    /** 向门洞中线靠拢的最大速度（格/tick） */
    private static final double ALIGN_SPEED = 0.08;

    private final MillVillager villager;
    /** 打开的门 -> 打开时的游戏时间 */
    private final Map<BlockPos, Long> opened = new HashMap<>();

    public DoorOpener(MillVillager villager) {
        this.villager = villager;
    }

    public void tick() {
        Level level = villager.level();
        long now = level.getGameTime();
        tryOpen(level, villager.blockPosition(), now);
        Path path = villager.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            int end = Math.min(path.getNextNodeIndex() + 2, path.getNodeCount());
            for (int i = path.getNextNodeIndex(); i < end; i++) {
                Node node = path.getNode(i);
                tryOpen(level, new BlockPos(node.x, node.y, node.z), now);
            }
            alignThroughDoorway(level, path.getNextNode().asBlockPos());
        }
        closePassedDoors(level, now);
    }

    /**
     * 穿过门或栅栏门时向门洞中线靠拢：村民偏离中线时会卡在打开的门板边上，原版移动不会自己修正
     */
    private void alignThroughDoorway(Level level, BlockPos next) {
        BlockPos here = villager.blockPosition();
        BlockPos doorway = isDoorway(level.getBlockState(here)) ? here
            : isDoorway(level.getBlockState(next)) ? next : null;
        if (doorway == null || villager.distanceToSqr(doorway.getX() + 0.5, doorway.getY(), doorway.getZ() + 0.5) > 4.0) {
            return;
        }
        double dx = doorway.getX() + 0.5 - villager.getX();
        double dz = doorway.getZ() + 0.5 - villager.getZ();
        // 前进方向是离门洞较远的那个轴，只修正另一个轴
        Vec3 nudge = Math.abs(dx) >= Math.abs(dz)
            ? new Vec3(0, 0, Mth.clamp(dz * 0.5, -ALIGN_SPEED, ALIGN_SPEED))
            : new Vec3(Mth.clamp(dx * 0.5, -ALIGN_SPEED, ALIGN_SPEED), 0, 0);
        if (nudge.lengthSqr() > 1.0E-4) {
            villager.setDeltaMovement(villager.getDeltaMovement().add(nudge));
        }
    }

    private static boolean isDoorway(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock;
    }

    private void tryOpen(Level level, BlockPos pos, long now) {
        if (villager.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) > OPEN_DISTANCE_SQR) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door && DoorBlock.isWoodenDoor(state) && !door.isOpen(state)) {
            door.setOpen(villager, level, state, pos, true);
            opened.put(pos.immutable(), now);
        } else if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            setGateOpen(level, state, pos, true);
            opened.put(pos.immutable(), now);
        }
    }

    private void setGateOpen(Level level, BlockState state, BlockPos pos, boolean open) {
        level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, open), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
        level.playSound(null, pos, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
            SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
        level.gameEvent(villager, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
    }

    private void closePassedDoors(Level level, long now) {
        Iterator<Map.Entry<BlockPos, Long>> it = opened.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            BlockPos pos = entry.getKey();
            if (now - entry.getValue() < CLOSE_DELAY || villager.blockPosition().distSqr(pos) < 4) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock door && door.isOpen(state)) {
                door.setOpen(villager, level, state, pos, false);
            } else if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN)) {
                setGateOpen(level, state, pos, false);
            }
            it.remove();
        }
    }
}
