#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
    echo "Usage: $0 path/to/distribution.zip" >&2
    exit 2
fi

work=$(mktemp -d)
server_pid=
cleanup() {
    if [[ -n "$server_pid" ]]; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT

unzip -q "$1" -d "$work/dist"
mkdir "$work/pages"
echo '<a href="guide">Guide</a>' > "$work/pages/index.html"

{
    LC_ALL=C "${JAVA_HOME:?Set JAVA_HOME to JDK 25}/bin/jwebserver" -b 127.0.0.1 -p 0 -d "$work/pages" &
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
"$work"/dist/*/bin/queued-link-extractor --concurrency 1 "${base}index.html" "${base}missing" > "$work/actual"
diff -u "$work/expected" "$work/actual"
cat "$work/actual"
