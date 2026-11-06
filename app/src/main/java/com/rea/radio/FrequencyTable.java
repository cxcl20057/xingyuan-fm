package com.rea.radio;

import java.util.HashMap;
import java.util.Map;

/**
 * 台名 → 频率。
 *
 * <h3>为什么要硬编码这张表</h3>
 * 央广接口（{@code /web/appBroadcast/list}）返回的字段只有
 * {@code contentId / title / subtitle / image / 4 个播放地址}，<b>没有任何频率字段</b>，
 * 且 {@code subtitle} 实测全部是「暂无节目单」。所以频率只能由台名反查，没有别的来源。
 *
 * <h3>只收录有把握的，不猜</h3>
 * 宁可某个台不显示频率，也不显示错的——错频率比没有频率更糟。
 * 这里每条都至少有一条独立来源交叉印证（2026-10 核对）：
 *
 * <p><b>成都市广播电视台五大频率</b>（成都广电官网 cdtv.cn 电台频率栏 + 云听入驻公告）：
 * FM99.8 新闻 / FM91.4 交通 / FM105.6 经济 / FM88.2 故事 / FM94.6 文化休闲。
 * 另有旁证：成都广电自建 CDN 的路径就是 {@code live/fm998}、{@code fm914}、{@code fm1056}、
 * {@code fm882}、{@code fm946}，与上表逐一对上，两条独立证据链吻合。
 *
 * <p><b>四川广播电视台</b>（四川台百科 + 云听入驻公告）：
 * FM98.1 综合 / FM106.1 新闻 / FM101.7 交通 / FM97.0 民族 / FM95.5 岷江音乐 / FM102.6 城市之音。
 *
 * <p><b>注意别和「台名里自带数字」的台混起来</b>：列表里还有
 * 「成都潮台88.7」「城市96.5」「AsiaFM成都96.5」「年代音乐1022」这类台，
 * 它们的数字是台名本身的一部分，不在本表内——查不到就照原样显示，不做任何推断。
 * 曾考虑过「从台名里正则抠数字当频率」，正因为这批台会被误抠成频率而放弃。
 */
final class FrequencyTable {

    /** 台名（接口原文，精确匹配）→ 频率显示串。 */
    private static final Map<String, String> BY_TITLE = build();

    private FrequencyTable() {
        // 工具类，不实例化
    }

    private static Map<String, String> build() {
        Map<String, String> m = new HashMap<>();

        // —— 成都市广播电视台（5 个）——
        m.put("成都新闻广播", "FM99.8");
        m.put("成都交通文艺广播", "FM91.4");
        m.put("成都经济广播", "FM105.6");
        m.put("成都故事广播", "FM88.2");
        m.put("成都文化休闲广播", "FM94.6");

        // —— 四川广播电视台（6 个）——
        m.put("四川综合广播", "FM98.1");
        m.put("四川新闻广播", "FM106.1");
        m.put("四川交通广播", "FM101.7");
        m.put("四川民族广播", "FM97.0");
        m.put("四川岷江音乐频率", "FM95.5");
        m.put("四川城市之音", "FM102.6");

        return m;
    }

    /**
     * 取频率。
     *
     * @param title 台名（接口原文或补录台名）
     * @return 形如 {@code "FM102.6"}；查不到返回 {@code null}，调用方据此决定是否追加括号
     */
    static String of(String title) {
        if (title == null) return null;
        return BY_TITLE.get(title.trim());
    }

    /** 本表覆盖多少个台（诊断面板与单测用）。 */
    static int size() {
        return BY_TITLE.size();
    }
}
