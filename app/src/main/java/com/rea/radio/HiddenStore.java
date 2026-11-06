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
 * 被隐藏的电台。
 *
 * <p>为什么单独一个存储而不是并进 {@link FavoriteStore}：
 * 两者生命周期完全不同 —— 收藏是「主动挑出来的少数」，隐藏是「排除掉的黑名单」，
 * 混在一起会让「取消收藏」和「隐藏」语义互相污染（取消收藏不该让台重新出现）。
 *
 * <p>按 contentId 存。同时把台名存一份，纯粹是为了「隐藏管理」里能显示人看得懂的名字
 * （该台已下架、列表里找不到时也不至于显示成裸 id）。
 *
 * <p>隐藏不删数据、不可逆操作也不做：随时可以在「隐藏管理」里取消。
 */
final class HiddenStore {

    private static final String FILE = "radio_hidden";
    private static final String K_LIST = "items";

    private HiddenStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 隐藏条目（id + 台名，台名只为显示）。 */
    static final class Item {
        String contentId = "";
        String title = "";
    }

    /** 全部隐藏条目，最近隐藏的在前。 */
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
                it.title = o.optString("title", "");
                if (it.contentId.length() > 0) out.add(it);
            }
        } catch (Throwable t) {
            AppLog.w("隐藏", "解析失败，按空处理：" + t.getMessage());
        }
        return out;
    }

    /** 隐藏 ID 集合（判断是否显示时用，O(1) 查）。 */
    static Set<String> ids(Context c) {
        Set<String> s = new LinkedHashSet<>();
        for (Item it : list(c)) s.add(it.contentId);
        return s;
    }

    static boolean isHidden(Context c, String contentId) {
        return contentId != null && ids(c).contains(contentId);
    }

    static boolean hide(Context c, RadioCnApi.Station s) {
        if (s == null || s.contentId == null || s.contentId.length() == 0) return false;
        List<Item> cur = list(c);
        for (Item it : cur) {
            if (s.contentId.equals(it.contentId)) return true; // 已隐藏
        }
        Item it = new Item();
        it.contentId = s.contentId;
        it.title = s.title == null ? "" : s.title;
        cur.add(0, it);
        save(c, cur);
        AppLog.i("隐藏", "已隐藏：" + it.title + "（共隐藏 " + cur.size() + " 个）");
        return true;
    }

    static void unhide(Context c, String contentId) {
        if (contentId == null) return;
        List<Item> cur = list(c);
        List<Item> out = new ArrayList<>();
        String name = null;
        for (Item it : cur) {
            if (contentId.equals(it.contentId)) name = it.title;
            else out.add(it);
        }
        save(c, out);
        if (name != null) AppLog.i("隐藏", "已恢复显示：" + name);
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
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        sp(c).edit().putString(K_LIST, arr.toString()).apply();
    }
}
