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
import net.minecraft.world.level.block.DoorBlock;
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
