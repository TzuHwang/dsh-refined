package com.jasoncian.millenaire_rewrite.command;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 世界加速（1.20.1 没有 /tick sprint）：每个服务器 tick 后让所有维度额外运行 factor - 1 次，
 * 村民、作物、时间和村庄成长都会一起加速。玩家本身不加速。
 */
@Mod.EventBusSubscriber(modid = MillenaireRewrite.MOD_ID)
public final class TickWarp {

    public static final int MAX_FACTOR = 20;
    private static int factor = 1;
    private static boolean warping;
    /** 已额外运行的维度 tick 数（测试用） */
    public static long extraTicks;

    private TickWarp() {
    }

    public static int factor() {
        return factor;
    }

    public static void setFactor(int value) {
        factor = Math.max(1, Math.min(MAX_FACTOR, value));
        MillenaireRewrite.LOGGER.info("World speed set to x{}", factor);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || factor <= 1 || warping) {
            return;
        }
        warping = true;
        try {
            for (int i = 1; i < factor; i++) {
                for (ServerLevel level : event.getServer().getAllLevels()) {
                    ForgeEventFactory.onPreLevelTick(level, () -> true);
                    level.tick(() -> true);
                    extraTicks++;
                    ForgeEventFactory.onPostLevelTick(level, () -> true);
                }
            }
        } finally {
            warping = false;
        }
    }

    /**
     * 系统属性 millenaire.warp 设定启动时的加速倍数（测试服务器默认 10 倍）
     */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        String warp = System.getProperty("millenaire.warp", "");
        if (!warp.isEmpty()) {
            try {
                setFactor(Integer.parseInt(warp.trim()));
            } catch (NumberFormatException e) {
                MillenaireRewrite.LOGGER.warn("Bad millenaire.warp value '{}'", warp);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        factor = 1;
    }
}
