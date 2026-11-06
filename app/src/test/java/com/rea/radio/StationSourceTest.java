package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 播放地址候选顺序与成都本地源的单测。
 *
 * <p>这里刻意把「联网」和「不联网」的断言分开：
 * <ul>
 *   <li>{@link #chengdu_stations_get_the_local_cdbs_source_last()} 等纯逻辑用例<b>永远跑</b>，
 *       不碰网络，构建机断网也不会红；</li>
 *   <li>{@link #every_candidate_of_chengdu_stations_is_reachable()} 需要联网，
 *       失败时只打印不抛异常——它是给人看的实测报告，不是门禁。</li>
 * </ul>
 * 这样既能锁住「官方源必须排在最后」这条规则，又不会让 CI 因为央广接口抖动而误报。
 */
public class StationSourceTest {

    private static RadioCnApi.Station station(String title, String high, String multi,
                                              String low, String lowHls) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.title = title;
        s.mp3PlayUrlHigh = high;
        s.playUrlMulti = multi;
        s.mp3PlayUrlLow = low;
        s.playUrlLow = lowHls;
        return s;
    }

    @Test
    public void chengdu_stations_get_the_local_cdbs_source_last() {
        RadioCnApi.Station s = station("成都交通文艺广播",
                "http://ytcastmp3.radio.cn/80/stream_11360.mp3?key=a",
                "", "http://ytcastmp3.radio.cn/80/stream_11360.mp3?key=b",
                "http://ytcast.radio.cn/80/radios/11360/index.m3u8?key=c");

        List<String> c = s.candidates();
        assertEquals("央广给的三档 + 官方源，共 4 条", 4, c.size());
        assertEquals("http://livecdn.pull.cdbs.com.cn/live/fm914/playlist.m3u8", c.get(3));
    }

    @Test
    public void cdbs_source_is_only_a_fallback_never_the_first_choice() {
        RadioCnApi.Station s = station("成都新闻广播",
                "http://ytcastmp3.radio.cn/65/stream_22400.mp3?key=a", "", "", "");
        List<String> c = s.candidates();
        assertEquals("第一个必须还是央广的高码率", "http://ytcastmp3.radio.cn/65/stream_22400.mp3?key=a", c.get(0));
        assertEquals("官方源永远垫底", "http://livecdn.pull.cdbs.com.cn/live/fm998/playlist.m3u8", c.get(c.size() - 1));
    }

    @Test
    public void cdbs_source_survives_even_when_radio_cn_gives_nothing() {
        // 央广这条线路整个挂掉时，官方源是唯一能播的
        RadioCnApi.Station s = station("成都经济广播", "", "", "", "");
        List<String> c = s.candidates();
        assertEquals(1, c.size());
        assertEquals("http://livecdn.pull.cdbs.com.cn/live/fm1056/playlist.m3u8", c.get(0));
    }

    @Test
    public void non_chengdu_stations_are_untouched() {
        // 四川省级是 HLS，本来就能放，绝不能给它塞一个成都的源
        RadioCnApi.Station s = station("四川交通广播",
                "http://ytcast.radio.cn/130/radios/220449/index.m3u8?key=a",
                "", "http://ytcast.radio.cn/130/radios/110449/index.m3u8?key=b",
                "http://ytcast.radio.cn/61/radios/10449/index.m3u8?key=c");
        assertNull("不该命中成都表", s.chengduFallback());
        assertEquals("三档原样保留", 3, s.candidates().size());

        RadioCnApi.Station n = station("中国之声", "http://ngcdn001.cnr.cn/live/zgzs/index.m3u8", "", "", "");
        assertNull(n.chengduFallback());
    }

    @Test
    public void all_five_official_channels_are_mapped() {
        String[][] table = {
                {"成都新闻广播", "fm998"},
                {"成都交通文艺广播", "fm914"},
                {"成都经济广播", "fm1056"},
                {"成都故事广播", "fm882"},
                {"成都文化休闲广播", "fm946"},
        };
        for (String[] row : table) {
            RadioCnApi.Station s = station(row[0], "", "", "", "");
            assertEquals(row[0], "http://livecdn.pull.cdbs.com.cn/live/" + row[1] + "/playlist.m3u8",
                    s.chengduFallback());
        }
        // 央广巴里的「成都潮台88.7」不属于成都广电，不该被硬套上一个官方源
        assertNull(station("成都潮台88.7", "", "", "", "").chengduFallback());
    }

    @Test
    public void quality_label_distinguishes_the_official_source() {
        RadioCnApi.Station s = station("成都新闻广播",
                "http://ytcastmp3.radio.cn/65/stream_22400.mp3?key=a", "", "", "");
        assertEquals("高码率", s.qualityOf(s.mp3PlayUrlHigh));
        assertEquals("成都官方源", s.qualityOf("http://livecdn.pull.cdbs.com.cn/live/fm998/playlist.m3u8"));
    }

    // ------------------------------------------------------------ 四川省级：云听空列表的两个台

    /**
     * 城市之音 / 岷江音乐：卫星源<b>不得</b>进入候选（v1.9.8 起）。
     *
     * <p>v1.9.7 曾让它排第一，理由是「云听空、卫星源顶上」。实测双重错误：
     * <ol>
     *   <li>它是死链 —— {@code wxsccszs} 返回 404、{@code wxscmjyyt} 连不上，
     *       社区源站自己标注「近 30 天在线率 0%」；</li>
     *   <li>排第一意味着每次起播先撞它，https 握手超时要干等 15 秒才轮到下一候选。</li>
     * </ol>
     * 所以候选里只留云听自己下发的地址；{@code sichuanFallback()} 仍然保留，
     * 万一哪天恢复，加一行就复活。
     */
    @Test
    public void sichuan_satellite_source_is_not_in_the_candidates() {
        RadioCnApi.Station cs = station("四川城市之音",
                "http://ytcast.radio.cn/130/radios/220450/index_220450.m3u8?key=a",
                "", "http://ytcast.radio.cn/130/radios/110450/index_110450.m3u8?key=b",
                "http://ytcast.radio.cn/62/radios/10450/index_10450.m3u8?key=c");
        List<String> cc = cs.candidates();
        assertEquals("只保留云听下发的三条", 3, cc.size());
        for (String u : cc) {
            assertFalse("卫星源是死链，不能出现在候选里：" + u,
                    u.contains("satellitepull.cnr.cn"));
        }
        assertEquals("高码率仍然排第一",
                "http://ytcast.radio.cn/130/radios/220450/index_220450.m3u8?key=a", cc.get(0));

        RadioCnApi.Station mj = station("四川岷江音乐频率",
                "http://ytcast.radio.cn/130/radios/220447/index_220447.m3u8?key=a",
                "", "http://ytcast.radio.cn/130/radios/110447/index_110447.m3u8?key=b",
                "http://ytcast.radio.cn/62/radios/10447/index_10447.m3u8?key=c");
        for (String u : mj.candidates()) {
            assertFalse(u.contains("satellitepull.cnr.cn"));
        }
        // 方法本身还在，只是不再参与候选
        assertEquals("https://satellitepull.cnr.cn/live/wxscmjyyt/playlist.m3u8",
                mj.sichuanFallback());
    }

    @Test
    public void other_stations_do_not_get_a_sichuan_satellite_source() {
        assertNull(station("四川交通广播", "", "", "", "").sichuanFallback());
        assertNull(station("成都新闻广播", "", "", "", "").sichuanFallback());
        assertNull(station("中国之声", "", "", "", "").sichuanFallback());
        // 命中后第一位就是它，不再额外插入
        RadioCnApi.Station n = station("中国之声",
                "http://ngcdn001.cnr.cn/live/zgzs/index.m3u8", "", "", "");
        assertEquals("http://ngcdn001.cnr.cn/live/zgzs/index.m3u8", n.candidates().get(0));
    }

}
