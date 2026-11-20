# NEC Mobile

[简体中文](README.md) | **English** | [日本語](README.ja.md)

Play PC-88 and PC-98 games on Android. NEC Mobile brings the M88 PC-88 core and the NP2kai PC-98 core into one app, with separate game libraries, resources, and machine settings for each platform.

Version **0.26.8**, for **ARM64 devices running Android 8.0 or later**.

- Android APK `NEC-Mobile-0.26.8-arm64.apk`
- Source archive `NEC-Mobile-0.26.8-sources.zip`

## Features

- Import multiple disk images, folders, or ZIPs, and browse a compact library with covers, play time, name search, and media filters.
- Swap and eject floppy disks during play, including multiple disks embedded in a PC-88 D88 file.
- Relative touchpad mouse, on-screen keyboard, physical keyboard input, and automatic screen rotation.
- Per-game machine, CPU, memory, sound board, and mixer settings.
- Six manual save-state slots per game, plus separate quick-save and automatic session slots.
- SF2 MIDI synthesis and audio recovery after backgrounding or output-device changes.
- A dark-green Compose / Material 3 interface with platform switching, a recent-game card, game management, and launch-media sheets.
- The **最近更新** tab displays current and historical release notes, copied from CHANGELOG during the build.
- An independent automatic session slot, speed and aspect controls, and phone audio settings.
- Import PC-98 DOS folders into private HDI disks and select FREECG98.BMP fonts.

## Getting started

Menu labels below match the app's Chinese interface.

1. Install the APK and use the top **PC-88 / PC-98** selector. The app remembers the last platform.
2. The app supplies default boot firmware and fonts. Import game-specific firmware through **设置 → 系统 ROM / 字库**, or include it in the game's folder or ZIP.
3. Use **添加游戏 → 选择镜像** to select a game's disk set and enter its name. Folder, ZIP, and PC-98 DOS-directory import options are also available.
4. Use the list's play button to start, then choose resume or restart when a session exists. Tap a title or its menu to manage the game. Edit **启动环境与盘位** for PC-98, or **默认盘位** for PC-88. Floppy A, floppy B, and the hard disk have independent slots and a selectable boot device.

The app stores game progress in imported disk copies. The recent-game card offers resume or startup.

| Platform | Accepted disk formats |
| --- | --- |
| PC-88 | D88, 88D, with up to 64 embedded disks per file |
| PC-98 floppy | D88, 88D, FDI, XDF, HDM |
| PC-98 hard disk | HDI, NHD, HDD, THD, loaded through the boot configuration |

**游戏管理** provides renaming, machine and sound settings, disk export, and removal. For a cover, use a PNG, JPEG, or WebP image named `cover`, `folder`, `front`, or `title`. You can also capture the current game screen through **菜单 → 更多设置 → 将当前画面设为封面**.

## Controls

Tap the translucent floating button to expand or collapse the shortcuts. Drag it to either edge. Portrait and landscape positions are saved separately. The panel provides Enter, keyboard, input mode, and menu, plus Space on PC-88. Back closes the expanded panel.

Choose automatic configuration, virtual mouse, or swipe keys through **菜单 → 操作方式 / 鼠标**. Automatic configuration uses an applied preset, then the previous control mode, with swipe keys as the initial default. Complete guest mouse transactions offer a **试用鼠标** suggestion. Tap it to select and remember mouse mode for this game. Tap the in-game control-mode button to switch, or hold it to open settings. A pending switch takes effect when the current touch ends.

**操作预设** provides keypad or arrow controls with Enter/Space, a mouse touchpad, and text-command input. Recognized titles offer a preset suggestion for confirmation and association with the imported game.

| Action | Result |
| --- | --- |
| Slide in virtual mouse mode | Move the game cursor relatively |
| Tap with one finger | Left-click at the cursor's current position |
| Tap with two fingers | Right-click |
| Hold for about 350 ms, then slide | Drag with the left button held |
| Use the bottom-left mouse buttons | Click or hold a button while sliding with another finger |
| Tap 确认 / 空格 / 键盘 | Send Enter, send Space, or open the on-screen keyboard |

In swipe-key mode, each up/down/left/right gesture first sends one keypad 2/4/6/8 or arrow key. Keep touching for 420 ms to hold the direction, lift to release, or swipe in another direction to turn. A one-finger tap sends the selected Enter or Space key, and a two-finger tap sends the other confirmation key. The expanded tool ball has a central close button surrounded by Enter, Esc, Space, quick save, quick load, keyboard, control mode, and menu. Choose automatic rotation, landscape, or portrait through **菜单 → 屏幕方向**.

The game pauses when the app moves to the background or loses audio focus. It resumes in the foreground once audio focus is available and manual pause is off. After headphones disconnect, select **继续运行** from the menu. To restart audio output manually, use **菜单 → 更多设置 → 恢复声音输出**.

