package com.rea.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

/**
 * 通话期间「挂起 / 恢复」的回归测试。
 *
 * <p>对应 bug（v1.9.6 提出、v1.9.7 换了修法）：来电时方向盘「下一曲」不再接听、
 * 仍然切台（用户听到的是原车收音机在换台）。
 *
 * <p><b>v1.9.7 的结论变了</b>：逆向「星愿车控完全正常酷我音乐 6.0.0.9」后发现，
 * 按键去向由<b>媒体源类型</b>决定（在线音乐 6 vs 数字广播 11），不是由
 * MediaSession 活跃状态或播放焦点决定 —— 酷我的会话全程活跃、从不
 * {@code abandonPlayFocus}，通话却完全正常。所以这里锁的不变量改成：
 *
 * <ol>
 *   <li>通话中<b>只暂停</b>，会话<b>保持活跃</b>（置为不活跃会让媒体中心把当前音源
 *       切回原车收音机，方向盘立刻变成调台）；</li>
 *   <li>挂断后会话仍然是活跃的，不需要「恢复」这一步（少一个会忘的状态）；</li>
 *   <li>只有「是通话让播放停的」才自动恢复播放：用户在通话中自己按了暂停，
 *       挂断后不该自己播起来。</li>
 * </ol>
 *
 * <p><b>抓不到什么</b>：车机固件到底怎么按媒体源类型派键、通话时会不会切音频模式。
 * 那只能上车看 {@code [通话]} / {@code [媒体源]} 日志（见 docs/通话让路.md）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CallSuspendTest {

    private ServiceController<RadioService> svc;

    private RadioPlayer player() {
        svc = Robolectric.buildService(RadioService.class).create();
        RadioPlayer p = RadioService.player();
        assertNotNull("服务创建后必须能拿到播放器", p);
        return p;
    }

    /** 不变量 ①②：通话中会话保持活跃，挂断后依旧活跃。 */
    @Test
    public void session_stays_active_across_call() {
        RadioPlayer p = player();
        assertTrue("平时会话应当活跃（否则按键派不到本应用）", p.isSessionActive());

        p.onCallStarted();
        assertTrue("应进入通话挂起状态", p.isCallSuspended());
        assertTrue("通话中会话必须保持活跃：置为不活跃会把当前音源让给原车收音机，"
                + "方向盘随即变成调台", p.isSessionActive());

        p.onCallEnded();
        assertFalse("挂断后应退出挂起状态", p.isCallSuspended());
        assertTrue("挂断后会话仍然活跃", p.isSessionActive());

        svc.destroy();
    }

    /**
     * 不变量 ③-a：通话前在播 → 挂断后自动恢复。
     *
     * <p>播放器新建时 {@code playWhenReady} 默认是 true，这里就借用这个初始态当
     * 「通话前正在播」。
     */
    @Test
    public void resume_after_call_when_it_was_playing() {
        RadioPlayer p = player();
        assertTrue(p.isPlayWhenReady());

        p.onCallStarted();
        assertFalse("通话开始应暂停", p.isPlayWhenReady());

        p.onCallEnded();
        assertTrue("通话前在播，挂断后应自动恢复", p.isPlayWhenReady());

        svc.destroy();
    }

    /** 不变量 ③-b：通话中用户自己按了暂停 → 挂断后不能自己播起来。 */
    @Test
    public void stay_paused_when_user_paused_during_call() {
        RadioPlayer p = player();

        p.onCallStarted();     // 电话把我停了（pausedByCall = true）
        p.pause();             // 用户主动按下暂停：这个意图必须盖住「通话后恢复」
        p.onCallEnded();
        assertFalse("用户在通话中自己停了，挂断后不该自动播放", p.isPlayWhenReady());

        svc.destroy();
    }

    /** 没有通话就调 {@code onCallEnded()}：必须是空操作，不能顺手把播放打开。 */
    @Test
    public void call_end_without_call_start_does_nothing() {
        RadioPlayer p = player();
        p.pause();
        p.onCallEnded();
        assertFalse("没进过通话状态，不该恢复播放", p.isPlayWhenReady());

        svc.destroy();
    }
}
