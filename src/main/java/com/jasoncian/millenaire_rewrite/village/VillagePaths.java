package com.jasoncian.millenaire_rewrite.village;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.blocks.decorative.PathBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 村庄道路：把建筑的道路起点（图纸中的 pathStartPos，通常在门口）沿地面连到市政厅，
 * 遇到已连到市政厅的道路（路网）即并入。建筑图纸自带的装饰路面只当作好走的地面，
 * 不算并入路网，否则道路会在建筑旁边就提前结束。
 *
 * 只走东西南北四个方向，每步高差最多 1 格，避开水面、非自然地面和其他建筑；
 * 起点和终点所属的建筑内部可以走露天地面（庭院、空地），这样道路能一直通到门口。
 * 只把自然地面替换为路面，并清掉路上的草和花。
 */
public final class VillagePaths {

    /** 搜索范围：起点和终点外扩的格数 */
    private static final int SEARCH_MARGIN = 24;
    private static final int MAX_EXPANSIONS = 40000;
    /** 起点附近不并入路网的范围，避免一出门就接上自己门口的路 */
    private static final int ENDPOINT_RADIUS = 3;
    /** 在建筑自己的占地内行走的成本倍数，让道路尽快离开庭院 */
    private static final double COST_INSIDE_FACTOR = 5.0;
    private static final double COST_STEP = 1.0;
    private static final double COST_EXISTING_PATH = 0.4;
    private static final double COST_PER_HEIGHT = 1.0;
    /** 转弯成本：让路线偏好直线，避免锯齿状的斜线 */
    private static final double COST_TURN = 0.6;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** 调试：本次搜索中格子被拒绝的原因统计 */
    private static final Map<String, Integer> REJECTED = new HashMap<>();

    private VillagePaths() {
    }

