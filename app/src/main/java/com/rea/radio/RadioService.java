package com.rea.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;

import java.util.List;

/**
 * 前台服务：播放器与 MediaSession 的真正宿主。
 *
 * <h3>为什么必须这么做</h3>
 * 之前 MediaSession 建在 Activity 里，Activity 一销毁（按返回、切到导航、车机回收）
 * 会话就 {@code release}，车机媒体中心左下角自然看不到「星愿收音机」，方向盘按键也会派空。
 * 把播放器搬进前台服务后：
 * <ul>
 *   <li>服务存活 → MediaSession 存活 → 常驻媒体条目；</li>
 *   <li>通知带播放控制，部分车机把「正在播放的媒体通知」作为媒体源 UI；</li>
 *   <li>可以随车机开机自启（见 {@link BootReceiver}），一上车就是「活的」媒体会话。</li>
 * </ul>
 */
public class RadioService extends Service implements RadioPlayer.Listener, CallGuard.Listener {

    public static final String ACTION_START = "com.rea.radio.START";
    public static final String ACTION_NEXT = "com.rea.radio.NEXT";
    public static final String ACTION_PREV = "com.rea.radio.PREV";
    public static final String ACTION_TOGGLE = "com.rea.radio.TOGGLE";
    public static final String ACTION_PLAY_INDEX = "com.rea.radio.PLAY_INDEX";

    private static final String CHANNEL = "radio_playback";
    private static final int NOTIF_ID = 1024;
    private static final String PREF = "service_state";
    private static final String KEY_LAST = "last_station_title";

    private RadioPlayer player;
    private static volatile RadioService current;

