package com.jasoncian.millenaire_rewrite.buildingplan;

import com.jasoncian.millenaire_rewrite.core.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按图纸在世界中立即放置一栋建筑（村庄生成时使用）。
 *
 * 坐标和朝向换算沿用原版 BuildingPlan.adjustForOrientation / attemptAutoRotation：
 * 图纸第 floor 层位于 origin.y + floor + startLevel；
 * 朝向每 +1，方块的 facing 逆时针旋转 90°。
 */
public final class BuildingPlacer {

    /** 只通知客户端，不触发邻近方块的物理更新（避免沙子掉落、火把在施工中掉落） */
    private static final int FLAGS = Block.UPDATE_CLIENTS;
    /** 门：另外跳过形状更新，避免上下两格放置时互相覆盖属性 */
    private static final int DOOR_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private BuildingPlacer() {
    }

    /**
     * 放置结果：记录图纸中的功能点位，供后续村民 AI 使用
     */
    public static final class Result {
        public final Map<String, List<BlockPos>> specialPoints = new HashMap<>();
        @Nullable
        public BlockPos mainChest;
        public int blocksPlaced;
        /** 本次放置过的位置，用于最后统一刷新形状 */
        final List<BlockPos> placed = new ArrayList<>();
        /** 图纸中门的预定状态，延后到最后统一放置 */
        final Map<BlockPos, BlockState> plannedDoors = new HashMap<>();

        void addPoint(String type, BlockPos pos) {
            specialPoints.computeIfAbsent(type, k -> new ArrayList<>()).add(pos.immutable());
        }
    }

    /**
     * @param origin      建筑中心，y 为地面上第一格空气的高度
     * @param orientation 放置方向 0-3（会再加上图纸自身的 buildingOrientation）
     */
    public static Result place(ServerLevel level, BuildingPlan plan, BlockPos origin, int orientation) {
        int finalOrientation = (orientation + plan.buildingOrientation) % 4;
        Rotation rotation = rotationFor(finalOrientation);
        Result result = new Result();

        // 第一轮：结构方块、空气、地面；第二轮：需要依附的方块（火把、门、告示牌等）
        for (int pass = 0; pass < 2; pass++) {
            for (int floor = 0; floor < plan.nbFloors; floor++) {
                for (int l = 0; l < plan.length; l++) {
                    for (int w = 0; w < plan.width; w++) {
                        PointType pt = plan.plan[floor][l][w];
                        BlockPos pos = worldPos(origin, floor + plan.startLevel,
                            l - plan.lengthOffset(), w - plan.widthOffset(), finalOrientation);
                        boolean underground = floor + plan.startLevel < 0;
                        if (pt.state() != null) {
                            if (pt.secondStep() == (pass == 1)) {
                                set(level, pos, pt.state().rotate(rotation), result);
                            }
                        } else if (pass == 0) {
                            placeSpecialFirstPass(level, plan, pt, pos, underground, rotation, result);
                        } else {
                            placeSpecialSecondPass(level, pt, pos, rotation, result);
                        }
                    }
                }
            }
        }
        completeDoors(level, result);
        settleShapes(level, result);
        return result;
    }

    /**
     * 原版 adjustForOrientation：把图纸内偏移换算为世界坐标
     */
    static BlockPos worldPos(BlockPos origin, int dy, int xOffset, int zOffset, int orientation) {
        int x = origin.getX();
        int z = origin.getZ();
        return switch (orientation) {
            case 1 -> new BlockPos(x + zOffset, origin.getY() + dy, z - xOffset - 1);
            case 2 -> new BlockPos(x - xOffset - 1, origin.getY() + dy, z - zOffset - 1);
            case 3 -> new BlockPos(x - zOffset - 1, origin.getY() + dy, z + xOffset);
            default -> new BlockPos(x + xOffset, origin.getY() + dy, z + zOffset);
        };
    }

