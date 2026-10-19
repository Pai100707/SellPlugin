package com.paismp.sell;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Loads every *.yml in plugins/Sell/price/ and calculates item worth. */
public final class PriceManager {

    public enum Result { OK, UNKNOWN_TYPE, NOT_FOUND, FAILED }

    /** One line of the /worth catalog. type = name of the price file it comes from. */
    public record CatalogEntry(String type, String sortName, double worth, ItemStack icon) {
    }

    private final SellPlugin plugin;
    private final Map<Material, Double> base = new EnumMap<>(Material.class);
    private final Map<Material, String> typeOf = new EnumMap<>(Material.class);
    private final Map<String, TreeMap<Integer, Double>> enchants = new HashMap<>();
    /** Material (POTION, SPLASH_POTION, ...) -> potion type key (lower case, e.g. "strong_swiftness") -> price */
    private final Map<Material, Map<String, Double>> potions = new EnumMap<>(Material.class);
    private final Map<String, File> files = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private String potionFileType = "Item";
    private String enchantFileType = "Tool";
    private List<CatalogEntry> catalog;
    private int invalid;

    public PriceManager(SellPlugin plugin) {
        this.plugin = plugin;
    }

    private File dir() {
        return new File(plugin.getDataFolder(), "price");
    }

    // ------------------------------------------------------------------ loading

    public synchronized void load() {
        base.clear();
        typeOf.clear();
        enchants.clear();
        potions.clear();
        files.clear();
        catalog = null;
        invalid = 0;
        List<String> bad = new ArrayList<>();

        File[] list = dir().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (list == null) {
            return;
        }
        Arrays.sort(list);
        for (File f : list) {
            String type = f.getName().substring(0, f.getName().length() - 4);
            files.put(type, f);
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

            ConfigurationSection items = y.getConfigurationSection("items");
            if (items != null) {
                for (String key : items.getKeys(false)) {
                    Material m = Material.matchMaterial(key);
                    if (m == null || m.isAir() || !m.isItem()) {
                        invalid++;
                        bad.add(key);
                        continue;
                    }
                    base.put(m, items.getDouble(key));
                    typeOf.put(m, type);
                }
            }

            ConfigurationSection es = y.getConfigurationSection("enchantments");
            if (es != null) {
                enchantFileType = type;
                for (String key : es.getKeys(false)) {
                    Enchantment e = enchantment(key);
                    if (e == null) {
                        invalid++;
                        bad.add(key);
                        continue;
                    }
                    ConfigurationSection lv = es.getConfigurationSection(key);
                    if (lv == null) continue;
                    TreeMap<Integer, Double> map = enchants.computeIfAbsent(e.getKey().getKey(), k -> new TreeMap<>());
                    for (String level : lv.getKeys(false)) {
                        try {
                            map.put(Integer.parseInt(level), lv.getDouble(level));
                        } catch (NumberFormatException ex) {
                            invalid++;
                            bad.add(key + "." + level);
                        }
                    }
                }
            }

            ConfigurationSection ps = y.getConfigurationSection("potions");
            if (ps != null) {
                potionFileType = type;
                for (String matKey : ps.getKeys(false)) {
                    Material m = Material.matchMaterial(matKey);
                    ConfigurationSection inner = ps.getConfigurationSection(matKey);
                    if (m == null || inner == null) {
                        invalid++;
                        bad.add(matKey);
                        continue;
                    }
                    Map<String, Double> map = potions.computeIfAbsent(m, k -> new HashMap<>());
                    for (String potionKey : inner.getKeys(false)) {
                        String lower = potionKey.toLowerCase(Locale.ROOT);
                        if (potionType(lower) == null) {
                            invalid++;
                            bad.add(matKey + "." + potionKey);
                            continue;
                        }
                        map.put(lower, inner.getDouble(potionKey));
                    }
                }
            }
        }
        if (!bad.isEmpty()) {
            plugin.getLogger().warning(invalid + " unknown name(s) in price files were skipped (not in this Minecraft version?): "
                    + String.join(", ", bad.subList(0, Math.min(15, bad.size()))) + (bad.size() > 15 ? " ..." : ""));
        }
        plugin.getLogger().info("Loaded " + base.size() + " item prices, " + enchants.size() + " enchantments and "
                + potions.values().stream().mapToInt(Map::size).sum() + " potion prices from " + files.size() + " price file(s).");
    }

    public int itemCount() { return base.size(); }
    public int enchantCount() { return enchants.size(); }
    public int invalidCount() { return invalid; }
    public List<String> types() { return new ArrayList<>(files.keySet()); }

    public static Enchantment enchantment(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }

    /** Potion type from a name like "STRONG_SWIFTNESS" or "minecraft:strong_swiftness"; null if unknown. */
    public static PotionType potionType(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.POTION.get(key);
    }

    // ------------------------------------------------------------------ calculation

    public double basePrice(Material m) {
        return base.getOrDefault(m, 0.0);
    }

