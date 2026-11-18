# FreeDOS(98) corresponding source

The Android package includes PC-98 FreeDOS system disks. This directory
contains the upstream source archives for the system files in those disks.

| File in the disk | Upstream binary release | Corresponding source |
| --- | --- | --- |
| `KERNEL.SYS` and `SYS.COM` | `fd98_2hd144_20220123.zip`, from [lpproj/fdkernel test-20220120-cherrypick](https://github.com/lpproj/fdkernel/releases/tag/test-20220120-cherrypick) | [fdkernel-source-20220120.zip](fdkernel-source-20220120.zip) |
| `COMMAND.COM` | `nec98/japanese/command.upx` from `freecom_dbcswip-20220117-bin.zip`, from [lpproj/freecom_dbcs2 dbcswip-20220117](https://github.com/lpproj/freecom_dbcs2/releases/tag/dbcswip-20220117) | [freecom-source-20220117.zip](freecom-source-20220117.zip) |

The floppy boot records come from the kernel release's 2HD image. The HDI uses
the IPL and FreeDOS partition boot records from the same release's
`fd98_hd_250m_20220123.zip`. The upstream documentation `BTNPART.TXT`
identifies the hard-disk IPL as reusable as public-domain code. Kernel, SYS
and shell binaries are copied from the named releases.

NEC Mobile wraps the floppy in an FDI header, creates a smaller 512-byte
sector FAT16 partition for the HDI, and includes only the files listed above.
The app writes a launch script and DOS configuration when preparing a game.
The source archives include the upstream build instructions and license
notices. [GNU GPL version 2](../../COPYING)