## Changing disks during play

1. When the game requests another disk, open **菜单 → 更换 / 取出磁盘**.
2. Select floppy drive A or B as requested by the game, then choose **换入其他盘**.
3. Select the requested disk, return to the game, and follow its prompt.

The list contains floppy disks imported for that game. For a D88 file containing several disks, choose by internal disk title or number. Use **装入磁盘** for an empty drive and **取出当前盘** to eject a disk. Disk changes take effect in the current session. Hard-disk images are selected through the boot configuration.

## Saves and progress

Open **菜单 → 即时存档 / 读档** to access six slots. Select a slot to save, load, or delete a state. You can load an existing state after starting the game again.

A save state contains the CPU, memory, device state, mounted disk selection, and MIDI channels. Loading a state combines the restored machine state with the disks' current contents. The game's own save function writes progress to the disk copies.

Loading a state restores its disk selection. A different machine or hard-disk environment triggers a player-process restart with the saved configuration. Earlier managed system-disk instances remain available, and disk removal checks save-state references.

To keep in-game saves, export the relevant disk through **管理 → 管理磁盘文件 → 导出磁盘副本**. Removing a game or uninstalling the app deletes its app-managed disks and save states.

Use **快存** and **快读** on the tool ball to overwrite or load the game's independent quick slot directly.

Backgrounding and normal exit automatically save the session. The next **启动** asks whether to resume or restart. Resume restores the machine and mounted media, while restart boots with the default media.

Cards show accumulated foreground running time. Pauses, menus, state operations, and background time are excluded. The in-game menu also shows the current session's duration. Existing games begin accumulating time after this update.

Open **设置 → 数据占用与清理** to view disk, state, resource, template, and temporary-file usage. Successful DOS-to-HDI imports remove their staged source files, and overwriting a state reclaims its old snapshot. The cleanup action removes historical import copies, abandoned imports, and unreferenced snapshots.

## Machine, sound, and MIDI settings

Open **游戏管理 → 完整机器设置** from the game card. Changes take effect the next time you start the game.

- **PC-88** offers N88-V1(S), N88-V1(H), N88-V2, N, N80, and N80-V2 modes, 4/8 MHz operation, expansion memory, OPN/OPNA, and FM, SSG, ADPCM, and rhythm levels.
- **PC-98** offers VX/VM/PC-286 models, CPU/FPU profiles, memory settings, and sound boards such as the 86, 26K, 118, and SB16 with board combinations. Mixer controls cover FM, SSG, ADPCM, PCM, rhythm, and BEEP.

PC-88 also offers main/sub CPU ratios, sub CPU scheduling, CPU/floppy waits, DIP switches, PCG, and display timing. PC-98 adds sound BIOS, GDC, scanline, palette, and LCD settings.

Speed settings are 50%, 100%, 150%, and 200%; audio pitch follows speed. Full-speed mode is muted. Aspect settings include 4:3, native pixels, fill, and integer scaling. Small screens fall back to proportional downscaling. Orientation is saved per game.

**设置 → 手机音频输出** sets the shared output rate and buffer for subsequent launches. **更多设置 → 实际音频输出** in the player shows the active route and actual AAudio parameters. Core PCM is resampled to the output rate.

Select or import PC-98 fonts through **游戏管理 → 字库设置 · FREECG98.BMP**. Imports are selected immediately for the next launch. Priority is manual choice, game package, global import, then bundled font. Within each source, FREECG98.BMP and FONT.BMP take priority over FONT.ROM. Supported BMP files are uncompressed 2048×2048 1-bit images; the app prepares a runtime copy and preserves the original. The last-start information shows the format accepted by the core.

If Japanese disk names were decoded as GBK during extraction, **游戏管理 → 修复磁盘名称编码** previews their recovery from Shift_JIS bytes. It updates display labels without reimporting the disks.

### MIDI

1. Import and select an SF2 soundfont through **设置 → MIDI 音色库** on the home screen. Each file can be up to 64 MB.
2. Enable MIDI in that game's **音源设置**.
3. Start the game and select MIDI music in its own settings.

TinySoundFont synthesizes General MIDI music using the selected SF2. PC-98 uses MPU-98 at E0D0/E0D2 with IRQ 6 by default. PC-88 uses the C2/C3 serial interface. Supply your own game images and SF2 soundfonts.

## PC-98 DOS folders

Select **添加游戏 → DOS 游戏目录 / DOS 游戏 ZIP** in the PC-98 library. The default template uses FreeDOS(98). Confirm the BAT/EXE/COM entry and create the disk. The working directory, drive letter, initialization commands, and alternative templates are available in advanced startup settings.

