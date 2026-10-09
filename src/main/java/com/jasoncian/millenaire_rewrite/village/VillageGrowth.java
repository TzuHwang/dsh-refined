package com.jasoncian.millenaire_rewrite.village;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan;
import com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes;
import com.jasoncian.millenaire_rewrite.content.ResidentSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 村庄成长（方案 A：计时成长，不消耗资源）。
 *
 * 村庄生成时立即建好市政厅和村庄类型的 start 建筑；之后每隔一段时间，
 * 从 core（优先）/ secondary 建筑和已有建筑的升级中，按图纸优先级选出下一个工程，
 * 在市政厅周围选址并逐格建造，完工后住进该建筑的居民。施工进度随村庄存档保存。
 */
public class VillageGrowth {

    /** 两个工程之间的间隔（tick），2 分钟 */
    public static final int PROJECT_INTERVAL = 2400;
    /** 每 tick 放置的方块数 */
    public static final int BLOCKS_PER_TICK = 4;
    /** 建筑之间至少留出的空地 */
    private static final int SITE_MARGIN = 3;
    private static final int MIN_SITE_DISTANCE = 12;
    private static final int MAX_SITE_DISTANCE = 72;
    private static final int SITE_ATTEMPTS = 160;
    /** 选址时允许的地面高度差 */
    private static final int MAX_HEIGHT_DIFFERENCE = 5;
    /** 完工消息的通知范围 */
    private static final double NOTIFY_RANGE = 96.0;

    /**
     * 已建成的建筑
     */
    public static final class PlacedBuilding {
        public final String key;
        public final char variation;
        public int level;
        public final BlockPos origin;
        public final int orientation;
        public final BoundingBox footprint;
        public final boolean townHall;

        PlacedBuilding(String key, char variation, int level, BlockPos origin, int orientation,
                       BoundingBox footprint, boolean townHall) {
            this.key = key;
            this.variation = variation;
            this.level = level;
            this.origin = origin;
            this.orientation = orientation;
            this.footprint = footprint;
            this.townHall = townHall;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Key", key);
            tag.putString("Variation", String.valueOf(variation));
            tag.putInt("Level", level);
            tag.put("Origin", NbtUtils.writeBlockPos(origin));
            tag.putInt("Orientation", orientation);
            tag.putIntArray("Footprint", new int[] {footprint.minX(), footprint.minY(), footprint.minZ(),
                footprint.maxX(), footprint.maxY(), footprint.maxZ()});
            tag.putBoolean("TownHall", townHall);
            return tag;
        }

        static PlacedBuilding load(CompoundTag tag) {
            int[] f = tag.getIntArray("Footprint");
            return new PlacedBuilding(tag.getString("Key"), tag.getString("Variation").charAt(0), tag.getInt("Level"),
                NbtUtils.readBlockPos(tag.getCompound("Origin")), tag.getInt("Orientation"),
                new BoundingBox(f[0], f[1], f[2], f[3], f[4], f[5]), tag.getBoolean("TownHall"));
        }
    }

    /**
     * 进行中的工程：新建（upgradeOf = -1）或升级 buildings 中的第 upgradeOf 栋
     */
    private static final class Project {
        final String key;
        final char variation;
        final int level;
        final BlockPos origin;
        final int orientation;
        final int upgradeOf;
        int progress;

        Project(String key, char variation, int level, BlockPos origin, int orientation, int upgradeOf) {
            this.key = key;
            this.variation = variation;
            this.level = level;
            this.origin = origin;
            this.orientation = orientation;
            this.upgradeOf = upgradeOf;
        }
    }

    private record Site(BlockPos origin, int orientation, BoundingBox footprint) {
    }

    private final Village village;
    private String villageTypeFile = "";
    private final List<PlacedBuilding> buildings = new ArrayList<>();
    @Nullable
    private Project project;
    @Nullable
    private BuildingPlacer.Construction construction;
    private long nextProjectTime;
    /** 本次运行中找不到建筑位置的建筑，暂不再尝试（不存档，重启后会重试） */
    private final Set<String> unplaceable = new HashSet<>();

    public VillageGrowth(Village village) {
        this.village = village;
    }

    public List<PlacedBuilding> getBuildings() {
        return buildings;
    }

    public boolean isConstructing() {
        return project != null;
    }

    @Nullable
    public String getCurrentProjectKey() {
        return project == null ? null : project.key + " " + project.level;
    }

    private String cultureFolder() {
        return VillageTypes.cultureFolder(village.getCulture());
    }

    // ================ 村庄生成 ================

