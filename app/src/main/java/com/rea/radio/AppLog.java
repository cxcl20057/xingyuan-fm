package com.rea.radio;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 运行日志（诊断用）。
 *
 * <p>车机调试的痛点是没有 adb，出了问题只能靠猜。这里把关键链路逐条记录下来，
 * 直接在界面上显示：
 * <pre>
 * 16:40:12 [取址] 四川交通广播 命中缓存（3 分钟前）
 * 16:40:12 [播放] MediaPlayer.setDataSource → ytcastmp3...mp3
 * 16:40:13 [缓冲] BUFFERING_START
 * 16:40:14 [就绪] 开始播放
 * 16:41:02 [按键] MediaSession.onPause   ← 方向盘按键有没有到、走哪条路，一眼看出
 * </pre>
 *
 * <p>日志只在内存里保留最近 {@link #CAPACITY} 条，不写文件、不上传。
 */
final class AppLog {

    enum Level { INFO, WARN, ERROR, KEY }

    static final class Line {
        final long at;
        final Level level;
        final String tag;
        final String msg;

        Line(long at, Level level, String tag, String msg) {
            this.at = at;
            this.level = level;
            this.tag = tag;
            this.msg = msg;
        }

        String format() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(at))
                    + " [" + tag + "] " + msg;
        }
    }

    interface Sink {
        void onLog(AppLog.Line line);
    }

    private static final int CAPACITY = 300;

    /**
     * 派发到主线程的节流窗口（毫秒）。
     *
     * <h3>为什么必须有这个（v1.8.6 修 ANR）</h3>
     * 旧实现每写一条日志就 {@code mainHandler.post(...)} 一次，<b>没有任何上限</b>。
     * 播放器一旦进入「打不开 → 重试 → 取址 → 再打不开」的抖动循环，
     * 每轮吐出 3~5 条日志，几秒钟就能往主线程塞进<b>十万条</b> Runnable。
     * 实测线上日志（OPPO PKB110）：
     * <pre>
     * W LOG_FLOWCTRL: ==LOGS OVER PROC QUOTA(300), rows(12570) bytes(4326473) DROPPED==
     * I LegacyMessageQueue: searched 70000 messages, recent msg is
     *     { callback=com.rea.radio.AppLog$$ExternalSyntheticLambda0 ... when=-8s }
     * </pre>
     * 主线程 99% CPU 全花在处理自己的日志消息上，5 秒内没空处理触摸事件 → ANR。
     * 用户看到的是「点了没反应，一会儿整个卡死」。
     *
     * <h3>节流策略：合并 + 限频，绝不丢内容</h3>
     * <ul>
     *   <li>已有一次派发在等待 → 不再 post，只置脏标记（<b>合并</b>）。</li>
     *   <li>派发时一次性把缓冲里<b>所有</b>新行交给 sink（sink 本来就该处理整批）。</li>
     *   <li>两次派发至少间隔 {@value #DISPATCH_INTERVAL_MS} 毫秒。
     *       日志是给人看的，10 条/秒 已经远超可读速度。</li>
     * </ul>
     * 这样无论上游刷得多疯，主线程每 100ms 最多处理一条日志消息 —— 复杂度从
     * 「与日志条数成正比」降成「与时间成正比」。真实内容一条不少，
     * 因为缓冲里留着全部（上限 {@link #CAPACITY} 条），派发时整批取走。
     */
    private static final long DISPATCH_INTERVAL_MS = 100L;

    private static final ArrayDeque<Line> BUFFER = new ArrayDeque<>();
    private static final List<Sink> SINKS = new ArrayList<>();

    /** 已有一条派发消息排在主线程队列里，不要重复 post。 */
    private static boolean dispatchPending;
    /** 上次真正派发给 sink 的时刻。 */
    private static long lastDispatchAt;
    /** 上次派发时的最后一条，用于算出「这次要交付哪几条新行」。受 DISPATCH_LOCK 保护。 */
    private static Line lastDelivered;

    /** 后台线程也会写日志，所以合并状态用独立的锁保护，不能跟 BUFFER 抢同一把。 */
    private static final Object DISPATCH_LOCK = new Object();

    /**
     * 主线程 Handler，<b>延迟到第一次用到时再建</b>。
     *
     * <p>不能写成 {@code static final Handler MAIN = new Handler(Looper.getMainLooper())}：
     * 静态字段在<b>类加载</b>时就初始化，而类可能被哪个线程先加载是不确定的。
     * 一旦最先加载本类的是 {@link BootReceiver} 或播放线程这类后台线程，
     * {@code Looper.getMainLooper()} 在某些系统镜像下可能还没准备好，返回 null，
     * 于是 {@code new Handler(null)} 直接抛 NPE——注意这是<b>异常初始化</b>，
     * 之后每次访问这个类都会变成 {@code NoClassDefFoundError}，整个应用都废掉。
     *
     * <p>日志工具本该是"怎么都不会出问题"的东西，所以这里宁可多写几行。
     */
    private static volatile Handler mainHandler;

    private static Handler main() {
        Handler h = mainHandler;
        if (h != null) return h;
        synchronized (AppLog.class) {
            if (mainHandler == null) {
                Looper looper = Looper.getMainLooper();
                if (looper == null) return null;   // 真的没有主线程，那就只能放弃回调
                mainHandler = new Handler(looper);
            }
            return mainHandler;
        }
    }

    private AppLog() {
    }

    static void i(String tag, String msg) {
        add(Level.INFO, tag, msg);
    }

    static void w(String tag, String msg) {
        add(Level.WARN, tag, msg);
    }

    static void e(String tag, String msg) {
        add(Level.ERROR, tag, msg);
    }

    /** 按键 / 媒体会话事件单独标色，排查方向盘问题时就靠它。 */
    static void key(String tag, String msg) {
        add(Level.KEY, tag, msg);
    }

    private static void add(Level lv, String tag, String msg) {
        final Line line = new Line(System.currentTimeMillis(), lv, tag, msg);
        synchronized (BUFFER) {
            BUFFER.addLast(line);
            while (BUFFER.size() > CAPACITY) BUFFER.removeFirst();
        }
        // 镜像一份到 logcat（v1.8.9，Carlink 适配时加的）。
        // 起因：线上日志里本应用一行都没有，完全无法判断 app 跑到了哪一步。
        // 原因是 AppLog 此前只把日志留在内存里、供界面上的「诊断」页看，
        // 抓 logcat 时自然什么都抓不到，只能对着系统的 M→F 死循环干猜。
        //
        // 放在 add() 里而不是 Sink 里：Sink 派发在主线程且受限频，
        // 若 startForeground 那句把进程搞挂了，主线程派发根本没机会执行。
        // 直接同步写 logcat，崩溃前的最后几行才留得住。
        mirrorToLogcat(lv, tag, msg);
        // 统一回主线程派发，避免 Adapter/TextView 在子线程被写。
        // 注意：这里不再「每行一次 post」，而是合并 + 限频（见 DISPATCH_INTERVAL_MS 的说明）。
        scheduleDispatch();
    }

    /** logcat 里用它过滤：{@code adb logcat -s XingyuanRadio}。 */
    static final String LOGCAT_TAG = "XingyuanRadio";

    private static void mirrorToLogcat(Level lv, String tag, String msg) {
        String text = "[" + tag + "] " + msg;
        try {
            switch (lv) {
                case ERROR:
                    android.util.Log.e(LOGCAT_TAG, text);
                    break;
                case WARN:
                    android.util.Log.w(LOGCAT_TAG, text);
                    break;
                default:
                    android.util.Log.i(LOGCAT_TAG, text);
                    break;
            }
        } catch (Throwable ignored) {
            // 日志本身绝不允许影响主流程（部分机型的 Log 在极端情况下会抛）
        }
    }

    /**
     * 请求一次主线程派发；已有在途的、或离上次太近的，就什么都不做。
     *
     * <p>这个方法必须<b>极轻</b>：它会在日志洪水的每一行上被调用。
     * 只做三次原子性判断，不分配对象、不遍历。
     */
    private static void scheduleDispatch() {
        Handler h = main();
        if (h == null) return;   // 没有主线程 Looper：日志留在缓冲里即可，不影响业务

        long now = android.os.SystemClock.uptimeMillis();
        long delay;
        synchronized (DISPATCH_LOCK) {
            if (dispatchPending) return;      // 已有一条在排队，它会把最新内容一并带走
            long since = now - lastDispatchAt;
            if (since < 0) since = 0;         // 时钟回拨兜底
            delay = since >= DISPATCH_INTERVAL_MS ? 0 : DISPATCH_INTERVAL_MS - since;
            dispatchPending = true;
        }
        h.postDelayed(AppLog::flushToSinks, delay);
    }

    /** 把缓冲里尚未交付的行一次性交给所有 sink。跑在主线程。 */
    private static void flushToSinks() {
        List<Line> pending = new ArrayList<>();
        synchronized (BUFFER) {
            Line anchor;
            synchronized (DISPATCH_LOCK) {
                dispatchPending = false;
                lastDispatchAt = android.os.SystemClock.uptimeMillis();
                anchor = lastDelivered;
            }
            // 从上次交付位置往后取。BUFFER 会淘汰老行，所以用「是否还在缓冲里」判断已交付。
            boolean after = anchor == null;
            for (Line l : BUFFER) {
                if (!after) {
                    if (l == anchor) after = true;
                    continue;
                }
                pending.add(l);
            }
            if (!BUFFER.isEmpty()) {
                synchronized (DISPATCH_LOCK) {
                    lastDelivered = BUFFER.peekLast();
                }
            }
        }
        if (pending.isEmpty()) return;

        List<Sink> copy;
        synchronized (SINKS) {
            if (SINKS.isEmpty()) return;      // 没有 sink：连遍历都省掉（当前产品形态即如此）
            copy = new ArrayList<>(SINKS);
        }
        for (Line l : pending) {
            for (Sink s : copy) s.onLog(l);
        }
    }

    static void addSink(Sink s) {
        synchronized (SINKS) {
            if (!SINKS.contains(s)) SINKS.add(s);
        }
    }

    static void removeSink(Sink s) {
        synchronized (SINKS) {
            SINKS.remove(s);
        }
    }

    static List<Line> snapshot() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
        // 派发状态一起复位。
        // 这里不只是为了测试干净：「清空日志」是用户的明确意图（诊断面板上的「清空」按钮），
        // 若此时恰好有一条派发消息在排队、且它随后被取消/丢弃，dispatchPending 就永久卡在 true，
        // 之后所有日志再也不会上屏 —— 界面看着像「日志功能坏了」，其实是锁死了。
        synchronized (DISPATCH_LOCK) {
            lastDelivered = null;
            dispatchPending = false;
            lastDispatchAt = 0;   // 清空后第一条日志应当立即派发，不被限频窗口挡住
        }
    }
}
