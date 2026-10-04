"""nieche —— Rust 模拟核心的 Python 绑定（ctypes）。

**三端外壳用的就是这一个模块。** 它不依赖 Python 参照实现（emu-core-py），
那份只在开发和验证时用得上。

    NiecheSession(path, audio=True)
    .boot() / .stop() / .step() -> bytes
    .set_keys(mask) / .set_touch(x, y, state) / .soft_key(side)
    .take_events() -> [dict] / .take_events_json() -> str
    .size / .name / .screens / .nonblank / .frame_no / .alive

输入的语义（边沿、锁存、触摸排队、长按连发、软键两段式）**在 Rust 核心里**，
外壳不要重复实现——两份实现迟早会各错一遍。

数据根（存档、模块的虚拟文件系统）按这个顺序定：
环境变量 NIECHE_HOME，否则 ~/.nieche-emu。
"""
import ctypes
import json
import os
import sys

#: 本层要求的核心 ABI 版本。对不上就不要往下走：
#: 结构变了还硬调，症状会是随机崩溃而不是一句清楚的报错。
ABI = 2

_LIBNAMES = {
    "darwin": ["libnieche.dylib"],
    "win32": ["nieche.dll", "libnieche.dll"],
}


def _candidates():
    """按优先级给出可能的库位置。"""
    env = os.environ.get("NIECHE_LIB")
    if env:
        yield env
    names = _LIBNAMES.get(sys.platform, ["libnieche.so"])
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    dirs = [
        # PyInstaller 冻结后，--add-binary 进来的东西落在 _MEIPASS，
        # 那既不是 __file__ 的目录也不是它的上级。不加这一条，
        # 打出来的 exe 永远找不到 DLL，然后无声回落到 Python 核心。
        getattr(sys, "_MEIPASS", ""),
        here,
        root,
        os.path.join(root, "lib"),
        os.path.join(root, "rust", "target", "release"),
        os.path.join(os.environ.get("CARGO_TARGET_DIR", ""), "release"),
        os.path.join(os.path.expanduser("~"), ".cache", "nieche-rust", "release"),
    ]
    for d in dirs:
        if not d:
            continue
        for n in names:
            yield os.path.join(d, n)


class CoreUnavailable(Exception):
    """找不到核心库，或版本对不上。调用方应当回落到 Python 实现。"""


_lib = None


def load():
    """加载并绑定核心库。**失败要抛，不要静默回落**——
    静默回落的话，用户只会看到"怎么突然变慢了"而不知道发生了什么。"""
    global _lib
    if _lib is not None:
        return _lib
    last = None
    for p in _candidates():
        if not os.path.exists(p):
            continue
        try:
            lib = ctypes.CDLL(p)
        except OSError as e:
            last = e
            continue
        _bind(lib)
        v = lib.nieche_abi_version()
        if v != ABI:
            raise CoreUnavailable(f"{p} 的 ABI 是 {v}，本层要求 {ABI}")
        _lib = lib
        return lib
    raise CoreUnavailable(f"找不到核心库（最后一次错误：{last}）")


def selftest() -> bool:
    """原生层自检：让核心真跑几条 ARM 指令。

    **只加载动态库是不够的。** Windows 上冻结出来的 exe 开着 Control Flow
    Guard，unicorn 的 JIT 一跳到运行时生成的代码就会被 __fastfail 打死整个
    进程，没有异常也没有日志——必须真的跑到 JIT 才测得出来。
    """
    return load().nieche_selftest() == 1


def _bind(lib):
    c = ctypes
    p, u8p, sz = c.c_void_p, c.POINTER(c.c_ubyte), c.c_size_t
    sig = [
        ("nieche_abi_version", [], c.c_uint32),
        ("nieche_selftest", [], c.c_int32),
        ("nieche_open", [c.c_char_p], p),
        ("nieche_close", [p], None),
        ("nieche_boot", [p], c.c_int32),
        ("nieche_stop", [p], None),
        ("nieche_size", [p, c.POINTER(c.c_uint32), c.POINTER(c.c_uint32)], None),
        ("nieche_step", [p, u8p, sz], sz),
        ("nieche_frame_no", [p], c.c_uint64),
        ("nieche_set_keys", [p, c.c_uint32], None),
        ("nieche_set_touch", [p, c.c_int32, c.c_int32, c.c_int32], None),
        ("nieche_soft_key", [p, c.c_int32], None),
        ("nieche_nonblank", [p], c.c_uint32),
        ("nieche_screens", [p], c.c_uint32),
        ("nieche_name", [p, u8p, sz], sz),
        ("nieche_take_events", [p, u8p, sz], sz),
        ("nieche_take_logs", [p, u8p, sz], sz),
    ]
    for name, argtypes, restype in sig:
        f = getattr(lib, name)
        f.argtypes = argtypes
        f.restype = restype


