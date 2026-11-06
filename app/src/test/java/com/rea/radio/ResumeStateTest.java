package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 「退出再打开被重置到第一个台」的回归测试。
 *
 * <p>这个 bug 的成因很隐蔽：界面启动时把列表请求发了两遍，第二遍重建队列时
 * 恢复标记已被第一遍消费掉，于是从下标 0 起播，还顺手把「第 0 台」写回了
 * 偏好存储 —— 下次开机自然又是第一个台。
 *
 * <p>下面三个用例分别锁死三层：
 * ① 偏好存储：contentId 优先、下标只作兜底；
 * ② 播放器：原地换队列不能打断正在播的那台（rebindQueue）；
 * ③ 冷启动恢复：restoreAt 在流仍有效时不应重新打开。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ResumeStateTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    private RadioCnApi.Station st(String id, String title) {
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = id;
        s.title = title;
        s.subtitle = "四川 · 直播";
        return s;
    }

    private List<RadioCnApi.Station> queueOf(int n) {
        List<RadioCnApi.Station> q = new ArrayList<>();
        for (int i = 0; i < n; i++) q.add(st(String.valueOf(i + 1), "台" + (i + 1)));
        return q;
    }

    // ---------------------------------------------------------------- ①②

    /** 偏好存储必须原样存回 contentId / 下标 / 省份 / 播放态。 */
    @Test
    public void play_prefs_roundtrip() {
        RadioCnApi.Station s = st("450", "四川城市之音");
        PlayPrefs.save(ctx, s, 3, 510000, "四川", false);

        PlayPrefs.Snapshot snap = PlayPrefs.load(ctx);
        assertEquals("450", snap.contentId);
        assertEquals("四川城市之音", snap.title);
        assertEquals(3, snap.index);
        assertEquals(510000, snap.provinceCode);
        assertEquals("四川", snap.provinceName);
        assertEquals(false, snap.playing);
    }

    /** 空存储时要有合理默认值，不能崩。 */
    @Test
    public void play_prefs_defaults_when_empty() {
        ctx.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        PlayPrefs.Snapshot snap = PlayPrefs.load(ctx);
        assertNotNull(snap);
        assertEquals("", snap.contentId);
        assertEquals(MainActivity.DEFAULT_PROVINCE_CODE, snap.provinceCode);
        assertTrue("默认自动播放", snap.playing);
    }

    // ---------------------------------------------------------------- ③

    /**
     * 换队列时，当前正在播的台必须还能在新队列里被找到并保持下标 ——
     * 这是「界面重开不打断播放」的地基。
     */
    @Test
    public void rebind_keeps_current_station_by_content_id() throws Exception {
        RadioPlayer p = newPlayer();
        List<RadioCnApi.Station> q1 = queueOf(5);
        p.setQueue(q1, 3, false);       // 停在第 4 台
        assertEquals("4", p.current().contentId);

        // 模拟列表刷新：顺序变了一点，但同一个台还在
        List<RadioCnApi.Station> q2 = new ArrayList<>();
        q2.add(st("9", "新台"));
        q2.addAll(queueOf(5));
        p.rebindQueue(q2, 0, false);

        assertNotNull("换队列后不能变成没有当前台", p.current());
        assertEquals("必须仍指向原来那台，而不是回到第一个", "4", p.current().contentId);
        assertEquals(4, p.currentIndex());
    }

    /** 当前台不在新列表里（如切了省份）→ 才按 fallback 重建。 */
    @Test
    public void rebind_falls_back_when_station_missing() {
        RadioPlayer p = newPlayer();
        p.setQueue(queueOf(5), 3, false);
        assertEquals("4", p.current().contentId);

        List<RadioCnApi.Station> other = new ArrayList<>();
        other.add(st("x1", "别的省台"));
        other.add(st("x2", "别的省台2"));
        p.rebindQueue(other, 1, false);

        assertEquals("当前台不在了应落到 fallback 下标", "x2", p.current().contentId);
    }

    /** setQueueQuietly 装好队列后，下标必须是恢复的那台而不是 0。 */
    @Test
    public void quiet_queue_restores_recorded_index() {
        RadioPlayer p = newPlayer();
        p.setQueueQuietly(queueOf(10), 7, false);
        assertNotNull(p.current());
        assertEquals("恢复必须落到记下的台，不能是第一个", "8", p.current().contentId);
        assertEquals(7, p.currentIndex());
    }

    /** restoreAt 的越界保护：存了个比列表还大的下标也不能崩。 */
    @Test
    public void restore_clamps_out_of_range_index() {
        RadioPlayer p = newPlayer();
        p.setQueueQuietly(queueOf(3), 99, false);
        assertEquals("越界应收敛到最后一台", "3", p.current().contentId);
    }

    /** 空队列时 restoreAt 应当安全返回，不抛空指针。 */
    @Test
    public void restore_on_empty_queue_is_safe() {
        RadioPlayer p = newPlayer();
        p.setQueueQuietly(new ArrayList<>(), 0, false);
        assertEquals(-1, p.currentIndex());
        assertTrue(p.queue().isEmpty());
    }

    // ---------------------------------------------------------------- 台标版面

    /**
     * 台标版面选择：方图必须 crop 铺满（否则比圆小、露出底盘），
     * 长条标必须 fit（否则两端被切）。
     */
    @Test
    public void logo_scale_type_matches_shape() {
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        android.graphics.Bitmap square = android.graphics.Bitmap.createBitmap(
                400, 400, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Bitmap wide = android.graphics.Bitmap.createBitmap(
                400, 120, android.graphics.Bitmap.Config.ARGB_8888);

        LogoLoader.applyScaleType(iv, square);
        assertEquals("方形台标要裁圆铺满", android.widget.ImageView.ScaleType.CENTER_CROP,
                iv.getScaleType());

        LogoLoader.applyScaleType(iv, wide);
        assertEquals("横向长条要完整显示", android.widget.ImageView.ScaleType.FIT_CENTER,
                iv.getScaleType());
    }

    /** 极扁的标（如 400×100）也必须走 fit，不能被裁掉两端。 */
    @Test
    public void very_wide_logo_uses_fit() {
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                400, 100, android.graphics.Bitmap.Config.ARGB_8888);
        LogoLoader.applyScaleType(iv, b);
        assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER, iv.getScaleType());
    }

    /** null bitmap 不应改动 scaleType（避免抛 NPE）。 */
    @Test
    public void null_bitmap_does_not_throw() {
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        LogoLoader.applyScaleType(iv, null);
        assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP, iv.getScaleType());
    }

    // ---------------------------------------------------------------- 文件夹恢复

    /**
     * 「用户手动选过的文件夹」必须被打标，下次启动才不会被定位结果顶掉。
     */
    @Test
    public void manual_province_choice_is_marked() {
        ctx.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        assertFalse("初始应为未手动选择", PlayPrefs.load(ctx).chosenByUser);

        PlayPrefs.markProvinceChosen(ctx, 500000, "重庆");
        PlayPrefs.Snapshot snap = PlayPrefs.load(ctx);
        assertTrue("手动选过就要打标", snap.chosenByUser);
        assertEquals(500000, snap.provinceCode);
        assertEquals("重庆", snap.provinceName);
    }

    /** 文件夹与电台必须成对恢复，不能只恢复一半。 */
    @Test
    public void folder_and_station_restore_together() {
        ctx.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        PlayPrefs.markProvinceChosen(ctx, 500000, "重庆");
        PlayPrefs.save(ctx, st("9001", "重庆交通广播"), 5, 500000, "重庆", true);

        PlayPrefs.Snapshot snap = PlayPrefs.load(ctx);
        assertEquals("重庆", snap.provinceName);
        assertEquals(500000, snap.provinceCode);
        assertEquals("9001", snap.contentId);
        assertEquals(5, snap.index);
        assertTrue("手动选择标记不能被普通保存冲掉", snap.chosenByUser);
    }

    /** 不传 chosenByUser / showingFavView 时不得改动已有值（定时 persist 会频繁调用）。 */
    @Test
    public void partial_save_keeps_flags() {
        PlayPrefs.markProvinceChosen(ctx, 500000, "重庆");
        PlayPrefs.save(ctx, st("1", "A"), 0, 500000, "重庆", true); // 不带后两个参数
        PlayPrefs.Snapshot snap = PlayPrefs.load(ctx);
        assertTrue("普通保存不能把 chosenByUser 清掉", snap.chosenByUser);
    }

    /** 上次停在收藏视图，重启后也要停在收藏视图。 */
    @Test
    public void favorite_view_state_is_remembered() {
        PlayPrefs.save(ctx, st("1", "A"), 0, 510000, "四川", true, null, true);
        assertTrue(PlayPrefs.load(ctx).showingFavorites);

        PlayPrefs.save(ctx, st("1", "A"), 0, 510000, "四川", true, null, false);
        assertFalse(PlayPrefs.load(ctx).showingFavorites);
    }

    // ---------------------------------------------------------------- 按钮单击

    /**
     * 「按钮要点两次」的根因是 focusableInTouchMode=true。
     * 这里直接守源码：控制按钮样式不能出现该属性，否则触屏第一下只会移焦点。
     */
    @Test
    public void control_button_is_not_focusable_in_touch_mode() throws Exception {
        String xml = readSource("src/main/res/values/styles.xml");
        int start = xml.indexOf("name=\"CtrlButton\"");
        assertTrue("找不到 CtrlButton 样式", start > 0);
        String block = xml.substring(start, Math.min(xml.length(), start + 700));
        assertFalse("CtrlButton 不能设 focusableInTouchMode，否则触屏要点两次",
                block.contains("focusableInTouchMode"));
        assertTrue("仍应保留 focusable 以支持方向盘/键盘导航",
                block.contains("android:focusable"));
    }

    private String readSource(String rel) throws Exception {
        java.io.File f = new java.io.File(rel);
        if (!f.exists()) f = new java.io.File("app/" + rel);
        if (!f.exists()) throw new AssertionError("找不到源文件：" + rel);
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    // ---------------------------------------------------------------- 工具

    private RadioPlayer newPlayer() {
        RadioPlayer p = new RadioPlayer(ctx);
        p.setListener(null);
        return p;
    }
}
