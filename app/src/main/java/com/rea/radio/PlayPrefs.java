package com.rea.radio;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 记住「上次在哪个文件夹、哪个台」以及播放状态，下次打开直接回到原处。
 *
 * <p>这里的「文件夹」就是省份（四川 / 重庆 / …）。恢复必须是<b>文件夹 + 台</b>成对恢复，
 * 否则会出现记的是 A 省的台、列表却是 B 省，用户看到的就不是上次那个。
 *
 * <p>存储内容刻意做成“内容无关”的：
 * <ul>
 *   <li>{@code contentId} 是电台的稳定标识（接口里长期不变），用它定位比存下标可靠——
 *       哪天列表增删了电台，下标会错位，contentId 不会；</li>
 *   <li>同时存下标作为兜底（万一该台被下架）；</li>
 *   <li>{@code chosenByUser} 记录「这个省份是用户手动选的，还是定位推出来的」。
 *       只有用户手动选过才在下次启动原样恢复；否则首次安装时应该跟随定位。</li>
 * </ul>
 *
 * <p>注意：<b>不存播放 URL</b>。接口返回的地址带一次性签名 key，存下来必然失效，
 * 必须播放时重新申请。
 */
final class PlayPrefs {

    private static final String FILE = "radio_prefs";
    private static final String K_ID = "last_content_id";
    private static final String K_TITLE = "last_title";
    private static final String K_INDEX = "last_index";
    private static final String K_PROVINCE_CODE = "last_province_code";
    private static final String K_PROVINCE_NAME = "last_province_name";
    private static final String K_PLAYING = "last_playing";
    /** 省份是否是用户手动选的（true 才在启动时强制恢复）。 */
    private static final String K_CHOSEN = "province_chosen_by_user";
    /** 上次的视图：0=全部电台，1=收藏。 */
    private static final String K_VIEW_FAV = "last_view_favorites";

    private PlayPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static final class Snapshot {
        String contentId = "";
        String title = "";
        int index = 0;
        int provinceCode = MainActivity.DEFAULT_PROVINCE_CODE;
        String provinceName = "四川";
        boolean playing = true;
        /** false = 从没手动选过省份，允许用定位结果覆盖。 */
        boolean chosenByUser = false;
        /** 上次停在收藏视图还是全部列表。 */
        boolean showingFavorites = false;
    }

    static Snapshot load(Context c) {
        Snapshot s = new Snapshot();
        try {
            SharedPreferences p = sp(c);
            s.contentId = p.getString(K_ID, "");
            // 同 FavoriteStore：v1.8.3 之前存的是「台名（FMxxx）」，读出来剥掉后缀
            s.title = StationTitle.strip(p.getString(K_TITLE, ""));
            s.index = p.getInt(K_INDEX, 0);
            s.provinceCode = p.getInt(K_PROVINCE_CODE, MainActivity.DEFAULT_PROVINCE_CODE);
            s.provinceName = p.getString(K_PROVINCE_NAME, "四川");
            s.playing = p.getBoolean(K_PLAYING, true);
            s.chosenByUser = p.getBoolean(K_CHOSEN, false);
            s.showingFavorites = p.getBoolean(K_VIEW_FAV, false);
        } catch (Throwable t) {
            // 这里必须兜底，理由不复杂：本方法在 MainActivity.onCreate 第 4 行就被调用，
            // 是整个界面能不能起来的第一道门。
            //
            // 危险的场景是「同一个 sp 文件里某个 key 的类型变了」（比如上个版本把
            // last_index 存成 String，这版按 int 读），getInt 会抛 ClassCastException。
            // 以前没兜底，后果就是一启动就闪退，而且用户永远进不去——
            // 连"清数据"这种操作都得先打开系统设置才能做。
            //
            // 兜底策略：当作全新安装处理。恢复不了上次的台是小事，打不开是大事。
            AppLog.w("恢复", "读取上次状态失败，按全新安装处理：" + t);
            return new Snapshot();
        }
        return s;
    }

    static void save(Context c, RadioCnApi.Station s, int index, int provinceCode,
                     String provinceName, boolean playing) {
        save(c, s, index, provinceCode, provinceName, playing, null, null);
    }

    /**
     * 全量保存。
     *
     * @param chosenByUser   null 表示不改（沿用已有值），避免定时 persist 把它冲掉
     * @param showingFavView null 表示不改
     */
    static void save(Context c, RadioCnApi.Station s, int index, int provinceCode,
                     String provinceName, boolean playing,
                     Boolean chosenByUser, Boolean showingFavView) {
        SharedPreferences.Editor e = sp(c).edit();
        if (s != null) {
            e.putString(K_ID, s.contentId == null ? "" : s.contentId);
            e.putString(K_TITLE, s.title == null ? "" : s.title);
        }
        e.putInt(K_INDEX, index);
        e.putInt(K_PROVINCE_CODE, provinceCode);
        e.putString(K_PROVINCE_NAME, provinceName);
        e.putBoolean(K_PLAYING, playing);
        if (chosenByUser != null) e.putBoolean(K_CHOSEN, chosenByUser);
        if (showingFavView != null) e.putBoolean(K_VIEW_FAV, showingFavView);
        e.apply();
    }

    /** 用户手动选了省份：立刻落盘并标记为「用户的选择」。 */
    static void markProvinceChosen(Context c, int code, String name) {
        sp(c).edit()
                .putInt(K_PROVINCE_CODE, code)
                .putString(K_PROVINCE_NAME, name)
                .putBoolean(K_CHOSEN, true)
                .apply();
    }
}
