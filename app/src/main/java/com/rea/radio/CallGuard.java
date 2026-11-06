package com.rea.radio;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * 「现在是不是在通话 / 响铃」的判定，以及通话期间必须让路的统一出口。
 *
 * <h3>要解决的问题</h3>
 *
 * <p>装车前用户可以「按方向盘下一曲接听来电、上一曲挂断」。装了本应用之后不行了 ——
 * 按键仍然切台，电话接不起来。
 *
 * <p>原因不是我们抢了电话的键，而是<b>我们成了媒体中心的当前媒体源</b>：
 * {@code EasMediaCenter} 注册成功后 {@code requestPlay} 拿到播放焦点，
 * 之后车机方向盘按键就按「谁持有焦点」派发，全部派给 {@code onNext / onPrevious}。
 * 电话界面<b>从来没拿到过</b>这两个键。
 *
 * <p>注意「暂停时也还焦点」这一条是 v1.9.1 的刻意设计（还了焦点再按播放键就没人接），
 * 所以不能靠「暂停时顺带还焦点」解决 —— 必须有一个明确的「通话期间让路」状态。
 *
 * <h3>三个信号，都是不需要权限的</h3>
 *
 * <p>本应用<b>不申请</b> {@code READ_PHONE_STATE}（电台应用要这个权限很怪，车机上
 * 还可能弹不出授权框），所以 {@code TelephonyManager.getCallState()} 用不了。
 * 可用的替代信号有三个：
 *
 * <ol>
 *   <li><b>音频模式</b> {@code AudioManager.getMode()}：车机（含蓝牙 HFP 通话）
 *       在通话时会把音频模式切成 {@code MODE_IN_CALL}，VoIP 是
 *       {@code MODE_IN_COMMUNICATION}，响铃时是 {@code MODE_RINGTONE}。
 *       这是媒体类应用检测通话的通用做法，不需要任何权限。</li>
 *   <li><b>音频焦点被强制拿走</b>（{@code AUDIOFOCUS_LOSS_TRANSIENT}）：
 *       来电响铃/通话会以 {@code AUDIOFOCUS_GAIN_TRANSIENT} 申请焦点，
 *       我们就会收到 LOSS_TRANSIENT。</li>
 *   <li><b>焦点被拿走且不还的时间</b>：导航播报、语音助手也会短暂拿走焦点，
 *       但它们通常用 {@code GAIN_TRANSIENT_MAY_DUCK}（我们收到的是
 *       {@code LOSS_TRANSIENT_CAN_DUCK}，可以继续小声播）。
 *       对真正的 {@code LOSS_TRANSIENT} 再等 {@link #SUSTAIN_MS}，还回来就说明是
 *       一声播报，不还就按通话处理 —— 这样既不会在导航播报时误停，也不会漏掉通话。</li>
 * </ol>
 *
 * <h3>「让路」分两级，别混成一级</h3>
 *
 * <table border="1">
 *   <tr><th></th><th>判据</th><th>动作</th></tr>
 *   <tr><td>让出按键</td><td>{@link #shouldYieldKeys}：通话模式 <b>或</b> 焦点刚被拿走</td>
 *       <td>{@code onKeyDown / MediaButtonReceiver / EAS 回调}一律不处理、不吞事件</td></tr>
 *   <tr><td>让出媒体源</td><td>{@link #isCallNow}：通话模式 <b>或</b> 焦点被拿走超过 2.5 秒</td>
 *       <td>暂停播放、{@code abandonPlayFocus}、会话置不活跃</td></tr>
 * </table>
 *
 * <p>第一级必须<b>立刻</b>生效（按键是毫秒级的事），第二级要稳一点（避免导航播报也停），
 * 所以判据不同。<b>不要</b>为了省事把两级合并：合并成第一级，导航播报会把电台停掉；
 * 合并成第二级，来电响铃后的头 2.5 秒按键仍然被我们吃掉，电话还是接不起来。
 */
final class CallGuard {

    /**
     * 焦点被强制拿走多久之后，判定为「通话」而不是「一声播报」。
     *
     * <p>取 2.5 秒的理由：车机导航一句播报通常 1~2 秒，来电响铃不会 2.5 秒就挂。
     */
    static final long SUSTAIN_MS = 2_500L;

    /** 轮询音频模式的间隔。通话状态变化以秒计，1 秒足够，代价可忽略。 */
    private static final long POLL_MS = 1_000L;

    /** 通话状态变化回调。<b>只会在主线程调用。</b> */
    interface Listener {
        /**
         * @param inCall {@code true} = 进入通话/响铃（该让路了）；
         *               {@code false} = 通话结束（该恢复了）
         */
        void onCallStateChanged(boolean inCall);
    }

    private static AudioManager audio;
    private static Handler main;
    private static Listener listener;

    /** 此刻音频焦点是否被「强制」拿走（LOSS_TRANSIENT，不是 CAN_DUCK）。 */
    private static volatile boolean transientLoss;

    /** 焦点被拿走已超过 {@link #SUSTAIN_MS}，按通话处理。 */
    private static volatile boolean sustainedLoss;

    /** 上一次上报给 {@link Listener} 的值，用于只在真正变化时回调。 */
    private static volatile boolean reported;

    /** 上一次看到的音频模式，用于日志去重。 */
    private static int lastMode = Integer.MIN_VALUE;

    private CallGuard() {
    }

    // ------------------------------------------------------------ 纯判据（可单测）

    /**
     * 这个音频模式算不算「通话中」。
     *
     * <p>{@code MODE_RINGTONE} 也算：来电响铃时车机处于这个模式，而用户正是
     * <b>响铃阶段</b>按「下一曲」接听 —— 漏了它就等于没修。
     */
    static boolean isCallMode(int mode) {
        return mode == AudioManager.MODE_RINGTONE
                || mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    /** 是否该暂停并交出媒体源（第二级）。 */
    static boolean isCallNow(int mode, boolean sustained) {
        return isCallMode(mode) || sustained;
    }

    /**
     * 是否该让出按键（第一级）。
     *
     * <p>比 {@link #isCallNow} 敏感：只要焦点刚被拿走就立刻让，
     * 不等 2.5 秒 —— 响铃的头两秒正是用户要按键接听的时候。
     */
    static boolean shouldYieldKeys(int mode, boolean transientLoss, boolean sustained) {
        return isCallMode(mode) || transientLoss || sustained;
    }

    // ------------------------------------------------------------ 运行时

    /** 由 {@link RadioService#onCreate()} 调用，开始监听。 */
    static void start(Context ctx, Listener l) {
        if (ctx == null) return;
        if (main == null) main = new Handler(Looper.getMainLooper());
        audio = (AudioManager) ctx.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        listener = l;
        lastMode = Integer.MIN_VALUE;
        main.removeCallbacks(pollTask);
        main.post(pollTask);
        AppLog.key("通话", "开始监听通话状态（音频模式 + 音频焦点，不申请电话权限）");
    }

    /** 由 {@link RadioService#onDestroy()} 调用。 */
    static void stop() {
        listener = null;
        transientLoss = false;
        sustainedLoss = false;
        reported = false;
        lastMode = Integer.MIN_VALUE;
        Handler h = main;
        if (h != null) {
            h.removeCallbacks(pollTask);
            h.removeCallbacks(sustainTask);
        }
    }

    /**
     * 播放器收到音频焦点变化时转进来。
     *
     * <p>{@code LOSS_TRANSIENT} 只标记「先让键」，2.5 秒不还才升级为「暂停」；
     * {@code GAIN} / {@code LOSS} 都清掉标记并立刻复评一次 —— 通话结束的第一时间
     * 就要把按键和媒体源要回来，不能等下一次轮询。
     */
    static void onFocusChange(int change) {
        Handler h = main;
        if (h == null) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            transientLoss = true;
            h.removeCallbacks(sustainTask);
            h.postDelayed(sustainTask, SUSTAIN_MS);
            AppLog.key("通话", "音频焦点被强制拿走 → 先让出按键，"
                    + (SUSTAIN_MS / 1000) + " 秒仍不还再按通话处理");
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            transientLoss = false;
            sustainedLoss = false;
            h.removeCallbacks(sustainTask);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            // 永久失去：通话结束后系统也可能先给 LOSS 再给 GAIN，两个都清干净。
            transientLoss = false;
            sustainedLoss = false;
            h.removeCallbacks(sustainTask);
        } else {
            // LOSS_TRANSIENT_CAN_DUCK：导航播报之类，可以继续小声播，什么都不做。
            return;
        }
        evaluate();
    }

    /** 通话中且已升级为「让出媒体源」。 */
    static boolean inCall() {
        return isCallNow(currentMode(null), sustainedLoss);
    }

    static boolean inCall(Context ctx) {
        return isCallNow(currentMode(ctx), sustainedLoss);
    }

    /** 按键处理前的判据：通话中 / 焦点刚被拿走 → 不处理、不吞。 */
    static boolean shouldYieldKeys() {
        return shouldYieldKeys(currentMode(null), transientLoss, sustainedLoss);
    }

    static boolean shouldYieldKeys(Context ctx) {
        return shouldYieldKeys(currentMode(ctx), transientLoss, sustainedLoss);
    }

    /** 诊断日志用：当前判据的完整快照。 */
    static String describe() {
        return describe(currentMode(null));
    }

    private static String describe(int mode) {
        String m;
        if (mode == AudioManager.MODE_RINGTONE) m = "响铃";
        else if (mode == AudioManager.MODE_IN_CALL) m = "通话中";
        else if (mode == AudioManager.MODE_IN_COMMUNICATION) m = "网络通话";
        else m = "正常";
        return "音频模式=" + m + "(" + mode + ")"
                + " 焦点被拿走=" + transientLoss
                + " 持续超时=" + sustainedLoss;
    }

    // ------------------------------------------------------------ 轮询

    private static final Runnable pollTask = new Runnable() {
        @Override public void run() {
            Handler h = main;
            if (h == null || listener == null) return;   // 已 stop
            evaluate();
            h.postDelayed(this, POLL_MS);
        }
    };

    /** 焦点被拿走满 {@link #SUSTAIN_MS}：升级为「通话」，准备暂停。 */
    private static final Runnable sustainTask = new Runnable() {
        @Override public void run() {
            if (!transientLoss) return;
            sustainedLoss = true;
            AppLog.key("通话", "音频焦点仍未归还 → 判定为通话，准备暂停并交出媒体源");
            evaluate();
        }
    };

    /** 读当前音频模式并（必要时）回调监听方。主线程调用。 */
    private static void evaluate() {
        int mode = currentMode(null);
        if (mode != lastMode) {
            lastMode = mode;
            AppLog.i("通话", "音频模式 → " + describe(mode));
        }
        boolean call = isCallNow(mode, sustainedLoss);
        if (call == reported) return;
        reported = call;
        AppLog.key("通话", (call ? "进入通话/响铃，开始让路：" : "通话结束，恢复按键与媒体源：")
                + describe(mode));
        Listener l = listener;
        if (l == null) return;
        try {
            l.onCallStateChanged(call);
        } catch (Throwable t) {
            // 让路逻辑本身不能把播放搞挂
            AppLog.w("通话", "通话状态回调异常：" + t);
        }
    }

    /**
     * 当前音频模式。传 {@code null} 时用 {@link #start} 存下的实例；
     * 服务还没起（或已停）时由调用方自己传 Context，避免拿到陈旧值。
     */
    private static int currentMode(Context ctx) {
        AudioManager am = audio;
        if (ctx != null) {
            AudioManager own = (AudioManager) ctx.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
            if (own != null) am = own;
        }
        if (am == null) return AudioManager.MODE_NORMAL;
        try {
            return am.getMode();
        } catch (Throwable t) {
            return AudioManager.MODE_NORMAL;
        }
    }
}
