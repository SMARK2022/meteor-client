/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.prompts;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.TranslationHelper;
import net.minecraft.client.gui.screen.Screen;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class YesNoPrompt extends Prompt<YesNoPrompt> {
    private static final String KEY_PREFIX = "meteor.meteor_client.gui.prompt.button";

    private Runnable onYes = () -> {};
    private Runnable onNo = () -> {};

    private YesNoPrompt(GuiTheme theme, Screen parent) {
        super(theme, parent);
    }

    public static YesNoPrompt create() {
        return new YesNoPrompt(GuiThemes.get(), mc.currentScreen);
    }

    public static YesNoPrompt create(GuiTheme theme, Screen parent) {
        return new YesNoPrompt(theme, parent);
    }

    public YesNoPrompt onYes(Runnable action) {
        this.onYes = action;
        return this;
    }

    public YesNoPrompt onNo(Runnable action) {
        this.onNo = action;
        return this;
    }

    @Override
    protected void initialiseWidgets(PromptScreen screen) {
        WButton yesButton = screen.list.add(theme.button(TranslationHelper.translate(KEY_PREFIX + ".yes", "Yes"))).expandX().widget();
        yesButton.action = () -> {
            dontShowAgain(screen);
            onYes.run();
            screen.close();
        };

        WButton noButton = screen.list.add(theme.button(TranslationHelper.translate(KEY_PREFIX + ".no", "No"))).expandX().widget();
        noButton.action = () -> {
            dontShowAgain(screen);
            onNo.run();
            screen.close();
        };
    }
}
