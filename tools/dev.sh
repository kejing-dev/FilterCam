#!/usr/bin/env bash
# QA / dev helper (Git Bash). DEV = the factory emulator assigned to filter-cam in state.json.
#   tools/dev.sh cmd NAME [--es k v ...]   debug broadcast (AGENTS.md), prints the CMD / STATE lines
#   tools/dev.sh shot FILE                 pico-cli screenshot of the current view to FILE
#   tools/dev.sh install                   install the debug APK and restart the app
#   tools/dev.sh log [REGEX]               FilterCam log lines
set -u
DEV="${DEV:-emulator-5554}"
PKG=io.embodit.filtercam
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="adb -s $DEV"
act="${1:-}"; shift || true
case "$act" in
  cmd)
    name="$1"; shift
    if [ $# -gt 0 ] && [[ "$1" != --* ]]; then set -- --es s "$1" "${@:2}"; fi
    $A shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$name" "$@" >/dev/null
    sleep "${WAIT:-1}"
    $A logcat -d -s FilterCam:I | grep -E "CMD (ok|fail|reached|timeout)|DUMP|STATE" | tail -4 ;;
  shot) pico-cli capture screenshot -d "$DEV" -o "$1" --replace | tail -1 ;;
  install)
    $A install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
    $A shell am force-stop $PKG
    $A shell am start -n $PKG/.platform.LaunchActivity | tail -1 ;;
  log) $A logcat -d -s FilterCam:* | grep -E "${1:-.}" | tail -40 ;;
  *) echo "usage: tools/dev.sh cmd|shot|install|log" ;;
esac
