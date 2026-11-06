package com.rea.radio;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * 把下载好的 APK 以 {@code content://} 形式交给系统安装器。
 *
 * <h3>为什么不能直接传 {@code file://}</h3>
 * Android 7.0（API 24）起禁止跨应用传 {@code file://}，
 * 会抛 {@code FileUriExposedException}。必须用 {@link FileProvider}
 * 把文件"授权"出去，并附带 {@code FLAG_GRANT_READ_URI_PERMISSION}。
 *
 * <h3>authority 为什么要拼包名</h3>
 * {@code FileProvider} 的 authority 必须在整个设备上唯一。
 * 直接写死一个固定串，一旦用户装了别的也用同名的应用就会冲突装不上。
 * 拼上 {@code applicationId} 是标准做法：
 * {@code com.rea.radio.fileprovider}。
 *
 * <h3>只暴露 update/ 这一个目录</h3>
 * paths 里只声明了 {@code external-files-path name="update" path="update/"}，
 * 也就是只能读到 {@code getExternalFilesDir(null)/update/} 下的文件，
 * 其余私有目录（缓存、日志、收藏数据）一个都共享不出去。
 * <h3>为什么必须是 public（这里是踩过的坑）</h3>
 * 系统在 {@code handleBindApplication → installProvider} 阶段实例化 Manifest 里声明的
 * Provider，走的是 {@code Class.newInstance()}，它<b>要求目标类 public 且非 final</b>。
 *
 * <p>本类最初被写成 {@code final class ApkProvider}（包级私有），结果一装上去就闪退，
 * 堆栈是：
 * <pre>
 * java.lang.RuntimeException: Unable to get provider com.rea.radio.ApkProvider
 *   Caused by: java.lang.IllegalAccessException:
 *     java.lang.Class&lt;com.rea.radio.ApkProvider&gt; is not accessible
 *     from java.lang.Class&lt;android.app.AppComponentFactory&gt;
 *     at java.lang.Class.newInstance(Native Method)
 * </pre>
 *
 * <p>要命的是这个崩溃发生在 {@code Application.onCreate} <b>之前</b>，
 * 进程根本活不到画界面，所以表现为「一点就退出」，与系统版本、厂商 ROM 无关
 * （手机 ColorOS 17 与车机 Android 9 崩得一模一样）。
 *
 * <p>教训：<b>凡是 AndroidManifest 里声明过的组件
 * （Activity / Service / Receiver / Provider / Application），类必须是 public 且不能 final。</b>
 * 这一点单元测试和 Robolectric 都测不出来——它们不走系统的 provider 实例化路径。
 */
public class ApkProvider extends FileProvider {

    /** APK 的 MIME 类型，安装器靠它认出这是安装包。 */
    static final String MIME = "application/vnd.android.package-archive";

    /** 与本类在 AndroidManifest 里声明的 authority 保持一致。 */
    static String authority(Context ctx) {
        return ctx.getPackageName() + ".fileprovider";
    }

    /** 把一个 APK 文件转成可交给安装器的 Uri。 */
    static Uri uriFor(Context ctx, File apk) {
        return getUriForFile(ctx, authority(ctx), apk);
    }
}
