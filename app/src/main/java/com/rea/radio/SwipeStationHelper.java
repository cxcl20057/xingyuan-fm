package com.rea.radio;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 播放界面上半区滑动换台。
 *
 * <p>车机上「抬手滑一下」比「瞄准小按钮点」安全得多（不用低头找按钮）。
 * 需求是左右滑动换台；这里做双向都认：
 * <ul>
 *   <li><b>上下滑</b>：上滑 = 下一台，下滑 = 上一台（像翻台列表）</li>
 *   <li><b>左右滑</b>：左滑 = 下一台，右滑 = 上一台（像翻页）</li>
 * </ul>
 *
 * <p>为什么用「旁听」而不是 OnTouchListener：
 * 按钮是 clickable 的，按下即消费事件并返回 true，父容器的 OnTouchListener
 * 根本收不到后续 MOVE/UP。所以这里在 Activity.dispatchTouchEvent 里
 * <b>先正常分发给子控件</b>，再把同一个事件喂给本类旁听，互不干扰。
 *
 * <p>几个必须处理好的细节（车机触屏容易误判）：
 * <ol>
 *   <li><b>阈值</b>：必须超过系统 touchSlop，再叠一个最小位移，
 *       否则手指轻微抖动就切台；</li>
 *   <li><b>方向判定只在松手时做</b>，不要边滑边切 —— 否则一次滑动会连切好几台；</li>
 *   <li><b>只认上半区</b>（默认 55% 高度以内），<b>而且必须横向也在接收面板内</b> ——
 *       车机是左右两栏，右栏列表的纵向位置正落在左栏上半区，只比纵向会把
 *       「滑列表」判成「滑空白区」，慢速拖一下就会切台（v1.9.11 修）；</li>
 *   <li><b>起点在列表里时纵向一律不切台</b>：那是用户在翻列表。
 *       判据要沿祖先链找滚动容器 —— 命中列表行里的文字时，
 *       最深的 View 既不是 ListView 也不 clickable（v1.6.3 修）。</li>
 *   <li><b>多指/长按不触发</b>，避免误操作。</li>
 * </ol>
 *
 * <p>判定逻辑抽成静态方法 {@link #decide}，可以脱离真机单测。
 */
final class SwipeStationHelper {

    /** 滑动结果。 */
    enum Action {
        NONE, NEXT, PREV
    }

    /** 最小滑动距离（dp）。车机屏幕大，给小了会误触。 */
    static final float MIN_SWIPE_DP = 90f;

    /** 只在这块区域内响应（屏高的比例）：上半区。 */
    static final float ACTIVE_HEIGHT_RATIO = 0.58f;

    private final float minSwipe;
    private float swipeThreshold;
    private final Callback cb;

    private float downX, downY;
    private boolean tracking;
    private boolean multiTouch;
    /** 本次手势的起点是否在可滚动容器（列表）里：是的话纵向位移归滚动，不切台。 */
    private boolean inScrollable;

    interface Callback {
        void onSwipeNext();

        void onSwipePrev();
    }

    SwipeStationHelper(Context ctx, Callback cb) {
        this.cb = cb;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        this.minSwipe = minSwipePx(ViewConfiguration.get(ctx).getScaledTouchSlop(), dm.density);
        this.swipeThreshold = this.minSwipe;
    }

    /**
     * 切换触发阈值（手机上有更小的拇指滑动距离）。
     *
     * <p>手机横屏时屏幕变矮，90dp 相对整屏的占比更大，同一套阈值会显得更"重"，
     * 所以版面切换时由 Activity 同步更新。
     */
    void setThresholdPx(float px) {
        if (px > 0) this.swipeThreshold = px;
    }

    float thresholdPx() {
        return swipeThreshold;
    }

    /**
     * 最小触发距离（像素）。抽出来便于单测覆盖「既大于 touchSlop 又不至于太钝」。
     *
     * <p>下限 90dp 是按车机（1m 观看、手指粗放）定的；手机上 90dp 相当整屏 1/4，
     * 会显得很难滑，所以手机版由 {@link PhoneLayout#phoneSwipeDp} 传更小的值进来。
     */
    static float minSwipePx(float touchSlopPx, float density) {
        return Math.max(touchSlopPx * 2f, MIN_SWIPE_DP * density);
    }

    /** 手机版最小滑动距离（像素）：48dp，约为手机屏宽的 1/8，拇指轻扫即触发。 */
    static float minSwipePxPhone(float touchSlopPx, float density) {
        return Math.max(touchSlopPx * 2f, PhoneLayout.PHONE_SWIPE_DP * density);
    }

    /**
     * 旁听一个触摸事件。调用方必须已经把它正常分发给了子控件。
     *
     * @param e        事件（Activity 坐标系）
     * @param areaView 允许滑动的区域；为 null 时全区生效
     * @param target   按下点落在哪个子控件上；只用来判断「在不在滚动容器里」，
     *                 具体见 {@link #insideScrollable}。为 null 时按空白区处理
     */
    void observe(MotionEvent e, View areaView, View target) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                multiTouch = false;
                // 起点落在列表/滚动区里 → 这次手势整体交给滚动，绝不切台。
                // 注意判据是「按下的控件自己或它的任意祖先可滚动」，
                // 不能只看命中的那一层：手指落在列表行的文字上时，
                // 命中的是行内的 TextView，它既不是 ListView 也不 clickable。
                inScrollable = insideScrollable(target);
                tracking = !inScrollable && !isButton(target) && insideArea(e, areaView);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                break;

            case MotionEvent.ACTION_UP:
                if (tracking && !multiTouch) {
                    Action a = decide(e.getX() - downX, e.getY() - downY, swipeThreshold,
                            inScrollable);
                    if (a == Action.NEXT) cb.onSwipeNext();
                    else if (a == Action.PREV) cb.onSwipePrev();
                }
                tracking = false;
                inScrollable = false;
                break;

            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                inScrollable = false;
                break;

            default:
                break;
        }
    }

    /**
     * 按下点是否在某个可滚动容器里（含自身）。
     *
     * <p>这是「列表滚动不切台」的唯一判据，必须沿<b>祖先链</b>一路往上找：
     * 手指落在列表某一行上时，dispatchTouchEvent 里命中的最深的 View 是行内的
     * TextView / LinearLayout，而它们既不是 ListView 也不 clickable。
     * 只判命中的那一层，列表里一滑就被当成换台手势。
     */
    static boolean insideScrollable(View v) {
        View cur = v;
        // 最多走 32 层：正常布局远达不到，这里是防异常布局成环时死循环
        for (int depth = 0; cur != null && depth < 32; depth++) {
            if (isScrollable(cur)) return true;
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** 可滚动容器：列表、网格、滚动视图（横向滚动也在内，用户是在翻它而不是换台）。 */
    private static boolean isScrollable(View v) {
        return v instanceof android.widget.AbsListView
                || v instanceof android.widget.ScrollView
                || v instanceof android.widget.HorizontalScrollView;
    }

    /** 按钮这类自带点击语义的控件：按下它就算点击，不能算滑动起点。 */
    private static boolean isButton(View v) {
        return v != null && v.isClickable() && !insideScrollable(v);
    }

    /**
     * 按下点是否落在「接收滑动的区域」里：横向必须在面板内，纵向只认上半区。
     *
     * <p><b>横向边界是 v1.9.11 补的</b>。旧实现只比了纵向比例：
     * {@code rel = (e.getY() - area.getTop()) / area.getHeight()}。
     * 车机是左右两栏布局，电台列表在<b>右</b>栏，而右栏的纵向位置正落在左栏的上半区，
     * 于是「在列表上纵向滑动」被判成「在左栏空白区滑动」→ 滑列表就切台。
     * 现象很迷惑：快速甩一下不切（位移小、没到阈值），慢慢拖到 90dp 就切一台。
     *
     * <p>抽成静态纯函数是为了能单测（不需要真机布局）。
     *
     * @param x,y                触点（Activity 坐标）
     * @param left,top,w,h       区域在 Activity 坐标系里的位置与尺寸
     */
    static boolean inActiveArea(float x, float y, int left, int top, int w, int h) {
        // 尺寸还没测量出来（w/h = 0）时不能除零，也谈不上「在区域里」
        if (w <= 0 || h <= 0) return false;
        float relX = (x - left) / (float) w;
        float relY = (y - top) / (float) h;
        return relX >= 0f && relX <= 1f && relY >= 0f && relY <= ACTIVE_HEIGHT_RATIO;
    }

    /** 按下点是否落在接收区内（按区域自身位置算，避免受坐标原点影响）。 */
    private boolean insideArea(MotionEvent e, View area) {
        if (area == null) return true;
        return inActiveArea(e.getX(), e.getY(), area.getLeft(), area.getTop(),
                area.getWidth(), area.getHeight());
    }

    /**
     * 根据位移判断意图（纯函数，便于单测）。
     *
     * @param dx       横向位移（右为正）
     * @param dy       纵向位移（下为正）
     * @param minSwipe 最小触发距离（像素）
     */
    static Action decide(float dx, float dy, float minSwipe) {
        return decide(dx, dy, minSwipe, false);
    }

    /**
     * 根据位移判断意图，并可声明「这次手势起点在滚动容器里」。
     *
     * <p>起点在滚动容器里时<b>只保留横向判定</b>：纵向位移是在翻列表，不是在换台。
     * 这是 v1.6.3 修的那个 bug —— 手指在列表上往下拨，只要纵向超过阈值就会切台，
     * 表现为「滑动列表会莫名其妙换台」。用户想看下面的台，结果台被换掉了。
     *
     * <p>横向仍保留：列表行本来就是竖排的，横向滑动不可能被列表消费，
     * 所以横向滑动当成换台是安全的，也让「列表上左右滑换台」继续可用。
     *
     * @param inScrollable 起点是否在可滚动容器内
     */
    static Action decide(float dx, float dy, float minSwipe, boolean inScrollable) {
        float adx = Math.abs(dx);
        float ady = Math.abs(dy);

        // 上下滑优先判断：竖屏列表翻台的直觉更强
        if (ady >= minSwipe && ady >= adx) {
            if (inScrollable) return Action.NONE;        // 列表里纵向 = 滚动，不是换台
            return dy < 0 ? Action.NEXT : Action.PREV;   // 上滑=下一台，下滑=上一台
        }
        if (adx >= minSwipe && adx > ady) {
            return dx < 0 ? Action.NEXT : Action.PREV;   // 左滑=下一台，右滑=上一台
        }
        return Action.NONE;
    }
}
