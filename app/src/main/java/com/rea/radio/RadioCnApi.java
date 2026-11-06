package com.rea.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * radio.cn（央广云听）开放接口客户端。
 *
 * 逆向依据：www.radio.cn/pc-portal/js/api.js
 *  - 网关：https://ytmsout.radio.cn
 *  - 固定头：equipmentId=0000, platformCode=WEB, Content-Type=application/json
 *  - 签名：sign = MD5( sortByKey("k=v&k=v") + "&timestamp=" + ts + "&key=" + KEY ).toUpperCase()
 *    无参数时 raw 为空串，签名串为 "timestamp=..&key=.."（注意没有前导 &）
 *  - 单参数接口会把参数拼进路径（本项目用到的两个接口参数均 > 1 个，走 query 即可）
 *
 * 局限：KEY 为前端公开常量，服务端若轮换需同步更新；接口无官方 SLA。
 */
public final class RadioCnApi {

    private static final String KEY = "f0fc4c668392f9f9a447e48584c214ee";
    private static final String BASE = "https://ytmsout.radio.cn";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private RadioCnApi() {
    }

    // ---------------------------------------------------------------- 数据模型

    public static final class Station {
        public String contentId;
        public String title;
        public String subtitle;
        public String image;
        public String playUrlLow;
        public String mp3PlayUrlLow;
        public String mp3PlayUrlHigh;
        public String playUrlMulti;
        public String provinceName;
        /** 所属省份编码，刷新单台地址时用它定位整省列表。 */
        public int provinceCode;

        /**
         * 成都本地备用源（成都广播电视台官方 CDN）。
         *
         * <p>为什么要单独给成都留一手：央广接口虽然给成都的台都发了地址，但那些台走的是
         * {@code ytcastmp3.radio.cn} 上的纯 MP3 长连接（实测 130~261 kbps），
         * 和四川省级/国家台的 HLS 不是同一条线路。实测下来少数网络里只有 HLS 能通、
         * MP3 长连接会一直转圈，表现就是「成都的台全都放不了，省级和国家台正常」。
         *
         * <p>这些地址来自成都广播电视台自建 CDN {@code livecdn.pull.cdbs.com.cn}，
         * 与央广、与云听都无关，所以央广那条线路出问题时它仍然可用。
         * 实测（2026-10）：6 个主播放列表里 5 个返回 200，分片为 MP2T 封装的 MP3、128 kbps。
         *
         * <p>注意它是<b>两层 HLS</b>：主列表里套着一个带 {@code wsSession} 的二级列表，
         * 二级列表里才是 {@code .ts} 分片。ExoPlayer/MediaPlayer 会自己跟进去，无需特殊处理。
         */
        private static final java.util.Map<String, String> CDBS = buildCdbs();

        private static java.util.Map<String, String> buildCdbs() {
            java.util.HashMap<String, String> m = new java.util.HashMap<>();
            // 台名关键字 → 频率（只放实测 200 的，404 的别写进来当死链）
            m.put("成都新闻广播", "fm998");
            m.put("成都交通文艺广播", "fm914");
            m.put("成都经济广播", "fm1056");
            m.put("成都故事广播", "fm882");
            m.put("成都文化休闲广播", "fm946");
            return m;
        }

        /** 这台在成广电官方源里的地址；不是成都的台、或该台没有官方源时返回 null。 */
        public String chengduFallback() {
            if (title == null) return null;
            for (java.util.Map.Entry<String, String> e : CDBS.entrySet()) {
                if (title.contains(e.getKey())) {
                    return "http://livecdn.pull.cdbs.com.cn/live/" + e.getValue() + "/playlist.m3u8";
                }
            }
            return null;
        }

        /**
         * 四川省级台的<b>央广卫星源</b>（{@code satellitepull.cnr.cn}）。
         *
         * <h3>为什么需要这条</h3>
         *
         * <p>实测（2026-11-02）：央广云听给「四川城市之音」（contentId 450）、
         * 「四川岷江音乐频率」（contentId 447）下发的三条地址
         * <b>全部返回 HTTP 200 + 空 body</b>：
         * <pre>
         * http://ytcast.radio.cn/130/radios/220450/index_220450.m3u8   → 200, 0 字节
         * http://ytcast.radio.cn/130/radios/110450/index_110450.m3u8   → 200, 0 字节
         * http://ytcast.radio.cn/62/radios/10450/index_10450.m3u8      → 200, 0 字节
         * </pre>
         * 同省的四川交通广播（449）、四川民族广播（451）、四川综合广播（452）
         * 同一套路径都返回正常的分片列表（339 字节的 {@code #EXTM3U}）——
         * 所以不是网络问题、不是签名问题，而是<b>这两个台在云听侧没有推流</b>。
         *
         * <p>空 body 的 200 是最恶心的一种失败：播放器不认为「连不上」，
         * 只会在解析播放列表时报错，表现就是「点了转两下就没声」。
         * 所以这里给它们换一条<b>同一家电台、不同 CDN</b> 的官方源。
         *
         * <p>地址来源：社区维护的电台源清单（2026-11-01 更新）里这两个台用的就是它们。
         */
        private static final java.util.Map<String, String> SC_SATELLITE = buildScSatellite();

