package com.rea.radio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 电台台标加载器（央广网 radio.cn 官方台标）。
 *
 * <p>台标地址来自接口的 {@code image} 字段，实测四川 44 个台全部可下载，
 * 为 400×400 的 PNG/JPEG，平均 56KB。加载策略三级：
 * <ol>
 *   <li><b>内存</b> LRU（8MB）：滚动列表不重复解码；</li>
 *   <li><b>磁盘</b> {@code files/logos/<md5>.img}：车机地库/隧道断网也能显示，
 *       不必每次启动都重新下载；</li>
 *   <li><b>网络</b>：两线程串行下载，避免和音频抢带宽。</li>
 * </ol>
 *
 * <p>下载失败或地址为空时回调 {@code onFail}，由界面回落到「台名首字」的圆形字标，
 * 保证任何情况下都有东西可看，不会出现空白方块。
 *
 * <p>注意：台标版权归各电台所有，本类只做"按官方地址下载 + 本地缓存展示"，
 * 不打包分发、不做二次加工。
 */
final class LogoLoader {

    interface Callback {
        /** 拿到台标；失败时 bitmap 为 null，调用方应回落到字标。 */
        void onLogo(ImageView view, String contentId, Bitmap bitmap);
    }

    private static final long MAX_DISK_AGE_MS = 30L * 24 * 60 * 60 * 1000; // 30 天
    private static final int MAX_EDGE = 320; // 台标最大显示 132dp@2x≈264px，320 足够且省内存

    /**
     * 宽高比超过这个值就认为是「横向长条标」（如四川交通广播 400×120 = 3.33），
     * 必须完整显示不能裁；否则当方图处理，裁成圆、铺满。
     * 实测取值：方图 1.0~1.6，长条 2.5~4.0，1.8 能干净分开。
     */
    private static final float WIDE_RATIO = 1.8f;

    private static LruCache<String, Bitmap> memory;
    private static ExecutorService pool;
    private static volatile Handler mainHandler;

    private LogoLoader() {
    }

    /**
     * 方形台标 → 圆形裁剪铺满（centerCrop），横向长条标 → 完整显示（fitCenter）。
     *
     * <p>这是根据实车截图修的：之前一律 fitCenter，结果方形台标缩在圆里、
     * 四个角露出底盘白底，看起来就是「图片太小没盖住圆」。
     */
    static void applyScaleType(ImageView view, Bitmap bmp) {
        if (view == null || bmp == null) return;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        float ratio = (w <= 0 || h <= 0) ? 1f : (float) w / (float) h;
        view.setScaleType(ratio > WIDE_RATIO
                ? ImageView.ScaleType.FIT_CENTER   // 长条：完整显示，两端不切
                : ImageView.ScaleType.CENTER_CROP); // 方图：铺满整圆，不留白边
    }

    /**
     * 懒加载且线程安全。
     *
     * <p>原来是无锁的 {@code if (xxx == null) xxx = new ...}，有两个隐患：
     * <ul>
     *   <li>台标加载会被后台网络线程调用，两三个线程同时首次进来会各自 new 一个
     *       LruCache，先放进去的那个被覆盖——图还在但缓存对不上，表现为反复重新下载；</li>
     *   <li>副作用更严重的：静态字段的初始化顺序不确定，
     *       {@code static final Handler MAIN = new Handler(Looper.getMainLooper())}
     *       会在类加载时就执行，若首次加载本类的是后台线程且此时主 Looper 尚未就绪，
     *       直接抛 NPE，之后整个类变成 {@code NoClassDefFoundError}。</li>
     * </ul>
     */
    private static synchronized void ensureInit() {
        if (memory == null) {
            int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8); // 1/8 堆
            memory = new LruCache<String, Bitmap>(Math.min(maxKb, 8 * 1024)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
        }
        if (pool == null) {
            pool = Executors.newFixedThreadPool(2);
        }
    }

    /** 主线程 Handler，同样延迟创建，理由见 {@link #ensureInit()}。可能为 null。 */
    private static Handler main() {
        Handler h = mainHandler;
        if (h != null) return h;
        Looper looper = Looper.getMainLooper();
        if (looper == null) return null;
        synchronized (LogoLoader.class) {
            if (mainHandler == null) mainHandler = new Handler(looper);
            return mainHandler;
        }
    }

