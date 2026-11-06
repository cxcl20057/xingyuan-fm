package com.rea.radio;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

/**
 * MediaSession 常驻性验证 —— 这是「方向盘按键 / 车机左下角媒体卡片」能否出现的根本前提。
 *
 * <p>回归点：之前播放器建在 Activity 里，Activity 一销毁 MediaSession 就 release，
 * 车机媒体中心自然看不到本应用。现在播放器归 {@link RadioService} 所有，
 * 本测试确保：服务活着时，界面销毁不影响播放器单例。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RadioServiceTest {

    @Test
    public void service_creates_and_holds_the_player() {
        ServiceController<RadioService> c =
                Robolectric.buildService(RadioService.class).create();
        RadioService svc = c.get();

        RadioPlayer p = RadioService.player();
        assertNotNull("服务启动后应能取到播放器", p);
        assertTrue("服务应处于运行态", RadioService.isRunning());
        assertSame("广播接收器与界面必须拿到同一个播放器", p, RadioPlayerHolder.get());

        c.destroy();
        assertNull("服务销毁后播放器引用应清空", RadioService.player());
        assertTrue("服务销毁后不应再报告运行中", !RadioService.isRunning());
    }

    @Test
    public void player_survives_when_activity_is_destroyed() {
        ServiceController<RadioService> c =
                Robolectric.buildService(RadioService.class).create();
        RadioPlayer beforeActivity = RadioService.player();
        assertNotNull(beforeActivity);

        // 打开界面再关掉：播放器必须还是原来那个，不能被界面 release 掉
        Robolectric.buildActivity(MainActivity.class).create().destroy();

        assertSame("界面销毁不得重建/释放播放器", beforeActivity, RadioService.player());
        c.destroy();
    }
}
