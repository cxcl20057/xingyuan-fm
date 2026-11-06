import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import android.net.Uri;
import java.net.URI;

/**
 * 用 media3 1.4.1 真实的 HlsPlaylistParser 解析成都广电的两层播放列表。
 * 目的：验证"换成 ExoPlayer 就能吃下这个源"这个论断，而不是只靠单测自说自话。
 */
public class HlsProbe {

    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "XingyuanRadio/1.7");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
        }
        c.disconnect();
        if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + url);
        return bos.toString("UTF-8");
    }

    static HlsPlaylist parse(HlsPlaylistParser parser, String base, String body) throws Exception {
        return parser.parse(Uri.parse(base),
                new ByteArrayInputStream(body.getBytes("UTF-8")));
    }

    public static void main(String[] args) throws Exception {
        int fails = 0;
        String[] masters = {
                "http://livecdn.pull.cdbs.com.cn/live/fm914/playlist.m3u8",
                "http://livecdn.pull.cdbs.com.cn/live/fm946/playlist.m3u8",
        };
        HlsPlaylistParser parser = new HlsPlaylistParser();

        for (String masterUrl : masters) {
            System.out.println("==== " + masterUrl);
            String body;
            try {
                body = fetch(masterUrl);
            } catch (Exception e) {
                System.out.println("  [失败] 主列表拉取: " + e);
                fails++;
                continue;
            }
            System.out.println("  主列表 " + body.length() + " 字节");

            HlsPlaylist p1;
            try {
                p1 = parse(parser, masterUrl, body);
            } catch (Exception e) {
                System.out.println("  [失败] 主列表解析: " + e); e.printStackTrace(System.out);
                fails++;
                continue;
            }
            System.out.println("  解析为 " + p1.getClass().getSimpleName());
            if (!(p1 instanceof HlsMultivariantPlaylist)) {
                System.out.println("  [失败] 不是多码率列表");
                fails++;
                continue;
            }
            HlsMultivariantPlaylist mv = (HlsMultivariantPlaylist) p1;
            System.out.println("  变体数=" + mv.variants.size());
            if (mv.variants.isEmpty()) {
                System.out.println("  [失败] 没有变体");
                fails++;
                continue;
            }

            for (HlsMultivariantPlaylist.Variant v : mv.variants) {
                System.out.println("  variant bandwidth=" + v.format.bitrate + " -> " + v.url);
                String sub;
                try {
                    sub = fetch(v.url.toString());
                } catch (Exception e) {
                    System.out.println("    [失败] 二级列表拉取: " + e);
                    fails++;
                    continue;
                }
                HlsPlaylist p2;
                try {
                    p2 = parse(parser, v.url.toString(), sub);
                } catch (Exception e) {
                    System.out.println("    [失败] 二级列表解析: " + e);
                    fails++;
                    continue;
                }
                if (!(p2 instanceof HlsMediaPlaylist)) {
                    System.out.println("    [失败] 二级不是媒体列表，实为 " + p2.getClass().getSimpleName());
                    fails++;
                    continue;
                }
                HlsMediaPlaylist mp = (HlsMediaPlaylist) p2;
                System.out.println("    OK 媒体列表 分片数=" + mp.segments.size()
                        + " targetDurationUs=" + mp.targetDurationUs
                        + " 直播=" + !mp.hasEndTag);
                if (!mp.segments.isEmpty()) {
                    HlsMediaPlaylist.Segment s0 = mp.segments.get(0);
                    System.out.println("    首片 " + s0.url);
                    // 再确认分片真能拿到数据，避免"解析过但内容是 404"
                    String segUrl = Uri.parse(v.url.toString()).resolve(s0.url.toString()).toString();
                    System.out.println("    首片绝对地址 " + segUrl);
                    try (InputStream in = open(segUrl)) {
                        byte[] buf = new byte[1880];
                        int n = in.read(buf);
                        System.out.println("    首片首读 " + n + " 字节 同步字节=0x"
                                + Integer.toHexString(buf[0] & 0xFF));
                    } catch (Exception e) {
                        System.out.println("    [失败] 分片下载: " + e);
                        fails++;
                    }
                }
            }
            System.out.println();
        }

        System.out.println(fails == 0 ? "结论：全部通过" : "结论：有 " + fails + " 处失败");
        if (fails != 0) System.exit(1);
    }

    static InputStream open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "XingyuanRadio/1.7");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        return c.getInputStream();
    }
}
