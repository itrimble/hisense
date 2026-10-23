#!/bin/bash
# Build the TV Home launcher APK with the bare SDK tools (no Gradle).
#   ./build.sh          -> build/tv-home.apk
# Install:  ../../scripts/06-sideload.sh src/tv-home/build/tv-home.apk
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/.local/android-sdk}"
BT="$SDK/build-tools"
JAR="$SDK/platforms/android-30/android.jar"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
# Personal signing key; lives outside the project so it is never shared.
KS="${KEYSTORE:-$HOME/.android/tv-home.keystore}"
OUT=build

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAR" "$JAVA_HOME/bin/javac"; do
  [ -e "$f" ] || { echo "missing: $f"; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

[ -f res/drawable-xhdpi/banner.png ] && [ -f assets/sounds/move.wav ] || python3 make_assets.py

echo "== resources =="
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" \
  --manifest AndroidManifest.xml -A assets --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 30 "$OUT/res.zip"

echo "== compile =="
javac -source 8 -target 8 -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar" -Xlint:-options -encoding UTF-8 \
  -d "$OUT/classes" $(find java "$OUT/gen" -name '*.java')

echo "== dex =="
"$BT/d8" --release --min-api 24 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

echo "== sign =="
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias tvhome -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=TV Home (personal sideload)" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/tv-home.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/tv-home.apk"
ls -lh "$OUT/tv-home.apk"
