package com.rea.radio;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.ecarx.eas.sdk.ECarXApiClient;
import com.ecarx.eas.sdk.mediacenter.AbstractMusicPlaybackInfo;
import com.ecarx.eas.sdk.mediacenter.MediaCenterAPI;
import com.ecarx.eas.sdk.mediacenter.MediaInfo;
import com.ecarx.eas.sdk.mediacenter.MediaInfoWrapper;
import com.ecarx.eas.sdk.mediacenter.MediaListInfo;
import com.ecarx.eas.sdk.mediacenter.MediaListInfoBean;
import com.ecarx.eas.sdk.mediacenter.MediaListsInfo;
import com.ecarx.eas.sdk.mediacenter.RegisterRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把本应用注册成星愿车机媒体中心的媒体源（EAS）。
 *
 * <h3>这一步到底解决了什么</h3>
 *
 * <p>v1.9.0 修好了「前台按方向盘有反应」，但切到后台（看导航、回桌面）就没反应了。
 * 根因是车机把方向盘私有键码（346/347/348）派给<b>当前焦点窗口</b>，
 * 后台应用天然收不到。
 *
 * <p>能后台受控的应用（QQ 音乐、网易云）走的是另一条路：注册成媒体中心媒体源，
 * 由媒体中心把按键翻译成 {@code onPlay / onNext / onPrevious} 回调。
 * 顺带的副作用正好是用户观察到的现象 —— 这些应用在桌面会有个<b>常驻的小播放控件</b>，
 * 那个控件就是媒体中心的「当前媒体源卡片」。
 *
 * <h3>为什么用 EAS 而不是 MediaSession</h3>
 *
 * <p>标准 Android 的 {@code MediaSession} 只在「标准媒体键」上有效。
 * 星愿车机不发标准媒体键，它自己有一套媒体中心（{@code ecarx.xsf.mediacenter}）
 * 负责媒体源仲裁，方向盘按键由它按「谁持有播放焦点」来分发。
 * 所以必须向它注册，MediaSession 再完整也没用（这是 v1.9.0 之前的结论）。
 *
 * <h3>SDK 从哪来</h3>
 *
 * <p>{@code app/libs/eas-sdk.jar} 是从<b>网易云车机版</b> APK 里提取的
 * {@code com.ecarx.eas.*} / {@code ecarx.xsf.*} 类（见 {@code docs/方向盘按键.md} 第七章）。
 * 网易云本身是普通第三方应用（装在 {@code /data/app}），证明：
 * <ul>
 *   <li>第三方应用<b>不需要系统签名</b>就能注册成媒体源；</li>
 *   <li>不需要额外的 signature 级权限（它的清单里也没有 EAS 相关权限）。</li>
 * </ul>
 *
 * <p>代价是这份 SDK 属于「逆向提取」，厂商升级媒体中心后接口可能变。
 * 所以这里所有调用都包了 {@code try/catch (Throwable)}：
 * <b>EAS 不通时应用要完整降级为 v1.9.0 的前台行为，绝不允许崩或卡住。</b>
 *
 * <h3>连接是异步的，别指望 init 完就能用</h3>
 *
 * <p>{@code api.init()} 只是把 Context 交给框架，真正的 binder 连接在后台线程完成，
 * 完成后回调 {@code onAPIReady(true)}。在它之前调 {@code registerMusic}
 * 只会拿到 SDK 的一句日志：
 * <pre>
 * E MediaCenterAPI: >> please wait for mediacenter init success!!! &lt;&lt;
 * </pre>
 * 并返回 {@code null}。所以注册逻辑放在回调里，另外还有一层定时重试兜底
 * （实测媒体中心进程被重启后回调不会再来一次）。
 */
final class EasMediaCenter implements EasBridge.Sink {

    private static final String TAG = "媒体源";

    /** 注册失败/框架未就绪时的快速重试间隔：5 次、约 48 秒内试满。 */
    private static final long[] FAST_RETRY_MS = { 800L, 2_000L, 5_000L, 10_000L, 30_000L };

    /**
     * 快速重试用尽后的慢速自愈间隔，<b>不写日志</b>。
     *
     * <p>早期实现是「试满 5 次就彻底放弃」，这里必须改掉，原因是本应用随开机自启
     * （{@code BootReceiver}），而车机媒体中心 {@code ecarx.xsf.mediacenter} 的启动
     * 比我们晚是常态 —— 一旦 48 秒内没连上，<b>那一次开机的后台方向盘就永久废了</b>，
     * 只能手动重开 App 才恢复。改成慢速长期重试后，媒体中心起来后自己会接上。
     *
     * <p>慢速阶段不写日志，是因为手机上（没有 {@code ecarx.xsf.mediacenter} 服务）
     * 永远连不上，天天刷日志没意义；快速阶段的失败原因仍然是可诊断的。
     */
    private static final long SLOW_RETRY_MS = 3 * 60_000L;

