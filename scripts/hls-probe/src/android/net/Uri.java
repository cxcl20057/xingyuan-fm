package android.net;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 只实现 HlsPlaylistParser 真正用得上的两个方法（parse / resolve），
 * 目的是把 android.jar 的桩顶掉——桩会直接抛 RuntimeException("Stub!")。
 * 这不是通用实现，只服务于离线探针。
 */
public final class Uri {
    private final String s;

    private Uri(String s) { this.s = s; }

    public static Uri parse(String s) {
        if (s == null) throw new NullPointerException("uriString");
        return new Uri(s);
    }

    public Uri resolve(String ref) {
        try {
            URI base = new URI(s);
            // HLS 里的 ref 常常是相对路径 + 查询串，URI.resolve 能正确处理
            return new Uri(base.resolve(ref).toString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public String getScheme() {
        int i = s.indexOf(':');
        return i < 0 ? null : s.substring(0, i);
    }

    public String getHost() {
        try { return new URI(s).getHost(); } catch (URISyntaxException e) { return null; }
    }

    @Override public String toString() { return s; }
    @Override public boolean equals(Object o) { return o instanceof Uri && s.equals(((Uri) o).s); }
    @Override public int hashCode() { return s.hashCode(); }
}
