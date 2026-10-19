package com.paismp.sell;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * A GUI layout read from gui_layout/&lt;name&gt;.yml. It only knows positions, materials and actions;
 * all texts are looked up by key in lang/en.yml through {@link Messages}.
 */
public final class GuiLayout {

    public enum Action { NONE, SELL, CLOSE, PREVIOUS_PAGE, NEXT_PAGE, CATEGORY, SORT, SEARCH }

    public static final class Button {
        public final String id;
        public final Material material;
        public final int[] slots;
        public final Action action;
        public final String nameKey;
        public final String loreKey;
        public final boolean glow;

        Button(String id, Material material, int[] slots, Action action, String nameKey, String loreKey, boolean glow) {
            this.id = id;
            this.material = material;
            this.slots = slots;
            this.action = action;
            this.nameKey = nameKey;
            this.loreKey = loreKey;
            this.glow = glow;
        }
    }

    public final int rows;
    public final String titleKey;
    public final boolean sellOnClose;
    public final int[] contentSlots;
    public final List<Button> buttons;
    private final Map<Integer, Button> bySlot = new LinkedHashMap<>();
    private final boolean[] content;

    private GuiLayout(int rows, String titleKey, boolean sellOnClose, int[] contentSlots, List<Button> buttons) {
        this.rows = rows;
        this.titleKey = titleKey;
        this.sellOnClose = sellOnClose;
        this.contentSlots = contentSlots;
        this.buttons = buttons;
        this.content = new boolean[rows * 9];
        for (int s : contentSlots) {
            content[s] = true;
        }
        for (Button b : buttons) {
            for (int s : b.slots) {
                bySlot.put(s, b);
            }
        }
    }

    public int size() {
        return rows * 9;
    }

    public boolean isContentSlot(int slot) {
        return slot >= 0 && slot < content.length && content[slot];
    }

    /** The button on this slot, or null. */
    public Button buttonAt(int slot) {
        return bySlot.get(slot);
    }

    // ------------------------------------------------------------------ loading

    /** Loads plugins/Sell/gui_layout/&lt;name&gt;.yml; keys that are missing fall back to the built-in file. */
    public static GuiLayout load(SellPlugin plugin, String name) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), "gui_layout/" + name + ".yml");
        YamlConfiguration user = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration builtIn = new YamlConfiguration();
        try (InputStream in = plugin.getResource("gui_layout/" + name + ".yml")) {
            if (in != null) {
                builtIn = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        // keys missing in the server's file are taken from the built-in file
        YamlConfiguration cfg = user;
        int rows = Math.max(1, Math.min(6, user.getInt("rows", builtIn.getInt("rows", 6))));
        int size = rows * 9;
        String title = user.getString("title", builtIn.getString("title", "gui." + name + ".title"));
        boolean sellOnClose = user.getBoolean("sell_on_close", builtIn.getBoolean("sell_on_close", true));
        Object contentRaw = user.contains("content_slots") ? user.get("content_slots") : builtIn.get("content_slots");
        int[] contentSlots = parseSlots(contentRaw, size, name + ".content_slots", log);

        List<Button> buttons = new ArrayList<>();
        ConfigurationSection section = cfg.isConfigurationSection("buttons")
                ? cfg.getConfigurationSection("buttons")
                : builtIn.getConfigurationSection("buttons");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection b = section.getConfigurationSection(id);
                if (b == null) continue;
                Material material = Material.matchMaterial(b.getString("material", "STONE"));
                if (material == null || material.isAir() || !material.isItem()) {
                    log.warning("gui_layout/" + name + ".yml: button '" + id + "' has an unknown material, using STONE.");
                    material = Material.STONE;
                }
                Action action;
                try {
                    action = Action.valueOf(b.getString("action", "NONE").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    log.warning("gui_layout/" + name + ".yml: button '" + id + "' has an unknown action, using NONE.");
                    action = Action.NONE;
                }
                int[] slots = parseSlots(b.get("slots"), size, name + "." + id + ".slots", log);
                buttons.add(new Button(id, material, slots, action,
                        b.getString("name", ""), b.getString("lore", ""), b.getBoolean("glow", false)));
            }
        }
        return new GuiLayout(rows, title, sellOnClose, contentSlots, buttons);
    }

    /** "0-44,49" or a yaml list of numbers / ranges. Invalid or out of range parts are skipped. */
    static int[] parseSlots(Object raw, int size, String where, Logger log) {
        List<String> tokens = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) tokens.add(String.valueOf(o));
        } else if (raw != null) {
            for (String t : String.valueOf(raw).split(",")) tokens.add(t);
        }
        TreeSet<Integer> out = new TreeSet<>();
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                int dash = t.indexOf('-', 1);
                int from;
                int to;
                if (dash > 0) {
                    from = Integer.parseInt(t.substring(0, dash).trim());
                    to = Integer.parseInt(t.substring(dash + 1).trim());
                } else {
                    from = Integer.parseInt(t);
                    to = from;
                }
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                    if (i >= 0 && i < size) {
                        out.add(i);
                    } else {
                        log.warning("gui_layout " + where + ": slot " + i + " is outside the menu (0-" + (size - 1) + "), skipped.");
                    }
                }
            } catch (NumberFormatException e) {
                log.warning("gui_layout " + where + ": '" + t + "' is not a slot number, skipped.");
            }
        }
        int[] arr = new int[out.size()];
        int i = 0;
        for (int s : out) arr[i++] = s;
        return arr;
    }

    // ------------------------------------------------------------------ item building

    /** Builds the item of a button; kv are the placeholders for its name and lore. */
    public ItemStack build(Button button, Messages msg, String... kv) {
        ItemStack stack = new ItemStack(button.material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        if (!button.nameKey.isEmpty()) {
            meta.displayName(msg.text(button.nameKey, kv));
        }
        if (!button.loreKey.isEmpty()) {
            List<Component> lore = msg.lines(button.loreKey, kv);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (button.glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
