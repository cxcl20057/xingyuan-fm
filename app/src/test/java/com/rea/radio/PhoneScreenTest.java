package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.View;
import android.widget.ListView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机竖屏渲染验证：1080×2340 @xxhdpi ⇒ 可用 360×780dp。
 *
 * <p>断言的是「在手机上装得上、看得见、按得着」，而不是像素级还原：
 * <ol>
 *   <li>必须走手机单列布局（不能是两栏）；</li>
 *   <li>三个控制按钮要落在屏幕内、且够大；</li>
 *   <li>列表要留出可见高度，不能被播放卡挤没；</li>
 *   <li>台标不能占掉太多高度。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
// v1.9.5 起版面按窗口高度分：h480dp-land 才是车机两栏版，
// 手机竖屏（h780dp-port）落回 layout/ 的手机单列版。
@Config(sdk = 28, qualifiers = "w360dp-h780dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhoneScreenTest {

    private static final int W = 1080;
    private static final int H = 2340;
    private static final float DENSITY = 3f; // xxhdpi

    private MainActivity build() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View root = a.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        return a;
    }

    private static Rect rectOf(View v) {
        Rect r = new Rect();
        v.getGlobalVisibleRect(r);
        return r;
    }

    @Test
    public void phone_uses_single_column_layout() {
        MainActivity a = build();
        View bar = a.findViewById(R.id.panel_left);
        ListView list = a.findViewById(R.id.list_stations);
        Rect l = rectOf(bar);
        Rect r = rectOf(list);

        // 单列：播放控制与列表纵向排列。
        // v1.8.3 起播放控制沉到底部（迷你播放条），所以列表在条的上方 ——
        // 旧版是「顶部大播放卡 + 下方列表」，方向反了，但「不并排」这一点没变。
        assertTrue("播放条应占满宽度（单列），实际宽 " + l.width() + "px / 屏宽 " + W,
                l.width() >= W * 0.9);
        assertTrue("列表应在播放条上方：列表 bottom=" + r.bottom + "，条 top=" + l.top,
                r.bottom <= l.top + 2);
        assertTrue("列表宽度应接近满屏，实际 " + r.width(), r.width() >= W * 0.9);
    }

    @Test
    public void controls_are_inside_screen_and_big_enough() {
        MainActivity a = build();
        for (int id : new int[]{R.id.btn_prev, R.id.btn_play, R.id.btn_next}) {
            View b = a.findViewById(id);
            Rect r = rectOf(b);
            assertTrue("按钮 " + a.getResources().getResourceEntryName(id)
                    + " 高度应 ≥ 48dp，实际 " + r.height() + "px", r.height() >= 48 * DENSITY);
            assertTrue("按钮应完全在屏幕内，bottom=" + r.bottom + " / " + H, r.bottom <= H);
            assertTrue("按钮宽度不能为 0", r.width() > 0);
        }
    }

    @Test
    public void list_keeps_usable_height() {
        MainActivity a = build();
        ListView list = a.findViewById(R.id.list_stations);
        Rect r = rectOf(list);
        int minRows = 3;
        int rowPx = PhoneLayout.rowHeightDp(360, 780) * (int) DENSITY;
        assertTrue("列表区至少能看到 " + minRows + " 行，实际高度 " + r.height()
                + "px，行高 " + rowPx + "px", r.height() >= rowPx * minRows);
    }

    @Test
    public void logo_is_mini_sized_on_play_bar() {
        MainActivity a = build();
        Rect logo = rectOf(a.findViewById(R.id.logo_wrap));
        Rect bar = rectOf(a.findViewById(R.id.panel_left));
        int logoDp = Math.round(logo.height() / DENSITY);

        // v1.8.3 起手机是底部迷你播放条，台标固定 44dp —— 不再按短边算 118dp。
        // 这里同时钉住「哪套版面」的识别：PhoneLayout.isMiniBar 必须认出来是迷你条，
        // 否则 apply() 会把 44dp 的台标重新撑成 118dp，条直接被顶爆。
        assertEquals("迷你播放条台标应固定 44dp，实际 " + logoDp + "dp",
                PhoneLayout.miniLogoSizeDp(), logoDp);
        assertTrue("台标不能让播放条超过屏高的 40%（否则列表就被挤压了），条高 "
                        + bar.height() + "px / 屏高 " + H,
                bar.height() < H * 0.4);
    }

    @Test
    public void list_row_height_matches_phone_spec() {
        MainActivity a = build();
        List<RadioCnApi.Station> data = new ArrayList<>();
        for (String t : new String[]{"四川交通广播", "四川新闻广播", "四川城市之音"}) {
            RadioCnApi.Station s = new RadioCnApi.Station();
            s.title = t;
            s.subtitle = "直播中";
            s.provinceName = "四川";
            s.contentId = t;
            data.add(s);
        }
        try {
            java.lang.reflect.Method show = MainActivity.class
                    .getDeclaredMethod("showStations", List.class);
            show.setAccessible(true);
            show.invoke(a, data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ListView list = a.findViewById(R.id.list_stations);
        View root = a.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertTrue("列表应有子项", list.getChildCount() > 0);
        View row = list.getChildAt(0);
        assertNotNull(row);
        int expect = PhoneLayout.rowHeightDp(360, 780) * (int) DENSITY;
        assertEquals("手机行高应为 " + expect + "px", expect, row.getLayoutParams().height);
    }

    @Test
    public void capture_phone_screenshot() throws Exception {
        MainActivity a = build();
        List<RadioCnApi.Station> data = new ArrayList<>();
        String[] titles = {
                "四川交通广播", "四川新闻广播", "四川综合广播", "四川城市之音",
                "四川岷江音乐频率", "四川民族广播", "中国交通广播（四川）",
                "成都交通文艺广播", "成都经济广播", "成都潮台88.7"
        };
        for (String t : titles) {
            RadioCnApi.Station s = new RadioCnApi.Station();
            s.title = t;
            s.subtitle = "直播中";
            s.provinceName = "四川";
            s.contentId = String.valueOf(t.hashCode());
            data.add(s);
        }
        // 让截图里同时出现「已收藏（实心橙）」和「未收藏（空心灰）」两种状态，
        // 否则只看截图永远验证不到已收藏态的样子，而那正是最容易做错的部分。
        FavoriteStore.remove(a, data.get(0).contentId);
        FavoriteStore.remove(a, data.get(1).contentId);
        FavoriteStore.add(a, data.get(1));
        try {
            java.lang.reflect.Method show = MainActivity.class
                    .getDeclaredMethod("showStations", List.class);
            show.setAccessible(true);
            show.invoke(a, data);
            java.lang.reflect.Method info = MainActivity.class
                    .getDeclaredMethod("showStationInfo", RadioCnApi.Station.class);
            info.setAccessible(true);
            // 让正在播的那台也是已收藏的，这样播放条上能看到实心橙心形。
            // 必须同时设 playingOverrideForTest：单测里播放器起不来，
            // player.current() 恒为 null，不设的话播放条上永远是空心（截图验证不到已收藏态）。
            a.playingOverrideForTest = data.get(1);
            info.invoke(a, data.get(1));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ((android.widget.TextView) a.findViewById(R.id.tv_region)).setText("四川 · 宜宾 ▾");

        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                W, H, android.graphics.Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(bmp));
        java.io.File out = new java.io.File("build/phone-render-1080x2340.png");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
        }
        assertTrue("截图应生成", out.exists() && out.length() > 5000);

        // 收尾：清掉为截图造的收藏数据。
        // 不清的话会污染同一个 JVM 里的其他测试类 —— Robolectric 复用应用 Context，
        // SharedPreferences 是跨类共享的。实测 ListFollowsPlaybackTest 会因为
        // 「收藏视图里只有这几台」而把 30 行列表算成 5 行，行号断言全崩。
        // 这类跨类污染排查起来很费劲（单跑绿、合跑红），必须当场清掉。
        FavoriteStore.remove(a, data.get(0).contentId);
        FavoriteStore.remove(a, data.get(1).contentId);
    }
}
