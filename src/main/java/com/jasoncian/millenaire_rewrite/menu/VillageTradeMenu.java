package com.jasoncian.millenaire_rewrite.menu;

import com.jasoncian.millenaire_rewrite.core.ModMenuTypes;
import com.jasoncian.millenaire_rewrite.economy.VillageTrade;
import com.jasoncian.millenaire_rewrite.util.CurrencyUtils;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import com.jasoncian.millenaire_rewrite.village.VillageManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 村庄商店交易界面（原版 ContainerTrade）：上半部是商店的买卖清单，下半部是玩家背包。
 * 清单不是物品槽，由界面绘制；点击后发包到服务端成交，服务端再把新的清单发回来。
 */
public class VillageTradeMenu extends AbstractContainerMenu {

    /** 背包区域在界面中的纵向位置 */
    public static final int INVENTORY_Y = 152;

    private final UUID villageId;
    private final int buildingIndex;
    public final String title;
    private List<VillageTrade.Offer> offers;
    private int money;
    private int reputation;
    @Nullable
    private String lastProblem;

    /** 服务端 */
    public VillageTradeMenu(int containerId, Inventory inventory, Village village, int buildingIndex, String title) {
        super(ModMenuTypes.VILLAGE_TRADE.get(), containerId);
        this.villageId = village.getVillageId();
        this.buildingIndex = buildingIndex;
        this.title = title;
        this.offers = new ArrayList<>();
        addPlayerInventory(inventory);
        refresh(inventory.player);
    }

    /** 客户端 */
    public VillageTradeMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(ModMenuTypes.VILLAGE_TRADE.get(), containerId);
        this.villageId = buf.readUUID();
        this.buildingIndex = buf.readVarInt();
        this.title = buf.readUtf();
        addPlayerInventory(inventory);
        readState(buf);
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, INVENTORY_Y + 58));
        }
    }

    // ================ 服务端 ================

    @Nullable
    private Village village(Player player) {
        return player.level() instanceof ServerLevel level ? VillageManager.get(level).getVillage(villageId) : null;
    }

    @Nullable
    private VillageGrowth.PlacedBuilding building(Village village) {
        List<VillageGrowth.PlacedBuilding> buildings = village.getGrowth().getBuildings();
        return buildingIndex >= 0 && buildingIndex < buildings.size() ? buildings.get(buildingIndex) : null;
    }

    /**
     * 重新计算清单、金钱和声望（服务端）
     */
    public void refresh(Player player) {
        Village village = village(player);
        VillageGrowth.PlacedBuilding building = village == null ? null : building(village);
        if (village == null || building == null || !(player.level() instanceof ServerLevel level)) {
            offers = new ArrayList<>();
            return;
        }
        offers = VillageTrade.offers(level, village, building, player);
        money = CurrencyUtils.getPlayerTotalCurrency(player);
        reputation = village.getGrowth().getReputation(player.getUUID());
    }

    /**
     * 成交（服务端，由客户端的点击包触发）
     */
    public void trade(ServerPlayer player, boolean villageSells, String key, int amount) {
        Village village = village(player);
        VillageGrowth.PlacedBuilding building = village == null ? null : building(village);
        if (village == null || building == null) {
            return;
        }
        VillageTrade.Result result = VillageTrade.execute(player.serverLevel(), village, building, player, villageSells,
            key, amount);
        lastProblem = result.problem();
        refresh(player);
        broadcastChanges();
    }

    public void writeState(FriendlyByteBuf buf) {
        buf.writeVarInt(offers.size());
        offers.forEach(o -> o.write(buf));
        buf.writeVarInt(money);
        buf.writeInt(reputation);
        buf.writeUtf(lastProblem == null ? "" : lastProblem);
    }

    /**
     * 打开界面时写给客户端的数据（与客户端构造函数对应）
     */
    public static void writeOpenData(FriendlyByteBuf buf, Village village, int buildingIndex, String title,
                                     ServerPlayer player) {
        buf.writeUUID(village.getVillageId());
        buf.writeVarInt(buildingIndex);
        buf.writeUtf(title);
        VillageGrowth.PlacedBuilding building = village.getGrowth().getBuildings().get(buildingIndex);
        List<VillageTrade.Offer> offers = VillageTrade.offers(player.serverLevel(), village, building, player);
        buf.writeVarInt(offers.size());
        offers.forEach(o -> o.write(buf));
        buf.writeVarInt(CurrencyUtils.getPlayerTotalCurrency(player));
        buf.writeInt(village.getGrowth().getReputation(player.getUUID()));
        buf.writeUtf("");
    }

    /**
     * 卖家所在的商店：打开交易界面；卖家不在商店里时返回 false
     */
    public static boolean open(ServerPlayer player, com.jasoncian.millenaire_rewrite.entity.MillVillager seller) {
        Village village = seller.getHomeVillage();
        if (village == null || !VillageTrade.isSeller(seller)) {
            return false;
        }
        VillageGrowth.PlacedBuilding shop = VillageTrade.shopAt(village, seller);
        if (shop == null) {
            return false;
        }
        if (village.getGrowth().getReputation(player.getUUID()) < VillageGrowth.MIN_REPUTATION_FOR_TRADE) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatableWithFallback(
                "message.millenaire_rewrite.seller_boycott", "%s refuses to trade with you.", seller.getName()));
            return true;
        }
        int index = village.getGrowth().getBuildings().indexOf(shop);
        String title = shopTitle(village, shop) + " - " + seller.getName().getString();
        net.minecraftforge.network.NetworkHooks.openScreen(player, new net.minecraft.world.SimpleMenuProvider(
                (id, inventory, p) -> new VillageTradeMenu(id, inventory, village, index, title),
                net.minecraft.network.chat.Component.literal(title)),
            buf -> writeOpenData(buf, village, index, title, player));
        return true;
    }

    private static String shopTitle(Village village, VillageGrowth.PlacedBuilding shop) {
        try {
            var plan = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan.get(
                com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes.cultureFolder(village.getCulture()),
                shop.key, shop.variation, shop.level);
            if (plan.nativeName != null) {
                return plan.nativeName;
            }
        } catch (java.io.IOException e) {
            // 用建筑键名
        }
        return shop.key;
    }

    // ================ 客户端 ================

    public void readState(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<VillageTrade.Offer> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(VillageTrade.Offer.read(buf));
        }
        offers = list;
        money = buf.readVarInt();
        reputation = buf.readInt();
        String problem = buf.readUtf();
        lastProblem = problem.isEmpty() ? null : problem;
    }

    public List<VillageTrade.Offer> offers(boolean villageSells) {
        return offers.stream().filter(o -> o.villageSells() == villageSells).toList();
    }

    public int money() {
        return money;
    }

    public int reputation() {
        return reputation;
    }

    @Nullable
    public String lastProblem() {
        return lastProblem;
    }

    // ================ 容器 ================

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (!(player.level() instanceof ServerLevel)) {
            return true;
        }
        Village village = village(player);
        VillageGrowth.PlacedBuilding building = village == null ? null : building(village);
        return building != null && player.distanceToSqr(building.origin.getX(), building.origin.getY(),
            building.origin.getZ()) < 48 * 48;
    }
}