#: set_touch 的字符串状态 -> C ABI 的整数
_TOUCH = {"down": 0, "up": 1, "move": 2}


class NiecheSession:
    """一局游戏，跑在 Rust 核心里。"""

    def __init__(self, path, audio=True):
        self.lib = load()
        # audio 参数保留是为了和 emu.host.Session 同签名；
        # 核心本来就不发声，声音是外壳按 take_events() 里的事件放的。
        self._audio = audio
        self.h = self.lib.nieche_open(os.fspath(path).encode("utf-8"))
        if not self.h:
            raise RuntimeError(f"核心打不开模块：{path}")
        self.alive = True
        self._buf = None

    # ------------------------------------------------------------ 生命周期
    def boot(self):
        if self.lib.nieche_boot(self.h) != 1:
            raise RuntimeError("引导失败")
        return self

    def stop(self):
        """**必须调** —— 游戏的存档多半是在模块的 AppStop 里落盘的。"""
        if not self.alive:
            return
        self.alive = False
        self.lib.nieche_stop(self.h)

    def close(self):
        if self.h:
            self.stop()
            self.lib.nieche_close(self.h)
            self.h = None

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass

    # ------------------------------------------------------------ 输入
    def set_keys(self, mask):
        self.lib.nieche_set_keys(self.h, ctypes.c_uint32(int(mask)))

    def set_touch(self, x, y, state):
        self.lib.nieche_set_touch(self.h, int(x), int(y), _TOUCH.get(state, 0))

    def soft_key(self, side, pressed=True):
        if pressed:
            self.lib.nieche_soft_key(self.h, 1 if side == "right" else 0)

    # ------------------------------------------------------------ 推进
    def step(self):
        """跑一帧，返回小端 RGB565 原始帧缓冲。"""
        # 缓冲按最大屏幕尺寸留一次就够——**尺寸可能在开头几帧变小**，
        # 每帧重新分配只是白白多几十次 malloc。
        if self._buf is None:
            self._buf = (ctypes.c_ubyte * (320 * 480 * 2))()
        n = self.lib.nieche_step(self.h, self._buf, len(self._buf))
        return bytes(memoryview(self._buf)[:n])

    def _take(self, fn):
        """「先探长度再取」：容量不够那次不会消耗数据，所以可以放心问两遍。"""
        n = fn(self.h, None, 0)
        if not n:
            return ""
        buf = (ctypes.c_ubyte * n)()
        got = fn(self.h, buf, n)
        return bytes(memoryview(buf)[:got]).decode("utf-8", "replace")

    def take_events(self):
        s = self._take(self.lib.nieche_take_events)
        out = []
        for line in s.split("\n"):
            if not line.strip():
                continue
            try:
                out.append(json.loads(line))
            except ValueError:
                out.append({"kind": "log", "text": line})
        for e in out:
            if e.get("kind") == "exit":
                self.alive = False
        return out

    def take_events_json(self):
        return json.dumps(self.take_events(), ensure_ascii=False)

    def take_logs(self):
        return self._take(self.lib.nieche_take_logs)

    # ------------------------------------------------------------ 查询
    @property
    def size(self):
        w, h = ctypes.c_uint32(), ctypes.c_uint32()
        self.lib.nieche_size(self.h, ctypes.byref(w), ctypes.byref(h))
        return w.value, h.value

    @property
    def name(self):
        return self._take(self.lib.nieche_name)

    @property
    def screens(self):
        return self.lib.nieche_screens(self.h)

    @property
    def nonblank(self):
        return self.lib.nieche_nonblank(self.h)

    @property
    def frame_no(self):
        return self.lib.nieche_frame_no(self.h)


def open_session(path, audio=True):
    """要一局游戏。**没有回落**——发布产物只跑 Rust 核心。

    以前这里会在核心加载不上时悄悄换成 Python 实现。那条路已经去掉了：
    回落的代价是慢十几倍，而用户只会觉得"这机器怎么这么卡"，
    根本不知道跑的根本不是同一个东西。现在加载不上就直接报错。

    返回 (会话, "rust")，第二个值保留是为了外壳照旧显示核心来源。
    """
    return NiecheSession(path, audio=audio), "rust"


def home() -> str:
    """数据根：存档和模块的虚拟文件系统都落在这儿。

    外壳只需要这一个路径函数，所以直接放这里，
    免得为了它去依赖 Python 参照实现那整个包。
    """
    root = os.environ.get("NIECHE_HOME") or os.path.expanduser("~/.nieche-emu")
    os.makedirs(root, exist_ok=True)
    return root
