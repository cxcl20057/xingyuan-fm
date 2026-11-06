package com.rea.radio;

import android.app.PendingIntent;
import android.net.Uri;

import com.ecarx.eas.sdk.mediacenter.AbstractMusicPlaybackInfo;
import com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo;

/**
 * 给车机媒体中心看的「当前播放状态」快照。
 *
 * <h3>为什么要继承 {@code MusicPlaybackInfo} 而不是实现接口</h3>
 *
 * <p>SDK 里同一件事有两套类型：
 * <ul>
 *   <li>{@code IMusicPlaybackInfo}（接口，配 {@code IMusicClient} 用）；</li>
 *   <li>{@code MusicPlaybackInfo}（类，配 {@code MusicClient} 用）。</li>
 * </ul>
 *
 * <p>两边本该等价，但这份 SDK 里 {@code MediaCenterProxy.registerMusic(IMusicClient)}
 * 是个<b>空壳</b>：反编译出来只有 {@code aconst_null; areturn} 两句，
 * 永远返回 null、永远注册不上（实测日志就是「registerMusic 返回 null」）。
 * 能走通的只有类版 {@code registerMusic(MusicClient, RegisterRequest)}。
 *
 * <p>类版要求 {@code getMusicPlaybackInfo()} 返回 {@code MusicPlaybackInfo}。
 * 那个类的 34 个 getter <b>全部是硬编码 {@code return null / return 0}</b>
 * —— 它是框架反序列化用的空壳 DTO，第三方填不进值。所以这里继承它、
 * 把有用的 getter 逐个覆盖，靠 Java 的协变返回类型同时满足类版签名。
 *
 * <h3>字段取值上几个刻意的决定</h3>
 *
 * <ul>
 *   <li><b>{@code duration} 恒为 0</b>：直播没有总时长。填真实秒数会让媒体中心
 *       画出可拖动的进度条，用户一拖就触发 seek —— 对直播毫无意义。</li>
 *   <li><b>artwork 给「网络地址」，不给 {@code file://}</b>：台标存在本应用私有目录，
 *       媒体中心是另一个 uid，读不到 {@code file://}，也不在本应用的
 *       {@code grantUriPermissions} 白名单里。所以这里只透传接口给的
 *       {@code http(s)} 台标地址（酷我的 {@code getArtwork()} 同样是网络 Uri）；
 *       没有网络地址时返回 null，让媒体中心画占位块。</li>
 *   <li><b>{@code sourceType} 运行时决定</b>：先试 {@code SOURCE_TYPE_ONLINE}（6，
 *       酷我用的就是它），车机不收再回落 {@code SOURCE_TYPE_DAB}（11）。
 *       详见 {@link #TYPE_ONLINE} 的注释 —— 这一项直接关系到方向盘按键派给谁。</li>
 *   <li><b>台名同时填进 {@code radioStationName}</b>：车机部分界面（仪表、语音）
 *       只读电台专用字段，不读通用 title。</li>
 * </ul>
 */
final class EasPlaybackInfo extends MusicPlaybackInfo {

    /**
     * 媒体源类型：<b>在线音乐（6）</b>。
     *
     * <p>这个值决定了媒体中心把本应用归到哪一类音源，进而决定方向盘按键怎么派。
     * 逆向「星愿车控完全正常酷我音乐 6.0.0.9」得到的结论（见
     * {@code docs/酷我车机版逆向分析.md}）：酷我注册的正是
     * {@code updateMediaSourceTypeList(token, {6})} + {@code updateCurrentSourceType(token, 6)}，
     * 也就是 {@code SOURCE_TYPE_ONLINE}。
     *
     * <p><b>为什么不能再用 11（{@code SOURCE_TYPE_DAB}，数字广播）</b>：
     * 11 属于「收音机/数字广播」这一族。车机把这一族的方向盘「上一曲/下一曲」
     * 直接当<b>调台</b>处理（调谐动作在 MCU 侧完成，不经过 Android 应用），
     * 于是两个后果：
     * <ul>
     *   <li>按键在 MCU 层就被「调台」消费掉了，根本到不了通话界面 → 来电接不起来；</li>
     *   <li>用户听到的是原车收音机在换台（而不是本应用换台）。</li>
     * </ul>
     * 换成 6 之后，本应用就是普通的「在线媒体」源，通话期间按键交回电话应用。
     *
     * <p>老版本（v1.9.6 及以前）用 11，是因为当时观测到 12（{@code SOURCE_TYPE_ONLINE_RADIO}）
     * 不被车机接受、弹窗显示「当前暂无播放内容」，就退到了 11。但那次尝试漏了配套的
     * {@code updateMediaSourceTypeList} 声明 —— 类型不是「报上去就生效」的，
     * 必须先用 {@code updateMediaSourceTypeList} 声明这一族，再用
     * {@code updateCurrentSourceType} 指定当前值。酷我的调用顺序就是证据。
     *
     * <p><b>为什么写成全限定名：</b>{@code AbstractMusicPlaybackInfo} 内部嵌套了一个
     * 同名的 {@code @interface SourceType}（IntDef 注解），本类是它的子类，
     * 那个嵌套注解类型会进入作用域并<b>遮蔽</b>顶层的
     * {@code com.ecarx.eas.sdk.mediacenter.SourceType} —— 只写 {@code SourceType.XXX}
     * 会编译不过（提示「找不到符号，位置: @interface SourceType」）。
     */
    static final int TYPE_ONLINE =
            com.ecarx.eas.sdk.mediacenter.SourceType.SOURCE_TYPE_ONLINE;      // 6

