"""Puts the trips of the days Israel's clocks change where the ministry runs them.

A GTFS day's times count from noon less twelve hours, which is midnight on every day but two: on the Sunday the clocks
go back it is 01:00 summer time, on the Friday they go forward 23:00 the evening before. The ministry times its trips
by the clock on the wall (server/live.py's ministry_time: on the night the clocks went back, its trips at 00:xx ran in summer
time, those at 01:xx in winter time), so OTP would run that Sunday's 00:xx trips an hour late and that Friday's before 02:00 an hour
early. Each of them goes to the day before instead, 24 hours later, which counts from midnight. The live updates still
find it: they name a trip by the day and time the ministry gives, and OTP looks for one it can't find on that day in
the day before, 24 hours later.

    python3 clocks.py <dir with israel-gtfs.zip>

rewrites <dir>/israel-gtfs.zip when its timetable has such a day and leaves it alone otherwise. It runs after
predict.py, which copies a trip's calendar for each kind of day it splits the trip into: a day taken off a trip here
goes in calendar_dates.txt, which predict.py wouldn't copy. Only the standard library, so it runs in any Python image.
"""
import csv
import datetime
import io
import os
import shutil
import sys
import zipfile
from zoneinfo import ZoneInfo

ISRAEL = ZoneInfo("Asia/Jerusalem")
WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
DAY = 86400
ONE = datetime.timedelta(days=1)


def start_of(day):
    """When a GTFS day's times count from: noon less twelve hours."""
    return int(datetime.datetime.combine(day, datetime.time(12), ISRAEL).timestamp()) - DAY // 2


def ministry_time(day, seconds):
    """When a trip timetabled seconds into day sets out, as the ministry stamps it (server/live.py's)."""
    at = datetime.datetime.combine(day, datetime.time()) + datetime.timedelta(seconds=seconds)
    return int(at.replace(tzinfo=ISRAEL, fold=1).timestamp())


def changes(first, last):
    """The days from first to last whose times don't count from midnight."""
    out, day = [], first
    while day <= last:
        if start_of(day) != int(datetime.datetime.combine(day, datetime.time(), ISRAEL).timestamp()):
            out.append(day)
        day += ONE
    return out


def moves(day, first):
    """Whether a trip leaving first seconds into day runs when the ministry has it only on the day before."""
    at = ministry_time(day, first)
    return first < DAY and at != start_of(day) + first and at == start_of(day - ONE) + first + DAY


def stamp(day):
    return day.strftime("%Y%m%d")


def date_of(text):
    return datetime.date(int(text[:4]), int(text[4:6]), int(text[6:8]))


