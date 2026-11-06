package com.rea.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 「200 但空 body」的识别测试。
 *
 * <p>对应现象（2026-11-02）：四川城市之音（450）、四川岷江音乐（447）在云听侧
 * 下发的四条地址全部返回 {@code HTTP 200 + Content-Length: 0}。播放器不认为连不上，
 * 只在解析播放列表时报错，用户看到的就是「转两下没声」。
 *
 * <p>这里用本机 socket 精确复现三种响应，锁住 {@link RadioCnApi#probeHasContent} 的判据：
 * <ol>
 *   <li>200 + 空 body + .m3u8 → 判定无内容（这是本 bug 的形态）；</li>
 *   <li>200 + 合法 {@code #EXTM3U} → 判定有内容，不能误杀；</li>
 *   <li>404 → 判定无内容。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@code com.sun.net.httpserver}：</b>那是 JDK 的模块，
 * Android 的 bootclasspath 里没有，单元测试编译期就找不到符号。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StreamProbeTest {

    private static final String CRLF = "\r\n";

    private ServerSocket server;
    private Thread acceptor;
    private int port;
    private final Map<String, String> routes = new HashMap<>();

    private static String response(int code, String reason, String contentType, String body) {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return "HTTP/1.1 " + code + " " + reason + CRLF
                + "Content-Type: " + contentType + CRLF
                + "Content-Length: " + b.length + CRLF
                + "Connection: close" + CRLF + CRLF + (body == null ? "" : body);
    }

    @Before
    public void setUp() throws Exception {
        // 200 + 空 body：和云听线上行为完全一致（Content-Length: 0，一个字节都不写）
        routes.put("/empty/index.m3u8",
                response(200, "OK", "application/octet-stream", null));
        // 正常列表：必须被判为「有内容」
        routes.put("/ok/index.m3u8",
                response(200, "OK", "application/vnd.apple.mpegurl",
                        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n"
                                + "#EXTINF:10,\nsegment1.ts\n"));
        routes.put("/missing/index.m3u8",
                response(404, "Not Found", "text/html", "not found"));

        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!server.isClosed()) {
                    Socket s = null;
                    try {
                        s = server.accept();
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        String line = r.readLine();
                        String h;
                        while ((h = r.readLine()) != null && !h.isEmpty()) {
                            // 丢掉请求头
                        }
                        String path = "/";
                        if (line != null) {
                            String[] parts = line.split(" ");
                            if (parts.length > 1) path = parts[1];
                        }
                        int q = path.indexOf('?');
                        if (q > 0) path = path.substring(0, q);
                        String resp = routes.get(path);
                        if (resp == null) resp = response(404, "Not Found", "text/html", "nf");
                        OutputStream os = s.getOutputStream();
                        os.write(resp.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (Exception ignored) {
                        // 单次连接异常不影响后续用例
                    } finally {
                        if (s != null) {
                            try { s.close(); } catch (Exception ignored) { }
                        }
                    }
                }
            }
        }, "probe-test-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void tearDown() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    public void empty_body_m3u8_is_reported_as_no_content() {
        assertFalse("200 + 空 body 必须判定为没有内容，否则永远不会换源",
                RadioCnApi.probeHasContent(url("/empty/index.m3u8")));
    }

    @Test
    public void valid_m3u8_is_reported_as_having_content() {
        assertTrue("正常的 #EXTM3U 列表不能被误杀",
                RadioCnApi.probeHasContent(url("/ok/index.m3u8")));
    }

    @Test
    public void http_error_is_reported_as_no_content() {
        assertFalse(RadioCnApi.probeHasContent(url("/missing/index.m3u8")));
    }

    @Test
    public void null_or_blank_url_is_not_probed() {
        assertFalse(RadioCnApi.probeHasContent(null));
        assertFalse(RadioCnApi.probeHasContent(""));
    }
}
