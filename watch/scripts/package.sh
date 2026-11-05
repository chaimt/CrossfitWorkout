#!/usr/bin/env bash
# Build the release .iq package for every product in the manifest.
#   bash scripts/package.sh           production UUID -> bin/wodcoach.iq
#   bash scripts/package.sh --beta    beta UUID       -> bin/wodcoach-beta.iq
set -euo pipefail

source "$(dirname "$0")/lib-sdk.sh" "$@"

IQ="${PRG%.prg}.iq"
echo "Packaging $IQ with $SDK_BIN/monkeyc ..." >&2
exec "$SDK_BIN/monkeyc" -e -r -f "$JUNGLE" -o "$IQ" -y developer_key -l "$TYPECHECK"
