package com.jasoncian.millenaire_rewrite.village.layout;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 一个村庄套用的佈局：模板 + 世界中的位置、旋转、镜像，以及各临街带已占用的区段。
 *
 * 模板坐标先镜像（x 取反），再顺时针旋转 rotation 个 90 度，最后平移到广场中心 centre。
 */
public final class VillageLayout {

    /** 临街带上相邻建筑之间留的空隙（给连接道路和采光） */
    private static final int GAP = 3;
    /** 建筑和道路边缘之间留的空地 */
    private static final int SETBACK = 1;
    /** 道路上方清除树木的高度 */
    private static final int ROAD_CLEAR_HEIGHT = 24;
    /** 道路每格最多挖低或填高的格数，超过时（悬崖、深谷）不铺这一格 */
    private static final int MAX_ROAD_CHANGE = 8;
    /** 道路两侧整成缓坡的宽度 */
    private static final int ROAD_SHOULDER = 3;
    /** 地块允许的地面高差（建造时整成平台） */
    private static final int PLOT_MAX_DIFFERENCE = 12;
    /** 建筑地面与门前路面相差不超过这么多时，对齐路面 */
    private static final int ALIGN_TO_ROAD = 4;

    /** 世界方向：0 西、1 南、2 东、3 北（与 BuildingPlacer 的 orientation 0~3 门朝向一致） */
    private static final int[][] DIRECTIONS = {{-1, 0}, {0, 1}, {1, 0}, {0, -1}};

    public final LayoutTemplate template;
    public final BlockPos centre;
    public final int rotation;
    public final boolean mirror;
    /** 每条临街带已占用的区段 [start, end]（距道路起点的距离） */
    private final List<List<int[]>> occupied = new ArrayList<>();
    /** 道路和广场的格子（x/z 打包为 long） */
    private Set<Long> roadCells;

    public VillageLayout(LayoutTemplate template, BlockPos centre, int rotation, boolean mirror) {
        this.template = template;
        this.centre = centre.immutable();
        this.rotation = Math.floorMod(rotation, 4);
        this.mirror = mirror;
        for (int i = 0; i < template.frontages.size(); i++) {
            occupied.add(new ArrayList<>());
        }
    }

    /**
     * 同一个佈局，广场高度改为 y（地面上第一格空气）。市政厅落地后，广场对齐市政厅的地面，
     * 门口才能平接广场，道路再从这个高度按坡度向外铺
     */
    public VillageLayout withCentreY(int y) {
        VillageLayout copy = new VillageLayout(template, new BlockPos(centre.getX(), y, centre.getZ()), rotation, mirror);
        for (int i = 0; i < occupied.size(); i++) {
            copy.occupied.get(i).addAll(occupied.get(i));
        }
        return copy;
    }

    // ================ 坐标变换 ================

