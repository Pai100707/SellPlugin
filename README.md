# Sell 1.3.1 (com.paismp.sell) - /sell /worth /editsell for Paper 1.21.11

The search button uses the Paper dialog (text box) API; Bedrock players need a Geyser version that converts dialogs,
otherwise they can use `/worth search <text>`.

## Build
Java 21 + Gradle (the Gradle wrapper files are NOT included, add your own).
`gradle build`  ->  `build/libs/Sell-1.3.1.jar`

## Install
1. Install Vault + an economy plugin.
2. Put `Sell-1.3.1.jar` in `plugins/`, start the server.
3. Files created in `plugins/Sell/`: `config.yml`, `gui_layout/`, `lang/`, `price/`.
   Every `.yml` you add to `price/` is loaded automatically.

### Updating from 1.3.0
- `layout/` was renamed to `gui_layout/` and now describes the GUIs. Delete the old `layout/` folder.
- Delete `lang/en.yml` and `config.yml` so the new ones are generated (copy your own changes over).
  Missing lang keys fall back to the built-in text, so nothing breaks if you keep the old file.
- `price/*.yml` are never overwritten.

## Commands / permissions
| Command | What it does | Permission |
|---|---|---|
| `/sell` | opens the sell menu, 6 rows (items are sold when you close it or click the lime pane) - GUI only | sell.sell (everyone) |
| `/worth` | opens the paged price list (arrows = pages, compass = category, spyglass = search, hopper = sort) | sell.worth (everyone) |
| `/worth search <text>` | same list filtered by a name (chat version of the search button) | sell.worth |
| `/worth hand` | shows the worth of your held item in chat | sell.worth |
| `/sell reload` | reloads config, lang, gui_layout and price | sell.reload (op) |
| `/editsell <Type> <add/modify/remove> <Item> [level] [price]` | edits prices in game | sell.admin (op) |
| multiplier | `sell.multiplier.<number>`, e.g. `sell.multiplier.1.5` | |

/editsell examples:
- `/editsell Block add DIRT 1`
- `/editsell Tool modify DIAMOND_SWORD 100`
- `/editsell Tool add SHARPNESS 1 100`
- `/editsell Block remove DIRT`
- `/editsell Item add HAND 25`
- potions: `/editsell Item add POTION:STRONG_SWIFTNESS 40` (also SPLASH_POTION, LINGERING_POTION, TIPPED_ARROW)

## Texts and layouts
- **All texts** (chat, GUI names, GUI lore, sell/worth chat layouts) are in `lang/en.yml`.
- `gui_layout/sell.yml` and `gui_layout/worth.yml` only say where things are: rows, slots, materials,
  and the action of each button (`SELL`, `PREVIOUS_PAGE`, `NEXT_PAGE`, `CATEGORY`, `SORT`, `CLOSE`).
  `name:` / `lore:` / `title:` there are keys of `lang/en.yml`.
- To add another language copy `lang/en.yml` to `lang/xx.yml` and set `language: xx` in `config.yml`.

## Price files
- `price/*.yml` (Block, Item, Tool, Potion) sections: `items:` (Material -> price), `enchantments:` (ENCHANT -> level -> price),
  `potions:` (POTION / SPLASH_POTION / LINGERING_POTION / TIPPED_ARROW -> potion type -> price).
- Items not listed (or set to 0) can not be sold. Unknown names are skipped and listed once in the console.
- Final worth = (base x durability ratio + enchantments) x amount x multiplier, then rounded (FLOOR by default).
- The category button of `/worth` has one entry per price file name.

## Notes
- Shulker box: with items -> sells the sellable contents and gives the box back (unsellable stuff stays inside);
  empty box -> sells the box itself.
- Creative mode is NOT blocked: a creative player can spawn items for free and sell them. Keep sell.sell away from creative-mode staff.
- Anti-exploit: items with custom model data or custom plugin data (PDC/NBT) are never sold.
  `anti_exploit.protected_keys` / `protected_namespaces` explicitly protect AmethystTools items
  (`amethysttools:*` keys) even if `block_persistent_data` is turned off.
- The worth lore on inventory items was REMOVED in 1.3.1 (lore made items stop stacking).
  `legacy_lore_cleanup: true` strips the lore that 1.3.0 left on items when the player joins.