    public double enchantPrice(Enchantment e, int level) {
        TreeMap<Integer, Double> map = enchants.get(e.getKey().getKey());
        if (map == null || map.isEmpty()) return 0;
        Double exact = map.get(level);
        if (exact != null) return exact;
        Map.Entry<Integer, Double> lower = map.floorEntry(level);
        if (lower == null || lower.getKey() <= 0) return 0;
        return lower.getValue() * level / lower.getKey();
    }

    /** Price of the potion type of this item (potions section), or null if there is none. */
    private Double potionPrice(ItemStack item) {
        Map<String, Double> map = potions.get(item.getType());
        if (map == null || map.isEmpty()) return null;
        if (!(item.getItemMeta() instanceof PotionMeta pm)) return null;
        PotionType type = pm.getBasePotionType();
        if (type == null) return null;
        return map.get(type.getKey().getKey());
    }

    /** Worth of ONE item (before amount / multiplier / rounding). 0 = not sellable. */
    public double unitWorth(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        double basePrice = basePrice(item.getType());
        Double potion = potionPrice(item);
        if (potion != null) basePrice = potion;
        boolean book = item.getType() == Material.ENCHANTED_BOOK;   // books are worth only their enchantments
        if (basePrice <= 0 && !book) return 0;

        ItemMeta meta = item.getItemMeta();
        double durMult = 1.0;
        if (plugin.getConfig().getBoolean("formula.durability.enable", true)
                && "REMAINING_RATIO".equalsIgnoreCase(plugin.getConfig().getString("formula.durability.multiplier_mode", "REMAINING_RATIO"))
                && meta instanceof Damageable dmg) {
            int max = dmg.hasMaxDamage() ? dmg.getMaxDamage() : item.getType().getMaxDurability();
            if (max > 0) {
                durMult = Math.max(0.0, Math.min(1.0, (max - dmg.getDamage()) / (double) max));
            }
        }

        double enchantSum = 0;
        if (plugin.getConfig().getBoolean("formula.enchantments.enable", true)) {
            for (Map.Entry<Enchantment, Integer> en : item.getEnchantments().entrySet()) {
                enchantSum += enchantPrice(en.getKey(), en.getValue());
            }
            if (meta instanceof EnchantmentStorageMeta storage) {
                for (Map.Entry<Enchantment, Integer> en : storage.getStoredEnchants().entrySet()) {
                    enchantSum += enchantPrice(en.getKey(), en.getValue());
                }
            }
            enchantSum *= plugin.getConfig().getDouble("formula.enchantments.default_multiplier", 1.0);
        }
        return Math.max(0, basePrice * durMult + enchantSum);
    }

    public long round(double value) {
        String type = plugin.getConfig().getString("formula.round_type", "FLOOR").toUpperCase(Locale.ROOT);
        return switch (type) {
            case "ROUND" -> Math.round(value);
            case "CEIL" -> (long) Math.ceil(value - 1e-9);
            default -> (long) Math.floor(value + 1e-9);
        };
    }

    // ------------------------------------------------------------------ catalog (/worth menu)

    /** Every priced item, potion and enchanted book. Built once and rebuilt after prices change. */
    public synchronized List<CatalogEntry> catalog() {
        if (catalog == null) {
            catalog = buildCatalog();
        }
        return catalog;
    }

