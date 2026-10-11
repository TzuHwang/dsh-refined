package com.jasoncian.millenaire_rewrite.entity.ai.generic;

import com.jasoncian.millenaire_rewrite.content.Crops;
import com.jasoncian.millenaire_rewrite.economy.BuildingStock;
import com.jasoncian.millenaire_rewrite.economy.Goods;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 原版的农牧通用目标：收成（harvesting）、种植（planting）、采集方块（gatherblocks）、
 * 屠宰牲畜（slaughteranimal）、种树苗（plantsapling）。
 */
public final class FarmGoalTypes {

    private FarmGoalTypes() {
    }

    @Nullable
    public static GenericGoal create(GenericGoalConfig config) {
        return switch (config.type) {
            case "harvesting" -> new Harvesting(config);
            case "planting" -> new Planting(config);
            case "gatherblocks" -> new GatherBlocks(config);
            case "slaughteranimal" -> new SlaughterAnimal(config);
            case "plantsapling" -> new PlantSapling(config);
            default -> null;
        };
    }

    /** 原版 BonusItem：商品,几率（0~100）[,建筑标签] */
    record Bonus(Goods.Good good, int chance, @Nullable String tag) {
    }

    static List<Bonus> bonuses(GenericGoalConfig config, String name) {
        List<Bonus> list = new ArrayList<>();
        for (String line : config.all(name)) {
            String[] p = line.split(",");
            Goods.Good g = Goods.get(p[0].trim());
            if (g == null) {
                continue;
            }
            int chance = p.length > 1 ? Integer.parseInt(p[1].trim()) : 100;
            list.add(new Bonus(g, chance, p.length > 2 ? p[2].trim().toLowerCase() : null));
        }
        return list;
    }

    static void giveBonuses(MillVillager villager, List<Bonus> bonuses, @Nullable VillageGrowth.PlacedBuilding b,
                            @Nullable ServerLevel level, boolean intoBuilding) {
        for (Bonus bonus : bonuses) {
            if (bonus.tag != null && (b == null || !village(villager).getGrowth().tagsOf(b).contains(bonus.tag))) {
                continue;
            }
            if (villager.getRandom().nextInt(100) <= bonus.chance) {
                if (intoBuilding && b != null && level != null && BuildingStock.store(level, b, bonus.good, 1) == 1) {
                    continue;
                }
                villager.addGood(bonus.good, 1);
            }
        }
    }

    private static com.jasoncian.millenaire_rewrite.village.Village village(MillVillager villager) {
        return villager.getHomeVillage();
    }

    /** 作物类型对应的耕作点（原版 getSoilPoints(cropType)） */
    static List<BlockPos> soils(VillageGrowth.PlacedBuilding b, @Nullable String cropType) {
        if ("flower".equals(cropType)) {
            List<BlockPos> list = new ArrayList<>(b.points("flowersoil"));
            list.addAll(b.points("grass"));
            return list;
        }
        String soil = Crops.soilForCropType(cropType);
        return soil == null ? List.of() : b.points(soil);
    }