    /**
     * 播放状态变化时的补试节流。
     *
     * <p>用户按下播放/切台就是「我现在要用」的信号，这时如果还没注册上，
     * 立刻补试一次比干等 3 分钟合理得多 —— 大多数「开机没反应」会被一次按键救回来。
     */
    private static final long RETRY_ON_PUSH_GAP_MS = 3_000L;

    /** 媒体列表在「媒体源清单」页显示的标题。 */
    private static final String LIST_TITLE = "星愿电台";

    /**
     * 一次推送的列表项上限。
     *
     * <p>电台接口按省返回，通常 20~80 个；但直辖市/全国视角下有上百个。
     * Binder 单次事务有 1MB 上限，列表项带 N 个字符串，理论上千条才危险。
     * 这里仍设个上限，宁可少列也不要在弱配置车机上抛 TransactionTooLarge。
     */
    private static final int MAX_LIST_ITEMS = 300;

    private static EasMediaCenter instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaCenterAPI api;
    private EasMusicClient client;

    /** registerMusic 的返回值，后续所有调用都要带上它（框架用它标识「哪个客户端」）。 */
    private volatile Object token;

    private volatile RadioPlayer player;

    /** 主线程维护的最新快照，给 Binder 线程上的 {@code getMusicPlaybackInfo()} 用。 */
    private volatile EasPlaybackInfo last;

    /** 队列快照（MediaInfo 列表），给 Binder 线程上的 {@code getPlaylist()} 用。 */
    private volatile List<MediaInfo> lastItems = Collections.emptyList();

    /** 最近一次推给媒体中心的媒体列表。 */
    private volatile MediaListInfo lastList;

    /** 上次推送的列表指纹：内容或当前下标变了才重推，避免每次状态变化都传一遍全表。 */
    private String lastListKey;

    /** 我们当前是否持有媒体中心的播放焦点。 */
    private volatile boolean hasFocus;

    /** 上次上报的播放状态，用于只在新状态时记日志。 */
    private int lastPushedStatus = -1;

    private boolean initStarted;
    /** 快速重试已用掉几次（到 {@link #FAST_RETRY_MS} 长度后转慢速）。 */
    private int fastRetryIndex;

    /** 上次真正发起注册尝试的时刻（{@code elapsedRealtime}），给 push 补试做节流。 */
    private long lastAttemptAt;

    /** 服务已解绑：此后不再重试，避免重建时旧实例还在后台空转。 */
    private boolean disposed;

    /** 排队的重试任务，便于去重（同一时刻只允许一个）。 */
    private final Runnable retryTask = new Runnable() {
        @Override public void run() {
            if (disposed || token != null) return;
            if (api == null) {
                // 上一次连 api 都没拿到（媒体中心进程还没起）。允许重新连一次，
                // 否则 api 永远是 null，后面的 register() 只会空转。
                initStarted = false;
                start();
                return;
            }
            register();
        }
    };

    private EasMediaCenter(Context app) {
        this.app = app;
    }

    /**
     * 装到服务上。幂等：服务重建时只更新 {@link RadioPlayer} 引用，不重复注册。
     *
     * <p>调用点：{@code RadioService.onCreate()} —— 必须在播放器建好之后、
     * 进前台之前。晚了会漏掉「开机就播」那一次状态上报。
     */
    static void install(Context ctx, RadioPlayer p) {
        if (ctx == null || p == null) return;
        EasMediaCenter c = instance;
        if (c != null) {
            c.player = p;
            return;
        }
        c = new EasMediaCenter(ctx.getApplicationContext());
        c.player = p;
        instance = c;
        EasBridge.setSink(c);
        c.start();
    }

    /** 服务销毁时解绑。{@code MediaCenterAPI} 是进程级单例，这里<b>不</b>调 release —— 见方法内说明。 */
    static void uninstall() {
        EasMediaCenter c = instance;
        if (c == null) return;
        instance = null;
        EasBridge.setSink(null);
        // 交还按键派发权：服务停掉后媒体中心不再回调我们，
        // 前台私有键分支必须重新接手，否则会变成「前台按了也没反应」。
        EasBridge.setActive(false);
        c.player = null;
        // 停掉排队的重试：RadioService 是 START_STICKY、会被反复重建，
        // 不清理的话旧实例（连同它的 MediaCenterAPI 引用）一直被 Handler 队列攥着。
        c.disposed = true;
        c.main.removeCallbacksAndMessages(null);
        // 注意：故意不调 api.release()。
        //
        // MediaCenterAPI 背后是进程级单例 + 框架级 binder 连接，release 会把它关掉，
        // 而 RadioService 是 START_STICKY、会被系统反复重建（内存回收、Carlink 切屏）。
        // 一旦关掉连接，重建后要重新 init 一整套，媒体中心那边还会残留旧客户端。
        // 代价只是多一个空引用，进程死了自然就没了，不值得为它冒「重建后注册不上」的险。
    }

