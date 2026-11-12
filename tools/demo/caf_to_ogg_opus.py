"""afconvert가 만든 CAF(Opus)를 Ogg Opus로 다시 담는다. 다시 인코딩하지 않는다.

Mac의 afconvert는 Ogg Opus를 바로 쓰지 못해서(오류 'pck?'), CAF로 인코딩한 뒤 이 스크립트로 Ogg에 옮겨 담는다.
사용: python3 -I caf_to_ogg_opus.py 입력.caf 결과.ogg
"""
import random
import struct
import sys


def vlq(buf, i):
    """CAF 패킷 표의 가변 길이 정수를 읽는다."""
    v = 0
    while True:
        b = buf[i]
        i += 1
        v = (v << 7) | (b & 0x7F)
        if not b & 0x80:
            return v, i


TABLE = []
for n in range(256):
    r = n << 24
    for _ in range(8):
        r = ((r << 1) ^ 0x04C11DB7) if r & 0x80000000 else (r << 1)
    TABLE.append(r & 0xFFFFFFFF)


def crc(data):
    c = 0
    for b in data:
        c = ((c << 8) ^ TABLE[((c >> 24) ^ b) & 0xFF]) & 0xFFFFFFFF
    return c


def page(packets, granule, serial, seq, flags):
    lacing, body = b"", b""
    for p in packets:
        n = len(p)
        while n >= 255:
            lacing += b"\xff"
            n -= 255
        lacing += bytes([n])
        body += p
    hdr = struct.pack("<4sBBqIIIB", b"OggS", 0, flags, granule, serial, seq, 0, len(lacing)) + lacing
    hdr = hdr[:22] + struct.pack("<I", crc(hdr + body)) + hdr[26:]
    return hdr + body


def main(src, dst):
    d = open(src, "rb").read()
    if d[:4] != b"caff":
        sys.exit("CAF 파일이 아닙니다")
    i, chunks = 8, {}
    while i + 12 <= len(d):
        typ = d[i:i + 4]
        size = struct.unpack(">q", d[i + 4:i + 12])[0]
        i += 12
        if size < 0:
            size = len(d) - i
        chunks[typ] = d[i:i + size]
        i += size
    sr, fmt, _flags, bpp, fpp, ch, _bits = struct.unpack(">d4sIIIII", chunks[b"desc"][:32])
    if fmt != b"opus":
        sys.exit(f"Opus가 아닙니다: {fmt}")
    pakt = chunks[b"pakt"]
    npk, nvalid, priming, _rem = struct.unpack(">qqii", pakt[:24])
    j, sizes, frames = 24, [], []
    for _ in range(npk):
        if bpp == 0:
            s, j = vlq(pakt, j)
        else:
            s = bpp
        if fpp == 0:
            f, j = vlq(pakt, j)
        else:
            f = fpp
        sizes.append(s)
        frames.append(f)
    data = chunks[b"data"][4:]

    scale = 48000 / sr  # Ogg Opus의 위치 값은 항상 48kHz 기준
    pre_skip = int(round(priming * scale))
    serial = random.randint(1, 0x7FFFFFFF)
    out = [page([struct.pack("<8sBBHIhB", b"OpusHead", 1, ch, pre_skip, int(sr), 0, 0)], 0, serial, 0, 0x02)]
    vendor = b"gildongmu-demo"
    out.append(page([b"OpusTags" + struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", 0)], 0, serial, 1, 0))

    pos, gran, seq, batch = 0, 0, 2, []
    end_gran = pre_skip + int(round(nvalid * scale)) if nvalid > 0 else None
    for k, (s, f) in enumerate(zip(sizes, frames)):
        batch.append(data[pos:pos + s])
        pos += s
        gran += int(round(f * scale))
        last = k == len(sizes) - 1
        if len(batch) == 50 or last:
            g = min(gran, end_gran) if (last and end_gran) else gran
            out.append(page(batch, g, serial, seq, 0x04 if last else 0))
            seq += 1
            batch = []
    open(dst, "wb").write(b"".join(out))
    print(f"{dst}: {(gran - pre_skip) / 48000:.2f}초, {sum(len(p) for p in out)}바이트")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("사용: caf_to_ogg_opus.py 입력.caf 결과.ogg")
    main(sys.argv[1], sys.argv[2])
