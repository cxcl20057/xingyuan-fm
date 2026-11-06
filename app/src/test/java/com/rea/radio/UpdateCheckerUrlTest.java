package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 更新地址的回归测试（v1.9.12）。
 *
 * <h3>为什么专门给「地址」写测试</h3>
 * 这条链路的 bug 不是解析错、也不是下载错，而是<b>仓库名写错了</b>：
 * UpdateChecker 里写死 {@code cxcl20057/xinyuan-radio}（正式版仓库），
 * 而 Carlink 版的 Release 全发在 {@code cxcl20057/xinyuan-radio-carlink}。
 * 匿名访问前者的 {@code /releases} 和 {@code /api/v1/.../releases} 都是 404，
 * 表现就是用户说的「链接根本点不开」。
 *
 * <p>这种错误类型检查器发现不了（两边都是合法字符串），只能靠断言
 * 「地址必须指向本渠道的仓库」把它钉住。改动 {@code app/build.gradle}
 * 里的 {@code UPDATE_REPO} 而忘记对齐 {@code UPDATE_CHANNEL}，这里会红。
 */
public class UpdateCheckerUrlTest {

    /** 仓库全名必须和渠道对得上：carlink 渠道 → 带 -carlink 后缀的仓库。 */
    @Test
    public void repo_matches_channel() {
        boolean carlink = "carlink".equals(BuildConfig.UPDATE_CHANNEL);
        if (carlink) {
            assertTrue("Carlink 渠道必须指向 xinyuan-radio-carlink，实际是 " + UpdateChecker.REPO,
                    UpdateChecker.REPO.endsWith("/xinyuan-radio-carlink"));
        } else {
            assertEquals("正式渠道必须指向 xinyuan-radio",
                    "cxcl20057/xinyuan-radio", UpdateChecker.REPO);
        }
    }

    /** API 与网页地址必须命中<b>同一个</b>仓库，而且各自路径正确。 */
    @Test
    public void api_and_page_point_at_the_same_repo() {
        String api = UpdateChecker.RELEASES_API;
        String page = UpdateChecker.RELEASES_PAGE;
        String repo = UpdateChecker.REPO;

        assertTrue(api, api.startsWith(UpdateChecker.GITEA_BASE + "/api/v1/repos/"));
        assertTrue(api, api.contains("/" + repo + "/releases"));
        assertTrue(api, api.endsWith("?limit=5"));

        assertTrue(page, page.startsWith(UpdateChecker.GITEA_BASE + "/"));
        assertTrue(page, page.contains("/" + repo + "/releases"));
        assertTrue(page, page.endsWith("/releases"));

        // 两个地址里出现的必须是同一个 owner/repo 段
        assertTrue("API 与网页必须同仓：" + api + " / " + page, api.contains(repo) && page.contains(repo));
    }

    /** 旧实现里的正式版仓库不能出现在 Carlink 构建的地址里（这就是那个 404）。 */
    @Test
    public void carlink_build_never_targets_official_repo() {
        if (!"carlink".equals(BuildConfig.UPDATE_CHANNEL)) return;   // 正式构建不管这条
        String api = UpdateChecker.RELEASES_API;
        String page = UpdateChecker.RELEASES_PAGE;
        // 注意：不能简单地 contains("xinyuan-radio") —— 后者是前者的子串
        assertFalse(api, api.contains("/repos/cxcl20057/xinyuan-radio/"));
        assertFalse(page, page.contains("/cxcl20057/xinyuan-radio/"));
    }

    /** 纯函数拼接：换任何仓库名都要拼得出来（不依赖编译期常量）。 */
    @Test
    public void url_builders_are_pure() {
        assertEquals("https://git.cdctu.top:45666/api/v1/repos/owner/repo/releases?limit=5",
                UpdateChecker.releasesApi("owner/repo"));
        assertEquals("https://git.cdctu.top:45666/owner/repo/releases",
                UpdateChecker.releasesPage("owner/repo"));
    }