    // ------------------------------------------------------------ 连接与注册

    private void start() {
        if (initStarted) return;
        initStarted = true;
        try {
            api = MediaCenterAPI.get(app);
            api.init(app, new ECarXApiClient.Callback() {
                @Override public void onAPIReady(final boolean ok) {
                    AppLog.key(TAG, "EAS 框架就绪回调：" + ok);
                    if (!ok) return;
                    main.post(new Runnable() {
                        @Override public void run() { register(); }
                    });
                }
            });
            AppLog.key(TAG, "已向 EAS 媒体中心发起连接（package=" + app.getPackageName() + "）");
            // 兜底：部分车机版本不会回调 onAPIReady，靠定时重试也能注册上。
            scheduleRetry();
        } catch (Throwable t) {
            if (t instanceof NoClassDefFoundError || t instanceof ClassNotFoundException) {
                // SDK 类根本不在包里（改了依赖、或 future 误删 libs/）。重试也不会有结果。
                AppLog.w(TAG, "缺少 EAS SDK 类，跳过媒体源注册（降级为仅前台可用方向盘）：" + t);
                return;
            }
            // 典型原因：非星愿车机（没有 com.ecarx.eas.carservice）、
            // 或媒体中心进程还没起来（开机自启时很常见）。都只降级，不影响播放，稍后重试。
            AppLog.w(TAG, "EAS 暂时不可用，稍后重试（期间降级为仅前台可用方向盘）：" + t);
            scheduleRetry();
        }
    }

    private void register() {
        if (disposed || token != null) return;
        if (api == null) return;
        lastAttemptAt = android.os.SystemClock.elapsedRealtime();
        try {
            if (client == null) client = new EasMusicClient(this);
            // 必须用「类版 + RegisterRequest」这个重载。
            //
            // api.registerMusic(IMusicClient) 在 MediaCenterProxy 里是空壳
            // （aconst_null; areturn），永远返回 null —— 实测就卡在这一步。
            // 类版才会真正转发到 ecarx.xsf.mediacenter 服务。
            RegisterRequest request = new RegisterRequest.Builder()
                    .packageName(app.getPackageName())
                    // displayId 0 = 主屏；zoneId 0 = 默认音区（驾驶位）。
                    // 星愿是单屏单音区车型，两个都取默认值。
                    .displayId(0)
                    .zoneId(0)
                    .build();
            Object t = api.registerMusic(client, request);
            if (t == null) {
                warnDuringFastPhase("registerMusic 返回 null（框架还没连上）");
                scheduleRetry();
                return;
            }
            token = t;
            fastRetryIndex = 0;
            hasFocus = false;
            // 从这一刻起按键由媒体中心派发。必须同时把前台那条私有键分支关掉，
            // 否则 App 在前台时一次按键会切两个台（媒体中心是「焦点窗口 + 媒体源」
            // 各派一份，实测间隔约 233ms）。
            EasBridge.setActive(true);
            AppLog.key(TAG, "✅ 已注册为媒体中心媒体源，token=" + t
                    + "（前台私有键分支已让位，避免一次按键切两台）");
            // 关键一步：声明媒体源类型 + 抢焦点 + 指定当前源类型。
            // 顺序照抄酷我车机版（见 docs/酷我车机版逆向分析.md），差一步都不生效。
            declareSourceType(t);
            // 顺手把车机的媒体源表倒进日志（一次即可）：排查「点媒体条打开的是别人」
            // 时，先看 6 归谁、谁优先级更高。
            dumpAppSources();
            // 注册后立刻上报一次，媒体中心的卡片才有内容可显示。
            push(player);
        } catch (Throwable t) {
            warnDuringFastPhase("registerMusic 异常：" + t);
            scheduleRetry();
        }
    }

