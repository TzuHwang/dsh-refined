package com.jasoncian.millenaire_rewrite.entity.ai.work;

import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 砍伐村里树林（任何建筑的种树点）上长大的树，并补种树苗（原版 chopTrees + plantSaplings）。
 * 伐木工住在伐木工小屋，树种在另一栋树林建筑里，所以不限于自家建筑。
 */
public class GoalChopTrees extends WorkGoal {

    /** 种树点类型 -> 补种的树苗 */
    private static final Map<String, Block> SAPLINGS = Map.ofEntries(
        Map.entry("oakspawn", Blocks.OAK_SAPLING),
        Map.entry("appletreespawn", Blocks.OAK_SAPLING),
        Map.entry("olivetreespawn", Blocks.OAK_SAPLING),
        Map.entry("pistachiotreespawn", Blocks.OAK_SAPLING),
        Map.entry("pinespawn", Blocks.SPRUCE_SAPLING),
        Map.entry("birchspawn", Blocks.BIRCH_SAPLING),
        Map.entry("junglespawn", Blocks.JUNGLE_SAPLING),
        Map.entry("acaciaspawn", Blocks.ACACIA_SAPLING),
        Map.entry("darkoakspawn", Blocks.DARK_OAK_SAPLING),
        Map.entry("cherrytreespawn", Blocks.CHERRY_SAPLING),
        Map.entry("sakuratreespawn", Blocks.CHERRY_SAPLING)
    );
    /** 一棵树最多砍的原木数和水平范围 */
    private static final int MAX_LOGS = 128;
    private static final int MAX_HORIZONTAL = 6;

    public GoalChopTrees() {
        super("choptrees", 40, 40);
    }

    @Nullable
    @Override
    protected BlockPos findTarget(MillVillager villager) {
        Village village = villager.getHomeVillage();
        if (village == null) {
            return null;
        }
        Level level = villager.level();
        List<BlockPos> spawns = new ArrayList<>();
        for (VillageGrowth.PlacedBuilding b : village.getGrowth().getBuildings()) {
            SAPLINGS.keySet().forEach(type -> spawns.addAll(b.points(type)));
        }
        return WorkSites.nearest(villager, spawns,
            p -> reachable(villager, p) && level.getBlockState(p).is(BlockTags.LOGS));
    }

    @Override
    protected void work(MillVillager villager, BlockPos base) {
        Level level = villager.level();
        if (!level.getBlockState(base).is(BlockTags.LOGS)) {
            return;
        }
        // 从树根沿相连的原木向上、向四周找整棵树
        Set<BlockPos> logs = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        logs.add(base);
        while (!queue.isEmpty() && logs.size() < MAX_LOGS) {
            BlockPos p = queue.poll();
            for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, 0, -1), p.offset(1, 1, 1))) {
                if (Math.abs(n.getX() - base.getX()) > MAX_HORIZONTAL || Math.abs(n.getZ() - base.getZ()) > MAX_HORIZONTAL) {
                    continue;
                }
                BlockPos np = n.immutable();
                if (!logs.contains(np) && level.getBlockState(np).is(BlockTags.LOGS)) {
                    logs.add(np);
                    queue.add(np);
                }
            }
        }
        for (BlockPos p : logs) {
            BlockState state = level.getBlockState(p);
            villager.addToInventory(state.getBlock().asItem(), 1);
            level.destroyBlock(p, false);
        }
        // 补种树苗（按该种树点的树种）
        Village village = villager.getHomeVillage();
        String type = "oakspawn";
        if (village != null) {
            for (VillageGrowth.PlacedBuilding b : village.getGrowth().getBuildings()) {
                for (String t : SAPLINGS.keySet()) {
                    if (b.points(t).contains(base)) {
                        type = t;
                    }
                }
            }
        }
        if (level.getBlockState(base).isAir()) {
            level.setBlock(base, SAPLINGS.get(type).defaultBlockState(), 3);
        }
    }
}