    /** 兜底 APK 名跟着渠道走，且空 tag 不能拼出「-null」。 */
    @Test
    public void fallback_apk_name_follows_channel() {
        assertEquals("星愿FM-Carlink-v1.9.12.apk", UpdateChecker.fallbackApkName("v1.9.12", "carlink"));
        assertEquals("星愿收音机-v1.9.12.apk", UpdateChecker.fallbackApkName("v1.9.12", "official"));
        // 本渠道的默认重载要和显式传渠道一致
        assertEquals(UpdateChecker.fallbackApkName("v1.9.12", BuildConfig.UPDATE_CHANNEL),
                UpdateChecker.fallbackApkName("v1.9.12"));
        assertTrue(UpdateChecker.fallbackApkName(null, "carlink").endsWith(".apk"));
        assertFalse("空 tag 不能拼出 -null", UpdateChecker.fallbackApkName("  ", "carlink").contains("null"));
    }

    // ------------------------------------------------------------------
    // v2.0：一个 Release 挂两个包（Carlink 自用版 + 正式版）
    //
    // 这是「取第一个 .apk」的老写法最危险的后果：文件顺序一变，Carlink 版
    // 就会下载到正式版那个包。两个包名不同、签名不同，装完桌面上会多出一个
    // 不相干的 App，而用户以为自己只是升了个级。
    // ------------------------------------------------------------------

    /** 两个包都在时，各自渠道只能拿到自己那个。 */
    @Test
    public void each_channel_picks_its_own_apk() {
        String assets = "["
                + "{\"name\":\"星愿FM-Carlink-v2.0.0-20261106.apk\",\"size\":3563726,"
                + "\"browser_download_url\":\"https://x/carlink.apk\"},"
                + "{\"name\":\"星愿FM-正式版-v2.0.0-20261106.apk\",\"size\":3560000,"
                + "\"browser_download_url\":\"https://x/official.apk\"}"
                + "]";

        String carlink = UpdateChecker.pickApkAsset(assets, "carlink");
        String official = UpdateChecker.pickApkAsset(assets, "official");
        assertTrue("Carlink 渠道必须选名字带 Carlink 的包",
                UpdateChecker.str(carlink, "name").contains("Carlink"));
        assertFalse("正式渠道不能选到 Carlink 包",
                UpdateChecker.str(official, "name").contains("Carlink"));
    }

    /** 顺序颠倒也必须各自选中自己那个 —— 上传顺序由人决定，代码不能依赖它。 */
    @Test
    public void order_of_assets_does_not_matter() {
        String assets = "["
                + "{\"name\":\"星愿FM-正式版-v2.0.0.apk\",\"size\":1,"
                + "\"browser_download_url\":\"https://x/official.apk\"},"
                + "{\"name\":\"星愿FM-Carlink-v2.0.0.apk\",\"size\":2,"
                + "\"browser_download_url\":\"https://x/carlink.apk\"}"
                + "]";
        String carlink = UpdateChecker.pickApkAsset(assets, "carlink");
        assertEquals("星愿FM-Carlink-v2.0.0.apk", UpdateChecker.str(carlink, "name"));
        assertEquals("https://x/carlink.apk", UpdateChecker.str(carlink, "browser_download_url"));
    }

    /** 只有一个包（历史版本）：没有精确匹配时退回第一个，别让更新直接失效。 */
    @Test
    public void single_apk_falls_back_to_first() {
        String assets = "[{\"name\":\"星愿FM-Carlink-v2.0.0.apk\",\"size\":2,"
                + "\"browser_download_url\":\"https://x/a.apk\"}]";
        assertNotNull(UpdateChecker.pickApkAsset(assets, "official"));
        assertNotNull(UpdateChecker.pickApkAsset(assets, "carlink"));
    }

    /** 没有 apk 的 assets（只贴了说明的 Release）返回 null，交给兜底路径处理。 */
    @Test
    public void no_apk_asset_returns_null() {
        String assets = "[{\"name\":\"notes.txt\",\"size\":9,"
                + "\"browser_download_url\":\"https://x/n.txt\"}]";
        assertNull(UpdateChecker.pickApkAsset(assets, "carlink"));
        assertNull(UpdateChecker.pickApkAsset(assets, "official"));
    }
}
