#!/usr/bin/env bash
# Fetch pinned Termux packages + pure-Python wheels and unpack the Termux staging tree.
#
#   native/python/fetch-termux.sh --out toolchains/python-stage [--cache DIR] [--manifest FILE]
#
# Versions and sha256 are pinned in native/python/manifest.json; any mismatch aborts.
# Output: <out>/data/data/com.termux/files/usr/...  (dpkg -x layout, ready for normalize.py)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$HERE/manifest.json"
OUT=""
CACHE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="${2:?}"; shift 2 ;;
    --cache) CACHE="${2:?}"; shift 2 ;;
    --manifest) MANIFEST="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$OUT" ]] || { echo "usage: $0 --out DIR [--cache DIR]" >&2; exit 2; }
CACHE="${CACHE:-$OUT/.cache}"
mkdir -p "$OUT" "$CACHE"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v dpkg-deb >/dev/null || { echo "dpkg-deb is required (Debian/Ubuntu host)" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }

python3 - "$MANIFEST" <<'PY' > "$CACHE/.list"
import json, sys
for p in json.load(open(sys.argv[1]))["packages"]:
    print("\t".join([p["name"], p["url"], p["sha256"], p["file"]]))
PY

count=0; failed=0
while IFS=$'\t' read -r name url sha file; do
  [[ -n "$name" ]] || continue
  deb="$CACHE/$file"
  if [[ ! -f "$deb" ]] || ! echo "$sha  $deb" | sha256sum -c --quiet - 2>/dev/null; then
    tmp="$deb.part"
    rm -f "$tmp"
    if ! curl -fSL --retry 3 --retry-delay 2 -m 300 -o "$tmp" "$url"; then
      echo "FETCH FAILED: $name ($url)" >&2; failed=$((failed+1)); rm -f "$tmp"; continue
    fi
    if ! echo "$sha  $tmp" | sha256sum -c --quiet -; then
      echo "SHA256 MISMATCH: $name ($file)" >&2; rm -f "$tmp"; failed=$((failed+1)); continue
    fi
    mv "$tmp" "$deb"
  fi
  dpkg-deb -x "$deb" "$OUT"
  count=$((count+1))
done < "$CACHE/.list"
rm -f "$CACHE/.list"

[[ "$failed" -eq 0 ]] || { echo "$failed package(s) failed" >&2; exit 1; }
[[ -d "$OUT/data/data/com.termux/files/usr" ]] || { echo "unexpected layout under $OUT" >&2; exit 1; }

SP="$OUT/data/data/com.termux/files/usr/lib/python3.14/site-packages"
python3 "$HERE/install-wheels.py" "$MANIFEST" "$CACHE/wheels" "$SP"
echo "fetched and unpacked $count packages into $OUT"
