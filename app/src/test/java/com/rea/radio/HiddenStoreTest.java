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
 * 隐藏电台：
 *  - 隐藏后 isHidden 为真，重新读盘仍然保留（重启不丢）；
 *  - 重复隐藏不会产生两条；
 *  - 恢复显示后彻底消失；
 *  - 与「收藏」互不影响：隐藏≠取消收藏，取消收藏≠恢复显示。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HiddenStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        HiddenStore.clear(ctx);
        FavoriteStore.clear(ctx);
    }

    private RadioCnApi.Station st(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        return s;
    }

    @Test
    public void hide_then_is_hidden() {
        assertFalse(HiddenStore.isHidden(ctx, "449"));
        HiddenStore.hide(ctx, st("449", "四川交通广播"));
        assertTrue(HiddenStore.isHidden(ctx, "449"));
        assertEquals(1, HiddenStore.count(ctx));
    }

    @Test
    public void hide_twice_no_duplicate() {
        HiddenStore.hide(ctx, st("449", "四川交通广播"));
        HiddenStore.hide(ctx, st("449", "四川交通广播"));
        assertEquals("重复隐藏不能产生两条", 1, HiddenStore.count(ctx));
    }

    @Test
    public void hide_keeps_title_for_manager_ui() {
        HiddenStore.hide(ctx, st("449", "四川交通广播"));
        List<HiddenStore.Item> list = HiddenStore.list(ctx);
        assertEquals(1, list.size());
        assertEquals("449", list.get(0).contentId);
        assertEquals("管理列表要能显示台名", "四川交通广播", list.get(0).title);
    }

    @Test
    public void unhide_removes_completely() {
        HiddenStore.hide(ctx, st("449", "四川交通广播"));
        HiddenStore.hide(ctx, st("450", "四川城市之音"));
        HiddenStore.unhide(ctx, "449");
        assertEquals(1, HiddenStore.count(ctx));
        assertFalse(HiddenStore.isHidden(ctx, "449"));
        assertTrue("另一个不该被连累", HiddenStore.isHidden(ctx, "450"));
    }

    @Test
    public void clear_restores_all() {
        HiddenStore.hide(ctx, st("1", "A"));
        HiddenStore.hide(ctx, st("2", "B"));
        HiddenStore.clear(ctx);
        assertEquals(0, HiddenStore.count(ctx));
    }

    @Test
    public void empty_id_ignored() {
        assertFalse(HiddenStore.hide(ctx, st("", "无名")));
        assertFalse(HiddenStore.hide(ctx, null));
        assertEquals(0, HiddenStore.count(ctx));
    }

    /** 隐藏与收藏互不干扰 —— 这是两个独立的用户意图，不能互相污染。 */
    @Test
    public void hide_does_not_touch_favorites() {
        RadioCnApi.Station s = st("449", "四川交通广播");
        FavoriteStore.add(ctx, s);
        HiddenStore.hide(ctx, s);

        assertTrue("隐藏不该取消收藏", FavoriteStore.contains(ctx, "449"));
        assertTrue(HiddenStore.isHidden(ctx, "449"));

        // 反过来：取消收藏也不该让隐藏的台重新出现
        FavoriteStore.remove(ctx, "449");
        assertTrue("取消收藏不该恢复显示", HiddenStore.isHidden(ctx, "449"));
    }

    /** ids() 用于过滤列表，必须与 list() 一致。 */
    @Test
    public void ids_matches_list() {
        HiddenStore.hide(ctx, st("1", "A"));
        HiddenStore.hide(ctx, st("2", "B"));
        assertEquals(2, HiddenStore.ids(ctx).size());
        assertTrue(HiddenStore.ids(ctx).contains("1"));
        assertTrue(HiddenStore.ids(ctx).contains("2"));
    }
}
