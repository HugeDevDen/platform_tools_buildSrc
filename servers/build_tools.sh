#!/bin/bash
# Expected arguments:
# $1 = out_dir
# $2 = dist_dir
# $3 = build_number
# $4 = number of --parallel-thread (optional)

set -e

PROG_DIR=$(dirname "$0")
CURRENT_OS=$(uname | tr A-Z a-z)

function die() {
  echo "$*" > /dev/stderr
  echo "Usage: $0 <out_dir> <dest_dir> <build_number> [num_threads=47]" > /dev/stderr
  exit 1
}

while [[ -n "$1" ]]; do
  if [[ -z "$OUT_DIR" ]]; then
    OUT_DIR="$1"
  elif [[ -z "$DIST_DIR" ]]; then
    DIST_DIR="$1"
  elif [[ -z "$BNUM" ]]; then
    BNUM="$1"
  elif [[ -z "$NUM_THREADS" ]]; then
    NUM_THREADS="$1"
  else
    die "[$0] Unknown parameter: $1"
  fi
  shift
done

if [[ -z "$OUT_DIR"  ]]; then die "## Error: Missing out folder"; fi
if [[ -z "$DIST_DIR" ]]; then die "## Error: Missing destination folder"; fi
if [[ -z "$BNUM"     ]]; then die "## Error: Missing build number"; fi

if [[ "$OUT_DIR" != /* ]]
then
    pushd "$PROG_DIR"/../../..
    OUT_DIR="$PWD/$OUT_DIR"
    popd
fi

TARGET="dist makeSdk"
if [[ $CURRENT_OS == "linux" ]]; then
    TARGET="$TARGET makeWinSdk"
fi

cd "$PROG_DIR"

GRADLE_FLAGS="--no-daemon --info"

# first build Eclipse/Monitor
( set -x ; OUT_DIR="$OUT_DIR" DIST_DIR="$DIST_DIR" BUILD_NUMBER="$BNUM" ../../gradlew -b ../../build.gradle $GRADLE_FLAGS  publishLocal ) || exit $?
( set -x ; OUT_DIR="$OUT_DIR" DIST_DIR="$DIST_DIR" BUILD_NUMBER="$BNUM" ../../gradlew -b ../../../sdk/eclipse/build.gradle $GRADLE_FLAGS copydeps buildEclipse ) || exit $?

# temp disable --parallel builds
#OUT_DIR="$OUT_DIR" DIST_DIR="$DIST_DIR" ../../gradlew -b ../../build.gradle --parallel-threads="${NUM_THREADS:-47}" $GRADLE_FLAGS makeSdk
( set -x ; OUT_DIR="$OUT_DIR" DIST_DIR="$OUT_DIR/emu-only-dist" BUILD_NUMBER="$BNUM" ../../gradlew --stacktrace -b ../../build.gradle -c ../../settings-emu-only.gradle $GRADLE_FLAGS $TARGET ) || exit $?
( set -x ; OUT_DIR="$OUT_DIR" DIST_DIR="$DIST_DIR" BUILD_NUMBER="$BNUM" ../../gradlew -b ../../build.gradle $GRADLE_FLAGS dist makeSdk ) || exit $?

for i in `find "$OUT_DIR"/emu-only-dist/*.zip`; do cp $i  "$DIST_DIR"/$(basename $(echo $i|sed 's/\.zip/-emu-only.zip/'));done
