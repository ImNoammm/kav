"""Compile the Israeli MOT GTFS feed into the Kav bundle the app ships.

Emits a compact varint binary that the app decodes into typed arrays: stops,
lines, and the trips of one ordinary week, each tagged with the days it runs.
Defaults to the Tel Aviv metro bbox; KAV_BBOX=national covers the country,
which is what android/app/src/main/assets/il.kav is.

Read-only over already-downloaded files; makes no network calls.
"""
import csv, os, sys, math, re, datetime, struct, collections

import zipfile, io
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ZIP  = os.environ.get("KAV_GTFS", os.path.join(HERE, ".cache", "israel-gtfs.zip"))
OUT  = os.environ.get("KAV_OUT",  os.path.join(HERE, "android", "app", "src", "main", "assets"))
os.makedirs(OUT, exist_ok=True)
_zip = zipfile.ZipFile(ZIP)

# KAV_WEEK picks the Sunday, by default the coming one. Check the per-day counts
# printed below before shipping a holiday week.
_SUN    = datetime.date.today() + datetime.timedelta((6 - datetime.date.today().weekday()) % 7)
WEEK    = datetime.date.fromisoformat(os.environ.get("KAV_WEEK", _SUN.isoformat()))
assert WEEK.weekday() == 6, "KAV_WEEK must be a Sunday"
DAYS    = [WEEK + datetime.timedelta(d) for d in range(7)]
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
# services running in that week, as a mask of its days (bit 0 = Sunday)
active = {}
for r in csv.DictReader(openf("calendar.txt")):
    s, e = r["start_date"], r["end_date"]
    sd = datetime.date(int(s[:4]), int(s[4:6]), int(s[6:8]))
    ed = datetime.date(int(e[:4]), int(e[4:6]), int(e[6:8]))
    mask = 0
    for i, day in enumerate(DAYS):
        if sd <= day <= ed and r[DAYCOLS[i]] == "1":
            mask |= 1 << i
    if mask:
        active[r["service_id"]] = mask
print(f"services running in the week of {WEEK}: {len(active):,}")

# The Israeli feed keeps the city in stop_desc, not in stop_name, so a query
# like "הדרור 30 ראש העין" can never match on the name alone. Pull it out and
# index it: ~1,200 distinct cities cost 1-2 bytes per stop instead of ~20.
CITY_RE = re.compile(r"עיר:\s*(.*?)\s*(?:רציף:|קומה:|$)")
# A bus station's platforms are stops of their own, sharing the station's code; stop_desc numbers each one.
PLATFORM_RE = re.compile(r"רציף:\s*([^\s:]+)(?=\s|$)")
stop_idx, stops = {}, []
city_idx, cities = {}, []
stop_platform = {}
for r in csv.DictReader(openf("stops.txt")):
    lat, lon = float(r["stop_lat"]), float(r["stop_lon"])
    if not (BBOX[0] <= lat <= BBOX[1] and BBOX[2] <= lon <= BBOX[3]): continue
    m = CITY_RE.search(r.get("stop_desc") or "")
    city = m.group(1).strip() if m else ""
    if city not in city_idx:
        city_idx[city] = len(cities); cities.append(city)
    m = PLATFORM_RE.search(r.get("stop_desc") or "")
    if m:
        stop_platform[len(stops)] = m.group(1)
    stop_idx[r["stop_id"]] = len(stops)
    stops.append((r["stop_name"].strip(), lat, lon, int(r["stop_code"] or 0), city_idx[city]))
print(f"stops in bbox: {len(stops):,}   cities: {len(cities):,}   platforms: {len(stop_platform):,}")

# Operators, for the sections the lines browser draws under each mode chip. The
# feed's agency_id is dense and small; the bundle stores an index into this name
# table per route, which costs one varint each.
agency_idx, agencies = {}, []
for r in csv.DictReader(openf("agency.txt")):
    agency_idx[r["agency_id"]] = len(agencies)
    agencies.append(r["agency_name"].strip())
print(f"agencies: {len(agencies):,}")

