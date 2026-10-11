package com.jasoncian.millenaire_rewrite.gametest;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.economy.BuildingStock;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 原版施工：村民从市政厅取材料，在工地一块一块地盖，完工时扣掉材料。
 */
@GameTestHolder(MillenaireRewrite.MOD_ID)
@PrefixGameTestTemplate(false)
public class ConstructionTests {

    /** 每个"天"在测试里占的游戏 tick（白天占大半，施工只在白天） */
    private static final int TEST_DAY = 1500;
    private static final int DAYS = 8;

    /**
     * 诺曼村：市政厅放好下一个工程的材料，妻子取走材料并把房子盖完
     */
    @GameTest(template = "empty", timeoutTicks = TEST_DAY * DAYS + 400, batch = "time_construction")
    public static void builderFetchesResourcesAndBuilds(GameTestHelper helper) {
        if (TestFilter.skip(helper, "builderFetchesResourcesAndBuilds")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 22000));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        var village = com.jasoncian.millenaire_rewrite.worldgen.VillageGenerator.forceGenerateVillage(level, centre,
            com.jasoncian.millenaire_rewrite.entity.culture.Culture.NORMAN, null);
        if (village == null) {
            TestTerrain.setForced(level, centre, false);
            helper.fail("village generation failed");
            return;
        }
        var growth = village.getGrowth();
        level.setDayTime(1000);
        growth.tick(level);
        if (!growth.isConstructing() && !growth.startNextProject(level)) {
            TestTerrain.setForced(level, centre, false);
            helper.fail("no project to build");
            return;
        }
        String project = growth.getCurrentProjectKey();
        var cost = growth.projectCost();
        var hall = growth.townHall();
        int before = cost.keySet().stream().mapToInt(g -> BuildingStock.count(level, hall, g)).sum();
        cost.forEach((good, n) -> BuildingStock.store(level, hall, good, n));
        int stocked = cost.entrySet().stream().mapToInt(e -> BuildingStock.count(level, hall, e.getKey())).sum();
        int needed = cost.values().stream().mapToInt(Integer::intValue).sum();
        MillenaireRewrite.LOGGER.info("[gametest] construction: project {} cost {} (stocked {}/{})", project, cost, stocked,
            needed);
        int previous = com.jasoncian.millenaire_rewrite.command.TickWarp.factor();
        com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(10);
        com.jasoncian.millenaire_rewrite.village.VillageGrowth.buildSpeed = 10;
        for (int d = 0; d < DAYS; d++) {
            int t = 1 + d * TEST_DAY;
            helper.runAtTickTime(t, () -> level.setDayTime(1000));
            helper.runAtTickTime(t + TEST_DAY - 300, () -> level.setDayTime(14000));
            helper.runAtTickTime(t + TEST_DAY / 2, () -> MillenaireRewrite.LOGGER.info(
                "[gametest] construction: project {} {}, builders {}", growth.getCurrentProjectKey(), growth.projectProgress(level),
                village.getActiveVillagers().stream().filter(v -> v.getGoalManager().getCurrentGoal() != null
                    && v.getGoalManager().getCurrentGoal().getKey().matches("construction|getresourcesforbuild"))
                    .map(v -> v.getFullName() + " " + v.getGoalManager().getCurrentGoal().getKey() + " at "
                        + v.blockPosition().toShortString() + " dest " + v.getGoalManager().getDestination()).toList()));
        }
        helper.runAtTickTime(TEST_DAY * DAYS + 100, () -> {
            com.jasoncian.millenaire_rewrite.command.TickWarp.setFactor(previous);
            com.jasoncian.millenaire_rewrite.village.VillageGrowth.buildSpeed = 1;
            String now = growth.getCurrentProjectKey();
            int left = cost.keySet().stream().mapToInt(g -> BuildingStock.count(level, hall, g)).sum();
            MillenaireRewrite.LOGGER.info("[gametest] construction: {} -> {}, materials left in town hall {}/{}",
                project, now, left, stocked);
            com.jasoncian.millenaire_rewrite.village.VillageManager.get(level).removeVillage(village);
            village.getActiveVillagers().forEach(v -> v.discard());
            TestTerrain.setForced(level, centre, false);
            if (project.equals(now)) {
                helper.fail("project " + project + " was not finished");
            }
            // 材料在取货时从市政厅拿走（伐木工之后还会送原木来，只检查确实少了）
            if (needed > 0 && left >= stocked) {
                helper.fail("materials were not used (" + left + " left, " + before + " before stocking)");
            }
            helper.succeed();
        });
    }

    /**
     * /millenaire spawn 指令：建好市政厅并生成居民（玩家回报过 spawn 后没有村民）
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void spawnCommandCreatesVillagers(GameTestHelper helper) {
        if (TestFilter.skip(helper, "spawnCommandCreatesVillagers")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 22600));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        var ground = TestTerrain.ground(centre);
        var source = level.getServer().createCommandSourceStack().withLevel(level)
            .withPosition(net.minecraft.world.phys.Vec3.atBottomCenterOf(ground)).withPermission(4).withSuppressedOutput();
        int result = level.getServer().getCommands().performPrefixedCommand(source, "millenaire spawn norman");
        var manager = com.jasoncian.millenaire_rewrite.village.VillageManager.get(level);
        helper.runAtTickTime(100, () -> {
            var box = new net.minecraft.world.phys.AABB(ground).inflate(96);
            var villagers = level.getEntitiesOfClass(com.jasoncian.millenaire_rewrite.entity.MillVillager.class, box);
            var village = manager.getAllVillages().stream()
                .filter(v -> v.getTownHallPos().distSqr(ground) < 96 * 96).findFirst().orElse(null);
            MillenaireRewrite.LOGGER.info("[gametest] spawn command: result {}, village {}, {} villagers in world, {} registered",
                result, village == null ? null : village.getName(), villagers.size(),
                village == null ? 0 : village.getActiveVillagers().size());
            if (village != null) {
                manager.removeVillage(village);
            }
            villagers.forEach(v -> v.discard());
            TestTerrain.setForced(level, centre, false);
            if (village == null) {
                helper.fail("spawn command created no village");
            }
            if (villagers.isEmpty()) {
                helper.fail("spawn command created no villagers");
            }
            helper.succeed();
        });
    }
}
