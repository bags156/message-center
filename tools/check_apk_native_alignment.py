"""Reject native libraries that cannot load on Android's 16 KB memory pages."""
import struct
import sys
import zipfile


def check(path):
    checked = 0
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if not name.startswith('lib/') or not name.endswith('.so'):
                continue
            data = apk.read(name)
            if data[:6] != b'\x7fELF\x02\x01':
                raise ValueError(f'{name}: expected a 64-bit little-endian library')
            offset = struct.unpack_from('<Q', data, 32)[0]
            size, count = struct.unpack_from('<HH', data, 54)
            for n in range(count):
                header = offset + n * size
                if struct.unpack_from('<I', data, header)[0] != 1:
                    continue
                file_offset, address = struct.unpack_from('<QQ', data, header + 8)
                alignment = struct.unpack_from('<Q', data, header + 48)[0]
                if alignment < 16384 or (address - file_offset) % 16384:
                    raise ValueError(f'{name}: a load segment is not 16 KB compatible')
            checked += 1
    if not checked:
        raise ValueError('No native libraries were checked')
    print(f'16 KB native alignment verified: {checked} libraries')


if __name__ == '__main__':
    check(sys.argv[1])