    /**
     * 村庄生成时调用：记录市政厅，并立即建好村庄类型的 start 建筑
     */
    public void initialise(ServerLevel level, VillageTypes.VillageType type, BuildingPlan townHall,
                           BlockPos origin, int orientation, RandomSource random) {
        villageTypeFile = type.fileName();
        buildings.add(new PlacedBuilding(townHall.key, townHall.variation, townHall.level, origin, orientation,
            BuildingPlacer.footprint(townHall, origin, orientation), true));

        for (String key : type.start()) {
            try {
                BuildingPlan plan = BuildingPlan.get(cultureFolder(), key, 'A', 0);
                Site site = findSite(level, plan, random);
                if (site == null) {
                    MillenaireRewrite.LOGGER.warn("Village {}: no site for start building {}", village.getName(), key);
                    continue;
                }
                BuildingPlacer.Construction c = BuildingPlacer.prepare(plan, site.origin(), site.orientation());
                c.step(level, Integer.MAX_VALUE, false, false);
                c.finish(level);
                BuildingPlacer.spawnAnimals(level, c.result);
                ResidentSpawner.spawnResidents(level, village, plan, c.result);
                buildings.add(new PlacedBuilding(key, 'A', 0, site.origin(), site.orientation(), site.footprint(), false));
            } catch (IOException e) {
                MillenaireRewrite.LOGGER.error("Village {}: cannot load start building {}", village.getName(), key, e);
            }
        }
        nextProjectTime = level.getGameTime() + PROJECT_INTERVAL;
        village.markDirty();
    }

    // ================ 成长 ================

    public void tick(ServerLevel level) {
        // 未生成成长数据的旧村庄，或玩家不在附近（市政厅区块未加载）时暂停
        if (villageTypeFile.isEmpty() || !level.isLoaded(village.getTownHallPos())) {
            return;
        }
        if (project != null) {
            BuildingPlacer.Construction c = construction(level);
            if (c == null) {
                return;
            }
            int done = c.step(level, BLOCKS_PER_TICK, true, true);
            if (done > 0) {
                project.progress = c.progress();
                village.markDirty();
            }
            if (c.isDone()) {
                complete(level);
            }
        } else if (level.getGameTime() >= nextProjectTime) {
            startNextProject(level);
        }
    }

    /**
     * 调试/指令用：有工程时立即完工，没有时立即开始下一个工程并完工
     *
     * @return 完成的工程描述，没有可建造的工程时返回 null
     */
    @Nullable
    public String forceNext(ServerLevel level) {
        if (project == null) {
            startNextProject(level);
        }
        if (project == null) {
            return null;
        }
        String description = project.key + " " + project.level;
        BuildingPlacer.Construction c = construction(level);
        if (c != null) {
            c.step(level, Integer.MAX_VALUE, false, false);
            complete(level);
        }
        return description;
    }

    /**
     * 选择并开始下一个工程
     *
     * @return 是否开始了工程
     */
    public boolean startNextProject(ServerLevel level) {
        nextProjectTime = level.getGameTime() + PROJECT_INTERVAL;
        VillageTypes.VillageType type;
        try {
            type = VillageTypes.get(village.getCulture(), villageTypeFile);
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.error("Village {}: cannot read village type {}", village.getName(), villageTypeFile, e);
            return false;
        }
        if (type == null) {
            return false;
        }

        Set<String> built = new HashSet<>();
        buildings.forEach(b -> built.add(b.key));

        // 新建筑：core 全部建完（或无法选址）之前不考虑 secondary
        List<String> pendingCore = type.core().stream()
            .filter(k -> !built.contains(k) && !unplaceable.contains(k)).toList();
        List<String> newCandidates = pendingCore.isEmpty()
            ? type.secondary().stream().filter(k -> !built.contains(k) && !unplaceable.contains(k)).toList()
            : pendingCore;

        BuildingPlan best = null;
        int bestUpgradeOf = -1;
        for (String key : newCandidates) {
            BuildingPlan plan = plan(key, 'A', 0);
            if (plan != null && (best == null || plan.priority > best.priority)) {
                best = plan;
                bestUpgradeOf = -1;
            }
        }
        for (int i = 0; i < buildings.size(); i++) {
            PlacedBuilding b = buildings.get(i);
            if (!BuildingPlan.exists(cultureFolder(), b.key, b.variation, b.level + 1)) {
                continue;
            }
            BuildingPlan plan = plan(b.key, b.variation, b.level + 1);
            if (plan != null && (best == null || plan.priority > best.priority)) {
                best = plan;
                bestUpgradeOf = i;
            }
        }
        if (best == null) {
            return false;
        }

        if (bestUpgradeOf >= 0) {
            PlacedBuilding b = buildings.get(bestUpgradeOf);
            project = new Project(best.key, best.variation, best.level, b.origin, b.orientation, bestUpgradeOf);
        } else {
            Site site = findSite(level, best, level.random);
            if (site == null) {
                MillenaireRewrite.LOGGER.info("Village {}: no site found for {}, skipping it", village.getName(), best.key);
                unplaceable.add(best.key);
                return startNextProject(level);
            }
            project = new Project(best.key, best.variation, 0, site.origin(), site.orientation(), -1);
        }
        construction = null;
        village.markDirty();
        MillenaireRewrite.LOGGER.info("Village {} started {} {} (level {}) at {}", village.getName(),
            bestUpgradeOf >= 0 ? "upgrading" : "building", best.key, best.level, project.origin.toShortString());
        return true;
    }

