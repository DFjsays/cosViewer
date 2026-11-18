#!/bin/bash
# cos下载 —— 手机本机构建 APK
set -euo pipefail

ROOT=/root/workspace
APP=$ROOT/cosdl
# 读取本机配置（secret.properties 不入库，缺失时用 example 模板）
read_prop() {
  local key="$1" def="$2" f v
  f="$APP/secret.properties"
  [ -f "$f" ] || f="$APP/secret.properties.example"
  v="$(grep -E "^${key}=" "$f" 2>/dev/null | head -1 | cut -d= -f2- || true)"
  [ -z "$v" ] && v="$def"
  printf '%s' "$v"
}

SDK=$ROOT/android-build/sdk
BT=$SDK/build-tools/android-14
PLATFORM=$SDK/platforms/android-34/android.jar
OUT=$ROOT/android-build/cosdl-out
KEYSTORE="$(read_prop build.keystore "$ROOT/android-build/debug.keystore")"
KS_PASS="$(read_prop build.keystorePass "android")"

MIN_SDK=26
TARGET_SDK=28
VERSION_CODE=39
VERSION_NAME=10.5

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/5] aapt：编译资源与清单"
aapt package -f -m \
  -J "$OUT/gen" \
  -M "$APP/AndroidManifest.xml" \
  -S "$APP/res" \
  -A "$APP/assets" \
  -I "$PLATFORM" \
  -F "$OUT/base.apk" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE \
  --version-name $VERSION_NAME

echo "[1.5/5] 生成 Secret 常量（来自 secret.properties）"
SECRET_JAVA="$OUT/gen/com/dsh/cosdl/Secret.java"
mkdir -p "$(dirname "$SECRET_JAVA")"
cat > "$SECRET_JAVA" <<JAVA
package com.dsh.cosdl;

/** 构建时自动生成，不入库。 */
final class Secret {
    static final String API_HOST = "$(read_prop api.host '')";
    static final String API_KEY = "$(read_prop api.key '')";
    static final String API_CLOUD = "$(read_prop api.cloud '')";
    static final String TOKEN_PREFIX = "$(read_prop api.tokenPrefix 'CosAppMakeBigMoney')";
    static final String UA = "$(read_prop api.ua 'cosav/3.0.24')";
    static final String GH_OWNER = "$(read_prop update.owner '')";
    static final String GH_REPO = "$(read_prop update.repo '')";
}
JAVA

echo "[2/5] javac：编译 Java 源码"
find "$APP/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -nowarn -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1 || true
if grep -q "error:" "$OUT/javac.log"; then
  echo "!! javac 失败："
  sed -n '1,40p' "$OUT/javac.log"
  exit 1
fi
if [ ! -d "$OUT/classes/com/dsh/cosdl" ]; then echo "!! javac 没有产出"; exit 1; fi

echo "[3/5] d8：生成 classes.dex"
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
java -cp "$ROOT/android-build/r8-new.jar" com.android.tools.r8.D8 --release \
  --min-api $MIN_SDK --lib "$PLATFORM" \
  --output "$OUT/dex" "$OUT/classes.jar" 2>&1 | grep -v -E '^Warning' || true
if [ ! -f "$OUT/dex/classes.dex" ]; then echo "!! d8 没有产出 classes.dex"; exit 1; fi

echo "[4/5] 打包进 APK"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex)
if [ -d "$APP/libs/arm64-v8a" ]; then
  mkdir -p "$OUT/lib/arm64-v8a"
  cp "$APP/libs/arm64-v8a/"*.so "$OUT/lib/arm64-v8a/"
  (cd "$OUT" && zip -q -r -X "$OUT/unsigned.apk" lib)
fi

echo "[4.5/5] zipalign"
python3 "$APP/align.py" "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] apksigner：签名"
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KEYSTORE" --ks-pass pass:$KS_PASS --key-pass pass:$KS_PASS \
  --ks-key-alias androiddebugkey \
  --min-sdk-version $MIN_SDK \
  --out "$OUT/cosViewer.apk" "$OUT/aligned.apk"

java -jar "$BT/lib/apksigner.jar" verify --min-sdk-version $MIN_SDK "$OUT/cosViewer.apk"
echo "=== 构建完成：$OUT/cosViewer.apk ==="
ls -lh "$OUT/cosViewer.apk"
