package com.rea.radio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放地址持久缓存。
 *
 * <p><b>为什么需要它（实测结论）</b>：
 * radio.cn 返回的播放地址形如
 * {@code http://ytcastmp3.radio.cn/72/stream_10749.mp3?type=1&key=<32hex>&time=<hex 时间戳>}，
 * 其中 {@code time} 是「签发时刻」的十六进制 Unix 秒，{@code key} 是与 path+time 绑定的摘要。
 * 实测：
 * <ul>
 *   <li>同一秒内多次请求，key 完全不变 → key 是「时间片」派生的，不是一次性随机串；</li>
 *   <li>把 time 改成别的时刻（哪怕只差 1 秒）立刻 403 → 服务端严格校验 key/path/time 三元组；</li>
 *   <li><b>原样保存的地址在 12 分钟后仍可正常拉流</b>（HTTP 200/206）。</li>
 * </ul>
 * 所以地址「不能长期写死、但可以持久缓存复用」。本类即把这层缓存落地：
 * 优先用缓存秒开 → 403/连不上时只刷新这一个台 → 后台整省预取。
 *
 * <p>缓存只存地址与时间戳，不存任何音频内容。
 */
final class StreamCache {

    private static final String PREF = "stream_cache";
    /** 缓存放多久还算「新鲜」：优先直接用，不再等网络。 */
    private static final long FRESH_MS = 30 * 60 * 1000L;
    /** 超过这个时长直接判废，连试都不试，省一次超时等待。 */
    private static final long EXPIRE_MS = 6 * 60 * 60 * 1000L;
    /**
     * 候选顺序的版本号。
     *
     * <p>v1.6.0 把候选顺序从「multi → low → high」改成「high → multi → low」，
     * 而缓存里存的是<b>排好序</b>的地址列表。升级后如果继续用旧缓存，
     * 第一项仍然是低码率地址，就等于没升级。所以老缓存一律判废重取。
     */
    private static final int ORDER_VERSION = 2;

    private StreamCache() {
    }

    static final class Entry {
        final String contentId;
        final List<String> urls;
        final long fetchedAt;

        Entry(String contentId, List<String> urls, long fetchedAt) {
            this.contentId = contentId;
            this.urls = urls;
            this.fetchedAt = fetchedAt;
        }

        boolean fresh() {
            return !urls.isEmpty() && System.currentTimeMillis() - fetchedAt < FRESH_MS;
        }

        boolean expired() {
            return urls.isEmpty() || System.currentTimeMillis() - fetchedAt > EXPIRE_MS;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static void put(Context c, RadioCnApi.Station s) {
        if (s == null || s.contentId == null || s.contentId.length() == 0) return;
        List<String> urls = s.candidates();
        if (urls.isEmpty()) return;
        JSONArray arr = new JSONArray();
        for (String u : urls) arr.put(u);
        JSONObject o = new JSONObject();
        try {
            o.put("u", arr);
            o.put("t", System.currentTimeMillis());
            o.put("v", ORDER_VERSION);
        } catch (Exception ignored) {
            return;
        }
        sp(c).edit().putString(s.contentId, o.toString()).apply();
    }

    static Entry get(Context c, String contentId) {
        if (contentId == null || contentId.length() == 0) return null;
        String raw = sp(c).getString(contentId, null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            // 老版本（v1.5.x 及更早）写的缓存没有 "v" 字段，其顺序是旧优先级，
            // 直接当没有缓存处理：宁可多一次取址，也别一升级还在放低码率。
            if (o.optInt("v", 1) != ORDER_VERSION) return null;
            JSONArray arr = o.optJSONArray("u");
            List<String> urls = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String u = arr.optString(i, "");
                    if (u.length() > 0) urls.add(u);
                }
            }
            return new Entry(contentId, urls, o.optLong("t", 0));
        } catch (Exception e) {
            return null;
        }
    }

    /** 取一个「还没过期」的缓存地址作为首选；没有则返回 null。 */
    static String freshUrl(Context c, String contentId) {
        Entry e = get(c, contentId);
        if (e == null || e.expired()) return null;
        return e.urls.get(0);
    }

    static void clear(Context c) {
        sp(c).edit().clear().apply();
    }
}
