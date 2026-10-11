package com.jasoncian.millenaire_rewrite.command;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.entity.culture.Culture;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.layout.LayoutTemplate;
import com.jasoncian.millenaire_rewrite.village.layout.VillageLayout;
import com.jasoncian.millenaire_rewrite.worldgen.VillageGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * 佈局模板展示：把所有模板的村庄排成网格（每格 CELL x CELL），每格用不同颜色的羊毛围边，
 * 角落立告示牌写上文化、模板和市政厅。每个 tick 只生成一个村庄，避免服务器卡死。
 */
@Mod.EventBusSubscriber(modid = MillenaireRewrite.MOD_ID)
public final class LayoutShowcase {

    /** 每格边长（日本、塞尔柱的村庄田地可伸到离广场 110 格） */
    private static final int CELL = 240;
    /** 网格列数 */
    private static final int COLUMNS = 6;
    /** 每个村庄生成后立即完成的工程数 */
    private static final int PROJECTS = 30;
    /** 展示的文化（目录名、文化、默认市政厅） */
    private static final Object[][] CULTURES = {
        {"norman", Culture.NORMAN, "manor"},
        {"byzantines", Culture.BYZANTINE, "kastron"},
        {"indian", Culture.INDIAN, "chiefhouse"},
        {"japanese", Culture.JAPANESE, "japanesepalace"},
        {"mayan", Culture.MAYAN, "mayanpyramid"},
        {"seljuk", Culture.SELJUK, "hamlettownhall"},
        {"inuits", Culture.INUIT, "tannery"},
    };
    private static final DyeColor[] COLORS = {
        DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.CYAN, DyeColor.BLUE,
        DyeColor.PURPLE, DyeColor.MAGENTA, DyeColor.PINK, DyeColor.BROWN, DyeColor.BLACK, DyeColor.WHITE,
        DyeColor.LIGHT_BLUE, DyeColor.GREEN, DyeColor.GRAY, DyeColor.LIGHT_GRAY,
    };

    private record Job(ServerLevel level, BlockPos cellCorner, String culture, Culture cultureEnum, String template,
                       String townHall, DyeColor color) {
    }

    private static final ArrayDeque<Job> JOBS = new ArrayDeque<>();
    @Nullable
    private static Consumer<String> report;
    private static boolean stopWhenDone;
    /** 展示生成期间暂停自然生成村庄（加载大量区块时会排进队列） */
    public static volatile boolean running;

    private LayoutShowcase() {
    }

