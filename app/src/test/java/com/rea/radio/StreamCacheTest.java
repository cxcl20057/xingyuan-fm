package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 播放地址缓存与诊断日志：
 *  - 缓存能把「一次性签名地址」原样存取，且不写入任何音频内容；
 *  - 超过过期阈值后不再被当作可用地址（避免拿废地址去连接，白等一次超时）；
 *  - 关键：缓存条目本身不含明文密钥以外的隐私信息，且空 contentId 不落盘；
 *  - 日志环形缓冲不会无限增长。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StreamCacheTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        StreamCache.clear(ctx);
    }

    @Test
    public void put_then_get_roundtrips_all_candidates() {
        RadioCnApi.Station s = station("449", "四川交通广播");
        StreamCache.put(ctx, s);

        StreamCache.Entry e = StreamCache.get(ctx, "449");
        assertNotNull(e);
        assertEquals("应保存全部候选地址", s.candidates().size(), e.urls.size());
        assertEquals("缓存里的第一个必须是最优音质那一路", s.mp3PlayUrlHigh, e.urls.get(0));
        assertTrue("刚存入应算新鲜", e.fresh());
        assertFalse("刚存入不应过期", e.expired());
        assertNotNull(StreamCache.freshUrl(ctx, "449"));
    }

    @Test
    public void legacy_cache_without_order_version_is_ignored() {
        // 模拟 v1.5.x 落下的缓存：有地址、有落盘时间，但没有 "v"（顺序是旧的 multi→low→high）
        String legacy = "{\"u\":[\"http://a/multi.m3u8?key=old\",\"http://a/low.mp3?key=old\"],"
                + "\"t\":" + System.currentTimeMillis() + "}";
        ctx.getSharedPreferences("stream_cache", Context.MODE_PRIVATE)
                .edit().putString("449", legacy).apply();

        assertNull("旧顺序的缓存应判废，否则升级后还在放低码率", StreamCache.get(ctx, "449"));
        assertNull(StreamCache.freshUrl(ctx, "449"));
    }

    @Test
    public void empty_contentId_is_not_cached() {
        RadioCnApi.Station s = station("", "无 ID 电台");
        StreamCache.put(ctx, s);
        assertNull("空 contentId 不应产生缓存条目", StreamCache.get(ctx, ""));
    }

    @Test
    public void station_without_urls_produces_no_entry() {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = "999";
        s.title = "无地址电台";
        StreamCache.put(ctx, s);
        assertNull(StreamCache.get(ctx, "999"));
    }

    @Test
    public void candidates_prefer_best_quality_then_downgrade() {
        RadioCnApi.Station s = station("1", "顺序校验");
        assertEquals(4, s.candidates().size());
        // 音质优先：高码率 → 多码率 → 中码率 → 标准（旧的 multi→low→high 顺序等于主动降质）
        assertTrue(s.candidates().get(0).startsWith("http://a/high.mp3?"));
        assertTrue(s.candidates().get(1).startsWith("http://a/multi.m3u8?"));
        assertTrue(s.candidates().get(2).startsWith("http://a/low.mp3?"));
        assertTrue(s.candidates().get(3).startsWith("http://a/low.m3u8?"));

        // 关键：三个 MP3/HLS 字段经常指向同一个地址（实测四川台就是这样），必须去重
        RadioCnApi.Station dup = new RadioCnApi.Station();
        dup.contentId = "2";
        dup.mp3PlayUrlLow = "http://x/s.mp3?key=1";
        dup.mp3PlayUrlHigh = "http://x/s.mp3?key=1";
        assertEquals("重复地址应去重", 1, dup.candidates().size());
    }

    @Test
    public void qualityOf_labels_the_tier_the_url_came_from() {
        RadioCnApi.Station s = station("3", "档位");
        assertEquals("高码率", s.qualityOf(s.mp3PlayUrlHigh));
        assertEquals("多码率", s.qualityOf(s.playUrlMulti));
        assertEquals("中码率", s.qualityOf(s.mp3PlayUrlLow));
        assertEquals("标准", s.qualityOf(s.playUrlLow));
        assertEquals("未知", s.qualityOf(null));

        // 上一轮会话缓存的地址（key 已变）：按路径特征猜档位
        assertEquals("高码率", s.qualityOf("http://x/live/stream_20749.mp3?key=old"));
        assertEquals("中码率", s.qualityOf("http://x/live/stream_10749.mp3?key=old"));
        assertEquals("高码率HLS", s.qualityOf("http://x/radios/220449/index.m3u8?key=old"));
        assertEquals("标准HLS", s.qualityOf("http://x/radios/10449/index.m3u8?key=old"));
    }

    @Test
    public void appLog_keeps_only_recent_lines() {
        AppLog.clear();
        for (int i = 0; i < 400; i++) AppLog.i("测试", "line " + i);
        int n = AppLog.snapshot().size();
        assertTrue("日志应有上限，实际 " + n, n <= 300);
        assertEquals(300, n);
        assertEquals(399, extractNumber(AppLog.snapshot().get(n - 1).msg));
    }

    @Test
    public void firstGlyph_picks_first_letter_or_digit() {
        assertEquals("四", MainActivity.firstGlyph("四川交通广播"));
        assertEquals("F", MainActivity.firstGlyph("FM 100.9"));
        // 空白/空串/纯符号时回落到 "FM"，取首字母
        assertEquals("F", MainActivity.firstGlyph("   "));
        assertEquals("F", MainActivity.firstGlyph(null));
        assertEquals("F", MainActivity.firstGlyph(""));
        assertEquals("1", MainActivity.firstGlyph("100.9"));
    }

    private static int extractNumber(String s) {
        return Integer.parseInt(s.replaceAll("[^0-9]", ""));
    }

    private static RadioCnApi.Station station(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        s.provinceName = "四川";
        s.provinceCode = 510000;
        s.playUrlMulti = "http://a/multi.m3u8?key=k1&time=t1";
        s.mp3PlayUrlLow = "http://a/low.mp3?key=k2&time=t1";
        s.mp3PlayUrlHigh = "http://a/high.mp3?key=k3&time=t1";
        s.playUrlLow = "http://a/low.m3u8?key=k4&time=t1";
        return s;
    }
}
