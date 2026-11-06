package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 补录台与排序规则：都是纯函数，直接断言。
 *
 * <p>整个类跑在 Robolectric 下，是为了「补录默认开关」那条用例能真正建一个 MainActivity；
 * 其余用例不依赖 Android 运行时，结果不受影响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalStationsTest {

    private static RadioCnApi.Station st(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        return s;
    }

    // ------------------------------------------------------------ 补录

    /** 四川：接口没收录的三个台要补进来。 */
    @Test
    public void three_missing_chengdu_stations_are_added() {
        List<RadioCnApi.Station> data = new ArrayList<>(Arrays.asList(
                st("1", "四川交通广播"), st("2", "成都经济广播")));
        List<RadioCnApi.Station> out = LocalStations.mergeInto(data, 510000);

        assertEquals("原来 2 个 + 补录 3 个", 5, out.size());
        assertTrue(titles(out).contains("成都新闻广播"));
        assertTrue(titles(out).contains("成都故事广播"));
        assertTrue(titles(out).contains("成都文化休闲广播"));
    }

    /** 补录台必须带可播地址，否则补进来是个空台。 */
    @Test
    public void local_stations_have_playable_url() {
        List<RadioCnApi.Station> out = LocalStations.mergeInto(
                new ArrayList<>(), 510000);
        assertEquals(3, out.size());
        for (RadioCnApi.Station s : out) {
            assertNotNull(s.mp3PlayUrlHigh);
            assertTrue("补录台地址应指向成都广电 CDN：" + s.mp3PlayUrlHigh,
                    s.mp3PlayUrlHigh.contains("livecdn.pull.cdbs.com.cn"));
            assertTrue("必须是 HLS 播放列表", s.mp3PlayUrlHigh.endsWith(".m3u8"));
            assertFalse("候选地址不能是空的", s.candidates().isEmpty());
        }
    }

    /** contentId 要稳定且可识别，收藏/续播都靠它。 */
    @Test
    public void local_content_ids_are_stable_and_recognizable() {
        List<RadioCnApi.Station> out = LocalStations.mergeInto(new ArrayList<>(), 510000);
        for (RadioCnApi.Station s : out) {
            assertTrue("应能被识别为补录台：" + s.contentId, LocalStations.isLocal(s.contentId));
        }
        // 两次生成必须完全一致（否则重启后收藏对不上）
        List<RadioCnApi.Station> again = LocalStations.mergeInto(new ArrayList<>(), 510000);
        assertEquals(out.get(0).contentId, again.get(0).contentId);

        assertFalse("央广的数字 ID 不该被当成补录台", LocalStations.isLocal("12345"));
        assertFalse(LocalStations.isLocal(null));
    }

    /** 非四川不补（不能给广东列表硬塞成都台）。 */
    @Test
    public void other_provinces_are_untouched() {
        List<RadioCnApi.Station> data = new ArrayList<>(Arrays.asList(st("1", "广东广播")));
        assertSame("非四川应原样返回同一个对象", data,
                LocalStations.mergeInto(data, 440000));
    }

    /** 接口哪天开始收录同名台了，不能出现两个。 */
    @Test
    public void duplicate_title_is_not_added_twice() {
        List<RadioCnApi.Station> data = new ArrayList<>(Arrays.asList(
                st("1", "成都新闻广播"), st("2", "成都故事广播"), st("3", "成都文化休闲广播")));
        List<RadioCnApi.Station> out = LocalStations.mergeInto(data, 510000);
        assertEquals("接口已有的不该重复补", 3, out.size());
        assertEquals(1, count(out, "成都新闻广播"));
    }

    @Test
    public void null_input_is_safe() {
        assertEquals(null, LocalStations.mergeInto(null, 510000));
    }

    // ------------------------------------------------------------ 排序

    /**
     * 省台在最前、成都台紧随其后，国家台与地市台在后。
     * 这是用户明确要求的「省台和成都的电台都靠前」。
     */
    @Test
    public void province_and_chengdu_come_first() {
        List<RadioCnApi.Station> data = Arrays.asList(
                st("a", "中国交通广播（四川）"),
                st("b", "绵阳交通广播"),
                st("c", "成都经济广播"),
                st("d", "四川交通广播"),
                st("e", "成都新闻广播"),
                st("f", "德阳综合广播"),
                st("g", "四川城市之音"));

        List<RadioCnApi.Station> out = StationSorter.provinceFirst(data, "四川");
        List<String> t = titles(out);

        // 前三个必须是省台（顺序保持服务端相对次序）
        assertEquals(Arrays.asList("四川交通广播", "四川城市之音"), t.subList(0, 2));
        // 接下来是成都的台
        assertTrue("成都台应紧跟省台之后，实际：" + t,
                t.indexOf("成都经济广播") < t.indexOf("绵阳交通广播"));
        assertTrue(t.indexOf("成都新闻广播") < t.indexOf("绵阳交通广播"));
        // 国家台不能在本地台前面
        assertTrue("国家台不该排在省台/成都台前面，实际：" + t,
                t.indexOf("中国交通广播（四川）") > t.indexOf("成都新闻广播"));
    }

    /** 成都组内按 新闻 → 交通 → 经济 → 故事 → 文化休闲 固定顺序。 */
    @Test
    public void chengdu_group_has_fixed_order() {
        List<RadioCnApi.Station> data = Arrays.asList(
                st("1", "成都文化休闲广播"),
                st("2", "成都故事广播"),
                st("3", "成都经济广播"),
                st("4", "成都交通文艺广播"),
                st("5", "成都新闻广播"),
                st("6", "四川交通广播"));

        List<RadioCnApi.Station> out = StationSorter.provinceFirst(data, "四川");
        List<String> t = titles(out);
        List<String> cd = new ArrayList<>();
        for (String s : t) if (s.startsWith("成都")) cd.add(s);

        assertEquals(Arrays.asList(
                "成都新闻广播", "成都交通文艺广播", "成都经济广播",
                "成都故事广播", "成都文化休闲广播"), cd);
    }

    /** 补录台与原接口的台混在一起时，也要按上一条的顺序排。 */
    @Test
    public void local_and_remote_chengdu_stations_sort_together() {
        List<RadioCnApi.Station> merged = LocalStations.mergeInto(new ArrayList<>(Arrays.asList(
                st("1", "成都经济广播"), st("2", "成都交通文艺广播"))), 510000);
        List<RadioCnApi.Station> out = StationSorter.provinceFirst(merged, "四川");
        List<String> cd = new ArrayList<>();
        for (String s : titles(out)) if (s.startsWith("成都")) cd.add(s);
        assertEquals(Arrays.asList(
                "成都新闻广播", "成都交通文艺广播", "成都经济广播",
                "成都故事广播", "成都文化休闲广播"), cd);
    }

    /** 同一档内，非成都台之间要保持服务端原顺序（稳定排序）。 */
    @Test
    public void stable_within_tier() {
        List<RadioCnApi.Station> data = Arrays.asList(
                st("1", "德阳综合广播"), st("2", "绵阳交通广播"), st("3", "乐山综合广播"));
        List<String> t = titles(StationSorter.provinceFirst(data, "四川"));
        assertEquals(Arrays.asList("德阳综合广播", "绵阳交通广播", "乐山综合广播"), t);
    }

    @Test
    public void empty_and_null_are_safe() {
        assertTrue(StationSorter.provinceFirst(null, "四川").isEmpty());
        assertTrue(StationSorter.provinceFirst(new ArrayList<>(), "四川").isEmpty());
    }

    /** 省名带不带「省」字，结果要一致。 */
    @Test
    public void province_name_with_or_without_suffix() {
        List<RadioCnApi.Station> data = Arrays.asList(
                st("1", "成都经济广播"), st("2", "四川交通广播"));
        assertEquals(titles(StationSorter.provinceFirst(data, "四川")),
                titles(StationSorter.provinceFirst(data, "四川省")));
    }

    // ------------------------------------------------------------ 开关

    /**
     * 补录默认必须是开的：这是线上路径。有人把它改成 false 或者忘了接回 showStations，
     * 用户那边三个台就又「找不到」了，所以这里钉死默认值。
     */
    @Test
    public void local_stations_enabled_by_default() {
        MainActivity a = org.robolectric.Robolectric
                .buildActivity(MainActivity.class).create().get();
        java.lang.reflect.Field f;
        try {
            f = MainActivity.class.getDeclaredField("localStationsEnabled");
            f.setAccessible(true);
            assertTrue("默认必须开启补录（否则 FM99.8 等三个台又会消失）",
                    f.getBoolean(a));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------ 工具

    private static List<String> titles(List<RadioCnApi.Station> l) {
        List<String> out = new ArrayList<>(l.size());
        for (RadioCnApi.Station s : l) out.add(s.title);
        return out;
    }

    private static int count(List<RadioCnApi.Station> l, String title) {
        int n = 0;
        for (RadioCnApi.Station s : l) if (title.equals(s.title)) n++;
        return n;
    }
}
