
import os
import sys

_session = None

def init(native_lib_dir, data_dir):

    os.environ["NIECHE_LIB"] = os.path.join(native_lib_dir, "libnieche.so")

    os.environ["NIECHE_HOME"] = data_dir
    return check()

def check():

    try:
        import nieche
        if not nieche.selftest():
            return "核心已加载但自检没过——原生层跑不动"
        return f"Rust 核心已加载（ABI {nieche.load().nieche_abi_version()}）"
    except Exception as e:
        return f"核心加载失败: {type(e).__name__}: {e}"

def start(path, audio=False):

    global _session
    stop()
    from nieche import NiecheSession
    _session = NiecheSession(path, audio=audio).boot()
    w, h = _session.size
    return f"{w},{h}"

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
