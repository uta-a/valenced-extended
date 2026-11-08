#!/usr/bin/env python3
"""旧版の完全一致パターンを別版ELFの実行可能セクションで探索する。python -I で実行。"""
import argparse
import json
import re
from pathlib import Path
from elftools.elf.elffile import ELFFile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('elf', type=Path)
p.add_argument('patterns', type=Path)
p.add_argument('version')
a = p.parse_args()
patterns = json.loads(a.patterns.read_text())[a.version]
with a.elf.open('rb') as f:
    elf = ELFFile(f)
    sections = [(s['sh_addr'], s.data()) for s in elf.iter_sections() if s['sh_flags'] & 4]
for name, spec in patterns.items():
    pattern = bytes.fromhex(spec['bytes'])
    if spec['mask'] != 'ff' * len(pattern):
        raise ValueError('完全一致以外のマスクには対応しない')
    matches = [hex(base + m.start()) for base, data in sections for m in re.finditer(re.escape(pattern), data)]
    print(name, 'size', len(pattern), 'matches', matches)
