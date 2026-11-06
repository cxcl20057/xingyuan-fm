package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.ListView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 端到端回归：方向盘/界面连续切台时，列表必须跟着滚动。
 *
 * <p>对应实车 bug：「上一曲下一曲的时候播放列表不会随着上下滚动，
 * 光标都移出屏幕了也不翻页，根本看不见你在第几台」。
 *
 * <p>车机 960×540dp、列表区实测 401dp、行高 86dp ⇒ 一屏只能看到 4~5 行，
 * 而四川有 44 个台，所以连切 6 次就必然要翻页。
 *
 * <h3>为什么断言方式变了（v1.6.1）</h3>
 *
 * <p>v1.6.0 把 {@code setSelection} 换成 {@code smoothScrollToPositionFromTop}（平滑滚动）。
 * 但 Robolectric 4.11 的 {@code ShadowAbsListView} 对它<b>没有对应的影子方法</b>：
 * <ul>
 *   <li>只有 {@code smoothScrollToPosition(int)} 被影子接管（记一个 int），
 *       而代码调的是带 offset/duration 的 {@code smoothScrollToPositionFromTop}，
 *       会落到 {@code smoothScrollBy} 上，记录的是「距离」而不是「行号」；</li>
 *   <li>{@code smoothScrollBy} 同样只是记账，<b>列表不会真的位移</b>。</li>
 * </ul>
 * 所以 JVM 里断言「列表翻页了 / 第 N 行可见」永远失败——这不是业务 bug，
 * 是测试框架不驱动滚动动画，真机上 AOSP 的实现是靠 {@code postOnAnimation} 逐帧滚的，正常。
 *
 * <p>因此验证拆成两层，各自测真正能测的东西：
 * <ol>
 *   <li><b>业务逻辑层</b>（本类前半）：按 contentId 算出屏幕行号，
 *       再用 {@link QueueRow#pendingTargetFor} 复核「该不该滚、滚到第几行」，
 *       并验证<b>行号换算在隐藏台之后仍然正确</b>——这才是 bug 的根源。</li>
 *   <li><b>高亮与可见性层</b>（后半）：用同步的 {@code setSelectionFromTop}
 *       把列表摆到目标行，验证选中态、可见范围。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ListFollowsPlaybackTest {

    private static final int W = 1920;
    private static final int H = 1080;

    private MainActivity a;
    private ListView list;
    /** 测试里直接 new 一个播放器塞给界面：Robolectric 不跑前台服务，绑不到真播放器。 */
    private RadioPlayer player;

    private void setup(int stationCount) {
        // 先清掉上次留下的恢复状态，再建界面。
        // Robolectric 在同一个 JVM 里复用应用 Context，别的测试类（如 PlayPrefsTest）
        // 存下的 index 会被 onCreate 读到，起播台就不是第一个了，
        // 后续「往下切 8 次应停在第 8 行」这类断言会偶发失败。
        // 顺序不能反：onCreate 里已经把恢复状态读进内存了，建完再清没有意义。
        org.robolectric.RuntimeEnvironment.getApplication()
                .getSharedPreferences("radio_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();
        a = Robolectric.buildActivity(MainActivity.class).create().get();
        // 下面是手造的「电台N」假数据，不是央广原始列表：关掉本地补录，
        // 否则会按四川补进 3 个成都台，20 台变 23 台，所有行号断言都会偏
        a.setLocalStationsEnabled(false);
        player = new RadioPlayer(a);

        // 必须在 showStations 之前挂上播放器：showStations 里是 `if (player != null)` 才灌队列，
        // 晚挂就会得到空队列，next()/prev() 直接空转
        try {
            java.lang.reflect.Method attach = MainActivity.class
                    .getDeclaredMethod("attach", RadioPlayer.class);
            attach.setAccessible(true);
            attach.invoke(a, player);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        List<RadioCnApi.Station> data = new ArrayList<>();
        for (int i = 0; i < stationCount; i++) {
            RadioCnApi.Station s = new RadioCnApi.Station();
            s.title = "电台" + (i + 1);
            s.subtitle = "直播中";
            s.provinceName = "四川";
            s.contentId = "id" + i;
            data.add(s);
        }
        try {
            java.lang.reflect.Method show = MainActivity.class
                    .getDeclaredMethod("showStations", List.class);
            show.setAccessible(true);
            show.invoke(a, data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        list = a.findViewById(R.id.list_stations);
        layout();
    }

    private void layout() {
        View root = a.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        // 让所有 post / postDelayed（含兜底定位）都真的跑到
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(800, TimeUnit.MILLISECONDS);
    }

    /**
     * 把列表直接摆到某一行（绕开空实现的平滑滚动），用于验证高亮与可见性。
     *
     * <p>必须先掐掉挂起的滚动任务：前面的 {@code player.next()} 会通过
     * {@code scrollListToRow} 往列表上 post 一个 {@code settleScrollTask}，
     * 它在 {@link #layout()} 里的 {@code idleFor(800ms)} 阶段会执行
     * {@code setSelectionFromTop(旧目标, 0)}，把刚刚定位好的行又拽回去。
     * 表现就是偶发「该行应位于首行 expected:3 but was:0」。
     * 这里调界面自己的 {@code cancelPendingScroll()}，它正是「用户一上手就放弃
     * 程序发起的滚动」用的同一个入口，语义与本场景完全一致。
     */
    private void jumpTo(int row) {
        try {
            java.lang.reflect.Method cancel =
                    MainActivity.class.getDeclaredMethod("cancelPendingScroll");
            cancel.setAccessible(true);
            cancel.invoke(a);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        list.setSelectionFromTop(row, 0);
        layout();
        // 收敛判据用「目标行可见」而不是「目标行在首行」：
        // 后者在列表滚到底时永远不成立（AOSP 会把 selection 往回贴），
        // 会导致这里白跑一次、也读不出真实意图。只要看得见就停手。
        if (!isRowVisible(row)) {
            list.setSelectionFromTop(row, 0);
            layout();
        }
    }

    /** 定位失败时把列表真实状态打出来，免得只看到一句「必须可见」不知道怎么坏的。 */
    private String listState(int row) {
        return "目标行=" + row
                + " 首行=" + list.getFirstVisiblePosition()
                + " 可见子view数=" + list.getChildCount()
                + " 总数=" + list.getCount()
                + " 顶部残留=" + list.getChildAt(0);
    }

    private boolean isRowVisible(int row) {
        int first = list.getFirstVisiblePosition();
        int last = first + list.getChildCount() - 1;
        return row >= first && row <= last;
    }

    /** 按 contentId 在屏幕列表里找行号（与业务代码同一套换算口径）。 */
    private int rowOfCurrent() {
        RadioCnApi.Station cur = player.current();
        android.widget.BaseAdapter ad = (android.widget.BaseAdapter) list.getAdapter();
        for (int i = 0; i < ad.getCount(); i++) {
            RadioCnApi.Station st = (RadioCnApi.Station) ad.getItem(i);
            if (st.contentId.equals(cur.contentId)) return i;
        }
        return -1;
    }

    // ================================================================ 业务逻辑层

    /**
     * 核心回归：连按下一台，随着列表在 JVM 里不会真的滚（受框架所限），
     * 但业务逻辑必须每一拍都算出「该滚到第几行」，且这个目标行能把当前台带进可见区。
     *
     * <p>这里用「假设上一拍已经滚到目标行」来推进可见区，逐拍验证——
     * 等价于把真机上门连续滚动的位置回放一遍。
     */
    @Test
    public void every_next_press_computes_a_target_that_brings_the_row_into_view() {
        setup(30);
        int visibleCount = list.getChildCount();
        assertTrue("一屏至少 4 行，否则这个测试没意义，实际 " + visibleCount, visibleCount >= 4);
        int firstVisible = 0;
        int scrollCount = 0;

        for (int i = 0; i < 12; i++) {
            player.next();
            layout();
            int row = rowOfCurrent();
            assertTrue("切到第 " + (i + 1) + " 次时该台必须还在列表里", row >= 0);

            int target = QueueRow.pendingTargetFor(row, firstVisible, visibleCount);
            boolean visible = row >= firstVisible && row < firstVisible + visibleCount;

            if (visible) {
                assertEquals("第 " + (row + 1) + " 行已经在可见区（" + firstVisible + "~"
                                + (firstVisible + visibleCount - 1) + "），不该再滚",
                        -1, target);
            } else {
                scrollCount++;
                assertTrue("第 " + (row + 1) + " 行在可见区外（" + firstVisible + "~"
                        + (firstVisible + visibleCount - 1) + "），必须请求滚动", target >= 0);
                assertTrue("滚动目标 " + (target + 1) + " 必须让第 " + (row + 1) + " 行落在"
                                + target + "~" + (target + visibleCount - 1) + " 区间内",
                        row >= target && row < target + visibleCount);
                assertTrue("目标行上方应留余量（最多 3 行），实际目标 " + (target + 1)
                        + " / 目标行 " + (row + 1), target >= row - 3);
                // 模拟真机：这一拍滚过去了
                firstVisible = target;
            }
        }
        assertTrue("连按 12 次必然要翻页，实际一次都没滚", scrollCount > 0);
        assertTrue("连按 12 次后应已翻过至少一屏，实际首行 " + firstVisible, firstVisible >= 4);
    }

    /** 往回切时，目标行必须往回收（列表跟随向上翻）。 */
    @Test
    public void every_prev_press_computes_a_target_that_keeps_the_row_in_view() {
        setup(30);
        int visibleCount = list.getChildCount();
        int firstVisible = 0;

        // 先往下切 15 次，模拟列表已经翻到很后面
        for (int i = 0; i < 15; i++) {
            player.next();
            layout();
            int row = rowOfCurrent();
            int target = QueueRow.pendingTargetFor(row, firstVisible, visibleCount);
            if (target >= 0) firstVisible = target;
        }
        assertTrue("先往下切后列表应已翻出去，实际首行 " + firstVisible, firstVisible > 0);
        int farFirst = firstVisible;

        // 再往回切 10 次，可见区要跟着往回收
        for (int i = 0; i < 10; i++) {
            player.prev();
            layout();
            int row = rowOfCurrent();
            assertTrue("往回切到第 " + (row + 1) + " 行，该台必须还在列表里", row >= 0);
            int target = QueueRow.pendingTargetFor(row, firstVisible, visibleCount);
            if (target >= 0) firstVisible = target;
            assertTrue("往回切后可见区必须能看见第 " + (row + 1) + " 行（首行 "
                    + firstVisible + "）", row >= firstVisible);
        }
        assertTrue("往回切 10 次后列表应往上滚过，实际首行 " + firstVisible
                + "（原来 " + farFirst + "）", firstVisible < farFirst);
    }

    /**
     * 隐藏台之后行号仍要算对——这正是 v1.5.1 那个 bug 的根源：
     * 拿队列下标当屏幕行号，隐藏过台之后必然错位。
     */
    @Test
    public void hidden_stations_do_not_break_row_mapping() {
        setup(20);
        // hideStation(pos) 的参数是「屏幕行号」，不是队列下标。隐藏屏幕第 1、2 行。
        for (int i = 1; i <= 2; i++) {
            try {
                java.lang.reflect.Method hide = MainActivity.class
                        .getDeclaredMethod("hideStation", int.class);
                hide.setAccessible(true);
                hide.invoke(a, i);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            layout();
        }
        // 隐藏了 2 台，列表只剩 18 行
        android.widget.BaseAdapter ad = (android.widget.BaseAdapter) list.getAdapter();
        assertEquals("隐藏 2 台后列表应剩 18 行", 18, ad.getCount());

        // 隐藏后当前台仍是第 1 台（还没切），它在屏幕第 0 行
        assertEquals("隐藏其它台不该改变正在播的这台", 0, rowOfCurrent());

        for (int i = 0; i < 8; i++) {
            player.next();
            layout();
        }
        // 从第 0 行往下切 8 次 ⇒ 屏幕第 8 行。
        // 关键点：如果拿「队列下标」当行号，这里会算错（隐藏前后队列长度不同）——
        // 下面用 contentId 换算复核，证明两条路径一致。
        int row = rowOfCurrent();
        assertTrue("正在播的台必须在列表里", row >= 0);
        assertEquals("从第 0 行往下切 8 次应停在屏幕第 8 行（下标 8）", 8, row);
        assertEquals("按 contentId 换算的行号必须与屏幕列表一致",
                row, QueueRow.rowOfStation(player.current().contentId, visibleStations()));
        assertTrue("换算出的行号不能超出列表范围（错位到列表外就完全没有高亮）",
                row < ad.getCount());
    }

    private RadioCnApi.Station[] visibleStations() throws AssertionError {
        try {
            java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("visibleStations");
            m.setAccessible(true);
            return (RadioCnApi.Station[]) m.invoke(a);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ================================================================ 高亮与可见性层

    /** 把列表摆到目标行，高亮与可见性必须都对。 */
    @Test
    public void target_row_gets_highlighted_and_visible() {
        setup(30);
        for (int i = 0; i < 12; i++) {
            player.next();
            layout();
        }
        int row = rowOfCurrent();
        assertTrue("正在播的台必须在列表里", row >= 0);

        jumpTo(row);
        assertTrue("跳到第 " + (row + 1) + " 行后必须可见（" + listState(row) + "）",
                isRowVisible(row));
        // 注意不能断言「该行一定是首行」：列表内容不足一屏时（比如只有两行）根本滚不动，
        // setSelectionFromTop(1,0) 会被 AOSP 贴回 0，这与业务逻辑无关。
        // 能滚的时候才要求它成为首行。
        if (list.getCount() > list.getChildCount()) {
            assertEquals("内容超过一屏时，该行应位于首行", row, list.getFirstVisiblePosition());
        }
        assertTrue("该行的选中态应为 true", list.isItemChecked(row));
    }

    /** 目标行已经在可见区时，不该再发起滚动（用户可能在翻别的台看）。 */
    @Test
    public void visible_row_does_not_trigger_scroll() {
        setup(30);
        player.next();
        layout();
        int row = rowOfCurrent();
        int target = QueueRow.pendingTargetFor(row, 0, list.getChildCount());
        assertEquals("第 " + (row + 1) + " 行本来就看得见，不该滚", -1, target);
        assertFalse("shouldScroll 也应为 false", QueueRow.shouldScroll(row, 0, list.getChildCount()));
    }
}
