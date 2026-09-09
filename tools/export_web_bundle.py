"""Compile the Israeli MOT GTFS feed into a browser-loadable Kav bundle.

Scope: Tel Aviv metro bbox, a single service day. Emits a compact varint
binary that the web client decodes into typed arrays and routes over with
CSA. This is the shipping format, not a demo format.

Read-only over already-downloaded files; makes no network calls.
"""
import csv, os, sys, math, re, datetime, struct, collections

import zipfile, io
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ZIP  = os.environ.get("KAV_GTFS", os.path.join(HERE, ".cache", "israel-gtfs.zip"))
OUT  = os.environ.get("KAV_OUT",  os.path.join(HERE, "webui", "data"))
os.makedirs(OUT, exist_ok=True)
_zip = zipfile.ZipFile(ZIP)

TODAY   = datetime.date(2026, 9, 7)
DAYCOLS = ["sunday","monday","tuesday","wednesday","thursday","friday","saturday"]
# lat_min, lat_max, lon_min, lon_max. Override with KAV_BBOX="lat0,lat1,lon0,lon1";
# KAV_BBOX=national covers the whole country.
_BB = os.environ.get("KAV_BBOX", "31.90,32.25,34.60,35.05")
BBOX = ((29.0, 33.5, 34.0, 36.0) if _BB == "national"
        else tuple(float(x) for x in _BB.split(",")))
REGION = os.environ.get("KAV_REGION", "tlv")
XFER_M  = 300                                # max footpath length, metres
WALK_MS = 1.2                                # walking speed m/s

def openf(n):
    return io.TextIOWrapper(_zip.open(n), encoding="utf-8-sig", newline="")

def vint(buf, n):                             # zigzag varint
    n = ((-n << 1) - 1) if n < 0 else (n << 1)   # zigzag: -1->1, 1->2
    while True:
        b = n & 0x7F; n >>= 7
        buf.append(b | 0x80) if n else buf.append(b)
        if not n: break

def vstr(buf, s):
    e = s.encode("utf-8"); vint(buf, len(e)); buf += e

# services running on the target day
active = set()
for r in csv.DictReader(openf("calendar.txt")):
    s, e = r["start_date"], r["end_date"]
    sd = datetime.date(int(s[:4]), int(s[4:6]), int(s[6:8]))
    ed = datetime.date(int(e[:4]), int(e[4:6]), int(e[6:8]))
    if sd <= TODAY <= ed and r[DAYCOLS[(TODAY.weekday()+1) % 7]] == "1":
        active.add(r["service_id"])
print(f"services active on {TODAY}: {len(active):,}")

# The Israeli feed keeps the city in stop_desc, not in stop_name, so a query
# like "הדרור 30 ראש העין" can never match on the name alone. Pull it out and
# index it: ~1,200 distinct cities cost 1-2 bytes per stop instead of ~20.
CITY_RE = re.compile(r"עיר:\s*(.*?)\s*(?:רציף:|קומה:|$)")
stop_idx, stops = {}, []
city_idx, cities = {}, []
for r in csv.DictReader(openf("stops.txt")):
    lat, lon = float(r["stop_lat"]), float(r["stop_lon"])
    if not (BBOX[0] <= lat <= BBOX[1] and BBOX[2] <= lon <= BBOX[3]): continue
    m = CITY_RE.search(r.get("stop_desc") or "")
    city = m.group(1).strip() if m else ""
    if city not in city_idx:
        city_idx[city] = len(cities); cities.append(city)
    stop_idx[r["stop_id"]] = len(stops)
    stops.append((r["stop_name"].strip(), lat, lon, int(r["stop_code"] or 0), city_idx[city]))
print(f"stops in bbox: {len(stops):,}   cities: {len(cities):,}")

route_idx, routes = {}, []
for r in csv.DictReader(openf("routes.txt")):
    route_idx[r["route_id"]] = len(routes)
    routes.append((r["route_short_name"].strip(), r["route_long_name"].strip(),
                   int(r["route_type"])))

trip_route = {}
for r in csv.DictReader(openf("trips.txt")):
    if r["service_id"] in active:
        trip_route[r["trip_id"]] = (route_idx.get(r["route_id"], 0), r.get("shape_id") or "")
print(f"trips today (national): {len(trip_route):,}")

# stream stop_times, keep trips that touch the bbox
def hms(x):
    a = x.split(":"); return int(a[0])*3600 + int(a[1])*60 + int(a[2])

kept, cur, seq, rows = [], None, [], 0
def flush(tid, sq):
    if not sq or tid not in trip_route: return
    inside = [p for p in sq if p[2] is not None]
    if len(inside) < 2: return                # must be usable inside the region
    ridx, shp = trip_route[tid]
    kept.append((ridx, inside, shp))