    private static Rotation rotationFor(int orientation) {
        return switch (orientation) {
            case 1 -> Rotation.COUNTERCLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state, Result result) {
        if (state.getBlock() instanceof DoorBlock) {
            // 门的上下两格放置时会互相同步属性，污染图纸数据，所以先记录、最后统一放置
            result.plannedDoors.put(pos.immutable(), state);
            return;
        }
        if (level.getBlockState(pos) != state) {
            level.setBlock(pos, state, FLAGS);
            result.blocksPlaced++;
            result.placed.add(pos.immutable());
        }
    }

    /**
     * 栅栏、墙、玻璃板、楼梯转角等的连接形状只在邻居变化时重新计算；
     * 先放的方块不会因为后放的邻居而刷新（反之亦然），所以建造完成后统一按邻居重算一次。
     */
    private static void settleShapes(ServerLevel level, Result result) {
        for (BlockPos pos : result.placed) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof DoorBlock) {
                continue;
            }
            BlockState updated = Block.updateFromNeighbourShapes(state, level, pos);
            if (updated != state) {
                level.setBlock(pos, updated, FLAGS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    // ================ 门 ================

    /**
     * 1.12 的门把朝向存在下半部、铰链存在上半部（图纸中的 DoorUpper / DoorUpperInv），
     * 而 1.20 要求上下两格属性一致。这里以下半部的朝向和上半部的铰链为准同步两格；
     * 图纸没有上半部时补上，并按相邻的门推断双开门铰链。
     */
    private static void completeDoors(ServerLevel level, Result result) {
        Map<BlockPos, BlockState> planned = result.plannedDoors;
        for (Map.Entry<BlockPos, BlockState> entry : planned.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                continue;
            }
            Direction facing = state.getValue(DoorBlock.FACING);
            BlockState upper = planned.get(pos.above());
            DoorHingeSide hinge;
            if (upper != null && upper.getBlock() == state.getBlock()
                && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                hinge = upper.getValue(DoorBlock.HINGE);
            } else if (isLowerDoor(planned, pos.relative(facing.getClockWise()), facing)) {
                hinge = DoorHingeSide.LEFT;
            } else if (isLowerDoor(planned, pos.relative(facing.getCounterClockWise()), facing)) {
                hinge = DoorHingeSide.RIGHT;
            } else {
                hinge = DoorHingeSide.LEFT;
            }
            BlockState lower = state.setValue(DoorBlock.HINGE, hinge);
            level.setBlock(pos, lower, DOOR_FLAGS);
            level.setBlock(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), DOOR_FLAGS);
            result.blocksPlaced += 2;
        }
    }

    private static boolean isLowerDoor(Map<BlockPos, BlockState> planned, BlockPos pos, Direction facing) {
        BlockState state = planned.get(pos);
        return state != null && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
            && state.getValue(DoorBlock.FACING) == facing;
    }

    // ================ 特殊标记 ================

    private static void placeSpecialFirstPass(ServerLevel level, BuildingPlan plan, PointType pt, BlockPos pos,
                                              boolean underground, Rotation rotation, Result result) {
        String type = pt.special();
        if (type == null) {
            return;
        }
        switch (type) {
            case "empty" -> {
                if (plan.level == 0) {
                    set(level, pos, Blocks.AIR.defaultBlockState(), result);
                }
            }
            case "preserveground" -> {
                if (!level.getBlockState(pos).isSolid()) {
                    set(level, pos, (underground ? Blocks.DIRT : Blocks.GRASS_BLOCK).defaultBlockState(), result);
                }
            }
            case "allbuttrees", "stall", "brickspot", "healingspot", "fishingspot",
                 "plainSignGuess", "signwallGuess", "ladderGuess" -> {
                // 暂不处理
            }
            case "grass", "soil", "ricesoil", "turmericsoil", "sugarcanesoil", "potatosoil", "vinesoil",
                 "maizesoil", "cacaospot", "carrotsoil", "flowersoil", "cottonsoil", "freegrass_block" ->
                set(level, pos, Blocks.GRASS_BLOCK.defaultBlockState(), result);
            case "netherwartsoil" -> set(level, pos, Blocks.SOUL_SAND.defaultBlockState(), result);
            case "snailsoilblock" -> set(level, pos, Blocks.DIRT.defaultBlockState(), result);
            case "furnaceGuess" -> set(level, pos, Blocks.FURNACE.defaultBlockState(), result);
            case "silkwormblock" -> set(level, pos, ModBlocks.SILKWORM.get().defaultBlockState(), result);
            case "dispenserunknownpowder" -> set(level, pos, Blocks.DISPENSER.defaultBlockState(), result);
            case "brewingstand" -> set(level, pos, Blocks.BREWING_STAND.defaultBlockState(), result);
            default -> {
                BlockState resource = resourceBlock(type);
                if (resource != null) {
                    set(level, pos, resource, result);
                } else if (type.startsWith("mainchest") || type.startsWith("lockedchest")) {
                    placeChest(level, type, pos, rotation, result);
                } else if (type.endsWith("spawn") && !type.endsWith("treespawn")) {
                    result.addPoint(type, pos);
                } else {
                    // sleepingPos、sellingPos 等功能点位，以及暂未实现的装饰实体
                    result.addPoint(type, pos);
                }
            }
        }
    }

    private static void placeSpecialSecondPass(ServerLevel level, PointType pt, BlockPos pos, Rotation rotation,
                                               Result result) {
        String type = pt.special();
        if (type == null) {
            return;
        }
        if (type.equals("torchGuess")) {
            set(level, pos, Blocks.TORCH.defaultBlockState(), result);
        } else if (type.endsWith("treespawn") || type.endsWith("spawn") && saplingFor(type) != null) {
            BlockState sapling = saplingFor(type);
            if (sapling != null) {
                set(level, pos, sapling, result);
            }
        } else if (type.contains("BannerWall")) {
            Direction facing = Direction.byName(type.substring(type.indexOf("Wall") + 4).toLowerCase());
            if (facing != null) {
                Block banner = type.startsWith("village") ? Blocks.WHITE_WALL_BANNER : Blocks.BLUE_WALL_BANNER;
                set(level, pos, banner.defaultBlockState()
                    .setValue(WallBannerBlock.FACING, facing).rotate(rotation), result);
            }
        } else if (type.contains("BannerStanding")) {
            int rot = Integer.parseInt(type.substring(type.indexOf("Standing") + 8));
            Block banner = type.startsWith("village") ? Blocks.WHITE_BANNER : Blocks.BLUE_BANNER;
            set(level, pos, banner.defaultBlockState().setValue(BannerBlock.ROTATION, rot).rotate(rotation), result);
        }
    }

    private static void placeChest(ServerLevel level, String type, BlockPos pos, Rotation rotation, Result result) {
        Direction facing;
        if (type.endsWith("Top")) {
            facing = Direction.EAST;
        } else if (type.endsWith("Bottom")) {
            facing = Direction.WEST;
        } else if (type.endsWith("Left")) {
            facing = Direction.NORTH;
        } else if (type.endsWith("Right")) {
            facing = Direction.SOUTH;
        } else {
            facing = Direction.NORTH;
        }
        boolean main = type.startsWith("mainchest");
        BlockState chest = main
            ? Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing)
            : ModBlocks.LOCKED_CHEST.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        set(level, pos, chest.rotate(rotation), result);
        if (main) {
            result.mainChest = pos.immutable();
        }
        result.addPoint(main ? "mainchest" : "lockedchest", pos);
    }