# The feed gives the Carmelit and the Rakavlit the same type 5, though one is an
# underground funicular and the other hangs from a wire over the Technion. Moovit's
# own agency record separates them online; offline the split is made here, where the
# agency is still in hand: agency 20 is the Carmelit, and 7 is MVRouteType's
# funicular, which is what it is. The Rakavlit keeps 5, the cable car it actually is.
#
# Shuttles get the same treatment. The MOT feed has no shuttle type - Moovit's own
# shuttle category (the Israel Railways replacement runs, Nativ La'Asakim) is
# curated data that never reaches this feed at all - but the park-and-ride shuttle
# lines it does carry arrive as ordinary buses whose stops are named for what they
# are. 711 is the extended-GTFS "Shuttle Bus", so they leave here wearing it.
CARMELIT_AGENCY = "20"
SHUTTLE = "שאטל"

route_idx, routes = {}, []
for r in csv.DictReader(openf("routes.txt")):
    rtype = int(r["route_type"])
    if rtype == 5 and r["agency_id"] == CARMELIT_AGENCY:
        rtype = 7
    if rtype == 3 and (SHUTTLE in r["route_long_name"] or SHUTTLE in r["route_short_name"]):
        rtype = 711
    route_idx[r["route_id"]] = len(routes)
    routes.append((r["route_short_name"].strip(), r["route_long_name"].strip(), rtype,
                   agency_idx.get(r["agency_id"], -1)))

trip_route, trip_days = {}, {}
for r in csv.DictReader(openf("trips.txt")):
    if r["service_id"] in active:
        trip_route[r["trip_id"]] = route_idx.get(r["route_id"], 0)
        trip_days[r["trip_id"]] = active[r["service_id"]]
print(f"trips in the week (national): {len(trip_route):,}")

# stream stop_times, keep trips that touch the bbox
def hms(x):
    a = x.split(":"); return int(a[0])*3600 + int(a[1])*60 + int(a[2])

kept, cur, seq, rows = [], None, [], 0
def flush(tid, sq):
    if not sq or tid not in trip_route: return
    inside = [p for p in sq if p[2] is not None]
    if len(inside) < 2: return                # must be usable inside the region
    kept.append((trip_route[tid], trip_days[tid], inside))

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
n_conn = sum(len(t[2]) - 1 for t in kept)
n_st = sum(len(t[2]) for t in kept)
print(f"trips kept: {len(kept):,}   stop times: {n_st:,}   connections: {n_conn:,}")
for i, day in enumerate(DAYS):
    print(f"  {day:%a %d %b}: {sum(1 for t in kept if t[1] >> i & 1):,} trips")

# encode
# KAV5 adds the stop-time count up front and a day mask per trip; KAV6 the platforms at the end.
buf = bytearray(b"KAV6")
vint(buf, len(stops)); vint(buf, len(routes)); vint(buf, len(kept)); vint(buf, len(cities))
vint(buf, n_st)
vint(buf, len(agencies))
for a in agencies:
    vstr(buf, a)

for c in cities:
    vstr(buf, c)

plat = plon = 0
for name, la, lo, code, ci in stops:
    ila, ilo = int(round(la*1e5)), int(round(lo*1e5))
    vint(buf, ila - plat); vint(buf, ilo - plon); plat, plon = ila, ilo
    vint(buf, code); vint(buf, ci); vstr(buf, name)

for short, long, rtype, agency in routes:
    vstr(buf, short); vstr(buf, long); vint(buf, rtype); vint(buf, agency)

for ridx, days, sq in kept:
    vint(buf, ridx); vint(buf, days); vint(buf, len(sq)); vint(buf, sq[0][0])
    pt, ps = sq[0][0], 0
    for arr, dep, s in sq:
        vint(buf, arr - pt); vint(buf, dep - arr); vint(buf, s - ps)
        pt, ps = dep, s

vint(buf, len(stop_platform))
prev = 0
for s in sorted(stop_platform):
    vint(buf, s - prev); vstr(buf, stop_platform[s])
    prev = s

path = os.path.join(OUT, REGION + ".kav")
open(path, "wb").write(buf)
raw = len(buf)
import gzip
# Net.read takes either, so the gzip is there for whoever wants the smaller APK.
gz = gzip.compress(bytes(buf), 9)
open(path + ".gz", "wb").write(gz)

print(f"\nbundle: {raw/1e6:.2f} MB raw   {len(gz)/1e6:.2f} MB gzip -> {path}")
