
import os
import sys

_session = None

def init(native_lib_dir, data_dir):

    os.environ["LIBUNICORN_PATH"] = native_lib_dir
    os.environ["LIBCAPSTONE_PATH"] = native_lib_dir

    os.environ["NIECHE_LIB"] = os.path.join(native_lib_dir, "libnieche.so")

    os.environ["NIECHE_HOME"] = data_dir
    return check()

def check():

    try:
        from emu.native import load
        return f"Rust 核心已加载（ABI {load().nieche_abi_version()}）"
    except Exception as e:
        native_err = f"{type(e).__name__}: {e}"
    try:
        import unicorn
        import capstone
        return (f"回落到 Python 核心（Rust 核心不可用：{native_err}）；"
                f"unicorn {getattr(unicorn, '__version__', '?')} / "
                f"capstone {getattr(capstone, '__version__', '?')} 已加载")
    except Exception as e:
        return f"两个核心都加载失败: Rust={native_err}  Python={type(e).__name__}: {e}"

core = "?"

def start(path, audio=False):

    global _session, core
    stop()
    from emu.native import open_session
    sess, core = open_session(path, audio=audio)
    _session = sess.boot()
    w, h = _session.size
    return f"{w},{h}"

def which_core():

    return core

def step():

    return _session.step() if _session else b""

def set_keys(mask):
    if _session:
        _session.set_keys(int(mask))

def set_touch(x, y, state):
    if _session:
        _session.set_touch(int(x), int(y), state)

def events():
    return _session.take_events_json() if _session else "[]"

def selftest(path, frames=40):

    try:
        import time
        start(path, audio=False)
        n = 0
        for _ in range(frames):
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
