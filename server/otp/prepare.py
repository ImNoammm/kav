"""Gets the ministry's timetable and OpenStreetMap ready for OpenTripPlanner, which Kav's server plans trips with.

OTP reads GTFS strictly and the ministry's feed breaks five of its rules: an operator without a website, a
translations.txt in a layout from before the standard, a route type (8, its shared taxis) that isn't GTFS, station
records used as stops in trips, and trips whose times go back along their stops. Moovit files those taxis as buses, so
they become buses here; the stations become stops, and those trips' times are put in order as Moovit's are (see
untangle). Trains and light rail take bikes, as Moovit plans them. The Carmelit gets its real ride time (see
CARMELIT), a train passing Tel Aviv Savidor Center its stop there (see DWELL_AT), and the next days' trains Israel
Railways' own timetable runs otherwise go its way (see RAILWAY_URL). Tel Aviv's weekend lines, in no
ministry feed, come from the city's own timetable (see NAIM_URL). Everything else streams through untouched.

Moovit walks into a train or light rail station through its own entrances and then spends a fixed time inside
(stations.tsv). The same goes in as GTFS pathways, so trips here start and end at a station the way Moovit's do.

    python3 prepare.py <dir>

leaves <dir>/israel-gtfs.zip and <dir>/israel.osm.pbf for `--build`. Only the standard library, so it runs in any
Python image.
"""
import csv
import datetime
import http.client
import io
import json
import math
import os
import re
import shutil
import sys
import time
import urllib.request
import zipfile
from zoneinfo import ZoneInfo

GTFS_URL = os.environ.get("GTFS_URL", "https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip")
OSM_URL = os.environ.get("OSM_URL", "https://download.geofabrik.de/asia/israel-and-palestine-latest.osm.pbf")
OSM_EVERY = 7 * 86400  # streets change slowly; the timetable is fetched every night
WAITS = (60, 120, 300, 600, 900)  # seconds between tries: about half an hour for the ministry's server to recover
HERE = os.path.dirname(os.path.abspath(__file__))
STATIONS = os.path.join(HERE, "stations.tsv")
NO_URL = "https://www.gov.il/he/departments/ministry_of_transport_and_road_safety"
BIKES_ON = ("0", "2")  # route types: light rail and trains
# The Carmelit's operator gives its ride as no more than 8 minutes (carmelithaifa.co.il, Globes 4 October 2018). The
# ministry's feed has 12, its departure interval, and the four stations between the ends all within 93 seconds of the
# start, quicker than the line's 28 km/h allows. Until someone times a ride, those four are spread over the 8 minutes
# by the feed's own distances, the way GTFS readers place stops given no time.
CARMELIT = "20"  # agency
CARMELIT_RIDE = 8 * 60
# Israel Railways' own timetable stands nearly every train passing Tel Aviv Savidor Center there for two minutes, and
# the ministry's feed has it leave the minute it comes in, so trips from or through it would leave two minutes early and
# a change there to such a train would be missed. Its departure comes this long after its arrival where the feed has
# none, keyed by stop code: seconds.
DWELL_AT = {"17038": 120}
# The least time from one train to another at a station, which the planner itself must know: told only after the search
# (live.py's TRAIN_CHANGE), a 2-minute change it found could push out the right route, which was then left out.
# Moovit's own plans change trains in 3 minutes at the least.
TRAIN_CHANGE = 180
# Israel Railways' own timetable (rail-api.rail.co.il, with the public key its website sends, as server/live.py asks it
# for platforms) for the next RAILWAY_DAYS days puts right the trains the ministry's feed has otherwise (a line cut
# short for works, evening specials the feed hasn't), as Moovit has them. On a day the railway runs a train otherwise,
# its run goes on a copy of the trip for that day alone; a train only it has is added; every other train, and every day
# it didn't answer, stays the ministry's (rail_plan).
RAILWAY_URL = "https://rail-api.rail.co.il/rjpa/api/v1/timetable/searchTrain"
RAILWAY_KEY = "5e64d66cf03f4547bcac5de2de06b566"
RAILWAY_DAYS = 3  # today and the two after: what a feed fetched the night before can miss
RAILWAY_GAP = 2.2  # seconds between two questions
# Pairs of the railway's stations whose trains between them take in every train.
RAILWAY_PAIRS = ((3700, 4600), (4900, 4600), (2300, 2100), (2100, 2300), (3900, 4300), (8800, 4310), (300, 400), (5000, 9100),
                 (9100, 5000), (400, 300), (3500, 3700), (8600, 3700), (1280, 1260), (7300, 7500), (7500, 7300), (1400, 700),
                 (1220, 1300), (7300, 7320), (5800, 5900), (3600, 3500))
# The railway's station ids by the ministry's station codes, as server/live.py's RAILWAY_IDS and the app's IsraelRail.kt.
RAILWAY_IDS = {
    2643: 4310, 2652: 4300, 2653: 3900, 17000: 300, 17002: 400, 17004: 700, 17008: 1220, 17010: 1300, 17012: 1500,
    17014: 1600, 17016: 2100, 17018: 2200, 17020: 2300, 17022: 2500, 17024: 2800, 17026: 2820, 17028: 3100, 17030: 3300,
    17032: 3400, 17034: 3500, 17036: 3600, 17038: 3700, 17040: 4100, 17042: 4170, 17044: 4250, 17046: 4600, 17048: 4640,
    17050: 4660, 17052: 4680, 17054: 4690, 17056: 4800, 17058: 5000, 17060: 5010, 17062: 5150, 17064: 5200, 17066: 5300,
    17068: 5410, 17070: 5800, 17072: 5900, 17074: 6300, 17080: 7000, 17082: 7300, 17084: 7320, 17086: 7500, 17088: 8550,
    17090: 8600, 17092: 8700, 17094: 8800, 17096: 9000, 17098: 9100, 17100: 9200, 17102: 9800, 17104: 4900, 17106: 9600,
    17108: 9650, 17109: 9700, 17110: 1240, 17111: 1280, 17112: 1260, 17113: 1250, 17114: 3310, 17115: 1840, 17116: 1820,
    17117: 1400, 17118: 680, 17119: 6150, 17120: 6900, 17121: 2940, 17122: 2960, 17123: 1220}
