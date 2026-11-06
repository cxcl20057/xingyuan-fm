package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

/**
 * 组件类的可实例化性。
 *
 * <h3>这个测试是为一次真实闪退写的</h3>
 * v1.8.0 上线后手机（ColorOS 17）和车机（Android 9）都是「一点图标就退出」，
 * 9 次崩溃堆栈完全一样：
 * <pre>
 * FATAL EXCEPTION: main
 * java.lang.RuntimeException: Unable to get provider com.rea.radio.ApkProvider
 *   at android.app.ActivityThread.installProvider
 *   at android.app.ActivityThread.handleBindApplication
 *   Caused by: java.lang.IllegalAccessException:
 *     java.lang.Class&lt;com.rea.radio.ApkProvider&gt; is not accessible
 *     from java.lang.Class&lt;android.app.AppComponentFactory&gt;
 *     at java.lang.Class.newInstance(Native Method)
 * </pre>
 *
 * <p>原因是 {@code ApkProvider} 被写成了 {@code final class}（缺 {@code public}）。
 * 系统实例化 Manifest 声明的组件用的是 {@code Class.newInstance()}，
 * 它要求类 <b>public 且非 final</b>。
 *
 * <p>为什么以前没被拦住：Robolectric 建 Activity 走的是自己的模拟路径，
 * <b>不会</b>像真机那样用 {@code AppComponentFactory} 反射实例化每一个 Provider。
 * 所以必须单独写一条用例，直接从 Manifest 里把所有组件类捞出来逐个校验——
 * 这比「记得住」可靠。
 *
 * <p>顺带守住的另一类错误：类被改成抽象、或者类名拼错（{@code ClassNotFound}）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ComponentInstantiableTest {

    private static PackageInfo selfInfo() throws Exception {
        return ApplicationProvider.getApplicationContext()
                .getPackageManager()
                .getPackageInfo("com.rea.radio",
                        PackageManager.GET_ACTIVITIES
                                | PackageManager.GET_SERVICES
                                | PackageManager.GET_RECEIVERS
                                | PackageManager.GET_PROVIDERS);
    }

    /**
     * 遍历 Manifest 里声明的所有组件，逐个校验：
     * 类存在、是 public、不是 final、不是抽象、有 public 无参构造。
     *
     * <p>这四条正好是 {@code Class.newInstance()} 的全部前提。
     */
    @Test
    public void every_manifest_component_can_be_reflected_by_system() throws Exception {
        PackageInfo pi = selfInfo();
        int checked = 0;

        java.util.List<String> names = new java.util.ArrayList<>();
        if (pi.activities != null) for (android.content.pm.ActivityInfo a : pi.activities) names.add(a.name);
        if (pi.services != null) for (android.content.pm.ServiceInfo s : pi.services) names.add(s.name);
        if (pi.receivers != null) for (android.content.pm.ActivityInfo r : pi.receivers) names.add(r.name);
        if (pi.providers != null) for (android.content.pm.ProviderInfo p : pi.providers) names.add(p.name);

        for (String name : names) {
            Class<?> c = Class.forName(name);   // 类名写错直接在这里挂

            assertTrue(name + " 必须是 public，否则系统反射实例化会抛 IllegalAccessException",
                    Modifier.isPublic(c.getModifiers()));
            assertTrue(name + " 不能是 final —— Class.newInstance() 不接受 final 类",
                    !Modifier.isFinal(c.getModifiers()));
            assertTrue(name + " 不能是抽象类", !Modifier.isAbstract(c.getModifiers()));

            Constructor<?> ctor = c.getDeclaredConstructor();
            assertTrue(name + " 的构造器必须是 public",
                    Modifier.isPublic(ctor.getModifiers()));

            checked++;
        }

        assertTrue("Manifest 里应当至少声明 5 个组件（4 个 + 1 个 provider），实际 " + checked,
                checked >= 5);
    }

    /**
     * 单独钉住这次出事的那个类。
     *
     * <p>上面那条已经是通用规则了，这条是"点名守"：万一以后有人把通用用例删了、
     * 或者换了别的写法，这条会先叫。
     */
    @Test
    public void apk_provider_is_public_and_not_final() {
        Class<?> c = ApkProvider.class;
        assertTrue("ApkProvider 必须是 public", Modifier.isPublic(c.getModifiers()));
        assertTrue("ApkProvider 不能是 final", !Modifier.isFinal(c.getModifiers()));
    }

    /** FileProvider 的 authority 必须与 AndroidManifest 里声明的一致，否则运行期才炸。 */
    @Test
    public void provider_authority_matches_manifest() throws Exception {
        PackageInfo pi = selfInfo();
        assertNotNull("应能读到 provider 列表", pi.providers);
        String declared = null;
        for (android.content.pm.ProviderInfo p : pi.providers) {
            if (ApkProvider.class.getName().equals(p.name)) declared = p.authority;
        }
        assertNotNull("Manifest 里没有声明 ApkProvider", declared);
        assertEquals("authority 必须与代码里算出来的一致",
                ApkProvider.authority(ApplicationProvider.getApplicationContext()),
                declared);
    }

    /** Application 类也要能被系统实例化（当前没自定义，但规则同样适用）。 */
    @Test
    public void application_class_is_public_if_declared() throws Exception {
        ApplicationInfo ai = ApplicationProvider.getApplicationContext().getApplicationInfo();
        if (ai.className == null || ai.className.isEmpty()) return;   // 用默认 Application，无需校验
        Class<?> c = Class.forName(ai.className);
        assertTrue("Application 必须 public", Modifier.isPublic(c.getModifiers()));
        assertTrue("Application 不能 final", !Modifier.isFinal(c.getModifiers()));
    }
}
