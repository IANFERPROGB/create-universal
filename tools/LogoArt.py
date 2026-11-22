"""Logo de Create Universal: el planetario mecánico (un engranaje de latón como Sol y planetas en brazos).

Uso: python tools/LogoArt.py <src/main/resources> <carpeta para el banner>
Genera:
  - <resources>/create_universal_icon.png  icono del mod (pixel art 32x32 ampliado a 128x128), el de la lista de mods
  - <banner>/banner.png                     banner de 1280x320 para la página del proyecto
"""
import math
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFont

SPACE = (14, 19, 34)
SPACE_EDGE = (27, 36, 64)
BRASS = (201, 161, 74)
BRASS_LIGHT = (227, 192, 112)
BRASS_DARK = (138, 106, 44)
ORBIT = (107, 90, 50)
SUN = (255, 207, 90)
SUN_LIGHT = (255, 231, 163)
SUN_EDGE = (232, 169, 44)
EARTH = (79, 134, 217)
LAND = (88, 179, 104)
MARS = (208, 112, 74)
SATURN = (227, 200, 138)


def disc(d, cx, cy, r, color):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)


def gear(d, cx, cy, r_body, r_tip, teeth, tooth_w, color, edge=None):
    for k in range(teeth):
        a = 2 * math.pi * k / teeth
        ca, sa = math.cos(a), math.sin(a)
        px, py = -sa, ca
        pts = []
        for along, side in ((r_body - 1, -tooth_w), (r_tip, -tooth_w * 0.8), (r_tip, tooth_w * 0.8), (r_body - 1, tooth_w)):
            pts.append((cx + ca * along + px * side, cy + sa * along + py * side))
        d.polygon(pts, fill=color)
    disc(d, cx, cy, r_body, color)
    if edge:
        d.ellipse([cx - r_body, cy - r_body, cx + r_body, cy + r_body], outline=edge, width=max(1, int(r_body / 8)))


def icon(path):
    n = 32
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, n - 1, n - 1], radius=4, fill=SPACE, outline=SPACE_EDGE)
    for x, y in ((4, 5), (27, 4), (5, 26), (28, 22), (14, 3)):
        img.putpixel((x, y), (220, 226, 255, 255))
    c = 15.5
    for k in range(40):
        a = 2 * math.pi * k / 40
        if k % 2 == 0:
            img.putpixel((int(round(c + math.cos(a) * 12.5)), int(round(c + math.sin(a) * 12.5))), ORBIT + (255,))
    # brazos
    d.line([(c, c), (24, 7)], fill=BRASS_DARK, width=2)
    d.line([(c, c), (7, 24)], fill=BRASS_DARK, width=2)
    gear(d, c, c, 6.2, 8.6, 8, 1.4, BRASS)
    disc(d, c, c, 4.6, BRASS_DARK)
    disc(d, c, c, 3.4, SUN)
    img.putpixel((14, 14), SUN_LIGHT + (255,))
    img.putpixel((15, 14), SUN_LIGHT + (255,))
    # la Tierra
    disc(d, 24.5, 6.5, 3.2, EARTH)
    img.putpixel((23, 6), LAND + (255,))
    img.putpixel((24, 5), LAND + (255,))
    img.putpixel((25, 8), LAND + (255,))
    # Marte
    disc(d, 6.5, 24.5, 2.2, MARS)
    img.resize((128, 128), Image.NEAREST).save(path)


def banner(path):
    w, h, s = 1280, 320, 2
    img = Image.new('RGB', (w * s, h * s), SPACE)
    d = ImageDraw.Draw(img)
    rnd = random.Random(7)
    for _ in range(260):
        x, y = rnd.randrange(w * s), rnd.randrange(h * s)
        r = rnd.choice((1, 1, 1, 2, 2, 3))
        v = rnd.randrange(150, 255)
        disc(d, x, y, r, (v, v, min(255, v + 20)))
    cx, cy = 190 * s, 160 * s
    for r in (78, 118):
        steps = int(2 * math.pi * r / 7)
        for k in range(steps):
            if k % 2 == 0:
                a = 2 * math.pi * k / steps
                disc(d, cx + math.cos(a) * r * s, cy + math.sin(a) * r * s, 1.6 * s, ORBIT)
    earth = (cx + math.cos(-0.62) * 78 * s, cy + math.sin(-0.62) * 78 * s)
    saturn = (cx + math.cos(2.3) * 118 * s, cy + math.sin(2.3) * 118 * s)
    mars = (cx + math.cos(-2.2) * 118 * s, cy + math.sin(-2.2) * 118 * s)
    for p in (earth, saturn, mars):
        d.line([(cx, cy), p], fill=BRASS_DARK, width=6 * s)
        disc(d, p[0], p[1], 7 * s, BRASS_DARK)
    gear(d, cx, cy, 38 * s, 50 * s, 12, 6.5 * s, BRASS, BRASS_DARK)
    disc(d, cx, cy, 25 * s, SUN)
    d.ellipse([cx - 25 * s, cy - 25 * s, cx + 25 * s, cy + 25 * s], outline=SUN_EDGE, width=3 * s)
    disc(d, cx - 8 * s, cy - 8 * s, 7 * s, SUN_LIGHT)
    disc(d, earth[0], earth[1], 17 * s, EARTH)
    d.ellipse([earth[0] - 12 * s, earth[1] - 9 * s, earth[0] + 3 * s, earth[1] + 2 * s], fill=LAND)
    d.ellipse([earth[0] + 2 * s, earth[1] + 4 * s, earth[0] + 12 * s, earth[1] + 12 * s], fill=LAND)
    disc(d, saturn[0], saturn[1], 18 * s, SATURN)
    d.ellipse([saturn[0] - 36 * s, saturn[1] - 9 * s, saturn[0] + 36 * s, saturn[1] + 9 * s], outline=BRASS, width=4 * s)
    disc(d, mars[0], mars[1], 11 * s, MARS)
    img = img.resize((w, h), Image.LANCZOS)

    # Letras en pixel art (dibujadas pequeñas y ampliadas sin suavizar), latón con sombra.
    def pixel_text(text, size, scale):
        font = ImageFont.truetype('C:/Windows/Fonts/ariblk.ttf', size)
        box = font.getbbox(text)
        tw, th = box[2] - box[0] + 2, box[3] - box[1] + 2
        mask = Image.new('1', (tw, th), 0)
        ImageDraw.Draw(mask).text((-box[0] + 1, -box[1] + 1), text, font=font, fill=1)
        return mask.resize((tw * scale, th * scale), Image.NEAREST)

    def paste_text(mask, x, y, color, shadow):
        img.paste(Image.new('RGB', mask.size, shadow), (x + 6, y + 6), mask)
        img.paste(Image.new('RGB', mask.size, color), (x, y), mask)

    title = pixel_text('CREATE UNIVERSAL', 15, 5)
    tx = 380
    paste_text(title, tx, 92, BRASS_LIGHT, BRASS_DARK)
    sub = pixel_text('El espacio para tus naves de Create', 9, 3)
    paste_text(sub, tx + 4, 92 + title.size[1] + 26, (200, 210, 235), (40, 50, 80))
    img.save(path)


def main():
    resources, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    icon(os.path.join(resources, 'create_universal_icon.png'))
    banner(os.path.join(out, 'banner.png'))
    print('ok')


if __name__ == '__main__':
    main()