        private static java.util.Map<String, String> buildScSatellite() {
            java.util.HashMap<String, String> m = new java.util.HashMap<>();
            m.put("城市之音", "https://satellitepull.cnr.cn/live/wxsccszs/playlist.m3u8");
            m.put("岷江音乐", "https://satellitepull.cnr.cn/live/wxscmjyyt/playlist.m3u8");
            return m;
        }

        /** 四川省级台的央广卫星源；不匹配时返回 null。 */
        public String sichuanFallback() {
            if (title == null) return null;
            for (java.util.Map.Entry<String, String> e : SC_SATELLITE.entrySet()) {
                if (title.contains(e.getKey())) return e.getValue();
            }
            return null;
        }

        /**
         * 候选地址：<b>音质优先</b>，失败再逐档降级。
         *
         * <p>实测（2026-10，国家台 + 四川/北京/广东/上海，见 {@code scripts/verify_streams.py}）：
         * <ul>
         *   <li>{@code mp3PlayUrlHigh} 是最高档：MP3 直连实测 ≈204 kbps，
         *       分片 HLS 实测 ≈141 kbps；</li>
         *   <li>{@code mp3PlayUrlLow} 中档：MP3 ≈70 kbps，HLS 分片 ≈51 kbps；</li>
         *   <li>{@code playUrlLow} 最低：HLS 分片 ≈48 kbps，而且是 10 秒一片，
         *       起播要等好几个分片，最容易"卡一下再响"；</li>
         *   <li>{@code playUrlMulti} 多数省份为空，有值时与国家台的 high 同址。</li>
         * </ul>
         *
         * <p>旧顺序是 multi → low → high → playUrlLow，等于宁可听 51 kbps 也不用 141 kbps，
         * 所以这里改成「高码率 → 多码率 → 中码率 → 标准」。直连 MP3 排在同档 HLS 前面：
         * 同样是高码率，MP3 是连续流，起播快、抗抖动，HLS 要等分片。
         */
        public List<String> candidates() {
            List<String> out = new ArrayList<>(6);
            // v1.9.8：卫星源不再参与候选。
            //
            // v1.9.7 把它排在<b>第一位</b>（理由是「云听空、卫星源顶上」），实测是个双重错误：
            //   1. 它是死链——`satellitepull.cnr.cn/live/wxsccszs/playlist.m3u8` 返回 404，
            //      `wxscmjyyt` 直接连不上；社区源站自己也标着「近 30 天在线率 0%」；
            //   2. 排第一意味着每次起播都先撞它，https 握手超时要干等 15 秒才轮到下一个候选。
            // 结果就是「点了半天没反应」，比 v1.9.6 更差。见 docs/四川两路电台排查.md。
            //
            // SC_SATELLITE / sichuanFallback() 保留在代码里（万一哪天恢复了，加一行就复活），
            // 但默认不进候选。
            addIf(out, mp3PlayUrlHigh);
            addIf(out, playUrlMulti);
            addIf(out, mp3PlayUrlLow);
            addIf(out, playUrlLow);
            // 官方源排最后：央广地址能放就别绕，放不了它才是救命的那个
            addIf(out, chengduFallback());
            return out;
        }

        /**
         * 这个地址属于哪一档音质（只用于日志与诊断面板显示）。
         *
         * <p>先和本台四个字段逐字比对——同一批请求拿到的地址一定相等；
         * 比对不上说明是上一轮会话缓存的地址（key 已变），再按路径粗判。
         */
        public String qualityOf(String url) {
            if (url == null || url.isEmpty()) return "未知";
            if (url.equals(mp3PlayUrlHigh)) return "高码率";
            if (url.equals(playUrlMulti)) return "多码率";
            if (url.equals(mp3PlayUrlLow)) return "中码率";
            if (url.equals(playUrlLow)) return "标准";
            // 成都官方源：HLS 分片里装的是 128 kbps MP3，单独给个名字，日志里一眼能认出来
            if (url.equals(chengduFallback())) return "成都官方源";
            if (url.equals(sichuanFallback())) return "央广卫星源";
            return guessQuality(url);
        }

