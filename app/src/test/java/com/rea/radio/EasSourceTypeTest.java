package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.net.Uri;

import com.ecarx.eas.sdk.mediacenter.AbstractMusicPlaybackInfo;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 媒体源类型与小窗字段的单测（v1.9.7）。
 *
 * <h3>为什么要单独锁这两件事</h3>
 *
 * <p><b>媒体源类型</b>：它决定车机把方向盘按键派给谁。老版本报 11（数字广播），
 * 方向盘「上一曲/下一曲」被当<b>调台</b>在 MCU 层消费掉，来电时按键到不了通话界面。
 * 逆向酷我车机版确认它报的是 6（在线音乐），所以默认值必须是 6 —— 这条一旦被
 * 谁改回 11，就是「电话接不起来」的回归，而编译和界面都看不出来。
 *
 * <p><b>小窗字段</b>：酷我的小窗有封面和第二行文字，靠的是 {@code getArtwork()}
 * 返回<b>网络 Uri</b>、{@code getLyricContent()} 返回一行文字。本应用早期这两个
 * 都是 null，于是点开媒体条后的小窗是一块空白。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EasSourceTypeTest {

    @After
    public void restore() {
        // sourceType 是静态的，别污染同进程里的其它用例
        EasPlaybackInfo.setSourceType(EasPlaybackInfo.TYPE_ONLINE);
    }

    private static EasPlaybackInfo bean(Uri artwork, String lyric) {
        return new EasPlaybackInfo("四川交通广播", "四川省", "FM 直播",
                AbstractMusicPlaybackInfo.PLAYBACK_STATUS_PLAYING, 3, "com.rea.radio",
                "449", artwork, lyric, null);
    }

    @Test
    public void default_source_type_is_online_music_not_digital_radio() {
        assertEquals("默认必须是「在线音乐」6 —— 与酷我车机版一致",
                6, EasPlaybackInfo.sourceType());
        assertEquals(com.ecarx.eas.sdk.mediacenter.SourceType.SOURCE_TYPE_ONLINE,
                EasPlaybackInfo.sourceType());
        assertEquals("11 只作为车机不接受 6 时的回落值",
                11, EasPlaybackInfo.TYPE_DAB);
    }

    @Test
    public void playback_info_reports_the_current_source_type() {
        assertEquals(6, bean(null, null).getSourceType());

        EasPlaybackInfo.setSourceType(EasPlaybackInfo.TYPE_DAB);
        assertEquals("回落成 11 之后上报的也必须是 11",
                11, bean(null, null).getSourceType());
    }

    @Test
    public void artwork_and_lyric_are_passed_through_to_the_mini_window() {
        Uri art = Uri.parse("https://ytmsout.radio.cn/img/449.png");
        EasPlaybackInfo b = bean(art, "正在直播：音乐在路上");
        assertSame("网络台标要原样透传（小窗靠它画封面）", art, b.getArtwork());
        assertEquals("正在直播：音乐在路上", b.getLyricContent());
        assertEquals("小窗第二行也用同一个值", "正在直播：音乐在路上", b.getCurrentLyricSentence());
    }

    @Test
    public void missing_artwork_stays_null() {
        EasPlaybackInfo b = bean(null, null);
        assertNull("没有网络台标时返回 null，让媒体中心画占位块", b.getArtwork());
        assertNull(b.getLyricContent());
    }
}
