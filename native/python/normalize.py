#!/usr/bin/env python3
"""Normalize an unpacked Termux rootfs into APK-ready Python artifacts.

Input:  <staging>/data/data/com.termux/files/usr  (dpkg -x layout from fetch-termux.sh)
Output: <out>/python-jniLibs/arm64-v8a/*.so      (launcher, libpython, deps, extension modules)
        <out>/python-runtime/lib/python3.14/...  (stdlib + site-packages, no .so)
        <out>/python-runtime/ext-map.txt         (runtime path <TAB> jniLibs lib name)

Android only extracts `lib*.so` from the APK and only nativeLibraryDir is exec/load-able, so:
  - every native library is renamed to an unversioned `libX.so` and its DT_NEEDED entries rewritten;
  - every CPython extension module becomes `libpy_<hash>.so`; ext-map tells EngineInstaller which
    import-machinery path must symlink to it.
RPATH/RUNPATH is rewritten to `$ORIGIN` (libraries and modules all live in nativeLibraryDir).

Usage:
  normalize.py <staging> --out-dir DIR [--prefix PATH]
  normalize.py --check <out-dir>
"""
from __future__ import annotations
import argparse, hashlib, os, re, shutil, struct, sys

PT_LOAD, PT_DYNAMIC, PT_INTERP = 1, 2, 3
DT_NEEDED, DT_SONAME, DT_RPATH, DT_RUNPATH, DT_STRTAB, DT_STRSZ = 1, 14, 15, 29, 5, 10
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libstdc++.so"}
# The Node half of the APK ships its own libc++_shared.so; AGP refuses duplicate jniLibs names.
# Rename the Python-side copy and rewrite its dependents' NEEDED entries.
FORCED_RENAMES = {"libc++_shared.so": "libcxx_py.so"}


def die(msg: str) -> "NoReturn":
    print(f"normalize: ERROR: {msg}", file=sys.stderr)
    raise SystemExit(1)


class Elf:
    def __init__(self, data: bytes):
        if data[:4] != b"\x7fELF":
            raise ValueError("not ELF")
        self.d = data
        self.is64 = data[4] == 2
        e = "<" if data[5] == 1 else ">"
        if not self.is64:
            raise ValueError("only ELF64 is supported")
        self.phoff = struct.unpack_from(e + "Q", data, 0x20)[0]
        self.phentsize = struct.unpack_from(e + "H", data, 0x36)[0]
        self.phnum = struct.unpack_from(e + "H", data, 0x38)[0]
        self.e = e
        self.loads, self.dyn = [], None
        self.interp = None
        self.machine = struct.unpack_from(e + "H", data, 0x12)[0]
        for i in range(self.phnum):
            p = struct.unpack_from(e + "IIQQQQQQ", data, self.phoff + i * self.phentsize)
            if p[0] == PT_LOAD:
                self.loads.append((p[3], p[5], p[2]))          # vaddr, memsz, offset
            elif p[0] == PT_DYNAMIC:
                self.dyn = (p[2], p[5])
            elif p[0] == PT_INTERP:
                self.interp = data[p[2]:p[2] + p[5]].rstrip(b"\0").decode("latin1")
        self.entries, self.strtab = [], None
        if self.dyn:
            off, size = self.dyn
            i = 0
            while i < size:
                tag, val = struct.unpack_from(e + "qQ", data, off + i)
                i += 16
                if tag == 0:
                    break
                self.entries.append((tag, val))
                if tag == DT_STRTAB:
                    self.strtab = val
        self.str_off = self._v2o(self.strtab) if self.strtab is not None else None

    def _v2o(self, va: int):
        for vaddr, memsz, off in self.loads:
            if vaddr <= va < vaddr + memsz:
                return off + (va - vaddr)
        return None

    def string(self, idx: int) -> str:
        assert self.str_off is not None
        end = self.d.index(b"\0", self.str_off + idx)
        return self.d[self.str_off + idx:end].decode("latin1")

    def dynamic(self, tag: int) -> list[str]:
        out = []
        if self.str_off is None:
            return out
        for t, v in self.entries:
            if t == tag:
                out.append(self.string(v))
        return out

    def alignments(self) -> set[int]:
        out = set()
        for i in range(self.phnum):
            p = struct.unpack_from(self.e + "IIQQQQQQ", self.d, self.phoff + i * self.phentsize)
            if p[0] == PT_LOAD:
                out.add(p[7])
        return out


