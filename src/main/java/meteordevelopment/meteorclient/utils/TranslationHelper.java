/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.resource.language.I18n;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 这里干两件事：
 * 一是把 Meteor 自己以及 addon 暴露出来的模块 / 设置 / HUD 元信息映射到当前语言；
 * 二是在运行时捕获缺失 key，顺手沉淀成一个可直接回填语言包的 JSON。
 *
 * 之所以把这两个动作绑在一起，是因为它们都依赖同一套 key 生成规则。
 * 真正的翻译决策仍然是纯 key -> 文本查表，副作用只留在最外层的 missing report 写盘里。
 */
public class TranslationHelper {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MISSING_TRANSLATIONS_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final Path MISSING_TRANSLATIONS_FILE = MeteorClient.FOLDER.toPath().resolve("missing-translations.json");

    private static boolean initialized = false;
    private static boolean applied = false;
    private static boolean missingTranslationsLoaded = false;

    // 这里只缓存第一次见到的原始英文，避免反复切语言之后把已翻译文本再当作新的基线覆盖掉。
    private static final Map<String, String[]> originalModuleTexts = new HashMap<>();
    private static final Map<String, String[]> originalSettingTexts = new HashMap<>();
    private static final Map<String, String[]> originalConfigTexts = new HashMap<>();
    private static final Map<String, String[]> originalHudTexts = new HashMap<>();

    // LinkedHashMap 保持首次发现顺序，后面人工补语言包时更容易沿着实际使用轨迹看缺口。
    private static final LinkedHashMap<String, String> missingTranslations = new LinkedHashMap<>();

    /**
     * 资源管理器和语言表在客户端真正跑起来之前并不稳定，
     * 所以初始化被刻意推迟到首个 tick，避免在模组装载早期拿到半成品 I18n 状态。
     */
    public static void initIfNeeded() {
        if (initialized) return;
        initialized = true;
        refresh();
    }

    /**
     * `translate-modules` 是这个 helper 唯一的总闸。
     * 如果用户在资源尚未就绪前切换开关，这里直接吞掉，等首 tick 再统一收敛状态。
     */
    public static void onConfigChanged(boolean enabled) {
        if (!initialized) return;
        if (enabled) translateAll();
        else resetAll();
    }

    /**
     * 给外部一个幂等的重同步入口。
     * 不关心调用方是语言切换、配置重载还是 addon 热插入，最终都只收敛到 translate / reset 两条路。
     */
    public static void refresh() {
        if (Config.get().translateModules.get()) {
            translateAll();
        } else {
            resetAll();
        }
    }

    /**
     * 整个翻译流程的核心阶段只做状态映射：
     * 先拿稳定 key，再尝试查语言表，命中就覆盖运行时标题，未命中就把缺口扔给 missing report。
     */
    public static void translateAll() {
        int translated = 0;

        for (Module module : Modules.get().getAll()) {
            String moduleKey = getModuleKey(module);

            originalModuleTexts.putIfAbsent(moduleKey, new String[]{module.title, module.description});

            String titleResult = tryTranslate(moduleKey + ".name", module.title);
            if (titleResult != null) {
                module.title = titleResult;
                translated++;
            }

            String descResult = tryTranslate(moduleKey + ".description", module.description);
            if (descResult != null) {
                module.description = descResult;
            }

            for (SettingGroup group : module.settings) {
                for (Setting<?> setting : group) {
                    String settingKey = getSettingKey(module, group, setting);

                    originalSettingTexts.putIfAbsent(settingKey, new String[]{setting.title, setting.description});

                    String stResult = tryTranslate(settingKey + ".name", setting.title);
                    if (stResult != null) setting.title = stResult;

                    String sdResult = tryTranslate(settingKey + ".description", setting.description);
                    if (sdResult != null) setting.description = sdResult;
                }
            }
        }

        applied = true;
        MeteorClient.LOG.info("Translation applied: {} modules translated", translated);

        translateConfigSettings();
        translateHudElements();
    }

    /**
     * HUD 的 info 不会像模块那样走常规设置页，但它同样会被 GUI、编辑器和搜索入口直接消费，
     * 所以这里单独扫一遍，避免 HUD 名称语言和模块语言出现割裂。
     */
    private static void translateHudElements() {
        for (HudElementInfo<?> info : Hud.get().infos.values()) {
            String key = getHudElementKey(info);

            originalHudTexts.putIfAbsent(key, new String[]{info.title, info.description});

            String titleResult = tryTranslate(key + ".name", info.title);
            if (titleResult != null) info.title = titleResult;

            String descResult = tryTranslate(key + ".description", info.description);
            if (descResult != null) info.description = descResult;
        }
    }

    /**
     * Config 本身不属于模块树，key 命名空间也独立，必须单独处理。
     * 这里复用同一套缺失 key 记录逻辑，这样主仓库和 addon 的翻译缺口最终都会落到同一份报告里。
     */
    private static void translateConfigSettings() {
        for (SettingGroup group : Config.get().settings) {
            for (Setting<?> setting : group) {
                String key = getConfigSettingKey(group, setting);

                originalConfigTexts.putIfAbsent(key, new String[]{setting.title, setting.description});

                String stResult = tryTranslate(key + ".name", setting.title);
                if (stResult != null) setting.title = stResult;

                String sdResult = tryTranslate(key + ".description", setting.description);
                if (sdResult != null) setting.description = sdResult;
            }
        }
    }

