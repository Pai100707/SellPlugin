package com.paismp.sell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Selling logic shared by the sell menu, /sell hand, /worth hand and the worth menu. */
public final class SellService {

    /** Result of evaluating one or more stacks. */
    public static final class Evaluation {
        /** Material -> {amount, worth} */
        public final Map<Material, long[]> lines = new LinkedHashMap<>();
        public long total;
        public int itemCount;
        public boolean viaShulker;
        /** What should be left of the evaluated stack after selling (null = nothing). */
        public ItemStack remaining;

        void add(Material m, int amount, long worth) {
            long[] cur = lines.computeIfAbsent(m, k -> new long[2]);
            cur[0] += amount;
            cur[1] += worth;
            total += worth;
            itemCount += amount;
        }

        /** Adds the sold lines of another evaluation to this one (remaining is not merged). */
        public void absorb(Evaluation other) {
            for (Map.Entry<Material, long[]> e : other.lines.entrySet()) {
                long[] cur = lines.computeIfAbsent(e.getKey(), k -> new long[2]);
                cur[0] += e.getValue()[0];
                cur[1] += e.getValue()[1];
            }
            total += other.total;
            itemCount += other.itemCount;
            viaShulker |= other.viaShulker;
        }
    }

    private final SellPlugin plugin;
    private final NamespacedKey legacyMarker;
    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);
    private final Set<String> protectedKeys = new HashSet<>();
    private final Set<String> protectedNamespaces = new HashSet<>();
    private String soundKey;

    public SellService(SellPlugin plugin) {
        this.plugin = plugin;
        this.legacyMarker = new NamespacedKey(plugin, "worth_lore");
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        blacklist.clear();
        for (String s : c.getStringList("anti_exploit.blacklist_materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blacklist.add(m);
        }
        protectedKeys.clear();
        for (String s : c.getStringList("anti_exploit.protected_keys")) {
            protectedKeys.add(s.toLowerCase(Locale.ROOT));
        }
        protectedNamespaces.clear();
        for (String s : c.getStringList("anti_exploit.protected_namespaces")) {
            protectedNamespaces.add(s.toLowerCase(Locale.ROOT));
        }
        soundKey = resolveSound(c.getString("effects.sound.name", "ENTITY_EXPERIENCE_ORB_PICKUP"));
    }

    private static String resolveSound(String name) {
        if (name == null || name.isBlank()) return null;
        for (Sound s : Registry.SOUNDS) {
            NamespacedKey k = Registry.SOUNDS.getKey(s);
            if (k == null) continue;
            String key = k.getKey();
            if (key.equalsIgnoreCase(name) || key.replace('.', '_').equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    public double getMultiplier(Player player) {
        double best = 1.0;
        String prefix = "sell.multiplier.";
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission().toLowerCase(Locale.ROOT);
            if (!perm.startsWith(prefix)) continue;
            try {
                best = Math.max(best, Double.parseDouble(perm.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    /** Items made by other plugins that must never be sold (AmethystTools etc.). */
    public boolean isProtected(ItemStack item) {
        if (item == null || protectedKeys.isEmpty() && protectedNamespaces.isEmpty()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        for (NamespacedKey k : meta.getPersistentDataContainer().getKeys()) {
            if (protectedNamespaces.contains(k.getNamespace().toLowerCase(Locale.ROOT))
                    || protectedKeys.contains(k.asString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Anti-exploit filter (protected plugin items, custom model data, custom PDC/NBT, blacklist). */
    public boolean isBlocked(ItemStack item) {
        if (blacklist.contains(item.getType())) return true;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        if (isProtected(item)) return true;
        FileConfiguration c = plugin.getConfig();
        if (c.getBoolean("anti_exploit.block_custom_model_data", true) && meta.hasCustomModelDataComponent()) {
            return true;
        }
        if (c.getBoolean("anti_exploit.block_custom_name", false) && meta.hasDisplayName()) {
            return true;
        }
        if (c.getBoolean("anti_exploit.block_persistent_data", true)) {
            for (NamespacedKey k : meta.getPersistentDataContainer().getKeys()) {
                if (!k.equals(legacyMarker)) return true;   // legacy 1.3.0 lore marker is harmless
            }
        }
        return false;
    }

    public long stackWorth(ItemStack item, double multiplier) {
        if (item == null || item.getType().isAir() || isBlocked(item)) return 0;
        double unit = plugin.prices().unitWorth(item);
        if (unit <= 0) return 0;
        return plugin.prices().round(unit * item.getAmount() * multiplier);
    }

    private static boolean isEmpty(Inventory inv) {
        for (ItemStack it : inv.getContents()) {
            if (it != null && !it.getType().isAir()) return false;
        }
        return true;
    }

    /** Dry-run on one stack: works on copies, never touches the real item. */
    public Evaluation evaluate(ItemStack hand, double multiplier) {
        Evaluation ev = new Evaluation();
        if (hand == null || hand.getType().isAir()) return ev;

        if (Tag.SHULKER_BOXES.isTagged(hand.getType())
                && hand.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof ShulkerBox box) {
            Inventory inv = box.getInventory();
            if (!isEmpty(inv)) {
                ev.viaShulker = true;
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack content = inv.getItem(i);
                    if (content == null || content.getType().isAir()) continue;
                    long worth = stackWorth(content, multiplier);
                    if (worth <= 0) continue;           // unsellable stays inside the box
                    ev.add(content.getType(), content.getAmount(), worth);
                    inv.setItem(i, null);
                }
                bsm.setBlockState(box);
                ItemStack copy = hand.clone();
                copy.setItemMeta(bsm);
                ev.remaining = copy;
                return ev;
            }
            // empty shulker box -> sell the box itself (falls through)
        }

        long worth = stackWorth(hand, multiplier);
        if (worth > 0) {
            ev.add(hand.getType(), hand.getAmount(), worth);
            ev.remaining = null;
        }
        return ev;
    }

    /** Gives items back to the player; whatever does not fit is dropped at his feet. Returns the item count. */
    public int giveBack(Player player, Collection<ItemStack> items) {
        int count = 0;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            count += item.getAmount();
            Map<Integer, ItemStack> left = player.getInventory().addItem(item);
            Location at = player.getLocation();
            for (ItemStack rest : left.values()) {
                at.getWorld().dropItemNaturally(at, rest);
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ messages / effects

    /** Chat summary of a sale (lang: sell_message, sell_item_line, sell_more_line). */
    public void sendSummary(CommandSender to, Evaluation ev, double multiplier) {
        Messages msg = plugin.messages();
        String itemLine = msg.raw("sell_item_line");
        int max = Math.max(1, plugin.getConfig().getInt("chat.max_items_shown", 10));

        List<String> itemLines = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<Material, long[]> e : ev.lines.entrySet()) {
            if (shown >= max) break;
            itemLines.add(Messages.apply(itemLine,
                    "item", Messages.pretty(e.getKey()),
                    "amount", String.valueOf(e.getValue()[0]),
                    "worth", msg.money(e.getValue()[1])));
            shown++;
        }
        int more = ev.lines.size() - shown;
        if (more > 0) {
            itemLines.add(Messages.apply(msg.raw("sell_more_line"), "more", String.valueOf(more)));
        }

        String[] ph = {
                "total", msg.money(ev.total),
                "count", String.valueOf(ev.itemCount),
                "types", String.valueOf(ev.lines.size()),
                "multiplier", msg.money(multiplier)
        };
        for (String line : msg.rawList("sell_message")) {
            if (line.trim().equals("<items>")) {
                for (String il : itemLines) to.sendMessage(Messages.color(il));
            } else {
                to.sendMessage(Messages.color(Messages.apply(line, ph)));
            }
        }
    }

    public void playEffects(Player player, Evaluation ev) {
        FileConfiguration c = plugin.getConfig();
        Messages msg = plugin.messages();
        String total = msg.money(ev.total);
        String count = String.valueOf(ev.itemCount);

        if (c.getBoolean("effects.sound.enable", true) && soundKey != null) {
            player.playSound(player.getLocation(), soundKey,
                    (float) c.getDouble("effects.sound.volume", 1.0),
                    (float) c.getDouble("effects.sound.pitch", 1.2));
        }
        if (c.getBoolean("effects.actionbar.enable", true)) {
            player.sendActionBar(msg.text("effects.actionbar", "total", total, "count", count));
        }
        if (c.getBoolean("effects.title.enable", false)) {
            Component title = msg.text("effects.title", "total", total, "count", count);
            Component sub = msg.text("effects.subtitle", "total", total, "count", count);
            player.showTitle(Title.title(title, sub));
        }
    }
}
