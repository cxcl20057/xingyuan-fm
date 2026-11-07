package com.rea.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用内检查更新：查 Release（正式版走 GitHub，Carlink 版走 Gitea）→ 下载 APK → 拉起系统安装器。
 *
 * <h3>为什么走 Release 页面而不是自己搭更新接口</h3>
 * 发布流程已经把每个版本打成 Release（v2.2.4 起正式版发 GitHub、私有版发 Gitea），
 * 里面既有 APK 也有改动说明。<b>Release 本身就是一个更新接口</b>，
 * 没必要再造一套服务端。App 只依赖公开读的 Release API，不需要内置任何令牌。
 *
 * <h3>三条绕不开的约束（都实测过，不是猜的）</h3>
 * <ol>
 *   <li><b>不能静默安装。</b>静默安装要 {@code INSTALL_PACKAGES} 权限，那是系统签名级权限，
 *       普通应用申请了也拿不到。所以只能「下载完拉系统安装器，用户点确认」。
 *       这是所有非商店应用的共同处境，不是本项目的缺陷。</li>
 *   <li><b>targetSdk 28 也要处理「未知来源」。</b>Android 8.0 起按应用逐个授权，
 *       {@link Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES} 需要用户手动打开。
 *       没开就直接拉安装器，会被系统静默拦掉（表现为「点了没反应」），
 *       所以必须先探测再引导。</li>
 *   <li><b>APK 不能放内部私有目录。</b>{@code FileProvider} 要共享出去给安装器读，
 *       放在 {@code getExternalFilesDir} 下配合 FileProvider 的 path 声明即可，
 *       不需要 {@code WRITE_EXTERNAL_STORAGE}（那是 Android 9 及以下的老限制，
 *       而外部私有目录从 API 19 起就免权限）。</li>
 * </ol>
 *
 * <h3>下载地址从哪来</h3>
 * 优先取 Release 里 <b>assets 的 {@code browser_download_url}</b>——这是官方给的直链。
 * 万一某个版本的 assets 为空（比如手工建的 Release 只贴了说明），
 * 退回 {@code <仓库>/releases/download/<tag>/<文件名>} 这个约定路径。
 *
 * <h3>只做「用户点一下才检查」</h3>
 * 按用户选择实现的是「点按钮才检查」，不做启动静默检查。
 * 好处是不会在网络差的时候多一次无谓请求，也不会打扰日常使用。
 */
final class UpdateChecker {

    private static final String TAG = "更新";

    /**
     * 更新源三件套，构建时由 {@code app/build.gradle} 按渠道注入（v2.2.4 起）：
     *
     * <ul>
     *   <li><b>正式版（公开）→ GitHub</b>：{@code cxcl20057/xingyuan-fm}，
     *       Release API 与 Gitea 字段兼容（tag_name / assets[].browser_download_url），
     *       只是 URL 形状不同，所以整条 URL 在 gradle 里拼好。</li>
     *   <li><b>Carlink 版（私有）→ Gitea</b>（私有自建 Git，地址在私有仓库的构建配置里；
     *       公开仓库的该渠道注入空串），和 v2.2.4 之前完全一样。</li>
     * </ul>
     *
     * <p>历史上这里写死 {@code GITEA_BASE} + {@code REPO} 两个常量在代码里拼 URL，
     * v2.2.4 起正式版迁到 GitHub，拼法不再统一，改为三条 URL 全部注入。
     *
     * <p>匿名可读，无需令牌；GitHub 匿名限额 60 次/小时（按 IP），「点按钮才检查」
     * 的用量远够。
     */
    static final String RELEASES_API = BuildConfig.UPDATE_API;

    /** 给人看的 Release 页（「打开下载页」按钮用它）。 */
    static final String RELEASES_PAGE = BuildConfig.UPDATE_PAGE;

    /** assets 为空时兜底直链的前缀（{@code …/releases/download}）。 */
    static final String DL_BASE = BuildConfig.UPDATE_DL_BASE;

