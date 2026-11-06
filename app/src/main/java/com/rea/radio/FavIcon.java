package com.rea.radio;

import android.widget.ImageView;

/**
 * 收藏心形的显示（v1.8.4）。
 *
 * <h3>为什么需要这个类</h3>
 * 界面上有<b>三处</b>收藏入口：车机播放卡、手机播放条、列表每一行。
 * 第一版靠「同一张实心心形 + 灰色/橙色 tint」表达状态，渲染出来发现：
 * 列表里 9 个灰色实心心形排成一列，比台名还抢眼，看起来像「全都收藏了」。
 *
 * <p>改成两态两张图之后，问题变成「三个地方都得记得换图」——
 * 只要有一处漏了，用户就会看到「已收藏但还是空心」。
 * 所以把判定与换图收在一处，三处调用方都只写一句 {@link #bind}。
 *
 * <h3>两态用两张图，而不是一张图 + 颜色</h3>
 * 「空心 = 未收藏、实心 = 已收藏」是用户已经形成的直觉（参考图与主流音乐 App 都这样）。
 * 只靠颜色区分时，未收藏是灰的、已收藏是橙的，但形状一样 ——
 * 色弱用户在深色主题下几乎分不出。
 */
final class FavIcon {

    private FavIcon() {
    }

    /**
     * 把收藏状态刷到图标上。
     *
     * @param icon 心形 ImageView（为空时安全返回，方便调用方不写判空）
     * @param fav  是否已收藏
     */
    static void bind(ImageView icon, boolean fav) {
        if (icon == null) return;
        icon.setImageResource(fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        // 颜色仍由 fav_icon_tint 按 selected 决定：空心灰 → 实心橙。
        // 形状 + 颜色双重区分，任何一种失效都还能看懂。
        icon.setSelected(fav);
        icon.setContentDescription(fav ? "取消收藏" : "收藏");
    }
}
