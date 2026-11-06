package com.rea.radio;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 手机 / 车机两种版面的运行期适配。
 *
 * <p>为什么要分两套布局而不是一套自适应：
 * <ul>
 *   <li>车机是宽扁横屏（2x 车机 960×540dp、实测 1x 车机 1920×968dp），
 *       天然适合「左控制 + 右列表」两栏；</li>
 *   <li>手机竖屏只有 360~430dp 宽，两栏会把 28sp 的台名压到只剩两三个字。
 *       硬套自适应会得到「哪边都不好用」的结果，所以直接换版面。</li>
 * </ul>
 *
 * <p><b>两件事必须分开看</b>，混在一起是常见错误：
 * <ol>
 *   <li>{@link #useTwoColumn} —— <b>用哪个布局文件</b>。
 *       对应资源限定符 {@code layout-h480dp-land/}（车机两栏、28sp 车规字号）。</li>
 *   <li>{@link #compactMetrics} —— <b>尺寸要不要按手机收紧</b>。手机无论横竖都要收紧。</li>
 * </ol>
 *
 * <p><b>v1.9.5 起版面按「窗口可用尺寸」分，不再按「横竖屏」分</b>：
 * 原来 {@code layout/} 装的是车机两栏版、{@code layout-port/} 装的是手机版，
 * 于是<b>所有横屏窗口都吃 28sp 大字号</b>——手机横屏 780×360dp、Carlink 1.6 的
 * 默认小窗（800×450dp 量级）都在此列，文字溢出、行高吃掉整屏。
 * 改成 {@code layout-h480dp-land} 后：可用高度 ≥ 480dp 且横屏才是车机版面，
 * 其余一律单列。依据见 Google《构建车载自适应应用》：适配判据应当看
 * <b>窗口尺寸类别</b>（window size class），而不是设备类型或方向，
 * 宽屏还要用「列表-详情」多窗格把宽度用起来——本应用的两栏就是这种多窗格。
 */
final class PhoneLayout {

    /** Android 官方 tablet/large 断点，也是 Material 的 compact / medium 分界。 */
    static final int PHONE_MAX_SHORT_EDGE_DP = 600;

    /**
     * 两栏布局所需的最小<b>可用高度</b>（v1.9.5 起）。
     *
     * <p>这个值必须与资源目录限定符 {@code layout-h480dp-land/} 里的 480 完全一致——
     * 布局文件由系统按限定符选，代码只是复述同一个判据，两边一旦漂移就会出现
     * 「加载了两栏布局、代码却认为不在两栏」，或者反过来。
     *
     * <p>为什么卡高度而不是卡短边：两栏版左栏要竖着塞下
     * 台标 132 + 台名 2×40 + 直播 19 + 时段 17 + 副标题 16 + 时间轴 30 + 状态 18
     * + 按钮 92 + 各种间距 ≈ 480dp，<b>高度才是硬约束</b>，宽度总是够的
     * （车机 960dp、投屏窗口 1422dp）。
     *
     * <p>480 这个数的取值理由（实测多种运行环境）：
     * <ul>
     *   <li>车机直装：2x 车机是 960×540dp，实测 1x 车机（160dpi）是 1920×968dp，
     *       都远大于 480，稳稳命中两栏；</li>
     *   <li>Carlink 投屏虚拟屏 3200×1600 密度 2.25 → 1422×711dp，稳过；</li>
     *   <li>手机横屏 780×360dp、Carlink 1.6 小窗 800×450dp 都落在 480 以下，
     *       正好被挡在两栏之外。</li>
     * </ul>
     */
    static final int TWO_COLUMN_MIN_HEIGHT_DP = 480;

    /** 手机版滑动换台的最小距离：48dp ≈ 360dp 屏宽的 1/8，拇指轻扫即可。 */
    static final float PHONE_SWIPE_DP = 48f;

    private PhoneLayout() {
    }

    /**
     * 是否用两栏布局（{@code layout-h480dp-land/activity_main.xml}）。
     *
     * <p>判据必须与资源限定符逐字对应：{@code h480dp} = 可用高度 ≥ 480dp，
     * {@code land} = 宽 ≥ 高。两个条件同时成立，系统才会加载两栏那套 XML。
     *
     * @param widthDp  当前可用宽度 dp
     * @param heightDp 当前可用高度 dp
     */
    static boolean useTwoColumn(int widthDp, int heightDp) {
        return widthDp >= heightDp && heightDp >= TWO_COLUMN_MIN_HEIGHT_DP;
    }

    /**
     * 是否按手机尺寸收紧（行高变矮、滑动阈值变轻）。
     *
     * <p>判据是「不是两栏版面，且不是平板」：两栏版面本身就是车机字号，
     * 不能再收紧；平板（短边 ≥ 600dp 且竖着）沿用布局文件里的手机字号即可，
     * 也不收紧。剩下的——手机竖屏、手机横屏、Carlink 小窗——一律收紧。
     *
     * <p>手机横屏（780×360dp）是宽扁的却仍是单列：高度只有 360dp，
     * 两栏版左栏要 480dp 才排得下，所以只能走单列 + 紧凑尺寸。
     */
    static boolean compactMetrics(int widthDp, int heightDp) {
        if (useTwoColumn(widthDp, heightDp)) return false;      // 车机/大屏横屏：保持车规字号
        if (Math.min(widthDp, heightDp) >= PHONE_MAX_SHORT_EDGE_DP) return false; // 平板：不收紧
        return true;                                             // 手机竖屏/横屏、Carlink 小窗
    }

    /** 由 Configuration 取当前可用 dp（已排除状态栏/导航栏）。 */
    static int[] sizeDp(Configuration c, DisplayMetrics dm) {
        int w = (int) (c.screenWidthDp > 0 ? c.screenWidthDp
                : dm.widthPixels / Math.max(1f, dm.density));
        int h = (int) (c.screenHeightDp > 0 ? c.screenHeightDp
                : dm.heightPixels / Math.max(1f, dm.density));
        return new int[]{w, h};
    }

    /**
     * 手机台标直径：按屏幕最短边取，夹在 110~160dp。
     *
     * <p>360dp 宽的小屏手机得 119dp，430dp 的大屏得 142dp，折叠屏展开也就到上限 160dp——
     * 保证播放卡在小屏上塞得进下半屏，在大屏上也不显得空旷。
     *
     * <p><b>注意这是「大播放卡」版面的尺寸</b>。v1.8.3 起手机改用底部迷你播放条，
     * 台标固定 44dp，<b>不能再套这个值</b>，否则会把播放条撑成一个方块。
     * 见 {@link #miniLogoSizeDp()}。
     */
    static int logoSizeDp(int widthDp, int heightDp) {
        int shortEdge = Math.min(widthDp, heightDp);
        int size = Math.round(shortEdge * 0.33f);
        return Math.max(110, Math.min(160, size));
    }

    /**
     * 底部迷你播放条里的台标直径（v1.8.3）。
     *
     * <p>固定 44dp 不随屏幕变：播放条里台标只承担「认得出是哪台」，
     * 详细信息（台名、副标题、频率）都在旁边文字上。屏幕上放大它只会挤掉文字。
     */
    static int miniLogoSizeDp() {
        return 44;
    }

    /**
     * 当前这套手机版面是不是「底部迷你播放条」版（v1.8.3）。
     *
     * <p>判据用<b>布局文件里声明的初始宽度</b>：迷你条的台标在 XML 里就是 44dp，
     * 旧的大播放卡版是 132dp。比对 dp 值比引入一个额外的 id 省事，
     * 也不会因为换了布局文件而失效。
     *
     * <p>抽成方法是为了让 {@link #apply} 和排查日志用<b>同一个判据</b>——
     * 两处各写一遍迟早会漂移，然后日志就开始骗人。
     */
    static boolean isMiniBar(View logoWrap, float density) {
        if (logoWrap == null) return false;
        ViewGroup.LayoutParams lp = logoWrap.getLayoutParams();
        if (lp == null) return false;
        return Math.round(lp.width / Math.max(0.01f, density)) <= miniLogoSizeDp();
    }

    /** 实际会落到的台标直径 dp（供日志/排查用，必须与 {@link #apply} 的取值一致）。 */
    static int effectiveLogoSizeDp(Activity a, View logoWrap, int widthDp, int heightDp) {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        return isMiniBar(logoWrap, dm.density)
                ? miniLogoSizeDp()
                : logoSizeDp(widthDp, heightDp);
    }

    /**
     * 列表行高（手机）。
     *
     * <p>v1.8.3 起手机列表吃满「顶栏 + 标签 + 迷你播放条」之外的全部高度，
     * 竖屏 360×640dp 上列表区约 400dp、430×900dp 上约 640dp ——
     * 早已不是旧版那种只有 150~250dp 的窘境，所以原来「列表 < 220dp 就压到 56dp」
     * 的判据失效了。
     *
     * <p>现在的算法反过来：<b>默认 56dp</b>（多露行数，一屏 7~8 行），
     * 只有真正的大屏（短边 ≥ 420dp）才放到 64dp 让触控更从容。
     * 56dp 仍高于 Material 的 48dp 最小可点区域，单手点得到。
     */
    static int rowHeightDp(int widthDp, int heightDp) {
        return Math.min(widthDp, heightDp) >= 420 ? 64 : 56;
    }

    // ------------------------------------------------------------ 应用

    /**
     * 把版面参数真正落到视图上。
     *
     * <p>只调整「手机上必须变」的三处：台标直径、字标字号、列表行高。
     * 其余交给布局文件里的 dp/sp，避免运行期到处 setTextSize 难以维护。
     *
     * @param compact 是否按手机尺寸收紧（{@link #compactMetrics}）；
     *                车机/平板横屏传 false，直接保持布局文件里的原值
     */
    static void apply(Activity a, boolean compact, View logoWrap, TextView logoText) {
        if (!compact) return;
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        Configuration c = a.getResources().getConfiguration();
        int[] wh = sizeDp(c, dm);

        // v1.8.3：手机改用底部迷你播放条后，台标固定小尺寸。
        // 判据统一走 isMiniBar，别在这里另写一套。
        boolean miniBar = isMiniBar(logoWrap, dm.density);

        if (logoWrap != null) {
            int sizeDp = miniBar ? miniLogoSizeDp() : logoSizeDp(wh[0], wh[1]);
            int size = Math.round(sizeDp * dm.density);
            ViewGroup.LayoutParams lp = logoWrap.getLayoutParams();
            if (lp != null && (lp.width != size || lp.height != size)) {
                lp.width = size;
                lp.height = size;
                logoWrap.setLayoutParams(lp);
            }
        }
        if (logoText != null) {
            logoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, miniBar
                    ? 22f
                    : Math.max(34, Math.round(logoSizeDp(wh[0], wh[1]) * 0.44f)));
        }
    }

    /** 列表行高（像素），供 Adapter 使用。 */
    static int rowHeightPx(Activity a) {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        Configuration c = a.getResources().getConfiguration();
        int[] wh = sizeDp(c, dm);
        return Math.round(rowHeightDp(wh[0], wh[1]) * dm.density);
    }
}
