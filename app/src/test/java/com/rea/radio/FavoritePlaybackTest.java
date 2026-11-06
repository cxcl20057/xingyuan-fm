package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.6.3 回归：「收藏夹里点选换台，界面已切、声音迟迟不切」。
 *
 * <p>实车原话：「在收藏夹内部使用触摸点选进行换台时候，反应很慢播放界面已经显示了，
 * 但是声音一直不切换。但是用上一台 下一台按钮切换就能正常切换。」
 *
 * <h3>根因（两条，都在这份测试里钉死）</h3>
 * <ol>
 *   <li><b>两条路不是同一条</b>：按钮走 {@code playAt}，收藏点选走 {@code setQueue}。
 *       {@code setQueue} 是「重置」语义——先把 {@code index} 清成 -1 再内部 playAt，
 *       等于每次点选都按冷启动处理。现在统一成「{@code rebindQueue} 换队列 + {@code playAt} 切台」。</li>
 *   <li><b>跨省收藏补不到地址</b>：收藏快照早先不存省份码，
 *       取出来的台 {@code provinceCode=0}，刷新地址时旧实现回落写死的四川（510000）。
 *       收藏了北京/广东的台时在四川列表里永远找不到，地址补不上 →
 *       界面切过去了、声音永远不出。现在收藏存省份码，且刷新有多级兜底。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FavoritePlaybackTest {

    private static RadioCnApi.Station st(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        s.mp3PlayUrlHigh = "http://example.com/" + id + ".mp3";
        return s;
    }

    // ---------------------------------------------------------------- 收藏存省份码

    /** 收藏必须把省份码一起存下来，否则跨省收藏的台刷新地址时无从下手。 */
    @Test
    public void favorite_keeps_province_code() {
        Context c = RuntimeEnvironment.getApplication();
        FavoriteStore.clear(c);

        RadioCnApi.Station beijing = st("bj-1", "北京音乐广播");
        beijing.provinceCode = 110000;
        FavoriteStore.add(c, beijing);

        List<FavoriteStore.Item> items = FavoriteStore.list(c);
        assertEquals(1, items.size());
        assertEquals("省份码必须落盘", 110000, items.get(0).provinceCode);
        assertEquals("还原回 Station 时也要带上", 110000, items.get(0).toStation().provinceCode);
        FavoriteStore.clear(c);
    }

    /** 老版本存的收藏没有省份码字段：读出来是 0（「不知道」），不能瞎猜一个省份。 */
    @Test
    public void legacy_favorite_without_province_reads_as_unknown() {
        Context c = RuntimeEnvironment.getApplication();
        FavoriteStore.clear(c);
        // 直接伪造一条老格式（没有 "pc"）
        c.getSharedPreferences("radio_favorites", Context.MODE_PRIVATE).edit()
                .putString("items", "[{\"id\":\"old-1\",\"title\":\"老收藏\",\"sub\":\"\",\"img\":\"\"}]")
                .commit();

        List<FavoriteStore.Item> items = FavoriteStore.list(c);
        assertEquals(1, items.size());
        assertEquals("老数据省份未知应为 0", 0, items.get(0).provinceCode);
        FavoriteStore.clear(c);
    }

    // ---------------------------------------------------------------- 点选与按钮同一条路

    /**
     * 收藏里点某一行，切到的必须<b>就是那一行</b>的台。
     *
     * <p>收藏顺序故意和「全部」列表不同，确认不是靠下标凑巧对上。
     */
    @Test
    public void clicking_a_favorite_row_plays_that_row() {
        RadioPlayer p = new RadioPlayer(RuntimeEnvironment.getApplication());

        List<RadioCnApi.Station> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) all.add(st("id" + i, "T" + i));
        p.setQueue(all, 0, false);

        // 收藏顺序：先收 T4 再收 T2，所以收藏第 2 行是 T2
        List<RadioCnApi.Station> fav = new ArrayList<>();
        fav.add(all.get(4));
        fav.add(all.get(2));

        p.rebindQueue(fav, 1, true);
        p.playAt(1);

        assertNotNull(p.current());
        assertEquals("点第 2 行就必须播第 2 行那台", "id2", p.current().contentId);
    }

    /** 从「全部」切到「收藏」且当前台没被收藏时，下标要落到 fallback，不能留在越界位置。 */
    @Test
    public void rebind_without_current_station_points_at_fallback_only() {
        RadioPlayer p = new RadioPlayer(RuntimeEnvironment.getApplication());
        p.setQueue(queueOf(5), 3, false);
        assertEquals("id3", p.current().contentId);

        List<RadioCnApi.Station> fav = new ArrayList<>();
        fav.add(st("f0", "收藏0"));
        fav.add(st("f1", "收藏1"));
        // 当前台 id3 不在收藏里 → 只对齐下标，不开流（开流由随后的 playAt 负责）
        p.rebindQueue(fav, 1, true);

        assertNotNull("队列换掉后仍要有当前台，不能变 null", p.current());
        assertEquals("应落到调用方给的 fallback 下标", "f1", p.current().contentId);
        assertEquals("队列也确实换成了收藏", 2, p.queue().size());
    }

    private static List<RadioCnApi.Station> queueOf(int n) {
        List<RadioCnApi.Station> q = new ArrayList<>();
        for (int i = 0; i < n; i++) q.add(st("id" + i, "T" + i));
        return q;
    }

    // ---------------------------------------------------------------- 兜底省份

    /** 播放器的兜底省份要能被界面设置进来（跨省收藏取址时唯一的线索）。 */
    @Test
    public void fallback_province_can_be_set() {
        RadioPlayer p = new RadioPlayer(RuntimeEnvironment.getApplication());
        p.setFallbackProvince(440000);      // 不抛异常即可；值在下一次取址时被用到
        p.setFallbackProvince(0);           // 0 = 未知，也必须接受
    }

    /**
     * attach 之后播放器必须拿到界面当前省份。
     *
     * <p>注意这里只做 attach，不触发任何切台/落盘：{@code persist()} 会写
     * SharedPreferences，写进去的恢复状态会污染后面跑的界面类测试
     * （表现为别处偶发「恢复到了上一台、列表没滚」之类的假失败）。
     */
    @Test
    public void activity_passes_province_to_player() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
        RadioPlayer p = new RadioPlayer(a);
        try {
            java.lang.reflect.Method attach = MainActivity.class
                    .getDeclaredMethod("attach", RadioPlayer.class);
            attach.setAccessible(true);
            attach.invoke(a, p);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        int got = readFallbackProvince(p);
        assertTrue("界面绑定时应把省份码同步给播放器，实际 " + got, got > 0);

        // 收尾：把播放器与界面都释放掉，避免 Robolectric 之间残留定时器/监听
        p.release();
        Robolectric.buildActivity(MainActivity.class).create().destroy();
    }

    private static int readFallbackProvince(RadioPlayer p) {
        try {
            java.lang.reflect.Field f = RadioPlayer.class.getDeclaredField("fallbackProvinceCode");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** 没有地址的台不能把播放器永远卡在「正在准备」——必须有明确的失败终态。 */
    @Test
    public void station_without_url_does_not_stay_in_opening_state() {
        RadioPlayer p = new RadioPlayer(RuntimeEnvironment.getApplication());
        RadioCnApi.Station bare = new RadioCnApi.Station();
        bare.contentId = "no-url";
        bare.title = "没地址的台";

        List<RadioCnApi.Station> q = new ArrayList<>();
        q.add(bare);
        p.setQueue(q, 0, true);

        // 走到 refreshThenOpen 后应该立刻进入「正在刷新」而不是无限递归
        assertTrue("应标记为正在刷新地址", readRefreshing(p));
    }

    private static boolean readRefreshing(RadioPlayer p) {
        try {
            java.lang.reflect.Field f = RadioPlayer.class.getDeclaredField("refreshing");
            f.setAccessible(true);
            return f.getBoolean(p);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
