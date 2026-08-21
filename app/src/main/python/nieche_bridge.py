
import os
import sys

_session = None

def init(native_lib_dir, data_dir):

    os.environ["LIBUNICORN_PATH"] = native_lib_dir
    os.environ["LIBCAPSTONE_PATH"] = native_lib_dir

    os.environ["NIECHE_HOME"] = data_dir
    return check()

def check():

    try:
        import unicorn
        import capstone
        return f"unicorn {unicorn.__version__ if hasattr(unicorn, '__version__') else '?'} / "               f"capstone {capstone.__version__ if hasattr(capstone, '__version__') else '?'} 已加载"
    except Exception as e:
        return f"原生库加载失败: {type(e).__name__}: {e}"

def start(path, audio=False):

    global _session
    stop()
    from emu.host import Session
    _session = Session(path, audio=audio).boot()
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

def soft_key(side, pressed=True):
    if _session:
        _session.soft_key(side, bool(pressed))

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
            n = _session.rt.fb.nonblank()
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
