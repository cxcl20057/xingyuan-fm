package com.rea.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * 白天 / 夜间主题的三态开关（v2.1）。
 *
 * <p><b>为什么不用 AppCompatDelegate：</b>本工程是 framework {@code Activity} +
 * {@code Theme.Material.*}，没有引入 appcompat，硬加一个依赖会连累 Carlink
 * 白名单包的 dex 与既有单测。这里用 {@code createConfigurationContext()} 覆盖
 * {@code Configuration.uiMode} 的夜间位，让系统的 {@code values-night/} 限定符
 * 自己选资源 —— 纯 framework、API 17+ 可用、零新依赖。
 *
 * <p><b>为什么还要「跟随系统」：</b>车机（吉利银河 E22H，Android 9）的夜间态
 * 可能由大灯 / 车机全局主题驱动 {@code uiMode}。默认跟随系统，同时保留手动
 * 白天 / 夜间两档，因为不少车厂并不真的改 {@code uiMode}。
 *
 * <p><b>坑：</b>manifest 的 {@code configChanges} 含 {@code uiMode}，系统切夜间时
 * Activity <b>不会</b>重建，已经 inflate 的 drawable / ColorStateList 不会自己换色。
 * 所以 {@link MainActivity#onConfigurationChanged} 必须显式 {@code recreate()}，
 * 光放 {@code values-night/} 是不够的。
 */
public final class ThemeMode {

    /** 跟随系统车机夜间态（默认）。 */
    public static final int FOLLOW = 0;
    /** 强制白天。 */
    public static final int DAY = 1;
    /** 强制夜间。 */
    public static final int NIGHT = 2;

    private static final String PREFS = "ui_prefs";
    private static final String KEY = "theme_mode";

    private ThemeMode() { }

    private static Context app(Context c) {
        Context a = c.getApplicationContext();
        return a != null ? a : c;
    }

    /** 非法值一律回落到 FOLLOW，避免以后读到一个旧版本遗留的枚举就白屏。 */
    public static int normalize(int mode) {
        return (mode == DAY || mode == NIGHT) ? mode : FOLLOW;
    }

    public static int get(Context c) {
        return normalize(app(c).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY, FOLLOW));
    }

    public static void set(Context c, int mode) {
        app(c).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY, normalize(mode)).apply();
    }

    /** 顶栏按钮点一下的下一档：跟随系统 → 白天 → 夜间 → 跟随系统。 */
    public static int next(int mode) {
        int m = normalize(mode);
        return m == FOLLOW ? DAY : (m == DAY ? NIGHT : FOLLOW);
    }

    public static String shortLabel(int mode) {
        switch (normalize(mode)) {
            case DAY: return "白天";
            case NIGHT: return "夜间";
            default: return "自动";
        }
    }

    public static String label(int mode) {
        switch (normalize(mode)) {
            case DAY: return "白天（强制）";
            case NIGHT: return "夜间（强制）";
            default: return "跟随系统";
        }
    }

    /** 系统的 {@code uiMode} 是否处于夜间。纯函数，可单测。 */
    public static boolean systemNight(int uiMode) {
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 某个模式 + 某个系统 uiMode 下，最终该用哪套色板。纯函数，可单测。 */
    public static boolean effectiveNight(int mode, int uiMode) {
        int m = normalize(mode);
        if (m == DAY) return false;
        if (m == NIGHT) return true;
        return systemNight(uiMode);
    }

    /** 当前 Activity 实际生效的是不是夜间。 */
    public static boolean isNight(Context c) {
        return effectiveNight(get(c), c.getResources().getConfiguration().uiMode);
    }

    /**
     * 在 {@code attachBaseContext} 里调用：手动模式下覆盖夜间位，
     * 让 {@code values-night/colors.xml} 与 {@code values-night/styles.xml} 生效。
     * 跟随系统时原样返回，由系统自己的 config 决定。
     */
    public static Context wrap(Context base) {
        int m = get(base);
        if (m == FOLLOW) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int bits = (m == NIGHT)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | bits;
        return base.createConfigurationContext(cfg);
    }
}
