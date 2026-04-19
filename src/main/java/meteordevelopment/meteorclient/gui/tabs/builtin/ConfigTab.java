/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.tabs.builtin;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.TranslationHelper;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;
import net.minecraft.client.gui.screen.Screen;

public class ConfigTab extends Tab {
    private static final String KEY_PREFIX = "meteor.meteor_client.gui.tab.config";

    public ConfigTab() {
        super("Config");
    }

    @Override
    protected String translationKey() {
        return KEY_PREFIX + ".title";
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new ConfigScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof ConfigScreen;
    }

    public static class ConfigScreen extends WindowTabScreen {
        private final Settings settings;

        public ConfigScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);

            settings = Config.get().settings;
            settings.onActivated();

            onClosed(() -> {
                String prefix = Config.get().prefix.get();

                if (prefix.isBlank()) {
                    YesNoPrompt.create(theme, this.parent)
                        .title(tr("prompt.empty_prefix.title", "Empty command prefix"))
                        .message(tr("prompt.empty_prefix.line_1", "You have set your command prefix to nothing."))
                        .message(tr("prompt.empty_prefix.line_2", "This WILL prevent you from sending chat messages."))
                        .message(tr("prompt.empty_prefix.line_3", "Do you want to reset your prefix back to '.'?"))
                        .onYes(() -> Config.get().prefix.set("."))
                        .id("empty-command-prefix")
                        .show();
                }
                else if (prefix.equals("/")) {
                    YesNoPrompt.create(theme, this.parent)
                        .title(tr("prompt.prefix_conflict.title", "Potential prefix conflict"))
                        .message(tr("prompt.prefix_conflict.line_1", "You have set your command prefix to '/', which is used by minecraft."))
                        .message(tr("prompt.prefix_conflict.line_2", "This can cause conflict issues between meteor and minecraft commands."))
                        .message(tr("prompt.prefix_conflict.line_3", "Do you want to reset your prefix to '.'?"))
                        .onYes(() -> Config.get().prefix.set("."))
                        .id("minecraft-prefix-conflict")
                        .show();
                }
                else if (prefix.length() > 7) {
                    YesNoPrompt.create(theme, this.parent)
                        .title(tr("prompt.long_prefix.title", "Long command prefix"))
                        .message(tr("prompt.long_prefix.line_1", "You have set your command prefix to a very long string."))
                        .message(tr("prompt.long_prefix.line_2", "This means that in order to execute any command, you will need to type %s followed by the command you want to run.", prefix))
                        .message(tr("prompt.long_prefix.line_3", "Do you want to reset your prefix back to '.'?"))
                        .onYes(() -> Config.get().prefix.set("."))
                        .id("long-command-prefix")
                        .show();
                }
            });
        }

        @Override
        public void initWidgets() {
            add(theme.settings(settings)).expandX();
        }

        @Override
        public void tick() {
            super.tick();

            settings.tick(window, theme);
        }

        @Override
        public boolean toClipboard() {
            return NbtUtils.toClipboard(Config.get());
        }

        @Override
        public boolean fromClipboard() {
            return NbtUtils.fromClipboard(Config.get());
        }

        private static String tr(String key, String fallback, Object... args) {
            return TranslationHelper.translate(KEY_PREFIX + "." + key, fallback, args);
        }
    }
}
