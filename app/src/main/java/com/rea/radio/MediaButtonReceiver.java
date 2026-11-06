package com.rea.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * 方向盘 / 蓝牙耳机按键的<b>兜底入口</b>（服务还没起来时用来唤醒它）。
 *
 * <h3>v1.9.7 起：这个接收器<b>不再截断广播</b></h3>
 *
 * <p>老版本在清单里给它写了 {@code android:priority="2147483647"}（有序广播的最高优先级），
 * 处理完再调 {@code abortBroadcast()}。后果是：<b>排在它后面的接收者永远收不到按键</b>，
 * 其中包括车机的蓝牙电话应用。而星愿车机上「来电时按方向盘下一曲接听、上一曲挂断」
 * 正是靠电话应用收到这两个键才成立的 —— 于是电话接不起来，按键被别处（原车收音机）
 * 消费掉。
 *
 * <p>对照逆向对象「星愿车控完全正常酷我音乐 6.0.0.9」：它的清单里
 * <b>根本没有 ACTION_MEDIA_BUTTON 的静态接收者</b>，媒体键全靠 EAS 的
 * {@code MusicClient.onNext/onPrevious} 与标准 MediaSession。也就是说，
 * 一个「优先级拉满 + abort」的广播接收者对车机来说不是必需，而是有害。
 *
 * <p>现在的做法：
 * <ul>
 *   <li>清单里去掉 {@code priority}（回到默认 0，不再插队）；</li>
 *   <li><b>永不</b> {@code abortBroadcast()}——按键继续往后传，电话应用照样收得到；</li>
 *   <li>会话活跃时这里<b>只记日志不动作</b>，避免同一次按键被「MediaSession + 广播」
 *       各处理一次（那会一次按键切两个台）。</li>
 * </ul>
 */
public class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            AppLog.w("按键", "收到非 MEDIA_BUTTON 广播: "
                    + (intent == null ? "null" : intent.getAction()));
            return;
        }
        KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        RadioPlayer p = RadioPlayerHolder.get();

        if (ev != null) {
            AppLog.key("按键", "广播 MEDIA_BUTTON keyCode=" + ev.getKeyCode()
                    + " action=" + ev.getAction()
                    + " repeat=" + ev.getRepeatCount()
                    + " 播放器=" + (p == null ? "未启动" : "就绪"));
        }

        // 通话/响铃期间一个键都不碰：不处理、不拉起服务、也不截断广播。
        // 方向盘「下一曲接听、上一曲挂断」要靠这些键落到通话那边才成立。
        if (CallGuard.shouldYieldKeys(context)) {
            AppLog.key("按键", "通话中 → 媒体键原样放行（交给通话界面）"
                    + "（" + CallGuard.describe() + "）");
            return;
        }

        if (p == null) {
            // 服务被回收后按方向盘：先把服务拉起，本次按键丢弃，
            // 下一次按键即可正常工作，避免用户以为按键彻底坏了。
            AppLog.w("按键", "播放器未就绪 → 拉起前台服务");
            Intent svc = new Intent(context, RadioService.class)
                    .setAction(RadioService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
            return;
        }

        // 会话还活着 → 媒体键由 MediaSession 的 onMediaButtonEvent 处理，
        // 这里什么都不做。两边都处理会让一次按键切两个台（实测过）。
        if (p.isSessionActive()) {
            AppLog.key("按键", "会话活跃 → 广播这条只记录，动作交给 MediaSession");
            return;
        }

        if (ev == null || ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0) {
            return;
        }

        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                p.toggle();
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                p.resume();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                p.pause();
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                p.next();
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                p.prev();
                break;
            default:
                AppLog.w("按键", "未映射的按键 keyCode=" + ev.getKeyCode());
                break;
        }
        // 注意：这里<b>不再</b> abortBroadcast()，按键继续往后传。
    }
}
