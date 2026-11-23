# NEC Mobile for iPhone and iPad

iOS 15 或更新系统，ARM64 设备。PC-88 使用 M88，PC-98 使用 NP2kai，与 Android 共用模拟循环、键鼠输入队列、音频混音、换盘及 DOS 文件系统代码。

## 云端构建

GitHub Actions 的 **iOS build** 工作流使用 macOS 和 Xcode。`main` 与 `ios-port` 分支的相关改动自动触发构建，工作流也提供手动运行入口。

构建产物位于该次运行的 **Artifacts → NEC-Mobile-iOS**。

- `NEC-Mobile-iOS-unsigned.ipa` 用于设备持有人进行个人签名后安装。
- `NEC-Mobile-iOS-simulator.zip` 包含模拟器 App。

运行详情中的 **Run simulator smoke check** 会通过真实游玩会话检查导入、画面、压缩即时档、退盘装盘、后台保存、时长与继续游戏；**iOS-simulator-check** 产物保留界面截图和结果。

## Windows 朋友测试

1. 从工作流产物下载 ZIP 并取出 IPA。
2. 设备持有人在自己的 Windows 电脑准备 Apple 设备驱动和 Sideloadly。
3. 连接 iPhone / iPad，信任该电脑，在 Sideloadly 中选择 IPA，使用设备持有人自己的 Apple ID 签名安装。
4. 按系统提示信任个人开发者。iOS 16 或更新系统开启开发者模式。
5. 打开应用，通过“添加游戏”选择镜像、文件夹或 ZIP。多盘游戏可以一次选入多张镜像。PC-98 DOS 目录可生成独立 HDI。

个人免费签名通常需要每 7 天续签。TestFlight 分发使用 Apple Developer Program 团队的签名和 App Store Connect 配置。

## 游戏与资源

应用在 Documents/NECMobile 内保存独立可写磁盘、资源和存档，可通过系统“文件”App 或电脑文件共享访问。导出磁盘副本可备份游戏内存档。

PC-98 提供内置 FreeDOS(98) 软盘、硬盘模板和空白存档盘。自备 DOS 系统 HDI / FDI 可从设置导入。XMS / EMS 配置使用匹配的自备 DOS 内存驱动。

ROM、字库和 OPNA 节奏 WAV 可全局或按游戏导入。FREECG98.BMP 使用 2048×2048、1 位未压缩 BMP。MIDI 使用 SF2 音色库。

运行时通过环形工具球使用 Esc、Enter、空格、快存、快读、键盘与菜单。支持滑动方向持续按住、鼠标触控板、实体键盘、换盘和退盘。

六个手动槽、快速槽和自动会话独立保存。切后台时申请有限的后台执行时间完成会话保存，重新启动时可继续游戏。即时档采用流式压缩，读取时临时展开。

## 本地 macOS 编译

需要 Xcode、CMake、Python 3 和 JDK 17。

```sh
python3 scripts/build_ios.py
python3 scripts/build_ios.py --simulator
python3 scripts/smoke_ios.py
```

应用和内核资源沿用项目许可。许可文件位于应用“设置 → 来源与许可”。内置 FreeDOS(98) 对应源码位于仓库 third_party/freedos98。
