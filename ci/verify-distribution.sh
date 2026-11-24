#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
    echo "Expected exactly one distribution ZIP, received $#." >&2
    if (( $# > 0 )); then printf '  %s\n' "$@" >&2; fi
    echo "Pass one ZIP path or run ./gradlew clean build to clear older distributions." >&2
    exit 2
fi

if [[ ! -f "$1" ]]; then
    echo "Distribution ZIP does not exist: $1" >&2
    exit 2
fi
if ! command -v unzip >/dev/null; then
    echo "Install unzip to verify the distribution." >&2
    exit 2
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" || ! -x "$JAVA_HOME/bin/jwebserver" ]]; then
    echo "Set JAVA_HOME to a JDK 25 installation containing java and jwebserver." >&2
    exit 2
fi
java_version=$(LC_ALL=C "$JAVA_HOME/bin/java" -version 2>&1)
if ! grep -Eq 'version "25([.+-][^"]*)?"' <<< "$java_version"; then
    printf 'Expected JDK 25 at JAVA_HOME. Detected:\n%s\n' "$java_version" >&2
    exit 2
fi

work=$(mktemp -d)
cli_pid=
server_pid=
cleanup() {
    if [[ -n "$cli_pid" ]]; then
        kill -KILL "$cli_pid" 2>/dev/null || true
        wait "$cli_pid" 2>/dev/null || true
    fi
    if [[ -n "$server_pid" ]]; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

unzip -q "$1" -d "$work/dist"
mkdir "$work/pages"
echo '<a href="guide">Guide</a>' > "$work/pages/index.html"

{
    LC_ALL=C "$JAVA_HOME/bin/jwebserver" -b 127.0.0.1 -p 0 -d "$work/pages" &
    server_pid=$!
} > "$work/server.log" 2>&1
base=
for ((attempt=0; attempt<100; attempt++)); do
    base=$(awk '/^URL http:\/\/127\.0\.0\.1:[0-9]+\/$/ {print $2; exit}' "$work/server.log")
    if [[ -n "$base" ]]; then break; fi
    if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
    sleep 0.1
done
if [[ -z "$base" ]]; then
    cat "$work/server.log" >&2
    exit 1
fi

cat > "$work/expected" <<JSON
{"input":1,"url":"${base}index.html","status":"ok","links":["${base}guide"]}
{"input":2,"url":"${base}missing","status":"error","stage":"fetch","error":"HTTP 404"}
JSON
show_diagnostics() {
    for file in actual cli.stderr server.log; do
        echo "--- $file ---" >&2
        cat "$work/$file" >&2
    done
}

"$work"/dist/*/bin/queued-link-extractor --concurrency 1 "${base}index.html" "${base}missing" > "$work/actual" 2> "$work/cli.stderr" &
cli_pid=$!
deadline=$((SECONDS + 60))
while kill -0 "$cli_pid" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
        echo "Packaged CLI exceeded its 60-second deadline." >&2
        show_diagnostics
        exit 124
    fi
    sleep 0.1
done
status=0
wait "$cli_pid" || status=$?
cli_pid=
if (( status != 0 )); then
    echo "Packaged CLI exited with status $status." >&2
    show_diagnostics
    exit "$status"
fi
if ! diff -u "$work/expected" "$work/actual"; then
    show_diagnostics
    exit 1
fi
cat "$work/actual"
