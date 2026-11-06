package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 台名显示规则与频率表。
 *
 * <h3>v1.8.3 的转向</h3>
 * 旧版本会在台名后拼「（FM102.6）」并挂字号跨度，本文件原来大半在验证那套逻辑。
 * 用户要求去掉频率后缀后，{@link StationTitle#plain} / {@link StationTitle#styled}
 * 都退化成「原样返回台名」，相关用例也改成了守护这一点。
 *
 * <p>{@link FrequencyTable} 本身的用例<b>全部保留</b>：表还在、还被
 * {@code LocalStations} 的注释和本地补录的可信度依赖，删掉这些断言等于放弃了
 * 「频率必须与官方一致」这条约束 —— 将来要把频率挪到副标题或详情页时，
 * 这些断言就是唯一能防止记错的地方。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StationTitleTest {

    @Test
    public void known_station_gets_frequency() {
        assertEquals("FM102.6", FrequencyTable.of("四川城市之音"));
        assertEquals("FM99.8", FrequencyTable.of("成都新闻广播"));
        assertEquals("FM94.6", FrequencyTable.of("成都文化休闲广播"));
    }

    /** 表里没有的台要返回 null，绝不能瞎猜一个。 */
    @Test
    public void unknown_station_has_no_frequency() {
        assertNull("「成都潮台88.7」的数字在台名里，不在表里，不该被当成频率",
                FrequencyTable.of("成都潮台88.7"));
        assertNull(FrequencyTable.of("城市96.5"));
        assertNull(FrequencyTable.of("年代音乐1022"));
        assertNull(FrequencyTable.of(null));
        assertNull(FrequencyTable.of(""));
    }

    // --------------------------------------------------- v1.8.3：不再拼频率

    /**
     * 台名必须原样输出，<b>一个字符都不许多</b>。
     *
     * <p>这条用例守的是用户明确提的要求：「去掉所有电台名称后面的频率即（FM102.6）」。
     * 如果将来有人手滑把拼接加回来，这里会立刻变红。
     */
    @Test
    public void plain_text_is_just_the_title() {
        assertEquals("四川城市之音", StationTitle.plain("四川城市之音"));
        assertEquals("成都潮台88.7", StationTitle.plain("成都潮台88.7"));
        assertEquals("", StationTitle.plain(null));

        // 表里有的台也不许加括号
        assertTrue("已知频率的台也不该出现括号",
                !StationTitle.plain("四川城市之音").contains("（"));
        assertTrue("已知频率的台也不该出现 FM",
                !StationTitle.plain("四川城市之音").contains("FM"));
    }

    /** styled 也不再挂任何字号跨度 —— 没有频率就没有需要缩小的部分。 */
    @Test
    public void styled_text_has_no_span() {
        CharSequence cs = StationTitle.styled("四川城市之音");
        assertEquals("四川城市之音", cs.toString());
        if (cs instanceof Spanned) {
            AbsoluteSizeSpan[] spans =
                    ((Spanned) cs).getSpans(0, cs.length(), AbsoluteSizeSpan.class);
            assertEquals("不该再有字号跨度", 0, spans.length);
        }
        assertEquals("", StationTitle.styled(null).toString());
    }

    /**
     * 旧数据清洗：v1.8.2 及更早存进收藏/偏好的台名带频率后缀，读出来必须剥掉。
     *
     * <p>不剥的后果：老用户升级后，收藏列表里一部分台带「（FM102.6）」、
     * 一部分不带，看起来像数据坏了。
     */
    @Test
    public void strip_removes_legacy_frequency_suffix() {
        assertEquals("四川城市之音", StationTitle.strip("四川城市之音（FM102.6）"));
        assertEquals("成都潮台88.7", StationTitle.strip("成都潮台88.7（FM88.7）"));
        // 本来就没有后缀的不动
        assertEquals("成都潮台88.7", StationTitle.strip("成都潮台88.7"));
        assertEquals("", StationTitle.strip(null));
        // 左括号在首位的极端情况不能把台名截成空串
        assertEquals("（怪名字）", StationTitle.strip("（怪名字）"));
    }

    // --------------------------------------------------- 频率表可信度（保留）

    /** 频率表只覆盖我们核对过的台，不能悄悄膨胀成"猜所有台"。 */
    @Test
    public void table_only_covers_verified_stations() {
        assertEquals("只覆盖 5 个成都台 + 6 个省台", 11, FrequencyTable.size());
        for (String t : LocalStations.titles()) {
            assertNotNull("补录台必须有频率（将来要把频率挪到副标题时靠它）",
                    FrequencyTable.of(t));
        }
    }

    /** 补录台的频率必须与官方一致（否则台名与频率对不上，比不显示更糟）。 */
    @Test
    public void local_station_frequencies_match_official() {
        assertEquals("FM99.8", FrequencyTable.of("成都新闻广播"));
        assertEquals("FM88.2", FrequencyTable.of("成都故事广播"));
        assertEquals("FM94.6", FrequencyTable.of("成都文化休闲广播"));
    }

    /**
     * CDN 频道号必须与频率对得上：fm998 ↔ FM99.8。
     *
     * <p>这是两条独立证据链的交叉点：频率来自官网公告，频道号来自 CDN 实测。
     * 如果两者对不上，说明要么频率记错了、要么 CDN 路径理解错了。
     */
    @Test
    public void cdn_channel_matches_frequency() {
        String[][] pairs = {
                {"成都新闻广播", "fm998"},
                {"成都故事广播", "fm882"},
                {"成都文化休闲广播", "fm946"},
        };
        for (String[] p : pairs) {
            String freq = FrequencyTable.of(p[0]);
            assertNotNull(p[0] + " 必须有频率", freq);
            // FM99.8 → "998"，fm998 → "998"
            String fromTable = freq.replace("FM", "").replace(".", "");
            String fromChannel = p[1].replaceFirst("^fm", "");
            assertEquals(p[0] + " 的频率与 CDN 频道号对不上", fromTable, fromChannel);
        }
    }
}
