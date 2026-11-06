#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读真实 colors.xml，逐对核算白天 / 夜间两套色板的 WCAG 对比度。

用法：
    python scripts/check_contrast.py            # 打印表格，不达标退出码 1
    python scripts/check_contrast.py --md      # 只输出 Markdown 表格（贴文档用）

为什么要单独一个脚本：Java 单测里写死颜色常量只能证明「算法对」，
证明不了「当前 XML 里的值对」。这个脚本读的是资源文件本身，
改了色板没改对比度会当场报错。
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PALETTES = {
    "白天": os.path.join(ROOT, "app/src/main/res/values/colors.xml"),
    "夜间": os.path.join(ROOT, "app/src/main/res/values-night/colors.xml"),
}

# (前景 token, 背景 token, 最低对比度, 说明)
# 门槛依据 UI/UX Pro Max：主文字 4.5:1，次文字 / 大图形 3:1；
# 装饰性发丝线（divider）与卡片/页面底的层次差不按此要求，另行说明。
CHECKS = [
    ("text_primary",  "panel",               4.5, "主文字 / 卡片"),
    ("text_secondary","panel",               4.5, "次文字 / 卡片"),
    ("text_hint",     "panel",               3.0, "三级文字 / 卡片"),
    ("item_text",     "panel",               4.5, "列表台名 / 卡片"),
    ("item_sub_text", "panel",               3.0, "列表副标题 / 卡片"),
    ("accent_deep",   "accent_wash",         4.5, "强调字 / 浅橙底"),
    ("accent_deep",   "item_bg_selected",    4.5, "选中行文字 / 选中底"),
    ("icon_on_accent","accent_solid",        4.5, "白图标 / 主播放键"),
    ("accent_ring",   "accent_solid_pressed",3.0, "焦点描边 / 深橙"),
    ("cover_text",    "cover_bg",            3.0, "首字 / 封面方块"),
    ("icon_secondary","surface_muted",       3.0, "次要图标 / 圆钮底"),
    ("log_key",       "panel",               3.0, "日志按键蓝 / 卡片"),
    ("log_warn",      "panel",               3.0, "日志警告 / 卡片"),
    ("log_error",     "panel",               3.0, "日志错误 / 卡片"),
    ("log_info",      "panel",               3.0, "日志普通 / 卡片"),
]


def read_colors(path):
    s = open(path, encoding="utf-8").read()
    return dict(re.findall(r'<color\s+name="([^"]+)">\s*(#[0-9A-Fa-f]{6,8})\s*</color>', s))


def luminance(h):
    h = h.lstrip("#")
    if len(h) == 8:
        h = h[2:]
    ch = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(ch[0]) + 0.7152 * f(ch[1]) + 0.0722 * f(ch[2])


def ratio(a, b):
    x, y = luminance(a), luminance(b)
    hi, lo = max(x, y), min(x, y)
    return (hi + 0.05) / (lo + 0.05)


def main():
    md_only = "--md" in sys.argv
    bad = 0
    rows = []
    for theme, path in PALETTES.items():
        c = read_colors(path)
        for fg, bg, need, note in CHECKS:
            if fg not in c or bg not in c:
                print("[缺 token] %s %s / %s" % (theme, fg, bg), file=sys.stderr)
                bad += 1
                continue
            got = ratio(c[fg], c[bg])
            ok = got >= need
            if not ok:
                bad += 1
            rows.append((theme, note, c[fg], c[bg], need, got, ok))

    if md_only:
        print("| 主题 | 配对 | 前景 | 背景 | 门槛 | 实测 | 结果 |")
        print("|---|---|---|---|---|---|---|")
        for theme, note, fg, bg, need, got, ok in rows:
            print("| %s | %s | `%s` | `%s` | %.1f:1 | **%.2f:1** | %s |"
                  % (theme, note, fg, bg, need, got, "通过" if ok else "不达标"))
    else:
        print("%-4s %-26s %-9s %-9s %-8s %-9s %s"
              % ("主题", "配对", "前景", "背景", "门槛", "实测", "结果"))
        for theme, note, fg, bg, need, got, ok in rows:
            print("%-4s %-26s %-9s %-9s %-8s %-9s %s"
                  % (theme, note, fg, bg, "%.1f:1" % need, "%.2f:1" % got,
                     "OK" if ok else "FAIL"))
        print("\n合计 %d 对，不达标 %d 对" % (len(rows), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