    private int[] dir(int x, int z) {
        if (mirror) {
            x = -x;
        }
        for (int i = 0; i < rotation; i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new int[] {x, z};
    }

    private int[] point(int x, int z) {
        int[] d = dir(x, z);
        return new int[] {centre.getX() + d[0], centre.getZ() + d[1]};
    }

    private static int directionIndex(int dx, int dz) {
        for (int i = 0; i < 4; i++) {
            if (DIRECTIONS[i][0] == dx && DIRECTIONS[i][1] == dz) {
                return i;
            }
        }
        throw new IllegalArgumentException(dx + "," + dz);
    }

    // ================ 建筑正面 ================

    private static final Map<String, Integer> FRONT_OFFSET = new ConcurrentHashMap<>();

    /**
     * 图纸在 orientation 0 时门朝哪个方向（大多数朝西；buildingorientation 特殊的图纸会偏移），
     * 由图纸的道路起点在占地的哪一侧判断
     */
    private static int frontOffset(BuildingPlan plan) {
        return FRONT_OFFSET.computeIfAbsent(plan.culture + "/" + plan.key + plan.variation, k -> {
            BuildingPlacer.Construction c = BuildingPlacer.prepare(plan, BlockPos.ZERO, 0);
            BoundingBox f = BuildingPlacer.footprint(plan, BlockPos.ZERO, 0);
            int[] votes = new int[4];
            for (BlockPos s : c.result.specialPoints.getOrDefault("pathStartPos", List.of())) {
                int[] distance = {s.getX() - f.minX(), f.maxZ() - s.getZ(), f.maxX() - s.getX(), s.getZ() - f.minZ()};
                int best = 0;
                for (int i = 1; i < 4; i++) {
                    if (distance[i] < distance[best]) {
                        best = i;
                    }
                }
                votes[best]++;
            }
            int offset = 0;
            for (int i = 1; i < 4; i++) {
                if (votes[i] > votes[offset]) {
                    offset = i;
                }
            }
            return offset;
        });
    }

    /**
     * 让建筑正面朝向世界方向 front 的 orientation
     */
    public static int orientationFacing(BuildingPlan plan, int front) {
        return Math.floorMod(front - frontOffset(plan), 4);
    }

    // ================ 选址 ================

    /** 一处可以建造的位置 */
    public record Placement(BlockPos origin, int orientation, BoundingBox footprint, int frontage, int start, int end) {
    }

    /**
     * 在线段 a + u*t 的 n 侧、距线 offset 处放一个 along×depth 的矩形
     */
    private static BoundingBox rect(int[] a, int[] u, int[] n, int t, int along, int offset, int depth, int y) {
        int x1 = a[0] + u[0] * t + n[0] * offset;
        int z1 = a[1] + u[1] * t + n[1] * offset;
        int x2 = a[0] + u[0] * (t + along - 1) + n[0] * (offset + depth - 1);
        int z2 = a[1] + u[1] * (t + along - 1) + n[1] * (offset + depth - 1);
        return new BoundingBox(Math.min(x1, x2), y, Math.min(z1, z2), Math.max(x1, x2), y, Math.max(z1, z2));
    }

    /**
     * 让 plan 以 orientation 放置时占地正好是 target 的原点
     */
    private static BlockPos originFor(BuildingPlan plan, int orientation, BoundingBox target, int y) {
        BoundingBox f = BuildingPlacer.footprint(plan, BlockPos.ZERO, orientation);
        return new BlockPos(target.minX() - f.minX(), y, target.minZ() - f.minZ());
    }

    /**
     * 市政厅：广场北侧，正面朝广场，正面离广场边 1 格
     */
    public Placement townHall(ServerLevel level, BuildingPlan plan) {
        Placement p = townHall(plan);
        Integer ground = VillageGrowth.averageGround(level, p.footprint(), PLOT_MAX_DIFFERENCE);
        if (ground == null) {
            return p;
        }
        BlockPos origin = new BlockPos(p.origin().getX(), ground, p.origin().getZ());
        return new Placement(origin, p.orientation(), BuildingPlacer.footprint(plan, origin, p.orientation()), -1, 0, 0);
    }

    private Placement townHall(BuildingPlan plan) {
        int front = directionIndex(dir(0, 1)[0], dir(0, 1)[1]);
        int orientation = orientationFacing(plan, front);
        BoundingBox f = BuildingPlacer.footprint(plan, BlockPos.ZERO, orientation);
        int[] u = dir(1, 0);
        int along = u[0] != 0 ? f.getXSpan() : f.getZSpan();
        int depth = u[0] != 0 ? f.getZSpan() : f.getXSpan();
        BoundingBox box = rect(point(0, 0), u, dir(0, -1), -along / 2, along, template.plaza + 2, depth, centre.getY());
        return new Placement(originFor(plan, orientation, box, centre.getY()), orientation, box, -1, 0, 0);
    }

    /**
     * 在类别为 category 的临街带上为 plan 找一段空位（找不到时再试其他临街带）。
     * 找到的区段立即记为占用。
     *
     * @param blocked 不能占用的格子（其他建筑占地等）
     */
    @Nullable
    public Placement allocate(ServerLevel level, BuildingPlan plan, String category, Predicate<BoundingBox> blocked) {
        Placement p = allocate(level, plan, f -> f.tag().equals(category), blocked);
        if (p == null) {
            p = allocate(level, plan, f -> !f.tag().equals(category), blocked);
        }
        if (p != null) {
            occupied.get(p.frontage()).add(new int[] {p.start(), p.end()});
        }
        return p;
    }

    @Nullable
    private Placement allocate(ServerLevel level, BuildingPlan plan, Predicate<LayoutTemplate.Frontage> accept,
                               Predicate<BoundingBox> blocked) {
        for (int i = 0; i < template.frontages.size(); i++) {
            LayoutTemplate.Frontage fr = template.frontages.get(i);
            if (!accept.test(fr)) {
                continue;
            }
            LayoutTemplate.Road road = template.roads.get(fr.road());
            int tdx = road.dirX();
            int tdz = road.dirZ();
            // 右侧法向：面朝 (dx, dz) 时右手方向
            int[] tn = fr.right() ? new int[] {-tdz, tdx} : new int[] {tdz, -tdx};
            int[] u = dir(tdx, tdz);
            int[] n = dir(tn[0], tn[1]);
            int[] a = point(road.fromX(), road.fromZ());
            int front = directionIndex(-n[0], -n[1]);
            int orientation = orientationFacing(plan, front);
            BoundingBox f = BuildingPlacer.footprint(plan, BlockPos.ZERO, orientation);
            int along = u[0] != 0 ? f.getXSpan() : f.getZSpan();
            int depth = u[0] != 0 ? f.getZSpan() : f.getXSpan();
            if (depth > fr.depth()) {
                MillenaireRewrite.LOGGER.debug("[layoutdebug] {} frontage {} ({}): depth {} > {}", plan.key, i, fr.tag(), depth, fr.depth());
                continue;
            }
            int[] why = new int[4];
            int offset = road.width() / 2 + 1 + SETBACK;
            int t = fr.start();
            while (t + along - 1 <= fr.end()) {
                int conflictEnd = conflict(i, t, t + along - 1);
                if (conflictEnd != Integer.MIN_VALUE) {
                    why[0]++;
                    t = conflictEnd + GAP + 1;
                    continue;
                }
                BoundingBox box = rect(a, u, n, t, along, offset, depth, centre.getY());
                if (coversRoad(box) || blocked.test(box) || !loaded(level, box)) {
                    why[coversRoad(box) ? 1 : 2]++;
                    t += 2;
                    continue;
                }
                Integer ground = VillageGrowth.averageGround(level, box, PLOT_MAX_DIFFERENCE);
                if (ground == null) {
                    t += 2;
                    continue;
                }
                BlockPos origin = alignDoorWithRoad(level, plan, orientation, originFor(plan, orientation, box, ground),
                    a, u, t + along / 2);
                return new Placement(origin, orientation, BuildingPlacer.footprint(plan, origin, orientation), i, t,
                    t + along - 1);
            }
            MillenaireRewrite.LOGGER.debug("[layoutdebug] {} frontage {} ({}) {}x{} len {}: occupied {} road {} blocked {}",
                plan.key, i, fr.tag(), along, depth, fr.end() - fr.start(), why[0], why[1], why[2]);
        }
        return null;
    }

    /**
     * 让门口与门前的路面齐平：门口（道路起点）投影到道路中线上，取那里的路面高度，
     * 建筑整体升降使门口的地面等于路面（相差不超过 ALIGN_TO_ROAD 时）。
     * 街道沿坡度起伏时，门口不在建筑正中间也能接上街道，不会出现两格高的台阶。
     *
     * @param fallbackT 图纸没有道路起点时，用距道路起点 fallbackT 处的路面
     */
    private static BlockPos alignDoorWithRoad(ServerLevel level, BuildingPlan plan, int orientation, BlockPos origin,
                                              int[] a, int[] u, int fallbackT) {
        List<BlockPos> starts = BuildingPlacer.prepare(plan, origin, orientation).result.specialPoints
            .getOrDefault("pathStartPos", List.of());
        int t = fallbackT;
        int doorOffset = 0;
        if (!starts.isEmpty()) {
            BlockPos door = starts.get(0);
            t = (door.getX() - a[0]) * u[0] + (door.getZ() - a[1]) * u[1];
            doorOffset = door.getY() - origin.getY();
        }
        int roadY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a[0] + u[0] * t, a[1] + u[1] * t);
        int y = roadY - doorOffset;
        if (Math.abs(y - origin.getY()) > ALIGN_TO_ROAD) {
            return origin;
        }
        return new BlockPos(origin.getX(), y, origin.getZ());
    }

