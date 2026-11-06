package com.rea.radio;

/**
 * 版本号比较。
 *
 * <h3>为什么不用字符串比大小</h3>
 * {@code "v1.10.0".compareTo("v1.9.0")} 是负数（'1' < '9'），
 * 会把 1.10.0 判成旧版本，属于经典坑。必须按数字段逐个比。
 *
 * <h3>认哪些格式</h3>
 * Gitea 的 tag 是 {@code v1.7.0}；本机的 {@code VersionName} 是 {@code 1.7.0}。
 * 两者都要能解析，所以先去掉前导 {@code v/V}，再按 {@code .} 切段。
 * 段数不同按缺位补 0 处理（{@code 1.7} 等价于 {@code 1.7.0}）。
 *
 * <h3>预发布后缀怎么算</h3>
 * {@code 1.8.0-beta1} 里的 {@code -beta1} 会被剥掉，只比数字部分——
 * 也就是 {@code 1.8.0-beta1} 与 {@code 1.8.0} 视为<b>同一版本</b>。
 * 对本项目够用：发布流程只发正式版，没有预发布渠道。
 * 若将来要发 beta，这里必须改成「预发布 < 正式版」，否则用户装不到 beta 也升不回正式版。
 */
final class Version implements Comparable<Version> {

    private final int[] parts;

    private Version(int[] parts) {
        this.parts = parts;
    }

    /**
     * 解析版本串。
     *
     * @param raw 形如 {@code "v1.7.0"} / {@code "1.7.0"} / {@code "1.8"}
     * @return 解析结果；{@code null} 或空串返回 {@code null}
     */
    static Version parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // 去掉前导 v/V
        if (s.charAt(0) == 'v' || s.charAt(0) == 'V') s = s.substring(1);
        // 剥掉预发布/构建后缀（-beta1 / +build3）
        int cut = s.length();
        int dash = s.indexOf('-');
        int plus = s.indexOf('+');
        if (dash >= 0) cut = Math.min(cut, dash);
        if (plus >= 0) cut = Math.min(cut, plus);
        s = s.substring(0, cut);
        if (s.isEmpty()) return null;

        String[] seg = s.split("\\.");
        int[] p = new int[seg.length];
        for (int i = 0; i < seg.length; i++) {
            // 某一段不是纯数字就当 0，别让整个比较崩掉（比如奇怪的 tag 1.7.x）
            p[i] = toInt(seg[i]);
        }
        return new Version(p);
    }

    private static int toInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return 0;   // 非纯数字，按 0
            // 防溢出：段值不会有 10 位，超了直接夹住
            if (v > 100000000) return Integer.MAX_VALUE;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    @Override
    public int compareTo(Version o) {
        if (o == null) return 1;
        int n = Math.max(parts.length, o.parts.length);
        for (int i = 0; i < n; i++) {
            int a = i < parts.length ? parts[i] : 0;
            int b = i < o.parts.length ? o.parts[i] : 0;
            if (a != b) return a < b ? -1 : 1;
        }
        return 0;
    }

    /** 是否有更新版本可用（即 {@code remote} 比 {@code local} 新）。 */
    static boolean isNewer(String remote, String local) {
        Version r = parse(remote);
        Version l = parse(local);
        if (r == null) return false;
        if (l == null) return true;   // 本机版本号读不出来，宁可提示更新
        return r.compareTo(l) > 0;
    }
}
