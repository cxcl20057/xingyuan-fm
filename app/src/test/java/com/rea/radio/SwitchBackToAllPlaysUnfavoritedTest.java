package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.8.8 回归：从收藏切回「全部」后，点未收藏电台点击无效。
 *
 * <h3>实车原话</h3>
 * 「在收藏列表播放收藏电台 → 切回全部电台列表 → 点未收藏的台 → 点不动、不播放。」
 *
 * <h3>根因：播放器队列与界面列表错位</h3>
 * 播放器的 {@code queue} 和界面的 {@code stations}（全部电台）是两份独立数据：
 * <ol>
 *   <li>收藏视图下点选，{@code playFromList} 走 {@code rebindQueue} 把 {@code player.queue}
 *       <b>换成收藏子集</b>（只有少数几台）。</li>
 *   <li>切回「全部」时 {@code setView(false)} 只刷新界面列表，<b>没有把播放器队列换回来</b>。</li>
 *   <li>于是在「全部」里点未收藏台，{@code visiblePosToQueuePos} 在收藏子集里按 contentId
 *       找不到该台 → 返回 -1 → {@code if (qp >= 0)} 不成立 → 点击被静默吞掉。</li>
 * </ol>
 *
 * <p>本类覆盖完整闭环，断言点选后 {@code player.current()} 真的变成目标台。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-xhdpi")
public class SwitchBackToAllPlaysUnfavoritedTest {

    private MainActivity a;
    private RadioPlayer player;

    @Before
    public void setUp() {
        Context app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("radio_favorites", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("radio_hidden", Context.MODE_PRIVATE).edit().clear().commit();

        a = Robolectric.buildActivity(MainActivity.class).create().get();
        a.setLocalStationsEnabled(false);   // 手造数据，别补录
        player = new RadioPlayer(a);

        attach(a, player);

        // 6 个台：id0..id5
        List<RadioCnApi.Station> data = new ArrayList<>();
        for (int i = 0; i < 6; i++) data.add(st("id" + i, "电台" + i));
        showStations(a, data);
    }

    private static RadioCnApi.Station st(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        s.subtitle = "直播中";
        s.mp3PlayUrlHigh = "http://example.com/" + id + ".mp3";
        return s;
    }

    /**
     * 核心回归：收藏里点选之后切回全部，点一个未收藏的台必须真的切过去。
     *
     * <p>收藏顺序故意和「全部」不同，确认不是靠下标凑巧对上。
     */
    @Test
    public void after_switching_back_to_all_clicking_unfavorited_plays_it() {
        // 收藏 id4 再收藏 id2：FavoriteStore.add 会把新收藏「提到最前」，
        // 所以收藏列表顺序是 [id2, id4]。
        FavoriteStore.add(a, stationFromQueue(4));
        FavoriteStore.add(a, stationFromQueue(2));

        // 1) 切到收藏视图
        setView(a, true);

        // 2) 在收藏里点第 1 行（= id4，因为顺序是 [id2, id4]）：
        //    playFromList 走收藏分支，把 player.queue 换成收藏子集
        playFromList(a, 1);
        assertEquals("收藏里点第 1 行应播 id4", "id4", player.current().contentId);
        assertEquals("此刻播放器队列应是收藏子集（2 台）", 2, player.queue().size());

        // 3) 切回「全部」视图
        setView(a, false);

        // 4) 在「全部」里点一个未收藏的台（id5，在收藏子集里不存在）
        //    修复前：visiblePosToQueuePos 在收藏子集里找不到 id5 → 返回 -1 → 点击无效。
        //    修复后：setView(false) 已把队列换回全部电台 → 能找到 → 正常切台。
        playFromList(a, 5);
        assertEquals("切回全部后点 id5 必须真的切到 id5", "id5", player.current().contentId);
    }

    /**
     * 切回「全部」时，正在播的收藏台若不在全部列表里（跨省/被过滤），
     * 队列要补占位保证 current() 不返回 null、播放不中断。
     */
    @Test
    public void switching_back_keeps_playing_when_current_station_not_in_all_list() {
        // 造一个「不在全部列表里」的收藏台，模拟跨省收藏 / 数据已刷新掉的情况
        RadioCnApi.Station ghost = st("ghost-1", "已下架的收藏台");
        FavoriteStore.add(a, ghost);

        // 让播放器当前台就是这个 ghost（绕过界面直接喂队列）
        List<RadioCnApi.Station> q = new ArrayList<>();
        q.add(ghost);
        player.setQueue(q, 0, false);
        assertEquals("起点：当前台是 ghost", "ghost-1", player.current().contentId);

        // 切到收藏再切回全部，触发队列重建
        setView(a, true);
        setView(a, false);

        // 当前台不在 stations 里，重建后要补占位，播放不中断、current() 不 null
        assertNotNull("切回全部后 current() 不能变 null（播放不能断）", player.current());
        assertEquals("当前台应仍是 ghost", "ghost-1", player.current().contentId);
    }

    // ------------------------------------------------------------ 反射驱动

    private void attach(MainActivity activity, RadioPlayer p) {
        try {
            Method m = MainActivity.class.getDeclaredMethod("attach", RadioPlayer.class);
            m.setAccessible(true);
            m.invoke(activity, p);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void showStations(MainActivity activity, List<RadioCnApi.Station> data) {
        try {
            Method m = MainActivity.class.getDeclaredMethod("showStations", List.class);
            m.setAccessible(true);
            m.invoke(activity, data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void setView(MainActivity activity, boolean favorites) {
        try {
            Method m = MainActivity.class.getDeclaredMethod("setView", boolean.class);
            m.setAccessible(true);
            m.invoke(activity, favorites);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void playFromList(MainActivity activity, int pos) {
        try {
            Method m = MainActivity.class.getDeclaredMethod("playFromList", int.class);
            m.setAccessible(true);
            m.invoke(activity, pos);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private RadioCnApi.Station stationFromQueue(int index) {
        List<RadioCnApi.Station> q = player.queue();
        assertTrue("队列里应有下标 " + index, index >= 0 && index < q.size());
        return q.get(index);
    }
}
