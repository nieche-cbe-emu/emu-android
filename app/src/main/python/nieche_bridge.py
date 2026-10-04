"""Kotlin 和模拟核心之间的那一层。

安卓上 Chaquopy 把 CPython 嵌在 **app 自己的进程里**，所以这里没有子进程、
没有管道，Kotlin 直接调下面这几个函数。

核心只有一个：libnieche.so（Rust，ctypes 直接接）。
**没有回落。** 以前装不上会悄悄换成 Python 实现，慢十几倍，
而用户只会觉得"这机器怎么这么卡"，根本不知道跑的不是同一个东西。
现在装不上就直接把原因报到界面上。

启动顺序有一处不能颠倒：**必须先设好两个 *_PATH 环境变量再 import**。
unicorn / capstone 的 Python 绑定都是 ctypes 的，它们在 import 期间就去找
.so；安卓上 .so 在 apk 解出来的 nativeLibraryDir 里，不告诉它就找不到。
"""
import os
import sys

_session = None


def init(native_lib_dir, data_dir):
    """Kotlin 启动时调一次。native_lib_dir 是 applicationInfo.nativeLibraryDir。"""
    # apk 里打包的 .so 都解到 nativeLibraryDir
    os.environ["NIECHE_LIB"] = os.path.join(native_lib_dir, "libnieche.so")
    # 存档、模块的虚拟文件系统都落在 app 私有目录下
    os.environ["NIECHE_HOME"] = data_dir
    return check()


def check():
    """自检：核心能不能加载。装不上的话早点报出来，
    别等进了游戏才崩在一个看不懂的地方。"""
    try:
        import nieche
        if not nieche.selftest():
            return "核心已加载但自检没过——原生层跑不动"
        return f"Rust 核心已加载（ABI {nieche.load().nieche_abi_version()}）"
    except Exception as e:
        return f"核心加载失败: {type(e).__name__}: {e}"


def start(path, audio=False):
    """加载并启动一个 .cbe。返回 "宽,高"。"""
    global _session
    stop()
    from nieche import NiecheSession
    _session = NiecheSession(path, audio=audio).boot()
    w, h = _session.size
    return f"{w},{h}"


def step():
    """跑一帧，返回 RGB565 原始帧缓冲。Chaquopy 会把 bytes 转成 Kotlin 的 ByteArray。"""
    return _session.step() if _session else b""


def set_keys(mask):
    if _session:
        _session.set_keys(int(mask))


def set_touch(x, y, state):
    if _session:
        _session.set_touch(int(x), int(y), state)


def soft_key(side):
    """软键是两段式的（先戳屏幕角落，几帧后画面没动才补按键位）。
    语义在核心里，外壳只报是哪一边。"""
    if _session:
        _session.soft_key(side)


def events():
    return _session.take_events_json() if _session else "[]"


def selftest(path, frames=40):
    """不经界面直接跑一段，把结果打进 logcat。

    安卓上排查问题只有 logcat 一条通道，而"界面黑屏"和"模拟核心没跑起来"
    在截图上长得一模一样。这个函数把两者分开：它只管核心。
    """
    try:
        import time
        start(path, audio=False)
        n = 0
        for _ in range(frames):          # 前若干帧在加载资源，不计入帧率
            step()
        t0 = time.time()
        for _ in range(frames):
            step()
            n = _session.nonblank
        fps = frames / max(1e-6, time.time() - t0)
        w, h = _session.size
        from emu import font as _f
        ok = _f.load() is not None
        print(f"[selftest] {path} {w}x{h}，非黑像素 {n}/{w*h}，{fps:.1f} fps，"
              f"字库{'已加载' if ok else '缺失(文字会变方块)'}")
        return f"ok {n} {fps:.1f}fps"
    except Exception as e:
        import traceback
        traceback.print_exc()
        print(f"[selftest] 失败: {type(e).__name__}: {e}")
        return f"fail {e}"
    finally:
        stop()


def stop():
    global _session
    if _session:
        _session.stop()
        _session = None