    /**
     * 声明媒体源类型 → 抢播放焦点 → 指定当前源类型。
     *
     * <h3>为什么这三步必须成组、且顺序不能变</h3>
     *
     * <p>逆向「星愿车控完全正常酷我音乐 6.0.0.9」得到的调用序列（{@code onAPIReady}）：
     * <pre>
     * registerMusic(pkg, this)              → token
     * updateMediaSourceTypeList(token, {6})  // 声明「我是哪一族音源」
     * requestPlay(token)                     // 拿播放焦点
     * updateCurrentSourceType(token, 6)      // 把当前源类型落成 6
     * </pre>
     *
     * <p>老版本只往 {@code getSourceType()} 里报了个值，<b>既没声明源类型列表，
     * 也没指定当前源类型</b>。媒体中心于是按它自己的默认（数字广播 11）处理，
     * 结果就是：方向盘「上一曲/下一曲」被当成调台，在 MCU 层就被消费掉 ——
     * 来电时按键到不了通话界面（接不起来），用户听到的却是原车收音机在换台。
     *
     * <p>回落策略：车机若不接受 6，就退回 11。回落只影响「归属分类」，
     * 播放与界面照旧，不会因为类型被拒就变成不能播。
     */
    private void declareSourceType(Object t) {
        if (t == null || api == null) return;
        int want = EasPlaybackInfo.sourceType();
        boolean ok = false;
        try {
            ok = api.updateMediaSourceTypeList(t, new int[]{want});
        } catch (Throwable e) {
            AppLog.w(TAG, "updateMediaSourceTypeList(" + want + ") 异常：" + e);
        }
        if (!ok && want != EasPlaybackInfo.TYPE_DAB) {
            EasPlaybackInfo.setSourceType(EasPlaybackInfo.TYPE_DAB);
            want = EasPlaybackInfo.TYPE_DAB;
            try {
                ok = api.updateMediaSourceTypeList(t, new int[]{want});
            } catch (Throwable e) {
                AppLog.w(TAG, "回落 updateMediaSourceTypeList(" + want + ") 异常：" + e);
            }
            AppLog.w(TAG, "媒体中心不接受在线音乐(6)，回落到数字广播(11)");
        }
        AppLog.key(TAG, "声明媒体源类型 → " + want + "（"
                + (ok ? "已被接受" : "未确认，按车机默认处理") + "）");
        // 抢焦点这一步只在「本来就在播」时做。
        // 开机自启时服务会先起来但没开始播，此时抢焦点等于无端霸占媒体源，
        // 会把正在播别的应用（或蓝牙音乐）的焦点顶掉。
        RadioPlayer rp = player;
        if (rp == null || !rp.isPlayWhenReady()) return;
        try {
            hasFocus = api.requestPlay(t);
            if (hasFocus) api.updateCurrentSourceType(t, want);
            AppLog.key(TAG, "requestPlay（注册后取焦点）→ " + hasFocus
                    + "，当前源类型 → " + want);
        } catch (Throwable e) {
            AppLog.w(TAG, "注册后取焦点异常：" + e);
        }
    }

    /**
     * 失败原因只在快速阶段写日志。
     *
     * <p>转慢速后（多半是手机上根本没有媒体中心服务）再报也是一样的内容，
     * 每 3 分钟刷一条只会把诊断面板的环形缓冲挤掉有用的信息。
     */
    private void warnDuringFastPhase(String why) {
        if (fastRetryIndex < FAST_RETRY_MS.length) AppLog.w(TAG, why + "，稍后重试");
    }

    /**
     * 排下一次注册尝试：前 5 次快（诊断友好），之后转慢速长期自愈（不写日志）。
     *
     * <p>同一个时刻只允许一个排队任务，避免 {@code onAPIReady} 回调和重试任务叠加。
     */
    private void scheduleRetry() {
        if (disposed || token != null) return;
        long delay;
        if (fastRetryIndex < FAST_RETRY_MS.length) {
            delay = FAST_RETRY_MS[fastRetryIndex++];
        } else {
            delay = SLOW_RETRY_MS;
        }
        main.removeCallbacks(retryTask);
        main.postDelayed(retryTask, delay);
    }

    // ------------------------------------------------------------ 状态推送