    /**
     * 同步取「内存或磁盘」里已经有的台标，绝不发起网络请求。
     *
     * <p>给 MediaSession 元数据用：锁屏 / 通知栏 / 蓝牙车机要 ART 图，
     * 但设置元数据是在播放状态变化的同步流程里，不能等下载。
     * 本地没有就返回 null，界面上正常加载完成后会再写一次元数据。
     */
    static Bitmap cached(Context ctx, String contentId) {
        if (contentId == null || contentId.length() == 0) return null;
        ensureInit();
        Bitmap hit = memory.get(contentId);
        if (hit != null) return hit;
        Bitmap disk = fromDisk(ctx.getApplicationContext(), contentId);
        if (disk != null) memory.put(contentId, disk);
        return disk;
    }

    /**
     * 异步加载并设置到 {@code view}。
     *
     * <p>拿到图后会自动调 {@link #applyScaleType} 选好版面，调用方只管
     * {@code setImageBitmap} + 显示即可，不会出现方图缩在圆里的情况。
     *
     * @param contentId 用来判断回调回来时这个 View 是否已被复用给别的台
     */
    static void load(final Context ctx, final String contentId, final String url,
                     final ImageView view, final Callback cb) {
        if (view == null) return;
        if (contentId == null || contentId.length() == 0 || url == null || url.length() == 0) {
            cb.onLogo(view, contentId, null);
            return;
        }
        ensureInit();

        Bitmap hit = memory.get(contentId);
        if (hit != null) {
            applyScaleType(view, hit);
            cb.onLogo(view, contentId, hit);
            return;
        }

        final Context app = ctx.getApplicationContext();
        pool.execute(() -> {
            Bitmap bmp = null;
            try {
                bmp = fromDisk(app, contentId);
                if (bmp == null) {
                    bmp = fromNetwork(app, contentId, url);
                }
            } catch (Throwable t) {
                AppLog.w("台标", "加载失败 " + contentId + ": " + t.getMessage());
            }
            final Bitmap result = bmp;
            if (result != null) memory.put(contentId, result);
            Handler h = main();
            Runnable r = () -> {
                applyScaleType(view, result);
                cb.onLogo(view, contentId, result);
            };
            // 没有主 Looper 时不能直接丢弃回调，否则台标永远不显示；
            // 退而直接在当前线程执行（调用方是后台线程，但回调里只做 setImageBitmap）。
            if (h == null) r.run();
            else h.post(r);
        });
    }

    // ------------------------------------------------------------ 磁盘

    private static File fileFor(Context c, String contentId) {
        File dir = new File(c.getFilesDir(), "logos");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, md5(contentId) + ".img");
    }

    private static Bitmap fromDisk(Context c, String contentId) {
        File f = fileFor(c, contentId);
        if (f == null || !f.exists()) return null;
        if (System.currentTimeMillis() - f.lastModified() > MAX_DISK_AGE_MS) {
            // 太久没更新，认为可能已换台标，重新下载
            return null;
        }
        try {
            return decode(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 网络

    private static Bitmap fromNetwork(Context c, String contentId, String url) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9)");
            conn.connect();
            if (conn.getResponseCode() != 200) return null;
            in = conn.getInputStream();

            // 先落内存做解码，避免把大图整个读两遍
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 6 * 1024 * 1024) return null; // 异常大图直接放弃
                bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();

            Bitmap bmp = decodeBytes(data);
            if (bmp != null) {
                File f = fileFor(c, contentId);
                if (f != null) {
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(data);
                    } catch (Throwable ignored) { }
                }
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------ 解码

    /** 按最长边 MAX_EDGE 采样，避免把 400×400 原图整块塞进内存。 */
    private static Bitmap decode(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, o);
    }

    private static Bitmap decodeBytes(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static int sampleSize(int w, int h) {
        int s = 1;
        if (w <= 0 || h <= 0) return 1;
        while (Math.max(w, h) / s > MAX_EDGE) s *= 2;
        return s;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** 清缓存（诊断面板用）。 */
    static void clear(Context c) {
        if (memory != null) memory.evictAll();
        File dir = new File(c.getApplicationContext().getFilesDir(), "logos");
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }
}
