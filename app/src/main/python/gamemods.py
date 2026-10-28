
HEAP = 0x40000000
HEAP_SCAN = 0x110000
SB = 0x4006a140
TAB_PTR = SB + 0x245c
REC = 0x58
UNIT = 0x54
ATTRS = (0x38, 0x3a, 0x3c, 0x3e)
NAMES_OFF = 0x0a
SITE_SIG = bytes.fromhex("78412901d103231956c1")
NOP2 = bytes.fromhex("46c046c0")

_lock_on = False
_patched = {}
_tick = 0

def reset():

    global _patched, _tick
    _patched = {}
    _tick = 0

def _is_bw(s):
    try:
        return s.name == "BW"
    except Exception:
        return False

def _heap(s):
    return s.mem_read(HEAP, HEAP_SCAN)

def _name(r):
    return r[NAMES_OFF:NAMES_OFF + 6].split(b"\0")[0].decode("gbk", "replace")

def _tables(heap):

    out = []
    key = "刘备".encode("gbk")
    start = 0
    while True:
        j = heap.find(key, start)
        if j < 0:
            break
        start = j + 1
        i = j - NAMES_OFF
        if i < 0 or i + 20 * REC > len(heap):
            continue
        if all(heap[i + k * REC + 4] == k + 1 and heap[i + k * REC + 5] == 1 for k in range(20)):
            out.append(HEAP + i)
    return out

def _current_table(s, tables):
    p = int.from_bytes(s.mem_read(TAB_PTR, 4), "big")
    return p if p in tables else (tables[-1] if tables else 0)

def _units(heap, rec, tables):

    key = rec[NAMES_OFF:NAMES_OFF + 6].split(b"\0")[0]
    out = []
    start = 0
    while True:
        j = heap.find(key, start)
        if j < 0:
            break
        start = j + 1
        a = j - NAMES_OFF
        if a < 0 or a + UNIT > len(heap):
            continue
        addr = HEAP + a
        if any(t <= addr < t + 20 * REC for t in tables):
            continue
        r = heap[a:a + UNIT]
        if r[4] != rec[4] or r[5] != 1:
            continue
        if not all(15 <= r[o + 1] <= 40 for o in ATTRS):
            continue
        out.append(addr)
    return out

def roster(s):

    if not _is_bw(s):
        return None
    heap = _heap(s)
    tables = _tables(heap)
    cur = _current_table(s, tables)
    if not cur:
        return []
    out = []
    for i in range(20):
        r = heap[cur - HEAP + i * REC:cur - HEAP + (i + 1) * REC]
        out.append((i, _name(r), r[0x24], bool(r[2])))
    return out

def _apply_level(r, lv):

    t = min(lv, 50)
    for o in ATTRS:
        cap = r[o + 1]
        target = min(cap, -(-cap * t // 50))
        r[o] = max(r[o], target)
    ag = r[0x3e]
    r[0x45], r[0x46], r[0x48] = r[0x38], r[0x3c], ag
    r[0x47], r[0x49] = ag * 3 // 2, ag * 2 // 3
    target = 9999 * t // 50
    for mx, cur in ((0x2e, 0x30), (0x32, 0x34)):
        m = max(int.from_bytes(r[mx:mx + 2], "big", signed=True), target)
        r[mx:mx + 2] = m.to_bytes(2, "big", signed=True)
        r[cur:cur + 2] = m.to_bytes(2, "big", signed=True)
    r[0x24] = lv
    r[0x23] = 0

def set_level(s, idx, lv):
    if not _is_bw(s):
        return "当前游戏不是三国志·赤壁传"
    lv = max(1, min(99, int(lv)))
    idx = int(idx)
    heap = _heap(s)
    tables = _tables(heap)
    if not tables:
        return "没找到武将表（游戏还没进到主菜单之后？）"
    cur = _current_table(s, tables)
    template = min(tables)
    base = bytearray(heap[cur - HEAP + idx * REC:cur - HEAP + (idx + 1) * REC])
    before = (base[0x24], [base[o] for o in ATTRS], int.from_bytes(base[0x2e:0x30], "big", signed=True))
    rows = [t + idx * REC for t in tables if t != template or t == cur]
    targets = [(a, REC) for a in rows] + [(a, UNIT) for a in _units(heap, base, tables)]
    for a, n in targets:
        r = bytearray(s.mem_read(a, n))
        _apply_level(r, lv)
        s.mem_write(a, bytes(r))
    after = bytearray(base)
    _apply_level(after, lv)
    hp = int.from_bytes(after[0x2e:0x30], "big", signed=True)
    a4 = [after[o] for o in ATTRS]
    return (f"{_name(base)}：等级 {before[0]} → {lv}，"
            f"武力/智力/统帅/敏捷 {'/'.join(map(str, before[1]))} → {'/'.join(map(str, a4))}，"
            f"生命 {before[2]} → {hp}")

def _find_sites(heap):
    out = []
    start = 0
    while True:
        j = heap.find(SITE_SIG, start)
        if j < 0:
            break
        start = j + 1
        bl = j + len(SITE_SIG)
        if heap[bl] & 0xf8 == 0xf0:
            out.append((HEAP + bl, heap[bl:bl + 4]))
    return out

def _apply_lock(s):
    global _patched
    if not _patched:
        sites = _find_sites(_heap(s))
        if len(sites) != 2:
            return f"还没找到减耐久的代码（找到 {len(sites)} 处），游戏载入后会自动锁定"
        _patched = dict(sites)
    for a, orig in _patched.items():
        if _lock_on:
            if s.mem_read(a, 4) != NOP2:
                s.mem_write(a, NOP2)
        else:
            if s.mem_read(a, 4) == NOP2:
                s.mem_write(a, orig)
    return None

def set_lock(s, on):
    global _lock_on
    _lock_on = bool(on)
    if not s or not _is_bw(s):
        return "耐久锁定已" + ("打开" if _lock_on else "关闭") + "（进入赤壁传后生效）"
    err = _apply_lock(s)
    if err:
        return err
    return "耐久锁定已" + ("打开：武器不再磨损" if _lock_on else "关闭")

def is_locked():
    return _lock_on

def tick(s):

    global _tick
    if not _lock_on:
        return
    _tick += 1
    if _tick % 30:
        return
    if _is_bw(s):
        try:
            _apply_lock(s)
        except Exception:
            pass

ISLAND_POINTS = 0x20000000 + 0x57e4
POINTS_MAX = 999999

def _is_island(s):
    try:
        return s.name == "IsLet"
    except Exception:
        return False

def kind(s):

    if not s:
        return ""
    if _is_bw(s):
        return "BW"
    if _is_island(s):
        return "IsLet"
    return ""

def island_points(s):

    if not s or not _is_island(s):
        return -1
    return int.from_bytes(s.mem_read(ISLAND_POINTS, 4), "little")

def island_set_points(s, v):
    if not s or not _is_island(s):
        return "当前游戏不是孤岛"
    v = max(0, min(POINTS_MAX, int(v)))
    before = island_points(s)
    if not s.mem_write(ISLAND_POINTS, v.to_bytes(4, "little")):
        return "写入失败"
    return f"生存点 {before} → {v}（打开商城可以看到）"
