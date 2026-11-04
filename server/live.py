"""Live buses for Kav, from the Ministry of Transport's real-time feed (SIRI-SM 2.8).

The ministry only answers whitelisted servers, so the app asks here. Every 15 seconds, the
fastest the ministry allows, this takes its national snapshot of every active trip and keeps
it in memory, and answers by area or by line. For a few routes someone is following, and for
the stops someone is looking at, it asks the ministry directly, which is fresher and has the
arrival times. Line numbers and names, and the road each route takes, come from the
ministry's timetable (GTFS), read once a day. Requests are not logged.

It also plans trips: OpenTripPlanner, next to it on the server, routes on the
same timetable and OpenStreetMap, and this picks the few routes worth showing.

Only Kav is answered: it signs each request with the key it's built with (KAV_APP_KEY here).

Settings come from the environment: MOT_KEY, MOT_URL, MOT_URL_BACKUP (the ministry's test
server, used when the main one fails), MOT_CA (the intermediate certificate the ministry leaves
out of its chain), KAV_APP_KEY, GTFS_URL, OTP_URL (the planner), PLANNER (off for none) and PORT.
"""
import array
from contextlib import closing
import bisect
import base64
import csv
import datetime
import functools
import gzip
import hashlib
import hmac
import io
import itertools
import json
import math
import os
import re
import secrets
import shutil
import sqlite3
import ssl
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile
import zoneinfo
from concurrent.futures import Future, ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEY = os.environ.get("MOT_KEY", "")
URL = os.environ.get("MOT_URL", "")
BACKUP = os.environ.get("MOT_URL_BACKUP", "")
PORT = int(os.environ.get("PORT", "8080"))
APP_KEY = os.environ.get("KAV_APP_KEY", "").encode()
GTFS_URL = os.environ.get("GTFS_URL", "https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip")
HISTORY = os.environ.get("HISTORY", "")  # where the stop passings are kept; none kept without it
REALTIME = os.environ.get("REALTIME", "")  # the folder shared with the planner, for its live updates
ETA = os.environ.get("ETA", "")  # Kav's own predicted arrivals (eta/eta.py), which beat the ministry's own
SHARED_TRIPS = os.environ.get("SHARED_TRIPS", os.path.join(HISTORY or ".", "shared-trips.sqlite3"))
KEEP_DAYS = 70

SNAPSHOT_EVERY = 15  # the ministry's minimum between snapshot requests
IDLE_EVERY = 30  # also how often the history below is timed while nobody looks
IDLE_AFTER = 120
STALE = 300  # past this a snapshot is too old to use
MAX_FOLLOW = 4
FOLLOW_TTL = 15
FOLLOW_PER_SECOND = 2
FOLLOW_BURST = 12  # one Live view asks about its stops in up to six requests at once
MAX_STOPS = 20
MAX_AREA = 0.4  # degrees a side, a city or so
OLD_POSITION = 600  # a position older than this isn't where the bus is
AHEAD = 120  # seconds a report may be stamped after now; some buses' clocks run hours fast
MAX_WORKING = 128  # requests worked on at once; far more than Kav's users make, so only a flood meets it
# PLANNER=off (server/selfhost): no planner here, so no routes from this server.
NO_PLANNER = os.environ.get("PLANNER", "on").lower() in ("off", "0", "no", "false")
OTP_URL = "" if NO_PLANNER else os.environ.get("OTP_URL", "http://kav-otp:8080/otp/gtfs/v1")
MAX_PLANNING = 4  # trips planned at once; each takes OpenTripPlanner a second or two
MORE_WAIT = 5  # seconds a trip's other sections may take after its routes list

TLS = ssl.create_default_context()
if os.environ.get("MOT_CA"):
    TLS.load_verify_locations(os.environ["MOT_CA"])


def log(*parts):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *parts, flush=True)


def epoch(text):
    return int(datetime.datetime.fromisoformat(text).timestamp())


def reporting(at, now):
    """A report made at this time says where the bus is now: within OLD_POSITION, and not stamped more than AHEAD after
    now."""
    return now - at < OLD_POSITION and at - now <= AHEAD


def ask(url, query, timeout):
    request = urllib.request.Request(f"{url}?Key={KEY}&{query}", headers={"User-Agent": "kav-live"})
    with urllib.request.urlopen(request, context=TLS, timeout=timeout) as r:
        return json.loads(r.read())


def whole(text, top):
    """A whole number from 0 to top, or None when the report has none."""
    try:
        n = int(float(text))
    except (TypeError, ValueError):
        return None
    return n if 0 <= n <= top else None


def visits(doc):
    """The answer's time and its vehicles as (route, key, lat, lon, recorded, bearing, speed).

    The key is the journey as the ministry names it: route, first departure, vehicle. A
    tracked arrival carries the same string as its vehicle id, which is how the app matches them.
    """
    delivery = doc["Siri"]["ServiceDelivery"]
    at = epoch(delivery["ResponseTimestamp"])
    monitored = delivery.get("StopMonitoringDelivery") or []
    if isinstance(monitored, dict):
        monitored = [monitored]
    out = []
    for part in monitored:
        for v in part.get("MonitoredStopVisit") or []:
            try:
                j = v["MonitoredVehicleJourney"]
                where = j.get("VehicleLocation")
                if not where:
                    continue
                lat, lon = float(where["Latitude"]), float(where["Longitude"])
                if not (29 < lat < 34 and 34 < lon < 36):
                    continue
                key = f'{j["LineRef"]}_{j["OriginAimedDepartureTime"]}_{j["VehicleRef"]}'
                bearing = whole(j.get("Bearing"), 360)
                out.append((
                    int(j["LineRef"]), key, round(lat, 6), round(lon, 6), epoch(v["RecordedAtTime"]),
                    None if bearing is None else bearing % 360, whole(j.get("Velocity"), 199),
                ))
            except (KeyError, ValueError, TypeError):
                continue
    return at, out


def newest(rows):
    """One row per vehicle, the latest report. A route query lists a vehicle once per stop."""
    best = {}
    for row in rows:
        if row[0] not in best or row[3] > best[row[0]][3]:
            best[row[0]] = row
    return list(best.values())


class Snapshot:
    def __init__(self, at, rows, source):
        self.at = at
        self.source = source
        self.count = len(rows)
        self.rows = rows
        self.routes = {}
        for route, *row in rows:
            self.routes.setdefault(route, []).append(tuple(row))


class Remote(io.RawIOBase):
    """The ministry's GTFS zip read in place: only the parts asked for come down, a quarter megabyte at a time
    unless a bigger chunk suits a long read."""

    def __init__(self, url, chunk=262144):
        self.url, self.pos, self.start, self.buffer, self.chunk = url, 0, 0, b"", chunk
        self.size = int(self.fetch(0, 0)[1].split("/")[1])

    def fetch(self, start, end):
        request = urllib.request.Request(self.url, headers={"Range": f"bytes={start}-{end}", "User-Agent": "kav-live"})
        with urllib.request.urlopen(request, timeout=120) as r:
            return r.read(), r.headers.get("Content-Range", "")

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=0):
        self.pos = offset if whence == 0 else self.pos + offset if whence == 1 else self.size + offset
        return self.pos

    def read(self, n=-1):
        if n is None or n < 0:
            n = self.size - self.pos
        n = min(n, self.size - self.pos)
        if n <= 0:
            return b""
        if not (self.start <= self.pos and self.pos + n <= self.start + len(self.buffer)):
            self.start = self.pos
            self.buffer = self.fetch(self.pos, min(self.size, self.pos + max(n, self.chunk)) - 1)[0]
        data = self.buffer[self.pos - self.start:self.pos - self.start + n]
        self.pos += len(data)
        return data


def ends(name):
    """Where a route starts and ends: MOT names it "stop-city<->stop-city-direction", the direction a digit and then the
    variant: "#", a digit or a letter ("-1א")."""
    left, _, right = name.partition("<->")
    right = re.sub(r"-[0-9]+[#0-9א-ת]*$", "", right)

    def split(part):
        stop, dash, city = part.rpartition("-")
        return (stop.strip(), city.strip()) if dash else (part.strip(), "")
    return split(left), split(right)


# Tel Aviv-Yafo's weekend lines (Na'im BeSofash), in no ministry feed: the city's own timetable, which otp/prepare.py merges
# into the planner's with their ids NAIM_OFFSET up; the catalog takes their routes the same way, the last ones read kept
# while the city's server fails.
NAIM_URL = os.environ.get("NAIM_URL", "https://opendatasource.tel-aviv.gov.il/OpenData_Ducaments/gtfs.zip")
NAIM_OFFSET = 9_000_000
# A train only Israel Railways' own timetable has (otp/prepare.py's rail_plan) is on a route this above its number, in the
# planner only (train 8188 on route 8008188).
RAIL_OFFSET = 8_000_000
RAIL_ONLY_EVERY = 3600  # the planner asked for those routes again after this
naim_held = {"lines": {}}


def naim_name(name):
    """A weekend line's name ("מגבעתיים אל תל אביב יפו 705") the ministry's way ("-גבעתיים<->-תל אביב יפו"), as
    otp/prepare.py writes it, which ends() reads."""
    name = re.sub(r"\s+\d+$", "", name.strip())
    left, sep, right = name.partition(" אל ")
    if not sep:
        left, sep, right = name.partition(" ל")
    if not sep:
        return name
    if left.startswith("מ") and not left.startswith("מסוף "):
        left = left[1:]
    return "-%s<->-%s" % (left.strip(), right.strip())


def naim_lines(data):
    """The weekend lines' routes from their GTFS (bytes) as parse_routes gives the ministry's, ids NAIM_OFFSET up."""
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        text = z.read("routes.txt").decode("utf-8-sig")
    return {NAIM_OFFSET + int(r["route_id"]): route_line(str(NAIM_OFFSET + int(r["agency_id"])), r["route_short_name"],
                                                         naim_name(r["route_long_name"]), int(r["route_type"]))
            for r in csv.DictReader(io.StringIO(text))}


def load_lines():
    found = parse_routes(zipfile.ZipFile(Remote(GTFS_URL)).read("routes.txt").decode("utf-8-sig"))
    # The copy the planner's rebuild keeps in the shared folder first: the city's site answers a server abroad with a
    # maintenance page.
    kept = os.path.join(REALTIME, "naim-gtfs.zip") if REALTIME else ""
    try:
        if kept and os.path.exists(kept):
            with open(kept, "rb") as f:
                naim_held["lines"] = naim_lines(f.read())
        else:
            with urllib.request.urlopen(urllib.request.Request(NAIM_URL, headers={"User-Agent": "kav-server"}), timeout=60) as r:
                naim_held["lines"] = naim_lines(r.read())
    except Exception as e:
        log("weekend lines failing:", repr(e))
    found.update(naim_held["lines"])
    return found


def parse_routes(text):
    """route id: [number, to, to city, from, from city, agency, type, MOT's own name], as Kav's timetable types them."""
    return {int(r["route_id"]): route_line(r["agency_id"], r["route_short_name"], r["route_long_name"], int(r["route_type"]))
            for r in csv.DictReader(io.StringIO(text))}


def route_line(agency, number, name, kind):
    name = name.strip()
    if kind == 5 and agency == "20":
        kind = 7  # Carmelit
    if kind == 3 and ("שאטל" in name or "שאטל" in number):
        kind = 711
    (origin, from_city), (to, to_city) = ends(name)
    return [number.strip(), to, to_city, origin, from_city, int(agency), kind, name]


def encode(points):
    """A path as Google's encoded polyline, to five decimals (about a metre), which the app already reads."""
    out, last_lat, last_lon = [], 0, 0
    for lat, lon in points:
        lat, lon = round(lat * 1e5), round(lon * 1e5)
        for step in (lat - last_lat, lon - last_lon):
            v = ~(step << 1) if step < 0 else step << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1f)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        last_lat, last_lon = lat, lon
    return "".join(out)


def decode(text):
    """The points of an encoded polyline, as encode writes them."""
    points, i, lat, lon = [], 0, 0, 0
    while i < len(text):
        steps = []
        for _ in range(2):
            v = shift = 0
            while True:
                b = ord(text[i]) - 63
                i += 1
                v |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20:
                    break
            steps.append(~(v >> 1) if v & 1 else v >> 1)
        lat, lon = lat + steps[0], lon + steps[1]
        points.append((lat / 1e5, lon / 1e5))
    return points


def stretch(path, stops, start=0):
    """The part of a run's path from stops[start] to its last stop given. stops are the run's from its first, each placed
    on the path after the one before it: where the path comes nearest, or the first time it comes within 30 m of that
    on a path that loops back. Through the stops themselves when there's no path."""
    def apart(p, q):
        return math.hypot((p[0] - q[0]) * 111_320, (p[1] - q[1]) * 111_320 * math.cos(math.radians(p[0])))

    if len(path) < 2:
        return list(stops[start:])
    at = []
    for stop in stops:
        rest = range(at[-1] if at else 0, len(path))
        nearest = min(apart(path[k], stop) for k in rest)
        at.append(next(k for k in rest if apart(path[k], stop) <= nearest + 30))
    return [stops[start]] + path[at[start] + 1:at[-1]] + [stops[-1]]


def shapes_from(archive):
    """route id: the road its trips most often take, encoded. A shape's points come one after another in shapes.txt,
    so only one shape is held at a time."""
    counts = {}
    with archive.open("trips.txt") as f:
        for r in csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig")):
            if r["shape_id"]:
                seen = counts.setdefault(int(r["route_id"]), {})
                seen[r["shape_id"]] = seen.get(r["shape_id"], 0) + 1
    wanted = {}
    for route, seen in counts.items():
        wanted.setdefault(max(seen, key=seen.get), []).append(route)
    found = {}
    with archive.open("shapes.txt") as f:
        rows = csv.reader(io.TextIOWrapper(f, encoding="utf-8-sig"))
        column = {name: i for i, name in enumerate(next(rows))}
        shape, lat, lon, order = (column[c] for c in ("shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence"))
        for key, points in itertools.groupby(rows, key=lambda r: r[shape]):
            routes = wanted.get(key)
            if not routes:
                continue
            path = encode((float(r[lat]), float(r[lon])) for r in sorted(points, key=lambda r: int(r[order])))
            for route in routes:
                found[route] = path
    return found


def load_shapes():
    return shapes_from(zipfile.ZipFile(Remote(GTFS_URL, chunk=4 << 20)))


catalog = {"lines": {}, "numbers": {}, "shapes": {}}


def keep_lines():
    while True:
        try:
            found = load_lines()
            # The railway-only trains keep_rail_only() added stay till it next asks.
            found.update({k: v for k, v in catalog["lines"].items() if RAIL_OFFSET <= k < NAIM_OFFSET and k not in found})
            numbers = {}
            for route, line in found.items():
                numbers.setdefault(line[0], []).append(route)
            catalog["lines"], catalog["numbers"] = found, numbers
            log("lines loaded:", len(found))
            time.sleep(12 * 3600)
        except Exception as e:
            log("lines failing:", e)
            time.sleep(300)


def rail_only_lines(ask=None):
    """The planner's railway-only routes (RAIL_OFFSET up, under NAIM_OFFSET) as the catalog has a line, numbered by their
    train: in none of the ministry's feeds, so not in its routes.txt either."""
    ask = ask or ask_graphql
    found = {}
    for r in (ask("{ routes(transportModes: [RAIL]) { gtfsId shortName longName type agency { gtfsId } } }") or {}).get("routes") or []:
        try:
            route = int(r["gtfsId"].split(":", 1)[1])
        except (KeyError, ValueError, IndexError):
            continue
        if RAIL_OFFSET <= route < NAIM_OFFSET:
            found[route] = route_line(r["agency"]["gtfsId"].split(":", 1)[1], str(route - RAIL_OFFSET), r.get("longName") or "",
                                      r.get("type") or 2)
    return found


def keep_rail_only():
    """Adds the planner's railway-only trains (rail_only_lines) to the catalog, once an hour: a rebuild adds the next
    days', whose rows would otherwise come with no line, number or platform."""
    while True:
        try:
            found = rail_only_lines()
            if found:
                lines = dict(catalog["lines"])
                lines.update(found)
                numbers = {k: list(v) for k, v in catalog["numbers"].items()}
                for route, line in found.items():
                    if route not in numbers.get(line[0], []):
                        numbers.setdefault(line[0], []).append(route)
                catalog["lines"], catalog["numbers"] = lines, numbers
            time.sleep(RAIL_ONLY_EVERY)
        except Exception as e:
            log("railway-only lines failing:", e)
            time.sleep(300)


# Israel Railways' own runs of trains on the days it runs them otherwise than the ministry's timetable, which the planner
# has on one-day copies of their trips (otp/prepare.py's rail_plan, trip ids ending "~railYYYYMMDD"). The live updates
# name trips as the ministry's lists do, by the ministry's trip, and find their stops in trip-patterns.json, the ministry's
# own timetable, which has no copies, so a train run otherwise would have no live times.
RAIL_COPIES_EVERY = 1800  # the planner asked for its copies again after this
rail_copies_held = {"copies": {}}


def rail_copies(ask=None):
    """{(route, day): (seconds into the day it sets out, its stops' codes, when it's due at each, seconds into the day)}
    for the planner's one-day copies of train trips: a train's route runs one trip a day, so that copy is the train that
    day."""
    ask = ask or ask_graphql
    found = {}
    query = ("{ routes(transportModes: [RAIL]) { gtfsId trips { gtfsId activeDates stoptimes { stop { code } scheduledArrival "
             "scheduledDeparture } } } }")
    for r in (ask(query) or {}).get("routes") or []:
        route = r["gtfsId"].split(":", 1)[-1]
        for t in r.get("trips") or []:
            calls = t.get("stoptimes") or []
            if "~rail" not in t.get("gtfsId", "") or not calls:
                continue
            for d in t.get("activeDates") or []:
                found[(route, "%s-%s-%s" % (d[:4], d[4:6], d[6:8]))] = (
                    calls[0]["scheduledDeparture"], [str(c["stop"]["code"]) for c in calls],
                    [c.get("scheduledArrival", c["scheduledDeparture"]) for c in calls])
    return found


def keep_rail_copies():
    """rail_copies_held, asked of the planner once per RAIL_COPIES_EVERY: a rebuild brings the next days' copies."""
    while True:
        try:
            rail_copies_held["copies"] = rail_copies()
            time.sleep(RAIL_COPIES_EVERY)
        except Exception as e:
            log("railway copies failing:", e)
            time.sleep(300)


def keep_shapes():
    while True:
        try:
            catalog["shapes"] = load_shapes()
            log("shapes loaded:", len(catalog["shapes"]))
            time.sleep(24 * 3600)
        except Exception as e:
            log("shapes failing:", e)
            time.sleep(300)


def lines_of(routes):
    known = catalog["lines"]
    out = {str(r): known[r] for r in set(routes) if r in known}
    # A train only the railway runs that the catalog has no line for yet (railway_specials): a train of its number.
    out.update({str(r): [str(r - RAIL_OFFSET), "", "", "", "", 2, 2, ""] for r in set(routes)
                if r not in known and RAIL_OFFSET <= r < NAIM_OFFSET})
    return out


state = {"snapshot": None, "next": 0.0, "asked": 0.0, "failing": None}
wake = threading.Event()

# The snapshot gives a train's place but never when it gets anywhere, so the railway stations' own lists give each
# train's expected arrival at every station ahead, all the stations in one request.
TRAINS_EVERY = 60
TRAINS_KEEP = 300  # lists older than this are left out
trains_held = {"asked": 0.0, "at": 0.0, "doc": None, "codes": None, "codes_at": 0.0, "failed": None, "listed": {}}


def codes_of_mode(kind, feed=None):
    """The stop codes of every route of that kind (the catalog's: 2 trains, 0 light rail) in the timetable kav-live holds
    (trip-patterns.json), for a server run without a planner to ask (server/selfhost's PLANNER=off)."""
    doc = (feed or live_feed).patterns_now() or {}
    found = set()
    for route, starts in (doc.get("trips") or {}).items():
        if (catalog["lines"].get(int(route)) or [None] * 7)[6] != kind:
            continue
        for at in starts.values():
            for n in (at.values() if isinstance(at, dict) else (at,)):
                found.update(doc["patterns"][n].split(","))
    return sorted(c for c in found if c)


def train_lists(now, ask_planner=None):
    """The ministry's lists for every railway station (the planner's stops of mode RAIL), asked at most once per
    TRAINS_EVERY and within Kav's share of requests; None while it has none younger than TRAINS_KEEP."""
    ask_planner = ask_planner or ask_graphql
    if URL and now - trains_held["asked"] >= TRAINS_EVERY:
        trains_held["asked"] = now
        try:
            if not trains_held["codes"] or now - trains_held["codes_at"] > PLANNER_STOPS_TTL:
                if OTP_URL:
                    stops = ask_planner("{ stops { code vehicleMode } }").get("stops") or []
                    codes = sorted({str(s["code"]) for s in stops if s.get("vehicleMode") == "RAIL" and s.get("code")})
                else:
                    codes = codes_of_mode(2)
                trains_held.update(codes=codes, codes_at=now)
            if trains_held["codes"] and spend():
                query = "MonitoringRef=%s&StopVisitDetailLevel=normal&PreviewInterval=PT90M" % ",".join(trains_held["codes"])
                trains_held.update(doc=ask(URL, query, 15), at=now, failed=None)
                note_listed(trains_held["doc"], now)
        except Exception as e:
            if trains_held["failed"] != repr(e):
                log("train lists failing:", repr(e))
                trains_held["failed"] = repr(e)
    return trains_held["doc"] if now - trains_held["at"] <= TRAINS_KEEP else None


TRAMS_EVERY = 60
trams_held = {"asked": 0.0, "codes": None, "codes_at": 0.0, "failed": None}


def tram_lists(now, ask_planner=None):
    """The ministry's lists for every light rail stop (the planner's stops of mode TRAM), asked at most once per
    TRAMS_EVERY and within Kav's share of requests, and the trams in them noted (note_trams): a board first opened while a
    tram sets out then has it too, not only one someone had been looking at. Kept apart from train_lists(), whose trains
    go to the planner as live updates."""
    ask_planner = ask_planner or ask_graphql
    if not URL or now - trams_held["asked"] < TRAMS_EVERY:
        return
    trams_held["asked"] = now
    try:
        if not trams_held["codes"] or now - trams_held["codes_at"] > PLANNER_STOPS_TTL:
            if OTP_URL:
                stops = ask_planner("{ stops { code vehicleMode } }").get("stops") or []
                codes = sorted({str(s["code"]) for s in stops if s.get("vehicleMode") == "TRAM" and s.get("code")})
            else:
                codes = codes_of_mode(0)
            trams_held.update(codes=codes, codes_at=now)
        if trams_held["codes"] and spend():
            query = "MonitoringRef=%s&StopVisitDetailLevel=normal&PreviewInterval=PT90M" % ",".join(trams_held["codes"])
            rows = arrivals(ask(URL, query, 15))
            with stops_lock:
                note_trams(rows, now)
            trams_held["failed"] = None
    except Exception as e:
        if trams_held["failed"] != repr(e):
            log("tram lists failing:", repr(e))
            trams_held["failed"] = repr(e)


TRAIN_DROPPED = 180  # a station's lists without a train they had this long: it has gone by (left_station)


def note_listed(doc, now):
    """Notes when the stations' lists last had each train, by station code, route and first departure, and what they
    call it: its number (by route and first departure) and its platform at the station (named_trains)."""
    listed = trains_held.setdefault("listed", {})
    platforms = trains_held.setdefault("platforms", {})
    numbers = trains_held.setdefault("numbers", {})
    monitored = ((doc.get("Siri") or {}).get("ServiceDelivery") or {}).get("StopMonitoringDelivery") or []
    if isinstance(monitored, dict):
        monitored = [monitored]
    for part in monitored:
        for v in part.get("MonitoredStopVisit") or []:
            try:
                j = v["MonitoredVehicleJourney"]
                call = j["MonitoredCall"]
                key = (str(call["StopPointRef"]), int(j["LineRef"]), epoch(j["OriginAimedDepartureTime"]))
                listed[key] = now
                platform = str(call.get("ArrivalPlatformName") or call.get("DeparturePlatformName") or "").strip()
                if PLATFORM.match(platform):
                    platforms[key] = platform
                number = str(j.get("PublishedLineName") or "").strip()
                if number:
                    numbers[key[1:]] = (number, now)
            except (KeyError, ValueError, TypeError):
                continue
    for key in [k for k, at in listed.items() if now - at > 3 * 3600]:
        del listed[key]
        platforms.pop(key, None)
    for key in [k for k, (_, at) in numbers.items() if now - at > 3 * 3600]:
        del numbers[key]


# Israel Railways' own timetable (rail-api.rail.co.il, with the public key its website sends), for platforms: asked by
# the server for fixed pairs of stations whose trains cover every line, so the railway learns nothing of anyone's trip
# (the app asks it only when a rider does). Its answer is the whole day's trains with their platform at every station.
RAILWAY_URL = "https://rail-api.rail.co.il/rjpa/api/v1/timetable/searchTrain"
RAILWAY_KEY = "5e64d66cf03f4547bcac5de2de06b566"
# The railway's ids by the ministry's station codes, as the app's IsraelRail.kt has them; two codes name HaMifratz Central.
RAILWAY_IDS = {
    2643: 4310, 2652: 4300, 2653: 3900, 17000: 300, 17002: 400, 17004: 700, 17008: 1220, 17010: 1300, 17012: 1500,
    17014: 1600, 17016: 2100, 17018: 2200, 17020: 2300, 17022: 2500, 17024: 2800, 17026: 2820, 17028: 3100, 17030: 3300,
    17032: 3400, 17034: 3500, 17036: 3600, 17038: 3700, 17040: 4100, 17042: 4170, 17044: 4250, 17046: 4600, 17048: 4640,
    17050: 4660, 17052: 4680, 17054: 4690, 17056: 4800, 17058: 5000, 17060: 5010, 17062: 5150, 17064: 5200, 17066: 5300,
    17068: 5410, 17070: 5800, 17072: 5900, 17074: 6300, 17080: 7000, 17082: 7300, 17084: 7320, 17086: 7500, 17088: 8550,
    17090: 8600, 17092: 8700, 17094: 8800, 17096: 9000, 17098: 9100, 17100: 9200, 17102: 9800, 17104: 4900, 17106: 9600,
    17108: 9650, 17109: 9700, 17110: 1240, 17111: 1280, 17112: 1260, 17113: 1250, 17114: 3310, 17115: 1840, 17116: 1820,
    17117: 1400, 17118: 680, 17119: 6150, 17120: 6900, 17121: 2940, 17122: 2960, 17123: 1220}
# Pairs of the railway's stations (from, to) whose answers carry every train the ministry runs, each on its own run: a
# station before the other on its way. These 20 are the fewest found over every pair of stations on a day's runs.
RAILWAY_PAIRS = ((3700, 4600), (4900, 4600), (2300, 2100), (2100, 2300), (8800, 3900), (3900, 4300), (300, 400), (400, 300),
                 (9100, 5000), (5000, 9100), (680, 8600), (8600, 680), (1280, 1260), (7500, 7300), (7300, 7500), (1400, 1220),
                 (1220, 1300), (2940, 3700), (5900, 7320), (5000, 5300))
RAILWAY_EVERY = 900  # its platforms asked again after this
RAILWAY_KEEP = 3600  # and what it said kept this long while it fails
# From this hour of the evening, tomorrow's timetable too, once per RAILWAY_TOMORROW_EVERY (120 requests an evening): an
# evening's routes and boards come to trains of tomorrow's date: the day's answers leave out the next day's trains, and
# their trains after midnight are the night before's runs, whose platforms can differ.
RAILWAY_TOMORROW_FROM, RAILWAY_TOMORROW_EVERY = 18, 3600
# A train's number is one run a day: the run of it nearest a time, within this, is the one then.
RAILWAY_RUN_NEAR = 6 * 3600
railway_held = {"asked": 0.0, "at": 0.0, "platforms": {}, "runs": {}, "busy": False, "failed": None,
                "tomorrow_asked": 0.0, "tomorrow_runs": {}, "tomorrow_busy": False}


def railway_platforms(answers):
    """{(train number, station code): platform} at every station of each train in the railway's answers."""
    codes = {}
    for code, station in RAILWAY_IDS.items():
        codes.setdefault(station, []).append(str(code))
    out = {}
    for answer in answers:
        for travel in ((answer or {}).get("result") or {}).get("travels") or []:
            for train in travel.get("trains") or []:
                number = str(train.get("trainNumber") or "")
                for stop in train.get("routeStations") or [] if number else ():
                    platform = str(stop.get("platform") or "").strip()
                    if PLATFORM.match(platform) and platform != "0":
                        for code in codes.get(stop.get("stationId"), ()):
                            out[(number, code)] = platform
    return out


def railway_runs(answers):
    """{(train number, station code): {when it's there: platform}} at every station of each train in the railway's
    answers, its "HH:MM" on the day nearest when the train sets out on the journey answered (railway_when()). A day's
    answers have its trains after the midnight before it, the next day's those after the coming one: the same number,
    a day apart."""
    codes = {}
    for code, station in RAILWAY_IDS.items():
        codes.setdefault(station, []).append(str(code))
    out = {}
    for answer in answers:
        for travel in ((answer or {}).get("result") or {}).get("travels") or []:
            for train in travel.get("trains") or []:
                number = str(train.get("trainNumber") or "")
                try:
                    start = datetime.datetime.fromisoformat(train["departureTime"]).replace(tzinfo=ISRAEL).timestamp()
                except (KeyError, TypeError, ValueError):
                    continue
                for stop in train.get("routeStations") or [] if number else ():
                    platform, at = str(stop.get("platform") or "").strip(), railway_when(stop.get("arrivalTime"), start)
                    if PLATFORM.match(platform) and platform != "0" and at is not None:
                        for code in codes.get(stop.get("stationId"), ()):
                            out.setdefault((number, code), {})[at] = platform
    return out


def railway_platform(number, code, at=None):
    """The railway's platform for the train at the station: its run's nearest at (RAILWAY_RUN_NEAR), in today's answers
    first, then tomorrow's; with no time or no run of it then, today's answers' (railway_held["platforms"]). None while
    its timetable isn't at hand, or where it gives none."""
    if time.time() - railway_held["at"] > RAILWAY_KEEP or not railway_held["platforms"]:
        return None
    if at is not None:
        for runs in (railway_held["runs"], railway_held["tomorrow_runs"]):
            near = [t for t in runs.get((number, code)) or () if abs(t - at) <= RAILWAY_RUN_NEAR]
            if near:
                return runs[(number, code)][min(near, key=lambda t: abs(t - at))]
    return railway_held["platforms"].get((number, code))


