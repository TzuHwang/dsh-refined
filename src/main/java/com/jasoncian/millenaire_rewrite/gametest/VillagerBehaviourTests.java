package com.jasoncian.millenaire_rewrite.gametest;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan;
import com.jasoncian.millenaire_rewrite.content.ResidentSpawner;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.culture.Culture;
import com.jasoncian.millenaire_rewrite.village.Village;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 村民基本行为测试：白天在村里活动、晚上回到睡觉位置。
 */
@GameTestHolder(MillenaireRewrite.MOD_ID)
@PrefixGameTestTemplate(false)
public class VillagerBehaviourTests {

    private static final int DAY_CHECK_TICK = 1200;
    private static final int NIGHT_CHECK_TICK = 2600;
    /** 白天：漫步半径 16 格，加上走动中的余量 */
    private static final double MAX_DAY_DISTANCE = 24.0;
    /** 晚上：睡觉目标的到达范围为 2 格 */
    private static final double MAX_SLEEP_DISTANCE = 3.0;

    @GameTest(template = "empty", timeoutTicks = NIGHT_CHECK_TICK + 200)
    public static void residentsStayInVillageAndSleepAtHome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<MillVillager> residents;
        BlockPos origin;
        try {
            BuildingPlan plan = BuildingPlan.load("norman", "manor", 'A', 0);
            BlockPos column = helper.absolutePos(new BlockPos(0, 0, 700));
            // 远离测试结构的区块默认不更新实体，强制加载并在平台上进行（不受随机地形影响）
            TestTerrain.setForced(level, column, true);
            TestTerrain.buildPlatform(level, column);
            origin = TestTerrain.ground(column);
            BuildingPlacer.Result result = BuildingPlacer.place(level, plan, origin, 0);
            Village village = new Village(origin, Culture.NORMAN);
            residents = ResidentSpawner.spawnResidents(level, village, plan, result);
        } catch (Exception e) {
            helper.fail(e.toString());
            return;
        }
        if (residents.isEmpty()) {
            helper.fail("no residents spawned");
            return;
        }
        level.setDayTime(1000);

        // 记录白天每个村民离开出生点的最大距离，确认他们确实在活动
        java.util.Map<MillVillager, Double> maxMoved = new java.util.HashMap<>();
        for (int t = 100; t < DAY_CHECK_TICK; t += 100) {
            helper.runAtTickTime(t, () -> residents.forEach(v -> maxMoved.merge(v,
                Math.sqrt(v.blockPosition().distSqr(v.getHousePos())), Math::max)));
        }

        helper.runAtTickTime(DAY_CHECK_TICK, () -> {
            MillenaireRewrite.LOGGER.info("[gametest] day max distance moved: {}", maxMoved.values());
            if (maxMoved.values().stream().allMatch(d -> d < 3.0)) {
                helper.fail("no resident moved during the day: " + maxMoved.values());
            }
            for (MillVillager v : residents) {
                double d = Math.sqrt(v.blockPosition().distSqr(origin));
                MillenaireRewrite.LOGGER.info("[gametest] day: {} at {} ({} from centre), goal {}",
                    v.getFirstName(), v.blockPosition().toShortString(), String.format("%.1f", d),
                    v.getGoalManager().getCurrentGoal());
                if (d > MAX_DAY_DISTANCE) {
                    helper.fail(v.getVillagerType() + " wandered " + String.format("%.1f", d) + " blocks from the village");
                }
            }
            level.setDayTime(14000);
        });

        helper.runAtTickTime(NIGHT_CHECK_TICK, () -> {
            for (MillVillager v : residents) {
                double d = Math.sqrt(v.blockPosition().distSqr(v.getHousePos()));
                MillenaireRewrite.LOGGER.info("[gametest] night: {} at {} ({} from home {}), goal {}",
                    v.getFirstName(), v.blockPosition().toShortString(), String.format("%.1f", d),
                    v.getHousePos().toShortString(), v.getGoalManager().getCurrentGoal());
                if (d > MAX_SLEEP_DISTANCE) {
                    helper.fail(v.getVillagerType() + " is " + String.format("%.1f", d) + " blocks from its sleeping position at night");
                }
            }
            level.setDayTime(1000);
            residents.forEach(MillVillager::discard);
            TestTerrain.setForced(level, origin, false);
            helper.succeed();
        });
    }
}
