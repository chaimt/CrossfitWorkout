#!/usr/bin/env bash
# Build the debug .prg for fr965 with the local Connect IQ SDK.
#   bash scripts/build.sh           production UUID (manifest.xml)
#   bash scripts/build.sh --beta    beta UUID (manifest-beta.xml)
set -euo pipefail

source "$(dirname "$0")/lib-sdk.sh" "$@"

echo "Building $PRG for $DEVICE with $SDK_BIN/monkeyc (-l $TYPECHECK) ..." >&2
exec "$SDK_BIN/monkeyc" -f "$JUNGLE" -o "$PRG" -y developer_key -d "$DEVICE" -l "$TYPECHECK" -w
