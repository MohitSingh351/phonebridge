#!/usr/bin/env bash
# Builds PhoneBridge.app without Xcode: SwiftPM release build → app bundle → codesign (sandbox + hardened runtime).
#
# Signing uses a self-signed "PhoneBridge Local Signing" identity created once in your login keychain.
# A stable identity keeps Keychain-item access and the Local Network permission across rebuilds
# (ad-hoc signatures change every build, so macOS would ask again each time).
#
# Usage: scripts/build-mac-app.sh [--open]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$ROOT/mac/Packages/PhoneBridgeKit"
OUT="$ROOT/mac/build"
APP="$OUT/PhoneBridge.app"
SIGN_NAME="PhoneBridge Local Signing"

# Dedicated signing keychain with a locally stored random password: lets codesign use the key from any shell
# without GUI prompts, and keeps the dev signing key out of your login keychain.
SIGN_KC="$HOME/Library/Keychains/phonebridge-signing.keychain-db"
SIGN_PASS_FILE="$HOME/.config/phonebridge/signing-keychain-pass"

ensure_identity() {
  if [ ! -f "$SIGN_KC" ]; then
    echo "==> creating local code-signing identity '$SIGN_NAME' (one time)"
    mkdir -p "$(dirname "$SIGN_PASS_FILE")"
    ( umask 077; /usr/bin/openssl rand -hex 24 > "$SIGN_PASS_FILE" )
    local kcpass; kcpass="$(cat "$SIGN_PASS_FILE")"
    security create-keychain -p "$kcpass" "$SIGN_KC"
    security set-keychain-settings "$SIGN_KC"   # no auto-lock timeout
    local tmp; tmp="$(mktemp -d)"
    cat > "$tmp/cfg" <<CFG
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $SIGN_NAME
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CFG
    /usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config "$tmp/cfg" \
      -keyout "$tmp/key.pem" -out "$tmp/cert.pem" 2>/dev/null
    local p12pass; p12pass="$(/usr/bin/openssl rand -hex 16)"
    /usr/bin/openssl pkcs12 -export -inkey "$tmp/key.pem" -in "$tmp/cert.pem" -name "$SIGN_NAME" \
      -passout "pass:$p12pass" -out "$tmp/id.p12"
    security import "$tmp/id.p12" -k "$SIGN_KC" -P "$p12pass" -T /usr/bin/codesign >/dev/null
    security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$kcpass" "$SIGN_KC" >/dev/null
    rm -rf "$tmp"
  fi
  security unlock-keychain -p "$(cat "$SIGN_PASS_FILE")" "$SIGN_KC"
}

echo "==> swift build (release)"
(cd "$PKG" && swift build -c release --product PhoneBridge 2>&1 | grep -E "error|warning: .*Sources|Compiling|Build complete" | grep -v Compiling || true)
BIN="$(cd "$PKG" && swift build -c release --show-bin-path)/PhoneBridge"
[ -x "$BIN" ] || { echo "build failed"; exit 1; }

echo "==> assembling $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/PhoneBridge"
cp "$ROOT/mac/bundle/Info.plist" "$APP/Contents/Info.plist"
plutil -lint "$APP/Contents/Info.plist" >/dev/null

ensure_identity
echo "==> codesign"
codesign --force --options runtime --timestamp=none \
  --entitlements "$ROOT/mac/bundle/PhoneBridge.entitlements" \
  --keychain "$SIGN_KC" --sign "$SIGN_NAME" "$APP"
codesign --verify --strict "$APP"
echo "==> built $APP"

if [[ "${1:-}" == "--open" ]]; then
  pkill -x PhoneBridge 2>/dev/null || true
  open "$APP"
fi
