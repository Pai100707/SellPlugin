package com.paismp.sell;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The /worth menu: a paged price list with category and sort buttons. Nothing in it can be taken. */
public final class WorthGui implements InventoryHolder {

    private static final String[] SORT_KEYS = {"name", "price_high", "price_low"};

    private final SellPlugin plugin;
    private final Player player;
    private final GuiLayout layout;
    private final Inventory inventory;
    private final List<String> categories = new ArrayList<>();   // index 0 = all
    private List<PriceManager.CatalogEntry> view = new ArrayList<>();
    private int category;
    private int sort;
    private int page;
    private String query;

    public WorthGui(SellPlugin plugin, Player player, GuiLayout layout) {
        this(plugin, player, layout, 0, 0, "");
    }

    public WorthGui(SellPlugin plugin, Player player, GuiLayout layout, int category, int sort, String query) {
        this.plugin = plugin;
        this.player = player;
        this.layout = layout;
        this.categories.add("");
        this.categories.addAll(plugin.prices().types());
        this.category = Math.floorMod(category, this.categories.size());
        this.sort = Math.floorMod(sort, SORT_KEYS.length);
        this.query = query == null ? "" : query.trim();
        this.inventory = Bukkit.createInventory(this, layout.size(), plugin.messages().text(layout.titleKey));
        rebuildView();
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open() {
        player.openInventory(inventory);
    }

    // ------------------------------------------------------------------ data

    private void rebuildView() {
        String wanted = categories.get(category);
        String[] words = query.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        List<PriceManager.CatalogEntry> list = new ArrayList<>();
        for (PriceManager.CatalogEntry e : plugin.prices().catalog()) {
            if (!wanted.isEmpty() && !e.type().equalsIgnoreCase(wanted)) continue;
            if (!query.isEmpty()) {
                String name = e.sortName().replace('_', ' ');
                boolean all = true;
                for (String w : words) {
                    if (!w.isEmpty() && !name.contains(w.replace('_', ' '))) {
                        all = false;
                        break;
                    }
                }
                if (!all) continue;
            }
            list.add(e);
        }
        Comparator<PriceManager.CatalogEntry> byName = Comparator.comparing(PriceManager.CatalogEntry::sortName);
        switch (SORT_KEYS[sort]) {
            case "price_high" -> list.sort(Comparator.comparingDouble(PriceManager.CatalogEntry::worth).reversed().thenComparing(byName));
            case "price_low" -> list.sort(Comparator.comparingDouble(PriceManager.CatalogEntry::worth).thenComparing(byName));
            default -> list.sort(byName);
        }
        view = list;
        page = 0;
    }

    private int pages() {
        int per = Math.max(1, layout.contentSlots.length);
        return Math.max(1, (view.size() + per - 1) / per);
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        Messages msg = plugin.messages();
        double multiplier = plugin.service().getMultiplier(player);
        int per = layout.contentSlots.length;

        for (int i = 0; i < per; i++) {
            int slot = layout.contentSlots[i];
            int index = page * per + i;
            inventory.setItem(slot, index < view.size() ? icon(view.get(index), multiplier) : null);
        }

        String categoryName = categories.get(category).isEmpty() ? msg.string("gui.worth.category_all") : categories.get(category);
        String sortName = msg.string("gui.worth.sort_modes." + SORT_KEYS[sort]);
        for (GuiLayout.Button button : layout.buttons) {
            ItemStack item = layout.build(button, msg,
                    "page", String.valueOf(page + 1),
                    "pages", String.valueOf(pages()),
                    "category", categoryName,
                    "sort", sortName,
                    "query", query.isEmpty() ? msg.string("gui.worth.search_none") : query);
            for (int slot : button.slots) {
                if (!layout.isContentSlot(slot)) {
                    inventory.setItem(slot, item.clone());
                }
            }
        }
    }

    private ItemStack icon(PriceManager.CatalogEntry entry, double multiplier) {
        Messages msg = plugin.messages();
        ItemStack item = entry.icon().clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        int stack = Math.max(1, item.getType().getMaxStackSize());
        double unit = entry.worth() * multiplier;
        List<Component> lore = new ArrayList<>();
        List<Component> existing = meta.lore();
        if (existing != null) lore.addAll(existing);
        lore.addAll(msg.lines("gui.worth.entry_lore",
                "worth", msg.money(unit),
                "stack", String.valueOf(stack),
                "stack_worth", msg.money(plugin.prices().round(unit * stack)),
                "multiplier", msg.money(multiplier)));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ events

    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inventory.getSize()) return;
        GuiLayout.Button button = layout.buttonAt(raw);
        if (button == null) return;

        boolean right = event.isRightClick();
        switch (button.action) {
            case PREVIOUS_PAGE -> {
                if (page > 0) {
                    page--;
                    render();
                }
            }
            case NEXT_PAGE -> {
                if (page + 1 < pages()) {
                    page++;
                    render();
                }
            }
            case CATEGORY -> {
                category = Math.floorMod(category + (right ? -1 : 1), categories.size());
                rebuildView();
                render();
            }
            case SORT -> {
                sort = Math.floorMod(sort + (right ? -1 : 1), SORT_KEYS.length);
                rebuildView();
                render();
            }
            case SEARCH -> {
                if (right) {
                    query = "";
                    rebuildView();
                    render();
                } else {
                    openSearch();
                }
            }
            case CLOSE -> player.getScheduler().run(plugin, task -> player.closeInventory(), null);
            default -> {
            }
        }
    }

    /** Shows the text box; afterwards the menu opens again with the result (same category and sort). */
    private void openSearch() {
        int cat = category;
        int srt = sort;
        String old = query;
        player.getScheduler().run(plugin, task -> {
            player.closeInventory();
            SearchDialog.open(plugin, player, old,
                    text -> new WorthGui(plugin, player, layout, cat, srt, text).open(),
                    () -> new WorthGui(plugin, player, layout, cat, srt, old).open());
        }, null);
    }

    public void onDrag(InventoryDragEvent event) {
        event.setCancelled(true);
    }
}
