package com.jasoncian.millenaire_rewrite.gametest;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan;
import com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes;
import com.jasoncian.millenaire_rewrite.entity.culture.Culture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 建筑图纸的游戏内测试，用 ./gradlew runGameTestServer 运行。
 */
@GameTestHolder(MillenaireRewrite.MOD_ID)
@PrefixGameTestTemplate(false)
public class BuildingPlanTests {

    /** 测试建筑放在测试结构上方，避开超平坦世界的基岩 */
    private static final int BUILD_HEIGHT = 30;
    private static final int SPACING = 80;

    /**
     * 所有文化的所有村庄中心建筑都能读取
     */
    @GameTest(template = "empty")
    public static void allTownHallPlansLoad(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        int loaded = 0;
        for (Culture culture : Culture.values()) {
            try {
                for (VillageTypes.VillageType type : VillageTypes.list(culture)) {
                    try {
                        BuildingPlan.load(VillageTypes.cultureFolder(culture), type.centre(), 'A', 0);
                        loaded++;
                    } catch (Exception e) {
                        failures.add(culture.getId() + "/" + type.centre() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                failures.add(culture.getId() + ": " + e.getMessage());
            }
        }
        MillenaireRewrite.LOGGER.info("[gametest] loaded {} town hall plans, {} failures", loaded, failures.size());
        failures.forEach(f -> MillenaireRewrite.LOGGER.error("[gametest] {}", f));
        if (!failures.isEmpty()) {
            helper.fail(failures.size() + " plans failed to load, first: " + failures.get(0));
        }
        helper.succeed();
    }

    /**
     * 所有市政厅的初始居民类型都能读取，皮肤和衣服材质文件都存在
     */
    @GameTest(template = "empty")
    public static void townHallResidentsValid(GameTestHelper helper) {
        java.nio.file.Path assets = net.minecraftforge.fml.ModList.get()
            .getModFileById(MillenaireRewrite.MOD_ID).getFile().findResource("assets", MillenaireRewrite.MOD_ID);
        List<String> problems = new ArrayList<>();
        int residents = 0;
        for (Culture culture : Culture.values()) {
            try {
                for (VillageTypes.VillageType vt : VillageTypes.list(culture)) {
                    BuildingPlan plan = BuildingPlan.load(VillageTypes.cultureFolder(culture), vt.centre(), 'A', 0);
                    List<String> keys = new ArrayList<>(plan.maleResidents);
                    keys.addAll(plan.femaleResidents);
                    for (String key : keys) {
                        residents++;
                        try {
                            var type = com.jasoncian.millenaire_rewrite.content.VillagerTypes.get(plan.culture, key);
                            if (type.textures().isEmpty()) {
                                problems.add(plan.key + "/" + key + ": no skin textures");
                            }
                            List<String> files = new ArrayList<>(type.textures());
                            type.clothes().values().forEach(files::addAll);
                            for (String tex : files) {
                                if (!java.nio.file.Files.exists(assets.resolve(tex))) {
                                    problems.add(plan.key + "/" + key + ": missing " + tex);
                                }
                            }
                        } catch (Exception e) {
                            problems.add(plan.key + "/" + key + ": " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                problems.add(culture.getId() + ": " + e);
            }
        }
        MillenaireRewrite.LOGGER.info("[gametest] town hall residents: {} checked, {} problems", residents, problems.size());
        problems.stream().limit(30).forEach(pr -> MillenaireRewrite.LOGGER.error("[gametest] {}", pr));
        if (!problems.isEmpty()) {
            helper.fail(problems.size() + " resident problems, first: " + problems.get(0));
        }
        helper.succeed();
    }

    /**
     * 生成诺曼庄园的居民：人数与图纸一致，并且都有名字和皮肤
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void manorResidentsSpawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        try {
            BuildingPlan plan = BuildingPlan.load("norman", "manor", 'A', 0);
            BlockPos origin = helper.absolutePos(new BlockPos(0, BUILD_HEIGHT + 120, SPACING * 5));
            BuildingPlacer.Result result = BuildingPlacer.place(level, plan, origin, 0);
            var village = new com.jasoncian.millenaire_rewrite.village.Village(origin, Culture.NORMAN);
            var spawned = com.jasoncian.millenaire_rewrite.content.ResidentSpawner.spawnResidents(level, village, plan, result);
            int expected = plan.maleResidents.size() + plan.femaleResidents.size();
            for (var v : spawned) {
                MillenaireRewrite.LOGGER.info("[gametest] resident {} type={} skin={} clothes0={}",
                    v.getFullName(), v.getVillagerType(), v.getSkinTexture(), v.getClothTexture(0));
                if (v.getFirstName().isEmpty() || v.getSkinTexture() == null) {
                    helper.fail("resident without name or skin: " + v.getVillagerType());
                }
            }
            if (spawned.size() != expected) {
                helper.fail("expected " + expected + " residents, spawned " + spawned.size());
            }
            spawned.forEach(v -> v.discard());
        } catch (Exception e) {
            helper.fail(e.toString());
        }
        helper.succeed();
    }

    /**
     * 随机选择村庄类型时，每种可生成的类型都有机会被选中
     */
    @GameTest(template = "empty")
    public static void villageTypePickIsRandom(GameTestHelper helper) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        try {
            MillenaireRewrite.LOGGER.info("[gametest] norman village types: {}", VillageTypes.list(Culture.NORMAN));
            net.minecraft.util.RandomSource random = helper.getLevel().random;
            for (int i = 0; i < 200; i++) {
                counts.merge(VillageTypes.pick(Culture.NORMAN, random).fileName(), 1, Integer::sum);
            }
        } catch (Exception e) {
            helper.fail(e.toString());
            return;
        }
        MillenaireRewrite.LOGGER.info("[gametest] norman village type picks: {}", counts);
        if (counts.size() < 4) {
            helper.fail("only picked " + counts);
        }
        helper.succeed();
    }

    /**
     * 建造完成后，栅栏、墙、玻璃板、楼梯的连接形状都已和邻居一致
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void townHallShapesSettled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        String[][] buildings = {{"norman", "manor"}, {"norman", "abbey"}, {"byzantines", "church"}, {"japanese", "japanesefort"}};
        for (int i = 0; i < buildings.length; i++) {
            BuildingPlan plan;
            try {
                plan = BuildingPlan.load(buildings[i][0], buildings[i][1], 'A', 0);
            } catch (Exception e) {
                helper.fail("cannot load " + buildings[i][1] + ": " + e);
                return;
            }
            // 与其他测试的建筑错开，避免重叠
            BlockPos origin = helper.absolutePos(new BlockPos(i * SPACING, BUILD_HEIGHT + 60, SPACING * 3));
            BuildingPlacer.place(level, plan, origin, i % 4);
            int radius = Math.max(plan.length, plan.width);
            for (BlockPos p : BlockPos.betweenClosed(origin.offset(-radius, plan.startLevel, -radius),
                    origin.offset(radius, plan.nbFloors + plan.startLevel, radius))) {
                BlockState state = level.getBlockState(p);
                Block block = state.getBlock();
                if (!(block instanceof FenceBlock || block instanceof WallBlock
                        || block instanceof IronBarsBlock || block instanceof StairBlock)) {
                    continue;
                }
                checked++;
                BlockState expected = Block.updateFromNeighbourShapes(state, level, p);
                if (expected != state) {
                    problems.add(plan.key + " " + p.toShortString() + " " + state + " should be " + expected);
                }
            }
        }
        MillenaireRewrite.LOGGER.info("[gametest] connected shapes: {} checked, {} problems", checked, problems.size());
        problems.stream().limit(20).forEach(pr -> MillenaireRewrite.LOGGER.error("[gametest] {}", pr));
        if (checked == 0) {
            helper.fail("no fences/walls/panes/stairs found");
        } else if (!problems.isEmpty()) {
            helper.fail(problems.size() + " unsettled shapes, first: " + problems.get(0));
        }
        helper.succeed();
    }

    /**
     * 诺曼庄园在四个朝向下：每扇门上下两格属性一致，双开门铰链相反
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void manorDoorsAllOrientations(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BuildingPlan plan;
        try {
            plan = BuildingPlan.load("norman", "manor", 'A', 0);
        } catch (Exception e) {
            helper.fail("cannot load manor: " + e);
            return;
        }
        List<String> problems = new ArrayList<>();
        int doublePairs = 0;
        for (int orientation = 0; orientation < 4; orientation++) {
            BlockPos origin = helper.absolutePos(new BlockPos(orientation * SPACING, BUILD_HEIGHT, 0));
            BuildingPlacer.place(level, plan, origin, orientation);
            int radius = Math.max(plan.length, plan.width);
            for (BlockPos p : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius), origin.offset(radius, 4, radius))) {
                BlockState lower = level.getBlockState(p);
                if (!(lower.getBlock() instanceof DoorBlock) || lower.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                    continue;
                }
                BlockState upper = level.getBlockState(p.above());
                if (!(upper.getBlock() instanceof DoorBlock)
                    || upper.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER
                    || upper.getValue(DoorBlock.FACING) != lower.getValue(DoorBlock.FACING)
                    || upper.getValue(DoorBlock.HINGE) != lower.getValue(DoorBlock.HINGE)) {
                    problems.add("o" + orientation + " " + p.toShortString() + " halves differ: " + lower + " / " + upper);
                    continue;
                }
                Direction facing = lower.getValue(DoorBlock.FACING);
                BlockState right = level.getBlockState(p.relative(facing.getClockWise()));
                if (right.getBlock() instanceof DoorBlock && right.getValue(DoorBlock.FACING) == facing
                    && right.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    doublePairs++;
                    if (right.getValue(DoorBlock.HINGE) == lower.getValue(DoorBlock.HINGE)) {
                        problems.add("o" + orientation + " " + p.toShortString() + " double door with same hinge "
                            + lower.getValue(DoorBlock.HINGE));
                    }
                }
            }
        }
        MillenaireRewrite.LOGGER.info("[gametest] manor doors: {} double pairs, {} problems", doublePairs, problems.size());
        problems.forEach(pr -> MillenaireRewrite.LOGGER.error("[gametest] {}", pr));
        if (doublePairs == 0) {
            helper.fail("no double doors found in manor");
        } else if (!problems.isEmpty()) {
            helper.fail(problems.size() + " door problems, first: " + problems.get(0));
        }
        helper.succeed();
    }
}
