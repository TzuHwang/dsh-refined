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

    /** 村庄选址半径约 72 格，强制加载周围 6 个区块 */
    private static final int CHUNK_RADIUS = 6;

    private static Village createVillage(GameTestHelper helper, int relativeZ, Culture culture) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(0, 0, relativeZ));
        setForced(level, pos, true);
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
        ChunkPos centre = new ChunkPos(pos);
        for (int cx = -CHUNK_RADIUS; cx <= CHUNK_RADIUS; cx++) {
            for (int cz = -CHUNK_RADIUS; cz <= CHUNK_RADIUS; cz++) {
                level.setChunkForced(centre.x + cx, centre.z + cz, forced);
            }
        }
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
            if (village.getActiveVillagerCount() <= initialResidents) {
                helper.fail("no new residents moved in");
            }
        } finally {
            cleanup(level, village);
        }
        helper.succeed();
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