RAIL_OFFSET = 8_000_000  # a train only the railway has, on no route of the ministry's, gets a route this above its number
ISRAEL = ZoneInfo("Asia/Jerusalem")
# Tel Aviv-Yafo's weekend lines (Na'im BeSofash, 705-729), which no ministry feed has: the city publishes their timetable
# (opendatasource.tel-aviv.gov.il item 21, Ryde's GTFS, renewed daily) and it goes into the planner's here. Its ids sit
# NAIM_OFFSET above its own, numbers as the ministry's are and far above them (server/live.py reads route ids as numbers,
# and adds the same lines to its catalog the same way); its stops are the ministry's where the stop code is. The last
# copy fetched is kept with the ministry's own timetable, outside the planner's folder: it takes in every timetable there.
NAIM_URL = os.environ.get("NAIM_URL", "https://opendatasource.tel-aviv.gov.il/OpenData_Ducaments/gtfs.zip")
NAIM_OFFSET = 9_000_000
NAIM_WAITS = (60, 300)
# The city's site answers addresses abroad with a maintenance page, so a Cloudflare Worker run near Tel Aviv fetches
# the file for Kav's server, and the city's address is tried after.
# The Worker's address and the key it asks for, "<url> <key>", are in this file on the server, not in the repository.
NAIM_WORKER = os.path.join(HERE, "naim-worker")

# Ariel's isolated OSM bus-stop node 5210698027 is on the sidewalk. OTP ignores it and links the feed's
# point to the road, making a 75 m approach take 227 m. Only correct that exact old feed position.
STOP_POINTS = {"63235": ((32.102837, 35.178064), (32.1029095, 35.1780599))}


def whole_zip(path):
    try:
        with zipfile.ZipFile(path) as z:
            return z.testzip() is None
    except (zipfile.BadZipFile, OSError):
        return False


def fetch(url, path, check=None, waits=WAITS, extra=None):
    """Downloads url to path, sending the extra headers too. The ministry's server sometimes ends a transfer early
    without an error, or drops it: a file shorter than the length the server gave goes on from where it stopped, one
    `check` turns down starts again, and only a whole file replaces path."""
    part = path + ".part"
    if os.path.exists(part):
        os.remove(part)
    for wait in waits + (None,):
        have = os.path.getsize(part) if os.path.exists(part) else 0
        headers = {"User-Agent": "kav-server", **(extra or {})}
        if have:
            headers["Range"] = "bytes=%d-" % have
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as r:
                more = have and r.status == 206
                length = r.headers.get("Content-Range", "").rpartition("/")[2] if more else r.headers.get("Content-Length")
                with open(part, "ab" if more else "wb") as f:
                    shutil.copyfileobj(r, f, 1 << 20)
            size = os.path.getsize(part)
            if length and length.isdigit() and size < int(length):
                problem = "%d of %s bytes" % (size, length)
            elif check and not check(part):
                problem = "not a whole file"
                os.remove(part)
            else:
                os.replace(part, path)
                return
        except (OSError, http.client.HTTPException) as e:
            problem = repr(e)
        print("fetching %s: %s" % (url, problem), flush=True)
        if wait is None:
            raise OSError("could not fetch %s: %s" % (url, problem))
        time.sleep(wait)


def rows(src, name):
    with io.TextIOWrapper(src.open(name), encoding="utf-8-sig", newline="") as f:
        return list(csv.reader(f))


def text(table):
    out = io.StringIO()
    csv.writer(out, lineterminator="\n").writerows(table)
    return out.getvalue()


def naim_name(name):
    """A weekend line's name ("מגבעתיים אל תל אביב יפו 705") the ministry's way ("-גבעתיים<->-תל אביב יפו"), which Kav reads
    where a line goes from."""
    name = re.sub(r"\s+\d+$", "", name.strip())
    left, sep, right = name.partition(" אל ")
    if not sep:
        left, sep, right = name.partition(" ל")
    if not sep:
        return name
    if left.startswith("מ") and not left.startswith("מסוף "):
        left = left[1:]
    return "-%s<->-%s" % (left.strip(), right.strip())


def naim_feed(path, head, stops):
    """The weekend lines' tables to add to the ministry's, {file: [{column: value}]}: ids NAIM_OFFSET up, its services
    named naim_..., each stop the ministry's stop of the same code (the nearest, if several), else a stop of its own."""
    up = lambda x: str(NAIM_OFFSET + int(x))

    def trip(t):
        first, sep, rest = t.partition("_")
        return up(first) + sep + rest if first.isdigit() else up(0) + "_" + t
    with zipfile.ZipFile(path) as z:
        def table(name):
            if name not in z.namelist():
                return []
            with io.TextIOWrapper(z.open(name), encoding="utf-8-sig", newline="") as f:
                return list(csv.DictReader(f))
        sid, code, kind, lat, lon = (head.index(k) for k in ("stop_id", "stop_code", "location_type", "stop_lat", "stop_lon"))
        by_code = {}
        for r in stops:
            if r[kind] in ("0", "") and r[code]:
                by_code.setdefault(r[code], []).append((float(r[lat]), float(r[lon]), r[sid]))
        out, stop_of = {"stops.txt": []}, {}
        for s in table("stops.txt"):
            same = by_code.get(s["stop_code"].strip())
            if same:
                y, x = float(s["stop_lat"]), float(s["stop_lon"])
                stop_of[s["stop_id"]] = min(same, key=lambda n: (n[0] - y) ** 2 + (n[1] - x) ** 2)[2]
            else:
                stop_of[s["stop_id"]] = up(s["stop_id"])
                out["stops.txt"].append(dict(s, stop_id=up(s["stop_id"]), location_type="0", parent_station=""))
        out["agency.txt"] = [dict(a, agency_id=up(a["agency_id"])) for a in table("agency.txt")]
        out["routes.txt"] = [dict(r, route_id=up(r["route_id"]), agency_id=up(r["agency_id"]),
                                  route_long_name=naim_name(r["route_long_name"])) for r in table("routes.txt")]
        out["trips.txt"] = [dict(t, route_id=up(t["route_id"]), service_id="naim_" + t["service_id"], trip_id=trip(t["trip_id"]),
                                 shape_id=up(t["shape_id"]) if t.get("shape_id") else "") for t in table("trips.txt")]
        # No one gets off at a trip's first stop nor on at its last, as the ministry's trips have it and Ryde's don't,
        # so a bus ending at a stop isn't on its board as leaving it.
        times, ends = table("stop_times.txt"), {}
        for s in times:
            n = int(s["stop_sequence"])
            a, b = ends.get(s["trip_id"], (n, n))
            ends[s["trip_id"]] = (min(a, n), max(b, n))

        def end(s):
            n, (a, b) = int(s["stop_sequence"]), ends[s["trip_id"]]
            return {"drop_off_type": "1"} if n == a else {"pickup_type": "1"} if n == b else {}
        out["stop_times.txt"] = [dict(s, trip_id=trip(s["trip_id"]), stop_id=stop_of[s["stop_id"]], **end(s)) for s in times]
        out["shapes.txt"] = [dict(s, shape_id=up(s["shape_id"])) for s in table("shapes.txt")]
        for name in ("calendar.txt", "calendar_dates.txt"):
            out[name] = [dict(c, service_id="naim_" + c["service_id"]) for c in table(name)]
    return out


