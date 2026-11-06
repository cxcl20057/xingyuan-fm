#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
星愿收音机 —— 数据链路自检脚本（与 App 内 Java 实现 1:1 对齐）

用途：上车前在电脑上确认
  1) radio.cn 签名算法仍有效；
  2) 四川电台列表能拉到、排序符合“省级台优先”；
  3) 每个电台至少有一个可播地址（MP3 直连或 HLS）。
用法：python3 scripts/verify_streams.py [省份代码，默认 510000 四川]
"""
import hashlib
import json
import sys
import time
import urllib.parse
import urllib.request

KEY = "f0fc4c668392f9f9a447e48584c214ee"
BASE = "https://ytmsout.radio.cn"
UA = ("Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")


def sign(params: dict, ts: int) -> str:
    raw = "&".join(f"{k}={params[k]}" for k in sorted(params)) if params else ""
    text = (raw + "&" if raw else "") + f"timestamp={ts}&key={KEY}"
    return hashlib.md5(text.encode()).hexdigest().upper()


def get(path: str, params: dict):
    ts = int(time.time() * 1000)
    query = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"{BASE}{path}?{query}", headers={
        "Content-Type": "application/json",
        "equipmentId": "0000",
        "platformCode": "WEB",
        "timestamp": str(ts),
        "sign": sign(params, ts),
        "User-Agent": UA,
    })
    with urllib.request.urlopen(req, timeout=15) as r:
        body = json.loads(r.read().decode("utf-8"))
    if body.get("code") != 0:
        raise RuntimeError(f"接口异常: {body}")
    return body["data"]


def head_ok(url: str) -> tuple:
    """返回 (是否可播, 内容类型/说明)"""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=10) as r:
            head = r.read(120)
            code = r.status
        if head.startswith(b"#EXTM3U"):
            return True, "HLS"
        if head[:4] in (b"ID3\x03", b"ID3\x04") or head[:2] in (b"\xff\xfb", b"\xff\xf3", b"\xff\xf1"):
            return True, "MP3直连"
        return code == 200, f"未知({head[:16]!r})"
    except Exception as e:
        return False, f"失败({e.__class__.__name__})"


def main():
    code = int(sys.argv[1]) if len(sys.argv) > 1 else 510000
    provs = get("/web/appProvince/list/all", {})
    name = next((p["provinceName"] for p in provs if p["provinceCode"] == code), "未知")
    print(f"省份: {name} ({code})，共有 {len(provs)} 个省级选项")

    stations = get("/web/appBroadcast/list", {"categoryId": "0", "provinceCode": code})
    print(f"电台数: {len(stations)}\n")

    ok = bad = 0
    for i, s in enumerate(stations):
        urls = [u for u in (s.get("playUrlMulti"), s.get("mp3PlayUrlLow"),
                            s.get("mp3PlayUrlHigh"), s.get("playUrlLow")) if u]
        verdict = ("无地址", False)
        for u in urls:
            good, how = head_ok(u)
            if good:
                verdict = (how, True)
                break
        if verdict[1]:
            ok += 1
            print(f"  [{i+1:02d}] ✓ {s['title']:<24} {verdict[0]}")
        else:
            bad += 1
            print(f"  [{i+1:02d}] ✗ {s['title']:<24} {verdict[0]}  候选数={len(urls)}")

    print(f"\n可播 {ok} / 总 {len(stations)}，不可播 {bad}")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
