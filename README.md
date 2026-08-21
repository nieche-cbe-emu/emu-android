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

## 键位映射：尚未定论

模拟器目前把 **bit12 当左软键、bit13 当右软键**，挂断键不映射任何位。

依据来自孤岛的过场文本页：屏幕左下角画「加速」、右下角画「跳过」——软键标签的
标准位置——按 bit13 触发的正是「跳过」，按 bit12/确定 触发「加速」；进游戏后
底部左「菜单」右「商城」，bit12 打开的就是菜单。众神之战同样 bit12 开菜单。

**但这不足以当作结论，右软键的行为对不上它自己的标签：**

- 众神之战右下角画「任务」，按 bit13 弹的却是「是否退出游戏？」
- 孤岛游戏内右下角画「商城」，按 bit13 弹的却是「确定要回到标题界面？」

两个游戏都没有用右软键打开标签上写的那个功能。可能是这些标签本来就只吃触摸
（众神之战的「任务」，32 个位逐个长按 40 帧都打不开，游戏也从不调用
`Get_CurKeyDownState` 读原始键状态字），也可能是映射本身还不对。

挂断键不映射任何位，同样只是推断：没有任何模块轮询过一个"挂断位"，
真机上它由手机系统直接终止应用。

固件里 `CurKeyDownState` 这个全局是间接寻址的，literal pool 里搜不到引用，
所以还没能从固件侧读出手机键码到位的翻译表。这条线索还没走完。

键位在模拟器里可以自己改。**欢迎带着实机对照的结果开 issue。**
