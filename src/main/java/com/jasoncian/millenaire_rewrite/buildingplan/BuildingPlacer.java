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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        final List<BlockPos> doors = new ArrayList<>();

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
            result.doors.add(pos.immutable());
        }
        if (level.getBlockState(pos) != state) {
            level.setBlock(pos, state, FLAGS);
            result.blocksPlaced++;
        }
    }

    // ================ 门 ================

    /**
     * 图纸只记录门的下半部（1.12 的门方块），这里补上上半部并决定铰链方向：
     * 并排同朝向的两扇门组成双开门，左边一扇铰链在左、右边一扇铰链在右。
     * 若图纸在相邻两层都画了门，则把上面那格视为上半部。
     */
    private static void completeDoors(ServerLevel level, Result result) {
        Set<BlockPos> doorSet = new HashSet<>(result.doors);
        result.doors.sort(Comparator.comparingInt(BlockPos::getY));
        for (BlockPos pos : result.doors) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock)) {
                continue;
            }
            BlockPos below = pos.below();
            if (doorSet.contains(below) && level.getBlockState(below).getBlock() == state.getBlock()) {
                continue;
            }
            Direction facing = state.getValue(DoorBlock.FACING);
            DoorHingeSide hinge = isDoor(level, pos.relative(facing.getClockWise()), facing)
                ? DoorHingeSide.LEFT
                : isDoor(level, pos.relative(facing.getCounterClockWise()), facing)
                    ? DoorHingeSide.RIGHT
                    : DoorHingeSide.LEFT;
            BlockState lower = state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.HINGE, hinge);
            level.setBlock(pos, lower, FLAGS);
            level.setBlock(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), FLAGS);
        }
    }

    private static boolean isDoor(ServerLevel level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.FACING) == facing;
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