with openf("stop_times.txt") as f:
    f.readline()
    for line in f:
        p = line.rstrip("\n").split(",")
        if len(p) < 5: continue
        if p[0] != cur:
            flush(cur, seq); cur, seq = p[0], []
        if cur in trip_route:
            try: seq.append((hms(p[1]), hms(p[2]), stop_idx.get(p[3])))
            except Exception: pass
        rows += 1
        if rows % 5_000_000 == 0: print(f"  {rows:,} rows", flush=True)
flush(cur, seq)
n_conn = sum(len(t[1]) - 1 for t in kept)
print(f"trips kept: {len(kept):,}   connections: {n_conn:,}")

# footpaths via a spatial grid
CELL = XFER_M / 111_320.0
grid = collections.defaultdict(list)
for i, (_, la, lo, _, _) in enumerate(stops):
    grid[(int(la/CELL), int(lo/CELL))].append(i)

xfer = [[] for _ in stops]
for i, (_, la, lo, _, _) in enumerate(stops):
    gx, gy = int(la/CELL), int(lo/CELL)
    coslat = math.cos(math.radians(la))
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for j in grid.get((gx+dx, gy+dy), ()):
                if j <= i: continue
                _, la2, lo2, _, _ = stops[j]
                dm = math.hypot((la2-la)*111_320.0, (lo2-lo)*111_320.0*coslat)
                if dm <= XFER_M:
                    w = int(dm / WALK_MS)
                    xfer[i].append((j, w)); xfer[j].append((i, w))
print(f"footpaths: {sum(len(x) for x in xfer)//2:,} pairs (<= {XFER_M} m)")

# GTFS shapes are the paths vehicles actually drive, i.e. the roads. Deduped,
# simplified and tiered by how many trips use them, they render as a road
# network with real hierarchy: no basemap, no tiles, no API key.
RDP_M   = 12.0            # simplification tolerance, metres
shape_uses, shape_type = collections.Counter(), {}
route_type_of = {rid: routes[i][2] for rid, i in route_idx.items()}
for r in csv.DictReader(openf("trips.txt")):
    if r["service_id"] not in active: continue
    sid = r.get("shape_id") or ""
    if not sid: continue
    shape_uses[sid] += 1
    shape_type[sid] = route_type_of.get(r["route_id"], 3)
print(f"shapes referenced today: {len(shape_uses):,}")

raw_shapes = collections.defaultdict(list)
with openf("shapes.txt") as f:
    hdr = [h.strip() for h in f.readline().split(",")]   # feed uses CRLF
    ix = {n: i for i, n in enumerate(hdr)}
    la_i, lo_i, sq_i = ix["shape_pt_lat"], ix["shape_pt_lon"], ix["shape_pt_sequence"]
    for line in f:
        p = line.rstrip("\r\n").split(",")
        if len(p) <= sq_i: continue
        sid = p[0]
        if sid not in shape_uses: continue
        try: raw_shapes[sid].append((int(p[sq_i]), float(p[la_i]), float(p[lo_i])))
        except Exception: pass
print(f"shapes loaded: {len(raw_shapes):,}")

def rdp(pts, tol_deg):
    """iterative Ramer-Douglas-Peucker (recursion blows up on 5k-point shapes)"""
    if len(pts) < 3: return pts
    keep = [False]*len(pts); keep[0] = keep[-1] = True
    stack = [(0, len(pts)-1)]
    while stack:
        a, b = stack.pop()
        if b <= a+1: continue
        ax, ay = pts[a][1], pts[a][0]; bx, by = pts[b][1], pts[b][0]
        dx, dy = bx-ax, by-ay
        den = dx*dx + dy*dy
        worst, wi = -1.0, -1
        for i in range(a+1, b):
            px_, py_ = pts[i][1], pts[i][0]
            if den == 0: d = math.hypot(px_-ax, py_-ay)
            else:
                t = max(0.0, min(1.0, ((px_-ax)*dx + (py_-ay)*dy)/den))
                d = math.hypot(px_-(ax+t*dx), py_-(ay+t*dy))
            if d > worst: worst, wi = d, i
        if worst > tol_deg:
            keep[wi] = True; stack.append((a, wi)); stack.append((wi, b))
    return [p for p, k in zip(pts, keep) if k]

