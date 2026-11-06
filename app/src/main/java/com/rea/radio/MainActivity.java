package com.rea.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 车机主界面（横屏 1920×1080）。
 *
 * <p>布局：左 46% 控制区（台标 + 台名 + 直播状态 + 节目时间轴 + 三个大按钮），
 * 右 54% 电台列表。
 *
 * <p>播放器不在这里 new，而是复用 {@link RadioService} 的前台服务实例：
 * 这样界面关掉、切到导航，MediaSession 仍常驻，方向盘按键与车机媒体中心条目都不丢。
 */
public class MainActivity extends Activity {

    /** 四川优先，其后为其余省份（按用户“我是四川人”的偏好排序）。 */
    private static final String[] PREFERRED = {"四川", "重庆", "云南", "贵州"};
    static final int DEFAULT_PROVINCE_CODE = 510000;

    /**
     * 车机列表行台名字号，与 {@code layout-h480dp-land/item_station.xml} 里的
     * {@code item_title} 一致（v2.2 起改为 {@code @dimen/car_text_item_title}）。
     *
     * <p>值只在 dimens.xml 定义：默认（2x 车机 / Carlink）22sp，
     * 低密度真车（sw1080dp，160dpi）44sp。这里取默认值作文档说明。
     *
     * <p>v1.8.3 起台名不再拼频率（见 {@link StationTitle} 的注释），
     * 所以这个常量<b>不再参与任何计算</b>，只作为「台名用多大字」的文档说明保留。
     */
    static final float TITLE_SIZE_SP = 22f;

    /**
     * 车机播放卡上的大台名字号，与 {@code layout-h480dp-land/activity_main.xml} 里的
     * {@code tv_station} 一致（v2.2 起改为 {@code @dimen/car_text_station}）。
     *
     * <p>值只在 dimens.xml 定义：默认 34sp、sw1080dp 下 68sp。
     * 同样只作文档说明，不再参与计算。手机版见 {@code layout/}。
     */
    static final float STATION_SIZE_SP = 34f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RadioPlayer player;
    private RadioCnApi.Station[] stations = new RadioCnApi.Station[0];

    /**
     * 本次 onCreate 时生效的是不是夜间。
     * manifest 的 configChanges 含 uiMode，系统切夜间不会重建 Activity，
     * 所以 onConfigurationChanged 里要拿它和新的系统态比较，变了才 recreate。
     */
    private boolean themeNightAtCreate;

    private ListView list;
    private TextView tvStation, tvStatus, tvSubtitle, tvRegion, tvLogo, tvLive,
            tvPeriod, tvTimelineNow, tvMediaHint, btnLog;
    private ProgressBar progressTimeline;
    private android.widget.ImageView ivLogo;
    private View btnPlay, btnPrev, btnNext;

    /**
     * 播放/暂停键里的图标（v1.8.4 起由文字改为矢量图标）。
     *
     * <p>v1.8.4 之前这里是 {@code TextView tvPlayLabel}，靠改文字「播放 / 暂停」表达状态；
     * 现在是 {@code ImageView}，靠换图 {@code ic_play / ic_pause} 表达。
     *
     * <p><b>为什么不能继续用文字</b>：按钮上写「暂停」时，用户按下去其实是暂停，
     * 但很多车机的方向盘/触屏用户会把按钮文字理解成「当前状态」而不是「点它会做什么」，
     * 于是反复犹豫。图形化 + 与主流播放器（参考图）一致，歧义最小。
     */
    private android.widget.ImageView ivPlayIcon;

    /**
     * 播放卡上那颗收藏心形（v1.8.3 新增按钮，v1.8.4 由 ★ 文字改为矢量图标）。
     *
     * <p>两套布局里 {@code btn_fav_playing} 都是 {@code FrameLayout}（圆角按钮），
     * 心形是里面的 {@code ImageView}（id = {@code iv_fav_playing}）。
     * 收藏状态通过按钮的 {@code selected} 传递：心形设了 {@code duplicateParentState}，
     * 它的 tint（{@code res/color/fav_icon_tint.xml}）会跟着变成橙色。
     */
    private View btnFavPlaying;

    private int provinceCode = DEFAULT_PROVINCE_CODE;
    private String provinceName = "四川";
    private List<RadioCnApi.Province> provinceList = new ArrayList<>();
    private int currentIndex = -1;
    private long lastBackAt = 0;
    private PlayPrefs.Snapshot restore;
    private boolean pendingRestore = true;
    /** 列表请求去重：防止同一省份被并发拉两次导致队列重建、冲掉当前台。 */
    private boolean loadingStations = false;
    private int loadingCode = Integer.MIN_VALUE;
    /** 定位得到的城市（只用于界面提示，不参与恢复判断）。 */
    private String lastLocatedCity = "";

    // ------------------------------------------------------------ 进程级列表缓存
    //
    // 场景：车机内存吃紧，App 有前台服务保活 → **进程活着，界面被回收**。
    // 从桌面再点进来时 onCreate 重跑，如果无条件重新拉「省份 + 电台列表」，
    // 就会看到：闪一下「加载 XX 电台…」→ 列表清空 → 重新填回 → 滚动归零，
    // 观感就是「重新加载了列表、播放位置被重置」。
    //
    // 所以把最近一次成功的结果按省份缓存在进程里：
    //   · 重建时先拿缓存把界面铺好（不显示加载态）；
    //   · 网络刷新放到后台静默做，内容没变就完全不碰界面。
    // 进程被系统真正杀掉时缓存自然消失，不影响数据新鲜度。
    private static List<RadioCnApi.Province> cachedProvinces;
    private static String cachedStationsCode;
    private static List<RadioCnApi.Station> cachedStations;

    /**
     * 是否按手机尺寸收紧（行高变矮、滑动阈值变轻、台标按短边缩放）。
     * 手机横竖屏都算；车机与横屏平板为 false。
     */
    private boolean compactUi = false;

    /** 当前是否两栏布局（车机 / 横屏平板）。判据同 {@link PhoneLayout#useTwoColumn}。 */
    private boolean twoColumn = true;


    /** 时间轴秒表：每秒刷新“此刻”位置。 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTimeline();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------ 列表平滑滚动

    /** 待滚动的目标行；-1 表示没有排队的滚动。 */
    private int pendingScrollRow = -1;

    /**
     * 真正执行滚动：按像素距离算时长，从当前位置滑到目标行。
     *
     * <p>连按下一台时，每次都会先 {@code removeCallbacks} 再 post，所以排在队列里的
     * 只有最后一次；而 {@code smoothScrollToPositionFromTop} 是从列表<b>当前</b>位置继续滑，
     * 不会回到起点重来，视觉上就是一段连续的、跟着光标走的滚动。
     */
    private final Runnable smoothScrollTask = new Runnable() {
        @Override
        public void run() {
            int target = pendingScrollRow;
            if (list == null || target < 0) return;
            int first = list.getFirstVisiblePosition();
            int count = list.getChildCount();
            if (count <= 0) {                 // 还没布局：先定位，动画等下一帧
                list.setSelection(target);
                return;
            }
            int rowPx = Math.max(1, list.getHeight() / count);
            int duration = QueueRow.scrollDurationFor(Math.abs(target - first) * rowPx);
            // 平滑滚动内部走 postOnAnimation + smoothScrollBy（动画帧驱动）。
            list.smoothScrollToPositionFromTop(target, 0, duration);
            list.removeCallbacks(settleScrollTask);
            list.postDelayed(settleScrollTask, duration + 80L);
            AppLog.i("列表", "平滑滚到第 " + (target + 1) + " 行（" + duration + "ms）");
        }
    };

    /**
     * 兜底收尾：动画被打断（布局变化、数据更新）时，保证目标行最终真的在屏幕上。
     *
     * <p>这个兜底不是"保险起见"，而是补 AOSP 的一个黑洞：{@code AbsListView.PositionScroller}
     * 在 {@code mDataChanged} 为真时会把滚动请求存进 {@code mPositionScrollAfterLayout}
     * 然后<b>直接 return</b>，而这个延迟任务只在 {@code ListView.layoutChildren()} 里被消费。
     * 也就是说，如果 {@code notifyDataSetChanged()} 之后没有真的产生一次布局，
     * 滚动请求会一直挂着不执行（Android 9.0.0_r1：AbsListView.java:7395 / ListView.java:1945）。
     *
     * <p>所以这里用同步的 {@code setSelectionFromTop} 兜底：它不依赖动画帧，一定会落地。
     * 只有目标行还没露出来才补，避免动画已经到位还硬拽一下。
     */
    private final Runnable settleScrollTask = new Runnable() {
        @Override
        public void run() {
            int target = pendingScrollRow;
            pendingScrollRow = -1;
            if (list == null || target < 0) return;
            int count = list.getChildCount();
            if (count <= 0) {                       // 一直没有子 View（未布局）：直接定位
                list.setSelection(target);
                return;
            }
            int first = list.getFirstVisiblePosition();
            int last = first + count - 1;
            if (target < first || target > last) {
                list.setSelectionFromTop(target, 0);
                AppLog.w("列表", "平滑滚动未到位，兜底定位到第 " + (target + 1) + " 行");
            }
        }
    };

    /** 用户一碰屏幕就放弃程序发起的滚动（否则会和手指抢）。 */
    private void cancelPendingScroll() {
        pendingScrollRow = -1;
        if (list == null) return;
        list.removeCallbacks(smoothScrollTask);
        list.removeCallbacks(settleScrollTask);
    }

