#!/bin/bash
# Run the game tests on a fresh world. The game test server keeps its world
# in run-gametest/world between runs, so buildings from earlier runs would
# otherwise still stand where the tests build villages.
#
# Run only some tests (the others pass immediately):
#   MILL_TESTS=layout,hillside tools/run-gametests.sh
# matches test method names case-insensitively by substring.
# MILL_WARP=10 runs the test world 10x faster (tests wait in world ticks);
# helps single waiting tests, slows down a full run.
set -e
cd "$(dirname "$0")/.."
rm -rf run-gametest/world
export JAVA_HOME="${JAVA_HOME:-/d/code_library/micraft_mods_dev/tools/jdk-17.0.20.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Djavax.net.ssl.trustStoreType=Windows-ROOT}"
./gradlew runGameTestServer --console=plain "$@"
