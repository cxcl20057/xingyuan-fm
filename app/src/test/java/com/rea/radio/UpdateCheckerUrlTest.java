package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 更新地址的回归测试（v1.9.12 立，v2.2.4 改注入式 URL 后重写）。
 *
 * <h3>为什么专门给「地址」写测试</h3>
 * 这条链路的 bug 不是解析错、也不是下载错，而是<b>仓库/平台写错了</b>：
 * v1.9.12 的问题是仓库名写死成正式版仓库，Carlink 版点「更新」拿到 404；
 * v2.2.4 起正式版迁到 GitHub、私有版留在 Gitea，又多了一个「平台对不对」的坑。
 *
 * <p>这种错误类型检查器发现不了（两边都是合法字符串），只能靠断言
 * 「三条 URL 必须指向本渠道的平台与仓库」把它钉住。改动 {@code app/build.gradle}
 * 里的 {@code UPDATE_API/UPDATE_PAGE/UPDATE_DL_BASE} 而忘记对齐
 * {@code UPDATE_CHANNEL}，这里会红。
 */
public class UpdateCheckerUrlTest {

    private static final String GITEA_HOST = "https://git.cdctu.top:45666";
    private static final String GITHUB_API_HOST = "https://api.github.com/repos/";
    private static final String GITHUB_PAGE_HOST = "https://github.com/";

    /**
     * 正式版（公开）必须整组指向 GitHub，Carlink 版（私有）必须整组指向 Gitea。
     *
     * <p>「全空」放行：公开仓库不带私有渠道的更新源（构建时注入空串），
     * 那种构建的这三条 URL 全为空，不是错误 —— 公开仓库本来就不发 carlink 包。
     */
    @Test
    public void update_urls_point_at_the_right_host_for_channel() {
        String api = UpdateChecker.RELEASES_API;
        String page = UpdateChecker.RELEASES_PAGE;
        String dl = UpdateChecker.DL_BASE;
        boolean allEmpty = api.isEmpty() && page.isEmpty() && dl.isEmpty();

        if ("carlink".equals(BuildConfig.UPDATE_CHANNEL)) {
            assertTrue("Carlink 渠道的更新源要么全空（公开仓库），要么必须走 Gitea：" + api,
                    allEmpty || api.startsWith(GITEA_HOST + "/api/v1/repos/"));
            assertTrue("Carlink 渠道的更新源要么全空（公开仓库），要么必须走 Gitea：" + page,
                    allEmpty || page.startsWith(GITEA_HOST + "/"));
            assertTrue("Carlink 渠道的更新源要么全空（公开仓库），要么必须走 Gitea：" + dl,
                    allEmpty || dl.startsWith(GITEA_HOST + "/"));
        } else {
            assertTrue("正式版 API 必须走 GitHub：" + api, api.startsWith(GITHUB_API_HOST));
            assertTrue("正式版网页必须走 GitHub：" + page, page.startsWith(GITHUB_PAGE_HOST));
            assertTrue("正式版兜底直链必须走 GitHub：" + dl, dl.startsWith(GITHUB_PAGE_HOST));
        }
    }

    /** API 与网页地址必须命中<b>同一个</b>仓库，而且各自路径正确。 */
    @Test
    public void api_and_page_point_at_the_same_repo() {
        String api = UpdateChecker.RELEASES_API;
        String page = UpdateChecker.RELEASES_PAGE;

        // 公开仓库的 carlink 分支注入空串：跳过
        if (api.isEmpty() && page.isEmpty()) {
            assertTrue("全空则兜底前缀也必须为空", UpdateChecker.DL_BASE.isEmpty());
            return;
        }

        // 从两条 URL 里抠出 owner/repo 段，必须一致
        String repoFromApi = extractRepo(api);
        String repoFromPage = extractRepo(page);
        assertNotNull("API 里抠不出 owner/repo：" + api, repoFromApi);
        assertEquals("API 与网页必须同仓：" + api + " / " + page, repoFromApi, repoFromPage);

        // 仓库必须和渠道对得上：carlink → xinyuan-radio-carlink；正式 → xingyuan-fm
        if ("carlink".equals(BuildConfig.UPDATE_CHANNEL)) {
            assertEquals("cxcl20057/xinyuan-radio-carlink", repoFromApi);
        } else {
            assertEquals("cxcl20057/xingyuan-fm", repoFromApi);
        }

        // 各自路径形状
        assertTrue(api, api.endsWith("/releases?limit=5") || api.endsWith("/releases?per_page=5"));
        assertTrue(page, page.endsWith("/releases"));
        assertTrue("兜底前缀必须是 …/releases/download：" + UpdateChecker.DL_BASE,
                UpdateChecker.DL_BASE.endsWith("/releases/download"));
    }

    /** 从「https://host/owner/repo/后续」里抠出 owner/repo（两平台的路径都从第 2 段开始）。 */
    private static String extractRepo(String url) {
        // 去协议与主机
        int scheme = url.indexOf("://");
        if (scheme < 0) return null;
        int hostStart = scheme + 3;
        // GitHub API 是 api.github.com/repos/owner/repo，网页是 github.com/owner/repo，
        // Gitea 网页是 host/owner/repo、API 是 host/api/v1/repos/owner/repo。
        // 统一处理：找到 "repos/" 或 "com/" 之后的第一个 owner/repo 段。
        for (String marker : new String[]{"/repos/", "github.com/", "45666/"}) {
            int i = url.indexOf(marker, hostStart);
            if (i >= 0) {
                String rest = url.substring(i + marker.length());
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    String candidate = rest.substring(0, slash);
                    int slash2 = rest.indexOf('/', slash + 1);
                    if (slash2 > 0 && !candidate.equals("api") && !candidate.equals("releases")) {
                        return candidate + rest.substring(slash, slash2);
                    }
                }
            }
        }
        return null;
    }

    /** 平台不能串门：正式版不连私有 Git，Carlink 版不连 GitHub（公开仓库上没有它的包）。 */
    @Test
    public void channels_never_cross_platforms() {
        String api = UpdateChecker.RELEASES_API;
        String page = UpdateChecker.RELEASES_PAGE;
        String dl = UpdateChecker.DL_BASE;

        if ("carlink".equals(BuildConfig.UPDATE_CHANNEL)) {
            assertFalse("Carlink 构建不得指向 GitHub：" + api, api.contains("github.com"));
            assertFalse("Carlink 构建不得指向 GitHub：" + page, page.contains("github.com"));
            assertFalse("Carlink 构建不得指向 GitHub：" + dl, dl.contains("github.com"));
        } else {
            assertFalse("正式构建不得指向私有 Gitea：" + api, api.contains("git.cdctu.top"));
            assertFalse("正式构建不得指向私有 Gitea：" + page, page.contains("git.cdctu.top"));
            assertFalse("正式构建不得指向私有 Gitea：" + dl, dl.contains("git.cdctu.top"));
        }
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
