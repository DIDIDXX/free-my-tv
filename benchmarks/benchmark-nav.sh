#!/usr/bin/env bash
set -euo pipefail
# Usage: ANDROID_SERIAL=<device> ADB_BIN=/path/to/adb bash benchmarks/benchmark-nav.sh
# TV must be awake, unlocked, and connected. This sends remote navigation keys.
ADB=("${ADB_BIN:-adb}")
if [ -n "${ANDROID_SERIAL:-}" ]; then ADB+=(-s "$ANDROID_SERIAL"); fi

"${ADB[@]}" shell am start -W -n io.freemytv/.HomeActivity >/dev/null
sleep 2
"${ADB[@]}" shell dumpsys gfxinfo io.freemytv:home reset >/dev/null

"${ADB[@]}" shell '
key() { input keyevent "$1"; sleep "${2:-0.35}"; }
# 1. Shelf focus right and left
key 22; key 22; key 22
key 21; key 21; key 21
# 2. Open drawer, navigate drawer items, close drawer
key 20 0.55
key 22; key 22; key 22
key 21; key 21; key 21
key 19 0.55
# 3. Move to cards, navigate cards
key 19 0.4
key 22; key 22
key 21; key 21
# 4. Move to top bar (tabs + corner buttons), navigate and return to shelf
key 19 0.4
key 22; key 22; key 22; key 22
key 21; key 21; key 21; key 21
key 20 0.4
key 20 0.8
'

"${ADB[@]}" shell dumpsys gfxinfo io.freemytv:home
