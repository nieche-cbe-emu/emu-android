# emu-android

尼彩 CBE 模拟器的安卓外壳。Chaquopy 把 CPython 嵌进 app 进程，
Kotlin 直接调 Python，没有子进程也没有管道——但 Python 这一层很薄，
只是 [emu-core-rs](https://github.com/nieche-cbe-emu/emu-core-rs) 的
ctypes 绑定（`nieche.py`），真正干活的是 `libnieche.so`。

**没有回落。** 核心装不上就把原因直接报到界面上。

## 构建

```
./sync_python.sh          # 从同级的 emu-core-rs 拷一个 nieche.py 进来
./gradlew assembleDebug
```

需要 JDK 17、Android SDK（platform 34、build-tools 34）。
Gradle 锁在 8.7——Gradle 9 移除了插件仍在用的 `org.gradle.util.VersionNumber`。

`app/src/main/jniLibs/` 需要放 `libnieche.so`（arm64-v8a 与 armeabi-v7a）。
它不在仓库里，用 [emu-tools](https://github.com/nieche-cbe-emu/emu-tools) 的
`tools/build-android.sh` 现编——那个脚本里记了交叉编译的四个坑，
其中「缺 `__clear_cache`」编译链接全过，**只有装到机器上 dlopen 时才会暴露**。

## 帧率

工具栏最右边那个按钮是**游戏速度**，不只是画面流畅度：模块的动画和计时
都是按帧推进的，跑多快游戏就多快。默认 20；真机上 MSW8533 跑这些游戏
大概只有 10–15 fps，而 Rust 核心在手机上轻松跑满，不压着会快得没法玩。

点它可以**直接输入任意帧率**（1–240），选择会记住。
状态栏实时显示**实测帧率**——游戏快慢看的就是这个数。

## 说明

本仓库只有代码。游戏数据不在这里，也不会提供。
