# emu-android

尼彩 CBE 模拟器的安卓外壳。Chaquopy 把 CPython 嵌进 app 进程，
Kotlin 直接调用 [emu-core](https://github.com/nieche-cbe-emu/emu-core)，
没有子进程也没有管道。

## 构建

```
./sync_python.sh          # 从同级的 emu-core 同步核心，并给 unicorn 打补丁
./gradlew assembleDebug
```

需要 JDK 17、Android SDK（platform 34、build-tools 34）。
Gradle 锁在 8.7——Gradle 9 移除了插件仍在用的 `org.gradle.util.VersionNumber`。

`app/src/main/jniLibs/` 里是给安卓交叉编译好的 `libunicorn.so` 与
`libcapstone.so`（arm64-v8a 与 armeabi-v7a）。重新编译用 NDK：

```
cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
      -DBUILD_SHARED_LIBS=ON -DUNICORN_ARCH=arm
```

## 两个坑

`useLegacyPackaging` 必须为 true：设 false 时 .so 留在 apk 里由 linker 直接映射，
磁盘上没有这个文件，而 capstone 的加载器是先 `os.path.exists` 再 `CDLL`。

Chaquopy 的 `resources.files()` 返回 AssetPath，unicorn 的加载器把它直接塞进
`pathlib.Path()` 会在 import 阶段抛 TypeError。`sync_python.sh` 会自动打这个补丁。

## 操作

上面是画面、下面是功能机键盘。单指是游戏触摸，双指缩放平移。
`--es selftest <路径>` 可以不经界面直接跑一段并把结果打进 logcat。
