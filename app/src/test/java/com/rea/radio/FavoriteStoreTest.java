package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * 收藏列表：
 *  - 按 contentId 去重，重复收藏不会出现两条；
 *  - 最近收藏的排最前（用户刚加完就能在顶部看到）；
 *  - 取消收藏后其它条目顺序不乱；
 *  - 跨进程重启（新建 store 实例重新读盘）能还原；
 *  - 空 contentId 不落盘（防止脏数据让整个收藏列表报废）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FavoriteStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        FavoriteStore.clear(ctx);
    }

    private RadioCnApi.Station st(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        s.subtitle = "正在直播";
        s.image = "http://img/" + id + ".png";
        return s;
    }

    @Test
    public void add_then_contains_and_count() {
        assertFalse(FavoriteStore.contains(ctx, "449"));
        FavoriteStore.add(ctx, st("449", "四川交通广播"));
        assertTrue(FavoriteStore.contains(ctx, "449"));
        assertEquals(1, FavoriteStore.count(ctx));
    }

    /** 重复收藏同一台 → 只保留一条，且提到最前。 */
    @Test
    public void add_same_station_twice_does_not_duplicate() {
        FavoriteStore.add(ctx, st("449", "四川交通广播"));
        FavoriteStore.add(ctx, st("450", "四川城市之音"));
        FavoriteStore.add(ctx, st("449", "四川交通广播"));

        List<FavoriteStore.Item> list = FavoriteStore.list(ctx);
        assertEquals("不能出现重复条目", 2, list.size());
        assertEquals("重复收藏应提到最前", "449", list.get(0).contentId);
        assertEquals("450", list.get(1).contentId);
    }

    @Test
    public void newest_favorite_comes_first() {
        FavoriteStore.add(ctx, st("1", "A"));
        FavoriteStore.add(ctx, st("2", "B"));
        FavoriteStore.add(ctx, st("3", "C"));
        List<FavoriteStore.Item> list = FavoriteStore.list(ctx);
        assertEquals("3", list.get(0).contentId);
        assertEquals("1", list.get(2).contentId);
    }

    @Test
    public void remove_keeps_others_intact() {
        FavoriteStore.add(ctx, st("1", "A"));
        FavoriteStore.add(ctx, st("2", "B"));
        FavoriteStore.add(ctx, st("3", "C"));
        FavoriteStore.remove(ctx, "2");

        List<FavoriteStore.Item> list = FavoriteStore.list(ctx);
        assertEquals(2, list.size());
        assertEquals("3", list.get(0).contentId);
        assertEquals("1", list.get(1).contentId);
        assertFalse(FavoriteStore.contains(ctx, "2"));
    }

    @Test
    public void toggle_flips_state() {
        RadioCnApi.Station s = st("449", "四川交通广播");
        assertTrue("第一次 toggle 应变为已收藏", FavoriteStore.toggle(ctx, s));
        assertTrue(FavoriteStore.contains(ctx, "449"));
        assertFalse("再 toggle 应取消收藏", FavoriteStore.toggle(ctx, s));
        assertFalse(FavoriteStore.contains(ctx, "449"));
    }

    /** 跨「重启」还原：重新读盘后标题/副标题/台标都还在（断网也能显示列表）。 */
    @Test
    public void survives_reload_with_display_fields() {
        RadioCnApi.Station s = st("449", "四川交通广播");
        FavoriteStore.add(ctx, s);

        List<FavoriteStore.Item> list = FavoriteStore.list(ctx);
        assertEquals(1, list.size());
        FavoriteStore.Item it = list.get(0);
        assertEquals("449", it.contentId);
        assertEquals("四川交通广播", it.title);
        assertEquals("正在直播", it.subtitle);
        assertEquals("http://img/449.png", it.image);

        // 快照能还原成可播放的 Station
        RadioCnApi.Station back = it.toStation();
        assertEquals("449", back.contentId);
        assertEquals("四川交通广播", back.title);
    }

    /** 空 contentId / null 一律忽略，不写脏数据。 */
    @Test
    public void empty_or_null_content_id_is_ignored() {
        assertFalse("空 ID 不该被收藏", FavoriteStore.add(ctx, st("", "无名")));
        assertFalse(FavoriteStore.add(ctx, null));
        assertEquals(0, FavoriteStore.count(ctx));
    }

    /** 清空后必须真的空，不能残留。 */
    @Test
    public void clear_empties_store() {
        FavoriteStore.add(ctx, st("1", "A"));
        FavoriteStore.add(ctx, st("2", "B"));
        FavoriteStore.clear(ctx);
        assertEquals(0, FavoriteStore.count(ctx));
    }
}
