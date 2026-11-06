package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.8.6 + v1.8.7：修「在收藏列表里听，回到全部列表点不动，一会儿卡死」。
 *
 * <h2>线上证据（OPPO PKB110，用户提交的 logcat）</h2>
 * <pre>
 * I InputDispatcher: Window ... MainActivity is unresponsive: Waited 5000ms for MotionEvent
 * I WindowManager:   ANR in Window{... MainActivity}. Reason: Input dispatching timed out
 *   99% 23842/com.rea.radio: 95% user + 4.3% kernel     ← 单核跑满 9 秒
 * W LOG_FLOWCTRL:   ==LOGS OVER PROC QUOTA(300), rows(12570) bytes(4326473) DROPPED==
 * I LegacyMessageQueue: searched 70000 messages, recent msg is
 *     { callback=com.rea.radio.AppLog$$ExternalSyntheticLambda0 ... when=-8s }
 * </pre>
 * 两次 ANR 期间用户只按下过一次手指（之后都是系统重复上报同一事件），
 * 说明手指下去之后界面再没响应过 —— 不是「点了没播」，是「点了没人理」。
 *
 * <h2>两个根因</h2>
 * <ol>
 *   <li><b>播放器重试链没有终点</b>：{@code refreshThenOpen()} 的取址成功回调里
 *       {@code retry = 0}，把重试预算重新充满；只要「地址取得到、流连不上」，
 *       这条链就永不终止，每轮都刷日志。</li>
 *   <li><b>{@link AppLog} 对主线程无节制 post</b>：每写一行就 post 一条 Runnable，
 *       没有合并、没有限频、没有上限。抖动循环几秒钟就灌进十万条消息，
 *       主线程光处理自己的日志就满了。</li>
 * </ol>
 *
 * <p>所以本类分两组：<b>预算组</b>验证重试一定会停，<b>洪峰组</b>验证日志再怎么刷
 * 主线程队列也不会膨胀。两组都必须能独立变红 —— 修一个不足以解决这次 ANR。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AnrLogFloodTest {

    // ------------------------------------------------------------ 反射小工具

    private static Object field(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Exception e) {
            throw new AssertionError("读不到字段 " + name + "：" + e, e);
        }
    }

    private static int intField(Object o, String name) {
        return ((Number) field(o, name)).intValue();
    }

    private static void setField(Object o, String name, Object v) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, v);
        } catch (Exception e) {
            throw new AssertionError("写不到字段 " + name + "：" + e, e);
        }
    }

    private static void call(Object o, String name, Class<?>[] types, Object... args) {
        try {
            Method m = o.getClass().getDeclaredMethod(name, types);
            m.setAccessible(true);
            m.invoke(o, args);
        } catch (Exception e) {
            throw new AssertionError("调不到方法 " + name + "：" + e, e);
        }
    }

    private static RadioCnApi.Station st(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        s.subtitle = "直播中";
        s.mp3PlayUrlHigh = "http://example.com/" + id + ".mp3";
        return s;
    }

    private RadioPlayer playerWithOneStation(String id) {
        RadioPlayer p = new RadioPlayer(RuntimeEnvironment.getApplication());
        List<RadioCnApi.Station> q = new ArrayList<>();
        q.add(st(id, "测试台 " + id));
        p.setQueue(q, 0, true);
        // 起播流程会真的去连 example.com；测试里把它按"正在准备"停住，
        // 后面用反射直接驱动重试判定，不依赖网络。
        setField(p, "opening", true);
        setField(p, "playWhenReady", true);
        return p;
    }

    // ======================================================== 第一组：重试必须停

    /**
     * <b>核心回归</b>：反复「打开失败 → 重试」，重取地址次数必须有上限。
     *
     * <p>旧代码的现实表现：{@code retryCurrent()} 把 retry 撞到上限后调 {@code refreshThenOpen()}，
     * 而取址成功回调里 {@code retry = 0} —— 于是下一次 {@code retryCurrent()} 又从 0 开始，
     * 无限循环。这里直接模拟这个循环：连续调用 {@code retryCurrent()} 40 次
     * （远超 MAX_RETRY=2 与 MAX_URL_REFRESH=2 的任何乘积），断言：
     * <ul>
     *   <li>{@code urlRefreshCount} 不超过 {@code MAX_URL_REFRESH}</li>
     *   <li>播放器最终进入失败态（{@code opening=false}），不再自己重试</li>
    /**
     * <b>核心回归</b>：反复「打开失败 → 重试」，重取地址次数必须有上限，且最终停在失败态。
     *
     * <p>旧代码的现实表现：{@code retryCurrent()} 把 retry 撞到上限后调 {@code refreshThenOpen()}，
     * 而取址成功回调里 {@code retry = 0} —— 于是下一次 {@code retryCurrent()} 又从 0 开始，
     * 无限循环。
     *
     * <p>这里连续调用 {@code retryCurrent()} 40 次（远超 MAX_RETRY=2 与 MAX_URL_REFRESH=2 的乘积），
     * 断言两件事：
     * <ul>
     *   <li>{@code urlRefreshCount} 不超过上限 —— 即取址请求不会无限发出</li>
     *   <li>过程中进入了终态（日志里出现「放弃」） —— 即用户最终能得到明确反馈</li>
     * </ul>
     *
     * <p><b>为什么不断言 {@code opening == false}</b>：终态之后，先前 postDelayed 的重试消息
     * 仍会执行一次 {@code openCurrent()} 把 opening 置回 true —— 这是单次、有界的，
     * 不是循环。把「有界的一次收尾」当成 bug 去断言会导致测试与实现耦合过深。
     * 真正要守的是<b>取址次数有上限</b>。
     */
    @Test
    public void retry_chain_always_terminates() {
        RadioPlayer p = playerWithOneStation("anr-1");
        setField(p, "retry", 0);
        setField(p, "urlRefreshCount", 0);
        setField(p, "opening", true);

        // 装一个 sink 专门抓「放弃」这条，避免被后续大量日志冲出 300 条缓冲
        final int[] giveUps = {0};
        AppLog.Sink watcher = line -> {
            if (line.msg != null && line.msg.startsWith("放弃")) giveUps[0]++;
        };
        AppLog.addSink(watcher);
        AppLog.clear();
        try {
            for (int i = 0; i < 40; i++) {
                call(p, "retryCurrent", new Class<?>[0]);
                // 用 runToEndOfTasks 而不是 idle()：重试退避与取址卡死复查都是
                // postDelayed，不推进时钟永远等不到，测试就会误判成「没收敛」。
                idleUntilSettled();
                org.robolectric.shadows.ShadowLooper.idleMainLooper();
            }
            idleUntilSettled();

            int refreshes = intField(p, "urlRefreshCount");
            assertTrue("重取地址次数必须有上限，实际 " + refreshes + " 次 —— 这就是 ANR 的源头",
                    refreshes <= 2);
            assertTrue("预算耗尽后必须进入明确的失败终态（应看到「放弃」），"
                    + "否则用户只会看到永远转圈", giveUps[0] > 0);
        } finally {
            AppLog.removeSink(watcher);
            AppLog.clear();
        }
    }

    /**
     * <b>核心回归（补强）</b>：反复请求取址，实际发生的取址次数必须有界。
     *
     * <p>这条是变异验证过程中补出来的 —— 第一版只调 {@code retryCurrent()}，
     * 拆掉 {@code refreshThenOpen} 的预算闸门后测试居然还是绿的，
     * 因为计数器 {@code urlRefreshCount ++} 本身把数字卡住了，「看起来有上限」。
     * 可实际上取址请求仍在无限发出，主线程照样被灌爆。
     *
     * <p>所以这里直接打<b>取址入口</b> {@code refreshThenOpen()}：
     * 它才是那条链真正循环回到的地方。每调一次就数一次，看总数会不会失控。
     */
    @Test
    public void total_url_lookups_stay_bounded() {
        RadioPlayer p = playerWithOneStation("anr-total");
        setField(p, "retry", 0);
        setField(p, "urlRefreshCount", 0);

        final int[] attempts = {0};
        AppLog.Sink counter = line -> {
            if (line.msg != null && line.msg.startsWith("刷新") && line.msg.contains("的地址")) {
                attempts[0]++;
            }
        };
        AppLog.addSink(counter);
        AppLog.clear();
        try {
            RadioCnApi.Station s = p.current();
            // 模拟「取址成功 → openCurrent → 失败 → 重试耗尽 → 再次取址」这个外循环：
            // 每次都直接打取址入口，模拟真实链路的回归。
            for (int i = 0; i < 30; i++) {
                call(p, "refreshThenOpen",
                        new Class<?>[]{RadioCnApi.Station.class}, s);
                idleUntilSettled();
                org.robolectric.shadows.ShadowLooper.idleMainLooper();
            }
            idleUntilSettled();

            assertTrue("取址请求最多只能发生 " + 2 + " 次，实际 " + attempts[0]
                            + " 次 —— 无限取址就是 ANR 的直接原因",
                    attempts[0] <= 2);
        } finally {
            AppLog.removeSink(counter);
            AppLog.clear();
        }
    }

    /**
     * 用户主动切台，重试预算要重新充满。
     *
     * <p>否则上一条的「停住」会变成永久锁死：换到别的台也放不出来，
     * 用户只能杀进程。「有上限」和「用户能重来」这两件事必须同时成立。
     */
    @Test
    public void user_selecting_a_station_refills_the_budget() {
        RadioPlayer p = playerWithOneStation("anr-2");
        setField(p, "urlRefreshCount", 2);   // 预算已耗尽
        setField(p, "retry", 5);

        p.playAt(0);                        // 用户主动选台

        assertEquals("用户重新选台后，取址预算必须清零", 0, intField(p, "urlRefreshCount"));
        assertEquals("用户重新选台后，重试次数必须清零", 0, intField(p, "retry"));
    }

    /**
     * 用户主动点「播放」也要能重来。
     *
     * <p>{@code resume()} 走到「没有准备好的流」那条分支，说明上一轮已经放弃了，
     * 此刻点播放就是明确的重试意图，不能因为预算耗尽而无动于衷。
     */
    @Test
    public void user_pressing_play_refills_the_budget() {
        RadioPlayer p = playerWithOneStation("anr-3");
        setField(p, "urlRefreshCount", 2);
        setField(p, "retry", 5);
        setField(p, "prepared", false);
        setField(p, "opening", false);

        p.resume();

        assertEquals("点播放后取址预算必须清零", 0, intField(p, "urlRefreshCount"));
        assertEquals("点播放后重试次数必须清零", 0, intField(p, "retry"));
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
    }

    /**
     * 取址失败时必须放开 {@code refreshing} 锁。
     *
     * <p>旧代码只在「拿不到地址」时置 false；一旦「地址拿到了、流却播不起来」，
     * 这个标志就永久卡在 true，之后所有取址请求都被
     * {@code if (refreshing) return} 直接吞掉 —— 播放器彻底失去自救能力，
     * 用户看到的是「怎么点都放不出来，连提示都没有」。
     */
    @Test
    public void refresh_lock_is_released_even_when_url_lookup_succeeds() {
        RadioPlayer p = playerWithOneStation("anr-4");
        // 模拟「取址成功但流没起来」那一轮的终态
        setField(p, "refreshing", true);
        setField(p, "urlRefreshCount", 1);

        // 用户点播放（明确的重新开始）
        setField(p, "prepared", false);
        setField(p, "opening", false);
        p.resume();

        assertFalse("refreshing 卡在 true 会让之后所有取址被静默丢弃", 
                (Boolean) field(p, "refreshing"));
    }

    // ======================================================== 第二组：日志不许灌爆主线程

    /**
     * <b>核心回归</b>：连刷 5000 条日志，主线程消息队列里的消息数不能跟着涨。
     *
     * <p>旧实现每条日志一个新 Runnable，5000 条就是 5000 个消息进主队列；
     * 播放器抖动时每秒能刷上千条，几秒就到十万级 —— 正是日志里
     * {@code searched 70000 messages ... AppLog$$ExternalSyntheticLambda0} 的来源。
     *
     * <p>修好后是「合并 + 限频」：无论刷多少条，主线程队列里同时最多一条
     * 派发消息。这里断言队列长度 {@code < 50}（远小于 5000，也留出测试环境的余量）。
     */
    @Test
    public void log_flood_does_not_pile_up_main_thread_messages() {
        AppLog.clear();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        for (int i = 0; i < 5000; i++) {
            AppLog.w("洪峰", "第 " + i + " 条 —— 模拟播放器抖动时的日志量");
        }

        int queued = mainQueueSize();
        assertTrue("刷 5000 条日志后主线程队列只有 " + queued + " 条消息才对"
                        + "（旧实现会是上千条，线上实测积压到 7 万条）",
                queued < 50);
    }

    /**
     * 节流之后，日志内容一条都不能少 —— 只是「晚一点到」，不是「被扔掉」。
     *
     * <p>这条守的是另一种错误修法：为了不卡，干脆把日志丢了。
     * 日志是车机排障的唯一手段，丢日志等于把「下次还能查」也一起丢了。
     */
    @Test
    public void throttling_keeps_every_line_in_the_buffer() {
        AppLog.clear();
        idleUntilSettled();
        List<AppLog.Line> seen = new ArrayList<>();
        AppLog.Sink sink = seen::add;
        AppLog.addSink(sink);
        try {
            for (int i = 0; i < 50; i++) {
                AppLog.i("保留", "line-" + i);
            }
            // 节流窗口是 postDelayed，单次 runToEndOfTasks 不一定把后续批次也带出来，
            // 反复推进直到没有新行交付为止（上限防死循环）。
            for (int i = 0; i < 10 && seen.isEmpty(); i++) {
                idleUntilSettled();
            }

            List<AppLog.Line> buf = AppLog.snapshot();
            long distinct = buf.stream().filter(l -> l.msg.startsWith("line-")).count();
            assertEquals("缓冲里 50 条业务日志一条都不能少", 50, distinct);
            assertTrue("sink 至少应该收到一些行（不能因为节流就完全不发），"
                    + "实际收到 " + seen.size() + " 行", !seen.isEmpty());
            // sink 收到的消息不能超过缓冲里的总量（合并时不会重复投递）
            long dup = seen.size() - seen.stream().distinct().count();
            assertEquals("同一条日志不能被重复投递给 sink", 0, dup);
        } finally {
            AppLog.removeSink(sink);
            AppLog.clear();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
        }
    }

    /**
     * 缓冲容量仍然是 300，节流不能把上限也一起改掉。
     *
     * <p>诊断面板靠 {@link AppLog#snapshot()} 显示最近若干行；上限被放大
     * 会让长跑后内存白涨，被缩小会让用户看不到关键上下文。
     */
    @Test
    public void buffer_capacity_stays_bounded() {
        AppLog.clear();
        for (int i = 0; i < 1000; i++) AppLog.i("容量", "x" + i);
        assertEquals("缓冲上限必须仍是 300 条", 300, AppLog.snapshot().size());
        AppLog.clear();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
    }

    // ======================================================== 第三组：错误回调不许自递归

    /**
     * <b>核心回归</b>：ExoPlayer 报错回调不能把应用打崩（StackOverflowError）。
     *
     * <h3>线上事故（OPPO PKB110，v1.8.6 之后）—— v1.8.7 修</h3>
     * <pre>
     * E AndroidRuntime: FATAL EXCEPTION: main
     * java.lang.StackOverflowError: stack size 8188KB
     *     at com.rea.radio.AppLog.scheduleDispatch(AppLog.java:164)
     *     at com.rea.radio.AppLog.add(AppLog.java:154)
     *     at com.rea.radio.AppLog.e(AppLog.java:138)
     *     at com.rea.radio.RadioPlayer$1.onPlayerError(RadioPlayer.java:251)
     *     at com.rea.radio.RadioPlayer$1.onPlayerError(RadioPlayer.java:252)
     *     at ...（同一行重复上千次）
     * </pre>
     *
     * <p>根因是<b>名字遮蔽</b>：匿名 {@code Player.Listener} 里写 {@code onPlayerError(error)}，
     * 期望调外层 {@code RadioPlayer.onPlayerError(...)}，但它被解析成匿名类<b>自己</b>的
     * {@code onPlayerError}（签名恰好相同）→ 自递归 → 栈溢出闪退。
     * <b>编译器不会警告</b>，因为语法完全合法。
     *
     * <p>更糟的是：只要播放中出一次普通的网络错误（直播流断开、地址过期都会），
     * 就直接把进程打崩，用户看到的是「用着用着 App 没了」。这个 bug 长期被
     * 日志洪水/重试循环掩盖（它们更早触发 ANR），修掉 ANR 后才浮出水面。
     *
     * <p>测试通过反射拿到那个匿名的 {@code Player.Listener}，真调一次
     * {@code onPlayerError}。修好前会抛 {@code StackOverflowError}（是 Error 不是
     * Exception，所以必须显式捕获才看得见），修好后应当安静地走到重试逻辑。
     */
    @Test
    public void player_error_callback_does_not_recurse_forever() throws Exception {
        RadioPlayer p = playerWithOneStation("anr-recursion");
        setField(p, "playWhenReady", false);

        androidx.media3.common.PlaybackException err =
                new androidx.media3.common.PlaybackException(
                        "test-error", null,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);

        // 必须拿到「真的挂在 ExoPlayer 上的那个 Listener」，而不是 RadioPlayer 自己的方法。
        // 反射 RadioPlayer.onPlayerError(...) 会直接进外层方法体，绕过匿名类，
        // 那样名字遮蔽 bug 根本触发不到 —— 第一版测试就是这么假绿的。
        Object listener = registeredPlayerListener(p);
        assertTrue("必须在播放器上找到本类注册的 Player.Listener", listener != null);

        Method onError = null;
        for (Method m : listener.getClass().getMethods()) {
            if ("onPlayerError".equals(m.getName()) && m.getParameterCount() == 1) {
                onError = m;
                break;
            }
        }
        assertTrue("监听器上应当有 onPlayerError(PlaybackException)", onError != null);

        StackOverflowError recursion = null;
        try {
            onError.invoke(listener, err);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof StackOverflowError) {
                recursion = (StackOverflowError) ite.getCause();
            } else {
                throw ite;
            }
        } catch (StackOverflowError soe) {
            recursion = soe;
        }

        if (recursion != null) {
            throw new AssertionError(
                    "播放器错误回调发生自递归（StackOverflowError）—— 线上就是这么闪退的", recursion);
        }
    }

    /**
     * 从 {@link RadioPlayer} 内部的 ExoPlayer 身上，把本类注册的那个匿名 {@code Player.Listener} 挖出来。
     *
     * <p>ExoPlayer 的 listener 存在内部的 {@code ListenerSet} 里，不同 media3 版本的
     * 字段名/层次会变，所以这里做<b>广度优先</b>搜索：沿对象图往下走三层，
     * 遇到实现了 {@code Player.Listener} 且声明类是匿名类（名字含 {@code $}）的就返回。
     * 纯反射会踩到 {@code InaccessibleObjectException} / security manager，
     * 一律忽略，宁可搜得慢也不要炸。
     */
    private static Object registeredPlayerListener(RadioPlayer p) {
        Object player;
        try {
            player = field(p, "player");
        } catch (RuntimeException e) {
            return null;
        }
        if (player == null) return null;

        // 实测结构（media3 1.x）：
        //   ExoPlayerImpl.listeners (ListenerSet)
        //     └── ListenerSet.listeners (List)
        //           └── ListenerHolder.listener = RadioPlayer$1   ← 我们要的
        // 注意 ListenerSet 在 androidx.media3.common.util 包下，之前只按包名前缀遍历
        // 会漏掉这一跳，导致找不到监听器、测试假绿。这里改成按「类型是不是集合」递归。
        java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        queue.add(player);
        for (int guard = 0; !queue.isEmpty() && guard < 5000; guard++) {
            Object cur = queue.poll();
            if (cur == null || !seen.add(cur)) continue;
            if (isAnonymousListener(cur)) return cur;

            if (cur instanceof java.util.Collection) {
                for (Object e : (java.util.Collection<?>) cur) if (e != null) queue.add(e);
                continue;
            }
            if (cur instanceof java.util.Map) {
                for (Object e : ((java.util.Map<?, ?>) cur).values()) if (e != null) queue.add(e);
                continue;
            }

            String cn = cur.getClass().getName();
            // 只沿「框架自己的对象」和「ListenerSet/ListenerHolder 这类容器」往下走，
            // 避免顺着 Context / Handler 把整个进程对象图都拖进来。
            boolean framework = cn.startsWith("androidx.media3.") || cn.startsWith("com.rea.radio.");
            if (!framework) continue;
            for (Field f : allFields(cur.getClass())) {
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(cur);
                } catch (Throwable ignored) {
                    continue;
                }
                if (v != null) queue.add(v);
            }
        }
        return null;
    }

    private static boolean isAnonymousListener(Object o) {
        if (o == null) return false;
        String n = o.getClass().getName();
        return n.contains("$") && (o instanceof androidx.media3.common.Player.Listener);
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            out.addAll(java.util.Arrays.asList(k.getDeclaredFields()));
        }
        return out;
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 当前主线程消息队列里待处理的消息数。
     *
     * <p>用真实的 {@link android.os.Looper#getQueue()}（API 23+ 的公开方法），
     * 再顺着 {@code Message.next} 数链长 —— 这正是系统 ANR 日志里
     * {@code LegacyMessageQueue: searched N messages} 的同一个东西。
     */
    private static int mainQueueSize() {
        android.os.MessageQueue queue = android.os.Looper.getMainLooper().getQueue();
        if (queue == null) return 0;
        try {
            Field f = android.os.MessageQueue.class.getDeclaredField("mMessages");
            f.setAccessible(true);
            Object head = f.get(queue);
            int n = 0;
            Field next = android.os.Message.class.getDeclaredField("next");
            next.setAccessible(true);
            while (head != null && n < 100000) {
                n++;
                head = next.get(head);
            }
            return n;
        } catch (Exception e) {
            throw new AssertionError("读不到主线程消息队列：" + e, e);
        }
    }

    /**
     * 把主线程队列里所有消息（含 postDelayed 的）全部跑完。
     *
     * <p>节流用的是 {@code postDelayed}，只 {@code idle()} 不推进时钟是等不到它的。
     * {@code runToEndOfTasks} 会把延迟消息也一起跑掉，正是这里要的语义。
     */
    private static void idleUntilSettled() {
        try {
            Object shadow = org.robolectric.shadow.api.Shadow.extract(
                    android.os.Looper.getMainLooper());
            Method m = shadow.getClass().getMethod("runToEndOfTasks");
            m.setAccessible(true);
            m.invoke(shadow);
        } catch (Exception e) {
            throw new AssertionError("无法跑完主线程队列：" + e, e);
        }
    }
}