COSLAT = math.cos(math.radians((BBOX[0]+BBOX[1])/2))
tol_lat = RDP_M / 111_320.0
lines, dropped_pts, kept_pts = [], 0, 0
shape_runs = collections.defaultdict(list)      # shape_id -> indices into `lines`
for sid, pts in raw_shapes.items():
    pts.sort()
    seq = [(la, lo) for _, la, lo in pts]
    # split wherever the path leaves the region, so we never draw a false shortcut
    run = []
    runs = []
    for la, lo in seq:
        if BBOX[0] <= la <= BBOX[1] and BBOX[2] <= lo <= BBOX[3]: run.append((la, lo))
        else:
            if len(run) >= 2: runs.append(run)
            run = []
    if len(run) >= 2: runs.append(run)
    if not runs: continue
    rt = shape_type.get(sid, 3)
    for run in runs:
        dropped_pts += len(run)
        simp = rdp([(la, lo*COSLAT) for la, lo in run], tol_lat)
        simp = [(la, lo/COSLAT) for la, lo in simp]
        if len(simp) < 2: continue
        kept_pts += len(simp)
        shape_runs[sid].append(len(lines))
        lines.append((rt, shape_uses[sid], simp))
print(f"road lines: {len(lines):,}   points {dropped_pts:,} -> {kept_pts:,} after simplify")

# No corridor merging: collapsing near-identical geometry by hash could pin a
# trip to a road it does not actually drive. It only merged 6 lines nationally.
merged = [[rt, uses, pts] for rt, uses, pts in lines]
print(f"road lines kept (no corridor merge): {len(merged):,}")

# longest in-bbox run per shape
shape_line = {}
for sid, idxs in shape_runs.items():
    shape_line[sid] = max(idxs, key=lambda i: len(lines[i][2]))

# tier by usage -> road hierarchy; rail/tram always top tier
counts = sorted(u for _, u, _ in merged)
def pct(p): return counts[min(len(counts)-1, int(len(counts)*p))] if counts else 0
t1, t2, t3 = pct(0.55), pct(0.85), pct(0.96)
def tier_of(rt, uses):
    if rt in (0, 1, 2): return 3
    if uses >= t3: return 3
    if uses >= t2: return 2
    if uses >= t1: return 1
    return 0
tiered = [(tier_of(rt, u), pts, i) for i, (rt, u, pts) in enumerate(merged)]
tiered.sort(key=lambda x: (x[0], x[1][0][0], x[1][0][1]))    # tier, then spatial
remap = {old: new for new, (_, _, old) in enumerate(tiered)}
merged = [(t, pts) for t, pts, _ in tiered]
shape_line = {sid: remap[i] for sid, i in shape_line.items()}
print("tier sizes:", collections.Counter(t for t, _ in merged))

# encode
buf = bytearray(b"KAV3")
vint(buf, len(stops)); vint(buf, len(routes)); vint(buf, len(kept)); vint(buf, len(cities))

for c in cities:
    vstr(buf, c)

plat = plon = 0
for name, la, lo, code, ci in stops:
    ila, ilo = int(round(la*1e5)), int(round(lo*1e5))
    vint(buf, ila - plat); vint(buf, ilo - plon); plat, plon = ila, ilo
    vint(buf, code); vint(buf, ci); vstr(buf, name)

for short, long, rtype in routes:
    vstr(buf, short); vstr(buf, long); vint(buf, rtype)

for ridx, sq, _shp in kept:
    vint(buf, ridx); vint(buf, len(sq)); vint(buf, sq[0][0])
    pt, ps = sq[0][0], 0
    for arr, dep, s in sq:
        vint(buf, arr - pt); vint(buf, dep - arr); vint(buf, s - ps)
        pt, ps = dep, s

for i in range(len(stops)):
    vint(buf, len(xfer[i]))
    for j, w in xfer[i]:
        vint(buf, j - i); vint(buf, w)

# trip -> road line, so a ride leg follows the road rather than a straight hop
vint(buf, len(kept))
for _ridx, _sq, shp in kept:
    vint(buf, shape_line.get(shp, -1) + 1)     # 0 = none

vint(buf, len(merged))
plat = plon = 0
for tier, pts in merged:
    vint(buf, tier); vint(buf, len(pts))
    for la, lo in pts:
        ila, ilo = int(round(la*1e5)), int(round(lo*1e5))
        vint(buf, ila - plat); vint(buf, ilo - plon)
        plat, plon = ila, ilo

path = os.path.join(OUT, REGION + ".kav")
open(path, "wb").write(buf)
raw = len(buf)
import gzip, base64
gz = gzip.compress(bytes(buf), 9)
open(path + ".gz", "wb").write(gz)

# Some hosts serve the page with an opaque origin, where fetch() is refused
# but <script src> is not CORS-checked. Ship the same gzip as a script too.
b64 = base64.b64encode(gz).decode("ascii")
open(path + ".js", "w", encoding="ascii").write('self.KAV_GZ_B64="%s";' % b64)

print(f"\nbundle: {raw/1e6:.2f} MB raw   {len(gz)/1e6:.2f} MB gzip"
      f"   {len(b64)/1e6:.2f} MB as script -> {path}")
