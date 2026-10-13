import sys, zipfile, struct, os
CLS = "androidx/room/compiler/processing/javac/kotlin/JvmDescriptorUtilsKt.class"
TAIL = bytes.fromhex("bb006d59bb002c59b70030" "12df" "b6004c2ab6004cb60050b6006eb70071bf")
assert len(TAIL) == 30

def cp_end(b):
    n = struct.unpack(">H", b[8:10])[0]
    i, idx = 10, 1
    while idx < n:
        t = b[i]
        if t == 1: i += 3 + struct.unpack(">H", b[i+1:i+3])[0]
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): i += 5
        elif t in (5, 6): i += 9; idx += 1
        elif t in (7, 8, 16, 19, 20): i += 3
        elif t == 15: i += 4
        else: raise SystemExit("bad cp tag %d" % t)
        idx += 1
    return n, i

def patch(b):
    assert b.count(TAIL) == 1, "tail anchor not found exactly once"
    n, end = cp_end(b)
    assert n + 4 < 65535
    u = lambda s: b"\x01" + struct.pack(">H", len(s)) + s
    add = (u(b"kotlin") + b"\x08" + struct.pack(">H", n)
           + u(b"Unit") + b"\x08" + struct.pack(">H", n + 2))
    code = (bytes([0x00] * 14)
            + b"\x13" + struct.pack(">H", n + 1)
            + b"\x13" + struct.pack(">H", n + 3)
            + b"\x03\xbd\x00\x62\xb8\x00\xb8\xc0\x00\x77")
    assert len(code) == 30
    b = b.replace(TAIL, code)
    return b[:8] + struct.pack(">H", n + 4) + b[10:end] + add + b[end:]

src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w") as zo:
    found = False
    for it in zi.infolist():
        d = zi.read(it.filename)
        if it.filename == CLS:
            d = patch(d); found = True
        zo.writestr(it, d, compress_type=it.compress_type)
    assert found, "class not in jar"
print("patched", src, "->", dst)
