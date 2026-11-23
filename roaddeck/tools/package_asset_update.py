#!/usr/bin/env python3
"""Prepare the 0.4.1 asset-only update; align and sign with Android build tools next.

No DEX/native code is changed. The Gradle project can also rebuild these sources.
"""
import argparse
import copy
import hashlib
import pathlib
import re
import struct
import zipfile

BASE_SHA256 = 'f8f243d84f534a50ecd7046281aec311f6eb2e5adeb53e0cc5a85a7b92d68332'
ASSETS = ('assets/ui/app.js', 'assets/ui/overlay.js', 'assets/ui/index.html')


def bump_manifest(data):
    b = bytearray(data)
    u16 = lambda p: struct.unpack_from('<H', b, p)[0]
    u32 = lambda p: struct.unpack_from('<I', b, p)[0]
    strings, locations = [], []
    version_changed = False
    name_changed = False

    def length8(p):
        x = b[p]
        return (((x & 127) << 8) | b[p + 1], p + 2) if x & 128 else (x, p + 1)

    def set_string(index, old, new):
        assert strings[index] == old
        pos, size, encoding = locations[index]
        encoded = new.encode(encoding)
        assert len(encoded) == size
        b[pos:pos + size] = encoded
        strings[index] = new

    p = u16(2)
    while p < len(b):
        chunk_type, header_size, size = struct.unpack_from('<HHI', b, p)
        assert size >= header_size > 0 and p + size <= len(b)
        if chunk_type == 1:
            count, flags, start = u32(p + 8), u32(p + 16), p + u32(p + 20)
            for i in range(count):
                q = start + u32(p + header_size + i * 4)
                if flags & 256:
                    _, q = length8(q)
                    n, q = length8(q)
                    encoding = 'utf-8'
                else:
                    n = u16(q)
                    q += 2
                    if n & 32768:
                        n = ((n & 32767) << 16) | u16(q)
                        q += 2
                    n *= 2
                    encoding = 'utf-16le'
                strings.append(bytes(b[q:q+n]).decode(encoding))
                locations.append((q, n, encoding))
        elif chunk_type == 0x102 and strings[u32(p + 20)] == 'manifest':
            start = p + 16 + u16(p + 24)
            for i in range(u16(p + 28)):
                q = start + i * u16(p + 26)
                name, raw, dtype, value = strings[u32(q + 4)], u32(q + 8), b[q + 15], u32(q + 16)
                if name == 'versionCode':
                    assert dtype == 0x10 and value == 4
                    struct.pack_into('<I', b, q + 16, 5)
                    if raw != 0xffffffff:
                        set_string(raw, '4', '5')
                    version_changed = True
                elif name == 'versionName':
                    assert dtype == 3
                    indexes = {value}
                    if raw != 0xffffffff:
                        indexes.add(raw)
                    for index in indexes:
                        set_string(index, '0.4.0-overlay', '0.4.1-overlay')
                    name_changed = True
        p += size
    assert p == len(b) and version_changed and name_changed
    return bytes(b)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base_apk', type=pathlib.Path)
    parser.add_argument('project', type=pathlib.Path)
    parser.add_argument('output_apk', type=pathlib.Path)
    args = parser.parse_args()
    assert hashlib.sha256(args.base_apk.read_bytes()).hexdigest() == BASE_SHA256
    args.output_apk.parent.mkdir(parents=True, exist_ok=True)
    changed = []
    with zipfile.ZipFile(args.base_apk) as src, zipfile.ZipFile(args.output_apk, 'w') as dst:
        assert src.testzip() is None
        for entry in src.infolist():
            name = entry.filename
            if re.match(r'^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$', name, re.I):
                continue
            data = src.read(name)
            if name == 'AndroidManifest.xml':
                data = bump_manifest(data)
                changed.append(name)
            elif name in ASSETS:
                data = (args.project / 'app/src/main' / name).read_bytes()
                assert data != src.read(name), 'Expected modified asset: ' + name
                changed.append(name)
            info = copy.copy(entry)
            dst.writestr(info, data)
    assert set(changed) == {'AndroidManifest.xml', *ASSETS}
    with zipfile.ZipFile(args.base_apk) as src, zipfile.ZipFile(args.output_apk) as dst:
        assert dst.testzip() is None
        for name in dst.namelist():
            if name not in changed:
                assert src.read(name) == dst.read(name), 'Unexpected change: ' + name
    print('Asset update packaged; unchanged entries verified; version 0.4.1-overlay (5).')
    print('Unsigned output:', args.output_apk)


if __name__ == '__main__':
    main()
