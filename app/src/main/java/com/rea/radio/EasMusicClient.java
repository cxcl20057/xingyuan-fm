package com.rea.radio;

import com.ecarx.eas.sdk.mediacenter.MediaInfo;
import com.ecarx.eas.sdk.mediacenter.MediaListsInfo;
import com.ecarx.eas.sdk.mediacenter.MusicClient;
import com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo;

import java.util.List;

/**
 * 媒体中心反向调用本应用的入口。
 *
 * <h3>这个类为什么是「方向盘按键」的正解</h3>
 *
 * <p>车机方向盘按键实际由 {@code com.android.car} 处理：如果当前有「活跃媒体源」
 * （也就是在媒体中心注册过、且声明自己正在播的客户端），按键就被翻译成
 * {@link #onPlay()} / {@link #onNext()} / {@link #onPrevious()} 这类回调，
 * <b>直接调到本类</b> —— 跟本应用是不是前台窗口毫无关系。这就是
 * 「QQ 音乐切到导航界面后方向盘照样能切歌」的机制。
 *
 * <p>v1.9.0 只做了前台版（{@code MainActivity.onKeyDown} 里接私有键码 346/347/348），
 * 那是「按键落到焦点窗口」的兜底路径；本类补的是后台路径。两条路径<b>互不冲突</b>：
 * 前台时按键走 {@code onKeyDown}，后台时走这里。别为了这个删掉那个。
 *
 * <h3>为什么继承 {@code MusicClient} 类，而不是实现 {@code IMusicClient} 接口</h3>
 *
 * <p>SDK 里两套 API 都在，但<b>只有类版能注册上</b>：
 * {@code MediaCenterProxy.registerMusic(IMusicClient)} 反编译出来是
 * {@code aconst_null; areturn} —— 空壳，直接返回 null。实测日志：
 * <pre>
 * [媒体源] EAS 框架就绪回调：true
 * [媒体源] registerMusic 返回 null（框架还没连上？）稍后重试
 * </pre>
 * 框架其实已经连上了，是这个重载压根没实现。类版
 * {@code registerMusic(MusicClient, RegisterRequest)} 才是真正转发到服务的。
 *
 * <p>代价要接受：{@code MusicClient} 的播放状态返回类型是
 * {@code MusicPlaybackInfo}（34 个 getter 全是 {@code return null} 的空壳 DTO），
 * 所以状态快照走 {@link EasPlaybackInfo} 继承覆盖。
 *
 * <p>好处是只覆盖真正用到的方法 —— {@code MusicClient} 已经有 36 个默认实现，
 * 没覆盖的（收藏、下载、媒体列表、语音搜索）会返回默认值，不会崩。
 *
 * <h3>线程注意</h3>
 *
 * <p>这些回调跑在 Binder 线程（媒体中心的调用线程），而 {@link RadioPlayer}
 * 里的 ExoPlayer 与 MediaSession 都建在主线程的 Looper 上，跨线程直接调会
 * 踩状态竞争（表现为偶发「按一下切两台」或播放器内部 assert）。
 * 所以一律 {@link EasMediaCenter#post(Runnable)} 回主线程再执行。
 *
 * <h3>返回值</h3>
 *
 * <p>返回 {@code true} 表示「我接了」。返回 {@code false} 时媒体中心会认为
 * 本应用处理不了，可能继续往下一个媒体源派（也就是「切到别人的台」）。
 * 所以只要请求合理就必须返回 {@code true}，哪怕动作稍微延后执行。
 */
final class EasMusicClient extends MusicClient {

    private final EasMediaCenter owner;

    EasMusicClient(EasMediaCenter owner) {
        this.owner = owner;
    }

    // ------------------------------------------------------------ 通话让路

    /**
     * 通话/响铃期间，媒体中心转来的按键一律「不处理」。
     *
     * <p>正常情况下媒体中心不该在通话时还派给我们 —— {@code yieldForCall()} 已经
     * 把播放焦点还回去了。但它是异步的，实测从「焦点还掉」到「路由切换」之间有窗口，
     * 而且车机固件可能压根不认焦点变化。所以这里再拦一道，双保险。
     *
     * <p>返回 {@code false} 而不是 {@code true}：{@code false} 的语义是
     * 「本应用处理不了这个请求」，媒体中心才会继续往下一个处理者派（通话界面）。
     * 返回 {@code true} 等于把按键吞掉，电话就更接不起来了。
     *
     * @param what 回调名，只用于日志
     * @return {@code true} = 已按通话让路处理，调用方应直接 return false
     */
    private boolean yieldToCall(String what) {
        if (!CallGuard.shouldYieldKeys(owner.context())) return false;
        AppLog.key("通话", "通话中：媒体中心 " + what + " 不处理（按键留给通话界面）");
        return true;
    }

