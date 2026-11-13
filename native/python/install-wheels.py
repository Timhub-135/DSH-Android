#!/usr/bin/env python3
"""Download (via pip), verify and unpack the pinned pure-Python wheels into site-packages.

  install-wheels.py <manifest.json> <wheel-cache> <site-packages>
"""
import hashlib, json, os, subprocess, sys, zipfile


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    manifest, cache, sp = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(cache, exist_ok=True)
    os.makedirs(sp, exist_ok=True)
    entries = json.load(open(manifest)).get("pypi", [])
    failed = 0
    for e in entries:
        name, ver, want, file = e["name"], e["version"], e["sha256"], e["file"]
        whl = os.path.join(cache, file)
        if not os.path.isfile(whl) or sha256(whl) != want:
            if os.path.exists(whl):
                os.remove(whl)
            r = subprocess.run([sys.executable, "-m", "pip", "download", "--no-deps",
                                "--only-binary=:all:", "--dest", cache, f"{name}=={ver}"],
                               capture_output=True, text=True)
            if r.returncode != 0:
                print(f"pip download failed: {name}=={ver}\n{r.stderr[-500:]}", file=sys.stderr)
                failed += 1
                continue
            if not os.path.isfile(whl):
                print(f"pip did not produce {file}", file=sys.stderr)
                failed += 1
                continue
        got = sha256(whl)
        if got != want:
            print(f"SHA256 MISMATCH: {file} (got {got})", file=sys.stderr)
            failed += 1
            continue
        with zipfile.ZipFile(whl) as z:
            z.extractall(sp)
    if failed:
        print(f"{failed} wheel(s) failed", file=sys.stderr)
        return 1
    print(f"installed {len(entries)} pure-Python wheels into {sp}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