        /**
         * 按路径猜档位：实测高/中/低三档只在路径上差一个数字前缀，例如
         * {@code /live/stream_20749.mp3}（高）/ {@code stream_10749.mp3}（中），
         * {@code /radios/220449/index.m3u8}（高）/ {@code /radios/110449/…}（中）。
         */
        private static String guessQuality(String url) {
            String u = url.toLowerCase(java.util.Locale.US);
            boolean hls = u.contains(".m3u8");
            if (u.contains("/stream_2") || u.matches(".*/2\\d{4,5}/.*")) {
                return hls ? "高码率HLS" : "高码率";
            }
            if (u.contains("/stream_1") || u.matches(".*/1[1-9]\\d{3,4}/.*")) {
                return hls ? "中码率HLS" : "中码率";
            }
            return hls ? "标准HLS" : "标准";
        }

        private static void addIf(List<String> list, String v) {
            if (v != null) {
                v = v.trim();
                if (v.length() > 0 && !"null".equals(v) && !list.contains(v)) list.add(v);
            }
        }
    }

    public static final class Province {
        public String name;
        public int code;

        public Province(String name, int code) {
            this.name = name;
            this.code = code;
        }
    }

    // ---------------------------------------------------------------- 业务接口

    /** 省级行政区列表（含 provinceCode=0 的“国家”）。 */
    public static List<Province> fetchProvinces() throws Exception {
        List<Province> out = new ArrayList<>();
        JSONArray arr = getData("/web/appProvince/list/all", null);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Province(o.optString("provinceName"), o.optInt("provinceCode")));
        }
        return out;
    }

    /**
     * 按省份拉取电台列表。
     * <p>注意：该接口返回的每个播放地址都带一次性 key（实测每次请求 key 都变化），
     * 因此必须实时请求，不能缓存成静态列表分发。
     */
    public static List<Station> fetchStations(int provinceCode) throws Exception {
        String[] k = {"categoryId", "provinceCode"};
        String[] v = {"0", String.valueOf(provinceCode)};
        JSONArray arr = getData("/web/appBroadcast/list", pair(k, v));
        List<Station> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Station s = new Station();
            s.contentId = o.optString("contentId");
            s.title = o.optString("title");
            s.subtitle = o.optString("subtitle");
            s.image = o.optString("image");
            s.playUrlLow = optStr(o, "playUrlLow");
            s.mp3PlayUrlLow = optStr(o, "mp3PlayUrlLow");
            s.mp3PlayUrlHigh = optStr(o, "mp3PlayUrlHigh");
            s.playUrlMulti = optStr(o, "playUrlMulti");
            s.provinceCode = provinceCode;
            if (s.title != null && s.title.length() > 0) out.add(s);
        }
        return out;
    }

    /**
     * 单台刷新播放地址（地址过期/403 时用）。
     *
     * <p>没有「按 contentId 取流地址」的公开接口，但整省列表接口一次能拿到全部台的地址，
     * 所以这里拉一次整省、找到同 contentId 的台、把新地址写回 {@code target} 并存入缓存。
     * 副作用是顺带刷新了同省其它台的候选地址，对「持久可用」是好事。
     *
     * <p><b>省份不知道时不要乱猜</b>：旧实现 {@code provinceCode <= 0} 直接回落四川（510000）。
     * 收藏里有北京/广东的台（早先的收藏快照不带省份码），一刷新就去四川列表里找，
     * 永远找不到 → 地址补不上 → 界面已经切了、声音一直不出。
     * 现在的顺序是：
     * <ol>
     *   <li>知道省份 → 拉该省；</li>
     *   <li>不知道省份 → 拉<b>国家级</b>列表（实测 {@code provinceCode=0} 只返回 19 个中央台，
     *       不是全国列表，所以只在这一档里找，不能指望它兜住省级台）；</li>
     *   <li>再不行 → 退到上次浏览过的省份（{@code fallbackProvinceCode}）。</li>
     * </ol>
     *
     * @param target               要刷新的台
     * @param fallbackProvinceCode 该台省份未知时的备选省份（一般是当前浏览的省份），0 表示没有
     * @return true 表示拿到了新地址
     */
    public static boolean refreshStationUrls(Station target, int fallbackProvinceCode)
            throws Exception {
        if (target == null || target.contentId == null) return false;

        // ① 省份已知：直接拉该省
        if (target.provinceCode > 0 && fillFrom(target, fetchStations(target.provinceCode))) {
            return true;
        }
        // ② 省份未知：国家级列表（provinceCode=0）
        if (target.provinceCode <= 0 && fillFrom(target, fetchStations(0))) {
            AppLog.i("取址", target.title + " 从国家级列表补到地址");
            return true;
        }
        // ③ 最后退到调用方给的备选省份（跨省收藏时唯一还能试的地方）
        if (fallbackProvinceCode > 0 && fallbackProvinceCode != target.provinceCode
                && fillFrom(target, fetchStations(fallbackProvinceCode))) {
            AppLog.i("取址", target.title + " 从省份 " + fallbackProvinceCode + " 补到地址");
            return true;
        }
        AppLog.w("取址", (target.title == null ? "?" : target.title)
                + " 刷新后仍无可用地址（省份码 " + target.provinceCode + "）");
        return false;
    }

    /** 兼容旧调用：不带备选省份。 */
    public static boolean refreshStationUrls(Station target) throws Exception {
        return refreshStationUrls(target, 0);
    }

    /** 在 fresh 里找同 contentId 的台，把地址写回 target；找到且有地址才返回 true。 */
    private static boolean fillFrom(Station target, List<Station> fresh) {
        if (fresh == null) return false;
        for (Station s : fresh) {
            if (target.contentId.equals(s.contentId)) {
                target.playUrlMulti = s.playUrlMulti;
                target.mp3PlayUrlLow = s.mp3PlayUrlLow;
                target.mp3PlayUrlHigh = s.mp3PlayUrlHigh;
                target.playUrlLow = s.playUrlLow;
                if (s.provinceCode > 0) target.provinceCode = s.provinceCode;
                return !target.candidates().isEmpty();
            }
        }
        return false;
    }

    private static String optStr(JSONObject o, String key) {
        String v = o.optString(key, "");
        return "null".equals(v) ? "" : v;
    }

    // ---------------------------------------------------------------- 签名 / HTTP

    private static Map<String, String> pair(String[] k, String[] v) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < k.length; i++) m.put(k[i], v[i]);
        return m;
    }

    private static JSONArray getData(String path, Map<String, String> params) throws Exception {
        long ts = System.currentTimeMillis();
        String sign = sign(params, ts);

        StringBuilder url = new StringBuilder(BASE).append(path);
        if (params != null && !params.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) url.append('&');
                first = false;
                url.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            }
        }

        HttpURLConnection c = (HttpURLConnection) new URL(url.toString()).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("equipmentId", "0000");
        c.setRequestProperty("platformCode", "WEB");
        c.setRequestProperty("timestamp", String.valueOf(ts));
        c.setRequestProperty("sign", sign);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");

        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;
        try {
            int code = c.getResponseCode();
            r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) { }
            c.disconnect();
        }

        JSONObject root = new JSONObject(sb.toString());
        int code = root.optInt("code", -1);
        if (code != 0) {
            throw new IllegalStateException("接口返回 code=" + code + " msg=" + root.optString("message"));
        }
        JSONArray data = root.optJSONArray("data");
        return data == null ? new JSONArray() : data;
    }

    static String sign(Map<String, String> params, long ts) {
        StringBuilder raw = new StringBuilder();
        if (params != null && !params.isEmpty()) {
            List<String> keys = new ArrayList<>(params.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                if (raw.length() > 0) raw.append('&');
                raw.append(k).append('=').append(params.get(k));
            }
            raw.append('&');
        }
        raw.append("timestamp=").append(ts).append("&key=").append(KEY);
        return md5(raw.toString()).toUpperCase(Locale.US);
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------- 流地址体检

    /**
     * 判断一个流地址<strong>到底有没有内容</strong>（只回答"有没有音"，不管音质）。
     *
     * <h3>为什么要这个</h3>
     *
     * <p>云听对个别台会返回 <b>HTTP 200 + 空 body</b>：
     * 四川城市之音（450）、四川岷江音乐（447）在 2026-11-02 实测，
     * 四条地址（{@code 130/radios/220450}、{@code 110450}、{@code 62/20450}、{@code 62/10450}）
     * 全是 {@code Content-Length: 0}，而同一时刻同省的四川交通广播（449）同路径返回 338 字节的
     * {@code #EXTM3U}。这不是网络问题、不是签名问题，是电台侧没推流。
     *
     * <p>空 body 的 200 是"最恶心的一种失败"：连接成功、状态码成功，
     * 播放器不认为连不上，只在解析播放列表时报错，用户看到的就是"转两下没声"。
     * 有了这个方法，播放器就能在失败后回头确认"是连不上，还是连上了没东西"，
     * 把后者记进黑名单，下次直接跳过。
     *
     * @return {@code true} 表示读到内容（或无法判断，宁可放过）；{@code false} 表示确认没内容
     */
    public static boolean probeHasContent(String url) {
        if (url == null || url.isEmpty()) return false;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Icy-MetaData", "1");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return false;
            java.io.InputStream in = c.getInputStream();
            byte[] buf = new byte[512];
            int n;
            try {
                n = in.read(buf);
            } finally {
                in.close();
            }
            if (n <= 0) return false;                       // 200 但一个字节都没有
            String head = new String(buf, 0, n, "UTF-8");
            // HLS：合法的主/子列表一定以 #EXTM3U 开头
            if (url.contains(".m3u8")) return head.contains("#EXTM3U");
            return true;                                    // MP3 直连：读到音频字节就算有
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
