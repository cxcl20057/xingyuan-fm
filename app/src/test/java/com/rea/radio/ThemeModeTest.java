package com.rea.radio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v2.1 双主题：三态切换的纯逻辑 + 两套色板的 WCAG 下限。
 *
 * <p>这里刻意不依赖 Robolectric / Android Context：模式判定写成了
 * {@code effectiveNight(int, int)} 这样的纯函数，JVM 上就能跑，
 * 不会因为 Windows 缺 nativeruntime 而被跳过。真实 XML 色板的逐对核算
 * 由 {@code scripts/check_contrast.py} 承担（它读的是文件，不是这里的常量）。
 */
public class ThemeModeTest {

    // Configuration.UI_MODE_NIGHT_MASK / _YES / _NO 的字面值。
    // 写成常量是为了让本测试完全脱离 android.jar 的 stub。
    private static final int MASK = 0x30;
    private static final int NIGHT_YES = 0x20;
    private static final int NIGHT_NO = 0x10;

    @Test
    public void normalize_keeps_the_three_valid_modes() {
        assertEquals(ThemeMode.FOLLOW, ThemeMode.normalize(ThemeMode.FOLLOW));
        assertEquals(ThemeMode.DAY, ThemeMode.normalize(ThemeMode.DAY));
        assertEquals(ThemeMode.NIGHT, ThemeMode.normalize(ThemeMode.NIGHT));
    }

    @Test
    public void normalize_maps_any_unknown_value_to_follow() {
        // 以后读到旧版本遗留的枚举 / 损坏的 SharedPreferences 也不能白屏
        assertEquals(ThemeMode.FOLLOW, ThemeMode.normalize(-1));
        assertEquals(ThemeMode.FOLLOW, ThemeMode.normalize(99));
    }

    @Test
    public void next_cycles_follow_day_night_follow() {
        assertEquals(ThemeMode.DAY, ThemeMode.next(ThemeMode.FOLLOW));
        assertEquals(ThemeMode.NIGHT, ThemeMode.next(ThemeMode.DAY));
        assertEquals(ThemeMode.FOLLOW, ThemeMode.next(ThemeMode.NIGHT));
        assertEquals(ThemeMode.DAY, ThemeMode.next(999));
    }

    @Test
    public void labels_are_short_on_the_button_and_explicit_in_about() {
        assertEquals("自动", ThemeMode.shortLabel(ThemeMode.FOLLOW));
        assertEquals("白天", ThemeMode.shortLabel(ThemeMode.DAY));
        assertEquals("夜间", ThemeMode.shortLabel(ThemeMode.NIGHT));
        assertEquals("跟随系统", ThemeMode.label(ThemeMode.FOLLOW));
        assertTrue(ThemeMode.label(ThemeMode.DAY).contains("强制"));
        assertTrue(ThemeMode.label(ThemeMode.NIGHT).contains("强制"));
    }

    @Test
    public void system_night_only_looks_at_the_mask() {
        // 高位带着其它 uiMode 位（车载 / 电视 / 平板）时不能误判
        assertTrue(ThemeMode.systemNight(NIGHT_YES | 0x03));
        assertFalse(ThemeMode.systemNight(NIGHT_NO | 0x0C));
        assertFalse(ThemeMode.systemNight(0));
        assertEquals(MASK, 0x30);
    }

    @Test
    public void follow_mode_tracks_the_car_headunit() {
        assertTrue(ThemeMode.effectiveNight(ThemeMode.FOLLOW, NIGHT_YES));
        assertFalse(ThemeMode.effectiveNight(ThemeMode.FOLLOW, NIGHT_NO));
    }

    @Test
    public void manual_day_ignores_a_night_system() {
        assertFalse(ThemeMode.effectiveNight(ThemeMode.DAY, NIGHT_YES));
    }

    @Test
    public void manual_night_ignores_a_day_system() {
        assertTrue(ThemeMode.effectiveNight(ThemeMode.NIGHT, NIGHT_NO));
    }

    // ---------------------------------------------------------- WCAG 下限

    @Test
    public void light_palette_meets_text_contrast() {
        assertTrue(ColorContrast.ratioHex("#16181D", "#FFFFFF") >= 4.5);   // 主文字
        assertTrue(ColorContrast.ratioHex("#5A6270", "#FFFFFF") >= 4.5);   // 次文字
        assertTrue(ColorContrast.ratioHex("#7A828E", "#FFFFFF") >= 3.0);   // 三级 / 图形
        assertTrue(ColorContrast.ratioHex("#C2410C", "#FFF1E8") >= 4.5);   // 浅橙底上的强调字
        assertTrue(ColorContrast.ratioHex("#FFFFFF", "#C94808") >= 4.5);   // 白图标 / 主播放键
    }

    @Test
    public void dark_palette_meets_text_contrast() {
        // 暗色必须单独测，不能默认「白天过了就过了」
        assertTrue(ColorContrast.ratioHex("#F2F4F8", "#191D24") >= 4.5);
        assertTrue(ColorContrast.ratioHex("#AEB6C4", "#191D24") >= 4.5);
        assertTrue(ColorContrast.ratioHex("#8A93A1", "#191D24") >= 3.0);
        assertTrue(ColorContrast.ratioHex("#FFB27A", "#3A2512") >= 4.5);
        assertTrue(ColorContrast.ratioHex("#FFFFFF", "#C2520C") >= 4.5);
    }

    @Test
    public void bright_orange_alone_would_fail_white_text() {
        // 这条把「为什么拆出 accent_solid」钉死：
        // v2.0 的 #FF6600 直接放白字只有 2.94:1，达不到 AA。
        assertTrue(ColorContrast.ratioHex("#FFFFFF", "#FF6600") < 4.5);
        assertTrue(ColorContrast.ratioHex("#FFFFFF", "#C94808") >= 4.5);
    }

    @Test
    public void contrast_ratio_is_symmetric_and_bounded() {
        assertEquals(21.0, ColorContrast.ratioHex("#000000", "#FFFFFF"), 0.01);
        assertEquals(1.0, ColorContrast.ratioHex("#123456", "#123456"), 0.001);
        assertEquals(ColorContrast.ratioHex("#16181D", "#FFFFFF"),
                ColorContrast.ratioHex("#FFFFFF", "#16181D"), 0.0001);
    }

    @Test
    public void parse_hex_accepts_rgb_and_argb() {
        assertEquals(0xFF6600, ColorContrast.parseHex("#FF6600"));
        assertEquals(0xFF6600, ColorContrast.parseHex("#FFFF6600"));
        assertEquals(0x000000, ColorContrast.parseHex("#000000"));
    }

    // ---------------------------------------------------------- 列表首字封面

    @Test
    public void cover_initial_takes_the_first_letter_or_digit() {
        assertEquals("四", CoverInitial.of("四川交通广播（FM101.7）"));
        assertEquals("F", CoverInitial.of("FM101.7 中国之声"));
        // 括号本身会被跳过，但括号里的 FM 是字母，仍然是首字
        assertEquals("F", CoverInitial.of("  （FM）中国之声"));
        assertEquals("中", CoverInitial.of("【】中国之声"));
    }

    @Test
    public void cover_initial_never_returns_blank() {
        assertEquals("·", CoverInitial.of(null));
        assertEquals("·", CoverInitial.of(""));
        assertEquals("·", CoverInitial.of("（）【】 "));
    }
}
