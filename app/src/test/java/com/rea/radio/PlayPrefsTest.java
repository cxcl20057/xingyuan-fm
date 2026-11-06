package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 验证“开机恢复上次电台”的持久化：存 contentId（稳定）+ 下标（兜底），且**不存播放地址**。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlayPrefsTest {

    /**
     * 每个用例后清掉偏好。
     *
     * <p>不清会串味：{@code default_when_nothing_saved} 依赖「什么都没存」，
     * 而同一个 JVM 里 Robolectric 复用应用 Context，前一个用例存下的 index=5
     * 会让它读到脏数据；更糟的是这些值会被别的界面测试读到，
     * 表现成「恢复到了第 6 台」，让人以为列表滚动坏了。
     */
    @org.junit.After
    public void cleanUp() {
        ApplicationProvider.getApplicationContext()
                .getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void save_and_load_roundtrip() {
        Context c = ApplicationProvider.getApplicationContext();
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = "449";
        s.title = "四川交通广播";
        s.mp3PlayUrlLow = "http://example.com/stream.mp3?key=SECRET";

        PlayPrefs.save(c, s, 5, 510000, "四川", true);

        PlayPrefs.Snapshot snap = PlayPrefs.load(c);
        assertEquals("449", snap.contentId);
        assertEquals("四川交通广播", snap.title);
        assertEquals(5, snap.index);
        assertEquals(510000, snap.provinceCode);
        assertEquals("四川", snap.provinceName);
        assertTrue(snap.playing);

        // 绝不应把带一次性 key 的地址持久化
        String raw = c.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
                .getAll().toString();
        assertTrue("不应持久化播放地址", !raw.contains("SECRET"));
        assertTrue("不应持久化任何 http 地址", !raw.contains("http"));
    }

    @Test
    public void default_when_nothing_saved() {
        Context c = ApplicationProvider.getApplicationContext();
        PlayPrefs.Snapshot snap = PlayPrefs.load(c);
        assertEquals("", snap.contentId);
        assertEquals(510000, snap.provinceCode);
        assertTrue(snap.playing);
    }

    /**
     * 存储里某个 key 的类型对不上时，必须<b>降级成"全新安装"</b>而不是抛异常。
     *
     * <p>这不是假想：{@code last_index} 被写成 String 时，{@code getInt} 会抛
     * {@code ClassCastException}。而 {@code load} 是 {@code onCreate} 的第一屏，
     * 抛出去就是「一打开就闪退」，用户连界面都进不去、也没法在 App 里自救。
     *
     * <p>版本升级时字段改类型、或者被别的工具写过这个文件，都会踩到。
     */
    @Test
    public void wrong_value_type_degrades_instead_of_crashing() {
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_index", "不是数字")
                .putString("last_province_code", "四川")   // 本应是 int，故意写成 String
                .commit();

        PlayPrefs.Snapshot snap = PlayPrefs.load(c);   // 关键：不能抛

        // 断言的是「整体退回默认值」这个行为，而不是某个具体数字——
        // 注意默认省份码 510000 就在 last_index 的坏值附近，直接比数字容易假通过。
        assertEquals("contentId 应退回空", "", snap.contentId);
        assertEquals("下标应退回 0", 0, snap.index);
        assertEquals("省份名应退回默认", "四川", snap.provinceName);
        assertTrue("应判为可启动", snap.playing);
        assertTrue("默认省份码应是正整数", snap.provinceCode > 0);
    }

    /** 正常的 String 值不受影响：不能因为加了兜底就把好数据也一起丢了。 */
    @Test
    public void good_data_still_loads_after_guard_added() {
        Context c = ApplicationProvider.getApplicationContext();
        RadioCnApi.Station s = new RadioCnApi.Station();
        s.contentId = "1001";
        s.title = "四川交通广播";
        PlayPrefs.save(c, s, 7, 510000, "四川", false);

        PlayPrefs.Snapshot snap = PlayPrefs.load(c);
        assertEquals("1001", snap.contentId);
        assertEquals("四川交通广播", snap.title);
        assertEquals(7, snap.index);
    }
}
