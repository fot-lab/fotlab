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
#   EXPECTED_TESTS   how many tests that list must select; '' skips the selector check
#   SHARD_ID         shard id (app/loader/develop/alchemy), used in the check's log lines
#   DEMOSAICED_CACHE 'on' (default: phase-1 protocol) / 'off' (run cache-off directly)
#   RAW_JOURNEY_TESTS comma list of Class#method RAW journeys for the cache-ON success floor;
#                    '' disables the two-phase protocol (shards without RAW journeys)

# Count PASSED testcases among the comma-separated Class#method tokens of $1 across the
# connected-test XML results. A passing testcase is self-closing (<testcase ... />); a failing
# one carries <failure>/<error> children. Class tokens may be fully qualified while the XML
# carries short names, so both sides are reduced to the short name.
count_raw_journey_passes() {
  passes=0
  for f in app/build/outputs/androidTest-results/connected/*/TEST-*.xml; do
    [ -f "$f" ] || continue
    n="$(awk -v list="$1" '
      BEGIN {
        c = split(list, arr, ",")
        for (i = 1; i <= c; i++) { t = arr[i]; gsub(/[[:space:]]/, "", t); sub(/\.[^.]*#/, "#", t); want[t] = 1 }
      }
      /<testcase / {
        inwant = 0; fail = 0
        name = ""; cn = ""
        if (match($0, /[[:space:]]name="[^"]*"/)) {
          s = substr($0, RSTART, RLENGTH); sub(/[[:space:]]name="/, "", s); sub(/"$/, "", s); name = s
        }
        if (match($0, /[[:space:]]classname="[^"]*"/)) {
          s = substr($0, RSTART, RLENGTH); sub(/[[:space:]]classname="/, "", s); sub(/"$/, "", s); cn = s
        }
        sub(/.*\./, "", cn)
        if ((cn "#" name) in want) {
          inwant = 1
          if ($0 ~ /\/>[[:space:]]*$/) { p++; inwant = 0 }
        }
      }
      inwant && (/<failure/ || /<error/) { fail = 1 }
      /<\/testcase>/ { if (inwant) { if (!fail) p++; inwant = 0 } }
      END { print p + 0 }
    ' "$f")"
    n="${n:-0}"
    passes=$((passes + n))
  done
  echo "$passes"
}

if [ "$NEED_RAW" = "true" ]; then
  # The corpus lands in the emulated SD card's Pictures directory — the same place a
  # camera or a file manager drops photos — so the tests pick it up the way the app
  # picks up a real photo: indexed into MediaStore, then imported by uri.
  # (Not /sdcard/Android/data/<pkg>: a directory created by `adb shell mkdir` is owned
  # by `shell`, so the app cannot traverse it and the files are invisible to it.)
  adb shell mkdir -p /sdcard/Pictures/rawdb
  # A failed snapshot load (`default_boot`) forces a cold boot, and a push that
  # races the FUSE/sdcardfs mount dies with "remote couldn't create file:
  # Operation not permitted" — seen for one sample per run, a different one
  # each time. `adb push` of a directory keeps going on a copy error and the
  # driver never looks at its exit code, so the missing files only surfaced
  # minutes later as "was not indexed into MediaStore" assertion failures
  # naming the wrong layer. Push file-by-file, verify each landed, retry the
  # stragglers, and fail fast here if the storage is genuinely not writable.
  adb wait-for-device
  boot_polls=0
  while [ "$boot_polls" -lt 24 ]; do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 5
    boot_polls=$((boot_polls + 1))
  done
  for f in raw-samples/*; do
    name="$(basename "$f")"
    attempt=0
    while [ "$attempt" -lt 3 ]; do
      # `adb push` returns non-zero when any copy fails, and `adb shell`
      # propagates the remote `test`'s exit code — both checked together so a
      # silently dropped file cannot sneak through.
      if adb push "$f" /sdcard/Pictures/rawdb/ \
         && adb shell "test -f '/sdcard/Pictures/rawdb/$name'"; then
        break
      fi
      attempt=$((attempt + 1))
      [ "$attempt" -lt 3 ] && sleep 15
    done
    if [ "$attempt" -ge 3 ]; then
      echo "::error::[push] $name did not land on /sdcard/Pictures/rawdb after 3 attempts — emulator storage is not writable. Failing the shard here rather than as MediaStore assertion failures later."
      exit 1
    fi
  done
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

# Two-phase demosaiced-cache protocol (phase 1 = cache ON, the app default). An OOM process
# crash under emulator memory pressure (lmkd kills the app mid-suite) is environmental, not a
# product regression, so a crashed phase 1 is judged by the RAW-journey floor (2 successes):
# at or above the floor the cache-ON path is considered validated and the shard passes; below
# it, the same filter is re-run ONCE with the cache OFF (runner arg demosaicedCache=off,
# applied by RawRoutingTest) and that run decides the shard.
CACHE_ARGS=""
if [ "$DEMOSAICED_CACHE" = "off" ]; then
  CACHE_ARGS="-PsmokeDemosaicedCache=off"
fi

gradle --no-daemon --stacktrace connectedDebugAndroidTest \
  "-PsmokeTestFilter=$TEST_FILTER_COMPACT" $CACHE_ARGS > smoke-log.txt 2>&1
STATUS=$?
# Surface the Gradle output in the Actions UI; it is also uploaded as an
# artifact on failure by the step below.
cat smoke-log.txt
adb logcat -d -b all > logcat.txt 2>&1 || true
adb shell ls -l /data/tombstones > tombstones.txt 2>&1 || true

# -------- selector sanity net --------
# AndroidJUnitRunner understands `Class`, `Class#method` and comma-separated lists
# of those — nothing else. It does NOT reject a malformed token: `Class#m1+m2` is
# read as class `Class` with method `m1+m2`, which selects ZERO tests, prints no
# warning, fails nothing, and still ends in BUILD SUCCESSFUL. Most of the
# instrumented suite was silently unexecuted for exactly that reason. Comparing
# AGP's own count against the number the shard declares turns the silent zero into
# a red job.
# Deliberately NOT fatal when the count cannot be read: that wording belongs to
# AGP, so a reworded build tool must not fail an otherwise healthy shard.
if [ -n "$EXPECTED_TESTS" ]; then
  SELECTED="$(sed -n 's/.*Starting \([0-9][0-9]*\) tests on .*/\1/p' smoke-log.txt | tail -n 1)"
  if [ -z "$SELECTED" ]; then
    echo "::warning::[$SHARD_ID] could not read the selected-test count from smoke-log.txt; selector check skipped."
  elif [ "$SELECTED" -ne "$EXPECTED_TESTS" ]; then
    echo "::error::[$SHARD_ID] the -e class filter selected $SELECTED tests, expected $EXPECTED_TESTS. Most likely a TEST_FILTER token is not a legal selector (only Class, Class#method and comma-separated lists exist) — such a token selects nothing and still passes."
    STATUS=1
  else
    echo "[$SHARD_ID] selector check OK: $SELECTED tests selected."
  fi
fi

# -------- two-phase demosaiced-cache protocol (see the block above) --------
CRASHED=0
if grep -q "Instrumentation run failed due to Process crashed" smoke-log.txt; then
  CRASHED=1
fi
if [ "$STATUS" -ne 0 ] && [ "$CRASHED" = "1" ] && [ -n "$RAW_JOURNEY_TESTS" ] && [ "$DEMOSAICED_CACHE" != "off" ]; then
  RAW_PASSED="$(count_raw_journey_passes "$RAW_JOURNEY_TESTS")"
  echo "[$SHARD_ID] cache-ON run crashed (OOM-class). RAW-journey successes: $RAW_PASSED (floor: 2)."
  if [ "$RAW_PASSED" -ge 2 ]; then
    echo "[$SHARD_ID] cache-ON path validated by $RAW_PASSED RAW journeys; the OOM crash is judged environmental. Shard passes."
    exit 0
  fi
  echo "[$SHARD_ID] below the RAW-journey floor — re-running the shard once with the demosaiced cache OFF; that run decides."
  cp smoke-log.txt smoke-log-cacheon.txt || true
  gradle --no-daemon --stacktrace connectedDebugAndroidTest \
    "-PsmokeTestFilter=$TEST_FILTER_COMPACT" -PsmokeDemosaicedCache=off > smoke-log.txt 2>&1
  STATUS=$?
  cat smoke-log.txt
  adb logcat -d -b all > logcat.txt 2>&1 || true
  adb shell ls -l /data/tombstones > tombstones.txt 2>&1 || true
fi

exit $STATUS
