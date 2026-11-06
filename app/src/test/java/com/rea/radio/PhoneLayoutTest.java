package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 手机 / 车机版面判定（纯函数，不需要 Robolectric）。
 *
 * <p>这是「装到手机上不会变成两栏挤成一团」的第一道保障：
 * 判定错了，后面所有尺寸调整都白搭。
 */
public class PhoneLayoutTest {

    // ------------------------------------------------------------ 版面判定

    @Test
    public void car_screen_uses_two_column_layout() {
        // 星愿车机：1920×1080 @2x ⇒ 可用 960×540dp，宽扁
        assertTrue("车机必须走两栏版", PhoneLayout.useTwoColumn(960, 540));
        assertFalse("车机不是手机尺寸", PhoneLayout.compactMetrics(960, 540));
    }

    @Test
    public void common_phones_use_single_column() {
        // 覆盖市面上主流竖屏手机宽度：360 / 384 / 393 / 411 / 430dp
        int[][] phones = {
                {360, 640},   // 老机型 720p
                {360, 780},   // 1080p 常见
                {384, 854},
                {393, 851},   // 接近 Pixel
                {411, 869},   // 1080p 宽体
                {430, 932},   // 大屏 iPhone 尺寸档
        };
        for (int[] wh : phones) {
            assertFalse(wh[0] + "×" + wh[1] + " 不能用两栏",
                    PhoneLayout.useTwoColumn(wh[0], wh[1]));
            assertTrue(wh[0] + "×" + wh[1] + " 应按手机尺寸收紧",
                    PhoneLayout.compactMetrics(wh[0], wh[1]));
        }
    }

    @Test
    public void phone_in_landscape_stays_single_column() {
        // 手机转横屏：360×780 → 780×360。
        // 它确实是「宽扁」的，但高度只有 360dp，两栏版塞不下（左栏内容 ≈ 480dp）。
        // 若这里判成两栏，手机上转个屏界面就会挤成一团 —— 这条断言锁死该行为。
        assertFalse("手机横屏不能用两栏（高度 360dp < 480dp）",
                PhoneLayout.useTwoColumn(780, 360));
        assertTrue("但尺寸仍按手机收紧", PhoneLayout.compactMetrics(780, 360));
    }

    @Test
    public void carlink_projection_uses_two_column() {
        // Carlink 投屏的虚拟屏：3200×1600 @2.25 ⇒ 1422×711dp
        // （v1.9.x 实车日志 [屏幕] onCreate id=10017(虚拟屏) 1422x711dp 密度2.25）
        assertTrue("投屏窗口比车机还大，必须两栏",
                PhoneLayout.useTwoColumn(1422, 711));
        assertFalse("投屏窗口同样是车规字号，不收紧",
                PhoneLayout.compactMetrics(1422, 711));
    }

    @Test
    public void carlink_small_window_uses_single_column() {
        // Carlink 1.6 起投屏应用默认以**小窗**打开，可自由缩放拖动。
        // 800×450dp 这类窗口高度塞不下两栏，必须落回单列 + 手机尺寸，
        // 否则 28sp 台名 + 86dp 行高会把整个窗口撑爆。
        assertFalse("小窗高度 450dp < 480dp，不能用两栏",
                PhoneLayout.useTwoColumn(800, 450));
        assertTrue("小窗尺寸按手机收紧", PhoneLayout.compactMetrics(800, 450));
        // 再窄一点也一样
        assertFalse(PhoneLayout.useTwoColumn(640, 400));
        assertTrue(PhoneLayout.compactMetrics(640, 400));
    }

    @Test
    public void portrait_tablet_uses_single_column() {
        // 800×1280 竖屏平板：宽 < 高，两栏左右排不开 → 单列；
        // 但短边 800dp ≥ 600，不算手机，尺寸不收紧（台名用 28sp 更好看）
        assertFalse(PhoneLayout.useTwoColumn(800, 1280));
        assertFalse(PhoneLayout.compactMetrics(800, 1280));
    }

