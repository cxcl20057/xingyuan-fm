#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 抓「闪退」现场日志（在电脑上跑，手机/车机用 USB 连电脑）
#
#   Linux/macOS:  ./scripts/crash_log.sh
#   Windows:      在 Git Bash 里跑，或者照下面注释手动执行两条命令
#
# 做的事：
#   1. 确认设备已连接（adb devices）
#   2. 清空日志缓冲
#   3. 拉起 App，等它闪退
#   4. 把与崩溃相关的行全部抓下来，存成 crash-<时间>.txt
#
# 抓到后把这个 txt 发给我就行。里面没有隐私内容（只有包名、异常堆栈、类名）。
# ---------------------------------------------------------------------------
set -uo pipefail

PKG="com.rea.radio"
ADB="${ADB:-adb}"

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "找不到 adb。装法："
  echo "  - 装了 Android Studio：在 SDK 的 platform-tools 目录里"
  echo "  - 或者直接下载 platform-tools 解压后把目录加进 PATH"
  echo "  然后重跑：ADB=/你的路径/adb $0"
  exit 1
fi

echo "▶ 检查设备…"
"$ADB" devices
if ! "$ADB" shell echo ok >/dev/null 2>&1; then
  echo "✗ 没有可用设备。请确认："
  echo "  1) USB 线是数据线（不是只管充电的）"
  echo "  2) 手机上开了「开发者选项 → USB 调试」，并在弹窗里点了允许"
  echo "     车机：连点版本号进开发者模式，再开 USB 调试"
  exit 1
fi

OUT="crash-$(date +%Y%m%d-%H%M%S).txt"
echo "▶ 清空日志缓冲…"
"$ADB" logcat -c

echo "▶ 拉起 App（如果它闪退，请在手机上再手动点一次图标）…"
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "▶ 等 15 秒收集日志…"
sleep 15

{
  echo "==================== 设备信息 ===================="
  "$ADB" shell getprop ro.product.model 2>/dev/null
  "$ADB" shell getprop ro.build.version.release 2>/dev/null
  "$ADB" shell getprop ro.build.version.sdk 2>/dev/null
  "$ADB" shell getprop ro.product.cpu.abi 2>/dev/null
  echo
  echo "==================== 已安装版本 ===================="
  "$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionName|versionCode|targetSdk" | head -5
  echo
  echo "==================== 崩溃日志 ===================="
  "$ADB" logcat -d -v time 2>/dev/null \
    | grep -E "AndroidRuntime|FATAL|$PKG|ActivityManager.*(died|crash|Process)|PhoneWindow|InflateException|ClassNotFound|NoSuchMethod|NoSuchField|SecurityException|Resources\\\$NotFound" \
    | tail -400
} > "$OUT"

echo
echo "✅ 已保存：$(pwd)/$OUT"
echo "   大小 $(wc -l < "$OUT") 行"
echo
echo "如果里面能看到「FATAL EXCEPTION」和「Caused by」，那就够了，直接发给我。"
echo "如果文件几乎是空的，说明崩溃发生在更底层，换成下面这条把完整日志抓下来："
echo "   $ADB logcat -d > full.log"
