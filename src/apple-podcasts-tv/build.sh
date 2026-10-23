#!/bin/bash
# Build the Podcasts TV APK with the bare SDK tools (no Gradle).
#   ./build.sh          -> build/podcasts-tv.apk
# Install:  ../../scripts/06-sideload.sh src/apple-podcasts-tv/build/podcasts-tv.apk
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/.local/android-sdk}"
BT="$SDK/build-tools"
JAR="$SDK/platforms/android-30/android.jar"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
# Personal signing key; lives outside the project so it is never shared.
KS="${KEYSTORE:-$HOME/.android/podcasts-tv.keystore}"
OUT=build

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAR" "$JAVA_HOME/bin/javac"; do
  [ -e "$f" ] || { echo "missing: $f"; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

[ -f res/drawable-xhdpi/banner.png ] || python3 make_art.py

# ZXing core draws the phone-keyboard QR code (pinned, checksum from Maven Central).
ZX=libs/zxing-core-3.5.3.jar
if [ ! -f "$ZX" ]; then
  mkdir -p libs
  curl -sfLo "$ZX" https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar
fi
echo "ca1349214a356cd7958651b2d5a0e1f3811a9c4b  $ZX" | shasum -a 1 -c --quiet

# androidx.webkit: lets the sign-in helper run inside Apple's cross-origin login frame
# (pinned, checksum of the AAR from Google's Maven repository).
WK=libs/androidx-webkit-1.12.1.jar
if [ ! -f "$WK" ]; then
  mkdir -p libs build
  curl -sfLo build/webkit.aar https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.12.1/webkit-1.12.1.aar
  echo "18a0a51ad129e0c8563f031d46c8a7e61169f56ae84e562a0d75686941262111  build/webkit.aar" | shasum -a 256 -c --quiet
  unzip -p build/webkit.aar classes.jar > "$WK"
fi

echo "== resources =="
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" \
  --manifest AndroidManifest.xml -A assets --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 30 "$OUT/res.zip"

echo "== compile =="
LIBS=$(ls libs/*.jar | tr '\n' ':')
javac -source 8 -target 8 -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar:$LIBS" -Xlint:-options -encoding UTF-8 \
  -d "$OUT/classes" $(find java "$OUT/gen" -name '*.java')

echo "== dex =="
"$BT/d8" --release --min-api 24 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') libs/*.jar
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

echo "== sign =="
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias podcaststv -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=Podcasts TV (personal sideload)" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/podcasts-tv.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/podcasts-tv.apk"
ls -lh "$OUT/podcasts-tv.apk"
