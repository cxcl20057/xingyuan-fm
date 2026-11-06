package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「列表跟随光标滚动」的纯逻辑测试。
 *
 * <p>对应实车 bug：方向盘连按十几台以后，列表不翻页、光标跑到屏幕外看不见。
 */
public class QueueRowTest {

    private static RadioCnApi.Station s(String id) {
        RadioCnApi.Station st = new RadioCnApi.Station();
        st.contentId = id;
        st.title = id;
        return st;
    }

    private static RadioCnApi.Station[] stations(String... ids) {
        RadioCnApi.Station[] out = new RadioCnApi.Station[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = s(ids[i]);
        return out;
    }

    // ------------------------------------------------------------ 行号查找

    @Test
    public void finds_row_by_content_id_not_by_index() {
        // 队列下标 7 的台，因为前面隐藏过 2 个，屏幕上其实在第 5 行
        RadioCnApi.Station[] visible = stations("a", "b", "c", "d", "g", "h", "i");
        assertEquals("必须按 contentId 找行，不能直接用队列下标",
                4, QueueRow.rowOfStation("g", visible));
        assertEquals(0, QueueRow.rowOfStation("a", visible));
        assertEquals(6, QueueRow.rowOfStation("i", visible));
    }

    @Test
    public void missing_station_returns_minus_one() {
        RadioCnApi.Station[] visible = stations("a", "b");
        // 正在播的台被隐藏了 / 换省份了 / 收藏视图里没有它
        assertEquals(-1, QueueRow.rowOfStation("zzz", visible));
        assertEquals(-1, QueueRow.rowOfStation(null, visible));
        assertEquals(-1, QueueRow.rowOfStation("a", null));
    }

    @Test
    public void empty_list_is_safe() {
        assertEquals(-1, QueueRow.rowOfStation("a", new RadioCnApi.Station[0]));
    }

    // ------------------------------------------------------------ 滚动判定

    @Test
    public void does_not_scroll_when_target_is_visible() {
        // 可见 0~4 行，目标在第 3 行 → 已经在屏幕上，别动
        assertEquals("目标可见时不该拽动列表",
                -1, QueueRow.scrollTargetFor(3, 0, 5));
        assertEquals(-1, QueueRow.scrollTargetFor(0, 0, 5));
        assertEquals(-1, QueueRow.scrollTargetFor(4, 0, 5));
    }

    @Test
    public void scrolls_down_when_target_below_screen() {
        // 可见 0~4 行，目标第 6 行 → 必须往下翻
        int target = QueueRow.scrollTargetFor(6, 0, 5);
        assertEquals("应把目标行留出上方余量（5 行可见 → 留 1 行）", 5, target);
    }

    @Test
    public void scrolls_up_when_target_above_screen() {
        // 可见 10~14 行，目标第 8 行 → 往上翻
        int target = QueueRow.scrollTargetFor(8, 10, 5);
        assertEquals(7, target);
    }

    @Test
    public void long_list_keeps_three_rows_of_context() {
        // 车机列表区 401dp / 86dp 行高 ≈ 4.6 行 → 可见 4~5 行。
        // 连按下一台到第 30 行时，应滚到 29 而不是 30：目标行上方留 1 行余量，
        // 这样目标行不会贴在最下沿被遮住，也还能看见下面接着是哪些台。
        int target = QueueRow.scrollTargetFor(30, 20, 5);
        assertEquals(29, target);
        assertEquals("目标必须落在可见范围内",
                true, target <= 30 && target + 5 > 30);
    }

    @Test
    public void never_scrolls_negative() {
        // 目标在第 1 行、可见区在很下面 → 滚到 0 就行，不能是负数
        assertEquals(0, QueueRow.scrollTargetFor(1, 10, 5));
        assertEquals(0, QueueRow.scrollTargetFor(0, 10, 5));
    }

    @Test
    public void unknown_row_never_scrolls() {
        assertEquals(-1, QueueRow.scrollTargetFor(-1, 0, 5));
    }

    @Test
    public void unmeasured_list_still_scrolls() {
        // onCreate 里第一次 setSelection 时列表还没布局，childCount = 0
        assertEquals("还没测量完也要滚过去", 7, QueueRow.scrollTargetFor(7, 0, 0));
    }

    @Test
    public void margin_scales_with_visible_rows() {
        // 可见 3 行 → margin = max(1, 3/3) = 1
        assertEquals(9, QueueRow.scrollTargetFor(10, 0, 3));
        // 可见 20 行（平板/大屏）→ margin = 3（上限）
        assertEquals(17, QueueRow.scrollTargetFor(20, 0, 20));
    }

    // ------------------------------------------------------------ 平滑滚动时长

    @Test
    public void scroll_duration_grows_with_distance_but_stays_in_range() {
        // 一定不会为 0：0 时长在 ListView 里等于“瞬间跳”，看不出滚动
        assertEquals(120, QueueRow.scrollDurationFor(0));
        assertEquals(120, QueueRow.scrollDurationFor(300));
        // 车机一行 86dp × 2x ≈ 172px：翻 5 行 ≈ 860px → 约 286ms，看得清也跟得上手
        int fiveRows = QueueRow.scrollDurationFor(860);
        assertTrue("翻 5 行应在 200~400ms 之间，实际 " + fiveRows,
                fiveRows >= 200 && fiveRows <= 400);
        // 一次翻几十行也不会拖太久（最多 420ms）
        assertEquals(420, QueueRow.scrollDurationFor(50_000));
        // 距离越远时长越长（单调），否则连按切台会出现“近的快、远的慢”
        assertTrue(QueueRow.scrollDurationFor(600) >= QueueRow.scrollDurationFor(400));
    }
}
