package com.jasoncian.millenaire_rewrite.entity.ai.original;

import com.jasoncian.millenaire_rewrite.economy.BuildingStock;
import com.jasoncian.millenaire_rewrite.economy.Goods;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原版写死在程序里的工作目标：繁殖牲畜、剪羊毛、伐木工种树苗、种/收甘蔗。
 */
public final class WorkSpecials {

    private WorkSpecials() {
    }

    // ================ 繁殖 ================

    /**
     * 原版 GoalBreedAnimals：家里（标签 sheeps / cattle / pigs / chicken）有饲料、成年牲畜至少两头、
     * 总数少于生成点两倍时，喂一头让它发情
     */
    public static final class BreedAnimals extends OriginalGoal {

        private record Kind(String tag, EntityType<?> type, String spawn, Item[] food) {
        }

        private static final Kind[] KINDS = {
            new Kind("sheeps", EntityType.SHEEP, "sheepspawn", new Item[] {Items.WHEAT}),
            new Kind("cattle", EntityType.COW, "cowspawn", new Item[] {Items.WHEAT}),
            new Kind("pigs", EntityType.PIG, "pigspawn", new Item[] {Items.CARROT}),
            new Kind("chicken", EntityType.CHICKEN, "chickenspawn", new Item[] {Items.WHEAT_SEEDS}),
        };

        @Nullable
        private Animal target;
        @Nullable
        private Item food;

        public BreedAnimals() {
            super("breed");
            this.range = 2.0;
            this.duration = 20;
        }

        @Override
        public int priority(MillVillager villager) {
            return 50;
        }

