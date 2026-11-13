#!/usr/bin/env python3
"""Writes the in-app updater's update.json (format 1) for a built, signed release APK.

The app reads it from the release asset `update.json` and from `update/latest.json` in the
repository (core/update/UpdateManifest.kt); docs/RELEASE.md has the release steps.

  tools/make-update-manifest.py app/build/outputs/apk/release/DSH-Android-v0.3.4-arm64.apk \\
      --notes-zh notes-zh.md --notes-en notes-en.md -o update.json

Everything is read from the APK: package / versionCode / versionName / minSdk (aapt2 dump
badging), the signing certificate's SHA-256 (apksigner verify --print-certs, which also has to
pass), the file's size and SHA-256. The download URL is the GitHub release asset
(releases/download/<tag>/<apk name>); --url adds mirrors after it (they are only tried in order,
and the app accepts nothing whose SHA-256 and signer do not match, so a mirror cannot swap the APK).

Tools: --apksigner / $APKSIGNER and --aapt2 / $AAPT2, else the newest build-tools of
$ANDROID_HOME / $ANDROID_SDK_ROOT / sdk.dir in local.properties. apksigner needs java on PATH or $JAVA_HOME.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "io.github.timhub135.dsh"
DEFAULT_REPO = "Timhub-135/DSH-Android"


def die(msg):
    sys.exit(f"make-update-manifest: {msg}")


def sdk_dirs():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            yield Path(os.environ[env])
    lp = ROOT / "local.properties"
    if lp.is_file():
        m = re.search(r"^sdk\.dir=(.+)$", lp.read_text(), re.M)
        if m:
            yield Path(m.group(1).strip())


def find_tool(name, explicit, env):
    if explicit:
        return explicit
    if os.environ.get(env):
        return os.environ[env]
    for sdk in sdk_dirs():
        bt = sdk / "build-tools"
        if bt.is_dir():
            for v in sorted(bt.iterdir(), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)], reverse=True):
                if (v / name).is_file():
                    return str(v / name)
    die(f"{name} not found: pass --{name} or set ${env} / ANDROID_HOME")


def run(cmd):
    env = dict(os.environ)
    if env.get("JAVA_HOME"):
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    r = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        die(f"{' '.join(cmd[:2])} failed:\n{r.stdout}{r.stderr}")
    return r.stdout


def signer_sha256(apksigner, apk):
    out = run([apksigner, "verify", "--print-certs", "-v", str(apk)])
    if not re.search(r"^Verifies$", out, re.M):
        die("apksigner did not verify the APK")
    certs = sorted(set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out)))
    if len(certs) != 1:
        die(f"expected exactly one signing certificate, found {len(certs)}")
    # Some vendor installers, file managers and app stores only read v1 (JAR) signatures and call a
    # v2/v3-only APK unsigned. apksigner skips schemes the APK's minSdk doesn't need, so ask as for API 21.
    out = run([apksigner, "verify", "-v", "--min-sdk-version", "21", str(apk)])
    missing = [v for v in ("v1", "v2", "v3") if not re.search(rf"^Verified using {v} scheme .*: true$", out, re.M)]
    if missing:
        die(f"APK is not signed with {', '.join(missing)}: enable v1+v2+v3 signing for release builds")
    return certs[0]


def badging(aapt2, apk):
    out = run([aapt2, "dump", "badging", str(apk)])
    pkg = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out, re.M)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", out, re.M)
    if not pkg or not sdk:
        die("could not read package / minSdk from aapt2 dump badging")
    return pkg.group(1), int(pkg.group(2)), pkg.group(3), int(sdk.group(1))


def notes(path):
    if not path:
        return None
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    # The hash line of the GitHub release text is the manifest's job here.
    lines = [l for l in lines if not l.strip().startswith("APK SHA-256")]
    text = "\n".join(lines).strip()
    return text or None


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("apk", type=Path)
    p.add_argument("--notes-zh")
    p.add_argument("--notes-en")
    p.add_argument("--tag", help="release tag (default v<versionName>)")
    p.add_argument("--name", help="release asset name of the APK (default: the file's name)")
    p.add_argument("--repo", default=DEFAULT_REPO)
    p.add_argument("--url", action="append", default=[], help="extra download URL after the GitHub asset (repeatable)")
    p.add_argument("--published-at", help="ISO 8601 time (default now, UTC)")
    p.add_argument("--expect-signer", help="fail unless the APK's certificate SHA-256 is this")
    p.add_argument("--apksigner")
    p.add_argument("--aapt2")
    p.add_argument("-o", "--output", type=Path, help="write here (default stdout)")
    a = p.parse_args()

    if not a.apk.is_file():
        die(f"no such APK: {a.apk}")
    pkg, code, name, min_sdk = badging(find_tool("aapt2", a.aapt2, "AAPT2"), a.apk)
    if pkg != PACKAGE:
        die(f"package is {pkg}, not {PACKAGE}")
    signer = signer_sha256(find_tool("apksigner", a.apksigner, "APKSIGNER"), a.apk)
    if a.expect_signer and signer != a.expect_signer.replace(":", "").lower():
        die(f"signer {signer} is not the expected {a.expect_signer}")
    asset = a.name or a.apk.name
    tag = a.tag or f"v{name}"
    urls = [f"https://github.com/{a.repo}/releases/download/{tag}/{asset}"] + a.url
    for u in urls:
        if not u.startswith("https://"):
            die(f"not an https URL: {u}")
    note_map = {k: v for k, v in (("zh", notes(a.notes_zh)), ("en", notes(a.notes_en))) if v}
    published = a.published_at or datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    manifest = {
        "format": 1,
        "packageName": pkg,
        "versionCode": code,
        "versionName": name,
        "minSdk": min_sdk,
        "apk": {"name": asset, "size": a.apk.stat().st_size, "sha256": sha256(a.apk), "urls": urls},
        "signerSha256": signer,
        "notes": note_map,
        "publishedAt": published,
    }
    text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    if a.output:
        a.output.parent.mkdir(parents=True, exist_ok=True)
        a.output.write_text(text, encoding="utf-8")
        print(f"{a.output}: {name} ({code}), {manifest['apk']['size']} bytes, sha256 {manifest['apk']['sha256']}", file=sys.stderr)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
