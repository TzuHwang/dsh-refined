package com.jasoncian.millenaire_rewrite.core;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.menu.FirePitMenu;
import com.jasoncian.millenaire_rewrite.menu.ImportTableMenu;
import com.jasoncian.millenaire_rewrite.menu.LockedChestMenu;
import com.jasoncian.millenaire_rewrite.menu.TownHallMenu;
import com.jasoncian.millenaire_rewrite.menu.TradingMenu;
import com.jasoncian.millenaire_rewrite.menu.VillagerInteractionMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

/**
 * 模组菜单类型注册器
 *
 * 负责注册所有Millenaire mod的菜单类型
 * 用于GUI容器系统
 *
 * 菜单类型：
 * - 火坑 (FirePit) - 多槽位烹饪GUI
 * - 锁定箱子 (LockedChest) - 村庄存储GUI（待实现）
 * - 交易 (Trading) - 村民交易GUI（待实现）
 */
public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.MENU_TYPES, MillenaireRewrite.MOD_ID);

    // ================ 功能方块菜单 ================

    /** 火坑菜单 */
    public static final RegistryObject<MenuType<FirePitMenu>> FIRE_PIT =
        MENUS.register("fire_pit", () -> IForgeMenuType.create(FirePitMenu::new));

    /** 锁定箱子菜单 */
    public static final RegistryObject<MenuType<LockedChestMenu>> LOCKED_CHEST =
        MENUS.register("locked_chest", () -> IForgeMenuType.create(LockedChestMenu::new));

    /** 导入桌菜单 */
    public static final RegistryObject<MenuType<ImportTableMenu>> IMPORT_TABLE =
        MENUS.register("import_table", () -> IForgeMenuType.create(ImportTableMenu::new));

    // ================ 村庄管理菜单 ================

    /** 市政厅菜单 */
    public static final RegistryObject<MenuType<TownHallMenu>> TOWN_HALL =
        MENUS.register("town_hall", () -> IForgeMenuType.create(TownHallMenu::new));

    // ================ 村民交互菜单 ================

    /** 村民交互菜单 */
    public static final RegistryObject<MenuType<VillagerInteractionMenu>> VILLAGER_INTERACTION =
        MENUS.register("villager_interaction", () -> IForgeMenuType.create(VillagerInteractionMenu::new));

    /** 交易菜单 */
    public static final RegistryObject<MenuType<TradingMenu>> TRADING =
        MENUS.register("trading", () -> IForgeMenuType.create(TradingMenu::new));

    /** 村庄商店交易菜单（原版式：按商店的买卖清单交易） */
    public static final RegistryObject<MenuType<com.jasoncian.millenaire_rewrite.menu.VillageTradeMenu>> VILLAGE_TRADE =
        MENUS.register("village_trade", () -> IForgeMenuType.create(com.jasoncian.millenaire_rewrite.menu.VillageTradeMenu::new));

    /**
     * 注册所有菜单类型到模组事件总线
     *
     * @param eventBus 模组事件总线
     */
    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
