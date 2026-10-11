package com.jasoncian.millenaire_rewrite.entity.ai.original;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan;
import com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes;
import com.jasoncian.millenaire_rewrite.content.ResidentSpawner;
import com.jasoncian.millenaire_rewrite.content.VillagerTypes;
import com.jasoncian.millenaire_rewrite.economy.BuildingStock;
import com.jasoncian.millenaire_rewrite.economy.Goods;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 原版的家庭与人口：夜里小孩长大、妻子怀孕生子（MillVillager.performNightAction / attemptChildConception），
 * 长大的小孩搬进有空位的房子成为大人（GoalChildBecomeAdult）。
 */
public final class FamilyGoals {

    /** 成长值上限（原版 size 20 时可以成年） */
    public static final int ADULT_SIZE = 20;
    /** 原版 maxChildrenNumber：全村小孩上限 */
    private static final int MAX_CHILDREN = 10;

    private FamilyGoals() {
    }

    @Nullable
    private static VillagerTypes.VillagerType typeOf(MillVillager villager) {
        try {
            return VillagerTypes.get(VillageTypes.cultureFolder(villager.getCulture()), villager.getVillagerType());
        } catch (IOException e) {
            return null;
        }
    }

    // ================ 夜间动作 ================

    /**
     * 原版 performNightAction：每晚睡下时做一次
     */
    public static void nightAction(MillVillager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        Village village = villager.getHomeVillage();
        VillageGrowth.PlacedBuilding house = OriginalGoal.house(villager);
        if (village == null || house == null) {
            return;
        }
        if (villager.isChild() && villager.getChildSize() < ADULT_SIZE) {
            grow(villager, level, house);
        }
        VillagerTypes.VillagerType type = typeOf(villager);
        if (type != null && (type.maleChild() != null || type.femaleChild() != null)) {
            attemptConception(villager, level, village, house, type);
        }
    }

    /**
     * 原版 growSize：每晚长 2；家里有鸡蛋时吃一个，多长 1~5
     */
    private static void grow(MillVillager child, ServerLevel level, VillageGrowth.PlacedBuilding house) {
        int growth = 2;
        Goods.Good egg = new Goods.Good("egg", Items.EGG, null, null);
        if (BuildingStock.take(level, house, egg, 1) == 1) {
            growth += 1 + child.getRandom().nextInt(5);
        }
        child.setChildSize(Math.min(ADULT_SIZE, child.getChildSize() + growth));
    }

    /**
     * 原版 attemptChildConception：家里小孩少于两个、全村小孩没超过上限、丈夫睡在旁边时，有 2/10 的机会怀孕
     */
    private static void attemptConception(MillVillager mother, ServerLevel level, Village village,
                                          VillageGrowth.PlacedBuilding house, VillagerTypes.VillagerType type) {
        List<MillVillager> residents = GoodsGoals.residents(village, house);
        if (residents.stream().filter(MillVillager::isChild).count() > 1) {
            return;
        }
        if (village.getActiveVillagers().stream().filter(MillVillager::isChild).count() > MAX_CHILDREN) {
            return;
        }
        boolean manNearby = false;
        for (MillVillager v : level.getEntitiesOfClass(MillVillager.class, mother.getBoundingBox().inflate(4, 2, 4))) {
            if (v != mother && v.getGender() == MillVillager.GENDER_MALE && !v.isChild()) {
                manNearby = true;
            }
        }
        if (!manNearby || mother.getRandom().nextInt(10) >= 2) {
            return;
        }
        boolean boy = mother.getRandom().nextBoolean();
        String childType = boy ? type.maleChild() : type.femaleChild();
        if (childType == null) {
            childType = boy ? type.femaleChild() : type.maleChild();
        }
        try {
            MillVillager child = ResidentSpawner.spawnResident(level, village, type.culture(), childType,
                mother.getHousePos() != null ? mother.getHousePos() : mother.blockPosition());
            if (child != null) {
                child.setFamilyName(mother.getFamilyName());
                child.setChild(true);
                child.setChildSize(0);
                child.setCustomName(net.minecraft.network.chat.Component.literal(child.getFullName()));
                MillenaireRewrite.LOGGER.info("Village {}: {} had a child, {}", village.getName(), mother.getFullName(),
                    child.getFullName());
            }
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.warn("Cannot create child of type {}: {}", childType, e.getMessage());
        }
    }

