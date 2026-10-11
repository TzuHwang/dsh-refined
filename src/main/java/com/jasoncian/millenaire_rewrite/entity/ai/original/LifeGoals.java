package com.jasoncian.millenaire_rewrite.entity.ai.original;

import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 原版的生活目标：睡觉（所有村民都有）、回家休息、去休闲建筑社交、和别的村民聊天。
 */
public final class LifeGoals {

    private LifeGoals() {
    }

    /**
     * 原版 GoalSleep：夜里回家，在家里的睡觉点躺下，天亮起来
     */
    public static final class Sleep extends OriginalGoal {

        public Sleep() {
            super("sleep");
            this.canBeDoneInDayTime = false;
            this.canBeDoneAtNight = true;
            this.range = 2.0;
            this.duration = 10;
        }

        @Override
        public int priority(MillVillager villager) {
            return 0;
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            VillageGrowth.PlacedBuilding house = house(villager);
            if (house == null) {
                BlockPos home = villager.getHousePos();
                return home == null ? null : GoalInformation.ofDestination(home);
            }
            // 同一栋房子的村民分开睡在不同的睡觉点
            List<BlockPos> spots = house.points("sleepingPos");
            if (spots.isEmpty()) {
                return GoalInformation.ofBuilding(house.origin, house.origin);
            }
            int index = Math.floorMod((int) villager.getVillagerId(), spots.size());
            return GoalInformation.ofBuilding(spots.get(index), house.origin);
        }

        @Override
        protected boolean act(MillVillager villager) {
            if (!villager.isSleeping()) {
                villager.getNavigation().stop();
                villager.startSleeping(villager.blockPosition());
            }
            // 原版 performNightAction：每晚一次（小孩长大、妻子怀孕）
            if (!villager.nightActionPerformed()) {
                villager.setNightActionPerformed(true);
                FamilyGoals.nightAction(villager);
            }
            // 一直睡到天亮（夜间限制失效时目标结束）
            return false;
        }

        @Override
        public void onComplete(MillVillager villager) {
            wake(villager);
        }

        @Override
        public void onFail(MillVillager villager) {
            wake(villager);
        }

        @Override
        public void onInterrupt(MillVillager villager) {
            wake(villager);
        }

        private static void wake(MillVillager villager) {
            villager.setNightActionPerformed(false);
            if (villager.isSleeping() || villager.hasPose(Pose.SLEEPING)) {
                villager.stopSleeping();
            }
        }
    }

    /**
     * 原版 GoalGoRest：离家超过 5 格时回家待一会；优先级 0，保证村民总有事做
     */
    public static final class GoRest extends OriginalGoal {

        public GoRest() {
            super("gorest");
            this.leisure = true;
            this.range = 10.0;
            this.duration = 200;
        }

        @Override
        public int priority(MillVillager villager) {
            return 0;
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            BlockPos home = villager.getHousePos();
            return home != null && villager.distanceToSqr(home.getX(), home.getY(), home.getZ()) > 25;
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            VillageGrowth.PlacedBuilding house = house(villager);
            if (house != null) {
                return GoalInformation.ofDestination(pointOf(house, "sleepingPos"));
            }
            BlockPos home = villager.getHousePos();
            return home == null ? null : GoalInformation.ofDestination(home);
        }

        @Override
        protected boolean act(MillVillager villager) {
            return true;
        }
    }

    /**
     * 原版 GoalGoSocialise：去随机一栋休闲建筑（标签 leasure）的休闲点，没有时去市政厅
     */
    public static final class GoSocialise extends OriginalGoal {

        @Nullable
        private BlockPos destination;

        public GoSocialise() {
            super("gosocialise");
            this.leisure = true;
            this.range = 5.0;
            this.duration = 200;
        }

        @Override
        public int priority(MillVillager villager) {
            return 5;
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            destination = null;
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            if (destination == null) {
                Village village = village(villager);
                if (village == null) {
                    return null;
                }
                List<VillageGrowth.PlacedBuilding> places = new ArrayList<>(village.getGrowth().buildingsWithTag("leasure"));
                places.addAll(village.getGrowth().buildingsWithTag("leisure"));
                if (places.isEmpty()) {
                    VillageGrowth.PlacedBuilding hall = townHall(villager);
                    if (hall == null) {
                        return null;
                    }
                    places.add(hall);
                }
                VillageGrowth.PlacedBuilding b = places.get(villager.getRandom().nextInt(places.size()));
                destination = b.points("leisurePos").isEmpty() ? pointOf(b, "sellingPos") : pointOf(b, "leisurePos");
            }
            return GoalInformation.ofDestination(destination);
        }

        @Override
        protected boolean act(MillVillager villager) {
            return true;
        }
    }

    /**
     * 原版 GoalGoChat：找附近正在社交的村民，两人面对面聊一会
     */
    public static final class Chat extends OriginalGoal {

        @Nullable
        private MillVillager partner;

        public Chat() {
            super("chat");
            this.leisure = true;
            this.range = 3.0;
            this.duration = 40;
        }

        @Override
        public int priority(MillVillager villager) {
            return 10;
        }

        @Nullable
        private static MillVillager findPartner(MillVillager villager) {
            Village village = village(villager);
            if (village == null) {
                return null;
            }
            for (MillVillager v : village.getActiveVillagers()) {
                if (v != villager && !v.isRemoved() && v.getGoalManager().getCurrentGoal() != null
                    && "gosocialise".equals(v.getGoalManager().getCurrentGoal().getKey()) && v.distanceToSqr(villager) < 25) {
                    return v;
                }
            }
            return null;
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            return findPartner(villager) != null;
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            partner = findPartner(villager);
            if (partner != null) {
                // 对方也停下来聊天（原版把对方的目标也设为 chat）
                Chat other = new Chat();
                other.partner = villager;
                partner.getGoalManager().forceGoal(other);
            }
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            MillVillager p = partner != null ? partner : findPartner(villager);
            return p == null ? null : GoalInformation.ofEntity(p);
        }

        @Override
        protected boolean isStillValidSpecific(MillVillager villager) {
            return partner == null || !partner.isRemoved();
        }

        @Override
        protected boolean act(MillVillager villager) {
            if (partner != null) {
                villager.getLookControl().setLookAt(partner);
            }
            return true;
        }
    }
}
