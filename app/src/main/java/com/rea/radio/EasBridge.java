package com.rea.radio;

/**
 * {@link RadioPlayer} 与「车机媒体中心（EAS）」之间的窄接口。
 *
 * <h3>为什么要有这个中间层</h3>
 *
 * <p>v1.9.0 之前，方向盘按键只在 App 是焦点窗口时有效 —— 因为车机把 346/347/348
 * 三个私有键码派给焦点窗口的 {@code onKeyDown}，App 一切到后台（看导航、回桌面）
 * 就收不到了。要「后台也能控」，唯一正规路子是把本应用注册成车机媒体中心的
 * 媒体源，让媒体中心替我们把按键转成 {@code onPlay / onNext / onPrevious} 回调。
 *
 * <p>那套 SDK（{@code com.ecarx.eas.sdk.*}）是<b>从网易云车机版里提取的</b>，
 * 依赖一大堆 {@code android.*} 与非公开类。而 {@code RadioPlayer} 是要在
 * JVM 单元测试里跑的（Robolectric / 纯 JUnit 都有）。如果 {@code RadioPlayer}
 * 直接调 {@code EasMediaCenter}，测试环境一加载这些类就会炸，
 * 于是「播放器」和「车机适配」被这条硬依赖绑死。
 *
 * <p>所以这里只留一个纯 Java 的空接口：
 * <ul>
 *   <li>播放器侧只认 {@link Sink}，编译期看不到任何 {@code com.ecarx} 类型；</li>
 *   <li>{@link EasMediaCenter} 在 {@code RadioService.onCreate} 里把自己注册进来；</li>
 *   <li>没人注册（单元测试、非星愿车机）时 {@link #push} 是空操作，永远不抛。</li>
 * </ul>
 */
final class EasBridge {

    /** 把当前播放状态推给媒体中心。实现方必须自己吞掉全部异常。 */
    interface Sink {
        void push(RadioPlayer player);
    }

    private static volatile Sink sink;

    /**
     * 媒体中心是否已经接管按键派发（即 registerMusic 成功拿到 token）。
     *
     * <p>这个标记只解决一件事：<b>前台会「跳两台」</b>。
     *
     * <p>实测（v1.9.2，2026-10-26）：App 在前台时按一次「下一台」，
     * {@code MainActivity.onKeyDown} 收到私有键码 347，<b>233ms 后</b>
     * 媒体中心又把 {@code onNext} 派给我们注册的 {@code MusicClient} ——
     * 也就是说媒体中心是「焦点窗口和媒体源各派一份」的广播式行为。
     * 两条路径都执行，一次按键就切了两个台。
     *
     * <p>所以：媒体中心一旦接管，前台那条私有键分支就该让位（只吞事件、不执行动作）；
     * 媒体中心没接管（非星愿车机、注册还没成功）时，前台分支仍是唯一的保障，必须保留。
     */
    private static volatile boolean active;

    private EasBridge() {
    }

    static void setSink(Sink s) {
        sink = s;
    }

    /** 媒体中心接管/交还按键派发。由 {@link EasMediaCenter} 在注册成功/卸载时调用。 */
    static void setActive(boolean on) {
        active = on;
    }

    /**
     * 媒体中心是否正在替我们派发按键。
     *
     * <p>为 {@code true} 时，前台私有键分支必须<b>放弃执行动作</b>，
     * 否则就是一次按键切两个台（见 {@link #active} 的注释）。
     */
    static boolean isActive() {
        return active;
    }

    /**
     * 通知媒体中心「状态变了」。
     *
     * <p>调用点只有一个：{@code RadioPlayer.updateSessionState()} —— 切台、播放/暂停、
     * 缓冲开始结束全都会走到那里，天然是状态的唯一出口。放在别处就会出现
     * 「通知栏对了、媒体中心还是旧的」这种不一致。
     */
    static void push(RadioPlayer player) {
        Sink s = sink;
        if (s == null) return;
        try {
            s.push(player);
        } catch (Throwable t) {
            // 车机媒体中心是「锦上添花」的能力，绝不能因为它把播放搞挂。
            AppLog.w("媒体源", "推送播放状态失败：" + t);
        }
    }
}