    // ================ 成年 ================

    /**
     * 原版 GoalChildBecomeAdult：长大的小孩搬进有空位的房子（同性别的居民名额没满，且没有同姓的异性大人），
     * 成为那栋房子对应的居民类型
     */
    public static final class BecomeAdult extends OriginalGoal {

        @Nullable
        private VillageGrowth.PlacedBuilding newHouse;
        @Nullable
        private String newType;

        public BecomeAdult() {
            super("becomeadult");
            this.range = 3.0;
        }

        @Override
        public int priority(MillVillager villager) {
            return 100;
        }

        private boolean find(MillVillager child) {
            newHouse = null;
            newType = null;
            Village village = village(child);
            VillageGrowth.PlacedBuilding home = house(child);
            if (village == null || !child.isChild() || child.getChildSize() < ADULT_SIZE) {
                return false;
            }
            boolean male = child.getGender() == MillVillager.GENDER_MALE;
            List<VillageGrowth.PlacedBuilding> candidates = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (VillageGrowth.PlacedBuilding b : village.getGrowth().getBuildings()) {
                if (b == home || b.subBuilding) {
                    continue;
                }
                String slot = freeSlot(village, b, male, child.getFamilyName());
                if (slot != null) {
                    candidates.add(b);
                    types.add(slot);
                }
            }
            if (candidates.isEmpty()) {
                return false;
            }
            int i = child.getRandom().nextInt(candidates.size());
            newHouse = candidates.get(i);
            newType = types.get(i);
            return true;
        }

        /**
         * 原版 canChildMoveIn：房子里这个性别的居民名额（图纸 initial.male/female）还没住满时，返回空着的类型
         */
        @Nullable
        private static String freeSlot(Village village, VillageGrowth.PlacedBuilding b, boolean male, String familyName) {
            List<String> slots;
            try {
                BuildingPlan plan = BuildingPlan.get(VillageTypes.cultureFolder(village.getCulture()), b.key, b.variation, 0);
                slots = male ? plan.maleResidents : plan.femaleResidents;
            } catch (IOException e) {
                return null;
            }
            if (slots.isEmpty()) {
                return null;
            }
            int sameSex = 0;
            for (MillVillager v : GoodsGoals.residents(village, b)) {
                if (v.isChild()) {
                    continue;
                }
                boolean vMale = v.getGender() == MillVillager.GENDER_MALE;
                if (vMale != male && v.getFamilyName().equals(familyName)) {
                    return null;
                }
                if (vMale == male) {
                    sameSex++;
                }
            }
            return sameSex < slots.size() ? slots.get(sameSex) : null;
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            return find(villager);
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            find(villager);
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            return newHouse == null ? null : GoalInformation.ofBuilding(pointOf(newHouse, "sleepingPos"), newHouse.origin);
        }

        @Override
        protected boolean act(MillVillager child) {
            Village village = village(child);
            if (village == null || newHouse == null || newType == null || !(child.level() instanceof ServerLevel level)) {
                return true;
            }
            try {
                BlockPos home = pointOf(newHouse, "sleepingPos");
                MillVillager adult = ResidentSpawner.spawnResident(level, village,
                    VillageTypes.cultureFolder(village.getCulture()), newType, home);
                if (adult != null) {
                    adult.setFirstName(child.getFirstName());
                    adult.setFamilyName(child.getFamilyName());
                    adult.moveTo(child.getX(), child.getY(), child.getZ(), child.getYRot(), 0);
                    adult.setCustomName(net.minecraft.network.chat.Component.literal(adult.getFullName()));
                    MillenaireRewrite.LOGGER.info("Village {}: {} grew up and moved into {} as {}", village.getName(),
                        child.getFullName(), newHouse.key, newType);
                    village.unregisterVillager(child);
                    child.discard();
                }
            } catch (IOException e) {
                MillenaireRewrite.LOGGER.warn("Cannot create adult of type {}: {}", newType, e.getMessage());
            }
            return true;
        }
    }
}
