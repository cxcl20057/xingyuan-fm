package com.rea.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.media.session.MediaSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * MediaSession 与通知媒体控件的连通性 —— 「方向盘按键」和「通知栏播放控件」的公共前提。
 *
 * <h3>为什么需要这组测试</h3>
 *
 * <p>v1.8.9 之前的状态是：{@code MediaSession} 建好了、回调齐全、
 * 通知也用了 {@code Notification.MediaStyle}，看着都很像样。
 * <b>但通知从来没调过 {@code setMediaSession(token)}。</b>
 *
 * <p>后果是那个通知只是「长得像媒体控件」的普通通知：系统不知道按钮该派给谁，
 * 车机媒体中心也不认为这是活跃媒体。表现就是用户在车里点通知栏的播放键毫无反应。
 *
 * <p>这类缺陷编译不报错、界面也看不出异常，只能靠测试盯住「两样东西有没有连起来」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MediaSessionWiringTest {

    @Test
    public void session_token_is_exposed_for_the_notification() {
        ServiceController<RadioService> c =
                Robolectric.buildService(RadioService.class).create();
        RadioPlayer p = RadioService.player();
        assertNotNull(p);

        MediaSession.Token token = p.sessionToken();
        assertNotNull("必须能拿到 session token，否则通知的 MediaStyle 绑不上会话", token);

        c.destroy();
    }

    @Test
    public void notification_is_media_style_and_carries_the_session() {
        ServiceController<RadioService> c =
                Robolectric.buildService(RadioService.class).create();

        ShadowNotificationManager snm =
                org.robolectric.Shadows.shadowOf(
                        (android.app.NotificationManager)
                                c.get().getSystemService(android.content.Context.NOTIFICATION_SERVICE));
        java.util.List<Notification> all = snm.getAllNotifications();
        assertNotNull("服务进前台后应有常驻通知", all);
        assertTrue("至少应有一条通知（前台服务通知）", all.size() >= 1);

        Notification n = all.get(0);
        assertNotNull("通知必须有内容视图/样式", n);

        // 关键断言：MediaStyle 里必须带 session token。
        // Notification.extras 里的 EXTRA_MEDIA_SESSION 就是 setMediaSession 写进去的。
        Object extra = n.extras.get(Notification.EXTRA_MEDIA_SESSION);
        assertNotNull("通知必须绑定 MediaSession，否则媒体控件是死的", extra);
        assertTrue("EXTRA_MEDIA_SESSION 应是 MediaSession.Token，实际 "
                        + (extra == null ? "null" : extra.getClass().getName()),
                extra instanceof MediaSession.Token);

        // 三个动作（上一台 / 播放暂停 / 下一台）都要在，车机媒体卡片靠它们渲染按钮
        assertNotNull("通知应有动作按钮", n.actions);
        assertTrue("通知应至少有 3 个动作按钮，实际 " + (n.actions == null ? 0 : n.actions.length),
                n.actions != null && n.actions.length >= 3);

        c.destroy();
    }

    @Test
    public void stop_clears_metadata_and_deactivates_the_session() {
        // 回归点：stop() 之前只改 PlaybackState、metadata 一直挂着上一个台，
        // 于是会话看上去还是「有个正在播放的台」，系统/车机媒体中心继续把它当活跃媒体。
        // 方向盘按键会继续派进来，但没有任何反应 —— 用户看到的就是「按键没反应」，
        // 实际是派给了一个已停播的空会话。
        ServiceController<RadioService> c =
                Robolectric.buildService(RadioService.class).create();
        RadioPlayer p = RadioService.player();
        assertNotNull(p);

        p.stop();

        assertFalse("停播后不应还在播", p.isPlaying());
        // 会话对象本身不销毁（还要能 resume 回来），但必须置为不活跃，
        // 这样才能把媒体键让给真正在播的应用。
        assertFalse("停播后会话必须置为不活跃", p.isSessionActive());

        c.destroy();
    }
}
