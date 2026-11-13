#!/usr/bin/env bash
# Validate a normalized output tree (python-jniLibs/ + python-runtime/).
#   native/python/check.sh toolchains/out
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:?usage: $0 <out-dir>}"
exec python3 "$HERE/normalize.py" --check "$OUT"