    /**
     * 关闭翻译时绝不能再从语言表反推英文，
     * 因为很多文本在运行时已经被覆盖过了，唯一可靠的基线只有首次缓存下来的原始字符串。
     */
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

        resetConfigSettings();
        resetHudElements();

        applied = false;
        MeteorClient.LOG.info("Translation reset to English");
    }

    /**
     * Config 页面里的 Setting 对象和模块 Setting 是不同来源，reset 时也必须沿原路返回。
     */
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

    /**
     * HUD info 会被缓存到全局注册表里，reset 不做的话切回英文后界面仍然会残留旧语言标题。
     */
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
     * 这个 helper 的所有运行时翻译都故意共用同一个总开关，
     * 这样 GUI 文案、模块名、HUD 名称在用户视角里是同一套语言策略，而不是各自为政。
     */
    public static boolean shouldTranslate() {
        return Config.get() != null && Config.get().translateModules.get();
    }

    /**
     * 给运行时 GUI 文案用的轻量入口。
     * 这里不会像模块那样改写对象字段，只做一次即取即用的查表；
     * 没命中时直接回英文 fallback，同时把 key 记到报告里，方便后续补翻译而不是静默丢失。
     */
    public static String translate(String key, String fallback, Object... args) {
        String fallbackText = formatFallback(fallback, args);
        if (!shouldTranslate()) return fallbackText;

        String translated = I18n.translate(key, args);
        if (!translated.equals(key)) return translated;

        recordMissingTranslation(key, fallbackText);
        return fallbackText;
    }

    /**
     * 语言 key 生成必须足够保守。
     * 这里只做最基础的规范化，保证主仓库和 addon 两边对同一名字能稳定落到同一个 key 上。
     */
    private static String baseFormat(String name) {
        return name.toLowerCase()
            .replace(" ", "_")
            .replace("-", "_")
            .replace(".", "_")
            .replace("\"", "_");
    }

    /**
     * addon 名字直接进 key 前缀，这样第三方模组只要遵守现有命名约定，
     * helper 就能在不认识具体 addon 代码的情况下稳定生成它的翻译路径。
     */
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
     * 模块 / 设置 / HUD 的批量翻译都走这里。
     * 返回 null 的语义很明确：当前 key 没有翻译，调用方继续保留原始英文即可。
     */
    private static String tryTranslate(String key, String fallback) {
        String result = I18n.translate(key);
        if (!result.equals(key)) return result;

        recordMissingTranslation(key, fallback);
        return null;
    }

    /**
     * 运行时 GUI 文案有格式化参数时，fallback 也必须和翻译分支保持同样的输出形状，
     * 否则缺失 key 时界面文本会突然掉回未格式化模板。
     */
    private static String formatFallback(String fallback, Object... args) {
        if (args == null || args.length == 0) return fallback;
        return String.format(Locale.ROOT, fallback, args);
    }

    /**
     * 缺失 key 的记录逻辑被压成一个很小的副作用边界：
     * 先把已有报告读进内存，再做一次 `putIfAbsent`，只有第一次见到的新 key 才触发落盘。
     * 这样不会在高频渲染路径里重复刷文件，也不会把人工已经整理过的顺序打乱。
     */
    private static void recordMissingTranslation(String key, String fallback) {
        loadMissingTranslations();

        String existing = missingTranslations.putIfAbsent(key, fallback);
        if (existing != null) return;

        flushMissingTranslations();
    }

    /**
     * missing report 是运行时生成物，不值得每次查 key 都重新扫盘。
     * 首次懒加载后整局共享同一份内存快照，后面的逻辑只做增量写入。
     */
    private static void loadMissingTranslations() {
        if (missingTranslationsLoaded) return;
        missingTranslationsLoaded = true;

        if (!Files.exists(MISSING_TRANSLATIONS_FILE)) return;

        try (Reader reader = Files.newBufferedReader(MISSING_TRANSLATIONS_FILE)) {
            LinkedHashMap<String, String> loaded = GSON.fromJson(reader, MISSING_TRANSLATIONS_TYPE);
            if (loaded != null) missingTranslations.putAll(loaded);
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to load missing translations report.", e);
        }
    }

    /**
     * 报告文件故意维持语言包那种 `key -> fallback` 的扁平 JSON 结构，
     * 目的不是给机器看元数据，而是让人能直接复制粘贴回 `en_us.json` / `zh_cn.json` 去补全。
     */
    private static void flushMissingTranslations() {
        try {
            Files.createDirectories(MISSING_TRANSLATIONS_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(MISSING_TRANSLATIONS_FILE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                GSON.toJson(missingTranslations, MISSING_TRANSLATIONS_TYPE, writer);
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to write missing translations report.", e);
        }
    }
}
