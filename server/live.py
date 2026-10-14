"""Live buses for Kav, from the Ministry of Transport's real-time feed (SIRI-SM 2.8).

The ministry only answers whitelisted servers, so the app asks here. Every 15 seconds, the
fastest the ministry allows, this takes its national snapshot of every active trip and keeps
it in memory, and answers by area or by line. For a few routes someone is following, and for
the stops someone is looking at, it asks the ministry directly, which is fresher and has the
arrival times. Line numbers and names, and the road each route takes, come from the
ministry's timetable (GTFS), read once a day. Requests are not logged.

Only Kav is answered: it signs each request with the key it's built with (KAV_APP_KEY here).

Settings come from the environment: MOT_KEY, MOT_URL, MOT_URL_BACKUP (the ministry's test
server, used when the main one fails), MOT_CA (the intermediate certificate the ministry leaves
out of its chain), KAV_APP_KEY, GTFS_URL and PORT.
"""
import csv
import datetime
import hashlib
import hmac
import io
import itertools
import json
import os
import re
import ssl
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEY = os.environ.get("MOT_KEY", "")
URL = os.environ.get("MOT_URL", "")
BACKUP = os.environ.get("MOT_URL_BACKUP", "")
PORT = int(os.environ.get("PORT", "8080"))
APP_KEY = os.environ.get("KAV_APP_KEY", "").encode()
GTFS_URL = os.environ.get("GTFS_URL", "https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip")

SNAPSHOT_EVERY = 15  # the ministry's minimum between snapshot requests
IDLE_EVERY = 60
IDLE_AFTER = 120
STALE = 300  # past this the app is better off with Moovit's positions
MAX_FOLLOW = 4
FOLLOW_TTL = 15
FOLLOW_PER_SECOND = 2
FOLLOW_BURST = 12  # one Live view asks about its stops in up to six requests at once
MAX_STOPS = 20
MAX_AREA = 0.4  # degrees a side, a city or so
OLD_POSITION = 600  # a position older than this isn't where the bus is
MAX_WORKING = 128  # requests worked on at once; far more than Kav's users make, so only a flood meets it

TLS = ssl.create_default_context()
if os.environ.get("MOT_CA"):
    TLS.load_verify_locations(os.environ["MOT_CA"])


def log(*parts):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *parts, flush=True)


def epoch(text):
    return int(datetime.datetime.fromisoformat(text).timestamp())


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

    The key is the journey as the ministry names it: route, first departure, vehicle. Moovit
    gives a tracked arrival the same string as its vehicle id, which is how the app matches them.
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
    """Where a route starts and ends: MOT names it "stop-city<->stop-city-direction"."""
    left, _, right = name.partition("<->")
    right = re.sub(r"-[0-9]+[#0-9]*$", "", right)

    def split(part):
        stop, dash, city = part.rpartition("-")
        return (stop.strip(), city.strip()) if dash else (part.strip(), "")
    return split(left), split(right)


def load_lines():
    return parse_routes(zipfile.ZipFile(Remote(GTFS_URL)).read("routes.txt").decode("utf-8-sig"))


def parse_routes(text):
    """route id: [number, to, to city, from, from city, agency, type, MOT's own name], as Kav's timetable types them."""
    found = {}
    for r in csv.DictReader(io.StringIO(text)):
        kind = int(r["route_type"])
        name = r["route_long_name"].strip()
        if kind == 5 and r["agency_id"] == "20":
            kind = 7  # Carmelit
        if kind == 3 and ("שאטל" in name or "שאטל" in r["route_short_name"]):
            kind = 711
        (origin, from_city), (to, to_city) = ends(name)
        found[int(r["route_id"])] = [r["route_short_name"].strip(), to, to_city, origin, from_city,
                                     int(r["agency_id"]), kind, name]
    return found


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
            numbers = {}
            for route, line in found.items():
                numbers.setdefault(line[0], []).append(route)
            catalog["lines"], catalog["numbers"] = found, numbers
            log("lines loaded:", len(found))
            time.sleep(12 * 3600)
        except Exception as e:
            log("lines failing:", e)
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
    return {str(r): known[r] for r in set(routes) if r in known}


state = {"snapshot": None, "next": 0.0, "asked": 0.0, "failing": None}
wake = threading.Event()


def take_snapshot():
    errors = []
    for url, source in ((URL, "main"), (BACKUP, "backup")):
        if not url:
            continue
        try:
            at, rows = visits(ask(url, "MonitoringRef=AllActiveTripsFilter&StopVisitDetailLevel=normal", 60))
        except Exception as e:
            errors.append(f"{source}: {e}")
            continue
        # The test server sometimes hands out a snapshot from minutes ago.
        if source == "backup" and time.time() - at > 360:
            errors.append(f"{source}: {int(time.time() - at)} s old")
            continue
        return Snapshot(at, rows, source)
    raise IOError("; ".join(errors) or "no MOT_URL")


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


