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

    @GameTest(template = "empty", timeoutTicks = NIGHT_CHECK_TICK + 200, batch = "time_residents")
    public static void residentsStayInVillageAndSleepAtHome(GameTestHelper helper) {
        if (TestFilter.skip(helper, "residentsStayInVillageAndSleepAtHome")) {
            return;
        }
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

    /**
     * 原版日常：夜里回家躺下睡觉，白天起来工作或休闲（回家休息、社交、聊天）
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "time_sleep")
    public static void villagersSleepAtNightAndLiveByDay(GameTestHelper helper) {
        if (TestFilter.skip(helper, "villagersSleepAtNightAndLiveByDay")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 17000));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        var village = com.jasoncian.millenaire_rewrite.worldgen.VillageGenerator.forceGenerateVillage(level, centre,
            com.jasoncian.millenaire_rewrite.entity.culture.Culture.NORMAN, "manor");
        if (village == null) {
            helper.fail("village generation failed");
            return;
        }
        for (int i = 0; i < 6; i++) {
            village.getGrowth().forceNext(level);
        }
        int previous = com.jasoncian.millenaire_rewrite.command.TickWarp.factor();
        com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(10);
        level.setDayTime(14000);
        java.util.function.Supplier<String> goals = () -> village.getActiveVillagers().stream()
            .map(v -> v.getGoalManager().getCurrentGoal() == null ? "none" : v.getGoalManager().getCurrentGoal().getKey())
            .collect(java.util.stream.Collectors.groupingBy(k -> k, java.util.TreeMap::new, java.util.stream.Collectors.counting()))
            .toString();
        int[] asleep = new int[1];
        int[] adults = new int[1];
        helper.runAtTickTime(1500, () -> {
            var villagers = village.getActiveVillagers();
            adults[0] = (int) villagers.stream().filter(v -> !v.isChild()).count();
            asleep[0] = (int) villagers.stream().filter(v -> !v.isChild() && v.isSleeping()).count();
            MillenaireRewrite.LOGGER.info("[gametest] night: {}/{} adults asleep, goals {}", asleep[0], adults[0], goals.get());
            level.setDayTime(1000);
        });
        helper.runAtTickTime(2400, () -> {
            com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(previous);
            var villagers = village.getActiveVillagers();
            int stillAsleep = (int) villagers.stream().filter(v -> v.isSleeping()).count();
            MillenaireRewrite.LOGGER.info("[gametest] day: {} still asleep, goals {}", stillAsleep, goals.get());
            com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).removeVillage(village);
            villagers.forEach(v -> v.discard());
            TestTerrain.setForced(level, centre, false);
            if (adults[0] == 0 || asleep[0] * 2 < adults[0]) {
                helper.fail("only " + asleep[0] + "/" + adults[0] + " adults went to sleep at night");
            }
            if (stillAsleep > 0) {
                helper.fail(stillAsleep + " villagers did not wake up in the morning");
            }
            helper.succeed();
        });
    }

    /**
     * 原版通用制作目标：诺曼妻子在面包店用 9 个小麦做 3 个面包（makebread，产品放进面包店的箱子）
     */
    @GameTest(template = "empty", timeoutTicks = 2000, batch = "time_bread")
    public static void wifeBakesBreadAtBakery(GameTestHelper helper) {
        if (TestFilter.skip(helper, "wifeBakesBreadAtBakery")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 17600));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var plan = BuildingPlan.get("norman", "bakery", 'A', 0);
            var origin = TestTerrain.ground(centre);
            var result = BuildingPlacer.place(level, plan, origin, 0);
            var village = new Village(origin, Culture.NORMAN);
            com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).registerVillage(village);
            var bakery = village.getGrowth().recordBuilding(plan, origin, 0, result, false);
            var wheat = com.jasoncian.millenaire_rewrite.economy.Goods.get("wheat");
            var bread = com.jasoncian.millenaire_rewrite.economy.Goods.get("bread");
            com.jasoncian.millenaire_rewrite.economy.BuildingStock.store(level, bakery, wheat, 18);
            var home = bakery.points("sleepingPos").isEmpty() ? origin : bakery.points("sleepingPos").get(0);
            MillVillager wife = ResidentSpawner.spawnResident(level, village, "norman", "wife", home);
            level.setDayTime(1000);
            int previous = com.jasoncian.millenaire_rewrite.command.TickWarp.factor();
            com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(10);
            helper.runAtTickTime(1500, () -> {
                com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(previous);
                int breadMade = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, bakery, bread);
                int wheatLeft = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, bakery, wheat);
                MillenaireRewrite.LOGGER.info("[gametest] bakery: bread {}, wheat left {}, wife goal {}", breadMade, wheatLeft,
                    wife == null ? null : wife.getGoalManager().getCurrentGoal());
                com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).removeVillage(village);
                if (wife != null) {
                    wife.discard();
                }
                TestTerrain.setForced(level, centre, false);
                if (breadMade < 3) {
                    helper.fail("the wife baked " + breadMade + " bread");
                }
                helper.succeed();
            });
        } catch (java.io.IOException e) {
            TestTerrain.setForced(level, centre, false);
            helper.fail(e.toString());
        }
    }
}