    @Test
    public void landscape_tablet_uses_two_column() {
        // 1280×800 横屏平板：宽扁且高度 800 ≥ 520，两栏放得下
        assertTrue(PhoneLayout.useTwoColumn(1280, 800));
        assertFalse("横屏平板不是手机，尺寸不收紧", PhoneLayout.compactMetrics(1280, 800));
    }

    @Test
    public void short_landscape_phone_never_gets_two_column() {
        // 覆盖小屏手机横屏：640×360 / 780×360 / 851×393
        int[][] landscapePhones = {{640, 360}, {780, 360}, {851, 393}};
        for (int[] wh : landscapePhones) {
            assertFalse(wh[0] + "×" + wh[1] + " 高度不够，必须单列",
                    PhoneLayout.useTwoColumn(wh[0], wh[1]));
        }
    }

    // ------------------------------------------------------------ 台标尺寸

    @Test
    public void logo_scales_with_short_edge_and_is_clamped() {
        // 360*0.33=118.8→119dp；430*0.33=141.9→142dp；折叠屏展开 600dp → 夹到上限 160
        assertEquals(119, PhoneLayout.logoSizeDp(360, 780));
        assertEquals(142, PhoneLayout.logoSizeDp(430, 932));
        assertEquals(160, PhoneLayout.logoSizeDp(600, 1000));
        // 极小屏（320dp）也不能小于下限 110
        assertEquals(110, PhoneLayout.logoSizeDp(320, 480));
    }

    @Test
    public void logo_always_leaves_room_for_rest_of_card() {
        // 台标不能吃掉播放卡的大半高度，否则台名/按钮被挤出屏幕。
        // 竖屏手机上播放卡预算约「屏高 - 顶栏 56 - 标签 44 - 列表 150」。
        int[][] phones = {{360, 640}, {360, 780}, {411, 869}, {430, 932}};
        for (int[] wh : phones) {
            int cardBudget = wh[1] - 56 - 44 - 150;
            int logo = PhoneLayout.logoSizeDp(wh[0], wh[1]);
            assertTrue(wh[0] + "×" + wh[1] + "：台标 " + logo + "dp 超过播放卡预算 "
                            + cardBudget + "dp 的 60%",
                    logo <= cardBudget * 0.6);
        }
    }

    // ------------------------------------------------------------ 行高

    @Test
    public void row_height_shrinks_on_small_phones() {
        // v1.8.3 起列表吃满「顶栏 + 标签 + 迷你播放条」之外的全部高度，
        // 行数够用，所以默认压到 56dp 多露几行；只有短边 ≥ 420dp 的大屏才放到 64dp。
        assertEquals("小屏（短边 < 420dp）行高 56dp",
                56, PhoneLayout.rowHeightDp(360, 640));
        assertEquals("主流屏（411dp）仍是 56dp",
                56, PhoneLayout.rowHeightDp(411, 869));
        assertEquals("大屏（短边 ≥ 420dp）行高 64dp",
                64, PhoneLayout.rowHeightDp(430, 932));
    }

    @Test
    public void row_height_is_tappable() {
        // 56dp 仍然大于 Android 推荐的 48dp 最小触控目标
        assertTrue(PhoneLayout.rowHeightDp(360, 640) >= 48);
    }

    // ------------------------------------------------------------ 滑动阈值

    @Test
    public void phone_swipe_threshold_is_lighter_than_car() {
        float density = 2f;
        float slop = 16f; // 约 8dp 的 touchSlop
        float phone = SwipeStationHelper.minSwipePxPhone(slop, density);
        float car = SwipeStationHelper.minSwipePx(slop, density);
        assertEquals("手机 48dp", 96f, phone, 0.01f);
        assertEquals("车机 90dp", 180f, car, 0.01f);
        assertTrue("手机上滑动阈值必须比车机轻", phone < car);
    }

    @Test
    public void swipe_threshold_never_below_touch_slop() {
        // 某些设备 touchSlop 很大（戴手套模式 32dp），此时必须让位给 touchSlop*2
        float density = 2f;
        float hugeSlop = 64f;
        float phone = SwipeStationHelper.minSwipePxPhone(hugeSlop, density);
        assertTrue("阈值不能小于 touchSlop*2", phone >= hugeSlop * 2f);
    }
}
