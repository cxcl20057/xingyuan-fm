package com.rea.radio;

/**
 * 星愿车机（GEELYGEOME E22H / ECARX IHU801G）方向盘按键的私有键码。
 *
 * <h3>为什么需要这个类</h3>
 *
 * 方向盘按键一开始「完全没反应」，排查了媒体中心白名单、appSource 媒体源注册、
 * 伪装包冲突等等，全部无效。真实原因很朴素：
 *
 * <p><b>这组按键根本不是标准媒体键，是车厂私有键码。</b>
 *
 * <p>标准媒体键（85 播放暂停 / 87 下一首 / 88 上一首）由系统全局拦截，转成
 * {@code ACTION_MEDIA_BUTTON} 派给 MediaSession —— 所以我们原来那套
 * MediaSession + MediaButtonReceiver 的链路是对的，但<b>从来没被用过</b>，
 * 因为车机压根不发这些键。
 *
 * <h3>链路实测</h3>
 *
 * <pre>
 * 方向盘 CAN 信号
 *   ↓
 * com.android.car（CarInputService）注入 KeyEvent
 *   ↓  logcat tag: CAR.INPUT
 *      "onKeyEvent event:KeyEvent{...keyCode=348...} isCustomEventHandler:false"
 *   ↓
 * InputDispatcher → 派发给「当前焦点窗口」
 *   ↓
 * 焦点 Activity.onKeyDown(346 / 347 / 348)
 * </pre>
 *
 * <p>判据：{@code KeyEvent.toString()} 对这三个码打印的是<b>数字本身</b>而不是
 * {@code KEYCODE_XXX} 符号名 —— 说明 Android 框架里没有定义它们。框架不认识的码，
 * 自然不会像媒体键那样被全局拦截转给 MediaSession。
 *
 * <h3>实测键码表</h3>
 *
 * <table border="1">
 *   <tr><th>keyCode</th><th>功能</th><th>证据</th></tr>
 *   <tr><td>348</td><td>播放 / 暂停</td><td>两轮测试均以此键开头，与 347 成对出现</td></tr>
 *   <tr><td>346</td><td>上一台</td><td>第一轮「上一台→下一台」顺序中的前者</td></tr>
 *   <tr><td>347</td><td>下一台</td><td>第二轮只按「播放暂停+下一台」，出现 348→347</td></tr>
 *   <tr><td>309</td><td>音量 +</td><td>对照键，系统层正常处理（不是媒体键）</td></tr>
 * </table>
 *
 * <p><b>无法用 {@code input keyevent 348} 模拟验证</b>：input 工具会校验范围，
 * 超范围按 UNKNOWN 丢弃。只能由车机框架注入。所以单元测试只验证「映射表正确」，
 * 真实按键必须装车实测。
 *
 * <h3>已知限制</h3>
 *
 * <p>{@code onKeyDown} 只在 App 是焦点窗口时触发。App 切到后台（比如在看导航）时，
 * 按键会送给当时的焦点窗口，这条路就断了。
 *
 * <p><b>后台的情况已由 v1.9.1 解决，但不是在这里解的</b>：真正的解法是把本应用
 * 注册成车机媒体中心的媒体源（{@code EasMediaCenter}），由媒体中心把方向盘按键
 * 翻译成 {@code onPlay/onPause/onNext/onPrevious} 回调 —— 跟焦点窗口无关。
 * 两条路径互不冲突：前台走这里（快、无依赖），后台走 EAS。
 *
 * <p>所以这个类只负责「前台兜底」，别把后台逻辑往这儿塞。
 */
public final class CarKeyCodes {

    private CarKeyCodes() { }

    /** 方向盘「播放 / 暂停」（车厂私有码，非 {@code KEYCODE_MEDIA_PLAY_PAUSE}=85）。 */
    public static final int KEY_PLAY_PAUSE = 348;

    /** 方向盘「上一台」（车厂私有码，非 {@code KEYCODE_MEDIA_PREVIOUS}=88）。 */
    public static final int KEY_PREV = 346;

    /** 方向盘「下一台」（车厂私有码，非 {@code KEYCODE_MEDIA_NEXT}=87）。 */
    public static final int KEY_NEXT = 347;

    /**
     * 这个键码是不是方向盘私有码。
     *
     * <p>抽成方法而不用一串 {@code ||}：一来测试可以直接断言，
     * 二来以后换车型只改这一个文件。
     */
    public static boolean isSteeringWheelKey(int keyCode) {
        return keyCode == KEY_PLAY_PAUSE || keyCode == KEY_PREV || keyCode == KEY_NEXT;
    }

    /**
     * 把键码翻译成动作。
     *
     * <p>返回 {@code null} 表示「不是方向盘私有码，别管」。
     * 用枚举而不是直接在这判断里调播放器，是为了让「键码 → 动作」这层映射
     * 能在 JVM 单元测试里验证 —— {@code onKeyDown} 里那段跑不了（要真 Activity）。
     */
    public static Action actionFor(int keyCode) {
        switch (keyCode) {
            case KEY_PLAY_PAUSE: return Action.TOGGLE;
            case KEY_PREV:       return Action.PREV;
            case KEY_NEXT:       return Action.NEXT;
            default:             return null;
        }
    }

    /** 方向盘按键能触发的动作。 */
    public enum Action {
        /** 播放 / 暂停切换。 */
        TOGGLE,
        /** 上一台。 */
        PREV,
        /** 下一台。 */
        NEXT
    }
}
