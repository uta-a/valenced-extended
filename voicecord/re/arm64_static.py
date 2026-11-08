#!/usr/bin/env python3
"""AArch64 ELF の文字列参照・分岐・逆アセンブルを調べる。python -I で実行する。"""
import argparse
import hashlib
import re
import struct
from pathlib import Path
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('elf', type=Path)
p.add_argument('--strings', help='文字列の正規表現')
p.add_argument('--xref', type=lambda x: int(x, 0), help='ADRP+ADD/LDR または BL/B の参照先')
p.add_argument('--disasm', type=lambda x: int(x, 0))
p.add_argument('--size', type=lambda x: int(x, 0), default=0x180)
p.add_argument('--pattern', type=lambda x: int(x, 0), help='関数先頭の固定バイト列（--size バイト）を生成')
a = p.parse_args()
with a.elf.open('rb') as f:
    elf = ELFFile(f)
    sections = [(s.name, s['sh_addr'], s.data(), s['sh_flags']) for s in elf.iter_sections() if s['sh_type'] != 'SHT_NOBITS']
    if not (a.strings or a.xref is not None or a.disasm is not None or a.pattern is not None):
        f.seek(0)
        print('sha256', hashlib.file_digest(f, 'sha256').hexdigest())
        for name, addr, data, flags in sections:
            print(name, hex(addr), hex(len(data)), hex(flags))
        for sym in elf.get_section_by_name('.dynsym').iter_symbols():
            if any(k in sym.name for k in ('Connection8GetStats', 'Connection12SetPTTActive', 'nativeDestroyInstance', 'nativeCreateBuiltinAudioEncoderFactory')):
                print(hex(sym['st_value']), sym.name)
cs = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
for name, base, data, flags in sections:
    if a.strings and not flags & 4:
        for m in re.finditer(rb'[\x20-\x7e]{5,}', data):
            text = m.group().decode('ascii')
            if re.search(a.strings, text, re.I):
                print(hex(base + m.start()), text)
    if not flags & 4:
        continue
    if a.disasm is not None and base <= a.disasm < base + len(data):
        off = a.disasm - base
        for ins in cs.disasm(data[off:off+a.size], a.disasm):
            print(f'{ins.address:#010x}: {ins.bytes.hex()} {ins.mnemonic:8} {ins.op_str}')
    if a.pattern is not None and base <= a.pattern < base + len(data):
        off = a.pattern - base
        pattern = data[off:off+a.size]
        print('bytes', pattern.hex())
        print('mask', 'ff' * len(pattern))
        print('matches', [hex(addr + m.start()) for _, addr, buf, fl in sections if fl & 4 for m in re.finditer(re.escape(pattern), buf)])
    if a.xref is not None:
        # ADRP の直後だけでなく、間に引数設定を挟む参照も拾う。
        pages = {}
        for off in range(0, len(data) - 3, 4):
            word, = struct.unpack_from('<I', data, off)
            pc = base + off
            if word & 0x9f000000 == 0x90000000:
                imm = ((word >> 5 & 0x7ffff) << 2) | (word >> 29 & 3)
                if imm & 0x100000:
                    imm -= 0x200000
                pages[word & 31] = ((pc & ~4095) + (imm << 12), pc)
            elif word & 0x9f000000 == 0x10000000:
                imm = ((word >> 5 & 0x7ffff) << 2) | (word >> 29 & 3)
                if imm & 0x100000:
                    imm -= 0x200000
                if pc + imm == a.xref:
                    print(f'{pc:#x} ADR -> {a.xref:#x}')
            elif word & 0x7f000000 == 0x11000000:
                reg = word >> 5 & 31
                if reg in pages:
                    page, origin = pages[reg]
                    imm = (word >> 10 & 4095) << (12 if word >> 22 & 1 else 0)
                    if pc - origin <= 80 and page + imm == a.xref:
                        print(f'{origin:#x} -> {pc:#x} ADRP+ADD -> {a.xref:#x}')
            elif word & 0x7c000000 == 0x14000000:
                imm = word & 0x3ffffff
                if imm & 0x2000000:
                    imm -= 0x4000000
                if pc + imm * 4 == a.xref:
                    print(f'{pc:#x} {"BL" if word >> 31 else "B"} -> {a.xref:#x}')
