"""Compile the Israeli MOT GTFS feed into the Kav bundle the app ships.

Emits a compact varint binary that the app decodes into typed arrays: stops,
lines, and the trips of one ordinary week, each tagged with the days it runs.
Defaults to the Tel Aviv metro bbox; KAV_BBOX=national covers the country,
which is what android/app/src/main/assets/il.kav is.

Read-only over already-downloaded files; makes no network calls.
"""
import csv, os, sys, re, datetime

import zipfile, io
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(HERE, "server", "otp"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # on the server it sits beside prepare.py (rebuild.sh)
from prepare import carmelit, day_masks, naim_feed, once, untangle  # the timetable fixes the server's planner makes too
ZIP  = os.environ.get("KAV_GTFS", os.path.join(HERE, ".cache", "israel-gtfs.zip"))
# Tel Aviv's weekend lines (Na'im BeSofash), in no ministry feed: the city's own GTFS
# (server/otp/prepare.py NAIM_URL) joins the ministry's when it's there, its stops the
# ministry's of the same code, its operator named as riders know it, the name the Lines
# tab uses for these lines (ui/Lines.kt NAIM_NAME).
NAIM = os.environ.get("KAV_NAIM", os.path.join(HERE, ".cache", "naim-gtfs.zip"))
NAIM_NAME = "נעים בסופ״ש"
OUT  = os.environ.get("KAV_OUT",  os.path.join(HERE, "android", "app", "src", "main", "assets"))
os.makedirs(OUT, exist_ok=True)
_zip = zipfile.ZipFile(ZIP)

# KAV_WEEK picks the Sunday, by default the coming one. Check the per-day counts
# printed below before shipping a holiday week. KAV_FROM (a date, or "today" in Israel) takes the seven days from
# then instead: what Kav's server builds each night, so a phone has today's own trips. Each day sets its
# weekday's bit (Sunday 0), as the app reads them, so seven days from any day give every weekday once.
_SUN    = datetime.date.today() + datetime.timedelta((6 - datetime.date.today().weekday()) % 7)
_FROM   = os.environ.get("KAV_FROM", "")
if _FROM:
    import zoneinfo
    START = (datetime.datetime.now(zoneinfo.ZoneInfo("Asia/Jerusalem")).date() if _FROM == "today"
             else datetime.date.fromisoformat(_FROM))
else:
    START = datetime.date.fromisoformat(os.environ.get("KAV_WEEK", _SUN.isoformat()))
    assert START.weekday() == 6, "KAV_WEEK must be a Sunday"
DAYS    = [START + datetime.timedelta(d) for d in range(7)]
weekday = lambda day: (day.weekday() + 1) % 7  # Sunday 0, as the bundle's day bits count
# lat_min, lat_max, lon_min, lon_max. Override with KAV_BBOX="lat0,lat1,lon0,lon1";
# KAV_BBOX=national covers the whole country.
_BB = os.environ.get("KAV_BBOX", "31.90,32.25,34.60,35.05")
BBOX = ((29.0, 33.5, 34.0, 36.0) if _BB == "national"
        else tuple(float(x) for x in _BB.split(",")))
REGION = os.environ.get("KAV_REGION", "tlv")

def openf(n):
    return io.TextIOWrapper(_zip.open(n), encoding="utf-8-sig", newline="")

naim = {}
if os.path.exists(NAIM):
    with openf("stops.txt") as f:
        listed = list(csv.reader(f))
    naim = naim_feed(NAIM, listed[0], listed[1:])
    for a in naim["agency.txt"]:
        a["agency_name"] = NAIM_NAME
    print(f"weekend lines: {len(naim['routes.txt'])} routes, {len(naim['trips.txt'])} trips from {NAIM}")

def table(n):
    """A table's rows, the weekend lines' after the ministry's."""
    yield from csv.DictReader(openf(n))
    yield from naim.get(n, [])

def vint(buf, n):                             # zigzag varint
    n = ((-n << 1) - 1) if n < 0 else (n << 1)   # zigzag: -1->1, 1->2
    while True:
        b = n & 0x7F; n >>= 7
        buf.append(b | 0x80) if n else buf.append(b)
        if not n: break

def vstr(buf, s):
    e = s.encode("utf-8"); vint(buf, len(e)); buf += e

# services running on those days, as a mask of their weekdays (bit 0 = Sunday), with calendar_dates.txt's exceptions
# where a feed has them (the weekend lines' holidays)
def exceptions():
    try:
        yield from csv.DictReader(openf("calendar_dates.txt"))
    except KeyError:
        pass
    yield from naim.get("calendar_dates.txt", [])

active = day_masks(table("calendar.txt"), exceptions(), DAYS)
print(f"services running {DAYS[0]} to {DAYS[-1]}: {len(active):,}")

# The Israeli feed keeps the city in stop_desc, not in stop_name, so a query
# like "דיזנגוף 50 תל אביב" can never match on the name alone. Pull it out and
# index it: ~1,200 distinct cities cost 1-2 bytes per stop instead of ~20.
CITY_RE = re.compile(r"עיר:\s*(.*?)\s*(?:רציף:|קומה:|$)")
# A bus station's platforms are stops of their own, sharing the station's code; stop_desc numbers each one.
PLATFORM_RE = re.compile(r"רציף:\s*([^\s:]+)(?=\s|$)")
stop_idx, stops = {}, []
city_idx, cities = {}, []
stop_platform = {}
point = {}                                    # every stop's (lat, lon), for the trips whose times go back (untangle)
for r in table("stops.txt"):
    lat, lon = float(r["stop_lat"]), float(r["stop_lon"])
    point[r["stop_id"]] = (lat, lon)
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
for r in table("agency.txt"):
    agency_idx[r["agency_id"]] = len(agencies)
    agencies.append(r["agency_name"].strip())
print(f"agencies: {len(agencies):,}")

# The feed gives the Carmelit and the Rakavlit the same type 5, though one is an
# underground funicular and the other hangs from a wire over the Technion. The split
# is made here, where the agency is still in hand: agency 20 is the Carmelit, and 7
# is GTFS's funicular, which is what it is. The Rakavlit keeps 5, the cable car it
# actually is.
#
# Shuttles get the same treatment. The MOT feed has no shuttle type: the shuttles
# kept as a category of their own (the Israel Railways replacement runs, Nativ
# La'Asakim) are curated data that never reaches this feed. The park-and-ride
# shuttle lines it does carry arrive as ordinary buses whose stops are named for
# what they are. 711 is the extended-GTFS "Shuttle Bus", so they leave here
# wearing it.
CARMELIT_AGENCY = "20"
SHUTTLE = "שאטל"

route_idx, routes = {}, []
for r in table("routes.txt"):
    rtype = int(r["route_type"])
    if rtype == 5 and r["agency_id"] == CARMELIT_AGENCY:
        rtype = 7
    if rtype == 3 and (SHUTTLE in r["route_long_name"] or SHUTTLE in r["route_short_name"]):
        rtype = 711
    route_idx[r["route_id"]] = len(routes)
    routes.append((r["route_short_name"].strip(), r["route_long_name"].strip(), rtype,
                   agency_idx.get(r["agency_id"], -1)))

trip_route, trip_days = {}, {}
for r in table("trips.txt"):
    if r["service_id"] in active:
        trip_route[r["trip_id"]] = route_idx.get(r["route_id"], 0)
        trip_days[r["trip_id"]] = active[r["service_id"]]
print(f"trips in the week (national): {len(trip_route):,}")

# stream stop_times, keep trips that touch the bbox
def hms(x):
    a = x.split(":"); return int(a[0])*3600 + int(a[1])*60 + int(a[2])

funicular = {agency_idx[CARMELIT_AGENCY]} if CARMELIT_AGENCY in agency_idx else set()
carmelit_trips = {t for t, r in trip_route.items() if routes[r][3] in funicular}

kept, cur, seq, rows = [], None, [], 0
def flush(tid, sq):
    if not sq or tid not in trip_route: return
    sq.sort()                                 # stop_sequence order, which the feed's rows don't always keep
    sq = once(sq)                             # a stop listed twice in a row, once
    if tid in carmelit_trips:
        times = [(at, at) for at in carmelit([c[4] for c in sq], sq[0][2])]
    else:
        times = untangle([(a, d, point.get(s)) for _, a, d, s, _ in sq])
        if times is None: return              # due at its last stop before it leaves its first: the planner leaves it out too
    inside = [(a, d, stop_idx[c[3]]) for (a, d), c in zip(times, sq) if c[3] in stop_idx]
    if len(inside) < 2: return                # must be usable inside the region
    kept.append((trip_route[tid], trip_days[tid], inside))

with openf("stop_times.txt") as f:
    dist_col = f.readline().rstrip("\r\n").split(",").index("shape_dist_traveled")
    for line in f:
        p = line.rstrip("\n").split(",")
        if len(p) < 5: continue
        if p[0] != cur:
            flush(cur, seq); cur, seq = p[0], []
        if cur in trip_route:
            try:
                dist = float(p[dist_col] or 0) if cur in carmelit_trips else 0
                seq.append((int(p[4]), hms(p[1]), hms(p[2]), p[3], dist))
            except Exception: pass
        rows += 1
        if rows % 5_000_000 == 0: print(f"  {rows:,} rows", flush=True)
for s in sorted(naim.get("stop_times.txt", []), key=lambda s: (s["trip_id"], int(s["stop_sequence"]))):
    if s["trip_id"] != cur:
        flush(cur, seq); cur, seq = s["trip_id"], []
    if cur in trip_route:
        seq.append((int(s["stop_sequence"]), hms(s["arrival_time"]), hms(s["departure_time"]), s["stop_id"], 0))
flush(cur, seq)
# A trip the timetable has once for each set of days, the very same stops at the very same times, goes once with all its
# days: seven days that span two timetables would have most trips twice. Two such on one day (a second bus at the same
# times) stay two.
merged, order = {}, []
for ridx, days, sq in kept:
    k = (ridx, tuple(sq))
    groups = merged.setdefault(k, [])
    free = next((g for g in groups if not g[0] & days), None)
    if free is None:
        free = [0]
        groups.append(free)
        order.append((k, free))
    free[0] |= days
kept = [(ridx, g[0], list(sq)) for (ridx, sq), g in order]
n_conn = sum(len(t[2]) - 1 for t in kept)
n_st = sum(len(t[2]) for t in kept)
print(f"trips kept: {len(kept):,}   stop times: {n_st:,}   connections: {n_conn:,}")
for day in DAYS:
    print(f"  {day:%a %d %b}: {sum(1 for t in kept if t[1] >> weekday(day) & 1):,} trips")

# encode
# KAV5 adds the stop-time count up front and a day mask per trip; KAV6 the platforms at the end, then named sections.
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

# Sections of their own after the platforms, each its name and its length, which an app that doesn't know one skips.
# Older versions of Kav stop reading before them, so they still take this timetable from Kav's server.
def section(name, body):
    vstr(buf, name); vint(buf, len(body)); buf.extend(body)

# "en": English names. The ministry's translations.txt is in its layout from before the standard, the Hebrew text itself
# beside the language and its translation, and writes gershayim as " where stops.txt has two apostrophes. It names nearly
# every stop and town; the rest stay Hebrew. Every town's, every stop's, then the names lines end at (as Kav's server
# reads them from their long names, server/live.py's ends()) that are no stop's, with theirs: what the app shows instead
# of the Hebrew when the rider wants English names.
english = {}
try:
    for r in csv.DictReader(openf("translations.txt")):
        if r["lang"] == "EN" and r["translation"].strip():
            english[r["trans_id"].strip()] = r["translation"].strip()
except KeyError:
    pass
def in_english(he):
    return english.get(he) or english.get(he.replace("''", '"')) or ""

def ends(name):
    left, _, right = name.partition("<->")
    right = re.sub(r"-[0-9]+[#0-9א-ת]*$", "", right)
    return [part.rpartition("-")[0].strip() if "-" in part else part.strip() for part in (left, right)]

en = bytearray()
vint(en, len(cities))
for c in cities:
    vstr(en, in_english(c))
vint(en, len(stops))
for name, *_ in stops:
    vstr(en, in_english(name))
named = {name for name, *_ in stops}
extra = sorted({e for _, long, _, _ in routes for e in ends(long) if e and e not in named and in_english(e)})
vint(en, len(extra))
for he in extra:
    vstr(en, he); vstr(en, in_english(he))
section("en", en)
print(f"english: {sum(1 for c in cities if in_english(c)):,} of {len(cities):,} towns, "
      f"{sum(1 for name, *_ in stops if in_english(name)):,} of {len(stops):,} stops, {len(extra):,} more line ends")

path = os.path.join(OUT, REGION + ".kav")
open(path, "wb").write(buf)
raw = len(buf)
import gzip
# Net.read takes either, so the gzip is there for whoever wants the smaller APK.
gz = gzip.compress(bytes(buf), 9)
open(path + ".gz", "wb").write(gz)

# KAV_MANIFEST: what the app checks the gzip against before it takes it (rebuild.sh signs this file, live.py serves both).
if os.environ.get("KAV_MANIFEST"):
    import hashlib, json
    with open(os.path.join(OUT, "manifest.json"), "w") as f:
        json.dump({"format": "KAV6", "from": DAYS[0].isoformat(), "days": len(DAYS), "sha256": hashlib.sha256(gz).hexdigest(),
                   "size": len(gz)}, f, separators=(",", ":"))

print(f"\nbundle: {raw/1e6:.2f} MB raw   {len(gz)/1e6:.2f} MB gzip -> {path}")
