package com.paismp.sell;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SellCommand implements TabExecutor {

    private final SellPlugin plugin;

    public SellCommand(SellPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "sell" -> handleSell(sender, args);
            case "worth" -> handleWorth(sender, args);
            case "editsell" -> handleEdit(sender, args);
            default -> { return false; }
        }
        return true;
    }

    private static boolean isPotionKind(Material m) {
        return m == Material.POTION || m == Material.SPLASH_POTION
                || m == Material.LINGERING_POTION || m == Material.TIPPED_ARROW;
    }

    // ------------------------------------------------------------------ /sell

    private void handleSell(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("sell.reload")) {
                msg.send(sender, "no_permission");
                return;
            }
            plugin.reloadAll();
            msg.send(sender, "reload_done",
                    "items", String.valueOf(plugin.prices().itemCount()),
                    "enchants", String.valueOf(plugin.prices().enchantCount()),
                    "invalid", String.valueOf(plugin.prices().invalidCount()));
            return;
        }

        if (!(sender instanceof Player player)) {
            msg.send(sender, "players_only");
            return;
        }
        if (!player.hasPermission("sell.sell")) {
            msg.send(player, "no_permission");
            return;
        }
        new SellGui(plugin, player, plugin.sellLayout()).open();
    }

    // ------------------------------------------------------------------ /worth

    private void handleWorth(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players_only");
            return;
        }
        if (!player.hasPermission("sell.worth")) {
            msg.send(player, "no_permission");
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("hand")) {
            worthHand(player);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("search")) {
            // chat version of the search button (works for every client, e.g. Bedrock)
            String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            new WorthGui(plugin, player, plugin.worthLayout(), 0, 0, query).open();
            return;
        }
        new WorthGui(plugin, player, plugin.worthLayout()).open();
    }

    /** /worth hand: chat message with the worth of the main hand item. */
    private void worthHand(Player player) {
        Messages msg = plugin.messages();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            msg.send(player, "hold_item");
            return;
        }

        double multiplier = plugin.service().getMultiplier(player);
        SellService.Evaluation ev = plugin.service().evaluate(hand, multiplier);
        String name = Messages.pretty(hand.getType());

        if (ev.total <= 0) {
            msg.send(player, "worth_no_worth", "item", name);
            return;
        }

        if (ev.viaShulker) {
            msg.send(player, "worth_shulker_message",
                    "item", name,
                    "contents_count", String.valueOf(ev.itemCount),
                    "contents_worth", msg.money(ev.total),
                    "multiplier", msg.money(multiplier));
        } else {
            double unit = plugin.prices().unitWorth(hand) * multiplier;
            msg.send(player, "worth_message",
                    "item", name,
                    "amount", String.valueOf(hand.getAmount()),
                    "unit_worth", msg.money(unit),
                    "stack_worth", msg.money(ev.total),
                    "multiplier", msg.money(multiplier));
        }
    }

    // ------------------------------------------------------------------ /editsell

    private void handleEdit(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (!sender.hasPermission("sell.admin")) {
            msg.send(sender, "no_permission");
            return;
        }
        String types = String.join(", ", plugin.prices().types());
        if (args.length < 3) {
            msg.send(sender, "editsell_usage", "types", types);
            return;
        }

        String type = args[0];
        String action = args[1].toLowerCase(Locale.ROOT);
        String itemArg = args[2];
        if (plugin.prices().types().stream().noneMatch(t -> t.equalsIgnoreCase(type))) {
            msg.send(sender, "editsell_unknown_type", "type", type, "types", types);
            return;
        }
        if (!action.equals("add") && !action.equals("modify") && !action.equals("remove")) {
            msg.send(sender, "editsell_usage", "types", types);
            return;
        }

        // resolve item / potion / enchantment
        Material material = null;
        PotionType potion = null;
        Enchantment enchant = null;

        if (itemArg.equalsIgnoreCase("HAND") && sender instanceof Player p
                && !p.getInventory().getItemInMainHand().getType().isAir()) {
            ItemStack held = p.getInventory().getItemInMainHand();
            material = held.getType();
            if (isPotionKind(material) && held.getItemMeta() instanceof PotionMeta pm && pm.getBasePotionType() != null) {
                potion = pm.getBasePotionType();
            }
        } else {
            int colon = itemArg.lastIndexOf(':');
            if (colon > 0) {
                Material left = Material.matchMaterial(itemArg.substring(0, colon));
                if (left != null && isPotionKind(left)) {
                    potion = PriceManager.potionType(itemArg.substring(colon + 1));
                    if (potion == null) {
                        msg.send(sender, "editsell_unknown_potion", "potion", itemArg.substring(colon + 1));
                        return;
                    }
                    material = left;
                }
            }
            if (material == null) {
                material = Material.matchMaterial(itemArg);
                if (material != null && (material.isAir() || !material.isItem())) material = null;
                if (material == null) enchant = PriceManager.enchantment(itemArg);
            }
        }
        if (material == null && enchant == null) {
            msg.send(sender, "editsell_unknown_item", "item", itemArg);
            return;
        }

        boolean mustExist = action.equals("modify");
        PriceManager prices = plugin.prices();

        if (material != null && potion != null) {
            String label = material.name() + ":" + potion.getKey().getKey().toUpperCase(Locale.ROOT);
            if (action.equals("remove")) {
                report(sender, prices.removePotion(type, material, potion), "editsell_potion_removed", "editsell_nothing_to_remove",
                        "item", label, "type", type);
                return;
            }
            if (args.length < 4) {
                msg.send(sender, "editsell_usage", "types", types);
                return;
            }
            Double price = parsePrice(sender, args[3]);
            if (price == null) return;
            report(sender, prices.setPotion(type, material, potion, price, mustExist),
                    action.equals("add") ? "editsell_potion_added" : "editsell_potion_modified", "editsell_not_found",
                    "item", label, "type", type, "price", msg.money(price));
            return;
        }

        if (material != null) {
            String itemName = material.name();
            if (action.equals("remove")) {
                report(sender, prices.removeItem(type, material), "editsell_item_removed", "editsell_nothing_to_remove",
                        "item", itemName, "type", type);
                return;
            }
            if (args.length < 4) {
                msg.send(sender, "editsell_usage", "types", types);
                return;
            }
            Double price = parsePrice(sender, args[3]);
            if (price == null) return;
            report(sender, prices.setItem(type, material, price, mustExist),
                    action.equals("add") ? "editsell_item_added" : "editsell_item_modified", "editsell_not_found",
                    "item", itemName, "type", type, "price", msg.money(price));
            return;
        }

        String enchName = enchant.getKey().getKey().toUpperCase(Locale.ROOT);
        if (action.equals("remove")) {
            Integer level = null;
            if (args.length >= 4) {
                level = parseLevel(sender, args[3]);
                if (level == null) return;
            }
            report(sender, prices.removeEnchant(type, enchant, level), "editsell_ench_removed", "editsell_nothing_to_remove",
                    "item", enchName, "type", type, "level", level == null ? "all levels" : String.valueOf(level));
            return;
        }
        if (args.length < 5) {
            msg.send(sender, "editsell_usage", "types", types);
            return;
        }
        Integer level = parseLevel(sender, args[3]);
        Double price = parsePrice(sender, args[4]);
        if (level == null || price == null) return;
        report(sender, prices.setEnchant(type, enchant, level, price, mustExist),
                action.equals("add") ? "editsell_ench_added" : "editsell_ench_modified", "editsell_not_found",
                "item", enchName, "type", type, "level", String.valueOf(level), "price", msg.money(price));
    }

    private void report(CommandSender sender, PriceManager.Result result, String okKey, String missKey, String... kv) {
        Messages msg = plugin.messages();
        switch (result) {
            case OK -> msg.send(sender, okKey, kv);
            case NOT_FOUND -> msg.send(sender, missKey, kv);
            case UNKNOWN_TYPE -> msg.send(sender, "editsell_unknown_type", "types", String.join(", ", plugin.prices().types()), "type", "?");
            default -> msg.send(sender, "editsell_save_failed");
        }
    }

    private Double parsePrice(CommandSender sender, String s) {
        try {
            double v = Double.parseDouble(s);
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            plugin.messages().send(sender, "editsell_bad_number", "value", s);
            return null;
        }
    }

    private Integer parseLevel(CommandSender sender, String s) {
        try {
            int v = Integer.parseInt(s);
            if (v < 1) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            plugin.messages().send(sender, "editsell_bad_number", "value", s);
            return null;
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        String cur = args[args.length - 1].toLowerCase(Locale.ROOT);

        if (name.equals("sell")) {
            if (args.length == 1 && sender.hasPermission("sell.reload") && "reload".startsWith(cur)) {
                out.add("reload");
            }
            return out;
        }
        if (name.equals("worth")) {
            if (args.length == 1) {
                if ("hand".startsWith(cur)) out.add("hand");
                if ("search".startsWith(cur)) out.add("search");
            }
            return out;
        }
        if (!sender.hasPermission("sell.admin")) {
            return out;
        }

        if (args.length == 1) {
            for (String t : plugin.prices().types()) if (t.toLowerCase(Locale.ROOT).startsWith(cur)) out.add(t);
        } else if (args.length == 2) {
            for (String a : List.of("add", "modify", "remove")) if (a.startsWith(cur)) out.add(a);
        } else if (args.length == 3) {
            int colon = cur.lastIndexOf(':');
            if (colon > 0) {
                String left = cur.substring(0, colon).toUpperCase(Locale.ROOT);
                Material lm = Material.matchMaterial(left);
                if (lm != null && isPotionKind(lm)) {
                    for (PotionType t : Registry.POTION) {
                        String full = lm.name() + ":" + t.getKey().getKey().toUpperCase(Locale.ROOT);
                        if (full.toLowerCase(Locale.ROOT).startsWith(cur)) out.add(full);
                    }
                }
                return out;
            }
            if ("hand".startsWith(cur)) out.add("HAND");
            for (Enchantment e : Registry.ENCHANTMENT) {
                String n = e.getKey().getKey().toUpperCase(Locale.ROOT);
                if (n.toLowerCase(Locale.ROOT).startsWith(cur)) out.add(n);
            }
            for (Material m : Material.values()) {
                if (out.size() >= 60) break;
                if (m.isLegacy() || m.isAir() || !m.isItem()) continue;
                if (m.name().toLowerCase(Locale.ROOT).startsWith(cur)) {
                    out.add(m.name());
                    if (isPotionKind(m) && cur.length() >= 4) out.add(m.name() + ":");
                }
            }
        } else if (args.length == 4) {
            Enchantment e = PriceManager.enchantment(args[2]);
            if (e != null) {
                for (int i = 1; i <= Math.max(1, e.getMaxLevel()); i++) out.add(String.valueOf(i));
            }
        }
        return out;
    }
}
