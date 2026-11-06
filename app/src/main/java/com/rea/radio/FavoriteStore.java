package com.rea.radio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 收藏的电台。
 *
 * <p>为什么按 contentId 存：它是央广接口里长期不变的稳定标识，比台名可靠
 * （台名偶尔会改，比如「四川岷江音乐频率」这类带后缀的）。同时把台名、副标题、
 * 台标地址一起存下来，这样**断网也能看到收藏列表**、不会因为列表接口挂了就一片空白。
 *
 * <p>不存播放地址：地址带一次性签名 key，存下来必然失效，播放时再实时取。
 *
 * <p>顺序按「最后收藏的排最前」（add 时插到头部），这样刚收藏的台一眼能找到。
 */
final class FavoriteStore {

    private static final String FILE = "radio_favorites";
    private static final String K_LIST = "items";

    private FavoriteStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 收藏条目：只保留展示和定位需要的字段。 */
    static final class Item {
        String contentId = "";
        String title = "";
        String subtitle = "";
        String image = "";
        /**
         * 所属省份编码，0 表示「不知道」。
         *
         * <p>为什么必须存它：收藏里没有实时播放地址（地址带一次性签名，存不住），
         * 播放时若发现地址为空，{@code RadioPlayer} 会拿 {@code provinceCode}
         * 去重拉一次该省列表再补地址。旧版没存这个字段，{@code toStation()} 出来的
         * 对象恒为 0，于是刷新回落到写死的四川（510000）——
         * 收藏了北京/广东的台时，在四川列表里永远找不到这个 contentId，
         * 地址补不上，表现就是「界面已经切过去了，声音一直不响」。
         */
        int provinceCode;

        RadioCnApi.Station toStation() {
            RadioCnApi.Station s = new RadioCnApi.Station();
            s.contentId = contentId;
            s.title = title;
            s.subtitle = subtitle;
            s.image = image;
            s.provinceCode = provinceCode;
            return s;
        }

        static Item of(RadioCnApi.Station s) {
            Item i = new Item();
            i.contentId = s.contentId == null ? "" : s.contentId;
            i.title = s.title == null ? "" : s.title;
            i.subtitle = s.subtitle == null ? "" : s.subtitle;
            i.image = s.image == null ? "" : s.image;
            i.provinceCode = s.provinceCode;
            return i;
        }
    }

    /** 全部收藏，最近收藏的在前。 */
    static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        String raw = sp(c).getString(K_LIST, "");
        if (raw == null || raw.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.contentId = o.optString("id", "");
                // v1.8.3 之前的版本把台名连同频率一起写进来过（「四川城市之音（FM102.6）」）。
                // 升级后界面上不再显示频率，旧数据不清洗就会一直挂着那个后缀，
                // 和新收藏的台混在一起看着很怪。读的时候统一剥掉，不用迁移脚本。
                it.title = StationTitle.strip(o.optString("title", ""));
                it.subtitle = o.optString("sub", "");
                it.image = o.optString("img", "");
                // 老版本存的收藏没有 "pc" 字段，读出来是 0 —— 按「不知道」处理，
                // 由播放器去猜省份，不要在这里瞎猜一个省份码。
                it.provinceCode = o.optInt("pc", 0);
                if (it.contentId.length() > 0) out.add(it);
            }
        } catch (Throwable t) {
            AppLog.w("收藏", "解析失败，按空处理：" + t.getMessage());
        }
        return out;
    }

    static boolean contains(Context c, String contentId) {
        if (contentId == null || contentId.length() == 0) return false;
        for (Item it : list(c)) if (contentId.equals(it.contentId)) return true;
        return false;
    }

    /** 加入收藏（已存在则提到最前）。返回 true 表示现在处于「已收藏」。 */
    static boolean add(Context c, RadioCnApi.Station s) {
        if (s == null || s.contentId == null || s.contentId.length() == 0) return false;
        List<Item> cur = list(c);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(s.contentId);
        List<Item> out = new ArrayList<>();
        out.add(Item.of(s));
        for (Item it : cur) {
            if (seen.add(it.contentId)) out.add(it);
        }
        save(c, out);
        AppLog.i("收藏", "已收藏：" + s.title + "（共 " + out.size() + " 个）");
        return true;
    }

    /** 取消收藏。返回 true 表示现在处于「未收藏」。 */
    static boolean remove(Context c, String contentId) {
        if (contentId == null) return false;
        List<Item> cur = list(c);
        List<Item> out = new ArrayList<>();
        String removed = null;
        for (Item it : cur) {
            if (contentId.equals(it.contentId)) removed = it.title;
            else out.add(it);
        }
        save(c, out);
        if (removed != null) AppLog.i("收藏", "已取消收藏：" + removed);
        return true;
    }

    /** 收藏/取消 二选一。返回操作后的状态（true=已收藏）。 */
    static boolean toggle(Context c, RadioCnApi.Station s) {
        if (s == null || s.contentId == null) return false;
        if (contains(c, s.contentId)) {
            remove(c, s.contentId);
            return false;
        }
        add(c, s);
        return true;
    }

    static int count(Context c) {
        return list(c).size();
    }

    static void clear(Context c) {
        sp(c).edit().remove(K_LIST).apply();
    }

    private static void save(Context c, List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", it.contentId);
                o.put("title", it.title);
                o.put("sub", it.subtitle);
                o.put("img", it.image);
                o.put("pc", it.provinceCode);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        sp(c).edit().putString(K_LIST, arr.toString()).apply();
    }
}
