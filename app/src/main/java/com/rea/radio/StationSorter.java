package com.rea.radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 电台列表的排序规则：「省台 → 成都/省会台 → 其余」。
 *
 * <h3>为什么从 MainActivity 里抽出来</h3>
 * 这段规则原本是 {@code MainActivity.provinceFirst} 的私有方法，
 * 只能靠 Robolectric 起整个界面才能测——而它其实<b>是纯函数</b>：
 * 输入一个列表 + 省份名，输出一个新顺序。
 * 抽出来之后可以用普通 JUnit 直接断言，排序规则改动也更容易被测试守住。
 *
 * <h3>三档顺序</h3>
 * <ol>
 *   <li><b>省级台</b>：以省名开头、且不含任何地市名（「四川交通广播」）；</li>
 *   <li><b>省会/主要城市台</b>：含省名或含地市名、且不是国家级台（「成都新闻广播」）；</li>
 *   <li><b>其余台</b>：国家台（「中国交通广播」）与未能归类的台。</li>
 * </ol>
 *
 * <p>国家台单独归到第三档是有意的：它们是全国覆盖的公共频率，
 * 用户在本地场景下更关心「我所在省/市有什么台」，所以让本地台压在前面。
 *
 * <h3>成都组内的固定顺序</h3>
 * 第二档里如果出现成都的台，按「新闻 → 交通 → 经济 → 故事 → 文化休闲」排
 * （见 {@link LocalStations#chengduRank}）。其余台保持服务端原有相对顺序（稳定排序）。
 */
final class StationSorter {

    /** 主要城市关键字，用于把「省级台」和「地市台」分开。 */
    static final String[] MAJOR_CITIES = {
            "成都", "绵阳", "德阳", "自贡", "攀枝花", "泸州", "广元", "遂宁", "内江", "乐山",
            "南充", "眉山", "宜宾", "广安", "达州", "雅安", "巴中", "资阳", "阿坝", "甘孜",
            "凉山", "仁寿", "双流", "都江堰", "西昌"
    };

    private StationSorter() {
    }

    /**
     * 排序主入口。
     *
     * @param data         原始列表（不修改）
     * @param provinceName 当前省份名，可带「省」字（「四川省」「四川」都行）
     * @return 新列表，三档顺序；同一档内保持服务端原有顺序
     */
    static List<RadioCnApi.Station> provinceFirst(List<RadioCnApi.Station> data, String provinceName) {
        if (data == null || data.isEmpty()) return new ArrayList<>();
        final String pn = provinceName == null ? "" : provinceName.replace("省", "").trim();

        List<RadioCnApi.Station> tier1 = new ArrayList<>();
        List<RadioCnApi.Station> tier2 = new ArrayList<>();
        List<RadioCnApi.Station> tier3 = new ArrayList<>();

        for (RadioCnApi.Station s : data) {
            if (s == null) continue;
            String t = s.title == null ? "" : s.title;
            boolean isCity = containsCity(t);
            boolean startsWithProvince = pn.length() > 0 && t.startsWith(pn);
            boolean national = isNational(t);

            if (startsWithProvince && !isCity) tier1.add(s);
            else if ((t.contains(pn) || isCity) && !national) tier2.add(s);
            else tier3.add(s);
        }

        sortChengduGroup(tier2);

        List<RadioCnApi.Station> out = new ArrayList<>(data.size());
        out.addAll(tier1);
        out.addAll(tier2);
        out.addAll(tier3);
        return out;
    }

    /** 成都的台在第二档内按固定顺序排；非成都台不受影响，各自保持原相对次序。 */
    private static void sortChengduGroup(List<RadioCnApi.Station> tier2) {
        // Collections.sort 是稳定排序，所以「非成都台」之间仍保持服务端顺序。
        // 用 sort 而不是自己搬元素：自写搬移很容易在「权重相同」时把顺序打乱。
        Collections.sort(tier2, new Comparator<RadioCnApi.Station>() {
            @Override
            public int compare(RadioCnApi.Station a, RadioCnApi.Station b) {
                return Integer.compare(LocalStations.chengduRank(a == null ? null : a.title),
                        LocalStations.chengduRank(b == null ? null : b.title));
            }
        });
    }

    static boolean containsCity(String title) {
        if (title == null) return false;
        for (String city : MAJOR_CITIES) {
            if (title.contains(city)) return true;
        }
        return false;
    }

    static boolean isNational(String title) {
        if (title == null) return false;
        return title.contains("中国") || title.contains("中央") || title.contains("国家");
    }
}
