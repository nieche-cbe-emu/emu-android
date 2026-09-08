# emu-android

CoolBar `.cbe` 模拟器的安卓外壳。Chaquopy 将 CPython 嵌入应用进程，Kotlin 直接
调用 `nieche.py`；实际执行由
[emu-core-rs](https://github.com/nieche-cbe-emu/emu-core-rs) 的
`libnieche.so` 完成。

## 特性

- 虚拟键盘与软键；触摸事件排队下发，不合并按下与抬起
- 双指捏合缩放与平移，单指为游戏触摸
- 帧率可任意设定，状态栏显示实测帧率
- 游戏库：记录用过的模块
- 核心加载失败时在界面上给出原因，不作降级

## 环境要求

- Android 7.0（API 24）及以上，`arm64-v8a` 或 `armeabi-v7a`
- 构建需要 JDK 17、Android SDK（compileSdk 34、build-tools 34）
- Gradle 8.7（wrapper 已锁定；Gradle 9 移除了插件仍在使用的 `org.gradle.util.VersionNumber`）

## 构建

```bash
./sync_python.sh          # 从同级的 emu-core-rs 拷入 nieche.py
./gradlew assembleDebug
```

`app/src/main/jniLibs/<abi>/libnieche.so` 不在仓库内，用
[emu-tools](https://github.com/nieche-cbe-emu/emu-tools) 的
`tools/build-android.sh` 交叉编译后放入。

## 关键配置

| 项 | 值 | 位置 |
|---|---|---|
| `minSdk` | `24` | `app/build.gradle` |
| `targetSdk` / `compileSdk` | `34` | `app/build.gradle` |
| `abiFilters` | `arm64-v8a`, `armeabi-v7a` | `app/build.gradle` |
| Python 版本 | `3.11` | `app/build.gradle`（Chaquopy） |
| `jniLibs.useLegacyPackaging` | `true` | `app/build.gradle`，`.so` 必须解压到 `nativeLibraryDir` |

## 环境变量

由 `nieche_bridge.init()` 在启动时设置，不需要手工配置：

| 变量 | 值 |
|---|---|
| `NIECHE_HOME` | 应用私有目录 |
| `NIECHE_LIB` | `<nativeLibraryDir>/libnieche.so` |

## 帧率

模块的动画与计时按帧推进，帧率直接决定游戏快慢。原机运行这些模块约
10–15 fps。工具栏最右的按钮可直接输入任意帧率（1–240），选择会保存；
状态栏显示实测值。

## 说明

本仓库只包含代码。游戏数据不在此处，也不提供。