    // ------------------------------------------------------------ 播放控制

    @Override
    public boolean onPlay() {
        AppLog.key("媒体源", "媒体中心 onPlay");
        if (yieldToCall("onPlay")) return false;
        owner.post(new Runnable() {
            @Override public void run() {
                RadioPlayer p = owner.player();
                if (p != null) p.resume();
            }
        });
        return true;
    }

    @Override
    public boolean onPause() {
        AppLog.key("媒体源", "媒体中心 onPause");
        owner.post(new Runnable() {
            @Override public void run() {
                RadioPlayer p = owner.player();
                if (p != null) p.pause();
            }
        });
        return true;
    }

    /** 方向盘「下一台」。 */
    @Override
    public boolean onNext() {
        AppLog.key("媒体源", "媒体中心 onNext");
        if (yieldToCall("onNext")) return false;
        owner.post(new Runnable() {
            @Override public void run() {
                RadioPlayer p = owner.player();
                if (p != null) p.next();
            }
        });
        return true;
    }

    /** 方向盘「上一台」。 */
    @Override
    public boolean onPrevious() {
        AppLog.key("媒体源", "媒体中心 onPrevious");
        if (yieldToCall("onPrevious")) return false;
        owner.post(new Runnable() {
            @Override public void run() {
                RadioPlayer p = owner.player();
                if (p != null) p.prev();
            }
        });
        return true;
    }

    /**
     * 媒体中心让本应用退出播放（用户切到了别的媒体源）。
     *
     * <p>用 {@code stop()} 而不是 {@code pause()}：媒体中心的语义是「不当我是当前源了」，
     * 此时要把 MediaSession 置为不活跃（见 {@code RadioPlayer.stop()}），
     * 否则会话还挂着，方向盘会被抢回来。
     */
    @Override
    public boolean onExit() {
        AppLog.key("媒体源", "媒体中心 onExit");
        owner.post(new Runnable() {
            @Override public void run() {
                RadioPlayer p = owner.player();
                if (p != null) p.stop();
                owner.clearFocus();
            }
        });
        return true;
    }

    /**
     * 进度拖动。直播没有进度，直接忽略。
     *
     * <p>这里<b>不能</b>映射成切台 —— 车机锁屏误滑一下就会换台（v1.8.9 之前的坑）。
     */
    @Override
    public void onSeek(long positionMs) {
        AppLog.key("媒体源", "媒体中心 onSeek(" + positionMs + ") → 直播不可 seek，忽略");
    }

    /** 语音/媒体中心要求「重播」：直播直接当恢复播放。 */
    @Override
    public boolean onReplay() {
        AppLog.key("媒体源", "媒体中心 onReplay → 按恢复播放处理");
        return onPlay();
    }

    /**
     * 快进 / 快退。
     *
     * <p>直播没有「快进 10 秒」这回事。语义上最接近的是「下一台」，
     * 但<b>不能</b>这么映射：车机上长按快进会以 200ms 间隔连发多次，
     * 一次长按能切掉五六个台。这里忽略并记日志，保证
     * 「上一台 / 下一台」是唯一可用的切台方式。
     */
    @Override
    public boolean onForward() {
        AppLog.key("媒体源", "媒体中心 onForward → 直播无快进，忽略");
        return true;
    }

    @Override
    public boolean onRewind() {
        AppLog.key("媒体源", "媒体中心 onRewind → 直播无快退，忽略");
        return true;
    }

    /**
     * 媒体中心选中了本应用这个媒体源（比如用户在切源列表里点了「星愿收音机」）。
     *
     * <p>本应用只有一个源、没有「条目」概念，所以「被选中」就等价于「开始播」。
     */
    @Override
    public boolean onSourceSelected(int sourceType) {
        AppLog.key("媒体源", "媒体中心 onSourceSelected(" + sourceType + ")");
        if (yieldToCall("onSourceSelected")) return false;
        return onPlay();
    }

