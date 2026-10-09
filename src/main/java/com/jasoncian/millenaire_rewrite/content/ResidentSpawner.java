package com.jasoncian.millenaire_rewrite.content;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan;
import com.jasoncian.millenaire_rewrite.core.ModEntities;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.villager.VillagerProfession;
import com.jasoncian.millenaire_rewrite.village.Village;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.List;

/**
 * 按图纸 initial.male / initial.female 生成建筑的初始居民。
 */
public final class ResidentSpawner {

    private ResidentSpawner() {
    }

    /**
     * @return 实际生成的村民
     */
    public static List<MillVillager> spawnResidents(ServerLevel level, Village village, BuildingPlan plan,
                                                    BuildingPlacer.Result placed) {
        List<BlockPos> beds = placed.specialPoints.getOrDefault("sleepingPos", List.of());
        BlockPos fallback = placed.mainChest != null ? placed.mainChest.above() : village.getTownHallPos();
        List<String> residents = new ArrayList<>(plan.maleResidents);
        residents.addAll(plan.femaleResidents);

        List<MillVillager> spawned = new ArrayList<>();
        for (int i = 0; i < residents.size(); i++) {
            BlockPos home = beds.isEmpty() ? fallback : beds.get(i % beds.size());
            try {
                VillagerTypes.VillagerType type = VillagerTypes.get(plan.culture, residents.get(i));
                MillVillager villager = spawn(level, village, type, home);
                if (villager != null) {
                    spawned.add(villager);
                }
            } catch (Exception e) {
                MillenaireRewrite.LOGGER.error("Cannot spawn resident {} of {}", residents.get(i), plan, e);
            }
        }
        return spawned;
    }

    private static MillVillager spawn(ServerLevel level, Village village, VillagerTypes.VillagerType type,
                                      BlockPos home) {
        MillVillager villager = ModEntities.MILL_VILLAGER.get().create(level);
        if (villager == null) {
            return null;
        }
        villager.moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, level.random.nextFloat() * 360F, 0F);
        // finalizeSpawn 会随机性别并写入占位名字，所以先调用，再覆盖为图纸设定
        villager.finalizeSpawn(level, level.getCurrentDifficultyAt(home), MobSpawnType.STRUCTURE, null, null);

        villager.setCulture(village.getCulture());
        villager.setVillagerType(type.key());
        villager.setProfession(professionFor(type));
        villager.setGender(type.female() ? MillVillager.GENDER_FEMALE : MillVillager.GENDER_MALE);
        villager.setFirstName(VillagerTypes.randomName(type.culture(), type.firstNameList(), level.random));
        villager.setFamilyName(VillagerTypes.randomName(type.culture(), type.familyNameList(), level.random));
        villager.setAppearance(type.randomTexture(level.random),
            type.randomClothes(0, level.random), type.randomClothes(1, level.random));
        villager.setVillagerScale(type.baseHeight());
        villager.getAttribute(Attributes.MAX_HEALTH).setBaseValue(type.health());
        villager.setHealth(type.health());
        villager.setHousePos(home);
        villager.setTownHallPos(village.getTownHallPos());
        villager.setPersistenceRequired();
        villager.setCustomName(Component.literal(villager.getFullName() + ", " + type.nativeName()));

        level.addFreshEntity(villager);
        village.registerVillager(villager);
        return villager;
    }

    /**
     * 原版村民类型远多于 VillagerProfession，这里按名称/标签取最接近的职业（影响工作 AI）
     */
    private static VillagerProfession professionFor(VillagerTypes.VillagerType type) {
        String key = type.key();
        if (key.contains("knight")) {
            return VillagerProfession.KNIGHT;
        }
        if (key.contains("guard") || key.contains("soldier") || type.tags().contains("helpInAttacks")) {
            return VillagerProfession.GUARD;
        }
        if (key.contains("priest") || key.contains("abbot") || key.contains("monk")) {
            return VillagerProfession.PRIEST;
        }
        if (key.contains("smith")) {
            return VillagerProfession.BLACKSMITH;
        }
        if (key.contains("lumber")) {
            return VillagerProfession.LUMBERJACK;
        }
        if (key.contains("miner")) {
            return VillagerProfession.MINER;
        }
        if (type.tags().contains("seller") || key.contains("merchant")) {
            return VillagerProfession.MERCHANT;
        }
        if (type.female()) {
            return VillagerProfession.WIFE;
        }
        return VillagerProfession.FARMER;
    }
}
