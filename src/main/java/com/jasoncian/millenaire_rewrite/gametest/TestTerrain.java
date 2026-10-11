package com.jasoncian.millenaire_rewrite.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 测试地形工具。测试世界每次都是全新的正常地形（山地、河流、洞穴），
 * 村庄和村民行为测试在高空平台上进行，结果才不受随机地形影响。
 */
public final class TestTerrain {

    public static final int PLATFORM_TOP = 200;
    public static final int PLATFORM_HALF_SIZE = 85;
    private static final int PLATFORM_DEPTH = 12;
    /** 村庄选址半径约 72 格，强制加载周围 6 个区块 */
    private static final int CHUNK_RADIUS = 6;

    private TestTerrain() {
    }

    /**
     * 强制加载（或释放）pos 周围的区块。强制加载是异步的，这里同步加载，确保之后立刻可用
     */
    public static void setForced(ServerLevel level, BlockPos pos, boolean forced) {
        setForced(level, pos, forced, CHUNK_RADIUS);
    }

    public static void setForced(ServerLevel level, BlockPos pos, boolean forced, int chunkRadius) {
        ChunkPos centre = new ChunkPos(pos);
        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                level.setChunkForced(centre.x + cx, centre.z + cz, forced);
                if (forced) {
                    level.getChunk(centre.x + cx, centre.z + cz);
                }
            }
        }
    }

    /** 平台上方清空的高度 */
    private static final int CLEAR_HEIGHT = 40;

    /**
     * 在 centre 周围搭一块草地平台，顶面在 PLATFORM_TOP，地面上第一格空气为 PLATFORM_TOP + 1
     */
    public static void buildPlatform(ServerLevel level, BlockPos centre) {
        buildPlatform(level, centre, PLATFORM_HALF_SIZE);
    }

    /**
     * @param halfSize 平台半宽（佈局村庄的田地可能伸到 100 格以外）
     */
    public static void buildPlatform(ServerLevel level, BlockPos centre, int halfSize) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -halfSize; x <= halfSize; x++) {
            for (int z = -halfSize; z <= halfSize; z++) {
                for (int y = PLATFORM_TOP - PLATFORM_DEPTH; y < PLATFORM_TOP; y++) {
                    p.set(centre.getX() + x, y, centre.getZ() + z);
                    level.setBlock(p, stone, flags);
                }
                p.set(centre.getX() + x, PLATFORM_TOP, centre.getZ() + z);
                level.setBlock(p, grass, flags);
                // 清空平台上方：新种子的世界里山峰、树木可能高过平台
                for (int y = PLATFORM_TOP + 1; y <= PLATFORM_TOP + CLEAR_HEIGHT; y++) {
                    p.set(centre.getX() + x, y, centre.getZ() + z);
                    if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), flags);
                    }
                }
            }
        }
    }

    /**
     * 平台上的地面位置（地面上第一格空气）
     */
    public static BlockPos ground(BlockPos column) {
        return new BlockPos(column.getX(), PLATFORM_TOP + 1, column.getZ());
    }
}
