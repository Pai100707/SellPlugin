package com.paismp.sell;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** The text box of the /worth search button (Paper dialog, no sign). */
public final class SearchDialog {

    private SearchDialog() {
    }

    /**
     * Shows the dialog. onSubmit gets the typed text; onCancel runs when the player clicks the cancel button
     * (closing the dialog with Escape does neither).
     */
    public static void open(SellPlugin plugin, Player player, String current,
                            Consumer<String> onSubmit, Runnable onCancel) {
        Messages msg = plugin.messages();
        ClickCallback.Options once = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(10))
                .build();

        ActionButton search = ActionButton.builder(msg.text("dialog.search.confirm"))
                .width(150)
                .action(DialogAction.customClick((view, audience) -> {
                    String text = view.getText("query");
                    String query = text == null ? "" : text.trim();
                    player.getScheduler().run(plugin, task -> onSubmit.accept(query), null);
                }, once))
                .build();
        ActionButton cancel = ActionButton.builder(msg.text("dialog.search.cancel"))
                .width(150)
                .action(DialogAction.customClick((view, audience) ->
                        player.getScheduler().run(plugin, task -> onCancel.run(), null), once))
                .build();

        DialogInput input = DialogInput.text("query", msg.text("dialog.search.label"))
                .width(300)
                .initial(current == null ? "" : current)
                .maxLength(64)
                .build();

        DialogBase base = DialogBase.builder(msg.text("dialog.search.title"))
                .inputs(List.of(input))
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.confirmation(search, cancel)));
        player.showDialog(dialog);
    }
}
