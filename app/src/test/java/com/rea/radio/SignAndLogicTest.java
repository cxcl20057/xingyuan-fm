package com.rea.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 纯逻辑单测：签名算法 + 省名归一化。
 * 用固定 ts，期望值与网页 api.js(CryptoJS) 实算结果逐位比对。
 */
public class SignAndLogicTest {

    @Test
    public void sign_withParams_matchesWebJs() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("categoryId", "0");
        p.put("provinceCode", "510000");
        assertEquals("7510ADFB705F022CC70D7CEF8C0CBFB6",
                RadioCnApi.sign(p, 1792649000000L));
    }

    @Test
    public void sign_withoutParams_matchesWebJs() {
        assertEquals("ED88142A3CBAA63F3287C6918EABB34B",
                RadioCnApi.sign(new LinkedHashMap<String, String>(), 1792649000000L));
    }

    @Test
    public void provinceNormalize_handlesSuffixAndIpFormats() {
        assertEquals("四川", RegionResolver.normalize("四川省"));
        assertEquals("四川", RegionResolver.normalize("四川"));
        assertEquals("北京", RegionResolver.normalize("北京市"));
        assertEquals("内蒙古", RegionResolver.normalize("内蒙古自治区"));
        assertEquals("四川", RegionResolver.normalize("Sichuan"));
        assertEquals(null, RegionResolver.normalize("Unknown Region"));
    }
}
