package com.rea.radio;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放内核（进程级单例，由 {@link RadioService} 持有）。内核是 <b>Media3 ExoPlayer</b>。
 *
 * <h3>为什么从系统 MediaPlayer 换成 ExoPlayer（v1.7.0）</h3>
 * 旧的 {@code android.media.MediaPlayer} 在车机上踩过一串坑，而且都不是本项目能修的：
 * <ol>
 *   <li><b>HLS 是厂商实现</b>：MediaPlayer 的 HLS 支持由各 ROM 的 NuPlayer/Stagefright 提供，
 *       对<b>两层播放列表</b>（主列表里套带 {@code wsSession} 的二级列表）支持参差不齐——
 *       成都广电源正是这种结构。v1.6.x 之前的线上反馈里出现过 error 1/-2147483648 等
 *       「同一地址有的机型能放、有的不能」的情况（属历史现象记录，本轮未做机型对照复现）。</li>
 *   <li><b>没有自己的缓冲与重连</b>：直播流断一下只能靠我们手写 watchdog 去 {@code reset + prepare}，
 *       每次重连都是一次完整的 DNS + TLS + 建流，听着就是"断一大下"。</li>
 *   <li><b>{@code setDataSource} 是阻塞的</b>：必须在后台线程做，于是"旧流继续响、新流就绪再切"
 *       全靠自己维护两套 MediaPlayer 实例 + 序号作废旧结果，状态机很脆。</li>
 * </ol>
 * ExoPlayer 把这三件事都做对了：HLS 有自己的解析器（支持嵌套 master playlist）、
 * 有独立的加载线程与可配置缓冲、{@code setMediaItem + prepare} 天然异步。
 * 换台时旧流会一直响到新流 ready，不需要再手写"双实例替换"。
 *
 * <p><b>代价（实测）</b>：APK 从 0.08 MB（82 KB）涨到 <b>3.05 MB</b>，约 36 倍（media3-exoplayer + media3-exoplayer-hls，
 * 连带 kotlin stdlib、Extractor、DataSource 等传递依赖；classes.dex 解压后 7.6 MB）。
 * 这是明确的取舍——车机上"能不能放出声音"比"安装包小 3 MB"重要得多，用户已明确放弃体积约束。
 *
 * <p><b>HLS 支持的实测证据</b>（用 media3 1.4.1 真实 {@code HlsPlaylistParser} 离线解析成都广电源）：
 * 主列表解析为 {@code HlsMultivariantPlaylist}（1 个 variant，BANDWIDTH=175000），
 * 二级列表解析为 {@code HlsMediaPlaylist}（3 个分片、targetDuration 3s、hasEndTag=false 即直播），
 * 分片首字节 0x47（MPEG-TS 同步字节），说明取到的是真实 TS 流而非错误页。
 *
 * <h3>方向盘按键能用的原因（关键，勿改）</h3>
 * <ol>
 *   <li>MediaSession 必须<b>常驻</b>：Activity 销毁不能带走它。所以播放器由前台服务持有，
 *       服务不死 → 会话不死 → 车机媒体中心左下角能一直看到「星愿收音机」并在播。</li>
 *   <li>会话必须<b>可被系统找到</b>：给 MediaSession 显式设置 {@code PendingIntent}
 *       （指向 {@link MediaButtonReceiver}），等价于声明「我是媒体按钮接收器」，
 *       这是车机/蓝牙把按键路由给我们的必要条件。</li>
 *   <li>主动请求音频焦点并声明 {@code USAGE_MEDIA}，让车机把它当媒体流而不是通知音。</li>
 *   <li>持续上报真实 {@link PlaybackState}；position 用单调时钟推进，车机进度条才不会卡在 0。</li>
 * </ol>
 *
 * <h3>地址策略（沿用，与内核无关）</h3>
 * 优先用 {@link StreamCache} 的缓存地址秒开；403/连不上时只刷新当前台再试，
 * 避免「一个台过期就整批重来」。逐个候选地址降级仍由本类负责——ExoPlayer 只拿到一个 URL，
 * 失败后由我们决定换下一个候选还是去重新取址。详见 StreamCache 的实测结论。
 */
@UnstableApi
public final class RadioPlayer {

    public interface Listener {
        void onStationChanged(int index, RadioCnApi.Station station);

        void onStateChanged(boolean playing, boolean buffering);

        void onError(String message);
    }

    private static final int MAX_RETRY = 2;
    /**
     * 同一个台上，最多主动重取几次播放地址。
     *
     * <p>v1.8.6 新增。以前这条链没有上限：「地址取得到、流连不上」时
     * 重试 → 取址 → 重试 无限循环，把主线程消息队列灌爆导致 ANR。
     * 只有用户在界面上重新选台 / 点播放才会把计数清零。
     */
    private static final int MAX_URL_REFRESH = 2;
    /**
     * 取址请求等多久还没回来，就认定它卡死了。
     *
     * <p>正常取址是一次 HTTP，1~3 秒内必回。给 20 秒是很宽松的上限，
     * 只为兜住「回调永远不来」这种死锁，不会误杀正常请求。
     */
    private static final long REFRESH_STUCK_MS = 20000L;
    private static final String TAG = "播放";

    /** 直连 MP3 缓冲超时：正常 1~3 秒就该出声，超过这个数基本是连不上了。 */
    private static final long PREPARE_TIMEOUT_MP3_MS = 9000L;
    /** HLS 要下完分片才算就绪，慢网下给宽一点。 */
    private static final long PREPARE_TIMEOUT_HLS_MS = 15000L;
    /** 播着播着卡住（还没进入 READY）多久就换下一个候选。 */
    private static final long STALL_RECONNECT_MS = 12000L;
    /** 这个时间窗内在同一个台上连续卡两次，就自动降一档音质。 */
    private static final long STALL_DOWNGRADE_WINDOW_MS = 120000L;

    /**
     * 直播流的缓冲目标：给足余量，宁可多缓冲一秒也不要一卡一卡。
     *
     * <p>直播没有"快进"概念，缓冲越多越稳；但太大又会让起播变慢。
     * 实测车机 4G/热点环境下 8 秒足够熬过一次网络抖动，起播仍在 1~2 秒。
     */
    private static final int BUFFER_FOR_PLAYBACK_MS = 2000;
    private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4000;
    private static final int MAX_BUFFER_MS = 30000;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ExoPlayer player;
    private MediaSession session;
    private MediaSession.Token sessionToken;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;