    /**
     * 状态变化 → 推给媒体中心。
     *
     * <p>调用链：{@code RadioPlayer.updateSessionState()} → {@link EasBridge#push} → 这里。
     * 主线程调用，因此可以安全读播放器状态。
     *
     * <h3>播放焦点：在播就要，暂停时<b>不还</b></h3>
     *
     * <p>媒体中心按「谁持有播放焦点」决定方向盘按键派给谁，所以在播时必须
     * {@code requestPlay} 抢过来。
     *
     * <p>暂停时<b>千万不能</b> {@code abandonPlayFocus}：实测（v1.9.1 第一次上车）
     * 方向盘按「播放/暂停」→ 我们暂停 → 焦点还回去 → <b>再按一次播放键就没人接了</b>，
     * 因为媒体中心手上已经没有当前源，不知道该把播放键派给谁。日志证据：
     * <pre>
     * [媒体源] 媒体中心 onPause
     * [媒体源] abandonPlayFocus（还回播放焦点）
     * [播放] 已暂停
     * （之后再按播放键：一条日志都没有）
     * </pre>
     * 所以焦点只在「媒体中心明确把我们切走」时才还 —— 见 {@link #clearFocus()}，
     * 由 {@code EasMusicClient.onExit()} 调用。用户切到 QQ 音乐时，
     * 对方会自己 requestPlay 把焦点抢走，不需要我们主动让。
     */
    @Override
    public void push(RadioPlayer p) {
        if (p != null) player = p;
        RadioPlayer rp = player;
        if (rp == null) return;

        EasPlaybackInfo info = build(rp);
        last = info;
        // 队列快照也在这里（主线程）建好：Binder 线程上的 getPlaylist / getMultiMediaList
        // 直接读它，不去碰 RadioPlayer 的 ArrayList（会被主线程整个换掉）。
        lastItems = buildItems(rp);
        lastList = buildList(rp, lastItems);

        Object t = token;
        if (t == null || api == null) {
            // 还没注册上：只留快照（注册成功后会立刻补推一次）。
            // 但用户此刻按了播放/切台，就是在说「我现在要用」——距上次尝试超过 3 秒就补试一次。
            // 比干等慢速重试（3 分钟）合理得多，大多数「开机后第一次按没反应」会被这次救回来。
            if (!disposed && android.os.SystemClock.elapsedRealtime() - lastAttemptAt
                    >= RETRY_ON_PUSH_GAP_MS) {
                main.removeCallbacks(retryTask);
                main.post(retryTask);
            }
            return;
        }

        boolean playing = rp.isPlayWhenReady() && rp.current() != null;
        try {
            // 在播就拿住播放焦点，并把「当前源类型」落成我们声明的那个。
            //
            // v1.9.7 起不再在通话期间主动 abandonPlayFocus（老版本 v1.9.6 的
            // `yieldedForCall` 逻辑已删除）。依据是酷我车机版：它<b>从不</b>放弃
            // 播放焦点（整个包里 0 处 abandonPlayFocus），通话照样正常。
            // 反过来，我们一放弃焦点，媒体中心就会把「当前音源」切回原车收音机，
            // 方向盘按键随之变成调台 —— 正是要修的那个毛病。
            //
            // v1.9.9：焦点归属必须**每次向车机重新确认**，不能只信本地缓存。
            //
            // 老实现是 `if (playing && !hasFocus)`，而 hasFocus 是「上次我们抢成功过」
            // 的记忆。云听这类常驻的在线音乐应用会在后台自己再 requestPlay 一次，
            // 焦点被它拿走之后我们毫无察觉：缓存仍是 true，那个 if 永远不成立，
            // 于是既不会发现、也不会抢回来。
            //
            // 现场症状与之一致：媒体条上明明是我们的台（说明
            // updateMusicPlaybackState 已被接受），可点一下媒体条弹出的是云听的
            // 界面 —— 车机按「焦点属主」决定点开哪个 App，那一刻的属主是云听。
            boolean real = queryFocus(t);
            if (playing) {
                if (!real) {
                    logFocusOwner(t, "焦点已不在本应用");
                    hasFocus = api.requestPlay(t);
                    if (hasFocus) {
                        api.updateCurrentSourceType(t, EasPlaybackInfo.sourceType());
                        AppLog.key(TAG, "重新 requestPlay 成功，当前源类型 → "
                                + EasPlaybackInfo.sourceType());
                    } else {
                        AppLog.w(TAG, "重新 requestPlay 被拒（对方仍持有焦点）");
                    }
                } else {
                    hasFocus = true;
                }
            } else {
                hasFocus = real;
            }
            // 先推列表、再推状态：服务要按「列表 id + 下标」定位当前项，
            // 顺序反了会出现一瞬「有状态但没列表」的窗口，详情页会闪「暂无播放内容」。
            pushListIfChanged(rp, t);
            boolean ok = api.updateMusicPlaybackState(t, info);
            if (!ok) AppLog.w(TAG, "updateMusicPlaybackState 返回 false");
            // 状态变了才记一条：装车排查「卡片按钮不跟着变」时，先看服务有没有收到播放。
            int st = info.getPlaybackStatus();
            if (st != lastPushedStatus) {
                lastPushedStatus = st;
                AppLog.key(TAG, "上报播放状态 → "
                        + (st == AbstractMusicPlaybackInfo.PLAYBACK_STATUS_PLAYING ? "播放中" : "暂停")
                        + "（焦点=" + hasFocus + "，列表 " + lastItems.size() + " 项）");
            }
        } catch (Throwable e) {
            AppLog.w(TAG, "推送状态异常：" + e);
        }
    }

    /**
     * 向车机查询「播放焦点现在到底在谁手上」。
     *
     * <p>不能只看本地 {@link #hasFocus}：那是「我曾经抢到过」的记忆，
     * 别的媒体源随时可以在后台把焦点拿走（云听就是典型 —— 它常驻、会自己
     * {@code requestPlay}）。本地缓存会一直停在 true，导致我们永不重抢。
     *
     * <p>查询失败（老版本 SDK 没这个方法 / binder 断了）时退回本地认知，
     * 保证不改变既有行为 —— 这条链路坏了不能拖累播放。
     */
    private boolean queryFocus(Object t) {
        try {
            return api.hasFocus(t);
        } catch (Throwable e) {
            return hasFocus;
        }
    }

