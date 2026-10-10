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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    /** 选址时允许的地面高度差（建造时会清除上方、填实下方） */
    private static final int MAX_HEIGHT_DIFFERENCE = 8;
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
        /** 图纸中的道路起点（通常在门口），用于连接道路网 */
        public final List<BlockPos> pathStarts;
        /** 图纸中的功能点位（耕作点、资源点、种树点、箱子、睡觉位置等），升级时合并新点位 */
        public final Map<String, List<BlockPos>> points = new HashMap<>();

        PlacedBuilding(String key, char variation, int level, BlockPos origin, int orientation,
                       BoundingBox footprint, boolean townHall, List<BlockPos> pathStarts,
                       Map<String, List<BlockPos>> points) {
            this.key = key;
            this.variation = variation;
            this.level = level;
            this.origin = origin;
            this.orientation = orientation;
            this.footprint = footprint;
            this.townHall = townHall;
            this.pathStarts = List.copyOf(pathStarts);
            addPoints(points);
        }

        void addPoints(Map<String, List<BlockPos>> newPoints) {
            newPoints.forEach((type, list) -> {
                List<BlockPos> existing = points.computeIfAbsent(type, k -> new ArrayList<>());
                for (BlockPos p : list) {
                    if (!existing.contains(p)) {
                        existing.add(p);
                    }
                }
            });
        }

        public List<BlockPos> points(String type) {
            return points.getOrDefault(type, List.of());
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= footprint.minX() && pos.getX() <= footprint.maxX()
                && pos.getZ() >= footprint.minZ() && pos.getZ() <= footprint.maxZ();
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
            ListTag starts = new ListTag();
            pathStarts.forEach(p -> starts.add(NbtUtils.writeBlockPos(p)));
            tag.put("PathStarts", starts);
            CompoundTag pointsTag = new CompoundTag();
            points.forEach((type, list) -> {
                ListTag l = new ListTag();
                list.forEach(p -> l.add(NbtUtils.writeBlockPos(p)));
                pointsTag.put(type, l);
            });
            tag.put("Points", pointsTag);
            return tag;
        }

        static PlacedBuilding load(CompoundTag tag) {
            int[] f = tag.getIntArray("Footprint");
            List<BlockPos> starts = new ArrayList<>();
            ListTag list = tag.getList("PathStarts", 10);
            for (int i = 0; i < list.size(); i++) {
                starts.add(NbtUtils.readBlockPos(list.getCompound(i)));
            }
            Map<String, List<BlockPos>> points = new HashMap<>();
            CompoundTag pointsTag = tag.getCompound("Points");
            for (String type : pointsTag.getAllKeys()) {
                ListTag l = pointsTag.getList(type, 10);
                List<BlockPos> typePoints = new ArrayList<>();
                for (int i = 0; i < l.size(); i++) {
                    typePoints.add(NbtUtils.readBlockPos(l.getCompound(i)));
                }
                points.put(type, typePoints);
            }
            return new PlacedBuilding(tag.getString("Key"), tag.getString("Variation").charAt(0), tag.getInt("Level"),
                NbtUtils.readBlockPos(tag.getCompound("Origin")), tag.getInt("Orientation"),
                new BoundingBox(f[0], f[1], f[2], f[3], f[4], f[5]), tag.getBoolean("TownHall"), starts, points);
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
    /** 已连到市政厅的道路格子（x/z 打包为 long），新道路遇到即并入 */
    private final Set<Long> pathNetwork = new HashSet<>();
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

    public Set<Long> getPathNetwork() {
        return pathNetwork;
    }

    /**
     * 记录一栋已经放置好的建筑（测试或外部放置建筑时使用）
     */
    public PlacedBuilding recordBuilding(BuildingPlan plan, BlockPos origin, int orientation,
                                         BuildingPlacer.Result result, boolean townHall) {
        PlacedBuilding placed = new PlacedBuilding(plan.key, plan.variation, plan.level, origin, orientation,
            BuildingPlacer.footprint(plan, origin, orientation), townHall, pathStarts(result), result.specialPoints);
        buildings.add(placed);
        village.markDirty();
        return placed;
    }

    /**
     * 占地包含该位置的建筑（用于找村民的家）
     */
    @Nullable
    public PlacedBuilding getBuildingAt(BlockPos pos) {
        for (PlacedBuilding b : buildings) {
            if (b.contains(pos)) {
                return b;
            }
        }
        return null;
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
                           BlockPos origin, int orientation, BuildingPlacer.Result townHallResult,
                           RandomSource random) {
        villageTypeFile = type.fileName();
        buildings.add(new PlacedBuilding(townHall.key, townHall.variation, townHall.level, origin, orientation,
            BuildingPlacer.footprint(townHall, origin, orientation), true, pathStarts(townHallResult),
            townHallResult.specialPoints));

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
                PlacedBuilding placed = new PlacedBuilding(key, 'A', 0, site.origin(), site.orientation(),
                    site.footprint(), false, pathStarts(c.result), c.result.specialPoints);
                buildings.add(placed);
            } catch (IOException e) {
                MillenaireRewrite.LOGGER.error("Village {}: cannot load start building {}", village.getName(), key, e);
            }
        }
        // 所有 start 建筑建好后再一次铺好路网（后建的建筑可能压到先铺的路）
        VillagePaths.rebuildNetwork(level, this, pathMaterial(type));
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
            PlacedBuilding upgraded = buildings.get(p.upgradeOf);
            upgraded.level = p.level;
            upgraded.addPoints(c.result.specialPoints);
            // 升级可能在庭院里加了栅栏等，把穿过庭院的道路截断：重建路网绕开它们
            if (VillagePaths.coversNetwork(this, upgraded.footprint)) {
                try {
                    VillagePaths.rebuildNetwork(level, this, pathMaterial(VillageTypes.get(village.getCulture(), villageTypeFile)));
                } catch (IOException e) {
                    MillenaireRewrite.LOGGER.warn("Village {}: cannot read village type for paths", village.getName());
                }
            }
        } else {
            ResidentSpawner.spawnResidents(level, village, c.plan, c.result);
            PlacedBuilding placed = new PlacedBuilding(p.key, p.variation, p.level, p.origin, p.orientation,
                BuildingPlacer.footprint(c.plan, p.origin, p.orientation), false, pathStarts(c.result),
                c.result.specialPoints);
            boolean coveredPaths = VillagePaths.coversNetwork(this, placed.footprint);
            buildings.add(placed);
            try {
                var material = pathMaterial(VillageTypes.get(village.getCulture(), villageTypeFile));
                if (coveredPaths) {
                    // 新建筑压断了旧道路：重建整个路网，确保每段路都通到市政厅
                    VillagePaths.rebuildNetwork(level, this, material);
                } else {
                    VillagePaths.connect(level, this, placed, material);
                }
            } catch (IOException e) {
                MillenaireRewrite.LOGGER.warn("Village {}: cannot read village type for paths", village.getName());
            }
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

    private static List<BlockPos> pathStarts(BuildingPlacer.Result result) {
        return result.specialPoints.getOrDefault("pathStartPos", List.of());
    }

    /**
     * 村庄类型的道路材料 -> 1.20.1 方块（与 blocklist 转换器中的映射一致）
     */
    static net.minecraft.world.level.block.state.BlockState pathMaterial(@Nullable VillageTypes.VillageType type) {
        String name = type == null || type.pathMaterials().isEmpty() ? "pathdirt" : type.pathMaterials().get(0);
        return switch (name) {
            case "pathgravel", "pathgravelslabs" -> com.jasoncian.millenaire_rewrite.blocks.system.BuildingBlockRegistry
                .getBlock("gravel_path").get().defaultBlockState();
            case "pathslabs" -> com.jasoncian.millenaire_rewrite.blocks.system.BuildingBlockRegistry
                .getBlock("stone_path").get().defaultBlockState();
            case "pathsandstone", "pathochretiles" -> com.jasoncian.millenaire_rewrite.blocks.system.BuildingBlockRegistry
                .getBlock("sandstone_path").get().defaultBlockState();
            case "pathsnow" -> net.minecraft.world.level.block.Blocks.SNOW_BLOCK.defaultBlockState();
            default -> net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState();
        };
    }

    // ================ 选址 ================

    /**
     * 在市政厅周围由近到远随机找一块够平、不与已有建筑重叠、区块已加载的地方。
     * 建筑朝向村庄中心（原版 computeOrientation）。
     */
    @Nullable
    private Site findSite(ServerLevel level, BuildingPlan plan, RandomSource random) {
        // 先找不压到道路的位置，找不到再允许压过道路（之后会重建路网）
        Site site = findSite(level, plan, random, true);
        return site != null ? site : findSite(level, plan, random, false);
    }

    @Nullable
    private Site findSite(ServerLevel level, BuildingPlan plan, RandomSource random, boolean avoidPaths) {
        BlockPos centre = village.getTownHallPos();
        for (int attempt = 0; attempt < SITE_ATTEMPTS; attempt++) {
            double distance = MIN_SITE_DISTANCE + (MAX_SITE_DISTANCE - MIN_SITE_DISTANCE) * attempt / (double) SITE_ATTEMPTS;
            double angle = random.nextDouble() * Math.PI * 2;
            int x = centre.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = centre.getZ() + (int) Math.round(Math.sin(angle) * distance);
            int orientation = orientationTowards(x, z, centre);

            BoundingBox rough = BuildingPlacer.footprint(plan, new BlockPos(x, 0, z), orientation);
            if (!isLoaded(level, rough) || overlapsExisting(rough)
                || avoidPaths && VillagePaths.coversNetwork(this, rough)) {
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
                    MillenaireRewrite.LOGGER.debug("site {}: water/ice at {} {} {} ({})", box, x, y - 1, z,
                        level.getBlockState(new BlockPos(x, y - 1, z)));
                    return null;
                }
                min = Math.min(min, y);
                max = Math.max(max, y);
                sum += y;
                count++;
            }
        }
        if (count == 0 || max - min > MAX_HEIGHT_DIFFERENCE) {
            MillenaireRewrite.LOGGER.debug("site {}: height range {}-{}", box, min, max);
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
        tag.putLongArray("PathNetwork", pathNetwork.stream().mapToLong(Long::longValue).toArray());
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
        pathNetwork.clear();
        for (long cell : tag.getLongArray("PathNetwork")) {
            pathNetwork.add(cell);
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
