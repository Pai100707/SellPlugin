package com.paismp.sell;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The /sell menu: put items in, they are sold when the menu is closed (or when SELL is clicked). */
public final class SellGui implements InventoryHolder {

    private final SellPlugin plugin;
    private final Player player;
    private final GuiLayout layout;
    private final Inventory inventory;
    private boolean finished;

    public SellGui(SellPlugin plugin, Player player, GuiLayout layout) {
        this.plugin = plugin;
        this.player = player;
        this.layout = layout;
        this.inventory = Bukkit.createInventory(this, layout.size(), plugin.messages().text(layout.titleKey));
        refreshButtons();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open() {
        player.openInventory(inventory);
    }

    // ------------------------------------------------------------------ content

    private List<ItemStack> contentStacks() {
        List<ItemStack> out = new ArrayList<>();
        for (int slot : layout.contentSlots) {
            ItemStack it = inventory.getItem(slot);
            if (it != null && !it.getType().isAir()) out.add(it);
        }
        return out;
    }

    /** Evaluates everything in the content slots without changing it. */
    private SellService.Evaluation preview(double multiplier, int[] unsellableOut) {
        SellService service = plugin.service();
        SellService.Evaluation total = new SellService.Evaluation();
        int unsellable = 0;
        for (ItemStack stack : contentStacks()) {
            SellService.Evaluation ev = service.evaluate(stack, multiplier);
            if (ev.total > 0) {
                total.absorb(ev);
            } else {
                unsellable++;
            }
        }
        unsellableOut[0] = unsellable;
        return total;
    }

    /** Updates the SELL button (live total) after the contents changed. */
    public void refreshButtons() {
        Messages msg = plugin.messages();
        double multiplier = plugin.service().getMultiplier(player);
        int[] unsellable = new int[1];
        SellService.Evaluation ev = preview(multiplier, unsellable);
        for (GuiLayout.Button button : layout.buttons) {
            ItemStack icon = layout.build(button, msg,
                    "total", msg.money(ev.total),
                    "count", String.valueOf(ev.itemCount),
                    "multiplier", msg.money(multiplier),
                    "unsellable", String.valueOf(unsellable[0]));
            for (int slot : button.slots) {
                if (!layout.isContentSlot(slot)) {
                    inventory.setItem(slot, icon.clone());
                }
            }
        }
    }

    private void refreshLater() {
        player.getScheduler().runDelayed(plugin, task -> {
            if (!finished && player.getOpenInventory().getTopInventory().getHolder(false) == this) {
                refreshButtons();
            }
        }, null, 1L);
    }

    // ------------------------------------------------------------------ events

    public void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw >= 0 && raw < inventory.getSize()) {
            if (layout.isContentSlot(raw)) {
                refreshLater();
                return;
            }
            event.setCancelled(true);
            GuiLayout.Button button = layout.buttonAt(raw);
            if (button != null && button.action == GuiLayout.Action.SELL) {
                player.getScheduler().run(plugin, task -> {
                    if (finished) return;
                    if (!layout.sellOnClose) {
                        finish(true);
                    }
                    player.closeInventory();
                }, null);
            }
            return;
        }
        // click in the player's own inventory (shift-click moves items into the menu)
        refreshLater();
    }

    public void onDrag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw < inventory.getSize() && !layout.isContentSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        refreshLater();
    }

    /** Called when the menu is closed. */
    public void onClose() {
        finish(layout.sellOnClose);
    }

    /** Plugin shutdown: give everything back, never sell. */
    public void shutdown() {
        finish(false);
    }

    // ------------------------------------------------------------------ selling

    private void finish(boolean sell) {
        if (finished) return;
        finished = true;

        List<ItemStack> stacks = new ArrayList<>();
        for (int slot : layout.contentSlots) {
            ItemStack it = inventory.getItem(slot);
            if (it != null && !it.getType().isAir()) {
                stacks.add(it);
                inventory.setItem(slot, null);
            }
        }
        if (stacks.isEmpty()) return;

        Messages msg = plugin.messages();
        SellService service = plugin.service();
        if (!sell) {
            service.giveBack(player, stacks);
            return;
        }
        if (!player.hasPermission("sell.sell")) {
            msg.send(player, "no_permission");
            service.giveBack(player, stacks);
            return;
        }
        Economy economy = plugin.getEconomy();
        if (economy == null) {
            msg.send(player, "no_economy");
            service.giveBack(player, stacks);
            return;
        }

        double multiplier = service.getMultiplier(player);
        SellService.Evaluation total = new SellService.Evaluation();
        List<ItemStack> returned = new ArrayList<>();
        for (ItemStack stack : stacks) {
            SellService.Evaluation ev = service.evaluate(stack, multiplier);
            if (ev.total > 0) {
                total.absorb(ev);
                if (ev.remaining != null) returned.add(ev.remaining);   // e.g. the shulker box with its unsellable items
            } else {
                returned.add(stack);
            }
        }

        if (total.total <= 0) {
            msg.send(player, "nothing_to_sell");
            service.giveBack(player, stacks);
            return;
        }
        EconomyResponse response = economy.depositPlayer(player, total.total);
        if (!response.transactionSuccess()) {
            msg.send(player, "payout_failed");
            service.giveBack(player, stacks);
            return;
        }

        int given = service.giveBack(player, returned);
        service.sendSummary(player, total, multiplier);
        service.playEffects(player, total);
        if (given > 0) {
            msg.send(player, "unsellable_returned", "count", String.valueOf(given));
        }
    }
}
