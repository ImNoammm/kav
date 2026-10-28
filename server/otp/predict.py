"""Moves the timetable's buses to when they really get to each stop, as Moovit plans them: by how long each line has
taken, at that time of day and on that kind of day, from its first stop to each of its stops. The times come from
when Kav's server saw each bus pass each stop (server/live.py's Recorder), over the last DAYS days. Trains, the
Jerusalem light rail and the Haifa cable car keep their timetable, as Moovit keeps them. A trip's first stop keeps its
time too, which is how the live updates find the trip.

    python3 predict.py <dir with israel-gtfs.zip> <history dir>

rewrites <dir>/israel-gtfs.zip. Trips running on more than one kind of day (Sunday to Thursday, Friday, Saturday)
become one trip per kind. Only the standard library, so it runs in any Python image.
"""
import array
import bisect
import collections
import csv
import datetime
import gzip
import io
import os
import shutil
import sys
import zipfile

DAYS = {"W": 14, "F": 28, "S": 28}  # days looked back: Fridays and Saturdays come once a week
WINDOW = 2700  # trips that set out up to 45 minutes either side count
# How many past trips were quicker: 0.4, which matched Moovit's predictions best. At half the buses came closer to the
# time worked out, but a rider at the stop a minute before a first bus planned ahead missed it about twice as often.
SHARE = 0.4
LEAST = 3  # past trips needed for a prediction
EARLIEST, LATEST = -900, 5400  # how far a prediction may move a stop from the timetable
HOP = 0.5  # a bus takes at least this share of the timetable's time from one stop to the next
# A stop the history puts more than JUMP seconds and JUMP_TIMES times past the timetable's time from the stop before
# keeps the delay of the stop before it, and so does every later stop that jumps again: a trip's history can have it
# leave its first stop on time some days and up to 40 minutes late others (line 93's 16:30, route 29721: stop 2 reached
# 2, 20, 40, 9 and 22 minutes after it set out, so 20). At the stops this moves Kav is far less often minutes later
# than the bus, though more often early.
JUMP, JUMP_TIMES = 600, 3
TAVEL = "22"  # the Tel Aviv light rail's operator: Moovit predicts it like a bus
KINDS = {"W": ("sunday", "monday", "tuesday", "wednesday", "thursday"), "F": ("friday",), "S": ("saturday",)}
DAY_NAMES = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
# A day the ministry runs clearly fewer buses on than its other days of that kind (an election, a holiday) is a special
# day: its trips keep the ministry's own times, as Moovit's do, and its passings stay out of the history.
SPECIAL = 0.85


def kind_of(date):
    """Sunday to Thursday, Friday or Saturday."""
    return {4: "F", 5: "S"}.get(date.weekday(), "W")


def history(folder, today, days=DAYS, skip=()):
    """(route, stop code, kind of day) -> (first departures, times taken), sorted by first departure: how long each
    trip seen took from its first stop to that stop, by the second of its day it set out."""
    found = {}
    starts = {}
    for back in range(1, max(days.values()) + 1):
        day = (today - datetime.timedelta(days=back)).isoformat()
        if day in skip:
            continue
        for name in (day + ".tsv", day + ".tsv.gz"):
            path = os.path.join(folder, name)
            if not os.path.exists(path):
                continue
            opener = gzip.open if name.endswith(".gz") else open
            with opener(path, "rt", encoding="utf-8") as f:
                for line in f:
                    parts = line.rstrip("\n").split("\t")
                    if len(parts) != 7 or not parts[5]:
                        continue
                    route, _, service, start, _, code, passed = parts
                    when = starts.get((service, start))
                    if when is None:
                        try:
                            first = datetime.datetime.fromisoformat(start)
                            midnight = datetime.datetime.fromisoformat(service).replace(tzinfo=first.tzinfo)
                            when = (int(first.timestamp()), int((first - midnight).total_seconds()),
                                    kind_of(datetime.date.fromisoformat(service)))
                        except ValueError:
                            when = (0, 0, "")
                        starts[(service, start)] = when
                    if not when[2] or back > days[when[2]]:
                        continue
                    key = (route, code, when[2])
                    pair = found.get(key)
                    if pair is None:
                        pair = found[key] = (array.array("l"), array.array("l"))
                    pair[0].append(when[1])
                    pair[1].append(int(passed) - when[0])
    out = {}
    for key, (at, took) in found.items():
        order = sorted(range(len(at)), key=at.__getitem__)
        out[key] = (array.array("l", (at[i] for i in order)), array.array("l", (took[i] for i in order)))
    return out


