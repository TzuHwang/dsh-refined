package com.jasoncian.millenaire_rewrite.client.gui;

import com.jasoncian.millenaire_rewrite.economy.VillageTrade;
import com.jasoncian.millenaire_rewrite.menu.VillageTradeMenu;
import com.jasoncian.millenaire_rewrite.network.VillageTradePackets;
import com.jasoncian.millenaire_rewrite.network.compat.PacketDistributor;
import com.jasoncian.millenaire_rewrite.util.CurrencyUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 村庄商店交易界面（原版 GuiTrade）：上面是村庄出售的商品，中间是村庄收购的商品，下面是玩家背包。
 * 左键买卖 1 个，右键 8 个，Shift + 左键 64 个；鼠标停留显示价格、库存和所需声望。
 */
public class VillageTradeScreen extends AbstractContainerScreen<VillageTradeMenu> {

    private static final int COLUMNS = 9;
    private static final int ROWS = 3;
    private static final int PER_PAGE = COLUMNS * ROWS;
    private static final int SELLS_Y = 30;
    private static final int BUYS_Y = 96;

    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_DARK = 0xFF555555;
    private static final int PANEL_LIGHT = 0xFFFFFFFF;
    private static final int SLOT = 0xFF8B8B8B;
    private static final int UNAVAILABLE = 0x80202020;

    private final int[] page = new int[2];

    public VillageTradeScreen(VillageTradeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = VillageTradeMenu.INVENTORY_Y + 82;
        this.inventoryLabelY = VillageTradeMenu.INVENTORY_Y - 11;
    }

    // ================ 布局 ================

    private List<VillageTrade.Offer> list(boolean villageSells) {
        return menu.offers(villageSells);
    }

    private int listY(boolean villageSells) {
        return villageSells ? SELLS_Y : BUYS_Y;
    }

    private int pages(boolean villageSells) {
        return Math.max(1, (list(villageSells).size() + PER_PAGE - 1) / PER_PAGE);
    }

    /** 鼠标下的商品，没有时返回 null */
    private VillageTrade.Offer offerAt(double mouseX, double mouseY) {
        for (boolean sells : new boolean[] {true, false}) {
            int col = (int) Math.floor((mouseX - leftPos - 8) / 18);
            int row = (int) Math.floor((mouseY - topPos - listY(sells)) / 18);
            if (col < 0 || col >= COLUMNS || row < 0 || row >= ROWS) {
                continue;
            }
            int index = page[sells ? 0 : 1] * PER_PAGE + row * COLUMNS + col;
            List<VillageTrade.Offer> offers = list(sells);
            if (index < offers.size()) {
                return offers.get(index);
            }
        }
        return null;
    }

    private int playerHas(VillageTrade.Offer offer) {
        int count = 0;
        for (ItemStack stack : menu.getItems()) {
            if (ItemStack.isSameItemSameTags(stack, offer.item())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean available(VillageTrade.Offer offer) {
        if (menu.reputation() < offer.minReputation()) {
            return false;
        }
        if (offer.villageSells()) {
            return offer.stock() != 0 && menu.money() >= offer.price();
        }
        return playerHas(offer) > 0;
    }

    // ================ 绘制 ================

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        g.fill(x, y, x + imageWidth, y + 1, PANEL_LIGHT);
        g.fill(x, y, x + 1, y + imageHeight, PANEL_LIGHT);
        g.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, PANEL_DARK);
        g.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, PANEL_DARK);
        for (boolean sells : new boolean[] {true, false}) {
            drawSlots(g, x + 8, y + listY(sells), COLUMNS, ROWS);
        }
        drawSlots(g, x + 8, y + VillageTradeMenu.INVENTORY_Y, 9, 3);
        drawSlots(g, x + 8, y + VillageTradeMenu.INVENTORY_Y + 58, 9, 1);
    }