def arrivals(doc):
    """Every bus due at the stops asked, one per trip: [stop, route, key, number, first departure, aimed, expected,
    lat, lon, recorded, bearing, speed, order]. Times in seconds; no position when the bus isn't reporting one."""
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
                if where and now - recorded < OLD_POSITION:
                    lat, lon = round(float(where["Latitude"]), 6), round(float(where["Longitude"]), 6)
                    if not (29 < lat < 34 and 34 < lon < 36):
                        lat = lon = None
                bearing = whole(j.get("Bearing"), 360) if lat is not None else None
                row = [
                    stop, int(j["LineRef"]), f'{j["LineRef"]}_{first}_{j.get("VehicleRef", "")}',
                    str(j.get("PublishedLineName", "")), epoch(first),
                    epoch(call["AimedArrivalTime"]) if call.get("AimedArrivalTime") else 0,
                    epoch(call["ExpectedArrivalTime"]) if call.get("ExpectedArrivalTime") else 0,
                    lat, lon, recorded if lat is not None else 0,
                    None if bearing is None else bearing % 360,
                    whole(j.get("Velocity"), 199) if lat is not None else None,
                    whole(call.get("Order"), 999),
                ]
            except (KeyError, ValueError, TypeError):
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


def board(codes):
    """The stops' arrivals straight from the ministry, each stop at most once per FOLLOW_TTL for everyone."""
    codes = list(dict.fromkeys(str(int(c)) for c in codes))
    now = time.time()
    with stops_lock:
        held = {c: stops_seen.get(c) for c in codes}
    missing = [c for c, hit in held.items() if hit is None or now - hit[0] >= FOLLOW_TTL]
    if missing:
        try:
            if not URL or not spend():
                raise IOError("busy")
            rows = arrivals(ask(URL, f"MonitoringRef={','.join(missing)}&StopVisitDetailLevel=normal&PreviewInterval=PT90M", 15))
        except Exception:
            # What was seen a little while ago beats nothing.
            if all(held[c] is None for c in missing):
                raise
        else:
            by_stop = {}
            for row in rows:
                by_stop.setdefault(str(row[0]), []).append(row)
            at = time.time()
            with stops_lock:
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
AREA = re.compile(r"^/live/v1/area/(\d{1,2}\.\d{1,4}),(\d{1,2}\.\d{1,4}),(\d{1,2}\.\d{1,4}),(\d{1,2}\.\d{1,4})$")
STOPS = re.compile(r"^/live/v1/stops/([0-9]{1,6}(?:,[0-9]{1,6}){0,%d})$" % (MAX_STOPS - 1))
LINE = re.compile(r"^/live/v1/line/([^/]{1,40})$")
SHAPE = re.compile(r"^/live/v1/shape/([0-9]{1,9})$")
NUMBER = re.compile(r"^[0-9A-Za-z\u05d0-\u05ea]{1,8}$")


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

    def do_GET(self):
        path = self.path.split("?", 1)[0]
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
            })
        if (area := AREA.match(path)) is not None:
            return self.area(*(float(x) for x in area.groups()), fresh, now)
        if (stops := STOPS.match(path)) is not None:
            return self.stops(tuple(sorted(set(stops.group(1).split(",")))), now)
        if (line := LINE.match(path)) is not None:
            return self.line(urllib.parse.unquote(line.group(1)), fresh, now)
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
        # A bus that stopped reporting ten minutes ago isn't somewhere to show.
        rows = [r for r in fresh.rows if south <= r[2] <= north and west <= r[3] <= east and now - r[4] < OLD_POSITION]
        self.answer(200, {
            "at": fresh.at, "next": self.snapshot_next(woke, now),
            "v": [list(r[1:]) for r in rows], "lines": lines_of(r[0] for r in rows),
        })

    def stops(self, codes, now):
        try:
            rows = board(codes)
        except Exception:
            return self.answer(503, {"error": "the ministry didn't answer"})
        self.answer(200, {
            "at": int(now), "next": int(now + FOLLOW_TTL), "a": rows, "lines": lines_of(r[1] for r in rows),
        })

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

    def handle_error(self, request, client_address):
        error = sys.exc_info()[1]
        if not isinstance(error, ConnectionError):
            log("request failed:", repr(error))


if __name__ == "__main__":
    threading.Thread(target=poll, daemon=True).start()
    threading.Thread(target=keep_lines, daemon=True).start()
    threading.Thread(target=keep_shapes, daemon=True).start()
    server = Server(("0.0.0.0", PORT), Handler)
    log("listening on", PORT)
    server.serve_forever()
