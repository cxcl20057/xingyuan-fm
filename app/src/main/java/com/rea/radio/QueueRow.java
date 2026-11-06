package com.rea.radio;

/**
 * 列表「跟随光标」的纯逻辑（可单测，不依赖 Android 控件）。
 *
 * <p>为什么需要它——实车反馈的 bug：方向盘上一台/下一台切了十几台以后，
 * 列表停在原地不跟着翻页，光标跑到屏幕外，看不见当前播的是哪台。
 *
 * <p>根因有两层：
 * <ol>
 *   <li><b>高亮的行号算错了</b>：播放队列和屏幕列表不是同一个东西。队列可能含隐藏台，
 *       收藏视图下队列又变成收藏列表。拿队列下标当行号，一命中隐藏台就错位，
 *       错位到列表外就"完全没有高亮"。</li>
 *   <li><b>滚动被自己吞掉</b>：{@code setSelection()} 的滚动是「等布局完成后」才执行的，
 *       而紧随其后的 {@code notifyDataSetChanged()} 会触发重新布局，
 *       把还没执行的滚动动作冲掉。</li>
 * </ol>
 */
final class QueueRow {

    private QueueRow() {
    }

    /**
     * 在屏幕列表里找「正在播的这台」在第几行。
     *
     * @param playingId 正在播的电台 contentId
     * @param visible   当前屏幕列表（已过滤隐藏）
     * @return 行号；不在列表里返回 -1
     */
    static int rowOfStation(String playingId, RadioCnApi.Station[] visible) {
        if (playingId == null || visible == null) return -1;
        for (int i = 0; i < visible.length; i++) {
            if (visible[i] != null && playingId.equals(visible[i].contentId)) return i;
        }
        return -1;
    }

    /**
     * 列表要不要为「正在播的这台」滚动，滚到哪一行。
     *
     * <p>规则：目标行只要完整可见就**不动**——用户可能正在翻别的台看，
     * 一按切台就把列表拽走会很烦；只有看不见时才滚。
     * 滚动位置留出上方余量（最多 3 行），这样能同时看到上一台和下面几台，
     * 而不是把目标行贴到屏幕最上沿。
     *
     * @param row          目标行号
     * @param firstVisible 当前第一个可见行的行号
     * @param visibleCount 当前可见行数
     * @return 需要滚动的目标行；-1 表示不用滚
     */
    static int scrollTargetFor(int row, int firstVisible, int visibleCount) {
        if (row < 0) return -1;
        if (visibleCount <= 0) return row;             // 还没测量完，先滚过去
        if (row >= firstVisible && row < firstVisible + visibleCount) return -1;
        int margin = Math.min(3, Math.max(1, visibleCount / 3));
        return Math.max(0, row - margin);
    }

    /**
     * 平滑滚动用多久（毫秒）：按要滚过的像素距离换算，不要按行数。
     *
     * <p>为什么不能固定时长：车机一屏只有 4~5 行、手机屏能到 7~8 行，
     * 同样"翻 3 行"在两种屏上走的像素差一倍；按行数定时长，
     * 手机上就会快得像闪一下，车机上又拖沓。
     *
     * <p>速度取 ≈3px/ms（60fps 下每帧约 50px，视觉上是一段连续滑动而不是跳格），
     * 再夹在 120~420ms：少于 120ms 人眼只看到"跳"，超过 420ms 连按切台会追不上手。
     *
     * @param distancePx 需要滚动的像素距离（取绝对值）
     * @return 动画时长，毫秒
     */
    static int scrollDurationFor(int distancePx) {
        int ms = Math.max(0, distancePx) / 3;
        return Math.max(120, Math.min(420, ms));
    }

    // ---------------------------------------------------------------- 平滑滚动

    /**
     * 发出滚动请求前，判断"该不该滚、滚到第几行"。
     *
     * <p>抽成纯函数是为了能脱离控件单测——Robolectric 的 {@code ShadowAbsListView}
     * 把平滑滚动整个换成了空实现，JVM 里永远看不到列表真的位移，
     * 所以「目标行算得对不对」只能在这一层验证。
     *
     * <p>与旧实现的关键差别：旧代码在「目标行已可见」时直接 {@code return}，
     * 连请求都不发，导致 {@link #shouldScroll} 这一层无法体现"不动"这个决定。
     * 现在统一返回结果，由调用方据此决定是否落 {@code pendingScrollRow}。
     *
     * @param row          目标行号
     * @param firstVisible 当前第一个可见行号
     * @param visibleCount 当前可见行数
     * @return 目标行；-1 表示不该滚（已可见，或行号非法）
     */
    static int pendingTargetFor(int row, int firstVisible, int visibleCount) {
        return scrollTargetFor(row, firstVisible, visibleCount);
    }

    /** 是否真的需要发起一次滚动（目标行当前不可见）。 */
    static boolean shouldScroll(int row, int firstVisible, int visibleCount) {
        return pendingTargetFor(row, firstVisible, visibleCount) >= 0;
    }
}
