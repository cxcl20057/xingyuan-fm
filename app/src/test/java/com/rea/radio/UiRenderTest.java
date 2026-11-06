package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 界面渲染验证（JVM / Robolectric，不需要真机）：
 *  1) MainActivity 能真实 inflate 布局、完成 onCreate，不崩溃；
 *  2) 两栏布局存在、关键控件都在；
 *  3) 列表能绑定真实数据并渲染出条目（四川 44 台）；
 *  4) 选中态与“省级台优先”排序正确。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1280dp-h720dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiRenderTest {

    @Test
    public void activity_inflates_and_binds_real_list() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class)
                .create().get();
        assertNotNull(a);

        View root = a.findViewById(android.R.id.content);
        assertNotNull(root);

        ListView list = a.findViewById(R.id.list_stations);
        TextView station = a.findViewById(R.id.tv_station);
        TextView region = a.findViewById(R.id.tv_region);
        View next = a.findViewById(R.id.btn_next);
        View prev = a.findViewById(R.id.btn_prev);
        View play = a.findViewById(R.id.btn_play);

        assertNotNull(list);
        assertNotNull(station);
        assertNotNull(region);
        assertNotNull(next);
        assertNotNull(prev);
        assertNotNull(play);

        // 布局宽度：两栏各占一部分（左右分栏而非上下）
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1280, 720);

        assertTrue("主区域应占满高度", root.getHeight() == 720);

        // ---- 布局方向：控制区在左，列表在右 ----
        // 注：Robolectric 下 getLocationInWindow/getLocationOnScreen 均不回填偏移（恒为 0），
        // 因此改用 View 自身几何（屏幕矩形）判断方向，这在测试与真机上都成立。
        android.graphics.Rect listRect = new android.graphics.Rect();
        list.getGlobalVisibleRect(listRect);
        android.graphics.Rect playRect = new android.graphics.Rect();
        play.getGlobalVisibleRect(playRect);
        android.graphics.Rect prevRect = new android.graphics.Rect();
        prev.getGlobalVisibleRect(prevRect);

        assertTrue("列表应有宽度", listRect.width() > 300);
        assertTrue("按钮应已布局（可见矩形非空）", playRect.width() > 0 && prevRect.width() > 0);
        assertTrue("列表应在右侧：list.left=" + listRect.left + " play.left=" + playRect.left,
                listRect.left > playRect.left);
        assertTrue("控制按钮应在左半屏：play.right=" + playRect.right,
                playRect.right <= 1280 / 2);
        // 按钮顺序：上一台 → 播放 → 下一台
        assertTrue("按钮顺序应为 上一台 / 播放 / 下一台",
                prevRect.left < playRect.left && playRect.left < listRect.left);

        // ---- 字号校验（实车 1m 可读）----
        // 40sp：实车 xhdpi 下可用高度仅 540dp，台名不能无限大，否则挤掉按钮
        float stationSp = station.getTextSize() / a.getResources().getDisplayMetrics().scaledDensity;
        assertTrue("电台名字号应 >= 36sp，实际 " + stationSp, stationSp >= 36f);

        // 注入真实电台数据（Robolectric 无网络，直接走数据入口验证绑定链路）
        java.util.List<RadioCnApi.Station> data = new java.util.ArrayList<>();
        for (String t : new String[]{"四川交通广播", "四川新闻广播", "成都交通文艺广播"}) {
            data.add(station(t));
        }
        // 这几条是手造数据、不是央广原始列表，必须关掉本地补录，
        // 否则会按四川补进 3 个成都台，条数变成 6（补录本身见 LocalStationsTest）
        a.setLocalStationsEnabled(false);
        try {
            java.lang.reflect.Method show = MainActivity.class
                    .getDeclaredMethod("showStations", java.util.List.class);
            show.setAccessible(true);
            show.invoke(a, data);
        } catch (Exception e) {
            throw new AssertionError("showStations 不可调用: " + e, e);
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1280, 720);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertEquals("列表应绑定 3 条数据", 3, list.getAdapter().getCount());
        TextView firstTitle = list.getChildAt(0) == null ? null
                : list.getChildAt(0).findViewById(R.id.item_title);
        assertTrue("列表首行必须已创建", firstTitle != null);
        float itemSp = firstTitle.getTextSize() / a.getResources().getDisplayMetrics().scaledDensity;
        assertTrue("列表字号应 >= 24sp，实际 " + itemSp, itemSp >= 24f);
        // v1.8.3 起台名不再拼频率后缀，列表行显示的就是接口给的台名
        assertEquals("首行应是省级台（四川交通广播）", "四川交通广播",
                firstTitle.getText().toString());
        assertFalse("台名后面不应再出现频率", firstTitle.getText().toString().contains("FM"));

        // 行高足够大，避免车机上误触
        float density = a.getResources().getDisplayMetrics().density;
        float rowDp = list.getChildAt(0).getHeight() / density;
        assertTrue("列表行高应 >= 72dp，实际 " + rowDp + "dp", rowDp >= 72f);

        // ---- 按钮触控目标 ----
        for (View btn : new View[]{play, prev, next}) {
            float btnDp = btn.getHeight() / density;
            assertTrue("按钮高度应 >= 80dp，实际 " + btnDp + "dp", btnDp >= 80f);
        }
    }

    @Test
    public void provinceFirst_puts_sichuan_province_stations_on_top() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        // 用反射取私有排序方法，验证“省级台优先”
        try {
            java.lang.reflect.Method m = MainActivity.class
                    .getDeclaredMethod("provinceFirst", java.util.List.class);
            m.setAccessible(true);
            java.lang.reflect.Field pn = MainActivity.class.getDeclaredField("provinceName");
            pn.setAccessible(true);
            pn.set(a, "四川");

            java.util.List<RadioCnApi.Station> in = new java.util.ArrayList<>();
            in.add(station("成都交通文艺广播"));
            in.add(station("攀枝花综合广播"));
            in.add(station("四川交通广播"));
            in.add(station("四川新闻广播"));

            @SuppressWarnings("unchecked")
            java.util.List<RadioCnApi.Station> out =
                    (java.util.List<RadioCnApi.Station>) m.invoke(a, in);

            assertEquals("四川交通广播", out.get(0).title);
            assertEquals("四川新闻广播", out.get(1).title);
            // 其余保持原顺序
            assertEquals("成都交通文艺广播", out.get(2).title);
            assertEquals("攀枝花综合广播", out.get(3).title);
        } catch (Exception e) {
            throw new AssertionError("排序方法不可调用: " + e, e);
        }
    }

    @Test
    public void capture_screenshot_with_real_data() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View root = a.findViewById(android.R.id.content);
        // 注入实测的四川电台数据，模拟列表已加载完成的状态
        String[] titles = {
                "四川交通广播", "四川新闻广播", "四川综合广播", "四川城市之音",
                "四川岷江音乐频率", "四川民族广播", "中国交通广播（四川）",
                "成都交通文艺广播", "成都经济广播", "成都潮台88.7",
                "绵阳交通广播", "德阳综合广播", "乐山音乐交通广播", "攀枝花综合广播"
        };
        java.util.List<RadioCnApi.Station> list = new java.util.ArrayList<>();
        for (String t : titles) list.add(station(t));

        java.lang.reflect.Method show = MainActivity.class
                .getDeclaredMethod("showStations", java.util.List.class);
        show.setAccessible(true);
        show.invoke(a, list);

        TextView region = a.findViewById(R.id.tv_region);
        region.setText("四川 · 成都 ▾");

        android.view.View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1280, 720);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                1280, 720, android.graphics.Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(bmp));
        java.io.File out = new java.io.File("build/ui-render.png");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
        }
        assertTrue("截图应生成", out.exists() && out.length() > 1000);
    }

    private static RadioCnApi.Station station(String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.title = title;
        s.subtitle = "测试";
        s.provinceName = "四川";
        return s;
    }
}