def _replace_string(b: bytearray, base: int, offset: int, new: str, old: str) -> None:
    if len(new) > len(old):
        die(f"replacement '{new}' is longer than '{old}' in an ELF string table")
    start = base + offset
    b[start:start + len(new)] = new.encode("latin1")
    for i in range(start + len(new), start + len(old)):
        b[i] = 0


def _patch_verneed(b: bytearray, e: "Elf", rename: dict[str, str]) -> None:
    """GNU symbol versioning: Verneed.vn_file must match the (renamed) DT_NEEDED string."""
    if e.str_off is None:
        return
    d = e.d
    shoff = struct.unpack_from(e.e + "Q", d, 0x28)[0]
    shentsize = struct.unpack_from(e.e + "H", d, 0x3A)[0]
    shnum = struct.unpack_from(e.e + "H", d, 0x3C)[0]
    if shoff == 0 or shnum == 0:
        return
    for i in range(shnum):
        sh = struct.unpack_from(e.e + "IIQQQQIIQQ", d, shoff + i * shentsize)
        sh_type, sh_offset, sh_size = sh[1], sh[4], sh[5]
        if sh_type != 0x6FFFFFFE:  # SHT_GNU_verneed
            continue
        pos = sh_offset
        end = sh_offset + sh_size
        while pos + 16 <= end:
            _vn_version, _vn_cnt, vn_file, _vn_aux, vn_next = struct.unpack_from(e.e + "HHIII", d, pos)
            old = e.string(vn_file)
            new = rename.get(old, old)
            if new != old:
                _replace_string(b, e.str_off, vn_file, new, old)
            if vn_next == 0:
                break
            pos += vn_next


def patch(data: bytes, rename: dict[str, str]) -> bytes:
    e = Elf(data)
    if e.str_off is None:
        return data
    b = bytearray(data)
    for t, v in e.entries:
        if t not in (DT_NEEDED, DT_SONAME, DT_RPATH, DT_RUNPATH) or e.str_off is None:
            continue
        old = e.string(v)
        if t in (DT_RPATH, DT_RUNPATH):
            new = "$ORIGIN"
        else:
            new = rename.get(old, old)
        if new == old:
            continue
        _replace_string(b, e.str_off, v, new, old)
    _patch_verneed(b, e, rename)
    return bytes(b)


def unversioned(filename: str, soname: str | None) -> str:
    for key in (soname, filename):
        if key in FORCED_RENAMES:
            return FORCED_RENAMES[key]
    if soname:
        return re.sub(r"\.so(\.\d+)+$", ".so", soname)
    stripped = re.sub(r"\.so(\.\d+)+$", ".so", filename)
    if stripped != filename:
        return stripped
    m = re.match(r"^(.*?)\.(\d+(?:\.\d+)+)\.so$", filename)
    return m.group(1) + ".so" if m else filename


def find_usr(staging: str, prefix: str | None) -> str:
    if prefix:
        if not os.path.isdir(prefix):
            die(f"--prefix is not a directory: {prefix}")
        return prefix
    cand = os.path.join(staging, "data/data/com.termux/files/usr")
    if os.path.isdir(cand):
        return cand
    die(f"cannot find data/data/com.termux/files/usr under {staging}")


def copy_tree(src: str, dst: str, skip_dirs: set[str], skip_suffixes: tuple[str, ...]) -> int:
    """Copy a tree dereferencing symlinks and dropping dangling ones (Termux ships both)."""
    n = 0
    for dp, dns, fns in os.walk(src):
        dns[:] = [d for d in dns if d not in skip_dirs and not (dp == src and d in {"test", "turtledemo", "tkinter", "idlelib"})]
        rel = os.path.relpath(dp, src)
        outdir = dst if rel == "." else os.path.join(dst, rel)
        os.makedirs(outdir, exist_ok=True)
        for f in fns:
            p = os.path.join(dp, f)
            if f.endswith(skip_suffixes):
                continue
            if os.path.islink(p):
                real = os.path.realpath(p)
                if not os.path.exists(real):
                    continue
                p = real
            try:
                shutil.copyfile(p, os.path.join(outdir, f))
                n += 1
            except OSError as exc:
                print(f"  skip {p}: {exc}", file=sys.stderr)
    return n