    /** 离村民最近的满足条件的点 */
    @Nullable
    static BlockPos closest(MillVillager villager, List<BlockPos> points, java.util.function.Predicate<BlockPos> ok) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos p : points) {
            if (ok.test(p)) {
                double d = villager.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = p;
                }
            }
        }
        return best;
    }

    // ================ 收成 ================

    /**
     * 原版 GoalGenericHarvestCrop：收掉成熟的作物（不补种，由种植目标补种），按几率得到收获物；
     * 一块收完接着收下一块，优先级为 1000 - 距离
     */
    public static final class Harvesting extends GenericGoal {
        @Nullable
        private final String cropType;
        @Nullable
        private final BlockState harvestState;
        private final List<Bonus> harvestItems;

        Harvesting(GenericGoalConfig config) {
            super(config);
            this.cropType = config.string("croptype");
            this.harvestState = LegacyStates.parse(config.string("harvestblockstate"));
            this.harvestItems = bonuses(config, "harvestitem");
            this.duration = config.msParam("duration", 100);
            this.tags.add("tag_agriculture");
        }

        private boolean ripe(ServerLevel level, BlockPos soil) {
            BlockState crop = level.getBlockState(soil.above());
            if (harvestState != null) {
                return crop.equals(harvestState) || crop.getBlock() == harvestState.getBlock() && !(crop.getBlock() instanceof CropBlock);
            }
            return crop.getBlock() instanceof CropBlock c && c.isMaxAge(crop) && cropBlockMatches(crop.getBlock());
        }

        private boolean cropBlockMatches(Block block) {
            String soil = Crops.soilForCropType(cropType);
            Block expected = soil == null ? null : Crops.cropForSoil(soil);
            return expected == null || expected == block;
        }

        @Override
        public int priority(MillVillager villager) {
            return destPos == null ? 0 : (int) (1000 - Math.sqrt(villager.distanceToSqr(destPos.getX(), destPos.getY(), destPos.getZ())));
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            BlockPos soil = closest(villager, soils(b, cropType), p -> ripe(level, p));
            return soil == null ? null : soil.above();
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (destPos != null && ripe(level, destPos.below())) {
                giveBonuses(villager, harvestItems, b, level, false);
                BlockState crop = level.getBlockState(destPos);
                level.setBlock(destPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                if (crop.getBlock() instanceof DoublePlantBlock) {
                    level.setBlock(destPos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            // 原版：还有别的成熟作物就接着收
            BlockPos next = destinationIn(villager, level, b);
            if (next != null) {
                destPos = next;
                return false;
            }
            return true;
        }
    }

    // ================ 种植 ================

    /**
     * 原版 GoalGenericPlantCrop：在空的耕作点种下作物，消耗一粒种子（身上或建筑里）
     */
    public static final class Planting extends GenericGoal {
        @Nullable
        private final String cropType;
        private final List<BlockState> plantStates = new ArrayList<>();
        @Nullable
        private final Goods.Good seed;
        private final Block soilBlock;

        Planting(GenericGoalConfig config) {
            super(config);
            this.cropType = config.string("croptype");
            for (String s : config.all("plantblockstate")) {
                BlockState state = LegacyStates.parse(s);
                if (state != null) {
                    plantStates.add(state);
                }
            }
            this.seed = config.good("seed");
            String soil = config.string("soiltype");
            this.soilBlock = soil == null || soil.contains("farmland") ? Blocks.FARMLAND
                : soil.contains("grass") ? Blocks.GRASS_BLOCK : Blocks.DIRT;
            this.duration = config.msParam("duration", 100);
            this.tags.add("tag_agriculture");
        }

        private boolean plantable(ServerLevel level, BlockPos soil) {
            BlockState ground = level.getBlockState(soil);
            BlockState above = level.getBlockState(soil.above());
            BlockState twoAbove = level.getBlockState(soil.above(2));
            boolean groundOk = ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.FARMLAND);
            return groundOk && (above.isAir() || above.is(Blocks.SNOW))
                && (twoAbove.isAir() || twoAbove.is(Blocks.SNOW) || twoAbove.is(BlockTags.LEAVES));
        }

        @Nullable
        private BlockState cropState() {
            if (!plantStates.isEmpty()) {
                return null;
            }
            String soil = Crops.soilForCropType(cropType);
            Block crop = soil == null ? null : Crops.cropForSoil(soil);
            return crop == null ? null : crop.defaultBlockState();
        }

        @Override
        public int priority(MillVillager villager) {
            return destPos == null ? 0 : (int) (1000 - Math.sqrt(villager.distanceToSqr(destPos.getX(), destPos.getY(), destPos.getZ())));
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (seed != null && available(villager, level, b, seed) == 0) {
                return null;
            }
            if (plantStates.isEmpty() && cropState() == null) {
                return null;
            }
            BlockPos soil = closest(villager, soils(b, cropType), p -> plantable(level, p));
            return soil == null ? null : soil.above();
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (destPos == null || !plantable(level, destPos.below())) {
                return true;
            }
            if (seed != null && consume(villager, level, b, seed, 1) == 0) {
                return true;
            }
            if (!level.getBlockState(destPos.below()).is(soilBlock)) {
                BlockState ground = soilBlock == Blocks.FARMLAND
                    ? Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7)
                    : soilBlock.defaultBlockState();
                level.setBlock(destPos.below(), ground, Block.UPDATE_ALL);
            }
            BlockState crop = plantStates.isEmpty() ? cropState()
                : plantStates.get(villager.getRandom().nextInt(plantStates.size()));
            if (crop != null) {
                level.setBlock(destPos, crop, Block.UPDATE_ALL);
                if (crop.getBlock() instanceof DoublePlantBlock) {
                    level.setBlock(destPos.above(), crop.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
                }
            }
            BlockPos next = destinationIn(villager, level, b);
            if (next != null) {
                destPos = next;
                return false;
            }
            return true;
        }
    }

    // ================ 采集方块 ================

    /**
     * 原版 GoalGenericGatherBlocks：采建筑周围某种方块（如结了果的苹果树叶），得到收获物，方块变成另一种状态
     */
    public static final class GatherBlocks extends GenericGoal {
        @Nullable
        private final BlockState gatherState;
        @Nullable
        private final BlockState resultState;
        private final List<Bonus> harvestItems;
        private final int minimumAvailable;
        private final boolean collectInBuilding;

        GatherBlocks(GenericGoalConfig config) {
            super(config);
            this.gatherState = LegacyStates.parse(config.string("gatherblockstate"));
            this.resultState = LegacyStates.parse(config.string("resultingblockstate"));
            this.harvestItems = bonuses(config, "harvestitem");
            this.minimumAvailable = config.intParam("minimumavailableblocks", 4);
            this.collectInBuilding = config.boolParam("collectinbuilding");
            this.duration = config.msParam("duration", 100);
        }

        private List<BlockPos> targets(ServerLevel level, VillageGrowth.PlacedBuilding b) {
            List<BlockPos> list = new ArrayList<>();
            if (gatherState == null) {
                return list;
            }
            BoundingBox f = b.footprint;
            for (BlockPos p : BlockPos.betweenClosed(f.minX() - 3, b.origin.getY() - 1, f.minZ() - 3, f.maxX() + 3,
                b.origin.getY() + 20, f.maxZ() + 3)) {
                if (level.getBlockState(p).equals(gatherState)) {
                    list.add(p.immutable());
                }
            }
            return list;
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            List<BlockPos> targets = targets(level, b);
            return targets.size() < minimumAvailable ? null : closest(villager, targets, p -> true);
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (destPos != null && gatherState != null && level.getBlockState(destPos).equals(gatherState)) {
                giveBonuses(villager, harvestItems, b, level, collectInBuilding);
                level.setBlock(destPos, resultState != null ? resultState : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            return true;
        }
    }

    // ================ 屠宰 ================

    /**
     * 原版 GoalGenericSlaughterAnimal：建筑附近的某种成年牲畜比图纸的生成点多时，宰一头，得到它的掉落物和额外物品
     */
    public static final class SlaughterAnimal extends GenericGoal {
        @Nullable
        private final EntityType<?> animal;
        @Nullable
        private final String animalKey;
        private final List<Bonus> bonusItems;
        private final boolean aggressive;
        @Nullable
        private LivingEntity target;

        SlaughterAnimal(GenericGoalConfig config) {
            super(config);
            this.animalKey = config.string("animalkey");
            this.animal = animalKey == null ? null
                : BuiltInRegistries.ENTITY_TYPE.getOptional(new ResourceLocation(animalKey.contains(":") ? animalKey
                    : "minecraft:" + animalKey)).orElse(null);
            this.bonusItems = bonuses(config, "bonusitem");
            this.aggressive = config.boolParam("aggressiveslaughter");
            this.duration = config.msParam("duration", 100);
        }

        private List<LivingEntity> adults(ServerLevel level, BlockPos around, int radius) {
            List<LivingEntity> list = new ArrayList<>();
            if (animal == null) {
                return list;
            }
            for (Entity e : level.getEntities((Entity) null, new AABB(around).inflate(radius, 10, radius),
                e -> e.getType() == animal && e.isAlive())) {
                if (e instanceof LivingEntity living && !(e instanceof AgeableMob ageable && ageable.isBaby())) {
                    list.add(living);
                }
            }
            return list;
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (animal == null) {
                return null;
            }
            int wanted = b.points(animalKey.replace("minecraft:", "") + "spawn").size();
            List<LivingEntity> near = adults(level, b.origin, 25);
            if (near.size() <= (aggressive ? wanted / 2 : wanted)) {
                return null;
            }
            LivingEntity closest = null;
            for (LivingEntity e : adults(level, b.origin, 15)) {
                if (closest == null || e.distanceToSqr(villager) < closest.distanceToSqr(villager)) {
                    closest = e;
                }
            }
            target = closest;
            return closest == null ? null : closest.blockPosition();
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            GoalInformation info = super.getDestination(villager);
            return target != null && target.isAlive() ? GoalInformation.ofEntity(target) : info;
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (target != null && target.isAlive() && target.distanceToSqr(villager) < 16) {
                // 掉落物直接进村民背包（原版由村民攻击后捡起）
                var lootTable = level.getServer().getLootData().getLootTable(target.getLootTable());
                LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.THIS_ENTITY, target)
                    .withParameter(LootContextParams.ORIGIN, target.position())
                    .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().mobAttack(villager))
                    .create(LootContextParamSets.ENTITY);
                for (ItemStack drop : lootTable.getRandomItems(params)) {
                    villager.addToInventory(drop, drop.getCount());
                }
                giveBonuses(villager, bonusItems, b, level, false);
                target.discard();
            }
            return true;
        }
    }

    // ================ 种树苗 ================

    /**
     * 原版 GoalGenericPlantSapling：在建筑的种树点（oakspawn 等）空着时种回对应的树苗
     */
    public static final class PlantSapling extends GenericGoal {

        PlantSapling(GenericGoalConfig config) {
            super(config);
            this.duration = config.msParam("duration", 100);
        }

        @Nullable
        public static Block saplingFor(String spawnType) {
            return switch (spawnType) {
                case "oakspawn", "appletreespawn", "olivetreespawn", "pistachiotreespawn" -> Blocks.OAK_SAPLING;
                case "pinespawn" -> Blocks.SPRUCE_SAPLING;
                case "birchspawn" -> Blocks.BIRCH_SAPLING;
                case "junglespawn" -> Blocks.JUNGLE_SAPLING;
                case "acaciaspawn" -> Blocks.ACACIA_SAPLING;
                case "darkoakspawn" -> Blocks.DARK_OAK_SAPLING;
                case "cherrytreespawn", "sakuratreespawn" -> Blocks.CHERRY_SAPLING;
                default -> null;
            };
        }

        private static boolean free(ServerLevel level, BlockPos p) {
            BlockState s = level.getBlockState(p);
            return (s.isAir() || s.is(Blocks.SNOW) || s.canBeReplaced() && !s.is(BlockTags.SAPLINGS))
                && !level.getBlockState(p).is(BlockTags.LOGS);
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            List<BlockPos> spots = new ArrayList<>();
            b.points.forEach((type, points) -> {
                if (saplingFor(type) != null) {
                    spots.addAll(points);
                }
            });
            return closest(villager, spots, p -> free(level, p));
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (destPos == null || !free(level, destPos)) {
                return true;
            }
            for (var e : b.points.entrySet()) {
                if (e.getValue().contains(destPos)) {
                    Block sapling = saplingFor(e.getKey());
                    if (sapling != null) {
                        level.setBlock(destPos, sapling.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    break;
                }
            }
            return true;
        }
    }
}
