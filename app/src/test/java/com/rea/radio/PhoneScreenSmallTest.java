package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

/**
 * 小屏手机（720×1280 @xhdpi ⇒ 360×640dp）回归。
 *
 * <p>这是最容易出问题的尺寸：可用高度只有 640dp，
 * 「顶栏 + 播放卡 + 标签 + 列表」很容易超一屏，超了就会把按钮顶出屏幕。
 * 所以这里重点断言「三个按钮和至少 2 行列表都在屏幕内」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h640dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhoneScreenSmallTest {

    private static final int W = 720;
    private static final int H = 1280;
    private static final int DENSITY = 2;

    private MainActivity build() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View root = a.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        return a;
    }

    private static Rect rectOf(View v) {
        Rect r = new Rect();
        v.getGlobalVisibleRect(r);
        return r;
    }

    @Test
    public void everything_fits_on_small_phone() {
        MainActivity a = build();

        for (int id : new int[]{R.id.btn_prev, R.id.btn_play, R.id.btn_next}) {
            Rect r = rectOf(a.findViewById(id));
            assertTrue("小屏上按钮不能超出屏幕：bottom=" + r.bottom + " / " + H,
                    r.bottom <= H);
            assertTrue("按钮高度不能低于 48dp，实际 " + (r.height() / DENSITY) + "dp",
                    r.height() >= 48 * DENSITY);
        }

        ListView list = a.findViewById(R.id.list_stations);
        Rect listR = rectOf(list);
        int rowPx = PhoneLayout.rowHeightDp(360, 640) * DENSITY;
        assertTrue("小屏列表至少露出 2 行，实际 " + (listR.height() / DENSITY) + "dp",
                listR.height() >= rowPx * 2);
    }

    @Test
    public void small_phone_uses_shorter_rows() {
        // 短边 360dp < 380dp ⇒ 56dp 行高，比主流屏更紧凑
        assertEquals(56, PhoneLayout.rowHeightDp(360, 640));
    }

    @Test
    public void small_phone_logo_is_clamped_to_min() {
        // 360 * 0.33 = 118.8 → 119dp，高于下限 110dp，不必夹
        assertEquals(119, PhoneLayout.logoSizeDp(360, 640));
        assertFalse("小屏也不该用两栏", PhoneLayout.useTwoColumn(360, 640));
    }

    @Test
    public void capture_small_phone_screenshot() throws Exception {
        MainActivity a = build();
        java.util.List<RadioCnApi.Station> data = new java.util.ArrayList<>();
        for (String t : new String[]{"四川交通广播", "四川新闻广播", "四川城市之音",
                "四川岷江音乐频率", "成都经济广播"}) {
            RadioCnApi.Station s = new RadioCnApi.Station();
            s.title = t;
            s.subtitle = "直播中";
            s.provinceName = "四川";
            s.contentId = t;
            data.add(s);
        }
        try {
            java.lang.reflect.Method show = MainActivity.class
                    .getDeclaredMethod("showStations", java.util.List.class);
            show.setAccessible(true);
            show.invoke(a, data);
            java.lang.reflect.Method info = MainActivity.class
                    .getDeclaredMethod("showStationInfo", RadioCnApi.Station.class);
            info.setAccessible(true);
            info.invoke(a, data.get(0));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                W, H, android.graphics.Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(bmp));
        java.io.File out = new java.io.File("build/phone-small-720x1280.png");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
        }
        assertTrue(out.exists() && out.length() > 5000);
    }
}
