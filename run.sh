#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# run.sh — Run the harness with .env loaded.
#
# Sources .env (if present) so ANTHROPIC_API_KEY is available without
# manually exporting it, then compiles and runs the given main class
# (default: Main) via a direct `java -cp` invocation.
#
# Deliberately NOT `mvn exec:java`: that goal runs main() as a thread inside
# Maven's own JVM rather than a real process, and that was observed to hang
# indefinitely (18 min wall-clock, 15s CPU — a genuine deadlock, not a slow
# API call) once Track B's MCP-via-Spring-AI code path was involved. Direct
# `java -cp` is also what an IDE's Run/Debug configuration does under the
# hood, so this is the version that's actually steppable in a debugger.
#
# Usage:
#   ./run.sh                                            # com.wgblackmon.Main
#   ./run.sh com.wgblackmon.web.WebApplication          # Track C web UI
#   ./run.sh --debug com.wgblackmon.web.WebApplication  # same, JDWP on port 5005
#                                                       # (suspend=n — attach any time)
# -----------------------------------------------------------------------------

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ -f .env ]; then
    echo "[run.sh] Loading .env ..."
    set -a
    source .env
    set +a
fi

# Optional --debug flag: attaches a JDWP agent so IntelliJ (or any debugger)
# can connect on port 5005 after the process starts (suspend=n means the JVM
# does not pause waiting for a debugger — it starts immediately and you attach
# whenever you're ready).
DEBUG_OPTS=""
if [[ "${1:-}" == "--debug" ]]; then
    DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
    shift
    echo "[run.sh] Debug mode: JDWP listening on port 5005 (attach any time after startup)"
fi

MAIN_CLASS="${1:-com.wgblackmon.Main}"

echo "[run.sh] Compiling ..."
mvn -q compile test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/.classpath.txt

CP="target/classes;target/test-classes;$(cat target/.classpath.txt)"

echo "[run.sh] Running $MAIN_CLASS ..."
exec java $DEBUG_OPTS -cp "$CP" "$MAIN_CLASS"
