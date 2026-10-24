#!/usr/bin/env bash
# 手工构建 APK（不依赖 Gradle/AGP，直接走 aapt2 + javac + d8 + apksigner）
#
# 用法：
#   bash build_apk.sh
#   TOOLCHAIN=/path/to/toolchain ANDROID_SDK=/path/to/sdk bash build_apk.sh
#
# 需要：JDK 17、Android SDK（platforms/android-30、build-tools/30.0.3）
#
# 注意：aapt2 在 Windows 上打不开含中文的目录（报 "failed to open directory"），
# 所以先把 app/src/main 复制到纯英文路径下再编译，产物再拷回来。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
TC="${TOOLCHAIN:-C:/Users/Administrator/.toolchain}"
JDK="${JAVA_HOME:-$TC/jdk/jdk-17.0.20.1+1}"
SDK="${ANDROID_SDK:-$TC/sdk}"
BT="$SDK/build-tools/30.0.3"
PLAT="$SDK/platforms/android-30/android.jar"
WORK="$TC/work"
SRC="$WORK/src"
OUT="$WORK/out"

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

rm -rf "$WORK"
mkdir -p "$SRC" "$OUT/gen" "$OUT/classes" "$OUT/dex"

# 版本号从 build.gradle 读，别在脚本里另写一份（迟早忘了同步）
VER=$(grep -oE 'versionName[[:space:]]+"[^"]+"' "$ROOT/app/build.gradle" \
      | sed -E 's/.*"([^"]+)".*/\1/' | head -1)
CODE=$(grep -oE 'versionCode[[:space:]]+[0-9]+' "$ROOT/app/build.gradle" \
      | grep -oE '[0-9]+' | head -1)
[ -n "$VER" ] && [ -n "$CODE" ] || { echo "读不到版本号，检查 app/build.gradle"; exit 1; }
APK="$ROOT/apk/看得清-$VER.apk"
mkdir -p "$ROOT/apk"
echo "版本：$VER (versionCode $CODE) → apk/看得清-$VER.apk"

cp -r "$ROOT/app/src/main/." "$SRC/"

echo "== 1/6 aapt2 compile =="
"$BT/aapt2.exe" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2 link =="
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 29 \
  --version-code "$CODE" \
  --version-name "$VER" \
  "$OUT/res.zip"

echo "== 3/6 javac =="
find "$SRC/java" "$OUT/gen" -name "*.java" > "$OUT/srcs.txt"
"$JDK/bin/javac.exe" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLAT" -d "$OUT/classes" "@$OUT/srcs.txt"

echo "== 4/6 d8 =="
"$JDK/bin/jar.exe" cf "$OUT/classes.jar" -C "$OUT/classes" .
# 用 java -cp d8.jar 直接跑：d8.bat 在 Git Bash 里会把参数吞掉、还返回 0，产物是空的
"$JDK/bin/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLAT" --release --min-api 24 --output "$OUT/dex" "$OUT/classes.jar"

echo "== 5/6 打包 =="
cd "$OUT"
cp base.apk unsigned.apk
cp dex/classes.dex ./classes.dex
"$JDK/bin/jar.exe" uf unsigned.apk classes.dex
"$BT/zipalign.exe" -f -p 4 unsigned.apk aligned.apk

echo "== 6/6 签名 =="
KS="$TC/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool.exe" -genkeypair -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$JDK/bin/java.exe" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/kanqing.apk" aligned.apk

cp "$OUT/kanqing.apk" "$APK"

echo "== 校验 =="
"$BT/aapt2.exe" dump badging "$APK" | head -4
"$JDK/bin/java.exe" -jar "$BT/lib/apksigner.jar" verify --print-certs "$APK" | head -2
ls -lh "$APK"
