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
  # The Command Line Tools' compiler sometimes fails to load the TestingMacros plugin ("plugin for module
  # 'TestingMacros' not found") at random. It's not stale state — cleaning doesn't help — and a plain retry
  # passes, so retry up to 3 times on that specific error only. `-j 1` makes it rarer.
  for attempt in 1 2 3; do
    if out="$(swift test -j 1 2>&1)"; then break; fi
    if grep -q "plugin for module 'TestingMacros' not found" <<<"$out" && [ "$attempt" -lt 3 ]; then
      echo "   (TestingMacros plugin flake — retrying)"
      continue
    fi
    echo "$out" | tail -30; exit 1
  done
  grep -E "Test run with" <<<"$out"
)

echo "==> android: gradle test"
(cd "$ROOT/android" && ./gradlew test --console=plain -q)

echo "All checks passed."
