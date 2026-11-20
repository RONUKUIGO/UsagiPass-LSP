#!/bin/sh
# 构建 UsagiPass (LSP)
#   sh build.sh              开发版（有设置界面/桌面图标，日志可开关）
#   sh build.sh --release    发行版（无设置界面/无桌面图标/无开关）
#
# 发行版签名：口令不落盘，走工作区密钥规范（docs/KEYSTORE.md）：
#   wskeys run -- sh build.sh --release
set -e

WS=/storage/emulated/0/Tools/LSPilot/projects/usagipass
BUILD=/opt/work/usagipass
OUT=/storage/emulated/0/Tools/LSPilot/outputs/apk
MODE=debug
[ "$1" = "--release" ] && MODE=release

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
export HOME=/home
export GRADLE_OPTS="-Djava.io.tmpdir=/opt/tmp"

mkdir -p /opt/tmp "$BUILD" "$OUT"
echo "▶ 同步源码 → $BUILD"
rm -rf "$BUILD/app" "$BUILD/gradle.properties" "$BUILD/local.properties" \
       "$BUILD/settings.gradle.kts" "$BUILD/build.gradle.kts"
cp -r "$WS/lsp/." "$BUILD/"

TASK=assembleDebug
[ "$MODE" = "release" ] && TASK=assembleRelease
echo "▶ gradle $TASK"
cd "$BUILD"
gradle --console=plain --no-daemon "$TASK" 2>&1 | tail -20

if [ "$MODE" = "release" ]; then
  APK="$BUILD/app/build/outputs/apk/release/app-release.apk"
  [ -f "$APK" ] || APK="$BUILD/app/build/outputs/apk/release/app-release-unsigned.apk"
else
  APK="$BUILD/app/build/outputs/apk/debug/app-debug.apk"
fi
[ -f "$APK" ] || { echo "✗ 没有产物"; exit 1; }

VC=$(grep -oE 'versionCode = [0-9]+' "$WS/lsp/app/build.gradle.kts" | grep -oE '[0-9]+')
VER=$(grep -oE 'versionName = "[^"]+"' "$WS/lsp/app/build.gradle.kts" | sed 's/.*= "//;s/"//')
DST="$OUT/usagipass-v${VC}${MODE_EXTRA:+-$MODE}.apk"
[ "$MODE" = "release" ] && DST="$OUT/usagipass-v${VC}-release.apk" || DST="$OUT/usagipass-v${VC}-dev.apk"
cp -f "$APK" "$DST"
echo "✓ APK: $DST"
echo "  $VER ($MODE)  size=$(stat -c %s "$DST")  sha256=$(sha256sum "$DST" | cut -d' ' -f1)"
case "$(basename "$APK")" in
  *unsigned*) echo "  ⚠ 未签名：用 wskeys sign-apk \"$DST\" 后签" ;;
esac