def railway_ask(pair, day, hour):
    body = json.dumps({"fromStation": str(pair[0]), "toStation": str(pair[1]), "date": day, "hour": hour,
                       "scheduleType": "ByDeparture", "systemType": "2", "languageId": "Hebrew"}).encode()
    req = urllib.request.Request(RAILWAY_URL, data=body, method="POST", headers={
        "ocp-apim-subscription-key": RAILWAY_KEY, "Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def railway_refresh(now, ask=None):
    """The railway's platforms for every pair of RAILWAY_PAIRS from an hour before now on (railway_held); what it said
    before stays RAILWAY_KEEP while it fails."""
    ask = ask or railway_ask
    try:
        since = datetime.datetime.fromtimestamp(now - 3600, ISRAEL)
        answers = [ask(pair, since.strftime("%Y-%m-%d"), since.strftime("%H:%M")) for pair in RAILWAY_PAIRS]
        found = railway_platforms(answers)
        if found:
            railway_held.update(platforms=found, runs=railway_runs(answers), at=now, failed=None)
    except Exception as e:
        if railway_held["failed"] != repr(e):
            log("railway platforms failing:", repr(e))
            railway_held["failed"] = repr(e)
    finally:
        railway_held["busy"] = False


def railway_tomorrow(now, ask=None):
    """Tomorrow's runs for every pair of RAILWAY_PAIRS (railway_held["tomorrow_runs"]); what it said before stays while
    it fails."""
    ask = ask or railway_ask
    try:
        day = (datetime.datetime.fromtimestamp(now, ISRAEL) + datetime.timedelta(days=1)).strftime("%Y-%m-%d")
        found = railway_runs([ask(pair, day, "00:00") for pair in RAILWAY_PAIRS])
        if found:
            railway_held["tomorrow_runs"] = found
    except Exception as e:
        if railway_held["failed"] != repr(e):
            log("railway platforms failing:", repr(e))
            railway_held["failed"] = repr(e)
    finally:
        railway_held["tomorrow_busy"] = False


# The railway's own place for one train, its last station and the next ("nextStation" 0: standing there), asked for that
# train alone when the no-GPS rule would bring it in (with_coming): from the railway's answer for the station and the next
# one on its run. Asking for every train instead takes 6 to 10 whole-day answers each time, too many to ask every minute.
RAILWAY_PLACE_FRESH = 90  # a place the railway gave is taken this long
RAILWAY_ASKING_MOST = 4  # trains asked about at once
# train number -> (when asked, the railway's station ids along its run, last station, next station, when it has the train
# due at each of them); no last station when it has the train but no place for it
railway_places = {}
railway_asking = set()


def railway_passed(number, code, following, now, start=None):
    """Whether the railway has the train past the station: its last station one after it on the train's run, or this one
    with the train under way to the next. None when it isn't known: then the railway is asked, in the background, for the
    station and the one following it on the train's run (railway_place)."""
    held = railway_places.get(number)
    if held is not None and now - held[0] <= RAILWAY_PLACE_FRESH:
        _, run, last, going = held[:4]
        here = RAILWAY_IDS.get(int(code))
        if here in run and last in run:
            return run.index(last) > run.index(here) or (last == here and going not in (0, None))
        return None
    railway_wanted(number, code, following, now, start)
    return None


def railway_wanted(number, code, following, now, start=None):
    """Asks the railway in the background for the train's place (railway_place), from its answer for the station and the
    following one on the train's run, for RAILWAY_ASKING_MOST trains at most at once."""
    if number and number not in railway_asking and len(railway_asking) < RAILWAY_ASKING_MOST \
            and int(code) in RAILWAY_IDS and int(following) in RAILWAY_IDS:
        railway_asking.add(number)
        (start or (lambda f, *a: threading.Thread(target=f, args=a, daemon=True).start()))(railway_place, number, code, following, now)


def railway_place(number, code, following, now, ask=None):
    """The railway's place for the train (railway_places), from its answer for the station and the following one, and when
    it has the train due at each station of its run: its timetable, and the delay it gives with the place. A train it has
    with no place keeps its run."""
    ask = ask or railway_ask
    try:
        since = datetime.datetime.fromtimestamp(now - 5400, ISRAEL)
        answer = ask((RAILWAY_IDS[int(code)], RAILWAY_IDS[int(following)]), since.strftime("%Y-%m-%d"), since.strftime("%H:%M"))
        found = (now, [], None, None, {})
        for travel in ((answer or {}).get("result") or {}).get("travels") or []:
            for train in travel.get("trains") or []:
                if str(train.get("trainNumber")) == number and (not found[1] or train.get("trainPosition")):
                    place, stops = train.get("trainPosition") or {}, train.get("routeStations") or []
                    late, due = 60 * int(place.get("calcDiffMinutes") or 0), {}
                    for stop in stops:
                        when = railway_when(stop.get("arrivalTime"), now)
                        if when is not None:
                            due[stop.get("stationId")] = when + late
                    found = (now, [stop.get("stationId") for stop in stops], place.get("currentLastStation"),
                             place.get("nextStation"), due)
        railway_places[number] = found
        if len(railway_places) > 2000:
            for key in [k for k, v in list(railway_places.items()) if now - v[0] > 3600]:
                del railway_places[key]
    except Exception as e:
        if railway_held["failed"] != repr(e):
            log("railway place failing:", repr(e))
            railway_held["failed"] = repr(e)
    finally:
        railway_asking.discard(number)


def railway_when(text, now):
    """When the railway's "HH:MM" is: that time on the day that puts it nearest now; None when it isn't a time."""
    try:
        hour, minute = (int(x) for x in str(text).split("T")[-1].split(":")[:2])
        day = datetime.datetime.fromtimestamp(now, ISRAEL).replace(hour=hour, minute=minute, second=0, microsecond=0)
    except ValueError:
        return None
    return int(min((day + datetime.timedelta(days=d) for d in (-1, 0, 1)), key=lambda t: abs(t.timestamp() - now)).timestamp())


# A train Kav brings in itself whose reports have named the same station for TRAIN_PARKED is asked about at the railway
# too (railway_stopped): its reports can stand still while Kav's times have it coming. Trains running a long way between
# two stations name the same one as long. The railway's places run behind, so one station before the one the reports
# name says nothing.
TRAIN_PARKED = 600
TRAIN_BEHIND = 900  # a row this long after the railway's own time for its train at the station isn't that train's


def railway_stopped(number, code, named, since, due, now, following=None, start=None):
    """Whether the railway has a train whose reports have named the same station since then, for TRAIN_PARKED or longer,
    not coming to the station when its row has it: past it; not running (it has the train with no place, its timetable
    having it out, and the reports have it past the station it sets out from); two or more stations before the one its
    reports name; or due there TRAIN_BEHIND or more before the row has it, while not past the station its reports name
    (one whose reports stop while it runs on is past it, and comes, if sooner). None when it isn't known: then the railway
    is asked, with railway_passed's budget, for the station its reports name and this one (or the one after it)."""
    if since is None or now - since < TRAIN_PARKED or not str(named).isdigit() or not str(code).isdigit():
        return None
    here, there = RAILWAY_IDS.get(int(code)), RAILWAY_IDS.get(int(named))
    held = railway_places.get(number)
    if held is None or now - held[0] > RAILWAY_PLACE_FRESH:
        if here != there:
            railway_wanted(number, named, code, now, start)
        elif following is not None:
            railway_wanted(number, code, following, now, start)
        return None
    _, run, last, going = held[:4]
    times = held[4] if len(held) > 4 else {}
    if here not in run or there not in run or run.index(there) > run.index(here):
        return None
    if last is None:
        return run.index(there) > 0 and times.get(run[0], now) < now
    if last not in run:
        return None
    at, named_at = run.index(last), run.index(there)
    if at > run.index(here) or at == run.index(here) and going not in (0, None):
        return True
    if at > named_at or at == named_at and going not in (0, None):
        return False
    if at < named_at - 1:
        return True
    return here != there and here in times and due - times[here] >= TRAIN_BEHIND


def railway_due(now, start=None):
    """Asks the railway again in the background once per RAILWAY_EVERY (railway_refresh), and from RAILWAY_TOMORROW_FROM
    in the evening for tomorrow's too, once per RAILWAY_TOMORROW_EVERY (railway_tomorrow), never both at once."""
    start = start or (lambda f, *a: threading.Thread(target=f, args=a, daemon=True).start())
    if railway_held["busy"] or railway_held["tomorrow_busy"]:
        return
    if now - railway_held["asked"] >= RAILWAY_EVERY:
        railway_held.update(asked=now, busy=True)
        start(railway_refresh, now)
    elif now - railway_held["tomorrow_asked"] >= RAILWAY_TOMORROW_EVERY and \
            datetime.datetime.fromtimestamp(now, ISRAEL).hour >= RAILWAY_TOMORROW_FROM:
        railway_held.update(tomorrow_asked=now, tomorrow_busy=True)
        start(railway_tomorrow, now)


def named_trains(rows):
    """The rows, a train's without a number or platform given what the stations' lists last had for it: the number its
    run has at any station, the platform this station's. Kav brings some trains in itself, at the station they start
    from (with_starting) and in their last minute before one, once its list has dropped them (with_coming, with_near),
    and gave them neither, where both are known. A platform still missing is the railway's own (railway_held), its run
    due there then's (railway_platform()), and at the station a train sets out from the railway's is taken over the
    ministry's. Further on, where the two differ, the ministry's stays."""
    platforms, numbers = trains_held.get("platforms") or {}, trains_held.get("numbers") or {}
    railway = railway_held["platforms"] if time.time() - railway_held["at"] <= RAILWAY_KEEP else {}
    out = []
    for r in rows:
        if (not r[3] or not r[13] or r[12] == 1 and railway) and (catalog["lines"].get(r[1]) or [None] * 7)[6] == 2:
            r = list(r)
            r[3] = r[3] or (numbers.get((r[1], r[4])) or ("",))[0]
            r[13] = r[13] or platforms.get((str(r[0]), r[1], r[4]), "")
            theirs = (railway_platform(r[3], str(r[0]), r[5] or r[6] or None) or "") if r[3] else ""
            if theirs and (not r[13] or r[12] == 1):
                r[13] = theirs
        out.append(r)
    return out


def railway_specials(rows):
    """The rows, those at a railway station of a route that isn't the catalog's train as the railway's train their
    vehicle names. A train only Israel Railways' timetable runs comes in the stations' lists with its number for its
    route, the id of whatever route the ministry's timetable has by that number (train 8646 as route 8646, a bus
    line from Modiin Illit to Bnei Brak). It comes as the catalog's railway-only line
    for it (RAIL_OFFSET + number, rail_only_lines) where there is one; else, of a route the catalog has as something
    else, as a train of that number where the railway's own timetable has it at that station (railway_held), and not at
    all where it doesn't. A route the catalog doesn't know, with no railway-only line for its vehicle, stays as it is."""
    platforms = railway_held["platforms"] if time.time() - railway_held["at"] <= RAILWAY_KEEP else {}
    out = []
    for r in rows:
        kind = (catalog["lines"].get(r[1]) or [None] * 7)[6]
        if kind != 2 and int(r[0]) in RAILWAY_IDS:
            _, _, rest = str(r[2]).partition("_")
            first, _, number = rest.partition("_")
            route = RAIL_OFFSET + int(number) if number.isdigit() and 0 < int(number) < NAIM_OFFSET - RAIL_OFFSET else None
            special = route is not None and (catalog["lines"].get(route) or [None] * 7)[6] == 2
            if special or kind is not None and route is not None and (number, str(r[0])) in platforms:
                r = [r[0], route, "%d_%s_%s" % (route, first, number), number] + list(r[4:])
            elif kind is not None:
                continue
        out.append(r)
    return out


def left_station(code, route, first, now):
    """Whether a train has gone by the station: its list had it, and the lists asked since, for TRAIN_DROPPED, haven't.
    A station's list drops a train about a minute before it gets there, and its reports can stick a station back while
    it runs on."""
    at = trains_held.get("listed", {}).get((str(code), route, first))
    return at is not None and trains_held["at"] - at >= TRAIN_DROPPED and now - trains_held["at"] <= TRAINS_KEEP


# The ministry's position for a train can stay where the train stood, freshly stamped, while it runs on: a third of the
# trains on the Live map can be 3 km or more from where they are, and trains under way that report no position at all
# aren't on it.
# Such positions are left out. A train's times hold, so every answer puts a train where they put it, as the app does
# on a line's page (KavLive.trainSpot): its time at the first station ahead (Kav's own where it has one, else the
# stations' lists') moves the timetable's clocks, and the train is that far between the station it last left and the
# next, in a straight line, the timetable having no track for trains. One that hasn't left its first station is there.
failed_places = {"error": None}


def train_places(now, doc=None, feed=None):
    """Where each train in the stations' lists (train_lists()) is by its times at now, by route and first departure:
    (its journey key, lat, lon, heading in degrees or None, whether it has left its first station, whether the lists have
    it under way: some list gives it no timetabled time, as they give a train once it is logged in). A train whose run,
    stations or times aren't known has none."""
    feed = live_feed if feed is None else feed
    if doc is None:
        doc = trains_held["doc"] if now - trains_held["at"] <= TRAINS_KEEP else None
    if not doc:
        return {}
    try:
        starting_at([], now)  # the stations' places (planner_stops), asked of the planner again when old
        monitored = doc["Siri"]["ServiceDelivery"].get("StopMonitoringDelivery") or []
        if isinstance(monitored, dict):
            monitored = [monitored]
        runs = {}
        for part in monitored:
            for v in part.get("MonitoredStopVisit") or []:
                try:
                    j = v["MonitoredVehicleJourney"]
                    call = j["MonitoredCall"]
                    line, start, day = j["LineRef"], j["OriginAimedDepartureTime"], j["FramedVehicleJourneyRef"]["DataFrameRef"]
                    route, first, order, due = int(line), epoch(start), int(call["Order"]), epoch(call["ExpectedArrivalTime"])
                except (KeyError, ValueError, TypeError):
                    continue
                named = str(call.get("StopPointRef") or "")
                if (catalog["lines"].get(route) or [None] * 7)[6] != 2 or not named.isdigit():
                    continue
                kav = predictions.at_stop(route, first, int(named), now)
                run = runs.setdefault((route, first), [f"{line}_{start}_{j.get('VehicleRef', '')}", line, start, day, False, []])
                run[4] = run[4] or not call.get("AimedArrivalTime")
                run[5].append((order, named, due if kav is None else int(kav)))

        def station(code):
            found = planner_stops["where"].get(code) or ()
            return (sum(p[0] for p in found) / len(found), sum(p[1] for p in found) / len(found)) if found else None
        places = {}
        for journey, (key, line, start, day, going, calls) in runs.items():
            set_out = datetime.datetime.fromisoformat(start)
            into = int((set_out - datetime.datetime.fromisoformat(day).replace(tzinfo=set_out.tzinfo)).total_seconds())
            copy = rail_copies_held["copies"].get((line, day))
            if copy is not None:
                # The planner's copy of the trip that day is the train (train_updates()).
                codes, clocks = copy[1], [t - copy[0] for t in copy[2]]
            else:
                codes, clocks = feed.stops_of(line, into, day), feed.clocks_of(line, into, day)
            if not codes or not clocks or len(codes) != len(clocks):
                continue
            found = next(((k, due) for order, named, due in sorted(calls, key=lambda c: c[2])
                          if (k := feed.placed(codes, order, named)) is not None), None)
            if found is None:
                continue
            # There at that time, now at the latest: a station's list has a train until a minute before it comes.
            k, due = found
            when = [max(due, now) + c - clocks[k - 1] for c in clocks]
            ahead = next(i for i, w in enumerate(when) if w >= now)
            if ahead == 0:
                at = station(codes[0])
                if at is not None:
                    places[journey] = (key, round(at[0], 6), round(at[1], 6), None, False, going)
                continue
            a, b = station(codes[ahead - 1]), station(codes[ahead])
            if a is None or b is None:
                continue
            f = min(1.0, (now - when[ahead - 1]) / max(1, when[ahead] - when[ahead - 1]))
            dx, dy = (b[1] - a[1]) * math.cos(math.radians(a[0])), b[0] - a[0]
            heading = round(math.degrees(math.atan2(dx, dy))) % 360 if dx or dy else None
            places[journey] = (key, round(a[0] + (b[0] - a[0]) * f, 6), round(a[1] + (b[1] - a[1]) * f, 6), heading, True, going)
        failed_places["error"] = None
        return places
    except Exception as e:
        if failed_places["error"] != repr(e):
            log("train places failing:", repr(e))
            failed_places["error"] = repr(e)
        return {}


def trains_placed(rows, now, places=None):
    """Snapshot rows (route, key, lat, lon, recorded, bearing, speed) with each train where its times put it at now
    (train_places()), heading that way, and the trains under way that report no position too."""
    places = train_places(now) if places is None else places
    if not places:
        return rows
    routes = {route for route, _ in places}
    out, seen = [], set()
    for r in rows:
        journey = None
        if r[0] in routes:
            try:
                journey = (r[0], epoch(r[1].split("_")[1]))
            except (IndexError, ValueError):
                pass
        p = places.get(journey)
        if p is None:
            out.append(r)
            continue
        seen.add(journey)
        out.append((r[0], r[1], p[1], p[2], int(now), p[3], None))
    # Not one still at its first station that reports no position, as now: logged in, it may not be at the platform yet.
    out += [(route, p[0], p[1], p[2], int(now), p[3], None) for (route, first), p in places.items()
            if (route, first) not in seen and p[4] and p[5]]
    return out


def trains_on_board(rows, now, places=None):
    """A board's rows (stops()) with each train where its times put it (train_places()), as on the map
    (trains_placed()): one with a position, and one under way that reports none."""
    if not any((catalog["lines"].get(r[1]) or [None] * 7)[6] == 2 for r in rows):
        return rows
    places = train_places(now) if places is None else places
    out = []
    for r in rows:
        p = places.get((r[1], r[4]))
        if p is not None and (r[7] is not None or p[4] and p[5]):
            r = r[:7] + [p[1], p[2], int(now), p[3], None] + r[12:]
        out.append(r)
    return out


def take_snapshot():
    errors = []
    for url, source in ((URL, "main"), (BACKUP, "backup")):
        if not url:
            continue
        try:
            doc = ask(url, "MonitoringRef=AllActiveTripsFilter&StopVisitDetailLevel=normal", 60)
            at, rows = visits(doc)
        except Exception as e:
            errors.append(f"{source}: {e}")
            continue
        # The test server sometimes hands out a snapshot from minutes ago.
        if source == "backup" and time.time() - at > 360:
            errors.append(f"{source}: {int(time.time() - at)} s old")
            continue
        recorder.feed(doc)
        live_feed.feed(doc, train_lists(time.time()))
        tram_lists(time.time())
        railway_due(time.time())
        return Snapshot(at, rows, source)
    raise IOError("; ".join(errors) or "no MOT_URL")


class Recorder:
    """When each bus reaches each stop, for the predicted times the planner rides on (otp/predict.py). The snapshot
    names the stop every bus is at or has just passed (its distance is how far along the route the bus is, not how far
    from the stop), so a bus whose stop has moved on reached the new one at about that report. Stops it reached between
    two reports are spaced evenly in between. One file a day under HISTORY, a line per stop reached: route, the
    ministry's journey ref, the day it belongs to, its first departure, the stop's place on the route, the stop's code
    where named (blank when spaced), and when. A report stamped ahead of now is left out: its times are wrong, and they
    would make every other bus look long gone."""

    FORGET = 1800  # a bus unseen this long has ended its trip
    # Unless it comes back where it stood: its clocks go on (forgotten). A bus whose reports stop and come back from the
    # same spot would otherwise stay on the boards, as just heard, where it never left.
    FORGOTTEN_KEEP = 3 * 3600
    BEHIND_MOST = 3  # reports running from behind where a bus was that make it be there

    def __init__(self, folder):
        self.folder = folder
        self.trips = {}
        self.reached = {}  # when each bus first named the stop it names now; not known for one first seen there
        self.naming = {}  # since when each bus has named the stop it names now, as far as heard: reached, or first seen
        self.behind = {}  # how many reports running each bus has sent from behind where it was
        # metres along its route (DistanceFromStop) where each bus was when first heard naming the stop it names now,
        # and where it is now
        self.along = {}
        self.heard = {}  # when each bus was first heard on its trip
        self.forgotten = {}  # each bus forgotten lately: (moved, heard, when)
        # The buses whose clocks run ahead, as trips has them but heard now: their times are wrong and stay out of the
        # passings, but where they are isn't (with_near).
        self.ahead = {}
        # Where each bus was and when it was last seen MOVED away from there; when None, not since it was first seen.
        # A bus logged into a trip it isn't running sits still (with_near).
        self.moved = {}
        # Each bus's places over the last TRACK_KEEP seconds, oldest first, ((when, lat, lon), ...), a tuple a reader can
        # take whole (passed_on_road()).
        self.track = {}
        # The place each bus reports, the times of its first and latest reports from that very place and how many came from
        # it: a GPS that froze sends the same coordinates with fresh times (quiet()).
        self.same = {}
        self.out = None
        self.day = None
        self.failed = None

    def feed(self, doc, clock=None):
        if not self.folder:
            return
        try:
            self._feed(doc, time.time() if clock is None else clock)
            self.failed = None
        except Exception as e:
            if self.failed != repr(e):
                log("history failing:", repr(e))
                self.failed = repr(e)

    def _feed(self, doc, clock):
        delivery = doc["Siri"]["ServiceDelivery"]
        monitored = delivery.get("StopMonitoringDelivery") or []
        if isinstance(monitored, dict):
            monitored = [monitored]
        rows = []
        latest = 0
        ahead = {}
        for part in monitored:
            for v in part.get("MonitoredStopVisit") or []:
                try:
                    j = v["MonitoredVehicleJourney"]
                    call = j["MonitoredCall"]
                    frame = j.get("FramedVehicleJourneyRef") or {}
                    # Some operators send 0 for every journey, so a trip is its route, first departure and bus.
                    key = (j["LineRef"], j["OriginAimedDepartureTime"], j["VehicleRef"])
                    ref, day = frame.get("DatedVehicleJourneyRef", ""), frame.get("DataFrameRef", "")
                    order, seen = int(call["Order"]), epoch(v["RecordedAtTime"])
                    try:
                        metres = int(float(call.get("DistanceFromStop")))
                    except (TypeError, ValueError):
                        metres = None
                    self.note_place(key, j.get("VehicleLocation"), clock, seen)
                    if seen - clock > AHEAD:
                        was = self.ahead.get(key)
                        if was is not None and order > was[0][0]:
                            self.reached[key] = int(clock)
                        if was is None or order != was[0][0]:
                            self.naming[key] = int(clock)
                        ahead[key] = ((order, int(clock), call.get("StopPointRef", "")), ref, day)
                        continue
                    now = (order, seen, call.get("StopPointRef", ""))
                    latest = max(latest, seen)
                except (KeyError, ValueError, TypeError):
                    continue
                last = self.trips.get(key)
                if last is not None and seen <= last[0][1]:
                    continue
                # A report from behind where the bus already was is a glitch, and changes nothing; BEHIND_MOST running, it
                # was the report it was ahead by: a bus can name its last stop just after setting out, and every report
                # after that would be left out.
                if last is not None and order < last[0][0]:
                    self.behind[key] = self.behind.get(key, 0) + 1
                    if self.behind[key] >= self.BEHIND_MOST:
                        self.trips[key] = (now, ref, day)
                        self.naming[key] = int(min(seen, clock))
                        self.reached.pop(key, None)
                        self.along.pop(key, None)
                        self.behind.pop(key)
                    continue
                self.behind.pop(key, None)
                if last is None:
                    self.heard[key] = seen
                    gone, spot = self.forgotten.pop(key, None), (self.moved.get(key) or (None,))[0]
                    if gone is not None and gone[0] is not None and gone[1] is not None and spot is not None and math.hypot(
                            (spot[0] - gone[0][0][0]) * 111_320,
                            (spot[1] - gone[0][0][1]) * 111_320 * math.cos(math.radians(spot[0]))) <= self.MOVED_FAR:
                        self.moved[key], self.heard[key] = gone[0], gone[1]
                self.trips[key] = (now, ref, day)
                if metres is not None:
                    was = self.along.get(key)
                    self.along[key] = (metres if was is None or last is None or order != last[0][0] else was[0], metres)
                if last is None or order != last[0][0]:
                    self.naming[key] = int(min(seen, clock))
                if last is None or order == last[0][0]:
                    continue
                self.reached[key] = seen
                (order0, seen0, _), ref, day = last
                line, start = key[0], key[1]
                # The stops reached since the last report, spaced evenly up to the one this report names.
                for k in range(order0 + 1, order + 1):
                    at = seen0 + (seen - seen0) * (k - order0) / (order - order0)
                    rows.append((line, ref, day, start, k, now[2] if k == order else "", round(at)))
        if latest:
            for key in [k for k, (now, _, _) in self.trips.items() if latest - now[1] > self.FORGET]:
                self.forgotten[key] = (self.moved.get(key), self.heard.get(key), latest)
                del self.trips[key]
                self.reached.pop(key, None)
                self.along.pop(key, None)
                self.heard.pop(key, None)
            for key in [k for k, (_, _, at) in self.forgotten.items() if latest - at > self.FORGOTTEN_KEEP]:
                del self.forgotten[key]
        self.ahead = ahead
        for kept in (self.moved, self.reached, self.naming, self.behind, self.along, self.heard, self.track, self.same):
            for key in [k for k in kept if k not in self.trips and k not in ahead]:
                del kept[key]
        if rows:
            self.write(rows)

    MOVED_FAR = 100  # metres a bus goes for it to count as moving
    TRACK_KEEP = 360  # seconds of each bus's places kept (track)

    def note_place(self, key, where, clock, seen=None):
        """Notes when the bus last went MOVED_FAR from where it was, and since when it has reported this very place."""
        try:
            spot = (float(where["Latitude"]), float(where["Longitude"]))
        except (TypeError, KeyError, ValueError):
            return
        if seen is not None and seen - clock <= AHEAD:
            was = self.same.get(key)
            if was is None or was[0] != spot:
                self.same[key] = (spot, seen, seen, 1)
            elif seen > was[2]:
                self.same[key] = (spot, was[1], seen, was[3] + 1)
        went = self.track.get(key) or ()
        if not went or went[-1][1:] != spot or clock - went[-1][0] >= 60:
            self.track[key] = tuple(p for p in went if clock - p[0] <= self.TRACK_KEEP) + ((int(clock), spot[0], spot[1]),)
        was = self.moved.get(key)
        if was is None:
            self.moved[key] = (spot, None)
        elif math.hypot((spot[0] - was[0][0]) * 111_320,
                        (spot[1] - was[0][1]) * 111_320 * math.cos(math.radians(spot[0]))) > self.MOVED_FAR:
            self.moved[key] = (spot, int(clock))

    def write(self, rows):
        for row in rows:
            day = datetime.datetime.fromtimestamp(row[6], ISRAEL).strftime("%Y-%m-%d")
            # Only ever forward: a stop reached before midnight but heard after it goes in today's file, since going back
            # would reopen a day already put away (every reader takes the day from the row, not the file's name).
            if self.day is None or day > self.day:
                self.rotate(day)
            self.out.write("\t".join(str(x) for x in row) + "\n")
        self.out.flush()

    def rotate(self, day):
        if self.out:
            self.out.close()
        os.makedirs(self.folder, exist_ok=True)
        self.out = open(os.path.join(self.folder, day + ".tsv"), "a", encoding="utf-8")
        self.day = day
        threading.Thread(target=self.tidy, args=(day,), daemon=True).start()

    def tidy(self, today):
        """Days that are over are kept compressed, and only for KEEP_DAYS."""
        oldest = (datetime.date.fromisoformat(today) - datetime.timedelta(days=KEEP_DAYS)).isoformat()
        for name in sorted(os.listdir(self.folder)):
            day, _, rest = name.partition(".")
            path = os.path.join(self.folder, name)
            if day < oldest:
                os.remove(path)
            elif rest == "tsv" and day < today:
                # A day put away before keeps its rows: these are added to them, never written over them.
                with open(path + ".gz.part", "wb") as out:
                    if os.path.exists(path + ".gz"):
                        with open(path + ".gz", "rb") as old:
                            shutil.copyfileobj(old, out)
                    with open(path, "rb") as f, gzip.open(out, "wb") as g:
                        shutil.copyfileobj(f, g)
                os.replace(path + ".gz.part", path + ".gz")
                os.remove(path)


ISRAEL = zoneinfo.ZoneInfo("Asia/Jerusalem")  # a bus's day, whatever the server's clock
recorder = Recorder(HISTORY)


def pb_varint(n):
    out = bytearray()
    n &= (1 << 64) - 1
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def pb_field(number, value):
    """One protobuf field: a whole number as a varint, text and nested messages length-prefixed."""
    if isinstance(value, int):
        return pb_varint(number << 3) + pb_varint(value)
    raw = value.encode() if isinstance(value, str) else value
    return pb_varint(number << 3 | 2) + pb_varint(len(raw)) + raw


class Predictions:
    """Kav's own predicted arrivals (eta/eta.py writes them every half minute) for the buses on their way: each
    journey's stops ahead by route and first departure, as [place on the trip, stop code, when]. They beat the
    ministry's expected times at every distance; once they're more than OLD old the ministry's stand again."""

    OLD = 120

    def __init__(self, path):
        self.path, self.read_at, self.runs, self.at = path, 0, {}, 0

    def runs_now(self, now):
        if not self.path:
            return {}
        try:
            changed = os.path.getmtime(self.path)
            if changed != self.read_at:
                with open(self.path) as f:
                    got = json.load(f)
                self.runs, self.at, self.read_at = got["runs"], got["at"], changed
        except (OSError, ValueError, KeyError):
            return {}
        return self.runs if now - self.at <= self.OLD else {}

    def ahead(self, route, first, now):
        """The journey's predicted stops, earliest first."""
        return self.runs_now(now).get("%s_%d" % (route, first), ())

    def at_stop(self, route, first, code, now):
        """When the bus gets to that stop, or None. A stop it passes twice: the next time."""
        times = [eta for _, c, eta in self.ahead(route, first, now) if c == code]
        return min(times) if times else None


predictions = Predictions(ETA)


PREVIEW = 5400  # how far ahead a stop's arrivals go, as the ministry is asked for them (PT90M)


def with_coming(rows, codes, fresh, now, naming=None, trips=None):
    """board()'s rows, and the buses on their way to those stops that the ministry's list leaves out: Kav's own
    predictions have them there, and the snapshot has where each bus last reported. Only a bus reporting lately comes
    in. The ministry's lists leave out about a tenth of the buses due within half an hour, most of them due within two
    minutes. Not a bus off its trip (off_trip), nor a train gone by the station (left_station, train_by_now), nor one
    whose reports have stood still that Israel Railways has elsewhere or not running (railway_stopped). A train reporting
    no place that the station's list had and has just dropped comes too, while its reports have it short of the station
    or at it for TRAIN_GONE: a station's list drops a train about a minute before it comes."""
    if fresh is None:
        return rows
    naming = recorder.naming if naming is None else naming
    trips = recorder.trips if trips is None else trips
    gone, runs = {}, None
    wanted = {str(c) for c in codes}
    have = {(str(r[0]), r[1], r[4]) for r in rows}
    buses, out = {}, list(rows)
    for key, stops in predictions.runs_now(now).items():
        route, first = (int(x) for x in key.rsplit("_", 1))
        for k, (place, code, eta) in enumerate(stops):
            if str(code) not in wanted or (str(code), route, first) in have or not now <= eta <= now + PREVIEW:
                continue
            if route not in buses:
                buses[route] = {}
                for row in fresh.routes.get(route, []):
                    try:
                        if reporting(row[3], now):
                            trip = epoch(row[0].split("_")[1])
                            if trip not in buses[route] or lately(row, naming) > lately(buses[route][trip], naming):
                                buses[route][trip] = row
                    except (IndexError, ValueError, TypeError):
                        continue
            bus = buses[route].get(first)
            if bus is None:
                if (catalog["lines"].get(route) or [None] * 7)[6] != 2:
                    continue
                if runs is None:
                    runs = {}
                    for run, state in list(trips.items()):
                        try:
                            if (catalog["lines"].get(int(run[0])) or [None] * 7)[6] == 2:
                                runs[(int(run[0]), epoch(run[1]))] = (run, state[0])
                        except (TypeError, ValueError):
                            continue
                # Where its reports have it, by station: one Kav's times have after this one, it's past this one.
                run, (_, seen, named) = runs.get((route, first)) or (None, (None, 0, None))
                if run is None or now - seen > OLD_POSITION or str(named) in {str(c) for _, c, _ in stops[k + 1:]} \
                        or str(named) == str(code) and now - naming.get(run, now) > TRAIN_GONE \
                        or (str(code), route, first) not in trains_held.get("listed", {}) or left_station(code, route, first, now):
                    continue
                # Its reports can stop a station back while it goes on: Israel Railways' own place for it settles that.
                if k + 1 < len(stops) and railway_passed(str(run[2]), code, stops[k + 1][1], now):
                    continue
                if railway_stopped(str(run[2]), code, named, naming.get(run), eta, now, stops[k + 1][1] if k + 1 < len(stops) else None):
                    continue
                number = (catalog["lines"].get(route) or [""])[0]
                out.append([int(code), route, "_".join(run), number, first, 0, int(eta), None, None, 0, None, None, place, ""])
                have.add((str(code), route, first))
                continue
            vehicle, lat, lon, at, bearing, speed = bus
            if (route, first) not in gone:
                gone[(route, first)] = off_trip(route, naming.get(tuple(str(vehicle).split("_", 2))), lat, lon, now)
            if gone[(route, first)] or (catalog["lines"].get(route) or [None] * 7)[6] == 2 and (
                    left_station(code, route, first, now) or train_by_now(code, lat, lon, at, speed, now)):
                continue
            heard = tuple(str(vehicle).split("_", 2))
            if (catalog["lines"].get(route) or [None] * 7)[6] == 2 and railway_stopped(
                    heard[-1], code, (trips.get(heard) or ((None, None, None),))[0][2], naming.get(heard), eta, now,
                    stops[k + 1][1] if k + 1 < len(stops) else None):
                continue
            number = (catalog["lines"].get(route) or [""])[0]
            out.append([int(code), route, vehicle, number, first, 0, int(eta), lat, lon, at, bearing, speed, place, ""])
            have.add((str(code), route, first))
    return out


# A bus about to set out is often missing from the ministry's lists for its first stops, and a card planned on that bus
# would show the one after it.
STARTING = 600  # how far ahead a bus not yet out comes from the timetable
# And a train at the station it starts from, from up to half an hour before.
STARTING_TRAIN = 1800
# A light rail tram is on no list for a few minutes as it sets out: the stops' lists drop it about two minutes before its
# time and Kav's own times have it once it is seen moving, three or four minutes after, though it is due all along.
# One setting out, from TRAM_SETTING_OUT[0] before its time to [1] after, that a stop's list had within TRAM_LISTED and
# no longer has, comes from the timetable at that stop, due there from now on. Jerusalem's light rail, which no list
# has, never comes this way.
TRAM_SETTING_OUT = (180, 300)
TRAM_LISTED = 600
# (stop code, route, first departure) -> when that stop's list last had the tram (note_trams: from board() and, for every
# light rail stop each minute, tram_lists())
trams_listed = {}
STARTING_TTL = 120  # a stop's timetable is asked again after this
PLANNER_STOPS_TTL = 6 * 3600  # the planner's stops by code, asked again after this
BUSES = (3, 711)  # route types: buses and shuttles
# A bus waiting at its stop is there in the ministry's positions, though its list for the stop gives it no place, and
# it is shown live.
AT_STOP = 150  # metres from the stop that a bus is at it
NEAR_STOP = 60  # metres from the stop within which a bus at it is there whatever it has done (gone_by())
PAST_STOP = 50  # metres along its route since it named the stop that a bus further off than NEAR_STOP has left it
LATE_START = 600  # a bus still at the stop this long after its time hasn't set out yet
# A bus waiting at its trip's first stop is often further off than AT_STOP, at the terminal, and nearly always sets out
# (waiting()).
FIRST_NEAR = 400  # metres from its trip's first stop that a bus naming it is there or leaving it
SET_OUT = 50  # metres along its route past which a bus has set out
BOARD_AHEAD = 5400  # how far ahead the ministry's list for a stop goes (board()'s PreviewInterval)
TIMETABLE_MOST = 1000  # departures asked of the planner a stop; a stop with this many may have more
LATER = 3  # departures a route boarding at a stop comes with at least, the ones past BOARD_AHEAD from the timetable
LATER_AHEAD = 86400  # how far past BOARD_AHEAD they're asked for
LATER_TTL = 600  # a stop's are asked again after this
# A trip a bus has been heard on that set out longer ago than this doesn't come from the timetable at a stop whose board
# has nothing of its route (with_later): its time there by the timetable has likely gone by, where the
# planner's is predicted, later. One no bus has been heard on, of a line the ministry has no live data of, comes until
# its time there, by its timetable.
SET_OUT_MOST = 1200
# stop code -> (when asked, [(route, first departure, when there, place on the trip, trip's number, boarded there, when
# there by the planner's live updates)])
starting_held = {}
later_held = {}  # stop code -> (when asked, [(route, first departure, when there, place on the trip)]), past BOARD_AHEAD
later_failed = {"at": 0}  # when the planner last failed to say those
planner_stops = {"at": 0, "failed": 0, "ids": {}, "where": {}}
starting_lock = threading.Lock()


def starting_at(codes, now, ask=None):
    """The planner's timetable at those stops from LATE_START ago to BOARD_AHEAD seconds or so ahead, by stop code, each
    stop asked at most once per STARTING_TTL. Nothing for a minute after the planner fails to answer."""
    ask = ask or ask_graphql
    with starting_lock:
        if now - planner_stops["failed"] < 60:
            return {}
        stale = now - planner_stops["at"] > PLANNER_STOPS_TTL
        held = {c: starting_held.get(c) for c in codes}
    try:
        if stale:
            ids, where = {}, {}
            for s in ask("{ stops { gtfsId code lat lon } }").get("stops") or []:
                ids.setdefault(str(s.get("code")), []).append(s["gtfsId"])
                if s.get("lat") is not None and s.get("lon") is not None:
                    where.setdefault(str(s.get("code")), []).append((s["lat"], s["lon"]))
            with starting_lock:
                planner_stops.update(at=now, ids=ids, where=where)
        missing = [c for c, hit in held.items() if hit is None or now - hit[0] >= STARTING_TTL]
        asked = [i for c in missing for i in planner_stops["ids"].get(c, [])]
        found = {c: [] for c in missing}
        if asked:
            doc = ask("{ stops(ids: %s) { code stoptimesWithoutPatterns(startTime: %d, timeRange: %d, numberOfDepartures: %d, "
                      "omitNonPickups: false) { scheduledDeparture realtimeDeparture serviceDay stopPosition pickupType "
                      "trip { route { gtfsId } tripShortName departureStoptime { scheduledDeparture } } } } }" % (
                          json.dumps(asked), now - LATE_START, LATE_START + BOARD_AHEAD + STARTING_TTL, TIMETABLE_MOST))
            for s in doc.get("stops") or []:
                for t in s.get("stoptimesWithoutPatterns") or []:
                    try:
                        route = int(t["trip"]["route"]["gtfsId"].split(":", 1)[1])
                        first = t["serviceDay"] + t["trip"]["departureStoptime"]["scheduledDeparture"]
                        found.setdefault(str(s["code"]), []).append((route, first, t["serviceDay"] + t["scheduledDeparture"],
                                                                      t["stopPosition"], t["trip"].get("tripShortName") or "",
                                                                      t.get("pickupType") != "NONE",
                                                                      t["serviceDay"] + t.get("realtimeDeparture", t["scheduledDeparture"])))
                    except (KeyError, TypeError, ValueError, IndexError):
                        continue
    except Exception:
        with starting_lock:
            planner_stops["failed"] = now
        return {}
    with starting_lock:
        for c in missing:
            held[c] = starting_held[c] = (now, found.get(c, []))
        if len(starting_held) > MAX_STOPS_KEPT:
            for c, _ in sorted(starting_held.items(), key=lambda kv: kv[1][0])[:len(starting_held) - MAX_STOPS_KEPT]:
                starting_held.pop(c, None)
    return {c: hit[1] for c, hit in held.items() if hit}


def buses_of(fresh, now, naming=None):
    """The snapshot's buses reporting lately, by route and first departure: (vehicle key, lat, lon, at, bearing, speed).
    Of two logged into one trip, the one that named a new stop last (lately())."""
    naming = recorder.naming if naming is None else naming
    found = {}
    for route, held in (fresh.routes.items() if fresh is not None else ()):
        for row in held:
            try:
                if reporting(row[3], now):
                    trip = (route, epoch(row[0].split("_")[1]))
                    if trip not in found or lately(row, naming) > lately(found[trip], naming):
                        found[trip] = row
            except (IndexError, ValueError, TypeError):
                continue
    return found


def lately(row, naming):
    """When a snapshot row's bus last named a new stop, and when it reported: two buses can be logged into one trip, one
    of them running another, which would hide the trip's own bus."""
    return naming.get(tuple(str(row[0]).split("_", 2)), 0), row[3]


def at_stop(bus, code, within=AT_STOP):
    """Whether the bus is at that stop, by the planner's place for it."""
    return bus is not None and any(
        math.hypot((bus[1] - lat) * 111_320, (bus[2] - lon) * 111_320 * math.cos(math.radians(lat))) <= within
        for lat, lon in planner_stops["where"].get(str(code), ()))


def waiting(bus, place, trips=None, along=None):
    """Whether the bus, naming its trip's first stop and within FIRST_NEAR of it, is there or leaving it, for the trip's
    rows at its first two stops: at the first itself only while not SET_OUT along its route (Recorder.along)."""
    trips = recorder.trips if trips is None else trips
    along = recorder.along if along is None else along
    if bus is None or place not in (1, 2):
        return False
    key = tuple(str(bus[0]).split("_", 2))
    state = trips.get(key)
    if state is None or state[0][0] != 1 or not at_stop(bus, state[0][2], FIRST_NEAR):
        return False
    on = along.get(key)
    return place == 2 or on is not None and on[1] <= SET_OUT


def with_starting(rows, codes, now, ask=None, fresh=None, buses=None):
    """rows, and the buses not yet out due at those stops within STARTING that they leave out, from the planner's
    timetable (where a bus is due when it hasn't set out): a row without a bus, its trip named as the ministry names it;
    one already due that hasn't set out, still at the stop LATE_START after its time, due now with its bus's place. A train
    leaving the station it starts from, within STARTING_TRAIN, the same way, named by its number, but none after its
    time: the stations' lists have a train until it leaves, so one they leave out has gone, its place often stuck at the
    station. The city's weekend lines (NAIM_OFFSET up), and the trains only Israel Railways' timetable has (RAIL_OFFSET
    up), are in none of the ministry's lists, out or not: theirs come all along their way and as far ahead as those lists
    go, by their timetable. A light rail tram setting out that a stop's list had lately comes at
    that stop too, as far ahead as the lists go (TRAM_SETTING_OUT), with its place and no aimed time once it has set out
    and its vehicle reports, as the list's own rows of it come. A bus waiting at its first stop further off than AT_STOP
    gives its row there and at the stop after its place, due at its time at the earliest (waiting()), and keeps it after
    its time as long as one at the stop would."""
    have = {(str(r[0]), r[1], r[4]) for r in rows}
    out = list(rows)
    buses = buses_of(fresh, now) if buses is None else buses
    for code, trips in starting_at([str(int(c)) for c in codes], now, ask).items():
        for route, first, at, place, number, boarded, _ in trips:
            line = catalog["lines"].get(route)
            if line is None or not boarded or (code, route, first) in have:
                continue
            unlisted, since = route >= RAIL_OFFSET, 120
            if line[6] in BUSES:
                ahead, late = BOARD_AHEAD if unlisted else STARTING, LATE_START
            elif line[6] == 2 and (at == first or unlisted):
                ahead, late, line = BOARD_AHEAD if unlisted else STARTING_TRAIN, 60, [number or line[0]] + line[1:]
            elif line[6] == 0 and first - TRAM_SETTING_OUT[0] <= now <= first + TRAM_SETTING_OUT[1] and at >= now \
                    and now - trams_listed.get((code, route, first), 0) <= TRAM_LISTED:
                ahead, late, since = BOARD_AHEAD, LATE_START, TRAM_SETTING_OUT[1]
            else:
                continue
            bus = buses.get((route, first))
            here = at_stop(bus, code)
            waits = not here and line[6] in BUSES and waiting(bus, place)
            if not ((first >= now - since or unlisted) and now - 60 <= at <= now + ahead or here and at >= now - late
                    or waits and now - late <= at <= now + ahead):
                continue
            start = datetime.datetime.fromtimestamp(first, ISRAEL).isoformat()
            if here or waits:
                vehicle, lat, lon, seen, bearing, speed = bus
                # At its first stop it leaves on time at the earliest, and comes at the next at its time at the earliest;
                # further on, a bus at the stop is due now, early or not.
                due = max(at, int(now)) if place == 1 or waits else int(now)
                out.append([int(code), route, vehicle, line[0], first, at, due, lat, lon, seen, bearing, speed, place, ""])
            elif line[6] == 0 and bus is not None and first <= now:
                vehicle, lat, lon, seen, bearing, speed = bus
                out.append([int(code), route, vehicle, line[0], first, 0, at, lat, lon, seen, bearing, speed, place, ""])
            else:
                out.append([int(code), route, "%d_%s_" % (route, start), line[0], first, at, at, None, None, 0, None, None, place, ""])
            have.add((code, route, first))
    return out


def later_at(codes, now, ask=None):
    """The planner's next LATER departures of each of its patterns at those stops past BOARD_AHEAD, as far as
    LATER_AHEAD, by stop code, each stop asked at most once per LATER_TTL. Nothing for a minute after the planner fails
    to answer; failing to say these, it is still asked for the others (starting_at)."""
    ask = ask or ask_graphql
    with starting_lock:
        if now - max(planner_stops["failed"], later_failed["at"]) < 60:
            return {}
        held = {c: later_held.get(c) for c in codes}
        ids = planner_stops["ids"]
    missing = [c for c, hit in held.items() if hit is None or now - hit[0] >= LATER_TTL]
    asked = [i for c in missing for i in ids.get(c, [])]
    found = {c: [] for c in missing}
    try:
        if asked:
            doc = ask("{ stops(ids: %s) { code stoptimesForPatterns(startTime: %d, timeRange: %d, numberOfDepartures: %d, "
                      "omitNonPickups: true) { pattern { route { gtfsId } } stoptimes { scheduledDeparture serviceDay "
                      "stopPosition trip { departureStoptime { scheduledDeparture } } } } } }" % (
                          json.dumps(asked), now + BOARD_AHEAD, LATER_AHEAD, LATER))
            for s in doc.get("stops") or []:
                for p in s.get("stoptimesForPatterns") or []:
                    for t in p.get("stoptimes") or []:
                        try:
                            found.setdefault(str(s["code"]), []).append((
                                int(p["pattern"]["route"]["gtfsId"].split(":", 1)[1]),
                                t["serviceDay"] + t["trip"]["departureStoptime"]["scheduledDeparture"],
                                t["serviceDay"] + t["scheduledDeparture"], t["stopPosition"]))
                        except (KeyError, TypeError, ValueError, IndexError):
                            continue
    except Exception:
        with starting_lock:
            later_failed["at"] = now
        return {}
    with starting_lock:
        for c in missing:
            held[c] = later_held[c] = (now, found.get(c, []))
        if len(later_held) > MAX_STOPS_KEPT:
            for c, _ in sorted(later_held.items(), key=lambda kv: kv[1][0])[:len(later_held) - MAX_STOPS_KEPT]:
                later_held.pop(c, None)
    return {c: hit[1] for c, hit in held.items() if hit}


def with_later(rows, codes, now, ask=None, followed=None, buses=None):
    """rows, and at each stop every route boarding there with fewer than LATER rows, the next of its departures past
    BOARD_AHEAD to make LATER, from the planner's timetable, without a bus: the ministry's lists go no further, where
    a board shows a line's next three however far off. Not a train: each of a line's runs is a route of its own. A
    route the board has nothing of comes so from now on, by its timetable, but for trips a bus has
    been heard on that set out over SET_OUT_MOST ago, those a bus of it has passed the stop or is at (Recorder.trips), and
    those the live updates call off. A trip of a route the board does have rows of comes so too when a bus has been
    heard on it and is silent now (not among buses, buses_of()'s) within those limits, by its timetable. And a
    route the board has fewer than LATER rows of gets its timetable's next trips after its last row within the window
    too, within those limits: the ministry's list can leave them out."""
    followed = recorder.trips if followed is None else followed
    have, trips, last = {}, {(str(r[0]), r[1], r[4]) for r in rows}, {}
    for r in rows:
        have[(str(r[0]), r[1])] = have.get((str(r[0]), r[1]), 0) + 1
        last[(str(r[0]), r[1])] = max(last.get((str(r[0]), r[1]), 0), r[5] or r[6] or 0)
    out = list(rows)
    listed, reached, off = set(have), {}, live_feed.called_off
    for (line, start, _), ((order, _, _), _, _) in list(followed.items()):
        try:
            reached[(int(line), epoch(start))] = max(reached.get((int(line), epoch(start)), 0), order)
        except (TypeError, ValueError):
            continue
    for code, timetable in starting_at([str(int(c)) for c in codes], now, ask).items():
        for route, first, at, place, number, boarded, _ in sorted(timetable, key=lambda t: t[2]):
            line = catalog["lines"].get(route)
            silent = buses is not None and (code, route) in listed and (route, first) in reached and (route, first) not in buses
            fill = (code, route) in listed and have.get((code, route), 0) < LATER and at > last.get((code, route), 0)
            if line is None or line[6] == 2 or not boarded \
                    or ((code, route) in listed or have.get((code, route), 0) >= LATER) and not silent and not fill \
                    or not now - 60 <= at <= now + BOARD_AHEAD or now - first > SET_OUT_MOST and (route, first) in reached \
                    or reached.get((route, first), 0) >= place \
                    or (route, first) in off or (code, route, first) in trips:
                continue
            start = datetime.datetime.fromtimestamp(first, ISRAEL).isoformat()
            out.append([int(code), route, "%d_%s_" % (route, start), line[0], first, at, at, None, None, 0, None, None, place, "", None])
            have[(code, route)] = have.get((code, route), 0) + 1
            trips.add((code, route, first))
    for code, timetable in later_at([str(int(c)) for c in codes], now, ask).items():
        for route, first, at, place in sorted(timetable, key=lambda t: t[2]):
            line = catalog["lines"].get(route)
            if line is None or line[6] == 2 or at <= now + BOARD_AHEAD or have.get((code, route), 0) >= LATER \
                    or (code, route, first) in trips:
                continue
            start = datetime.datetime.fromtimestamp(first, ISRAEL).isoformat()
            out.append([int(code), route, "%d_%s_" % (route, start), line[0], first, at, at, None, None, 0, None, None, place, "", None])
            have[(code, route)] = have.get((code, route), 0) + 1
            trips.add((code, route, first))
    return out


OFF_ROUTE = 1000  # metres from its route's road that a bus is off it (off_trip)
STUCK = 300  # seconds naming the same stop after which a bus off its route isn't running its trip (off_trip)


@functools.lru_cache(maxsize=64)
def road(text):
    """An encoded path's points, each latitude then longitude."""
    return array.array("d", (x for point in decode(text) for x in point))


def off_route(route, lat, lon):
    """How far a place is from its route's road (catalog["shapes"]), in metres; 0 for a route without one."""
    text = catalog["shapes"].get(route)
    points = road(text) if text else ()
    k = 111_320 * math.cos(math.radians(lat))
    px, py, best = lon * k, lat * 111_320, None
    for i in range(0, len(points) - 3, 2):
        ay, ax, by, bx = points[i] * 111_320, points[i + 1] * k, points[i + 2] * 111_320, points[i + 3] * k
        dx, dy = bx - ax, by - ay
        t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        d = math.hypot(px - ax - t * dx, py - ay - t * dy)
        best = d if best is None or d < best else best
    return best or 0


def off_trip(route, since, lat, lon, now):
    """Whether a bus isn't running its trip: it has named the same stop for STUCK (since when it first did) and is over
    OFF_ROUTE from its route's road. Nearly every bus past its first stop is within 200 m of its route's road; one far
    off it that keeps naming the same stop would otherwise show as coming to a stop kilometres away."""
    return since is not None and now - since >= STUCK and off_route(route, lat, lon) > OFF_ROUTE


FASTEST = 25  # metres a second, 90 km/h: no bus gets to a stop sooner than its distance at this speed (with_near)
# A train names a station from about 90 s before it gets there until about 90 s before the next: one that named it this
# long ago and is further than TRAIN_BY from it has left (with_near).
TRAIN_GONE = 120
TRAIN_BY = 400
# A train Kav brings in itself whose last report had it heading on at a speed that would have taken it to the station
# over TRAIN_LAPSE ago, time enough to slow down and stand its minute there, has gone by: its reports stopped.
TRAIN_LAPSE = 150


def train_by_now(code, lat, lon, at, speed, now):
    """Whether a train's last report, made at at going speed km/h, has it at the station TRAIN_LAPSE ago or more had it
    gone straight on. Standing, or with no place for it or the station, it can't be told."""
    if not speed or lat is None or lon is None or at is None:
        return False
    away = min((math.hypot((lat - y) * 111_320, (lon - x) * 111_320 * math.cos(math.radians(y)))
                for y, x in planner_stops["where"].get(str(code), ())), default=None)
    return away is not None and now - at > away / (speed / 3.6) + TRAIN_LAPSE


# A tram names the stop it is at or has just passed, as a bus does: one that named it over DWELL_MOST ago and is further
# than AT_STOP from it has left (with_near).


def with_near(rows, codes, now, ask=None, fresh=None, buses=None, trips=None, ahead=None, moved=None, reached=None):
    """rows, and every bus reporting lately and moving on its way to those stops that they still leave out: the
    ministry's list drops a bus about to come, and Kav's own times miss some runs or have gone by while the bus is still a
    stop away. It comes at the planner's live time there, due now at the earliest; the snapshot's place for the bus goes
    into the planner's numbering of the trip's stops, as the live updates do. Only a bus seen going along its trip's
    stops (Recorder.reached) and moving lately (MOVED_LATELY): one parked, or logged into a trip it isn't running, would
    come "due now" hours late. Never sooner than the bus could get there at FASTEST in a straight line. A train still
    naming the station TRAIN_GONE after it first did and over TRAIN_BY from it has left, nor one whose station hasn't
    changed that Israel Railways has elsewhere or not running (railway_stopped). A train comes with no aimed time, as the
    ministry's own train rows have none, so the app keeps it under way when it drops the train's position as too far
    off."""
    trips = recorder.trips if trips is None else trips
    ahead = recorder.ahead if ahead is None else ahead
    moved = recorder.moved if moved is None else moved
    reached = recorder.reached if reached is None else reached
    buses = buses_of(fresh, now) if buses is None else buses
    have = {(str(r[0]), r[1], r[4]) for r in rows}
    on_road = {}
    for key, ((order, seen, named), _, day) in list(trips.items()) + list(ahead.items()):
        line, start, _ = key
        went = (moved.get(key) or (None, None))[1]
        try:
            if now - seen <= OLD_POSITION and key in reached and went is not None and now - went <= MOVED_LATELY:
                on_road[(int(line), epoch(start))] = (line, start, order, named, day, reached[key])
        except (TypeError, ValueError):
            continue
    out = list(rows)
    for code, timetable in starting_at([str(int(c)) for c in codes], now, ask).items():
        for route, first, at, place, number, boarded, live in timetable:
            bus = buses.get((route, first))
            if not boarded or bus is None or (code, route, first) in have or (route, first) not in on_road:
                continue
            line, start, order, named, day, since = on_road[(route, first)]
            try:
                set_out = datetime.datetime.fromisoformat(start)
                into = int((set_out - datetime.datetime.fromisoformat(day).replace(tzinfo=set_out.tzinfo)).total_seconds())
            except (TypeError, ValueError):
                continue
            stops = live_feed.stops_of(line, into, day)
            if stops and named:
                order = live_feed.placed(stops, order, str(named))
            if order is None or order > place:
                continue
            vehicle, lat, lon, seen, bearing, speed = bus
            away = min((math.hypot((lat - y) * 111_320, (lon - x) * 111_320 * math.cos(math.radians(y)))
                        for y, x in planner_stops["where"].get(str(code), ())), default=0)
            kind = (catalog["lines"].get(route) or [None] * 7)[6]
            if order == place and (kind == 2 and now - since > TRAIN_GONE and away > TRAIN_BY
                                   or kind == 0 and now - since > DWELL_MOST and away > AT_STOP):
                continue
            if off_trip(route, since, lat, lon, now) or kind == 2 and (left_station(code, route, first, now)
                                                                       or train_by_now(code, lat, lon, seen, speed, now)):
                continue
            due = max(live, int(now + away / FASTEST))
            if kind == 2 and railway_stopped(str(vehicle).rsplit("_", 1)[-1], code, named, since, due, now,
                                             stops[place] if stops and place < len(stops) else None):
                continue
            out.append([int(code), route, vehicle, (catalog["lines"].get(route) or [number])[0], first, 0 if kind == 2 else at,
                        due, lat, lon, min(seen, int(now)), bearing, speed, place, ""])
            have.add((code, route, first))
    return out


def placed(rows, now, fresh=None, buses=None):
    """The rows of a bus the ministry's list gives no place for that the snapshot has at the stop, with that place, due
    now at the latest: such a bus shows live. A row whose place the snapshot has newer, for the same bus, gets
    that place, or it would show older than the snapshot has it."""
    buses = buses_of(fresh, now) if buses is None else buses
    out = []
    for r in rows:
        bus = buses.get((r[1], r[4]))
        if r[7] is None and at_stop(bus, r[0]):
            vehicle, lat, lon, seen, bearing, speed = bus
            r = r[:6] + [max(r[6], int(now)), lat, lon, seen, bearing, speed] + r[12:]
        elif r[7] is not None and bus is not None and bus[0] == r[2] and bus[3] > (r[9] or 0):
            vehicle, lat, lon, seen, bearing, speed = bus
            r = r[:7] + [lat, lon, seen, bearing, speed] + r[12:]
        elif r[7] is None and not r[5] and bus is not None and (catalog["lines"].get(r[1]) or [None] * 7)[6] in BUSES:
            # A bus under way the list gives no place for, reporting to the snapshot elsewhere: that place, its time as it
            # was, so it shows live.
            vehicle, lat, lon, seen, bearing, speed = bus
            r = r[:7] + [lat, lon, seen, bearing, speed] + r[12:]
        out.append(r)
    return out


def running(rows, feed=None):
    """The rows but those of the trips no bus is running (LiveFeed.missing): the ministry's list has them due at their
    timetable's time."""
    off = (feed or live_feed).called_off
    return [r for r in rows if r[7] is not None or (r[1], r[4]) not in off]


# A bus stays at a stop past its first this long at most: one that named the stop longer ago has left, the next stop not
# reached yet, whatever it reports from there since (a frozen GPS keeps reporting from the stop). The board drops
# it after that minute.
DWELL_MOST = 60
MOVED_LATELY = 900  # a bus not seen moving for this long is parked or not running its trip (with_near)


# A bus standing still this long, on a trip that set out as long ago and that no other bus is on, isn't running it
# (standing()): one alone on its trip that has stood this long short of its last stop doesn't move on, and showing it
# live at stops further on promises a bus that never comes. Another bus on the trip standing as long doesn't run it
# either; one standing as long beside one that is moving can still go on, so those stay.
STANDING = 1800


def standing(rows, now, trips=None, moved=None, heard=None):
    """The rows but those of a bus that has stood still for STANDING (Recorder.moved; since it was first heard if it
    never moved) on a trip that set out as long ago, reporting lately, no bus the snapshot has on the trip lately moving
    (or with no place to tell), still naming a stop before the row's."""
    trips = recorder.trips if trips is None else trips
    moved = recorder.moved if moved is None else moved
    heard = recorder.heard if heard is None else heard

    def still_since(k):
        went = moved.get(k)
        return None if went is None else went[1] if went[1] is not None else heard.get(k)
    moving = {}
    for k, state in list(trips.items()):
        since = still_since(k)
        if now - state[0][1] <= OLD_POSITION and (since is None or now - since < STANDING):
            moving[k[:2]] = moving.get(k[:2], 0) + 1
    out = []
    for r in rows:
        line, _, rest = str(r[2]).partition("_")
        first, _, vehicle = rest.partition("_")
        key = (line, first, vehicle)
        state, still = trips.get(key), still_since(key)
        if (vehicle and state is not None and still is not None and r[12] is not None and state[0][0] < r[12]
                and now - still >= STANDING and now - r[4] >= STANDING and now - state[0][1] <= OLD_POSITION
                and moving.get(key[:2], 0) == 0 and (catalog["lines"].get(r[1]) or [None] * 7)[6] in BUSES):
            continue
        out.append(r)
    return out


# A bus gone quiet: its GPS frozen (the very same place with fresh times) for FROZEN past its trip's first stop, or still at
# its trip's first stop NOT_SET_OUT after the trip's time, alone on its trip. Its trip then comes by the timetable:
# timetabled while the timetable's time at the stop is ahead, not at all once it has gone by. quiet() does the same with
# the ministry's own clock at each stop (otp/prepare.py's trip-patterns.json `timed`); without it, nothing. One still at
# its first stop last heard over LATELY_HEARD ago goes only once that time is STALE_GONE past.
FROZEN = 600
FROZEN_REPORTS = 3  # reports from that place at least: a bus silent and then heard again from where it stood isn't one
NOT_SET_OUT = 600
FIRST_ALONG = 250  # metres along its route within which a bus naming its trip's first stop hasn't set out
LATELY_HEARD = 90
STALE_GONE = 600


def official_time(row, feed=None):
    """When the row's trip is at its stop by the ministry's timetable, or None."""
    feed = live_feed if feed is None else feed
    try:
        first = datetime.datetime.fromisoformat(str(row[2]).split("_")[1])
    except (IndexError, ValueError):
        return None
    midnight = first.replace(hour=0, minute=0, second=0, microsecond=0)
    into = int((first - midnight).total_seconds())
    for seconds, day in ((into, midnight.date()), (into + 86400, midnight.date() - datetime.timedelta(days=1))):
        clock = feed.clock_at(str(row[1]), seconds, day.isoformat(), row[12], str(row[0]))
        if clock is not None:
            return int(first.timestamp()) + clock
    return None


def quiet(rows, now, trips=None, same=None, along=None, feed=None):
    """The rows, those of a bus gone quiet (see FROZEN) at a stop after the one it names as the timetable has them: at
    the timetable's time, white, while it is ahead; left out once it has gone by."""
    trips = recorder.trips if trips is None else trips
    same = recorder.same if same is None else same
    along = recorder.along if along is None else along
    heard = {}
    for k, state in list(trips.items()):
        if now - state[0][1] <= OLD_POSITION:
            heard[k[:2]] = heard.get(k[:2], 0) + 1
    out = []
    for r in rows:
        line, _, rest = str(r[2]).partition("_")
        first, _, vehicle = rest.partition("_")
        key = (line, first, vehicle)
        state, place, on = trips.get(key), same.get(key), along.get(key)
        if (not vehicle or state is None or r[12] is None or state[0][0] >= r[12] or heard.get(key[:2], 0) != 1
                or (catalog["lines"].get(r[1]) or [None] * 7)[6] not in BUSES):
            out.append(r)
            continue
        frozen = (state[0][0] > 1 and place is not None and place[2] - place[1] >= FROZEN and place[3] >= FROZEN_REPORTS
                  and now - place[2] <= LATELY_HEARD)
        waiting = state[0][0] == 1 and on is not None and on[1] <= FIRST_ALONG and now - r[4] >= NOT_SET_OUT
        due = official_time(r, feed) if frozen or waiting else None
        if due is None or not frozen and now - state[0][1] > LATELY_HEARD and now - STALE_GONE < due <= now:
            out.append(r)
        elif due > now:
            out.append(r[:5] + [due, due, None, None, 0, None, None] + r[12:])
    return out


# A white row (no place, a timetable time) of a trip the snapshot has no bus for, carrying a vehicle the Recorder doesn't
# follow, goes by the trip's only followed one too, unless its timetable has it at the stop over FOLLOWED_AHEAD from now:
# that one past the stop so early may be another trip's (gone_by()).
FOLLOWED_AHEAD = 600


SHAPE_TWICE = 150  # metres within which a road passing a place twice leaves where along it the place is unknown


def along_road(route, lat, lon):
    """Where a place is along its route's road (catalog["shapes"]): (metres along it, metres off it). None without a
    road, or where another stretch of it, apart along it from the nearest, passes within SHAPE_TWICE of the place too."""
    text = catalog["shapes"].get(route)
    points = road(text) if text else ()
    k = 111_320 * math.cos(math.radians(lat))
    px, py, run, hits = lon * k, lat * 111_320, 0.0, []
    for i in range(0, len(points) - 3, 2):
        ay, ax, by, bx = points[i] * 111_320, points[i + 1] * k, points[i + 2] * 111_320, points[i + 3] * k
        dx, dy = bx - ax, by - ay
        seg = math.hypot(dx, dy)
        t = 0.0 if seg == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (seg * seg)))
        hits.append((math.hypot(px - ax - t * dx, py - ay - t * dy), run + t * seg))
        run += seg
    if not hits:
        return None
    off, at = min(hits)
    if any(d <= SHAPE_TWICE and abs(s - at) > 2 * SHAPE_TWICE for d, s in hits):
        return None
    return at, off