    /**
     * 排好所有模板的生成任务
     *
     * @param origin 网格西北角（地面高度取地表）
     * @return 模板数
     */
    public static int start(ServerLevel level, BlockPos origin, Consumer<String> reporter, boolean stop) {
        JOBS.clear();
        int index = 0;
        for (Object[] c : CULTURES) {
            String folder = (String) c[0];
            for (LayoutTemplate t : LayoutTemplate.forCulture(folder)) {
                String townHall = t.townHalls.isEmpty() ? (String) c[2] : t.townHalls.get(0);
                BlockPos corner = origin.offset((index % COLUMNS) * CELL, 0, (index / COLUMNS) * CELL);
                JOBS.add(new Job(level, corner, folder, (Culture) c[1], t.name, townHall, COLORS[index % COLORS.length]));
                index++;
            }
        }
        report = reporter;
        stopWhenDone = stop;
        running = !JOBS.isEmpty();
        return index;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !running) {
            return;
        }
        Job job = JOBS.poll();
        if (job == null) {
            running = false;
            finish(event.getServer());
            return;
        }
        try {
            build(job);
        } catch (Exception e) {
            MillenaireRewrite.LOGGER.error("Showcase {}/{} failed", job.culture(), job.template(), e);
            say(job.culture() + "/" + job.template() + " failed: " + e.getMessage());
        }
    }

    private static void build(Job job) {
        ServerLevel level = job.level();
        BlockPos corner = job.cellCorner();
        // 先生成整格的区块（超平坦世界很快）
        for (int cx = corner.getX() >> 4; cx <= (corner.getX() + CELL - 1) >> 4; cx++) {
            for (int cz = corner.getZ() >> 4; cz <= (corner.getZ() + CELL - 1) >> 4; cz++) {
                level.getChunk(cx, cz, ChunkStatus.FULL, true);
            }
        }
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, corner.getX() + CELL / 2,
            corner.getZ() + CELL / 2);
        BlockPos centre = new BlockPos(corner.getX() + CELL / 2, groundY, corner.getZ() + CELL / 2);

        VillageLayout.forcedTemplate = job.template();
        Village village;
        try {
            village = VillageGenerator.forceGenerateVillage(level, centre, job.cultureEnum(), job.townHall());
        } finally {
            VillageLayout.forcedTemplate = null;
        }
        int buildings = 0;
        if (village != null) {
            for (int i = 0; i < PROJECTS && village.getGrowth().forceNext(level) != null; i++) {
            }
            buildings = village.getGrowth().getBuildings().size();
        }
        border(level, corner, job.color());
        sign(level, corner.offset(3, 0, 3), job);
        say(job.culture() + "/" + job.template() + " (" + job.townHall() + "): "
            + (village == null ? "generation failed" : buildings + " buildings") + ", " + JOBS.size() + " left");
    }

    /**
     * 格子四边铺一圈羊毛（替换地表方块，内缩 1 格，相邻格子的边线颜色不同）
     */
    private static void border(ServerLevel level, BlockPos corner, DyeColor color) {
        BlockState wool = woolOf(color);
        int x1 = corner.getX() + 1;
        int z1 = corner.getZ() + 1;
        int x2 = corner.getX() + CELL - 2;
        int z2 = corner.getZ() + CELL - 2;
        for (int x = x1; x <= x2; x++) {
            setSurface(level, x, z1, wool);
            setSurface(level, x, z2, wool);
        }
        for (int z = z1; z <= z2; z++) {
            setSurface(level, x1, z, wool);
            setSurface(level, x2, z, wool);
        }
    }

    private static void setSurface(ServerLevel level, int x, int z, BlockState state) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_CLIENTS);
    }

    private static BlockState woolOf(DyeColor color) {
        return switch (color) {
            case ORANGE -> Blocks.ORANGE_WOOL.defaultBlockState();
            case MAGENTA -> Blocks.MAGENTA_WOOL.defaultBlockState();
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WOOL.defaultBlockState();
            case YELLOW -> Blocks.YELLOW_WOOL.defaultBlockState();
            case LIME -> Blocks.LIME_WOOL.defaultBlockState();
            case PINK -> Blocks.PINK_WOOL.defaultBlockState();
            case GRAY -> Blocks.GRAY_WOOL.defaultBlockState();
            case LIGHT_GRAY -> Blocks.LIGHT_GRAY_WOOL.defaultBlockState();
            case CYAN -> Blocks.CYAN_WOOL.defaultBlockState();
            case PURPLE -> Blocks.PURPLE_WOOL.defaultBlockState();
            case BLUE -> Blocks.BLUE_WOOL.defaultBlockState();
            case BROWN -> Blocks.BROWN_WOOL.defaultBlockState();
            case GREEN -> Blocks.GREEN_WOOL.defaultBlockState();
            case RED -> Blocks.RED_WOOL.defaultBlockState();
            case BLACK -> Blocks.BLACK_WOOL.defaultBlockState();
            default -> Blocks.WHITE_WOOL.defaultBlockState();
        };
    }

    /**
     * 格子角落的告示牌：文化 / 模板 / 市政厅
     */
    private static void sign(ServerLevel level, BlockPos column, Job job) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
        level.setBlock(pos.below(), woolOf(job.color()), Block.UPDATE_CLIENTS);
        // rotation 6 ≈ 朝东南，从格子内部看得到
        level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 6), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText()
                .setMessage(0, Component.literal(job.culture()))
                .setMessage(1, Component.literal(job.template()))
                .setMessage(2, Component.literal("town hall:"))
                .setMessage(3, Component.literal(job.townHall()));
            sign.setText(text, true);
            sign.setText(text, false);
            sign.setChanged();
        }
    }

    private static void finish(MinecraftServer server) {
        say("Showcase complete");
        MillenaireRewrite.LOGGER.info("[showcase] complete");
        if (stopWhenDone) {
            server.saveEverything(true, true, true);
            server.halt(false);
        }
    }

    private static void say(String message) {
        MillenaireRewrite.LOGGER.info("[showcase] {}", message);
        if (report != null) {
            report.accept(message);
        }
    }
}
