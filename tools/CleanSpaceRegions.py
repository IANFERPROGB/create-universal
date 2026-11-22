"""Quita de un mundo los chunks del espacio que están vacíos (beta.3).

Antes de la beta.2 cada chunk del espacio por el que se pasaba se guardaba aunque estuviera vacío (14 GB en "CU Lab 5").
Esto lee cada archivo de región de dimensions/create_universal/space/region y deja solo los chunks que tienen algún
bloque que no sea aire o algún block entity (estaciones, la zona interna de las naves). Si una región se queda sin
nada, se borra el archivo. Las entidades, los POI y las naves de Sable van en otras carpetas y no se tocan.

Uso: python -I tools/CleanSpaceRegions.py <carpeta del mundo> [--apply]
Sin --apply solo cuenta lo que haría. HAZ UNA COPIA DEL MUNDO ANTES y con el juego cerrado.
"""
import gzip
import os
import re
import struct
import sys
import zlib
from multiprocessing import Pool

AIR = {b'minecraft:air', b'minecraft:cave_air', b'minecraft:void_air'}
NAME = re.compile(rb'\x08\x00\x04Name(..)', re.S)
BLOCK_ENTITIES = b'\x09\x00\x0eblock_entities'


def chunk_is_empty(data):
    """True si el NBT (sin comprimir) del chunk no tiene bloques que no sean aire ni block entities."""
    for m in NAME.finditer(data):
        n = struct.unpack('>H', m.group(1))[0]
        start = m.end()
        if data[start:start + n] not in AIR:
            return False
    i = data.find(BLOCK_ENTITIES)
    if i >= 0:
        count = struct.unpack('>i', data[i + len(BLOCK_ENTITIES) + 1:i + len(BLOCK_ENTITIES) + 5])[0]
        if count > 0:
            return False
    return True


def read_chunk(raw, offset):
    length = struct.unpack('>I', raw[offset:offset + 4])[0]
    kind = raw[offset + 4]
    payload = raw[offset + 5:offset + 4 + length]
    return length, kind, payload


def process(args):
    path, apply = args
    with open(path, 'rb') as f:
        raw = f.read()
    if len(raw) < 8192:
        return path, 0, 0, 0, 'tiny'
    locations = raw[:4096]
    timestamps = raw[4096:8192]
    keep = []
    total = 0
    for i in range(1024):
        loc = struct.unpack('>I', locations[i * 4:i * 4 + 4])[0]
        sector = loc >> 8
        if sector == 0:
            continue
        total += 1
        offset = sector * 4096
        try:
            length, kind, payload = read_chunk(raw, offset)
            if kind == 2:
                data = zlib.decompress(payload)
            elif kind == 1:
                data = gzip.decompress(payload)
            elif kind == 3:
                data = payload
            else:
                keep.append((i, raw[offset:offset + 4 + length]))  # LZ4 o externo: no se toca
                continue
        except Exception:
            keep.append((i, raw[offset:offset + 4 + struct.unpack('>I', raw[offset:offset + 4])[0]]))
            continue
        if not chunk_is_empty(data):
            keep.append((i, raw[offset:offset + 4 + length]))
    removed = total - len(keep)
    if apply and removed > 0:
        if not keep:
            os.remove(path)
        else:
            out_loc = bytearray(4096)
            body = bytearray()
            sector = 2
            for i, blob in keep:
                size = (len(blob) + 4095) // 4096
                out_loc[i * 4:i * 4 + 4] = struct.pack('>I', (sector << 8) | min(size, 255))
                body += blob + b'\x00' * (size * 4096 - len(blob))
                sector += size
            tmp = path + '.tmp'
            with open(tmp, 'wb') as f:
                f.write(bytes(out_loc) + timestamps + bytes(body))
            os.replace(tmp, path)
    return path, total, len(keep), removed, 'ok'


def main():
    world = sys.argv[1]
    apply = '--apply' in sys.argv
    region = os.path.join(world, 'dimensions', 'create_universal', 'space', 'region')
    files = [os.path.join(region, f) for f in os.listdir(region) if f.endswith('.mca')]
    before = sum(os.path.getsize(f) for f in files)
    total = kept = 0
    emptied = 0
    with Pool(8) as pool:
        for n, (path, t, k, r, status) in enumerate(pool.imap_unordered(process, [(f, apply) for f in files], chunksize=8), 1):
            total += t
            kept += k
            emptied += 1 if t > 0 and k == 0 else 0
            if n % 500 == 0:
                print('%d/%d regiones...' % (n, len(files)), flush=True)
    after = sum(os.path.getsize(os.path.join(region, f)) for f in os.listdir(region) if f.endswith('.mca'))
    print('Regiones: %d (%d se quedan vacías del todo)' % (len(files), emptied))
    print('Chunks: %d en total, %d con algo (se quedan), %d vacíos (%s)' % (total, kept, total - kept,
                                                                         'quitados' if apply else 'se quitarían'))
    print('Tamaño: %.2f GB antes, %.2f GB ahora' % (before / 1e9, after / 1e9))


if __name__ == '__main__':
    main()