    /**
     * 采石场资源点和"免费"方块
     */
    @Nullable
    private static BlockState resourceBlock(String type) {
        Block block = switch (type) {
            case "stonesource", "freestone" -> Blocks.STONE;
            case "sandsource", "freesand" -> Blocks.SAND;
            case "sandstonesource", "freesandstone" -> Blocks.SANDSTONE;
            case "claysource" -> Blocks.CLAY;
            case "gravelsource", "freegravel" -> Blocks.GRAVEL;
            case "granitesource" -> Blocks.GRANITE;
            case "dioritesource" -> Blocks.DIORITE;
            case "andesitesource" -> Blocks.ANDESITE;
            case "snowsource" -> Blocks.SNOW_BLOCK;
            case "icesource" -> Blocks.ICE;
            case "redsandstonesource" -> Blocks.RED_SANDSTONE;
            case "quartzsource" -> Blocks.QUARTZ_BLOCK;
            case "freewool" -> Blocks.WHITE_WOOL;
            case "freecobblestone" -> Blocks.COBBLESTONE;
            case "freestonebrick" -> Blocks.STONE_BRICKS;
            case "freepaintedbrick" -> Blocks.WHITE_TERRACOTTA;
            default -> null;
        };
        return block == null ? null : block.defaultBlockState();
    }

    @Nullable
    private static BlockState saplingFor(String type) {
        Block block = switch (type) {
            case "oakspawn", "appletreespawn", "olivetreespawn", "pistachiotreespawn" -> Blocks.OAK_SAPLING;
            case "pinespawn" -> Blocks.SPRUCE_SAPLING;
            case "birchspawn" -> Blocks.BIRCH_SAPLING;
            case "junglespawn" -> Blocks.JUNGLE_SAPLING;
            case "acaciaspawn" -> Blocks.ACACIA_SAPLING;
            case "darkoakspawn" -> Blocks.DARK_OAK_SAPLING;
            case "cherrytreespawn", "sakuratreespawn" -> Blocks.CHERRY_SAPLING;
            default -> null;
        };
        return block == null ? null : block.defaultBlockState();
    }

    // ================ 动物 ================

    /**
     * 在图纸标记的位置生成农场动物
     */
    public static void spawnAnimals(ServerLevel level, Result result) {
        result.specialPoints.forEach((type, positions) -> {
            EntityType<? extends Mob> entityType = switch (type) {
                case "cowspawn" -> EntityType.COW;
                case "pigspawn" -> EntityType.PIG;
                case "sheepspawn" -> EntityType.SHEEP;
                case "chickenspawn" -> EntityType.CHICKEN;
                case "wolfspawn" -> EntityType.WOLF;
                case "polarbearspawn" -> EntityType.POLAR_BEAR;
                case "squidspawn" -> EntityType.SQUID;
                default -> null;
            };
            if (entityType == null) {
                return;
            }
            for (BlockPos pos : positions) {
                Mob mob = entityType.create(level);
                if (mob != null) {
                    mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360F, 0F);
                    mob.setPersistenceRequired();
                    mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.STRUCTURE, null, null);
                    level.addFreshEntity(mob);
                }
            }
        });
    }
}
