#!/usr/bin/env python3
"""Render the FotLab launcher icon to a 512x512 PNG (pure stdlib, no PIL).

Mirrors app/src/main/res/drawable/ic_launcher_foreground.xml (viewport 108).
Scale = 512/108.
"""
import zlib
import struct
import os

S = 512 / 108.0


def px(v):
    return round(v * S)


WHITE = (255, 255, 255)
GRAY = (209, 209, 209)   # 18% gray subtitle plate
BROWN = (43, 26, 15)
RED = (229, 72, 77)
GREEN = (79, 180, 119)
BLUE = (59, 130, 214)
BLACK = (20, 20, 20)

W = H = 512
buf = bytearray(WHITE * (W * H))


def fill(x0, y0, x1, y1, color):
    ax0, ay0, ax1, ay1 = px(x0), px(y0), px(x1), px(y1)
    for y in range(ay0, ay1):
        if y < 0 or y >= H:
            continue
        base = y * W * 3
        for x in range(ax0, ax1):
            if x < 0 or x >= W:
                continue
            o = base + x * 3
            buf[o] = color[0]
            buf[o + 1] = color[1]
            buf[o + 2] = color[2]


# 18% gray subtitle plate 72x18 (y 72..90)
fill(18, 72, 90, 90, GRAY)

# dark-brown CMOS frame: 4.5dp rim on every side (white gap is transparent)
fill(18, 18, 90, 22.5, BROWN)    # top
fill(18, 67.5, 90, 72, BROWN)    # bottom
fill(18, 18, 22.5, 72, BROWN)    # left
fill(85.5, 18, 90, 72, BROWN)    # right

# 6x4 RGGB pixel array (each cell 9dp), origin (27,27)
for r in range(4):
    for c in range(6):
        x = 27 + c * 9
        y = 27 + r * 9
        if c % 2 == 0 and r % 2 == 0:
            col = RED
        elif (c % 2 == 1 and r % 2 == 0) or (c % 2 == 0 and r % 2 == 1):
            col = GREEN
        else:
            col = BLUE
        fill(x, y, x + 9, y + 9, col)

# "FotLab" wordmark: 9dp tall, cell 1.8dp, centred in the 72x18 plate.
PATTERNS = {
    'F': [(0, 0), (1, 0), (2, 0), (0, 1), (0, 2), (1, 2), (2, 2), (0, 3), (0, 4)],
    'o': [(0, 2), (1, 2), (2, 2), (0, 3), (2, 3), (0, 4), (1, 4), (2, 4)],
    't': [(1, 1), (0, 2), (1, 2), (2, 2), (1, 3), (1, 4), (2, 4)],
    'L': [(0, 0), (0, 1), (0, 2), (0, 3), (0, 4), (1, 4), (2, 4)],
    'a': [(0, 2), (1, 2), (2, 2), (0, 3), (2, 3), (0, 4), (1, 4), (2, 4), (3, 4)],
    'b': [(0, 0), (0, 1), (0, 2), (1, 2), (2, 2), (0, 3), (2, 3), (0, 4), (1, 4), (2, 4)],
}
WORD = "FotLab"
CELL = 1.8
TOP = 76.5          # top of cap-height band
TEXT_LEFT = 27      # 6 slots x 9dp, centred in 72-wide plate


def max_col(pat):
    return max(c for c, _ in pat)


for i, ch in enumerate(WORD):
    pat = PATTERNS[ch]
    art_w = (max_col(pat) + 1) * CELL
    art_left = TEXT_LEFT + i * 9 + (9 - art_w) / 2
    for col, row in pat:
        fill(art_left + col * CELL, TOP + row * CELL,
             art_left + col * CELL + CELL, TOP + row * CELL + CELL, BLACK)


def write_png(path):
    raw = bytearray()
    for y in range(H):
        raw.append(0)
        raw.extend(buf[y * W * 3:(y + 1) * W * 3])

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


out = os.path.join(os.path.dirname(__file__), "ic_launcher_512.png")
write_png(out)
print("wrote", out, os.path.getsize(out), "bytes")
