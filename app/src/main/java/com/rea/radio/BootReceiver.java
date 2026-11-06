package com.rea.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 车机开机自启。
 *
 * <p>目的不只是「开机就能听」，更关键的是让 {@link RadioService} 里的 MediaSession
 * <b>在车机媒体中心启动时就存在</b>。很多车机媒体中心只在开机扫描一遍媒体源，
 * 后启动的 App 不会被列进「可切换媒体」，方向盘按键也就永远派不到我们这里。
 *
 * <p>注意：只起服务、不主动出声（{@code autoPlay=false}），避免一上车就外放打扰。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !"com.ecarx.action.BOOT_COMPLETED".equals(a)) {
            return;
        }
        AppLog.i("自启", "收到开机广播：" + a);
        Intent svc = new Intent(context, RadioService.class).setAction(RadioService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);
    }
}