    /** 数字广播。老版本用它，通话按键被当调台消费，仅作回落值保留。 */
    static final int TYPE_DAB =
            com.ecarx.eas.sdk.mediacenter.SourceType.SOURCE_TYPE_DAB;         // 11

    /** 当前生效的媒体源类型。<b>运行时决定</b>：注册时先试 6，车机不收就回落 11。 */
    private static volatile int sourceType = TYPE_ONLINE;

    static int sourceType() {
        return sourceType;
    }

    static void setSourceType(int t) {
        sourceType = t;
    }

    /**
     * 本应用向媒体中心登记的媒体列表 id。
     *
     * <p><b>这个值必须和 {@code updateMediaList()} 推上去的列表 id 一致</b>，
     * 媒体中心靠「当前播放项属于哪个列表 + 排第几位」（{@link #getPlayingMediaListId()}
     * + {@link #getPlayingItemPositionInQueue()}）来定位当前项。
     *
     * <p>曾经漏了这两个字段（基类空壳返回 {@code null} / {@code 0}），表现为：
     * 桌面小控件能显示台名，但点进「媒体源清单」页显示<b>「当前暂无播放内容」</b> ——
     * 因为服务只知道「有个东西在播」，不知道它在哪个列表里、是第几条。
     */
    static final String LIST_ID = "com.rea.radio.playlist";

    private final String title;
    private final String artist;
    private final String album;
    private final String packageName;
    private final String uuid;
    private final int status;
    private final int position;
    private final PendingIntent launch;

    /**
     * 台标地址（<b>网络</b> Url，可为 null）。
     *
     * <p>逆向酷我得到的一条关键差异：它的 {@code getArtwork()} 返回的是<b>网络 Uri</b>
     * （{@code Uri.parse(http://...)}），媒体中心自己下载来画；本应用早期返回 null，
     * 于是「点媒体条弹出的小窗」里那块图是空的。
     *
     * <p>不能给 {@code file://}：媒体中心是另一个 uid，读不到本应用私有目录，
     * 也不在 {@code grantUriPermissions} 白名单里，塞了反而显示成破图。
     */
    private final Uri artwork;

    /**
     * 「歌词」行 —— 电台没有歌词，这里塞<b>节目名</b>（后台接口里的
     * {@code 正在直播：xxx}）。酷我小窗的第二行显示的正是这个字段。
     */
    private final String lyric;