    /**
     * 焦点不在本应用时，把「现在是谁」记进日志。
     *
     * <p>{@code queryCurrentFocusClient} 返回的是车机认定的当前媒体源包名，
     * 它同时是「点媒体条会打开哪个 App」的判据 —— 这一行日志就是那个问题的答案。
     */
    private void logFocusOwner(Object t, String why) {
        String pkg;
        try {
            pkg = api.queryCurrentFocusClient(t);
        } catch (Throwable e) {
            AppLog.w(TAG, why + "（查不到属主：" + e + "）");
            return;
        }
        if (app.getPackageName().equals(pkg)) {
            // hasFocus 说没有、属主却还是我们：接口口径不同，按有焦点处理，不折腾。
            AppLog.key(TAG, why + "，但属主仍是本应用（接口口径差异）");
        } else {
            AppLog.w(TAG, why + "，当前焦点属主 = " + pkg);
        }
    }

    /**
     * 把车机登记的「媒体源表」倒进日志（每个媒体源一行）。
     *
     * <p>这张表是车机判定「某个 sourceType 归谁」的依据：同一个 sourceType 被
     * 多个应用声明时，按 {@code priorityLevel} 仲裁。我们需要它来回答两个问题：
     * <ol>
     *   <li>我们声明的 6（在线音乐）是不是已经被云听之类的预装应用占着？</li>
     *   <li>我们的 priorityLevel 是多少，比对方高还是低？</li>
     * </ol>
     *
     * <p>只在注册成功后打一次，不然会把诊断面板的环形缓冲挤满。
     */
    private void dumpAppSources() {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.List<ecarx.xsf.mediacenter.session.AppSourceInfo> srcs =
                    api.getCustomAppSourceInfo();
            if (srcs == null || srcs.isEmpty()) {
                AppLog.key(TAG, "媒体源表：空（车机没有登记任何自定义媒体源）");
                return;
            }
            int n = 0;
            for (ecarx.xsf.mediacenter.session.AppSourceInfo s : srcs) {
                if (s == null) continue;
                if (n++ > 0) sb.append("\n");
                sb.append("· ").append(s.getPackageName())
                        .append(" 类型=").append(java.util.Arrays.toString(s.getSourceTypeList()))
                        .append(" 优先级=").append(s.getPriorityLevel())
                        .append(s.isRunning() ? " 运行中" : " 未运行");
                if (app.getPackageName().equals(s.getPackageName())) sb.append(" ← 本应用");
            }
            AppLog.key(TAG, "媒体源表（" + n + " 项）：\n" + sb);
        } catch (Throwable e) {
            AppLog.w(TAG, "读不到媒体源表（不影响播放）：" + e);
        }
    }

    /** 媒体中心要求退出时，主动还掉焦点，别让方向盘继续派给已经停下的本应用。 */
    void clearFocus() {
        Object t = token;
        if (t == null || api == null || !hasFocus) return;
        try {
            api.abandonPlayFocus(t);
        } catch (Throwable e) {
            AppLog.w(TAG, "abandonPlayFocus 异常：" + e);
        }
        hasFocus = false;
    }

    /**
     * 通话/响铃开始。<b>v1.9.7 起这里什么都不做</b>，只留一个日志锚点。
     *
     * <h3>为什么把 v1.9.6 的「让焦点」删掉</h3>
     *
     * <p>v1.9.6 的假设是：车机按「谁持有播放焦点」派键，所以通话期间必须
     * {@code abandonPlayFocus} 才会把键让给通话界面。逆向酷我车机版后这个假设被推翻：
     * 酷我<b>整个包里 0 处</b> {@code abandonPlayFocus}，通话期间从不还焦点，
     * 而它的「下一曲接听、上一曲挂断」在星愿车机上完全正常。
     *
     * <p>真正决定按键去向的是<b>媒体源类型</b>（见 {@link #declareSourceType}）：
     * 报成数字广播(11) 时按键被当调台；报成在线音乐(6) 时才是普通媒体源。
     * 而在通话中主动还焦点反而有害 —— 媒体中心转头把「当前音源」切回原车收音机，
     * 方向盘立刻变成调台，等于亲手把毛病做出来。
     */
    static void onCallStarted() {
        AppLog.key(TAG, "通话开始：保持播放焦点不动（按键去向由媒体源类型决定）");
    }

    /** 通话结束。原因同上，不还焦点也就不需要抢回来。 */
    static void onCallEnded() {
        AppLog.key(TAG, "通话结束：焦点保持原状");
    }

    /** 供 {@link EasMusicClient} 判断通话状态用（要 Context 读音频模式）。 */
    Context context() {
        return app;
    }

    // ------------------------------------------------------------ 媒体列表

    /**
     * 队列内容或当前下标变了，就把整张列表重推一次。
     *
     * <p>为什么不能只在注册后推一次：电台列表是按省拉的，切省 / 刷新地址 /
     * 从收藏切回全部都会换队列；下标变化也要更新，详情页要标出「正在播第几项」。
     * 用指纹判断，避免「暂停一下」也把几十项全表传一遍。
     */
    private void pushListIfChanged(RadioPlayer rp, Object t) {
        String key = listKey(rp);
        if (key.equals(lastListKey)) return;
        MediaListInfo list = lastList;
        if (list == null) {
            list = buildList(rp, lastItems);
            lastList = list;
        }
        try {
            api.updateMediaList(t, list);
            lastListKey = key;
            AppLog.key(TAG, "已推送媒体列表 " + lastItems.size()
                    + " 项，当前第 " + (rp.currentIndex() + 1) + " 项");
        } catch (Throwable e) {
            AppLog.w(TAG, "updateMediaList 异常：" + e);
        }
    }

    /** 列表指纹：项数 + 首项 id + 当前下标。任一变化都要重推。 */
    private String listKey(RadioPlayer rp) {
        List<RadioCnApi.Station> q = rp.queue();
        String head = q == null || q.isEmpty() ? "-" : q.get(0).contentId;
        return (q == null ? 0 : q.size()) + "|" + head + "|" + rp.currentIndex();
    }

    private MediaListInfo buildList(RadioPlayer rp, List<MediaInfo> items) {
        MediaListInfoBean bean = new MediaListInfoBean();
        bean.setMediaListId(EasPlaybackInfo.LIST_ID);
        bean.setMediaListType(AbstractMusicPlaybackInfo.TYPE_MEDIA_LIST_NORMAL);
        bean.setSourceType(EasPlaybackInfo.sourceType());
        bean.setTitle(LIST_TITLE);
        bean.setPendingIntent(launchIntent());
        bean.setMediaList(items);
        return bean;
    }

    /**
     * 队列 → 媒体中心认的 {@code MediaInfo} 列表。
     *
     * <p><b>为什么用 {@code MediaInfoWrapper}</b>：{@code MediaInfo} 和
     * {@code MusicPlaybackInfo} 是同一套路子 —— 空壳 DTO，每个 getter 都是
     * {@code aconst_null; areturn} / {@code iconst_0; ireturn}，直接 new 了填不进值。
     * SDK 另外给了带 setter 的 {@code MediaInfoWrapper}，用它比自己继承覆盖
     * 18 个 getter 省事，也不容易漏。
     */
    private List<MediaInfo> buildItems(RadioPlayer rp) {
        List<RadioCnApi.Station> q = rp.queue();
        if (q == null || q.isEmpty()) return Collections.emptyList();
        int n = Math.min(q.size(), MAX_LIST_ITEMS);
        List<MediaInfo> out = new ArrayList<MediaInfo>(n);
        for (int i = 0; i < n; i++) {
            RadioCnApi.Station s = q.get(i);
            if (s == null) continue;
            MediaInfoWrapper w = new MediaInfoWrapper();
            w.setTitle(StationTitle.plain(s.title));
            w.setArtist(s.provinceName);
            w.setAlbum("FM 直播");
            // 服务把「点了哪一条」回调回来时用的就是这个 uuid，见 playByMediaId。
            w.setUuid(s.contentId);
            w.setSourceType(EasPlaybackInfo.sourceType());
            w.setDuration(0L);
            // 每项都要带「属于哪个列表、排第几位」，否则清单页认不出当前项。
            w.setPlayingMediaListId(EasPlaybackInfo.LIST_ID);
            w.setPlayingMediaListType(AbstractMusicPlaybackInfo.TYPE_MEDIA_LIST_NORMAL);
            w.setPositionInQueue(i);
            w.setCollected(0);
            w.setSupportCollect(0);
            w.setVip(0);
            out.add(w);
        }
        return out;
    }

    /** Binder 线程：返回队列快照（{@code getPlaylist} 用）。 */
    List<MediaInfo> playlist() {
        return lastItems;
    }

    /** Binder 线程：返回媒体列表集合（{@code getMultiMediaList} 用）。 */
    MediaListsInfo multiMediaList() {
        MediaListsInfo all = new MediaListsInfo();
        MediaListInfo one = lastList;
        all.setMediaListsInfo(one == null
                ? Collections.<MediaListInfo>emptyList()
                : Collections.singletonList(one));
        return all;
    }

    /**
     * 用户在「媒体源清单」里点了某一项。
     *
     * @param mediaId 服务回传的标识。我们推列表时把它填在 {@code uuid} 里（= 电台 contentId），
     *                所以这里按 contentId 匹配，找不到就返回 false 让服务知道没人接。
     */
    boolean playByMediaId(String mediaId) {
        RadioPlayer rp = player;
        if (rp == null || mediaId == null) return false;
        List<RadioCnApi.Station> q = rp.queue();
        if (q == null) return false;
        for (int i = 0; i < q.size(); i++) {
            RadioCnApi.Station s = q.get(i);
            if (s != null && mediaId.equals(s.contentId)) {
                playAtAsync(i);
                return true;
            }
        }
        AppLog.w(TAG, "清单里没有这一项：" + mediaId);
        return false;
    }

    /** 按队列下标切台。Binder 线程调进来，一律转主线程执行。 */
    void playAtAsync(final int index) {
        main.post(new Runnable() {
            @Override public void run() {
                RadioPlayer rp = player;
                if (rp == null) return;
                List<RadioCnApi.Station> q = rp.queue();
                if (q == null || index < 0 || index >= q.size()) return;
                AppLog.key(TAG, "媒体中心要求播放第 " + (index + 1) + " 项");
                rp.playAt(index);
            }
        });
    }

    // ------------------------------------------------------------ 快照

    /**
     * 当前状态快照。可能被 Binder 线程调用（{@code getMusicPlaybackInfo}），
     * 所以优先返回主线程维护的 {@link #last}。
     */
    EasPlaybackInfo snapshot() {
        EasPlaybackInfo s = last;
        if (s != null) return s;
        RadioPlayer p = player;
        return p == null ? empty() : build(p);
    }

    /** 由主线程构造快照：读播放器状态 + 当前台信息。 */
    private EasPlaybackInfo build(RadioPlayer p) {
        RadioCnApi.Station s = p.current();
        boolean playing = p.isPlayWhenReady();

        int status;
        if (s == null) {
            status = AbstractMusicPlaybackInfo.PLAYBACK_STATUS_PAUSED;
        } else if (playing) {
            status = AbstractMusicPlaybackInfo.PLAYBACK_STATUS_PLAYING;
        } else {
            status = AbstractMusicPlaybackInfo.PLAYBACK_STATUS_PAUSED;
        }

        String title = s == null ? "星愿收音机" : StationTitle.plain(s.title);
        String artist = s == null || s.provinceName == null ? "广播直播" : s.provinceName;
        String uuid = s == null ? null : s.contentId;
        // 台标给小窗用：只透传接口给的网络地址（http/https），本地收藏的老台没有地址就留空。
        // 给 file:// 媒体中心读不到（另一个 uid），会显示成破图，不如不给。
        Uri artwork = null;
        if (s != null && s.image != null) {
            String img = s.image.trim();
            if (img.startsWith("http://") || img.startsWith("https://")) {
                try {
                    artwork = Uri.parse(img);
                } catch (Throwable ignored) {
                    artwork = null;
                }
            }
        }
        // 小窗第二行：接口的 subtitle 就是「正在直播：节目名」，正好当歌词行显示。
        String lyric = s == null || s.subtitle == null || s.subtitle.isEmpty()
                ? null : s.subtitle;
        return new EasPlaybackInfo(title, artist, "FM 直播", status,
                Math.max(p.currentIndex(), 0), app.getPackageName(), uuid,
                artwork, lyric, launchIntent());
    }

    private EasPlaybackInfo empty() {
        return new EasPlaybackInfo("星愿收音机", "广播直播", "FM 直播",
                AbstractMusicPlaybackInfo.PLAYBACK_STATUS_PAUSED, 0, app.getPackageName(), null,
                null, null, launchIntent());
    }

    /**
     * 点击媒体卡片时拉起界面。
     *
     * <p>用 {@code FLAG_MUTABLE}（Android 12+ 才显式声明）：车机媒体中心 / 桌面在
     * send 这个 PendingIntent 时可能带一个 <b>fillInIntent</b> 来标注入口来源，
     * immutable 会让系统直接抛
     * {@code Can't use fillInIntent with immutable PendingIntent}，
     * 表现就是「点卡片没反应、连日志都没有」。
     *
     * <p>requestCode 用 100，和 {@code RadioPlayer.sessionActivity()} 的 101 分开：
     * 两处都要能独立更新，共用一个 code 会互相覆盖 Intent。
     */
    private PendingIntent launchIntent() {
        try {
            Intent i = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            return PendingIntent.getActivity(app, 100, i, flags);
        } catch (Throwable t) {
            AppLog.w(TAG, "构造拉起 Intent 失败：" + t);
            return null;
        }
    }

    /** Binder 线程 → 主线程。 */
    void post(Runnable r) {
        main.post(r);
    }

    RadioPlayer player() {
        return player;
    }
}
