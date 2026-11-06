package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 滑动换台的方向判定（不需要真机，但要跑在 Robolectric 里）。
 *
 * <p>这里最容易出错的是「阈值」和「主方向优先」：
 * 手指斜着划一下，如果横竖都超过阈值就会误判方向；
 * 阈值给小了，车上颠一下手指抖就切台。
 *
 * <p>v1.6.3 起还要覆盖「起点在不在列表里」——这条判据靠真实的 View 层级验证，
 * 所以本类加了 Robolectric（否则 {@code new ListView(ctx)} 拿不到 Context）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SwipeStationHelperTest {

    private static final float MIN = 200f; // 像素

    @Test
    public void swipe_up_goes_next() {
        assertEquals("上滑应为下一台",
                SwipeStationHelper.Action.NEXT, SwipeStationHelper.decide(0, -MIN, MIN));
    }

    @Test
    public void swipe_down_goes_prev() {
        assertEquals("下滑应为上一台",
                SwipeStationHelper.Action.PREV, SwipeStationHelper.decide(0, MIN, MIN));
    }

    @Test
    public void swipe_left_goes_next() {
        assertEquals("左滑应为下一台",
                SwipeStationHelper.Action.NEXT, SwipeStationHelper.decide(-MIN, 0, MIN));
    }

    @Test
    public void swipe_right_goes_prev() {
        assertEquals("右滑应为上一台",
                SwipeStationHelper.Action.PREV, SwipeStationHelper.decide(MIN, 0, MIN));
    }

    /** 小于阈值一律不动 —— 否则手指轻微抖动就切台。 */
    @Test
    public void small_movement_does_nothing() {
        assertEquals(SwipeStationHelper.Action.NONE,
                SwipeStationHelper.decide(0, -MIN * 0.9f, MIN));
        assertEquals(SwipeStationHelper.Action.NONE,
                SwipeStationHelper.decide(MIN * 0.5f, MIN * 0.5f, MIN));
        assertEquals("原地没动", SwipeStationHelper.Action.NONE,
                SwipeStationHelper.decide(0, 0, MIN));
    }

    /** 斜着划：竖向位移更大时按竖向算。 */
    @Test
    public void diagonal_prefers_larger_axis() {
        assertEquals("竖多 → 上滑换下一台",
                SwipeStationHelper.Action.NEXT,
                SwipeStationHelper.decide(-MIN * 0.5f, -MIN * 1.2f, MIN));
        assertEquals("横多 → 左滑也换下一台",
                SwipeStationHelper.Action.NEXT,
                SwipeStationHelper.decide(-MIN * 1.2f, -MIN * 0.5f, MIN));
    }

    /** 两个方向都超过阈值且相等时，按竖向（上滑=下一台）判，行为必须可预测。 */
    @Test
    public void equal_axes_is_stable() {
        assertEquals(SwipeStationHelper.Action.NEXT,
                SwipeStationHelper.decide(-MIN, -MIN, MIN));
        assertEquals(SwipeStationHelper.Action.PREV,
                SwipeStationHelper.decide(MIN, MIN, MIN));
    }

    /** 只在上半区响应：比例常量必须小于一半多一点，列表区不能误触发。 */
    @Test
    public void active_area_is_upper_half() {
        assertTrue("上半区比例应在 0.5~0.7 之间，实际 "
                        + SwipeStationHelper.ACTIVE_HEIGHT_RATIO,
                SwipeStationHelper.ACTIVE_HEIGHT_RATIO >= 0.5f
                        && SwipeStationHelper.ACTIVE_HEIGHT_RATIO <= 0.7f);
        assertTrue("最小滑动距离不该过小（车机易抖）", SwipeStationHelper.MIN_SWIPE_DP >= 60f);
    }

    /** 阈值必须靠 dp 换算成像素，不能写死像素值，否则不同密度屏上手感天差地别。 */
    @Test
    public void threshold_scales_with_density() {
        float dp = 90f;
        float xhdpi = dp * 2f;   // 实车 @2x
        float mdpi = dp * 1f;
        assertFalse("1x 屏上 90dp 应该被判为有效滑动",
                SwipeStationHelper.decide(0, -xhdpi, xhdpi) == SwipeStationHelper.Action.NONE);
        assertFalse(SwipeStationHelper.decide(0, -mdpi, mdpi) == SwipeStationHelper.Action.NONE);
    }

    // ------------------------------------------------------------------
    // v1.6.3 回归：「手指在列表上滑，结果台被切走了」
    //
    // 实车原话：「列表在使用触摸进行滑动的时候，会导致上下一台切换」。
    // 根因是判定只看「命中的那一层控件可不可滚动」：手指落在列表行的文字/空白上时，
    // 命中的是行内的 TextView，它既不是 ListView 也不 clickable，
    // 于是被当成空白区，纵向位移一过阈值就切台。
    // ------------------------------------------------------------------

    /** 起点在滚动容器里时，纵向位移一律不切台（用户是在翻列表）。 */
    @Test
    public void vertical_swipe_inside_list_never_changes_station() {
        assertEquals("列表里上滑 = 往下翻列表，不是下一台",
                SwipeStationHelper.Action.NONE,
                SwipeStationHelper.decide(0, -MIN, MIN, true));
        assertEquals("列表里下滑 = 往上翻列表，不是上一台",
                SwipeStationHelper.Action.NONE,
                SwipeStationHelper.decide(0, MIN, MIN, true));
        assertEquals("斜着滑但竖向占优，也归滚动",
                SwipeStationHelper.Action.NONE,
                SwipeStationHelper.decide(-MIN * 0.4f, -MIN * 1.5f, MIN, true));
    }

    /**
     * 但列表里<b>横向</b>滑动仍然换台。
     *
     * <p>理由：列表行是竖排的，横向手势 ListView 根本不会消费，
     * 不会和滚动冲突；保留它让「在列表上左右滑换台」继续可用。
     */
    @Test
    public void horizontal_swipe_inside_list_still_works() {
        assertEquals("列表里左滑 = 下一台",
                SwipeStationHelper.Action.NEXT,
                SwipeStationHelper.decide(-MIN, 0, MIN, true));
        assertEquals("列表里右滑 = 上一台",
                SwipeStationHelper.Action.PREV,
                SwipeStationHelper.decide(MIN, 0, MIN, true));
    }

    /** 不在列表里时，纵向滑动照旧换台（这是播放区的主要手势，不能一起废掉）。 */
    @Test
    public void vertical_swipe_outside_list_still_changes_station() {
        assertEquals(SwipeStationHelper.Action.NEXT,
                SwipeStationHelper.decide(0, -MIN, MIN, false));
        assertEquals(SwipeStationHelper.Action.PREV,
                SwipeStationHelper.decide(0, MIN, MIN, false));
    }

    /**
     * 滚动容器判定必须沿祖先链走。
     *
     * <p>这是本次 bug 的核心：命中的是最里层的 TextView，它自己不可滚动，
     * 也不 clickable，但祖先里有 ListView —— 必须判成「在列表里」。
     */
    @Test
    public void scrollable_is_detected_through_ancestors() {
        android.content.Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        // 用 ScrollView 搭层级：ListView 是 AdapterView，addView 被 AOSP 直接禁止，
        // 但「沿祖先链找滚动容器」这条逻辑对二者完全一致。
        android.widget.ScrollView list = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        android.widget.TextView label = new android.widget.TextView(ctx);

        row.addView(label);
        list.addView(row);

        assertTrue("要沿祖先链认出它在滚动容器里",
                SwipeStationHelper.insideScrollable(label));
        assertTrue("行容器本身也要认出来", SwipeStationHelper.insideScrollable(row));
        assertTrue("滚动容器自己当然算", SwipeStationHelper.insideScrollable(list));

        // ListView 自身（不挂子 View）也必须被判为滚动容器
        assertTrue("ListView 自身必须是滚动容器",
                SwipeStationHelper.insideScrollable(new android.widget.ListView(ctx)));
    }

    /** 不在任何滚动容器里的普通 View：不能被误判成列表（否则滑动换台全废）。 */
    @Test
    public void plain_view_is_not_scrollable() {
        android.content.Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        android.widget.LinearLayout panel = new android.widget.LinearLayout(ctx);
        android.widget.TextView title = new android.widget.TextView(ctx);
        panel.addView(title);

        assertFalse("播放区不是滚动容器", SwipeStationHelper.insideScrollable(title));
        assertFalse("空引用也不能崩/误判", SwipeStationHelper.insideScrollable(null));
    }

    // ------------------------------------------------------------------
    // v1.9.11 回归：「慢慢滑列表会切一次台」
    //
    // 实车原话：「滑动播放列表的时候如果稍微长点一点时间没有从屏幕拿开手指，
    // 会触发一次下一曲或者上一曲」。快速甩一下不切（位移没到阈值），
    // 慢慢拖到 90dp 就切——根因是接收区只判纵向：车机右栏列表的纵向位置
    // 正落在左栏上半区，于是「在列表里滑」被判成「在空白区滑」。
    // ------------------------------------------------------------------

    /** 左栏接收区（1200×720，左半屏 552px 宽）内的点：纵向 58% 以内才算。 */
    @Test
    public void active_area_checks_both_axes() {
        final int left = 0, top = 0, w = 552, h = 720;

        assertTrue("左上角空白区，上滑换台",
                SwipeStationHelper.inActiveArea(200, 100, left, top, w, h));
        assertTrue("贴着上边界也算",
                SwipeStationHelper.inActiveArea(10, 0, left, top, w, h));

        assertFalse("右栏（列表）里的点不算 —— 这是本次修的 bug",
                SwipeStationHelper.inActiveArea(800, 100, left, top, w, h));
        assertFalse("左栏但下半区不算",
                SwipeStationHelper.inActiveArea(200, h * 0.9f, left, top, w, h));
        assertFalse("左边界之外不算",
                SwipeStationHelper.inActiveArea(-5, 100, left, top, w, h));
    }

    /** 面积异常（0 宽/0 高）不能除零崩掉，按「不算接收区」处理。 */
    @Test
    public void active_area_survives_zero_size() {
        assertFalse(SwipeStationHelper.inActiveArea(0, 0, 0, 0, 0, 0));
        assertFalse(SwipeStationHelper.inActiveArea(5, 5, 0, 0, 0, 720));
    }
}
