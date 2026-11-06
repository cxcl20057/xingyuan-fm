package com.rea.radio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 补录台：央广接口<b>没有收录</b>、但确实在播的电台。
 *
 * <h3>为什么需要这个类</h3>
 * 用户反馈「FM99.8、88.2、94.6 三个台在列表里找不到」。查证结果是：
 * {@code /web/appBroadcast/list} 对四川返回 44 个台，其中成都相关的只有
 * 「成都经济广播」「成都交通文艺广播」「成都潮台88.7」「AsiaFM成都96.5」四个，
 * <b>根本没有新闻/故事/文化休闲这三个频率</b>。
 *
 * <p>而这三条流是真实存在的：成都广电自建 CDN 上
 * {@code live/fm998}、{@code fm882}、{@code fm946} 实测全部 HTTP 200
 * （见 {@code scripts/hls-probe/run.sh} 同源的探测方式）。
 * 也就是说，之前的版本只是在 {@code RadioCnApi.CDBS} 里备了这三条地址当「兜底」，
 * 但列表里从来没有对应的台，兜底永远用不上——备胎备好了，没有车。
 *
 * <h3>这是补录，不是接口数据，必须能区分</h3>
 * 台名与频率的对应关系来自成都广电官网与云听入驻公告（见 {@link FrequencyTable} 的注释），
 * 不是接口字段。<b>名称有可能与官方现用名有出入</b>（比如官方现称「新闻频率」，
 * 央广旧条目叫「成都新闻广播」），所以：
 * <ul>
 *   <li>台名统一沿用央广那批成都台的命名风格（「成都××广播」），保持列表观感一致；</li>
 *   <li>{@code contentId} 用固定的 {@code cdbs-*} 前缀，与央广的数字 ID 天然不冲突，
 *       收藏、开机续播都能稳定对上；</li>
 *   <li>只在<b>四川</b>列表里合并，别的省份不硬塞成都台；</li>
 *   <li>若接口某天开始收录同名台，{@link #mergeInto} 会跳过补录，避免出现两个一样的台。</li>
 * </ul>
 */
final class LocalStations {

    /** 补录台的 contentId 前缀，用于识别本类产生的条目。 */
    static final String ID_PREFIX = "cdbs-";

    /** 成都台所属的省份编码（四川）。 */
    private static final int SICHUAN = 510000;

    private LocalStations() {
    }

    /** 一条补录台的定义：台名 + CDN 频道号。 */
    private static final class Def {
        final String title;
        final String channel;

        Def(String title, String channel) {
            this.title = title;
            this.channel = channel;
        }
    }

    /**
     * 补录清单。
     *
     * <p>频道号来自成都广电 CDN 路径实测：{@code fm998} / {@code fm882} / {@code fm946}，
     * 与官方频率 FM99.8 / FM88.2 / FM94.6 一一对应（这也是频率没写错的旁证）。
     */
    private static final List<Def> DEFS = Arrays.asList(
            new Def("成都新闻广播", "fm998"),
            new Def("成都故事广播", "fm882"),
            new Def("成都文化休闲广播", "fm946")
    );

    /** 成都本地台在列表里的排序权重：数值小的排前面。新闻 → 交通 → 经济 → 故事 → 文化休闲。 */
    private static final List<String> CHENGDU_ORDER = Arrays.asList(
            "成都新闻广播", "成都交通文艺广播", "成都经济广播",
            "成都故事广播", "成都文化休闲广播"
    );

    /**
     * 把补录台合并进已经取到的列表。
     *
     * <p>只在四川生效；接口已经有同名台的跳过；接口已经有同 contentId 的也跳过。
     *
     * @param data         接口返回的原始列表（不会被修改）
     * @param provinceCode 当前省份
     * @return 合并后的新列表；非四川返回 {@code data} 本身
     */
    static List<RadioCnApi.Station> mergeInto(List<RadioCnApi.Station> data, int provinceCode) {
        if (data == null) return null;
        if (provinceCode != SICHUAN) return data;

        Set<String> titles = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (RadioCnApi.Station s : data) {
            if (s == null) continue;
            if (s.title != null) titles.add(s.title.trim());
            if (s.contentId != null) ids.add(s.contentId);
        }

        List<RadioCnApi.Station> add = new ArrayList<>();
        for (Def d : DEFS) {
            String id = ID_PREFIX + d.channel;
            if (titles.contains(d.title) || ids.contains(id)) continue;  // 接口已收录，别重复
            add.add(build(d, id));
        }
        if (add.isEmpty()) return data;

        List<RadioCnApi.Station> out = new ArrayList<>(data.size() + add.size());
        out.addAll(data);
        out.addAll(add);
        return out;
    }

    private static RadioCnApi.Station build(Def d, String contentId) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = contentId;
        s.title = d.title;
        s.subtitle = "";
        s.image = null;                 // 没有台标图，界面会回落成「台名首字」字标
        s.provinceCode = SICHUAN;
        s.provinceName = "四川";
        // 只有一条地址，而且就是我们自己的来源，所以四个字段里只填这一个。
        // 放进 mp3PlayUrlHigh 是为了复用 candidates() 的「高码率优先」顺序——
        // 反正其它三个都是 null，会直接被跳过。
        s.mp3PlayUrlHigh = cdbsUrl(d.channel);
        return s;
    }

    /** 成都广电 CDN 的 HLS 地址（两层列表，ExoPlayer 能自己跟进去）。 */
    static String cdbsUrl(String channel) {
        return "http://livecdn.pull.cdbs.com.cn/live/" + channel + "/playlist.m3u8";
    }

    /** 这个 contentId 是不是补录台产生的。 */
    static boolean isLocal(String contentId) {
        return contentId != null && contentId.startsWith(ID_PREFIX);
    }

    /**
     * 成都本地台的排序权重，数值小的靠前。
     *
     * <p>注意这是<b>组内顺序</b>，不是全局顺序——「省台和成都台都靠前」由
     * {@code MainActivity.provinceFirst} 的三档分组负责，本方法只管成都这一组里谁在前。
     *
     * @return 0..4；不是这五个成都台返回 {@link Integer#MAX_VALUE}（排在组内最后，且不影响非成都台）
     */
    static int chengduRank(String title) {
        if (title == null) return Integer.MAX_VALUE;
        int i = CHENGDU_ORDER.indexOf(title.trim());
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** 补录台条数（诊断面板与单测用）。 */
    static int size() {
        return DEFS.size();
    }

    /** 补录台的台名列表（只读，单测用）。 */
    static List<String> titles() {
        List<String> out = new ArrayList<>(DEFS.size());
        for (Def d : DEFS) out.add(d.title);
        return Collections.unmodifiableList(out);
    }
}