    /**
     * v2.1：在 setContentView 之前把夜间位写进 Context，
     * 这样 values-night/ 的 colors 与 styles 才会被选中。
     * 必须在 attachBaseContext 做 —— 晚于这里，主题已经 apply 完了。
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeMode.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        themeNightAtCreate = ThemeMode.isNight(this);
        // 埋点：Carlink 投屏时进程状态异常（importance=carDisplay 却升不到前台），
        // 靠这几行能判断 app 到底跑到哪一步、跑在什么显示器上。
        logDisplayContext("onCreate");
        // 与 onNewIntent 的日志成对看，才能判定「回到 App」到底是复用了实例还是重建了。
        AppLog.i("界面", "onCreate 新实例@" + Integer.toHexString(System.identityHashCode(this))
                + " action=" + (getIntent() == null ? "null" : getIntent().getAction()));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        AppLog.i("界面", "布局已加载");

        list = findViewById(R.id.list_stations);
        tvStation = findViewById(R.id.tv_station);
        tvSubtitle = findViewById(R.id.tv_subtitle);
        tvStatus = findViewById(R.id.tv_status);
        tvRegion = findViewById(R.id.tv_region);
        tvLogo = findViewById(R.id.tv_logo);
        ivLogo = findViewById(R.id.iv_logo);
        tvLive = findViewById(R.id.tv_live);
        tvPeriod = findViewById(R.id.tv_period);
        tvTimelineNow = findViewById(R.id.tv_timeline_now);
        tvMediaHint = findViewById(R.id.tv_media_hint);
        btnLog = findViewById(R.id.btn_log);
        // v1.8.4：两套布局的收藏按钮结构统一了 —— 都是 FrameLayout 包一个
        // ImageView（id=iv_fav_playing）。不再需要 instanceof 分派，
        // 少一处「两套布局长得不一样」的认知负担。
        btnFavPlaying = findViewById(R.id.btn_fav_playing);
        progressTimeline = findViewById(R.id.progress_timeline);
        btnPlay = findViewById(R.id.btn_play);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        // id 仍叫 btn_play_label（保留旧名，避免改测试与外部引用），但类型已是 ImageView
        ivPlayIcon = findViewById(R.id.btn_play_label);

        restore = PlayPrefs.load(this);
        provinceCode = restore.provinceCode;
        provinceName = restore.provinceName;
        // 恢复上次停留的视图（全部 / 收藏）
        showingFavorites = restore.showingFavorites;
        AppLog.i("文件夹", "上次文件夹=" + provinceName + "（" + provinceCode + "）"
                + "，电台=" + (restore.title.isEmpty() ? "无" : restore.title)
                + "，视图=" + (showingFavorites ? "收藏" : "全部"));

        // 先把前台服务拉起来（MediaSession 常驻的前提），再绑定界面。
        // Android 8 起后台不允许 startService，一律走 startForegroundService；
        // 服务自己在 onCreate 里 startForeground，两边配合才不会触发
        // RemoteServiceException: Not allowed to start service。
        startRadioService();
        bindServicePlayer();

        btnPlay.setOnClickListener(v -> {
            if (player != null) player.toggle();
        });
        btnPrev.setOnClickListener(v -> {
            if (player != null) player.prev();
        });
        btnNext.setOnClickListener(v -> {
            if (player != null) player.next();
        });
        tvRegion.setOnClickListener(v -> chooseProvince());
        btnLog.setOnClickListener(v -> showDiagnostics());
        // 播放卡上的收藏星：只有车机（两栏）布局里有，手机布局没有这颗星（见 layout/）
        if (btnFavPlaying != null) {
            btnFavPlaying.setOnClickListener(v -> toggleFavoriteOfPlaying());
        }
        findViewById(R.id.btn_manage).setOnClickListener(v -> showHiddenManager());
        // 更新：点一下才查（不做启动静默检查）。逻辑全在 UpdateChecker 里。
        findViewById(R.id.btn_update).setOnClickListener(v -> new UpdateChecker(this).checkAndPrompt());
        // 版本号徽标：一直显示 vX.Y.Z，点开是「关于」（版本码 / 渠道 / 更新地址）
        initVersionBadge();
        // v2.1：白天 / 夜间 / 跟随系统，点一下循环，recreate 后由 attachBaseContext 换色板
        initThemeButton();
        findViewById(R.id.tab_all).setOnClickListener(v -> setView(false));
        findViewById(R.id.tab_fav).setOnClickListener(v -> setView(true));

        // 上半区滑动换台：上滑/左滑 = 下一台，下滑/右滑 = 上一台。
        // 注意不能挂在 panel_left 的 OnTouchListener 上 —— 按钮 clickable 会先消费事件，
        // 手指按在按钮上起滑就收不到；所以改在 Activity 层「先分发给子控件、再旁听」。
        swipe = new SwipeStationHelper(this, new SwipeStationHelper.Callback() {
            @Override
            public void onSwipeNext() {
                if (player == null) return;
                player.next();
                AppLog.i("手势", "上滑/左滑 → 下一台");
            }

            @Override
            public void onSwipePrev() {
                if (player == null) return;
                player.prev();
                AppLog.i("手势", "下滑/右滑 → 上一台");
            }
        });

        tvStation.setText(restore.title.isEmpty()
                ? "正在载入…"
                : StationTitle.plain(restore.title));
        tvRegion.setText(provinceName + " ▾");
        tvStatus.setText("载入中…");
        updateTimeline();
        ui.post(tick);
        applyScreenMode();
        installAdaptiveLayout();
        loadRegionThenStations();
    }

    /**
     * 车机可用高度有限（2x 车机约 540dp、实测 1x 车机 1920×968dp），
     * 台标 + 台名 + 时间轴 + 按钮很容易超一屏，LinearLayout 会把按钮挤扁。
     *
     * <p>这里在第一帧后用实测高度做一次「弹性收缩」：空间不足时按优先级
     * 依次缩小台标 → 缩台名，按钮行始终保留设计高度。
     * 全部使用 {@code wrap_content} 精确测量，不会触发无限重排。
     */
    private void installAdaptiveLayout() {
        // 单列纵向布局高度由内容撑开、不做压缩；这套收缩逻辑只服务两栏布局
        if (!twoColumn) return;
        final View left = findViewById(R.id.logo_wrap) == null ? null
                : (View) findViewById(R.id.logo_wrap).getParent();
        if (left == null) return;
        left.post(() -> {
            int avail = left.getHeight()
                    - left.getPaddingTop() - left.getPaddingBottom();
            if (avail <= 0) return;

            // 统计除台标外的固定内容高度
            int fixed = 0;
            for (int i = 0; i < ((ViewGroup) left).getChildCount(); i++) {
                View child = ((ViewGroup) left).getChildAt(i);
                if (child.getId() == R.id.logo_wrap
                        || child instanceof android.widget.Space) {
                    continue;
                }
                android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp != null && lp.height > 0) {
                    fixed += lp.height;
                    fixed += lp instanceof ViewGroup.MarginLayoutParams
                            ? ((ViewGroup.MarginLayoutParams) lp).topMargin : 0;
                } else {
                    fixed += child.getHeight();
                }
            }
            int logoH = ((ViewGroup) left).findViewById(R.id.logo_wrap).getHeight();
            int overflow = fixed + logoH - avail;
            if (overflow <= 0) return;

            int newH = Math.max(72, logoH - overflow);
            View logo = findViewById(R.id.logo_wrap);
            ViewGroup.LayoutParams lp = logo.getLayoutParams();
            lp.width = newH;
            lp.height = newH;
            logo.setLayoutParams(lp);
            if (tvLogo != null) tvLogo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    Math.max(30, newH * 0.46f));
            AppLog.i("界面", "空间不足，台标 " + logoH + " → " + newH + "dp 自适应");
        });
    }

    /**
     * 把「跑在哪块屏幕上、什么尺寸、什么模式」打进日志。
     *
     * <p>Carlink 投屏会新建一块虚拟 Display，app 被搬过去后：
     * 宽度/密度/是否车机模式全都可能变，而这些恰恰决定了走两栏还是单列。
     * 没有这几个数，远程排查只能靠猜。
     */
    private void logDisplayContext(String where) {
        try {
            android.content.res.Configuration c = getResources().getConfiguration();
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int[] wh = PhoneLayout.sizeDp(c, dm);
            String display = "默认屏";
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.Display d = getDisplay();
                if (d != null) {
                    display = "id=" + d.getDisplayId()
                            + (d.getDisplayId() == android.view.Display.DEFAULT_DISPLAY ? "(默认)" : "(虚拟屏)");
                }
            }
            AppLog.i("屏幕", where + " " + display
                    + " " + wh[0] + "x" + wh[1] + "dp"
                    + " 密度" + String.format(java.util.Locale.US, "%.2f", dm.density)
                    + " " + dm.widthPixels + "x" + dm.heightPixels + "px"
                    + " uiMode=" + (c.uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK)
                    + " 两栏=" + PhoneLayout.useTwoColumn(wh[0], wh[1])
                    + " 紧凑=" + PhoneLayout.compactMetrics(wh[0], wh[1]));
        } catch (Throwable t) {
            AppLog.w("屏幕", "读取屏幕信息失败：" + t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        //
        // 不能无条件调：普通车机/手机上每次回前台都重发一次 startForegroundService，
        // 会扰动播放器状态（曾经把 FavoriteKeepsScrollTest 的「点收藏不跟随在播台」搞红）。
        // Carlink 投屏时 app 跑在非默认 Display 上，这才是需要补前台的那个场景。
        if (isOnSecondaryDisplay()) {
            AppLog.i("屏幕", "检测到虚拟屏（投屏），补拉前台服务");
            startRadioService();
        }
    }

    /** app 是否被投到了非默认显示器（Carlink 融合桌面/镜像都会新建一块虚拟屏）。 */
    private boolean isOnSecondaryDisplay() {
        if (android.os.Build.VERSION.SDK_INT < 30) return false;
        try {
            android.view.Display d = getDisplay();
            return d != null && d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 启动播放服务（进前台）。
     *
     * <p>统一走 {@code startForegroundService}：Android 8 起 {@code startService}
     * 在后台会直接抛 {@code IllegalStateException}。界面上虽然通常算前台，
     * 但 Carlink 把界面投到虚拟屏时，系统对「是否前台」的判定会迟疑，
     * 这时候 {@code startService} 就是一颗随时会炸的雷。
     *
     * <p>整个调用包在 try 里：宁可服务晚点起来，也不要让界面在 onCreate 就崩掉——
     * 界面崩了用户看到的就是「白屏 + 一直加载中」，比没声音更难排查。
     */
    private void startRadioService() {
        Intent svc = new Intent(this, RadioService.class)
                .setAction(RadioService.ACTION_START);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable t) {
            AppLog.e("服务", "启动播放服务失败：" + t);
            try {
                startService(svc);
            } catch (Throwable t2) {
                AppLog.e("服务", "退回 startService 仍失败：" + t2);
            }
        }
    }

    /**
     * 绑定服务里的播放器。
     * 服务是 {@code startForegroundService} 启动的，onCreate 可能比界面稍晚，
     * 所以这里用短轮询等待，拿到后立刻挂监听。
     */
    private void bindServicePlayer() {
        bindServicePlayer(0);
    }

    private void bindServicePlayer(final int attempt) {
        final RadioPlayer p = RadioService.player();
        if (p != null) {
            attach(p);
            return;
        }
        if (attempt > 40) { // 约 8 秒
            tvStatus.setText("播放服务启动失败");
            AppLog.e("界面", "等待 RadioService 超时");
            return;
        }
        ui.postDelayed(() -> bindServicePlayer(attempt + 1), 200);
    }

    private void attach(RadioPlayer p) {
        player = p;
        player.setListener(new RadioPlayer.Listener() {
            @Override
            public void onStationChanged(int i, RadioCnApi.Station s) {
                highlight(i);
                showStationInfo(s);
                persist(s, i, true);
            }

            @Override
            public void onStateChanged(boolean playing, boolean buffering) {
                renderTransport(playing, buffering);
                persist(player.current(), currentIndex, playing);
            }

            @Override
            public void onError(String msg) {
                tvStatus.setText(msg);
            }
        });
        AppLog.i("界面", "已绑定前台服务播放器");
        // 兜底省份要在第一时间给到播放器：服务可能比界面先起来，
        // 万一此刻正在放一个「没地址」的收藏台，它需要这个值去取址。
        player.setFallbackProvince(provinceCode);
        tvMediaHint.setText(mediaHint());
        alignUiWithPlayer();
        // 注意：不在这里再拉一次列表。onCreate 里已经发过 loadRegionThenStations()，
        // 重复请求会让 showStations() 跑第二遍、把队列重建、把已恢复的台冲掉。
    }

    /**
     * 把界面拉到与播放器一致的状态。
     *
     * <h3>为什么必须有这一步</h3>
     *
     * <p>{@link RadioPlayer.Listener} 只在<b>状态发生变化时</b>回调。界面刚重建（或刚被
     * 从媒体条重新拉起）时，播放器那边可能什么都没变 —— 一次回调都不会来，
     * 界面就一直停在 {@code onCreate} 里从偏好读到的旧值上（空标题、旧按钮图标）。
     * 服务里的播放器其实还在正常播，用户看到的就是「播放位置被重置了」。
     *
     * <p>所以凡是「界面比播放器晚就绪」的时机（绑定成功、onNewIntent 复用实例）
     * 都要主动拉一次。
     */
    private void alignUiWithPlayer() {
        if (player == null) return;
        RadioCnApi.Station cur = player.current();
        if (cur != null) {
            showStationInfo(cur);
            int idx = player.currentIndex();
            if (idx >= 0) {
                currentIndex = idx;
                highlight(idx);
            }
        }
        renderTransport(player.isPlaying(), false);
    }

    /**
     * 已在运行的本实例被再次拉起时的回调。
     *
     * <h3>谁会走到这里</h3>
     *
     * <p>桌面左下媒体条、车机媒体中心的卡片点「进入应用」时，系统发的是
     * {@code NEW_TASK | SINGLE_TOP}（见 {@code RadioPlayer#sessionActivity()}）。
     * 只要 App 的 Task 还在，就会<b>复用当前实例</b>并走本方法，而不是重建 {@code onCreate}。
     *
     * <h3>为什么要打日志</h3>
     *
     * <p>「点媒体条进不去 App」有两种完全不同的失败，日志能一眼分开：
     * <ul>
     *   <li>一行 {@code onNewIntent} 都没有 → 系统<b>根本没发</b>启动请求，
     *       问题在媒体的 {@code sessionActivity} / 包名归属，不在本 Activity；</li>
     *   <li>出现 {@code onCreate} 而不是 {@code onNewIntent} → 请求发了，
     *       但落到了<b>新实例</b>（Task 被回收或 launchMode 不匹配）。</li>
     * </ul>
     * 实例 hash 一并打出来，多实例时能看出是哪一个。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 不 setIntent 的话 getIntent() 一直是首次启动那个，后续调试会被误导
        setIntent(intent);
        AppLog.i("界面", "onNewIntent 复用实例@" + Integer.toHexString(System.identityHashCode(this))
                + " action=" + (intent == null ? "null" : intent.getAction())
                + " component=" + (intent == null ? "-" : intent.getComponent()));
        // 复用实例不会走 onCreate/attach，界面上的台名、按钮图标可能还停在从前台
        // 退出去那一刻，这里主动对齐一次。
        alignUiWithPlayer();
    }

    /**
     * 把播放状态画到界面上（状态文字、播放/暂停图标、直播标识）。
     *
     * <p>抽出来是因为有两个入口：播放器状态**变化**时（监听器）、
     * 以及界面**重建**后要主动对齐一次（此时状态没变，监听器不会回调）。
     */
    private void renderTransport(boolean playing, boolean buffering) {
        tvStatus.setText(buffering ? "正在缓冲…" : playing ? "正在播放"
                : (player != null && player.isPlayWhenReady()) ? "连接中…" : "已暂停");
        // 播放中显示「暂停」图标（点它会暂停），暂停时显示「播放」图标
        if (ivPlayIcon != null) {
            ivPlayIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            ivPlayIcon.setContentDescription(playing ? "暂停" : "播放");
        }
        if (btnPlay != null) {
            btnPlay.setContentDescription(playing ? "暂停" : "播放");
        }
        btnPlay.setSelected(playing);
        // v2.0：不再用字符「●」「❚❚」——字符要靠设备字体提供 glyph，国产 ROM 裁剪不一，
        // 且不同字号下圆点/方块的直径不可控。车机版旁边有一个固定 8dp 的橙色圆点
        // （dot_live）负责"在播"的视觉，这里只负责文字与它的显隐。
        tvLive.setText(playing ? "正在直播" : "已暂停");
        // 浅色界面下 #FF6600 当小字只有约 3:1，压深一档到 accent_deep；暂停态转灰，
        // 让"正在播"这件事在余光里也能分辨。
        tvLive.setTextColor(getResources().getColor(
                playing ? R.color.accent_deep : R.color.text_secondary));
        View dot = findViewById(R.id.dot_live);
        if (dot != null) dot.setVisibility(playing ? View.VISIBLE : View.INVISIBLE);
    }

    /** 顶部提示：告诉用户当前媒体会话状态，方向盘排查时一眼能看。 */
    private String mediaHint() {
        return RadioService.isRunning()
                ? "会话常驻"
                : "媒体会话未启动";
    }

    private void showStationInfo(RadioCnApi.Station s) {
        if (s == null) return;
        // 播放卡台名 40sp，频率同样小两号
        tvStation.setText(StationTitle.plain(s.title));
        tvSubtitle.setText(s.subtitle == null || s.subtitle.length() == 0
                ? "广播直播" : s.subtitle);
        tvPeriod.setText("00:00–24:00");
        applyLogo(s);
        refreshPlayingFavorite();
    }

    /**
     * 同步播放卡上那颗收藏星的状态。
     *
     * <p>三处要调它：切台后（在播的台变了）、收藏状态变了（列表里点 ★ 或点这颗星自己）、
     * 以及第一次进入界面时。
     *
     * <p>用 selected 而不是换文字来表达"已收藏"：文字只换了符号，
     * 在车机上远看几乎分不出；配合 {@code fav_btn_bg} 的橙色底色，
     * 一眼就能看出这台有没有收藏。
     */
    private void refreshPlayingFavorite() {
        if (btnFavPlaying == null) return;
        RadioCnApi.Station cur = currentForFavorite();
        boolean fav = cur != null && FavoriteStore.contains(this, cur.contentId);
        btnFavPlaying.setSelected(fav);
        btnFavPlaying.setContentDescription(fav ? "取消收藏当前电台" : "收藏当前电台");
        // 心形换成实心/空心（形状 + 颜色双重区分），三处入口共用同一份逻辑。
        // 用 findViewById 而不是 findViewWithTag：两套布局里 id 一致，不需要额外维护 tag。
        FavIcon.bind(btnFavPlaying.findViewById(R.id.iv_fav_playing), fav);
    }

    /**
     * 正在播放的台（收藏相关的唯一取值入口）。
     *
     * <p>正常路径就是 {@code player.current()}。但播放器要真起 Media3 + 网络，
     * 单元测试里起不来，收藏星的「跟着在播台翻状态」这条逻辑就永远测不到。
     * 所以留一个 {@link #playingOverrideForTest} 钩子：只在测试里赋值，生产代码永远不动它。
     */
    private RadioCnApi.Station currentForFavorite() {
        if (playingOverrideForTest != null) return playingOverrideForTest;
        return player == null ? null : player.current();
    }

    /**
     * 测试专用：假装某一台正在播，用来验证收藏星的状态跟随（生产代码不写这个字段）。
     */
    RadioCnApi.Station playingOverrideForTest;

    /** 测试专用：重新计算播放卡上收藏星的显示状态。 */
    void refreshPlayingFavoriteForTest() {
        refreshPlayingFavorite();
    }

    /** 测试专用：模拟点击播放卡上的收藏星。 */
    void toggleFavoriteOfPlayingForTest() {
        toggleFavoriteOfPlaying();
    }

    /**
     * 点播放卡上的星：收藏/取消正在播的那台。
     *
     * <p>与列表里的 {@link #toggleFavorite(int)} 是同一件事，只是对象换成"当前正在播的"，
     * 所以不走行号、直接拿 {@link #currentForFavorite()}。
     */
    private void toggleFavoriteOfPlaying() {
        RadioCnApi.Station cur = currentForFavorite();
        if (cur == null) {
            Toast.makeText(this, "还没有在播的电台", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean nowFav = FavoriteStore.toggle(this, cur);
        Toast.makeText(this, (nowFav ? "已加入收藏：" : "已取消收藏：") + cur.title,
                Toast.LENGTH_SHORT).show();
        refreshPlayingFavorite();
        // 收藏变了，右侧列表的星标与"收藏 (n)"计数都要跟着变。
        // 传 false：这是用户主动改列表，不该把列表位置拽到在播台（v1.8.3 修的 bug）
        refreshList(false);
    }

    /**
     * 台标：先用「台名首字」立即填上，再去央广网拉官方台标覆盖。
     * 这样即使网络慢/没网，界面上也永远有东西，不会空白等待。
     *
     * <p>版面由 {@link LogoLoader#applyScaleType} 按图片宽高比决定：
     * 方图裁成圆铺满（不会比圆小），长条标完整显示（不切两端）。
     */
    private void applyLogo(final RadioCnApi.Station s) {
        final String glyph = firstGlyph(s.title);
        tvLogo.setText(glyph);
        tvLogo.setVisibility(View.VISIBLE);
        if (ivLogo != null) ivLogo.setVisibility(View.GONE);

        final String wantId = s.contentId;
        LogoLoader.load(this, s.contentId, s.image, ivLogo, (view, cid, bitmap) -> {
            // 回调回来时用户可能已经切台了，比对 contentId 防止串图
            RadioCnApi.Station cur = player == null ? null : player.current();
            String curId = cur == null ? wantId : cur.contentId;
            if (cid == null || !cid.equals(curId)) return;

            if (bitmap != null) {
                view.setImageBitmap(bitmap);
                view.setVisibility(View.VISIBLE);
                tvLogo.setVisibility(View.GONE);
                // 台标刚落地（内存/磁盘/网络）→ 补一次元数据，锁屏与通知栏才有 ART 图
                if (player != null) player.refreshMetadata();
            } else {
                // 无台标（无 image 字段 / 断网且无缓存）→ 保留字标
                view.setVisibility(View.GONE);
                tvLogo.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 取台名首个有效字符作为台标字（圆形台标只放一个字，辨识度最高）。 */
    static String firstGlyph(String title) {
        if (title != null) {
            for (int i = 0; i < title.length(); i++) {
                char c = title.charAt(i);
                if (Character.isLetterOrDigit(c)) return String.valueOf(c);
            }
        }
        return "F";
    }

    /** 直播没有真实播放进度：用「当天 0 点 → 现在」表示时间轴走到哪。 */
    private void updateTimeline() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);
        int s = c.get(Calendar.SECOND);
        if (progressTimeline != null) progressTimeline.setProgress(h * 60 + m);
        if (tvTimelineNow != null) {
            tvTimelineNow.setText(String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s));
        }
    }

    /**
     * 记录当前状态，供下次开机恢复。
     *
     * <p>这里的 {@code index} 必须始终是「全部电台」列表里的下标 —— 它只是
     * contentId 找不到时的兜底。收藏视图的行号是另一套编号，直接存会让下次
     * 开机恢复到毫不相干的台，所以传进来的 index 不可信时统一按 contentId 反查。
     */
    private void persist(RadioCnApi.Station s, int index, boolean playing) {
        if (s == null) return;
        int safeIndex = indexOf(s.contentId);
        if (safeIndex < 0) safeIndex = Math.max(0, index);
        // 省份是否"用户选的"与当前视图也一并记住：下次打开回到同一个文件夹、同一个标签
        PlayPrefs.save(this, s, safeIndex, provinceCode, provinceName, playing,
                null, showingFavorites);
    }

    // ------------------------------------------------------------ 数据加载

    private void loadRegionThenStations() {
        // ① 暖启动：进程里还留着上次的省份表，就先定文件夹、先渲染列表，
        //    界面立刻有内容，不闪「加载中」。网络请求照发，用来发现列表变化。
        if (cachedProvinces != null && !cachedProvinces.isEmpty()) {
            AppLog.i("列表", "暖启动：复用内存里的省份表，直接恢复界面");
            applyProvince(cachedProvinces, null);
        }
        new Thread(() -> {
            RegionResolver.Result r = RegionResolver.resolve();
            List<RadioCnApi.Province> provs;
            try {
                provs = RadioCnApi.fetchProvinces();
            } catch (Throwable t) {
                provs = new ArrayList<>();
            }
            final List<RadioCnApi.Province> ordered = sortProvinces(provs);
            final RegionResolver.Result rr = r;
            ui.post(() -> {
                if (!ordered.isEmpty()) cachedProvinces = ordered;
                if (rr != null && rr.fromNetwork
                        && restore.provinceCode == DEFAULT_PROVINCE_CODE
                        && "四川".equals(provinceName)) {
                    tvRegion.setText(rr.provinceName
                            + (rr.city.isEmpty() ? "" : " · " + rr.city) + " ▾");
                }
                applyProvince(ordered, rr);
            });
        }).start();
    }

    private List<RadioCnApi.Province> sortProvinces(List<RadioCnApi.Province> all) {
        LinkedHashSet<String> head = new LinkedHashSet<>();
        head.add("四川");
        for (String p : PREFERRED) head.add(p);
        List<RadioCnApi.Province> out = new ArrayList<>();
        for (String name : head) {
            for (RadioCnApi.Province p : all) {
                if (p.name.equals(name) && !out.contains(p)) out.add(p);
            }
        }
        for (RadioCnApi.Province p : all) if (!out.contains(p)) out.add(p);
        if (out.isEmpty()) {
            out.add(new RadioCnApi.Province("四川", 510000));
            out.add(new RadioCnApi.Province("国家", 0));
        }
        return out;
    }

    /**
     * 确定这次要打开哪个「文件夹」（省份）。
     *
     * <p>优先级（这是「每次打开都回到上次那个文件夹」的实现要点）：
     * <ol>
     *   <li>用户上次手动选过的省份 —— 存在偏好里，必须原样恢复，
     *       不能因为车开到别的省就被定位结果顶掉；</li>
     *   <li>从没手动选过（首次安装）→ 才用定位结果；</li>
     *   <li>定位也失败 → 默认四川。</li>
     * </ol>
     * 恢复的省份同时决定列表内容与上次那台电台的所在文件夹，两者必须一致，
     * 否则会出现「记住的是 A 省的台、列表却是 B 省」的错位。
     */
    private void applyProvince(List<RadioCnApi.Province> ordered, RegionResolver.Result rr) {
        provinceList = ordered;

        boolean userChoseBefore = restore.chosenByUser;

        if (!userChoseBefore) {
            // 首次使用：用定位结果
            if (rr != null && rr.fromNetwork && rr.provinceName != null
                    && rr.provinceName.length() > 0) {
                provinceName = rr.provinceName;
                if (rr.city != null && rr.city.length() > 0) {
                    lastLocatedCity = rr.city;
                }
                AppLog.i("文件夹", "首次使用，按定位：" + provinceName);
            } else {
                AppLog.i("文件夹", "首次使用且定位失败，用默认：" + provinceName);
            }
        } else {
            AppLog.i("文件夹", "恢复上次的文件夹：" + provinceName);
        }

        // 省份名 → 省份码（名字对不上时保留原 code）
        for (RadioCnApi.Province p : ordered) {
            if (p.name.equals(provinceName)) {
                provinceCode = p.code;
                break;
            }
        }
        tvRegion.setText(provinceName + " ▾");
        loadStations();
    }

    private void loadStations() {
        final int code = provinceCode;
        final String name = provinceName;
        // 告诉播放器「现在浏览的是哪个省」：收藏里的台若不知道该属哪个省、
        // 又没地址可用，它会拿这个省份去兜底取址，而不是回落写死的四川。
        if (player != null) player.setFallbackProvince(code);

        // 缓存命中：先把界面铺好，再静默刷新。
        // 顺序不能反 —— 先渲染才有东西可看，刷新失败也不会把已有内容清空。
        final List<RadioCnApi.Station> cached =
                (cachedStations != null && String.valueOf(code).equals(cachedStationsCode))
                        ? cachedStations : null;
        if (cached != null && !cached.isEmpty()) {
            AppLog.i("列表", "用缓存渲染 " + name + " 的 " + cached.size() + " 个电台");
            showStations(cached);
        }

        // 同一省份已经在拉，就不重复拉（重复拉会触发 showStations 二次执行、冲掉当前台）
        if (loadingStations && code == loadingCode) {
            AppLog.i("列表", "已在加载 " + name + "，跳过重复请求");
            return;
        }
        loadingStations = true;
        loadingCode = code;
        // 有缓存时不写「加载中」：那一刻列表已经显示出来了，
        // 再盖一层加载态就是用户抱怨的「又刷新了一遍」。
        if (cached == null) tvStatus.setText("加载 " + name + " 电台…");
        AppLog.i("列表", "请求 " + name + "(" + code + ") 电台列表"
                + (cached == null ? "" : "（静默刷新，不影响界面）"));
        new Thread(() -> {
            List<RadioCnApi.Station> res = null;
            String err = null;
            int nationalAdded = 0;
            try {
                res = RadioCnApi.fetchStations(code);
                for (RadioCnApi.Station s : res) s.provinceName = name;
                // 四川目录里再放一份国家台（v1.9.10）。国家台仍然留在「国家」文件夹，
                // 这里是复制，不是搬移；失败只是少这几个台，绝不能连累省级列表。
                if (code == NationalStations.SICHUAN) {
                    List<RadioCnApi.Station> nat = null;
                    try {
                        nat = RadioCnApi.fetchStations(NationalStations.NATIONAL_CODE);
                    } catch (Throwable t) {
                        AppLog.w("列表", "拉国家台失败，四川目录这次不含国家台：" + t.getMessage());
                    }
                    int before = res.size();
                    res = NationalStations.mergeInto(res, nat, code);
                    nationalAdded = res.size() - before;
                }
            } catch (Throwable t) {
                err = t.getMessage();
            }
            final int natAdded = nationalAdded;
            final List<RadioCnApi.Station> got = res;
            final String e = err;
            ui.post(() -> {
                loadingStations = false;
                if (got == null || got.isEmpty()) {
                    if (cached == null) {
                        tvStatus.setText("列表加载失败" + (e == null ? "" : "：" + e));
                        AppLog.e("列表", "加载失败：" + e);
                    } else {
                        // 静默刷新失败：界面已经用缓存铺好了，绝不回退成空列表
                        AppLog.w("列表", "静默刷新失败，保留当前已显示的内容：" + e);
                    }
                    return;
                }
                AppLog.i("列表", "拿到 " + got.size() + " 个电台"
                        + (natAdded > 0 ? "（含并入的国家台 " + natAdded + " 个）" : ""));
                boolean unchanged = cached != null && sameStations(cached, got);
                cachedStations = got;
                cachedStationsCode = String.valueOf(code);
                if (unchanged) {
                    // 内容一模一样：重建 adapter 只会把列表滚回顶部、闪一下
                    AppLog.i("列表", "列表内容无变化，界面与滚动位置保持不动");
                    return;
                }
                showStations(got);
            });
        }).start();
    }

    /** 两份列表是否「内容相同」（按顺序比 contentId）。用于判断要不要重建界面。 */
    private static boolean sameStations(List<RadioCnApi.Station> a, List<RadioCnApi.Station> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            RadioCnApi.Station x = a.get(i);
            RadioCnApi.Station y = b.get(i);
            String xi = x == null ? null : x.contentId;
            String yi = y == null ? null : y.contentId;
            if (xi == null ? yi != null : !xi.equals(yi)) return false;
        }
        return true;
    }

    /**
     * 本地补录开关。列表数据来源不是央广接口（单测注入的假数据）时必须关掉，
     * 否则补录台会混进别人的数据里，列表条数与序号全部错位。
     */
    private boolean localStationsEnabled = true;

    /** 仅用于测试：关掉补录，让 showStations 只处理传进来的数据。 */
    void setLocalStationsEnabled(boolean enabled) {
        this.localStationsEnabled = enabled;
    }

    private void showStations(List<RadioCnApi.Station> data) {
        // 先把央广没收录、但确实在播的成都台补进来（只对四川生效），再排序。
        // 顺序不能反：补录台也要参与「省台/成都台靠前」的分档，先排序再加就排不进去了。
        List<RadioCnApi.Station> withLocal =
                localStationsEnabled ? LocalStations.mergeInto(data, provinceCode) : data;
        if (withLocal.size() != data.size()) {
            AppLog.i("列表", "补录本地台 " + (withLocal.size() - data.size()) + " 个（央广未收录）");
        }
        List<RadioCnApi.Station> ordered = provinceFirst(withLocal);

        // 隐藏的台不参与列表，也不进播放队列 ——
        // 否则方向盘上一台/下一台会切到用户看不见的台，像是"卡住了"。
        Set<String> hidden = HiddenStore.ids(this);
        if (!hidden.isEmpty()) {
            List<RadioCnApi.Station> visible = new ArrayList<>(ordered.size());
            for (RadioCnApi.Station s : ordered) {
                if (!hidden.contains(s.contentId)) visible.add(s);
            }
            AppLog.i("隐藏", "过滤掉 " + (ordered.size() - visible.size())
                    + " 个隐藏电台，剩余 " + visible.size() + " 个");
            // 全被隐藏了也至少留一个，否则界面空转
            if (visible.isEmpty()) visible.addAll(ordered);
            ordered = visible;
        }
        stations = ordered.toArray(new RadioCnApi.Station[0]);

        // 先把所有台的地址落盘缓存：下次开机可秒开，弱网也不至于空白
        for (RadioCnApi.Station s : stations) StreamCache.put(this, s);
        AppLog.i("取址", "已缓存 " + stations.length + " 个台的地址");

        list.setAdapter(new StationAdapter());
        list.setOnItemClickListener((p, v, pos, id) -> playFromList(pos));

        // ---- 决定起播哪一台，优先级从高到低：
        // ① 播放服务里已经在放的台（界面被重开、服务还活着，必须沿用，不能打断）
        // ② 上次退出时记的台（contentId 优先，下标兜底）
        // ③ 列表第一个
        RadioCnApi.Station playingNow = player == null ? null : player.current();
        String keepId = playingNow == null ? null : playingNow.contentId;
        boolean serviceAlive = keepId != null;

        int start;
        boolean resumePlay;
        if (serviceAlive && (start = indexOf(keepId)) >= 0) {
            // 服务在放、列表里也有 → 只把队列换成新列表并指向同一台，不重新开流
            resumePlay = player.isPlayWhenReady();
            AppLog.i("列表", "沿用服务正在播放的台：" + ordered.get(start).title);
        } else {
            start = pendingRestore ? restoreIndex() : 0;
            resumePlay = pendingRestore ? restore.playing : true;
            AppLog.i("列表", pendingRestore
                    ? "恢复到上次播放的台：" + (start >= 0 && start < ordered.size()
                            ? ordered.get(start).title : "?")
                    : "保持当前索引 " + start);
        }
        pendingRestore = false;

        if (player != null) {
            if (serviceAlive) {
                // 服务已有队列：只换队列引用 + 对齐下标，音频不断
                player.rebindQueue(ordered, start, resumePlay);
            } else {
                // 冷启动：按上次记下的台建队列；若已有可用流则沿用，否则才新开
                player.setQueueQuietly(ordered, start, resumePlay);
            }
        }
        currentIndex = start;
        highlight(start);
        if (start >= 0 && start < stations.length) showStationInfo(stations[start]);
        refreshList();
    }

    private int indexOf(String contentId) {
        if (contentId == null) return -1;
        for (int i = 0; i < stations.length; i++) {
            if (contentId.equals(stations[i].contentId)) return i;
        }
        return -1;
    }

    /** 上次播放位置：contentId 优先（最可靠），找不到再用下标兜底。 */
    private int restoreIndex() {
        int byId = indexOf(restore.contentId);
        if (byId >= 0) return byId;
        if (restore.index >= 0 && restore.index < stations.length) return restore.index;
        return 0;
    }

    /**
     * “本地台优先”排序：省台 → 成都/省会台 → 其余。
     *
     * <p>规则本体在 {@link StationSorter}（纯函数，有独立单测）；这里只做转发。
     */
    private List<RadioCnApi.Station> provinceFirst(List<RadioCnApi.Station> data) {
        return StationSorter.provinceFirst(data, provinceName);
    }

    private void chooseProvince() {
        if (provinceList.isEmpty()) return;
        String[] names = new String[provinceList.size()];
        for (int i = 0; i < names.length; i++) names[i] = provinceList.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("选择地区")
                .setItems(names, (d, which) -> {
                    RadioCnApi.Province p = provinceList.get(which);
                    provinceName = p.name;
                    provinceCode = p.code;
                    tvRegion.setText(provinceName + " ▾");
                    pendingRestore = false;
                    // 用户主动选的文件夹，下次打开必须原样回到这里
                    PlayPrefs.markProvinceChosen(this, p.code, p.name);
                    AppLog.i("文件夹", "用户切换文件夹：" + p.name);
                    loadStations();
                })
                .show();
    }

    // ------------------------------------------------------------ 版本 / 关于

    /**
     * 版本号徽标：右下角（手机竖屏是顶栏）一直显示 {@code vX.Y.Z}。
     *
     * <p><b>为什么用 PackageManager 读、而不是直接用 {@code BuildConfig}：</b>
     * Carlink 包的 applicationId 是借来的白名单包名，但用户关心的是「车上装的
     * 到底是哪一个版本」——只有从系统的已安装包信息里读，才等于用户真正在跑的东西。
     * versionCode 也从这里取（报问题时「v1.9.12（36）」比只报版本名有用）。
     * 读失败时退回编译期常量，绝不显示空白。
     */
    private void initVersionBadge() {
        TextView tv = findViewById(R.id.text_version);
        if (tv == null) return;      // 老布局兜底，不因为少个控件崩掉
        String name = BuildConfig.VERSION_NAME;
        int code = BuildConfig.VERSION_CODE;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.trim().isEmpty()) name = pi.versionName;
            code = pi.versionCode;
        } catch (Exception ignored) {
            // 用编译期常量兜底
        }
        final String vName = name;
        final int vCode = code;
        tv.setText("v" + vName);
        tv.setOnClickListener(v -> showAbout(vName, vCode));
    }

    /**
     * 顶栏「自动 / 白天 / 夜间」胶囊。
     *
     * <p>两套布局都有这个 id（车机在右卡顶栏、手机在工具行），但用
     * {@code findViewById == null} 兜底：以后若某个限定符布局里不需要它，
     * 也不会因为少个控件就崩。切完直接 recreate —— 遍历 View 树逐个重刷颜色
     * 既容易漏，也会和 ListView 的复用撞车；服务常驻，播放不会因此中断。
     */
    private void initThemeButton() {
        final TextView btn = findViewById(R.id.btn_theme);
        if (btn == null) return;
        int m = ThemeMode.get(this);
        btn.setText(ThemeMode.shortLabel(m));
        btn.setContentDescription("外观模式：" + ThemeMode.label(m) + "，点击切换");
        btn.setOnClickListener(v -> {
            int next = ThemeMode.next(ThemeMode.get(this));
            ThemeMode.set(this, next);
            Toast.makeText(this, "外观：" + ThemeMode.label(next), Toast.LENGTH_SHORT).show();
            recreate();
        });
    }

    /**
     * 「关于」对话框：版本 / 版本码 / 渠道 / 包名 / 外观 / 更新地址。
     *
     * <p>更新地址直接明文列出来是刻意的：万一车机上没浏览器、或者打不开，
     * 用户能照着这行字用手机打开，不用再问「到底是哪个链接」。
     */
    private void showAbout(String name, int code) {
        boolean carlink = "carlink".equals(BuildConfig.UPDATE_CHANNEL);
        String msg = "版本：v" + name + "（versionCode " + code + "）\n"
                + "渠道：" + (carlink ? "Carlink 自用版" : "正式版") + "\n"
                + "包名：" + getPackageName() + "\n"
                + "外观：" + ThemeMode.label(ThemeMode.get(this)) + "\n\n"
                + "更新地址：\n" + UpdateChecker.RELEASES_PAGE;
        new AlertDialog.Builder(this)
                .setTitle("星愿 FM · 关于")
                .setMessage(msg)
                .setPositiveButton("检查更新", (d, w) -> new UpdateChecker(this).checkAndPrompt())
                .setNeutralButton("打开下载页", (d, w) -> new UpdateChecker(this).openReleasePage())
                .setNegativeButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------ 诊断

    private void showDiagnostics() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView head = new TextView(this);
        head.setText("媒体源自检\n"
                + "· 前台服务：" + (RadioService.isRunning() ? "运行中" : "未运行") + "\n"
                + "· 播放器：" + (player == null ? "未绑定" : "已就绪") + "\n"
                + "· 当前电台：" + (player == null || player.current() == null
                        ? "—" : player.current().title) + "\n"
                + "· 音质档位：" + (player == null ? "—" : player.currentSourceLabel()) + "\n"
                + "· 队列：" + (player == null ? 0 : player.queue().size()) + " 台"
                + "　下标 " + (player == null ? -1 : player.currentIndex()) + "\n"
                + "· 收藏：" + FavoriteStore.count(this) + " 个\n"
                + "· 上次记录：" + (restore == null || restore.title.isEmpty()
                        ? "—" : restore.title) + "\n"
                + "· 系统版本：Android " + Build.VERSION.RELEASE
                + "（API " + Build.VERSION.SDK_INT + "）\n"
                + "· 机型：" + Build.MODEL + "\n\n"
                + "按键是否到达，看下面日志里有没有 [按键] 开头的行：\n"
                + "有 → 车机已派发，问题在处理逻辑；\n"
                + "没有 → 车机没把本应用当媒体源（去车机设置选默认音乐应用）。");
        head.setTextSize(15);
        head.setTextColor(getResources().getColor(R.color.text_secondary));
        head.setLineSpacing(6, 1f);
        box.addView(head);

        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(getResources().getColor(R.color.log_info));
        tv.setLineSpacing(4, 1f);
        StringBuilder sb = new StringBuilder();
        List<AppLog.Line> lines = AppLog.snapshot();
        int from = Math.max(0, lines.size() - 120);
        for (int i = from; i < lines.size(); i++) sb.append(lines.get(i).format()).append('\n');
        tv.setText(sb.length() == 0 ? "（暂无日志，点一下方向盘按键试试）" : sb.toString());
        box.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        new AlertDialog.Builder(this)
                .setTitle("诊断 · 运行日志")
                .setView(box)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", (d, w) -> AppLog.clear())
                .show();
    }

    /**
     * 高亮「当前正在播放」的那一行，并在它跑出屏幕时把列表滚过来。
     *
     * <p>两个坑（都是实车反馈过的）：
     * <ol>
     *   <li><b>不能拿队列下标当行号</b>：队列里包含被隐藏的台，屏幕列表不含。
     *       隐藏一个台之后，队列下标 7 可能在第 5 行，硬套就会高亮错行、
     *       甚至高亮到列表外面去（看不到任何高亮）。统一走
     *       {@link QueueRow#rowOfQueueIndex} 按 contentId 换算。</li>
     *   <li><b>调了 notifyDataSetChanged 会打断 ListView 的滚动动画</b>：
     *       以前先 setSelection 再 notifyDataSetChanged，而 setSelection 的
     *       内部「等布局完成后滚动」也是通过 post 排进消息队列的——
     *       notifyDataSetChanged 一旦触发重新布局，滚动就被吞了。
     *       所以顺序必须是「先改数据 → 再定位」，并且这一帧内只滚动一次。</li>
     * </ol>
     */
    private void highlight(int queueIndex) {
        if (list == null) return;
        currentIndex = queueIndex;

        RadioCnApi.Station[] vis = visibleStations();
        // 按「正在播的那台的 contentId」找行，而不是拿队列下标当行号：
        // 隐藏台不在列表里，收藏视图的行序也和省份队列不同，下标对齐必然错位。
        RadioCnApi.Station playing = player == null ? null : player.current();
        int row = playing == null ? -1 : QueueRow.rowOfStation(playing.contentId, vis);
        if (row >= 0) {
            list.setItemChecked(row, true);
        } else {
            list.clearChoices();
        }
        if (list.getAdapter() != null) ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        // 数据刷新完再滚，否则滚动动作会被紧随其后的重新布局吞掉
        if (row >= 0) scrollListToRow(row);
    }

    /**
     * 只在目标行看不见时滚动，并把它放在靠上位置，而不是贴边。
     *
     * <p>滚动是<b>平滑</b>的（{@link #smoothScrollTask}），不是 {@code setSelection} 的一步跳。
     * 也不用「攒几次再滚」的去抖：连续切台时每次都在上一段动画的当前位置继续滑，
     * 画面是跟着手连续翻页的过程；攒批反而会一顿一跳。
     */
    private void scrollListToRow(int row) {
        if (list == null || row < 0) return;
        int first = list.getFirstVisiblePosition();
        int count = list.getChildCount();
        // 判据抽在 QueueRow 里：Robolectric 的平滑滚动是空实现，只有这一层能单测
        int target = QueueRow.pendingTargetFor(row, first, count);
        if (target < 0) return;                 // 已经看得见，别拽
        AppLog.i("列表", "光标移到第 " + (row + 1) + " 行（可见 "
                + first + "~" + (first + count - 1) + "），列表滚到第 " + (target + 1) + " 行");
        if (count <= 0) {                       // 首帧/切视图后还没测量：直接定位
            list.setSelection(target);
            return;
        }
        pendingScrollRow = target;
        // 同一帧内多次切台只保留最后一次；放到下一帧是为了避开
        // notifyDataSetChanged 触发的重新布局（同步发起的滚动会被那次布局吞掉）
        list.removeCallbacks(smoothScrollTask);
        list.removeCallbacks(settleScrollTask);
        list.post(smoothScrollTask);
    }

    // ------------------------------------------------------------ 手势

    private SwipeStationHelper swipe;

    /**
     * 先把事件正常分发给子控件（按钮点击、列表滚动都不受影响），
     * 再把同一个事件喂给滑动识别器旁听 —— 这是「按钮能点、空白区能滑」的关键。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        View leftPanel = findViewById(R.id.panel_left);
        // 按下点命中的最深一层控件。注意这里给的是「最深的那个 View」而不是
        // 「最深的可点击控件」：手指落在列表行的文字上时，命中的是行内 TextView，
        // 它本身不 clickable，但它的祖先里有 ListView —— 识别器会沿祖先链判断，
        // 从而知道这次手势是在翻列表，不该换台。
        //
        // 命中判定必须扫**整块内容区**，不能只在左面板里找（v1.9.11 修）：
        // 电台列表在右面板，只在左面板里找的话，摸到列表也只会返回左面板本身，
        // 于是「在列表里滑动」被当成「在空白区滑动」—— 慢速滑列表就会切台。
        View touched = null;
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelPendingScroll();      // 用户上手了，程序发起的滚动让位
            View root = findViewById(android.R.id.content);
            View probe = root != null ? root : leftPanel;
            if (probe != null) touched = findViewAt(probe, ev.getX(), ev.getY());
        }
        if (swipe != null) swipe.observe(ev, leftPanel, touched);
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 递归找按下点下最深的 View（坐标用屏幕绝对位置，避免层级换算出错）。
     *
     * <p>与旧版 {@code findControlAt} 的差别：不再在遇到非 clickable 的控件时就放弃，
     * 而是一路找到最深处。滑动识别器需要真实命中点才能沿祖先链判断
     * 「这次手势在不在列表里」，返回 null 会让它以为点在空白区。
     *
     * <p><b>落在 root 之外一律返回 null</b>（v1.9.11）。旧实现会兜底返回 root 本身，
     * 于是「点在左面板外面」也拿到左面板，识别器据此认为起点不在列表、且落在可滑动区，
     * 滑右边的电台列表就会切台。现在外面就是外面，识别器会当成空白区处理。
     */
    private View findViewAt(View root, float screenX, float screenY) {
        if (root == null) return null;
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        boolean inRoot = screenX >= loc[0] && screenX <= loc[0] + root.getWidth()
                && screenY >= loc[1] && screenY <= loc[1] + root.getHeight();
        if (!inRoot) return null;
        if (!(root instanceof ViewGroup)) return root;
        ViewGroup g = (ViewGroup) root;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View child = g.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.getLocationOnScreen(loc);
            if (screenX < loc[0] || screenX > loc[0] + child.getWidth()
                    || screenY < loc[1] || screenY > loc[1] + child.getHeight()) continue;
            View deeper = findViewAt(child, screenX, screenY);
            return deeper != null ? deeper : child;
        }
        return g;
    }

    // ------------------------------------------------------------ 按键

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ---- 星愿车机方向盘私有键码（346 上一台 / 347 下一台 / 348 播放暂停）----
        //
        // 这一段必须排在标准媒体键之前，而且**不能**依赖 MediaSession。
        //
        // 起因：线上反馈方向盘按键「完全没反应」，查下来 MediaSession 那套链路是对的，
        // 但车机根本不发标准媒体键（85/87/88），发的是 CarInputService 注入的
        // 框架未定义的私有码。框架不认识的 KeyEvent 不会被全局拦截转给 MediaSession，
        // 只会派给当前焦点窗口的 onKeyDown。
        //
        // repeatCount 必须判 0：长按会连续产生 repeat>0 的事件，
        // 不判断的话按住两秒能切掉十几台。
        if (CarKeyCodes.isSteeringWheelKey(keyCode)) {
            // 通话/响铃期间整段跳过（v1.9.6）：把事件交回系统，让车机把它
            // 派给通话界面（下一曲接听、上一曲挂断）。这里既不能执行动作，
            // 也不能继续 return true 吞掉 —— 吞掉就等于电话接不起来。
            if (CallGuard.shouldYieldKeys(this)) {
                AppLog.key("按键", "通话中 → 方向盘私有键 " + keyCode + " 不拦截"
                        + "（" + CallGuard.describe() + "）");
                return super.onKeyDown(keyCode, event);
            }
            CarKeyCodes.Action act = CarKeyCodes.actionFor(keyCode);
            // 媒体中心已经接管派发时，这里必须只吞事件、不执行动作。
            //
            // 实测（v1.9.2，2026-10-26）：App 在前台按一次「下一台」，
            // 本方法收到私有键码 347 执行了一次切台，233ms 后媒体中心又把
            // onNext 派给我们注册的 MusicClient，又切一次 —— 一次按键跳两台。
            // 原因是媒体中心「焦点窗口和媒体源各派一份」，两条路径都活着。
            //
            // 反过来，媒体中心没接管时（非星愿车机、注册还没成功、服务刚被系统重建），
            // 这条分支就是唯一的保障，绝不能因为上面那个 bug 就把它删掉。
            boolean byMediaCenter = EasBridge.isActive();
            AppLog.key("按键", "方向盘私有键 keyCode=" + keyCode
                    + " → " + act + " repeat=" + event.getRepeatCount()
                    + (byMediaCenter ? "（媒体中心已接管，此处仅吞事件）" : "（前台处理）"));
            if (!byMediaCenter && event.getRepeatCount() == 0 && act != null && player != null) {
                switch (act) {
                    case TOGGLE: player.toggle(); break;
                    case PREV:   player.prev();   break;
                    case NEXT:   player.next();   break;
                }
            }
            // 无论 repeat 与否都吞掉：私有码外面没人认识，交给 super 只会变成系统提示音
            return true;
        }

        // 通话/响铃期间标准媒体键也要放行（v1.9.6）：蓝牙耳机/部分车机发的是
        // KEYCODE_MEDIA_NEXT/PREVIOUS，通话应用正是靠它们接听/挂断。
        if (isMediaKey(keyCode) && CallGuard.shouldYieldKeys(this)) {
            AppLog.key("按键", "通话中 → 媒体键 " + keyCode + " 不拦截");
            return super.onKeyDown(keyCode, event);
        }

        boolean handled;
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (player != null) player.next();
                handled = true;
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (player != null) player.prev();
                handled = true;
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (player != null) player.toggle();
                handled = true;
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (player != null) player.resume();
                handled = true;
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null) player.pause();
                handled = true;
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (player != null) player.stop();
                handled = true;
                break;
            default:
                handled = false;
        }
        if (handled) {
            AppLog.key("按键", "界面 onKeyDown keyCode=" + keyCode);
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9
                && event.getRepeatCount() == 0) {
            int idx = keyCode - KeyEvent.KEYCODE_1;
            if (idx < stations.length && player != null) player.playAt(idx);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_0 && event.getRepeatCount() == 0
                && stations.length > 9 && player != null) {
            player.playAt(9);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 方向盘私有键码的按下事件也要吞掉。
     *
     * <p>只重写 {@code onKeyDown} 是不够的：一次物理按键会先发 ACTION_DOWN 再发 ACTION_UP，
     * DOWN 被我们 return true 消费了，但 UP 如果没人处理，会继续走
     * {@code onKeyUp → Activity.dispatchKeyEvent → ...} 交给系统。
     * 框架不认识这几个码，行为未定义 —— 实测里可能变成一声系统提示音或焦点乱跳。
     *
     * <p>这里什么都不做，只返回 true 表示「已处理，别再往上传」。
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (CarKeyCodes.isSteeringWheelKey(keyCode)) {
            // 通话中 DOWN 已经放行给系统了，UP 也必须一起放行：
            // 一只键的 DOWN 交给系统、UP 被我们吞掉，通话界面看到的就是「按下没松开」。
            if (CallGuard.shouldYieldKeys(this)) {
                return super.onKeyUp(keyCode, event);
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** 本界面会接手的标准媒体键（通话期间这几个必须让路）。 */
    private static boolean isMediaKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000) {
            persist(player == null ? null : player.current(), currentIndex,
                    player != null && player.isPlaying());
            // 注意：不释放播放器、不停服务 —— 方向盘与车机媒体条目要继续可用
            super.onBackPressed();
        } else {
            lastBackAt = now;
            tvStatus.setText("再按一次返回键退出界面（后台继续播）");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        persist(player == null ? null : player.current(), currentIndex,
                player != null && player.isPlaying());
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        // 关键：只解绑界面监听，绝不 release 播放器（它归前台服务所有）
        if (player != null) player.setListener(null);
        player = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------ 列表

    /** 当前列表显示的是收藏还是全部。 */
    private boolean showingFavorites = false;
    /** 收藏视图下实际展示的电台（由 FavoriteStore 转成 Station，带缓存地址就自动可用）。 */
    private RadioCnApi.Station[] favStations = new RadioCnApi.Station[0];

    /** 当前列表在「正在播放的队列」里对应的下标；-1 表示这台不在队列里。 */
    private int visiblePosToQueuePos(int pos) {
        RadioCnApi.Station[] vis = visibleStations();
        if (pos < 0 || pos >= vis.length || player == null) return -1;
        String id = vis[pos].contentId;
        List<RadioCnApi.Station> q = player.queue();
        for (int i = 0; i < q.size(); i++) {
            if (q.get(i).contentId != null && q.get(i).contentId.equals(id)) return i;
        }
        return -1;
    }

    private RadioCnApi.Station[] visibleStations() {
        return showingFavorites ? favStations : stations;
    }

    /** 当前视图中，正在播放的那台在第几行；不在视图里返回 -1。 */
    private int currentRowInView() {
        RadioCnApi.Station[] vis = visibleStations();
        String cur = player == null || player.current() == null ? null : player.current().contentId;
        return QueueRow.rowOfStation(cur, vis);
    }

    /**
     * 点列表某一行 → 播放。
     *
     * <p>两个要点：
     * ① 在「全部」里点，直接 playAt（队列就是全部电台，方向盘切台正常）；
     * ② 在「收藏」里点，先把队列换成收藏列表，再 playAt。
     *    这样方向盘上一台/下一台在收藏视图下也沿收藏顺序走，符合直觉。
     *
     * <p><b>为什么收藏这条路不能直接用 setQueue</b>：
     * {@code setQueue} 是「重置」语义——它会先 {@code index = -1} 再内部 playAt，
     * 走的是「没有当前台」的冷启动分支。而按钮切台走的是 {@code playAt}，
     * 两条路在播放器里不是同一条。v1.6.3 起收藏点选改成：
     * 先用 {@link RadioPlayer#rebindQueue} 只换队列不动流，
     * 再显式 {@code playAt}——与按钮完全同一条路径，声音切换行为一致。
     */
    private void playFromList(int pos) {
        if (player == null) return;
        RadioCnApi.Station[] vis = visibleStations();
        if (pos < 0 || pos >= vis.length) return;

        if (showingFavorites) {
            List<RadioCnApi.Station> q = new ArrayList<>();
            for (RadioCnApi.Station s : vis) {
                // 收藏里如果某个台的地址已被缓存，直接复用，弱网也能秒开
                q.add(withCachedUrl(s));
            }
            // 只换队列（不动正在播的流），然后像按钮一样显式切到目标行
            player.rebindQueue(q, pos, true);
            player.playAt(pos);
            currentIndex = pos;
        } else {
            int qp = visiblePosToQueuePos(pos);
            if (qp >= 0) player.playAt(qp);
        }
        highlight(pos);
    }

    /** 收藏里存的是「快照」，播放前把本地缓存的地址补回去。 */
    private RadioCnApi.Station withCachedUrl(RadioCnApi.Station s) {
        if (s.candidates().isEmpty()) {
            StreamCache.Entry e = StreamCache.get(this, s.contentId);
            if (e != null && e.urls != null && !e.urls.isEmpty()) {
                // 缓存里的顺序就是音质优先的顺序，第一项是最优档，补到 high 字段
                s.mp3PlayUrlHigh = e.urls.get(0);
            }
        }
        return s;
    }

    /** 切换「全部 / 收藏」。切视图绝不动当前播放，只换展示。 */
    private void setView(boolean favorites) {
        if (showingFavorites == favorites) return;
        showingFavorites = favorites;
        // 切回「全部」时，把播放器队列从「收藏子集」换回全部电台。
        // 收藏视图下点选会用 rebindQueue 把 player.queue 换成收藏列表，
        // 切回后若不换回来，在全部列表点未收藏台会在收藏队列里找不到 → 点击无效。
        if (!favorites) syncQueueToAllStations();
        refreshList();
        // 记住当前标签，下次打开回到同一个视图
        if (player != null) persist(player.current(), currentIndex,
                player.isPlayWhenReady());
    }

    /** 重算收藏快照 + 刷新标签样式与列表。 */
    private void refreshFavorites() {
        List<FavoriteStore.Item> items = FavoriteStore.list(this);
        List<RadioCnApi.Station> out = new ArrayList<>();
        for (FavoriteStore.Item it : items) {
            // 优先用「全部」列表里的同一个对象：带实时地址和省份名，副标题更完整
            int idx = indexOf(it.contentId);
            out.add(idx >= 0 ? stations[idx] : it.toStation());
        }
        favStations = out.toArray(new RadioCnApi.Station[0]);

        TextView tabAll = findViewById(R.id.tab_all);
        TextView tabFav = findViewById(R.id.tab_fav);
        int n = favStations.length;
        // v1.8.4 起不再用 ★ 字符：收藏统一用矢量心形（播放条上的按钮），
        // 标签这里只保留文字 + 数量，避免同一个界面里出现「心形 + 星形」两套符号。
        if (tabFav != null) tabFav.setText(n > 0 ? "我的收藏 (" + n + ")" : "我的收藏");
        if (tabAll != null) {
            tabAll.setBackgroundResource(showingFavorites ? R.drawable.tab_off : R.drawable.tab_on);
            // v2.0：选中标签是橙底，文字必须是白色（原深色主题下 text_primary 恰好是白，
            // 换浅色后它变成近黑，白字需求改用语义化的 icon_on_accent）
            tabAll.setTextColor(getResources().getColor(
                    showingFavorites ? R.color.text_secondary : R.color.icon_on_accent));
        }
        if (tabFav != null) {
            tabFav.setBackgroundResource(showingFavorites ? R.drawable.tab_on : R.drawable.tab_off);
            tabFav.setTextColor(getResources().getColor(
                    showingFavorites ? R.color.icon_on_accent : R.color.text_secondary));
        }
    }

    /** 刷新标签 + 列表（切视图、切台后都走这里），默认允许自动滚动跟随当前台。 */
    private void refreshList() {
        refreshList(true);
    }

    /**
     * 最近一次 {@link #refreshList(boolean)} 收到的 followPlaying。
     *
     * <p>存在的唯一理由是<b>可观测</b>：Robolectric 不驱动平滑滚动动画，
     * 「列表有没有被拽走」这个后果在 JVM 里不可靠地复现，
     * 只能退一步验证「有没有要求它跟过去」这个意图。见 {@code FavoriteKeepsScrollTest}。
     */
    boolean lastRefreshFollowedPlaying = true;

    /**
     * @param followPlaying 是否把列表滚到「正在播放的那台」。
     *
     * <p>这个参数是为一个真实 bug 加的：点某行的 ☆ 收藏后，列表会自动跳到正在播放的台，
     * 用户正在浏览的位置就丢了。原因就是本方法原来无条件调
     * {@link #scrollListToRow(int)} —— 而它本来是给「切台后让列表跟过去」用的。
     *
     * <p>两条规则：
     * <ul>
     *   <li><b>用户主动改变了列表内容</b>（收藏/取消收藏、隐藏）→ 不滚动。
     *       用户的眼睛盯着自己刚点的那一行，把列表拽走是最讨厌的行为。</li>
     *   <li><b>正在播放的台变了 / 换了视图</b> → 滚动。这时候不跟过去，用户就找不到在播的是哪台。</li>
     * </ul>
     */
    private void refreshList(boolean followPlaying) {
        lastRefreshFollowedPlaying = followPlaying;
        refreshFavorites();
        if (list == null) return;
        if (list.getAdapter() == null) {
            list.setAdapter(new StationAdapter());
        } else {
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        }
        int row = currentRowInView();
        if (row >= 0) {
            list.setItemChecked(row, true);
            // 切视图后当前台可能不在可见区（例如收藏刚加进列表末尾），要跟着滚过去
            if (followPlaying) scrollListToRow(row);
        }
        if (showingFavorites && favStations.length == 0) {
            tvStatus.setText("还没有收藏，点列表右侧的 ☆ 添加");
        }
    }

    /** 点某行的 ★：收藏/取消，并即时刷新星标和收藏计数。 */
    private void toggleFavorite(int pos) {
        RadioCnApi.Station[] vis = visibleStations();
        if (pos < 0 || pos >= vis.length) return;
        RadioCnApi.Station s = vis[pos];
        boolean nowFav = FavoriteStore.toggle(this, s);
        if (nowFav) Toast.makeText(this, "已加入收藏：" + s.title, Toast.LENGTH_SHORT).show();
        else Toast.makeText(this, "已取消收藏：" + s.title, Toast.LENGTH_SHORT).show();

        // 记住用户当时看的是哪几行，刷新后原样恢复。
        // 只"不主动滚动"还不够：收藏视图里取消收藏会让这一行从列表消失，
        // 后面所有行整体上移，用户看到的内容还是变了 —— 所以要显式还原顶部行。
        int first = list == null ? -1 : list.getFirstVisiblePosition();
        int top = -1;
        if (list != null && list.getChildCount() > 0) {
            top = list.getChildAt(0).getTop();
        }

        // 收藏的可能正是正在播的那台：播放卡上的星要同步
        refreshPlayingFavorite();
        refreshList(false);

        if (list != null && first >= 0) {
            int anchor = Math.min(first, Math.max(0, list.getCount() - 1));
            list.setSelectionFromTop(anchor, top);
        }
        // 收藏视图里取消收藏会让条目消失，此时把焦点放回播放卡，避免列表突然变空后无处可点
        if (showingFavorites && !nowFav && btnPlay != null) btnPlay.requestFocus();
    }

    /**
     * 隐藏一个台：从列表与播放队列里移除，方向盘不会再切到它。
     *
     * <p>如果隐藏的正好是正在播的那台怎么办：不打断播放、也不偷偷切台 ——
     * 只是它不再出现在列表里（左侧仍显示它在播）。这样用户不会觉得"点了隐藏音乐就没了"。
     */
    private void hideStation(int pos) {
        RadioCnApi.Station[] vis = visibleStations();
        if (pos < 0 || pos >= vis.length) return;
        RadioCnApi.Station s = vis[pos];
        HiddenStore.hide(this, s);
        Toast.makeText(this, "已隐藏：" + s.title + "（可在「隐藏管理」恢复）",
                Toast.LENGTH_SHORT).show();

        if (showingFavorites) {
            // 收藏视图里隐藏：只刷新收藏快照即可，不动主列表。
            // 同样不滚动 —— 这是用户主动改列表，不该把视线拽到正在播的台。
            refreshList(false);
        } else {
            // 全部视图：从 stations 里摘掉，并同步播放队列
            List<RadioCnApi.Station> keep = new ArrayList<>();
            for (RadioCnApi.Station x : stations) {
                if (!x.contentId.equals(s.contentId)) keep.add(x);
            }
            stations = keep.toArray(new RadioCnApi.Station[0]);
            syncQueueAfterHide();
            // 隐藏的那一行消失了，后面的行会上移；不滚动也会看到内容变化，
            // 但至少不要把列表一下子拽到几十行之外的正在播台。
            refreshList(false);
        }
    }

    /**
     * 隐藏后把播放队列同步成新的可见列表。
     * 正在播的台若被隐藏了，则保留在队列里（不打断），只是列表不再显示它。
     */
    private void syncQueueAfterHide() {
        syncQueueToAllStations();
    }

    /**
     * 把播放器队列重建为「全部电台」列表 {@link #stations}，并让下标指向当前台。
     *
     * <p>两条触发路径：
     * <ul>
     *   <li><b>隐藏台之后</b>（{@link #syncQueueAfterHide}）：stations 变了，队列要跟着变。</li>
     *   <li><b>从收藏切回「全部」视图</b>（{@link #setView}）：收藏视图下点选会把
     *       {@code player.queue} 换成收藏子集（{@code playFromList → rebindQueue}），
     *       切回全部后若不把队列换回来，在「全部」列表点未收藏台时
     *       {@code visiblePosToQueuePos} 在收藏子集里找不到该台 → 点击无效。</li>
     * </ul>
     *
     * <p>当前台若不在 {@code stations} 里（跨省收藏、数据已刷新掉），
     * 补在队首占位，保证 {@code current()} 不返回 null、播放不中断。
     */
    private void syncQueueToAllStations() {
        if (player == null) return;
        String curId = player.current() == null ? null : player.current().contentId;
        List<RadioCnApi.Station> q = new ArrayList<>();
        for (RadioCnApi.Station x : stations) q.add(x);
        // 当前台不在可见列表里 → 补在队首占位，保证 current() 不返回 null
        if (curId != null && indexOf(curId) < 0 && player.current() != null) {
            q.add(0, player.current());
        }
        int newIdx = 0;
        if (curId != null) {
            for (int i = 0; i < q.size(); i++) {
                if (curId.equals(q.get(i).contentId)) {
                    newIdx = i;
                    break;
                }
            }
        }
        player.rebindQueue(q, newIdx, player.isPlayWhenReady());
    }

    /** 「隐藏管理」：列出已隐藏的台，点一下即恢复显示。 */
    private void showHiddenManager() {
        final List<HiddenStore.Item> items = HiddenStore.list(this);
        if (items.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("隐藏管理")
                    .setMessage("还没有隐藏任何电台。\n\n在列表里点电台右侧的眼睛图标即可隐藏。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        View body = buildHiddenListView(items);
        new AlertDialog.Builder(this)
                .setTitle("隐藏管理（点即恢复显示）")
                .setView(body)
                .setPositiveButton("完成", (d, w) -> {
                    // 恢复后重建列表与队列
                    loadStations();
                })
                .setNegativeButton("全部恢复", (d, w) -> {
                    HiddenStore.clear(this);
                    AppLog.i("隐藏", "已恢复全部隐藏电台");
                    loadStations();
                })
                .show();
    }

    private View buildHiddenListView(final List<HiddenStore.Item> items) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        for (final HiddenStore.Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, pad / 2, 0, pad / 2);

            TextView name = new TextView(this);
            name.setText(it.title.length() == 0 ? it.contentId : it.title);
            name.setTextSize(17);
            name.setTextColor(getResources().getColor(R.color.text_primary));
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView btn = new TextView(this);
            btn.setText("恢复");
            btn.setTextSize(16);
            btn.setTextColor(getResources().getColor(R.color.accent_deep));
            btn.setPadding(pad, pad / 2, pad / 2, pad / 2);
            btn.setOnClickListener(v -> {
                HiddenStore.unhide(this, it.contentId);
                btn.setText("已恢复");
                btn.setTextColor(getResources().getColor(R.color.text_secondary));
                btn.setEnabled(false);
                name.setTextColor(getResources().getColor(R.color.text_secondary));
                AppLog.i("隐藏", "恢复显示：" + it.title);
            });

            row.addView(name);
            row.addView(btn);
            sc.addView(row);
        }
        box.addView(sc);
        return box;
    }

    private final class StationAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visibleStations().length;
        }

        @Override
        public Object getItem(int i) {
            return visibleStations()[i];
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView == null) {
                row = (LinearLayout) getLayoutInflater().inflate(R.layout.item_station, parent, false);
                applyRowHeight(row);
            } else {
                row = (LinearLayout) convertView;
            }
            RadioCnApi.Station s = visibleStations()[i];
            TextView no = row.findViewById(R.id.item_no);
            TextView title = row.findViewById(R.id.item_title);
            TextView sub = row.findViewById(R.id.item_sub);
            // v1.8.4：item_star 由 TextView 改为 ImageView（矢量心形）
            android.widget.ImageView star = row.findViewById(R.id.item_star);

            // v2.2：封面优先显示真实台标图，无图/未下载完回落到台名首字。
            // ListView 复用行：每次先清掉图片、亮出首字，再异步加载；
            // 回调时比对 contentId，行已被复用就丢弃，防止串图。
            android.widget.ImageView cover = row.findViewById(R.id.item_logo);
            if (cover != null) {
                cover.setImageDrawable(null);
                cover.setVisibility(View.GONE);
                cover.setTag(R.id.item_logo, s.contentId);
            }
            no.setVisibility(View.VISIBLE);
            no.setText(CoverInitial.of(s.title));
            // 台名 +（频率），频率字号小两号。见 StationTitle 的注释。
            // 基准必须读 item_title 的实际字号：手机版行内是 16sp、车机是 24sp，
            // 写死 TITLE_SIZE_SP 会让频率（24−2=22sp）比 16sp 的台名还大。
            title.setText(StationTitle.plain(s.title));
            sub.setText(subOf(s));

            boolean sel = player != null && player.current() != null
                    && player.current().contentId != null
                    && player.current().contentId.equals(s.contentId);
            row.setBackgroundResource(sel ? R.drawable.item_bg_selected : R.drawable.item_bg);
            // v2.0：正在播的那一行左侧亮起 3dp 橙色指针（item_mark）。
            // 用 INVISIBLE 而不是 GONE —— 两者都不可见，但 GONE 不占位，
            // 切台时整行文字会横向抖 3dp，扫视时很扎眼。
            View mark = row.findViewById(R.id.item_mark);
            if (mark != null) mark.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            title.setTextColor(getResources().getColor(
                    sel ? R.color.text_primary : R.color.item_text));
            // 浅橙底上用 accent 当小字只有 3:1，压深一档；未选中仍是弱灰。
            // 首字封面：未选中是中性灰底，选中跟整行一起变浅橙。
            // 背景不能只在 inflate 时设一次 —— ListView 会复用行，
            // 上一次是选中行的 convertView 滚到未选中位置必须换回来。
            no.setBackgroundResource(sel
                    ? R.drawable.cover_chip_selected : R.drawable.cover_chip);
            no.setTextColor(getResources().getColor(
                    sel ? R.color.accent_deep : R.color.cover_text));
            sub.setTextColor(getResources().getColor(
                    sel ? R.color.accent_deep : R.color.item_sub_text));

            // v2.2：异步加载真实台标（内存/磁盘/网络三级，与大台标共用缓存），
            // 成功则用图盖住首字。回调里通过 cover 的 parent 定位同行的首字 TextView，
            // 不捕获 no 局部变量——行被复用后 no 可能已指向别的行。
            if (cover != null) {
                LogoLoader.load(MainActivity.this, s.contentId, s.image, cover,
                        (view, cid, bitmap) -> {
                            Object tag = view.getTag(R.id.item_logo);
                            if (tag == null || !tag.equals(cid)) return; // 已复用
                            if (bitmap == null) return; // 无图，保留首字
                            View box = (View) view.getParent();
                            TextView noV = box == null ? null
                                    : box.findViewById(R.id.item_no);
                            if (noV != null) noV.setVisibility(View.GONE);
                            view.setImageBitmap(bitmap);
                            view.setVisibility(View.VISIBLE);
                        });
            }

            boolean fav = FavoriteStore.contains(MainActivity.this, s.contentId);
            FavIcon.bind(star, fav);
            final int pos = i;
            star.setOnClickListener(v -> toggleFavorite(pos));

            // 隐藏：把该台从列表里移除（可在「隐藏管理」恢复）。
            // v2.0 起是 ImageView（矢量 ic_hide），不再是字符「⊘」——
            // 着色已由布局里的 android:tint 决定，这里不再设文字色。
            android.widget.ImageView hide = row.findViewById(R.id.item_hide);
            hide.setOnClickListener(v -> hideStation(pos));
            return row;
        }
    }

    private String subOf(RadioCnApi.Station s) {
        String sub = s.subtitle == null ? "" : s.subtitle.trim();
        String prov = s.provinceName == null ? "" : s.provinceName;
        if (sub.length() > 0) return prov.length() > 0 ? prov + " · " + sub : sub;
        return prov;
    }

    // ------------------------------------------------------------ 手机适配

    /**
     * 列表行高：手机版按屏幕短边收紧（56/62dp），车机版用布局文件里的 86dp。
     *
     * <p>ListView 复用 convertView，所以只在 inflate 时设一次即可。
     */
    private void applyRowHeight(View row) {
        if (!compactUi) return;
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) return;
        int px = PhoneLayout.rowHeightPx(this);
        if (lp.height != px) {
            lp.height = px;
            row.setLayoutParams(lp);
        }
    }

    /** 按当前配置判定版面，并把手机专属的尺寸落到视图上。 */
    private void applyScreenMode() {
        int[] wh = PhoneLayout.sizeDp(getResources().getConfiguration(),
                getResources().getDisplayMetrics());
        // 两栏能不能成立由布局文件决定（layout-h480dp-land/ vs layout/），
        // 这里只是把「是不是两栏」记下来，供行高与压缩逻辑判断；
        // 判据与目录限定符逐字对应（可用高度 ≥ 480dp 且横屏），见 PhoneLayout.useTwoColumn。
        boolean twoCol = PhoneLayout.useTwoColumn(wh[0], wh[1]);
        boolean compact = PhoneLayout.compactMetrics(wh[0], wh[1]);
        if (twoCol != twoColumn || compact != compactUi) {
            AppLog.i("界面", "版面 → " + (twoCol ? "两栏" : "单列")
                    + " / " + (compact ? "手机尺寸" : "原尺寸")
                    + "（" + wh[0] + "×" + wh[1] + "dp）");
            twoColumn = twoCol;
            compactUi = compact;
        }
        if (compact) {
            // 台标尺寸要按「哪套版面」报，不能一律报 logoSizeDp：
            // v1.8.3 起手机是底部迷你播放条，台标固定 44dp，
            // 报 110~160dp 会让看日志的人以为布局没适配。
            AppLog.i("界面", "手机适配：短边 " + Math.min(wh[0], wh[1]) + "dp，台标 "
                    + PhoneLayout.effectiveLogoSizeDp(this, findViewById(R.id.logo_wrap),
                    wh[0], wh[1]) + "dp，行高 "
                    + PhoneLayout.rowHeightDp(wh[0], wh[1]) + "dp");
        }
        PhoneLayout.apply(this, compact, findViewById(R.id.logo_wrap), tvLogo);
        // 手机屏窄，90dp 的滑动距离相当于整屏 1/4，滑起来会"很重"；降到 48dp
        if (swipe != null) {
            float slop = ViewConfiguration.get(this).getScaledTouchSlop();
            float density = getResources().getDisplayMetrics().density;
            swipe.setThresholdPx(compact
                    ? SwipeStationHelper.minSwipePxPhone(slop, density)
                    : SwipeStationHelper.minSwipePx(slop, density));
        }
        // 行高变了，已缓存的 convertView 也要跟着变
        if (list != null && list.getAdapter() != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                applyRowHeight(list.getChildAt(i));
                list.getChildAt(i).requestLayout();
            }
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        }
    }

    /**
     * 转屏 / 分屏 / 折叠屏展开 / <b>系统切夜间</b>：
     * configChanges 里声明了就不重建 Activity，但要自己把版面参数重算一遍，
     * 否则台标还停在旧尺寸。
     *
     * <p>v2.1 的关键分支：manifest 的 configChanges 含 {@code uiMode}，
     * 车机从白天切到夜间时系统<b>只回调这里、不重建</b>。已经 inflate 出来的
     * drawable 和 ColorStateList 不会自己换色，光放 values-night/ 是没用的。
     * 所以拿「本次 onCreate 生效的夜间态」和「新的系统 uiMode」比一次，
     * 不一样就 recreate；手动强制白天/夜间两档不受系统变化影响，直接跳过。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean nowNight = ThemeMode.effectiveNight(ThemeMode.get(this), newConfig.uiMode);
        if (nowNight != themeNightAtCreate) {
            themeNightAtCreate = nowNight;
            AppLog.i("界面", "系统夜间态变化 → " + (nowNight ? "夜间" : "白天") + "，重建界面换色板");
            recreate();
            return;
        }
        applyScreenMode();
        // 车机版的高度收缩逻辑依赖实测尺寸，转屏后重跑一次
        installAdaptiveLayout();
    }
}
