package com.paismp.sell;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SellPlugin extends JavaPlugin {

    private static final String[] DEFAULT_RESOURCES = {
            "gui_layout/sell.yml", "gui_layout/worth.yml", "lang/en.yml",
            "price/Block.yml", "price/Item.yml", "price/Tool.yml"
    };

    private Economy economy;
    private Messages messages;
    private PriceManager prices;
    private SellService service;
    private LegacyLoreCleaner legacyCleaner;
    private GuiLayout sellLayout;
    private GuiLayout worthLayout;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String res : DEFAULT_RESOURCES) {
            if (!new File(getDataFolder(), res).exists()) {
                saveResource(res, false);
            }
        }

        messages = new Messages(this);
        prices = new PriceManager(this);
        service = new SellService(this);
        legacyCleaner = new LegacyLoreCleaner(this);

        reloadAll();

        SellCommand handler = new SellCommand(this);
        for (String name : new String[]{"sell", "worth", "editsell"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(handler);
                cmd.setTabCompleter(handler);
            }
        }
        Bukkit.getPluginManager().registerEvents(new GuiListener(), this);
        Bukkit.getPluginManager().registerEvents(legacyCleaner, this);
        legacyCleaner.cleanOnlinePlayers();
    }

    @Override
    public void onDisable() {
        // Our listeners are already disabled here, so give back the items of open sell menus by hand.
        for (Player p : Bukkit.getOnlinePlayers()) {
            Object holder = p.getOpenInventory().getTopInventory().getHolder(false);
            if (holder instanceof SellGui gui) {
                gui.shutdown();
                p.closeInventory();
            } else if (holder instanceof WorthGui) {
                p.closeInventory();
            }
        }
    }

    /** Reloads config, lang, gui_layout and prices. */
    public void reloadAll() {
        reloadConfig();
        messages.load();
        prices.load();
        service.reload();
        sellLayout = GuiLayout.load(this, "sell");
        worthLayout = GuiLayout.load(this, "worth");
    }

    /** Looks up Vault's economy lazily so load order does not matter. */
    public Economy getEconomy() {
        if (economy == null && getConfig().getBoolean("economy.use_vault", true)
                && Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
            }
        }
        return economy;
    }

    public Messages messages() { return messages; }
    public PriceManager prices() { return prices; }
    public SellService service() { return service; }
    public GuiLayout sellLayout() { return sellLayout; }
    public GuiLayout worthLayout() { return worthLayout; }
}