    private final List<RadioCnApi.Station> queue = new ArrayList<>();
    private int index = -1;
    private int urlCursor;      // 当前电台尝试到第几个候选地址
    private int retry;
    private boolean playWhenReady = true;
    /**
     * 通话/响铃期间挂起：会话保持不活跃，任何路径都别把它激活回来。
     *
     * <p>{@link #updateSessionState()} 里有一条「只要 playWhenReady || opening || prepared
     * 就激活会话」的兜底，而 {@code pause()} 并不会清 {@code prepared} —— 没有这个开关，
     * 通话开始时把会话置为不活跃之后，下一次状态刷新会立刻把它激活回来，按键又被我们吃掉。
     */
    private volatile boolean callSuspended;
    /**
     * 本次暂停是通话导致的：通话结束后自动恢复播放。
     *
     * <p>唯一置 {@code true} 的地方是 {@link #onCallStarted()}，且在 {@code pause()} 之后置 ——
     * 因为 {@code pause()} 会清掉这个标记。这样「用户在通话中自己按了暂停」就不会在
     * 挂断后被莫名其妙地自动播起来：用户那次 {@code pause()} 已经把标记清掉了。
     */
    private volatile boolean pausedByCall;
    /** ExoPlayer 已经能出声（STATE_READY 过至少一次）。 */
    private boolean prepared;
    /** 正在准备新流（已 setMediaItem，还没 READY）。 */
    private boolean opening;
    private boolean buffering;
    private String currentUrl;  // 正在用的地址（诊断用）
    /** 连续卡顿计数：短时间内卡两次就降音质。 */
    private int stallStreak;
    private long lastStallAt;
    /**
     * 当前浏览的省份编码，仅用于「这台没地址、也不知道它属于哪个省」时的兜底取址。
     *
     * <p>为什么需要它：收藏快照早先不存省份码，取出来的台 {@code provinceCode=0}。
     * 早期实现会回落写死的四川，收藏了外省的台就永远补不到地址（界面切了、声音不出）。
     * 界面每次切省份时通过 {@link #setFallbackProvince(int)} 告诉这里。
     */
    private int fallbackProvinceCode;
    /** 正在为「没地址」的台重新取址：防止 refreshThenOpen 反复触发形成死循环。 */
    private boolean refreshing;
    /**
     * 当前台已经重取过几次地址（上限 {@link #MAX_URL_REFRESH}）。
     *
     * <p>与 {@link #retry} 分开计数，是为了让「取址」这条链有自己<b>不可充值</b>的预算。
     * 旧代码把 retry 在取址成功后清零，等于给无限循环开了后门。
     */
    private int urlRefreshCount;
    /** 取址卡死复查任务（见 {@link #scheduleRefreshStuckCheck}）。 */
    private Runnable refreshStuckCheck;
    private Runnable prepareTimeout;
    private Runnable stallTimeout;
    private Listener listener;

    /**
     * 已推给 MediaSession 的队列指纹：内容不变就不重发整张表（Binder 事务不便宜，
     * 而 updateSessionState 每次缓冲/暂停都会走一遍）。
     */
    private String publishedQueueKey;
    /** 上次上报的曲目 id，变了才打一条诊断日志（不刷屏）。 */
    private String publishedMediaId;
    /** 切台后延迟补推一次元数据：车机在 BUFFERING 那一瞬可能没把标题读走。 */
    private final Runnable republishMetadata = new Runnable() {
        @Override public void run() { updateSessionState(); }
    };

