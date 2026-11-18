#!/usr/bin/env python3
"""极简 zipalign：重打包 APK，让 resources.arsc / .dex / .so 的本地头数据区 4 字节对齐。
Android 11+ 要求 targetSdk>=30 的 APK 中 resources.arsc 未压缩且 4 字节对齐。"""
import sys, os, struct, zlib, zipfile

DTIME = (0, 0)
DDATE = (0, 0x21)  # 1980-01-01


def crc(b):
    return zlib.crc32(b) & 0xFFFFFFFF


def needs_align(name):
    return name.endswith('.arsc') or name.endswith('.dex') or name.endswith('.so')


def main(src, dst):
    zin = zipfile.ZipFile(src)
    entries = []
    for info in zin.infolist():
        data = zin.read(info.filename)
        entries.append((info.filename, data, info.compress_type))

    out = bytearray()
    central = []
    for name, data, method in entries:
        nb = name.encode('utf-8')
        if method == 8:
            co = zlib.compressobj(9, zlib.DEFLATED, -15)  # raw deflate，不带 zlib 头
            comp = co.compress(data) + co.flush()
        else:
            comp = data
        local_off = len(out)
        extra = b''
        if needs_align(name):
            base = local_off + 30 + len(nb)
            # 先加 4 字节 extra 头，再补 0~3 字节使 data offset 4 对齐
            extra_len = 4 + ((4 - ((base + 4) % 4)) % 4)
            extra = struct.pack('<HH', 0x0000, extra_len - 4) + b'\x00' * (extra_len - 4)
        csize = len(comp)
        usize = len(data)
        c = crc(data)
        flags = 0x0000
        hdr = struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, flags, method,
                          DTIME[0], DDATE[0], c, csize, usize, len(nb), len(extra))
        out += hdr + nb + extra + comp
        central.append((name, nb, method, c, csize, usize, local_off, extra))

    cd_start = len(out)
    cd = bytearray()
    for name, nb, method, c, csize, usize, local_off, extra in central:
        cd += struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, 0x0000,
                          method, DTIME[0], DDATE[0], c, csize, usize,
                          len(nb), len(extra), 0, 0, 0, 0, local_off)
        cd += nb + extra
    out += cd
    cd_size = len(cd)

    eocd = struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(central), len(central),
                       cd_size, cd_start, 0)
    out += eocd
    with open(dst, 'wb') as f:
        f.write(out)

    # 输出对齐报告
    print("对齐检查：")
    for name, nb, method, c, csize, usize, local_off, extra in central:
        base = local_off + 30 + len(nb)
        data_off = base + len(extra)
        tag = 'ok' if (not needs_align(name)) or data_off % 4 == 0 else '!!'
        print("  %-45s off=%6d %s" % (name, data_off, tag))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
