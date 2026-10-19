package com.paismp.sell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Every text of the plugin comes from lang/&lt;language&gt;.yml (missing keys fall back to the built-in en.yml). */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final SellPlugin plugin;
    private YamlConfiguration lang = new YamlConfiguration();
    private YamlConfiguration fallback = new YamlConfiguration();
    private DecimalFormat moneyFormat = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));

    public Messages(SellPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        String language = plugin.getConfig().getString("language", "en");
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!langFile.exists()) {
            langFile = new File(plugin.getDataFolder(), "lang/en.yml");
        }
        lang = YamlConfiguration.loadConfiguration(langFile);

        fallback = new YamlConfiguration();
        try (InputStream in = plugin.getResource("lang/en.yml")) {
            if (in != null) {
                fallback = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }

        String pattern = plugin.getConfig().getString("number_format", "#,##0.##");
        try {
            moneyFormat = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
        } catch (IllegalArgumentException e) {
            moneyFormat = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        }
    }

    public String money(double value) {
        return moneyFormat.format(value);
    }

    public static Component color(String text) {
        return LEGACY.deserialize(text);
    }

    /** Same as {@link #color(String)} but never italic (item names / lore). */
    public static Component plain(String text) {
        return LEGACY.deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    /** Replaces "&lt;key&gt;" with value for each pair: apply(text, "key", "value", ...). */
    public static String apply(String text, String... kv) {
        String out = text;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("<" + kv[i] + ">", kv[i + 1]);
        }
        return out;
    }

    // ------------------------------------------------------------------ raw access

    public boolean has(String key) {
        return lang.contains(key) || fallback.contains(key);
    }

    public String raw(String key) {
        if (lang.contains(key)) {
            return lang.getString(key, "");
        }
        return fallback.getString(key, "&cMissing message: " + key);
    }

    public boolean isList(String key) {
        if (lang.contains(key)) {
            return lang.isList(key);
        }
        return fallback.isList(key);
    }

    public List<String> rawList(String key) {
        if (lang.contains(key)) {
            return lang.isList(key) ? lang.getStringList(key) : List.of(lang.getString(key, ""));
        }
        if (fallback.isList(key)) {
            return fallback.getStringList(key);
        }
        return List.of(raw(key));
    }

    // ------------------------------------------------------------------ building

    /** One text with placeholders applied, as a non-italic component (GUI item names). */
    public Component text(String key, String... kv) {
        return plain(apply(raw(key), kv));
    }

    /** A list text with placeholders applied, as non-italic components (GUI item lore). */
    public List<Component> lines(String key, String... kv) {
        List<Component> out = new ArrayList<>();
        for (String line : rawList(key)) {
            out.add(plain(apply(line, kv)));
        }
        return out;
    }

    /** Plain string with placeholders applied (for building other texts). */
    public String string(String key, String... kv) {
        return apply(raw(key), kv);
    }

    // ------------------------------------------------------------------ sending

    /** Single texts get the prefix, list texts are sent line by line without it. */
    public void send(CommandSender to, String key, String... kv) {
        if (isList(key)) {
            for (String line : rawList(key)) {
                to.sendMessage(color(apply(line, kv)));
            }
            return;
        }
        to.sendMessage(color(raw("prefix") + apply(raw(key), kv)));
    }

    public static String pretty(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
