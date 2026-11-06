package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 更新功能的解析层。
 *
 * <p>这里<b>不</b>去请求网络——用一份从真实 Gitea 抓下来的响应
 * （{@code app/src/test/resources/gitea_releases.json}）当固定样本。
 * 好处是测试可离线重跑，且解析面对的是真实字段布局（含嵌套 assets、转义字符、多版本）。
 * 坏处是样本会过时：只要字段结构没变，过时不影响这些用例的结论。
 */
public class UpdateCheckerTest {

    /** 单个反斜杠，避免在字面量里写出退格符。 */
    private static final String BS = String.valueOf((char) 0x5C);

    private static String realJson() throws Exception {
        try (InputStream in = UpdateCheckerTest.class.getClassLoader()
                .getResourceAsStream("gitea_releases.json")) {
            assertNotNull("测试资源 gitea_releases.json 丢了", in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 用真实响应：本机 1.6.0 时应挑出 v1.7.0 及其 APK 直链。 */
    @Test
    public void picks_newest_release_with_apk_from_real_response() throws Exception {
        UpdateChecker.Release r = UpdateChecker.pickNewest(realJson(), "1.6.0");
        assertNotNull("应当发现新版本", r);
        assertEquals("v1.7.0", r.tag);
        assertNotNull("必须拿到 APK 直链", r.apkUrl);
        assertTrue("直链应是 https", r.apkUrl.startsWith("https://"));
        assertTrue("直链应指向 .apk", r.apkUrl.toLowerCase().contains(".apk"));
        assertEquals("apk 大小应被解析出来", 3051580L, r.apkSize);
        assertNotNull("应带上台名", r.apkName);
        assertTrue("文件名应以 .apk 结尾", r.apkName.endsWith(".apk"));
    }

    /** 本机就是最新时不该提示。 */
    @Test
    public void already_latest_returns_null() throws Exception {
        assertNull("本机 1.7.0，远端最高也是 1.7.0，不该提示更新",
                UpdateChecker.pickNewest(realJson(), "1.7.0"));
        assertNull(UpdateChecker.pickNewest(realJson(), "1.9.9"));
    }

    /** 必须挑最高的，不是数组里第一个看起来合适的。 */
    @Test
    public void picks_highest_among_many() throws Exception {
        String json = "[" + release("v1.6.0", 100) + "," + release("v1.10.0", 300)
                + "," + release("v1.9.0", 200) + "]";
        UpdateChecker.Release r = UpdateChecker.pickNewest(json, "1.5.0");
        assertNotNull(r);
        assertEquals("1.10.0 比 1.9.0 新，别被字符串序骗了", "v1.10.0", r.tag);
    }

    /** 草稿版不能推给用户。 */
    @Test
    public void draft_is_skipped() {
        String json = "[" + releaseWithFlags("v9.9.9", 1, true, false) + ","
                + release("v1.8.0", 100) + "]";
        UpdateChecker.Release r = UpdateChecker.pickNewest(json, "1.7.0");
        assertNotNull(r);
        assertEquals("草稿版不该被选中", "v1.8.0", r.tag);
    }

    /** assets 为空时退回约定下载路径，别让用户点了个 404。 */
    @Test
    public void falls_back_to_convention_url_when_no_assets() {
        String json = "[{\"tag_name\":\"v1.9.0\",\"name\":\"v1.9.0\",\"body\":\"x\","
                + "\"draft\":false,\"prerelease\":false,\"assets\":[]}]";
        UpdateChecker.Release r = UpdateChecker.pickNewest(json, "1.7.0");
        assertNotNull(r);
        assertTrue("应拼出 releases/download 约定路径，实际：" + r.apkUrl,
                r.apkUrl.contains("/releases/download/v1.9.0/"));
        assertTrue(r.apkUrl.toLowerCase().endsWith(".apk"));
    }

    /** 说明正文里的换行转义要还原，否则对话框里是一坨 \n。 */
    @Test
    public void body_escapes_are_unescaped() {
        String json = "[{\"tag_name\":\"v1.9.0\",\"name\":\"v1.9.0\","
                + "\"body\":\"第一行\\n第二行\\t制表\\\"引号\\\"\","
                + "\"draft\":false,\"assets\":[]}]";
        UpdateChecker.Release r = UpdateChecker.pickNewest(json, "1.7.0");
        assertNotNull(r);
        assertTrue("\\n 应变成真换行", r.notes.contains("\n"));
        assertTrue("引号应还原", r.notes.contains("\"引号\""));
        assertFalse("不该残留字面 \\n", r.notes.contains("\\n"));
    }

    /** 空数组/坏 JSON 不能崩，只返回 null。 */
    @Test
    public void empty_or_broken_json_is_safe() {
        assertNull(UpdateChecker.pickNewest("[]", "1.0.0"));
        assertNull(UpdateChecker.pickNewest("", "1.0.0"));
        assertNull(UpdateChecker.pickNewest("not json at all", "1.0.0"));
        assertNull(UpdateChecker.pickNewest(null, "1.0.0"));
    }

    /** headline 不该把 tag 重复两遍。 */
    @Test
    public void headline_does_not_duplicate_tag() {
        UpdateChecker.Release r = new UpdateChecker.Release();
        r.tag = "v1.8.0";
        r.title = "v1.8.0 — 加了什么什么";
        assertEquals("v1.8.0 — 加了什么什么", r.headline());

        UpdateChecker.Release r2 = new UpdateChecker.Release();
        r2.tag = "v1.8.0";
        r2.title = "加了什么什么";
        assertEquals("v1.8.0 — 加了什么什么", r2.headline());
    }

    /**
     * 文件名要能挡住路径穿越。
     *
     * <p>判据不是"字符串长什么样"，而是<b>拼出来的路径必须还在下载目录里</b>——
     * 这才是真正要防的事。所以这里既断言清洗结果，也断言最终路径的规范性。
     */
    @Test
    public void apk_filename_is_sanitized() {
        // 穿越片段被摘掉，只保留真正的文件名
        assertEquals("x.apk", UpdateChecker.safeName("../../etc/x.apk", "v1"));
        // 反斜杠是 Windows 的目录分隔符，`a\b.apk` 的"最后一段"是 b.apk。
        // 期望是 b.apk 而不是 a_b.apk —— 路径语义上它是 a 目录下的 b.apk。
        assertEquals("b.apk", UpdateChecker.safeName("a" + BS + "b.apk", "v1"));
        // 不是 .apk 结尾就换成规范名
        assertTrue(UpdateChecker.safeName("evil.exe", "v1.8.0").endsWith("v1.8.0.apk"));
        assertTrue(UpdateChecker.safeName(null, "v1.8.0").endsWith("v1.8.0.apk"));
    }

    /** 关键约束：清洗后的名字拼上目录，必须仍然落在该目录下（不能有 .. 逃逸）。 */
    @Test
    public void sanitized_name_cannot_escape_directory() {
        String[] nasty = {
                "../../etc/passwd.apk",
                "..\\..\\windows\\x.apk",
                "/data/data/com.rea.radio/x.apk",
                "....//....//x.apk",
        };
        for (String n : nasty) {
            String safe = UpdateChecker.safeName(n, "v1");
            assertFalse("不能含路径分隔符：" + safe, safe.contains("/") || safe.contains("\\"));
            assertFalse("不能含 .. 逃逸片段：" + safe, safe.contains(".."));
            assertTrue("必须是 .apk：" + safe, safe.toLowerCase().endsWith(".apk"));
        }
    }

    // ------------------------------------------------------------ 构造样本

    private static String release(String tag, int size) {
        return releaseWithFlags(tag, size, false, false);
    }

    private static String releaseWithFlags(String tag, int size, boolean draft, boolean pre) {
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"" + tag + "\","
                + "\"body\":\"说明 " + tag + "\","
                + "\"draft\":" + draft + ",\"prerelease\":" + pre + ","
                + "\"assets\":[{\"name\":\"星愿收音机-" + tag + ".apk\",\"size\":" + size + ","
                + "\"browser_download_url\":\"https://example.com/dl/" + tag + "/app.apk\"}]}";
    }
}
