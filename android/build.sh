#!/usr/bin/env bash
# 手工打包 APK：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 不依赖 Gradle / Android Studio / 任何 maven 依赖
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
SDK="${ANDROID_SDK:-$LOCALAPPDATA/Android/Sdk}"
# 必须 >= 36：34.0.0 的 d8 会在内部类构造函数的 MethodParameters
# （name_index = 0，javac 为匿名内部类写的「无名参数」）上抛 NPE
BT=""
for v in 36.1.0 36.0.0 36.1.0-rc1 35.0.0; do
  if [ -f "$SDK/build-tools/$v/d8.bat" ]; then BT="$SDK/build-tools/$v"; break; fi
done
AJAR="$SDK/platforms/android-34/android.jar"
if [ -z "$BT" ]; then
  echo "没找到可用的 build-tools（d8 需要 36 以上）：sdkmanager \"build-tools;36.1.0\""
  exit 1
fi
echo "使用 build-tools: $BT"

APP_ID="com.bjtrip.app"
VER_CODE=1
VER_NAME="1.0"
OUT_NAME="北京5日行程.apk"

BUILD="$HERE/.build"
APK_UNSIGNED="$BUILD/unsigned.apk"
APK_ALIGNED="$BUILD/aligned.apk"
# 密钥不进仓库（.gitignore 里挡掉了），首次构建会自动生成一个
KS="$HERE/bjtrip.jks"
KS_PASS="${BJTRIP_KS_PASS:-bjtrip2026}"
KS_ALIAS="bjtrip"

for f in "$BT/aapt2.exe" "$BT/d8.bat" "$BT/zipalign.exe" "$BT/apksigner.bat" "$AJAR"; do
  [ -e "$f" ] || { echo "缺少 $f —— 先装 Android SDK build-tools;34.0.0 / platforms;android-34"; exit 1; }
done

rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$HERE/assets"

echo "[1/7] 收集网页资源 -> assets/"
for f in mobile.html manifest.webmanifest sw.js icon.svg \
         icon-192.png icon-512.png icon-192-maskable.png icon-512-maskable.png; do
  [ -f "$PROJ/$f" ] && cp "$PROJ/$f" "$HERE/assets/" && echo "     $f"
done

echo "[2/7] aapt2 compile 资源 ..."
"$BT/aapt2.exe" compile --dir "$HERE/res" -o "$BUILD/res.zip"

echo "[3/7] aapt2 link ..."
"$BT/aapt2.exe" link \
  -o "$APK_UNSIGNED" \
  -I "$AJAR" \
  --manifest "$HERE/AndroidManifest.xml" \
  -A "$HERE/assets" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VER_CODE" \
  --version-name "$VER_NAME" \
  --auto-add-overlay \
  "$BUILD/res.zip"

echo "[4/7] javac ..."
# Java 工具是原生 Windows 程序：路径转成 C:\... 形式，且不要走 @argfile
# （javac 的 argfile 会把反斜杠当转义符）
mapfile -t SOURCES < <(find "$HERE/java" "$BUILD/gen" -name '*.java' -exec cygpath -m {} \;)
if ! javac -nowarn -J-Duser.language=en -J-Duser.country=US \
      -source 8 -target 8 -encoding UTF-8 \
      -bootclasspath "$(cygpath -m "$AJAR")" \
      -d "$BUILD/classes" "${SOURCES[@]}" > "$BUILD/javac.log" 2>&1; then
  echo "  javac 失败："; sed 's/^/    /' "$BUILD/javac.log"; exit 1
fi
grep -c . "$BUILD/javac.log" >/dev/null && sed 's/^/    /' "$BUILD/javac.log" | grep -vi "warning\|deprecat\|obsolete" || true
echo "     编译 ${#SOURCES[@]} 个源文件，OK"

echo "[5/7] d8 生成 dex ..."
mapfile -t CLASSES < <(find "$BUILD/classes" -name '*.class' -exec cygpath -m {} \;)
"$BT/d8.bat" --release --min-api 24 --lib "$(cygpath -m "$AJAR")" \
  --output "$(cygpath -m "$BUILD/dex")" "${CLASSES[@]}"

echo "[6/7] 把 classes.dex 塞进 APK ..."
python - "$APK_UNSIGNED" "$BUILD/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('     classes.dex ->', apk)
PY

"$BT/zipalign.exe" -f 4 "$APK_UNSIGNED" "$APK_ALIGNED"

echo "[7/7] 签名 ..."
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v \
    -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=BJTrip, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN" >/dev/null 2>&1
  echo "     已生成签名密钥 $KS（口令 $KS_PASS，请自行保管）"
fi

"$BT/apksigner.bat" sign \
  --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$HERE/$OUT_NAME" "$APK_ALIGNED"

"$BT/apksigner.bat" verify --print-certs "$HERE/$OUT_NAME" | head -5
echo
echo "APK 产物: $HERE/$OUT_NAME"
ls -la "$HERE/$OUT_NAME"