    /**
     * 与已占用区段（含空隙）冲突时返回冲突区段的终点，否则返回 Integer.MIN_VALUE
     */
    private int conflict(int frontage, int start, int end) {
        int result = Integer.MIN_VALUE;
        for (int[] iv : occupied.get(frontage)) {
            if (start <= iv[1] + GAP && end >= iv[0] - GAP) {
                result = Math.max(result, iv[1]);
            }
        }
        return result;
    }

    private boolean coversRoad(BoundingBox box) {
        Set<Long> cells = roadCells();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                if (cells.contains(ChunkPos.asLong(x, z))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean loaded(ServerLevel level, BoundingBox box) {
        return level.hasChunkAt(new BlockPos(box.minX(), 0, box.minZ()))
            && level.hasChunkAt(new BlockPos(box.maxX(), 0, box.maxZ()))
            && level.hasChunkAt(new BlockPos(box.minX(), 0, box.maxZ()))
            && level.hasChunkAt(new BlockPos(box.maxX(), 0, box.minZ()));
    }

    // ================ 道路 ================

    /**
     * 道路和广场的所有格子
     */
    public Set<Long> roadCells() {
        if (roadCells == null) {
            Set<Long> cells = new HashSet<>();
            int p = template.plaza;
            for (int x = -p; x <= p; x++) {
                for (int z = -p; z <= p; z++) {
                    int[] w = point(x, z);
                    cells.add(ChunkPos.asLong(w[0], w[1]));
                }
            }
            for (LayoutTemplate.Road road : template.roads) {
                int half = road.width() / 2;
                int dx = road.dirX();
                int dz = road.dirZ();
                for (int t = 0; t <= road.length(); t++) {
                    for (int s = -half; s <= half; s++) {
                        int[] w = point(road.fromX() + dx * t - dz * s, road.fromZ() + dz * t + dx * s);
                        cells.add(ChunkPos.asLong(w[0], w[1]));
                    }
                }
            }
            roadCells = cells;
        }
        return roadCells;
    }

    /**
     * 道路路面高度：广场为中心高度，沿道路向外每格最多升降 1 格，尽量贴近原地面
     */
    private Map<Long, Integer> gradeRoads(ServerLevel level) {
        Set<Long> cells = roadCells();
        Map<Long, Integer> heights = new java.util.HashMap<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        int plazaY = centre.getY() - 1;
        int p = template.plaza;
        for (int x = -p; x <= p; x++) {
            for (int z = -p; z <= p; z++) {
                int[] w = point(x, z);
                long cell = ChunkPos.asLong(w[0], w[1]);
                heights.put(cell, plazaY);
                queue.add(cell);
            }
        }
        int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            long cell = queue.poll();
            int h = heights.get(cell);
            int x = ChunkPos.getX(cell);
            int z = ChunkPos.getZ(cell);
            for (int[] d : neighbours) {
                long n = ChunkPos.asLong(x + d[0], z + d[1]);
                if (!cells.contains(n) || heights.containsKey(n) || !level.hasChunkAt(new BlockPos(x + d[0], 0, z + d[1]))) {
                    continue;
                }
                Integer g = groundAt(level, x + d[0], z + d[1]);
                int target = g == null ? h : Math.max(h - 1, Math.min(h + 1, g));
                heights.put(n, target);
                queue.add(n);
            }
        }
        return heights;
    }

    /**
     * 铺设道路和广场：按坡度整平路面（挖低或填高），清除上方的树木和杂草，
     * 两侧整成缓坡；水面、建筑上不铺
     *
     * @return 铺好的格子（加入村庄路网）
     */
    /** 市政厅前院最多往里铺几格 */
    private static final int FORECOURT_DEPTH = 6;

    /**
     * 市政厅前院：图纸前缘常有几排空地，墙离广场还有好几格草地，门口接不上广场。
     * 沿市政厅宽度从广场边往里铺路面，碰到建筑方块就停（只改天然地面）
     *
     * @return 铺好的格子
     */
    public Set<Long> paveForecourt(ServerLevel level, BuildingPlan townHallPlan, BlockState material) {
        Set<Long> paved = new HashSet<>();
        int front = directionIndex(dir(0, 1)[0], dir(0, 1)[1]);
        BoundingBox f = BuildingPlacer.footprint(townHallPlan, BlockPos.ZERO, orientationFacing(townHallPlan, front));
        int along = dir(1, 0)[0] != 0 ? f.getXSpan() : f.getZSpan();
        int plazaY = centre.getY() - 1;
        for (int t = -along / 2; t < -along / 2 + along; t++) {
            for (int k = template.plaza + 1; k <= template.plaza + 1 + FORECOURT_DEPTH; k++) {
                int[] w = point(t, -k);
                Integer g = groundAt(level, w[0], w[1]);
                if (g == null || Math.abs(g - plazaY) > 1) {
                    break;
                }
                BlockState ground = level.getBlockState(new BlockPos(w[0], g, w[1]));
                if (!isPath(ground) && !isNaturalGround(ground)) {
                    break;
                }
                setColumn(level, w[0], w[1], g, plazaY, material);
                paved.add(ChunkPos.asLong(w[0], w[1]));
            }
        }
        return paved;
    }

    public Set<Long> paveRoads(ServerLevel level, BlockState material, Predicate<BlockPos> protectedColumn) {
        Map<Long, Integer> heights = gradeRoads(level);
        Set<Long> paved = new HashSet<>();
        for (Map.Entry<Long, Integer> e : heights.entrySet()) {
            int x = ChunkPos.getX(e.getKey());
            int z = ChunkPos.getZ(e.getKey());
            int h = e.getValue();
            if (protectedColumn.test(new BlockPos(x, 0, z))) {
                continue;
            }
            Integer g = groundAt(level, x, z);
            if (g == null || Math.abs(g - h) > MAX_ROAD_CHANGE) {
                continue;
            }
            BlockState ground = level.getBlockState(new BlockPos(x, g, z));
            if (!isPath(ground) && !isNaturalGround(ground)) {
                continue;
            }
            setColumn(level, x, z, g, h, material);
            paved.add(e.getKey());
        }
        shapeShoulders(level, heights, protectedColumn);
        return paved;
    }

    /**
     * 把一列地面从 g 改到 h：清除 h 以上的方块（含树木），h 以下填土，顶面为 top
     */
    private static void setColumn(ServerLevel level, int x, int z, int g, int h, BlockState top) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, h + 1, z);
        int clearTo = Math.max(g, h) + ROAD_CLEAR_HEIGHT;
        for (int y = h + 1; y <= clearTo; y++) {
            p.setY(y);
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && (y <= g || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)
                || s.canBeReplaced() && s.getFluidState().isEmpty())) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        for (int y = g + 1; y < h; y++) {
            p.setY(y);
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        p.setY(h);
        if (!level.getBlockState(p).is(top.getBlock())) {
            level.setBlock(p, top, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * 道路两侧 ROAD_SHOULDER 格内：地面与路面的高差不超过离道路的距离，形成缓坡而不是断崖
     */
    private void shapeShoulders(ServerLevel level, Map<Long, Integer> heights, Predicate<BlockPos> protectedColumn) {
        Map<Long, int[]> limits = new java.util.HashMap<>();
        for (Map.Entry<Long, Integer> e : heights.entrySet()) {
            int x = ChunkPos.getX(e.getKey());
            int z = ChunkPos.getZ(e.getKey());
            int h = e.getValue();
            for (int dx = -ROAD_SHOULDER; dx <= ROAD_SHOULDER; dx++) {
                for (int dz = -ROAD_SHOULDER; dz <= ROAD_SHOULDER; dz++) {
                    long n = ChunkPos.asLong(x + dx, z + dz);
                    if (heights.containsKey(n)) {
                        continue;
                    }
                    int d = Math.max(Math.abs(dx), Math.abs(dz));
                    int[] limit = limits.computeIfAbsent(n, k -> new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE});
                    limit[0] = Math.max(limit[0], h - d);
                    limit[1] = Math.min(limit[1], h + d);
                }
            }
        }
        for (Map.Entry<Long, int[]> e : limits.entrySet()) {
            int x = ChunkPos.getX(e.getKey());
            int z = ChunkPos.getZ(e.getKey());
            int[] limit = e.getValue();
            if (limit[0] > limit[1] || !level.hasChunkAt(new BlockPos(x, 0, z))
                || protectedColumn.test(new BlockPos(x, 0, z))) {
                continue;
            }
            Integer g = groundAt(level, x, z);
            if (g == null) {
                continue;
            }
            int target = Math.max(limit[0], Math.min(limit[1], g));
            if (target == g || Math.abs(target - g) > MAX_ROAD_CHANGE) {
                continue;
            }
            BlockState surface = level.getBlockState(new BlockPos(x, g, z));
            if (!isNaturalGround(surface)) {
                continue;
            }
            BlockState top = surface.is(BlockTags.SAND) || surface.is(Blocks.SNOW_BLOCK) ? surface
                : Blocks.GRASS_BLOCK.defaultBlockState();
            setColumn(level, x, z, g, target, top);
        }
    }

    /**
     * 道路格子中已经是路面的格子（重建路网时重新加入）
     */
    public Set<Long> existingRoadCells(ServerLevel level) {
        Set<Long> cells = new HashSet<>();
        for (long cell : roadCells()) {
            int x = ChunkPos.getX(cell);
            int z = ChunkPos.getZ(cell);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            if (isPath(level.getBlockState(new BlockPos(x, y, z)))) {
                cells.add(cell);
            }
        }
        return cells;
    }

    /**
     * 地面高度：跳过树干、树叶和杂草；水面返回 null
     */
    @Nullable
    private static Integer groundAt(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int min = y - ROAD_CLEAR_HEIGHT;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        while (y > min) {
            p.setY(y);
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty() || s.is(BlockTags.ICE)) {
                return null;
            }
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.isAir() || s.canBeReplaced()) {
                y--;
                continue;
            }
            return y;
        }
        return null;
    }

    private static boolean isPath(BlockState state) {
        return com.jasoncian.millenaire_rewrite.village.VillagePaths.isPath(state);
    }

    private static boolean isNaturalGround(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
            || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.STONE) || state.is(BlockTags.BASE_STONE_OVERWORLD)
            || state.is(Blocks.CLAY) || state.is(Blocks.FARMLAND);
    }

    // ================ 生成 ================

    /** 测试用：指定下一个村庄使用的模板名（null 时按权重随机） */
    @Nullable
    public static String forcedTemplate;

    /**
     * 为文化挑一个模板和朝向，使市政厅的位置是干燥、平坦的地面
     *
     * @return 没有模板或找不到合适朝向时返回 null（退回旧的随机选址）
     */
    @Nullable
    public static VillageLayout create(ServerLevel level, String culture, BuildingPlan townHall, BlockPos centre,
                                       RandomSource random) {
        List<LayoutTemplate> templates = LayoutTemplate.forTownHall(culture, townHall.key);
        if (templates.isEmpty()) {
            return null;
        }
        int total = templates.stream().mapToInt(t -> t.weight).sum();
        int roll = random.nextInt(Math.max(total, 1));
        LayoutTemplate template = templates.get(0);
        for (LayoutTemplate t : templates) {
            roll -= t.weight;
            if (roll < 0) {
                template = t;
                break;
            }
        }
        if (forcedTemplate != null) {
            LayoutTemplate forced = LayoutTemplate.get(culture, forcedTemplate);
            if (forced != null) {
                template = forced;
            }
        }
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centre.getX(), centre.getZ());
        BlockPos plaza = new BlockPos(centre.getX(), ground, centre.getZ());
        // 8 种旋转/镜像按地形打分（道路经过水面、高差），由好到差尝试；同分时随机
        List<VillageLayout> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            candidates.add(new VillageLayout(template, plaza, i / 2, i % 2 == 1));
        }
        java.util.Collections.shuffle(candidates, new java.util.Random(random.nextLong()));
        Map<VillageLayout, Integer> scores = new java.util.HashMap<>();
        candidates.forEach(c -> scores.put(c, c.terrainScore(level)));
        candidates.sort(java.util.Comparator.comparingInt(scores::get));
        for (VillageLayout layout : candidates) {
            Placement hall = layout.townHall(townHall);
            boolean loaded = loaded(level, hall.footprint());
            Integer hallGround = loaded ? VillageGrowth.averageGround(level, hall.footprint(), PLOT_MAX_DIFFERENCE) : null;
            MillenaireRewrite.LOGGER.debug("Layout {} rotation {} mirror {}: town hall {} loaded {} ground {}", template,
                layout.rotation, layout.mirror, hall.footprint(), loaded, hallGround);
            if (loaded && hallGround != null) {
                MillenaireRewrite.LOGGER.info("Village layout {} at {} rotation {} mirror {} (terrain score {})", template,
                    plaza.toShortString(), layout.rotation, layout.mirror, scores.get(layout));
                return layout;
            }
        }
        return null;
    }

    /**
     * 地形分数（越低越好）：道路格子与广场高度的高差之和，水面和未加载的格子重罚
     */
    private int terrainScore(ServerLevel level) {
        int score = 0;
        int plazaY = centre.getY() - 1;
        int i = 0;
        for (long cell : roadCells()) {
            if (i++ % 3 != 0) {
                continue;
            }
            int x = ChunkPos.getX(cell);
            int z = ChunkPos.getZ(cell);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
                score += 30;
                continue;
            }
            Integer g = groundAt(level, x, z);
            score += g == null ? 30 : Math.min(Math.abs(g - plazaY), 20);
        }
        return score;
    }

    // ================ 存档 ================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Culture", template.culture);
        tag.putString("Template", template.name);
        tag.put("Centre", NbtUtils.writeBlockPos(centre));
        tag.putInt("Rotation", rotation);
        tag.putBoolean("Mirror", mirror);
        ListTag frontages = new ListTag();
        for (List<int[]> intervals : occupied) {
            ListTag list = new ListTag();
            intervals.forEach(iv -> list.add(new IntArrayTag(iv)));
            frontages.add(list);
        }
        tag.put("Occupied", frontages);
        return tag;
    }

    @Nullable
    public static VillageLayout load(CompoundTag tag) {
        LayoutTemplate template = LayoutTemplate.get(tag.getString("Culture"), tag.getString("Template"));
        if (template == null) {
            return null;
        }
        VillageLayout layout = new VillageLayout(template, NbtUtils.readBlockPos(tag.getCompound("Centre")),
            tag.getInt("Rotation"), tag.getBoolean("Mirror"));
        ListTag frontages = tag.getList("Occupied", Tag.TAG_LIST);
        for (int i = 0; i < frontages.size() && i < layout.occupied.size(); i++) {
            ListTag list = (ListTag) frontages.get(i);
            for (int j = 0; j < list.size(); j++) {
                layout.occupied.get(i).add(list.getIntArray(j));
            }
        }
        return layout;
    }
}
