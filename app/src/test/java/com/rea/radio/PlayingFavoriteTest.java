package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 「正在播放的那台也能收藏」——车机与手机两套版面都要有，且行为一致（v1.8.3）。
 *
 * <p>为什么这个测试必须存在：两套布局里 {@code btn_fav_playing} 的<b>类型不同</b>——
 * <b>v1.8.3</b> 时两套布局里 {@code btn_fav_playing} 的类型不同（车机是 {@code TextView}，
 * 手机是 {@code FrameLayout}），靠 {@code instanceof} 分派；
 * <b>v1.8.4</b> 起统一为「{@code FrameLayout} 按钮 + {@code ImageView} 心形」，
 * 分派逻辑已删除。但两套版面仍各跑一遍 —— 防止将来又有人只改一边。
 *
 * <p>收藏状态现在看按钮的 {@code selected}，而不是文字内容：
 * 心形的颜色由 {@code res/color/fav_icon_tint.xml} 按 selected 切换，
 * 所以这里额外断言 {@code duplicateParentState} 开启 ——
 * 少了它，按钮 selected 变了但图标颜色不变，用户看不出收藏成功。
 *
 * <p>还要守住一个更隐蔽的点：收藏星的状态必须跟着<b>当前在播的台</b>走。
 * 切台之后如果星还亮着上一台的状态，用户会以为这台已经收藏过了。
 * 播放器在单测里起不来（要真起 Media3 + 网络），所以走
 * {@code MainActivity.playingOverrideForTest} 这个测试钩子喂一个「假装在播」的台。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlayingFavoriteTest {

    private static RadioCnApi.Station station(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        s.subtitle = "直播中";
        return s;
    }

    private static void idle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
    }

    // ------------------------------------------------------- 结构：两套版面都有

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    public void car_play_card_has_favorite_button() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View btn = a.findViewById(R.id.btn_fav_playing);
        assertNotNull("车机版播放卡上必须有收藏按钮", btn);
        // v1.8.4 起两套布局统一：按钮是 FrameLayout，心形是里面的 ImageView
        ImageView heart = a.findViewById(R.id.iv_fav_playing);
        assertNotNull("车机版收藏按钮里必须有心形图标", heart);
        assertTrue("收藏按钮宽 ≥ 48dp 才点得准，实际 "
                + btn.getLayoutParams().width, btn.getLayoutParams().width >= 48);
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    public void phone_play_bar_has_favorite_button() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View btn = a.findViewById(R.id.btn_fav_playing);
        assertNotNull("手机版播放条上必须有收藏按钮", btn);
        assertNotNull("手机版收藏按钮里必须有同一个心形图标",
                a.findViewById(R.id.iv_fav_playing));
        assertTrue("收藏按钮宽 ≥ 48dp 才点得准，实际 "
                + btn.getLayoutParams().width, btn.getLayoutParams().width >= 48);
    }

    // ------------------------------------------------------- 行为：星跟着在播台走

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    public void star_lights_up_only_when_the_playing_station_is_favorited() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View btn = a.findViewById(R.id.btn_fav_playing);
        ImageView heart = a.findViewById(R.id.iv_fav_playing);
        RadioCnApi.Station s = station("playing-fav-1", "测试在播台");
        FavoriteStore.remove(a, s.contentId);

        a.playingOverrideForTest = s;

        // 没收藏 → 空心星
        a.refreshPlayingFavoriteForTest();
        assertFalse("没收藏时按钮不该是 selected", btn.isSelected());
        assertEquals("收藏当前电台", btn.getContentDescription().toString());
        assertTrue("心形必须跟随按钮状态（duplicateParentState），否则颜色不会变",
                heart.isDuplicateParentStateEnabled());

        // 收藏了 → 实心星
        FavoriteStore.add(a, s);
        a.refreshPlayingFavoriteForTest();
        assertTrue("收藏后按钮该是 selected（心形会变橙）", btn.isSelected());
        assertEquals("取消收藏当前电台", btn.getContentDescription().toString());

        // 取消 → 回到空心
        FavoriteStore.remove(a, s.contentId);
        a.refreshPlayingFavoriteForTest();
        assertFalse("取消收藏后星该灭", btn.isSelected());

        FavoriteStore.remove(a, s.contentId);
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    public void star_follows_the_new_station_after_switching() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View btn = a.findViewById(R.id.btn_fav_playing);
        RadioCnApi.Station fav = station("playing-fav-2", "已收藏的台");
        RadioCnApi.Station notFav = station("playing-fav-3", "没收藏的台");
        FavoriteStore.remove(a, fav.contentId);
        FavoriteStore.remove(a, notFav.contentId);
        FavoriteStore.add(a, fav);

        // 在播的是「已收藏的台」→ 星亮
        a.playingOverrideForTest = fav;
        a.refreshPlayingFavoriteForTest();
        assertTrue("在播已收藏的台，星该亮", btn.isSelected());

        // 切到「没收藏的台」→ 星必须灭。
        // 这条是关键：如果 refreshPlayingFavorite 用了缓存的旧台，星会一直亮着骗人。
        a.playingOverrideForTest = notFav;
        a.refreshPlayingFavoriteForTest();
        assertFalse("切到没收藏的台后星必须灭（否则就是在骗用户）", btn.isSelected());

        FavoriteStore.remove(a, fav.contentId);
        FavoriteStore.remove(a, notFav.contentId);
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    public void phone_star_also_follows_playing_station() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View btn = a.findViewById(R.id.btn_fav_playing);
        ImageView heart = a.findViewById(R.id.iv_fav_playing);
        RadioCnApi.Station s = station("playing-fav-4", "手机在播台");
        FavoriteStore.remove(a, s.contentId);

        a.playingOverrideForTest = s;
        a.refreshPlayingFavoriteForTest();
        assertFalse("手机版没收藏时不该 selected", btn.isSelected());

        FavoriteStore.add(a, s);
        a.refreshPlayingFavoriteForTest();
        assertTrue("手机版收藏后按钮也该是 selected（心形变橙）", btn.isSelected());
        assertTrue("手机版心形也必须跟随按钮状态", heart.isDuplicateParentStateEnabled());

        FavoriteStore.remove(a, s.contentId);
    }

    /**
     * 收藏心形必须真的会变色 —— 直接验色值，而不是只验「设了 duplicateParentState」。
     *
     * <p>这是收藏功能能不能被用户看出来的根本依据：按钮 selected 只是内部状态，
     * 用户看到的只有颜色。如果 tint 写错（比如两个状态都指向同一个颜色），
     * 收藏成功与否在界面上完全没区别，而这在结构断言里是查不出来的。
     */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    public void favorite_icon_tint_switches_between_two_distinct_colors() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        android.content.res.ColorStateList tint =
                a.getResources().getColorStateList(R.color.fav_icon_tint, a.getTheme());

        int unfav = tint.getColorForState(new int[]{}, 0xFF000000);
        int fav = tint.getColorForState(new int[]{android.R.attr.state_selected}, 0xFF000000);

        assertEquals("未收藏应为 text_hint", a.getResources().getColor(R.color.text_hint), unfav);
        assertEquals("已收藏应为 accent", a.getResources().getColor(R.color.accent), fav);
        assertTrue("两种状态必须是不同颜色，否则用户看不出收藏成功", fav != unfav);
    }

    /**
     * 收藏图标必须<b>形状 + 颜色</b>双变：空心 → 实心、灰 → 橙。
     *
     * <p>守的是一个真实做错过的设计：v1.8.4 第一版用「同一张实心心形 + 灰/橙 tint」，
     * 结果列表里 9 个灰色实心心形连成一列，比台名还抢眼，整屏像「全都收藏了」。
     * 改成两态两张图之后，这条用例确保换图逻辑真的落地了。
     */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    public void favorite_icon_switches_shape_between_states() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        android.widget.ImageView heart = a.findViewById(R.id.iv_fav_playing);
        assertNotNull("按钮里必须有心形", heart);

        RadioCnApi.Station s = station("playing-fav-shape", "形状测试台");
        FavoriteStore.remove(a, s.contentId);
        a.playingOverrideForTest = s;

        a.refreshPlayingFavoriteForTest();
        assertFalse("未收藏应是空心（selected=false）", heart.isSelected());
        assertEquals("未收藏时无障碍描述是「收藏」",
                "收藏当前电台", a.findViewById(R.id.btn_fav_playing)
                        .getContentDescription().toString());

        FavoriteStore.add(a, s);
        a.refreshPlayingFavoriteForTest();
        assertTrue("收藏后应是实心（selected=true）", heart.isSelected());
        assertEquals("收藏后无障碍描述应变成「取消收藏」",
                "取消收藏当前电台", a.findViewById(R.id.btn_fav_playing)
                        .getContentDescription().toString());

        FavoriteStore.remove(a, s.contentId);
    }

    /**
     * 车机版的已收藏心形，颜色必须与底座描边<b>拉开明度差</b>。
     *
     * <p>守的是 v1.8.4 发布后从截图里发现的真实问题：车机收藏键是「圆底座 + 心形」，
     * 已收藏时底座描边与心形同用 {@code accent} —— 同色同心，心形的两个凹陷被橙圈吞掉，
     * 整个按钮远看就是一个橙点，完全认不出是心形。而这个缺陷<b>所有结构断言都查不出来</b>：
     * id 对、颜色确实变了、形状确实换了，只是换完之后「看不清」。
     *
     * <p><b>v2.0 改了方向</b>：界面从深色换成浅色后，底座变成「浅橙底 + 橙描边」，
     * 心形改用更深的 {@code accent_deep}（#C2410C）。也就是说层次从
     * 「深橙环 / 深底 / 浅橙心」翻成「橙环 / 浅橙底 / 深橙心」。
     * 约束的物理含义没变 —— <b>两者必须有足够的明度差</b>，方向随主题而定，
     * 所以这里断言的是「差值」，不再是「心形更亮」。
     */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    public void car_favorite_heart_color_differs_from_button_ring() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        android.content.res.ColorStateList tint =
                a.getResources().getColorStateList(R.color.fav_icon_tint_car, a.getTheme());
        int heartFav = tint.getColorForState(new int[]{android.R.attr.state_selected}, 0xFF000000);
        int ring = a.getResources().getColor(R.color.accent, a.getTheme());

        assertTrue("车机心形颜色不能等于描边色，否则心形糊在橙圈里认不出来",
                heartFav != ring);
        // 30 是实测留出的安全余量：accent(#FF6600) 与 accent_deep(#C2410C) 的
        // 感知亮度差约 39，车机低分辨率 + 屏幕反光下仍能分辨轮廓。
        assertTrue("车机心形与描边必须有明度差（实际差 "
                        + Math.abs(brightness(heartFav) - brightness(ring)) + "）",
                Math.abs(brightness(heartFav) - brightness(ring)) >= 30);
    }

    /** 感知亮度（0..255），用绿色分量加权，贴近人眼观感。 */
    private static int brightness(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    public void tapping_star_on_play_card_toggles_favorite() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        RadioCnApi.Station s = station("playing-fav-5", "点星测试台");
        FavoriteStore.remove(a, s.contentId);
        a.playingOverrideForTest = s;
        a.refreshPlayingFavoriteForTest();

        assertFalse("起点：没收藏", FavoriteStore.contains(a, s.contentId));

        a.toggleFavoriteOfPlayingForTest();
        idle();
        assertTrue("点一下应收藏", FavoriteStore.contains(a, s.contentId));

        a.toggleFavoriteOfPlayingForTest();
        idle();
        assertFalse("再点一下应取消收藏", FavoriteStore.contains(a, s.contentId));

        FavoriteStore.remove(a, s.contentId);
    }

    /**
     * 播放/暂停键必须是图标，且两套版面都指向同一个 ImageView。
     *
     * <p>v1.8.4 把「播放 / 暂停」三个字换成了 ▶ / ❚❚ 图形。
     * 这里只验证结构（是 ImageView、id 对得上、尺寸合理），
     * 「播放中显示暂停图标」这条要靠 {@code MainActivity} 里的回调触发，
     * 单测起不来 Media3，所以那部分由 onStateChanged 的代码审阅保证。
     */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    public void car_play_button_uses_icon_not_text() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View icon = a.findViewById(R.id.btn_play_label);
        assertNotNull("车机播放键里必须有图标", icon);
        assertTrue("应当是 ImageView（文字会因字体缺字变方框）", icon instanceof ImageView);
        int px = icon.getLayoutParams().width;
        assertTrue("图标不能太小（方向盘远看要认得出），实际 " + px + "px", px >= 24);
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    public void phone_play_button_uses_icon_not_text() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        View icon = a.findViewById(R.id.btn_play_label);
        assertNotNull("手机播放键里必须有图标", icon);
        assertTrue("应当是 ImageView", icon instanceof ImageView);

        // 四个控制键里都应有图标，不能残留文字按钮
        for (int id : new int[]{R.id.btn_prev, R.id.btn_play, R.id.btn_next, R.id.btn_fav_playing}) {
            ViewGroup box = a.findViewById(id);
            String name = a.getResources().getResourceEntryName(id);
            assertNotNull(name + " 里必须有图标（不能还是文字）", findIcon(box));
        }
    }

    /** 在按钮容器里找到那张 ImageView（四个控制键结构相同）。 */
    private static ImageView findIcon(android.view.ViewGroup box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            if (box.getChildAt(i) instanceof ImageView) return (ImageView) box.getChildAt(i);
        }
        return null;
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    public void tapping_star_without_playing_station_does_not_crash() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        // 播放器没起、也没喂 override → 没有在播的台。
        // 此时点星只该提示，绝不能 NPE 崩掉（车机上刚开机用户就可能乱点）
        a.toggleFavoriteOfPlayingForTest();
        idle();
        View btn = a.findViewById(R.id.btn_fav_playing);
        assertFalse("没有在播台时星不该被点亮", btn.isSelected());
    }
}