def in_order(header, table):
    """{column: value} rows as lists in the header's order, columns the header hasn't left out."""
    return [[r.get(k, "") for k in header] for r in table]


class Tail:
    """A file being written that remembers the last byte written to it."""

    def __init__(self, f):
        self.f, self.last = f, b""

    def write(self, b):
        if b:
            self.f.write(b)
            self.last = b[-1:]


def naim_sources(worker=NAIM_WORKER, url=NAIM_URL):
    """Where the weekend lines' timetable is fetched from, in order: [(url, headers)]. The Worker first, when its file
    is there."""
    try:
        with open(worker) as f:
            address, key = f.read().split()[:2]
    except (OSError, ValueError):
        return [(url, {})]
    return [(address, {"x-kav-key": key}), (url, {})]


def fetch_naim(folder, sources=None, waits=NAIM_WAITS):
    """The weekend lines' timetable fetched into folder from the first of sources (naim_sources() when None) that gives
    it, or the last copy there when none does; None when there's neither: the planner is built without them rather
    than not at all."""
    path = os.path.join(folder, "naim-gtfs.zip")
    for url, headers in naim_sources() if sources is None else sources:
        try:
            fetch(url, path, whole_zip, waits, headers)
            break
        except OSError as e:
            print("weekend lines: %s" % e, flush=True)
    else:
        print("weekend lines: %s" % ("the last copy goes in" if os.path.exists(path) else "left out"), flush=True)
    return path if os.path.exists(path) and whole_zip(path) else None


def stations_in_trips(src, stations):
    used = set()
    with io.TextIOWrapper(src.open("stop_times.txt"), encoding="utf-8-sig", newline="") as f:
        head = next(csv.reader([f.readline()]))
        at = head.index("stop_id")
        for line in f:
            stop = line.split(",", at + 1)[at]
            if stop in stations:
                used.add(stop)
    return used


def carmelit(distances, first):
    """A Carmelit trip's stop times in seconds: its first departure, then the ride spread by distance along the line."""
    span = distances[-1] - distances[0]
    return [first + round(CARMELIT_RIDE * ((d - distances[0]) / span if span > 0 else i / (len(distances) - 1)))
            for i, d in enumerate(distances)]


