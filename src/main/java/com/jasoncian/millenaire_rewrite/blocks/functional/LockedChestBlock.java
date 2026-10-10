package com.jasoncian.millenaire_rewrite.blocks.functional;

import com.jasoncian.millenaire_rewrite.blockentity.LockedChestBlockEntity;
import com.jasoncian.millenaire_rewrite.core.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 锁定箱子方块 - 基于OldSource BlockLockedChest实现
 *
 * 锁定箱子是村庄存储系统的核心组件。
 * 与村庄建筑系统集成，提供基于权限的访问控制。
 *
 * 功能特性：
 * - 27槽位存储
 * - 支持双箱子连接
 * - 村庄权限系统集成
 * - 盖子开合动画
 * - 自动化支持（漏斗等，受权限限制）
 *
 * @author Based on OldSource BlockLockedChest
 * @version 1.0.0
 */
public class LockedChestBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 与原版箱子相同：并排、同朝向的两个箱子组成大箱子 */
    public static final EnumProperty<ChestType> TYPE = BlockStateProperties.CHEST_TYPE;

    protected static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 14.0, 15.0);

    public LockedChestBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(TYPE, ChestType.SINGLE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TYPE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockState state = this.defaultBlockState().setValue(FACING, facing);
        // 与旁边同朝向的单箱子相连（原版 ChestBlock 的规则）
        if (isSingleFacing(context.getLevel().getBlockState(context.getClickedPos().relative(facing.getClockWise())), facing)) {
            return state.setValue(TYPE, ChestType.LEFT);
        }
        if (isSingleFacing(context.getLevel().getBlockState(context.getClickedPos().relative(facing.getCounterClockWise())), facing)) {
            return state.setValue(TYPE, ChestType.RIGHT);
        }
        return state;
    }

    private boolean isSingleFacing(BlockState state, Direction facing) {
        return state.is(this) && state.getValue(TYPE) == ChestType.SINGLE && state.getValue(FACING) == facing;
    }

    /**
     * 大箱子另一半所在的方向（与原版 ChestBlock.getConnectedDirection 相同）
     */
    public static Direction connectedDirection(BlockState state) {
        Direction facing = state.getValue(FACING);
        return state.getValue(TYPE) == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LockedChestBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ?
            createTickerHelper(type, ModBlockEntities.LOCKED_CHEST.get(), LockedChestBlockEntity::clientTick) :
            null;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof LockedChestBlockEntity lockedChest) {
                // 检查权限
                if (lockedChest.isLockedFor(player)) {
                    // 向玩家发送锁定消息
                    player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("container.millenaire_rewrite.locked_chest.locked"),
                        true);
                    return InteractionResult.CONSUME;
                }

                net.minecraftforge.network.NetworkHooks.openScreen((ServerPlayer) player, lockedChest, pos);
                player.awardStat(Stats.OPEN_CHEST);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (stack.hasCustomHoverName()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof LockedChestBlockEntity lockedChest) {
                lockedChest.setCustomName(stack.getHoverName());
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof LockedChestBlockEntity lockedChest) {
                Containers.dropContents(level, pos, lockedChest);
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                   LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // 与原版 ChestBlock.updateShape 相同：另一半放下时连上，另一半被破坏时变回单箱子
        if (neighborState.is(this) && direction.getAxis().isHorizontal()) {
            ChestType neighborType = neighborState.getValue(TYPE);
            if (state.getValue(TYPE) == ChestType.SINGLE && neighborType != ChestType.SINGLE
                && state.getValue(FACING) == neighborState.getValue(FACING)
                && connectedDirection(neighborState) == direction.getOpposite()) {
                return state.setValue(TYPE, neighborType.getOpposite());
            }
        } else if (state.getValue(TYPE) != ChestType.SINGLE && connectedDirection(state) == direction) {
            return state.setValue(TYPE, ChestType.SINGLE);
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter getter, BlockPos pos, PathComputationType pathComputationType) {
        return false;
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromBlockEntity(
            level.getBlockEntity(pos));
    }

    /**
     * 跟随建筑朝向旋转（建造器用 BlockState.rotate 旋转图纸中的方块）
     */
    @Override
    public BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        BlockState rotated = state.rotate(mirror.getRotation(state.getValue(FACING)));
        // 与原版箱子相同：镜像后大箱子的左右半互换
        return mirror == net.minecraft.world.level.block.Mirror.NONE ? rotated
            : rotated.setValue(TYPE, rotated.getValue(TYPE).getOpposite());
    }
}
