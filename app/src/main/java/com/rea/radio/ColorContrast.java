package com.rea.radio;

/**
 * WCAG 2.1 相对亮度与对比度计算（v2.1）。
 *
 * <p>存在的意义：两套色板不能靠「看着差不多」验收。UI/UX Pro Max 要求
 * 主文字 ≥ 4.5:1、次文字与大字图形 ≥ 3:1，并且<b>暗色单独测</b>，
 * 不能默认白天的数值带过去。{@code scripts/check_contrast.py} 用同一套公式
 * 读两份 colors.xml 逐对核算；这个类供 JVM 单测与运行时自检复用。
 *
 * <p>不依赖 {@code android.graphics.Color}，纯 JVM 可跑。
 */
public final class ColorContrast {

    private ColorContrast() { }

    /** 解析 #RRGGBB 或 #AARRGGBB，返回 0xRRGGBB。 */
    public static int parseHex(String s) {
        if (s == null) throw new IllegalArgumentException("color is null");
        String h = s.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 8) h = h.substring(2);   // 丢掉 alpha，只算不透明色
        if (h.length() != 6) throw new IllegalArgumentException("bad color: " + s);
        return (int) Long.parseLong(h, 16);
    }

    /** 相对亮度，0（黑）～ 1（白）。 */
    public static double luminance(int rgb) {
        double r = channel((rgb >> 16) & 0xFF);
        double g = channel((rgb >> 8) & 0xFF);
        double b = channel(rgb & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** 两个不透明色的对比度，1:1 ～ 21:1。 */
    public static double ratio(int rgb1, int rgb2) {
        double a = luminance(rgb1), b = luminance(rgb2);
        double hi = Math.max(a, b), lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    public static double ratioHex(String fg, String bg) {
        return ratio(parseHex(fg), parseHex(bg));
    }
}
