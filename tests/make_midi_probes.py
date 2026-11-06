"""Create original minimal boot disks which send one MIDI chord to the real ports."""
from pathlib import Path
import argparse
import struct

parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
args=parser.parse_args()
args.output.mkdir(parents=True,exist_ok=True)
notes=[0xc0,0,0x90,60,110,64,110,67,110]
# PC-8801 boot loader enters C000 after reading the boot track.
z80=bytearray.fromhex('f3 31 00 f0')
for value in [0,0,0,0x40,0x4e,0x37]: z80+=bytes([0x3e,value,0xd3,0xc3])
for value in notes: z80+=bytes([0x3e,value,0xd3,0xc2])
z80+=bytearray.fromhex('21 00 80 34 18 fd')
track=bytearray()
for sector in range(1,17):
    payload=bytearray(256)
    if sector==1: payload[:len(z80)]=z80
    track+=struct.pack('<BBBBHBBB5sH',0,0,sector,1,16,0,0,0,b'\0'*5,256)+payload
disk=bytearray(0x2b0)
disk[:14]=b'NEC MIDI probe'
struct.pack_into('<I',disk,0x20,0x2b0)
disk+=track
struct.pack_into('<I',disk,0x1c,len(disk))
(args.output/'midi.d88').write_bytes(disk)
# PC-98 1.23 MB floppy; BIOS enters its first sector at CS:0000.
x86=bytearray.fromhex('fa 0e 1f b8 00 90 8e d0 bc f0 ff ba d2 e0 b0 3f ee ba d0 e0')
for value in notes: x86+=bytes([0xb0,value,0xee])
x86+=bytearray.fromhex('ff 06 00 02 eb fa')
disk=bytearray(77*2*8*1024)
disk[:len(x86)]=x86
disk[510:512]=b'\x55\xaa'
(args.output/'midi.hdm').write_bytes(disk)
print('Created PC-88 C2/C3 and PC-98 E0D0/E0D2 MIDI probes')
