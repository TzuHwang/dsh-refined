package com.jasoncian.millenaire_rewrite.gametest;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.entity.culture.Culture;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import com.jasoncian.millenaire_rewrite.village.VillageManager;
import com.jasoncian.millenaire_rewrite.worldgen.VillageGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 村庄成长测试：选址不重叠、逐格施工能完工、完工后住进居民。
 */
@GameTestHolder(MillenaireRewrite.MOD_ID)
@PrefixGameTestTemplate(false)
public class VillageGrowthTests {

    private static final int PLATFORM_TOP = TestTerrain.PLATFORM_TOP;
    private static final int PLATFORM_HALF_SIZE = TestTerrain.PLATFORM_HALF_SIZE;

    private static void buildPlatform(ServerLevel level, BlockPos centre) {
        TestTerrain.buildPlatform(level, centre);
    }

    private static Village createVillage(GameTestHelper helper, int relativeZ, Culture culture) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(0, 0, relativeZ));
        setForced(level, pos, true);
        buildPlatform(level, pos);
        Village village = VillageGenerator.forceGenerateVillage(level, pos, culture);
        if (village == null) {
            throw new GameTestAssertException("village generation failed");
        }
        return village;
    }

    private static void cleanup(ServerLevel level, Village village) {
        VillageManager.get(level).removeVillage(village);
        village.getActiveVillagers().forEach(v -> v.discard());
        setForced(level, village.getTownHallPos(), false);
    }

    private static void setForced(ServerLevel level, BlockPos pos, boolean forced) {
        TestTerrain.setForced(level, pos, forced);
    }

    private static void assertNoOverlap(List<VillageGrowth.PlacedBuilding> buildings) {
        for (int i = 0; i < buildings.size(); i++) {
            for (int j = i + 1; j < buildings.size(); j++) {
                BoundingBox a = buildings.get(i).footprint;
                BoundingBox b = buildings.get(j).footprint;
                if (a.minX() <= b.maxX() && a.maxX() >= b.minX() && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ()) {
                    throw new GameTestAssertException(buildings.get(i).key + " overlaps " + buildings.get(j).key);
                }
            }
        }
    }

    /**
     * 村民在自家建筑的工作点工作：农夫收成、伐木工砍树林的树、矿工采石
     */
    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void villagersWorkAtWorkPoints(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(0, 0, 4400));
        setForced(level, centre, true);
        buildPlatform(level, centre);
        BlockPos ground = new BlockPos(centre.getX(), PLATFORM_TOP + 1, centre.getZ());
        Village village = new Village(ground, Culture.NORMAN);
        VillageManager.get(level).registerVillage(village);

        List<com.jasoncian.millenaire_rewrite.entity.MillVillager> workers = new java.util.ArrayList<>();
        java.util.Map<String, VillageGrowth.PlacedBuilding> placed = new java.util.HashMap<>();
        String[] keys = {"farm", "quarry", "lumbermanhut", "grove"};
        int[][] offsets = {{-35, 0}, {35, 0}, {0, 35}, {0, -35}};
        try {
            for (int i = 0; i < keys.length; i++) {
                var plan = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan.get("norman", keys[i], 'A', 0);
                BlockPos origin = ground.offset(offsets[i][0], 0, offsets[i][1]);
                var result = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.place(level, plan, origin, 0);
                placed.put(keys[i], village.getGrowth().recordBuilding(plan, origin, 0, result, i == 0));
                workers.addAll(com.jasoncian.millenaire_rewrite.content.ResidentSpawner.spawnResidents(level, village, plan, result));
            }
        } catch (java.io.IOException e) {
            cleanup(level, village);
            helper.fail(e.toString());
            return;
        }

        // 农场作物全部设为成熟；树林每个种树点放一棵长好的树（5 格高的原木）
        List<BlockPos> soils = placed.get("farm").points("soil");
        for (BlockPos soil : soils) {
            level.setBlock(soil.above(), ((net.minecraft.world.level.block.CropBlock) net.minecraft.world.level.block.Blocks.WHEAT)
                .getStateForAge(7), 3);
        }
        List<BlockPos> trees = placed.get("grove").points("oakspawn");
        for (BlockPos t : trees) {
            for (int y = 0; y < 5; y++) {
                level.setBlock(t.above(y), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState(), 3);
            }
        }
        level.setDayTime(1000);
        MillenaireRewrite.LOGGER.info("[gametest] work: {} soils, {} trees, workers {}", soils.size(), trees.size(),
            workers.stream().map(v -> v.getVillagerType()).toList());

        helper.runAtTickTime(1400, () -> {
            long harvested = soils.stream().filter(s -> {
                var crop = level.getBlockState(s.above());
                return crop.getBlock() instanceof net.minecraft.world.level.block.CropBlock cb && !cb.isMaxAge(crop);
            }).count();
            // 砍下的原木数（伐木工可能砍了一部分就先把木头送回家）
            long chopped = trees.stream().flatMap(t -> java.util.stream.IntStream.range(0, 5).mapToObj(t::above))
                .filter(p -> !level.getBlockState(p).is(net.minecraft.tags.BlockTags.LOGS)).count();
            int cobble = workers.stream().mapToInt(v -> v.countInInventory(net.minecraft.world.item.Items.COBBLESTONE)).sum()
                + chestCount(level, placed.get("quarry"), net.minecraft.world.item.Items.COBBLESTONE);
            MillenaireRewrite.LOGGER.info("[gametest] work: harvested {}/{}, chopped {}/{} logs, cobblestone {}",
                harvested, soils.size(), chopped, trees.size() * 5, cobble);
            cleanup(level, village);
            if (soils.isEmpty() || harvested == 0) {
                helper.fail("farmer harvested nothing");
            }
            if (trees.isEmpty() || chopped == 0) {
                helper.fail("lumberman chopped nothing");
            }
            if (cobble == 0) {
                helper.fail("miner mined nothing");
            }
            helper.succeed();
        });
    }

    private static int chestCount(ServerLevel level, VillageGrowth.PlacedBuilding building, net.minecraft.world.item.Item item) {
        int count = 0;
        List<BlockPos> chests = new java.util.ArrayList<>(building.points("lockedchest"));
        chests.addAll(building.points("mainchest"));
        for (BlockPos c : chests) {
            if (level.getBlockEntity(c) instanceof net.minecraft.world.Container container) {
                count += container.countItem(item);
            }
        }
        return count;
    }

    /**
     * 上锁箱子朝向房间内的空地（正面没有被方块挡住），并排的箱子组成大箱子；四个朝向都要正确
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void chestsFaceOutwardAndPair(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(0, 0, 5000));
        setForced(level, centre, true);
        buildPlatform(level, centre);
        String[] keys = {"farm", "quarry", "manor", "lumbermanhut"};
        int blocked = 0;
        int unpaired = 0;
        int chests = 0;
        try {
            for (int i = 0; i < keys.length; i++) {
                var plan = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan.get("norman", keys[i], 'A', 0);
                for (int orientation = 0; orientation < 4; orientation++) {
                    BlockPos origin = new BlockPos(centre.getX() - 60 + orientation * 40, PLATFORM_TOP + 1,
                        centre.getZ() - 60 + i * 40);
                    var result = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.place(level, plan, origin, orientation);
                    List<BlockPos> positions = new java.util.ArrayList<>(result.specialPoints.getOrDefault("lockedchest", List.of()));
                    positions.addAll(result.specialPoints.getOrDefault("mainchest", List.of()));
                    for (BlockPos pos : positions) {
                        var state = level.getBlockState(pos);
                        if (!(state.getBlock() instanceof com.jasoncian.millenaire_rewrite.blocks.functional.LockedChestBlock)) {
                            continue;
                        }
                        chests++;
                        var facing = state.getValue(com.jasoncian.millenaire_rewrite.blocks.functional.LockedChestBlock.FACING);
                        BlockPos front = pos.relative(facing);
                        if (level.getBlockState(front).isSolidRender(level, front)) {
                            blocked++;
                            MillenaireRewrite.LOGGER.info("[gametest] chest {} o{} at {} faces {} into {}", keys[i], orientation,
                                pos.toShortString(), facing, level.getBlockState(front));
                        }
                        var type = state.getValue(com.jasoncian.millenaire_rewrite.blocks.functional.LockedChestBlock.TYPE);
                        var side = level.getBlockState(pos.relative(facing.getClockWise()));
                        if (type == net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                            && side.getBlock() instanceof com.jasoncian.millenaire_rewrite.blocks.functional.LockedChestBlock
                            && side.getValue(com.jasoncian.millenaire_rewrite.blocks.functional.LockedChestBlock.FACING) == facing) {
                            unpaired++;
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            helper.fail(e.toString());
        } finally {
            setForced(level, centre, false);
        }
        MillenaireRewrite.LOGGER.info("[gametest] chests: {} checked, {} facing a wall, {} side-by-side but not paired",
            chests, blocked, unpaired);
        if (chests == 0) {
            helper.fail("no chests placed");
        }
        if (blocked > 0) {
            helper.fail(blocked + " chests face a wall");
        }
        if (unpaired > 0) {
            helper.fail(unpaired + " side-by-side chests are not joined");
        }
        helper.succeed();
    }

    /**
     * 地形过渡：建筑旁的小丘被削成渐进的斜坡（相邻两格高差不超过 3），旁边的树被清掉
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void surroundingsBlendIntoTerrain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(0, 0, 5600));
        setForced(level, centre, true);
        buildPlatform(level, centre);
        try {
            var plan = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan.get("norman", "farm", 'A', 0);
            BlockPos origin = new BlockPos(centre.getX(), PLATFORM_TOP + 1, centre.getZ());
            BoundingBox f = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.footprint(plan, origin, 0);
            // 东侧紧贴占地的小丘：高 8 格
            for (int x = f.maxX() + 1; x <= f.maxX() + 20; x++) {
                for (int z = f.minZ(); z <= f.maxZ(); z++) {
                    for (int y = PLATFORM_TOP + 1; y <= PLATFORM_TOP + 8; y++) {
                        level.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 2);
                    }
                }
            }
            // 南侧过渡带里的一棵树
            BlockPos tree = new BlockPos((f.minX() + f.maxX()) / 2, PLATFORM_TOP + 1, f.maxZ() + 2);
            for (int y = 0; y < 5; y++) {
                level.setBlock(tree.above(y), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState(), 2);
            }
            level.setBlock(tree.above(5), net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState(), 2);

            com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.place(level, plan, origin, 0);

            int ground = origin.getY() - 1;
            int maxStep = 0;
            int zMid = (f.minZ() + f.maxZ()) / 2;
            int previous = ground;
            StringBuilder profile = new StringBuilder();
            for (int x = f.maxX() + 1; x <= f.maxX() + 1 + com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.SURROUND_MARGIN; x++) {
                int h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zMid) - 1;
                maxStep = Math.max(maxStep, Math.abs(h - previous));
                profile.append(h - ground).append(' ');
                previous = h;
            }
            boolean treeCleared = !level.getBlockState(tree).is(net.minecraft.tags.BlockTags.LOGS)
                && !level.getBlockState(tree.above(5)).is(net.minecraft.tags.BlockTags.LEAVES);
            MillenaireRewrite.LOGGER.info("[gametest] blend: east slope heights above ground {}, max step {}, tree cleared {}",
                profile.toString().trim(), maxStep, treeCleared);
            if (maxStep > 3) {
                helper.fail("terrain next to the building has a step of " + maxStep + " blocks");
            }
            if (!treeCleared) {
                helper.fail("tree next to the building was not cleared");
            }
        } catch (java.io.IOException e) {
            helper.fail(e.toString());
        } finally {
            setForced(level, centre, false);
        }
        helper.succeed();
    }

    /**
     * 记录的占地范围必须包住实际放置的所有方块（选址防重叠、道路避让都依赖它）
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void footprintContainsBuilding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(0, 0, 3800));
        setForced(level, centre, true);
        buildPlatform(level, centre);
        List<String> problems = new java.util.ArrayList<>();
        try {
            com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan plan =
                com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan.get("norman", "manor", 'A', 0);
            for (int orientation = 0; orientation < 4; orientation++) {
                BlockPos origin = new BlockPos(centre.getX() - 60 + orientation * 40, PLATFORM_TOP + 1, centre.getZ());
                com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.place(level, plan, origin, orientation);
                BoundingBox f = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.footprint(plan, origin, orientation);
                int outside = 0;
                for (int x = f.minX() - 6; x <= f.maxX() + 6; x++) {
                    for (int z = f.minZ() - 6; z <= f.maxZ() + 6; z++) {
                        if (x >= f.minX() && x <= f.maxX() && z >= f.minZ() && z <= f.maxZ()) {
                            continue;
                        }
                        for (int y = PLATFORM_TOP + 1; y <= PLATFORM_TOP + 20; y++) {
                            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                                outside++;
                            }
                        }
                    }
                }
                MillenaireRewrite.LOGGER.info("[gametest] footprint o{}: {} blocks outside {}", orientation, outside, f);
                if (outside > 0) {
                    problems.add("orientation " + orientation + ": " + outside + " blocks outside the footprint");
                }
            }
        } catch (java.io.IOException e) {
            helper.fail(e.toString());
        } finally {
            setForced(level, centre, false);
        }
        if (!problems.isEmpty()) {
            helper.fail(String.join("; ", problems));
        }
        helper.succeed();
    }

    /**
     * 地基：建在深水上的建筑，最底层下方一路填实到水底，不会悬空
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void foundationFillsDeepWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(new BlockPos(0, 0, 3200));
        setForced(level, corner, true);
        // 高空中的 40x40 水池：石头池底 y=160，水深 30 格（161-190）
        int floorY = 160;
        int surfaceY = 191;
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                level.setBlock(new BlockPos(corner.getX() + x, floorY, corner.getZ() + z),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
                for (int y = floorY + 1; y < surfaceY; y++) {
                    level.setBlock(new BlockPos(corner.getX() + x, y, corner.getZ() + z),
                        net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
        try {
            com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan plan =
                com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan.get("norman", "manor", 'A', 0);
            BlockPos origin = new BlockPos(corner.getX() + 20, surfaceY, corner.getZ() + 20);
            com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.place(level, plan, origin, 0);
            int bottom = surfaceY + plan.startLevel - 1;
            int water = 0;
            for (int y = floorY + 1; y <= bottom; y++) {
                if (!level.getFluidState(new BlockPos(origin.getX(), y, origin.getZ())).isEmpty()) {
                    water++;
                }
            }
            MillenaireRewrite.LOGGER.info("[gametest] foundation: {} water blocks left under the manor centre (y {}-{})",
                water, floorY + 1, bottom);
            if (water > 0) {
                helper.fail(water + " water blocks left under the building");
            }
        } catch (java.io.IOException e) {
            helper.fail(e.toString());
        } finally {
            setForced(level, corner, false);
        }
        helper.succeed();
    }

    /**
     * 选址拒绝结冰的水面（冰层下有水），接受旁边的普通地面
     */
    @GameTest(template = "empty")
    public static void siteSelectionRejectsFrozenWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(new BlockPos(0, 0, 2600));
        setForced(level, corner, true);
        // 测试世界是正常地形，在高空搭两个平台避免受地形影响：冰面（结冰的水）和石头地面
        int y = 250;
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                level.setBlock(new BlockPos(corner.getX() + x, y, corner.getZ() + z),
                    net.minecraft.world.level.block.Blocks.ICE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(corner.getX() + 20 + x, y, corner.getZ() + z),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
            }
        }
        BoundingBox pond = new BoundingBox(corner.getX(), 0, corner.getZ(), corner.getX() + 9, 0, corner.getZ() + 9);
        BoundingBox land = new BoundingBox(corner.getX() + 20, 0, corner.getZ(), corner.getX() + 29, 0, corner.getZ() + 9);
        Integer pondGround = VillageGrowth.averageGround(level, pond);
        Integer landGround = VillageGrowth.averageGround(level, land);
        setForced(level, corner, false);
        MillenaireRewrite.LOGGER.info("[gametest] site: frozen pond -> {}, land -> {}", pondGround, landGround);
        if (pondGround != null) {
            helper.fail("frozen pond accepted as building ground");
        }
        if (landGround == null) {
            helper.fail("normal ground rejected");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void villageGrowsWithoutOverlaps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Village village = createVillage(helper, 1400, Culture.NORMAN);
        VillageGrowth growth = village.getGrowth();
        int initialBuildings = growth.getBuildings().size();
        int initialResidents = village.getActiveVillagerCount();
        MillenaireRewrite.LOGGER.info("[gametest] growth: start with {} buildings {}, {} residents", initialBuildings,
            growth.getBuildings().stream().map(b -> b.key).toList(), initialResidents);
        if (initialBuildings < 2) {
            helper.fail("start buildings were not built: " + growth.getBuildings().stream().map(b -> b.key).toList());
        }

        int projects = 0;
        for (int i = 0; i < 5; i++) {
            String done = growth.forceNext(level);
            if (done == null) {
                break;
            }
            projects++;
            MillenaireRewrite.LOGGER.info("[gametest] growth: completed {}", done);
        }
        List<VillageGrowth.PlacedBuilding> buildings = growth.getBuildings();
        MillenaireRewrite.LOGGER.info("[gametest] growth: after {} projects {} buildings, {} residents", projects,
            buildings.size(), village.getActiveVillagerCount());
        try {
            if (projects < 5) {
                helper.fail("only " + projects + " projects could be built");
            }
            assertNoOverlap(buildings);
            assertPathsConnected(level, village);
            if (village.getActiveVillagerCount() <= initialResidents) {
                helper.fail("no new residents moved in");
            }
        } finally {
            cleanup(level, village);
        }
        helper.succeed();
    }

    /**
     * 从市政厅道路起点沿路面方块（高差 1 格内）能走到每栋建筑的道路起点附近
     */
    private static void assertPathsConnected(ServerLevel level, Village village) {
        List<VillageGrowth.PlacedBuilding> buildings = village.getGrowth().getBuildings();
        VillageGrowth.PlacedBuilding hall = buildings.stream().filter(b -> b.townHall).findFirst().orElseThrow();
        BlockPos centre = village.getTownHallPos();
        int radius = PLATFORM_HALF_SIZE;
        java.util.Map<Long, Integer> pathCells = new java.util.HashMap<>();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                // 从地表往下找路面：门廊、屋檐下的路从上方看不到
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                for (int y = top; y >= top - 10; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var above = level.getBlockState(pos.above());
                    // 门和栅栏门下的路面也算通行（关着的栅栏门 isSolid 为 true）
                    boolean passable = !above.isSolid() || above.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                        || above.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
                    if (com.jasoncian.millenaire_rewrite.village.VillagePaths.isPath(level.getBlockState(pos)) && passable) {
                        pathCells.put(ChunkPos.asLong(x, z), y);
                        break;
                    }
                }
            }
        }
        if (pathCells.isEmpty()) {
            throw new GameTestAssertException("no path blocks were placed");
        }
        // 从市政厅道路起点附近的路面开始广度优先搜索
        java.util.Set<Long> reached = new java.util.HashSet<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        BoundingBox hf = hall.footprint;
        for (long cell : pathCells.keySet()) {
            int cx = ChunkPos.getX(cell);
            int cz = ChunkPos.getZ(cell);
            if (cx >= hf.minX() - 2 && cx <= hf.maxX() + 2 && cz >= hf.minZ() - 2 && cz <= hf.maxZ() + 2) {
                reached.add(cell);
                queue.add(cell);
            }
        }
        while (!queue.isEmpty()) {
            long cell = queue.poll();
            int x = ChunkPos.getX(cell);
            int z = ChunkPos.getZ(cell);
            int y = pathCells.get(cell);
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long n = ChunkPos.asLong(x + d[0], z + d[1]);
                Integer ny = pathCells.get(n);
                if (ny != null && Math.abs(ny - y) <= 1 && reached.add(n)) {
                    queue.add(n);
                }
            }
        }
        // 道路必须通到市政厅的门口（道路起点），不能只停在市政厅占地边缘
        boolean reachesHallDoor = hall.pathStarts.isEmpty()
            || reached.stream().anyMatch(cell -> nearAny(cell, hall.pathStarts, 1));
        MillenaireRewrite.LOGGER.info("[gametest] paths: reach town hall door: {}", reachesHallDoor);
        if (!reachesHallDoor) {
            throw new GameTestAssertException("paths stop at the town hall's edge instead of reaching its door");
        }
        // 路网中的每一格都必须能从市政厅沿路走到（不能有被截断的路段）
        long orphaned = village.getGrowth().getPathNetwork().stream().filter(cell -> !reached.contains(cell)).count();
        MillenaireRewrite.LOGGER.info("[gametest] paths: {} network cells, {} not reachable from the town hall",
            village.getGrowth().getPathNetwork().size(), orphaned);
        if (orphaned > 0) {
            village.getGrowth().getPathNetwork().stream().filter(cell -> !reached.contains(cell)).limit(6).forEach(cell -> {
                int x = ChunkPos.getX(cell);
                int z = ChunkPos.getZ(cell);
                StringBuilder sb = new StringBuilder();
                for (int[] d : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1;
                    sb.append(" [").append(d[0]).append(",").append(d[1]).append("] y=").append(y).append(" ")
                        .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                            level.getBlockState(new BlockPos(nx, y, nz)).getBlock()).getPath())
                        .append(pathCells.containsKey(ChunkPos.asLong(nx, nz)) ? "*" : "");
                }
                MillenaireRewrite.LOGGER.info("[gametest] orphan {} {}:{}", x, z, sb);
            });
            throw new GameTestAssertException(orphaned + " path network cells are cut off from the town hall");
        }
        int connected = 0;
        List<String> unconnected = new java.util.ArrayList<>();
        for (VillageGrowth.PlacedBuilding b : buildings) {
            if (b.townHall) {
                continue;
            }
            // 建筑占地外扩 2 格内有连通的路面即视为已连接
            BoundingBox f = b.footprint;
            if (reached.stream().anyMatch(cell -> {
                int x = ChunkPos.getX(cell);
                int z = ChunkPos.getZ(cell);
                return x >= f.minX() - 2 && x <= f.maxX() + 2 && z >= f.minZ() - 2 && z <= f.maxZ() + 2;
            })) {
                connected++;
            } else {
                unconnected.add(b.key);
            }
        }
        MillenaireRewrite.LOGGER.info("[gametest] paths: {} path blocks, {} buildings connected, unconnected {}",
            pathCells.size(), connected, unconnected);
        if (!unconnected.isEmpty()) {
            throw new GameTestAssertException("buildings not connected to the town hall: " + unconnected);
        }
    }

    private static boolean nearAny(long cell, List<BlockPos> points, int radius) {
        int x = ChunkPos.getX(cell);
        int z = ChunkPos.getZ(cell);
        for (BlockPos p : points) {
            if (Math.abs(p.getX() - x) <= radius && Math.abs(p.getZ() - z) <= radius) {
                return true;
            }
        }
        return false;
    }

    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void constructionCompletesOverTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Village village = createVillage(helper, 2000, Culture.NORMAN);
        VillageGrowth growth = village.getGrowth();
        int before = growth.getBuildings().size();
        if (!growth.startNextProject(level)) {
            cleanup(level, village);
            helper.fail("no project could be started");
            return;
        }
        MillenaireRewrite.LOGGER.info("[gametest] growth: constructing {}", growth.getCurrentProjectKey());
        helper.succeedWhen(() -> {
            if (growth.isConstructing()) {
                throw new GameTestAssertException("still constructing " + growth.getCurrentProjectKey());
            }
            int after = growth.getBuildings().size();
            boolean upgraded = after == before;
            MillenaireRewrite.LOGGER.info("[gametest] growth: construction finished ({}), {} buildings",
                upgraded ? "upgrade" : "new building", after);
            cleanup(level, village);
        });
    }
}
