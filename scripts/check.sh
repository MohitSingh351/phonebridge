#!/usr/bin/env bash
# Runs every automated check: spec/fixture sync, mac protocol tests, android protocol tests.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

echo "==> fixtures up to date with PROTOCOL.md"
python3 -I "$ROOT/protocol/tools/extract_fixtures.py" --check

echo "==> mac: swift test"
(
  cd "$ROOT/mac/Packages/PhoneBridgeKit"
  out="$(swift test 2>&1)" || {
    # SwiftPM (Command Line Tools) occasionally loses the TestingMacros plugin on incremental builds after
    # a release build; a clean build fixes it.
    if grep -q "plugin for module 'TestingMacros' not found" <<<"$out"; then
      echo "   (stale build state — cleaning and retrying)"
      rm -rf .build && out="$(swift test 2>&1)" || { echo "$out" | tail -30; exit 1; }
    else
      echo "$out" | tail -30; exit 1
    fi
  }
  grep -E "Test run with" <<<"$out"
)

echo "==> android: gradle test"
(cd "$ROOT/android" && ./gradlew test --console=plain -q)

echo "All checks passed."