def seconds(clock):
    h, m, s = clock.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def carmelit_times(src, trips):
    """{(trip, stop sequence): new clock} for the Carmelit's trips."""
    if not trips:
        return {}
    calls = {}
    with io.TextIOWrapper(src.open("stop_times.txt"), encoding="utf-8-sig", newline="") as f:
        head = next(csv.reader([f.readline()]))
        t, dep, seq, dist = (head.index(k) for k in ("trip_id", "departure_time", "stop_sequence", "shape_dist_traveled"))
        for line in f:
            if line.split(",", t + 1)[t] in trips:
                r = line.rstrip("\r\n").split(",")
                calls.setdefault(r[t], []).append((int(r[seq]), r[dep], float(r[dist] or 0)))
    out = {}
    for trip, stops in calls.items():
        stops.sort()
        for (sq, _, _), at in zip(stops, carmelit([d for _, _, d in stops], seconds(stops[0][1]))):
            out[(trip, str(sq))] = "%02d:%02d:%02d" % (at // 3600, at // 60 % 60, at % 60)
    return out


def dwell_times(src, trips, stops):
    """{(trip, stop sequence): (arrival, departure)} for the trains standing at a DWELL_AT station they pass through
    (`stops`: stop id: seconds), where the feed has them leave as they come in."""
    if not trips or not stops:
        return {}
    calls, ends = {}, {}
    with io.TextIOWrapper(src.open("stop_times.txt"), encoding="utf-8-sig", newline="") as f:
        head = next(csv.reader([f.readline()]))
        t, arr, dep, stop, seq = (head.index(k) for k in ("trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"))
        for line in f:
            if line.split(",", t + 1)[t] in trips:
                r = line.rstrip("\r\n").split(",")
                sq = int(r[seq])
                lo, hi = ends.get(r[t], (sq, sq))
                ends[r[t]] = (min(lo, sq), max(hi, sq))
                if r[stop] in stops and r[arr] == r[dep]:
                    calls[(r[t], r[seq])] = (r[arr], stops[r[stop]])
    out = {}
    for (trip, sq), (at, wait) in calls.items():
        if int(sq) not in ends[trip]:
            leave = seconds(at) + wait
            out[(trip, sq)] = (at, "%02d:%02d:%02d" % (leave // 3600, leave // 60 % 60, leave % 60))
    return out


def stops_of_trips(src, trips):
    """The stops the trips call at."""
    found = set()
    with io.TextIOWrapper(src.open("stop_times.txt"), encoding="utf-8-sig", newline="") as f:
        head = next(csv.reader([f.readline()]))
        t, stop = head.index("trip_id"), head.index("stop_id")
        for line in f:
            if line.split(",", t + 1)[t] in trips:
                found.add(line.rstrip("\r\n").split(",")[stop])
    return found


def far(a, b):
    """The straight-line distance between two (lat, lon), in degrees of latitude."""
    return math.hypot(a[0] - b[0], (a[1] - b[1]) * math.cos(math.radians((a[0] + b[0]) / 2)))


def untangle(calls):
    """A trip's times, [(arrival, departure, (lat, lon))] in stop_sequence order in seconds, as [(arrival, departure)]
    that never go back, which OTP needs (it leaves such a trip out: NegativeHopTime); None when its first stop is left
    after its last is reached. They go in order as Moovit puts them: a stop whose time is out of order with the stop
    before or after it loses it, again until those left are in order, and is placed between them by its straight-line
    distance along the trip. The first and last stops keep theirs."""
    if len(calls) < 2 or calls[0][1] > calls[-1][0]:
        return None
    times = [(a, d) for a, d, _ in calls]
    kept = list(range(len(calls)))
    while True:
        out = {k for p, k, q in zip(kept, kept[1:], kept[2:])
               if calls[p][1] > calls[k][0] or calls[k][0] > calls[k][1] or calls[k][1] > calls[q][0]}
        if not out:
            break
        kept = [k for k in kept if k not in out]
    if len(kept) < len(calls):
        along = [0.0]
        for a, b in zip(calls, calls[1:]):
            along.append(along[-1] + far(a[2], b[2]))
        for p, q in zip(kept, kept[1:]):
            for k in range(p + 1, q):
                share = (along[k] - along[p]) / (along[q] - along[p]) if along[q] > along[p] else (k - p) / (q - p)
                at = calls[p][1] + round((calls[q][0] - calls[p][1]) * share)
                times[k] = (at, at)
    return times


def once(calls):
    """A trip's calls, (stop sequence, arrival, departure, stop, ...) in stop_sequence order, with a stop listed twice or
    more in a row as one, from the first's arrival to the last's departure, as Moovit lists it. Never the first stop:
    its departure names the trip, and two routes are nothing but their first stop twice, 6-9 minutes apart (Qalandiya's
    281, Holon's taxi 9). The planner keeps every copy, the live updates naming stops by the ministry's sequence; the
    app's timetable takes this (tools/export_web_bundle.py)."""
    out = list(calls[:1])
    for c in calls[1:]:
        if len(out) > 1 and c[3] == out[-1][3]:
            out[-1] = tuple(out[-1][:2]) + (c[2],) + tuple(out[-1][3:])
        else:
            out.append(c)
    return out


def day_masks(calendar, exceptions, days):
    """{service_id: a mask of the weekdays (bit 0 = Sunday) it runs on among `days` (dates, at most one of each weekday)},
    from calendar.txt's rows and calendar_dates.txt's exceptions (1 adds the date, 2 takes it away). The app's timetable
    keeps a trip's days so (tools/export_web_bundle.py): seven days from any one give each weekday once."""
    bit = lambda day: 1 << (day.weekday() + 1) % 7
    names = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    stamp = {day: day.strftime("%Y%m%d") for day in days}
    out = {}
    for r in calendar:
        mask = 0
        for day in days:
            if r["start_date"] <= stamp[day] <= r["end_date"] and r[names[day.weekday()]] == "1":
                mask |= bit(day)
        if mask:
            out[r["service_id"]] = mask
    for r in exceptions:
        day = next((d for d in days if stamp[d] == r["date"]), None)
        if day is None:
            continue
        mask = out.get(r["service_id"], 0) | bit(day) if r["exception_type"] == "1" else out.get(r["service_id"], 0) & ~bit(day)
        if mask:
            out[r["service_id"]] = mask
        else:
            out.pop(r["service_id"], None)
    return out


def tangled_times(src, points):
    """{(trip, stop sequence): (arrival, departure)} for every stop of the trips whose rows aren't in stop_sequence
    order or whose times go back along it, put in order (untangle); points: stop id: (lat, lon) as text. Such trips
    (Metropoline's 2 round Sheba hospital) have their rows in time order, stop_sequence and distances in the order the
    bus calls, as Moovit's stops go. OTP sorted them by stop_sequence and left every one out. Their rows go in that
    order too (retime): predict.py takes a trip's rows as they come."""
    out = {}

    def before(a, b):
        """Whether clock a comes before clock b; an empty one (a stop given no time) doesn't."""
        if not a or not b:
            return False
        return a < b if len(a) == len(b) else seconds(a) < seconds(b)

    def clock(s):
        return "%02d:%02d:%02d" % (s // 3600, s // 60 % 60, s % 60)

    def check(rows):
        ordered = sorted(rows, key=lambda r: int(r[seq]))
        try:
            calls = [(seconds(r[arr]), seconds(r[dep]), tuple(map(float, points[r[stop]]))) for r in ordered]
        except (KeyError, ValueError):
            return  # a stop or time missing: left as it is
        times = untangle(calls)
        if times and (ordered != rows or times != [c[:2] for c in calls]):
            for r, (a, d) in zip(ordered, times):
                out[(r[t], r[seq])] = (clock(a), clock(d))
    with io.TextIOWrapper(src.open("stop_times.txt"), encoding="utf-8-sig", newline="") as f:
        head = next(csv.reader([f.readline()]))
        t, arr, dep, stop, seq = (head.index(k) for k in ("trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"))
        cur, rows, odd = None, [], False
        for line in f:
            r = line.rstrip("\r\n").split(",")
            if r[t] != cur:
                if odd:
                    check(rows)
                cur, rows, odd = r[t], [], False
            elif int(r[seq]) <= int(rows[-1][seq]) or before(r[arr], rows[-1][dep]):
                odd = True
            if before(r[dep], r[arr]):
                odd = True
            rows.append(r)
        if odd:
            check(rows)
    return out


def retime(f, g, times, order=()):
    """stop_times.txt copied byte for byte but for the clocks in `times`: one for both, or (arrival, departure), and the
    rows of the trips in `order`, which go in stop_sequence order."""
    head = f.readline()
    g.write(head)
    cols = head.decode("utf-8-sig").rstrip("\r\n").split(",")
    t, arr, dep, seq = (cols.index(k) for k in ("trip_id", "arrival_time", "departure_time", "stop_sequence"))
    trips = {trip.encode() for trip, _ in times}
    order = {trip.encode() for trip in order}
    out, held = [], []
    for line in f:
        trip = line.split(b",", t + 1)[t]
        if held and trip != held[0][0]:
            out.extend(l for _, _, l in sorted(held))
            held = []
        if trip in trips:
            body = line.rstrip(b"\r\n")
            r = body.decode().split(",")
            at = times.get((r[t], r[seq]), r[dep])
            r[arr], r[dep] = at if isinstance(at, tuple) else (at, at)
            line = ",".join(r).encode() + line[len(body):]
        if trip in order:
            held.append((trip, int(line.split(b",")[seq]), line if line.endswith(b"\n") else line + b"\n"))
            continue
        out.append(line)
        if len(out) >= 50000:
            g.write(b"".join(out))
            out = []
    out.extend(l for _, _, l in sorted(held))
    g.write(b"".join(out))


def read_stations(path=STATIONS):
    """Station code: [(entrance lat, entrance lon, seconds inside)], from stations.tsv."""
    found = {}
    if not os.path.exists(path):
        return found
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            code, lat, lon, seconds = line.rstrip("\n").split("\t")[:4]
            found.setdefault(int(code), []).append((float(lat), float(lon), int(seconds)))
    return found


def pathways(stops, entrances):
    """The stations' entrances and the walks inside them, as GTFS stops and pathways. A station's platforms are its
    stops sharing the code; they get a parent station unless the feed has one (Tel Aviv's light rail does), an
    entrance where Moovit walks in, and a pathway each way between every entrance and platform."""
    head = stops[0]
    sid, code, name = head.index("stop_id"), head.index("stop_code"), head.index("stop_name")
    lat, lon = head.index("stop_lat"), head.index("stop_lon")
    kind, parent = head.index("location_type"), head.index("parent_station")
    platforms, stations = {}, {}
    for r in stops[1:]:
        if not r[code].isdigit() or int(r[code]) not in entrances:
            continue
        if r[kind] == "1":
            stations[int(r[code])] = r[sid]
        elif r[kind] in ("", "0"):
            platforms.setdefault(int(r[code]), []).append(r)
    added, ways = [], [["pathway_id", "from_stop_id", "to_stop_id", "pathway_mode", "is_bidirectional", "traversal_time"]]
    for c, inside in sorted(platforms.items()):
        first = inside[0]
        station = stations.get(c)
        if station is None:
            station = "kav-station-%d" % c
            row = [""] * len(head)
            row[sid], row[name], row[lat], row[lon], row[kind] = station, first[name], first[lat], first[lon], "1"
            added.append(row)
        for p in inside:
            p[parent] = station
        for i, (la, lo, seconds) in enumerate(entrances[c]):
            door = "kav-entrance-%d-%d" % (c, i)
            row = [""] * len(head)
            row[sid], row[name], row[lat], row[lon] = door, first[name], "%.6f" % la, "%.6f" % lo
            row[kind], row[parent] = "2", station
            added.append(row)
            for p in inside:
                ways.append(["%s-%s" % (door, p[sid]), door, p[sid], "1", "1", str(seconds)])
    return added, ways


def railway_ask(pair, day):
    """The whole day's trains (day: an ISO date) between a pair of the railway's stations, as it answers."""
    body = json.dumps({"fromStation": str(pair[0]), "toStation": str(pair[1]), "date": day, "hour": "00:00",
                       "scheduleType": "ByDeparture", "systemType": "2", "languageId": "Hebrew"}).encode()
    req = urllib.request.Request(RAILWAY_URL, data=body, method="POST", headers={
        "ocp-apim-subscription-key": RAILWAY_KEY, "Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def railway_run(train):
    """A train of the railway's answer as (the day it sets out, [(station id, seconds after that day's midnight)]): its
    stations' clocks are hours and minutes alone, dated by when it leaves the station the answer asked from, or reaches
    the one asked to; None when its run has neither (a train "from" a station not on its run)."""
    stations = train["routeStations"]
    ids = [int(s["stationId"]) for s in stations]
    for end, clock in (("orignStation", "departureTime"), ("destinationStation", "arrivalTime")):
        if int(train[end]) in ids:
            i, at = ids.index(int(train[end])), datetime.datetime.fromisoformat(train[clock])
            break
    else:
        return None
    minutes = [int(s["arrivalTime"][:2]) * 60 + int(s["arrivalTime"][3:5]) for s in stations]
    when = [0] * len(minutes)
    when[i] = at.hour * 60 + at.minute
    for k in range(i + 1, len(minutes)):
        when[k] = when[k - 1] + (minutes[k] - minutes[k - 1]) % 1440
    for k in range(i - 1, -1, -1):
        when[k] = when[k + 1] - (minutes[k + 1] - minutes[k]) % 1440
    day = at.date() + datetime.timedelta(days=when[0] // 1440)
    shift = when[0] // 1440 * 1440
    return day, [(int(s["stationId"]), (w - shift) * 60) for s, w in zip(stations, when)]


def railway_runs(days, ask=railway_ask, gap=RAILWAY_GAP, pairs=RAILWAY_PAIRS):
    """{day: {train number: [(railway station id, seconds after the day's midnight)]}} for the days the railway answered
    every pair of, each train on the day it sets out (a day's answers have the night before's trains going by after
    midnight too); a day it didn't answer is left out, and its trains stay the ministry's."""
    found, answered, asked = {}, [], 0
    for day in days:
        seen = {}
        try:
            for pair in pairs:
                if asked:
                    time.sleep(gap)
                asked += 1
                for travel in ask(pair, day.isoformat())["result"]["travels"]:
                    for train in travel["trains"]:
                        dated = railway_run(train)
                        if dated:
                            seen.setdefault((dated[0], str(train["trainNumber"])), dated[1])
        except (OSError, ValueError, KeyError, TypeError, http.client.HTTPException) as e:
            print("Israel Railways' timetable for %s left out: %r" % (day, e), flush=True)
            continue
        answered.append(day)
        for key, run in seen.items():
            found.setdefault(key, run)
    return {day: {number: run for (on, number), run in found.items() if on == day} for day in answered}


def rail_plan(src, stops, routes, trips, railway):
    """What to add for the trains the railway (railway_runs) runs otherwise than the ministry's feed on its days, as
    {table: [{column: value}]}, and {trip: service} for the ministry's trips those days are taken off. A train runs
    otherwise when its stations or a clock differ (a train standing at a DWELL_AT station is due out after its stand,
    as the railway gives it); its run then goes on a copy of the trip on a service of that day alone, and the trip keeps
    its other days on a copy of its service, as clocks.py does it. A train only the railway has is added, on a route of
    the ministry's between the same two stations if there is one, unless it's another train under a second number (at
    two of its stations at the same minutes as one, as 9700 beside 700). A train on a day the feed has twice, or at a
    station the railway's ids don't name, is left alone, and so is one the railway runs the same: a feed that has the
    railway's runs already is unchanged."""
    out = {name: [] for name in ("routes.txt", "trips.txt", "calendar.txt", "calendar_dates.txt", "stop_times.txt")}
    moved = {}
    if not railway:
        return out, moved
    sh, rh, th = stops[0], routes[0], trips[0]
    code_of = {r[sh.index("stop_id")]: r[sh.index("stop_code")] for r in stops[1:]}
    named = {r[sh.index("stop_id")]: r[sh.index("stop_name")] for r in stops[1:]}
    station = {str(c): s for c, s in RAILWAY_IDS.items()}
    rail = {r[rh.index("route_id")]: r for r in routes[1:] if r[rh.index("route_type")] == "2"}
    t_id, t_route, t_service, t_head, t_way = (th.index(k) for k in ("trip_id", "route_id", "service_id", "trip_headsign", "direction_id"))
    rail_trips = {r[t_id]: r for r in trips[1:] if r[t_route] in rail}
    calendar = rows(src, "calendar.txt")
    ch = calendar[0]
    cal = {r[ch.index("service_id")]: r for r in calendar[1:]}
    dates = rows(src, "calendar_dates.txt") if "calendar_dates.txt" in src.namelist() else [["service_id", "date", "exception_type"]]
    dh = dates[0]
    extra = {}
    for r in dates[1:]:
        extra.setdefault(r[dh.index("service_id")], {})[r[dh.index("date")]] = r[dh.index("exception_type")]

    def by_calendar(service, day):
        r = cal.get(service)
        return r is not None and r[ch.index("start_date")] <= day.strftime("%Y%m%d") <= r[ch.index("end_date")] and \
            r[ch.index(WEEKDAYS[day.weekday()])] == "1"

    def runs(service, day):
        kind = extra.get(service, {}).get(day.strftime("%Y%m%d"))
        return kind == "1" if kind else by_calendar(service, day)
    calls = {}
    with io.TextIOWrapper(src.open("stop_times.txt"), encoding="utf-8-sig", newline="") as f:
        head = next(csv.reader([f.readline()]))
        t, arr, dep, stop, seq = (head.index(k) for k in ("trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"))
        for line in f:
            if line.split(",", t + 1)[t] in rail_trips:
                r = line.rstrip("\r\n").split(",")
                calls.setdefault(r[t], []).append((int(r[seq]), seconds(r[arr]), seconds(r[dep]), r[stop]))
    stop_at = {}  # railway station id: the stop the ministry's trains use there
    for trip in calls.values():
        for _, _, _, s in trip:
            stop_at.setdefault(station.get(code_of.get(s)), s)
    part = {}  # railway station id: how the ministry's route names write it ("station-city")
    for trip, cs in calls.items():
        left, _, right = rail[rail_trips[trip][t_route]][rh.index("route_long_name")].partition("<->")
        cs = sorted(cs)
        for s, name in ((cs[0][3], left), (cs[-1][3], right)):
            if name.strip():
                part.setdefault(station.get(code_of.get(s)), name.strip())

    def ministry_run(trip):
        """[(station id, the clock the railway would give)] of a trip, or None at a station it doesn't name."""
        cs = sorted(calls.get(trip, []))
        out = []
        for k, (_, a, d, s) in enumerate(cs):
            if station.get(code_of.get(s)) is None:
                return None
            stand = DWELL_AT.get(code_of.get(s), 0) if 0 < k < len(cs) - 1 and a == d else 0
            out.append((station[code_of[s]], a if k == len(cs) - 1 else d + stand))
        return out

    def same(mine, theirs):
        return [s for s, _ in mine] == [s for s, _ in theirs] and all(abs(a - b) < 60 for (_, a), (_, b) in zip(mine, theirs))

    def clock(x):
        return "%02d:%02d:%02d" % (x // 3600, x // 60 % 60, x % 60)

    def calls_of(trip, run, stops_by, came=None):
        """stop_times rows of trip running run, at the stops stops_by names; None if one isn't named. At a DWELL_AT
        station the railway's clock is when the train leaves: it comes in when the ministry's trip did (came), if that
        was earlier, or the stand before; never at the same clock, which dwell_times would stand again."""
        rows_out, last = [], 0
        for k, (s, at) in enumerate(run):
            stop = stops_by.get(s) or stop_at.get(s)
            if stop is None:
                return None
            stand = DWELL_AT.get(code_of.get(stop), 0) if 0 < k < len(run) - 1 else 0
            come = at
            if stand:
                come = (came or {}).get(s) if (came or {}).get(s, at) < at else at - stand
            come = max(last, come)
            rows_out.append({"trip_id": trip, "arrival_time": clock(come), "departure_time": clock(at), "stop_id": stop,
                             "stop_sequence": str(k + 1), "pickup_type": "1" if k == len(run) - 1 else "0",
                             "drop_off_type": "1" if k == 0 else "0"})
            last = at
        return rows_out
    changes, added = {}, []
    for day, theirs in sorted(railway.items()):
        on = {}
        for trip, r in rail_trips.items():
            if runs(r[t_service], day):
                on.setdefault(r[t_head].strip(), []).append(trip)
        for number, run in sorted(theirs.items()):
            mine = on.get(number)
            if mine is None:
                added.append((day, number, run))
                continue
            if len(mine) > 1:
                continue
            have = ministry_run(mine[0])
            if have is not None and not same(have, run):
                changes.setdefault(mine[0], []).append((day, run))
    # The trips with days of their own: a copy each such day, the rest of their days on a copy of their service.
    one_day = {}
    without = {}
    for trip, ons in sorted(changes.items()):
        r = rail_trips[trip]
        stops_by = {station.get(code_of.get(s)): s for _, _, _, s in calls[trip]}
        came = {station.get(code_of.get(s)): a for _, a, _, s in calls[trip]}
        made = []
        for day, run in ons:
            copy = "%s~rail%s" % (trip, day.strftime("%Y%m%d"))
            rows_made = calls_of(copy, run, stops_by, came)
            if rows_made is None:
                continue
            made.append(day)
            one_day[day] = "kav-rail-" + day.strftime("%Y%m%d")
            out["trips.txt"].append(dict(zip(th, r), trip_id=copy, service_id=one_day[day]))
            out["stop_times.txt"] += rows_made
        if made:
            key = (r[t_service], tuple(made))
            without.setdefault(key, r[t_service] + "~notrail" + "+".join(d.strftime("%Y%m%d") for d in made))
            moved[trip] = without[key]
    # The trains only the railway has, unless one is another's second number.
    for day, number, run in added:
        others = [r for n, r in railway[day].items() if n != number]
        times = set(run)
        if any(len(times & set(r)) >= 2 for r in others):
            continue
        route, way = None, "0"
        for trip, r in rail_trips.items():
            cs = sorted(calls.get(trip, []))
            if cs and station.get(code_of.get(cs[0][3])) == run[0][0] and station.get(code_of.get(cs[-1][3])) == run[-1][0]:
                route, way = r[t_route], r[t_way]
                break
        if route is None:
            route = str(RAIL_OFFSET + int(number))
            # A station no route of the ministry's starts or ends at goes in with no city, so that live.py's ends() reads
            # its whole name ("תל אביב האוניברסיטה - אקספו" has a dash of its own).
            ends = [part.get(s) or (named.get(stop_at.get(s)) or "") + "-" for s in (run[0][0], run[-1][0])]
            if not any(x["route_id"] == route for x in out["routes.txt"]):
                out["routes.txt"].append({"route_id": route, "agency_id": "2", "route_short_name": "", "route_desc": number,
                                          "route_long_name": "%s<->%s" % tuple(ends), "route_type": "2"})
        trip = "%s~rail%s" % (number, day.strftime("%Y%m%d"))
        rows_made = calls_of(trip, run, {})
        if rows_made is None:
            continue
        one_day[day] = "kav-rail-" + day.strftime("%Y%m%d")
        out["trips.txt"].append({"route_id": route, "service_id": one_day[day], "trip_id": trip, "trip_headsign": number,
                                 "direction_id": way})
        out["stop_times.txt"] += rows_made
    for day, service in sorted(one_day.items()):
        out["calendar.txt"].append(dict({k: "0" for k in WEEKDAYS}, service_id=service, start_date=day.strftime("%Y%m%d"),
                                        end_date=day.strftime("%Y%m%d"), **{WEEKDAYS[day.weekday()]: "1"}))
    for (service, gone), name in sorted(without.items()):
        if service in cal:
            out["calendar.txt"].append(dict(zip(ch, cal[service]), service_id=name))
        for d, kind in sorted(extra.get(service, {}).items()):
            if d not in {x.strftime("%Y%m%d") for x in gone}:
                out["calendar_dates.txt"].append({"service_id": name, "date": d, "exception_type": kind})
        for day in gone:
            if by_calendar(service, day):
                out["calendar_dates.txt"].append({"service_id": name, "date": day.strftime("%Y%m%d"), "exception_type": "2"})
    return out, moved


def clean(raw, out, entrances=None, extra=None, railway=None):
    """extra: the weekend lines' GTFS (fetch_naim), merged in unless raw has them already. railway: Israel Railways'
    trains (railway_runs), which put right the ministry's where they differ (rail_plan)."""
    entrances = read_stations() if entrances is None else entrances
    with zipfile.ZipFile(raw) as src, zipfile.ZipFile(out + ".part", "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        stops = rows(src, "stops.txt")
        head = stops[0]
        sid, kind, parent = head.index("stop_id"), head.index("location_type"), head.index("parent_station")
        code, lat, lon = head.index("stop_code"), head.index("stop_lat"), head.index("stop_lon")
        for r in stops[1:]:
            point = STOP_POINTS.get(r[code])
            if point and r[kind] in ("", "0") and (float(r[lat]), float(r[lon])) == point[0]:
                r[lat], r[lon] = map(str, point[1])
        # A feed cleaned here before comes out the same: its own stations and entrances are made afresh.
        stops = [head] + [r for r in stops[1:] if not r[sid].startswith("kav-")]
        for r in stops[1:]:
            if r[parent].startswith("kav-"):
                r[parent] = ""
        stations = {r[sid] for r in stops[1:] if r[kind] == "1"}
        used = stations_in_trips(src, stations)
        for r in stops[1:]:
            if r[sid] in used:
                r[kind] = "0"
            if r[parent] in used:
                r[parent] = ""
        added, ways = pathways(stops, entrances)
        naim = {}
        listed = rows(src, "routes.txt") if "routes.txt" in src.namelist() else [["route_id"]]
        ids = [r[listed[0].index("route_id")] for r in listed[1:]]
        if extra and not any(x.isdigit() and int(x) >= NAIM_OFFSET for x in ids):
            try:
                naim = naim_feed(extra, head, stops[1:])
            except (KeyError, ValueError, OSError, zipfile.BadZipFile) as e:
                print("weekend lines left out:", repr(e), flush=True)
        dst.writestr("stops.txt", text(stops + added + in_order(head, naim.get("stops.txt", []))))
        if len(ways) > 1:
            dst.writestr("pathways.txt", text(ways))

        agency = rows(src, "agency.txt")
        url = agency[0].index("agency_url")
        agency += in_order(agency[0], naim.get("agency.txt", []))
        for r in agency[1:]:
            if not r[url].strip():
                r[url] = NO_URL
        dst.writestr("agency.txt", text(agency))

        routes = rows(src, "routes.txt")
        mode = routes[0].index("route_type")
        for r in routes[1:]:
            if r[mode] == "8":
                r[mode] = "3"
        # The trains Israel Railways runs otherwise (rail_plan): their days' runs go in with the weekend lines' rows.
        try:
            fixed, moved = rail_plan(src, stops, routes, rows(src, "trips.txt"), railway)
        except (KeyError, ValueError, IndexError, TypeError) as e:
            print("Israel Railways' timetable left out:", repr(e), flush=True)
            fixed, moved = rail_plan(src, stops, routes, rows(src, "trips.txt"), None)
        for name in ("calendar.txt", "calendar_dates.txt", "stop_times.txt"):
            if fixed[name]:
                naim[name] = naim.get(name, []) + fixed[name]
        routes += in_order(routes[0], fixed["routes.txt"] + naim.get("routes.txt", []))
        dst.writestr("routes.txt", text(routes))

        # Moovit takes a bike along on trains and light rail, so their trips carry one here.
        carry = {r[routes[0].index("route_id")] for r in routes[1:] if r[mode] in BIKES_ON}
        trips = rows(src, "trips.txt")
        if "bikes_allowed" not in trips[0]:
            for r in trips:
                r.append("")
            trips[0][-1] = "bikes_allowed"
        t_id, t_service = trips[0].index("trip_id"), trips[0].index("service_id")
        for r in trips[1:]:
            r[t_service] = moved.get(r[t_id], r[t_service])
        trips += in_order(trips[0], fixed["trips.txt"])
        route, way, bikes = (trips[0].index(k) for k in ("route_id", "direction_id", "bikes_allowed"))
        for r in trips[1:]:
            if r[route] in carry:
                r[bikes] = "1"
        trips += in_order(trips[0], naim.get("trips.txt", []))
        dst.writestr("trips.txt", text(trips))

        funicular = {r[routes[0].index("route_id")] for r in routes[1:] if r[routes[0].index("agency_id")] == CARMELIT}
        times = carmelit_times(src, {r[trips[0].index("trip_id")] for r in trips[1:] if r[route] in funicular})
        rail = {r[routes[0].index("route_id")] for r in routes[1:] if r[mode] == "2"}
        dwell = {r[sid]: DWELL_AT[r[head.index("stop_code")]] for r in stops[1:] if r[head.index("stop_code")] in DWELL_AT}
        rail_trips = {r[trips[0].index("trip_id")] for r in trips[1:] if r[route] in rail}
        times.update(dwell_times(src, rail_trips, dwell))
        tangled = tangled_times(src, {r[sid]: (r[lat], r[lon]) for r in stops[1:]})
        times.update(tangled)
        # Trains change in TRAIN_CHANGE at the least at every station they call at, unless the feed has transfers of its own.
        made = "transfers.txt" not in src.namelist()
        if made:
            dst.writestr("transfers.txt", text([["from_stop_id", "to_stop_id", "transfer_type", "min_transfer_time"]] +
                                               [[x, x, "2", str(TRAIN_CHANGE)] for x in sorted(stops_of_trips(src, rail_trips))]))
        done = {"stops.txt", "agency.txt", "routes.txt", "trips.txt", "translations.txt", "pathways.txt"}
        for info in src.infolist():
            if info.filename in done:
                continue
            with src.open(info) as f, dst.open(info.filename, "w", force_zip64=True) as g:
                tail = Tail(g)
                if info.filename == "stop_times.txt" and times:
                    retime(f, tail, times, {trip for trip, _ in tangled})
                else:
                    shutil.copyfileobj(f, tail, 1 << 20)
                if naim.get(info.filename):
                    with io.TextIOWrapper(src.open(info), encoding="utf-8-sig", newline="") as h:
                        header = next(csv.reader(h))
                    if tail.last not in (b"", b"\n"):
                        g.write(b"\n")
                    g.write(text(in_order(header, naim[info.filename])).encode("utf-8"))
            done.add(info.filename)
        # The weekend lines' tables the ministry's feed has none of (calendar_dates.txt, for their holidays).
        for name, table in naim.items():
            if table and name not in done:
                header = list(table[0])
                dst.writestr(name, text([header] + in_order(header, table)))
    # Each route runs one way, which the live updates name the trip by (server/live.py's LiveFeed).
    directions = {r[route]: r[way] for r in trips[1:]}
    with open(os.path.join(os.path.dirname(out), "directions.json.part"), "w") as f:
        json.dump(directions, f)
    os.replace(os.path.join(os.path.dirname(out), "directions.json.part"), os.path.join(os.path.dirname(out), "directions.json"))
    os.replace(out + ".part", out)
    copies = sum(1 for r in fixed["trips.txt"] if r["trip_id"].split("~rail")[0] in moved)
    return {"stations turned into stops": len(used), "station entrances": sum(1 for r in added if r[kind] == "2"),
            "trips put in order": len({trip for trip, _ in tangled}),
            "Israel Railways' days": sorted(d.isoformat() for d in railway or {}),
            "train runs put right": copies, "trains added": len(fixed["trips.txt"]) - copies}


WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")


def trip_patterns(src, days):
    """Each trip running on `days` (service dates), by route and first departure (seconds after its day's midnight), with
    its stops' codes in order, when it ends (`ends`, the same way) and the timetable's own clock at each of its stops
    (`timed`, the same way: an index into `offsets`, each the seconds from its first departure to each stop, in the
    pattern's order; live.py's arrivals of a bus gone quiet go by them). The ministry now and then numbers a run's stops
    unlike the timetable (Kiryat Malakhi reported where the timetable has Be'er Sheva North); the live updates
    (server/live.py's LiveFeed) then send the report to the stop it names. A pattern shared by trips is kept once; a
    route leaving at one time with different stops on different days keeps each day's. The ends tell the live updates
    which trips should be on the road, so that one no bus has run is known not to be."""
    def table(name):
        with io.TextIOWrapper(src.open(name), encoding="utf-8-sig", newline="") as f:
            yield from csv.DictReader(f)
    stamp = {d: d.strftime("%Y%m%d") for d in days}
    services = {}
    for r in table("calendar.txt"):
        on = [d.isoformat() for d in days if r[WEEKDAYS[d.weekday()]] == "1" and r["start_date"] <= stamp[d] <= r["end_date"]]
        if on:
            services[r["service_id"]] = on
    trips = {r["trip_id"]: (r["route_id"], services[r["service_id"]]) for r in table("trips.txt") if r["service_id"] in services}
    code = {r["stop_id"]: r["stop_code"] for r in table("stops.txt")}
    patterns, index, out, done, placed, ends = [], {}, {}, set(), {}, {}
    offsets, offset_index, timed = [], {}, {}

    def keep(trip, calls):
        calls.sort()
        pattern = ",".join(c for _, _, c in calls)
        n = index.setdefault(pattern, len(patterns))
        if n == len(patterns):
            patterns.append(pattern)
        route, on = trips[trip]
        at = str(seconds(calls[0][1]))
        slot = out.setdefault(route, {}).setdefault(at, {})
        for day in on:
            slot[day] = n
        clock = ",".join(str(seconds(d) - int(at)) for _, d, _ in calls)
        m = offset_index.setdefault(clock, len(offsets))
        if m == len(offsets):
            offsets.append(clock)
        slot = timed.setdefault(route, {}).setdefault(at, {})
        for day in on:
            slot[day] = m
        placed[trip] = (route, at)
        last = ends.setdefault(route, {})
        last[at] = max(last.get(at, 0), seconds(calls[-1][1]))

    # The ministry's stop_times.txt keeps each trip's rows together; a trip met again later is left out rather than
    # given half its stops.
    with io.TextIOWrapper(src.open("stop_times.txt"), encoding="utf-8-sig", newline="") as f:
        head = next(csv.reader([f.readline()]))
        t, dep, stop, seq = (head.index(k) for k in ("trip_id", "departure_time", "stop_id", "stop_sequence"))
        cur, calls, split = None, [], set()
        for line in f:
            trip = line.split(",", t + 1)[t]
            if trip != cur:
                if calls:
                    keep(cur, calls)
                if trip in done:
                    split.add(trip)
                done.add(trip)
                cur, calls = trip, []
            if trip in trips and trip not in split:
                r = line.rstrip("\r\n").split(",")
                calls.append((int(r[seq]), r[dep], code.get(r[stop], "")))
        if calls:
            keep(cur, calls)
    for trip in split & placed.keys():
        route, at = placed[trip]
        for day in trips[trip][1]:
            out[route][at].pop(day, None)
            timed[route][at].pop(day, None)
        if not out[route][at]:
            del out[route][at]
            del timed[route][at]
    # A route leaving at that time every day the timetable has trips on (the ministry's starts today), with one pattern,
    # as nearly always, goes as just its number. One leaving then only some days keeps them: taken for every day, a line
    # would have trips due that it never runs, which the live updates never call off.
    covered = sorted({day for times in out.values() for by_day in times.values() for day in by_day})
    for kept in (out, timed):
        for times in kept.values():
            for at, by_day in times.items():
                if len(by_day) == len(covered) and len(set(by_day.values())) == 1:
                    times[at] = next(iter(by_day.values()))
    ends = {route: {at: end for at, end in times.items() if at in out.get(route, {})} for route, times in ends.items()}
    return {"days": covered, "patterns": patterns, "trips": out, "ends": ends, "offsets": offsets, "timed": timed}


def write_patterns(raw, where, today=None):
    """trip-patterns.json for the day before through two days after today (UTC, which covers Israel's day either way)."""
    today = today or datetime.datetime.now(datetime.timezone.utc).date()
    days = [today + datetime.timedelta(days=k) for k in range(-1, 3)]
    with zipfile.ZipFile(raw) as src:
        found = trip_patterns(src, days)
    path = os.path.join(where, "trip-patterns.json")
    with open(path + ".part", "w") as f:
        json.dump(found, f, separators=(",", ":"))
    os.replace(path + ".part", path)
    return {"patterns": len(found["patterns"]), "routes": len(found["trips"])}


def main(where):
    os.makedirs(where, exist_ok=True)
    # The ministry's own timetable, which the arrival model (eta) measures delays against, kept beside the folder the
    # planner builds from rather than in it: the planner takes in every timetable it finds there. The weekend lines' too.
    official = os.path.join(os.path.dirname(os.path.abspath(where)), "official")
    os.makedirs(official, exist_ok=True)
    raw = os.path.join(where, "mot-gtfs.zip")
    fetch(GTFS_URL, raw, whole_zip)
    today = datetime.datetime.now(ISRAEL).date()
    railway = railway_runs([today + datetime.timedelta(days=k) for k in range(RAILWAY_DAYS)])
    print("cleaned:", clean(raw, os.path.join(where, "israel-gtfs.zip"), extra=fetch_naim(official), railway=railway), flush=True)
    print("trip patterns:", write_patterns(raw, where), flush=True)
    os.replace(raw, os.path.join(official, "mot-gtfs.zip"))
    osm = os.path.join(where, "israel.osm.pbf")
    if not os.path.exists(osm) or time.time() - os.path.getmtime(osm) > OSM_EVERY:
        fetch(OSM_URL, osm)
        print("streets refreshed", flush=True)


if __name__ == "__main__":
    main(sys.argv[1])
