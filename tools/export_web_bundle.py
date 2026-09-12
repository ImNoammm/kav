"""Compile the Israeli MOT GTFS feed into the Kav bundle the app ships.

Emits a compact varint binary that the app decodes into typed arrays: stops,
lines, trips and a per-stop departure index. Defaults to
the Tel Aviv metro bbox and one service day; KAV_BBOX=national covers the
country, which is what android/app/src/main/assets/il.kav is.

Read-only over already-downloaded files; makes no network calls.
"""
import csv, os, sys, math, re, datetime, struct, collections

import zipfile, io
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ZIP  = os.environ.get("KAV_GTFS", os.path.join(HERE, ".cache", "israel-gtfs.zip"))
OUT  = os.environ.get("KAV_OUT",  os.path.join(HERE, "android", "app", "src", "main", "assets"))
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
        trip_route[r["trip_id"]] = route_idx.get(r["route_id"], 0)
print(f"trips today (national): {len(trip_route):,}")

# stream stop_times, keep trips that touch the bbox
def hms(x):
    a = x.split(":"); return int(a[0])*3600 + int(a[1])*60 + int(a[2])

kept, cur, seq, rows = [], None, [], 0
def flush(tid, sq):
    if not sq or tid not in trip_route: return
    inside = [p for p in sq if p[2] is not None]
    if len(inside) < 2: return                # must be usable inside the region
    kept.append((trip_route[tid], inside))

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

for ridx, sq in kept:
    vint(buf, ridx); vint(buf, len(sq)); vint(buf, sq[0][0])
    pt, ps = sq[0][0], 0
    for arr, dep, s in sq:
        vint(buf, arr - pt); vint(buf, dep - arr); vint(buf, s - ps)
        pt, ps = dep, s

path = os.path.join(OUT, REGION + ".kav")
open(path, "wb").write(buf)
raw = len(buf)
import gzip
# Net.read takes either, so the gzip is there for whoever wants the smaller APK.
gz = gzip.compress(bytes(buf), 9)
open(path + ".gz", "wb").write(gz)

print(f"\nbundle: {raw/1e6:.2f} MB raw   {len(gz)/1e6:.2f} MB gzip -> {path}")
