package com.rea.radio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 台标加载器：
 *  - 地址/ID 缺失时必须立刻回落，不能去发网络请求；
 *  - 磁盘缓存的文件名必须稳定（同一 contentId 永远命中同一文件），否则每次启动重下；
 *  - 采样策略：400×400 官方台标不能被整块解码（车机内存紧张）；
 *  - 缓存清理后磁盘目录里不留残留文件。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LogoLoaderTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        LogoLoader.clear(ctx);
    }

    /** 空 contentId → 直接回调 null（界面回落字标），不抛异常。 */
    @Test
    public void empty_content_id_falls_back_immediately() {
        ImageView iv = new ImageView(ctx);
        final boolean[] called = {false};
        final Bitmap[] got = {null};
        LogoLoader.load(ctx, null, "http://x/y.png", iv, (view, cid, bmp) -> {
            called[0] = true;
            got[0] = bmp;
        });
        assertTrue("必须同步回调，让界面立刻显示字标", called[0]);
        assertEquals(null, got[0]);
    }

    /** 空 url → 同样立刻回落。 */
    @Test
    public void empty_url_falls_back_immediately() {
        ImageView iv = new ImageView(ctx);
        final boolean[] called = {false};
        LogoLoader.load(ctx, "449", "", iv, (view, cid, bmp) -> called[0] = true);
        assertTrue(called[0]);
    }

    /** 同一 contentId 的缓存文件名必须稳定且与 contentId 绑定。 */
    @Test
    public void cache_file_name_is_stable_per_content_id() throws Exception {
        Method m = LogoLoader.class.getDeclaredMethod("md5", String.class);
        m.setAccessible(true);
        String a1 = (String) m.invoke(null, "449");
        String a2 = (String) m.invoke(null, "449");
        String b = (String) m.invoke(null, "110449");

        assertEquals("同一 ID 必须命中同一文件", a1, a2);
        assertTrue("不同 ID 必须落到不同文件", !a1.equals(b));
        assertEquals("应为 32 位小写十六进制", 32, a1.length());
        assertTrue(a1.matches("[0-9a-f]{32}"));
    }

    /** 400×400 官方台标应被采样到 ≤320，而不是整图进内存。 */
    @Test
    public void sample_size_keeps_bitmap_small() throws Exception {
        Method m = LogoLoader.class.getDeclaredMethod("sampleSize", int.class, int.class);
        m.setAccessible(true);

        assertEquals("400×400 只降一级采样", 2, (int) (Integer) m.invoke(null, 400, 400));
        assertEquals("264px 以内不采样", 1, (int) (Integer) m.invoke(null, 264, 264));
        assertEquals("超大图继续降级：1600/8=200", 8, (int) (Integer) m.invoke(null, 1600, 1600));
        assertEquals("320 边界不降级", 1, (int) (Integer) m.invoke(null, 320, 320));
        assertEquals("321 跨过边界降一级", 2, (int) (Integer) m.invoke(null, 321, 321));
        assertEquals("非法尺寸兜底 1", 1, (int) (Integer) m.invoke(null, 0, 0));
    }

    /** 真给一张 400×400 的 PNG：磁盘缓存可读写，且读回后尺寸被压到 320 以内。 */
    @Test
    public void disk_cache_roundtrip_shrinks_bitmap() throws Exception {
        Bitmap src = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
        src.eraseColor(0xFFE8492A);

        File dir = new File(ctx.getFilesDir(), "logos");
        assertTrue(dir.exists() || dir.mkdirs());
        File f = new File(dir, "smoke.img");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            assertTrue(src.compress(Bitmap.CompressFormat.PNG, 100, fos));
        }

        Method decode = LogoLoader.class.getDeclaredMethod("decode", String.class);
        decode.setAccessible(true);
        Bitmap out = (Bitmap) decode.invoke(null, f.getAbsolutePath());

        assertNotNull(out);
        assertTrue("最长边必须 ≤320", Math.max(out.getWidth(), out.getHeight()) <= 320);
        assertEquals("采样后应接近 200×200（400/2）", 200, out.getWidth());
    }

    /** 清缓存必须真的删干净。 */
    @Test
    public void clear_removes_disk_files() throws Exception {
        File dir = new File(ctx.getFilesDir(), "logos");
        assertTrue(dir.exists() || dir.mkdirs());
        File f = new File(dir, "junk.img");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(new byte[]{1, 2, 3});
        }
        assertTrue(f.exists());

        LogoLoader.clear(ctx);

        File[] left = dir.listFiles();
        assertTrue("清理后不应残留文件", left == null || left.length == 0);
    }

    /** 台名字标兜底：只取一个字符，且不因空格/标点得到空串。 */
    @Test
    public void first_glyph_returns_single_char() {
        assertEquals("四", MainActivity.firstGlyph("四川交通广播"));
        assertEquals("9", MainActivity.firstGlyph(" 97.0 音乐台")); // 数字也算有效字符
        assertEquals("文", MainActivity.firstGlyph("—— 文艺 ——")); // 跳过破折号取到汉字
        assertEquals("F", MainActivity.firstGlyph(null));
        assertEquals("F", MainActivity.firstGlyph(""));
    }
}