def normalize(staging: str, out_dir: str, prefix: str | None) -> None:
    usr = find_usr(staging, prefix)
    libdir = os.path.join(usr, "lib")
    jni = os.path.join(out_dir, "python-jniLibs", "arm64-v8a")
    runtime = os.path.join(out_dir, "python-runtime")
    for d in (jni, runtime):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d, exist_ok=True)

    # 1) collect native libraries (resolving Termux's symlinks to the real content)
    raw = []
    for f in sorted(os.listdir(libdir)):
        p = os.path.join(libdir, f)
        if ".so" not in f or not (os.path.isfile(p) or os.path.islink(p)):
            continue
        real = os.path.realpath(p)
        if not os.path.isfile(real):
            continue
        data = open(real, "rb").read()
        try:
            elf = Elf(data)
        except Exception:
            continue
        soname = (elf.dynamic(DT_SONAME) or [None])[0]
        raw.append((f, soname, data, elf.dynamic(DT_NEEDED)))

    # 2) unversioned names + one canonical file per name
    rename: dict[str, str] = {}
    for f, soname, _data, _needed in raw:
        target = unversioned(f, soname)
        for key in (f, soname):
            if key:
                rename[key] = target
    files: dict[str, bytes] = {}
    lib_needed: dict[str, list[str]] = {}
    for f, soname, data, needed in raw:
        target = unversioned(f, soname)
        if target in files:
            continue
        files[target] = data
        lib_needed[target] = [rename.get(n, n) for n in needed]

    # 3) launcher (bionic PIE) as libdshpython.so
    launcher = os.path.join(usr, "bin", "python3.14")
    if not os.path.isfile(launcher):
        die("missing usr/bin/python3.14")
    launcher_data = open(launcher, "rb").read()
    launcher_elf = Elf(launcher_data)
    if not (launcher_elf.interp or "").endswith("/linker64"):
        die("python launcher is not a bionic PIE executable")
    launcher_needed = [rename.get(n, n) for n in launcher_elf.dynamic(DT_NEEDED)]

    # 4) extension modules (roots: importable modules must all survive pruning)
    ext: list[tuple[str, str, bytes, list[str]]] = []
    pyroot = os.path.join(usr, "lib", "python3.14")
    for root, _dirs, fs in os.walk(pyroot):
        for f in sorted(fs):
            if not f.endswith(".so"):
                continue
            p = os.path.join(root, f)
            rel = os.path.relpath(p, os.path.join(usr, "lib"))       # python3.14/...
            runtime_rel = os.path.join("lib", rel).replace(os.sep, "/")
            target = "libpy_" + hashlib.sha1(runtime_rel.encode()).hexdigest()[:12] + ".so"
            data = open(os.path.realpath(p), "rb").read()
            needed = [rename.get(n, n) for n in Elf(data).dynamic(DT_NEEDED)]
            ext.append((runtime_rel, target, data, needed))

    # 5) keep only the dependency closure reachable from launcher + extension modules
    roots = list(launcher_needed) + [n for _rel, _t, _d, needed in ext for n in needed]
    keep: set[str] = set()
    missing: set[str] = set()
    queue = list(roots)
    while queue:
        n = queue.pop()
        if n in keep:
            continue
        if n in files:
            keep.add(n)
            queue.extend(lib_needed[n])
        elif n in SYSTEM_LIBS:
            continue
        else:
            missing.add(n)
    if missing:
        die("missing native libraries (add their Termux packages to manifest.json): " + ", ".join(sorted(missing)))

    launcher_out = os.path.join(jni, "libdshpython.so")
    open(launcher_out, "wb").write(patch(launcher_data, rename))
    os.chmod(launcher_out, 0o755)
    for name in sorted(keep):
        open(os.path.join(jni, name), "wb").write(patch(files[name], rename))
    for _rel, target, data, _needed in ext:
        open(os.path.join(jni, target), "wb").write(patch(data, rename))

    # 6) runtime tree (no .so; keep import-visible layout) + ext-map
    os.makedirs(os.path.join(runtime, "lib"), exist_ok=True)
    copied = copy_tree(pyroot, os.path.join(runtime, "lib", "python3.14"),
                       skip_dirs={"__pycache__", ".git", "node_modules"}, skip_suffixes=(".so", ".a", ".o"))
    lic = os.path.join(runtime, "licenses")
    docdir = os.path.join(usr, "share", "doc")
    if os.path.isdir(docdir):
        os.makedirs(lic, exist_ok=True)
        for pkg in sorted(os.listdir(docdir)):
            c = os.path.join(docdir, pkg, "copyright")
            if os.path.isfile(c):
                shutil.copyfile(c, os.path.join(lic, pkg + ".txt"))
    with open(os.path.join(runtime, "ext-map.txt"), "w") as fh:
        for rel, target, _data, _needed in sorted(ext):
            fh.write(f"{rel}\t{target}\n")
    with open(os.path.join(runtime, "VERSIONS.txt"), "w") as fh:
        fh.write("generated by native/python/normalize.py\n")
        fh.write(f"native libs: {len(keep)}, extension modules: {len(ext)}\n")
    print(f"jniLibs: {len(keep) + 1 + len(ext)} .so in {jni} ({len(files)} candidate libs, {len(files) - len(keep)} pruned)")
    print(f"runtime: {copied} files in {runtime} ({len(ext)} extension modules)")

