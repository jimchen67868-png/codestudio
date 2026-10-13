import sys, zipfile, struct
CLS = "androidx/room/compiler/processing/javac/kotlin/JvmDescriptorUtilsKt.class"
PAT = bytes.fromhex("bb006d59bb002c59b70030" "12df" "b6004c2ab6004cb60050b6006eb70071bf")
assert len(PAT) == 30

def patch(data):
    count = struct.unpack(">H", data[8:10])[0]
    pos = 10
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            pos += 3 + struct.unpack(">H", data[pos+1:pos+3])[0]
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag in (5, 6):
            pos += 9; i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag == 15:
            pos += 4
        else:
            raise ValueError("bad cp tag %d" % tag)
        i += 1
    end = pos
    def utf8(b):
        return b"\x01" + struct.pack(">H", len(b)) + b
    new = utf8(b"VOID") + utf8(b"Lcom/squareup/javapoet/TypeName;")
    new += b"\x0c" + struct.pack(">HH", count, count + 1)
    new += b"\x09" + struct.pack(">HH", 119, count + 2)
    rest = data[end:]
    if rest.count(PAT) != 1:
        raise ValueError("throw pattern matched %d times" % rest.count(PAT))
    rep = b"\xb2" + struct.pack(">H", count + 3) + b"\xb0" + b"\x00" * 26
    assert len(rep) == 30
    rest = rest.replace(PAT, rep)
    return data[:8] + struct.pack(">H", count + 4) + data[10:end] + new + rest

src, dst = sys.argv[1], sys.argv[2]
done = 0
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
    for info in zi.infolist():
        b = zi.read(info.filename)
        if info.filename == CLS:
            b = patch(b); done += 1
            print("patched", info.filename)
        zo.writestr(info.filename, b)
if done != 1:
    sys.exit("class not found")
