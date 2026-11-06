package com.rea.radio;

/**
 * 台名的显示格式。
 *
 * <h3>v1.8.3：不再在台名后面拼频率</h3>
 * 旧版会显示成「四川城市之音（FM102.6）」，频率用 {@code AbsoluteSizeSpan} 挂小两号。
 * 用户要求去掉这个后缀，理由也站得住：
 * <ul>
 *   <li>手机竖屏一行只有约 200dp 可用宽度，「四川交通广播（FM101.7）」必然被截成
 *       「四川交通广播（FM...」—— 频率永远看不全，等于没显示，还把台名也挤没了；</li>
 *   <li>频率本来就不是接口字段（{@link FrequencyTable} 是硬编码表，只覆盖 11 个台），
 *       有的台有、有的台没有，列表看起来参差不齐；</li>
 *   <li>台名本身就是用户找台的主要依据，把宽度留给它更划算。</li>
 * </ul>
 *
 * <p>{@link FrequencyTable} 仍然保留（{@code LocalStations} 的注释引用它，也有单测覆盖），
 * 只是不再参与台名渲染。
 *
 * <p><b>保留本类而不是直接删掉的理由</b>：调用点有三处（播放卡、列表行、通知栏元数据），
 * 统一走这里，将来若要把频率放到副标题或详情页，改一处就够；直接在各处写 {@code s.title}
 * 反而会散落成多处赋值。
 */
final class StationTitle {

    private StationTitle() {
    }

    /**
     * 纯文本形式 —— 就是台名本身。
     *
     * <p>用于通知栏、锁屏、诊断日志、无障碍朗读这些<b>只要字符串</b>的地方。
     * v1.8.3 起不再追加「（FMxxx）」。
     */
    static String plain(String title) {
        return title == null ? "" : title;
    }

    /**
     * 富文本形式 —— v1.8.3 起同样只是台名，不再挂字号跨度。
     *
     * <p>保留返回 {@code CharSequence} 是为了调用方（{@code TextView.setText}）不用改签名。
     */
    static CharSequence styled(String title) {
        return title == null ? "" : title;
    }

    /**
     * 从「可能带频率后缀」的旧字符串里取出台名。
     *
     * <p>仍然需要它：v1.8.2 及更早版本把「四川城市之音（FM102.6）」这类串写进过
     * 收藏（{@code FavoriteStore}）和播放偏好（{@code PlayPrefs}），升级后读到的是旧数据。
     * 不清洗的话，老用户的收藏里会一直挂着频率后缀，和新数据混在一起。
     */
    static String strip(String shown) {
        if (shown == null) return "";
        int i = shown.lastIndexOf('（');
        if (i <= 0) return shown;
        return shown.substring(0, i);
    }
}
