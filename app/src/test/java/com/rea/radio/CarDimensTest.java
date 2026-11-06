package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校验 values-sw1080dp/dimens.xml 与 values/dimens.xml 的 car_* 尺寸成 2 倍关系。
 *
 * <p>背景：实测星愿车机（IHU801G）真实密度是 160 dpi（mdpi / 1x），可用内容区
 * 1920×968dp、整屏 sw=1080dp。1dp 物理尺寸恒定，密度从 2x 掉到 1x 意味着屏幕 dp
 * 总量翻倍，同一套 dp 值在这台车上只显示成一半大。因此给 sw>=1080dp 单独放了一套
 * ×2 放大值（values-sw1080dp/dimens.xml）。
 *
 * <p>两份文件手写 40 多个条目，×2 很容易抄错一两个数；这个纯逻辑测试逐条比对，
 * 保证「sw1080dp 的每个值恰好是默认值的 2 倍、单位一致、条目一一对应」。
 * 不依赖 Robolectric 渲染，Windows / Linux 都能跑。
 */
public class CarDimensTest {

    /** 抓取 {@code <dimen name="car_xxx">123dp</dimen>} 三部分。 */
    private static final Pattern DIMEN = Pattern.compile(
            "<dimen name=\"(car_[a-z0-9_]+)\">([0-9]+(?:\\.[0-9]+)?)([a-z]+)</dimen>");

    @Test
    public void sw1080_values_are_exactly_double_of_defaults() throws Exception {
        Map<String, String[]> base = parse(findRes("values/dimens.xml"));
        Map<String, String[]> big = parse(findRes("values-sw1080dp/dimens.xml"));

        assertFalse("默认 dimens 不能为空", base.isEmpty());
        assertTrue("sw1080dp dimens 不能为空", big.size() > 0);
        assertEquals("两份 dimens 的 car_* 条目必须一一对应",
                base.size(), big.size());

        for (Map.Entry<String, String[]> e : base.entrySet()) {
            String name = e.getKey();
            assertTrue("sw1080dp 缺少条目 " + name, big.containsKey(name));
            String[] b = e.getValue(); // {数值, 单位}
            String[] s = big.get(name);
            assertEquals(name + " 的单位要一致", b[1], s[1]);
            double bv = Double.parseDouble(b[0]);
            double sv = Double.parseDouble(s[0]);
            assertEquals(name + " 在 sw1080dp 下应是默认值的 2 倍",
                    bv * 2, sv, 0.01);
        }
    }

    /** 解析 dimens 文件为 name → {数值, 单位}（保持出现顺序）。 */
    private static Map<String, String[]> parse(File f) throws Exception {
        String xml = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        Map<String, String[]> m = new LinkedHashMap<>();
        Matcher mt = DIMEN.matcher(xml);
        while (mt.find()) {
            m.put(mt.group(1), new String[]{mt.group(2), mt.group(3)});
        }
        return m;
    }

    /** 兼容 Gradle 测试工作目录是模块根（src/…）还是项目根（app/src/…）。 */
    private static File findRes(String rel) {
        File f = new File("src/main/res/" + rel);
        if (f.exists()) return f;
        f = new File("app/src/main/res/" + rel);
        if (f.exists()) return f;
        throw new AssertionError("找不到资源文件: " + rel);
    }
}
