# NEC Mobile

**简体中文** | [English](README.en.md) | [日本語](README.ja.md)

在 Android 上运行 PC-88 和 PC-98 游戏。项目将 M88 的 PC-88 内核与 NP2kai 的 PC-98 内核集成到同一个应用，分别管理两种平台的游戏、资源和机器配置。

当前版本 **0.2.4-alpha**，适用于 **Android 8.0 及以上、ARM64** 设备。

- [下载 APK](https://github.com/yy487/NEC-Mobile/releases/download/v0.2.4-alpha/NEC-Mobile-0.2.4-alpha-arm64.apk)
- [下载源码包](https://github.com/yy487/NEC-Mobile/releases/download/v0.2.4-alpha/NEC-Mobile-0.2.4-alpha-sources.zip)

## 功能

- 按游戏导入文件夹或 ZIP，用卡片管理磁盘、封面和上次游玩时间。
- 双软驱运行中换盘、退盘，支持 PC-88 D88 文件中的多张内嵌盘。
- 相对触控板鼠标、屏幕键盘、实体键盘输入和自动旋转。
- 按游戏保存机型、CPU、内存、声卡及分项音量。
- 每款游戏 6 个手动即时存档槽。
- SF2 MIDI 合成，以及前后台和音频设备切换后的声音恢复。

## 开始使用

1. 安装 APK，打开应用并选择 **PC-88** 或 **PC-98**。
2. 准备游戏所需的系统 ROM 和字库，从 **设置 → 导入系统 ROM / 字库** 导入。配套资源也可与磁盘放在同一游戏文件夹或 ZIP 中。
3. 点击 **添加游戏**，选择单款游戏的文件夹或 ZIP，将整套磁盘一起导入。
4. 点击游戏卡片启动。需要调整启动盘时，进入 **管理 → 启动磁盘**，选择第一张盘和第二张盘。

应用使用导入后的磁盘副本保存游戏进度。再次点击卡片时，按已记住的启动盘组合启动机器。

| 平台 | 可导入的磁盘格式 |
| --- | --- |
| PC-88 | D88、88D，单个文件最多 64 张内嵌盘 |
| PC-98 软盘 | D88、88D、FDI、XDF、HDM |
| PC-98 硬盘 | HDI、NHD、HDD、THD，通过启动盘配置加载 |

游戏卡片的 **管理** 提供重命名、机器与音源设置、磁盘导出和移除操作。封面可使用名为 `cover`、`folder`、`front` 或 `title` 的 PNG、JPEG、WebP 图片，也可通过 **菜单 → 更多设置 → 将当前画面设为封面** 截取。

## 操作

从 **菜单 → 操作方式 / 鼠标** 选择操作模式。PC-88 默认使用按键，PC-98 默认使用虚拟鼠标，选择会按游戏保存。

| 操作 | 效果 |
| --- | --- |
| 在虚拟鼠标模式下滑动 | 相对移动游戏光标 |
| 单指轻触 | 在光标当前位置点击左键 |
| 双指轻触 | 点击右键 |
| 长按约 350 毫秒后滑动 | 按住左键拖动 |
| 左下角左右键 | 点击或按住，可配合另一只手滑动 |
| 确认 / 空格 / 键盘 | 发送 Enter、Space 或展开屏幕键盘 |

按键模式下，轻触画面发送 Enter。数字选择和依赖键盘操作的游戏可使用屏幕键盘。屏幕方向可从 **菜单 → 屏幕方向** 选择自动、横屏或竖屏。

游戏在切后台或失去音频焦点时暂停，回到前台并取得焦点后，按手动暂停设置决定是否继续。耳机断开后，从菜单选择 **继续运行**。需要手动恢复声音时，使用 **菜单 → 更多设置 → 恢复声音输出**。

## 游戏中途换盘

1. 游戏提示换盘时，打开 **菜单 → 更换 / 取出磁盘**。
2. 选择要操作的磁盘槽，再点 **换入其他盘**。
3. 选择游戏要求的盘，返回游戏并按提示继续。

列表显示本游戏已导入的软盘。D88 内含多张盘时，按内部盘名或序号选择。空槽使用 **装入磁盘**，退盘使用 **取出当前盘**。换盘沿用当前运行会话，硬盘镜像通过启动配置选择。

## 存档与进度

从 **菜单 → 即时存档 / 读档** 打开六槽面板，选择一个槽保存、读取或删除。重新启动游戏后，可从这里读取已有即时档。

即时档保存 CPU、内存、设备状态、装盘组合和 MIDI 通道。读取时，恢复的机器状态与磁盘当前内容一起使用。游戏自带的存档功能将进度写入磁盘副本。

读取即时档时，使用保存时的游戏副本、机器配置和音源硬件设置，包括 MIDI 开关。混音器中的音量与 SF2 音色库可自行更换。

需要保留游戏内存档时，从 **管理 → 管理磁盘文件 → 导出磁盘副本** 导出对应磁盘。移除游戏或卸载应用会删除应用内磁盘和即时档。

## 机器、音源与 MIDI

从游戏卡片的 **管理 → 机器设置 / 音源设置** 调整配置，修改在下次启动生效。

- **PC-88** 提供 N88 BASIC 模式、4/8 MHz、扩展内存、OPN/OPNA 和 FM、SSG、ADPCM、节奏音量。
- **PC-98** 提供 VX/VM/PC-286、CPU/FPU 档位、内存，以及 86、26K、118、SB16 等声卡及组合，支持 FM、SSG、ADPCM、PCM、节奏和 BEEP 音量。

### MIDI

1. 从首页 **设置 → MIDI 音色库** 导入并选择 SF2，每份最大 64 MB。
2. 在该游戏的 **音源设置** 中启用 MIDI。
3. 启动游戏，在游戏自身的设置中选择 MIDI 音乐。

MIDI 通过 TinySoundFont 使用 SF2 合成 General MIDI 音乐。PC-98 使用 MPU-98，默认端口 E0D0/E0D2、IRQ 6。PC-88 使用 C2/C3 串行接口。游戏镜像、ROM、字库和 SF2 音色库由使用者自行准备。

## 构建

Windows 构建需要 **Python 3.11+、Node.js 22+、JDK 17+、CMake 3.22+**。在项目根目录运行以下命令，将占位符替换为本机路径。

```powershell
node scripts/download.mjs
python -X utf8 scripts/extract.py
python -X utf8 scripts/build.py --jdk '<JDK_PATH>' --cmake '<CMAKE_EXE>'
```

脚本将 Android SDK、NDK 等依赖放在项目的 `.cache/` 和 `work/` 中，编译两个内核、构建界面并签名 APK。产物写入 `release/`。

后续构建沿用 `.cache/signing/development.jks`，保留此文件以使用相同签名更新安装。

### 源码打包

```powershell
python -X utf8 scripts/package_sources.py
```

## 代码结构

界面使用 Java 17 和 Android 原生控件，模拟宿主使用 C/C++、JNI、ANativeWindow 和 AAudio。

| 目录 | 内容 |
| --- | --- |
| `app/` | 游戏库、运行界面、输入与设置 |
| `native/` | 模拟线程、视频、音频、MIDI 与内核适配 |
| `third_party/` | M88、NP2kai、TinySoundFont 源码 |
| `scripts/` | 依赖下载、源码适配、构建与打包 |
| `tests/` | Java、原生代码与 Android 功能检查 |
| `release/` | APK 和源码包 |

## 来源与许可

- PC-88 内核基于 cisc 开发的 M88。[M88](https://github.com/rururutan/m88)
- PC-98 内核基于 NP2kai。[NP2kai](https://github.com/libretro/NP2kai)
- MIDI 合成使用 TinySoundFont v0.9，采用 MIT 许可。[TinySoundFont](https://github.com/schellingb/TinySoundFont)

各组件的版权和许可原文保存在应用资源中，也可从 **设置 → 来源与许可** 查看。[许可索引](LICENSE.md) · [许可文件](app/src/main/assets/licenses/)