def taken(seen, start):
    """How long the trip setting out at start will take, from the past trips around then; None without enough."""
    at, took = seen
    lo, hi = bisect.bisect_left(at, start - WINDOW), bisect.bisect_right(at, start + WINDOW)
    if hi - lo < LEAST:
        return None
    sample = sorted(took[lo:hi])
    return sample[int(SHARE * (len(sample) - 1) + 0.5)]


def settle(times, planned):
    """The times nearest these (least squares) in which each stop comes at least HOP of the timetable's time after the
    one before, the first stop keeping its own. Each stop's time is worked out on its own, from different past trips,
    so a later stop can come out earlier than the one before it; raising it to the time before would put a bus at
    several stops in the same second."""
    offset, gone = [], 0.0
    for k in range(len(times)):
        if k:
            gone += HOP * max(0, planned[k] - planned[k - 1])
        offset.append(gone)
    # Pooling neighbours that come out of order, the first stop weighing so much that it stays.
    blocks = []
    for k, t in enumerate(times):
        weight = 1e9 if k == 0 else 1.0
        blocks.append([(t - offset[k]) * weight, weight, 1])
        while len(blocks) > 1 and blocks[-2][0] / blocks[-2][1] > blocks[-1][0] / blocks[-1][1]:
            total, weight, count = blocks.pop()
            blocks[-1][0] += total
            blocks[-1][1] += weight
            blocks[-1][2] += count
    out, k, last = [], 0, None
    for total, weight, count in blocks:
        for _ in range(count):
            at = int(round(total / weight + offset[k]))
            if last is not None:
                at = max(at, last)
            out.append(at)
            last, k = at, k + 1
    return out