    /**
     * 把建筑连接到村庄路网
     *
     * @return 铺设的路面方块数，找不到路线时为 -1
     */
    public static int connect(ServerLevel level, VillageGrowth growth, VillageGrowth.PlacedBuilding building,
                              BlockState material) {
        VillageGrowth.PlacedBuilding hall = growth.getBuildings().stream()
            .filter(b -> b.townHall).findFirst().orElse(null);
        if (hall == null || building.subBuilding) {
            return 0;
        }
        BlockPos start;
        BlockPos hallStart;
        List<BlockPos> route;
        if (building == hall) {
            // 市政厅：门口接到最近的已有道路（佈局的广场、主街）
            if (growth.getPathNetwork().isEmpty() || hall.pathStarts.isEmpty()) {
                return 0;
            }
            start = hall.pathStarts.get(0);
            long nearest = nearestCell(growth.getPathNetwork(), start);
            int gx = ChunkPos.getX(nearest);
            int gz = ChunkPos.getZ(nearest);
            hallStart = new BlockPos(gx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz), gz);
            route = findRoute(level, growth, hall, start, hall, hallStart);
        } else {
            hallStart = closest(endpoints(hall, building.origin), building.origin);
            start = closest(endpoints(building, hallStart), hallStart);
            route = findRoute(level, growth, building, start, hall, hallStart);
        }
        if (route == null) {
            MillenaireRewrite.LOGGER.info("No path route from {} to the town hall", building.key);
            return -1;
        }
        rampDoorSteps(level, growth, route);
        int placed = 0;
        for (BlockPos surface : route) {
            BlockState current = level.getBlockState(surface);
            // 门口（道路起点 1 格内）：上方被栅栏、墙挡住的格子（如栅栏门旁边）不铺路面
            if (isDoorCell(surface.getX(), surface.getZ(), start, hallStart) && feetBlocked(level, surface.above())) {
                continue;
            }
            if (isPath(current)) {
                growth.getPathNetwork().add(key(surface.getX(), surface.getZ()));
                continue;
            }
            // 门口的地板、台阶等不是自然地面，保留原样，也不记入路网
            if (!isNaturalGround(current)) {
                continue;
            }
            growth.getPathNetwork().add(key(surface.getX(), surface.getZ()));
            level.setBlock(surface, material, Block.UPDATE_CLIENTS);
            clearPlants(level, surface.above());
            clearPlants(level, surface.above(2));
            placed++;
        }
        MillenaireRewrite.LOGGER.debug("Path from {} to the town hall: {} cells, {} blocks placed",
            building.key, route.size(), placed);
        return placed;
    }

    /**
     * 门口附近允许高差超过 1 的台阶（图纸画的门口高度）；坡地上门前的路会因此比街道高出两三格。
     * 从两端的门口向外逐格把地面整成每格最多差 1 的坡道（挖低或填土），直到接上原本的地面。
     */
    private static void rampDoorSteps(ServerLevel level, VillageGrowth growth, List<BlockPos> route) {
        int n = route.size();
        if (n < 2) {
            return;
        }
        int[] heights = new int[n];
        for (int i = 0; i < n; i++) {
            heights[i] = route.get(i).getY();
        }
        for (int i = 1; i < n; i++) {
            int diff = heights[i] - heights[i - 1];
            if (Math.abs(diff) <= 1 || growth.getPathNetwork().contains(key(route.get(i).getX(), route.get(i).getZ()))) {
                break;
            }
            heights[i] = heights[i - 1] + Integer.signum(diff);
        }
        for (int i = n - 2; i >= 0; i--) {
            int diff = heights[i] - heights[i + 1];
            if (Math.abs(diff) <= 1 || growth.getPathNetwork().contains(key(route.get(i).getX(), route.get(i).getZ()))) {
                break;
            }
            heights[i] = heights[i + 1] + Integer.signum(diff);
        }
        for (int i = 0; i < n; i++) {
            BlockPos old = route.get(i);
            int h = heights[i];
            if (h == old.getY() || !isNaturalGround(level.getBlockState(old))) {
                continue;
            }
            BlockState top = level.getBlockState(old);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(old.getX(), 0, old.getZ());
            for (int y = h + 1; y <= old.getY(); y++) {
                p.setY(y);
                level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int y = old.getY(); y < h; y++) {
                p.setY(y);
                level.setBlock(p, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            p.setY(h);
            level.setBlock(p, top, Block.UPDATE_CLIENTS);
            clearPlants(level, p.above());
            route.set(i, p.immutable());
        }
    }

    /**
     * 重建路网：清空路网记录，由近到远把所有建筑重新连到市政厅。
     * 新建筑可能盖在旧道路上把它截断，重建后路网中的每一格都确实通到市政厅。
     *
     * @return 是否所有建筑都连上了
     */
    public static boolean rebuildNetwork(ServerLevel level, VillageGrowth growth, BlockState material) {
        growth.getPathNetwork().clear();
        growth.seedNetwork(level);
        VillageGrowth.PlacedBuilding hall = growth.getBuildings().stream()
            .filter(b -> b.townHall).findFirst().orElse(null);
        if (hall == null) {
            return true;
        }
        List<VillageGrowth.PlacedBuilding> ordered = new ArrayList<>(growth.getBuildings());
        ordered.sort(java.util.Comparator.comparingDouble(b -> b.origin.distSqr(hall.origin)));
        boolean all = true;
        for (VillageGrowth.PlacedBuilding b : ordered) {
            all &= connect(level, growth, b, material) >= 0;
        }
        return all;
    }

    /**
     * 占地范围是否压到路网
     */
    public static boolean coversNetwork(VillageGrowth growth, BoundingBox box) {
        for (long cell : growth.getPathNetwork()) {
            int x = ChunkPos.getX(cell);
            int z = ChunkPos.getZ(cell);
            if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }

    // ================ 寻路 ================

    private record Node(int x, int z, double cost, double estimate, int direction) {
    }

    /**
     * A*：返回路线上每一格的地表方块位置；找不到时返回 null
     */
    @Nullable
    private static List<BlockPos> findRoute(ServerLevel level, VillageGrowth growth,
                                            VillageGrowth.PlacedBuilding startBuilding, BlockPos start,
                                            VillageGrowth.PlacedBuilding goalBuilding, BlockPos goal) {
        List<VillageGrowth.PlacedBuilding> buildings = growth.getBuildings();
        int minX = Math.min(start.getX(), goal.getX()) - SEARCH_MARGIN;
        int maxX = Math.max(start.getX(), goal.getX()) + SEARCH_MARGIN;
        int minZ = Math.min(start.getZ(), goal.getZ()) - SEARCH_MARGIN;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + SEARCH_MARGIN;

        Map<Long, Double> best = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Integer> heights = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.estimate(), b.estimate()));

        REJECTED.clear();
        long startKey = key(start.getX(), start.getZ());
        heights.put(startKey, start.getY() - 1);
        best.put(startKey, 0.0);
        open.add(new Node(start.getX(), start.getZ(), 0, heuristic(start.getX(), start.getZ(), goal), -1));

        int expansions = 0;
        int closest = Integer.MAX_VALUE;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            Node node = open.poll();
            long nodeKey = key(node.x(), node.z());
            if (node.cost() > best.getOrDefault(nodeKey, Double.MAX_VALUE)) {
                continue;
            }
            int y = heights.get(nodeKey);
            closest = Math.min(closest, Math.abs(node.x() - goal.getX()) + Math.abs(node.z() - goal.getZ()));
            boolean nearStart = near(node.x(), node.z(), start);
            if (near(node.x(), node.z(), goal, 1) || !nearStart && growth.getPathNetwork().contains(nodeKey)) {
                List<BlockPos> route = reconstruct(cameFrom, heights, nodeKey);
                // 路线一定通到终点（门口）那一格
                if (near(node.x(), node.z(), goal, 1) && (node.x() != goal.getX() || node.z() != goal.getZ())) {
                    route.add(goal.below());
                }
                return route;
            }
            for (int dir = 0; dir < DIRECTIONS.length; dir++) {
                int[] d = DIRECTIONS[dir];
                int nx = node.x() + d[0];
                int nz = node.z() + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                Integer ny = walkableHeight(level, buildings, nx, nz, startBuilding, start, goalBuilding, goal);
                if (ny == null) {
                    continue;
                }
                // 门口可能在台阶上：进出门口的那一步不限高差（图纸本身画了台阶）
                boolean doorStep = isDoorCell(node.x(), node.z(), start, goal) || isDoorCell(nx, nz, start, goal);
                if (!doorStep && Math.abs(ny - y) > 1) {
                    REJECTED.merge("height", 1, Integer::sum);
                    continue;
                }
                BlockState surface = level.getBlockState(new BlockPos(nx, ny, nz));
                double step = (isPath(surface) ? COST_EXISTING_PATH : COST_STEP)
                    + (doorStep ? 0 : Math.abs(ny - y) * COST_PER_HEIGHT)
                    + (node.direction() >= 0 && node.direction() != dir ? COST_TURN : 0);
                if (insideAnyFootprint(buildings, nx, nz)) {
                    step *= COST_INSIDE_FACTOR;
                }
                double cost = node.cost() + step;
                long nk = key(nx, nz);
                if (cost < best.getOrDefault(nk, Double.MAX_VALUE)) {
                    best.put(nk, cost);
                    cameFrom.put(nk, nodeKey);
                    heights.put(nk, ny);
                    open.add(new Node(nx, nz, cost, cost + heuristic(nx, nz, goal), dir));
                }
            }
        }
        MillenaireRewrite.LOGGER.info("Path search {} -> {} failed after {} expansions, closest {} blocks, rejected {}",
            start.toShortString(), goal.toShortString(), expansions, closest, REJECTED);
        return null;
    }

    /**
     * 可行走格子的地表高度（地表方块的 y），不可行走时返回 null
     */
    @Nullable
    private static Integer walkableHeight(ServerLevel level, List<VillageGrowth.PlacedBuilding> buildings,
                                         int x, int z, VillageGrowth.PlacedBuilding startBuilding, BlockPos start,
                                         VillageGrowth.PlacedBuilding goalBuilding, BlockPos goal) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return null;
        }
        // 门口格子：高度取道路起点所在楼层
        if (near(x, z, start, 1)) {
            return start.getY() - 1;
        }
        if (near(x, z, goal, 1)) {
            return goal.getY() - 1;
        }
        // 其他建筑内部不可通行；自己的建筑内部按露天地面判断
        for (VillageGrowth.PlacedBuilding b : buildings) {
            BoundingBox f = b.footprint;
            // 子建筑与母建筑占地相同，由母建筑决定能否通行
            if (b != startBuilding && b != goalBuilding && !b.subBuilding
                && x >= f.minX() && x <= f.maxX() && z >= f.minZ() && z <= f.maxZ()) {
                REJECTED.merge("footprint", 1, Integer::sum);
                return null;
            }
        }
        // 自己建筑（起点/终点所在建筑）内部：沿门口那一层的地板走（城堡庭院、回廊等有屋顶或围墙的地方）
        Integer floor = startBuilding.contains(new BlockPos(x, 0, z)) ? floorNear(level, x, z, start.getY() - 1)
            : goalBuilding.contains(new BlockPos(x, 0, z)) ? floorNear(level, x, z, goal.getY() - 1) : null;
        if (floor != null) {
            return floor;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockState surface = level.getBlockState(new BlockPos(x, y, z));
        if (!surface.getFluidState().isEmpty() || surface.is(BlockTags.ICE)) {
            REJECTED.merge("fluid", 1, Integer::sum);
            return null;
        }
        if (isPath(surface) || isNaturalGround(surface)) {
            return y;
        }
        REJECTED.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(surface.getBlock()).getPath(),
            1, Integer::sum);
        return null;
    }

    /**
     * 参考高度上下 1 格内可以站立的地板高度：地板有碰撞、不是液体，上方两格可以通过
     */
    @Nullable
    private static Integer floorNear(ServerLevel level, int x, int z, int referenceY) {
        for (int dy : new int[] {0, 1, -1}) {
            BlockPos floor = new BlockPos(x, referenceY + dy, z);
            BlockState state = level.getBlockState(floor);
            if (!state.getCollisionShape(level, floor).isEmpty() && state.getFluidState().isEmpty()
                && !feetBlocked(level, floor.above()) && !feetBlocked(level, floor.above(2))) {
                return floor.getY();
            }
        }
        return null;
    }

    private static List<BlockPos> reconstruct(Map<Long, Long> cameFrom, Map<Long, Integer> heights, long end) {
        List<BlockPos> route = new ArrayList<>();
        Long k = end;
        while (k != null) {
            route.add(new BlockPos(ChunkPos.getX(k), heights.get(k), ChunkPos.getZ(k)));
            k = cameFrom.get(k);
        }
        Collections.reverse(route);
        return route;
    }

    // ================ 工具 ================

    /**
     * 道路端点：图纸的道路起点（门口）；没有时取占地边界上离目标最近的一点
     */
    private static List<BlockPos> endpoints(VillageGrowth.PlacedBuilding building, BlockPos towards) {
        BoundingBox f = building.footprint;
        if (!building.pathStarts.isEmpty()) {
            return building.pathStarts;
        }
        int x = Math.max(f.minX(), Math.min(f.maxX(), towards.getX()));
        int z = Math.max(f.minZ(), Math.min(f.maxZ(), towards.getZ()));
        return List.of(new BlockPos(x, building.origin.getY(), z));
    }

    /**
     * 脚下这一格是否被挡住（栅栏、墙等有碰撞的方块；门和栅栏门可以通过）
     */
    private static boolean feetBlocked(ServerLevel level, BlockPos feet) {
        BlockState state = level.getBlockState(feet);
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
            return false;
        }
        return !state.getCollisionShape(level, feet).isEmpty();
    }

    private static boolean isDoorCell(int x, int z, BlockPos start, BlockPos goal) {
        return near(x, z, start, 1) || near(x, z, goal, 1);
    }

    private static long nearestCell(java.util.Set<Long> cells, BlockPos target) {
        long best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (long cell : cells) {
            long dx = ChunkPos.getX(cell) - target.getX();
            long dz = ChunkPos.getZ(cell) - target.getZ();
            if (dx * dx + dz * dz < bestDistance) {
                bestDistance = dx * dx + dz * dz;
                best = cell;
            }
        }
        return best;
    }

    private static BlockPos closest(List<BlockPos> candidates, BlockPos target) {
        BlockPos best = candidates.get(0);
        for (BlockPos p : candidates) {
            if (p.distSqr(target) < best.distSqr(target)) {
                best = p;
            }
        }
        return best;
    }

    public static boolean insideAnyFootprint(List<VillageGrowth.PlacedBuilding> buildings, int x, int z) {
        for (VillageGrowth.PlacedBuilding b : buildings) {
            BoundingBox f = b.footprint;
            if (x >= f.minX() && x <= f.maxX() && z >= f.minZ() && z <= f.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(int x, int z, BlockPos p) {
        return near(x, z, p, ENDPOINT_RADIUS);
    }

    private static boolean near(int x, int z, BlockPos p, int radius) {
        return Math.abs(x - p.getX()) <= radius && Math.abs(z - p.getZ()) <= radius;
    }

    private static double heuristic(int x, int z, BlockPos goal) {
        return (Math.abs(x - goal.getX()) + Math.abs(z - goal.getZ())) * COST_EXISTING_PATH;
    }

    private static long key(int x, int z) {
        return ChunkPos.asLong(x, z);
    }

    public static boolean isPath(BlockState state) {
        return state.getBlock() instanceof PathBlock || state.is(Blocks.DIRT_PATH);
    }

    private static boolean isNaturalGround(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
            || state.is(Blocks.SNOW_BLOCK) || state.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    private static void clearPlants(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && state.canBeReplaced() && state.getFluidState().isEmpty()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
