package com.jasoncian.millenaire_rewrite;

import com.jasoncian.millenaire_rewrite.client.ModKeybindings;
import com.jasoncian.millenaire_rewrite.client.gui.FirePitScreen;
import com.jasoncian.millenaire_rewrite.client.gui.ImportTableScreen;
import com.jasoncian.millenaire_rewrite.client.gui.LockedChestScreen;
import com.jasoncian.millenaire_rewrite.client.gui.TownHallScreen;
import com.jasoncian.millenaire_rewrite.client.gui.TradingScreen;
import com.jasoncian.millenaire_rewrite.client.gui.VillagerInteractionScreen;
import com.jasoncian.millenaire_rewrite.core.ModBlocks;
import com.jasoncian.millenaire_rewrite.core.ModItems;
import com.jasoncian.millenaire_rewrite.core.ModBlockItems;
import com.jasoncian.millenaire_rewrite.core.ModEntities;
import com.jasoncian.millenaire_rewrite.core.ModBlockEntities;
import com.jasoncian.millenaire_rewrite.core.ModMenuTypes;
import com.jasoncian.millenaire_rewrite.core.ModToolMaterials;
import com.jasoncian.millenaire_rewrite.core.MillCreativeTabs;
import com.jasoncian.millenaire_rewrite.config.MillenaireConfig;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.menu.FirePitMenu;
import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import com.jasoncian.millenaire_rewrite.client.renderer.MillVillagerRenderer;
import org.slf4j.Logger;

/**
 * Millenaire Rewrite - 现代化重制版主类
 * 
 * 功能列表:
 * - 模组初始化和注册管理
 * - 创造模式标签页配置
 * - 模组事件处理
 * - 日志记录管理
 * - 现代化的模组架构设计
 * - 完整的注册系统管理
 * - 客户端和服务端分离
 * - 数据生成系统集成
 * - 配置系统支持
 * 
 * @author JasonCian
 * @version 0.1.3-alpha
 * @since 1.20.1
 */
@Mod(MillenaireRewrite.MOD_ID)
public class MillenaireRewrite {

    // 模组基本信息
    public static final String MOD_ID = "millenaire_rewrite";
    public static final String MOD_NAME = "Millenaire Rewrite";
    public static final String VERSION = "0.1.0-alpha";

    // 日志记录器
    public static final Logger LOGGER = LogUtils.getLogger();

    @SuppressWarnings("removal") // FMLJavaModLoadingContext.get() 在1.20.1中是正确的用法
    public MillenaireRewrite() {
        // 获取mod事件总线
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册核心组件
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        // ModBlockItems.register(modEventBus); 注册方块物品
        ModEntities.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        MillCreativeTabs.register(modEventBus);

        // 注册事件监听器
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerEntityAttributes);

        // 注册Forge事件总线 - 使用静态方法避免this泄漏
        MinecraftForge.EVENT_BUS.addListener(MillenaireRewrite::onServerStarting);

        // 注册配置（使用现代方式）
        modEventBus.addListener(MillenaireConfig::onLoad);
        modEventBus.addListener(MillenaireConfig::onReload);

        LOGGER.info("Millenaire Rewrite mod initialized!");
    }

    /**
     * 通用设置阶段
     * 在这里进行与客户端/服务器无关的初始化
     */
    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Millenaire Rewrite common setup starting...");

        event.enqueueWork(() -> {
            // 初始化工具层级排序
            ModToolMaterials.initializeTierSorting();

            // 在这里进行需要主线程的初始化工作
            // 例如：配置网络数据包、注册生物群系特性等

            // TODO: 华夏文明初始化系统（预留空间）
            //
            // 华夏文明初始化包括：
            // - 华夏村庄类型注册
            // - 华夏建筑模板加载
            // - 华夏村民类型配置
            // - 华夏贸易系统初始化
            // - 华夏文化语言系统设置
            // - 华夏特殊事件注册
            //
            // 示例代码：
            // HuaxiaCultureManager.initialize();
            // HuaxiaVillageRegistry.registerVillageTypes();
            // HuaxiaBuildingRegistry.loadBuildingTemplates();
            // HuaxiaTradeSystem.initializeTradeGoods();
        });

        LOGGER.info("Millenaire Rewrite common setup completed!");
    }

    /**
     * Register entity attributes
     */
    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.MILL_VILLAGER.get(), MillVillager.createAttributes().build());
        LOGGER.info("Millenaire Rewrite entity attributes registered!");
    }

    /**
     * Server starting event handler
     */
    public static void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Millenaire Rewrite server is starting...");
        // 在这里进行服务器启动时的初始化
        // 例如：加载村庄数据、初始化全局状态等
    }

    /**
     * 仅客户端的事件处理
     */
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("Millenaire Rewrite client setup starting...");

            event.enqueueWork(() -> {
                // 在这里进行客户端特定的初始化
                // 例如：注册渲染器、键位绑定等
            });

            LOGGER.info("Millenaire Rewrite client setup completed!");
        }

        @SubscribeEvent
        public static void registerScreens(RegisterMenuScreensEvent event) {
            // 注册火坑GUI屏幕
            event.register(ModMenuTypes.FIRE_PIT.get(), FirePitScreen::new);
            // 注册锁定箱子GUI屏幕
            event.register(ModMenuTypes.LOCKED_CHEST.get(), LockedChestScreen::new);
            // 注册导入桌GUI屏幕
            event.register(ModMenuTypes.IMPORT_TABLE.get(), ImportTableScreen::new);
            // 注册村民交互GUI屏幕
            event.register(ModMenuTypes.VILLAGER_INTERACTION.get(), VillagerInteractionScreen::new);
            // 注册市政厅GUI屏幕
            event.register(ModMenuTypes.TOWN_HALL.get(), TownHallScreen::new);
            // 注册交易GUI屏幕
            event.register(ModMenuTypes.TRADING.get(), TradingScreen::new);
        }

        @SubscribeEvent
        public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
            // 注册村民渲染器
            event.registerEntityRenderer(ModEntities.MILL_VILLAGER.get(), MillVillagerRenderer::new);
            LOGGER.info("Millenaire Rewrite entity renderers registered!");
        }

        @SubscribeEvent
        public static void registerKeybindings(RegisterKeyMappingsEvent event) {
            // 注册快捷键
            ModKeybindings.register(event);
        }
    }
}