def hms(seconds):
    return "%02d:%02d:%02d" % (seconds // 3600, seconds // 60 % 60, seconds % 60)


def seconds(text):
    h, m, s = text.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def table(src, name):
    with io.TextIOWrapper(src.open(name), encoding="utf-8-sig", newline="") as f:
        return list(csv.reader(f))


def text(rows):
    out = io.StringIO()
    csv.writer(out, lineterminator="\n").writerows(rows)
    return out.getvalue()


def special_days(calendar, trips_of, today):
    """The dates from the day before today to 15 days on that the ministry runs clearly fewer of these trips on than
    on its other days of that kind."""
    ch = calendar[0]
    count = {}
    for back in range(-1, 16):
        d = today + datetime.timedelta(days=back)
        ds, name = d.strftime("%Y%m%d"), DAY_NAMES[d.weekday()]
        count[d] = sum(trips_of.get(r[ch.index("service_id")], 0) for r in calendar[1:]
                       if r[ch.index("start_date")] <= ds <= r[ch.index("end_date")] and r[ch.index(name)] == "1")
    out = set()
    for kind in KINDS:
        seen = sorted(n for d, n in count.items() if n and kind_of(d) == kind)
        if len(seen) >= 3:
            out |= {d for d, n in count.items() if n and kind_of(d) == kind and n < SPECIAL * seen[len(seen) // 2]}
    return out


def remember(path, days):
    """The special days written down so far with these added, as ISO dates."""
    known = set()
    if os.path.exists(path):
        with open(path) as f:
            known = {line.strip() for line in f if line.strip()}
    added = known | {d.isoformat() for d in days}
    if added != known:
        with open(path, "w") as f:
            f.write("".join(d + "\n" for d in sorted(added)))
    return added


def predict(where, folder, today, kept=None):
    path = os.path.join(where, "israel-gtfs.zip")
    with zipfile.ZipFile(path) as src, zipfile.ZipFile(path + ".part", "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        routes = table(src, "routes.txt")
        h = routes[0]
        moved_routes = {r[h.index("route_id")] for r in routes[1:]
                        if r[h.index("route_type")] in ("3", "715") or (r[h.index("route_type")] == "0" and r[h.index("agency_id")] == TAVEL)}
        calendar = table(src, "calendar.txt")
        ch = calendar[0]
        trips = table(src, "trips.txt")
        th = trips[0]
        trip_route = {r[th.index("trip_id")]: r[th.index("route_id")] for r in trips[1:]}
        trip_service = {r[th.index("trip_id")]: r[th.index("service_id")] for r in trips[1:]}
        special = special_days(calendar, collections.Counter(
            trip_service[t] for t, route in trip_route.items() if route in moved_routes), today)
        known = remember(kept or os.path.join(os.path.dirname(os.path.abspath(where)), "special-days.txt"), special)
        seen = history(folder, today, skip=known)
        kinds = {}
        window = (today - datetime.timedelta(days=1)).strftime("%Y%m%d"), (today + datetime.timedelta(days=15)).strftime("%Y%m%d")
        for r in calendar[1:]:
            if r[ch.index("end_date")] < window[0] or r[ch.index("start_date")] > window[1]:
                continue
            runs = {d for d in (today + datetime.timedelta(days=b) for b in range(-1, 16))
                    if r[ch.index("start_date")] <= d.strftime("%Y%m%d") <= r[ch.index("end_date")]
                    and r[ch.index(DAY_NAMES[d.weekday()])] == "1"}
            if runs and runs <= special:
                continue
            kinds[r[ch.index("service_id")]] = [k for k, days in KINDS.items() if any(r[ch.index(d)] == "1" for d in days)]
        stops = table(src, "stops.txt")
        sh = stops[0]
        code_of = {r[sh.index("stop_id")]: r[sh.index("stop_code")] for r in stops[1:]}

        split = {}  # trip id -> the kinds of day it becomes a trip of its own for
        moved = 0
        out_times = io.TextIOWrapper(dst.open("stop_times.txt", "w", force_zip64=True), encoding="utf-8", newline="")
        writer = csv.writer(out_times, lineterminator="\n")

        def flush(trip, rows):
            nonlocal moved
            service = trip_service.get(trip)
            if trip_route.get(trip) not in moved_routes or service not in kinds or len(rows) < 2:
                writer.writerows(rows)
                return
            start = seconds(rows[0][2])
            route = trip_route[trip]
            planned = [seconds(r[2]) for r in rows]
            codes = [code_of.get(r[3], "") for r in rows]
            # A stop the trip passes twice has both passes in one history: it keeps the delay before it instead.
            twice = {c for c in codes if codes.count(c) > 1}
            versions = {}
            for kind in kinds[service]:
                times, late = [start], 0
                for i in range(1, len(rows)):
                    took = None
                    key = (route, codes[i], kind)
                    if codes[i] not in twice and key in seen:
                        took = taken(seen[key], start)
                    if took is not None:
                        at = min(max(start + took, planned[i] + EARLIEST), planned[i] + LATEST)
                        hop = max(0, planned[i] - planned[i - 1])
                        if at - times[-1] - hop > JUMP and at - times[-1] > JUMP_TIMES * max(hop, 60):
                            at = planned[i] + late
                        late = at - planned[i]
                    else:
                        at = planned[i] + late
                    times.append(at)
                times = settle(times, planned)
                versions[kind] = times if times != planned else None
            if all(v is None for v in versions.values()):
                writer.writerows(rows)
                return
            moved += 1
            many = len(versions) > 1
            if many:
                split[trip] = list(versions)
            for kind, times in versions.items():
                for r, at in zip(rows, times or [seconds(r[2]) for r in rows]):
                    row = list(r)
                    row[0] = trip + "~" + kind if many else trip
                    row[1] = row[2] = hms(at)
                    writer.writerow(row)

        with io.TextIOWrapper(src.open("stop_times.txt"), encoding="utf-8-sig", newline="") as f:
            reader = csv.reader(f)
            head = next(reader)
            writer.writerow(head)
            current, rows = None, []
            for r in reader:
                if r[0] != current:
                    if rows:
                        flush(current, rows)
                    current, rows = r[0], []
                rows.append(r)
            if rows:
                flush(current, rows)

        # A trip split by kind of day runs on a calendar of just those days.
        new_calendar = [ch] + calendar[1:]
        by_service = {c[ch.index("service_id")]: c for c in calendar[1:]}
        made = set()
        new_trips = [th]
        for r in trips[1:]:
            trip = r[th.index("trip_id")]
            if trip not in split:
                new_trips.append(r)
                continue
            service = r[th.index("service_id")]
            for kind in split[trip]:
                copy = list(r)
                copy[th.index("trip_id")] = trip + "~" + kind
                copy[th.index("service_id")] = service + "~" + kind
                new_trips.append(copy)
                if (service, kind) not in made:
                    made.add((service, kind))
                    base = by_service[service]
                    row = list(base)
                    row[ch.index("service_id")] = service + "~" + kind
                    for day in ("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"):
                        row[ch.index(day)] = base[ch.index(day)] if day in KINDS[kind] else "0"
                    new_calendar.append(row)

        out_times.close()
        dst.writestr("trips.txt", text(new_trips))
        dst.writestr("calendar.txt", text(new_calendar))
        for info in src.infolist():
            if info.filename in ("stop_times.txt", "trips.txt", "calendar.txt"):
                continue
            with src.open(info) as f, dst.open(info.filename, "w", force_zip64=True) as g:
                shutil.copyfileobj(f, g, 1 << 20)
    os.replace(path + ".part", path)
    return {"trips moved": moved, "split by kind of day": len(split), "line-stop histories": len(seen),
            "special days": sorted(d.isoformat() for d in special)}


if __name__ == "__main__":
    print("predicted:", predict(sys.argv[1], sys.argv[2], datetime.date.today()), flush=True)
