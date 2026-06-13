#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARBALL="$SCRIPT_DIR/android-sdk-termux.tar.gz"
DEST="$HOME/.android"
DOWNLOAD_URL="https://github.com/flipphoneguy/app-template/releases/latest/download/android-sdk-termux.tar.gz"

DEPS=(tar curl aapt2 ecj d8 apksigner zip)
MISSING=()
for dep in "${DEPS[@]}"; do
    command -v "$dep" >/dev/null || MISSING+=("$dep")
done
if [ ${#MISSING[@]} -gt 0 ]; then
    echo "Installing missing packages: ${MISSING[*]}"
    pkg install -y "${MISSING[@]}"
fi

if [ ! -f "$TARBALL" ]; then
    echo "android-sdk-termux.tar.gz not found locally."
    echo "Download ~43 MB from GitHub release? (Y/n)"
    read -r answer
    case "$answer" in
        [nN]*) echo "Aborted."; exit 1 ;;
    esac
    echo "Downloading from $DOWNLOAD_URL ..."
    curl -fL -o "$TARBALL" "$DOWNLOAD_URL"
fi

mkdir -p "$DEST"

echo "Extracting android.jar and framework-res.apk to $DEST ..."
tar xzf "$TARBALL" -C "$DEST"

if [ -f "$DEST/debug.keystore" ]; then
    echo "debug.keystore already exists, skipping."
else
    echo "Generating debug.keystore ..."
    keytool -genkey -v \
        -keystore "$DEST/debug.keystore" \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Debug"
fi

echo ""
echo "Done. Contents of $DEST:"
ls -lh "$DEST/android.jar" "$DEST/framework-res.apk" "$DEST/debug.keystore"
echo ""
echo "Ready to build. Run: ./build.sh"