    /** 连不上就算了，别让用户等。 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    /** 下载超时给宽一点：3 MB 在车上走热点可能很慢。 */
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 10000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 60000;

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());

    UpdateChecker(Activity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------ 数据结构

    /** 一个可安装的发布版本。 */
    static final class Release {
        String tag;
        String title;
        String notes;
        String apkUrl;
        String apkName;
        long apkSize;

        /** 拼给人看的标题：tag + 说明首行。 */
        String headline() {
            String t = title == null ? "" : title.trim();
            if (t.isEmpty()) return tag;
            // Gitea 的 name 常常是「v1.7.0 — 描述」，已经含 tag，直接用
            return t.startsWith(tag) ? t : tag + " — " + t;
        }
    }

    // ------------------------------------------------------------ 检查

    /** 检查结果回调，都在主线程。 */
    interface CheckCallback {
        void onResult(Release newer, String error);
    }

    /**
     * 查最新 Release 并和本机版本比。
     *
     * @param cb 主线程回调；{@code newer} 为 null 表示已是最新；{@code error} 非空表示查不到
     */
    void check(CheckCallback cb) {
        final String localVersion = localVersionName();
        new Thread(() -> {
            try {
                String json = httpGet(RELEASES_API);
                Release newest = pickNewest(json, localVersion);
                ui.post(() -> cb.onResult(newest, null));
            } catch (Throwable t) {
                AppLog.e(TAG, "检查更新失败：" + t);
                ui.post(() -> cb.onResult(null, describe(t)));
            }
        }, "update-check").start();
    }

    /**
     * 从 Release 列表 JSON 里挑出比本机新的最高版本。
     *
     * <p>不用第三方 JSON 库（项目零依赖是刻意的），这里用正则抠关键字段。
     * 老实说这种解析很脆弱——但它只服务于一个我们自己完全掌控的接口，
     * 且失败时会明确报错而不是装错东西。真要换库，只需改这一个方法。
     */
    static Release pickNewest(String json, String localVersion) {
        Release best = null;
        // 逐个 Release 对象切块：Gitea 返回的是数组，每个元素里可能有嵌套的 assets 数组
        for (String block : splitObjects(json)) {
            if (block.contains("\"draft\":true")) continue;        // 草稿不算
            String tag = str(block, "tag_name");
            if (tag == null || tag.isEmpty()) continue;
            if (!Version.isNewer(tag, localVersion)) continue;     // 不比本机新

            Release r = new Release();
            r.tag = tag;
            r.title = str(block, "name");
            r.notes = str(block, "body");

            // 在 assets 里找 .apk
            String apkBlock = findApkAsset(block);
            if (apkBlock != null) {
                r.apkUrl = str(apkBlock, "browser_download_url");
                r.apkName = str(apkBlock, "name");
                String size = num(apkBlock, "size");
                r.apkSize = size == null ? 0 : parseLong(size);
            }
            if (r.apkUrl == null || r.apkUrl.isEmpty()) {
                // assets 为空：按约定路径兜底（GitHub 与 Gitea 的直链形状一致：
                // <站点>/<owner/repo>/releases/download/<tag>/<文件名>）
                String name = fallbackApkName(tag);
                r.apkUrl = DL_BASE + "/" + tag + "/" + encodePath(name);
                r.apkName = name;
            }

            if (best == null || Version.isNewer(tag, best.tag)) best = r;
        }
        return best;
    }

    /**
     * assets 为空时按约定拼的 APK 文件名。
     *
     * <p>两个渠道的产物名本来就不一样（{@code 星愿FM-Carlink-1.9.12.apk} /
     * {@code 星愿收音机-1.9.12.apk}），兜底名也得跟着渠道走，否则即使在
     * 老版本没上传 asset 的 Release 上也会 404。
     *
     * @param channel {@code "carlink"} 或其它（视为正式版）
     */
    static String fallbackApkName(String tag, String channel) {
        String t = (tag == null || tag.trim().isEmpty()) ? "new" : tag.trim();
        return "carlink".equals(channel)
                ? "星愿FM-Carlink-" + t + ".apk"
                : "星愿收音机-" + t + ".apk";
    }

    /** 按本渠道兜底名。 */
    static String fallbackApkName(String tag) {
        return fallbackApkName(tag, BuildConfig.UPDATE_CHANNEL);
    }

    /**
     * 从 assets 数组里挑本渠道的 apk（纯函数，便于单测）。
     *
     * @param assetsJson assets 数组的 JSON 片段
     * @param channel    {@code "carlink"} 或其它（视为正式版）
     * @return 命中的 asset 对象片段；数组里没有 .apk 时返回 null
     */
    static String pickApkAsset(String assetsJson, String channel) {
        boolean wantCarlink = "carlink".equals(channel);
        String firstApk = null;
        for (String a : splitObjects(assetsJson)) {
            String nm = str(a, "name");
            if (nm == null || !nm.toLowerCase().endsWith(".apk")) continue;
            boolean isCarlink = nm.toLowerCase().contains("carlink");
            if (isCarlink == wantCarlink) return a;   // 精确匹配，优先
            if (firstApk == null) firstApk = a;
        }
        return firstApk;
    }

    /**
     * 从一段 Release JSON 里挑出属于<b>本渠道</b>的 {@code .apk} asset 对象。
     *
     * <p><b>v2.0 起一个 Release 里会挂两个包</b>（Carlink 自用版 + 正式版），
     * 所以不能再「取第一个 .apk」—— 顺序一变，Carlink 版就会下载到正式版那个包，
     * 装完桌面上多出一个不相干的 App，用户完全不知道为什么。
     *
     * <p>规则：文件名里带 {@code Carlink} 的归 Carlink 渠道，其余归正式版；
     * 没有精确匹配时退回第一个 .apk（宁可装错也要让人能升上去，但会记日志）。
     */
    private static String findApkAsset(String block) {
        int idx = block.indexOf("\"assets\":");
        if (idx < 0) return null;
        int arrStart = block.indexOf('[', idx);
        if (arrStart < 0) return null;
        int depth = 0;
        for (int i = arrStart; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    String arr = block.substring(arrStart, i + 1);
                    return pickApkAsset(arr, BuildConfig.UPDATE_CHANNEL);
                }
            }
        }
        return null;
    }

    /**
     * 把 JSON 里所有顶层 {@code {...}} 切出来（考虑字符串内的转义引号）。
     *
     * <p>刻意只返回<b>最外层</b>对象：对 releases 数组来说元素就是最外层，
     * 而每个元素内部的 {@code assets} 元素属于更深的层级，由 {@link #findApkAsset} 单独再切。
     * 如果这里把所有层级都吐出来，{@code pickNewest} 会把 asset 对象也当成一个 Release 去解析。
     */
    private static java.util.List<String> splitObjects(String json) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (json == null) return out;
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    /**
     * 取字符串字段（支持 {@code \"} 与 {@code \n} 转义）。
     *
     * <p><b>为什么不用正则</b>：最直觉的写法是
     * {@code "key"\s*:\s*"((?:\\.|[^"\\])*)"}，但这种"或"分支在长字符串上会
     * <b>指数级回溯</b>——真实响应里 {@code body} 字段有几千字，
     * 一跑就是 {@code StackOverflowError}（本项目实测踩到过，见 UpdateCheckerTest）。
     * 改成手写线性扫描：先定位 key，再逐字符读到未转义的引号为止，全程一遍过。
     */
    static String str(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int k = json.indexOf(needle, from);
            if (k < 0) return null;
            int i = k + needle.length();
            // 跳过空白与冒号
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '\t'
                    || json.charAt(i) == '\n' || json.charAt(i) == '\r')) i++;
            if (i >= json.length() || json.charAt(i) != ':') {
                from = k + needle.length();       // 不是 "key": 的形式，继续找下一个
                continue;
            }
            i++;
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '\t'
                    || json.charAt(i) == '\n' || json.charAt(i) == '\r')) i++;
            if (i >= json.length() || json.charAt(i) != '"') return null;   // 不是字符串值
            i++;
            StringBuilder b = new StringBuilder();
            boolean esc = false;
            for (; i < json.length(); i++) {
                char c = json.charAt(i);
                if (esc) {
                    esc = false;
                    b.append('\\').append(c);     // 交给 unescape 统一处理
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    return unescape(b.toString());
                } else {
                    b.append(c);
                }
            }
            return unescape(b.toString());        // 没闭合的引号，尽力而为
        }
    }

    /** 取数字字段。 */
    static String num(String json, String key) {
        if (json == null || key == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        if (s == null || s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            b.append(n);
                        }
                    } else b.append(n);
                    break;
                default: b.append(n);
            }
        }
        return b.toString();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------ 界面流程

    /**
     * 主入口：按钮点了就走这里。
     *
     * <p>查不到不打扰（一个 Toast 就够），查到新版本弹对话框让用户决定。
     */
    void checkAndPrompt() {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        check((newer, error) -> {
            if (!alive()) return;
            if (error != null) {
                new AlertDialog.Builder(activity)
                        .setTitle("检查更新失败")
                        .setMessage(error + "\n\n可以点「打开下载页」手动下载安装。")
                        .setPositiveButton("打开下载页", (d, w) -> openReleasePage())
                        .setNegativeButton("知道了", null)
                        .show();
                return;
            }
            if (newer == null) {
                new AlertDialog.Builder(activity)
                        .setTitle("已是最新版本")
                        .setMessage("当前版本 v" + localVersionName() + "，没有更新的版本。")
                        .setPositiveButton("好", null)
                        .show();
                return;
            }
            promptInstall(newer);
        });
    }

    private void promptInstall(Release r) {
        String size = r.apkSize > 0
                ? String.format("（%.1f MB）", r.apkSize / 1024.0 / 1024.0) : "";
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";

        String msg = "当前版本：v" + localVersionName() + "\n"
                + "最新版本：" + r.tag + size + "\n\n"
                + (notes.isEmpty() ? "" : "更新内容：\n" + notes);

        new AlertDialog.Builder(activity)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("下载并安装", (d, w) -> startDownload(r))
                .setNegativeButton("以后再说", null)
                .show();
    }

    /** 下载进度对话框；取消按钮要能真正中断下载线程。 */
    private volatile boolean cancelled = false;

    private void startDownload(Release r) {
        if (!alive()) return;
        // 先确认「未知来源」权限，没开就引导，别等下载完才发现装不上
        if (!canInstallPackages()) {
            promptUnknownSource(r);
            return;
        }

        cancelled = false;
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setTitle("正在下载 " + r.tag);
        pd.setMessage("准备中…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.setButton(ProgressDialog.BUTTON_NEGATIVE, "取消", (d, w) -> {
            cancelled = true;
            d.dismiss();
            AppLog.i(TAG, "用户取消下载");
        });
        pd.show();

        new Thread(() -> {
            File out;
            try {
                out = download(r, pd);
            } catch (Throwable t) {
                ui.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    if (!alive()) return;
                    AppLog.e(TAG, "下载失败：" + t);
                    new AlertDialog.Builder(activity)
                            .setTitle("下载失败")
                            .setMessage(describe(t) + "\n\n可以点「打开下载页」手动下载。")
                            .setPositiveButton("重试", (d, w) -> startDownload(r))
                            .setNeutralButton("打开下载页", (d, w) -> openReleasePage())
                            .setNegativeButton("取消", null)
                            .show();
                });
                return;
            }
            final File apk = out;
            ui.post(() -> {
                if (pd.isShowing()) pd.dismiss();
                if (!alive()) return;
                launchInstaller(apk);
            });
        }, "update-download").start();
    }

    /** 未开「未知来源」时的引导：直接跳到本应用的授权页。 */
    private void promptUnknownSource(Release r) {
        new AlertDialog.Builder(activity)
                .setTitle("需要允许安装应用")
                .setMessage("车机/手机需要你允许「星愿收音机」安装应用，才能自动装上新版本。\n\n"
                        + "点「去设置」打开开关后再回来点一次「检查更新」。\n"
                        + "（这是 Android 的安全限制，所有非应用商店的应用都一样。）")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(i);
                    } catch (Exception e) {
                        // 少数定制车机没有这个页面，退回应用详情页
                        try {
                            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(i);
                        } catch (Exception ignored) {
                            Toast.makeText(activity, "请到系统设置里允许本应用安装应用",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 下载 APK 到外部私有目录，返回文件。 */
    private File download(Release r, ProgressDialog pd) throws IOException {
        File dir = apkDir(activity);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录：" + dir);
        }
        // 每次下载前清掉旧的，避免残留半个文件被当成安装包
        File[] olds = dir.listFiles();
        if (olds != null) {
            for (File f : olds) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        File target = new File(dir, safeName(r.apkName, r.tag));

        HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        c.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS);
        c.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "XingyuanRadio/" + localVersionName());
        // Gitea 对 releases/download 会给 302 到实际存储地址
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code + "（" + r.apkUrl + "）");
        }
        final long total = c.getContentLength();
        AppLog.i(TAG, "开始下载 " + r.tag + "，长度 " + total + " 字节");

        long got = 0;
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) {
                    out.close();
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    throw new IOException("已取消");
                }
                out.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    final int pct = (int) (got * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        final long g = got;
                        ui.post(() -> {
                            if (pd.isShowing()) {
                                pd.setProgress(pct);
                                pd.setMessage(String.format("%.1f / %.1f MB",
                                        g / 1024.0 / 1024.0, total / 1024.0 / 1024.0));
                            }
                        });
                    }
                }
            }
            out.flush();
        } finally {
            c.disconnect();
        }

        if (total > 0 && got != total) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("下载不完整：" + got + "/" + total + " 字节");
        }
        AppLog.i(TAG, "下载完成 " + target.getName() + " " + got + " 字节");
        return target;
    }

    /** APK 存放目录（外部私有目录，免存储权限，且能被 FileProvider 共享）。 */
    static File apkDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();   // 极少数没外部存储的设备
        return new File(base, "update");
    }

    /**
     * 文件名兜底，防止路径穿越。
     *
     * <p>顺序很重要：<b>先按分隔符取最后一段，再清洗</b>。
     * 之前写反了——先把 {@code /} 换成 {@code _}，{@code ../../etc/x.apk} 就变成
     * {@code .._.._etc_x.apk}，虽然已经出不了目录，但会留下难看的 {@code .._} 前缀。
     * 正确的做法是先切段、再把这些字符清掉。
     */
    static String safeName(String name, String tag) {
        String n = name == null ? "" : name;
        // ① 先只取最后一段（两种分隔符都算）
        int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (cut >= 0) n = n.substring(cut + 1);
        // ② 再清洗掉可能残留的穿越片段与非法字符
        n = n.replace("..", "_").replace('/', '_').replace('\\', '_').trim();
        // ③ 不是 .apk 结尾（含清洗后变成空串）就用规范名
        if (n.isEmpty() || !n.toLowerCase().endsWith(".apk")) {
            String t = (tag == null || tag.trim().isEmpty()) ? "new" : tag.trim();
            n = "星愿收音机-" + t + ".apk";
        }
        return n;
    }

    /** 拉起系统安装器。 */
    private void launchInstaller(File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ApkProvider.uriFor(activity, apk), ApkProvider.MIME);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            AppLog.i(TAG, "已拉起系统安装器：" + apk.getName());
        } catch (Throwable t) {
            AppLog.e(TAG, "拉起安装器失败：" + t);
            new AlertDialog.Builder(activity)
                    .setTitle("无法启动安装器")
                    .setMessage(describe(t)
                            + "\n\nAPK 已下载到：\n" + apk.getAbsolutePath()
                            + "\n\n可以用文件管理器打开它安装，或点「打开下载页」重新下载。")
                    .setPositiveButton("打开下载页", (d, w) -> openReleasePage())
                    .setNegativeButton("知道了", null)
                    .show();
        }
    }

    /** 是否已允许安装未知来源应用。 */
    private boolean canInstallPackages() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;   // 8.0 之前是全局开关
        try {
            return activity.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return true;   // 查不出来就别挡着用户，装的时候系统会再拦一次
        }
    }

    /**
     * 打开本渠道的 Release 页。
     *
     * <p>v1.9.12 起跟着 {@link #RELEASES_PAGE} 走：Carlink 版打开的是
     * {@code xinyuan-radio-carlink}，不再跳到另一个仓库的 404。
     */
    void openReleasePage() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE));
            activity.startActivity(i);
        } catch (Exception e) {
            // 车机上常有「没装浏览器」的情况：别只弹个 Toast 让人一头雾水，
            // 把地址本身给出来，用户可以用手机浏览器打开。
            new AlertDialog.Builder(activity)
                    .setTitle("没有可用的浏览器")
                    .setMessage("可以用手机或电脑的浏览器打开下面的地址下载：\n\n" + RELEASES_PAGE)
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    // ------------------------------------------------------------ 杂项

    /** 本机版本名；读不到返回 "0"。 */
    String localVersionName() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private boolean alive() {
        return !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private static String describe(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        String name = t.getClass().getSimpleName();
        if (m == null || m.isEmpty()) return name;
        // 网络类异常给句人话，别甩 SocketTimeoutException 给用户
        if (t instanceof java.net.SocketTimeoutException) return "连接超时，网络可能不通";
        if (t instanceof java.net.UnknownHostException) return "连不上服务器（域名解析失败）";
        if (t instanceof javax.net.ssl.SSLException) return "HTTPS 握手失败：" + m;
        return m;
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "XingyuanRadio");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String encodePath(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
