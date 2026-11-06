package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.7.0：播放内核换成 ExoPlayer 后的回归测试。
 *
 * <p>换内核的动机不是"想用新库"，而是系统 {@code MediaPlayer} 在车机上有三个修不了的坑：
 * HLS 解析交给各 ROM 实现（成都广电源是<b>两层播放列表</b>，经常直接 error）、
 * 没有自己的缓冲与重连、{@code setDataSource} 是阻塞调用必须自己搞双实例替换。
 *
 * <p>本类验证的是「换内核没有把原有的播放器契约弄坏」——
 * 界面、方向盘、通知栏都只依赖这些公开行为，不关心底层是 MediaPlayer 还是 ExoPlayer。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExoPlayerKernelTest {

    private static RadioCnApi.Station st(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        // 给个地址，避免播放入口直接走到「刷新地址」分支（那会发真网络请求）
        s.mp3PlayUrlHigh = "http://example.com/" + id + ".mp3";
        return s;
    }

    private static List<RadioCnApi.Station> queueOf(int n) {
        List<RadioCnApi.Station> q = new ArrayList<>();
        for (int i = 0; i < n; i++) q.add(st("id" + i, "T" + i));
        return q;
    }

    private RadioPlayer newPlayer() {
        return new RadioPlayer(RuntimeEnvironment.getApplication());
    }

    /** 内核必须是 ExoPlayer：用反射确认类型，防止有人"顺手"改回 MediaPlayer。 */
    @Test
    public void kernel_is_exoplayer() {
        RadioPlayer p = newPlayer();
        try {
            java.lang.reflect.Field f = RadioPlayer.class.getDeclaredField("player");
            f.setAccessible(true);
            Object kernel = f.get(p);
            assertNotNull("播放内核不能为 null", kernel);
            // 字段声明类型是接口 ExoPlayer，运行时是 ExoPlayerImpl
            assertTrue("播放内核必须是 androidx.media3.exoplayer.ExoPlayer，实际 "
                            + kernel.getClass().getName(),
                    kernel instanceof androidx.media3.exoplayer.ExoPlayer);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("播放器字段名变了，请同步更新本测试", e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        p.release();
    }

    /** 队列与下标的常规操作行为不变。 */
    @Test
    public void queue_navigation_still_works() {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(4), 0, false);
        assertEquals("id0", p.current().contentId);

        p.next();
        assertEquals("id1", p.current().contentId);
        p.next();
        assertEquals("id2", p.current().contentId);

        p.prev();
        assertEquals("id1", p.current().contentId);

        // 首尾环绕：从 0 往前退回到最后一个
        p.playAt(0);
        p.prev();
        assertEquals("循环：从第一个往前应绕到最后一个", "id3", p.current().contentId);
        p.release();
    }

    /** playAt 越界不能崩、也不能改下标。 */
    @Test
    public void playAt_out_of_range_is_ignored() {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(3), 1, false);
        p.playAt(-1);
        assertEquals("id1", p.current().contentId);
        p.playAt(99);
        assertEquals("id1", p.current().contentId);
        p.release();
    }

    /**
     * 空队列时 next/prev/playAt 必须安全空转。
     *
     * <p>ExoPlayer 的 getCurrentPosition 在 IDLE 下会抛 {@code IllegalStateException}，
     * 状态上报里已经做了保护，这里确认空队列根本不会走到那一步。
     */
    @Test
    public void empty_queue_is_safe() {
        RadioPlayer p = newPlayer();
        p.next();
        p.prev();
        p.playAt(0);
        assertFalse("空队列不该报告在播放", p.isPlaying());
        p.release();
    }

    /**
     * pause 之后 {@code isPlayWhenReady()} 必须为 false。
     *
     * <p>界面用它区分「已暂停」和「连接中」，通知栏也用它决定按钮图标。
     */
    @Test
    public void pause_clears_play_when_ready() {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(2), 0, true);
        p.pause();
        assertFalse("暂停后 playWhenReady 应为 false", p.isPlayWhenReady());
        p.release();
    }

    /** stop 之后状态必须回落到「没在播」，且能重新起播。 */
    @Test
    public void stop_then_play_again() {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(3), 0, true);
        p.stop();
        assertFalse("停止后不该报告在播放", p.isPlaying());
        assertFalse("停止后 playWhenReady 应为 false", p.isPlayWhenReady());

        p.playAt(1);
        assertEquals("停止后仍能重新选中", "id1", p.current().contentId);
        assertTrue("playAt 后 playWhenReady 应恢复为 true", p.isPlayWhenReady());
        p.release();
    }

    /**
     * 重复点「正在播的那台」不应重建流。
     *
     * <p>旧内核时代靠 {@code i == index && prepared && !opening} 拦下来；
     * 换内核后条件里多了 playWhenReady，防止「暂停状态下点同一台」
     * 被当成"已经在播"而什么都不做（那样会点了没反应）。
     */
    @Test
    public void clicking_current_station_while_paused_still_resumes() {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(3), 1, true);
        p.pause();
        assertFalse(p.isPlayWhenReady());

        p.playAt(1);
        assertTrue("暂停时点同一台应该恢复播放，而不是被「已在播放」挡掉",
                p.isPlayWhenReady());
        p.release();
    }

    /** 当前地址的档位标签要能正常取（诊断面板用）。 */
    @Test
    public void source_label_is_available() {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(2), 0, true);
        String label = p.currentSourceLabel();
        assertNotNull("档位标签不能是 null", label);
        assertFalse("档位标签不能为空串", label.length() == 0);
        p.release();
    }

    /** 队列对外必须是只读视图：外部改不动内部队列（否则下标会静默错位）。 */
    @Test
    public void queue_is_read_only_outside() {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(3), 0, false);
        try {
            p.queue().clear();
            throw new AssertionError("外部居然能清空内部队列");
        } catch (UnsupportedOperationException expected) {
            // 正是想要的
        }
        assertEquals("内部队列不该被外部改动", 3, p.queue().size());
        p.release();
    }

    // ------------------------------------------------------------ 看门狗

    /**
     * 停顿看门狗不能被重复的状态回调往后推。
     *
     * <p>ExoPlayer 在 {@code STATE_BUFFERING} 期间会反复回调同一个状态（每次加载进度变化都走一遍），
     * 如果每次回调都重新计时，弱网下真实卡死就永远判不出来——直播流会一直转圈不重连。
     * 这条用例锁住「已经在计时就不再挂新任务」。
     */
    @Test
    public void stall_watchdog_is_not_pushed_back_by_repeated_callbacks() throws Exception {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(1), 0, true);
        java.lang.reflect.Method arm = RadioPlayer.class.getDeclaredMethod("armStallWatchdog");
        arm.setAccessible(true);
        java.lang.reflect.Field f = RadioPlayer.class.getDeclaredField("stallTimeout");
        f.setAccessible(true);

        arm.invoke(p);
        Object first = f.get(p);
        assertNotNull("看门狗应该挂上了", first);

        arm.invoke(p);
        arm.invoke(p);
        assertSame("重复回调不该换掉已挂的定时任务（否则截止时间被无限推迟）",
                first, f.get(p));
        p.release();
    }

    /**
     * 切台必须清掉上一台的停顿看门狗。
     *
     * <p>否则旧台留下的定时器会在 12 秒后对<b>新台</b>发起 {@code reconnect(true)}，
     * 表现为「刚切过去就自己重连一次」，还会被算进卡顿降档计数。
     */
    @Test
    public void switching_station_cancels_previous_stall_watchdog() throws Exception {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(3), 0, true);

        java.lang.reflect.Method arm = RadioPlayer.class.getDeclaredMethod("armStallWatchdog");
        arm.setAccessible(true);
        java.lang.reflect.Field f = RadioPlayer.class.getDeclaredField("stallTimeout");
        f.setAccessible(true);

        arm.invoke(p);
        assertNotNull("前置条件：看门狗已经挂上", f.get(p));

        p.playAt(1);
        assertNull("切台后上一台的看门狗必须被清掉", f.get(p));
        p.release();
    }
}