# A bus whose reports name the same stop STUCK_ORDER while it drives on has passed stops its order hasn't reached: where it
# is along its road says so. Only for a bus reporting fresh (ROAD_FRESH), within ROAD_NEAR of its road, gone
# ROAD_PROGRESS along it in the last ROAD_RECENT, where its road passes neither it nor the stop twice.
STUCK_ORDER = 600
ROAD_FRESH, ROAD_NEAR = 120, 100
ROAD_RECENT, ROAD_PROGRESS = 300, 200
ROAD_PAST = 200


def passed_on_road(route, code, bus, now, track=None):
    """Whether the snapshot's bus is ROAD_PAST or more along its route's road beyond the stop (see STUCK_ORDER)."""
    track = recorder.track if track is None else track
    if now - bus[3] > ROAD_FRESH:
        return False
    went = [p for p in track.get(tuple(str(bus[0]).split("_", 2))) or () if now - p[0] <= ROAD_RECENT]
    stops = planner_stops["where"].get(str(code)) or ()
    here = along_road(route, bus[1], bus[2])
    then = along_road(route, went[0][1], went[0][2]) if went else None
    there = [along_road(route, lat, lon) for lat, lon in stops]
    if here is None or then is None or not there or None in there or here[1] > ROAD_NEAR:
        return False
    return here[0] - then[0] >= ROAD_PROGRESS and here[0] - max(s[0] for s in there) >= ROAD_PAST


def gone_by(rows, now, trips=None, reached=None, buses=None, along=None, naming=None, track=None):
    """The rows but those of buses past the stop: the snapshot has them at a later stop of their trip, or (it names the
    stop a bus is at or has just passed) at this one but reporting away from it, or having reached it over DWELL_MOST
    ago; never at a bus's first stop, nor a train (it names the station it's heading for). The ministry's list keeps a
    bus a while after it has passed, when it is no longer due there."""
    trips = recorder.trips if trips is None else trips
    reached = recorder.reached if reached is None else reached
    along = recorder.along if along is None else along
    naming = recorder.naming if naming is None else naming
    buses = {} if buses is None else buses
    # A trip can come logged by two vehicles, its row carrying one the snapshot doesn't follow while the snapshot has
    # the trip's bus as another: that one tells where the trip is. A white row with no bus in the snapshot goes by it too
    # (FOLLOWED_AHEAD).
    followed = {}
    for k in list(trips):
        followed.setdefault(k[:2], []).append(k)
    out = []
    for r in rows:
        line, _, rest = str(r[2]).partition("_")
        first, _, vehicle = rest.partition("_")
        key = (line, first, vehicle)
        state, bus = trips.get(key), buses.get((r[1], r[4]))
        white = bus is None and r[7] is None and r[5] and r[5] - now <= FOLLOWED_AHEAD
        if state is None and (white or bus is not None and bus[0] != r[2]) and len(followed.get(key[:2], ())) == 1:
            key = followed[key[:2]][0]
            state = trips.get(key)
        here = at_stop(bus, r[0])
        # Within AT_STOP but over NEAR_STOP off and PAST_STOP on along its route since it named the stop: it has left,
        # where a bus at a big stop's far bay hasn't moved on.
        on = along.get(key)
        if here and on and on[1] - on[0] >= PAST_STOP and not at_stop(bus, r[0], NEAR_STOP):
            here = False
        if state is not None and r[12] is not None and (state[0][0] > r[12] or (
                state[0][0] == r[12] > 1 and (catalog["lines"].get(r[1]) or [None] * 7)[6] in BUSES
                and planner_stops["where"].get(str(r[0])) and (
                    bus is not None and not here or now - reached.get(key, now) > DWELL_MOST))):
            continue
        # Its reports standing at a stop before this one while it drives on: where it is on its road (STUCK_ORDER).
        if (state is not None and bus is not None and r[12] is not None and state[0][0] < r[12]
                and (catalog["lines"].get(r[1]) or [None] * 7)[6] in BUSES and now - naming.get(key, now) >= STUCK_ORDER
                and passed_on_road(r[1], r[0], bus, now, track)):
            continue
        out.append(r)
    return out


UNTIMETABLED_STALE = 400  # seconds since a bus on a trip the timetable doesn't run there last reported (timetabled())
UNTIMETABLED_ONCE = 120  # the same for a bus heard once, or still at its first stop, after its trip set out
# A bus this far off at the planner's time, not yet on its way, comes no later than FAR_AFTER after the ministry's own
# time for it, unless the ministry has it more than FAR_BEFORE before its timetable (far_ahead()).
FAR, FAR_AFTER, FAR_BEFORE = 3000, 240, 300


def at_its_end(row, feed=None):
    """Whether a row's stop is its trip's last, by the trip's own stops in the planner's timetable (trip-patterns.json):
    a stop a bus only lets riders off at on its way isn't, and those are listed."""
    feed = live_feed if feed is None else feed
    try:
        first = datetime.datetime.fromisoformat(str(row[2]).split("_")[1])
    except (IndexError, ValueError):
        return False
    midnight = first.replace(hour=0, minute=0, second=0, microsecond=0)
    into = int((first - midnight).total_seconds())
    for seconds, day in ((into, midnight.date()), (into + 86400, midnight.date() - datetime.timedelta(days=1))):
        stops = feed.stops_of(str(row[1]), seconds, day.isoformat())
        if stops:
            return row[12] == len(stops) and stops[-1] == str(row[0])
    return False


def timetabled(rows, codes, now, ask=None, trips=None, heard=None, moved=None, feed=None):
    """The rows but the ministry's ones without a bus for trips the planner's timetable doesn't run at that stop: its
    lists carry trips of some other timetable, which don't run. Where the planner doesn't know the stop, or the row is
    past what it was asked for, the row stays. One the planner has comes at the planner's live time there (its
    timetable moved to how long the line has taken, otp/predict.py, and by the live updates), unless that has gone by:
    nearer what the bus did than the ministry's, and the same as the route's own. A train's timetable isn't moved, so
    its delay is only in the live time. A train or a light rail tram no one boards there, one ending there, goes:
    no one can take it from there. So does a bus at its trip's last stop (at_its_end()), the next trip's own row there
    staying."""
    known = {code: trips for code, trips in starting_at([str(int(c)) for c in codes], now, ask).items()
             if code in planner_stops["ids"] and len(trips) < TIMETABLE_MOST}
    followed = recorder.trips if trips is None else trips
    heard = recorder.heard if heard is None else heard
    moved = recorder.moved if moved is None else moved
    out = []
    for r in rows:
        # A light rail tram the list has at a stop with no place on its trip is at one it isn't coming to: such trams are
        # on trips the planner doesn't run, about to set out the other way.
        if r[12] in (None, 0) and (catalog["lines"].get(r[1]) or [None] * 7)[6] == 0:
            continue
        trips = known.get(str(r[0]))
        same = [t for t in trips or () if (t[0], t[1]) == (r[1], r[4])]
        kind = (catalog["lines"].get(r[1]) or [None] * 7)[6]
        if same and not any(t[5] for t in same) and (kind in (0, 2) or kind in BUSES and at_its_end(r, feed)):
            continue
        # A bus on a trip the planner's timetable doesn't run there, reporting from where it stood minutes ago: a
        # vehicle logged into a trip that isn't running.
        if r[7] is not None and trips is not None and not same and (catalog["lines"].get(r[1]) or [None] * 7)[6] in BUSES:
            # Sooner for a bus heard just once, after its trip set out, and not since, or heard again but still at its
            # first stop, not MOVED_FAR from where it was first heard: such buses rarely come.
            line, _, rest = str(r[2]).partition("_")
            start, _, vehicle = rest.partition("_")
            key = (line, start, vehicle)
            first_heard, state = heard.get(key), followed.get(key)
            still = state is not None and state[0][0] == 1 and (moved.get(key) or (None, 0))[1] is None
            once = first_heard is not None and state is not None and first_heard >= r[4] + 60 and (state[0][1] == first_heard or still)
            if now - (r[9] or now) >= (UNTIMETABLED_ONCE if once else UNTIMETABLED_STALE):
                continue
        if r[7] is not None or trips is None or not now - LATE_START <= r[6] <= now + BOARD_AHEAD:
            out.append(r)
            continue
        if not same:
            continue
        trip = min(same, key=lambda t: (t[3] != r[12], abs(t[2] - r[6])))
        # A bus under way with no place, from the list or the snapshot (placed() has run), is the timetable's: its time
        # there comes as the aimed one, so the app shows it white, as timetabled, rather than as a live bus.
        if not r[5] and (catalog["lines"].get(r[1]) or [None] * 7)[6] in BUSES:
            r = r[:5] + [trip[2]] + r[6:]
        out.append(r[:6] + [far_ahead(trip[6], r, now)] + r[7:] if trip[6] >= now - 60 else r)
    return out


def far_ahead(planned, row, now):
    """The planner's time for a bus FAR or more off and not on its way yet, but no later than FAR_AFTER after the
    ministry's own time for it (the row's last): that far ahead the planner's moved timetable (otp/predict.py) runs
    late and the ministry's doesn't. Not when the ministry has the bus more than FAR_BEFORE before its timetable there
    (the row's aimed): a bus that hasn't set out doesn't come that early."""
    ministry = row[14] if len(row) > 14 else None
    if planned - now < FAR or not ministry or not row[5] or ministry < row[5] - FAR_BEFORE:
        return planned
    return min(planned, ministry + FAR_AFTER)


def kav_times(row, now, ministry=True, trips=None, reached=None):
    """A row of board() with Kav's own predicted time for a bus on its way, where it has one, in place of the
    ministry's expected one; the ministry's own goes last, where an app that doesn't know it never looks (none for a
    bus the ministry's list left out). A stop the bus has reached since Kav's times were worked out moves them
    (since_kav())."""
    kav = predictions.at_stop(row[1], row[4], row[0], now)
    if kav is not None:
        kav += since_kav(row, now, trips, reached)
    # Kav's times are a round old at most: one gone by is for a bus the ministry still has coming, so it's due now.
    return row[:6] + [row[6] if kav is None else max(kav, int(now))] + row[7:] + [row[6] if ministry else None]


def since_kav(row, now, trips=None, reached=None):
    """How much earlier (less than 0) or later than Kav's time the bus got to the stop it names now, where Kav's times
    have that stop ahead of it and before the row's: they're worked out every half minute from the stops reached as
    the history has them, which lag the snapshot, and its later stops come that much earlier or later. 0 for any
    other."""
    # Buses only: it was measured on them, and a train names a station before it gets there.
    if (catalog["lines"].get(row[1]) or [None] * 7)[6] not in BUSES:
        return 0
    trips = recorder.trips if trips is None else trips
    reached = recorder.reached if reached is None else reached
    line, _, rest = str(row[2]).partition("_")
    first, _, vehicle = rest.partition("_")
    key = (line, first, vehicle)
    state, at = trips.get(key), reached.get(key)
    if state is None or at is None:
        return 0
    order, _, code = state[0]
    ahead = predictions.ahead(row[1], row[4], now)
    places = [place for place, c, _ in ahead if c == row[0]]
    for place, c, eta in ahead:
        # The place and the code both: the ministry now and then numbers a run's stops unlike the timetable.
        if place == order and str(c) == str(code) and places and place < min(places):
            return int(at) - eta
    return 0