    @Override
    public boolean onMediaSelected(MediaInfo mediaInfo) {
        // 拿得到 id 就播那一条；拿不到（父类空壳 DTO 的 getUuid 返回 null）就当「继续播当前台」。
        if (yieldToCall("onMediaSelected")) return false;
        String id = mediaInfo == null ? null : mediaInfo.getUuid();
        if (id != null && owner.playByMediaId(id)) return true;
        return onPlay();
    }

    /** 循环模式对直播无意义，但得回 true，否则媒体中心 UI 会以为按钮坏了。 */
    @Override
    public boolean onLoopModeChange(int loopMode) {
        AppLog.key("媒体源", "媒体中心 onLoopModeChange(" + loopMode + ") → 直播不分循环模式");
        return true;
    }

    // ------------------------------------------------------------ 状态上报

    /**
     * 媒体中心随时可能来拉一次当前状态（比如它刚启动、或者用户打开媒体卡片）。
     *
     * <p>返回主线程维护的最新快照：{@code RadioPlayer.updateSessionState()} 每次被调用
     * 都会刷新它（切台、播放/暂停、缓冲开始结束），最多落后一次状态变化。
     * 不在这个 Binder 线程里现算 —— 现算要读 ExoPlayer 状态字段，
     * 那些字段只保证在主线程可见。
     */
    @Override
    public MusicPlaybackInfo getMusicPlaybackInfo() {
        return owner.snapshot();
    }

    /**
     * 本应用支持哪些媒体源类型。
     *
     * <p>只报「网络电台」一种。这里<b>不要</b>把本地音乐、视频都列上 ——
     * 媒体中心会据此在 UI 上给出「切换源」入口，列了却没有对应功能，
     * 用户切过去就是一片空白。
     */
    @Override
    public int[] getMediaSourceTypeList() {
        return new int[] { EasPlaybackInfo.sourceType() };
    }

    @Override
    public int getCurrentSourceType() {
        return EasPlaybackInfo.sourceType();
    }

    /** 直播没有「播放进度」，恒 0。 */
    @Override
    public long getCurrentProgress() {
        return 0L;
    }

    /**
     * 播放列表（媒体中心来拉时用）。
     *
     * <p>以前这里返回空表，理由是「本应用没有列表概念」。<b>那个判断是错的</b>：
     * 队列就是列表（当前省的全部电台），而媒体中心的「媒体源清单」页正是靠它渲染。
     * 返回空表的直接后果就是点进去显示<b>「当前暂无播放内容」</b>。
     *
     * <p>现在返回主线程维护的快照（{@link EasMediaCenter#playlist()}），
     * 与 {@code updateMediaList} 推上去的那份是同一批对象，不会出现「推的和拉到的不一样」。
     */
    @Override
    public List<MediaInfo> getPlaylist(int type) {
        return owner.playlist();
    }

    /**
     * 多列表拉取。车机媒体中心的「媒体源清单」页优先走这个，而不是 {@link #getPlaylist(int)}。
     *
     * <p>不覆盖它，父类返回 {@code null}，服务拿不到任何列表 —— 同样是「暂无播放内容」。
     * 本应用只有一个列表（当前省的电台队列），所以只装一个进去。
     */
    @Override
    public MediaListsInfo getMultiMediaList(int[] types) {
        return owner.multiMediaList();
    }

    /**
     * 用户在清单里点了某一条（服务用「源类型 + 媒体 id」的形式回调）。
     *
     * <p>SDK 有两个重载：{@code onMediaSelected(MediaInfo)} 和这个
     * {@code onMediaSelected(int, String)}。<b>实测清单页走的是后者</b>
     * —— 只覆盖 MediaInfo 版等于没接。
     */
    @Override
    public boolean onMediaSelected(int sourceType, String mediaId) {
        AppLog.key("媒体源", "媒体中心 onMediaSelected(" + sourceType + ", " + mediaId + ")");
        if (yieldToCall("onMediaSelected(int,String)")) return false;
        return owner.playByMediaId(mediaId);
    }

    /** 列表内选播（pos 是队列下标）。 */
    @Override
    public boolean selectListMediaPlay(int sourceType, int position, String mediaListId) {
        AppLog.key("媒体源", "媒体中心 selectListMediaPlay(source=" + sourceType
                + ", pos=" + position + ", list=" + mediaListId + ")");
        if (yieldToCall("selectListMediaPlay")) return false;
        owner.playAtAsync(position);
        return true;
    }
}
