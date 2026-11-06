# NEC Mobile

[简体中文](README.md) | **English** | [日本語](README.ja.md)

Play PC-88 and PC-98 games on Android. NEC Mobile brings the M88 PC-88 core and the NP2kai PC-98 core into one app, with separate game libraries, resources, and machine settings for each platform.

Current version **0.2.4-alpha**, for **ARM64 devices running Android 8.0 or later**.

- [Download APK](https://github.com/yy487/NEC-Mobile/releases/download/v0.2.4-alpha/NEC-Mobile-0.2.4-alpha-arm64.apk)
- [Download source archive](https://github.com/yy487/NEC-Mobile/releases/download/v0.2.4-alpha/NEC-Mobile-0.2.4-alpha-sources.zip)

## Features

- Import each game from a folder or ZIP and manage its disks, cover, and last-played time from a game card.
- Swap and eject floppy disks during play, including multiple disks embedded in a PC-88 D88 file.
- Relative touchpad mouse, on-screen keyboard, physical keyboard input, and automatic screen rotation.
- Per-game machine, CPU, memory, sound board, and mixer settings.
- Six manual save-state slots per game.
- SF2 MIDI synthesis and audio recovery after backgrounding or output-device changes.

## Getting started

Menu labels below match the app's Chinese interface.

1. Install the APK, open the app, and select **PC-88** or **PC-98**.
2. Prepare the system ROMs and fonts needed by the game. Import them through **设置 → 导入系统 ROM / 字库**. You can also include game-specific resources in the game's folder or ZIP.
3. Select **添加游戏** and choose a folder or ZIP containing one game and its complete disk set.
4. Tap the game card to start. To change the boot disk pair, open **管理 → 启动磁盘** and select the first and second disks.

The app stores game progress in imported disk copies. Tapping the card again boots the machine with the saved disk selection.

| Platform | Accepted disk formats |
| --- | --- |
| PC-88 | D88, 88D, with up to 64 embedded disks per file |
| PC-98 floppy | D88, 88D, FDI, XDF, HDM |
| PC-98 hard disk | HDI, NHD, HDD, THD, loaded through the boot configuration |

The game card's **管理** menu provides renaming, machine and sound settings, disk export, and removal. For a cover, use a PNG, JPEG, or WebP image named `cover`, `folder`, `front`, or `title`. You can also capture the current game screen through **菜单 → 更多设置 → 将当前画面设为封面**.

## Controls

Choose an input mode through **菜单 → 操作方式 / 鼠标**. PC-88 defaults to keyboard controls and PC-98 to the virtual mouse. The choice is saved per game.

| Action | Result |
| --- | --- |
| Slide in virtual mouse mode | Move the game cursor relatively |
| Tap with one finger | Left-click at the cursor's current position |
| Tap with two fingers | Right-click |
| Hold for about 350 ms, then slide | Drag with the left button held |
| Use the bottom-left mouse buttons | Click or hold a button while sliding with another finger |
| Tap 确认 / 空格 / 键盘 | Send Enter, send Space, or open the on-screen keyboard |

In keyboard mode, tapping the game screen sends Enter. Use the on-screen keyboard for number choices and other keyboard controls. Choose automatic rotation, landscape, or portrait through **菜单 → 屏幕方向**.

The game pauses when the app moves to the background or loses audio focus. It resumes in the foreground once audio focus is available and manual pause is off. After headphones disconnect, select **继续运行** from the menu. To restart audio output manually, use **菜单 → 更多设置 → 恢复声音输出**.

## Changing disks during play

1. When the game requests another disk, open **菜单 → 更换 / 取出磁盘**.
2. Select the drive to change, then choose **换入其他盘**.
3. Select the requested disk, return to the game, and follow its prompt.

The list contains floppy disks imported for that game. For a D88 file containing several disks, choose by internal disk title or number. Use **装入磁盘** for an empty drive and **取出当前盘** to eject a disk. Disk changes take effect in the current session. Hard-disk images are selected through the boot configuration.

## Saves and progress

Open **菜单 → 即时存档 / 读档** to access six slots. Select a slot to save, load, or delete a state. You can load an existing state after starting the game again.

A save state contains the CPU, memory, device state, mounted disk selection, and MIDI channels. Loading a state combines the restored machine state with the disks' current contents. The game's own save function writes progress to the disk copies.

Load states using the same game copy, machine configuration, and sound hardware settings used when saving, including the MIDI enable setting. Mixer levels and the SF2 soundfont can be changed independently.

To keep in-game saves, export the relevant disk through **管理 → 管理磁盘文件 → 导出磁盘副本**. Removing a game or uninstalling the app deletes its app-managed disks and save states.

## Machine, sound, and MIDI settings

Open **管理 → 机器设置 / 音源设置** from the game card. Changes take effect the next time you start the game.

- **PC-88** offers N88 BASIC modes, 4/8 MHz operation, expansion memory, OPN/OPNA, and FM, SSG, ADPCM, and rhythm levels.
- **PC-98** offers VX/VM/PC-286 models, CPU/FPU profiles, memory settings, and sound boards such as the 86, 26K, 118, and SB16 with board combinations. Mixer controls cover FM, SSG, ADPCM, PCM, rhythm, and BEEP.

### MIDI

1. Import and select an SF2 soundfont through **设置 → MIDI 音色库** on the home screen. Each file can be up to 64 MB.
2. Enable MIDI in that game's **音源设置**.
3. Start the game and select MIDI music in its own settings.

TinySoundFont synthesizes General MIDI music using the selected SF2. PC-98 uses MPU-98 at E0D0/E0D2 with IRQ 6 by default. PC-88 uses the C2/C3 serial interface. Supply your own game images, ROMs, fonts, and SF2 soundfonts.

## Building

The Windows build requires **Python 3.11+, Node.js 22+, JDK 17+, and CMake 3.22+**. Run the following commands from the project root, replacing the placeholders with local paths.

```powershell
node scripts/download.mjs
python -X utf8 scripts/extract.py
python -X utf8 scripts/build.py --jdk '<JDK_PATH>' --cmake '<CMAKE_EXE>'
```

The scripts place Android SDK, NDK, and other dependencies in the project's `.cache/` and `work/` directories, compile both cores, build the interface, and sign the APK. Output goes to `release/`.

Keep `.cache/signing/development.jks` to sign future builds with the same key and install them as updates.

### Source archive

```powershell
python -X utf8 scripts/package_sources.py
```

## Code layout

The interface uses Java 17 and native Android widgets. The emulation host uses C/C++, JNI, ANativeWindow, and AAudio.

| Directory | Contents |
| --- | --- |
| `app/` | Game library, player interface, input, and settings |
| `native/` | Emulation thread, video, audio, MIDI, and core adapters |
| `third_party/` | M88, NP2kai, and TinySoundFont sources |
| `scripts/` | Dependency downloads, source adaptations, builds, and packaging |
| `tests/` | Java, native, and Android feature checks |
| `release/` | APK and source archives |

## Credits and licenses

- The PC-88 core is based on M88 by cisc. [M88](https://github.com/rururutan/m88)
- The PC-98 core is based on NP2kai. [NP2kai](https://github.com/libretro/NP2kai)
- MIDI synthesis uses TinySoundFont v0.9 under the MIT license. [TinySoundFont](https://github.com/schellingb/TinySoundFont)

Original component copyright notices and licenses are stored in the app's assets and can also be viewed through **设置 → 来源与许可**. [License index](LICENSE.md) · [License files](app/src/main/assets/licenses/)