    private static void drawSlots(GuiGraphics g, int x, int y, int cols, int rows) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int sx = x + c * 18 - 1;
                int sy = y + r * 18 - 1;
                g.fill(sx, sy, sx + 18, sy + 18, PANEL_DARK);
                g.fill(sx + 1, sy + 1, sx + 18, sy + 18, PANEL_LIGHT);
                g.fill(sx + 1, sy + 1, sx + 17, sy + 17, SLOT);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, menu.title, 8, 6, 0x404040, false);
        g.drawString(font, Component.translatableWithFallback("gui.millenaire_rewrite.trade.village_sells", "Village sells"),
            8, SELLS_Y - 10, 0x404040, false);
        g.drawString(font, Component.translatableWithFallback("gui.millenaire_rewrite.trade.village_buys", "Village buys"),
            8, BUYS_Y - 10, 0x404040, false);
        Component money = CurrencyUtils.formatCurrency(menu.money());
        g.drawString(font, money, imageWidth - 8 - font.width(money), SELLS_Y - 10, 0x404040, false);
        Component rep = Component.translatableWithFallback("gui.millenaire_rewrite.trade.reputation", "Reputation: %s",
            menu.reputation());
        g.drawString(font, rep, imageWidth - 8 - font.width(rep), BUYS_Y - 10, 0x404040, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
        // 翻页
        for (boolean sells : new boolean[] {true, false}) {
            int pages = pages(sells);
            if (pages > 1) {
                String text = "< " + (page[sells ? 0 : 1] + 1) + "/" + pages + " >";
                int tx = (imageWidth - font.width(text)) / 2;
                g.drawString(font, text, tx, listY(sells) - 10, 0x404040, false);
            }
        }
        if (menu.lastProblem() != null) {
            Component problem = Component.translatableWithFallback("gui.millenaire_rewrite.trade.problem." + menu.lastProblem(),
                menu.lastProblem()).withStyle(ChatFormatting.DARK_RED);
            g.drawString(font, problem, imageWidth - 8 - font.width(problem), inventoryLabelY, 0x8B0000, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        for (boolean sells : new boolean[] {true, false}) {
            List<VillageTrade.Offer> offers = list(sells);
            int start = page[sells ? 0 : 1] * PER_PAGE;
            for (int i = 0; i < PER_PAGE && start + i < offers.size(); i++) {
                VillageTrade.Offer offer = offers.get(start + i);
                int x = leftPos + 8 + (i % COLUMNS) * 18;
                int y = topPos + listY(sells) + (i / COLUMNS) * 18;
                g.renderItem(offer.item(), x, y);
                if (!available(offer)) {
                    g.fill(x, y, x + 16, y + 16, UNAVAILABLE);
                }
            }
        }
        VillageTrade.Offer hovered = offerAt(mouseX, mouseY);
        if (hovered != null) {
            g.renderComponentTooltip(font, tooltip(hovered), mouseX, mouseY);
        } else {
            renderTooltip(g, mouseX, mouseY);
        }
    }

    private List<Component> tooltip(VillageTrade.Offer offer) {
        List<Component> lines = new ArrayList<>();
        lines.add(offer.item().getHoverName());
        lines.add(Component.translatableWithFallback(offer.villageSells() ? "gui.millenaire_rewrite.trade.price_buy"
            : "gui.millenaire_rewrite.trade.price_sell", offer.villageSells() ? "Price: %s" : "Village pays: %s",
            CurrencyUtils.formatCurrency(offer.price())).withStyle(ChatFormatting.GOLD));
        if (offer.villageSells()) {
            lines.add(offer.stock() < 0
                ? Component.translatableWithFallback("gui.millenaire_rewrite.trade.stock_unlimited", "In stock: plenty")
                : Component.translatableWithFallback("gui.millenaire_rewrite.trade.stock", "In stock: %s", offer.stock()));
        } else {
            lines.add(Component.translatableWithFallback("gui.millenaire_rewrite.trade.you_have", "You have: %s",
                playerHas(offer)));
        }
        if (offer.minReputation() > Integer.MIN_VALUE && menu.reputation() < offer.minReputation()) {
            lines.add(Component.translatableWithFallback("gui.millenaire_rewrite.trade.needs_reputation",
                "Needs reputation %s", offer.minReputation()).withStyle(ChatFormatting.RED));
        }
        lines.add(Component.translatableWithFallback("gui.millenaire_rewrite.trade.controls",
            "Click: 1, right click: 8, shift click: 64").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    // ================ 点击 ================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 翻页：点击标签行中间的 "< n/m >"
        for (boolean sells : new boolean[] {true, false}) {
            int pages = pages(sells);
            int ly = topPos + listY(sells) - 10;
            if (pages > 1 && mouseY >= ly && mouseY < ly + 9) {
                int centre = leftPos + imageWidth / 2;
                if (Math.abs(mouseX - centre) < 30) {
                    int i = sells ? 0 : 1;
                    page[i] = Math.floorMod(page[i] + (mouseX < centre ? -1 : 1), pages);
                    return true;
                }
            }
        }
        VillageTrade.Offer offer = offerAt(mouseX, mouseY);
        if (offer != null && (button == 0 || button == 1)) {
            int amount = button == 1 ? 8 : Screen.hasShiftDown() ? 64 : 1;
            PacketDistributor.sendToServer(new VillageTradePackets.Action(menu.containerId, offer.villageSells(),
                offer.key(), amount));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
