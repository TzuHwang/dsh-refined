package com.jasoncian.millenaire_rewrite.village;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 每个服务端世界 tick 结束时更新该世界的所有村庄（成长、施工）。
 */
@Mod.EventBusSubscriber(modid = MillenaireRewrite.MOD_ID)
public final class VillageTickEvents {

    private VillageTickEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }
        VillageManager.get(level).tick();
    }
}