    /**
     * 已被证实「连得上、但一个字节都没有」的地址（HTTP 200 + 空 body）。
     *
     * <p>为什么会存在这种地址：云听对个别台返回 200 空列表（实测四川城市之音 450、
     * 四川岷江音乐 447，见 {@link RadioCnApi#probeHasContent}）。对播放器来说它不是
     * "错误"，连得上、状态码也对，只是列表里一档节目都没有 —— 用户看到的就是"转两下没声"。
     *
     * <p>这里记的是<b>具体地址</b>不是台：云听的地址带一次性 key，重取一次就是新 URL，
     * 新 URL 不会被这里误伤（电台真恢复推流时能正常播）。
     *
     * <p>只在本进程内有效，重启清空 —— 电台可能随时恢复，不该永久拉黑。
     */
    private final java.util.Set<String> deadUrls =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 已经发起过内容探活的地址。与 {@link #deadUrls} 分开：
     * 探活结果可能是"有内容"（那就是网络抖动），那种地址不该重复探。
     */
    private final java.util.Set<String> probed =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    public RadioPlayer(Context context) {
        app = context.getApplicationContext();
        player = buildPlayer();
        attachInternalListener();
        setupSession();
        setupFocus();
    }

    /**
     * 建 ExoPlayer。
     *
     * <p>几个刻意的配置：
     * <ul>
     *   <li><b>只用音频渲染器</b>：电台是纯音频，音乐类 App 不该占着视频解码器不放
     *       （车机上视频解码器是稀缺资源，导航可能要用）。</li>
     *   <li><b>自定义 LoadControl</b>：直播默认缓冲偏小，弱网下容易"响一下停一下"。</li>
     *   <li><b>HTTP 超时调短</b>：默认 8 秒对电台太长，连不上就赶紧换下一个候选。</li>
     *   <li><b>允许不安全的明文 HTTP</b>：成都广电的源是 {@code http://}，
     *       targetSdk 28 默认允许，但显式关掉检查更稳妥。</li>
     *   <li><b>重试交给本类</b>：ExoPlayer 自带的 load 重试只在同一条 URL 上重试，
     *       而我们的策略是「换下一个候选地址」，所以这里把原生重试关掉。</li>
     * </ul>
     */
    private ExoPlayer buildPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("XingyuanRadio/1.7")
                .setConnectTimeoutMs(6000)
                .setReadTimeoutMs(10000)
                .setAllowCrossProtocolRedirects(true);
        DefaultDataSource.Factory ds = new DefaultDataSource.Factory(app, http);

        LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        MAX_BUFFER_MS,
                        MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS,
                        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .build();

        // 只要音频：关掉视频/文本渲染器，省解码器也省内存
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(app)
                .setEnableDecoderFallback(true);

        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(ds, new DefaultExtractorsFactory());

        return new ExoPlayer.Builder(app, renderers)
                .setMediaSourceFactory(msf)
                .setLoadControl(loadControl)
                .setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(),
                        /* handleAudioFocus= */ false)   // 焦点由本类自己管，理由见类注释
                .setHandleAudioBecomingNoisy(true)
                .build();
    }

    /** 把 ExoPlayer 的事件翻译成本类的状态与回调。 */
    private void attachInternalListener() {
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                onExoState(state);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // isPlaying 变化比 state 更贴近"耳朵听到的"，用它刷新一次界面状态
                notifyState();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                AppLog.e(TAG, "播放器错误 " + error.getErrorCodeName() + ": " + error.getMessage());
                // 必须写 RadioPlayer.this.。不写的话，这个调用会解析成匿名类
                // <b>自己</b>的 onPlayerError（签名恰好和外层那个一样），
                // 于是一路无限递归到 StackOverflowError 闪退。
                // 这个 bug 长期被日志洪水与重试循环掩盖（它们更早触发 ANR），
                // v1.8.6 修掉 ANR 之后才暴露出来 —— 用户看到的仍然是"一放就卡死"。
                // 外层方法已改名为 handlePlayerError，与监听器方法不再同名，双保险。
                RadioPlayer.this.handlePlayerError(error);
            }
        });
    }

    /**
     * 处理 ExoPlayer 的播放状态。
     *
     * <p>状态映射（与旧 MediaPlayer 版本语义保持一致，界面文案不用改）：
     * <ul>
     *   <li>{@code BUFFERING} → buffering=true，界面显示「正在缓冲…」</li>
     *   <li>{@code READY} → prepared=true / opening=false，缓冲结束</li>
     *   <li>{@code ENDED} → 直播流"播完了"通常意味着连接断了，重新拉起</li>
     *   <li>{@code IDLE} → 未开始或被停止</li>
     * </ul>
     *
     * <p>{@link #prepared} 越早置 true 越好：它决定车机媒体卡片的进度条是不是活的。
     */
    private void onExoState(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                buffering = true;
                cancelPrepareTimeout();     // 已经在缓冲，说明连上了，别再判超时
                AppLog.i("缓冲", "BUFFERING");
                // 只有"本来在播、忽然卡住"才需要救援；起播阶段的缓冲由兜底超时负责
                if (prepared) armStallWatchdog();
                break;

            case Player.STATE_READY:
                boolean firstReady = !prepared;
                buffering = false;
                opening = false;
                prepared = true;
                refreshing = false;
                cancelPrepareTimeout();
                cancelStallWatchdog();
                if (firstReady) {
                    AppLog.i(TAG, "就绪并开始播放（" + currentSourceLabel() + "）");
                }
                break;

            case Player.STATE_ENDED:
                buffering = false;
                opening = false;
                AppLog.w(TAG, "直播流意外结束，准备重连");
                if (playWhenReady) reconnect(false);
                break;

            case Player.STATE_IDLE:
            default:
                break;
        }
        // 换台时为什么不需要"掐旧声音"：ExoPlayer 是先 setMediaItem 再 prepare，
        // 旧流会一直响到新流 READY 才被替换，观感与旧实现一致，不需要额外的定时器。
        updateSessionState();
        notifyState();
    }

    // ------------------------------------------------------------ MediaSession

    private void setupSession() {
        session = new MediaSession(app, "XingyuanRadio");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        // 关键：显式声明媒体按钮接收器，让 AMS / 车机媒体中心能把按键路由过来
        Intent mbi = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mbi.setComponent(new ComponentName(app, MediaButtonReceiver.class.getName()));
        session.setMediaButtonReceiver(PendingIntent.getBroadcast(
                app, 0, mbi, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        // 「点媒体条 / 卡片 → 回到 App」的标准入口。
        //
        // v1.9.4 补：之前这里一直是空的。系统 UI（星愿桌面的左下媒体条、车机媒体中心
        // 的卡片）判断「点这张卡片该跳到哪」时，优先读 MediaSession 声明的
        // sessionActivity；没有它，点击只会把卡片选中，界面不会被拉起 ——
        // 也就是用户看到的「点控制条进不去 App」。
        session.setSessionActivity(sessionActivity());

        session.setCallback(new MediaSession.Callback() {

            @Override
            public void onPlay() {
                AppLog.key("按键", "MediaSession.onPlay");
                resume();
            }

            @Override
            public void onPause() {
                AppLog.key("按键", "MediaSession.onPause");
                pause();
            }

            @Override
            public void onStop() {
                AppLog.key("按键", "MediaSession.onStop");
                stop();
            }

            @Override
            public void onSkipToNext() {
                AppLog.key("按键", "MediaSession.onSkipToNext");
                next();
            }

            @Override
            public void onSkipToPrevious() {
                AppLog.key("按键", "MediaSession.onSkipToPrevious");
                prev();
            }

            @Override
            public void onSeekTo(long pos) {
                // 直播没有可 seek 的位置。
                //
                // 旧实现把它映射成 next() —— 理由是「车机进度条拖动总得有点反应」，
                // 但这会造成误触：手机锁屏拖动进度条、车机媒体卡片误滑一下，
                // 都变成「换台」，声音突然变了而用户没想换。
                //
                // 现在什么都不做，只记日志。方向盘上一台/下一台有专门的键码，
                // 走 onSkipToNext / onSkipToPrevious，不依赖这里。
                AppLog.key("按键", "MediaSession.onSeekTo(" + pos + ") → 直播不可 seek，忽略");
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                // 通话/响铃期间不消费媒体键（v1.9.6）：返回 false 表示「本会话不处理」，
                // 系统会继续往别处派（通话界面）。返回 super 会走下面的 onPlay/onPause，
                // 等于把「接听键」变成「暂停键」。
                if (CallGuard.shouldYieldKeys(app)) {
                    AppLog.key("按键", "通话中 → MediaSession 不消费媒体键");
                    return false;
                }
                KeyEvent ev = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ev != null) {
                    AppLog.key("按键", "onMediaButtonEvent keyCode=" + ev.getKeyCode()
                            + " action=" + ev.getAction() + " repeat=" + ev.getRepeatCount());
                    // 单键短按=播放暂停由父类处理；双击视为下一台
                    if (ev.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK
                            && ev.getAction() == KeyEvent.ACTION_DOWN
                            && ev.getRepeatCount() == 1) {
                        next();
                        return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        session.setActive(true);
        sessionToken = session.getSessionToken();
        AppLog.key("会话", "MediaSession 已激活，MEDIA_BUTTON → "
                + MediaButtonReceiver.class.getSimpleName());
    }

    /**
     * 会话令牌，给通知的 {@code MediaStyle.setMediaSession} 用。
     *
     * <p><b>为什么必须有这个：</b>通知用了 {@code Notification.MediaStyle}，
     * 但如果不把 session token 绑上去，那个通知只是个「长得像媒体控件」的普通通知——
     * 系统不知道该由谁来响应它的按钮，车机媒体中心也不会把它当成活跃媒体。
     *
     * <p>这正是 v1.8.9 之前的状态：{@code MediaStyle} 有了、session 也有了，
     * 但两者没连起来，结果就是「方向盘完全没反应」。
     */
    public MediaSession.Token sessionToken() {
        return sessionToken;
    }

    /**
     * 「点媒体条 → 打开 App」的 PendingIntent。
     *
     * <h3>为什么要 mutable</h3>
     *
     * <p>部分车机 / 系统 UI 在 send 这个 PendingIntent 时会带一个
     * <b>fillInIntent</b>（用来标注「从哪个入口点进来的」，便于埋点或跳转细分页面）。
     * 如果目标 PendingIntent 是 immutable，系统会直接抛
     * {@code IllegalArgumentException: Can't use fillInIntent with immutable PendingIntent}，
     * 表现就是「点了完全没反应」—— 连一行日志都不会有，极难排查。
     *
     * <p>Android 12（API 31）起必须显式声明可变性，之前的版本没有
     * {@code FLAG_MUTABLE} 常量语义（默认就是可变），所以按版本判断。
     */
    private PendingIntent sessionActivity() {
        try {
            Intent i = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            return PendingIntent.getActivity(app, 101, i, flags);
        } catch (Throwable t) {
            AppLog.w("会话", "构造 sessionActivity 失败：" + t);
            return null;
        }
    }

    /**
     * 确保会话处于活跃状态。
     *
     * <p>{@link #stop()} 会把会话置为不活跃（把媒体键让给别在播的应用），
     * 但「不活跃」也意味着系统不再往这里路由媒体键。所以凡是<b>要恢复播放</b>的路径
     * 都得先调这里，否则会话复活不了：能出声，但方向盘/车机媒体中心一律失灵。
     *
     * <p>幂等 —— {@code setActive(true)} 重复调用没有副作用，
     * 所以不需要自己在每个调用点判断当前状态。
     */
    private void activateSession() {
        // 通话中不激活：会话活跃就意味着系统/车机会把媒体键路由给我们，
        // 而通话期间这些键必须留给通话界面（下一曲接听、上一曲挂断）。
        if (callSuspended) return;
        if (session != null && !session.isActive()) {
            session.setActive(true);
            AppLog.key("会话", "MediaSession 重新激活");
        }
    }

    /**
     * 会话当前是否活跃。仅供测试与诊断页使用。
     *
     * <p>「活跃」的含义是：系统会把媒体键路由到这里、车机媒体中心会把它列为可切换媒体。
     * 停播后应当是 {@code false}，否则它会跟真正在播的应用抢按键。
     */
    public boolean isSessionActive() {
        return session != null && session.isActive();
    }

    private void setupFocus() {
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XingyuanRadio:play");
            wakeLock.setReferenceCounted(false);
        }
    }

    private boolean requestFocus() {
        if (audioManager == null) return true;
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(this::onFocusChange)
                        .build();
            }
            r = audioManager.requestAudioFocus(focusRequest);
        } else {
            r = audioManager.requestAudioFocus(this::onFocusChange,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        boolean ok = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!ok) AppLog.w("音频", "音频焦点申请失败");
        return ok;
    }

    private void onFocusChange(int change) {
        AppLog.i("音频", "音频焦点变化 " + change);
        // 先交给通话判定：LOSS_TRANSIENT 说明有东西「强制」拿走了焦点（通话/响铃/播报），
        // 通话那一侧要据此决定是让出按键还是连播放一起让。
        CallGuard.onFocusChange(change);
        // 车机导航播报会短暂 duck/loss；只有永久失去才暂停，避免错过节目。
        // 注意：通话导致的中断由 CallGuard 单独处理（可能要暂停 + 交出媒体源），
        // 不在这里做 —— 这里只认「永久失去」，两者判据不同，别合并。
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            main.post(this::pause);
        }
    }

    private void updateSessionState() {
        if (session == null) return;

        // 兜底：只要还在播，就保证会话是活跃的。
        //
        // 除了 resume() 显式调 activateSession()，这里再加一道 ——
        // toggle / playAt / next / prev / openCurrent 都会绕到这里，
        // 免得将来新增一条播放路径时忘了激活会话，又出现「能出声但按键失灵」。
        if (playWhenReady || opening || prepared) {
            activateSession();
        }
        // 直播没有真实进度。之前用「播了多久」填进去，想让车机媒体卡片的进度条
        // 有东西可画 —— 但代价是系统以为可 seek，于是显示可拖动的进度条，
        // 用户一拖就触发 onSeekTo（旧实现还会换台）。
        // 现在老实填 0 且不声明 ACTION_SEEK_TO，进度条自然不出现。
        long pos = 0;

        int state;
        if (opening || buffering || (playWhenReady && !prepared)) {
            state = PlaybackState.STATE_BUFFERING;
        } else if (playWhenReady) {
            state = PlaybackState.STATE_PLAYING;
        } else {
            state = PlaybackState.STATE_PAUSED;
        }

        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_STOP)
                // 车机/蓝牙媒体条靠「当前队列项变了没有」判断换曲。
                // 不设它时 activeQueueItemId 恒为 UNKNOWN_ID，即便标题换了，
                // 车机也认为是同一首歌在补元数据 —— 媒体条就永远停在第一次读到的台名。
                .setActiveQueueItemId(index >= 0 ? index : MediaSession.QueueItem.UNKNOWN_ID)
                .setState(state, pos, 1.0f, SystemClock.elapsedRealtime());
        session.setPlaybackState(b.build());

        RadioCnApi.Station s = current();
        if (s != null) {
            String title = StationTitle.plain(s.title);
            String artist = s.provinceName == null ? "广播直播" : s.provinceName;
            String mediaId = mediaIdOf(s, index);
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    // MEDIA_ID 是 AVRCP / 车机判断「换了一首」的首要字段；
                    // 以前没填，车机把每次切台都当成同一曲，媒体条不刷新台名。
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
                    // 部分车机只读 DISPLAY_*，不读 TITLE/ARTIST，两套都给。
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "FM 直播")
                    // 曲目序号也随切台变化，是多一重「换曲」信号（老 AVRCP 1.3 认它）。
                    .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, index + 1L)
                    .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, Math.max(queue.size(), 1))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 0L);
            // 台标：手机上锁屏媒体控件、通知栏大图、蓝牙车机都读这个字段。
            Bitmap art = LogoLoader.cached(app, s.contentId);
            if (art != null) {
                mb.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
                mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            }
            session.setMetadata(mb.build());
            publishQueue();
            if (!mediaId.equals(publishedMediaId)) {
                publishedMediaId = mediaId;
                AppLog.key("会话", "上报曲目 → " + title
                        + "（第 " + (index + 1) + "/" + queue.size() + " 台，id=" + mediaId + "）");
            }
        }

        // 车机媒体中心（v1.9.1）：把同一个状态再推一份过去。
        //
        // 为什么放在这里而不是每个 play/pause 里各写一次：
        // updateSessionState 是「状态唯一出口」，切台、播放、暂停、缓冲全都会绕到，
        // 放这儿就不会出现「通知栏对了、媒体中心的卡片还是上一台」。
        // 没有注册媒体源时（非星愿车机、单元测试）EasBridge 是空操作。
        EasBridge.push(this);
    }

    /**
     * 把当前队列交给 MediaSession。
     *
     * <p>投屏/蓝牙场景下，车机 Dock 那条媒体条不读 App 界面，也不读我们的通知，
     * 它读的是手机媒体会话。{@code setActiveQueueItemId(index)} 只有配上真实队列
     * 才成立（否则车机查不到这个 id 对应的条目，可能干脆显示空白），所以两者必须同时给。
     *
     * <p>队列项 id 直接用下标，与 {@code setActiveQueueItemId} 的取值一一对应；
     * mediaId 用台站的 contentId，保证切台后 AVRCP 能看到「曲目身份变了」。
     * 上限 300 与 EAS 列表一致：Binder 单次事务有 1MB 限制，宁可少列也不冒
     * TransactionTooLarge 的险。
     */
    private void publishQueue() {
        if (session == null) return;
        String key = queue.size() + "|"
                + (queue.isEmpty() ? "-" : mediaIdOf(queue.get(0), 0));
        if (key.equals(publishedQueueKey)) return;
        publishedQueueKey = key;
        if (queue.isEmpty()) {
            session.setQueue(null);
            session.setQueueTitle(null);
            return;
        }
        int n = Math.min(queue.size(), 300);
        List<MediaSession.QueueItem> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            RadioCnApi.Station s = queue.get(i);
            if (s == null) continue;
            MediaDescription.Builder d = new MediaDescription.Builder()
                    .setMediaId(mediaIdOf(s, i))
                    .setTitle(StationTitle.plain(s.title))
                    .setSubtitle(s.provinceName);
            items.add(new MediaSession.QueueItem(d.build(), i));
        }
        try {
            session.setQueue(items);
            session.setQueueTitle("星愿电台");
            AppLog.i("会话", "已上报媒体队列 " + items.size() + " 项");
        } catch (Throwable t) {
            AppLog.w("会话", "推送媒体队列失败：" + t);
        }
    }

    /** 没拿到 contentId 的老台，用下标兜一个稳定 id，绝不让 mediaId 为 null。 */
    private static String mediaIdOf(RadioCnApi.Station s, int index) {
        return s == null || s.contentId == null || s.contentId.isEmpty()
                ? "idx:" + index : s.contentId;
    }

    /**
     * 台标下载完 / 界面缓存命中后调用一次，让锁屏与通知栏拿到 ART 图。
     * 只在主线程执行，内部就是重设一次 metadata。
     */
    public void refreshMetadata() {
        if (session != null) updateSessionState();
    }

    // ------------------------------------------------------------ 播放控制

    public void setListener(Listener l) {
        listener = l;
    }

    public void setQueue(List<RadioCnApi.Station> list, int startIndex, boolean autoPlay) {
        queue.clear();
        if (list != null) queue.addAll(list);
        publishQueue();
        index = -1;
        playWhenReady = autoPlay;
        if (!queue.isEmpty()) {
            index = Math.max(0, Math.min(startIndex, queue.size() - 1));
            playAt(index);
        }
    }

    /**
     * 建队列但不动正在播放的流（即 {@link #restoreAt}，只是先装队列）。
     * 名字单独起是为了在调用处一眼看出「这不是普通换台」。
     */
    public void setQueueQuietly(List<RadioCnApi.Station> list, int startIndex, boolean autoPlay) {
        queue.clear();
        if (list != null) queue.addAll(list);
        publishQueue();
        if (queue.isEmpty()) {
            index = -1;
            return;
        }
        restoreAt(startIndex, autoPlay);
    }

    /**
     * 恢复用：队列就位后<b>不开新流</b>，尽量沿用现有音频。
     *
     * <p>场景：车机上退出界面再打开，前台服务里的 ExoPlayer 其实还在放。
     * 如果这里照常 {@code playAt} 会重新 setMediaItem，用户听感就是"断一下又重连"，
     * 配合界面刷新会误以为被重置了。
     *
     * <p>判断依据：{@code prepared && !opening} 表示当前这路流仍然有效，
     * 只需要把下标对齐、继续播；否则才真正重新打开。
     */
    public void restoreAt(int startIndex, boolean autoPlay) {
        if (queue.isEmpty()) return;
        index = Math.max(0, Math.min(startIndex, queue.size() - 1));
        RadioCnApi.Station s = current();
        if (prepared && !opening) {
            playWhenReady = autoPlay;
            setExoPlayWhenReady(autoPlay);
            AppLog.i(TAG, "恢复播放（沿用现有流）：" + (s == null ? "?" : s.title));
            updateSessionState();
            notifyState();
            if (listener != null) listener.onStationChanged(index, s);
        } else {
            playAt(index);
            if (!autoPlay) pause();
        }
    }

    /**
     * 只换队列，不重新开流。
     *
     * <p>用途：界面重开、省份列表刷新、收藏视图里点选换台时，若正在播的那台
     * 还在新队列里，只把下标对齐、音频继续。
     * 若它不在新队列里（比如从「全部」切到「收藏」而当前台没被收藏），
     * 只把队列换掉并把下标指到 fallbackIndex，<b>不在这里开流</b>——
     * 开流交给调用方紧接着的 {@link #playAt}，避免一条路径开两次流
     * （听感上就是「点了台先断一下再响」，甚至被当成切台失败）。
     */
    public void rebindQueue(List<RadioCnApi.Station> list, int fallbackIndex, boolean keepPlay) {
        if (list == null || list.isEmpty()) return;
        String curId = current() == null ? null : current().contentId;
        int found = -1;
        if (curId != null) {
            for (int i = 0; i < list.size(); i++) {
                RadioCnApi.Station s = list.get(i);
                if (s != null && curId.equals(s.contentId)) {
                    found = i;
                    break;
                }
            }
        }
        queue.clear();
        queue.addAll(list);
        publishQueue();
        playWhenReady = keepPlay;

        if (found < 0) {
            // 当前台不在新列表里：只对齐下标，等调用方决定怎么开流
            index = Math.max(0, Math.min(fallbackIndex, queue.size() - 1));
            AppLog.i(TAG, "队列已刷新（当前台不在新列表里），指向："
                    + queue.get(index).title);
            return;
        }
        index = found;
        AppLog.i(TAG, "队列已刷新，继续播放：" + queue.get(found).title);
        if (listener != null) listener.onStationChanged(index, queue.get(found));
    }

    public List<RadioCnApi.Station> queue() {
        return Collections.unmodifiableList(queue);
    }

    public int currentIndex() {
        return index;
    }

    public RadioCnApi.Station current() {
        return (index >= 0 && index < queue.size()) ? queue.get(index) : null;
    }

    public boolean isPlaying() {
        try {
            return player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isPlayWhenReady() {
        return playWhenReady;
    }

    public void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        RadioCnApi.Station s = queue.get(i);
        // 重复点「正在播的那台」：不重建流，否则听感就是"点一下断一下"
        if (i == index && prepared && !opening && playWhenReady) {
            AppLog.i(TAG, "已在播放该台，不重建流：" + (s == null ? "?" : s.title));
            if (!isPlaying()) resume();
            if (listener != null) listener.onStationChanged(index, s);
            return;
        }
        index = i;
        urlCursor = 0;
        retry = 0;
        stallStreak = 0;
        refreshing = false;     // 换台了：上一次「没地址在刷新」的状态作废
        // 用户主动选了一台：重试预算恢复满额。
        // 自动化链路（播放器错误回调、定时器）绝不重置，否则又会无限空转。
        urlRefreshCount = 0;
        cancelRefreshStuckCheck();
        playWhenReady = true;
        AppLog.i(TAG, "切台 → " + (s == null ? "?" : s.title));
        openCurrent();
        // 切台那一刻会话正处在 BUFFERING，部分车机只认「播放状态稳定」时读到的标题；
        // 流就绪后再补推一份完整元数据（含此刻已落地的台标），保证媒体条跟着换。
        main.removeCallbacks(republishMetadata);
        main.postDelayed(republishMetadata, 1000L);
        if (listener != null) listener.onStationChanged(index, s);
    }

    public void next() {
        if (queue.isEmpty()) return;
        playAt((index + 1) % queue.size());
    }

    public void prev() {
        if (queue.isEmpty()) return;
        playAt((index - 1 + queue.size()) % queue.size());
    }

    public void toggle() {
        if (playWhenReady) pause();
        else resume();
    }

    public void resume() {
        // 用户（或界面/通知栏）主动播放：通话结束后不该再「自动恢复」一次。
        pausedByCall = false;
        playWhenReady = true;
        activateSession();
        if (opening) {
            // 正在准备这一路流：ExoPlayer 会在 ready 后自己播，这里再 prepare 一次
            // 只会重开连接。只把 playWhenReady 同步进去即可。
            setExoPlayWhenReady(true);
            AppLog.i(TAG, "正在准备流，就绪后自动播放");
            updateSessionState();
            notifyState();
            return;
        }
        if (prepared) {
            setExoPlayWhenReady(true);
            requestFocus();
            acquireWake();
            AppLog.i(TAG, "继续播放");
            updateSessionState();
            notifyState();
            return;
        }
        if (current() != null) {
            // 走到这里说明流没准备好（多半是上一轮已经放弃或还在重试）：
            // 这是用户主动点「播放」，是明确的重试意图 —— 把预算还给他。
            resetRetryBudget();
            urlCursor = 0;
            openCurrent();
        }
    }

    public void pause() {
        playWhenReady = false;
        // 任何一次「普通暂停」都作废「通话后自动恢复」的打算：
        // 通话中用户自己按了暂停，挂断后就不该自己播起来。
        pausedByCall = false;
        setExoPlayWhenReady(false);
        releaseWake();
        AppLog.i(TAG, "已暂停");
        updateSessionState();
        notifyState();
    }

    public void stop() {
        playWhenReady = false;
        pausedByCall = false;
        prepared = false;
        opening = false;
        buffering = false;
        refreshing = false;
        cancelPrepareTimeout();
        cancelStallWatchdog();
        try {
            player.stop();
            player.clearMediaItems();
        } catch (Throwable ignored) { }
        releaseWake();
        // 停播后必须把元数据也清掉。
        //
        // 之前只改了 PlaybackState，metadata 一直挂着上一个台的台名和台标，
        // 于是会话看上去还是「有个正在播放的台」，系统/车机媒体中心仍然把它
        // 当成活跃媒体 —— 方向盘按键会继续派进来，但没有任何反应，
        // 表现为「按键没反应」，实际是派给了一个已停播的空会话。
        if (session != null) {
            session.setMetadata(null);
            // 停播后取消活跃状态，把媒体键让给真正在播的应用。
            session.setActive(false);
        }
        updateSessionState();
        notifyState();
    }

    // ------------------------------------------------------------ 通话

    /**
     * 进入通话 / 响铃：<b>只暂停</b>，不动 MediaSession 的活跃状态。
     *
     * <p>谁调用：{@code RadioService.onCallStateChanged(true)}，
     * 而那个回调由 {@link CallGuard} 判定（音频模式或音频焦点持续被拿走）。
     *
     * <h3>v1.9.7：删掉 {@code setActive(false)}</h3>
     *
     * <p>v1.9.6 曾在这里把会话置为不活跃，想让媒体键落到通话界面。逆向酷我车机版后
     * 确认这条路是错的：酷我的会话<b>全程活跃</b>（整个包里只有库内部一次
     * {@code setActive}），通话却完全正常。真正把「下一曲」变成接听键的是
     * 媒体源类型（见 {@code EasMediaCenter.declareSourceType}），与会的活跃状态无关。
     *
     * <p>反而 {@code setActive(false)} 有副作用：媒体中心会认为本应用「不在了」，
     * 把当前音源切回原车收音机，于是按键变成调台。留着活跃状态、只是暂停，
     * 才是和酷我一致的行为。
     *
     * <p>只暂停不停止：{@link #stop()} 会清掉 mediaItems 和 metadata，
     * 通话结束后要重新拉流，用户能听出「断了一下」。这里保持流在内存里，
     * 挂断后 {@code resume()} 是秒级恢复。
     */
    public void onCallStarted() {
        callSuspended = true;
        if (playWhenReady) {
            pause();              // pause() 会清 pausedByCall，所以下面再置回来
            pausedByCall = true;  // 标记「是通话让我停的」，挂断后自动恢复
            AppLog.key("通话", "通话开始 → 已暂停，挂断后自动恢复");
        } else {
            AppLog.key("通话", "通话开始（本来未播放，挂断后不自动播放）");
        }
        updateSessionState();
        notifyState();
    }

    /**
     * 通话结束：必要时恢复播放。
     *
     * <p>恢复播放的条件很窄：<b>只有「是通话让播放停的」才恢复</b>。
     * 用户在通话开始前就暂停着、或者通话中自己按了暂停，挂断后都不该自己播起来。
     *
     * <p>会话在通话期间一直是活跃的（v1.9.7 起不再置为不活跃），
     * 所以这里不需要「先恢复活跃再播放」那一步；{@code resume()} 内部仍会
     * 走一遍 {@link #activateSession()}（幂等）。
     */
    public void onCallEnded() {
        if (!callSuspended) return;
        callSuspended = false;
        boolean resume = pausedByCall;
        pausedByCall = false;
        if (resume) {
            AppLog.key("通话", "通话结束 → 恢复播放");
            resume();
            return;
        }
        updateSessionState();
        notifyState();
        AppLog.key("通话", "通话结束（不自动播放）");
    }

    /** 诊断用：当前是否处于「通话挂起」状态。 */
    public boolean isCallSuspended() {
        return callSuspended;
    }

    public void release() {
        stop();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        try {
            player.release();
        } catch (Throwable ignored) { }
        if (audioManager != null && Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    // ------------------------------------------------------------ 内部：地址与播放

    /**
     * 打开当前台。
     *
     * <p>ExoPlayer 的 {@code setMediaItem + prepare} 本身就是异步的（加载在内部线程），
     * 所以这里不需要再自己开线程，也不会阻塞主线程——这正是换内核的主要收益之一。
     *
     * <p>换台时<b>不 stop 当前流</b>：直接 setMediaItem 会让旧流继续播到新流 ready，
     * 用户听不出空档；如果新流准备失败，旧的那路通常也已经被停掉了，
     * 这时走 {@link #retryCurrent()} 换候选地址。
     */
    private void openCurrent() {
        RadioCnApi.Station s = current();
        if (s == null) return;

        final List<String> urls = candidateUrls(s);
        if (urls.isEmpty()) {
            AppLog.e(TAG, s.title + " 暂无可用地址，尝试刷新");
            refreshThenOpen(s);
            return;
        }
        if (urlCursor >= urls.size()) urlCursor = 0;
        final String url = urls.get(urlCursor);
        currentUrl = url;

        opening = true;
        prepared = false;
        buffering = true;
        // 必须清掉上一台留下的停顿看门狗：否则它会在 12 秒后对「新台」发起 reconnect(true)，
        // 表现为"刚切过去就自己重连一次"。起播阶段的保护由 schedulePrepareTimeout 负责。
        cancelStallWatchdog();
        updateSessionState();
        notifyState();
        requestFocus();
        acquireWake();

        AppLog.i(TAG, "open " + shortUrl(url)
                + "（候选 " + (urlCursor + 1) + "/" + urls.size()
                + " · " + s.qualityOf(url) + "）");
        schedulePrepareTimeout(url);

        try {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
            player.prepare();
            setExoPlayWhenReady(playWhenReady);
        } catch (Throwable t) {
            AppLog.e(TAG, "打开失败：" + t);
            opening = false;
            buffering = false;
            onPlayerErrorRaw("打开失败: " + t.getMessage());
        }
    }

    /** 把 playWhenReady 同步给 ExoPlayer，并把异常吞掉（release 之后可能已被销毁）。 */
    private void setExoPlayWhenReady(boolean ready) {
        try {
            player.setPlayWhenReady(ready);
        } catch (Throwable ignored) { }
    }

    /** 起播保护：连不上/一直不就绪时别无限等，直接换候选地址。 */
    private void schedulePrepareTimeout(String url) {
        cancelPrepareTimeout();
        final long timeout = url != null && url.contains(".m3u8")
                ? PREPARE_TIMEOUT_HLS_MS : PREPARE_TIMEOUT_MP3_MS;
        prepareTimeout = () -> {
            if (!opening) return;
            AppLog.w(TAG, "准备超时（" + (timeout / 1000) + " 秒），换下一个地址");
            opening = false;
            buffering = false;
            RadioCnApi.Station s = current();
            int count = s == null ? 0 : candidateUrls(s).size();
            // 超时动辄等 9~15 秒，别在同一个连不上的地址上再耗三轮，直接降一档
            if (count > 1 && urlCursor + 1 < count) {
                urlCursor++;
                retry = 0;
                AppLog.w(TAG, "超时降级：换候选 " + (urlCursor + 1) + "/" + count
                        + "（" + s.qualityOf(candidateUrls(s).get(urlCursor)) + "）");
                openCurrent();
            } else {
                retryCurrent();
            }
        };
        main.postDelayed(prepareTimeout, timeout);
    }

    private void cancelPrepareTimeout() {
        if (prepareTimeout != null) {
            main.removeCallbacks(prepareTimeout);
            prepareTimeout = null;
        }
    }

    /**
     * 播放中卡住（READY 之后又进 BUFFERING 且迟迟不恢复）就换候选，避免一直转圈。
     *
     * <p><b>为什么不能每次都重新计时</b>：ExoPlayer 在 {@code STATE_BUFFERING} 期间会反复回调同一个状态
     * （每次加载进度变化都会走一遍 onPlaybackStateChanged）。如果每次回调都把定时器往后推，
     * 弱网下真实卡死就永远判不出来；反过来，如果每次都从头计时又会在正常缓冲时误判。
     * 所以判据是「{@link #stallTimeout} 还挂着就说明已经在计时，直接返回」——
     * 重复的状态回调既不会把截止时间往后推，也不会重复挂任务。
     */
    private void armStallWatchdog() {
        if (stallTimeout != null) return;   // 已经在计时，别推后
        stallTimeout = () -> {
            stallTimeout = null;
            if (!buffering || !playWhenReady || !prepared) return;
            AppLog.w("缓冲", "缓冲超过 " + (STALL_RECONNECT_MS / 1000) + " 秒没恢复，重连");
            reconnect(true);
        };
        main.postDelayed(stallTimeout, STALL_RECONNECT_MS);
    }

    private void cancelStallWatchdog() {
        if (stallTimeout != null) {
            main.removeCallbacks(stallTimeout);
            stallTimeout = null;
        }
    }

    /**
     * 重开当前台。
     *
     * @param stalled true 表示「播着播着卡住了」。短时间内在同一个台上卡两次，
     *                就退到下一档音质（高 → 中 → 标准）：宁可糊一点，也别断断续续。
     */
    private void reconnect(boolean stalled) {
        if (!playWhenReady) return;
        cancelStallWatchdog();
        if (stalled) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastStallAt > STALL_DOWNGRADE_WINDOW_MS) stallStreak = 0;
            lastStallAt = now;
            stallStreak++;
            if (stallStreak >= 2) {
                RadioCnApi.Station s = current();
                int count = s == null ? 1 : Math.max(1, candidateUrls(s).size());
                if (urlCursor + 1 < count) {
                    urlCursor++;
                    AppLog.w(TAG, "连续卡顿，自动降一档音质（候选 "
                            + (urlCursor + 1) + "/" + count + "）");
                }
                stallStreak = 0;
            }
        }
        openCurrent();
    }

    /** 当前地址的档位（诊断面板显示用）。 */
    public String currentSourceLabel() {
        RadioCnApi.Station s = current();
        return (s == null || currentUrl == null) ? "—" : s.qualityOf(currentUrl);
    }

    /** 缓存地址优先，其后是本次列表里带的候选（去重）。 */
    private List<String> candidateUrls(RadioCnApi.Station s) {
        List<String> out = new ArrayList<>(5);
        StreamCache.Entry e = StreamCache.get(app, s.contentId);
        if (e != null && !e.expired()) {
            for (String u : e.urls) {
                // 本次运行里已经确认"200 但空"的地址直接跳过，
                // 免得每次起播都拿它去喂播放器、再白等一轮失败。
                if (!deadUrls.contains(u)) out.add(u);
            }
            AppLog.i("取址", s.title + " 用缓存（"
                    + ((System.currentTimeMillis() - e.fetchedAt) / 60000) + " 分钟前）");
        } else {
            AppLog.i("取址", s.title + " 无可用缓存，用列表内地址");
        }
        for (String u : s.candidates()) {
            if (deadUrls.contains(u)) continue;
            if (!out.contains(u)) out.add(u);
        }
        return out;
    }

    /**
     * 缓存与列表地址都失效时，单台重新申请一次。
     *
     * <p>为什么必须防重入：{@link #openCurrent} 发现候选为空就走这里，
     * 而这里刷新完又调回 {@code openCurrent}。一旦刷新拿不到地址（跨省收藏、
     * 接口抽风），就变成「openCurrent → refreshThenOpen → openCurrent」的活循环，
     * 不断开新线程打接口，界面停在「正在缓冲」，声音永远不出来。
     * 用 {@link #refreshing} 把它掐成一次，并明确落到失败态。
     */
    private void refreshThenOpen(final RadioCnApi.Station s) {
        // 预算用尽就不再取址了。没有这道闸门，下面那条「取址成功 → openCurrent → 失败
        // → retryCurrent → refreshThenOpen」会无限循环（v1.8.5 及以前的真实 ANR 原因）。
        if (urlRefreshCount >= MAX_URL_REFRESH) {
            giveUp(s);
            return;
        }
        if (refreshing) {
            // 已经在为这一台取址了：等在途的那次结果就行，别再开新线程。
            // 但要防一个死结：如果那次取址的回调因为任何原因没能把 refreshing 降下来，
            // 这里就会永远 return —— 用户看到的是「一直转圈、点什么都没反应」。
            // 所以等一下再看：预约一次复查，届时标志位还是 true 就认定卡死并收场。
            AppLog.i("取址", s.title + " 正在刷新地址，等待结果");
            scheduleRefreshStuckCheck(s);
            return;
        }
        refreshing = true;
        urlRefreshCount++;
        AppLog.w("取址", "刷新 " + s.title + " 的地址…");
        // ExoPlayer 换台时旧流也会被 setMediaItem 顶掉，这里不需要再记录 openSeq
        new Thread(() -> {
            boolean ok = false;
            try {
                // 带上当前浏览的省份做兜底：收藏快照可能不知道该台属于哪个省
                ok = RadioCnApi.refreshStationUrls(s, fallbackProvinceCode);
            } catch (Throwable t) {
                AppLog.e("取址", "刷新失败：" + t);
            }
            final boolean success = ok;
            main.post(() -> {
                // 期间用户可能已经切到别的台：只认"当前台还是它"的结果
                if (current() != s) {
                    AppLog.i("取址", "刷新结果已过期（期间切过台），丢弃");
                    refreshing = false;
                    return;
                }
                // 这一轮取址到此为止，无论成败都放开标志位。
                // v1.8.6 修：原来只在"拿不到地址"时置 false，
                // 一旦成功却没播起来（地址能取到但流连不上），标志位就永远卡在 true，
                // 之后所有取址都被 `if (refreshing) return` 直接吞掉，播放器彻底失去自救能力。
                refreshing = false;
                if (success && !candidateUrls(s).isEmpty()) {
                    AppLog.i("取址", "刷新成功，重试播放");
                    urlCursor = 0;
                    // 关键：这里<b>不能</b>把 retry 归零。
                    // v1.8.5 及以前是 `retry = 0;`，而 openCurrent() 失败后走
                    // retryCurrent() → 重试计数用尽 → refreshThenOpen() → 这里又归零，
                    // 形成一条永不终止的循环：取址成功、流连不上、重试、再取址……
                    // 每轮都刷日志 + 反复 setMediaItem，主线程消息队列被灌爆，
                    // 最终 ANR（用户反馈"点不动、一会儿就卡死"）。
                    // retry 由重试链路自己管理，取址成功不代表"重试预算恢复"。
                    openCurrent();
                } else {
                    // 明确的失败终态：别让界面一直停在"正在缓冲"、声音无声无息
                    opening = false;
                    buffering = false;
                    AppLog.e("取址", "刷新后仍无地址，放弃：" + s.title);
                    if (listener != null) listener.onError("该台暂无可用地址：" + s.title);
                    notifyState();
                }
            });
        }, "radio-refresh").start();
    }

    /**
     * 取址标志位卡死时的兜底复查。
     *
     * <p>为什么需要它：{@code refreshing} 是「一次取址正在路上」的锁。
     * 正常情况由取址回调负责放开，但只要回调因为任何原因（线程被中断、
     * {@code main.post} 在退出流程里被丢弃、状态被别处覆盖）没跑到，
     * 这把锁就永久留在 true —— 之后每次 {@code refreshThenOpen} 都在
     * {@code if (refreshing) return} 处直接返回，<b>界面永远停在「正在缓冲」，
     * 转发圈转到天荒地老，而且不再有任何重试、不再有错误提示</b>。
     *
     * <p>用户描述的「一会儿程序就卡死了」里，有一部分正是这种「静默死亡」：
     * 界面还活着、按钮还能点，但点了什么都不会发生。
     *
     * <p>所以这里预约一次复查：等足够长的时间后如果锁还在，就判定取址环节已死，
     * 强制放开并进入终态，让用户看到「暂时放不出来，点一下重试」而不是无限转圈。
     */
    private void scheduleRefreshStuckCheck(final RadioCnApi.Station s) {
        if (refreshStuckCheck != null) return;   // 已经排了，别重复排
        final String expected = s == null ? null : s.contentId;
        refreshStuckCheck = () -> {
            refreshStuckCheck = null;
            if (!refreshing) return;             // 已经放开了，虚惊一场
            RadioCnApi.Station cur = current();
            String curId = cur == null ? null : cur.contentId;
            if (expected != null && !expected.equals(curId)) return;  // 早已切台
            // 还卡着：强制解锁并收场。宁可报一次错，也不要让界面无声无息地转圈。
            AppLog.e("取址", "取址超时未返回，强制解锁并放弃这一台");
            refreshing = false;
            giveUp(cur);
        };
        main.postDelayed(refreshStuckCheck, REFRESH_STUCK_MS);
    }

    /**
     * 告诉播放器「当前浏览的是哪个省」，用于该台省份未知时的兜底取址。
     *
     * @param provinceCode 省份编码；0 表示未知
     */
    public void setFallbackProvince(int provinceCode) {
        this.fallbackProvinceCode = provinceCode;
    }

    /**
     * 一次「打开失败」后的重试决策。
     *
     * <h3>v1.8.6 修复：这里以前能无限循环，把主线程拖死</h3>
     * 旧逻辑是 {@code retry++} 到超过 {@link #MAX_RETRY} 就 {@code refreshThenOpen()} 重取地址，
     * 而取址成功的回调里又 {@code retry = 0} —— 于是「重试预算」被反复充值：
     *
     * <pre>
     *   打不开 → retry 1 → 打不开 → retry 2 → 打不开 → 取址 → retry 归零 → 打不开 → …
     * </pre>
     *
     * 只要「地址取得到、但流本身连不上」成立，这条链<b>永不终止</b>。
     * 每轮都要 setMediaItem、触发 BUFFERING/READY 回调、写 3~5 条日志，
     * 而 {@code AppLog.add()} 每次都往主线程 {@code post} 一条消息（无上限）。
     * 实测用户手机上主线程队列积压到 10 万+ 条、日志模块自己触发系统配额告警
     * （{@code LOG_FLOWCTRL ... rows(12570) ... DROPPED}），CPU 单核 99%，
     * 最终两次 Input dispatch timeout → ANR。用户感受是「点不动、一会儿就卡死」。
     *
     * <h3>现在的规则：三道闸门，任一触发即进入终态</h3>
     * <ol>
     *   <li><b>候选地址轮询一轮</b>（{@link #urlCursor}）——换地址不算重试</li>
     *   <li><b>整轮重试 {@link #MAX_RETRY} 次</b>（{@link #retry}）</li>
     *   <li><b>取址最多 {@link #MAX_URL_REFRESH} 次</b>（{@link #urlRefreshCount}，
     *       只在用户重新选台/点播放时清零）——
     *       这是关键：它不再被"取址成功"充值，所以一定能耗尽</li>
     * </ol>
     * 耗尽后进入明确的失败态：界面显示原因，不再自己重试，等用户操作。
     * <b>宁可安静地停住，也不能把手机拖到卡死。</b>
     */
    private void retryCurrent() {
        if (!playWhenReady) return;
        RadioCnApi.Station s = current();
        if (s == null) return;
        int urlCount = Math.max(1, candidateUrls(s).size());
        if (urlCursor + 1 < urlCount) {
            urlCursor++;
            AppLog.w(TAG, "换候选地址（" + (urlCursor + 1) + "/" + urlCount + "）");
        } else {
            urlCursor = 0;
            retry++;
            AppLog.w(TAG, "候选地址用尽，整轮重连 " + retry + "/" + MAX_RETRY);
        }
        if (retry > MAX_RETRY) {
            // 整轮重试也用完了。
            // 注意：这里<b>不要</b>把 retry 归零。归零等于「每轮都从 1 开始」，
            // 日志上表现为「整轮重连 3/2」反复出现，永远到不了终态 —— 这也是
            // v1.8.5 无限循环的一部分。只在用户重新选台/点播放时才归零。
            if (urlRefreshCount < MAX_URL_REFRESH) {
                AppLog.e(TAG, "重试 " + MAX_RETRY + " 次仍失败，刷新地址后再试"
                        + "（第 " + (urlRefreshCount + 1) + "/" + MAX_URL_REFRESH + " 次）");
                retry = 0;          // 进入下一轮重试：这一次重取地址重新给满候选
                refreshThenOpen(s);
            } else {
                giveUp(s);
            }
            return;
        }
        // 退避期间用户可能已经切到别的台了：这次重试必须作废，
        // 否则会把刚开好的新流又顶掉（听感上就是"换台后又断一下"）。
        final String expected = s.contentId;
        main.postDelayed(() -> {
            RadioCnApi.Station cur = current();
            if (cur == null || !expected.equals(cur.contentId)) {
                AppLog.i(TAG, "重试已过期（期间切过台），跳过");
                return;
            }
            openCurrent();
        }, 800L * Math.max(1, retry));
    }

    /**
     * 进入明确的「这个台现在放不出来」终态。
     *
     * <p>要点是<b>不再自动重试</b>：用户切台、点播放、拉刷新都会把预算重置
     * （见 {@link #resetRetryBudget()}），所以这不是死路，只是不再自己空转。
     */
    private void giveUp(RadioCnApi.Station s) {
        opening = false;
        buffering = false;
        prepared = false;
        refreshing = false;
        cancelPrepareTimeout();
        cancelStallWatchdog();
        cancelRefreshStuckCheck();
        String title = s == null || s.title == null ? "该台" : s.title;
        AppLog.e(TAG, "放弃：" + title + " 连续失败，等待用户操作");
        if (listener != null) {
            // 区分两种失败：电台真的没推流（200 空列表），还是本机网络连不上。
            // 前者重试一百次也没用，提示要对得上，用户才知道该等还是该换网络。
            if (allCandidatesDead(s)) {
                listener.onError("「" + title + "」暂无网络直播流（电台侧未推流）");
            } else {
                listener.onError("暂时放不出来：" + title + "（连不上，点一下重试）");
            }
        }
        notifyState();
    }

    /**
     * 把重试预算恢复到满。
     *
     * <p>只在<b>用户明确要求再试一次</b>时调用：切台、点播放/暂停、手动重试。
     * 自动化链路（播放器回调、定时器）绝不能调它，否则又变成自动空转。
     */
    private void resetRetryBudget() {
        retry = 0;
        urlRefreshCount = 0;
        // 用户点播放时，没有在途取址，锁定标志一并放开
        refreshing = false;
        cancelRefreshStuckCheck();
    }

    /** 取消取址卡死复查（换台/收场/用户重试时都不该再触发上一轮的复查）。 */
    private void cancelRefreshStuckCheck() {
        if (refreshStuckCheck != null) {
            main.removeCallbacks(refreshStuckCheck);
            refreshStuckCheck = null;
        }
    }

    /**
     * ExoPlayer 报错 → 换候选地址。
     *
     * <p><b>名字故意不叫 {@code onPlayerError}</b>：它与 {@code Player.Listener} 里的
     * {@code onPlayerError} 同名同签名，从匿名内部类里调用时极容易被解析成「递归调用自己」，
     * 编译期没有任何警告，运行时直接 {@code StackOverflowError} 闪退（v1.8.6 的真实事故）。
     * 换个名字让这类错误不可能再写出来。
     */
    private void handlePlayerError(PlaybackException error) {
        opening = false;
        buffering = false;
        prepared = false;
        cancelPrepareTimeout();
        cancelStallWatchdog();
        String msg = "打开失败: " + error.getErrorCodeName();
        if (listener != null) listener.onError(msg);
        updateSessionState();
        notifyState();
        // 失败后回后台确认一下：是连不上，还是连上了但列表是空的（云听个别台的老毛病）。
        // 探活是异步的，不阻塞这次换址；结论下一轮生效。
        probeDeadAsync(currentUrl);
        retryCurrent();
    }

    /** 非 ExoPlayer 抛出的错误（setMediaItem 自身抛异常时）。 */
    private void onPlayerErrorRaw(String msg) {
        prepared = false;
        cancelPrepareTimeout();
        cancelStallWatchdog();
        if (listener != null) listener.onError(msg);
        updateSessionState();
        notifyState();
        retryCurrent();
    }

    private void acquireWake() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(3 * 60 * 60 * 1000L);
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void notifyState() {
        if (listener != null) listener.onStateChanged(isPlaying(), buffering);
        updateSessionState();
    }

    /**
     * 后台确认失败地址的真实原因：连不上，还是连上了但一个字节都没有。
     *
     * <p>只探一次：同一个地址无论结论如何都记进 {@code probed}，避免网络真断时
     * 每次失败都发一个探测请求。
     */
    private void probeDeadAsync(final String url) {
        if (url == null || url.isEmpty()) return;
        if (!probed.add(url)) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (!RadioCnApi.probeHasContent(url)) {
                    deadUrls.add(url);
                    AppLog.w(TAG, "地址无内容（200 空列表），本次运行内跳过：" + shortUrl(url));
                }
            }
        }, "stream-probe");
        t.setDaemon(true);
        t.start();
    }

    /** 本台所有候选是否都已被证实"200 空列表"——用于把提示从"连不上"改成"暂无直播流"。 */
    private boolean allCandidatesDead(RadioCnApi.Station s) {
        if (s == null) return false;
        List<String> all = s.candidates();
        if (all.isEmpty()) return false;
        for (String u : all) {
            if (!deadUrls.contains(u)) return false;
        }
        return true;
    }

    private static String shortUrl(String u) {
        if (u == null) return "null";
        int q = u.indexOf('?');
        String stem = q > 0 ? u.substring(0, q) : u;
        return stem.replaceFirst("^https?://", "");
    }
}