    /**
     * @param title       台名（界面主标题）
     * @param artist      副标题，这里放省份
     * @param album       固定为「FM 直播」
     * @param status      {@code PLAYBACK_STATUS_PLAYING} / {@code _PAUSED} / {@code _PREPARE}
     * @param position    当前台在队列里的下标
     * @param packageName 本应用包名。**必须取真实包名**，不能写死
     *                    {@code com.rea.radio} —— 调试包是 {@code com.rea.radio.debug}，
     *                    媒体中心会拿它去比对注册时登记的包名（实测写死时
     *                    媒体控制服务的日志里 pkgName 与真实进程对不上）。
     * @param uuid        台的内容 id，媒体中心用它判断「是不是同一个媒体」
     * @param artwork     台标网络地址，可为 null
     * @param lyric       小窗第二行（节目名），可为 null
     * @param launch      点击媒体卡片时拉起本应用
     */
    EasPlaybackInfo(String title, String artist, String album,
                    int status, int position, String packageName, String uuid,
                    Uri artwork, String lyric, PendingIntent launch) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.status = status;
        this.position = position;
        this.packageName = packageName;
        this.uuid = uuid;
        this.artwork = artwork;
        this.lyric = lyric;
        this.launch = launch;
    }

    // ------------------------------------------------------------ 标题与身份

    @Override public String getTitle() { return title; }

    @Override public String getArtist() { return artist; }

    @Override public String getAlbum() { return album; }

    /**
     * 电台专用字段。车机的部分媒体卡片只认这个，不认 {@link #getTitle()}。
     *
     * <p>频率字段留空：播的是网络流，没有物理频率。硬把台名里的「FM102.6」
     * 抠出来当频率反而会误导 —— 有的台名根本没有频率。
     */
    @Override public String getRadioStationName() { return title; }

    @Override public String getRadioFrequency() { return null; }

    /**
     * {@code RADIO_MODE_PLAYING} = 「正在播一个确定的台」。
     *
     * <p>另一个值是 {@code RADIO_MODE_CAROUSEL}（轮播/搜索中），语义是「台还没定」，
     * 填了媒体中心会显示成「搜索中」。
     */
    @Override public int getRadioMode() { return AbstractMusicPlaybackInfo.RADIO_MODE_PLAYING; }

    @Override public int getPlaybackStatus() { return status; }

    @Override public int getSourceType() { return sourceType; }

    @Override public String getAppName() { return "星愿收音机"; }

    @Override public String getPackageName() { return packageName; }

    @Override public String getUuid() { return uuid; }

    // ------------------------------------------------------------ 进度与循环

    @Override public long getDuration() { return 0L; }

    @Override public int getLoopMode() { return AbstractMusicPlaybackInfo.LOOP_MODE_ALL; }

    @Override public int getPlayingItemPositionInQueue() { return position; }

    // ------------------------------------------------------------ 列表归属
    //
    // 这三个是「媒体源清单页 / 桌面卡片」能不能认出当前项的关键。
    // 基类（MusicPlaybackInfo，空壳 DTO）默认返回 null / 0 / false，
    // 那会让媒体中心认为「本应用在播，但不属于任何列表」，于是：
    //   · 清单页 → 「当前暂无播放内容」
    //   · 卡片上不显示「第几项」

    /** 当前播的这一条属于 {@link #LIST_ID} 这个列表。 */
    @Override public String getPlayingMediaListId() { return LIST_ID; }

    /** 普通列表（非推荐 / 非场景 / 非会员）。 */
    @Override
    public int getPlayingMediaListType() {
        return AbstractMusicPlaybackInfo.TYPE_MEDIA_LIST_NORMAL;
    }

    /**
     * 语音助手可以控制播放状态（播放 / 暂停）。
     *
     * <p>本应用确实支持这两个动作（见 {@code EasMusicClient.onPlay / onPause}），
     * 所以报 {@code true}。报 {@code false} 会让车机的语音指令绕开本应用。
     */
    @Override public boolean isSupportVrCtrlPlayStatus() { return true; }

    // ------------------------------------------------------------ 意图

    @Override public PendingIntent getLaunchIntent() { return launch; }

    /** 「播放器界面」同样拉到主界面：本应用只有一个界面。 */
    @Override public PendingIntent getPlayerIntent() { return launch; }

    // ------------------------------------------------------------ 一律为空的部分

    /**
     * 台标（网络地址）。
     *
     * <p>酷我的小窗能显示封面，靠的就是这里返回一个 {@code http://} 的 Uri。
     * 电台的台标本来就来自接口（{@code RadioCnApi.Station.image}），直接透传即可；
     * 本地收藏的老台、或接口没给图的台返回 null，小窗回落成一块占位色。
     */
    @Override public Uri getArtwork() { return artwork; }

    @Override public Uri getNextArtwork() { return null; }

    @Override public Uri getPreviousArtwork() { return null; }

    @Override public Uri getMediaPath() { return null; }

    @Override public Uri getLyric() { return null; }

    /** 小窗第二行。电台没有歌词，塞节目名（「正在直播：xxx」）。 */
    @Override public String getLyricContent() { return lyric; }

    @Override public String getCurrentLyricSentence() { return lyric; }

    @Override public String getAppIcon() { return null; }

    /** 单屏车机：媒体卡片画在主屏。多屏车型以后要按 displayId 分别推。 */
    @Override public int getDisplayId() { return 0; }

    // 收藏 / 下载 / 会员：本应用都没有。基类的默认值就是 false/0，
    // 但显式写出来，免得以后有人以为「忘了实现」。

    @Override public boolean isCollected() { return false; }

    @Override public boolean isDownloaded() { return false; }

    @Override public boolean isSupportCollect() { return false; }

    @Override public boolean isSupportDownload() { return false; }

    @Override public boolean isSupportLoopModeSwitch() { return false; }

    @Override public int getVip() { return 0; }
}
