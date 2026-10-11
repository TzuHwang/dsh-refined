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

    /**
     * 原版物资流动：木匠家里缺木头（requiredGood=wood,64）时去市政厅拿木头带回家，再在家里做木框架
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "time_goods")
    public static void carpenterFetchesWoodForHousehold(GameTestHelper helper) {
        if (TestFilter.skip(helper, "carpenterFetchesWoodForHousehold")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 18200));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var hallPlan = BuildingPlan.get("norman", "manor", 'A', 0);
            var housePlan = BuildingPlan.get("norman", "carpenterhouse", 'A', 0);
            var hallOrigin = TestTerrain.ground(centre);
            var houseOrigin = hallOrigin.offset(40, 0, 0);
            var hallResult = BuildingPlacer.place(level, hallPlan, hallOrigin, 0);
            var houseResult = BuildingPlacer.place(level, housePlan, houseOrigin, 0);
            var village = new Village(hallOrigin, Culture.NORMAN);
            com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).registerVillage(village);
            var hall = village.getGrowth().recordBuilding(hallPlan, hallOrigin, 0, hallResult, true);
            var house = village.getGrowth().recordBuilding(housePlan, houseOrigin, 0, houseResult, false);
            var wood = com.jasoncian.millenaire_rewrite.economy.Goods.get("wood");
            var frame = com.jasoncian.millenaire_rewrite.economy.Goods.get("timberframeplain");
            com.jasoncian.millenaire_rewrite.economy.BuildingStock.store(level, hall, wood, 200);
            var home = house.points("sleepingPos").isEmpty() ? houseOrigin : house.points("sleepingPos").get(0);
            MillVillager carpenter = ResidentSpawner.spawnResident(level, village, "norman", "carpenter", home);
            // 原版由同一户的妻子替家里取物资
            MillVillager wife = ResidentSpawner.spawnResident(level, village, "norman", "wife", home);
            level.setDayTime(1000);
            int previous = com.jasoncian.millenaire_rewrite.command.TickWarp.factor();
            com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(10);
            helper.runAtTickTime(2500, () -> {
                com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(previous);
                int woodAtHome = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, house, wood);
                int framesAtHome = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, house, frame);
                int woodInHall = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, hall, wood);
                MillenaireRewrite.LOGGER.info("[gametest] carpenter: hall wood {}, house wood {}, frames {}, carrying wood {}, goal {}",
                    woodInHall, woodAtHome, framesAtHome, carpenter == null ? 0 : carpenter.countGood(wood),
                    carpenter == null ? null : carpenter.getGoalManager().getCurrentGoal());
                com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).removeVillage(village);
                if (carpenter != null) {
                    carpenter.discard();
                }
                if (wife != null) {
                    wife.discard();
                }
                TestTerrain.setForced(level, centre, false);
                if (woodInHall >= 200) {
                    helper.fail("the carpenter did not fetch any wood from the town hall");
                }
                helper.succeed();
            });
        } catch (java.io.IOException e) {
            TestTerrain.setForced(level, centre, false);
            helper.fail(e.toString());
        }
    }

    /**
     * 原版剪羊毛：牧羊人剪掉自家养殖场附近羊的毛，羊毛带回家
     */
    @GameTest(template = "empty", timeoutTicks = 2000, batch = "time_sheep")
    public static void shepherdShearsSheep(GameTestHelper helper) {
        if (TestFilter.skip(helper, "shepherdShearsSheep")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 18800));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var plan = BuildingPlan.get("norman", "sheepchickenfarm", 'A', 0);
            var origin = TestTerrain.ground(centre);
            var result = BuildingPlacer.place(level, plan, origin, 0);
            var village = new Village(origin, Culture.NORMAN);
            com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).registerVillage(village);
            var farm = village.getGrowth().recordBuilding(plan, origin, 0, result, false);
            var home = farm.points("sleepingPos").isEmpty() ? origin : farm.points("sleepingPos").get(0);
            MillVillager shepherd = ResidentSpawner.spawnResident(level, village, "norman", "shepherd", home);
            List<net.minecraft.world.entity.animal.Sheep> sheep = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                var s = net.minecraft.world.entity.EntityType.SHEEP.create(level);
                s.moveTo(origin.getX() + 0.5 + i, origin.getY(), origin.getZ() - 6.5, 0, 0);
                level.addFreshEntity(s);
                sheep.add(s);
            }
            level.setDayTime(1000);
            int previous = com.jasoncian.millenaire_rewrite.command.TickWarp.factor();
            com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(10);
            helper.runAtTickTime(1500, () -> {
                com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(previous);
                long sheared = sheep.stream().filter(net.minecraft.world.entity.animal.Sheep::isSheared).count();
                var wool = new com.jasoncian.millenaire_rewrite.economy.Goods.Good("wool", net.minecraft.world.item.Items.WHITE_WOOL,
                    null, net.minecraft.tags.ItemTags.WOOL);
                int woolTotal = (shepherd == null ? 0 : shepherd.countGood(wool))
                    + com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, farm, wool);
                MillenaireRewrite.LOGGER.info("[gametest] shepherd: {} of 3 sheep sheared, wool {}, goal {}", sheared, woolTotal,
                    shepherd == null ? null : shepherd.getGoalManager().getCurrentGoal());
                com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).removeVillage(village);
                if (shepherd != null) {
                    shepherd.discard();
                }
                sheep.forEach(net.minecraft.world.entity.Entity::discard);
                TestTerrain.setForced(level, centre, false);
                if (sheared == 0 || woolTotal == 0) {
                    helper.fail("the shepherd sheared " + sheared + " sheep");
                }
                helper.succeed();
            });
        } catch (java.io.IOException e) {
            TestTerrain.setForced(level, centre, false);
            helper.fail(e.toString());
        }
    }

    /**
     * 原版打怪：守卫去打村里的怪物（近战，拿着村民类型的武器）
     */
    @GameTest(template = "empty", timeoutTicks = 2000, batch = "time_combat")
    public static void guardHuntsMonster(GameTestHelper helper) {
        if (TestFilter.skip(helper, "guardHuntsMonster")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 19400));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var plan = BuildingPlan.get("norman", "guardhouse", 'A', 0);
            var origin = TestTerrain.ground(centre);
            var result = BuildingPlacer.place(level, plan, origin, 0);
            var village = new Village(origin, Culture.NORMAN);
            com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).registerVillage(village);
            var house = village.getGrowth().recordBuilding(plan, origin, 0, result, false);
            var home = house.points("sleepingPos").isEmpty() ? origin : house.points("sleepingPos").get(0);
            MillVillager guard = ResidentSpawner.spawnResident(level, village, "norman", "guard", home);
            var spider = net.minecraft.world.entity.EntityType.SPIDER.create(level);
            spider.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() - 10.5, 0, 0);
            level.addFreshEntity(spider);
            level.setDayTime(1000);
            int previous = com.jasoncian.millenaire_rewrite.command.TickWarp.factor();
            com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(10);
            helper.runAtTickTime(1200, () -> {
                com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(previous);
                MillenaireRewrite.LOGGER.info("[gametest] guard: spider alive {}, guard health {}, holding {}, goal {}",
                    spider.isAlive(), guard == null ? 0 : guard.getHealth(), guard == null ? null : guard.getMainHandItem(),
                    guard == null ? null : guard.getGoalManager().getCurrentGoal());
                boolean alive = spider.isAlive();
                spider.discard();
                com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).removeVillage(village);
                if (guard != null) {
                    guard.discard();
                }
                TestTerrain.setForced(level, centre, false);
                if (alive) {
                    helper.fail("the guard did not kill the spider");
                }
                helper.succeed();
            });
        } catch (java.io.IOException e) {
            TestTerrain.setForced(level, centre, false);
            helper.fail(e.toString());
        }
    }

    /** 每个"天"在测试里占的游戏 tick：前半夜里睡觉，后半白天 */
    private static final int TEST_DAY = 600;

    /**
     * 从第 start 天开始，安排 days 个昼夜（setDayTime 切换夜晚/白天）
     */
    private static void scheduleDays(GameTestHelper helper, int start, int days) {
        var level = helper.getLevel();
        for (int d = 0; d < days; d++) {
            int t = start + d * TEST_DAY;
            helper.runAtTickTime(t, () -> level.setDayTime(14000));
            helper.runAtTickTime(t + TEST_DAY / 2, () -> level.setDayTime(1000));
        }
    }

    /**
     * 原版家庭：夫妻住在一起，夜里妻子有机会怀孕生下小孩（每晚 2/10）
     */
    @GameTest(template = "empty", timeoutTicks = 14000, batch = "time_family")
    public static void coupleHasChild(GameTestHelper helper) {
        if (TestFilter.skip(helper, "coupleHasChild")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 20000));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var plan = BuildingPlan.get("norman", "carpenterhouse", 'A', 0);
            var origin = TestTerrain.ground(centre);
            var result = BuildingPlacer.place(level, plan, origin, 0);
            var village = new Village(origin, Culture.NORMAN);
            com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).registerVillage(village);
            var house = village.getGrowth().recordBuilding(plan, origin, 0, result, false);
            var home = house.points("sleepingPos").isEmpty() ? origin : house.points("sleepingPos").get(0);
            ResidentSpawner.spawnResident(level, village, "norman", "carpenter", home);
            ResidentSpawner.spawnResident(level, village, "norman", "wife", home);
            int previous = com.jasoncian.millenaire_rewrite.command.TickWarp.factor();
            com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(10);
            scheduleDays(helper, 1, 20);
            helper.runAtTickTime(20 * TEST_DAY + 100, () -> {
                com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(previous);
                var children = village.getActiveVillagers().stream().filter(MillVillager::isChild).toList();
                MillenaireRewrite.LOGGER.info("[gametest] family: {} children {}", children.size(),
                    children.stream().map(c -> c.getFullName() + " " + c.getVillagerType() + " size " + c.getChildSize()).toList());
                com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).removeVillage(village);
                village.getActiveVillagers().forEach(v -> v.discard());
                TestTerrain.setForced(level, centre, false);
                if (children.isEmpty()) {
                    helper.fail("no child was born in 20 nights");
                }
                helper.succeed();
            });
        } catch (java.io.IOException e) {
            TestTerrain.setForced(level, centre, false);
            helper.fail(e.toString());
        }
    }

    /**
     * 原版成年：长大的男孩搬进空着的守卫屋，成为守卫
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "time_family")
    public static void childGrowsUpAndMovesOut(GameTestHelper helper) {
        if (TestFilter.skip(helper, "childGrowsUpAndMovesOut")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 20600));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var homePlan = BuildingPlan.get("norman", "carpenterhouse", 'A', 0);
            var guardPlan = BuildingPlan.get("norman", "guardhouse", 'A', 0);
            var origin = TestTerrain.ground(centre);
            var guardOrigin = origin.offset(30, 0, 0);
            var homeResult = BuildingPlacer.place(level, homePlan, origin, 0);
            var guardResult = BuildingPlacer.place(level, guardPlan, guardOrigin, 0);
            var village = new Village(origin, Culture.NORMAN);
            com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).registerVillage(village);
            var home = village.getGrowth().recordBuilding(homePlan, origin, 0, homeResult, false);
            village.getGrowth().recordBuilding(guardPlan, guardOrigin, 0, guardResult, false);
            var bed = home.points("sleepingPos").isEmpty() ? origin : home.points("sleepingPos").get(0);
            MillVillager boy = ResidentSpawner.spawnResident(level, village, "norman", "boy", bed);
            boy.setChildSize(18);
            String name = boy.getFullName();
            int previous = com.jasoncian.millenaire_rewrite.command.TickWarp.factor();
            com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(10);
            scheduleDays(helper, 1, 2);
            helper.runAtTickTime(2 * TEST_DAY + 600, () -> {
                com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(previous);
                var adult = village.getActiveVillagers().stream()
                    .filter(v -> !v.isChild() && v.getFullName().equals(name)).findFirst().orElse(null);
                MillenaireRewrite.LOGGER.info("[gametest] grown up: {} -> {} (boy removed {})", name,
                    adult == null ? null : adult.getVillagerType() + " at " + adult.getHousePos(), boy.isRemoved());
                com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).removeVillage(village);
                village.getActiveVillagers().forEach(v -> v.discard());
                boy.discard();
                TestTerrain.setForced(level, centre, false);
                if (adult == null || !adult.getVillagerType().equals("guard")) {
                    helper.fail("the boy did not grow up into a guard");
                }
                helper.succeed();
            });
        } catch (java.io.IOException e) {
            TestTerrain.setForced(level, centre, false);
            helper.fail(e.toString());
        }
    }

    /**
     * 原版外地商人：夜里市集召来商人（不超过摊位数），货存进市集；白天守摊；住满 5 晚离开
     */
    @GameTest(template = "empty", timeoutTicks = 2000, batch = "time_merchant")
    public static void foreignMerchantKeepsStall(GameTestHelper helper) {
        if (TestFilter.skip(helper, "foreignMerchantKeepsStall")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 21200));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var plan = BuildingPlan.get("norman", "market", 'A', 0);
            var origin = TestTerrain.ground(centre);
            var result = BuildingPlacer.place(level, plan, origin, 0);
            var village = new Village(origin, Culture.NORMAN);
            com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).registerVillage(village);
            var market = village.getGrowth().recordBuilding(plan, origin, 0, result, false);
            int stalls = market.points("stall").size();
            level.setDayTime(14000);
            com.jasoncian.millenaire_rewrite.entity.ai.original.VisitorGoals.tick(level, village);
            var merchants = village.getActiveVillagers();
            MillVillager merchant = merchants.stream().findFirst().orElse(null);
            int stock = merchant == null ? 0 : com.jasoncian.millenaire_rewrite.content.VillagerTypes
                .get("norman", merchant.getVillagerType()).merchantStock().keySet().stream()
                .map(com.jasoncian.millenaire_rewrite.economy.Goods::get).filter(java.util.Objects::nonNull)
                .mapToInt(g -> com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, market, g)).sum();
            MillenaireRewrite.LOGGER.info("[gametest] market: {} stalls, merchant {} stock {}", stalls,
                merchant == null ? null : merchant.getFullName() + " " + merchant.getVillagerType(), stock);
            Runnable cleanup = () -> {
                com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).removeVillage(village);
                village.getActiveVillagers().forEach(v -> v.discard());
                TestTerrain.setForced(level, centre, false);
            };
            if (stalls == 0 || merchant == null || stock == 0) {
                cleanup.run();
                helper.fail("market got no merchant with goods (stalls " + stalls + ")");
                return;
            }
            level.setDayTime(1000);
            merchant.getGoalManager().forceGoal(new com.jasoncian.millenaire_rewrite.entity.ai.original.VisitorGoals.KeepStall());
            helper.runAtTickTime(600, () -> {
                double d = market.points("stall").stream()
                    .mapToDouble(p -> merchant.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5)).min().orElse(1e9);
                // 住满 5 晚后离开
                for (int i = 0; i < 6 && !merchant.isRemoved(); i++) {
                    com.jasoncian.millenaire_rewrite.entity.ai.original.VisitorGoals.nightAction(merchant);
                }
                MillenaireRewrite.LOGGER.info("[gametest] merchant {} from stall, left {}", Math.sqrt(d), merchant.isRemoved());
                boolean left = merchant.isRemoved();
                cleanup.run();
                if (d > 9) {
                    helper.fail("merchant did not go to a stall (" + Math.sqrt(d) + " blocks)");
                }
                if (!left) {
                    helper.fail("merchant did not leave after 5 nights");
                }
                helper.succeed();
            });
        } catch (java.io.IOException e) {
            TestTerrain.setForced(level, centre, false);
            helper.fail(e.toString());
        }
    }
}