    private void complete(ServerLevel level) {
        Project p = project;
        BuildingPlacer.Construction c = construction;
        if (p == null || c == null) {
            return;
        }
        c.finish(level);
        BuildingPlacer.spawnAnimals(level, c.result);
        if (p.upgradeOf >= 0 && p.upgradeOf < buildings.size()) {
            buildings.get(p.upgradeOf).level = p.level;
        } else {
            ResidentSpawner.spawnResidents(level, village, c.plan, c.result);
            buildings.add(new PlacedBuilding(p.key, p.variation, p.level, p.origin, p.orientation,
                BuildingPlacer.footprint(c.plan, p.origin, p.orientation), false));
        }
        project = null;
        construction = null;
        nextProjectTime = level.getGameTime() + PROJECT_INTERVAL;
        village.markDirty();

        String name = c.plan.nativeName != null ? c.plan.nativeName : p.key;
        MillenaireRewrite.LOGGER.info("Village {} completed {} (level {})", village.getName(), p.key, p.level);
        Component message = Component.literal("[" + village.getName() + "] ").withStyle(net.minecraft.ChatFormatting.GOLD)
            .append(Component.translatableWithFallback(
                p.upgradeOf >= 0 ? "message.millenaire_rewrite.building_upgraded" : "message.millenaire_rewrite.building_completed",
                p.upgradeOf >= 0 ? "%s has been upgraded" : "%s has been completed", name)
                .withStyle(net.minecraft.ChatFormatting.WHITE));
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(p.origin.getX(), p.origin.getY(), p.origin.getZ()) < NOTIFY_RANGE * NOTIFY_RANGE) {
                player.sendSystemMessage(message);
            }
        }
    }

    @Nullable
    private BuildingPlacer.Construction construction(ServerLevel level) {
        if (construction == null && project != null) {
            BuildingPlan plan = plan(project.key, project.variation, project.level);
            if (plan == null) {
                MillenaireRewrite.LOGGER.error("Village {}: plan for project {} disappeared, dropping it",
                    village.getName(), project.key);
                project = null;
                return null;
            }
            construction = BuildingPlacer.prepare(plan, project.origin, project.orientation);
            construction.skipTo(project.progress);
        }
        return construction;
    }

    @Nullable
    private BuildingPlan plan(String key, char variation, int level) {
        try {
            return BuildingPlan.get(cultureFolder(), key, variation, level);
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.warn("Village {}: cannot load plan {}_{}{}: {}", village.getName(), key,
                variation, level, e.getMessage());
            return null;
        }
    }

    // ================ 选址 ================

    /**
     * 在市政厅周围由近到远随机找一块够平、不与已有建筑重叠、区块已加载的地方。
     * 建筑朝向村庄中心（原版 computeOrientation）。
     */
    @Nullable
    private Site findSite(ServerLevel level, BuildingPlan plan, RandomSource random) {
        BlockPos centre = village.getTownHallPos();
        for (int attempt = 0; attempt < SITE_ATTEMPTS; attempt++) {
            double distance = MIN_SITE_DISTANCE + (MAX_SITE_DISTANCE - MIN_SITE_DISTANCE) * attempt / (double) SITE_ATTEMPTS;
            double angle = random.nextDouble() * Math.PI * 2;
            int x = centre.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = centre.getZ() + (int) Math.round(Math.sin(angle) * distance);
            int orientation = orientationTowards(x, z, centre);

            BoundingBox rough = BuildingPlacer.footprint(plan, new BlockPos(x, 0, z), orientation);
            if (!isLoaded(level, rough) || overlapsExisting(rough)) {
                continue;
            }
            Integer ground = averageGround(level, rough);
            if (ground == null) {
                continue;
            }
            BlockPos origin = new BlockPos(x, ground, z);
            return new Site(origin, orientation, BuildingPlacer.footprint(plan, origin, orientation));
        }
        return null;
    }

    /**
     * 原版 BuildingPlan.computeOrientation：让建筑朝向村庄中心
     */
    private static int orientationTowards(int x, int z, BlockPos centre) {
        int relX = x - centre.getX();
        int relZ = z - centre.getZ();
        if (relX * relX > relZ * relZ) {
            return relX > 0 ? 0 : 2;
        }
        return relZ > 0 ? 3 : 1;
    }

    private static boolean isLoaded(ServerLevel level, BoundingBox box) {
        return level.hasChunkAt(new BlockPos(box.minX(), 0, box.minZ()))
            && level.hasChunkAt(new BlockPos(box.maxX(), 0, box.minZ()))
            && level.hasChunkAt(new BlockPos(box.minX(), 0, box.maxZ()))
            && level.hasChunkAt(new BlockPos(box.maxX(), 0, box.maxZ()));
    }

    private boolean overlapsExisting(BoundingBox box) {
        for (PlacedBuilding b : buildings) {
            if (overlapsXZ(box, b.footprint, SITE_MARGIN)) {
                return true;
            }
        }
        if (project != null && construction != null) {
            return overlapsXZ(box, BuildingPlacer.footprint(construction.plan, project.origin, project.orientation),
                SITE_MARGIN);
        }
        return false;
    }

    static boolean overlapsXZ(BoundingBox a, BoundingBox b, int margin) {
        return a.minX() - margin <= b.maxX() && a.maxX() + margin >= b.minX()
            && a.minZ() - margin <= b.maxZ() && a.maxZ() + margin >= b.minZ();
    }

    /**
     * 采样占地范围内的地面高度；有水/岩浆/冰面或高差过大时返回 null
     */
    @Nullable
    public static Integer averageGround(ServerLevel level, BoundingBox box) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        int count = 0;
        for (int x = box.minX(); x <= box.maxX(); x += 2) {
            for (int z = box.minZ(); z <= box.maxZ(); z += 2) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (isWaterSurface(level, new BlockPos(x, y - 1, z))) {
                    return null;
                }
                min = Math.min(min, y);
                max = Math.max(max, y);
                sum += y;
                count++;
            }
        }
        if (count == 0 || max - min > MAX_HEIGHT_DIFFERENCE) {
            return null;
        }
        return (int) Math.round(sum / (double) count);
    }

    /**
     * 地表是否为水面：液体、冰（结冰的河/湖），或下一格是液体（薄冰层下的水）
     */
    public static boolean isWaterSurface(ServerLevel level, BlockPos surface) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(surface);
        return !state.getFluidState().isEmpty()
            || state.is(net.minecraft.tags.BlockTags.ICE)
            || !level.getFluidState(surface.below()).isEmpty();
    }

    // ================ 存档 ================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("VillageType", villageTypeFile);
        tag.putLong("NextProjectTime", nextProjectTime);
        ListTag list = new ListTag();
        buildings.forEach(b -> list.add(b.save()));
        tag.put("Buildings", list);
        if (project != null) {
            CompoundTag p = new CompoundTag();
            p.putString("Key", project.key);
            p.putString("Variation", String.valueOf(project.variation));
            p.putInt("Level", project.level);
            p.put("Origin", NbtUtils.writeBlockPos(project.origin));
            p.putInt("Orientation", project.orientation);
            p.putInt("UpgradeOf", project.upgradeOf);
            p.putInt("Progress", project.progress);
            tag.put("Project", p);
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        villageTypeFile = tag.getString("VillageType");
        nextProjectTime = tag.getLong("NextProjectTime");
        buildings.clear();
        ListTag list = tag.getList("Buildings", 10);
        for (int i = 0; i < list.size(); i++) {
            buildings.add(PlacedBuilding.load(list.getCompound(i)));
        }
        project = null;
        construction = null;
        if (tag.contains("Project")) {
            CompoundTag p = tag.getCompound("Project");
            project = new Project(p.getString("Key"), p.getString("Variation").charAt(0), p.getInt("Level"),
                NbtUtils.readBlockPos(p.getCompound("Origin")), p.getInt("Orientation"), p.getInt("UpgradeOf"));
            project.progress = p.getInt("Progress");
        }
    }
}
