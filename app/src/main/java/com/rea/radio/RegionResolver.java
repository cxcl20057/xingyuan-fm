package com.rea.radio;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 通过公网出口 IP 识别所在省份，用于首次启动自动生成当地电台列表。
 *
 * 设计取舍：
 *  - rover 车机没有可靠的 GPS 权限，也不该为“听广播”去申请定位；
 *  - IP 归属地足够用于区分省份（省级粒度），失败时回退到默认省份（四川，用户所在地）。
 * 先试 ip-api（支持 regionName 中文），失败再试 ipip.net 文本接口。
 */
public final class RegionResolver {

    private static final String DEFAULT_PROVINCE = "四川";

    private RegionResolver() {
    }

    public static final class Result {
        public final String provinceName;   // 服务端省份名，如 "四川"
        public final String city;           // 仅用于界面提示，可能为空
        public final boolean fromNetwork;

        Result(String p, String c, boolean n) {
            provinceName = p;
            city = c == null ? "" : c;
            fromNetwork = n;
        }
    }

    public static Result resolve() {
        try {
            String body = http("http://ip-api.com/json/?lang=zh-CN&fields=status,country,regionName,city", 4000);
            JSONObject o = new JSONObject(body);
            if ("success".equals(o.optString("status"))) {
                String p = normalize(o.optString("regionName"));
                if (p != null) return new Result(p, o.optString("city"), true);
            }
        } catch (Throwable ignored) { }

        try {
            String body = http("https://myip.ipip.net", 4000);
            // 形如：当前 IP：1.2.3.4  来自于：中国 四川 成都  电信
            int idx = body.indexOf("来自于：");
            if (idx < 0) idx = body.indexOf("来自于:");
            if (idx >= 0) {
                String rest = body.substring(idx + 4).trim();
                String[] parts = rest.split("\\s+");
                if (parts.length >= 2) {
                    String p = normalize(parts[1]);
                    if (p != null) return new Result(p, parts.length >= 3 ? parts[2] : "", true);
                }
            }
        } catch (Throwable ignored) { }

        return new Result(DEFAULT_PROVINCE, "", false);
    }

    /** 把 "四川省" / "四川省成都市" / "四川 成都" 之类的文本收敛成接口使用的省名。 */
    static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 0) return null;
        for (String suffix : new String[]{"壮族自治区", "回族自治区", "维吾尔自治区", "自治区", "特别行政区", "省", "市"}) {
            int i = s.indexOf(suffix);
            if (i > 0) s = s.substring(0, i);
        }
        for (String p : KNOWN) {
            if (s.equals(p) || s.startsWith(p)) return p;
        }
        // 简称映射（ip 库有时给 "Guangxi" / "Nei Monggol" 等）
        if (s.toLowerCase().contains("sichuan")) return "四川";
        if (s.toLowerCase().contains("chongqing")) return "重庆";
        if (s.toLowerCase().contains("beijing")) return "北京";
        if (s.toLowerCase().contains("guangdong")) return "广东";
        return null;
    }

    /** 与服务端 appProvince/list/all 返回的省名保持一致。 */
    private static final String[] KNOWN = {
            "国家", "安徽", "北京", "重庆", "福建", "甘肃", "广东", "广西", "贵州", "海南",
            "河北", "河南", "黑龙江", "湖北", "湖南", "吉林", "江苏", "江西", "辽宁", "内蒙古",
            "宁夏", "青海", "山东", "山西", "陕西", "上海", "四川", "西藏", "新疆兵团", "新疆",
            "云南", "浙江"
    };

    private static String http(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11)");
        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) { }
            c.disconnect();
        }
        return sb.toString();
    }
}
