/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.resource.language.I18n;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class TranslationHelper {
    private static boolean initialized = false;
    private static boolean applied = false;

    // key → [originalTitle, originalDescription]
    private static final Map<String, String[]> originalModuleTexts = new HashMap<>();
    private static final Map<String, String[]> originalSettingTexts = new HashMap<>();
    private static final Map<String, String[]> originalConfigTexts = new HashMap<>();
    private static final Map<String, String[]> originalHudTexts = new HashMap<>();

    /**
     * Called on the first game tick. Checks Config and applies translation if enabled.
     */
    public static void initIfNeeded() {
        if (initialized) return;
        initialized = true;
        refresh();
    }

    /**
     * Called from Config.translateModules.onChanged.
     * Ignored if the first-tick init hasn't happened yet (resources not ready).
     */
    public static void onConfigChanged(boolean enabled) {
        if (!initialized) return;
        if (enabled) translateAll();
        else resetAll();
    }

    /**
     * Re-check Config and translate/reset accordingly. Can be called any time after init.
     */
    public static void refresh() {
        if (Config.get().translateModules.get()) {
            translateAll();
        } else {
            resetAll();
        }
    }

    public static void translateAll() {
        int translated = 0;

        for (Module module : Modules.get().getAll()) {
            String moduleKey = getModuleKey(module);

            // Store originals on first encounter
            originalModuleTexts.putIfAbsent(moduleKey, new String[]{module.title, module.description});

            // Translate module title
            String titleResult = tryTranslate(moduleKey + ".name");
            if (titleResult != null) {
                module.title = titleResult;
                translated++;
            }

            // Translate module description
            String descResult = tryTranslate(moduleKey + ".description");
            if (descResult != null) {
                module.description = descResult;
            }

            // Translate settings in each group
            for (SettingGroup group : module.settings) {
                for (Setting<?> setting : group) {
                    String settingKey = getSettingKey(module, group, setting);

                    originalSettingTexts.putIfAbsent(settingKey, new String[]{setting.title, setting.description});

                    String stResult = tryTranslate(settingKey + ".name");
                    if (stResult != null) setting.title = stResult;

                    String sdResult = tryTranslate(settingKey + ".description");
                    if (sdResult != null) setting.description = sdResult;
                }
            }
        }

        applied = true;
        MeteorClient.LOG.info("Translation applied: {} modules translated", translated);

        // Translate Config settings
        translateConfigSettings();

        // Translate HUD element infos
        translateHudElements();
    }

    private static void translateHudElements() {
        for (HudElementInfo<?> info : Hud.get().infos.values()) {
            String key = getHudElementKey(info);

            originalHudTexts.putIfAbsent(key, new String[]{info.title, info.description});

            String titleResult = tryTranslate(key + ".name");
            if (titleResult != null) info.title = titleResult;

            String descResult = tryTranslate(key + ".description");
            if (descResult != null) info.description = descResult;
        }
    }

    private static void translateConfigSettings() {
        for (SettingGroup group : Config.get().settings) {
            for (Setting<?> setting : group) {
                String key = getConfigSettingKey(group, setting);

                originalConfigTexts.putIfAbsent(key, new String[]{setting.title, setting.description});

                String stResult = tryTranslate(key + ".name");
                if (stResult != null) setting.title = stResult;

                String sdResult = tryTranslate(key + ".description");
                if (sdResult != null) setting.description = sdResult;
            }
        }
    }

    public static void resetAll() {
        for (Module module : Modules.get().getAll()) {
            String moduleKey = getModuleKey(module);
            String[] orig = originalModuleTexts.get(moduleKey);
            if (orig != null) {
                module.title = orig[0];
                module.description = orig[1];
            }

            for (SettingGroup group : module.settings) {
                for (Setting<?> setting : group) {
                    String settingKey = getSettingKey(module, group, setting);
                    String[] origS = originalSettingTexts.get(settingKey);
                    if (origS != null) {
                        setting.title = origS[0];
                        setting.description = origS[1];
                    }
                }
            }
        }

        // Reset Config settings
        resetConfigSettings();

        // Reset HUD element infos
        resetHudElements();

        applied = false;
        MeteorClient.LOG.info("Translation reset to English");
    }

    private static void resetConfigSettings() {
        for (SettingGroup group : Config.get().settings) {
            for (Setting<?> setting : group) {
                String key = getConfigSettingKey(group, setting);
                String[] origS = originalConfigTexts.get(key);
                if (origS != null) {
                    setting.title = origS[0];
                    setting.description = origS[1];
                }
            }
        }
    }

    private static void resetHudElements() {
        for (HudElementInfo<?> info : Hud.get().infos.values()) {
            String key = getHudElementKey(info);
            String[] orig = originalHudTexts.get(key);
            if (orig != null) {
                info.title = orig[0];
                info.description = orig[1];
            }
        }
    }

    public static boolean isApplied() {
        return applied;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns whether user-controlled translations are currently enabled.
     */
    public static boolean shouldTranslate() {
        return Config.get() != null && Config.get().translateModules.get();
    }

    /**
     * Translates a runtime UI string under the same Config-controlled switch used by module translations.
     * Falls back to the provided default text if translation is disabled or the key is missing.
     */
    public static String translate(String key, String fallback, Object... args) {
        String fallbackText = formatFallback(fallback, args);
        if (!shouldTranslate()) return fallbackText;

        String translated = I18n.translate(key, args);
        return translated.equals(key) ? fallbackText : translated;
    }

    // --- Key generation ---

    private static String baseFormat(String name) {
        return name.toLowerCase()
            .replace(" ", "_")
            .replace("-", "_")
            .replace(".", "_")
            .replace("\"", "_");
    }

    private static String getAddonId(Module module) {
        if (module.addon != null) {
            return baseFormat(module.addon.name);
        }
        return "meteor_client";
    }

    private static String getModuleKey(Module module) {
        return "meteor." + getAddonId(module) + "." + baseFormat(module.category.name) + "." + baseFormat(module.name);
    }

    private static String getSettingKey(Module module, SettingGroup group, Setting<?> setting) {
        return getModuleKey(module) + ".setting." + baseFormat(group.name) + "." + baseFormat(setting.name);
    }

    private static String getConfigSettingKey(SettingGroup group, Setting<?> setting) {
        return "meteor.meteor_client.config.setting." + baseFormat(group.name) + "." + baseFormat(setting.name);
    }

    private static String getHudElementKey(HudElementInfo<?> info) {
        return "meteor.meteor_client.hud." + baseFormat(info.name);
    }

    /**
     * Attempts to translate a key using MC's I18n system.
     * Returns the translated string, or null if no translation was found (key returned as-is).
     */
    private static String tryTranslate(String key) {
        String result = I18n.translate(key);
        return result.equals(key) ? null : result;
    }

    private static String formatFallback(String fallback, Object... args) {
        if (args == null || args.length == 0) return fallback;
        return String.format(Locale.ROOT, fallback, args);
    }
}
