#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 离线验证：media3 的 HlsPlaylistParser 能不能解开成都广电的两层播放列表
#
#   ./scripts/hls-probe/run.sh
#
# 为什么需要这个：
#   v1.7.0 把播放内核从系统 MediaPlayer 换成 ExoPlayer，理由之一是
#   "ExoPlayer 自带 HLS 解析器，能处理主列表套二级列表这种结构"。
#   这个论断不能只靠单测（单测里没有真流），要拿真实播放列表过一遍解析器。
#
# 它做什么：
#   1. 从 Gradle 缓存里拼出 media3 + guava 的 classpath（不联网下载）
#   2. 用 android.jar 提供 android.* 的编译期符号
#   3. src/android/** 里的两个最小实现（Uri / TextUtils）在运行期顶掉 android.jar 的桩
#      —— android.jar 的方法体全是 throw new RuntimeException("Stub!")
#   4. 跑 HlsProbe：拉主列表 → 解析 → 拉二级列表 → 解析 → 试读首个分片
#
# 判据（全部满足才算通过）：
#   主列表解析为 HlsMultivariantPlaylist；
#   二级列表解析为 HlsMediaPlaylist，分片数 > 0，hasEndTag=false（直播）；
#   首个分片能读到数据且首字节为 0x47（MPEG-TS 同步字节）。
# ---------------------------------------------------------------------------
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

JAVA_HOME="${JAVA_HOME:-/root/.sdkman/candidates/java/current}"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-home}"

ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "找不到 android.jar：$ANDROID_JAR"; exit 1; }

echo "== 1/3 拼 classpath"
MEDIA3_CP="$(find "$GRADLE_USER_HOME/caches/9.3.0/transforms" -name 'media3-*-1.4.1-api.jar' 2>/dev/null | sort | tr '\n' ':')"
[ -n "$MEDIA3_CP" ] || { echo "Gradle 缓存里没有 media3 1.4.1，先跑一次 gradle :app:assembleDebug"; exit 1; }

GUAVA_JAR="$(find "$GRADLE_USER_HOME/caches/modules-2" -name 'guava-33.0.0-android.jar' 2>/dev/null | head -1)"
[ -n "$GUAVA_JAR" ] || GUAVA_JAR="$(find "$GRADLE_USER_HOME/caches/modules-2" -name 'guava-*.jar' 2>/dev/null | head -1)"
[ -n "$GUAVA_JAR" ] || { echo "找不到 guava jar"; exit 1; }

CP="${MEDIA3_CP}${GUAVA_JAR}:${ANDROID_JAR}"
echo "   media3 jar 数：$(echo "$MEDIA3_CP" | tr ':' '\n' | grep -c jar)"
echo "   guava：$(basename "$GUAVA_JAR")"

echo "== 2/3 编译探针"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$CP" -d "$WORK/out" \
    "$HERE/src/android/net/Uri.java" \
    "$HERE/src/android/text/TextUtils.java" \
    "$HERE/HlsProbe.java"

echo "== 3/3 解析真实播放列表"
# out 必须排在 android.jar 前面，否则桩实现会把真 Uri / TextUtils 盖掉
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WORK/out:$CP" HlsProbe
