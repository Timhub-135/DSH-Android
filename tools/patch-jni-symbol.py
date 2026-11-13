#!/usr/bin/env python3
"""把 ELF .dynstr 里等长的包名片段原地替换（一次性 fork 镜像用）。

JNI 函数名 = `Java_` + 包路径（`.`→`_`）+ 方法名，存放在 ELF 的 `.dynstr`。
`jackwener` 与 `timhub135` 都是 9 个字符，等长替换不改变任何偏移/大小，
所以不需要 NDK 重编原生库。

    python3 tools/patch-jni-symbol.py arm64-v8a/libdshengine.so \
        --from jackwener --to timhub135

断言：.dynstr 命中恰好 1 处、替换前后长度不变、替换后出现新 JNI 符号
`Java_io_github_timhub135_dsh_engine_NodeRuntime_nativeStart`、旧字符串 0 处。
任一条件不满足即 exit 1（文件不会被部分写坏：先校验，再一次性写入）。
"""
import argparse
import struct
import sys

ELF_MAGIC = b"\x7fELF"
EM_NAMES = {0x28: "arm32", 0xB7: "aarch64", 0x3E: "x86-64"}


def die(msg):
    print(f"FAIL: {msg}", file=sys.stderr)
    raise SystemExit(1)


def read_elf64(data, path):
    """返回 (.dynstr 的 [offset, size], 节名列表)。只支持 ELF64。"""
    if data[:4] != ELF_MAGIC:
        die(f"{path}: not an ELF file")
    ei_class, ei_data = data[4], data[5]
    if ei_class != 2:
        die(f"{path}: only ELF64 is supported (ei_class={ei_class})")
    if ei_data != 1:
        die(f"{path}: only little-endian ELF is supported (ei_data={ei_data})")
    (e_machine,) = struct.unpack_from("<H", data, 0x12)
    (e_shoff,) = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    if e_shoff == 0 or e_shnum == 0:
        die(f"{path}: no section headers (stripped?)")
    if e_shentsize < 64:
        die(f"{path}: unexpected section header size {e_shentsize}")
    if e_shstrndx >= e_shnum:
        die(f"{path}: bad e_shstrndx {e_shstrndx} (shnum={e_shnum})")

    def section(i):
        off = e_shoff + i * e_shentsize
        (sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size,
         sh_link, sh_info, sh_addralign, sh_entsize) = struct.unpack_from("<IIQQQQIIQQ", data, off)
        return dict(name_off=sh_name, type=sh_type, offset=sh_offset, size=sh_size,
                    link=sh_link, entsize=sh_entsize)

    strtab = section(e_shstrndx)
    strtab_off, strtab_size = strtab["offset"], strtab["size"]
    if strtab_off + strtab_size > len(data):
        die(f"{path}: section name table out of bounds")
    names = {}
    for i in range(e_shnum):
        s = section(i)
        start = strtab_off + s["name_off"]
        end = data.find(b"\x00", start)
        names[i] = data[start:end].decode("ascii", "replace") if end >= 0 else ""
    dynstr = [(i, names[i], s) for i, s in ((i, section(i)) for i in range(e_shnum))
              if names[i] == ".dynstr"]
    if len(dynstr) != 1:
        die(f"{path}: expected exactly one .dynstr section, found {len(dynstr)}")
    s = dynstr[0][2]
    return (s["offset"], s["size"]), e_machine, names


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("elf", help="ELF shared library to patch in place (e.g. libdshengine.so)")
    ap.add_argument("--from", dest="old", default="jackwener", help="old package segment (default: jackwener)")
    ap.add_argument("--to", dest="new", default="timhub135", help="new package segment (default: timhub135)")
    args = ap.parse_args()

    old = args.old.encode()
    new = args.new.encode()
    if len(old) != len(new):
        die(f"--from and --to must have equal length ({len(old)} vs {len(new)})")
    if old == new:
        die("--from and --to are identical")

    path = args.elf
    try:
        with open(path, "rb") as f:
            data = bytearray(f.read())
    except OSError as e:
        die(f"cannot read {path}: {e}")

    (dynstr_off, dynstr_size), e_machine, names = read_elf64(data, path)
    if dynstr_off + dynstr_size > len(data):
        die(f"{path}: .dynstr out of bounds")
    dynstr = bytes(data[dynstr_off:dynstr_off + dynstr_size])

    hits = dynstr.count(old)
    if hits != 1:
        die(f"{path}: expected exactly 1 occurrence of {old.decode()} in .dynstr, found {hits}")
    file_hits = bytes(data).count(old)
    if file_hits != hits:
        die(f"{path}: {old.decode()} also appears outside .dynstr "
            f"(file={file_hits}, dynstr={hits}); refusing to patch")

    pos = dynstr_off + dynstr.index(old)
    before = len(data)
    data[pos:pos + len(new)] = new
    assert len(data) == before, "patch changed file size"

    expected_symbol = f"Java_io_github_{args.new}_dsh_engine_NodeRuntime_nativeStart"
    if expected_symbol.encode() not in data:
        die(f"{path}: patched file does not contain {expected_symbol}")
    if bytes(data).count(old) != 0:
        die(f"{path}: {old.decode()} still present after patch")

    try:
        with open(path, "r+b") as f:
            f.seek(0)
            f.write(data)
            f.truncate()
    except OSError as e:
        die(f"cannot write {path}: {e}")

    print(f"OK: {path}")
    print(f"  machine: {EM_NAMES.get(e_machine, hex(e_machine))}")
    print(f"  .dynstr: offset=0x{dynstr_off:x} size={dynstr_size} hit=1")
    print(f"  {args.old} -> {args.new} (size unchanged: {before} bytes)")
    print(f"  symbol: {expected_symbol}")


if __name__ == "__main__":
    main()
