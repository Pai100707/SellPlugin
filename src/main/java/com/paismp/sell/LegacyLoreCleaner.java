package com.paismp.sell;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Versions up to 1.3.0 added "Worth" lore to inventory items and counted the added lines in a PDC marker.
 * That lore stopped items from stacking. This class removes it again (join + enable); it never adds anything.
 */
public final class LegacyLoreCleaner implements Listener {

    private final SellPlugin plugin;
    private final NamespacedKey markerKey;

    public LegacyLoreCleaner(SellPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "worth_lore");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("legacy_lore_cleanup", true);
    }

    public void cleanOnlinePlayers() {
        if (!enabled()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            clean(p.getInventory());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (enabled()) {
            clean(event.getPlayer().getInventory());
        }
    }

    private void clean(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stripped = strip(inv.getItem(i));
            if (stripped != null) inv.setItem(i, stripped);
        }
    }

    /** Returns a copy without the old lore lines, or null if the item has none. */
    private ItemStack strip(ItemStack original) {
        if (original == null || original.getType().isAir()) return null;
        ItemMeta meta = original.getItemMeta();
        if (meta == null) return null;
        Integer added = meta.getPersistentDataContainer().get(markerKey, PersistentDataType.INTEGER);
        if (added == null) return null;

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        // never cut lore of items that belong to other plugins (AmethystTools rewrites its own last lore line)
        if (!plugin.service().isProtected(original) && added > 0 && lore.size() >= added) {
            lore = new ArrayList<>(lore.subList(0, lore.size() - added));
        }
        ItemStack copy = original.clone();
        ItemMeta newMeta = copy.getItemMeta();
        newMeta.lore(lore.isEmpty() ? null : lore);
        newMeta.getPersistentDataContainer().remove(markerKey);
        copy.setItemMeta(newMeta);
        return copy;
    }
}