class LiveFeed:
    """The planner's live updates (GTFS-RT TripUpdates), written after every snapshot to the folder it reads them from:
    a bus is at the stop its report names when it reports; a train at each station when the stations' lists expect it
    (train_lists()); a report stamped ahead of now is left out. The planner carries the delay on along the trip, and finds it by its route, direction, first
    departure and day, since some operators send no journey ref. The routes' directions come from the planner's own
    timetable (otp/prepare.py writes directions.json), and so do each trip's stops (trip-patterns.json): the ministry
    now and then numbers a run's stops unlike the timetable, and the update goes to the stop the report names."""

    STALE = 300
    # A report this old still gives the planner the bus's delay, as the stops' boards keep it live as long: dropped
    # sooner, a late bus between reports would go back to its timetable's time in the planner while the board showed it
    # live.
    KEEP = OLD_POSITION
    BEHIND = 60  # Kav's time for the next stop this much before the bus left its own isn't the bus's (encode())
    # A bus this far behind its trip's timetable at the stop it names isn't running that trip: it is waiting under an old
    # journey the ministry still lists, and the planner would take the trip as running that late.
    TOO_LATE = 2 * 3600
    SAME = {"17008": "17123", "17123": "17008"}  # one railway station, two stop codes
    # A trip no bus runs, though it should be on the road, most likely never runs. The planner would take it as running on
    # time and send riders to wait for a bus that never comes, so the updates call such trips off until a bus runs one,
    # and the stops' boards leave them out.
    MISSING_AFTER = 900  # unseen this long after it should have set out
    MISSING_LEFT = 300  # and due to run at least this much longer
    TRACKED = 3  # its route's trips seen running today, at least, and half of those due
    # And no bus of the route seen setting out this near its time: some routes run to other times than the timetable's
    # (route 11689: buses every six minutes, the timetable's at 15:20, 15:40, 15:50), which isn't a trip not run.
    MISSING_NEAR = 600

    def __init__(self, folder, history=""):
        self.folder = folder
        self.directions = {}
        self.read_at = 0
        self.patterns, self.patterns_at, self.split, self.starts = None, 0, {}, {}
        self.failed = None
        # The trips buses have been seen running, by route and first departure; before the passings kept (history) are
        # read, only trips setting out since the server started are judged.
        self.history, self.seen, self.since, self.warming = history, set(), time.time(), False
        self.missing, self.called_off = {}, frozenset()
        self.ends_by = {}

    def feed(self, doc, trains=None):
        if not self.folder:
            return
        try:
            now = time.time()
            self.note(doc, now)
            if self.history and not self.warming:
                self.warming = True
                threading.Thread(target=self.warm, args=(now,), daemon=True).start()
            self.missing = self.missing_now(now)
            self.called_off = frozenset((int(route), first) for route, first in self.missing)
            self.write(self.encode(doc, now, trains))
            self.write(json.dumps(self.on_road(doc, now)).encode(), "journeys.json")
            self.failed = None
        except Exception as e:
            if self.failed != repr(e):
                log("live feed failing:", repr(e))
                self.failed = repr(e)

    def directions_now(self):
        path = os.path.join(self.folder, "directions.json")
        try:
            changed = os.path.getmtime(path)
        except OSError:
            return self.directions
        if changed != self.read_at:
            with open(path, encoding="utf-8") as f:
                self.directions = json.load(f)
            self.read_at = changed
        return self.directions

    def patterns_now(self):
        """The planner's own timetable's trips (otp/prepare.py's trip-patterns.json), read again when it changes."""
        path = os.path.join(self.folder, "trip-patterns.json")
        try:
            changed = os.path.getmtime(path)
        except OSError:
            self.patterns, self.patterns_at = None, 0
            return None
        if changed != self.patterns_at:
            with open(path, encoding="utf-8") as f:
                self.patterns, self.patterns_at, self.split, self.starts = json.load(f), changed, {}, {}
        return self.patterns

    def note(self, doc, now):
        """The trips the snapshot has a bus running."""
        delivery = doc["Siri"]["ServiceDelivery"]
        monitored = delivery.get("StopMonitoringDelivery") or []
        if isinstance(monitored, dict):
            monitored = [monitored]
        for part in monitored:
            for v in part.get("MonitoredStopVisit") or []:
                try:
                    j = v["MonitoredVehicleJourney"]
                    self.seen.add((j["LineRef"], epoch(j["OriginAimedDepartureTime"])))
                except (KeyError, ValueError, TypeError):
                    continue
        if len(self.seen) > 400_000:
            self.seen = {k for k in self.seen if k[1] > now - 2 * 86400}

    def warm(self, now):
        """The trips seen running before the server started, from the passings kept (Recorder's files)."""
        try:
            found = set()
            today = datetime.datetime.fromtimestamp(now, ISRAEL).date()
            for day in (today - datetime.timedelta(days=1), today):
                for name, opener in ((day.isoformat() + ".tsv", open), (day.isoformat() + ".tsv.gz", gzip.open)):
                    path = os.path.join(self.history, name)
                    if os.path.exists(path):
                        with opener(path, "rt", encoding="utf-8") as f:
                            for line in f:
                                x = line.split("\t", 4)
                                if len(x) > 4:
                                    found.add((x[0], x[3]))
            self.seen |= {(route, epoch(start)) for route, start in found}
            self.since = 0
        except Exception as e:
            log("live feed history failing:", repr(e))

    def end_of(self, route, first):
        """When the trip of the route setting out then ends in the planner's timetable, or None."""
        patterns = self.patterns_now()
        if not patterns or "ends" not in patterns:
            return None
        day = datetime.datetime.fromtimestamp(first, ISRAEL).date()
        for d in (day.isoformat(), (day - datetime.timedelta(days=1)).isoformat()):
            ends = self.ends_by.get(d)
            if ends is None:
                ends = {(r, f): e for f, e, r, _ in self.starts_of(d)}
                self.ends_by = {k: v for k, v in self.ends_by.items() if k >= min(d, day.isoformat())} | {d: ends}
            if (str(route), first) in ends:
                return ends[(str(route), first)]
        return None

    def starts_of(self, day):
        """The day's trips by when each sets out: (first departure, end, route, seconds into its day)."""
        patterns = self.patterns_now()
        if day not in self.starts:
            midnight = int(datetime.datetime.combine(datetime.date.fromisoformat(day), datetime.time(), ISRAEL).timestamp())
            out = []
            for route, times in patterns["trips"].items():
                ends = patterns.get("ends", {}).get(route, {})
                for into, n in times.items():
                    if (day in n if isinstance(n, dict) else day in patterns["days"]) and into in ends:
                        out.append((midnight + int(into), midnight + ends[into], route, int(into)))
            out.sort()
            self.starts[day] = out
            for old in sorted(self.starts)[:-3]:
                del self.starts[old]
        return self.starts[day]

    def missing_now(self, now):
        """The trips that should be on the road that no bus has been seen running, on routes whose other trips buses are
        seen running: {(route, first departure): (seconds into its day, its day)}."""
        patterns = self.patterns_now()
        if not patterns or "ends" not in patterns:
            return {}
        today = datetime.datetime.fromtimestamp(now, ISRAEL).date()
        due, ran, found = {}, {}, {}
        for day in (today - datetime.timedelta(days=1), today):
            for first, end, route, into in self.starts_of(day.isoformat()):
                if first > now - self.MISSING_AFTER:
                    break
                if first < self.since:
                    continue
                due[route] = due.get(route, 0) + 1
                if (route, first) in self.seen:
                    ran[route] = ran.get(route, 0) + 1
                elif end >= now + self.MISSING_LEFT:
                    found[(route, first)] = (into, day.isoformat())
        found = {k: v for k, v in found.items() if ran.get(k[0], 0) >= self.TRACKED and 2 * ran[k[0]] >= due[k[0]]}
        starts, routes = {}, {k[0] for k in found}
        for route, first in self.seen:
            if route in routes:
                starts.setdefault(route, []).append(first)
        for times in starts.values():
            times.sort()

        def near(route, first):
            times = starts.get(route, [])
            i = bisect.bisect_left(times, first - self.MISSING_NEAR)
            return i < len(times) and times[i] <= first + self.MISSING_NEAR
        return {k: v for k, v in found.items() if not near(*k)}

    def stops_of(self, line, into, day):
        """The trip's stops' codes in order, from the planner's own timetable, or None."""
        if self.patterns_now() is None:
            return None
        n = self.patterns["trips"].get(line, {}).get(str(into))
        if isinstance(n, dict):
            n = n.get(day)
        if n is None:
            return None
        if n not in self.split:
            self.split[n] = self.patterns["patterns"][n].split(",")
        return self.split[n]

    def clock_at(self, line, into, day, place, code):
        """Seconds from the trip's first departure to its stop at that place in the ministry's timetable
        (trip-patterns.json `timed`, otp/prepare.py), where that place is that stop; None when it has no clocks."""
        patterns = self.patterns_now()
        if not patterns or "timed" not in patterns:
            return None
        stops, m = self.stops_of(line, into, day), patterns["timed"].get(line, {}).get(str(into))
        if isinstance(m, dict):
            m = m.get(day)
        if not stops or m is None or not 0 < place <= len(stops) or stops[place - 1] != code:
            return None
        clocks = patterns["offsets"][m].split(",")
        return int(clocks[place - 1]) if len(clocks) == len(stops) else None

    def clocks_of(self, line, into, day):
        """Seconds from the trip's first departure to each of its stops in the ministry's timetable, in order (as
        clock_at() gives one); None when it has none."""
        patterns = self.patterns_now()
        if not patterns or "timed" not in patterns:
            return None
        m = patterns["timed"].get(line, {}).get(str(into))
        if isinstance(m, dict):
            m = m.get(day)
        return None if m is None else [int(c) for c in patterns["offsets"][m].split(",")]

    def placed(self, codes, order, named):
        """The number of the stop a report names: its own where the trip has that stop there, else the nearest place on
        the trip that has it; None when the trip doesn't stop there at all."""
        same = lambda c: c == named or self.SAME.get(c) == named
        if 0 < order <= len(codes) and same(codes[order - 1]):
            return order
        at = [i + 1 for i, c in enumerate(codes) if same(c)]
        return min(at, key=lambda k: abs(k - order)) if at else None

    def encode(self, doc, now, trains=None):
        directions = self.directions_now()
        delivery = doc["Siri"]["ServiceDelivery"]
        monitored = delivery.get("StopMonitoringDelivery") or []
        if isinstance(monitored, dict):
            monitored = [monitored]
        # One update a trip: two vehicles can report the same journey, and the planner would take whichever came last.
        best = {}
        for part in monitored:
            for v in part.get("MonitoredStopVisit") or []:
                try:
                    j = v["MonitoredVehicleJourney"]
                    call = j["MonitoredCall"]
                    line, start, day = j["LineRef"], j["OriginAimedDepartureTime"], j["FramedVehicleJourneyRef"]["DataFrameRef"]
                    seen, order = epoch(v["RecordedAtTime"]), int(call["Order"])
                except (KeyError, ValueError, TypeError):
                    continue
                if line not in directions or now - seen > self.KEEP or seen - now > AHEAD:
                    continue
                first = datetime.datetime.fromisoformat(start)
                midnight = datetime.datetime.fromisoformat(day).replace(tzinfo=first.tzinfo)
                into = int((first - midnight).total_seconds())
                codes, named = self.stops_of(line, into, day), str(call.get("StopPointRef") or "")
                if codes and named:
                    order = self.placed(codes, order, named)
                    if order is None:
                        continue
                # The report names the stop the vehicle is at or has just passed; its distance is how far along the
                # route the vehicle is, not how far from the stop. A train says when it reaches the stop.
                try:
                    due = epoch(call["ExpectedArrivalTime"]) if j.get("OperatorRef") == "2" else seen
                except (KeyError, ValueError, TypeError):
                    continue
                # A bus waiting at its first stop leaves on time at the earliest.
                if order == 1:
                    due = max(due, int(first.timestamp()))
                clock = self.clock_at(line, into, day, order, codes[order - 1]) if codes and 0 < order <= len(codes) else None
                if clock is not None and due - int(first.timestamp()) - clock > self.TOO_LATE:
                    continue
                trip = (pb_field(5, line) + pb_field(6, int(directions[line]))
                        + pb_field(2, "%02d:%02d:%02d" % (into // 3600, into // 60 % 60, into % 60))
                        + pb_field(3, day.replace("-", "")))
                # Kav's own times for the stops ahead, where it has them from the very next stop on: the planner would
                # time a stop left out from the timetable, maybe after the next. It turns down a whole trip whose bus
                # would leave a stop after it reaches the next, so each stop is left as it's reached, a second at least
                # after the one before.
                kav = []
                for ahead, code, eta in predictions.ahead(line, int(first.timestamp()), now):
                    if ahead > order:
                        # The planner's timetable can be newer than the one Kav's times were worked out on: only
                        # while its trip has the very stop there.
                        if ahead != (kav[-1][0] if kav else order) + 1 or codes and (
                                ahead > len(codes) or codes[ahead - 1] != str(code)):
                            break
                        kav.append((ahead, eta))
                # Kav's times that have the bus at the next stop well before it left this one are from before it set
                # out, worked out from the timetable's start: the planner carries the bus's own delay on instead.
                if kav and kav[0][1] < due - self.BEHIND:
                    kav = []
                at = pb_field(2, pb_field(2, due))
                update = pb_field(1, trip) + pb_field(2, pb_field(1, order) + at + (pb_field(3, pb_field(2, due)) if kav else b""))
                last = due
                for ahead, eta in kav:
                    last = max(last + 1, eta)
                    update += pb_field(2, pb_field(1, ahead) + pb_field(2, pb_field(2, last)) + pb_field(3, pb_field(2, last)))
                update += pb_field(4, seen)
                entity = pb_field(2, pb_field(1, f"{line}_{start}_{j.get('VehicleRef', '')}") + pb_field(3, update))
                if (line, start) not in best or (seen, order) > best[(line, start)][:2]:
                    best[(line, start)] = (seen, order, entity)
        best.update(self.train_updates(trains, directions, now))
        header = pb_field(1, pb_field(1, "2.0") + pb_field(2, 0) + pb_field(3, int(now)))
        # The trips no bus is running, called off (schedule relationship CANCELED) until one is.
        off = []
        for (line, first), (into, day) in sorted(self.missing.items()):
            if line in directions:
                trip = (pb_field(5, line) + pb_field(6, int(directions[line]))
                        + pb_field(2, "%02d:%02d:%02d" % (into // 3600, into // 60 % 60, into % 60))
                        + pb_field(3, day.replace("-", "")) + pb_field(4, 3))
                off.append(pb_field(2, pb_field(1, "%s_%d_missing" % (line, first)) + pb_field(3, pb_field(1, trip))))
        return header + b"".join(entity for _, _, entity in best.values()) + b"".join(off)

    def train_updates(self, doc, directions, now):
        """Each train in the railway stations' lists (train_lists()), as encode() keeps a trip's update, by route and
        first departure: its expected arrival at every station ahead, each a second at least after the one before (the
        planner turns down a trip that gets to a station before leaving the one before), and from its first station on
        time at the earliest; an Israel Railways train's at Kav's own time there where it has one. On a day Israel
        Railways runs the train otherwise (the planner's copy, rail_copies()), the time isn't moved onto the railway's
        own: the trains come at the ministry's time and their delay."""
        if not doc:
            return {}
        monitored = doc["Siri"]["ServiceDelivery"].get("StopMonitoringDelivery") or []
        if isinstance(monitored, dict):
            monitored = [monitored]
        runs = {}
        for part in monitored:
            for v in part.get("MonitoredStopVisit") or []:
                try:
                    j = v["MonitoredVehicleJourney"]
                    call = j["MonitoredCall"]
                    line, start, day = j["LineRef"], j["OriginAimedDepartureTime"], j["FramedVehicleJourneyRef"]["DataFrameRef"]
                    seen, order, due = epoch(v["RecordedAtTime"]), int(call["Order"]), epoch(call["ExpectedArrivalTime"])
                except (KeyError, ValueError, TypeError):
                    continue
                if line not in directions or now - seen > self.KEEP or seen - now > AHEAD:
                    continue
                named = str(call.get("StopPointRef") or "")
                # Israel Railways' trains at Kav's own time there where it has one (kav-eta's rail model), which comes
                # closer than the list's. Not the light rail, where the list's own does better.
                if j.get("OperatorRef") == "2" and named.isdigit():
                    kav = predictions.at_stop(int(line), epoch(start), int(named), now)
                    due = due if kav is None else int(kav)
                run = runs.setdefault((line, start, day), [seen, j.get("VehicleRef", ""), []])
                run[0] = max(run[0], seen)
                run[2].append((order, named, due))
        out = {}
        for (line, start, day), (seen, vehicle, calls) in runs.items():
            first = datetime.datetime.fromisoformat(start)
            midnight = datetime.datetime.fromisoformat(day).replace(tzinfo=first.tzinfo)
            into = int((first - midnight).total_seconds())
            copy = rail_copies_held["copies"].get((line, day))
            if copy is not None:
                # The planner's copy of the trip that day is the train: its first departure and its stops.
                into, codes = copy[:2]
                first = midnight + datetime.timedelta(seconds=into)
            else:
                codes = self.stops_of(line, into, day)
            at = {}
            for order, named, due in calls:
                if codes and named:
                    order = self.placed(codes, order, named)
                    if order is None:
                        continue
                at.setdefault(order, max(due, int(first.timestamp())) if order == 1 else due)
            if not at:
                continue
            trip = (pb_field(5, line) + pb_field(6, int(directions[line]))
                    + pb_field(2, "%02d:%02d:%02d" % (into // 3600, into // 60 % 60, into % 60))
                    + pb_field(3, day.replace("-", "")))
            update, last = pb_field(1, trip), None
            for order in sorted(at):
                last = at[order] if last is None else max(last + 1, at[order])
                update += pb_field(2, pb_field(1, order) + pb_field(2, pb_field(2, last)))
            update += pb_field(4, seen)
            out[(line, start)] = (seen, min(at), pb_field(2, pb_field(1, f"{line}_{start}_{vehicle}") + pb_field(3, update)))
        return out

    def on_road(self, doc, now):
        """The journeys on the road, each reporting within STALE, by route and first departure: for the arrival model
        (eta/eta.py), which must not go on predicting a bus that has ended its run. One that two vehicles report at once
        is left out, as the model's own training left such runs out: which of them is where can't be told. Beside them,
        where each is along its route (DistanceFromStop, metres from where its run starts), when it said so and its
        reported speed (km/h, None without one), by "route_first": the model's times know only the stop a bus last
        reached (eta.position_blend), a motion model takes them as its motion inputs (eta/motion.py)."""
        delivery = doc["Siri"]["ServiceDelivery"]
        monitored = delivery.get("StopMonitoringDelivery") or []
        if isinstance(monitored, dict):
            monitored = [monitored]
        vehicles, where = {}, {}
        for part in monitored:
            for v in part.get("MonitoredStopVisit") or []:
                try:
                    j = v["MonitoredVehicleJourney"]
                    seen = epoch(v["RecordedAtTime"])
                    if now - seen <= self.STALE:
                        journey = (j["LineRef"], epoch(j["OriginAimedDepartureTime"]))
                        vehicles.setdefault(journey, set()).add(j.get("VehicleRef", ""))
                        metres = (j.get("MonitoredCall") or {}).get("DistanceFromStop")
                        if metres not in (None, "") and seen <= now and seen >= where.get(journey, (0, 0))[1]:
                            where[journey] = (int(float(metres)), seen, whole(j.get("Velocity"), 199))
                except (KeyError, ValueError, TypeError):
                    continue
        one = sorted([route, first] for (route, first), v in vehicles.items() if len(v) == 1)
        return {"at": int(now), "journeys": one,
                "places": {"%s_%d" % (route, first): list(where[(route, first)]) for route, first in one if (route, first) in where}}

    def write(self, data, name="trip-updates.pb"):
        path = os.path.join(self.folder, name)
        with open(path + ".part", "wb") as f:
            f.write(data)
        os.replace(path + ".part", path)


live_feed = LiveFeed(REALTIME, HISTORY)


def poll():
    while True:
        start = time.time()
        try:
            snapshot = take_snapshot()
            state["snapshot"] = snapshot
            if state["failing"]:
                log("snapshot back from", snapshot.source)
                state["failing"] = None
        except Exception as e:
            if state["failing"] != str(e):
                log("snapshot failing:", e)
                state["failing"] = str(e)
            snapshot = state["snapshot"]
        busy = time.time() - state["asked"] < IDLE_AFTER
        due = start + (SNAPSHOT_EVERY if busy else IDLE_EVERY)
        if busy and snapshot is not None:
            # A new snapshot comes out every 15 seconds: ask a little after the next one.
            due = min(max(due, snapshot.at + SNAPSHOT_EVERY + 3), start + 30)
        state["next"] = due
        while (left := due - time.time()) > 0:
            if wake.wait(left):
                wake.clear()
                due = max(start + SNAPSHOT_EVERY, time.time())
                state["next"] = due
        forget_follows()


follows = {}
follow_locks = {}
budget = {"tokens": float(FOLLOW_BURST), "at": time.time()}
budget_lock = threading.Lock()
pool = ThreadPoolExecutor(8)


def spend():
    with budget_lock:
        now = time.time()
        budget["tokens"] = min(float(FOLLOW_BURST), budget["tokens"] + (now - budget["at"]) * FOLLOW_PER_SECOND)
        budget["at"] = now
        if budget["tokens"] < 1:
            return False
        budget["tokens"] -= 1
        return True


def follow(route):
    """The route's vehicles straight from the ministry, at most once per FOLLOW_TTL for everyone."""
    with follow_locks.setdefault(route, threading.Lock()):
        hit = follows.get(route)
        if hit and time.time() - hit[0] < FOLLOW_TTL:
            return hit[1]
        if not URL or not spend():
            return hit[1] if hit else []
        try:
            _, rows = visits(ask(URL, f"MonitoringRef=all&LineRef={route}&StopVisitDetailLevel=normal", 10))
        except Exception:
            return hit[1] if hit else []
        rows = newest([tuple(r[1:]) for r in rows if r[0] == route])
        follows[route] = (time.time(), rows)
        return rows


def forget_follows():
    old = time.time() - 600
    for route in [r for r, (t, _) in list(follows.items()) if t < old]:
        follows.pop(route, None)
    with stops_lock:
        for code in [c for c, (t, _) in stops_seen.items() if t < old]:
            stops_seen.pop(code, None)


PLATFORM = re.compile(r"^[0-9A-Za-zא-ת]{1,4}$")


STALE_TRIP = 8 * 3600  # the longest trip, 5.9 h, and then some (arrivals())
# A bus due this long after its trip's end in the planner's timetable isn't running it: no bus came three hours late.
AFTER_END = 3 * 3600


def after_its_end(row, feed=None):
    """Whether the row's bus is due AFTER_END past the end of its trip in the planner's timetable."""
    feed = live_feed if feed is None else feed
    end = feed.end_of(row[1], row[4]) if row[6] else None
    return end is not None and row[6] - end > AFTER_END


def arrivals(doc):
    """Every bus due at the stops asked, one per trip: [stop, route, key, number, first departure, aimed, expected,
    lat, lon, recorded, bearing, speed, order, platform]. Times in seconds; no position when the bus isn't reporting
    one; the platform, a train's, once the operator has given it."""
    delivery = doc["Siri"]["ServiceDelivery"]
    monitored = delivery.get("StopMonitoringDelivery") or []
    if isinstance(monitored, dict):
        monitored = [monitored]
    now = time.time()
    best = {}
    for part in monitored:
        for v in part.get("MonitoredStopVisit") or []:
            try:
                j = v["MonitoredVehicleJourney"]
                call = j.get("MonitoredCall") or {}
                stop = int(v.get("MonitoringRef") or call["StopPointRef"])
                first = j["OriginAimedDepartureTime"]
                recorded = epoch(v["RecordedAtTime"]) if v.get("RecordedAtTime") else 0
                where = j.get("VehicleLocation")
                lat = lon = None
                if where and reporting(recorded, now):
                    lat, lon = round(float(where["Latitude"]), 6), round(float(where["Longitude"]), 6)
                    if not (29 < lat < 34 and 34 < lon < 36):
                        lat = lon = None
                bearing = whole(j.get("Bearing"), 360) if lat is not None else None
                platform = str(call.get("ArrivalPlatformName") or call.get("DeparturePlatformName") or "").strip()
                row = [
                    stop, int(j["LineRef"]), f'{j["LineRef"]}_{first}_{j.get("VehicleRef", "")}',
                    str(j.get("PublishedLineName", "")), epoch(first),
                    epoch(call["AimedArrivalTime"]) if call.get("AimedArrivalTime") else 0,
                    epoch(call["ExpectedArrivalTime"]) if call.get("ExpectedArrivalTime") else 0,
                    lat, lon, recorded if lat is not None else 0,
                    None if bearing is None else bearing % 360,
                    whole(j.get("Velocity"), 199) if lat is not None else None,
                    whole(call.get("Order"), 999),
                    platform if PLATFORM.match(platform) else "",
                ]
            except (KeyError, ValueError, TypeError):
                continue
            # A bus still logged into a trip it set out on long ago, parked overnight. No trip here runs six hours (991,
            # Haifa to Eilat, 5.9): one due STALE_TRIP after it set out isn't running it.
            if row[6] and row[6] - row[4] > STALE_TRIP:
                continue
            if after_its_end(row):
                continue
            # A trip can come twice, once with a vehicle that stopped reporting hours ago.
            trip = (stop, row[1], first)
            held = best.get(trip)
            if held is None or (row[7] is not None, row[9]) > (held[7] is not None, held[9]):
                best[trip] = row
    return list(best.values())


MAX_STOPS_KEPT = 5000

# stop code: (when the ministry was asked, the stop's rows). Kept per stop, so overlapping views, panning and other
# people share them, and only the stops not asked lately go to the ministry.
stops_seen = {}
stops_lock = threading.Lock()


def backup_arrivals(query):
    """The stops' arrivals from the ministry's test server, or None when it fails or has only minutes-old ones."""
    if not BACKUP:
        return None
    try:
        doc = ask(BACKUP, query, 15)
        if time.time() - epoch(doc["Siri"]["ServiceDelivery"]["ResponseTimestamp"]) > 360:
            return None
        return arrivals(doc)
    except Exception:
        return None


def note_trams(rows, now):
    """Notes when each stop's list last had each light rail tram (with_starting), forgetting what is too old to count."""
    for row in rows:
        if (catalog["lines"].get(row[1]) or [None] * 7)[6] == 0:
            trams_listed[(str(row[0]), row[1], row[4])] = now
    if len(trams_listed) > 5000:
        for key in [k for k, at in trams_listed.items() if now - at > TRAM_LISTED]:
            del trams_listed[key]


def board(codes):
    """The stops' arrivals straight from the ministry, each stop at most once per FOLLOW_TTL for everyone."""
    codes = list(dict.fromkeys(str(int(c)) for c in codes))
    now = time.time()
    with stops_lock:
        held = {c: stops_seen.get(c) for c in codes}
    missing = [c for c, hit in held.items() if hit is None or now - hit[0] >= FOLLOW_TTL]
    if missing:
        query = f"MonitoringRef={','.join(missing)}&StopVisitDetailLevel=normal&PreviewInterval=PT90M"
        try:
            if not URL or not spend():
                raise IOError("busy")
            rows = arrivals(ask(URL, query, 15))
        except Exception as e:
            # The ministry's test server stands in when the main one fails, as for the snapshot; not when Kav's own share
            # of requests is spent.
            rows = backup_arrivals(query) if str(e) != "busy" else None
            # What was seen a little while ago beats nothing.
            if rows is None and all(held[c] is None for c in missing):
                raise
        if rows is not None:
            by_stop = {}
            for row in rows:
                by_stop.setdefault(str(row[0]), []).append(row)
            at = time.time()
            with stops_lock:
                note_trams(rows, at)
                for c in missing:
                    held[c] = stops_seen[c] = (at, by_stop.get(c, []))
                if len(stops_seen) > MAX_STOPS_KEPT:
                    for c, _ in sorted(stops_seen.items(), key=lambda kv: kv[1][0])[:len(stops_seen) - MAX_STOPS_KEPT]:
                        stops_seen.pop(c, None)
    return [row for c in codes if held[c] for row in held[c][1]]


def noticed():
    """Marks the server busy. True when it was idle, so the next snapshot is being taken now."""
    now = time.time()
    idle = now - state["asked"] > IDLE_AFTER
    state["asked"] = now
    if idle:
        wake.set()
    return idle


FOLLOW = re.compile(r"^/live/v1/follow/([0-9]{1,9}(?:,[0-9]{1,9}){0,%d})$" % (MAX_FOLLOW - 1))
# From, to, when (epoch seconds, then d to leave then, n to leave now, a to arrive by or l for the last way there that
# night), the route types allowed (all if none), and up to three places to go by on the way.
PLAN = re.compile(r"^/live/v1/plan/(\d\d\.\d{1,6}),(\d\d\.\d{1,6}),(\d\d\.\d{1,6}),(\d\d\.\d{1,6})/(\d{10})([adnl])"
                  r"(?:/([0-7](?:,[0-7]){0,7}))?(?:/via/(\d\d\.\d{1,6},\d\d\.\d{1,6}(?:;\d\d\.\d{1,6},\d\d\.\d{1,6}){0,2}))?$")
MODES = {0: "TRAM", 1: "SUBWAY", 2: "RAIL", 3: "BUS", 4: "FERRY", 5: "CABLE_CAR", 6: "GONDOLA", 7: "FUNICULAR"}
BIKES_ON = (0, 2)  # light rail and trains, which take a bike along
# The answer's sections by the app's ids: the routes list, the timetable card, walking or cycling all the way, transit
# with a bike, routes on East Jerusalem's operators, the taxi card, and a taxi to transit.
MAIN, SCHEDULE, WALK_BIKE, BIKE_TRANSIT, EAST_JERUSALEM_ROUTES, TAXI, TAXI_TRANSIT = 1, 3, 521, 522, 1461, 1561, 1581
EAST_JERUSALEM = ("42", "44", "45", "49", "50", "51")  # the ministry's operators kept out of the routes list
JERUSALEM = (31.68, 31.95, 35.08, 35.35)  # where a trip may use them: south, north, west, east
WALK_SPEED, PLANNER_WALK_SPEED = 1.33, 1.35  # the walking pace shown, and the nearest OpenTripPlanner takes
# What the planner calls the walk inside a station: otp/prepare.py's pathways from stations.tsv have no name, and their
# time is the station's own (stations.tsv), not a pace.
STATION_PATHWAY = "pathway"
LEAST_CHANGE = 60  # seconds to spare at a change, at least
# The walk to the first ride sets out this much earlier, so the rider is at the stop a minute before Kav's time: a
# bus comes early more often than the times say, and arriving earlier never misses one.
FIRST_LEAN = 60
# At night buses come early more often still: a route whose first ride sets out from NIGHT_LEAN_FROM o'clock to MORNING
# sets out up to NIGHT_LEAN earlier in all, never before the time asked.
NIGHT_LEAN, NIGHT_LEAN_FROM = 150, 21
# From one train to another, at least this long, the walk to the other platform included: three minutes to change
# platforms at any station.
TRAIN_CHANGE = 180
DIRECT_MOST = 3600  # walking or cycling all the way is offered for an hour at most
# The routes list: no walk longer than 1.5 km in one go, three routes at
# least, the later ones arriving at most 1.7 times the quickest route's time after it. The third route is taken
# from those costing no more than half as much again as the quickest, with no more changes, arriving
# within one and a half times its time after it, and four routes at most.
WALK_MOST, LEAST_SHOWN, MOST_SHOWN, LATEST_SHOWN, DEAREST_SHOWN = 1600, 3, 4, 1.5, 1.5
# Its last ways there walk further: up to 1.95 km.
WALK_LAST = 2000
SCHEDULE_NEAR = 200  # metres from a train or Jerusalem light rail station that bring the timetable card
# The taxi card has the taxi there in four minutes, always, and times the drive a little slower than the
# planner's free-flowing roads: 7.6% slower, plus 163 seconds.
TAXI_PICKUP, TAXI_SLOWER, TAXI_EXTRA = 240, 1.076, 163
# A taxi to a station: its drives there take ten minutes at most, get there two minutes before the train, and
# are shown when they arrive no more than two minutes after the quickest route in the list.
TAXI_DRIVE_MOST, TAXI_AT_STATION, TAXI_LATER = 600, 120, 120
PLAN_QUERY = """
query($o: PlanLabeledLocationInput!, $d: PlanLabeledLocationInput!, $t: PlanDateTimeInput!, $modes: PlanModesInput,
      $prefs: PlanPreferencesInput, $via: [PlanViaLocationInput!], $window: Duration) {
  planConnection(origin: $o, destination: $d, dateTime: $t, modes: $modes, preferences: $prefs, via: $via,
                 searchWindow: $window, first: 40) {
    routingErrors { code }
    edges { node {
      start end generalizedCost systemNotices { tag }
      legs {
        mode start { scheduledTime estimated { time } } end { scheduledTime estimated { time } } distance headsign
        legGeometry { points } steps { distance streetName bogusName }
        from { lat lon stop { gtfsId } }
        to { lat lon stop { gtfsId } }
        route { gtfsId shortName longName type agency { gtfsId name } }
        serviceDate trip { gtfsId departureStoptime { scheduledDeparture } }
        stopCalls {
          stopLocation { ... on Stop { gtfsId code name lat lon platformCode } }
          schedule { time { ... on ArrivalDepartureTime { arrival departure } } }
        }
        nextLegs(numberOfLegs: 3) { start { scheduledTime } route { gtfsId } trip { gtfsId } }
      }
    } }
  }
}"""
planning = threading.BoundedSemaphore(MAX_PLANNING)


def local_id(gtfs_id):
    """The feed's own id, without the feed prefix OpenTripPlanner gives every one."""
    return gtfs_id.split(":", 1)[1] if gtfs_id and ":" in gtfs_id else gtfs_id


def plan_kinds(frm, to, modes, via=()):
    """The questions a trip needs: the routes list, walking and cycling all the way, transit with a bike when trains or
    light rail may be taken, and every operator's routes ("all") where East Jerusalem's run. Going by other places on
    the way, only the routes list."""
    if via:
        return ["main"]
    kinds = ["main", "walk", "cycle"]
    if not modes or any(m in BIKES_ON for m in modes):
        kinds.append("bike")
    if (not modes or 3 in modes) and any(JERUSALEM[0] < p[0] < JERUSALEM[1] and JERUSALEM[2] < p[1] < JERUSALEM[3] for p in (frm, to)):
        kinds.append("all")
    return kinds


LAST_WINDOW = "PT5H"  # how far back from three in the morning the last ways there are looked for
# And when none of those walks no more than WALK_MOST in one go, how far back the last ways that do are looked for: they
# can set out before the five hours (on a Friday the last buses go in the afternoon). Over fourteen hours a search can
# take several seconds, up to 18 s on a Friday afternoon in Gush Dan, so that question has until LAST_DAY_BY seconds
# after the trip was asked for, or the five hours' routes stand: the app gives up on a plan after 15 s.
LAST_DAY_WINDOW, LAST_DAY_BY = "PT14H", 9
# How far back from the time arriving by a time looks: the planner's own window can find no way at all where there are
# three (Haifa to Nazareth by 18:30).
ARRIVE_WINDOW = "PT2H"
# Leaving at a time with nothing to ride in the planner's own window (it took 50 minutes there), the same question over
# three hours, in what is left of LAST_DAY_BY, to find a later bus. At night only up to the morning
# (morning_after()), whose own question finds its routes.
WIDER_WINDOW = 3 * 3600


def last_way(when):
    """The latest departure: the routes there by three in the morning, when the service day ends."""
    at = datetime.datetime.fromtimestamp(when, ISRAEL)
    end = at.replace(hour=3, minute=0, second=0, microsecond=0)
    if end <= at:
        end += datetime.timedelta(days=1)
    return int(end.timestamp())


# At night: once no route still running walks no more than WALK_MOST in one go, the list is the next morning's
# first routes; while one does, it keeps tonight's. The morning is searched over three hours: over the planner's own
# window from four only the first buses come, most a 2-3 km walk away, where those setting out at five or so are a
# short walk away.
NIGHT, MORNING, MORNING_WINDOW = 20, 4, "PT3H"
# Before that, the same question with a second on foot costing twice what the planner's own weighting has it (2): at
# night every route it returns can end in a long walk, each named by its similar-legs filter, and at 4 it returns
# the ones with a short walk.
WALK_RELUCTANCE_AGAIN = 4
# And at every night search the next morning's question goes out too (over MORNING_TOO_WINDOW), beside the routes list's
# own: its routes join the others found, the next morning's first routes beside tonight's thin ones, if
# they come within WEIGHED_BY seconds of the trip being asked for.
MORNING_TOO, MORNING_TOO_WINDOW = True, "PT2H"


def morning_after(when):
    """For a trip asked about from the evening to four in the morning, when the next morning's routes are looked for
    from; None in the day."""
    at = datetime.datetime.fromtimestamp(when, ISRAEL)
    if MORNING <= at.hour < NIGHT:
        return None
    start = at.replace(hour=MORNING, minute=0, second=0, microsecond=0)
    if at.hour >= NIGHT:
        start += datetime.timedelta(days=1)
    return int(start.timestamp())


# Shabbat and the holidays, when the timetable has hardly a bus: a trip asked for from noon the day before (a Friday, a
# holiday's eve) to when the days off end, with nothing to ride, gets the first routes after them: the routes list's
# question from REST_BEFORE seconds before nightfall over REST_WINDOW, or else the next morning's (morning_after()).
# Those whose first ride sets out after nightfall (the sun REST_SUN degrees below the horizon where the trip starts)
# are tagged after_shabbat, or after_holiday when the last day off is a holiday on a weekday, whatever found them.
REST_BEFORE, REST_WINDOW, REST_SUN = 3 * 3600, "PT8H", 8.5
# Over the days off the planner is slow across their empty hours: the question after them, and the last way's earlier
# question asked before or during them, have until REST_BY seconds after the trip was asked for, inside the app's 15 s.
REST_BY = 12
HEBREW_EPOCH = -1373427  # the Hebrew calendar's first day as a proleptic Gregorian ordinal


def hebrew_new_year(year):
    """The date of 1 Tishrei of a Hebrew year, by the calendar's own arithmetic (Reingold and Dershowitz)."""
    def elapsed(y):
        months = (235 * y - 234) // 19
        days = 29 * months + (12084 + 13753 * months) // 25920
        return days + 1 if (3 * (days + 1)) % 7 < 3 else days
    a, b, c = elapsed(year - 1), elapsed(year), elapsed(year + 1)
    return datetime.date.fromordinal(HEBREW_EPOCH + b + (2 if c - b == 356 else 1 if b - a == 382 else 0))


@functools.lru_cache(maxsize=8)
def holidays(year):
    """Israel's holidays with no work in a year: Pesach's first and seventh days and Shavuot (from 15 Nisan, 163 days
    before Rosh Hashana), both days of Rosh Hashana, Yom Kippur, Sukkot's first day and Shmini Atzeret."""
    autumn = hebrew_new_year(year + 3761)
    return frozenset(autumn + datetime.timedelta(days=d) for d in (-163, -157, -113, 0, 1, 9, 14, 21))


def day_off(day):
    return day.weekday() == 5 or day in holidays(day.year)


def nightfall(day, p):
    """When the sun is REST_SUN degrees below the horizon on a day at point p (NOAA's approximation, a minute or so)."""
    g = 2 * math.pi / 365 * (day.timetuple().tm_yday - 1)
    eq = 229.18 * (0.000075 + 0.001868 * math.cos(g) - 0.032077 * math.sin(g) - 0.014615 * math.cos(2 * g) -
                   0.040849 * math.sin(2 * g))
    dec = (0.006918 - 0.399912 * math.cos(g) + 0.070257 * math.sin(g) - 0.006758 * math.cos(2 * g) +
           0.000907 * math.sin(2 * g) - 0.002697 * math.cos(3 * g) + 0.00148 * math.sin(3 * g))
    lat = math.radians(p[0])
    hour = math.degrees(math.acos(math.cos(math.radians(90 + REST_SUN)) / (math.cos(lat) * math.cos(dec)) -
                                  math.tan(lat) * math.tan(dec)))
    midnight = datetime.datetime.combine(day, datetime.time(), datetime.timezone.utc).timestamp()
    return int(midnight + 60 * (720 - 4 * (p[1] - hour) - eq))


def rest_end(when, p):
    """For a trip asked for from noon the day before a day off to that day's nightfall at p: when the last day off in a
    row ends, and whether that day is a Saturday; else None."""
    at = datetime.datetime.fromtimestamp(when, ISRAEL)
    day = at.date()
    if not day_off(day):
        if at.hour < 12 or not day_off(day + datetime.timedelta(days=1)):
            return None
        day += datetime.timedelta(days=1)
    while day_off(day + datetime.timedelta(days=1)):
        day += datetime.timedelta(days=1)
    end = nightfall(day, p)
    return (end, day.weekday() == 5) if end > when else None


# A trip starts at a train station's platform, with no walk, from up to about this far from it, where the
# planner would walk round to the station's entrance.
STATION_NEAR = 50
OFF_RAIL = ("TRAM", "SUBWAY", "CABLE_CAR", "GONDOLA", "FUNICULAR")  # stops a trip starting by a station may start at instead


def station_at(p, ask=None):
    """The train station a point is at, whose trains the routes list starts or ends on the platforms of: (its planner
    id, whether the trip's other rides start at the point), or None. They do at a light rail or cable car stop nearer
    than the station's, boarded there at once (asked_both()). The stops are looked for in a box: OpenTripPlanner's
    radius is a walk, round that entrance."""
    d = 0.0008
    try:
        found = (ask or ask_graphql)("{ stopsByBbox(minLat: %f, minLon: %f, maxLat: %f, maxLon: %f) { gtfsId lat lon vehicleMode "
                                     "parentStation { gtfsId } } }" % (p[0] - d, p[1] - d, p[0] + d, p[1] + d))
    except Exception:
        return None
    near = lambda s: math.hypot((s["lat"] - p[0]) * 111_320, (s["lon"] - p[1]) * 111_320 * math.cos(math.radians(p[0])))
    rail = [s for s in found.get("stopsByBbox") or [] if s.get("vehicleMode") == "RAIL" and near(s) <= STATION_NEAR]
    if not rail:
        return None
    s = min(rail, key=near)
    street = any(o.get("vehicleMode") in OFF_RAIL and near(o) < near(s) for o in found.get("stopsByBbox") or [])
    return (s.get("parentStation") or {}).get("gtfsId") or s["gtfsId"], street


# A bus stop picked by name is its namesake across the street too: a trip from (to) it may start (end) at either with
# no walk, as one from a train station starts on its platforms. Where the street map has no crossing between the two,
# the planner walks round to the next stop the same bus calls at. A point within PICKED_NEAR of a bus stop is that
# stop; the stop of the same name within TWIN_NEAR of it is its twin when the planner's walk to it is at least
# TWIN_DETOUR longer than the straight line. Only on a trip of up to TWIN_TRIP in a straight line: further, the side of
# the street changes a few metres of it, and the twin's questions double the planner's time. The latest TWINS_KEPT
# points' twins are kept for plan_variables().
PICKED_NEAR, TWIN_NEAR, TWIN_DETOUR, TWIN_TRIP, TWINS_KEPT = 3, 50, 100, 10_000, 4096
twins_found, twins_lock = {}, threading.Lock()
WALK_QUERY = ("{ planConnection(origin: {location: {coordinate: {latitude: %f, longitude: %f}}}, destination: {location: "
              "{coordinate: {latitude: %f, longitude: %f}}}, modes: {directOnly: true, direct: [WALK]}) "
              "{ edges { node { legs { distance } } } } }")


def twins_at(p, ask=None):
    """The twin of the bus stop at p, the nearest: (lat, lon), or None. Kept in twins_found."""
    ask = ask or ask_graphql
    d = 0.0008
    try:
        found = ask("{ stopsByBbox(minLat: %f, minLon: %f, maxLat: %f, maxLon: %f) { gtfsId name lat lon vehicleMode } }"
                    % (p[0] - d, p[1] - d, p[0] + d, p[1] + d))
    except Exception:
        return None
    near = lambda s: math.hypot((s["lat"] - p[0]) * 111_320, (s["lon"] - p[1]) * 111_320 * math.cos(math.radians(p[0])))
    bus = [s for s in found.get("stopsByBbox") or [] if s.get("vehicleMode") == "BUS"]
    stop = min(bus, key=near, default=None)
    same = [s for s in bus if stop and near(stop) <= PICKED_NEAR and s["gtfsId"] != stop["gtfsId"]
            and s.get("name") == stop.get("name") and near(s) <= TWIN_NEAR]
    nearest, twin = min(same, key=near, default=None), None
    if nearest:
        try:
            walks = (ask(WALK_QUERY % (p[0], p[1], nearest["lat"], nearest["lon"])).get("planConnection") or {}).get("edges")
            walked = min(sum(l.get("distance") or 0 for l in e["node"]["legs"]) for e in walks) if walks else 0
        except Exception:
            walked = 0
        if walked >= near(nearest) + TWIN_DETOUR:
            twin = (nearest["lat"], nearest["lon"])
    with twins_lock:
        twins_found.pop(p, None)
        twins_found[p] = twin
        while len(twins_found) > TWINS_KEPT:
            del twins_found[next(iter(twins_found))]
    return twin


def plan_variables(frm, to, when, arrive, modes, kind="main", via=(), window=None, exclude=(), at=(None, None),
                   walk_reluctance=None, transfers=None, rail=None):
    """at: the train stations (station_at) the trip starts and ends at, if any, which the routes list goes from and to.
    walk_reluctance: how much a second on foot costs the planner against one riding, where not its own (2). rail: how
    much a second on a train, the light rail or the cable car costs against one on a bus, where not the same (1)."""
    stamp = datetime.datetime.fromtimestamp(when, datetime.timezone.utc).isoformat()

    def point(p, station):
        if station and kind in ("main", "all"):
            return {"location": {"stopLocation": {"stopLocationId": station[0]}}}
        return {"location": {"coordinate": {"latitude": p[0], "longitude": p[1]}}}
    out = {"o": point(frm, at[0]), "d": point(to, at[1]), "t": {"latestArrival" if arrive else "earliestDeparture": stamp}}
    street = [s if not (s and s[1]) else None for s in at]
    if kind in ("main", "all") and street != list(at):
        # The same question from the points themselves where the other rides start or end there (asked_both()).
        out["points"] = {"o": point(frm, street[0]), "d": point(to, street[1])}
    twins = [None if s else twins_found.get(p) for p, s in ((frm, at[0]), (to, at[1]))]
    apart = math.hypot((frm[0] - to[0]) * 111_320, (frm[1] - to[1]) * 111_320 * math.cos(math.radians(frm[0])))
    if kind in ("main", "all") and any(twins) and apart <= TWIN_TRIP:
        # The same question from (to) a picked bus stop's twin across the street too (asked_twins()).
        out["twins"] = {k: point(t, None) for k, t in zip("od", twins) if t}
    if window:
        out["window"] = window
    if via:
        # The places on the way, which the trip is planned by a part at a time (through()).
        out["via"] = [list(p) for p in via]
    if kind in ("walk", "cycle"):
        out["modes"] = {"directOnly": True, "direct": ["WALK" if kind == "walk" else "BICYCLE"]}
        return out
    out["modes"] = {"transitOnly": True}
    allowed = sorted(set(modes)) if modes else []
    if kind == "bike":
        allowed = [m for m in allowed or MODES if m in BIKES_ON]
        out["modes"]["transit"] = {"access": ["BICYCLE"], "egress": ["BICYCLE"], "transfer": ["BICYCLE"]}
    if kind == "by_taxi":
        # Picked up four minutes from now at the earliest.
        out["t"] = {"earliestDeparture": datetime.datetime.fromtimestamp(when + TAXI_PICKUP, datetime.timezone.utc).isoformat()}
        # OpenTripPlanner takes a drop-off only together with walking; routes that only walk are left out later.
        out["modes"]["transit"] = {"access": ["CAR_DROP_OFF", "WALK"]}
    if allowed:
        out["modes"].setdefault("transit", {})["transit"] = [{"mode": MODES[m]} for m in allowed]
    if kind in ("main", "by_taxi"):
        out["prefs"] = {"transit": {"filters": [{"exclude": [{"agencies": ["1:" + a for a in EAST_JERUSALEM]}]}]}}
        if exclude:
            out["prefs"]["transit"]["filters"][0]["exclude"].append({"routes": ["1:" + r for r in exclude]})
    if walk_reluctance is not None:
        out.setdefault("prefs", {})["street"] = {"walk": {"reluctance": walk_reluctance}}
    if transfers is not None:
        out.setdefault("prefs", {}).setdefault("transit", {})["transfer"] = {"maximumTransfers": transfers}
    if kind in ("main", "by_taxi"):
        # Half a minute at the stop before the first bus and a minute at a change, half from boarding and half from the
        # change, where router-config.json's minute before every bus planned each route a minute later than it runs.
        rules = out.setdefault("prefs", {}).setdefault("transit", {})
        rules.setdefault("board", {})["slack"] = "PT30S"
        rules.setdefault("transfer", {})["slack"] = "PT30S"
    if rail is not None and kind == "main":
        # Every mode the planner may take must be named once any is given a cost of its own.
        named = out["modes"].setdefault("transit", {}).get("transit") or [{"mode": m} for m in ALL_MODES]
        out["modes"]["transit"]["transit"] = [dict(m, cost={"reluctance": rail}) if m["mode"] in RAIL_MODES else m for m in named]
    return out


# OpenTripPlanner's transit modes (its TransitMode), and those the ranking favours: a train or the light rail in a
# route is worth about twenty minutes to it (ROUTE_COST).
ALL_MODES = ("AIRPLANE", "BUS", "CABLE_CAR", "CARPOOL", "COACH", "FERRY", "FUNICULAR", "GONDOLA", "MONORAIL", "RAIL",
             "SNOW_AND_ICE", "SUBWAY", "TAXI", "TRAM", "TROLLEYBUS")
RAIL_MODES = ("RAIL", "TRAM", "SUBWAY", "CABLE_CAR", "GONDOLA", "FUNICULAR", "MONORAIL")


# A second planner on the ministry's own times (the same night's timetable before predict.py): the routes the predicted
# one misses join a list after Kav's own, at Kav's times (plan_answer()). Unset, there's none.
OFFICIAL_URL, OFFICIAL_WAIT = os.environ.get("OFFICIAL_URL", ""), 4


def post_plan(url, variables, wait):
    if variables.get("via"):
        until = time.time() + wait
        return through(variables, lambda v: post_plan(url, v, max(0.1, until - time.time())))
    body = json.dumps({"query": PLAN_QUERY, "variables": variables}).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=wait) as r:
        return json.load(r)


# A trip by places on the way is planned a part at a time, each part the planner's own question: from where it starts
# to the first place, from each place to the next, and from the last to where it ends (arriving by a time, from the
# last part back). The planner's own way through a place (a via visit) takes it several seconds, and at most places it
# fails. Each route to a place goes on with each route from it setting out after the rider arrives, and the routes kept
# are those no other beats in every way, as the planner keeps its own (pareto()). The walk from a place sets out
# FIRST_LEAN earlier, as a trip's first walk does (lean_first()), and the time at the place costs what waiting does.
# Each part after the first is asked over the times the rider is at its place, and VIA_WINDOW more. A part where
# walking beats every ride, whose rides the planner's filters drop, is walked: on from a place as soon as the rider is
# there, to a place to be there just in time for the route on (joined()).
VIA_WINDOW = 3600


def through(variables, post):
    """The planner's answer to a question by places on the way (the via of plan_variables()), asked of post() a part
    at a time: the routes all the way, and the routing errors of the last part asked."""
    arrive = "latestArrival" in variables["t"]
    asked = {k: v for k, v in variables.items() if k != "via"}
    places = [asked["o"]] + [{"location": {"coordinate": {"latitude": p[0], "longitude": p[1]}}} for p in variables["via"]]
    parts = list(zip(places, places[1:] + [asked["d"]]))
    # Whether a route gets to a place in time for one from it: a walk moves to fit, leaving at a time only later.
    before = lambda there, on: walk_only(on) or arrive and walk_only(there) or epoch(there["end"]) <= epoch(on["start"]) - FIRST_LEAN
    routes = None
    for o, d in parts[::-1] if arrive else parts:
        part = dict(asked, o=o, d=d)
        if routes is not None:
            at = sorted(epoch(r["start"]) - FIRST_LEAN if arrive else epoch(r["end"]) + FIRST_LEAN for r in routes)
            stamp = datetime.datetime.fromtimestamp(at[-1] if arrive else at[0], datetime.timezone.utc).isoformat()
            part.update(t={"latestArrival" if arrive else "earliestDeparture": stamp},
                        window="PT%dM" % -(-(at[-1] - at[0] + VIA_WINDOW) // 60))
        plan = (post(part).get("data") or {}).get("planConnection") or {}
        errors = plan.get("routingErrors") or []
        got = [e["node"] for e in plan.get("edges") or []
               if not {n.get("tag") for n in e["node"].get("systemNotices") or []} - {WALK_FILTER}]
        if not got:
            got = [dict(e["node"], systemNotices=[]) for e in plan.get("edges") or [] if walk_only(e["node"])][:1]
        if routes is None:
            routes = got
        else:
            first, then = (got, routes) if arrive else (routes, got)
            routes = pareto([r for a in first for r in pareto([joined(a, b) for b in then if before(a, b)])])
        if not routes:
            break
    return {"data": {"planConnection": {"routingErrors": errors, "edges": [{"node": r} for r in routes]}}}


def pareto(routes):
    """The routes no other is as good as in every way and better in one: setting out later, getting there sooner, with
    fewer rides, costing less."""
    rated = sorted(((-epoch(r["start"]), epoch(r["end"]), sum(l["mode"] not in ("WALK", "BICYCLE", "CAR") for l in r["legs"]),
                     r["generalizedCost"]), n, r) for n, r in enumerate(routes))
    kept = []
    for rating, _, r in rated:
        if not any(all(x <= y for x, y in zip(k, rating)) for k, _ in kept):
            kept.append((rating, r))
    return [r for _, r in kept]


def walk_only(route):
    """Whether a planner's route rides nothing."""
    return all(l["mode"] in ("WALK", "BICYCLE", "CAR") for l in route["legs"])


def joined(there, on):
    """A route to a place and one on from it as one route (through()); a walked one moved to fit the other."""
    if walk_only(on):
        on = shifted(on, epoch(there["end"]) - epoch(on["start"]))
    elif walk_only(there):
        there = shifted(there, epoch(on["start"]) - FIRST_LEAN - epoch(there["end"]))
    ride = next((i for i, l in enumerate(on["legs"]) if l["mode"] not in ("WALK", "BICYCLE", "CAR")), len(on["legs"]))
    lean = FIRST_LEAN if ride < len(on["legs"]) else 0
    stay = epoch(on["start"]) - lean - epoch(there["end"])
    return dict(on, start=there["start"], generalizedCost=there["generalizedCost"] + stay + on["generalizedCost"],
                systemNotices=(there.get("systemNotices") or []) + (on.get("systemNotices") or []),
                legs=there["legs"] + [moved(l, -lean) for l in on["legs"][:ride]] + on["legs"][ride:])


def shifted(route, by):
    """A planner's route by seconds later."""
    later = lambda text: (datetime.datetime.fromisoformat(text) + datetime.timedelta(seconds=by)).isoformat()
    return dict(route, start=later(route["start"]), end=later(route["end"]), legs=[moved(l, by) for l in route["legs"]])


def moved(leg, by):
    """A planner's leg by seconds later, as planned and as estimated."""
    later = lambda text: (datetime.datetime.fromisoformat(text) + datetime.timedelta(seconds=by)).isoformat()
    ends = {}
    for k in ("start", "end"):
        ends[k] = dict(leg[k], scheduledTime=later(leg[k]["scheduledTime"]))
        if (leg[k].get("estimated") or {}).get("time"):
            ends[k]["estimated"] = dict(leg[k]["estimated"], time=later(leg[k]["estimated"]["time"]))
    return dict(leg, **ends)


def on_platforms(node, start, end):
    """Whether a route rides a train from (start) and to (end) the station it was asked from and to, with no walk
    before (after) that train."""
    legs = node["legs"]
    rides = [i for i, l in enumerate(legs) if l["mode"] not in ("WALK", "BICYCLE", "CAR")]
    still = lambda part: all(l["start"]["scheduledTime"] == l["end"]["scheduledTime"] for l in part)
    return bool(rides) and (not start or legs[rides[0]]["mode"] == "RAIL" and still(legs[:rides[0]])) \
        and (not end or legs[rides[-1]]["mode"] == "RAIL" and still(legs[rides[-1] + 1:]))


def into_station(node, frm, to):
    """A route from the point frm (to the point to) whose first (last) ride is a train from (to) a stop within
    STATION_NEAR of it, from (to) that train's platform: the walk there and through the station left out, the
    platform start that a point so near the station has."""
    legs = node["legs"]
    rides = [i for i, l in enumerate(legs) if l["mode"] not in ("WALK", "BICYCLE", "CAR")]
    near = lambda s, p: math.hypot((s["lat"] - p[0]) * 111_320, (s["lon"] - p[1]) * 111_320 * math.cos(math.radians(p[0])))
    a, b = 0, len(legs)
    if rides and frm and legs[rides[0]]["mode"] == "RAIL" and near(legs[rides[0]]["from"], frm) <= STATION_NEAR:
        a = rides[0]
    if rides and to and legs[rides[-1]]["mode"] == "RAIL" and near(legs[rides[-1]]["to"], to) <= STATION_NEAR:
        b = rides[-1] + 1
    return node if (a, b) == (0, len(legs)) else dict(node, legs=legs[a:b])


def asked_both(variables, post):
    """A question from or to a train station by a light rail or cable car stop (plan_variables' points), asked both
    ways: the routes riding a train from (to) its platforms from the station, every other way from (to) the point
    itself, which boards the light rail at once and doesn't walk up and out of the station to it; the point's own ways
    into that station's trains from the platforms too (into_station()). Either answer alone if the other fails."""
    points = variables.get("points")
    if not points:
        return post(variables)
    asked = {k: v for k, v in variables.items() if k != "points"}
    with ThreadPoolExecutor(1) as pool:
        street = pool.submit(post, dict(asked, **points))
        try:
            platforms = post(asked)
        except Exception:
            return street.result()
        try:
            doc = street.result()
        except Exception:
            return platforms
    plan = (doc.get("data") or {}).get("planConnection")
    if plan is None:
        return platforms
    start, end = (asked[k] != points[k] for k in ("o", "d"))
    at = [(points[k]["location"]["coordinate"]["latitude"], points[k]["location"]["coordinate"]["longitude"]) if s else None
          for k, s in (("o", start), ("d", end))]
    other = ((platforms.get("data") or {}).get("planConnection") or {})
    kept = [e for e in other.get("edges") or [] if on_platforms(e["node"], start, end)]
    ridden = lambda e: tuple(((l.get("route") or {}).get("gtfsId"), (l.get("trip") or {}).get("gtfsId"))
                             for l in e["node"]["legs"] if l["mode"] not in ("WALK", "BICYCLE", "CAR"))
    have = {ridden(e) for e in kept}
    walked = [dict(e, node=into_station(e["node"], *at)) for e in plan.get("edges") or []]
    if kept and not walked:
        plan["routingErrors"] = other.get("routingErrors") or []
    plan["edges"] = [e for e in walked if ridden(e) not in have] + kept
    return doc


def asked_twins(variables, post):
    """A question from or to a picked bus stop with a twin (plan_variables' twins), asked from (to) each of the two
    (asked_both()), every answer's routes together: of those riding the same trips, the one the planner costs least.
    Any answer alone if the others fail."""
    twins = variables.get("twins")
    if not twins:
        return asked_both(variables, post)
    asked = {k: v for k, v in variables.items() if k != "twins"}
    ways = []
    for o in [asked["o"]] + ([twins["o"]] if "o" in twins else []):
        for d in [asked["d"]] + ([twins["d"]] if "d" in twins else []):
            way = dict(asked, o=o, d=d)
            if "points" in asked:
                # Only an end without a station has a twin, and there the point is the end itself.
                way["points"] = dict(asked["points"], **{k: e for k, e in (("o", o), ("d", d)) if e is not asked[k]})
            ways.append(way)
    docs, failed = [], None
    with ThreadPoolExecutor(len(ways) - 1) as pool:
        others = [pool.submit(asked_both, way, post) for way in ways[1:]]
        try:
            docs.append(asked_both(ways[0], post))
        except Exception as e:
            failed = e
        for other in others:
            try:
                docs.append(other.result())
            except Exception:
                pass
    if not docs:
        raise failed
    plans = [(doc, (doc.get("data") or {}).get("planConnection")) for doc in docs]
    plans = [(doc, plan) for doc, plan in plans if plan is not None]
    if not plans:
        return docs[0]
    ridden = lambda e: tuple(((l.get("route") or {}).get("gtfsId"), (l.get("trip") or {}).get("gtfsId"))
                             for l in e["node"]["legs"] if l["mode"] not in ("WALK", "BICYCLE", "CAR"))
    cost = lambda e: e["node"].get("generalizedCost") or 0
    kept = {}
    for _, plan in plans:
        for e in plan.get("edges") or []:
            if ridden(e) not in kept or cost(e) < cost(kept[ridden(e)]):
                kept[ridden(e)] = e
    doc, plan = plans[0]
    if kept and not plan.get("edges"):
        plan["routingErrors"] = next(p.get("routingErrors") or [] for _, p in plans if p.get("edges"))
    plan["edges"] = list(kept.values())
    return doc


def ask_official(variables, wait=OFFICIAL_WAIT):
    """The planner on the ministry's own times."""
    url = OFFICIAL_URL
    return asked_twins(variables, lambda v: post_plan(url, v, wait))


def ask_planner(variables, wait=25):
    url = OTP_URL
    return asked_twins(variables, lambda v: post_plan(url, v, wait))


# OpenTripPlanner's filter that drops a route costing more than walking all the way, which it works out even for a
# transit-only search. A bus a minute slower than the timetable, as a live one often is, then goes while a later one
# stays. Walking all the way is offered on its own.
WALK_FILTER = "transit-vs-walk-filter"


def reporting_runs(now, trips=None):
    """The runs the snapshot has had lately, with a place or without, by route and first departure (Recorder.trips)."""
    trips = recorder.trips if trips is None else trips
    out = set()
    for (line, start, _), ((_, seen, _), _, _) in list(trips.items()):
        try:
            if now - seen <= OLD_POSITION:
                out.add((int(line), epoch(start)))
        except (TypeError, ValueError):
            continue
    return out


def stops_once(calls):
    """A ride's stops with one the timetable lists twice in a row once, as the app's timetable has them (prepare.once);
    one that is nothing but a stop twice, as it is."""
    out = [s for i, s in enumerate(calls) if i == 0 or s != calls[i - 1]]
    return out if len(out) > 1 else calls


def itineraries(doc, stops, lines, agencies):
    """OpenTripPlanner's answer as Kav's routes, filling in the stops and lines they use. It lists the routes its filters
    would remove too, each with the filters' names (router-config.json): every one goes but those only the walking one
    names. A train boarded at the station it sets out from is live once its run reports: the stations' lists give the
    planner its times before it sets out."""
    found, reporting = [], None
    for edge in ((doc.get("data") or {}).get("planConnection") or {}).get("edges") or []:
        node = edge["node"]
        if {n.get("tag") for n in node.get("systemNotices") or []} - {WALK_FILTER}:
            continue
        legs, rides, inside, ends = [], 0, {}, {}
        for leg in node["legs"]:
            # The planner's time, which its live updates have moved where a bus is on the road, and the timetable's.
            dep, arr = (epoch((leg[k].get("estimated") or {}).get("time") or leg[k]["scheduledTime"]) for k in ("start", "end"))
            sdep, sarr = epoch(leg["start"]["scheduledTime"]), epoch(leg["end"]["scheduledTime"])
            shape = (leg.get("legGeometry") or {}).get("points") or ""
            if leg["mode"] in ("WALK", "BICYCLE", "CAR"):
                legs.append({"k": {"WALK": "w", "BICYCLE": "b", "CAR": "t"}[leg["mode"]], "dep": dep, "arr": arr,
                             "m": round(leg["distance"] or 0), "shape": shape})
                inside[len(legs) - 1] = round(sum(s.get("distance") or 0 for s in leg.get("steps") or []
                                                  if s.get("bogusName") and s.get("streetName") == STATION_PATHWAY))
                ends[len(legs) - 1] = [round(sum(s.get("distance") or 0 for s in itertools.takewhile(
                    lambda s: s.get("bogusName") and s.get("streetName") == STATION_PATHWAY, steps)))
                    for steps in (leg.get("steps") or [], (leg.get("steps") or [])[::-1])]
                if leg["mode"] == "CAR":
                    legs[-1].update({"from": [leg["from"]["lat"], leg["from"]["lon"]], "to": [leg["to"]["lat"], leg["to"]["lon"]]})
                continue
            route = leg["route"]
            line = local_id(route["gtfsId"])
            agency = local_id(route["agency"]["gtfsId"])
            agencies[agency] = route["agency"]["name"]
            # As the other answers name lines; OpenTripPlanner's own details until the timetable has been read.
            lines[line] = catalog["lines"].get(int(line)) if line.isdigit() and int(line) in catalog["lines"] else \
                route_line(agency, route["shortName"] or "", route["longName"] or "", route["type"])
            calls = []
            for call in leg.get("stopCalls") or []:
                stop = call.get("stopLocation") or {}
                if not stop.get("gtfsId"):
                    continue
                sid = local_id(stop["gtfsId"])
                calls.append(sid)
                stops[sid] = [stop.get("code") or "", stop.get("name") or "", stop.get("lat"), stop.get("lon"),
                              stop.get("platformCode") or ""]
            nxt = [[epoch(n["start"]["scheduledTime"]), local_id((n.get("trip") or {}).get("gtfsId") or "")]
                   for n in leg.get("nextLegs") or [] if (n.get("route") or {}).get("gtfsId") == route["gtfsId"]]
            rides += 1
            live, first = bool(leg["start"].get("estimated")), first_departure(leg)
            if live and route["type"] == 2 and first == sdep:
                reporting = reporting_runs(time.time()) if reporting is None else reporting
                live = line.isdigit() and (int(line), first) in reporting
            legs.append({"k": "r", "line": line, "trip": local_id(leg["trip"]["gtfsId"]).split("~")[0], "dep": dep, "arr": arr,
                         "sdep": sdep, "sarr": sarr, "live": live, "first": first,
                         "stops": stops_once(calls), "shape": shape, "next": nxt, "head": leg.get("headsign") or ""})
        from_the_door(legs, ends)
        walk_at_pace(legs, inside)
        lean_first(legs)
        if not legs or not changes_kept(legs, lines):
            continue
        found.append({"dep": legs[0]["dep"], "arr": legs[-1]["arr"], "cost": node["generalizedCost"], "rides": rides,
                      "walk": sum(l["arr"] - l["dep"] for l in legs if l["k"] == "w"), "legs": legs})
    return found


def lean_first(legs, lean=FIRST_LEAN):
    """The way to the first ride, set out lean earlier: the rider waits at the stop instead of meeting the bus."""
    first = next((i for i, l in enumerate(legs) if l["k"] == "r"), None)
    for leg in legs[:first] if first else ():
        leg["dep"] -= lean
        leg["arr"] -= lean


def lean_at_night(it, when):
    """A route whose first ride sets out at night, set out up to NIGHT_LEAN earlier in all (FIRST_LEAN of it already
    taken in itineraries()), never before when."""
    first = next((i for i, l in enumerate(it["legs"]) if l["k"] == "r"), None)
    if not first:
        return
    if MORNING <= datetime.datetime.fromtimestamp(it["legs"][first]["dep"], ISRAEL).hour < NIGHT_LEAN_FROM:
        return
    lean_first(it["legs"], max(0, min(NIGHT_LEAN - FIRST_LEAN, it["dep"] - when)))
    it["dep"] = it["legs"][0]["dep"]


def from_the_door(legs, ends):
    """A trip asked from (to) a station itself whose first (last) ride isn't on its platforms: the walk sets out from
    (ends at) the station's door, its time on the station's paths there not walked, as a point within STATION_NEAR of
    a station is beside it. ends: each walk's metres on a station's paths at its start and at its end, by its place in
    legs."""
    rides = [i for i, l in enumerate(legs) if l["k"] == "r"]
    if not rides:
        return
    for i, side in ((0, 0), (len(legs) - 1, 1)):
        leg, inside = legs[i], ends.get(i, (0, 0))[side]
        if leg["k"] != "w" or not inside or not (i < rides[0] if side == 0 else i > rides[-1]):
            continue
        street = round((leg["m"] - inside) / PLANNER_WALK_SPEED)
        if side == 0:
            leg["dep"] = max(leg["dep"], leg["arr"] - street)
        else:
            leg["arr"] = min(leg["arr"], leg["dep"] + street)
        leg["m"] -= inside


def walk_at_pace(legs, inside=None):
    """Walks at WALK_SPEED, a little longer than OpenTripPlanner's: the way to the first ride sets out
    that much earlier, and after a ride, the rest gets there that much later. inside: each walk's metres inside a
    station, by its place in legs, which a walk from one ride to another doesn't make longer: the time there is the
    station's own, and a longer change could lose a route that makes it."""
    rides = [i for i, l in enumerate(legs) if l["k"] == "r"]

    def longer(i, leg):
        if leg["k"] != "w":
            return 0
        m = leg["m"] - ((inside or {}).get(i, 0) if rides and rides[0] < i < rides[-1] else 0)
        return round(m / WALK_SPEED - m / PLANNER_WALK_SPEED)
    first = rides[0] if rides else 0
    shift = 0
    for i in reversed(range(first)):
        legs[i]["arr"] -= shift
        shift += longer(i, legs[i])
        legs[i]["dep"] -= shift
    shift = 0
    for i in range(first, len(legs)):
        leg = legs[i]
        if leg["k"] == "r":
            shift = 0
            continue
        leg["dep"] += shift
        shift += longer(i, leg)
        leg["arr"] += shift


def changes_kept(legs, lines=None):
    """Whether every change still leaves LEAST_CHANGE between getting to the stop and boarding, and from one train to
    another its TRAIN_CHANGE."""
    train = lambda leg: ((lines or {}).get(leg.get("line")) or [None] * 7)[6] == 2
    rode = None
    for i, leg in enumerate(legs):
        if leg["k"] == "r":
            if rode is not None and leg["dep"] - legs[i - 1]["arr"] < LEAST_CHANGE:
                return False
            if rode is not None and train(rode) and train(leg) and leg["dep"] - rode["arr"] < TRAIN_CHANGE:
                return False
            rode = leg
    return True


ALTERNATIVE_STOP = """s%d: stop(id: "1:%s") { stoptimesForPatterns(startTime: %d, timeRange: %d, numberOfDepartures: 6,
  omitNonPickups: true) { pattern { id route { gtfsId shortName longName type agency { gtfsId name } }
  stops { gtfsId code name lat lon platformCode } patternGeometry { points } } } }"""
# Each ride's own departures, from five minutes before it: a ride from the same stop half an hour after another can come
# after the six departures from before the first. Twelve, as Haifa's cable car leaves every minute: its six from five
# minutes before are all gone by its own, which would leave it no next one.
ALTERNATIVE_TIMES = """r%d: stop(id: "1:%s") { stoptimesForPatterns(startTime: %d, timeRange: %d, numberOfDepartures: 12,
  omitNonPickups: true) { pattern { id } stoptimes { serviceDay scheduledDeparture realtimeDeparture realtime stopPosition headsign
  trip { gtfsId departureStoptime { scheduledDeparture } } } } }"""
ALTERNATIVE_TRIP = """t%d: trip(id: "1:%s") { stoptimesForDate(serviceDate: "%s") { stop { gtfsId } serviceDay scheduledArrival
  realtimeArrival realtimeDeparture } }"""
# A route's first ride's departures between the time asked about and five minutes before its own: the card lists a
# line's departures from then, catchable or not.
EARLIER_TIMES = """b%d: stop(id: "1:%s") { stoptimesForPatterns(startTime: %d, timeRange: %d, numberOfDepartures: 4,
  omitNonPickups: true) { pattern { id } stoptimes { serviceDay scheduledDeparture realtimeDeparture realtime stopPosition
  trip { gtfsId } } } }"""
# The card offers each other line's departure up to three hours after the ride's, that one too, and none later.
ALTERNATIVE_SPAN = 10800


def train_platforms(number, board, alight, at=None):
    """The platforms a train run is boarded at and left at, by its number (the headsign), the two stations' codes and
    when it leaves (its run that day), as Israel Railways' timetable has them (railway_platform()); either None where it
    gives none (it has none for the trains setting out from Beer Sheva Center). None while the railway's timetable isn't
    at hand."""
    if time.time() - railway_held["at"] > RAILWAY_KEEP or not railway_held["platforms"]:
        return None
    return railway_platform(number, board, at), railway_platform(number, alight, at)


def ask_graphql(query):
    req = urllib.request.Request(OTP_URL, data=json.dumps({"query": query}).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r).get("data") or {}


ALTERNATIVE_PARTS = 4  # questions the alternatives' parts are asked in at once: the planner answers each part in turn


def ask_in_parts(ask, parts):
    """The planner's answers to a question made of named parts, asked as up to ALTERNATIVE_PARTS questions at once."""
    groups = [parts[i::ALTERNATIVE_PARTS] for i in range(min(ALTERNATIVE_PARTS, len(parts)))]
    with ThreadPoolExecutor(len(groups) or 1) as asking:
        found = {}
        for got in asking.map(lambda group: ask("{" + " ".join(group) + "}"), groups):
            found.update(got or {})
    return found


def alternatives(routes, stops, lines, agencies, ask=ask_graphql, since=None):
    """The other lines each ride could be taken on: every line from the same stop to the same stop,
    with its next departures from five minutes before the chosen one, and when it gets there; for a route's first ride,
    also each line's departures there from since (the time asked about) on ("before"). Two questions to the planner for
    all the routes together; a route keeps none when they fail."""
    rides = [leg for it in routes for leg in it["legs"] if leg["k"] == "r" and len(leg["stops"]) >= 2]
    boards = sorted({leg["stops"][0] for leg in rides})
    if not boards:
        return
    firsts = {id(l) for l in (next((l for l in it["legs"] if l["k"] == "r"), None) for it in routes) if l is not None}
    earlier = {k: leg for k, leg in enumerate(rides)
               if since is not None and id(leg) in firsts and leg["dep"] - 300 > since - 60}
    earliest = {b: min(leg["dep"] for leg in rides if leg["stops"][0] == b) - 300 for b in boards}
    # The lines there are looked for from the time asked about too: a train run is a route of its own, so the one
    # leaving at once has no departure after the ride's and its line's card would lack it. The window ends three hours
    # after the last ride's from there, a minute more for the planner's edge.
    latest = {b: max(leg["dep"] for leg in rides if leg["stops"][0] == b) + ALTERNATIVE_SPAN + 60 for b in boards}
    start = dict(earliest)
    for leg in earlier.values():
        start[leg["stops"][0]] = min(start[leg["stops"][0]], since - 60)
    try:
        found = ask_in_parts(ask, [ALTERNATIVE_STOP % (i, b, start[b], latest[b] - start[b])
                                   for i, b in enumerate(boards)] +
                             [ALTERNATIVE_TIMES % (k, leg["stops"][0], leg["dep"] - 300, ALTERNATIVE_SPAN + 360)
                              for k, leg in enumerate(rides)] +
                             [EARLIER_TIMES % (k, leg["stops"][0], since - 60, leg["dep"] - 300 - (since - 60))
                              for k, leg in earlier.items()])
    except Exception as e:
        log("alternatives failed:", repr(e))
        return
    patterns = {b: (found.get("s%d" % i) or {}).get("stoptimesForPatterns") or [] for i, b in enumerate(boards)}
    when = lambda st: st["serviceDay"] + st["realtimeDeparture"]
    trip_of = lambda st: local_id(st["trip"]["gtfsId"]).split("~")[0]
    # A departure as [when, trip], and 1 after them when the planner has it live: a train card's other trains are its
    # line's, other routes the ministry's board doesn't match to the ride: without it they'd show white though live.
    departure = lambda t, st: [t, trip_of(st)] + ([1] if st.get("realtime") else [])
    # A line, as below; and the lines each ride's route rides on elsewhere, which the card doesn't offer for the ride.
    key_of = lambda route: "rail:" + (route.get("longName") or "") if route.get("type") == 2 else local_id(route["gtfsId"])
    on = {id(leg): next((key_of(p["pattern"]["route"]) for p in patterns.get(leg["stops"][0], [])
                         if local_id(p["pattern"]["route"]["gtfsId"]) == leg["line"]), leg["line"]) for leg in rides}
    elsewhere = {id(leg): {on[id(l)] for l in it["legs"] if l is not leg and id(l) in on}
                 for it in routes for leg in it["legs"] if id(leg) in on}
    wanted = {}
    for k, leg in enumerate(rides):
        board, alight = leg["stops"][0], leg["stops"][-1]
        # A train's other trains are those from its own platform to its own: those between
        # other platforms are cards of their own (signature()).
        code = lambda s: (stops.get(s) or [s])[0]
        pair = train_platforms(leg.get("head") or "", code(board), code(alight), leg["dep"]) \
            if (lines.get(leg["line"]) or [None] * 7)[6] == 2 else None
        leg["alts"] = []
        timed, early = {}, {}
        for p in (found.get("r%d" % k) or {}).get("stoptimesForPatterns") or []:
            timed.setdefault(p["pattern"]["id"], []).extend(p["stoptimes"])
        for p in (found.get("b%d" % k) or {}).get("stoptimesForPatterns") or []:
            early.setdefault(p["pattern"]["id"], []).extend(p["stoptimes"])
        # Each line's ways from here to where the ride gets off. Each train run is a route of its own in the ministry's
        # timetable, so a train line is its runs between the same two ends; otherwise a train has no
        # next one, and every route with a train is its line's last departure.
        ways, own_line = {}, leg["line"]
        for p in patterns.get(board, []):
            order = [local_id(x["gtfsId"]) for x in p["pattern"]["stops"]]
            if board in order and alight in order[order.index(board) + 1:]:
                route = p["pattern"]["route"]
                key = key_of(route)
                if local_id(route["gtfsId"]) == leg["line"]:
                    own_line = key
                ways.setdefault(key, []).append((p, order))

        def boarded(st, order):
            """Where in the run a departure from the stop is: a run passing it twice gets there from one pass only, which
            OpenTripPlanner names by its place in the run, counted from one. None when that pass doesn't get there."""
            if order.count(board) < 2 or st.get("stopPosition") is None:
                return order.index(board)
            at = st["stopPosition"] - 1
            return at if 0 <= at < len(order) and order[at] == board and alight in order[at + 1:] else None
        # The line's own next departures there, which say too whether this is its last trip for a while; OTP lists a
        # pattern's departures latest first.
        own = sorted({when(st): st for p, order in ways.get(own_line, []) for st in timed.get(p["pattern"]["id"], [])
                      if when(st) > leg["dep"] and trip_of(st) != leg.get("trip") and boarded(st, order) is not None}.items())
        # Five departures: this one and the next four.
        leg["next"] = [departure(t, st) for t, st in own[:4]]

        def before(choices, until, own_trip=None):
            """A line's departures there from the time asked about up to until, oldest first."""
            return [departure(t, st) for t, st in sorted({
                when(st): st for p, order in choices for st in early.get(p["pattern"]["id"], []) + timed.get(p["pattern"]["id"], [])
                if since - 60 <= when(st) < until and trip_of(st) != own_trip and boarded(st, order) is not None}.items())]
        if k in earlier:
            leg["before"] = before(ways.get(own_line, []), leg["dep"], leg.get("trip"))
        for line, choices in ways.items():
            if line == own_line or line in elsewhere[id(leg)]:
                continue
            # One option a line, whichever of its ways leaves first; a train's, the first between the ride's platforms.
            times = sorted(((when(st), st, p, order) for p, order in choices for st in timed.get(p["pattern"]["id"], [])
                            if leg["dep"] - 300 <= when(st) <= leg["dep"] + ALTERNATIVE_SPAN and boarded(st, order) is not None),
                           key=lambda x: x[0])
            fits = [n for n, (_, st, _, _) in enumerate(times)
                    if pair is None or train_platforms(st.get("headsign") or "", code(board), code(alight), when(st)) == pair]
            if not fits:
                continue
            _, first, p, order = times[fits[0]]
            trip = local_id(first["trip"]["gtfsId"])
            at = boarded(first, order)
            calls = order[at:order.index(alight, at + 1) + 1]
            # The road it takes there, its stops placed from the start of its run so one it passes twice is placed right.
            run = [(x["lat"], x["lon"]) for x in p["pattern"]["stops"][:at + len(calls)]]
            path = stretch(decode((p["pattern"].get("patternGeometry") or {}).get("points") or ""), run, at)
            for x in p["pattern"]["stops"]:
                sid = local_id(x["gtfsId"])
                if sid in calls:
                    stops[sid] = [x.get("code") or "", x.get("name") or "", x.get("lat"), x.get("lon"), x.get("platformCode") or ""]
            r = p["pattern"]["route"]
            route = local_id(r["gtfsId"])
            agency = local_id(r["agency"]["gtfsId"])
            agencies[agency] = r["agency"]["name"]
            lines[route] = catalog["lines"].get(int(route)) if route.isdigit() and int(route) in catalog["lines"] else \
                route_line(agency, r["shortName"] or "", r["longName"] or "", r["type"])
            later = sorted({t: st for t, st, _, _ in times[fits[0] + 1:] if trip_of(st) != trip.split("~")[0]}.items())
            # When its trip set out, as a ride's own "first": the app finds the bus by it. Its times here are predicted, not
            # the timetable's, so they'd point the app at the wrong bus.
            alt = {"line": route, "trip": trip.split("~")[0], "dep": when(first),
                   "sdep": first["serviceDay"] + first["scheduledDeparture"], "live": bool(first["realtime"]), "arr": None,
                   "first": set_out(first), "head": first.get("headsign") or "",
                   "stops": stops_once(calls), "shape": encode(path), "next": [departure(t, st) for t, st in later[:5]]}
            if k in earlier:
                alt["before"] = before(choices, when(first), trip.split("~")[0])
            leg["alts"].append(alt)
            day = datetime.datetime.fromtimestamp(first["serviceDay"] + 43200, ISRAEL).strftime("%Y%m%d")
            wanted.setdefault((trip, day), []).append((alt, alight))
    if not wanted:
        return
    keys = list(wanted)
    try:
        found = ask_in_parts(ask, [ALTERNATIVE_TRIP % (i, t, d) for i, (t, d) in enumerate(keys)])
    except Exception as e:
        log("alternatives failed:", repr(e))
        found = {}
    for i, key in enumerate(keys):
        times = (found.get("t%d" % i) or {}).get("stoptimesForDate") or []
        ids = [local_id(st["stop"]["gtfsId"]) for st in times]
        for alt, alight in wanted[key]:
            # Where it's boarded: a run that passes the stop twice is boarded on the pass that leaves when the option does.
            passes = [n for n, sid in enumerate(ids) if sid == alt["stops"][0]]
            if not passes:
                continue
            on = min(passes, key=lambda n: abs(times[n]["serviceDay"] + (times[n].get("realtimeDeparture") or 0) - alt["dep"]))
            off = next((n for n in range(on + 1, len(ids)) if ids[n] == alight), None)
            if off is not None:
                alt["arr"] = times[off]["serviceDay"] + times[off]["realtimeArrival"]
    # One that never says when it gets there, or gets there before it leaves, isn't offered; the rest as the card
    # lists them, by when they leave.
    for leg in rides:
        leg["alts"] = sorted((a for a in leg["alts"] if a["arr"] is not None and a["arr"] > a["dep"]), key=lambda a: a["dep"])


def first_departure(leg):
    """When the ride's trip leaves its first stop by the timetable, which the predicted times never move: the app finds
    the bus on the road by it. None when the planner doesn't say."""
    try:
        digits = leg["serviceDate"].replace("-", "")
        day = datetime.date(int(digits[:4]), int(digits[4:6]), int(digits[6:8]))
        return ministry_time(day, int(leg["trip"]["departureStoptime"]["scheduledDeparture"]))
    except (KeyError, TypeError, ValueError):
        return None


def set_out(st):
    """first_departure() for one of a stop's departures: when its trip leaves its first stop by the timetable, or None."""
    try:
        day = datetime.datetime.fromtimestamp(st["serviceDay"] + 43200, ISRAEL).date()
        return ministry_time(day, int(st["trip"]["departureStoptime"]["scheduledDeparture"]))
    except (KeyError, TypeError, ValueError):
        return None


def ministry_time(day, seconds):
    """When a trip timetabled seconds into service day `day` sets out, as the ministry stamps it: by the clock, the hour
    the clocks go back taken the second time. A timetable counts from noon less twelve hours, which is the same except
    that night, when the ministry stamps the trips at 00:xx in summer time and those at 01:xx in winter time."""
    at = datetime.datetime.combine(day, datetime.time()) + datetime.timedelta(seconds=seconds)
    return int(at.replace(tzinfo=ISRAEL, fold=1).timestamp())


# The labels, in the order they show: warnings first, then what a route is best at. A route shows two at most.
TAG_ORDER = ("risky_transfer", "long_walk", "long_wait_time", "last_departure", "early_arrival", "direct", "least_walking",
             "low_transfer", "high_frequency", "flexible_route")
TIGHT = 120  # seconds to spare at a change, at most, for a tight connection
LONG_WALK = 1200  # metres walked in all
LONG_WAIT = 1300  # seconds waiting at one change
FREQUENT = 300  # seconds between the first line's departures, on average, at most


def tag(routes, arrive=False):
    """What each route is: its labels, from the routes' own facts. Each route is a dict with dep, arr, rides,
    walk (seconds on foot), walk_m (metres), slack (the least spare time at a change, None without one), wait (the
    longest wait at a change), headway (seconds between the first line's departures, None if unknown), last (rides
    the last trip of a line that day) and choices (every ride could be taken on another line too). Tags lists."""
    if not routes:
        return []
    found = [[] for _ in routes]
    for i, r in enumerate(routes):
        if r["slack"] is not None and r["slack"] <= TIGHT:
            found[i].append("risky_transfer")
        if r["walk_m"] >= LONG_WALK:
            found[i].append("long_walk")
        if r["wait"] >= LONG_WAIT:
            found[i].append("long_wait_time")
        if r["last"]:
            found[i].append("last_departure")
        if r["headway"] is not None and r["headway"] <= FREQUENT:
            found[i].append("high_frequency")
        if r["choices"]:
            found[i].append("flexible_route")

    def one(name, candidates, key):
        if candidates:
            found[min(candidates, key=key)].append(name)

    every = range(len(routes))
    one("early_arrival", list(every), lambda i: (routes[i]["arr"], routes[i]["rides"], routes[i]["walk"], -routes[i]["dep"]))
    one("direct", [i for i in every if routes[i]["rides"] == 1], lambda i: (routes[i]["arr"], routes[i]["walk"]))
    one("least_walking", list(every), lambda i: (routes[i]["walk_m"], routes[i]["arr"]))
    fewest = min(r["rides"] for r in routes)
    if fewest > 1:
        one("low_transfer", [i for i in every if routes[i]["rides"] == fewest], lambda i: (routes[i]["arr"], routes[i]["walk"]))
    return [sorted(set(t), key=TAG_ORDER.index)[:2] for t in found]


def signature(it, lines, stops, platforms=True):
    """A route as a rider tells it from another: each ride's line by its number, wherever it's boarded or left and
    whichever of its trips; a line without a number (a train) by the stations it's taken between, and a train by the
    platforms too where the railway's timetable is at hand (train_platforms()). The list shows each once, the
    line's next departures in its card; between two stations, a card for each pair of platforms, its trains from those
    alone.
    Without platforms, a train by its line instead (where its runs set out and end): the list has one route a line
    sequence, so never a train's line twice between the same stations from two platforms (recipe_list())."""
    out = []
    for l in it["legs"]:
        if l["k"] != "r":
            continue
        line = lines.get(l["line"]) or [""] * 7
        if line[0]:
            out.append((line[6], line[0]))
        else:
            code = lambda s: (stops.get(s) or [s])[0]
            ends = (code(l["stops"][0]), code(l["stops"][-1])) if l["stops"] else ("", "")
            if line[6] == 2:
                ends += (train_platforms(l.get("head") or "", *ends, l.get("dep")) or ()) if platforms else (line[7] if len(line) > 7 else "",)
            out.append((line[6],) + ends)
    return tuple(out)


# The routes list leads with the route quickest there counting each change as twenty minutes more and each minute on
# foot as three, far more often than with the earliest arrival.
RANK_CHANGE, RANK_WALK = 1200, 3
# The list as shown, leaving at a time, counts a change as half an hour (shown_order()): it then leads with a route
# as simple as the recipe's first. choose() still picks by rank().
SHOWN_CHANGE = 1800
# A list leaving at a time with no one-line route: the planner asked again for those alone (no change) over this
# window, and the best within reach joins the list (plan_answer()). Early in the morning the planner's own window can
# end before the first one-line bus, which the list leads with.
DIRECT_WINDOW = "PT2H"
# Leaving at a time, Kav's list is what its recipe picks from every route Kav's planners found (recipe_list()):
# walks no longer than LIST_END_WALK first or last and LIST_CHANGE_WALK at a change, up to LIST_RIDES
# rides, the cheapest of each line sequence by ROUTE_COST, from the cheapest on, leaving out a route that is a chosen
# one plus more rides; the LIST_SHOWN cheapest, and one more when it costs at most LIST_ONE_MORE more than the
# cheapest. The route that gets there soonest joins them when it arrives LIST_SOONER seconds or more before all of
# them, that again for each ride it takes beyond the fewest of theirs; else the soonest with no more rides than that,
# costing at most LIST_TIE more than the dearest of them, when it arrives LIST_SOONER_SIMPLE or more before all of
# them. A one-line route is listed at its next run: the copy getting there LIST_SOONER or more before the cheapest,
# when it costs at most LIST_NEXT_RUN more.
ROUTE_COST = {"ride": 1.0, "change_wait": 0.85, "walk": 3.25, "before": 0.5, "change": 21.5, "train": -22.0, "light_rail": -20.0}
LIST_END_WALK, LIST_CHANGE_WALK, LIST_RIDES, LIST_SHOWN, LIST_ONE_MORE = 2000, 1000, 5, 3, 20
LIST_SOONER, LIST_NEXT_RUN, LIST_SOONER_SIMPLE, LIST_TIE = 300, 10, 60, 5


def rank(it):
    """Where a route goes in the list, leaving at a time: lowest first."""
    return it["arr"] + RANK_CHANGE * max(it["rides"] - 1, 0) + RANK_WALK * it["walk"]


def route_cost(it, when, lines, arrive=False):
    """ROUTE_COST of a route asked about at when, in minutes: riding, 0.85 for each minute waiting at a change, 3.25 for
    each minute on foot and 0.5 for each minute from when to setting out, plus 21.5 a change, less 22 with a train and 20
    with the light rail. Arriving by when (the last way there too), the wait counted is from getting there to when."""
    w = ROUTE_COST
    legs = it["legs"]
    rides = [n for n, l in enumerate(legs) if l["k"] == "r"]
    if not rides:
        return float("inf")
    on_foot = lambda part: sum(max(0, l["arr"] - l["dep"]) for l in part if l["k"] != "r")
    riding = sum(legs[n]["arr"] - legs[n]["dep"] for n in rides)
    before = max(0, when - it["arr"]) if arrive else max(0, legs[rides[0]]["dep"] - when - on_foot(legs[:rides[0]]))
    at_changes = max(0, legs[rides[-1]]["arr"] - legs[rides[0]]["dep"] - riding - on_foot(legs[rides[0]:rides[-1]]))
    kinds = {(lines.get(legs[n]["line"]) or [None] * 7)[6] for n in rides}
    return (w["ride"] * riding + w["change_wait"] * at_changes + w["walk"] * on_foot(legs) + w["before"] * before) / 60 + \
        w["change"] * (len(rides) - 1) + (w["train"] if 2 in kinds else 0) + (w["light_rail"] if 0 in kinds else 0)


def told_apart(routes, lines, stops, first):
    """routes (one a signature()) but those riding a train the railway's timetable names no platforms for, unless the
    first of them by first between the same stations: it can't be told apart from the trains it names, so it's listed,
    as before platforms, only as the best there. Those are mostly the next day's trains in an evening's list, which the
    railway's timetable for the day leaves out."""
    bare = lambda it: tuple(r[:3] if r[0] == 2 else r for r in signature(it, lines, stops))
    blind = lambda it: any(r[0] == 2 and r[3:] == (None, None) for r in signature(it, lines, stops))
    best = {}
    for it in sorted(routes, key=first):
        best.setdefault(bare(it), it)
    return [it for it in routes if not blind(it) or best[bare(it)] is it]


def recipe_list(pool, when, lines, stops):
    """The routes of pool the recipe lists (ROUTE_COST and the rules beside it), cheapest first and the soonest
    last when it joins: a route no listed one shares a line sequence or a card with."""
    def walks_ok(it):
        legs = it["legs"]
        at = [n for n, l in enumerate(legs) if l["k"] == "r"]
        return 0 < len(at) <= LIST_RIDES and all(
            (l.get("m") or 0) <= (LIST_END_WALK if n < at[0] or n > at[-1] else LIST_CHANGE_WALK)
            for n, l in enumerate(legs) if l["k"] == "w")
    pool = [i for i in pool if i["rides"]]
    pool = [i for i in pool if walks_ok(i)] or pool
    cost = lambda i: route_cost(i, when, lines)
    # The cheapest of each line sequence (a train by its line); of those taken between the same platforms, one card.
    once = {}
    for i in pool:
        key = signature(i, lines, stops, False)
        if key not in once or cost(i) < cost(once[key]):
            once[key] = i
    cheapest = {}
    for i in pool:
        if once[signature(i, lines, stops, False)] is not i:
            continue
        key = signature(i, lines, stops)
        if key not in cheapest or cost(i) < cost(cheapest[key]):
            cheapest[key] = i
    for key, best in list(cheapest.items()):
        if best["rides"] == 1:
            runs = [i for i in pool if i["rides"] == 1 and signature(i, lines, stops) == key and cost(i) <= cost(best) + LIST_NEXT_RUN
                    and i["arr"] <= best["arr"] - LIST_SOONER]
            if runs:
                cheapest[key] = min(runs, key=lambda i: (i["arr"], cost(i)))
    chosen = []
    for i in sorted(told_apart(list(cheapest.values()), lines, stops, cost), key=cost):
        if not any(set(signature(c, lines, stops)) < set(signature(i, lines, stops)) for c in chosen):
            chosen.append(i)
    more = chosen[LIST_SHOWN:LIST_SHOWN + 1]
    shown = chosen[:LIST_SHOWN] + [i for i in more if cost(i) <= cost(chosen[0]) + LIST_ONE_MORE]
    if not shown:
        return shown
    fewest, first, dearest = min(i["rides"] for i in shown), min(i["arr"] for i in shown), max(cost(i) for i in shown)
    new = lambda i: signature(i, lines, stops) not in {signature(s, lines, stops) for s in shown} and \
        signature(i, lines, stops, False) not in {signature(s, lines, stops, False) for s in shown}
    soonest = min(pool, key=lambda i: (i["arr"], i["rides"], cost(i)))
    if soonest["arr"] <= first - LIST_SOONER * max(1, soonest["rides"] - fewest) and new(soonest):
        shown.append(soonest)
        return shown
    simple = [i for i in pool if i["rides"] <= fewest and cost(i) <= dearest + LIST_TIE and
              i["arr"] <= first - LIST_SOONER_SIMPLE and new(i)]
    if simple:
        shown.append(min(simple, key=lambda i: (i["arr"], i["rides"], cost(i))))
    return shown


# The list holds routes OpenTripPlanner's own comparison drops: beaten on time by a route that walks more, rides no
# train or starts on another line. So the planner is asked again weighing a route as the recipe does (a second on foot
# costing WEIGHED_WALK, one on a train or the light rail WEIGHED_RAIL), and, as soon as the routes list's own
# answer comes, the same again without the first line of each of that answer's WEIGHED_BANS best routes, all at
# once, each over WEIGHED_WINDOW. Their routes join the others found (plan_answer()), asked beside the routes list's
# own questions and taken if they come within WEIGHED_BY seconds of the trip being asked for. Asked any later, they
# would keep long trips' answers waiting to the cap.
WEIGHED, WEIGHED_WALK, WEIGHED_RAIL, WEIGHED_BANS, WEIGHED_BY = True, 5, 0.6, 3, 6
WEIGHED_WINDOW = "PT40M"
# Beside them, the routes list's own question with WEIGHED_SLACK at every change instead of half a minute: the
# connections with time to spare, which the planner drops beside a tight one setting out later or arriving sooner.
WEIGHED_SLACK = "PT3M"


def same_line(route, lines):
    """A route and every other route of its number and operator (its other way and variants), as excluded() has them."""
    out = {route}
    number, agency = (lines.get(route) or [""] * 7)[0], (lines.get(route) or [""] * 7)[5]
    if number:
        out.update(str(r) for r in catalog["numbers"].get(number, []) if (catalog["lines"].get(r) or [None] * 7)[5] == agency)
    return out


def weighed_answers(frm, to, when, modes, at, ask=None, bans=WEIGHED_BANS, by=WEIGHED_BY, main=None):
    """The planner's answers weighing routes as the recipe does (WEIGHED_WALK, WEIGHED_RAIL, WEIGHED_WINDOW): the
    question as asked, and the same without the first line of each of the best bans routes (rank(), a line once) of
    main(wait)'s answer, the routes list's own, or without main of the first answer; and, asked with the first, the
    routes list's own question with WEIGHED_SLACK at every change; all within by seconds, an answer that fails or
    comes too late left out."""
    ask = ask or ask_planner
    until = time.time() + by
    question = lambda exclude=(): plan_variables(frm, to, when, False, modes, "main", (), WEIGHED_WINDOW, exclude, at,
                                                 WEIGHED_WALK, rail=WEIGHED_RAIL)
    spared = plan_variables(frm, to, when, False, modes, "main", (), None, (), at)
    spared["prefs"]["transit"]["transfer"]["slack"] = WEIGHED_SLACK
    asking = ThreadPoolExecutor(2 + bans)
    try:
        asked = [asking.submit(ask, question(), by), asking.submit(ask, spared, by)]
        try:
            base = main(max(0.1, until - time.time())) if main else asked[0].result(timeout=max(0.1, until - time.time()))
        except Exception as e:
            log("planner failed on a weighed question:", repr(e))
            base = {}
        stops, lines = {}, {}
        firsts = []
        for it in sorted((i for i in itineraries(base, stops, lines, {}) if i["rides"]), key=rank):
            first = next(l["line"] for l in it["legs"] if l["k"] == "r")
            if len(firsts) < bans and not any(first in same_line(f, lines) for f in firsts):
                firsts.append(first)
        left = until - time.time()
        if left >= 0.5:
            asked += [asking.submit(ask, question(sorted(same_line(f, lines))), left) for f in firsts]
        docs = []
        for f in asked:
            try:
                docs.append(f.result(timeout=max(0.1, until - time.time())))
            except Exception as e:
                log("planner failed on a weighed question:", repr(e))
    finally:
        asking.shutdown(wait=False)
    return docs


def shown_order(it):
    """Where a route goes in the list shown, leaving at a time: rank() with SHOWN_CHANGE for each change."""
    return rank(it) + (SHOWN_CHANGE - RANK_CHANGE) * max(it["rides"] - 1, 0)


def choose(found, when, arrive, lines, stops, most=MOST_SHOWN, last=None, fill=False):
    """The few routes worth showing: the earliest arrival, the best without a change,
    the least walking, then the best of the rest by the list's own order (rank()), none the same as another; three at
    least, the best of those a little later when too few arrive soon enough after the best. A route
    with a walk longer than WALK_MOST only when every one has. Arriving by a time, the rest by
    OpenTripPlanner's cost; leaving at a time, by rank(), since that cost leaves out the wait before setting out. last:
    for the last way there tonight, the time it was asked at. fill: leaving at a time, still fewer than three, the rest
    by the list's order with no more changes than the best (plan_answer(), once nothing else is asked)."""
    transit = [i for i in found if i["rides"]]
    if not transit:
        return found[:1]
    short = [i for i in transit if longest_walk(i) <= WALK_MOST]
    if last is not None and short and max(i["dep"] for i in short) < last:
        # The last way tonight, when every route without a long walk has gone: those setting out from then on with a
        # walk up to WALK_LAST.
        short += [i for i in transit if WALK_MOST < longest_walk(i) <= WALK_LAST and i["dep"] >= last]
    transit = short or transit
    if last is not None:
        # And it leaves out the routes that set out more than a minute before it was asked while one is still to come.
        transit = [i for i in transit if i["dep"] >= last - 60] or transit
    by_arrival = lambda i: (i["arr"], i["rides"], i["walk"], -i["dep"])
    by_departure = lambda i: (-i["dep"], i["rides"], i["walk"], i["arr"])
    # Each way once, by its first trip there (its last, arriving by a time): the later ones are the card's next
    # departures. Picked by cost, a later trip whose ride is predicted a minute quicker would win. A
    # train's line once too, whichever platforms its later trains leave from (signature()).
    earliest, once = {}, set()
    for i in sorted(transit, key=by_departure if arrive else by_arrival):
        if signature(i, lines, stops, False) not in once:
            once.add(signature(i, lines, stops, False))
            earliest.setdefault(signature(i, lines, stops), i)
    transit = told_apart(list(earliest.values()), lines, stops, by_departure if arrive else by_arrival)
    best = min(transit, key=by_departure if arrive else by_arrival)
    span = max(best["arr"] - best["dep"], 600)
    within = lambda i, k: i["dep"] >= best["dep"] - k if arrive else i["arr"] <= best["arr"] + k
    fair = [i for i in transit if within(i, span // 2)]
    picks, seen = [], set()
    order = (lambda i: i["cost"]) if arrive else rank

    def add(it, upto=most):
        if signature(it, lines, stops) not in seen and len(picks) < upto:
            seen.add(signature(it, lines, stops))
            picks.append(it)

    add(best)
    direct = [i for i in fair if i["rides"] == 1]
    if direct:
        add(min(direct, key=by_departure if arrive else by_arrival))
    add(min(fair, key=lambda i: (walked(i), i["arr"])))
    for it in sorted(fair, key=order):
        add(it)
    later = [i for i in transit if within(i, LATEST_SHOWN * span) and i["cost"] <= DEAREST_SHOWN * best["cost"]
             and i["rides"] <= best["rides"]]
    for it in sorted(later, key=order):
        add(it, LEAST_SHOWN)
    if fill and not arrive:
        for it in sorted([i for i in transit if i["rides"] <= best["rides"]], key=order):
            add(it, LEAST_SHOWN)
    return picks


def merged(picks, lines, stops, arrive=False):
    """Routes whose every ride can be taken on the same lines, once each ride's other lines are known, as one: they
    show in one card, the lines side by side. The card is the one setting out first (last, arriving by a time),
    leading with the first bus of any of its lines."""
    out, seen = [], {}
    first = lambda it: next((l["dep"] for l in it["legs"] if l["k"] == "r"), it["dep"])
    for it in picks:
        key = []
        for l in it["legs"]:
            if l["k"] != "r":
                continue
            taken = {tuple((lines.get(x["line"]) or [""] * 7)[i] for i in (6, 0)) for x in [l] + l.get("alts", [])}
            key.append(frozenset(taken) if all(n for _, n in taken) else signature({"legs": [l]}, lines, stops))
        key = tuple(key)
        if key not in seen:
            seen[key] = len(out)
            out.append(it)
        elif (first(it) > first(out[seen[key]])) if arrive else (first(it) < first(out[seen[key]])):
            out[seen[key]] = it
    return out


def longest_walk(it):
    return max((l["m"] for l in it["legs"] if l["k"] == "w"), default=0)


def excluded(picks, lines):
    """The routes the picks ride, and every other route of each numbered line among them (its other way and its
    variants): asked again without them, the planner finds the other lines."""
    out = set()
    for it in picks:
        for l in it["legs"]:
            if l["k"] != "r":
                continue
            out.add(l["line"])
            number, agency = (lines.get(l["line"]) or [""] * 7)[0], (lines.get(l["line"]) or [""] * 7)[5]
            if number:
                out.update(str(r) for r in catalog["numbers"].get(number, []) if (catalog["lines"].get(r) or [None] * 7)[5] == agency)
    return sorted(out)


def walked(it):
    return sum(l["m"] for l in it["legs"] if l["k"] == "w")


def facts(it):
    """A route's facts tag() reads."""
    rides = [l for l in it["legs"] if l["k"] == "r"]
    slack, wait = None, 0
    for i, leg in enumerate(it["legs"]):
        if leg["k"] != "r":
            continue
        before = [j for j in range(i) if it["legs"][j]["k"] == "r"]
        if before:
            prev = it["legs"][before[-1]]
            moving = sum(l["arr"] - l["dep"] for l in it["legs"][before[-1] + 1:i] if l["k"] == "w")
            spare = leg["dep"] - prev["arr"] - moving
            slack = spare if slack is None else min(slack, spare)
            wait = max(wait, spare)
    headway = None
    if rides and rides[0]["next"]:
        times = [rides[0]["dep"]] + [n[0] for n in rides[0]["next"]]
        headway = (times[-1] - times[0]) / (len(times) - 1)
    return {"dep": it["dep"], "arr": it["arr"], "rides": len(rides), "walk": it["walk"], "walk_m": walked(it),
            "slack": slack, "wait": wait, "headway": headway, "last": any(not l["next"] for l in rides),
            "choices": bool(rides) and all(l.get("alts") for l in rides)}


def schedule(picks, lines):
    """The timetable card: when a route takes a train or Jerusalem's light rail from where it starts or to where it
    ends, that ride alone, station to station."""
    for it in picks:
        legs = it["legs"]
        rides = [i for i, l in enumerate(legs) if l["k"] == "r"]
        if not rides:
            continue
        for i, walked_m in ((rides[0], sum(l["m"] for l in legs[:rides[0]])), (rides[-1], sum(l["m"] for l in legs[rides[-1] + 1:]))):
            line = lines.get(legs[i]["line"]) or []
            if walked_m <= SCHEDULE_NEAR and len(line) > 6 and (line[6] == 2 or (line[6] == 0 and str(line[5]) == "21")):
                return {"dep": legs[i]["dep"], "arr": legs[i]["arr"], "legs": [legs[i]], "sec": SCHEDULE}
    return None


TAXI_QUERY = """{ plan(from: {lat: %.6f, lon: %.6f}, to: {lat: %.6f, lon: %.6f}, transportModes: [{mode: CAR, qualifier: PICKUP}],
  date: "%s", time: "%s") { itineraries { legs { mode distance duration legGeometry { points } } } } }"""


def taxi_query(frm, to, when):
    """A drive from the nearest street to the nearest street, walking the rest: where a taxi can pick up and drop off."""
    at = datetime.datetime.fromtimestamp(when, ISRAEL)
    return TAXI_QUERY % (frm[0], frm[1], to[0], to[1], at.strftime("%Y-%m-%d"), at.strftime("%H:%M"))


def taxi_card(data, frm, to, when):
    """The taxi card for leaving now: the taxi there in four minutes, then the drive door to door."""
    for it in ((data or {}).get("plan") or {}).get("itineraries") or []:
        car = [l for l in it["legs"] if l["mode"] == "CAR"]
        if not car:
            continue
        start = when + TAXI_PICKUP
        end = start + round(TAXI_SLOWER * sum(l["duration"] for l in car) + TAXI_EXTRA)
        return {"dep": when, "arr": end, "sec": TAXI, "legs": [
            {"k": "x", "dep": when, "arr": start},
            {"k": "t", "dep": start, "arr": end, "m": round(sum(l["distance"] for l in car)),
             "shape": (car[0].get("legGeometry") or {}).get("points") or "", "from": list(frm), "to": list(to)}]}
    return None


class Sections:
    """The other kinds' answers of ask_all(), as a dict of those that came within MORE_WAIT seconds of the routes
    list's: waited for when first read, so the routes list is worked out meanwhile."""

    def __init__(self, asked, until):
        self.asked, self.until, self.got = asked, until, None
        for answer in asked.values():
            answer.add_done_callback(lambda f: setattr(f, "came", time.time()))

    def held(self):
        if self.got is None:
            self.got = {}
            for kind, answer in self.asked.items():
                try:
                    got = answer.result(timeout=max(0.0, self.until - time.time()))
                    if getattr(answer, "came", 0) <= self.until:
                        self.got[kind] = got
                except Exception as e:
                    log("planner failed on", kind, repr(e))
        return self.got

    def __bool__(self):
        return True

    def get(self, kind, default=None):
        return self.held().get(kind, default)

    def __getitem__(self, kind):
        return self.held()[kind]

    def __iter__(self):
        return iter(self.held())

    def __len__(self):
        return len(self.held())


def ask_all(frm, to, when, arrive, modes, now, via=(), window=None, at=(None, None), on_main=None):
    """Every question the trip needs, at once: the routes list's answer, which must come, and the others' by kind,
    which are left out when they come more than MORE_WAIT seconds after it (Sections). on_main(answer): told the
    routes list's answer as soon as it comes."""
    kinds = plan_kinds(frm, to, modes, via)
    pool = ThreadPoolExecutor(len(kinds) + 2)
    try:
        asked = {k: pool.submit(ask_planner, plan_variables(frm, to, when, arrive, modes, k, via, window, at=at)) for k in kinds}
        if now and not via:
            asked["taxi"] = pool.submit(ask_graphql, taxi_query(frm, to, when))
            asked["by_taxi"] = pool.submit(ask_planner, plan_variables(frm, to, when, arrive, modes, "by_taxi"))
        doc = asked.pop("main").result()
        if on_main:
            on_main(doc)
        more = Sections(asked, time.time() + MORE_WAIT)
    finally:
        pool.shutdown(wait=False)
    return doc, more


def by_taxi(it, now, frm):
    """A route that starts with a taxi to the station: picked up four minutes
    after it's ordered, driven at TAXI_SLOWER and there two minutes before the ride. None when it would have
    to be ordered before now, or the drive is longer than TAXI_DRIVE_MOST."""
    legs = it["legs"]
    first = next((i for i, l in enumerate(legs) if l["k"] == "r"), None)
    taken = next((i for i, l in enumerate(legs) if l["k"] == "t"), None)
    # The planner may walk to the kerb first; the taxi picks up where the trip starts.
    if first is None or taken is None or taken > first or any(l["k"] != "w" for l in legs[:taken]):
        return None
    car = legs[taken]
    drive = round(TAXI_SLOWER * (car["arr"] - car["dep"]) + TAXI_EXTRA)
    walks = [dict(l) for l in legs[taken + 1:first]]
    at = legs[first]["dep"] - TAXI_AT_STATION - sum(l["arr"] - l["dep"] for l in walks)
    if drive > TAXI_DRIVE_MOST or at - drive - TAXI_PICKUP < now:
        return None
    for leg in walks:
        leg["dep"], leg["arr"] = at, at + leg["arr"] - leg["dep"]
        at = leg["arr"]
    start = legs[first]["dep"] - TAXI_AT_STATION - sum(l["arr"] - l["dep"] for l in walks) - drive - TAXI_PICKUP
    return dict(it, dep=start, legs=[
        {"k": "x", "dep": start, "arr": start + TAXI_PICKUP},
        dict(car, dep=start + TAXI_PICKUP, arr=start + TAXI_PICKUP + drive, **{"from": list(frm)})] + walks + legs[first:])


def operators(it, lines):
    return {str((lines.get(l["line"]) or [""] * 6)[5]) for l in it["legs"] if l["k"] == "r"}


OFFICIAL_TRIP = """k%d: trip(id: "1:%s") { stoptimesForDate(serviceDate: "%s") { stop { gtfsId } serviceDay scheduledArrival
  scheduledDeparture realtimeArrival realtimeDeparture realtime } }"""


def on_kavs_times(found, when, lines, ask):
    """The official planner's routes at Kav's own times: each ride on its trip as the predicted timetable runs it (its
    next departures too), the walks as long as they were. A route whose first ride has gone by when, whose changes no
    longer leave Kav's margins (changes_kept()), or with a trip the predicted timetable doesn't run so, is left out."""
    keys = []
    for it in found:
        for l in (l for l in it["legs"] if l["k"] == "r"):
            for trip, t in [(l["trip"], l["first"] or l["dep"])] + [(n[1], n[0]) for n in l.get("next") or []]:
                for back in (0, 86400):  # a trip after midnight can belong to the day before
                    key = (trip, datetime.datetime.fromtimestamp(t - back, ISRAEL).strftime("%Y%m%d"))
                    if key not in keys:
                        keys.append(key)
    if not keys:
        return []
    try:
        got = ask("{" + " ".join(OFFICIAL_TRIP % (i, t, d) for i, (t, d) in enumerate(keys)) + "}")
    except Exception as e:
        log("official routes not timed:", repr(e))
        return []
    runs = {}
    for i, (trip, _) in enumerate(keys):
        times = (got.get("k%d" % i) or {}).get("stoptimesForDate") or []
        if times:
            runs.setdefault(trip, []).append(times)

    def timed(trip, board, alight, near):
        """(departure, arrival, scheduled departure, scheduled arrival, live) of trip from board to alight (or at board),
        on the pass setting out nearest near, within three hours; None when the predicted timetable has none."""
        best = None
        for times in runs.get(trip, []):
            ids = [local_id(st["stop"]["gtfsId"]) for st in times]
            for on in (n for n, sid in enumerate(ids) if sid == board):
                off = next((n for n in range(on + 1, len(ids)) if ids[n] == alight), None) if alight else on
                if off is None:
                    continue
                a, b = times[on], times[off]
                t = (a["serviceDay"] + a["realtimeDeparture"], b["serviceDay"] + b["realtimeArrival"],
                     a["serviceDay"] + a["scheduledDeparture"], b["serviceDay"] + b["scheduledArrival"], bool(a.get("realtime")))
                if abs(t[0] - near) <= 3 * 3600 and (best is None or abs(t[0] - near) < abs(best[0] - near)):
                    best = t
        return best

    out = []
    for it in found:
        legs = json.loads(json.dumps(it["legs"]))
        rides = [n for n, l in enumerate(legs) if l["k"] == "r"]
        times = [timed(legs[n]["trip"], legs[n]["stops"][0], legs[n]["stops"][-1], legs[n]["dep"]) for n in rides]
        if not rides or None in times:
            continue
        for k, (n, t) in enumerate(zip(rides, times)):
            leg = legs[n]
            # The way to the first ride moves with its departure; each walk after a ride with that ride's arrival.
            for x in legs[:n] if k == 0 else ():
                x["dep"], x["arr"] = x["dep"] + t[0] - leg["dep"], x["arr"] + t[0] - leg["dep"]
            for x in legs[n + 1:rides[k + 1] if k + 1 < len(rides) else len(legs)]:
                x["dep"], x["arr"] = x["dep"] + t[1] - leg["arr"], x["arr"] + t[1] - leg["arr"]
            nxt = [[(timed(trip, leg["stops"][0], None, at) or [at])[0], trip] for at, trip in leg.get("next") or []]
            leg.update({"dep": t[0], "arr": t[1], "sdep": t[2], "sarr": t[3], "live": t[4], "next": nxt})
        if legs[0]["dep"] + FIRST_LEAN < when or not changes_kept(legs, lines):
            continue
        out.append(dict(it, legs=legs, dep=legs[0]["dep"], arr=legs[-1]["arr"]))
    return out


def plan_answer(doc, when, arrive, ask=None, more=None, frm=None, to=None, again=None, last=None, morning=None,
                earlier=None, reluctant=None, wider=None, official=None, direct=None, weighed=None, rested=None, off=None):
    """Kav's answer: the routes list from doc, OpenTripPlanner's answer for it; and from more, its answers for the
    other kinds of question (plan_kinds, and "taxi" for leaving now), the sections shown besides, each route
    marked with its section's id. again(routes) asks the routes list's question again without those routes. last: for
    the last way tonight, the time it was asked at (choose()). morning() asks the routes list's question for the next
    morning (morning_after()). earlier(routes) asks the last way's question over LAST_DAY_WINDOW, without those
    routes, in the time left. reluctant() asks the routes list's question with walking costing more (WALK_RELUCTANCE_AGAIN).
    wider() asks it over WIDER_WINDOW, in the time left. official() asks it of the planner on the ministry's own times.
    direct() asks it for routes with no change over DIRECT_WINDOW. weighed() gives the planner's answers weighing
    routes as the recipe does (weighed_answers()), whose routes join those found. rested(later) asks the routes list's
    question after the days off (rest_end()), later the next morning's, and says when they end and if on a Saturday.
    off: rest_end() for the time asked, the days off the routes are tagged after."""
    more = more or {}
    stops, lines, agencies = {}, {}, {}
    found = itineraries(doc, stops, lines, agencies)
    errors = [e["code"] for e in ((doc.get("data") or {}).get("planConnection") or {}).get("routingErrors") or []]
    picks = choose(found, when, arrive, lines, stops, last=last)
    # The last way tonight with no route found walking no more than WALK_MOST in one go: the last ones that do, from
    # earlier in the day. The lines found are left out of that question, or on a Friday night they fill the planner's
    # forty routes where the afternoon's last buses were. Nothing is asked after it: the five hours have no route walking
    # as little for other lines to add, and its time is the plan's.
    if earlier is not None and not any(i["rides"] and longest_walk(i) <= WALK_MOST for i in found):
        again = None
        try:
            day = itineraries(earlier(excluded([i for i in found if i["rides"]], lines)), stops, lines, agencies)
            if any(i["rides"] and longest_walk(i) <= WALK_MOST for i in day):
                found += day
                picks = choose(found, when, arrive, lines, stops, last=last)
        except Exception as e:
            log("planner failed on the earlier last ways:", repr(e))
    # Nothing to ride in the planner's own window: the same question over WIDER_WINDOW, and nothing asked after it.
    if wider is not None and not any(i["rides"] for i in found):
        again = None
        try:
            found += itineraries(wider(), stops, lines, agencies)
            picks = choose(found, when, arrive, lines, stops, last=last)
        except Exception as e:
            log("planner failed on a wider window:", repr(e))
    # Fewer routes than LEAST_SHOWN: the planner's answer can be the same lines' trips one after another (sixteen light
    # rail trains, where two bus lines would do too).
    if again is not None and len(picks) < LEAST_SHOWN and any(i["rides"] for i in picks):
        try:
            found += itineraries(again(excluded(picks, lines)), stops, lines, agencies)
            picks = choose(found, when, arrive, lines, stops, last=last)
        except Exception as e:
            log("planner failed on other lines:", repr(e))
    # Still fewer than three leaving at a time, after the other lines: the rest to three, even
    # those that cost more than half as much again as the quickest.
    if not arrive and len(picks) < LEAST_SHOWN and any(i["rides"] for i in picks):
        picks = choose(found, when, arrive, lines, stops, last=last, fill=True)
    # Routes riding somewhere but none within WALK_MOST: asked again with walking costing more, which finds
    # routes riding further. Not from midnight to MORNING, when the soonest routes are the next morning's first, which
    # the morning's question times.
    rode = any(leg["mode"] not in ("WALK", "BICYCLE", "CAR") for edge in ((doc.get("data") or {}).get("planConnection") or {}).get("edges") or []
               for leg in edge["node"]["legs"])
    small_hours = datetime.datetime.fromtimestamp(when, ISRAEL).hour < MORNING
    walked_less = dawned = False
    if reluctant is not None and rode and not small_hours and not arrive and not any(i["rides"] and longest_walk(i) <= WALK_MOST for i in picks):
        try:
            found += itineraries(reluctant(), stops, lines, agencies)
            picks = choose(found, when, arrive, lines, stops, last=last)
            walked_less = any(i["rides"] and longest_walk(i) <= WALK_MOST for i in picks)
        except Exception as e:
            log("planner failed walking less:", repr(e))
    # At night with no route still running within WALK_MOST, the next morning's first routes instead. Beside
    # tonight's found only walking less, the next morning's first route.
    if morning is not None and not arrive and (walked_less or not any(i["rides"] and longest_walk(i) <= WALK_MOST for i in picks)):
        try:
            early = itineraries(morning(), stops, lines, agencies)
            if any(i["rides"] for i in early):
                first = choose(early, when, arrive, lines, stops)
                picks = picks[:MOST_SHOWN - 1] + first[:1] if walked_less else first
                dawned = True
        except Exception as e:
            log("planner failed on the morning:", repr(e))
    # Shabbat or a holiday with nothing to ride: the first routes after it, leaving at a time, or the next morning's.
    after = None
    if rested is not None and not any(i["rides"] for i in picks):
        try:
            for later in (False, True):
                doc_after, ends, saturday = rested(later)
                got = [i for i in itineraries(doc_after, stops, lines, agencies) if i["rides"]]
                if got:
                    picks, arrive, dawned = choose(got, when, False, lines, stops), False, True
                    after = (ends, "after_shabbat" if saturday else "after_holiday")
                    break
        except Exception as e:
            log("planner failed after the days off:", repr(e))
    # Routes on the ministry's own times that the predicted ones missed, at Kav's times and margins (on_kavs_times()):
    # after Kav's own, to MOST_SHOWN, never first.
    added, also = [], []  # the official planner's routes at Kav's times and the one-line ones, for the recipe
    if official is not None and ask is not None and not arrive and len(picks) < MOST_SHOWN and after is None:
        try:
            have = {signature(i, lines, stops) for i in picks}
            fresh = [i for i in itineraries(official(), stops, lines, agencies) if i["rides"] and signature(i, lines, stops) not in have]
            timed = on_kavs_times(choose(fresh, when, arrive, lines, stops, most=2 * MOST_SHOWN), when, lines, ask) if fresh else []
            also += timed
            for it in choose(timed, when, arrive, lines, stops) if timed else []:
                if len(picks) + len(added) < MOST_SHOWN and signature(it, lines, stops) not in have:
                    have.add(signature(it, lines, stops))
                    added.append(it)
        except Exception as e:
            log("planner failed on the ministry's times:", repr(e))
    # No one-line route in the list: the best the planner has with no change, within reach of the soonest route
    # (LATEST_SHOWN of its time), in the list with Kav's own.
    if direct is not None and not arrive and picks and after is None and \
            not any(i["rides"] == 1 for i in picks + added):
        try:
            got = [i for i in itineraries(direct(), stops, lines, agencies) if i["rides"] == 1 and longest_walk(i) <= WALK_MOST]
            soonest = min(picks, key=lambda i: (i["arr"], i["rides"]))
            reach = soonest["arr"] + LATEST_SHOWN * max(soonest["arr"] - soonest["dep"], 600)
            got = [i for i in got if i["arr"] <= reach]
            also += got
            if got:
                picks.append(min(got, key=shown_order))
        except Exception as e:
            log("planner failed on one-line routes:", repr(e))
    # The planner's answers weighing routes as the recipe does: more of the routes the recipe would hold, below.
    if weighed is not None and not arrive and last is None and picks and not dawned:
        try:
            found = found + [i for got in weighed() for i in itineraries(got, stops, lines, agencies)]
        except Exception as e:
            log("planner failed on the weighed questions:", repr(e))
    # Leaving at a time, the recipe picks the list from everything found (not beside the next morning's, which
    # keep their own list).
    recipe = not arrive and last is None and not dawned and any(i["rides"] for i in found + also)
    if recipe:
        picks, added = recipe_list(found + also, when, lines, stops), []
    best = (lambda i: (-i["dep"], i["arr"])) if arrive else (lambda i: (i["arr"], -i["dep"]))
    extra = []
    # Walking or cycling all the way, an hour at most.
    for kind in ("walk", "cycle"):
        got = itineraries(more.get(kind) or {}, stops, lines, agencies)[:1]
        if got and got[0]["arr"] - got[0]["dep"] <= DIRECT_MOST:
            extra.append(dict(got[0], sec=WALK_BIKE))
    # The best way with a bike along on the train or light rail, unless cycling all the way takes less than half as long.
    biked = [i for i in itineraries(more.get("bike") or {}, stops, lines, agencies)
             if i["rides"] and any(l["k"] == "b" for l in i["legs"])]
    cycled = [i["arr"] - i["dep"] for i in extra if i["legs"][0]["k"] == "b"]
    if biked and (not cycled or min(biked, key=best)["arr"] - min(biked, key=best)["dep"] <= 2 * cycled[0]):
        extra.append(dict(min(biked, key=best), sec=BIKE_TRANSIT))
    # A route on East Jerusalem's operators, when it beats every route in the list.
    east = [i for i in itineraries(more.get("all") or {}, stops, lines, agencies) if operators(i, lines) & set(EAST_JERUSALEM)]
    if east and (not picks or best(min(east, key=best)) < min(best(p) for p in picks)):
        extra.append(dict(min(east, key=best), sec=EAST_JERUSALEM_ROUTES))
    # A taxi to the station, leaving now, when it gets there about as soon as the quickest route.
    taxied = [x for x in (by_taxi(i, when, frm) for i in itineraries(more.get("by_taxi") or {}, stops, lines, agencies)) if x]
    if taxied and picks and min(i["arr"] for i in taxied) <= min(p["arr"] for p in picks) + TAXI_LATER:
        extra.append(dict(min(taxied, key=best), sec=TAXI_TRANSIT))
    if ask is not None:
        alternatives(picks + added + [i for i in extra if i["rides"]], stops, lines, agencies, ask, None if arrive else when)
        picks = merged(picks, lines, stops, arrive)
    picks.sort(key=(lambda i: route_cost(i, when, lines, arrive)) if arrive or recipe else shown_order)
    picks += added
    after = after or (off and (off[0], "after_shabbat" if off[1] else "after_holiday"))
    # A route whose first ride sets out after the days off is tagged so first, and those riding during them come before it.
    past = [after is not None and next((l["dep"] for l in it["legs"] if l["k"] == "r"), 0) >= after[0] for it in picks]
    if any(past):
        picks = [it for it, p in zip(picks, past) if not p] + [it for it, p in zip(picks, past) if p]
        past.sort()
    tags = tag([facts(it) for it in picks], arrive)
    tags = [([after[1]] + t)[:2] if p else t for t, p in zip(tags, past)]
    if not arrive:
        for it in picks:
            lean_at_night(it, when)
    extra += [x for x in (schedule(picks, lines), more.get("taxi") and taxi_card(more["taxi"], frm, to, when)) if x]
    shown = picks + extra
    used_stops = {s for it in shown for l in it["legs"] if l["k"] == "r" for leg in [l] + l.get("alts", []) for s in leg["stops"]}
    used_lines = {leg["line"] for it in shown for l in it["legs"] if l["k"] == "r" for leg in [l] + l.get("alts", [])}
    return {
        "it": [{"dep": it["dep"], "arr": it["arr"], "tags": t, "legs": it["legs"]} for it, t in zip(picks, tags)] +
              [{"dep": it["dep"], "arr": it["arr"], "tags": [], "legs": it["legs"], "sec": it["sec"]} for it in extra],
        "stops": {k: v for k, v in stops.items() if k in used_stops},
        "lines": {k: v for k, v in lines.items() if k in used_lines},
        "agencies": {str(lines[k][5]): agencies.get(str(lines[k][5]), "") for k in used_lines},
        "errors": errors,
    }


AREA = re.compile(r"^/live/v1/area/(\d{1,2}\.\d{1,4}),(\d{1,2}\.\d{1,4}),(\d{1,2}\.\d{1,4}),(\d{1,2}\.\d{1,4})$")
STOPS = re.compile(r"^/live/v1/stops/([0-9]{1,6}(?:,[0-9]{1,6}){0,%d})$" % (MAX_STOPS - 1))
LINE = re.compile(r"^/live/v1/line/([^/]{1,40})$")
SHAPE = re.compile(r"^/live/v1/shape/([0-9]{1,9})$")
# The app's own timetable for the seven days from today (rebuild.sh builds it each night, tools/export_web_bundle.py
# KAV_FROM), what it is (manifest.json: its first day, its sha256 and size) and the manifest's signature with the key
# whose public half the app pins (data/Timetable.kt): a phone takes the file only when both check out.
BUNDLE_FILE = re.compile(r"^/live/v1/bundle/(manifest\.json|manifest\.json\.sig|il\.kav\.gz)$")
BUNDLE_TYPES = {"manifest.json": "application/json", "manifest.json.sig": "text/plain", "il.kav.gz": "application/gzip"}
NUMBER = re.compile(r"^[0-9A-Za-z\u05d0-\u05ea]{1,8}$")
# Line and stop links lead browsers to the releases; short trip codes open the saved-trip page.
LINK = re.compile(r"^/[tls](/.*)?$")
RELEASES = "https://github.com/ImNoammm/kav/releases/latest"
# Embedded app icons avoid extra image requests.
KAV_ICON = (
    "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"
    "/w+esQb3B57cHwAAAABJRU5ErkJggg=="
)
MOOVIT_ICON = (
    "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAMAAADVRocKAAABIFBMVEX///////7///3//v///v7//v3//vz//f3//fz//fv//fr+/v79/f3//P3/"
    "/Pv//Pr8/P38/Pz9+vn4+Pj/8+//8Ozz8/P37er/5d3/28/n5+fe3t7/1MT/zLf/wqb/up7Pz8+/v7//tZf/sI7/rYf/poD/oniwsLClpaX/nW//"
    "lWL/jVX/hEX/fTn/dzP/byf/aRT/aASGhob/ZxH/ZwT/ZgT/ZwP/ZgP/ZQP/ZwL/ZgL/ZgH/ZQL/ZQH/ZQD/ZAf/ZAH/ZABkZGT/Ygb/YwD/YQD/"
    "XwX/YAD/XgH/XQP/XQD/XAL/XAD/WwD/WgD/WQL/WQD/WAH/WAD/VwD/VgD/VQBSUlI1NTUfHx8NDQ0HBwgDAwQCAgMBAQIAAAIAAABNBrO5AAAE"
    "zklEQVR42u2ZeVPiSBTAX9ZkCJtMXOQoEDm8kBuVmIwYMDAoiqBsghyik+//Lbajw5BAwG7AP7aKV6UFXcX7db+j+/VrqH6xwAawAWwAG8D/D6Ai"
    "+SqAKoviVaVarVREUVbXDVDFUr3ZfW7f3d7etbrPrfqVWF4joCxVO4/X2dRxJIQkcpTKFNta/VJZE0CVbrTrdNQPFvFF0kW9IZXXAZCreuEoaGp1"
    "b7MuhnGx2wKFvvlj+e6NuDpAav1MoMkzLG1dAc2yADsHp51LdTVAWdZzITR3m/bfDAEgmNHV0ioARdUT30FwUG8K5QY4aN6UlgeUlccYMC6YK4wb"
    "IqeN0rKAsvIUBTcFi8QNocWEBQD1hx6FbfhEBAgV68pSAEk/RD+HzwmRfy/UJQCSlgGBAhzCkS6RA5Sbgp9hMPSbOZFrlogBkhYFFrDEBaHbskoI"
    "kNtZXP1mKKV0kRBQetqjsAEMHSzOO4jmAORWdosBbGEh9XRBBtBiaOH4ACrUrpAA1HrRDzQ+AKjt/J1CALjoZIDAQmYuHM9xM8yJ0QNLEvMe3kkp"
    "ZxlmYe/eOVIdAWrlPjRZgamGm9VvG6bBf10nANSvJy7gIHwSd5g/7FqGKaBzzk5wBCiN3PfxTz1wMjRez6fXwMP+wHir8eNhBtIdERsgtzNAjSfq"
    "HRgjw4gjkm0B5vAvY388jJK5iw+4eEyPXYAsMRq9jt6SdgAHgZfR6wtamWccRolnAoD2B4Cm2jfeDCM8ZSP+n545HAd+AjjDB3QmAB7i/eEgCfy0"
    "D8K2YTKA6YO/JtbwhAMoF+wmQt7l0bAl01IEJpKbWdoe8O9//OzQn5FvkNZEgjwo+ixbEYdUB86TKGQ9HAcchxbjjQfQB5tbsk2ZIJNvg7a9yAM1"
    "w+hN8mq//9oPWPXT4DslyOTqZSdmKyg80DNejJfa/q4XvLsntTcUQWGr39GpeV8hAIgoTq3nGc+F+8YvFJbDQW8wNIxXw6h5rCsQ4JBoN0UlxQ5Q"
    "tsTyng+MifST9rygt7ItmeREu3qcOpKROQLJ3hBN3RgNe0mvPS8YCN5WVRKAhGzkmtr90b/d+EnyJL777hX7mXyoyURnsnlmUgzMIn5/4mZPzBJZ"
    "2SJ1jxzqIg4ltIefOX0EKqpJhHURcrOPxj32ma3sg0xaOl6i2prFrYoiT+S1qVI/9dNYlcUWI+Sb8jLlewJvCSzE9Msl7geqgkoLF4YDGP/pjbLM"
    "DafUygksjVFap3VxuUug6Wc3jocXNV8W3jJ/3Ac/MxIyUKGhLHtPLjXzO4uNhO7iGU1c/qYv6qnFRhLgoCut0EpQRX3hPQHVvA9KeaVmiIpidW42"
    "MIAcsFIzBLmhUfDPczTN7OQepZX7Re28z3nLoJGD9bPVO15nWvbvb7Rjhh1319DxQgQdVQCUQwAddiV1HYCq2E3MEtwoQHHap1htTal7PE0Q0Baq"
    "4LROsQDqO4G2zT/2jKUfs/P7TmBoq368+eO3liU9AeNylUZVSldZZ2v5w9Pp7Y+cRrXA0XMJUz9B913Usj5zX3LBVkrH1k/yfnDWyQeBFcCX1aW1"
    "t/c/CK3CHkAw//n+sOwLiNR4iEYKLRL9hE8spXrzriF/5RtOuVxRql8J2LyjbQAbwAawATjKf97RGCm42pbGAAAAAElFTkSuQmCC"
)
EGG_ICON = (
    "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAMAAADVRocKAAABIFBMVEX///////7+/////v/+/v/+/v79/v7+/f79/f/9/f79/f39/P38/f78/P78"
    "/P37/P78+/37+/77+/36+/76+v329/vy8vrt7fjo6Pbl5Pbi4vXe3/Lb2/HT1e3IzOixt+Khp92ZodqWm9iNldVqlvR8g89lf9ljc8ldbMJgasRV"
    "acVTX75IWr8/Tbc4RrQ1Q7I0Q7E0QrAzQ7AyQrMzQrAyQrA0QbQzQbAyQbAxQa8yQLExQLAxP68wQK8wQK4wP7AwP68vP68xPrAvPq8vPq4uPq8w"
    "PbEuPa8sPK8sO60sOK0pOq4nN60kN60lNK0jM6wgMqshMaseMqweMKsjLK8fLqsdL6kdLKobLqocLakbLKkiKq8hKa8aKaoYJ6kRIKbwybGWAAAF"
    "6klEQVR42u2Xa3uaWBCAxxTTsBtiTbzFG0aWeAFRtjQS0QbXJMSIGlE00aL//1/sHFDTdp+QfumzX5g8UTgD8545M2fOCL3fLBAAAkAACAABIAAE"
    "gAAQAAJAAAgA/yegpbQ7nU672fxpXGu0tE7n61a63b2iSV74uhd/wJ2mjJfWavXNerY19XvzsulY9rfVVmYzs+MpFG2ytPbjqHnwA9zJ5kLk8tl0"
    "JndRfZrIu3FDMdc1ns2mz7eSTnJz10XFtqrFfGavOE9Vx+rbAMWW2I+wlbzwrHZ341eFGPwgF0ul19M7TiX34ziUn5pvAuR5JQEQZWLRWCxKAfDr"
    "luuXMiknyTjtCUVRMeriRenp2roIQEXpV8UR5QOQ7XIYokC5EzkA+hi4pezOv0wDAmlmP80oEMCXdQGiNFCRo72C8vFAG0hJwMchzpVKBfymIlCd"
    "qL324CoJx+RdCH06OTn542ALkF94vIAw2v3zhCjeASgOCwyEqWRtvVhseHz2+EPOvu7JOE+GvJovCaIgCLU0hAlA7t8naTKh2EWFKGr4CuUDaI3E"
    "T+gqgytfkyRpkYND9EeYyAMpjt7QIX69GJso6ywcEoCEDjBoP11b20PT7K+rcOgHkB3ODUDi4flpOh1viPuuHYfYYaC4aeBW07Qvqy2gvshT6EC8"
    "tqgrOF63yv4AbZp3I5AquyIU4Qjvc5Y8L1DRMCQfjbb7XOfbFqDdJ0iYCmvJcDf09B2APj7fJtCrhOF8pVo5wKXOP2/3zw4wNYUQUAyUHG8/vgfQ"
    "+7WEC6BiZ0Ri0UgkchZJSH0COAb2SfMevN4DynBAHUPp5dcAbVOM/ccDFMH0PMjaTXcl9DuyMlsAUKfAO3XXQGOJUfPzYFCLk9yHJFfkXCkWyX/N"
    "mBZwpWlGeJHIY9KGxBwB804LSRglRyWL17xeezF8MwZ3gxSq8YXN3JWXzfoFv/oNp4QW0QXDue5c99dikg7DLosYzARuY6JiuOHw2g+gTnPuDNKj"
    "RlNRms16WXyYzUZd/aGeoMIY70xltlpdccRPL3/nPESISbY2W81E1t3SPgBlSfYBLq84xKg1R2IolkylWeufv3GDnAHCIZXNxN0Lr1Toiyy5QbfT"
    "2TRghP0BrbFIHX3A1WA3g2utuym4IS8ula6OYUbCIbEMDL0vdupEiGAQwKuBnsK3Fi1Z4jINrLSyR0WgKZpJ3A9ueq3BZ5wqQ1GHWJKjcBDfVVP5"
    "mQ9hOaUoHGeOIU5j3voA2oPPSa+qxbK5JJn/GfDkWMH1umPdEo51FFJCBvcKcE4DFXO8ceeNwvLhIwpCwrj11nnQnIgJUvddl2k4PIOC88U9MVu6"
    "LbCpE4BYpvgofaRI+q8RYNTHN3w+EYJQIl/CNMKX47WB/hbAaDyL5wCRKHN6GjnFnCtYt97Dmm2Opg9CuSwOR5uiW0PLE1wJrf80sCeSUK5Kk/kw"
    "DVSYylr624e+0Zi0CondHs6WnK5X37oDXrRtczAeD7AoxzE4VMok3JHI3y8mA3M8MoZ4auC5R7LCp6swlD52D8U8ClcZzlVvMpgt8CnPizNruJI4"
    "UlCieJiinToaTV1UWjPLmgks2UXHZzVT82282vJos7Gs5+Vm2d5lg7JmsRjh+ufz2TiJaAQyQ0PH8iXFMe0gmcvn0m6kSezldzo7Q7ysSn2pein2"
    "je1BMRLjUZqJeAvHkAgnayOsP8rigo6Fo96JTzGY1cA6mn9nd/Mo/oWiXeJH9dHripo2RwxHGE/Ql1xtIiNdt7Jkz23HsQpA4amrvweoEoBEPi77"
    "N7tCW2ETrwX8nDNHDeKd/nDF5/Z9GnzIV5xe+53mt2t8xsmLxI9LaQfo9SbLq1IBO8rzTK5QGi4M1dgeUi+2yLO5zHk6m+cEe6Lo73bXXeOx13/s"
    "9vv3j/3XBlptPiznlk2a4vni9tWMrqgj54n0vra1HKvqr7Tv3e5t7wb/bm+63w/rskq6emzrlZ+6/UaLKLSWrAY/oQJAAAgAASAABIAAEAACQAD4"
    "jfIvPZJwhgk0bdIAAAAASUVORK5CYII="
)
BUS_NEARBY_ICON = (
    "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAMAAADVRocKAAABIFBMVEX////+//7+/f78+/38+v328Prl2/DTxebEpt6shNG3adG8Z9K6Z9G5ZtG4"
    "ZtG3ZdC1ZdC0ZM+zY86xY86wYs6vYs2uYc2lYsqtYMyrYMyqYMupX8unXsqmXcqkXcmjXMihW8igW8eeWsacWcaeVsaZWMSXV8SWVsOUVcKSVMGS"
    "U8GOVMCNUsCLUr+LUb6IUL6NTb+HT72FTryFSryCTbuATLp/S7p9S7l8Srh6Sbh5SLd3SLd2R7ZzR7V3RbZyRbVwRbRvRLNsQ7JtQrNpQbFzPLRn"
    "QLBnOrBkQK9iPq5gPa5ePK1bO6xZOqtYOapWOKlVOKlTN6lSN6hSNqhRNqdPNqdUNKlONaZNNKZMM6VKM6VJMqRIMqRJMaRUK6hFMaNNJ6fJAAAI"
    "a0lEQVR42rWYCXvaSBKGKzJnJjNxnElYxw42RogbhLmNuS9zJtnZYTLZRPP//8VWdevobslJnn3MC7Sqvy7Vh7obYQO/HBk4usGLF7+8eEGNfXjq"
    "GH4lXvzq8cQx/HZk4OXL317a/HaMGE5PX52esubl6TFiOH3lcYwYzs5enZ1RQ4+nj8/g9euzM3yxw9kRYnj9u8Pr348Rw9u3bwhq3x4jRoPjAufn"
    "//I4QowGxwUuLi7e2Q/+/Nn44t3523OahHM+F+coBuSTwcXF5YXHz8bv3usyl++C8uHy/+RCP4/JnOvvAvLQ4P17PL6nx/tHY4aoX+qxMMiEY3rA"
    "uZD8KfSrS6l/qccBNKk+9uL6pe9MuLpKJq+wsQ9BcfJKf36pJwU9mXoT1jQIi6BDRE/66qDBj9GjWjihJz0hqUfgBGL6tcuVHgMIvdZ958K1kPRI"
    "rCfw8iP6lasnCzGsH8lep1L2k0kQesOSpDqQ+gn0KIQgVriyu9f68xONrunazbgqxNkUXfvOhZsfk9ITOMNaPHvNuzdsguKFaylD8zJE0CCNzzQ/"
    "8IZDR66nU/j+TiCcYkPplEkXFC2k0t65aEnXmBLr8BjSfnAgm8Ukj5QZo5pmikaZW0QXEmxL8yagWJBBxkzF4+mCpOXYtqESRhJn4+R5wat2Y9Ka"
    "h28y6SCDjA/DiIW1k3CskPa0dC4R0rRQIpdOm7aVN1ZgY8/FfA8wDKxoUFkCD5lClH80Y2ba1Y00e5eRQoZNVsTM8BHKzxrcMi3XsWM0yGYNarIG"
    "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"
)
# Map glyphs from the app's ModeIcons.kt.
TRIP_MODES = {0: ['M15 0h-4C9.528 0 8.238 0.808 7.544 2H8.77C9.32 1.388 10.115 1 11 1h4c0.885 0 1.68 0.388 2.23 1h1.226C17.762 0.808 16.472 0 15 0z', 'M14.5 0.5h1l-2 3.5h-1z', 'M11.5 0.5h-1l2 3.5h1zM17 4H9C6.791 4 5 5.791 5 8v9c0 2.209 3.582 4 8 4s8-1.791 8-4V8c0-2.209-1.791-4-4-4zM8 17c-0.552 0-1-0.448-1-1s0.448-1 1-1 1 0.448 1 1-0.448 1-1 1zm10 0c-0.552 0-1-0.448-1-1s0.448-1 1-1 1 0.448 1 1-0.448 1-1 1zm1-5s0 2-6 2-6-2-6-2V8c0-1.105 0.895-2 2-2h8c1.105 0 2 0.895 2 2v4zM8.349 21.646L6.004 26h1.704l2.113-3.926zm7.83 0.428L18.292 26h1.704l-2.345-4.354z'], 1: ['M13 4c-4.971 0-9 3.358-9 7.5v5C4 18.847 5.612 21 7.6 21h10.8c1.988 0 3.6-2.153 3.6-4.5v-5C22 7.358 17.971 4 13 4zM7.5 19c-0.552 0-1-0.448-1-1s0.448-1 1-1 1 0.448 1 1-0.448 1-1 1zm11 0c-0.552 0-1-0.448-1-1s0.448-1 1-1 1 0.448 1 1-0.448 1-1 1zm1.5-4H6v-3.5C6 8.467 9.14 6 13 6s7 2.467 7 5.5V15zm-3 7l0.536 1H8.464L9 22H7.144L5 26h16l-2.144-4H17zm-9.608 3l0.536-1h10.144l0.536 1H7.392z', 'M23.5 20.5c1.431-2.089 2.5-4.777 2.5-7.5 0-7.18-5.82-13-13-13S0 5.82 0 13c0 2.723 1.069 5.411 2.5 7.5V17C2.046 15.796 2 14.361 2 13 2 6.935 6.935 2 13 2s11 4.935 11 11c0 1.361-0.046 2.296-0.5 3.5v4z'], 2: ['M21 7.177C21 4.623 19.142 3.6 16.569 3.6H16.2V2.8c0-0.442-0.358-0.8-0.8-0.8h-4.8c-0.442 0-0.8 0.358-0.8 0.8v0.8H9.308C6.735 3.6 5 4.623 5 7.177V16.4l1.412 1.592-0.731 0.829c-0.333 0-0.434 0.619-0.132 0.779 0 0 7.367 1.644 7.451 1.6l7.327-1.615c0.302-0.16 0.202-0.767-0.132-0.767L19.4 17.92 21 16.4V7.177zM9.4 18.4c-0.663 0-1.2-0.537-1.2-1.2 0-0.663 0.537-1.2 1.2-1.2 0.663 0 1.2 0.537 1.2 1.2 0 0.663-0.537 1.2-1.2 1.2zm7.2 0c-0.663 0-1.2-0.537-1.2-1.2 0-0.663 0.537-1.2 1.2-1.2 0.663 0 1.2 0.537 1.2 1.2 0 0.663-0.537 1.2-1.2 1.2zm2.4-5.765c0 0.71-2.431 1.714-6 1.714s-6-1.004-6-1.714V7.597C7 6.651 7.48 5.5 8.5 5.5h9c0.995 0 1.5 1.151 1.5 2.097v5.038zM7.5 25h11v1h-11zm0-2h11v1h-11z', 'M8.349 21.646L6.004 26h1.704l2.113-3.926zm7.83 0.428L18.292 26h1.704l-2.345-4.354z'], 3: ['M22.5 7V6c0-2.303-0.638-4-2.952-4H6.714C4.4 2 3.5 3.697 3.5 6v1H2v4h1.5l0.071 11.429C3.571 23.296 4.275 24 5.143 24H7c0.868 0 1.5-0.632 1.5-1.5V21h9v1.5c0 0.868 0.632 1.5 1.5 1.5h1.857c0.868 0 1.571-0.704 1.571-1.571L22.5 11H24V7h-1.5zM6.714 19.278c-0.864 0-1.571-0.704-1.571-1.564 0-0.86 0.707-1.564 1.571-1.564s1.571 0.704 1.571 1.564c0.001 0.86-0.706 1.564-1.571 1.564zM13 15c-3.866 0-7.5-1.605-7.5-2.381V5.968C5.5 4.934 5.895 4 7 4h12.25c1.105 0 1.25 0.934 1.25 1.968v6.651C20.5 13.395 16.866 15 13 15zm7.857 2.714c0 0.86-0.707 1.564-1.571 1.564s-1.571-0.704-1.571-1.564c0-0.86 0.707-1.564 1.571-1.564s1.571 0.704 1.571 1.564z'], 4: ['M22.257 10.03C21.812 8.249 20.212 7 18.377 7H15.5V4.5C15.5 4.224 15.276 4 15 4s-0.5 0.224-0.5 0.5V7H11V4.5C11 4.224 10.776 4 10.5 4S10 4.224 10 4.5V7H7.623c-1.835 0-3.435 1.249-3.881 3.03L2.5 13h21l-1.243-2.97zM8.5 11H5.438S6.522 9 7.44 9H8.5v2zm4 0h-3V9h3v2zm4 0h-3V9h3v2zm1 0V9h1.06c0.918 0 2.002 2 2.002 2H17.5zm-8.834 8.5c2.11 0 3.135-0.237 4.221-0.487 1.093-0.252 2.222-0.513 4.446-0.513 2.224 0 3.354 0.261 4.446 0.513 0.433 0.1 0.86 0.197 1.347 0.278L26 14H1l-1 4.5c2.223 0 3.353 0.261 4.446 0.513 1.085 0.25 2.11 0.487 4.22 0.487zm14.459 1.291c-0.487-0.081-0.913-0.178-1.347-0.278C20.687 20.261 19.557 20 17.333 20s-3.353 0.261-4.446 0.513C11.801 20.763 10.776 21 8.666 21c-2.109 0-3.135-0.237-4.22-0.487C3.353 20.261 2.223 20 0 20v1c2.11 0 3.135 0.237 4.22 0.487C5.313 21.739 6.442 22 8.666 22c2.224 0 3.354-0.261 4.446-0.513C14.198 21.237 15.223 21 17.333 21s3.135 0.237 4.221 0.487C22.646 21.739 22.776 22 25 22v-1c-1.268 0-1.141-0.086-1.875-0.209z'], 5: ['M5 26h1.856L9 22H7.144zm12-4l2.144 4H21l-2.144-4zM4.5 20h17v1h-17zM23 10v8h-1V5c0.552 0 1-0.448 1-1s-0.448-1-1-1h-4.5V2H18c0-1.105-2.239-2-5-2S8 0.895 8 2h0.5v1H4C3.448 3 3 3.448 3 4s0.448 1 1 1v13H3v-8H2v9h22v-9h-1zm-3-5v6h-4V5h4zm-6.035 10.25C13.853 15.681 13.465 16 13 16c-0.465 0-0.853-0.319-0.965-0.75C12.015 15.17 12 15.087 12 15c0-0.552 0.448-1 1-1s1 0.448 1 1c0 0.087-0.015 0.17-0.035 0.25zM11 11V5h4v6h-4zm-1-6v6H6V5h4z'], 6: ['M12.5 2.5h1V7h-1z', 'M12.5 2.5h1v6h-1z', 'M12.5 7.5h1l-2 2.5h-1z', 'M13.5 7.5h-1l2 2.5h1zM22 1.01L4 5V3.99L22 0zM18 10H8c-2.2 0-5 2.287-5 4.471v5.967C3 22.352 4.484 24.094 6.619 25c0.932 0.458 1.294 1 3.381 1h6c2.087 0 2.449-0.542 3.381-1C21.516 24.094 23 22.352 23 20.438v-5.967C23 12.287 20.2 10 18 10zm3 4.471V18h-4v-6h1c1.215 0 3 1.513 3 2.471zM16 12v6h-6v-6h6zM5 14.471C5 13.513 6.785 12 8 12h1v6H5v-3.529z'], 7: ['M23 6.799C23 4.673 21.159 3 18.889 3H7.111C4.841 3 3 4.673 3 6.799v12.849L23 15.7V6.799zM21 13H5V7.121C5 6.044 5.947 5 7.111 5h11.778C20.053 5 21 6.044 21 7.121V13z', 'M9 4.958h1V5H9zM9 5h1v8H9zm7-0.042h1V5h-1zM16 5h1v8h-1zm8 12.021l-22 4.5v1.958l22-4.5z']}
# A general chat preview: the private fragment is unavailable to preview fetchers.
TRIP_PREVIEW = base64.b64decode(
    "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"
    "JpMpICDAhepBC0sl4eOPPx4TE1NSUiJ4rCeh57Rv337t2rV16tSxlvMjjzwyffp0vccpLS1dunTpI4880q9fv8TExLCwsMDAwNq1a48YMeKbb74pLS21"
    "bNa8efN58+YFBgY6HuHkyZNr1661fO3v7z9x4kQt5x0/fnxISIjl661bt+7fv5/3PwAAAAiEPhgI3c5oNF65cmXMmDGdOnVSmYleraANBrPZ3KhRo9tu"
    "u81zw416rlR79eq1atUqa12cyWS68847Z86cqesg6enp06ZNq1mz5vXXXz9z5szVq1efP38+Pz+/pKQkLS1t4cKFt99+e5cuXaxzGLZu3fq5556TPZRt"
    "/d7tt9+u5eyTJ0+W3R0AAACotkTfu6Vbbrll9+7daWlpbumtZ51m0GQybdiwoVWrVnrbi1qZzWZRFM+fP9+xY8eCggLLQdyS4oxGY1xcXKtWrebOneuW"
    "Mpw3b96oUaMsX//000/ffPPNnDlzgoODLUtKSkpuueWWX3/91UOvYHJy8rZt24KCggRBuHTpUmJiYnFxsd02oaGhFy5cCAsLs3ybkpKyd+9elWM2btz4"
    "2LFjlq+Li4tr1aqVlZXF+x8AAADVHIPKOGGtHhw/fnzr1q1dToPCP5WEiYmJd999d25urqUnYdUvgS5dusyfP9+aBgsKCkaMGOG5NCgIwv79+z/77DPL"
    "13FxccOHD3fcJj8/f86cOdZvnVYS2m4wf/580iAAAAAg+HCTUcF9bUdLSkqioqKeeOIJyyijFbk2S0/CBx54IDExsaioSHB3A1dPlGeDBg38/f0tX+fk"
    "5AwaNGjZsmWefhFtA2e3bt1kt/nqq6+sX99yyy1+fn4qxX7rrbdav6W9KAAAAODjgdCNvQdzc3NvvfXWZs2amc1mg6FCJWapJKxRo8a9996bn5/v9p6E"
    "ni7b4ODg+Pj4SngR9+zZY/26Y8eOstusXbv2xIkTlq8TEhKuv/56paP169evfv36lq/T0tIqIdACAAAABELvzoSWQ5WUlMTGxj722GMVrx60ZkJJku69"
    "994GDRoUFhZajlmV0+ClS5esX/v7+//888+jR4/29CuYnZ1tNpstX9eqVUvpVZ41a5b1W9sxY+zYthedNWuWlikTAQAAAAJhtWapHszPz7/rrrvq169f"
    "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"
    "/TjDho1SGtBl8+YNbr/9/fv3XLokP7TMHXf8S+PAoe3adejdu58LZ/fSQgPgvVnR8VuNY8nINxYt/20F0qBUsTRoVyEoKRzcSdgjDQIEQp9SVFS0bNli"
    "2VWhoaEvvPCa3vof7WSH8hdFQ/v2mtphhoWFPf74cy6c98KFtIMH98muGj58dMOGmoaWGT36xrp167uxNA4dOnDsmNYGe5IkLV5c2dMPXr4sPwRl3br1"
    "dB0nPf3C/v17ZFe1atVmwIDrtRwkOjpGZRLLP/9c6vbblyRJacrHiIjIF1983Wl1cb169Z955kXXzn7NC+2pp6YtXbrO7s+SJWvvuedBfooCXhr5NA8u"
    "6rBFuZwnamosKrqYBhWDmKBpWFGZSSb0pkGJNAgQCKuBX3/9UamhZpMmTd98879xcfEuHLZevQb//vcj33zz85AhI2Q3OHv2tOzy226709/fX/3gERGR"
    "r7/+bnx8Dddu+ffff5NdbjQaX375zdjYOPXdu3TpPmXKVLe/EAsX/qZxy927d54/f66Sn5PU1POyy7t376X3UPPnz1Fa9dBDT7Rq1UZ999DQ0Ndeeyc8"
    "XH4OwK1b/5IdtbXifv99bmFhoeyq5OTWb745MzGxnsoz89Zb70dGRrl89mtbaF269JD79Y3YtWt3foQCPp0VxXJBUFCqL3Q6z4SLaVCtsahcUnOYUFCp"
    "wyFpECAQwkZ6+oU5c35SWpuUlPx///f5sGGjLXPiOZWQUHPEiDEzZrz/2Wffjhw5tmbNWkqBYceObbLLGzVqMn36ayrTzbdr12HmzI+bNUty+ZbXrFmR"
    "liYfb2rUqPnOO/+XlNRK/t9JURw5cpxSv74KWrlyuVKzTDt//DG/8p+TY8eOyC7v2bPPI4881aZN++joGKdJ3mLdujVnzpySXRUQEPDKK2+p9Mpr0qTp"
    "u+9+pDJzw/fff+OhEsjNzZk9e5bKO+Xjj79+/PFnu3TpnpBQMyAgIDQ0tG7d+kOGDH/jjZkvv/ymrh6qPlNoAKp85LOLfs6rCeUbi/5j6fh0y58l4y/+"
    "/eemi6JLaVCQz2xKjUUdB5LRPQE9aRDwqZ90vm3p0nWOC4uKikaNGujaAf39/d9//1P16dEyMy/99df63bt3nTx5LCcnJy8v12j0Cw0NDQkJrVEjoUGD"
    "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"
    "Fl5cXHT27JnNmzesX79WaQJDO3Pm/Hj+/Ln+/QcmJtYTRTE7+8rhwweXLl0YEBDoGAglSfr884/279/Tt++AOnXqiqKYkZG+Y8eWJUsW3nrrnXYb//DD"
    "15YEkphYLyYmJigoKC8v78yZU3/9tWHz5o0VHwDz4MH9zz77aLduvdq2bV+3bv3w8PCyMlN29pVjxw5v2bJp/361VyQz89IPPzwzbNiolJS20dGxJpPp"
    "9OmTy5YtslZvXsOXXle5aX8AJEn66KOZAwcO7datR3x8gtlsSktL3bx548qVy/71r/tduM6NG9cdOXLouuuuT05uHRsbJwjS5cuZp0+f2rhx7blzZ675"
    "ewcAAAAA7D322LOff/79uHETKAoAAAD4HgaVAQAAAAACIQAAAACAQAgAAAAAIBACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAHzR/wMBr+GaMuS/WAAAAABJRU5ErkJggg=="
)

TRIP_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark"><meta name="robots" content="noindex">
<meta name="referrer" content="no-referrer">
<title>A trip shared with you · Kav</title>
<meta name="description" content="See the route, stops and map. Open your trip in Kav or your transit app.">
<meta name="theme-color" content="#000000">
<meta property="og:type" content="website"><meta property="og:title" content="A trip shared with you · Kav">
<meta property="og:description" content="See the route and stops. Choose your transit app and go.">
<meta property="og:image" content="https://kav.noammm.uk/t/preview.png">
<meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">
<meta property="og:image:alt" content="Kav. Your trip, ready to go.">
<meta name="twitter:card" content="summary_large_image">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" integrity="sha384-sHL9NAb7lN7rfvG5lfHpm643Xkcjzp4jFvuavGOndn6pjVqS6ny56CAt3nsEVT4H" crossorigin="anonymous">
<style>
*{box-sizing:border-box}html{background:#000}
body{margin:0;height:100svh;min-height:100svh;background:#000;color:#f2f2f7;font:16px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,Arial,sans-serif;
display:flex;justify-content:center;padding:max(16px,env(safe-area-inset-top)) 20px max(16px,env(safe-area-inset-bottom))}
main{width:100%;max-width:460px;display:flex;flex-direction:column;align-items:center}
.brand{display:flex;align-items:center;gap:12px;width:100%;margin-bottom:20px}
.brand,#apps,.get,#trip-note{flex-shrink:0}
.github{margin-inline-start:auto;width:44px;height:44px;display:grid;place-items:center;color:var(--muted)}
.github svg{width:28px;height:28px;fill:currentColor}
.icon{width:48px;height:48px;display:block}
h1{font-size:22px;margin:0;font-weight:700}.via{color:#a1a1aa;margin:2px 0 0;font-size:13px}
h2{width:100%;font-size:27px;line-height:1.25;letter-spacing:-.6px;font-weight:650;margin:0 0 24px;overflow-wrap:anywhere}
a.go{display:flex;align-items:center;gap:14px;width:100%;padding:14px 16px;margin:0 0 12px;border-radius:18px;
background:#111113;border:1px solid #2c2c2e;color:inherit;text-decoration:none;font-size:18px;font-weight:600}
a.go{transition:background .18s,border-color .18s,transform .18s}a.go:hover{background:#1c1c1e;border-color:#48484a}a.go:active{background:#252528;transform:scale(.985)}.mark{width:40px;height:40px;border-radius:10px;flex:none;display:block}
.go span{flex:1}.go small{display:block;color:#a1a1aa;font-size:12px;font-weight:400;margin-top:3px}.go svg.chev{flex:none;opacity:.6}[dir=rtl] .go svg.chev{transform:scaleX(-1)}
.get{color:#8e8e93;font-size:14px;text-align:center;margin:16px 0 0}.get a{color:#bf5af2}
#preview{width:100%;flex:1 1 320px;min-height:120px;max-height:322px;border-radius:22px;overflow:hidden;border:1px solid #2c2c2e;background:#000}
#map{height:100%;width:100%;background:#000;isolation:isolate}
#map .leaflet-tile-pane{filter:brightness(.65) contrast(1.45)}
#trip-note{margin:12px 0 0;font-size:14px;color:#c7c7cc;text-align:center}
.leaflet-container{font-family:inherit}.leaflet-control-attribution{font-size:10px!important;background:#111c!important;color:#ccc}
.leaflet-control-attribution a{color:#ccc}.leaflet-tooltip{background:#111113;color:#fff;border-color:#48484a}
.stop-icon{width:28px;height:28px;border-radius:50%;background:var(--route);border:3px solid #111;display:grid;place-items:center;color:#fff}
.stop-icon svg{width:16px;height:16px}.end-icon{width:24px;height:24px;background:#bf5af2;border:3px solid #111;border-radius:50% 50% 50% 0;transform:rotate(-45deg)}
.end-icon:after{content:'';display:block;width:8px;height:8px;border-radius:50%;background:white;margin:5px}
dialog{background:#171719;color:#f2f2f7;border:1px solid #38383a;border-radius:24px;padding:30px 24px;width:calc(100% - 40px);max-width:360px;text-align:center}
dialog::backdrop{background:#000b;backdrop-filter:blur(5px)}
dialog .launch-title{display:flex;align-items:center;justify-content:center;flex-wrap:wrap;gap:10px;font-size:20px;font-weight:600;margin:16px 0}
dialog .launch-title img{width:36px;height:36px;border-radius:9px}dialog a{color:#cf83ff;display:inline-block;padding:12px 0}
dialog button{position:absolute;right:8px;top:8px;width:44px;height:44px;background:none;color:#aaa;border:0;font-size:26px;cursor:pointer}
dialog button:focus-visible,a:focus-visible{outline:2px solid #cf83ff;outline-offset:3px}
@media(prefers-reduced-motion:no-preference){
.brand,h2,#preview,.get,.go{animation:appear .4s cubic-bezier(.2,.8,.2,1) both}
h2{animation-delay:.04s}#preview{animation-delay:.08s}.get{animation-delay:.12s}
.go{animation-delay:calc(.16s + var(--i,0)*.05s)}
dialog[open]{animation:appear .2s ease-out}dialog[open]::backdrop{animation:dim .2s ease-out}
dialog.closing{animation:leave .18s ease-in both}dialog.closing::backdrop{animation:dim .18s ease-in reverse both}
#fallback:not([hidden]){animation:appear .2s ease-out both}
#map.ready .leaflet-overlay-pane{animation:mapIn .5s ease-out both}
@keyframes appear{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:translateY(0)}}
@keyframes leave{from{opacity:1;transform:translateY(0)}to{opacity:0;transform:translateY(12px)}}
@keyframes mapIn{from{opacity:0}to{opacity:1}}@keyframes dim{from{background:#0000}to{background:#000b}}}
@media(prefers-reduced-motion:reduce){a.go{transition:none}}

@media(max-height:500px) and (min-width:560px){
body{padding:12px}main{max-width:760px}.brand{margin-bottom:12px}.icon{width:40px;height:40px}
#apps{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
a.go{margin:0;padding:10px;font-size:16px}.mark{width:32px;height:32px}#preview{min-height:70px;margin-top:12px}
.get{margin-top:10px}}

:root{--page:#000;--ink:#f5f5f7;--muted:#c7c7ce;--card:#0e0e10;--edge:#ffffff29;--hover:#17171a;--accent:#bf5af2;--link:var(--accent)}
:root[data-theme=dark]{--page:#101012;--card:#1c1c1f;--edge:#ffffff1f;--hover:#26262a}
:root[data-theme=light]{--page:#f2f2ef;--ink:#16161a;--muted:#494951;--card:#fff;--edge:#dcdcd9;--hover:#e6e6e2;--link:color-mix(in srgb,var(--accent) 45%,#000)}
html,body,#preview,#map{background:var(--page)}body,dialog{color:var(--ink)}
.via,.go small,.get,#trip-note,dialog button{color:var(--muted)}
a.go,dialog{background:var(--card);border-color:var(--edge)}a.go:hover,a.go:active{background:var(--hover);border-color:var(--edge)}
.get a,dialog a{color:var(--link)}.end-icon{background:var(--accent)}
.go svg.chev path{stroke:currentColor}a:focus-visible,dialog button:focus-visible{outline-color:var(--link)}
:root[data-theme=light] #map .leaflet-tile-pane{filter:none}
</style></head><body><main>
<header class="brand"><img class="icon" alt="" src="data:image/png;base64,KAV_ICON">
<div><h1>Kav</h1><p class="via" id="via">Shared via Kav</p></div><a class="github" href="https://github.com/ImNoammm/kav" aria-label="GitHub"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 .3a12 12 0 0 0-3.8 23.4c.6.1.8-.3.8-.6v-2.2c-3.3.7-4-1.4-4-1.4-.5-1.4-1.3-1.7-1.3-1.7-1.1-.8.1-.8.1-.8 1.2.1 1.8 1.3 1.8 1.3 1 1.8 2.8 1.3 3.4 1 .1-.8.4-1.3.8-1.6-2.7-.3-5.5-1.3-5.5-6a4.7 4.7 0 0 1 1.2-3.2c-.1-.3-.5-1.6.1-3.2 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 0C17 5.1 18 5.4 18 5.4c.6 1.6.2 2.9.1 3.2a4.7 4.7 0 0 1 1.2 3.2c0 4.7-2.8 5.7-5.5 6 .4.4.8 1.1.8 2.2v3.1c0 .3.2.7.8.6A12 12 0 0 0 12 .3z"/></svg></a></header>
<h2 id="title"></h2>
<div id="apps" style="width:100%"></div>
<section id="preview" hidden><div id="map" aria-label="Trip map"></div></section>
<p id="trip-note" role="status" hidden></p>
<p class="get" id="get"><a href="RELEASES">Get Kav</a></p>
</main>
<dialog id="opening" aria-labelledby="opening-title"><button id="dismiss" aria-label="Close">×</button>
<p class="launch-title" id="opening-title" aria-live="polite"><span id="opening-word"></span><img id="opening-logo" alt=""><span id="opening-name"></span></p>
<p id="fallback" hidden><span id="didnt"></span> <a id="fallback-link" target="_blank" rel="noopener noreferrer"></a></p></dialog>
<script>
(async function () {
  var p = {}, unavailable = false, code = location.pathname.slice(1);
  if (/^[A-Za-z0-9]{7}$/.test(code)) {
    try {
      var response = await fetch("/api/share/" + code, {cache: "no-store"});
      if (!response.ok) throw new Error("Shared trip unavailable");
      p = await response.json();
    } catch (e) { unavailable = true; }
  }
  function point(v) {
    var m = /^(-?\\d{1,3}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)$/.exec(v || "");
    if (!m) return null;
    var a = +m[1], b = +m[2];
    return a >= -90 && a <= 90 && b >= -180 && b <= 180 ? [a, b] : null;
  }
  var from = point(p.from), to = point(p.to), he = p.lang === "he" || (p.lang !== "en" && /^(he|iw)/i.test(navigator.language || ""));
  var root = document.documentElement, theme = /^(oled|dark|light)$/.test(p.theme || "") ? p.theme : "oled";
  root.dataset.theme = theme; root.style.colorScheme = theme === "light" ? "light" : "dark";
  document.querySelector('meta[name="theme-color"]').content = theme === "light" ? "#f2f2ef" : theme === "dark" ? "#101012" : "#000000";
  var shownApps = Object.prototype.hasOwnProperty.call(p, "apps") ? p.apps.split(",").filter(function (v, i, a) { return ["moovit", "egg", "bnb", "kav"].indexOf(v) >= 0 && a.indexOf(v) === i; }) : ["moovit", "egg", "bnb", "kav"];
  var fromName = (p.fromName || "").slice(0, 120), toName = (p.toName || "").slice(0, 120);
  function t(en, hb) { return he ? hb : en; }
  var releases = "RELEASES";
  if (he) { document.documentElement.lang = "he"; document.documentElement.dir = "rtl"; }
  document.getElementById("via").textContent = t("Shared via Kav", "\\u05e9\\u05d5\\u05ea\\u05e3 \\u05d3\\u05e8\\u05da Kav");
  var title = document.getElementById("title");
  if (!to) { title.textContent = unavailable ? t("This link has expired or is unavailable", "הקישור הזה פג תוקף או אינו זמין") : t("Get Kav", "\\u05dc\\u05d4\\u05d5\\u05e8\\u05d3\\u05ea Kav"); return; }
  title.hidden = true;
  document.title = fromName + " → " + toName + " · Kav";
  var tripUrl = "https://kav.noammm.uk/" + code;
  var android = /Android/.test(navigator.userAgent);
  var kav = android ? "intent://kav.noammm.uk/" + code + "#Intent;scheme=https;package=uk.noammm.kav;end" : null;
  var opening = document.getElementById("opening"), timer, launchTimer, closeTimer;
  function closeOpening() {
    clearTimeout(timer); clearTimeout(launchTimer);
    if (!opening.open || opening.classList.contains("closing")) return;
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) { opening.close(); return; }
    opening.classList.add("closing");
    closeTimer = setTimeout(function () { opening.close(); opening.classList.remove("closing"); }, 180);
  }
  document.getElementById("dismiss").onclick = closeOpening;
  opening.addEventListener("cancel", function (e) { e.preventDefault(); closeOpening(); });
  opening.addEventListener("click", function (e) {
    var r = opening.getBoundingClientRect();
    if (e.target === opening && (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom)) closeOpening();
  });
  function fallback() { if (opening.open && !opening.classList.contains("closing")) document.getElementById("fallback").hidden = false; }
  document.addEventListener("visibilitychange", function () { if (!document.hidden) fallback(); });
  addEventListener("pageshow", fallback);
  var svg = "http://www.w3.org/2000/svg";
  function el(name, attrs, parent) {
    var e = document.createElementNS(svg, name);
    for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function chevron() {
    var c = el("svg", { "class": "chev", width: 18, height: 18, viewBox: "0 0 24 24" });
    el("path", { d: "M9 5l7 7-7 7", fill: "none", stroke: "#fff", "stroke-width": 2.2, "stroke-linecap": "round", "stroke-linejoin": "round" }, c);
    return c;
  }
  function app(key, href, label, icon, name, native, detail, install) {
    if (shownApps.indexOf(key) < 0) return;
    var packages = {moovit: "com.tranzmate", egg: "com.egged.egg", bnb: "com.mosko.bus"};
    if (android && packages[key] && native) {
      var scheme = native.split("://")[0];
      native = "intent://" + native.slice(scheme.length + 3) + "#Intent;scheme=" + scheme + ";package=" + packages[key] +
        ";S.browser_fallback_url=" + encodeURIComponent(install || href) + ";end";
    }
    var a = document.createElement("a");
    a.className = "go"; a.href = href; a.rel = "noreferrer"; a.dataset.app = key;
    a.style.setProperty("--i", document.querySelectorAll("#apps a").length);
    var m = document.createElement("img");
    m.className = "mark"; m.alt = ""; m.src = icon;
    var s = document.createElement("span");
    s.textContent = label;
    if (detail) { var sub = document.createElement("small"); sub.textContent = detail; s.appendChild(sub); }
    a.appendChild(m); a.appendChild(s); a.appendChild(chevron());
    a.onclick = function (e) {
      e.preventDefault(); clearTimeout(timer); clearTimeout(launchTimer); clearTimeout(closeTimer);
      opening.classList.remove("closing");
      document.getElementById("opening-word").textContent = t("Opening ", "פותחים את ");
      document.getElementById("opening-logo").src = icon;
      document.getElementById("opening-name").textContent = name + "…";
      document.getElementById("didnt").textContent = install ? t("App not installed?", "האפליקציה לא מותקנת?") : t("Didn't open?", "לא נפתח?");
      var retry = document.getElementById("fallback-link");
      retry.textContent = install ? t("Get " + name, "להורדת " + name) : t("Click this link", "לחצו על הקישור");
      retry.href = install || (name === "Kav" && /Android/.test(navigator.userAgent) ? native : href);
      document.getElementById("fallback").hidden = true;
      opening.showModal();
      // Give the popup a frame to paint before handing the link to the other app.
      launchTimer = setTimeout(function () {
        if (!opening.open) return;
        if (/Android|iPhone|iPad|iPod/.test(navigator.userAgent) && native) location.href = native;
        else window.open(href, "_blank", "noopener,noreferrer");
      }, 180);
      timer = setTimeout(fallback, 1800);
    };
    document.getElementById("apps").appendChild(a);
  }
  var sharedMoovit;
  try { var mv = new URL(p.moovit); if (mv.protocol === "https:" && /^(www\\.)?moovitapp\\.com$/.test(mv.hostname) && /^\\/(?:tripplan\\/)?i\\/[A-Za-z0-9_-]+$/.test(mv.pathname)) sharedMoovit = mv; } catch (e) {}
  if (sharedMoovit) app("moovit", sharedMoovit.href, t("Open in Moovit", "פתיחה ב-Moovit"), "data:image/png;base64,MOOVIT_ICON", "Moovit",
    "moovit://i/" + sharedMoovit.pathname.split("/").pop());
  else {
    var directions = "directions?dest_lat=" + to[0] + "&dest_lon=" + to[1] + "&dest_name=" + encodeURIComponent(toName);
    if (from) directions += "&orig_lat=" + from[0] + "&orig_lon=" + from[1] + "&orig_name=" + encodeURIComponent(fromName);
    if (/^[0-9]{1,10}$/.test(p.at || "")) directions += "&date=" + Number(p.at) * 1000;
    app("moovit", "https://moovitapp.com/" + directions, t("Search in Moovit", "חיפוש ב-Moovit"), "data:image/png;base64,MOOVIT_ICON", "Moovit",
      "moovit://" + directions, t("Find routes for this journey", "חיפוש מסלולים לנסיעה"));
  }
  function id(v) { return /^[A-Za-z0-9]{4,32}$/.test(v || "") ? v : null; }
  var query = "?to_ll=" + to.join("_") + "&to=" + encodeURIComponent(toName) + "&locale=" + (he ? "he" : "en");
  if (from) query += "&from_ll=" + from.join("_") + "&from=" + encodeURIComponent(fromName);
  var searchNote = t("Find routes for this journey", "חיפוש מסלולים לנסיעה");
  app("egg", id(p.egg) ? "https://egg.hopon.co/itinerary/" + p.egg : "https://egg.hopon.co/" + query,
    id(p.egg) ? t("Open in EGG", "פתיחה ב-EGG") : t("Search in EGG", "חיפוש ב-EGG"), "data:image/png;base64,EGG_ICON", "EGG",
    id(p.egg) ? "egged://itinerary/" + p.egg : "egged://" + query, id(p.egg) ? null : searchNote,
    android && id(p.egg) ? "https://play.google.com/store/apps/details?id=com.egged.egg" : null);
  app("bnb", id(p.bnb) ? "https://busnearby.co.il/itinerary/" + p.bnb : "https://busnearby.co.il/plan" + query,
    id(p.bnb) ? t("Open in Bus Nearby", "פתיחה ב-אוטובוס קרוב") : t("Search in Bus Nearby", "חיפוש באוטובוס קרוב"), "data:image/png;base64,BUS_NEARBY_ICON", t("Bus Nearby", "אוטובוס קרוב"),
    id(p.bnb) ? "busnearby://itinerary/" + p.bnb : "busnearby://" + query, id(p.bnb) ? null : searchNote,
    android && id(p.bnb) ? "https://play.google.com/store/apps/details?id=com.mosko.bus" : null);
  app("kav", android ? tripUrl : releases, t("Open in Kav", "פתיחה ב-Kav"), document.querySelector("img.icon").src, "Kav", kav);
  var apps = document.getElementById("apps");
  shownApps.forEach(function (key, i) { var a = apps.querySelector('[data-app="' + key + '"]'); if (a) { a.style.setProperty("--i", i); apps.appendChild(a); } });
  var get = document.getElementById("get");
  get.textContent = t("No Kav yet? ", "\\u05e2\\u05d5\\u05d3 \\u05d0\\u05d9\\u05df \\u05dc\\u05db\\u05dd Kav? ");
  var link = document.createElement("a");
  link.href = releases;
  link.textContent = t("Get it: free, no ads, open source", "\\u05dc\\u05d4\\u05d5\\u05e8\\u05d3\\u05d4: \\u05d7\\u05d9\\u05e0\\u05dd, \\u05d1\\u05dc\\u05d9 \\u05e4\\u05e8\\u05e1\\u05d5\\u05de\\u05d5\\u05ea, \\u05d1\\u05e7\\u05d5\\u05d3 \\u05e4\\u05ea\\u05d5\\u05d7");
  get.appendChild(link);
  if (!p.data || p.map === "0") return;
  var note = document.getElementById("trip-note");
  document.getElementById("preview").hidden = false;
  note.hidden = false; note.textContent = t("Loading trip…", "טוענים את הנסיעה…");
  try {
    if (!/^[A-Za-z0-9_-]{1,250000}$/.test(p.data)) throw new Error("Invalid trip");
    var raw = Uint8Array.from(atob(p.data.replace(/-/g, "+").replace(/_/g, "/")), function (c) { return c.charCodeAt(0); });
    var reader = new Blob([raw]).stream().pipeThrough(new DecompressionStream("gzip")).getReader();
    var decoder = new TextDecoder(), json = "", size = 0;
    while (true) {
      var chunk = await reader.read(); if (chunk.done) break;
      size += chunk.value.length;
      if (size > 2000000) { await reader.cancel(); throw new Error("Trip too large"); }
      json += decoder.decode(chunk.value, {stream: true});
    }
    var saved = JSON.parse(json + decoder.decode());
    if (saved.v !== 1 || !saved.trips || saved.trips.length !== 1) throw new Error("Invalid trip");
    var trip = saved.trips[0], parts = trip.legs || [], stops = saved.stops || {}, lines = saved.lines || {};
    var rides = parts.filter(function (l) { return l.kind === "RIDE"; });
    await new Promise(function (resolve, reject) {
      var script = document.createElement("script"); script.src = "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js";
      script.integrity = "sha384-cxOPjt7s7Iz04uaHJceBmS+qpjv2JkIHNVcuOrM+YHwZOmJGBXI00mdUXEq65HTH";
      script.crossOrigin = "anonymous"; script.onload = resolve; script.onerror = reject; document.head.appendChild(script);
    });
    var map = L.map("map", {zoomControl: false, scrollWheelZoom: false, attributionControl: true});
    map.attributionControl.setPrefix(false);
    L.tileLayer("https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_" + (theme === "light" ? "Light" : "Dark") + "_Gray_Base/MapServer/tile/{z}/{y}/{x}", {
      maxZoom: 16, attribution: 'Tiles &copy; <a href="https://www.esri.com/">Esri</a> &middot; Esri, HERE, Garmin, OpenStreetMap contributors'
    }).addTo(map);
    function valid(p) { return p && Number.isFinite(p[0]) && Number.isFinite(p[1]) && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180; }
    function path(flat) {
      var out = []; for (var n = 0; n + 1 < (flat || []).length; n += 2) { var p = [flat[n], flat[n + 1]]; if (valid(p)) out.push(p); }
      return out;
    }
    function tooltip(text) { var span = document.createElement("span"); span.dir = "auto"; span.textContent = text || ""; return span; }
    var all = [], rung = 0, modes = MODE_PATHS;
    parts.forEach(function (leg) {
      var points = path(leg.shape); if (points.length < 2) return;
      all.push.apply(all, points);
      var walking = leg.kind === "WALK", color = walking ? "#9999a5" : (saved.colors || [])[rung++];
      if (!/^#[0-9a-f]{6}$/i.test(color || "")) color = walking ? "#9999a5" : "#bf5af2";
      if (!walking) L.polyline(points, {color: "#101012", weight: 8, interactive: false, smoothFactor: 0}).addTo(map);
      L.polyline(points, {color: color, weight: walking ? 3 : 5, dashArray: walking ? "2 7" : null, interactive: false, smoothFactor: 0}).addTo(map);
      if (leg.kind !== "RIDE") return;
      var type = (saved.types || {})[(lines[leg.line] || {}).agency];
      (leg.stops || []).slice(1, -1).forEach(function (id) {
        var stop = stops[id]; if (!stop || !valid([stop.lat, stop.lon])) return;
        L.circleMarker([stop.lat, stop.lon], {radius: 2.5, color: color, fillColor: "#111", fillOpacity: 1, weight: 1.5}).bindTooltip(tooltip(stop.displayName || stop.name)).addTo(map);
      });
      [leg.fromStop, leg.toStop].forEach(function (id, end) {
        var stop = stops[id] || {}, p = [stop.lat, stop.lon]; if (!valid(p)) p = end ? points[points.length - 1] : points[0];
        var glyph = document.createElement("div"); glyph.className = "stop-icon"; glyph.style.setProperty("--route", color);
        var mark = el("svg", {viewBox: "0 0 26 26", fill: "#000", "aria-hidden": "true"}, glyph);
        (modes[type] || modes[3]).forEach(function (d) { el("path", {d: d}, mark); });
        var label = stop.displayName || stop.name || leg.name || t("Stop", "תחנה");
        L.marker(p, {title: label, icon: L.divIcon({html: glyph, className: "", iconSize: [28, 28], iconAnchor: [14, 14]})})
          .bindTooltip(tooltip(label)).addTo(map);
      });
    });
    if (!all.length) throw new Error("Trip has no route geometry");
    L.circleMarker(all[0], {radius: 5, color: "#fff", fillColor: "#111", fillOpacity: 1, weight: 2}).bindTooltip(tooltip(fromName)).addTo(map);
    L.marker(all[all.length - 1], {title: toName, icon: L.divIcon({html: '<div class="end-icon"></div>', className: "", iconSize: [24, 24], iconAnchor: [12, 24]})})
      .bindTooltip(tooltip(toName)).addTo(map);
    map.fitBounds(L.latLngBounds(all), {padding: [32, 32], maxZoom: 16, animate: false});
    note.hidden = true; document.getElementById("map").classList.add("ready");
    document.querySelectorAll(".leaflet-marker-icon[title]").forEach(function (mark) { mark.setAttribute("aria-label", mark.title); });
  } catch (error) {
    note.hidden = false;
    note.textContent = t("Couldn't load the trip preview. You can still open the trip in Kav.", "לא ניתן לטעון את התצוגה המקדימה. עדיין אפשר לפתוח את הנסיעה ב-Kav.");
  }
})();
</script></body></html>
""".replace("RELEASES", RELEASES).replace("KAV_ICON", KAV_ICON).replace("MOOVIT_ICON", MOOVIT_ICON).replace(
    "EGG_ICON", EGG_ICON).replace("BUS_NEARBY_ICON", BUS_NEARBY_ICON).replace("MODE_PATHS", json.dumps(TRIP_MODES)).encode()
ASSET_LINKS = json.dumps([{"relation": ["delegate_permission/common.handle_all_urls"], "target": {
    "namespace": "android_app", "package_name": "uk.noammm.kav", "sha256_cert_fingerprints": [
        "9D:18:3D:43:86:58:40:59:F5:33:76:98:7C:E0:A5:DE:49:60:CD:7A:9B:F4:49:5E:2C:F0:E1:20:F0:58:0F:44"]}}]).encode()


class SharedTrips:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.execute("PRAGMA auto_vacuum = FULL")
        db.execute("CREATE TABLE IF NOT EXISTS trips (code TEXT PRIMARY KEY, expires INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execute("CREATE INDEX IF NOT EXISTS trips_expiry ON trips(expires)")
        return db

    def put(self, trip, days):
        expires = int(time.time()) + days * 86400
        payload = json.dumps(trip, separators=(",", ":"))
        with self.lock, closing(self.connect()) as db, db:
            db.execute("DELETE FROM trips WHERE expires <= ?", (int(time.time()),))
            for _ in range(10):
                code = "".join(secrets.choice("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") for _ in range(7))
                try:
                    db.execute("INSERT INTO trips VALUES (?, ?, ?)", (code, expires, payload))
                    return {"url": "https://kav.noammm.uk/" + code, "expiresAt": expires}
                except sqlite3.IntegrityError:
                    continue
        raise RuntimeError("Could not allocate a shared link")

    def get(self, code):
        with self.lock, closing(self.connect()) as db, db:
            db.execute("DELETE FROM trips WHERE code = ? AND expires <= ?", (code, int(time.time())))
            row = db.execute("SELECT payload FROM trips WHERE code = ?", (code,)).fetchone()
            return json.loads(row[0]) if row else None

    def purge(self):
        with self.lock, closing(self.connect()) as db, db:
            db.execute("DELETE FROM trips WHERE expires <= ?", (int(time.time()),))


shared_trips = SharedTrips(SHARED_TRIPS)
SHORT_LINK = re.compile(r"^/[A-Za-z0-9]{7}$")
SHARE_API = re.compile(r"^/api/share/([A-Za-z0-9]{7})$")


def signed(path, mark, now=None):
    """Whether Kav asked: the ten minutes it asked in and an HMAC of them with the path, made with the key it's built
    with. Three hours either way, for a phone whose clock is off."""
    if not APP_KEY:
        return True
    try:
        slot, mac = mark.split(".", 1)
        slot = int(slot)
    except (AttributeError, ValueError):
        return False
    now = time.time() if now is None else now
    if abs(slot - int(now // 600)) > 18:
        return False
    want = hmac.new(APP_KEY, f"{slot}:{urllib.parse.unquote(path)}".encode(), hashlib.sha256).hexdigest()[:32]
    return hmac.compare_digest(want, mac)


SERVED_EVERY = 60  # seconds the server's account of its other parts is good for: every app asks for the status twice a minute
served_state = {"at": 0, "parts": {}}


def planner_answers(now):
    """Whether the planner answers, with a timetable that hasn't run out; None for a server run without one."""
    if not OTP_URL:
        return None
    req = urllib.request.Request(OTP_URL, data=json.dumps({"query": "{ serviceTimeRange { end } }"}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=3) as r:
            end = ((json.load(r).get("data") or {}).get("serviceTimeRange") or {}).get("end")
    except (OSError, ValueError):
        return False
    return bool(end) and end > now


def served(now, planner=planner_answers):
    """What the server has besides the live buses, for the app's Settings to tell whoever runs one: whether the planner
    answers, whether kav-eta is making Kav's own predicted times (its status, written every round, buses on the road or
    not), the first day of the timetable it hands out (rebuild.sh), and the public half of the key that timetable is
    signed with where it isn't Kav's, for the app to pin (a copy of the server makes one of its own, server/selfhost)."""
    if now - served_state["at"] < SERVED_EVERY:
        return served_state["parts"]
    parts = {"planner": planner(now), "predictions": False, "timetable": ""}
    # A server run without a planner (server/selfhost's PLANNER=off) says nothing of one, and the app offers none.
    if parts["planner"] is None:
        del parts["planner"]
    try:
        with open(os.path.join(os.path.dirname(ETA), "status.json"), encoding="utf-8") as f:
            said = json.load(f)
        parts["predictions"] = bool(ETA) and now - said["at"] <= Predictions.OLD and "error" not in said
    except (OSError, ValueError, KeyError, TypeError):
        pass
    try:
        with open(os.path.join(REALTIME, "bundle", "manifest.json"), encoding="utf-8") as f:
            parts["timetable"] = json.load(f)["from"]
    except (OSError, ValueError, KeyError, TypeError):
        pass
    try:
        with open(os.path.join(REALTIME, "bundle-key"), encoding="ascii") as f:
            parts["bundleKey"] = f.read().strip()
    except (OSError, ValueError):
        pass
    served_state.update(at=now, parts=parts)
    return parts


working = threading.BoundedSemaphore(MAX_WORKING)


class Handler(BaseHTTPRequestHandler):
    server_version = "kav-live"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = 30

    def log_message(self, *args):
        pass

    def answer(self, code, body):
        raw = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def public(self, path):
        """The app link statement and Kav's links, for anyone: whether path was one of them."""
        shared = SHARE_API.fullmatch(path)
        if shared:
            trip = shared_trips.get(shared[1])
            self.answer(200 if trip else 410, trip if trip else {"error": "expired or unavailable"})
            return True
        if path == "/.well-known/assetlinks.json":
            code, kind, body, more = 200, "application/json", ASSET_LINKS, {}
        elif path == "/t/preview.png":
            code, kind, body = 200, "image/png", TRIP_PREVIEW
            more = {"Cache-Control": "public, max-age=86400", "X-Content-Type-Options": "nosniff"}
        elif SHORT_LINK.fullmatch(path) or LINK.match(path) and path[1] == "t":
            code, kind, body = 200, "text/html; charset=utf-8", TRIP_PAGE
            more = {"Cache-Control": "public, max-age=300", "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff",
                    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline' https://unpkg.com/leaflet@1.9.4/dist/leaflet.css; "
                                               "script-src 'unsafe-inline' https://unpkg.com/leaflet@1.9.4/dist/leaflet.js; "
                                               "img-src data: https://services.arcgisonline.com; connect-src 'self'; base-uri 'none'; form-action 'none'"}
        elif LINK.match(path):
            code, kind, body = 302, "text/html; charset=utf-8", b'<!doctype html><a href="%s">Get Kav</a>\n' % RELEASES.encode()
            more = {"Location": RELEASES, "Cache-Control": "no-store"}
        else:
            return False
        self.send_response(code)
        for name, value in {"Content-Type": kind, **more, "Content-Length": str(len(body))}.items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)
        return True

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path != "/api/share" or not signed(path, self.headers.get("X-Kav")):
            self.close_connection = True
            return self.answer(404, {"error": "not found"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if not 0 < length <= 262144:
            self.close_connection = True
            return self.answer(413, {"error": "invalid share size"})
        if not working.acquire(blocking=False):
            self.close_connection = True
            return self.answer(503, {"error": "busy"})
        try:
            try:
                doc = json.loads(self.rfile.read(length))
                trip, days = doc.get("trip"), doc.get("days", 7)
                if type(days) is not int or days not in (3, 7, 30) or not isinstance(trip, dict):
                    raise ValueError()
                if not isinstance(trip.get("data"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,250000}", trip["data"]):
                    raise ValueError()
                for name in ("from", "to"):
                    lat, lon = map(float, trip[name].split(","))
                    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                        raise ValueError()
            except (ValueError, KeyError, TypeError, AttributeError):
                return self.answer(400, {"error": "invalid shared trip"})
            self.answer(201, shared_trips.put(trip, days))
        finally:
            working.release()

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if self.public(path):
            return
        # Kav alone, and the container's own health check.
        if self.client_address[0] != "127.0.0.1" and not signed(path, self.headers.get("X-Kav")):
            return self.answer(404, {"error": "not found"})
        if not working.acquire(blocking=False):
            return self.answer(503, {"error": "busy"})
        try:
            self.route(path)
        finally:
            working.release()

    def route(self, path):
        now = time.time()
        snapshot = state["snapshot"]
        fresh = snapshot if snapshot is not None and now - snapshot.at < STALE else None
        if path == "/live/v1/status":
            return self.answer(200, {
                "ok": fresh is not None,
                "at": snapshot.at if snapshot else 0,
                "vehicles": snapshot.count if snapshot else 0,
                "source": snapshot.source if snapshot else "",
                **served(now),
            })
        if (area := AREA.match(path)) is not None:
            return self.area(*(float(x) for x in area.groups()), fresh, now)
        if (stops := STOPS.match(path)) is not None:
            return self.stops(tuple(sorted(set(stops.group(1).split(",")))), now)
        if (line := LINE.match(path)) is not None:
            return self.line(urllib.parse.unquote(line.group(1)), fresh, now)
        if (trip := PLAN.match(path)) is not None:
            return self.plan(trip)
        if (bundle := BUNDLE_FILE.match(path)) is not None:
            return self.bundle(bundle.group(1))
        if (shape := SHAPE.match(path)) is not None:
            route = int(shape.group(1))
            if not catalog["shapes"]:
                return self.answer(503, {"error": "no shapes yet"})
            if route not in catalog["shapes"]:
                return self.answer(404, {"error": "no shape for this route"})
            return self.answer(200, {"route": route, "shape": catalog["shapes"][route]})
        asked = FOLLOW.match(path)
        if not asked:
            return self.answer(404, {"error": "not found"})
        routes = sorted({int(r) for r in asked.group(1).split(",")})
        noticed()
        rows = [row for r in routes for row in (fresh.routes.get(r, []) if fresh else [])]
        rows = newest(rows + [row for found in pool.map(follow, routes) for row in found])
        if any((catalog["lines"].get(r) or [None] * 7)[6] == 2 for r in routes):
            rows = [r[1:] for r in trains_placed([(int(r[0].split("_")[0]), *r) for r in rows], now) if r[0] in routes]
        self.answer(200, {"at": int(now), "next": int(now + FOLLOW_TTL), "v": [list(row) for row in rows]})


    def snapshot_next(self, woke, now):
        return int(now + 5 if woke else min(max(state["next"] + 2, now + 5), now + 30))

    def area(self, south, west, north, east, fresh, now):
        """Every bus the snapshot has in a box around where the app looks, with its line."""
        if not (south < north and west < east and north - south <= MAX_AREA and east - west <= MAX_AREA):
            return self.answer(400, {"error": "area too big"})
        woke = noticed()
        if fresh is None:
            return self.answer(503, {"error": "no recent snapshot"})
        # A bus that stopped reporting ten minutes ago isn't somewhere to show; a train is where its times put it.
        rows = [r for r in trains_placed(fresh.rows, now)
                if south <= r[2] <= north and west <= r[3] <= east and now - r[4] < OLD_POSITION]
        self.answer(200, {
            "at": fresh.at, "next": self.snapshot_next(woke, now),
            "v": [list(r[1:]) for r in rows], "lines": lines_of(r[0] for r in rows),
        })

    def stops(self, codes, now):
        snapshot = state["snapshot"]
        fresh = snapshot if snapshot is not None and now - snapshot.at < STALE else None
        try:
            ministry = railway_specials(board(codes))
            buses = buses_of(fresh, now)
            rows = with_starting(with_coming(ministry, codes, fresh, now), codes, now, buses=buses)
            rows = with_near(rows, codes, now, buses=buses)
            rows = placed([kav_times(r, now, i < len(ministry)) for i, r in enumerate(rows)], now, buses=buses)
            rows = standing(gone_by(running(timetabled(rows, codes, now)), now, buses=buses), now)
            rows = trains_on_board(with_later(named_trains(quiet(rows, now)), codes, now, buses=buses), now)
        except Exception:
            return self.answer(503, {"error": "the ministry didn't answer"})
        self.answer(200, {
            "at": int(now), "next": int(now + FOLLOW_TTL), "a": rows, "lines": lines_of(r[1] for r in rows),
        })

    def bundle(self, name):
        """One of the timetable's files from REALTIME/bundle, as it is; the timetable's own with its sha256 as its ETag,
        not sent again to a phone that has it."""
        folder = os.path.join(REALTIME, "bundle")
        try:
            size = os.path.getsize(os.path.join(folder, name))
            with open(os.path.join(folder, "manifest.json"), encoding="utf-8") as f:
                tag = '"%s"' % json.load(f)["sha256"]
        except (OSError, ValueError, KeyError, TypeError):
            return self.answer(404, {"error": "no timetable yet"})
        if name == "il.kav.gz" and self.headers.get("If-None-Match") == tag:
            self.send_response(304)
            self.send_header("ETag", tag)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", BUNDLE_TYPES[name])
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(size))
        if name == "il.kav.gz":
            self.send_header("ETag", tag)
        self.end_headers()
        with open(os.path.join(folder, name), "rb") as f:
            shutil.copyfileobj(f, self.wfile, 1 << 16)

    def plan(self, asked):
        """Routes from one place to another, planned here on the ministry's timetable."""
        started = time.time()
        flat, flon, tlat, tlon, when, way, modes, via = asked.groups()
        frm, to = (float(flat), float(flon)), (float(tlat), float(tlon))
        via = [tuple(float(x) for x in p.split(",")) for p in via.split(";")] if via else []
        if not all(29 < p[0] < 34 and 34 < p[1] < 36 for p in [frm, to] + via):
            return self.answer(400, {"error": "outside the timetable's area"})
        if not planning.acquire(blocking=False):
            return self.answer(503, {"error": "busy"})
        when, arrive, allowed = int(when), way in "al", [int(m) for m in modes.split(",")] if modes else []
        asked = when
        if way == "l":
            when = last_way(when)
        # A trip from or to a train station starts or ends on its platforms, and one from or to a picked bus stop may
        # start or end at its twin across the street (twins_at(), kept for plan_variables()).
        with ThreadPoolExecutor(4) as both:
            both.map(twins_at, (frm, to))
            at = tuple(both.map(station_at, (frm, to)))
        window = {"l": LAST_WINDOW, "a": ARRIVE_WINDOW}.get(way)
        # Asked beside the routes list's own questions, to be ready when plan_answer() takes them.
        like, main_answer, dawning = None, Future(), None
        dawn = morning_after(when) if way in "nd" else None
        rest, resting = (rest_end(when, frm) if way in "nda" and not via else None), None
        off = rest_end(asked, frm)
        if rest:
            asking = ThreadPoolExecutor(1)
            rest_from = max(when, rest[0] - REST_BEFORE)
            resting = asking.submit(ask_planner, plan_variables(frm, to, rest_from, False, allowed, "main", via,
                                                                REST_WINDOW, (), at))
            asking.shutdown(wait=False)
        if MORNING_TOO and dawn and not via:
            asking = ThreadPoolExecutor(1)
            dawning = asking.submit(ask_planner, plan_variables(frm, to, dawn, False, allowed, "main", via, MORNING_TOO_WINDOW, (), at))
            asking.shutdown(wait=False)
        officially = None
        if OFFICIAL_URL and way in "nd":
            asking = ThreadPoolExecutor(1)
            officially = asking.submit(ask_official, plan_variables(frm, to, when, arrive, allowed, "main", via, window,
                                                                    (), at))
            asking.shutdown(wait=False)
        if WEIGHED and way in "nd" and not via:
            asking = ThreadPoolExecutor(1)
            # half a second inside the wait below, so the answers that came in time are taken when one hasn't
            like = asking.submit(weighed_answers, frm, to, when, allowed, at, by=WEIGHED_BY - 0.5,
                                 main=lambda wait: main_answer.result(timeout=wait))
            asking.shutdown(wait=False)
        try:
            doc, more = ask_all(frm, to, when, arrive, allowed, way == "n", via, window, at, main_answer.set_result)
        except Exception as e:
            log("planner failed:", repr(e))
            return self.answer(503, {"error": "the planner didn't answer"})
        finally:
            planning.release()
        again = lambda routes: ask_planner(plan_variables(frm, to, when, arrive, allowed, "main", via, window, routes, at))
        morning = (lambda: ask_planner(plan_variables(frm, to, dawn, False, allowed, "main", via, MORNING_WINDOW, (), at))) if dawn else None
        reluctant = lambda: ask_planner(plan_variables(frm, to, when, arrive, allowed, "main", via, window, (), at, WALK_RELUCTANCE_AGAIN))

        def rested(later):
            """The routes list's question after the days off (resting), later the next morning's, and when they end."""
            left = REST_BY - (time.time() - started)
            if later:
                if left < 1:
                    raise TimeoutError("no time left")
                doc = ask_planner(plan_variables(frm, to, morning_after(rest[0] + 6 * 3600), False, allowed, "main",
                                                 via, MORNING_WINDOW, (), at), left)
            else:
                doc = resting.result(timeout=max(0.1, left))
            return doc, rest[0], rest[1]

        def earlier(routes, window=LAST_DAY_WINDOW):
            left = (REST_BY if way == "l" and off else LAST_DAY_BY) - (time.time() - started)
            if left < 1:
                raise TimeoutError("no time left")
            return ask_planner(plan_variables(frm, to, when, arrive, allowed, "main", via, window, routes, at), left)
        wide = min(WIDER_WINDOW, dawn - when) if dawn else WIDER_WINDOW

        def found_too():
            """The extra questions' answers that came in time: the weighed ones, then the next morning's."""
            got = []
            for asked_too in (like, dawning):
                if asked_too is None:
                    continue
                try:
                    answer = asked_too.result(timeout=max(0.1, WEIGHED_BY - (time.time() - started)))
                    got += answer if asked_too is like else [answer]
                except Exception as e:
                    log("planner failed on an extra question:", repr(e))
            return got
        self.answer(200, plan_answer(doc, when, arrive, ask_graphql, more, frm, to, again, asked if way == "l" else None,
                                     morning, earlier if way == "l" else None, reluctant,
                                     (lambda: earlier((), "PT%dM" % (wide // 60))) if way in "nd" and wide >= 600 else None,
                                     officially.result if officially else None,
                                     (lambda: ask_planner(plan_variables(frm, to, when, arrive, allowed, "main", via, DIRECT_WINDOW, (),
                                                                         at, transfers=0))) if way in "nd" and not via else None,
                                     found_too if like or dawning else None, rested=rested if resting else None, off=off))

    def line(self, number, fresh, now):
        """Every bus on the routes that carry this number, and those routes."""
        if not NUMBER.match(number):
            return self.answer(400, {"error": "not a line number"})
        woke = noticed()
        if fresh is None:
            return self.answer(503, {"error": "no recent snapshot"})
        routes = catalog["numbers"].get(number, [])
        rows = [[*row] for r in routes for row in fresh.routes.get(r, []) if now - row[3] < OLD_POSITION]
        self.answer(200, {
            "at": fresh.at, "next": self.snapshot_next(woke, now), "v": rows, "lines": lines_of(routes),
        })


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 128

    def service_actions(self):
        if time.time() >= getattr(self, "next_share_cleanup", 0):
            shared_trips.purge()
            self.next_share_cleanup = time.time() + 3600

    def handle_error(self, request, client_address):
        error = sys.exc_info()[1]
        if not isinstance(error, ConnectionError):
            log("request failed:", repr(error))


if __name__ == "__main__":
    threading.Thread(target=poll, daemon=True).start()
    threading.Thread(target=keep_lines, daemon=True).start()
    threading.Thread(target=keep_shapes, daemon=True).start()
    # Both are the planner's; a server run without one (server/selfhost's PLANNER=off) has neither.
    if OTP_URL:
        threading.Thread(target=keep_rail_copies, daemon=True).start()
        threading.Thread(target=keep_rail_only, daemon=True).start()
    server = Server(("0.0.0.0", PORT), Handler)
    log("listening on", PORT)
    server.serve_forever()
