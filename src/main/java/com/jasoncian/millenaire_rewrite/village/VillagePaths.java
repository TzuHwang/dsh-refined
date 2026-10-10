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
 * 只走东西南北四个方向，避开其他建筑、水面和非自然地面，每步高差最多 1 格；
 * 只把自然地面替换为路面，并清掉路上的草和花。
 */
public final class VillagePaths {

    /** 搜索范围：起点和终点外扩的格数 */
    private static final int SEARCH_MARGIN = 24;
    private static final int MAX_EXPANSIONS = 40000;
    /** 起点/终点附近允许穿过建筑占地（道路起点常在门口、占地边缘内） */
    private static final int ENDPOINT_RADIUS = 3;
    private static final double COST_STEP = 1.0;
    private static final double COST_EXISTING_PATH = 0.4;
    private static final double COST_PER_HEIGHT = 1.0;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** 调试：本次搜索中格子被拒绝的原因统计 */
    private static final Map<String, Integer> REJECTED = new HashMap<>();

    private VillagePaths() {
    }

    /**
     * 把建筑连接到村庄路网
     *
     * @return 铺设的路面方块数
     */
    public static int connect(ServerLevel level, VillageGrowth growth, VillageGrowth.PlacedBuilding building,
                              BlockState material) {
        VillageGrowth.PlacedBuilding hall = growth.getBuildings().stream()
            .filter(b -> b.townHall).findFirst().orElse(null);
        if (hall == null || hall == building) {
            return 0;
        }
        BlockPos hallStart = closest(endpoints(hall, building.origin), building.origin);
        BlockPos start = closest(endpoints(building, hallStart), hallStart);

        List<BlockPos> route = findRoute(level, growth, start, hallStart);
        if (route == null) {
            MillenaireRewrite.LOGGER.info("No path route from {} to the town hall", building.key);
            return 0;
        }
        int placed = 0;
        for (BlockPos surface : route) {
            if (insideAnyFootprint(growth.getBuildings(), surface.getX(), surface.getZ())) {
                continue;
            }
            growth.getPathNetwork().add(key(surface.getX(), surface.getZ()));
            BlockState current = level.getBlockState(surface);
            if (isPath(current)) {
                continue;
            }
            level.setBlock(surface, material, Block.UPDATE_CLIENTS);
            clearPlants(level, surface.above());
            clearPlants(level, surface.above(2));
            placed++;
        }
        MillenaireRewrite.LOGGER.debug("Path from {} to the town hall: {} cells, {} blocks placed",
            building.key, route.size(), placed);
        return placed;
    }

    // ================ 寻路 ================

    private record Node(int x, int z, double cost, double estimate) {
    }

    /**
     * A*：返回路线上每一格的地表方块位置；找不到时返回 null
     */
    @Nullable
    private static List<BlockPos> findRoute(ServerLevel level, VillageGrowth growth, BlockPos start, BlockPos goal) {
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
        open.add(new Node(start.getX(), start.getZ(), 0, heuristic(start.getX(), start.getZ(), goal)));

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
                return reconstruct(cameFrom, heights, nodeKey);
            }
            for (int[] d : DIRECTIONS) {
                int nx = node.x() + d[0];
                int nz = node.z() + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                Integer ny = walkableHeight(level, buildings, nx, nz, start, goal);
                if (ny == null) {
                    continue;
                }
                // 门口可能在台阶上：进出建筑占地的那一步不限高差（图纸本身画了台阶）
                boolean crossesBuilding = insideAnyFootprint(buildings, node.x(), node.z())
                    || insideAnyFootprint(buildings, nx, nz);
                if (!crossesBuilding && Math.abs(ny - y) > 1) {
                    REJECTED.merge("height", 1, Integer::sum);
                    continue;
                }
                BlockState surface = level.getBlockState(new BlockPos(nx, ny, nz));
                double step = (isPath(surface) ? COST_EXISTING_PATH : COST_STEP)
                    + (crossesBuilding ? 0 : Math.abs(ny - y) * COST_PER_HEIGHT);
                double cost = node.cost() + step;
                long nk = key(nx, nz);
                if (cost < best.getOrDefault(nk, Double.MAX_VALUE)) {
                    best.put(nk, cost);
                    cameFrom.put(nk, nodeKey);
                    heights.put(nk, ny);
                    open.add(new Node(nx, nz, cost, cost + heuristic(nx, nz, goal)));
                }
            }
        }
        MillenaireRewrite.LOGGER.debug("Path search {} -> {} failed after {} expansions, closest {} blocks, rejected {}",
            start.toShortString(), goal.toShortString(), expansions, closest, REJECTED);
        return null;
    }

    /**
     * 可行走格子的地表高度（地表方块的 y），不可行走时返回 null
     */
    @Nullable
    private static Integer walkableHeight(ServerLevel level, List<VillageGrowth.PlacedBuilding> buildings,
                                         int x, int z, BlockPos start, BlockPos goal) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return null;
        }
        boolean endpoint = near(x, z, start) || near(x, z, goal);
        if (insideAnyFootprint(buildings, x, z)) {
            // 建筑内部只允许在道路起点附近穿行，高度取起点所在楼层
            if (!endpoint) {
                REJECTED.merge("footprint", 1, Integer::sum);
                return null;
            }
            return (near(x, z, start) ? start : goal).getY() - 1;
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
     * 道路端点：靠近占地边缘的图纸道路起点（建筑内部深处的起点走不出来）；
     * 没有时取占地边界上离目标最近的一点
     */
    private static List<BlockPos> endpoints(VillageGrowth.PlacedBuilding building, BlockPos towards) {
        BoundingBox f = building.footprint;
        List<BlockPos> nearEdge = building.pathStarts.stream()
            .filter(p -> edgeDistance(f, p) < ENDPOINT_RADIUS)
            .toList();
        if (!nearEdge.isEmpty()) {
            return nearEdge;
        }
        int x = Math.max(f.minX(), Math.min(f.maxX(), towards.getX()));
        int z = Math.max(f.minZ(), Math.min(f.maxZ(), towards.getZ()));
        return List.of(new BlockPos(x, building.origin.getY(), z));
    }

    /**
     * 点到占地边界的距离（在占地外为 0）
     */
    private static int edgeDistance(BoundingBox f, BlockPos p) {
        int d = Math.min(Math.min(p.getX() - f.minX(), f.maxX() - p.getX()),
            Math.min(p.getZ() - f.minZ(), f.maxZ() - p.getZ()));
        return Math.max(0, d);
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

    static boolean insideAnyFootprint(List<VillageGrowth.PlacedBuilding> buildings, int x, int z) {
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
