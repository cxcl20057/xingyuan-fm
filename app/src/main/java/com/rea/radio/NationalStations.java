package com.rea.radio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把「国家台」并进省级目录（本版只对四川生效）。
 *
 * <h3>为什么需要这个类</h3>
 * 用户在四川开车，日常听的是省台和成都台，但偶尔也要听中国之声、经济之声这类中央台。
 * 原始逻辑里「国家台」只存在于自己的文件夹（{@code provinceCode=0}），
 * 打开四川目录就看不到它们，必须切文件夹再切回来 —— 车机上切一次要两次点击，
 * 开车时不方便。
 *
 * <p>所以这里做的是「<b>复制一份</b>」而不是「搬走」：国家台仍然留在「国家」文件夹里，
 * 同时在四川目录尾部再出现一次。两份互不影响，收藏、隐藏、续播都按 contentId 走，
 * 同一个台在两个目录里指向同一个流。
 *
 * <h3>两个必须守住的细节</h3>
 * <ul>
 *   <li><b>{@code provinceCode} 必须保持 0</b>。这个字段决定「地址过期后去哪张列表补地址」
 *       （见 {@link RadioCnApi#refreshStationUrls}）。若把它改成 510000，
 *       刷新时去四川列表里找中国之声 —— 找不到，播放就停在「正在缓冲」。
 *       保持 0 才会走「国家级列表」那一档。</li>
 *   <li><b>{@code provinceName} 改成「国家」</b>。列表副标题、媒体条第二行、
 *       播放通知都用它，写「国家」一眼能看出这是中央台，不会误以为是省台。</li>
 * </ul>
 *
 * <h3>位置</h3>
 * 追加在列表尾部即可：{@link StationSorter#provinceFirst} 会把「中国××」这类台
 * 归到第三档（本地台之后），顺序正好是「省台 → 成都台 → 国家台」。
 */
final class NationalStations {

    /** 国家级列表的省份编码（{@code provinceCode=0}，接口只返回中央台）。 */
    static final int NATIONAL_CODE = 0;

    /** 四川的省份编码。 */
    static final int SICHUAN = 510000;

    /** 并入后在列表里显示的归属名。 */
    static final String NAME = "国家";

    private NationalStations() {
    }

    /**
     * 把国家台追加到省级列表尾部。
     *
     * <p>只在四川生效；接口已经有同 contentId 或同名台的跳过（避免出现两个「中国之声」）。
     *
     * @param data         省级列表（不会被修改；返回的是新列表）
     * @param national     国家台列表，可为 null（拉取失败）
     * @param provinceCode 当前省份
     * @return 新列表；非四川或没有可追加的台时，原样返回 {@code data}
     */
    static List<RadioCnApi.Station> mergeInto(List<RadioCnApi.Station> data,
                                             List<RadioCnApi.Station> national,
                                             int provinceCode) {
        if (data == null) return null;
        if (provinceCode != SICHUAN) return data;
        if (national == null || national.isEmpty()) return data;

        Set<String> ids = new HashSet<>();
        Set<String> titles = new HashSet<>();
        for (RadioCnApi.Station s : data) {
            if (s == null) continue;
            if (s.contentId != null) ids.add(s.contentId);
            if (s.title != null) titles.add(s.title.trim());
        }

        List<RadioCnApi.Station> add = new ArrayList<>();
        for (RadioCnApi.Station s : national) {
            if (s == null || s.contentId == null || s.title == null) continue;
            if (s.title.trim().length() == 0) continue;
            if (ids.contains(s.contentId) || titles.contains(s.title.trim())) continue;
            // 复制一份再改字段：national 里的对象可能正被「国家」文件夹的列表引用，
            // 直接改会让那边也显示成「国家 · 国家」之类的错乱。
            add.add(copy(s));
        }
        if (add.isEmpty()) return data;

        List<RadioCnApi.Station> out = new ArrayList<>(data.size() + add.size());
        out.addAll(data);
        out.addAll(add);
        return out;
    }

    /** 复制一条台，并把归属改成「国家」、省份码保持 0（决定刷新走哪张列表）。 */
    private static RadioCnApi.Station copy(RadioCnApi.Station src) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = src.contentId;
        s.title = src.title;
        s.subtitle = src.subtitle;
        s.image = src.image;
        s.playUrlLow = src.playUrlLow;
        s.mp3PlayUrlLow = src.mp3PlayUrlLow;
        s.mp3PlayUrlHigh = src.mp3PlayUrlHigh;
        s.playUrlMulti = src.playUrlMulti;
        s.provinceName = NAME;
        s.provinceCode = NATIONAL_CODE;
        return s;
    }
}
