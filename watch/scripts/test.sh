#!/usr/bin/env bash
# Compile the test binary (monkeyc -t) and run it in the simulator (monkeydo -t).
# Starts the simulator in the background when it is not already running.
#   TEST_NAME=testFoo bash scripts/test.sh   run a single test
set -euo pipefail

source "$(dirname "$0")/lib-sdk.sh" "$@"

TEST_PRG="bin/wodcoach-test.prg"
TEST_DEVICE="${TEST_DEVICE:-$DEVICE}"

echo "Building unit tests for $TEST_DEVICE with $SDK_BIN/monkeyc ..." >&2
"$SDK_BIN/monkeyc" -f "$JUNGLE" -o "$TEST_PRG" -y developer_key -d "$TEST_DEVICE" -l "$TYPECHECK" -t

sim_running() {
  pgrep -f "ConnectIQ.app/Contents/MacOS|connectiq.*simulator" >/dev/null 2>&1
}
if ! sim_running; then
  echo "Starting the Connect IQ simulator ..." >&2
  "$SDK_BIN/connectiq" >/dev/null 2>&1 &
  for _ in $(seq 1 30); do
    sleep 1
    sim_running && break
  done
  sleep 3
fi

echo "Running tests: $SDK_BIN/monkeydo $TEST_PRG $TEST_DEVICE -t ${TEST_NAME:-}" >&2
# monkeydo exits non-zero even when every test passes, so the result line in its
# output is the source of truth for the exit status. The simulator cannot run tests
# while the macOS screen is locked (monkeydo hangs), hence the time limit.
LOG="bin/test-output.log"
args=("$TEST_PRG" "$TEST_DEVICE" -t)
[ -n "${TEST_NAME:-}" ] && args+=("$TEST_NAME")
perl -e 'alarm shift; exec @ARGV' "${TEST_TIMEOUT:-300}" "$SDK_BIN/monkeydo" "${args[@]}" 2>&1 | tee "$LOG" || true
if grep -q '^PASSED' "$LOG"; then
  exit 0
fi
if ! grep -q '^Ran [0-9]' "$LOG"; then
  echo "No test results: simulator timed out or is not usable (is the screen locked?)" >&2
fi
exit 1
