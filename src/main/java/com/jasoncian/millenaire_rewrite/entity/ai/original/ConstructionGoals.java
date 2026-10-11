package com.jasoncian.millenaire_rewrite.entity.ai.original;

import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.entity.ai.MillGoal;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;

import javax.annotation.Nullable;

/**
 * 原版的施工：建造者（诺曼是妻子）去市政厅取齐材料（GoalGetResourcesForBuild），
 * 再到工地一块一块地盖（GoalConstructionStepByStep），放下最后一块时扣掉材料。
 */
public final class ConstructionGoals {

    /** 离施工点多远时边走边盖（原版卡住时 30 格内直接放） */
    private static final double BUILD_REACH = 24.0;
    /** 两个施工动作之间的 tick（原版每块 7~16 tick，空气和泥土快 4 倍） */
    private static final int TICKS_PER_ACTION = 4;

    private ConstructionGoals() {
    }

    @Nullable
    private static VillageGrowth growth(MillVillager villager) {
        Village village = villager.getHomeVillage();
        return village == null ? null : village.getGrowth();
    }

    /**
     * 原版 GoalGetResourcesForBuild：有工程在等材料时，去市政厅把材料拿到身上
     */
    public static final class GetResourcesForBuild extends OriginalGoal {

        public GetResourcesForBuild() {
            super("getresourcesforbuild");
            this.range = 3.0;
            this.duration = 40;
        }

        @Override
        public int priority(MillVillager villager) {
            return 1000;
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            VillageGrowth g = growth(villager);
            return g != null && g.isBuilderMode() && g.projectNeedsSupplies() && g.townHall() != null;
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            VillageGrowth g = growth(villager);
            if (g != null) {
                g.claimProject(villager);
            }
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            VillageGrowth g = growth(villager);
            VillageGrowth.PlacedBuilding hall = g == null ? null : g.townHall();
            return hall == null ? null : GoalInformation.ofBuilding(pointOf(hall, "sellingPos"), hall.origin);
        }

        @Override
        protected boolean act(MillVillager villager) {
            VillageGrowth g = growth(villager);
            if (g != null && villager.level() instanceof ServerLevel level && !g.takeSupplies(level, villager)) {
                // 材料不够（被别人拿走了）：放弃，下次再来
                g.releaseProject(villager);
            }
            return true;
        }

        @Override
        public MillGoal nextGoal(MillVillager villager) {
            VillageGrowth g = growth(villager);
            return g != null && g.isBuilderOf(villager) ? new Construction() : null;
        }

        @Override
        public void onFail(MillVillager villager) {
            release(villager);
        }

        @Override
        public void onInterrupt(MillVillager villager) {
            release(villager);
        }

        private static void release(MillVillager villager) {
            VillageGrowth g = growth(villager);
            if (g != null) {
                g.releaseProject(villager);
            }
        }
    }

    /**
     * 原版 GoalConstructionStepByStep：带着材料的建造者在工地上一块一块地盖
     */
    public static final class Construction extends OriginalGoal {

        private int ticks;

        public Construction() {
            super("construction");
            this.range = 6.0;
        }

        @Override
        public int priority(MillVillager villager) {
            return 1500;
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            VillageGrowth g = growth(villager);
            return g != null && g.isBuilderOf(villager);
        }

        @Override
        protected boolean isStillValidSpecific(MillVillager villager) {
            return isPossibleSpecific(villager);
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            ticks = 0;
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            VillageGrowth g = growth(villager);
            if (g == null || !(villager.level() instanceof ServerLevel level)) {
                return null;
            }
            BlockPos site = g.projectSite(level);
            return site == null ? null : GoalInformation.ofDestination(site);
        }

        @Override
        protected boolean act(MillVillager villager) {
            VillageGrowth g = growth(villager);
            if (g == null || !(villager.level() instanceof ServerLevel level)) {
                return true;
            }
            BlockPos site = g.projectSite(level);
            if (site == null) {
                return true;
            }
            double d = villager.distanceToSqr(site.getX() + 0.5, site.getY(), site.getZ() + 0.5);
            // 跟着施工点走（原版每块都重设目的地）；离得近时不换，免得走走停停
            if (d > range * range) {
                villager.getGoalManager().updateDestination(site);
            }
            villager.getLookControl().setLookAt(site.getX() + 0.5, site.getY() + 0.5, site.getZ() + 0.5);
            if (++ticks % TICKS_PER_ACTION != 0 || d > BUILD_REACH * BUILD_REACH) {
                return false;
            }
            villager.swing(InteractionHand.MAIN_HAND);
            return g.buildStep(level, villager);
        }
    }
}
