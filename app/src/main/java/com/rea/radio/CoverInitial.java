package com.rea.radio;

/**
 * 列表行封面方块上的「首字」（v2.1）。
 *
 * <p>台名可能带括号、频率前缀或空格（「（FM101.7）中国之声」之类），
 * 直接 {@code substring(0,1)} 会拿到一个括号。这里取第一个字母 / 数字 / 汉字，
 * 全都没有时回落一个中性点，保证方块里永远有东西、不会塌成空白。
 */
public final class CoverInitial {

    private CoverInitial() { }

    public static String of(String title) {
        if (title == null) return "·";
        String t = title.trim();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) return String.valueOf(c);
        }
        return "·";
    }
}
