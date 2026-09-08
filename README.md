# emu-android

尼彩 CBE 模拟器的安卓外壳。Chaquopy 把 CPython 嵌进 app 进程，
Kotlin 直接调用 [emu-core](https://github.com/nieche-cbe-emu/emu-core)，
没有子进程也没有管道。

核心优先用 `libnieche.so`（emu-core 的 Rust 实现，经 ctypes 调用），
装不上才回落到 Python 实现。**界面上会显示当前用的是哪个核心。**

## 构建

```
./sync_python.sh          # 从同级的 emu-core 同步核心，并给 unicorn 打补丁
./gradlew assembleDebug
```

需要 JDK 17、Android SDK（platform 34、build-tools 34）。
Gradle 锁在 8.7——Gradle 9 移除了插件仍在用的 `org.gradle.util.VersionNumber`。

`app/src/main/jniLibs/` 里是交叉编译好的 `libunicorn.so` 与 `libcapstone.so`
（arm64-v8a 与 armeabi-v7a）。`libnieche.so` 不在仓库里，用 emu-core 的
`tools/build-android.sh` 现编——那个脚本里记了交叉编译的四个坑，
其中「缺 `__clear_cache`」只有装到真机上 dlopen 时才会暴露。

## 说明

本仓库只有代码。游戏数据不在这里，也不会提供。
