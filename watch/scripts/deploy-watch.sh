#!/usr/bin/env bash
# Copy WOD Coach onto a USB watch, or remove its sideloaded app file with --remove.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

mode="${1:-}"
if [ -n "$mode" ] && [ "$mode" != "--remove" ]; then
  echo "Usage: $0 [--remove]" >&2
  exit 2
fi
prg="bin/wodcoach.prg"
if [ "$mode" != "--remove" ] && [ ! -f "$prg" ]; then
  echo "bin/wodcoach.prg is missing. Build the app first." >&2
  exit 1
fi

read_product() {
  ioreg -p IOUSB -l -w 0 | awk '
    /IOUSBHostDevice@/ { vendor = 0; product = 0 }
    /"idVendor" =/ { vendor = $NF + 0 }
    /"idProduct" =/ { product = $NF + 0 }
    vendor == 2334 && product > 0 { print product; exit }
  '
}

# A Forerunner often enumerates as Garmin mode (product 3) for a few seconds
# before it switches to MTP. Wait that out before failing.
product=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  product="$(read_product)"
  if [ -n "$product" ] && [ "$product" != "3" ]; then
    break
  fi
  sleep 1
done
if [ -z "$product" ]; then
  echo "No Garmin watch is connected over USB." >&2
  exit 1
fi
if [ "$product" = "3" ]; then
  echo "The watch is plugged in, but it is not in USB file-transfer mode." >&2
  echo "Wake the watch and accept file transfer if it asks, then run this task again." >&2
  exit 1
fi

mtp_prefix=""
for prefix in /opt/homebrew/opt/libmtp /usr/local/opt/libmtp; do
  if [ -f "$prefix/include/libmtp.h" ]; then
    mtp_prefix="$prefix"
    break
  fi
done
if [ -z "$mtp_prefix" ]; then
  echo "libmtp is required to copy the app to the watch. Install it with: brew install libmtp" >&2
  exit 1
fi

helper="${TMPDIR:-/tmp}/mtp-send-prg"
cc -Wall -Wextra -o "$helper" "$root/scripts/mtp-send-prg.c" \
  -I"$mtp_prefix/include" -L"$mtp_prefix/lib" -lmtp
if [ "$mode" = "--remove" ]; then
  exec "$helper" --remove "wodcoach.prg"
fi
exec "$helper" "$prg" "wodcoach.prg"