def seconds(clock):
    h, m, s = clock.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def hms(at):
    return "%02d:%02d:%02d" % (at // 3600, at // 60 % 60, at % 60)


def table(src, name):
    with io.TextIOWrapper(src.open(name), encoding="utf-8-sig", newline="") as f:
        return list(csv.reader(f))


def text(rows):
    out = io.StringIO()
    csv.writer(out, lineterminator="\n").writerows(rows)
    return out.getvalue()


def move(where, today=None):
    """Moves the trips of the days from yesterday on (the planner's own first day, build-config.json)."""
    today = today or datetime.date.today()
    path = os.path.join(where, "israel-gtfs.zip")
    with zipfile.ZipFile(path) as src:
        calendar = table(src, "calendar.txt")
        c = {k: i for i, k in enumerate(calendar[0])}
        rows = {r[c["service_id"]]: r for r in calendar[1:]}
        dates = table(src, "calendar_dates.txt") if "calendar_dates.txt" in src.namelist() else \
            [["service_id", "date", "exception_type"]]
        d = {k: i for i, k in enumerate(dates[0])}
        extra = {}
        for r in dates[1:]:
            extra.setdefault(r[d["service_id"]], {})[date_of(r[d["date"]])] = r[d["exception_type"]]

        def by_calendar(service, day):
            r = rows.get(service)
            return r is not None and r[c["start_date"]] <= stamp(day) <= r[c["end_date"]] and \
                r[c[WEEKDAYS[day.weekday()]]] == "1"

        def runs(service, day):
            kind = extra.get(service, {}).get(day)
            return kind == "1" if kind else by_calendar(service, day)

        def days_of(service):
            found = {day for day, kind in extra.get(service, {}).items() if kind == "1"}
            r = rows.get(service)
            if r is not None:
                day = date_of(r[c["start_date"]])
                while day <= date_of(r[c["end_date"]]):
                    if runs(service, day):
                        found.add(day)
                    day += ONE
            return found

        span = [date_of(r[c["end_date"]]) for r in calendar[1:]] + [day for days in extra.values() for day in days]
        nights = changes(today - ONE, max(span)) if span else []
        on = {}  # service -> the days it runs that don't count from midnight
        for service in rows.keys() | extra.keys():
            found = [day for day in nights if runs(service, day)]
            if found:
                on[service] = found
        if not on:
            return {"days": [], "trips moved": 0}
        trips = table(src, "trips.txt")
        th = trips[0]
        t_id, t_service = th.index("trip_id"), th.index("service_id")
        service_of = {r[t_id]: r[t_service] for r in trips[1:] if r[t_service] in on}
        candidates = {trip.encode() for trip in service_of}
        decided = {}  # trip -> the days it leaves from the day before
        moved = set()  # trips running on such a day alone, which just go to the day before

        with zipfile.ZipFile(path + ".part", "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
            with src.open("stop_times.txt") as f, dst.open("stop_times.txt", "w", force_zip64=True) as g:
                head = f.readline()
                g.write(head)
                cols = head.decode("utf-8-sig").rstrip("\r\n").split(",")
                t, arr, dep, seq = (cols.index(k) for k in ("trip_id", "arrival_time", "departure_time", "stop_sequence"))
                out = []

                def later(line, trip):
                    r = line.rstrip(b"\r\n").decode().split(",")
                    r[t] = trip
                    for k in (arr, dep):
                        if r[k]:
                            r[k] = hms(seconds(r[k]) + DAY)
                    return (",".join(r) + "\n").encode()

                def flush(trip, block):
                    if not block:
                        return
                    trip = trip.decode()
                    if trip not in decided:
                        first = seconds(min((l.rstrip(b"\r\n").decode().split(",") for l in block),
                                            key=lambda r: int(r[seq]))[dep])
                        decided[trip] = [day for day in on[service_of[trip]] if moves(day, first)]
                        if decided[trip] and set(decided[trip]) == days_of(service_of[trip]) and len(decided[trip]) == 1:
                            moved.add(trip)
                    if trip in moved:
                        out.extend(later(line, trip) for line in block)
                        return
                    out.extend(block)
                    for day in decided[trip]:
                        out.extend(later(line, trip + "~" + stamp(day)) for line in block)

                current, block = None, []
                for line in f:
                    trip = line.split(b",", t + 1)[t]
                    if trip != current:
                        flush(current, block)
                        current, block = trip, []
                    if trip in candidates:
                        block.append(line)
                    else:
                        out.append(line)
                    if len(out) >= 50000:
                        g.write(b"".join(out))
                        out = []
                flush(current, block)
                g.write(b"".join(out))

            # A day's trips leaving from the day before run on a calendar of just that day; one also running on other
            # days stays on a copy of its calendar without this one.
            only, without = {}, {}
            new_trips = [th]
            for r in trips[1:]:
                trip = r[t_id]
                gone = decided.get(trip)
                if not gone:
                    new_trips.append(r)
                    continue
                for day in gone:
                    only.setdefault(day - ONE, "kav-clocks-" + stamp(day - ONE))
                if trip in moved:
                    new_trips.append(r[:t_service] + [only[gone[0] - ONE]] + r[t_service + 1:])
                    continue
                service = r[t_service]
                key = (service, tuple(gone))
                without.setdefault(key, service + "~not" + "+".join(stamp(day) for day in gone))
                new_trips.append(r[:t_service] + [without[key]] + r[t_service + 1:])
                for day in gone:
                    copy = list(r)
                    copy[t_id], copy[t_service] = trip + "~" + stamp(day), only[day - ONE]
                    new_trips.append(copy)
            new_calendar = calendar[:]
            new_dates = dates[:]

            def date_row(service, day, kind):
                row = [""] * len(dates[0])
                row[d["service_id"]], row[d["date"]], row[d["exception_type"]] = service, stamp(day), kind
                new_dates.append(row)

            for day, service in sorted(only.items()):
                row = [""] * len(calendar[0])
                row[c["service_id"]], row[c["start_date"]], row[c["end_date"]] = service, stamp(day), stamp(day)
                for name in WEEKDAYS:
                    row[c[name]] = "1" if name == WEEKDAYS[day.weekday()] else "0"
                new_calendar.append(row)
            for (service, gone), name in sorted(without.items()):
                if service in rows:
                    new_calendar.append(rows[service][:c["service_id"]] + [name] + rows[service][c["service_id"] + 1:])
                for day, kind in sorted(extra.get(service, {}).items()):
                    if day not in gone:
                        date_row(name, day, kind)
                for day in gone:
                    if by_calendar(service, day):
                        date_row(name, day, "2")
            dst.writestr("trips.txt", text(new_trips))
            dst.writestr("calendar.txt", text(new_calendar))
            dst.writestr("calendar_dates.txt", text(new_dates))
            for info in src.infolist():
                if info.filename in ("stop_times.txt", "trips.txt", "calendar.txt", "calendar_dates.txt"):
                    continue
                with src.open(info) as f, dst.open(info.filename, "w", force_zip64=True) as g:
                    shutil.copyfileobj(f, g, 1 << 20)
    os.replace(path + ".part", path)
    return {"days": [day.isoformat() for day in nights], "trips moved": len(moved),
            "trips copied": sum(1 for trip, gone in decided.items() if gone and trip not in moved)}


if __name__ == "__main__":
    print("clock changes:", move(sys.argv[1]), flush=True)