Each game gets a private writable HDI. Names must follow DOS 8.3 rules in code page 932. Incompatible names, collisions, or insufficient space stop the import. PC-98 FAT12/FAT16 templates support IO.SYS, MSDOS.SYS and COMMAND.COM, or the FreeDOS KERNEL.SYS and COMMAND.COM pair. Export the game HDI through disk management to back up its in-game saves.

The app creates a writable system copy for each game. Import your own PC-98 HDI template for games that require a particular commercial DOS version.

### Startup environments and save disks

PC-98 imports inspect boot records and DOS files. A clear missing-DOS layout with an identifiable entry gets a generated system disk. Games with their own boot environment use their supplied disks.

Open **游戏管理 → 启动环境与盘位** for automatic preparation or FreeDOS(98). For multiple entries, choose the data disk, enter the program path inside it and tap **准备启动环境**. Local builds can include supplied MS-DOS 2.1, 3.3D, 5.0A and 6.2 disks and the DOS 6.2 HIMEM / EMM386 drivers.

Use **管理磁盘文件 → 添加内置磁盘** to create a writable 1.2 MB FAT12 save disk or a DOS maintenance disk. The in-game disk picker can also create save disks and mount them in either floppy drive. Created disks stay in the game's disk list for reuse and export. Boot the FreeDOS maintenance disk in floppy A to use commands such as DIR, COPY, TYPE and SYS.

## Runtime resources

The APK includes the emulator cores and runtime libraries. Phones require Android 8.0 or later on ARM64.

| Resource | Current availability |
| --- | --- |
| Game disks, complete DOS folders, and game-specific drivers | Supplied by the player, including launch scripts and resident programs |
| PC-88 BASIC, floppy, character, and kanji ROMs | Bundled for the six supported BASIC modes |
| PC-98 compatible BIOS, standard font, and default sound BIOS | Supplied by the core and APK for the default configuration |
| DOS system | Bundled FreeDOS(98), with support for importing alternative PC-98 HDI templates |
| SF2 soundfont | Imported by the player when enabling MIDI |
| Fonts for translations or custom character mappings | Supplied with the game; standard fonts are bundled |
| Other sound BIOS, PC-88 dictionary, or expansion ROMs | Needed only by software or configurations that use them |
| OPNA rhythm samples, `2608_*.WAV` | The APK bundles all six samples and supports lowercase imported names and RYM/RIM aliases |

## Building

The Windows build requires **Python 3.11+, Node.js 22+, JDK 17+, and CMake 3.22+**. Run the following commands from the project root, replacing the placeholders with local paths.

```powershell
node scripts/download.mjs
python -X utf8 scripts/extract.py
python -X utf8 scripts/build.py --jdk '<JDK_PATH>' --cmake '<CMAKE_EXE>'
```

The build uses Gradle Wrapper 9.1.0, AGP 9.0.0, Kotlin/Compose compiler 2.2.10, API 36 Extension 19, and NDK r28c. The scripts install SDK/NDK files in `.cache/` and use Gradle to compile the Kotlin UI and four native libraries. Output goes to `release/`. Versions are defined in `version.properties`.

Restore the original `.cache/signing/development.jks` for an upgrade. For a new project, create your own RSA keystore at that path with alias `necmobile` and development password `android`. Private keys are excluded from source archives.

The default build includes FreeDOS(98). To package your own MS-DOS resources locally, run `scripts/build.py --bundle-dos --dos-assets <asset-directory>` with the DOS files in its `dos/` subdirectory. The [Chinese build guide](README.md) lists the supported asset names.

### Source archive

```powershell
python -X utf8 scripts/package_sources.py
```

## Code layout

The library and settings use Kotlin with Compose / Material 3; business logic and the player use Java 17. The emulation host uses C/C++, JNI, ANativeWindow, and AAudio. DOS disk writing uses FatFs.

| Directory | Contents |
| --- | --- |
| `app/` | Game library, player interface, input, and settings |
| `native/` | Emulation thread, video, audio, MIDI, and core adapters |
| `third_party/` | M88, NP2kai, TinySoundFont, and FatFs sources |
| `scripts/` | Dependency downloads, source adaptations, builds, and packaging |
| `tests/` | Java, native, and Android feature checks |
| `release/` | APK and source archives |

## Credits and licenses

- The PC-88 core is based on M88 by cisc. [M88](https://github.com/rururutan/m88)
- The PC-98 core is based on NP2kai. [NP2kai](https://github.com/libretro/NP2kai)
- MIDI synthesis uses TinySoundFont v0.9 under the MIT license. [TinySoundFont](https://github.com/schellingb/TinySoundFont)

The original NEC Mobile code uses GPL version 2. The license index lists third-party terms and the linking exception. [License index](LICENSE.md) · [FreeDOS(98) source](third_party/freedos98/README.md)
