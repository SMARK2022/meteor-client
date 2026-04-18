/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.resource.language.I18n;

import java.util.HashMap;
import java.util.Map;

public class TranslationHelper {
    private static boolean initialized = false;
    private static boolean applied = false;

    // key → [originalTitle, originalDescription]
    private static final Map<String, String[]> originalModuleTexts = new HashMap<>();
    private static final Map<String, String[]> originalSettingTexts = new HashMap<>();
    private static final Map<String, String[]> originalConfigTexts = new HashMap<>();

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

        // Reset Config settings
        resetConfigSettings();
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

     

    private static String getConfigSettingKey(SettingGroup group, Setting<?> setting) {
        return "meteor.meteor_client.config.setting." + baseFormat(group.name) + "." + baseFormat(setting.name);
    }   applied = false;
        MeteorClient.LOG.info("Translation reset to English");
    }

    public static boolean isApplied() {
        return applied;
    }

    public static boolean isInitialized() {
        return initialized;
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

    /**
     * Attempts to translate a key using MC's I18n system.
     * Returns the translated string, or null if no translation was found (key returned as-is).
     */
    private static String tryTranslate(String key) {
        String result = I18n.translate(key);
        return result.equals(key) ? null : result;
    }
}
