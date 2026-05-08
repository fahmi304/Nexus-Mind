#!/usr/bin/env bash
# Download nodejs-mobile libnode.so for local development.
# CI runs this automatically; run it once before building locally.

set -euo pipefail

VERSION="v18.20.4"
ZIP_URL="https://github.com/nodejs-mobile/nodejs-mobile/releases/download/${VERSION}/nodejs-mobile-${VERSION}-android.zip"
ZIP_TMP="/tmp/nodejs-mobile-${VERSION}-android.zip"
DEST="$(dirname "$0")/../app/src/main/jniLibs"

echo "Downloading nodejs-mobile ${VERSION}..."
curl -fL "$ZIP_URL" -o "$ZIP_TMP"

mkdir -p "$DEST/arm64-v8a" "$DEST/armeabi-v7a"

echo "Extracting libnode.so for arm64-v8a..."
unzip -p "$ZIP_TMP" bin/arm64-v8a/libnode.so > "$DEST/arm64-v8a/libnode.so"

echo "Extracting libnode.so for armeabi-v7a..."
unzip -p "$ZIP_TMP" bin/armeabi-v7a/libnode.so > "$DEST/armeabi-v7a/libnode.so"

rm -f "$ZIP_TMP"
echo "Done. libnode.so ready in $DEST"
