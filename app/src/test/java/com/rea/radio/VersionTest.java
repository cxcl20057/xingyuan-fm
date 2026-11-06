package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 版本比较：更新功能判"有没有新版"全靠它，必须逐个边界钉死。 */
public class VersionTest {

    @Test
    public void basic_ordering() {
        assertTrue(Version.isNewer("1.7.1", "1.7.0"));
        assertTrue(Version.isNewer("1.8.0", "1.7.9"));
        assertTrue(Version.isNewer("2.0.0", "1.99.99"));
    }

    /** 这正是字符串比较会翻车的地方：'1' < '9'，但 1.10.0 比 1.9.0 新。 */
    @Test
    public void double_digit_segment_is_not_string_compare() {
        assertTrue("1.10.0 必须比 1.9.0 新（字符串比较会判反）",
                Version.isNewer("1.10.0", "1.9.0"));
        assertFalse(Version.isNewer("1.9.0", "1.10.0"));
        assertTrue(Version.isNewer("1.7.10", "1.7.9"));
    }

    @Test
    public void leading_v_is_ignored() {
        assertTrue(Version.isNewer("v1.8.0", "1.7.0"));
        assertTrue(Version.isNewer("1.8.0", "v1.7.0"));
        assertTrue(Version.isNewer("V1.8.0", "v1.7.0"));
    }

    @Test
    public void same_version_is_not_newer() {
        assertFalse(Version.isNewer("1.7.0", "1.7.0"));
        assertFalse(Version.isNewer("v1.7.0", "1.7.0"));
    }

    @Test
    public void old_version_is_not_newer() {
        assertFalse("远端更旧时不能提示更新（否则会诱使降级）",
                Version.isNewer("1.6.3", "1.7.0"));
    }

    /** 段数不同时补 0 比较。 */
    @Test
    public void missing_segments_count_as_zero() {
        assertFalse(Version.isNewer("1.7", "1.7.0"));
        assertTrue(Version.isNewer("1.8", "1.7.0"));
        assertTrue(Version.isNewer("1.7.1", "1.7"));
    }

    /** 预发布后缀被剥掉，等于同版本（当前只发正式版，见 Version 注释）。 */
    @Test
    public void prerelease_suffix_is_stripped() {
        assertFalse(Version.isNewer("1.7.0-beta1", "1.7.0"));
        assertTrue(Version.isNewer("1.8.0-rc1", "1.7.0"));
    }

    @Test
    public void null_and_empty_are_handled() {
        assertFalse("远端解析不出来时不提示更新", Version.isNewer(null, "1.7.0"));
        assertFalse(Version.isNewer("", "1.7.0"));
        assertFalse(Version.isNewer("   ", "1.7.0"));
        // 本机版本读不出来时，宁可提示有更新（便于用户自救升级）
        assertTrue(Version.isNewer("1.7.0", null));
    }

    /** 非数字段按 0 处理，不能抛异常把检查更新打断。 */
    @Test
    public void garbage_segment_does_not_crash() {
        assertTrue(Version.isNewer("1.x.0", "0.9.0"));
        assertEquals(0, Version.parse("1.x.0").compareTo(Version.parse("1.0.0")));
    }
}
