package com.rea.radio;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 打真实接口的探测（不是常规单测）。
 *
 * <p><b>默认执行</b>，但网络不通时自动跳过（{@code Assume}）——发布脚本会跑全量测试，
 * 「检查更新」这条链路的解析逻辑应当每次都对着真实响应验一遍，而不是只对
 * 事先抓好的 {@code gitea_releases.json} 样本成立。
 *
 * <p>只有两种情况会跳过：连不上 Gitea（离线/证书过期），或者显式设了
 * {@code LIVE_UPDATE=0}。
 */
public class LiveReleaseProbeTest {

    @Test
    public void real_gitea_response_is_parsed_into_a_release() throws Exception {
        // 用环境变量控制而不是 -D：Gradle 的 -D 给的是 daemon 进程，
        // 实测不会传到 fork 出来的测试 JVM，只会让这条用例永远"跳过"。
        Assume.assumeFalse("显式关闭了真实网络探测（LIVE_UPDATE=0）",
                "0".equals(System.getenv("LIVE_UPDATE")));

        String json;
        try {
            json = fetch(UpdateChecker.RELEASES_API);
        } catch (Exception e) {
            // 离线、或者仓库证书过期（2026-11-24）都会走到这里。
            // 这不是代码缺陷，是环境问题，跳过并留下原因，别让发布被卡住。
            Assume.assumeNoException("连不上 Gitea，跳过真实网络探测", e);
            return;
        }
        System.out.println("[探测] 响应长度 " + json.length() + " 字节");

        UpdateChecker.Release r = UpdateChecker.pickNewest(json, "1.0.0");
        org.junit.Assert.assertNotNull("本机 1.0.0，应当能发现新版本", r);
        System.out.println("[探测] 最新 = " + r.tag + "  apkSize=" + r.apkSize);
        System.out.println("[探测] 直链 = " + r.apkUrl);
        System.out.println("[探测] 文件名 = " + r.apkName);

        org.junit.Assert.assertTrue("tag 应形如 v1.x.y", r.tag.matches("v?\\d+\\.\\d+.*"));
        org.junit.Assert.assertTrue("直链应是 .apk", r.apkUrl.toLowerCase().contains(".apk"));

        // 直链必须真能下到东西（只取前 1KB，不下整个包）
        int head = headBytes(r.apkUrl, 1024);
        System.out.println("[探测] 直链首读 " + head + " 字节");
        org.junit.Assert.assertTrue("直链应可下载", head > 0);

        // 已是最新时不该提示
        org.junit.Assert.assertNull("拿最新 tag 当本机版本时不该再有更新",
                UpdateChecker.pickNewest(json, r.tag));
    }

    private static int headBytes(String url, int n) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "XingyuanRadio-probe");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[n];
            int read = 0, r;
            while (read < n && (r = in.read(buf, read, n - read)) > 0) read += r;
            return read;
        } finally {
            c.disconnect();
        }
    }

    private static String fetch(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestProperty("User-Agent", "XingyuanRadio-probe");
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