    /**
     * 音频输出被切断（拔出耳机、蓝牙/车机断开）→ 暂停。
     *
     * <p>这条是 Android 媒体应用的基本规范，也是「车上拔掉蓝牙」时最容易被忽略的一处：
     * 不处理的话来电/断开瞬间音频会从手机外放继续响，车里和车外都尴尬。
     * 参考实现见 docs/听听广播-媒体实现分析.md（喜马拉雅 SDK 同样监听这个广播）。
     *
     * <p>只暂停、不停止：连接恢复后用户按一下方向盘就能继续，不用重新选台。
     */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent == null
                    || !AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                return;
            }
            RadioPlayer p = player;
            if (p == null || !p.isPlayWhenReady()) return;
            AppLog.i("音频", "音频输出被切断（耳机/蓝牙断开），暂停");
            p.pause();
        }
    };
    private boolean noisyRegistered;

    /** 服务级就绪后再打开界面，界面直接复用同一个播放器。 */
    public static RadioPlayer player() {
        RadioService s = current;
        return s == null ? null : s.player;
    }

    public static boolean isRunning() {
        return current != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        player = new RadioPlayer(this);
        player.setListener(this);
        RadioPlayerHolder.set(player);
        current = this;
        // 注册成车机媒体中心的媒体源（v1.9.1）。
        //
        // 必须在播放器建好之后：注册成功会立刻上报一次状态，早于播放器就只能报空。
        // 晚于 startForeground 也不行 —— 开机自启那条路径里播放器一起来就开始播，
        // 中间那几秒的方向盘按键会白丢。
        EasMediaCenter.install(this, player);
        startForegroundCompat();
        registerNoisyReceiver();
        // 通话让路：来电响铃 / 通话期间必须把方向盘按键和媒体源交回通话界面，
        // 否则「下一曲接听、上一曲挂断」这两个原有的按键习惯会失效（v1.9.6 修复）。
        CallGuard.start(this, this);
        AppLog.i("服务", "前台服务启动，播放器就绪");
    }

    /**
     * 注册「音频输出被切断」广播。
     *
     * <p>Android 13（API 33）起动态注册必须显式声明导出性，否则直接抛
     * {@code SecurityException}；低于 33 用老签名。本应用 compileSdk 34、
     * targetSdk 28，两条路都要能编过。
     */
    private void registerNoisyReceiver() {
        if (noisyRegistered) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(noisyReceiver, f);
            }
            noisyRegistered = true;
        } catch (Throwable t) {
            // 注册失败不该拖垮播放：这条只是体验兜底，缺了不影响出声。
            AppLog.w("音频", "noisy 广播注册失败：" + t);
        }
    }

    /**
     * 进前台。分版本处理，少一步就会在 Android 14+ 上被拒。
     *
     * <p><b>为什么不能只写 {@code startForeground(id, notif)}：</b>
     * Android 14（API 34）起，清单里声明了 {@code foregroundServiceType} 的服务，
     * 调 {@code startForeground} 时<b>必须显式传入 type</b>，否则抛
     * {@code MissingForegroundServiceTypeException}。抛出来之后前台状态没建立，
     * 进程就停在 background（importance=M），正好复现 Carlink 下
     * {@code cannot transition from M to F} 的现象。
     *
     * <p>另外 {@code mediaPlayback} 类型在 Android 14+ 还要求：
     * <ul>
     *   <li>声明 {@code FOREGROUND_SERVICE_MEDIA_PLAYBACK} 权限（清单已加）；</li>
     *   <li>启动时有音频焦点或媒体会话，否则可能被系统拒绝。</li>
     * </ul>
     * 这里做了兜底：万一被拒，退回不带 type 的调用，至少不让整个进程崩掉。
     */
    private void startForegroundCompat() {
        Notification n = buildNotification(null, false);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                return;
            } catch (Throwable t) {
                AppLog.e("服务", "带 mediaPlayback 类型进前台失败：" + t);
            }
        }
        try {
            startForeground(NOTIF_ID, n);
        } catch (Throwable t) {
            // Android 12+ 后台启动前台服务受限时会抛 ForegroundServiceStartNotAllowedException。
            // 这里不吞掉整个进程：服务仍活着，只是暂时不在前台，用户点开界面后会重新进前台。
            AppLog.e("服务", "进前台失败（后台启动受限？）：" + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_NEXT.equals(action)) player.next();
        else if (ACTION_PREV.equals(action)) player.prev();
        else if (ACTION_TOGGLE.equals(action)) player.toggle();
        else if (ACTION_PLAY_INDEX.equals(action) && intent != null) {
            player.playAt(intent.getIntExtra("index", 0));
        } else {
            AppLog.i("服务", "启动指令，当前队列 " + player.queue().size() + " 台");
        }
        return START_STICKY;
    }

    /** 由界面在列表加载完成后注入队列（服务只负责播，不管取列表）。 */
    public void setQueue(List<RadioCnApi.Station> list, int startIndex, boolean autoPlay) {
        player.setQueue(list, startIndex, autoPlay);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        AppLog.i("服务", "前台服务销毁");
        // 先停通话监听：它会在通话状态变化时回调本服务，服务都销毁了没意义。
        CallGuard.stop();
        // 先解绑媒体源再放播放器：解绑时会还掉播放焦点，
        // 反过来做的话媒体中心会短暂持有一个已经 release 的客户端。
        EasMediaCenter.uninstall();
        if (noisyRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Throwable ignored) {
                // 已注销过就算了，不值得为它打日志
            }
            noisyRegistered = false;
        }
        if (player != null) player.release();
        RadioPlayerHolder.set(null);
        current = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------ 播放器回调

    @Override
    public void onStationChanged(int index, RadioCnApi.Station station) {
        String title = station == null ? null : station.title;
        if (title != null) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putString(KEY_LAST, title).apply();
        }
        updateNotification(station, player.isPlayWhenReady());
    }

    @Override
    public void onStateChanged(boolean playing, boolean buffering) {
        updateNotification(player.current(), playing);
    }

    @Override
    public void onError(String message) {
        AppLog.e("服务", message);
    }

    // ------------------------------------------------------------ 通话

    /**
     * 通话/响铃状态变化（由 {@link CallGuard} 在主线程回调）。
     *
     * <p><b>v1.9.7 起这里只做「暂停 / 恢复」这一件事</b>，不再碰媒体中心的播放焦点，
     * 也不再 {@code abandonPlayFocus}。依据是逆向「星愿车控完全正常酷我音乐 6.0.0.9」：
     * 酷我整个包里 0 处 {@code abandonPlayFocus}，通话期间从不还焦点，而它的
     * 「下一曲接听、上一曲挂断」在星愿车机上完全正常。
     *
     * <p>决定按键去向的是<b>媒体源类型</b>（{@code SOURCE_TYPE_ONLINE}=6 vs
     * {@code SOURCE_TYPE_DAB}=11），不是焦点。主动还焦点反而会让媒体中心把当前音源
     * 切回原车收音机，方向盘立刻变成调台 —— 那正是要修的现象。
     *
     * <p>老实现里「先还焦点、再暂停」的顺序约束因此作废，两个动作已无先后要求。
     */
    @Override
    public void onCallStateChanged(boolean inCall) {
        RadioPlayer p = player;
        if (p == null) return;
        // v1.9.7：通话期间<b>不再动媒体中心的播放焦点</b>。
        // 理由见 EasMediaCenter.onCallStarted() —— 逆向酷我车机版证明它从不还焦点，
        // 而按键去向由「媒体源类型」决定，不是由焦点决定。
        if (inCall) {
            EasMediaCenter.onCallStarted();
            p.onCallStarted();
        } else {
            p.onCallEnded();
            EasMediaCenter.onCallEnded();
        }
        updateNotification(p.current(), p.isPlayWhenReady());
    }

    // ------------------------------------------------------------ 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "电台播放", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void updateNotification(RadioCnApi.Station s, boolean playing) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(s, playing));
    }

    private Notification buildNotification(RadioCnApi.Station s, boolean playing) {
        String title = s == null ? "星愿收音机" : s.title;
        String sub = s == null ? "准备中…" : (playing ? "正在播放 · FM 直播" : "已暂停");

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(sub)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_previous, "上一台",
                        cmd(ACTION_PREV, 11)).build())
                .addAction(new Notification.Action.Builder(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", cmd(ACTION_TOGGLE, 12)).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_next, "下一台",
                        cmd(ACTION_NEXT, 13)).build());

        if (Build.VERSION.SDK_INT >= 21) {
            Notification.MediaStyle style = new Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2);
            // 关键一步，缺了整个通知就是个「长得像媒体控件」的普通通知：
            // 系统不知道该由谁响应它的按钮，车机媒体中心也不会把它当成活跃媒体。
            // Android 21+ 用 setMediaSession；API 21 以下没有 MediaStyle，走不到这里。
            if (player != null && player.sessionToken() != null) {
                style.setMediaSession(player.sessionToken());
            }
            b.setStyle(style);
        }
        return b.build();
    }

    private PendingIntent cmd(String action, int req) {
        Intent i = new Intent(this, RadioService.class).setAction(action);
        return PendingIntent.getService(this, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
