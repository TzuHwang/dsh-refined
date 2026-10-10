package com.jasoncian.millenaire_rewrite.buildingplan;

import com.jasoncian.millenaire_rewrite.content.Crops;
import com.jasoncian.millenaire_rewrite.blocks.functional.LockedChestBlock;
import com.jasoncian.millenaire_rewrite.core.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按图纸在世界中放置一栋建筑。
 *
 * {@link #prepare} 先把图纸换算成按顺序执行的放置步骤（{@link Construction}），
 * 村庄生成时一次执行完（{@link #place}），村庄成长时每 tick 执行几步，形成逐格建造。
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

        void addPoint(String type, BlockPos pos) {
            specialPoints.computeIfAbsent(type, k -> new ArrayList<>()).add(pos.immutable());
        }
    }

    /** 地基最多向下填充的深度 */
    private static final int MAX_FOUNDATION_DEPTH = 24;
    /** 建筑上方最多向上清除的高度 */
    private static final int MAX_CLEAR_HEIGHT = 32;
    /** 建筑周围的地形过渡带宽度：清除树木，并把地面渐进地接回原地形 */
    public static final int SURROUND_MARGIN = 4;
    /** 过渡带每一列最多挖掉或填高的格数 */
    private static final int MAX_SURROUND_CHANGE = 12;

    /**
     * 一个放置步骤。onlyIfNotSolid 用于"保留地面"：只填补空洞，不覆盖原有地面；
     * foundation 为地基柱：从 pos 向下把空洞和水填实，直到碰到实心地面；
     * clearAbove 为清除柱：从 pos 向上把地形和树木清空，直到碰到空气。
     */
    private record Op(BlockPos pos, BlockState state, int flags, boolean onlyIfNotSolid, boolean foundation,
                      boolean clearAbove, int surround) {
        Op(BlockPos pos, BlockState state, int flags, boolean onlyIfNotSolid) {
            this(pos, state, flags, onlyIfNotSolid, false, false, 0);
        }
    }

    /**
     * 一栋建筑的施工计划：按顺序执行的放置步骤和施工进度
     */
    public static final class Construction {
        public final BuildingPlan plan;
        public final BlockPos origin;
        public final int orientation;
        public final Result result = new Result();

        private final List<Op> ops = new ArrayList<>();
        /** 建筑覆盖的所有非门位置，完工时统一刷新形状 */
        private final List<BlockPos> shaped = new ArrayList<>();
        /** 图纸中门的预定状态，最后统一换算成步骤 */
        private final Map<BlockPos, BlockState> plannedDoors = new HashMap<>();
        /** 图纸中上锁箱子的预定状态，配对成大箱子后再换算成步骤 */
        private final Map<BlockPos, BlockState> plannedChests = new HashMap<>();
        private int next;

        private Construction(BuildingPlan plan, BlockPos origin, int orientation) {
            this.plan = plan;
            this.origin = origin;
            this.orientation = orientation;
        }

        public int size() {
            return ops.size();
        }

        public int progress() {
            return next;
        }

        /** 从存档恢复施工进度 */
        public void skipTo(int index) {
            next = Math.min(index, ops.size());
        }

        public boolean isDone() {
            return next >= ops.size();
        }

        /**
         * 执行最多 max 个步骤。
         *
         * @param onlyLoaded 为 true 时遇到未加载的区块就暂停（村庄成长时不主动加载区块）
         * @param sound      是否播放放置方块的音效
         * @return 实际执行的步骤数
         */
        public int step(ServerLevel level, int max, boolean onlyLoaded, boolean sound) {
            int done = 0;
            while (done < max && next < ops.size()) {
                Op op = ops.get(next);
                if (onlyLoaded && !level.isLoaded(op.pos())) {
                    break;
                }
                apply(level, op, sound);
                next++;
                done++;
            }
            return done;
        }

        private void apply(ServerLevel level, Op op, boolean sound) {
            if (op.foundation()) {
                BlockPos.MutableBlockPos p = op.pos().mutable();
                for (int i = 0; i < MAX_FOUNDATION_DEPTH && p.getY() > level.getMinBuildHeight(); i++) {
                    BlockState below = level.getBlockState(p);
                    if (below.isSolid() && below.getFluidState().isEmpty()) {
                        break;
                    }
                    level.setBlock(p, op.state(), op.flags());
                    result.blocksPlaced++;
                    p.move(Direction.DOWN);
                }
                return;
            }
            if (op.clearAbove()) {
                // 整列向上扫描：树冠和建筑顶之间可能隔着空气
                BlockPos.MutableBlockPos p = op.pos().mutable();
                for (int i = 0; i < MAX_CLEAR_HEIGHT && p.getY() < level.getMaxBuildHeight(); i++) {
                    if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), op.flags());
                        result.blocksPlaced++;
                    }
                    p.move(Direction.UP);
                }
                return;
            }
            if (op.surround() > 0) {
                blendSurroundings(level, op);
                return;
            }
            BlockState current = level.getBlockState(op.pos());
            if (current == op.state() || op.onlyIfNotSolid() && current.isSolid()) {
                return;
            }
            level.setBlock(op.pos(), op.state(), op.flags());
            result.blocksPlaced++;
            if (sound && !op.state().isAir()) {
                SoundType type = op.state().getSoundType();
                level.playSound(null, op.pos(), type.getPlaceSound(), SoundSource.BLOCKS,
                    (type.getVolume() + 1.0F) / 4.0F, type.getPitch() * 0.8F);
            }
        }

        /**
         * 地形过渡带的一列：清除树木和杂草，再把地面高度从建筑地面渐进地接回原地形
         * （离建筑越近越接近建筑地面），形成自然的斜坡而不是断崖。
         * op.pos 的 y 为建筑地面（最上层地表方块）的高度，op.surround 为离占地的距离。
         */
        private void blendSurroundings(ServerLevel level, Op op) {
            int x = op.pos().getX();
            int z = op.pos().getZ();
            int groundY = op.pos().getY();
            // 清除树木、树叶和杂草
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int y = groundY - MAX_SURROUND_CHANGE; y <= groundY + MAX_CLEAR_HEIGHT; y++) {
                p.set(x, y, z);
                BlockState state = level.getBlockState(p);
                if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                    || !state.isAir() && state.canBeReplaced() && state.getFluidState().isEmpty()) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), op.flags());
                    result.blocksPlaced++;
                }
            }
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos surfacePos = new BlockPos(x, surfaceY, z);
            BlockState surface = level.getBlockState(surfacePos);
            if (!surface.getFluidState().isEmpty() || surface.is(BlockTags.ICE)) {
                return;
            }
            int target = groundY + (int) Math.round((surfaceY - groundY) * op.surround() / (SURROUND_MARGIN + 1.0));
            if (Math.abs(surfaceY - target) > MAX_SURROUND_CHANGE) {
                return;
            }
            BlockState top = surface.is(BlockTags.SAND) || surface.is(Blocks.SNOW_BLOCK) ? surface
                : Blocks.GRASS_BLOCK.defaultBlockState();
            if (surfaceY > target) {
                for (int y = surfaceY; y > target; y--) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), op.flags());
                }
                level.setBlock(new BlockPos(x, target, z), top, op.flags());
            } else if (surfaceY < target) {
                BlockState fill = top.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : top;
                for (int y = surfaceY; y < target; y++) {
                    level.setBlock(new BlockPos(x, y, z), fill, op.flags());
                }
                level.setBlock(new BlockPos(x, target, z), top, op.flags());
            }
            result.blocksPlaced += Math.abs(surfaceY - target) + 1;
        }

        /**
         * 完工：栅栏、墙、玻璃板、楼梯转角等的连接形状只在邻居变化时重新计算；
         * 先放的方块不会因为后放的邻居而刷新（反之亦然），所以完工后统一按邻居重算一次。
         */
        public void finish(ServerLevel level) {
            for (BlockPos pos : shaped) {
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || state.getBlock() instanceof DoorBlock) {
                    continue;
                }
                BlockState updated = Block.updateFromNeighbourShapes(state, level, pos);
                if (updated != state) {
                    level.setBlock(pos, updated, FLAGS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
            growTrees(level);
        }

        /**
         * 原版生成树林时种树点上直接是长好的树：让种树点上的树苗立即长成树，伐木工才有工作
         */
        private void growTrees(ServerLevel level) {
            result.specialPoints.forEach((type, positions) -> {
                if (!type.endsWith("spawn")) {
                    return;
                }
                for (BlockPos pos : positions) {
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof SaplingBlock sapling) {
                        // 树苗分两个阶段：第一次推进到 stage 1，第二次才生成树
                        sapling.advanceTree(level, pos, state, level.random);
                        BlockState staged = level.getBlockState(pos);
                        if (staged.getBlock() instanceof SaplingBlock) {
                            sapling.advanceTree(level, pos, staged, level.random);
                        }
                    }
                }
            });
        }

        private void set(BlockPos pos, BlockState state) {
            add(pos, state, false);
        }

        private void add(BlockPos pos, BlockState state, boolean onlyIfNotSolid) {
            if (state.getBlock() instanceof DoorBlock) {
                // 门的上下两格放置时会互相同步属性，污染图纸数据，所以先记录、最后统一换算
                plannedDoors.put(pos.immutable(), state);
                return;
            }
            BlockPos p = pos.immutable();
            ops.add(new Op(p, state, FLAGS, onlyIfNotSolid));
            shaped.add(p);
        }
    }

    // ================ 入口 ================

    /**
     * 立即放置整栋建筑（村庄生成时使用）
     *
     * @param origin      建筑中心，y 为地面上第一格空气的高度
     * @param orientation 放置方向 0-3（会再加上图纸自身的 buildingOrientation）
     */
    public static Result place(ServerLevel level, BuildingPlan plan, BlockPos origin, int orientation) {
        Construction construction = prepare(plan, origin, orientation);
        construction.step(level, Integer.MAX_VALUE, false, false);
        construction.finish(level);
        return construction.result;
    }

    /**
     * 把图纸换算为施工步骤（不读取世界，可在任何时候调用并从存档恢复进度）
     */
    public static Construction prepare(BuildingPlan plan, BlockPos origin, int orientation) {
        return prepare(plan, origin, orientation, column -> false);
    }

    /**
     * @param protectedColumn 地形过渡带不得改动的列（其他建筑、道路）
     */
    public static Construction prepare(BuildingPlan plan, BlockPos origin, int orientation,
                                       java.util.function.Predicate<BlockPos> protectedColumn) {
        Construction c = new Construction(plan, origin.immutable(), orientation);
        int finalOrientation = finalOrientation(plan, orientation);
        Rotation rotation = rotationFor(finalOrientation);

        // 整地（原版 prepareGround）：清除建筑上方的地形和树木，
        // 并把最底层下方向下填实到实心地面，避免嵌进山坡、悬空或建在水上
        if (plan.level == 0 && !plan.subBuilding) {
            for (int l = 0; l < plan.length; l++) {
                for (int w = 0; w < plan.width; w++) {
                    int dx = l - plan.lengthOffset();
                    int dz = w - plan.widthOffset();
                    BlockPos above = worldPos(origin, plan.startLevel + plan.nbFloors, dx, dz, finalOrientation);
                    c.ops.add(new Op(above, Blocks.AIR.defaultBlockState(), FLAGS, false, false, true, 0));
                    BlockPos below = worldPos(origin, plan.startLevel - 1, dx, dz, finalOrientation);
                    c.ops.add(new Op(below, Blocks.DIRT.defaultBlockState(), FLAGS, false, true, false, 0));
                }
            }
            // 建筑周围的过渡带
            BoundingBox box = footprint(plan, origin, orientation);
            for (int x = box.minX() - SURROUND_MARGIN; x <= box.maxX() + SURROUND_MARGIN; x++) {
                for (int z = box.minZ() - SURROUND_MARGIN; z <= box.maxZ() + SURROUND_MARGIN; z++) {
                    int d = Math.max(Math.max(box.minX() - x, x - box.maxX()), Math.max(box.minZ() - z, z - box.maxZ()));
                    BlockPos column = new BlockPos(x, origin.getY() - 1, z);
                    if (d > 0 && !protectedColumn.test(column)) {
                        c.ops.add(new Op(column, Blocks.AIR.defaultBlockState(), FLAGS, false, false, false, d));
                    }
                }
            }
        }

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
                                c.set(pos, pt.state().rotate(rotation));
                            }
                        } else if (pass == 0) {
                            specialFirstPass(c, pt, pos, underground, rotation);
                        } else {
                            specialSecondPass(c, pt, pos, rotation);
                        }
                    }
                }
            }
        }
        addChestOps(c);
        addDoorOps(c);
        return c;
    }

    /**
     * 建筑在地面上的占地范围（含 y 范围）
     */
    public static BoundingBox footprint(BuildingPlan plan, BlockPos origin, int orientation) {
        int o = finalOrientation(plan, orientation);
        BlockPos a = worldPos(origin, plan.startLevel, -plan.lengthOffset(), -plan.widthOffset(), o);
        BlockPos b = worldPos(origin, plan.startLevel + plan.nbFloors - 1,
            plan.length - 1 - plan.lengthOffset(), plan.width - 1 - plan.widthOffset(), o);
        return BoundingBox.fromCorners(a, b);
    }

    static int finalOrientation(BuildingPlan plan, int orientation) {
        return (orientation + plan.buildingOrientation) % 4;
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

    // ================ 箱子 ================

    /**
     * 并排、同朝向的上锁箱子配成大箱子（另一半在 facing 顺时针方向的为左半）
     */
    private static void addChestOps(Construction c) {
        Map<BlockPos, BlockState> chests = c.plannedChests;
        for (Map.Entry<BlockPos, BlockState> entry : chests.entrySet()) {
            BlockState state = entry.getValue();
            if (state.getValue(LockedChestBlock.TYPE) != ChestType.SINGLE) {
                continue;
            }
            Direction facing = state.getValue(LockedChestBlock.FACING);
            BlockPos partnerPos = entry.getKey().relative(facing.getClockWise());
            BlockState partner = chests.get(partnerPos);
            if (partner != null && partner.getValue(LockedChestBlock.TYPE) == ChestType.SINGLE
                && partner.getValue(LockedChestBlock.FACING) == facing) {
                entry.setValue(state.setValue(LockedChestBlock.TYPE, ChestType.LEFT));
                chests.put(partnerPos, partner.setValue(LockedChestBlock.TYPE, ChestType.RIGHT));
            }
        }
        chests.forEach((pos, state) -> {
            c.ops.add(new Op(pos, state, FLAGS, false));
            c.shaped.add(pos);
        });
    }

    // ================ 门 ================

    /**
     * 1.12 的门把朝向存在下半部、铰链存在上半部（图纸中的 DoorUpper / DoorUpperInv），
     * 而 1.20 要求上下两格属性一致。这里以下半部的朝向和上半部的铰链为准同步两格；
     * 图纸没有上半部时补上，并按相邻的门推断双开门铰链。
     */
    private static void addDoorOps(Construction c) {
        Map<BlockPos, BlockState> planned = c.plannedDoors;
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
            c.ops.add(new Op(pos, lower, DOOR_FLAGS, false));
            c.ops.add(new Op(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), DOOR_FLAGS, false));
        }
    }

    private static boolean isLowerDoor(Map<BlockPos, BlockState> planned, BlockPos pos, Direction facing) {
        BlockState state = planned.get(pos);
        return state != null && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
            && state.getValue(DoorBlock.FACING) == facing;
    }

    // ================ 特殊标记 ================

    private static void specialFirstPass(Construction c, PointType pt, BlockPos pos,
                                         boolean underground, Rotation rotation) {
        String type = pt.special();
        if (type == null) {
            return;
        }
        switch (type) {
            case "empty" -> {
                if (c.plan.level == 0) {
                    c.set(pos, Blocks.AIR.defaultBlockState());
                }
            }
            case "preserveground" ->
                c.add(pos, (underground ? Blocks.DIRT : Blocks.GRASS_BLOCK).defaultBlockState(), true);
            case "allbuttrees", "stall", "brickspot", "healingspot", "fishingspot",
                 "plainSignGuess", "signwallGuess", "ladderGuess" -> {
                // 暂不处理
            }
            case "soil", "ricesoil", "turmericsoil", "potatosoil", "maizesoil", "carrotsoil", "cottonsoil" -> {
                // 耕作点：湿润的耕地，第二轮在上方种下作物；记录点位供农夫工作
                c.set(pos, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE));
                c.result.addPoint(type, pos);
            }
            case "grass", "sugarcanesoil", "vinesoil", "cacaospot", "flowersoil", "freegrass_block" -> {
                c.set(pos, Blocks.GRASS_BLOCK.defaultBlockState());
                if (!type.equals("grass") && !type.equals("freegrass_block")) {
                    c.result.addPoint(type, pos);
                }
            }
            case "netherwartsoil" -> {
                c.set(pos, Blocks.SOUL_SAND.defaultBlockState());
                c.result.addPoint(type, pos);
            }
            case "snailsoilblock" -> c.set(pos, Blocks.DIRT.defaultBlockState());
            case "furnaceGuess" -> c.set(pos, Blocks.FURNACE.defaultBlockState());
            case "silkwormblock" -> c.set(pos, ModBlocks.SILKWORM.get().defaultBlockState());
            case "dispenserunknownpowder" -> c.set(pos, Blocks.DISPENSER.defaultBlockState());
            case "brewingstand" -> c.set(pos, Blocks.BREWING_STAND.defaultBlockState());
            default -> {
                BlockState resource = resourceBlock(type);
                if (resource != null) {
                    c.set(pos, resource);
                    if (type.endsWith("source")) {
                        // 采石场等的资源点：记录点位供矿工工作
                        c.result.addPoint(type, pos);
                    }
                } else if (type.startsWith("mainchest") || type.startsWith("lockedchest")) {
                    placeChest(c, type, pos, rotation);
                } else {
                    // sleepingPos、sellingPos、动物生成点等功能点位，以及暂未实现的装饰实体
                    c.result.addPoint(type, pos);
                }
            }
        }
    }

    private static void specialSecondPass(Construction c, PointType pt, BlockPos pos, Rotation rotation) {
        String type = pt.special();
        if (type == null) {
            return;
        }
        if (type.equals("torchGuess")) {
            c.set(pos, Blocks.TORCH.defaultBlockState());
        } else if (Crops.cropForSoil(type) instanceof CropBlock crop) {
            // 生长阶段按位置固定分散，田地看起来自然，也有一部分很快成熟可收
            int age = Math.floorMod(pos.hashCode(), crop.getMaxAge() + 1);
            c.set(pos.above(), crop.getStateForAge(age));
        } else if (type.equals("netherwartsoil")) {
            c.set(pos.above(), Blocks.NETHER_WART.defaultBlockState());
        } else if (type.endsWith("spawn")) {
            BlockState sapling = saplingFor(type);
            if (sapling != null) {
                c.set(pos, sapling);
            }
        } else if (type.contains("BannerWall")) {
            Direction facing = Direction.byName(type.substring(type.indexOf("Wall") + 4).toLowerCase());
            if (facing != null) {
                Block banner = type.startsWith("village") ? Blocks.WHITE_WALL_BANNER : Blocks.BLUE_WALL_BANNER;
                c.set(pos, banner.defaultBlockState().setValue(WallBannerBlock.FACING, facing).rotate(rotation));
            }
        } else if (type.contains("BannerStanding")) {
            int rot = Integer.parseInt(type.substring(type.indexOf("Standing") + 8));
            Block banner = type.startsWith("village") ? Blocks.WHITE_BANNER : Blocks.BLUE_BANNER;
            c.set(pos, banner.defaultBlockState().setValue(BannerBlock.ROTATION, rot).rotate(rotation));
        }
    }

    /**
     * 原版 BuildingPlan：Top 朝西、Bottom 朝东、Left 朝南、Right 朝北；主箱子也是上锁箱子
     */
    private static void placeChest(Construction c, String type, BlockPos pos, Rotation rotation) {
        Direction facing;
        if (type.endsWith("Top")) {
            facing = Direction.WEST;
        } else if (type.endsWith("Bottom")) {
            facing = Direction.EAST;
        } else if (type.endsWith("Left")) {
            facing = Direction.SOUTH;
        } else if (type.endsWith("Right")) {
            facing = Direction.NORTH;
        } else {
            facing = Direction.NORTH;
        }
        boolean main = type.startsWith("mainchest");
        BlockState chest = ModBlocks.LOCKED_CHEST.get().defaultBlockState()
            .setValue(LockedChestBlock.FACING, facing).rotate(rotation);
        c.plannedChests.put(pos.immutable(), chest);
        if (main) {
            c.result.mainChest = pos.immutable();
        }
        c.result.addPoint(main ? "mainchest" : "lockedchest", pos);
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