def check(out_dir: str) -> None:
    jni = os.path.join(out_dir, "python-jniLibs", "arm64-v8a")
    runtime = os.path.join(out_dir, "python-runtime")
    if not os.path.isdir(jni) or not os.path.isdir(runtime):
        die(f"missing {jni} or {runtime}")
    bad: list[str] = []
    entries = sorted(os.listdir(jni))
    names = {f for f in entries if f.endswith(".so")}
    for f in entries:
        if not f.endswith(".so"):
            bad.append(f"not a .so file name: {f}")
            continue
        if not f.startswith("lib"):
            bad.append(f"APK would not extract (needs lib*.so): {f}")
        data = open(os.path.join(jni, f), "rb").read()
        try:
            e = Elf(data)
        except Exception as exc:
            bad.append(f"{f}: {exc}")
            continue
        if e.machine != 0xB7:
            bad.append(f"{f}: not AArch64")
        if e.alignments() != {0x4000}:
            bad.append(f"{f}: LOAD alignment {sorted(hex(a) for a in e.alignments())}, want 0x4000")
        if f == "libdshpython.so":
            if not (e.interp or "").endswith("/linker64"):
                bad.append("launcher: missing /system/bin/linker64 interpreter")
        elif e.interp:
            bad.append(f"{f}: unexpected PT_INTERP {e.interp}")
        for need in e.dynamic(DT_NEEDED):
            if need not in names and need not in SYSTEM_LIBS:
                bad.append(f"{f}: NEEDED {need} is not shipped")
        for rpath in e.dynamic(DT_RPATH) + e.dynamic(DT_RUNPATH):
            if rpath != "$ORIGIN":
                bad.append(f"{f}: RPATH {rpath!r}, want $ORIGIN")
        if re.search(r"\.so(\.\d+)+$", f):
            bad.append(f"{f}: versioned soname name")
    ext = os.path.join(runtime, "ext-map.txt")
    if not os.path.isfile(ext):
        die("missing ext-map.txt")
    mapped = 0
    for line in open(ext):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 2:
            bad.append(f"ext-map: malformed line {line!r}")
            continue
        rel, target = parts
        mapped += 1
        if rel.startswith("/") or ".." in rel.split("/"):
            bad.append(f"ext-map: unsafe path {rel}")
        if not rel.startswith("lib/python3.14/"):
            bad.append(f"ext-map: path outside lib/python3.14: {rel}")
        if target not in names:
            bad.append(f"ext-map: target {target} not in jniLibs")
        link = os.path.join(runtime, rel)
        if os.path.exists(link) and not os.path.islink(link):
            bad.append(f"ext-map: {rel} is a real file in the runtime tree")
    if not os.path.isfile(os.path.join(runtime, "lib", "python3.14", "os.py")):
        bad.append("runtime: lib/python3.14/os.py missing")
    if bad:
        print("check FAILED:", file=sys.stderr)
        for b in bad:
            print("  -", b, file=sys.stderr)
        raise SystemExit(1)
    print(f"check OK: {len(names)} native libs, {mapped} extension modules")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("staging", nargs="?", help="unpacked Termux rootfs (dpkg -x layout)")
    ap.add_argument("--out-dir")
    ap.add_argument("--prefix", help="override <usr> path inside the staging tree")
    ap.add_argument("--check", metavar="OUT_DIR", help="validate an already normalized output tree")
    a = ap.parse_args()
    if a.check:
        check(a.check)
        return
    if not a.staging or not a.out_dir:
        ap.error("staging and --out-dir are required (or --check OUT_DIR)")
    normalize(a.staging, a.out_dir, a.prefix)


if __name__ == "__main__":
    main()
