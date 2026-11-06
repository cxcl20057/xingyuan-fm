package com.rea.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioManager;

import org.junit.Test;

/**
 * 通话让路判据的验证。
 *
 * <h3>为什么这两级判据必须分别测</h3>
 *
 * <p>v1.9.6 修的 bug：来电时方向盘「下一曲」不再接听，仍然是切台。根因是我们作为
 * 车机媒体中心的媒体源，一直持有播放焦点，车机就把按键全派给我们，通话界面从没拿到过。
 *
 * <p>修法里最容易搞错的是<b>两级判据被合并成一级</b>：
 * <ul>
 *   <li>合并成「激进」（只看焦点瞬间丢失就算通话）→ 车机导航播报会把电台停掉，
 *       这是 v1.8.x 就踩过的坑，用户会立刻听出来；</li>
 *   <li>合并成「保守」（等 2.5 秒才让键）→ 响铃头两秒按键还在我们手上，
 *       用户按「下一曲」接听照样接不起来，等于没修。</li>
 * </ul>
 *
 * <p>所以 {@code shouldYieldKeys}（让键，毫秒级）与 {@code isCallNow}（让媒体源，秒级）
 * 必须是两套判据。下面的用例把这张真值表钉死。
 *
 * <p><b>抓不到什么</b>：车机固件到底会不会在通话时切音频模式、会不会发 LOSS_TRANSIENT。
 * 那只能装车实测（看诊断日志里的「音频模式 → 通话中(2)」）。本测试只保证
 * 「拿到信号之后判据不犯错」。
 */
public class CallGuardTest {

    // ---------------------------------------------------------------- 音频模式

    /** 正常模式下不该让路 —— 否则电台平时就用不了。 */
    @Test
    public void normal_mode_does_not_yield() {
        assertFalse(CallGuard.isCallMode(AudioManager.MODE_NORMAL));
        assertFalse(CallGuard.isCallNow(AudioManager.MODE_NORMAL, false));
        assertFalse(CallGuard.shouldYieldKeys(AudioManager.MODE_NORMAL, false, false));
    }

    /**
     * 响铃必须算「通话」。
     *
     * <p>这条是本 bug 的关键：用户是在<b>响铃阶段</b>按「下一曲」接听的。
     * 漏掉 MODE_RINGTONE 就只修好了「通话中」，接听依然无效。
     */
    @Test
    public void ringtone_mode_counts_as_call() {
        assertTrue(CallGuard.isCallMode(AudioManager.MODE_RINGTONE));
        assertTrue("响铃要立即暂停让路", CallGuard.isCallNow(AudioManager.MODE_RINGTONE, false));
        assertTrue(CallGuard.shouldYieldKeys(AudioManager.MODE_RINGTONE, false, false));
    }

    @Test
    public void in_call_and_voip_modes_count_as_call() {
        assertTrue(CallGuard.isCallMode(AudioManager.MODE_IN_CALL));
        assertTrue(CallGuard.isCallMode(AudioManager.MODE_IN_COMMUNICATION));
        assertTrue(CallGuard.isCallNow(AudioManager.MODE_IN_CALL, false));
        assertTrue(CallGuard.isCallNow(AudioManager.MODE_IN_COMMUNICATION, false));
    }

    // ---------------------------------------------------------------- 音频焦点

    /**
     * 焦点刚被强制拿走：只让键，先不暂停。
     *
     * <p>导航播报、语音助手也会这样拿走焦点，一两秒就还。立刻暂停会把电台切得支离破碎。
     */
    @Test
    public void transient_loss_yields_keys_but_does_not_pause_yet() {
        assertTrue("按键必须立刻让出去",
                CallGuard.shouldYieldKeys(AudioManager.MODE_NORMAL, true, false));
        assertFalse("但还不该暂停（可能只是导航播报）",
                CallGuard.isCallNow(AudioManager.MODE_NORMAL, false));
    }

    /** 焦点被拿走超过 2.5 秒：升级为通话，连播放一起让。 */
    @Test
    public void sustained_loss_becomes_a_call() {
        assertTrue(CallGuard.isCallNow(AudioManager.MODE_NORMAL, true));
        assertTrue(CallGuard.shouldYieldKeys(AudioManager.MODE_NORMAL, false, true));
        assertTrue(CallGuard.isCallNow(AudioManager.MODE_RINGTONE, true));
    }

    /** 阈值本身：太长则响铃期间电台还在响，太短则导航播报会被当成电话。 */
    @Test
    public void sustain_window_is_seconds_not_milliseconds() {
        assertTrue("至少 1.5 秒，否则导航播报会误判",
                CallGuard.SUSTAIN_MS >= 1_500L);
        assertTrue("最多 5 秒，否则通话开始时电台还在响",
                CallGuard.SUSTAIN_MS <= 5_000L);
    }
}
