# 第三方组件与许可证

这个模拟器用到的第三方组件，以及它们各自的许可证。

## 模拟核心（emu-core-rs，三端的发布产物都带着它）

| 组件 | 许可证 | 说明 |
| --- | --- | --- |
| [unicorn-engine](https://www.unicorn-engine.org/) 2.1.5 | **GPL-2.0-only** | ARM CPU 模拟。源自 QEMU。 |
| [capstone](https://www.capstone-engine.org/) 0.13 | MIT | 反汇编，只在排查和分析时用 |
| encoding_rs 0.8 | (Apache-2.0 OR MIT) AND BSD-3-Clause | GBK ↔ Unicode |
| miniz_oxide 0.8 | MIT OR Zlib OR Apache-2.0 | 资源解压 |
| libc / cfg-if / adler2 | MIT OR Apache-2.0 等 | |

**整个发布产物因此是 GPL-2.0。** unicorn 是 GPL-2.0，链接它的二进制只能按同样的条款分发。

## 三端外壳

| 外壳 | 依赖 | 许可证 |
| --- | --- | --- |
| Windows（emu-windows） | Qt 6（Core / Gui / Widgets / Test） | 本项目按 Qt 的 **GPL-2.0-only** 选项使用（Qt 同时提供 LGPL-3.0 / GPL-2.0 / GPL-3.0） |
| Windows | winmm（MCI，系统自带） | 系统组件 |
| 安卓（emu-android） | [Chaquopy](https://chaquo.com/chaquopy/) | MIT（嵌入的 CPython：PSF-2.0） |
| macOS（emu-macos） | AVFoundation / SwiftUI | 系统框架 |

## 字库

| 字库 | 许可证 |
| --- | --- |
| [Fusion Pixel Font 缝合像素字体](https://github.com/TakWolf/fusion-pixel-font) 12px | SIL Open Font License 1.1 |
| [GNU Unifont](https://unifoundry.com/unifont/) | SIL Open Font License 1.1（兜底字形） |

字库的完整许可证见各仓库里的 `FONT-LICENSE.txt`。

## 不在这里的东西

模拟器不含任何原机固件、系统文件或游戏数据。`.cbe` 模块、固件镜像都要使用者自备。
