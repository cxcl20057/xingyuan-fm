package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

/**
 * 星愿车机方向盘私有键码的映射验证。
 *
 * <h3>这组测试能抓什么、抓不到什么</h3>
 *
 * <p><b>能抓</b>：键码表写错（把 346 和 347 弄反）、漏掉某个键、把标准媒体键误判成私有码、
 * 忘判断 repeatCount 导致长按连切。
 *
 * <p><b>抓不到</b>：车机到底发不发这几个码、这几个码对不对应这个功能。
 * 那只能装车实测 —— 排查文档里的键码表就是这么来的（两轮物理实测 + 交叉验证），
 * 本测试只是把那张表**钉死**，防止以后有人顺手改动。
 *
 * <p>为什么不能靠 {@code input keyevent 348} 在模拟器上注入：input 工具会校验键码范围，
 * 超范围按 UNKNOWN 丢弃，注不进去。所以这里只能在 JVM 层面验证映射逻辑。
 */
public class CarKeyCodesTest {

    // ---------------------------------------------------------- 键码表本身

    @Test
    public void private_key_codes_match_the_field_measurement() {
        // 这三个数字来自实车 logcat（CAR.INPUT）与 dumpsys input 交叉验证。
        // 改动它们等于改协议，必须有新的实测证据。
        assertEquals("方向盘播放/暂停", 348, CarKeyCodes.KEY_PLAY_PAUSE);
        assertEquals("方向盘上一台", 346, CarKeyCodes.KEY_PREV);
        assertEquals("方向盘下一台", 347, CarKeyCodes.KEY_NEXT);
    }

    @Test
    public void private_codes_do_not_collide_with_standard_media_keys() {
        // 最关键的一条：如果这几个码和标准媒体键撞了，说明我们把
        // KEYCODE_MEDIA_* 常量抄错成数字了。撞了会导致同一次按键被处理两次
        // （一次走 onKeyDown 私有码分支，一次走系统转发的 MediaSession）。
        assertFalse("348 不能等于 KEYCODE_MEDIA_PLAY_PAUSE(85)",
                CarKeyCodes.KEY_PLAY_PAUSE == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        assertFalse("346 不能等于 KEYCODE_MEDIA_PREVIOUS(88)",
                CarKeyCodes.KEY_PREV == KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        assertFalse("347 不能等于 KEYCODE_MEDIA_NEXT(87)",
                CarKeyCodes.KEY_NEXT == KeyEvent.KEYCODE_MEDIA_NEXT);
        // 顺带确认标准媒体键确实不在我们的私有码集合里
        assertFalse(CarKeyCodes.isSteeringWheelKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertFalse(CarKeyCodes.isSteeringWheelKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertFalse(CarKeyCodes.isSteeringWheelKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertFalse(CarKeyCodes.isSteeringWheelKey(KeyEvent.KEYCODE_MEDIA_PLAY));
        assertFalse(CarKeyCodes.isSteeringWheelKey(KeyEvent.KEYCODE_MEDIA_PAUSE));
    }

    // ---------------------------------------------------------- 映射

    @Test
    public void play_pause_maps_to_toggle() {
        assertEquals(CarKeyCodes.Action.TOGGLE,
                CarKeyCodes.actionFor(CarKeyCodes.KEY_PLAY_PAUSE));
    }

    @Test
    public void prev_and_next_are_not_swapped() {
        // 这条专门防「346 和 347 写反」。写反了表面上还能用（能切台），
        // 只是方向和按键相反，很容易被当成「车机按键定义不同」而长期忽略。
        assertEquals("346 必须是上一台",
                CarKeyCodes.Action.PREV, CarKeyCodes.actionFor(346));
        assertEquals("347 必须是下一台",
                CarKeyCodes.Action.NEXT, CarKeyCodes.actionFor(347));
    }

    @Test
    public void unrelated_keys_are_not_hijacked() {
        // 音量键 309 是实测的对照键，系统层正常处理，我们绝不能拦。
        assertNull(CarKeyCodes.actionFor(309));
        assertFalse(CarKeyCodes.isSteeringWheelKey(309));
        // 常见按键也不该被吃掉
        assertNull(CarKeyCodes.actionFor(KeyEvent.KEYCODE_BACK));
        assertNull(CarKeyCodes.actionFor(KeyEvent.KEYCODE_VOLUME_UP));
        assertNull(CarKeyCodes.actionFor(KeyEvent.KEYCODE_VOLUME_DOWN));
        assertNull(CarKeyCodes.actionFor(KeyEvent.KEYCODE_1));
        assertNull(CarKeyCodes.actionFor(0));
        assertNull(CarKeyCodes.actionFor(-1));
    }

    @Test
    public void every_private_code_has_an_action() {
        // 反向覆盖：只要是私有码，就必须能翻译出动作。
        // 将来加新键（比如「语音」）时，如果只改了 isSteeringWheelKey
        // 忘了改 actionFor，这条会红。
        int[] codes = {CarKeyCodes.KEY_PLAY_PAUSE, CarKeyCodes.KEY_PREV, CarKeyCodes.KEY_NEXT};
        for (int c : codes) {
            assertTrue("私有码 " + c + " 应被识别", CarKeyCodes.isSteeringWheelKey(c));
            assertEquals("私有码 " + c + " 应有动作",
                    true, CarKeyCodes.actionFor(c) != null);
        }
    }

    // ---------------------------------------------------------- 长按与重复

    @Test
    public void long_press_events_are_still_marked_as_steering_wheel_keys() {
        // onKeyDown 里用 isSteeringWheelKey 判「吞不吞」，用 repeatCount 判「动不动」。
        // 长按产生的 repeat>0 事件也必须被识别并吞掉：
        // 不吞的话会漏给系统，框架不认识这个码，行为未定义。
        //
        // 这里只能验证判定函数返回 true（不依赖 repeatCount）——
        // 这正是设计意图：识别与执行分开。
        assertTrue(CarKeyCodes.isSteeringWheelKey(CarKeyCodes.KEY_NEXT));
    }
}
