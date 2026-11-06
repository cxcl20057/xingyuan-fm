package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 四川目录并入国家台：纯函数断言，不需要 Android 运行时。
 *
 * <p>守住三件事：① 只在四川并入；② 不出现重复台；③ 省份码保持 0
 * （决定地址过期后去哪张列表补地址，写错就变成「一直缓冲」）。
 */
public class NationalStationsTest {

    private static RadioCnApi.Station st(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        return s;
    }

    private static List<String> titles(List<RadioCnApi.Station> list) {
        List<String> out = new ArrayList<>();
        for (RadioCnApi.Station s : list) out.add(s.title);
        return out;
    }

    /** 四川：国家台追加在尾部，省台原样保留。 */
    @Test
    public void national_stations_are_appended_after_sichuan() {
        List<RadioCnApi.Station> sic = Arrays.asList(
                st("450", "四川交通广播"), st("451", "四川民族广播"));
        List<RadioCnApi.Station> nat = Arrays.asList(
                st("1", "中国之声"), st("2", "经济之声"));

        List<RadioCnApi.Station> out = NationalStations.mergeInto(sic, nat, 510000);

        assertEquals("2 + 2", 4, out.size());
        assertEquals(Arrays.asList("四川交通广播", "四川民族广播", "中国之声", "经济之声"),
                titles(out));
    }

    /** 国家台复制体的归属是「国家」，省份码必须是 0。 */
    @Test
    public void national_copies_carry_national_code() {
        List<RadioCnApi.Station> out = NationalStations.mergeInto(
                Arrays.asList(st("450", "四川交通广播")),
                Arrays.asList(st("1", "中国之声")), 510000);

        RadioCnApi.Station cnr = out.get(out.size() - 1);
        assertEquals("国家", cnr.provinceName);
        assertEquals("省份码保持 0，地址过期才走国家级列表", 0, cnr.provinceCode);
    }

    /** 复制而不是同一个对象：改这边不能污染「国家」文件夹里的那份。 */
    @Test
    public void copies_are_independent_objects() {
        RadioCnApi.Station origin = st("1", "中国之声");
        origin.provinceName = "国家";
        List<RadioCnApi.Station> out = NationalStations.mergeInto(
                Arrays.asList(st("450", "四川交通广播")),
                Arrays.asList(origin), 510000);

        RadioCnApi.Station copy = out.get(out.size() - 1);
        assertNotSame(origin, copy);
        assertEquals("1", copy.contentId);
    }

    /** 同 contentId 或同名台不重复追加。 */
    @Test
    public void duplicates_are_skipped() {
        List<RadioCnApi.Station> sic = Arrays.asList(
                st("1", "中国之声"), st("450", "四川交通广播"));
        List<RadioCnApi.Station> nat = Arrays.asList(
                st("1", "中国之声"), st("9", "中国之声"));

        List<RadioCnApi.Station> out = NationalStations.mergeInto(sic, nat, 510000);

        assertEquals("原列表已经有一份，不再追加", 2, out.size());
    }

    /** 非四川目录不动：重庆、国家文件夹都保持原样。 */
    @Test
    public void other_provinces_untouched() {
        List<RadioCnApi.Station> sic = Arrays.asList(st("450", "四川交通广播"));
        List<RadioCnApi.Station> nat = Arrays.asList(st("1", "中国之声"));

        List<RadioCnApi.Station> out = NationalStations.mergeInto(sic, nat, 500000);
        assertSame("非四川直接返回原列表", sic, out);

        List<RadioCnApi.Station> natFolder = NationalStations.mergeInto(
                new ArrayList<>(nat), nat, NationalStations.NATIONAL_CODE);
        assertEquals("国家文件夹自己不加", 1, natFolder.size());
    }

    /** 国家台列表拉取失败（null / 空）：省级列表原样返回，不能丢台。 */
    @Test
    public void national_failure_keeps_sichuan_list() {
        List<RadioCnApi.Station> sic = Arrays.asList(st("450", "四川交通广播"));
        assertSame(sic, NationalStations.mergeInto(sic, null, 510000));
        assertSame(sic, NationalStations.mergeInto(sic, new ArrayList<>(), 510000));
    }

    /** 并入后再排序：省台 → 成都台 → 国家台。 */
    @Test
    public void sorter_puts_national_last() {
        List<RadioCnApi.Station> merged = NationalStations.mergeInto(
                new ArrayList<>(Arrays.asList(st("450", "成都经济广播"), st("451", "四川交通广播"))),
                new ArrayList<>(Arrays.asList(st("1", "中国之声"))), 510000);

        List<RadioCnApi.Station> ordered = StationSorter.provinceFirst(merged, "四川");
        assertEquals("四川交通广播", ordered.get(0).title);
        assertEquals("成都经济广播", ordered.get(1).title);
        assertEquals("中国之声", ordered.get(2).title);
        assertTrue("国家台只出现在尾部", "中国之声".equals(ordered.get(ordered.size() - 1).title));
    }
}
