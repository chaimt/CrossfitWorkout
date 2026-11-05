# Shared helpers for the WOD Coach task scripts (adapted from WOD Timer).
# Source this file:
#   source "$(dirname "$0")/lib-sdk.sh"
# Provides: SDK_BIN, DEVICE, PRG, JUNGLE, MANIFEST, TYPECHECK.
#
# Set BETA=1 (or pass --beta to build.sh / test.sh / run-simulator.sh) to build
# against manifest-beta.xml, which carries the beta app UUID (docs/protocol.md §0).

_lib_script="${BASH_SOURCE[0]:-$0}"
_lib_dir="$(cd "$(dirname "$_lib_script")" >/dev/null 2>&1 && pwd)" || _lib_dir="$PWD"
_lib_root="$(cd "$_lib_dir/.." >/dev/null 2>&1 && pwd)" || _lib_root="$PWD"
if [ ! -f "$_lib_root/manifest.xml" ]; then
  # Fallback: walk up from cwd looking for manifest.xml (e.g. sourced under zsh).
  _d="$PWD"
  while [ "$_d" != "/" ]; do
    if [ -f "$_d/manifest.xml" ]; then _lib_root="$_d"; break; fi
    _d="$(dirname "$_d")"
  done
fi
cd "$_lib_root"

for _arg in "$@"; do
  if [ "$_arg" = "--beta" ]; then BETA=1; fi
done

resolve_sdk_bin() {
  # 1. monkeyc on PATH wins.
  if command -v monkeyc >/dev/null 2>&1; then
    command -v monkeyc | xargs dirname
    return 0
  fi
  # 2. Latest SDK under the standard macOS location.
  local sdk_base="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks"
  if [ -d "$sdk_base" ]; then
    local latest
    latest="$(ls -1 "$sdk_base" 2>/dev/null | grep -E '^connectiq-sdk-mac-' | sort -V | tail -n 1)"
    if [ -n "$latest" ] && [ -x "$sdk_base/$latest/bin/monkeyc" ]; then
      echo "$sdk_base/$latest/bin"
      return 0
    fi
  fi
  # 3. SDK Manager pointer, when present.
  if [ -f "$HOME/.Garmin/ConnectIQ/current-sdk.cfg" ]; then
    local cfg
    cfg="$(cat "$HOME/.Garmin/ConnectIQ/current-sdk.cfg")"
    if [ -x "$cfg/bin/monkeyc" ]; then
      echo "$cfg/bin"
      return 0
    fi
  fi
  return 1
}

SDK_BIN="$(resolve_sdk_bin)" || {
  echo "Connect IQ SDK not found. Install it from https://developer.garmin.com/connect-iq/sdk/" >&2
  exit 1
}

if [ "${BETA:-0}" = "1" ]; then
  MANIFEST="manifest-beta.xml"
  JUNGLE="monkey-beta.jungle"
  PRG="bin/wodcoach-beta.prg"
else
  MANIFEST="manifest.xml"
  JUNGLE="monkey.jungle"
  PRG="bin/wodcoach.prg"
fi

if [ ! -f "$MANIFEST" ]; then
  echo "$MANIFEST not found. Run from the watch/ project root." >&2
  exit 1
fi
if [ ! -f developer_key ]; then
  echo "developer_key is missing from watch/. The compiler needs it to sign the build, and it must stay uncommitted." >&2
  exit 1
fi

# First <iq:product id="..."/> in the manifest is the default build target.
DEVICE="$(grep -o '<iq:product id="[^"]*"' "$MANIFEST" | head -n 1 | sed 's/.*="//; s/"//')"
DEVICE="${DEVICE:-fr965}"

# Type-check level: 2 = Informative (warnings shown, errors fail the build).
TYPECHECK="${TYPECHECK:-2}"

mkdir -p bin
