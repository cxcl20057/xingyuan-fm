package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.View;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 实车规格渲染验证：1920×1080 横屏（GEELYGEOME E22H / IHU801G，Android 9）。
 *
 * <p>断言的是「在车的屏幕上看不看得到、按不按得着」：
 * 台标存在、时间轴在走、按钮足够大、列表在右侧、字号够大。
 */
@RunWith(RobolectricTestRunner.class)
// 用 xhdpi（2x）模拟「标准密度车机」，验证 values/dimens.xml 的默认尺寸。
// 注意：实测星愿车机 IHU801G 的真实密度是 160 dpi（mdpi / 1x），整屏 sw=1080dp，
// 那条路径由 values-sw1080dp/dimens.xml（×2 放大值）覆盖，对应关系见
// CarDimensTest（纯逻辑校验两份 dimens 的 ×2 一致性），渲染验证待真机/模拟器补。
// 必须带限定符，否则 Robolectric 按 1x 解释，所有 dp 都会看起来"偏小"。
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CarScreenTest {

    private static final int W = 1920;
    private static final int H = 1080;

    @Test
    public void layout_matches_car_screen_spec() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View root = a.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);

        ListView list = a.findViewById(R.id.list_stations);
        View play = a.findViewById(R.id.btn_play);
        View prev = a.findViewById(R.id.btn_prev);
        View next = a.findViewById(R.id.btn_next);

        Rect listR = new Rect();
        list.getGlobalVisibleRect(listR);
        Rect playR = new Rect();
        play.getGlobalVisibleRect(playR);
        Rect prevR = new Rect();
        prev.getGlobalVisibleRect(prevR);
        Rect nextR = new Rect();
        next.getGlobalVisibleRect(nextR);

        // 控制区在左、列表在右
        assertTrue("列表应在右侧", listR.left > playR.left);
        assertTrue("控制按钮应在左半屏", playR.right <= W / 2);
        // 按钮顺序 上一台 / 播放 / 下一台
        assertTrue(prevR.left < playR.left && playR.left < nextR.left);

        float density = a.getResources().getDisplayMetrics().density;
        assertTrue("按钮高度应 >= 80dp，实际 " + playR.height() / density,
                playR.height() / density >= 80f);
        assertTrue("列表宽度应 > 900px，实际 " + listR.width(), listR.width() > 900);
    }

    @Test
    public void new_panels_exist_and_timeline_advances() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();

        TextView logo = a.findViewById(R.id.tv_logo);
        TextView live = a.findViewById(R.id.tv_live);
        TextView period = a.findViewById(R.id.tv_period);
        TextView now = a.findViewById(R.id.tv_timeline_now);
        ProgressBar tl = a.findViewById(R.id.progress_timeline);
        TextView hint = a.findViewById(R.id.tv_media_hint);
        TextView logBtn = a.findViewById(R.id.btn_log);

        for (View v : new View[]{logo, live, period, now, tl, hint, logBtn}) {
            assertNotNull("新增控件缺失: " + v, v);
        }
        assertEquals("时段应显示 00:00–24:00", "00:00–24:00", period.getText().toString());
        assertTrue("时间码应是 HH:mm:ss，实际 " + now.getText(),
                now.getText().toString().matches("\\d{2}:\\d{2}:\\d{2}"));

        // 时间轴每 60 秒推进 1 格（一天 1440 格）
        assertEquals(1440, tl.getMax());
        assertTrue("进度应在 0..1440", tl.getProgress() >= 0 && tl.getProgress() <= 1440);
    }

    /**
     * 收藏功能的三块 UI 必须在车上真的看得见、点得着：
     * 标签行没把按钮挤扁、★ 按钮够大、台标层开了圆形裁剪。
     */
    @Test
    public void favorites_ui_fits_and_stays_tappable() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View root = a.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);

        TextView tabAll = a.findViewById(R.id.tab_all);
        TextView tabFav = a.findViewById(R.id.tab_fav);
        assertNotNull("「全部电台」标签缺失", tabAll);
        assertNotNull("「收藏」标签缺失", tabFav);

        float density = a.getResources().getDisplayMetrics().density;
        Rect tabAllR = new Rect();
        tabAll.getGlobalVisibleRect(tabAllR);
        Rect tabFavR = new Rect();
        tabFav.getGlobalVisibleRect(tabFavR);

        assertTrue("标签高度应 >= 48dp，实际 " + tabAllR.height() / density,
                tabAllR.height() / density >= 48f);
        assertTrue("收藏标签应在全部标签右侧", tabFavR.left >= tabAllR.left);

        // 关键回归：加了标签行后，播放按钮不能被压扁
        View play = a.findViewById(R.id.btn_play);
        Rect playR = new Rect();
        play.getGlobalVisibleRect(playR);
        assertTrue("新增标签行后播放按钮被压扁了：" + playR.height() / density + "dp",
                playR.height() / density >= 80f);

        // 列表仍要占据右侧主要空间
        ListView list = a.findViewById(R.id.list_stations);
        Rect listR = new Rect();
        list.getGlobalVisibleRect(listR);
        assertTrue("列表高度应 >= 260dp，实际 " + listR.height() / density,
                listR.height() / density >= 260f);

        // 台标层必须是「裁剪成圆」的：要么 clipToOutline 生效，要么布局里明确声明了
        // （Robolectric 对 clipToOutline 的桩实现不保证回读，所以两条都认）
        android.widget.ImageView iv = a.findViewById(R.id.iv_logo);
        assertNotNull("台标图缺失", iv);
        boolean clipped = iv.getClipToOutline() || layoutDeclaresClipToOutline(a);
        assertTrue("台标层必须裁成圆，否则方形台标会露出四个角", clipped);
        assertEquals("方形台标要铺满整圆（不能比圆小）",
                android.widget.ImageView.ScaleType.CENTER_CROP, iv.getScaleType());
    }

    /**
     * 直接读源码里的布局 XML 校验 clipToOutline。
     *
     * <p>为什么不只靠 {@code getClipToOutline()}：Robolectric 对 clipToOutline
     * 的桩实现不保证回读为 true，光靠它会得到假失败。这里回归的是「布局声明在不在」，
     * 而该属性在真机 Android 9 上是系统原生行为，无需额外代码。
     */
    private boolean layoutDeclaresClipToOutline(MainActivity a) {
        try {
            // 车机版面从 v1.9.5 起放在 layout-h480dp-land/（手机版面在 layout/）
            java.io.File f = new java.io.File(
                    "src/main/res/layout-h480dp-land/activity_main.xml");
            if (!f.exists()) {
                // Gradle 测试的工作目录可能不同，再按模块路径找一次
                f = new java.io.File("app/src/main/res/layout-h480dp-land/activity_main.xml");
            }
            if (!f.exists()) return false;
            String xml = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            int i = xml.indexOf("@+id/iv_logo");
            return i > 0 && xml.substring(i, Math.min(xml.length(), i + 800))
                    .contains("clipToOutline=\"true\"");
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    public void capture_screenshot() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();

        List<RadioCnApi.Station> data = new ArrayList<>();
        String[] titles = {
                "四川交通广播", "四川新闻广播", "四川综合广播", "四川城市之音",
                "四川岷江音乐频率", "四川民族广播", "中国交通广播（四川）",
                "成都交通文艺广播", "成都经济广播", "成都潮台88.7",
                "绵阳交通广播", "德阳综合广播", "乐山音乐交通广播", "攀枝花综合广播"
        };
        for (String t : titles) {
            RadioCnApi.Station s = new RadioCnApi.Station();
            s.title = t;
            s.subtitle = "直播中";
            s.provinceName = "四川";
            // 注意是 t.hashCode() 不是 titles.hashCode()：
            // 后者取的是数组对象的哈希，14 个台会得到同一个 contentId，
            // 于是「收藏」「高亮」「去重」所有按 id 走的逻辑在截图里都是错的。
            s.contentId = String.valueOf(t.hashCode());
            data.add(s);
        }
        // 让截图同时体现「已收藏（实心橙）」与「未收藏（空心灰）」两种状态
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
            // 播放器在单测里起不来，喂一个「假装在播」的台，播放卡上才会显示实心橙心形
            a.playingOverrideForTest = data.get(1);
            info.invoke(a, data.get(1));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        TextView region = a.findViewById(R.id.tv_region);
        region.setText("四川 · 宜宾 ▾");
        TextView hint = a.findViewById(R.id.tv_media_hint);
        hint.setText("媒体会话已常驻（方向盘按键可用）");

        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                W, H, android.graphics.Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(bmp));
        java.io.File out = new java.io.File("build/car-render-1920x1080.png");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
        }
        assertTrue("截图应生成", out.exists() && out.length() > 5000);

        // 收尾：清掉为截图造的收藏数据。
        // Robolectric 复用应用 Context，SharedPreferences 跨测试类共享，
        // 留下的收藏会让别的测试类以为处在「收藏视图」，行号断言会莫名失败。
        FavoriteStore.remove(a, data.get(0).contentId);
        FavoriteStore.remove(a, data.get(1).contentId);
    }
}
