package com.jasoncian.millenaire_rewrite.entity.ai.original;

import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * 原版的战斗目标。
 */
public final class CombatGoals {

    private CombatGoals() {
    }

    /**
     * 原版 GoalHuntMonster：去打市政厅 50 格内地面上的怪物（不打苦力怕），走近后交给近战攻击
     */
    public static final class HuntMonster extends OriginalGoal {

        private static final int RANGE = 50;
        @Nullable
        private Monster target;

        public HuntMonster() {
            super("huntmonster");
            this.range = 4.0;
            // 原版没有限制昼夜：夜里怪物更多
            this.canBeDoneAtNight = true;
            this.tags.add("tag_fighting");
        }

        @Override
        public int priority(MillVillager villager) {
            return 50;
        }

        @Nullable
        private static Monster closestMonster(MillVillager villager) {
            BlockPos centre = villager.getVillageCentre();
            Monster best = null;
            for (Monster m : villager.level().getEntitiesOfClass(Monster.class, new AABB(centre).inflate(RANGE, 10, RANGE))) {
                if (!m.isAlive() || m instanceof Creeper) {
                    continue;
                }
                // 原版：只打地面以上的（不追进洞穴）
                int ground = villager.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, m.getBlockX(), m.getBlockZ());
                if (m.getY() < ground - 1) {
                    continue;
                }
                if (best == null || m.distanceToSqr(villager) < best.distanceToSqr(villager)) {
                    best = m;
                }
            }
            return best;
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            return closestMonster(villager) != null;
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            target = closestMonster(villager);
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            Monster m = target != null ? target : closestMonster(villager);
            return m == null ? null : GoalInformation.ofEntity(m);
        }

        @Override
        protected boolean isStillValidSpecific(MillVillager villager) {
            return target != null && target.isAlive();
        }

        @Override
        protected boolean act(MillVillager villager) {
            if (target != null && target.isAlive()) {
                villager.setTarget(target);
            }
            return true;
        }
    }
}