        private boolean find(MillVillager villager) {
            target = null;
            VillageGrowth.PlacedBuilding house = house(villager);
            Village village = village(villager);
            if (house == null || village == null || !(villager.level() instanceof ServerLevel level)) {
                return false;
            }
            for (Kind kind : KINDS) {
                if (!village.getGrowth().tagsOf(house).contains(kind.tag)) {
                    continue;
                }
                Item available = null;
                for (Item f : kind.food) {
                    if (BuildingStock.count(level, house, goodOf(f)) > 0) {
                        available = f;
                    }
                }
                if (available == null) {
                    continue;
                }
                int wanted = house.points(kind.spawn).size();
                List<Animal> animals = new ArrayList<>();
                for (Entity e : level.getEntities((Entity) null, new AABB(house.origin).inflate(15, 10, 15),
                    e -> e.getType() == kind.type && e.isAlive())) {
                    animals.add((Animal) e);
                }
                long adults = animals.stream().filter(a -> a.getAge() == 0).count();
                if (adults >= 2 && animals.size() < wanted * 2) {
                    for (Animal a : animals) {
                        if (a.getAge() == 0 && !a.isInLove()) {
                            target = a;
                            food = available;
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static Goods.Good goodOf(Item item) {
            return new Goods.Good("breedfood", item, null, null);
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
            return target == null ? null : GoalInformation.ofEntity(target);
        }

        @Override
        public ItemStack[] getHeldItemsTraveling(MillVillager villager) {
            return food == null ? new ItemStack[0] : new ItemStack[] {new ItemStack(food)};
        }

        @Override
        protected boolean isStillValidSpecific(MillVillager villager) {
            return target != null && target.isAlive();
        }

        @Override
        protected boolean act(MillVillager villager) {
            VillageGrowth.PlacedBuilding house = house(villager);
            if (target != null && food != null && house != null && villager.level() instanceof ServerLevel level
                && target.getAge() == 0 && !target.isInLove()
                && BuildingStock.take(level, house, goodOf(food), 1) == 1) {
                target.setInLove(null);
                villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            return true;
        }
    }

    // ================ 剪羊毛 ================

    /**
     * 原版 GoalShearSheep：家里（标签 sheeps）附近没剪过的成年羊，每只得 3 个对应颜色的羊毛
     */
    public static final class ShearSheep extends OriginalGoal {

        @Nullable
        private Sheep target;

        public ShearSheep() {
            super("shearsheep");
            this.range = 5.0;
        }

        @Override
        public int priority(MillVillager villager) {
            return 50;
        }

        @Nullable
        private Sheep closestSheep(MillVillager villager) {
            VillageGrowth.PlacedBuilding house = house(villager);
            Village village = village(villager);
            if (house == null || village == null || !village.getGrowth().tagsOf(house).contains("sheeps")) {
                return null;
            }
            Sheep best = null;
            for (Sheep s : villager.level().getEntitiesOfClass(Sheep.class, new AABB(house.origin).inflate(30, 10, 30))) {
                if (s.isAlive() && !s.isBaby() && !s.isSheared()
                    && (best == null || s.distanceToSqr(villager) < best.distanceToSqr(villager))) {
                    best = s;
                }
            }
            return best;
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            return closestSheep(villager) != null;
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            target = closestSheep(villager);
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            Sheep s = target != null ? target : closestSheep(villager);
            return s == null ? null : GoalInformation.ofEntity(s);
        }

        @Override
        public ItemStack[] getHeldItemsTraveling(MillVillager villager) {
            return new ItemStack[] {new ItemStack(Items.SHEARS)};
        }

        @Override
        protected boolean act(MillVillager villager) {
            for (Sheep s : villager.level().getEntitiesOfClass(Sheep.class, villager.getBoundingBox().inflate(4))) {
                if (s.isAlive() && !s.isBaby() && !s.isSheared()) {
                    villager.addToInventory(woolOf(s.getColor()), 3);
                    s.setSheared(true);
                    villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
            }
            return true;
        }

        private static Item woolOf(net.minecraft.world.item.DyeColor color) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                new net.minecraft.resources.ResourceLocation("minecraft", color.getName() + "_wool"));
        }
    }

    // ================ 伐木工种树苗 ================

    /**
     * 原版 GoalLumbermanPlantSaplings：在树林（标签 grove）空着的种树点种回树苗
     */
    public static final class PlantSaplings extends OriginalGoal {

        @Nullable
        private BlockPos spot;
        @Nullable
        private Block sapling;

        public PlantSaplings() {
            super("plantsaplings");
            this.range = 3.0;
            this.duration = 20;
        }

        @Override
        public int priority(MillVillager villager) {
            return 50;
        }

        private boolean find(MillVillager villager) {
            spot = null;
            Village village = village(villager);
            if (village == null) {
                return false;
            }
            double best = Double.MAX_VALUE;
            for (VillageGrowth.PlacedBuilding grove : village.getGrowth().buildingsWithTag("grove")) {
                for (Map.Entry<String, List<BlockPos>> e : grove.points.entrySet()) {
                    Block s = com.jasoncian.millenaire_rewrite.entity.ai.generic.FarmGoalTypes.PlantSapling.saplingFor(e.getKey());
                    if (s == null) {
                        continue;
                    }
                    for (BlockPos p : e.getValue()) {
                        BlockState state = villager.level().getBlockState(p);
                        boolean free = state.isAir() || state.is(Blocks.SNOW)
                            || state.canBeReplaced() && !state.is(BlockTags.SAPLINGS);
                        double d = villager.distanceToSqr(p.getX(), p.getY(), p.getZ());
                        if (free && d < best) {
                            best = d;
                            spot = p;
                            sapling = s;
                        }
                    }
                }
            }
            return spot != null;
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
            return spot == null ? null : GoalInformation.ofDestination(spot);
        }

        @Override
        public ItemStack[] getHeldItemsTraveling(MillVillager villager) {
            return sapling == null ? new ItemStack[0] : new ItemStack[] {new ItemStack(sapling)};
        }

        @Override
        protected boolean act(MillVillager villager) {
            if (spot != null && sapling != null) {
                BlockState state = villager.level().getBlockState(spot);
                if (state.isAir() || state.is(Blocks.SNOW) || state.canBeReplaced() && !state.is(BlockTags.SAPLINGS)) {
                    villager.level().setBlock(spot, sapling.defaultBlockState(), Block.UPDATE_ALL);
                    villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
            }
            return true;
        }
    }

    // ================ 甘蔗 ================

    /**
     * 原版 GoalIndianPlantSugarCane / GoalIndianHarvestSugarCane：在甘蔗园（标签 sugarplantation）的甘蔗点
     * 种甘蔗；长到两格以上时收掉上面的部分
     */
    public static final class SugarCane extends OriginalGoal {

        private final boolean harvest;
        @Nullable
        private BlockPos spot;

        public SugarCane(boolean harvest) {
            super(harvest ? "harvestsugarcane" : "plantsugarcane");
            this.harvest = harvest;
            this.range = 3.0;
            this.duration = 20;
            this.tags.add("tag_agriculture");
            this.maxSimultaneousTotal = 3;
        }

        @Override
        public int priority(MillVillager villager) {
            return harvest ? 60 : 50;
        }

        private boolean find(MillVillager villager) {
            spot = null;
            Village village = village(villager);
            if (village == null) {
                return false;
            }
            double best = Double.MAX_VALUE;
            for (VillageGrowth.PlacedBuilding b : village.getGrowth().buildingsWithTag("sugarplantation")) {
                for (BlockPos soil : b.points("sugarcanesoil")) {
                    BlockState first = villager.level().getBlockState(soil.above());
                    BlockState second = villager.level().getBlockState(soil.above(2));
                    boolean ok = harvest ? first.is(Blocks.SUGAR_CANE) && second.is(Blocks.SUGAR_CANE) : first.isAir();
                    double d = villager.distanceToSqr(soil.getX(), soil.getY(), soil.getZ());
                    if (ok && d < best) {
                        best = d;
                        spot = soil.above();
                    }
                }
            }
            return spot != null;
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
            return spot == null ? null : GoalInformation.ofDestination(spot);
        }

        @Override
        public ItemStack[] getHeldItemsTraveling(MillVillager villager) {
            return new ItemStack[] {new ItemStack(Items.SUGAR_CANE)};
        }

        @Override
        protected boolean act(MillVillager villager) {
            if (spot == null) {
                return true;
            }
            var level = villager.level();
            if (harvest) {
                // 留下最底下一节
                BlockPos p = spot.above();
                while (level.getBlockState(p).is(Blocks.SUGAR_CANE)) {
                    p = p.above();
                }
                for (BlockPos q = p.below(); q.getY() > spot.getY(); q = q.below()) {
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    villager.addToInventory(Items.SUGAR_CANE, 1);
                }
            } else if (level.getBlockState(spot).isAir() && Blocks.SUGAR_CANE.defaultBlockState().canSurvive(level, spot)) {
                level.setBlock(spot, Blocks.SUGAR_CANE.defaultBlockState(), Block.UPDATE_ALL);
            }
            villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return true;
        }
    }
}
