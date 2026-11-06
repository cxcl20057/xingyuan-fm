package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.BaseAdapter;
import android.widget.ListView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 点收藏不该动列表位置。
 *
 * <h3>这条测试守的是一个实车/手机都反馈过的 bug</h3>
 * 「点某行的 ☆ 收藏，点完之后列表自动跳到正在播放的那个台」。
 *
 * <p>根因在 {@code MainActivity.refreshList()}：它无条件调 {@code scrollListToRow(正在播的行)}，
 * 而这个方法本来是给「切台后列表跟过去」用的。收藏也走同一个 refresh，
 * 于是用户正在浏览的位置被一脚踢回正在播的台。
 *
 * <p>正确语义是两条，本类分别守：
 * <ul>
 *   <li><b>用户主动改列表</b>（收藏/取消/隐藏）→ 位置不动；</li>
 *   <li><b>在播的台变了</b> → 必须跟过去（这条由 {@link ListFollowsPlaybackTest} 守）。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FavoriteKeepsScrollTest {

    private static final int W = 1920;
    private static final int H = 1080;

    private MainActivity a;
    private RadioPlayer player;
    private ListView list;

    @Before
    public void setUp() {
        // 清干净：收藏/隐藏/恢复状态会跨用例串味
        android.content.Context app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("radio_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();

        a = Robolectric.buildActivity(MainActivity.class).create().get();
        a.setLocalStationsEnabled(false);   // 手造数据，别补录
        player = new RadioPlayer(a);

        try {
            java.lang.reflect.Method attach = MainActivity.class
                    .getDeclaredMethod("attach", RadioPlayer.class);
            attach.setAccessible(true);
            attach.invoke(a, player);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        List<RadioCnApi.Station> data = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            RadioCnApi.Station s = new RadioCnApi.Station();
            s.title = "电台" + (i + 1);
            s.subtitle = "直播中";
            s.provinceName = "四川";
            s.contentId = "id" + i;
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
        list = a.findViewById(R.id.list_stations);
        layout();

        // 收藏是全局状态，逐个用例前清空，否则第二次跑时星标已经是"已收藏"
        FavoriteStore.list(app).forEach(it -> {
            RadioCnApi.Station s = new RadioCnApi.Station();
            s.contentId = it.contentId;
            s.title = it.title;
            FavoriteStore.toggle(app, s);
        });
    }

    @After
    public void tearDown() {
        android.content.Context app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("radio_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private void layout() {
        View root = a.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(800, TimeUnit.MILLISECONDS);
    }

    /** 直接摆到某行（绕开 Robolectric 不驱动平滑滚动动画的限制）。 */
    private void jumpTo(int row) {
        cancelPendingScroll();
        list.setSelectionFromTop(row, 0);
        layout();
    }

    private void cancelPendingScroll() {
        try {
            java.lang.reflect.Method m =
                    MainActivity.class.getDeclaredMethod("cancelPendingScroll");
            m.setAccessible(true);
            m.invoke(a);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * 取出 {@code refreshList(boolean)} 收到的参数，用来验证「调用意图」。
     *
     * <h3>为什么光断言"列表位置没变"不够</h3>
     * Robolectric 的 {@code ShadowAbsListView} <b>不驱动平滑滚动动画</b>
     * （见 {@link ListFollowsPlaybackTest} 类注释），而 {@code scrollListToRow} 在
     * 「目标行本来就可见」时也会提前返回、什么都不做。
     * 结果就是：即使代码里错误地写了 {@code refreshList(true)}，位置也可能恰好没变 ——
     * 变异验证时这条断言<b>确实没能变红</b>，所以不能只靠它。
     *
     * <p>于是分两层守：
     * <ol>
     *   <li><b>调用意图</b>（本方法）：{@code refreshList} 必须收到 {@code false}。
     *       这与"滚不滚得动"无关，直接卡住错误的语义。</li>
     *   <li><b>真实后果</b>：让在播台<b>远离视野</b>，此时 {@code followPlaying=true}
     *       才会真的触发滚动；这种情况下一旦被拽走，位置断言必然失败。</li>
     * </ol>
     */
    private boolean captureFollowPlaying() {
        try {
            java.lang.reflect.Field f = MainActivity.class
                    .getDeclaredField("lastRefreshFollowedPlaying");
            f.setAccessible(true);
            return (boolean) f.get(a);
        } catch (Exception e) {
            throw new AssertionError("无法读取 refreshList 的跟随标志", e);
        }
    }

    private void toggleFavorite(int row) {
        try {
            java.lang.reflect.Method m =
                    MainActivity.class.getDeclaredMethod("toggleFavorite", int.class);
            m.setAccessible(true);
            m.invoke(a, row);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        layout();
    }

    /**
     * 核心回归：滚到远处某行，点它的 ☆，位置必须留在原处。
     *
     * <p>40 个台、一屏 4~5 行，从第 1 行滚到第 20 行足够远，
     * 一旦被拽回正在播的第 0 行，断言立刻炸。
     */
    @Test
    public void starring_a_far_row_keeps_list_position() {
        // 正在播第 0 台；把列表滚到第 20 行附近浏览
        jumpTo(20);
        int firstBefore = list.getFirstVisiblePosition();
        assertTrue("前置条件：应当已经滚到远离首行的位置，实际第 " + firstBefore + " 行",
                firstBefore >= 15);

        toggleFavorite(firstBefore);
        assertFalse("点收藏不应要求列表跟随正在播的台（原 bug 就在这里）",
                captureFollowPlaying());
        assertEquals("点收藏后列表不应跳走（原来在第 " + firstBefore + " 行）",
                firstBefore, list.getFirstVisiblePosition());
    }

    /**
     * 真实后果的复现：把在播台换到远处，再点收藏。
     *
     * <p>在播台留在第 0 行时它本来就可见，{@code scrollListToRow} 会提前返回，
     * 于是「拽走」这个 bug 在 JVM 里表现不出来——变异验证时踩过这个坑，
     * 所以必须另写一条把在播台推到视野之外的用例。
     */
    @Test
    public void manual_switch_then_star_does_not_yank_list_to_playing() {
        player.playAt(30);          // 正在播第 31 台
        layout();
        jumpTo(5);                  // 用户滚回第 5 行浏览
        int firstBefore = list.getFirstVisiblePosition();
        int playingRow = 30;
        assertTrue("前置条件：在播台应远离视野（在播第 " + (playingRow + 1)
                + " 行，视野首行 " + (firstBefore + 1) + "）",
                playingRow > firstBefore + 5);

        toggleFavorite(firstBefore);

        assertFalse("点收藏不应要求列表跟随在播台", captureFollowPlaying());
        assertTrue("点收藏后不该被拽到在播台附近，实际首行 "
                        + (list.getFirstVisiblePosition() + 1),
                list.getFirstVisiblePosition() < playingRow - 5);
    }

    /** 再点一次取消收藏，同样不能跳。 */
    @Test
    public void unstarring_keeps_list_position() {
        jumpTo(20);
        int firstBefore = list.getFirstVisiblePosition();
        toggleFavorite(firstBefore);        // 先收藏
        layout();
        int firstAfterStar = list.getFirstVisiblePosition();

        toggleFavorite(firstAfterStar);     // 再取消

        assertEquals("取消收藏后列表也不应跳走",
                firstAfterStar, list.getFirstVisiblePosition());
    }

    /** 收藏确实生效了：计数要变，否则"不跳"可能只是因为压根没处理。 */
    @Test
    public void starring_actually_adds_to_favorites() {
        jumpTo(20);
        int firstBefore = list.getFirstVisiblePosition();
        String id = ((BaseAdapter) list.getAdapter()).getItem(firstBefore) instanceof RadioCnApi.Station
                ? ((RadioCnApi.Station) ((BaseAdapter) list.getAdapter()).getItem(firstBefore)).contentId
                : null;

        toggleFavorite(firstBefore);

        assertTrue("收藏应当已写入", FavoriteStore.contains(
                ApplicationProvider.getApplicationContext(), id));
    }

    /** 切换「全部 / 收藏」视图时，仍应把在播的台带进视野（这条不能被误伤）。 */
    @Test
    public void switching_to_favorites_still_follows_playing() {
        // 先把第 0 台（正在播的）收藏
        toggleFavorite(0);
        jumpTo(20);   // 然后滚远

        try {
            java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("setView", boolean.class);
            m.setAccessible(true);
            m.invoke(a, true);      // 切到收藏视图
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        layout();

        // 收藏视图里只有 1 台，且它就是在播的台 —— 列表必然显示它
        assertEquals("收藏视图应只有 1 条", 1, list.getAdapter().getCount());
        assertEquals("收藏视图首行应是在播的台", 0, list.getFirstVisiblePosition());
    }
}
