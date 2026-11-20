"""Genera los escenarios (.nbt de estructura) de los Ponder de Create Universal (beta.1, D65).

Uso: python tools/PonderSchematics.py <src/main/resources>
Cada escenario lleva la placa de 5x5 en y=0 (como los de Create) y los bloques de la escena encima.
"""
import gzip
import io
import os
import struct
import sys

DATA_VERSION = 3955  # Minecraft 1.21.1


# ---------------------------------------------------------------- NBT mínimo

def _name(out, s):
    b = s.encode('utf-8')
    out.write(struct.pack('>H', len(b)))
    out.write(b)


def _payload(out, tag, value):
    if tag == 3:
        out.write(struct.pack('>i', value))
    elif tag == 8:
        _name(out, value)
    elif tag == 9:
        elem_tag, items = value
        out.write(struct.pack('>bi', elem_tag if items else 0, len(items)))
        for it in items:
            _payload(out, elem_tag, it)
    elif tag == 10:
        for k, (t, v) in value.items():
            out.write(struct.pack('>b', t))
            _name(out, k)
            _payload(out, t, v)
        out.write(b'\x00')
    else:
        raise ValueError(tag)


def write_structure(path, size, blocks):
    """blocks: {(x, y, z): (nombre, {propiedad: valor})}"""
    palette = []
    index = {}
    entries = []
    for pos, (name, props) in sorted(blocks.items()):
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            palette.append({'Name': (8, name), **({'Properties': (10, {k: (8, v) for k, v in props.items()})} if props else {})})
        entries.append({'pos': (9, (3, list(pos))), 'state': (3, index[key])})
    root = {
        'DataVersion': (3, DATA_VERSION),
        'size': (9, (3, list(size))),
        'palette': (9, (10, palette)),
        'blocks': (9, (10, entries)),
        'entities': (9, (10, [])),
    }
    raw = io.BytesIO()
    raw.write(struct.pack('>b', 10))
    _name(raw, '')
    _payload(raw, 10, root)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with gzip.open(path, 'wb') as f:
        f.write(raw.getvalue())


def plate():
    return {(x, 0, z): ('minecraft:white_concrete' if (x + z) % 2 == 0 else 'minecraft:light_gray_concrete', {})
            for x in range(5) for z in range(5)}


def shaft(axis):
    return ('create:shaft', {'axis': axis, 'waterlogged': 'false'})


def thruster(facing):
    return ('createpropulsion:creative_thruster', {'facing': facing, 'multi': 'false'})


def main(resources):
    out = os.path.join(resources, 'assets', 'create_universal', 'ponder')

    b = plate()
    b[(2, 1, 2)] = ('create_universal:ship_assistant', {'axis': 'x'})
    b[(3, 1, 2)] = shaft('x')
    b[(4, 1, 2)] = shaft('x')
    b[(0, 1, 1)] = thruster('west')
    b[(0, 1, 3)] = thruster('west')
    b[(2, 1, 1)] = ('minecraft:redstone_wire', {'north': 'side', 'south': 'side', 'east': 'none', 'west': 'none', 'power': '0'})
    b[(2, 1, 0)] = ('minecraft:redstone_lamp', {'lit': 'false'})
    write_structure(os.path.join(out, 'ship_assistant.nbt'), (5, 3, 5), b)

    b = plate()
    for x in range(1, 4):
        for z in range(1, 4):
            b[(x, 1, z)] = ('create_universal:map_table', {})
    write_structure(os.path.join(out, 'map_table.nbt'), (5, 3, 5), b)

    b = plate()
    b[(2, 1, 2)] = ('create_universal:holo_projector', {})
    write_structure(os.path.join(out, 'holo_projector.nbt'), (5, 5, 5), b)

    b = plate()
    b[(2, 0, 2)] = shaft('y')
    b[(2, 1, 2)] = ('create_universal:gravity_stabilizer', {'facing': 'down'})
    write_structure(os.path.join(out, 'gravity_stabilizer.nbt'), (5, 4, 5), b)

    b = plate()
    b[(2, 1, 2)] = ('create_universal:cruise_drive', {'axis': 'x'})
    b[(3, 1, 2)] = shaft('x')
    b[(4, 1, 2)] = shaft('x')
    b[(0, 1, 1)] = thruster('west')
    b[(0, 1, 2)] = thruster('west')
    b[(0, 1, 3)] = thruster('west')
    write_structure(os.path.join(out, 'cruise_drive.nbt'), (5, 3, 5), b)

    b = plate()
    b[(2, 1, 2)] = ('create_universal:space_suit_tank', {'facing': 'south', 'placed': 'true', 'waterlogged': 'false'})
    b[(2, 2, 2)] = shaft('y')
    b[(2, 3, 2)] = shaft('y')
    write_structure(os.path.join(out, 'space_suit_tank.nbt'), (5, 4, 5), b)
    print('ok')


if __name__ == '__main__':
    main(sys.argv[1])
