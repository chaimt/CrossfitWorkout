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
if [ -n "${TEST_NAME:-}" ]; then
  exec "$SDK_BIN/monkeydo" "$TEST_PRG" "$TEST_DEVICE" -t "$TEST_NAME"
else
  exec "$SDK_BIN/monkeydo" "$TEST_PRG" "$TEST_DEVICE" -t
fi
