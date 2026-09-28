#!/usr/bin/env python3
"""Render the FotLab launcher icon to a 512x512 PNG (pure stdlib, no PIL).

The pixel geometry mirrors app/src/main/res/drawable/ic_launcher_foreground.xml
(viewport 108x108). Scale here is 512/108.
"""
import zlib
import struct
import os

S = 512 / 108.0  # viewport(108) -> pixels(512)


def px(v):
    return round(v * S)


WHITE = (255, 255, 255)
BROWN = (43, 26, 15)
RED = (229, 72, 77)
GREEN = (79, 180, 119)
BLUE = (59, 130, 214)
BLACK = (20, 20, 20)

W = H = 512
buf = bytearray(WHITE * (W * H))  # flat RGB


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


# dark-brown CMOS frame (4 strips)
fill(10.8, 11.3, 97.2, 21.5, BROWN)   # top
fill(10.8, 65.5, 97.2, 75.7, BROWN)   # bottom
fill(10.8, 11.3, 21.0, 75.7, BROWN)   # left
fill(87.0, 11.3, 97.2, 75.7, BROWN)   # right

# 6x4 RGGB pixel array
for gr in range(2):        # group row
    for gc in range(3):     # group col
        bx = 21.0 + (gc * 2) * 11
        by = 21.5 + (gr * 2) * 11
        fill(bx, by, bx + 11, by + 11, RED)          # TL
        fill(bx + 11, by, bx + 22, by + 11, GREEN)   # TR
        fill(bx, by + 11, bx + 11, by + 22, GREEN)   # BL
        fill(bx + 11, by + 11, bx + 22, by + 22, BLUE)  # BR

# "FotLab" pixel text cells (cell = 3dp)
CELL = 3
TY = 81.7  # top of cap-height row 0


def cell(gx, col, row, color):
    fill(gx + col * CELL, TY + row * CELL, gx + col * CELL + CELL, TY + row * CELL + CELL, color)


# F
gx = 18
for c in (0, 1, 2):
    cell(gx, c, 0, BLACK)
cell(gx, 0, 1, BLACK)
for c in (0, 1, 2):
    cell(gx, c, 2, BLACK)
cell(gx, 0, 3, BLACK)
cell(gx, 0, 4, BLACK)
# o (lower, rows 2-4)
gx = 30
for c in (0, 1, 2):
    cell(gx, c, 2, BLACK)
cell(gx, 0, 3, BLACK)
cell(gx, 2, 3, BLACK)
for c in (0, 1, 2):
    cell(gx, c, 4, BLACK)
# t (lower, rows 1-4)
gx = 42
cell(gx, 1, 1, BLACK)
for c in (0, 1, 2):
    cell(gx, c, 2, BLACK)
cell(gx, 1, 3, BLACK)
cell(gx, 1, 4, BLACK)
cell(gx, 2, 4, BLACK)
# L
gx = 54
for r in range(5):
    cell(gx, 0, r, BLACK)
for c in (0, 1, 2):
    cell(gx, c, 4, BLACK)
# a (lower, 4 wide, rows 2-4)
gx = 66
for c in (0, 1, 2):
    cell(gx, c, 2, BLACK)
cell(gx, 0, 3, BLACK)
cell(gx, 2, 3, BLACK)
for c in (0, 1, 2, 3):
    cell(gx, c, 4, BLACK)
# b (full height)
gx = 81
cell(gx, 0, 0, BLACK)
cell(gx, 0, 1, BLACK)
for c in (0, 1, 2):
    cell(gx, c, 2, BLACK)
cell(gx, 0, 3, BLACK)
cell(gx, 2, 3, BLACK)
for c in (0, 1, 2):
    cell(gx, c, 4, BLACK)


def write_png(path):
    raw = bytearray()
    for y in range(H):
        raw.append(0)  # filter type 0
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