    private List<CatalogEntry> buildCatalog() {
        List<CatalogEntry> out = new ArrayList<>();

        for (Map.Entry<Material, Double> e : base.entrySet()) {
            Material m = e.getKey();
            if (e.getValue() <= 0) continue;
            Map<String, Double> variants = potions.get(m);
            if (variants != null && !variants.isEmpty()) continue;   // shown per potion type below
            out.add(new CatalogEntry(typeOf.getOrDefault(m, "Item"), Messages.pretty(m).toLowerCase(Locale.ROOT),
                    e.getValue(), new ItemStack(m)));
        }

        for (Map.Entry<Material, Map<String, Double>> pe : potions.entrySet()) {
            Material m = pe.getKey();
            for (Map.Entry<String, Double> e : pe.getValue().entrySet()) {
                PotionType type = potionType(e.getKey());
                if (type == null || e.getValue() <= 0) continue;
                ItemStack icon = new ItemStack(m);
                if (icon.getItemMeta() instanceof PotionMeta pm) {
                    pm.setBasePotionType(type);
                    icon.setItemMeta(pm);
                }
                out.add(new CatalogEntry(potionFileType,
                        (Messages.pretty(m) + " " + e.getKey()).toLowerCase(Locale.ROOT), e.getValue(), icon));
            }
        }

        {
            for (Map.Entry<String, TreeMap<Integer, Double>> en : enchants.entrySet()) {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(en.getKey()));
                if (ench == null) continue;
                for (Integer level : en.getValue().keySet()) {
                    ItemStack icon = new ItemStack(Material.ENCHANTED_BOOK);
                    if (icon.getItemMeta() instanceof EnchantmentStorageMeta sm) {
                        sm.addStoredEnchant(ench, level, true);
                        icon.setItemMeta(sm);
                    }
                    out.add(new CatalogEntry(enchantFileType,
                            String.format(Locale.ROOT, "enchanted book %s %03d", en.getKey(), level), unitWorth(icon), icon));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ editing (/editsell)

    private YamlConfiguration read(File f) {
        return YamlConfiguration.loadConfiguration(f);
    }

    private boolean save(YamlConfiguration y, File f) {
        try {
            y.save(f);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + f.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private String canonicalType(File target) {
        for (Map.Entry<String, File> en : files.entrySet()) {
            if (en.getValue().equals(target)) return en.getKey();
        }
        return "Item";
    }

    /** Writes value at path in the target file and removes the same path from every other price file. */
    private boolean writeUnique(File target, String path, Object value) {
        for (File f : files.values()) {
            YamlConfiguration y = read(f);
            if (f.equals(target)) {
                y.set(path, value);
                if (!save(y, f)) return false;
            } else if (y.contains(path)) {
                y.set(path, null);
                save(y, f);
            }
        }
        return true;
    }

    public synchronized Result setItem(String type, Material m, double price, boolean mustExist) {
        File target = files.get(type);
        if (target == null) return Result.UNKNOWN_TYPE;
        if (mustExist && !base.containsKey(m)) return Result.NOT_FOUND;
        if (!writeUnique(target, "items." + m.name(), price)) return Result.FAILED;
        base.put(m, price);
        typeOf.put(m, canonicalType(target));
        catalog = null;
        return Result.OK;
    }

    public synchronized Result removeItem(String type, Material m) {
        File target = files.get(type);
        if (target == null) return Result.UNKNOWN_TYPE;
        YamlConfiguration y = read(target);
        String path = "items." + m.name();
        if (!y.contains(path)) return Result.NOT_FOUND;
        y.set(path, null);
        if (!save(y, target)) return Result.FAILED;
        base.remove(m);
        typeOf.remove(m);
        catalog = null;
        return Result.OK;
    }

    public synchronized Result setEnchant(String type, Enchantment e, int level, double price, boolean mustExist) {
        File target = files.get(type);
        if (target == null) return Result.UNKNOWN_TYPE;
        String name = e.getKey().getKey();
        TreeMap<Integer, Double> existing = enchants.get(name);
        if (mustExist && (existing == null || !existing.containsKey(level))) return Result.NOT_FOUND;
        if (!writeUnique(target, "enchantments." + name.toUpperCase(Locale.ROOT) + "." + level, price)) return Result.FAILED;
        enchants.computeIfAbsent(name, k -> new TreeMap<>()).put(level, price);
        enchantFileType = canonicalType(target);
        catalog = null;
        return Result.OK;
    }

    /** level == null removes every level of the enchantment from that file. */
    public synchronized Result removeEnchant(String type, Enchantment e, Integer level) {
        File target = files.get(type);
        if (target == null) return Result.UNKNOWN_TYPE;
        String name = e.getKey().getKey();
        String section = "enchantments." + name.toUpperCase(Locale.ROOT);
        String path = level == null ? section : section + "." + level;
        YamlConfiguration y = read(target);
        if (!y.contains(path)) return Result.NOT_FOUND;
        y.set(path, null);
        if (!save(y, target)) return Result.FAILED;
        TreeMap<Integer, Double> map = enchants.get(name);
        if (map != null) {
            if (level == null) map.clear(); else map.remove(level);
        }
        catalog = null;
        return Result.OK;
    }

    public synchronized Result setPotion(String type, Material m, PotionType potion, double price, boolean mustExist) {
        File target = files.get(type);
        if (target == null) return Result.UNKNOWN_TYPE;
        String key = potion.getKey().getKey();
        Map<String, Double> existing = potions.get(m);
        if (mustExist && (existing == null || !existing.containsKey(key))) return Result.NOT_FOUND;
        if (!writeUnique(target, "potions." + m.name() + "." + key.toUpperCase(Locale.ROOT), price)) return Result.FAILED;
        potions.computeIfAbsent(m, k -> new HashMap<>()).put(key, price);
        potionFileType = canonicalType(target);
        catalog = null;
        return Result.OK;
    }

    public synchronized Result removePotion(String type, Material m, PotionType potion) {
        File target = files.get(type);
        if (target == null) return Result.UNKNOWN_TYPE;
        String key = potion.getKey().getKey();
        String path = "potions." + m.name() + "." + key.toUpperCase(Locale.ROOT);
        YamlConfiguration y = read(target);
        if (!y.contains(path)) return Result.NOT_FOUND;
        y.set(path, null);
        if (!save(y, target)) return Result.FAILED;
        Map<String, Double> map = potions.get(m);
        if (map != null) map.remove(key);
        catalog = null;
        return Result.OK;
    }
}
