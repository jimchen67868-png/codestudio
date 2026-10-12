import sys, zipfile, struct

def patch(data):
    if b"trySetAccessible" not in data:
        return data, 0
    count = struct.unpack(">H", data[8:10])[0]
    start = pos = 10
    ent = {}
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            ln = struct.unpack(">H", data[pos+1:pos+3])[0]
            ent[i] = (1, data[pos+3:pos+3+ln]); pos += 3 + ln
        elif tag in (3, 4):
            pos += 5
        elif tag in (5, 6):
            pos += 9; i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            ent[i] = (tag, struct.unpack(">HH", data[pos+1:pos+5])); pos += 5
        elif tag == 15:
            pos += 4
        else:
            raise ValueError("bad cp tag %d" % tag)
        i += 1
    end = pos
    targets = []
    for idx, (tag, info) in ent.items():
        if tag == 10:
            cls, nat = info
            if nat in ent and ent[nat][0] == 12:
                n, d = ent[nat][1]
                if ent[n][1] == b"trySetAccessible" and ent[d][1] == b"()Z":
                    targets.append((idx, cls))
    if not targets:
        raise ValueError("trySetAccessible mentioned but no methodref found")
    def utf8(b):
        return b"\x01" + struct.pack(">H", len(b)) + b
    new = utf8(b"setAccessible") + utf8(b"(Z)V")
    new += b"\x0c" + struct.pack(">HH", count, count + 1)
    refs = []
    for k, (idx, cls) in enumerate(targets):
        new += b"\x0a" + struct.pack(">HH", cls, count + 2)
        refs.append(count + 3 + k)
    rest = data[end:]
    total = 0
    for (idx, cls), nidx in zip(targets, refs):
        pat = b"\xb6" + struct.pack(">H", idx) + b"\x57"
        rep = b"\x04\xb6" + struct.pack(">H", nidx)
        n = rest.count(pat)
        total += n
        rest = rest.replace(pat, rep)
    if total == 0:
        raise ValueError("no call pattern matched")
    out = data[:8] + struct.pack(">H", count + 3 + len(targets)) + data[start:end] + new + rest
    return out, total

src, dst = sys.argv[1], sys.argv[2]
patched = 0
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
    for info in zi.infolist():
        b = zi.read(info.filename)
        if info.filename.endswith(".class"):
            b, n = patch(b)
            if n:
                patched += n
                print("patched", info.filename, n)
        zo.writestr(info.filename, b)
print("total call sites patched:", patched)
if patched == 0:
    sys.exit(1)
