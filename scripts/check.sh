#!/usr/bin/env bash
# Runs every automated check: spec/fixture sync, mac protocol tests, android protocol tests.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

echo "==> fixtures up to date with PROTOCOL.md"
python3 -I "$ROOT/protocol/tools/extract_fixtures.py" --check

echo "==> mac: swift test"
(cd "$ROOT/mac/Packages/PhoneBridgeKit" && swift test 2>&1 | tail -3)

echo "==> android: gradle test"
(cd "$ROOT/android" && ./gradlew test --console=plain -q)

echo "All checks passed."
