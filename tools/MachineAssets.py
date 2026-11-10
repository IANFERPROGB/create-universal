"""Genera las texturas y los JSON de los bloques de nave (alpha.9).

Parte del marco de la carcasa de latón de Create (se lee del jar, no se copia al repositorio) y le pone un panel propio.
Uso: python tools/MachineAssets.py <jar de Create> <src/main/resources>
"""
import io
import json
import math
import os
import random
import sys
import zipfile

from PIL import Image

CASING = 'assets/create/textures/block/brass_casing.png'


def casing_frame(jar):
    with zipfile.ZipFile(jar) as z:
        return Image.open(io.BytesIO(z.read(CASING))).convert('RGBA')


def panel(base, seed):
    """Copia la carcasa y rellena el interior (2..13) con un panel de acero oscuro."""
    img = base.copy()
    rnd = random.Random(seed)
    for y in range(2, 14):
        for x in range(2, 14):
            n = rnd.randint(-6, 6)
            edge = x in (2, 13) or y in (2, 13)
            c = (34, 38, 48) if not edge else (22, 24, 31)
            img.putpixel((x, y), (c[0] + n, c[1] + n, c[2] + n, 255))
    return img


def orbital_side(base):
    img = panel(base, 41)
    cx = cy = 7.5
    for y in range(2, 14):
        for x in range(2, 14):
            d = math.hypot(x - cx, y - cy)
            if 3.4 <= d <= 4.6:
                light = (x - cx) + (y - cy) < 0
                img.putpixel((x, y), (150, 232, 255, 255) if light else (70, 175, 225, 255))
    for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)):
        img.putpixel((x, y), (230, 250, 255, 255))
    img.putpixel((7, 7), (255, 255, 255, 255))
    # El "satélite" sobre el anillo.
    for x, y in ((11, 4), (12, 4), (11, 5), (12, 5)):
        img.putpixel((x, y), (245, 201, 106, 255))
    return img


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(data, indent=2) + '\n')


def shaft_block(res, name, side_texture):
    assets = os.path.join(res, 'assets', 'create_universal')
    side_texture.save(os.path.join(assets, 'textures', 'block', name + '_side.png'))
    textures = {'casing': 'create_universal:block/' + name + '_side', 'opening': 'create:block/brass_gearbox'}
    write(os.path.join(assets, 'models', 'block', name + '.json'),
          {'parent': 'create:block/encased_shaft/block', 'textures': textures})
    write(os.path.join(assets, 'models', 'item', name + '.json'),
          {'parent': 'create:block/encased_shaft/item', 'textures': textures})
    model = 'create_universal:block/' + name
    write(os.path.join(assets, 'blockstates', name + '.json'), {'variants': {
        'axis=x': {'model': model, 'uvlock': True, 'x': 90, 'y': 90},
        'axis=y': {'model': model, 'uvlock': True},
        'axis=z': {'model': model, 'uvlock': True, 'x': 90, 'y': 180}}})
    write(os.path.join(res, 'data', 'create_universal', 'loot_table', 'blocks', name + '.json'), {
        'type': 'minecraft:block',
        'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'create_universal:' + name}],
                   'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})


def main():
    jar, res = sys.argv[1], sys.argv[2]
    base = casing_frame(jar)
    shaft_block(res, 'orbital_stabilizer', orbital_side(base))
    print('Hecho.')


if __name__ == '__main__':
    main()
