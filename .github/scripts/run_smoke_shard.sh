#!/usr/bin/env bash
# Smoke-test driver for one emulator shard.
#
# Invoked by .github/actions/emulator_smoke_shard/action.yml as a SINGLE-LINE
# `script: bash .github/scripts/run_smoke_shard.sh`. Kept as a real file (not an
# inline YAML literal block) on purpose: the composite-action layer collapses a
# multiline `script:` input into one line before handing it to the nested
# android-emulator-runner action, which turns the inline `#` comments into
# end-of-line comments that swallow the rest of the code (incl. the `fi`
# closing the NEED_RAW/NEED_LUT guards) and makes dash report
# "expecting fi". A single-line script calling this file sidesteps that.
#
# Runs on the runner (not inside the emulator) after the AVD is booted.
# NOTE: POSIX-safe — the runner executes this with the emulator-runner's /usr/bin/sh
# (dash on ubuntu), so avoid bash-only syntax (PIPESTATUS, set -o pipefail).
#
# Env (provided by the composite action step):
#   NEED_RAW         'true'/'false' — push the RAW corpus from raw-samples/ to the SD card
#   NEED_LUT         'true'/'false' — stage the grade LUT cube into the app's internal files
#   SMOKE_TEST_FILTER AndroidJUnitRunner -e class list (may be folded/whitespace-padded)

if [ "$NEED_RAW" = "true" ]; then
  # The corpus lands in the emulated SD card's Pictures directory — the same place a
  # camera or a file manager drops photos — so the tests pick it up the way the app
  # picks up a real photo: indexed into MediaStore, then imported by uri.
  # (Not /sdcard/Android/data/<pkg>: a directory created by `adb shell mkdir` is owned
  # by `shell`, so the app cannot traverse it and the files are invisible to it.)
  adb shell mkdir -p /sdcard/Pictures/rawdb
  adb push raw-samples/. /sdcard/Pictures/rawdb/
  adb shell ls -l /sdcard/Pictures/rawdb
fi

if [ "$NEED_LUT" = "true" ]; then
  # Install first so the package data dir exists, then stream the cube through
  # run-as stdin (the file gets the APP uid; shell-created dirs under /sdcard
  # are not app-traversable and /data/local/tmp is SELinux-blocked on API 36).
  # connectedDebugAndroidTest later reinstalls with -r, preserving app data.
  gradle --no-daemon :app:installDebug
  adb shell "run-as io.github.fotlab.fotlab sh -c 'mkdir -p files/lut-fixture && cat > files/lut-fixture/FLog2C_to_CLASSIC-Neg_VLog.cube'" \
    < lut-samples/FLog2C_to_CLASSIC-Neg_VLog.cube
  adb shell run-as io.github.fotlab.fotlab ls -l files/lut-fixture
fi

adb logcat -c || true
# Strip every whitespace: the YAML filter is folded across several lines
# for readability, so it arrives space-separated; am instrument's -e class
# list splits on commas and would see " io.Bar" as an unknown class.
TEST_FILTER_COMPACT="$(printf '%s' "$SMOKE_TEST_FILTER" | tr -d '[:space:]')"
gradle --no-daemon --stacktrace connectedDebugAndroidTest "-PsmokeTestFilter=$TEST_FILTER_COMPACT" > smoke-log.txt 2>&1
STATUS=$?
# Surface the Gradle output in the Actions UI; it is also uploaded as an
# artifact on failure by the step below.
cat smoke-log.txt
adb logcat -d -b all > logcat.txt 2>&1 || true
adb shell ls -l /data/tombstones > tombstones.txt 2>&1 || true
exit $STATUS
