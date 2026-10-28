"""Kav's own predicted bus arrivals: the lab's live model (train_live.py, live3_all) for every bus on its way, every
30 seconds, from when Kav's server saw each bus reach each stop today (live.py's Recorder), the ministry's timetable,
and what the six weeks before say about each stretch. The buses' predictions go to live.py (now.json), which gives
them to the planner and to the app instead of the ministry's expected times, which they beat at every distance; every
five minutes all of them are logged with the ministry's own times for some of the stops, to keep scoring them.

Environment: HISTORY (live.py's history), BACKFILL (older days replayed from Hasadna's archive), GTFS (the ministry's
timetable, which otp/prepare.py keeps), MODEL (the model as numpy arrays, from the lab's ml/export_live.py), REALTIME
(where live.py lists the journeys on the road), OUT (the log and status), KAV_LIVE and KAV_APP_KEY (Kav's server,
which asks the ministry).
"""
import copy
import datetime
import hashlib
import hmac
import io
import json
import os
import random
import resource
import sys
import time
import traceback
import urllib.request
import zipfile
import zoneinfo

import numpy as np
import polars as pl

import inputs as I
import motion as V
import rail_inputs as R

HISTORY = os.environ.get("HISTORY", "/history")
BACKFILL = os.environ.get("BACKFILL", "/backfill")
GTFS = os.environ.get("GTFS", "/official/mot-gtfs.zip")
MODEL = os.environ.get("MODEL", "/models/live3_all.npz")
# A motion model's network for the buses whose motion can't be told (no fresh place on their stretch): the lab's live5
# control, fine-tuned beside it on the same days without the motion inputs. None: the motion model's own times.
MODEL_FALLBACK = os.environ.get("MODEL_FALLBACK", "")
RAIL = os.environ.get("RAIL", "/models/rail")  # the train and light-rail models, gbdt-<operator>.txt and theirs
REALTIME = os.environ.get("REALTIME", "/realtime")
OUT = os.environ.get("OUT", "/eta")
KAV_LIVE = os.environ.get("KAV_LIVE", "http://kav-live:8080")
# The planner's special days (otp/predict.py writes them: an election, a holiday), one date a line (special_days()).
SPECIAL_DAYS = os.environ.get("SPECIAL_DAYS", "/special-days.txt")
APP_KEY = os.environ.get("KAV_APP_KEY", "").encode()

ISRAEL = zoneinfo.ZoneInfo("Asia/Jerusalem")
EVERY = 30  # seconds between predictions
# Every prediction, and the ministry's own times for a few stops, are logged this often, to keep scoring them;
# 0, never: a copy of Kav's server has no need to.
LOG_EVERY = int(os.environ.get("LOG_EVERY", "300"))
ASKED = 20  # stops the ministry is asked about at once, as many as Kav's server asks it about
ASKS = 3  # times it's asked each time the log is kept
GONE = 1800  # a bus not seen reaching a stop this long has ended its trip, as live.py's Recorder takes it
FRESH = 180  # seconds Kav's server's list of the journeys on the road is good for
LOOK_BACK = 42  # days of history the medians come from, as in training
RUNS_UNTIL = 4  # a service day's last buses run until about this hour the next morning
SHARES = 8  # the history medians are worked out this many shares of the routes at a time
SLICE = 64 << 20  # bytes of the timetable's stop times read at a time
OPERATORS = {"2": "Israel Railways", "22": "Tel Aviv light rail"}  # the operators with a model of their own
# Of those, the ones whose own model's times go to live.py too, once scored better than the ministry's.
RAIL_HANDOVER = [a for a in os.environ.get("RAIL_HANDOVER", "").split(",") if a in OPERATORS]


def log(*args):
    print(datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S"), *args, flush=True)


def kind(day):
    """Sunday to Thursday, Friday or Saturday, as the history medians group days."""
    return {4: "F", 5: "S"}.get(day.weekday(), "W")


def history_files(folders):
    """Each day's history file by its date; a day in more than one folder comes from the first (live.py's own)."""
    files = {}
    for folder in folders:
        if folder and os.path.isdir(folder):
            for name in sorted(os.listdir(folder)):
                if name.endswith((".tsv", ".tsv.gz")):
                    files.setdefault(name[:10], os.path.join(folder, name))
    return files


def medians(files, day, look_back=LOOK_BACK, off=I.HOLIDAYS):
    """The lab's history medians (features.medians) as they join onto `day`'s own rows, from only the days that can
    count: the earlier days of its kind that weren't holidays, and only the columns the medians need. A route's medians
    never mix with another's, so they're worked out a share of the routes at a time. The lab's way holds every day and
    column at once, which takes more memory than the server has to spare."""
    wanted = {day - datetime.timedelta(days=i) for i in range(1, look_back + 1)}
    wanted = sorted(d for d in wanted if kind(d) == kind(day) and d not in off)
    frames = [pl.DataFrame(schema={"route": pl.Int32, "service": pl.Date, "start_sec": pl.Int64, "order": pl.Int32,
                                   "took": pl.Int32})]
    for name in sorted(files):
        d = datetime.date.fromisoformat(name)
        # A day's last buses are recorded in the next day's file.
        if d in wanted or d - datetime.timedelta(days=1) in wanted:
            frames.append(I.load_history([files[name]]).filter(pl.col("service").is_in(wanted))
                          .select(pl.col("route").cast(pl.Int32), "service", "start_sec", "order", "took"))
    past = pl.concat(frames, rechunk=False)
    med = lambda name: [pl.col("took").median().alias(name), pl.len().alias("n_" + name)]
    trip, by_bucket = ["route", "start_sec", "order"], ["route", "bucket", "order"]
    found = [[], [], [], []]
    for share in range(SHARES):
        part = past.filter(pl.col("route") % SHARES == share).with_columns((pl.col("start_sec") // 1800).alias("bucket"))
        recent = part.filter(pl.col("service") >= day - datetime.timedelta(days=7))
        weekday = part.filter(pl.col("service").dt.weekday() == day.isoweekday())
        for i, (frame, on, name) in enumerate([(part, trip, "h_trip"), (part, by_bucket, "h_bucket"),
                                               (weekday, by_bucket, "h_weekday"), (recent, by_bucket, "h_recent")]):
            found[i].append(frame.group_by(on).agg(med(name)))
    return [(pl.concat(f), on) for f, on in zip(found, [trip, by_bucket, by_bucket, by_bucket])]


def schedule(zip_path, day):
    """The lab's load_schedule(zip_path, day) (dataset.py): every stop of every run of the day, its time after the run's
    first departure and its code, and how far along the route it is (metres, for position_blend). stop_times.txt has every run of two months, tens of millions of rows; it's read a
    slice at a time keeping only the day's runs, where the lab's way holds it all at once."""
    z = zipfile.ZipFile(zip_path)
    read = lambda name, cols: pl.read_csv(io.BytesIO(z.read(name)), columns=cols, infer_schema=False, quote_char=None)
    weekday = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"][day.weekday()]
    ymd = day.strftime("%Y%m%d")
    cal = read("calendar.txt", ["service_id", weekday, "start_date", "end_date"])
    running = cal.filter((pl.col(weekday) == "1") & (pl.col("start_date") <= ymd) & (pl.col("end_date") >= ymd))
    trips = read("trips.txt", ["route_id", "service_id", "trip_id"]).join(running.select("service_id"), on="service_id")
    trips = trips.select("route_id", "trip_id")
    stops = read("stops.txt", ["stop_id", "stop_code"])
    parts = []
    with z.open("stop_times.txt") as f:
        header = f.readline()
        # How far along its route each stop is, where the timetable says (position_blend)
        cols = ["trip_id", "departure_time", "stop_id", "stop_sequence"] + (["shape_dist_traveled"] if b"shape_dist_traveled" in header else [])
        while chunk := f.read(SLICE):
            chunk += f.readline()  # to the end of its last line
            st = pl.read_csv(io.BytesIO(header + chunk), columns=cols, infer_schema=False, quote_char=None)
            parts.append(st.join(trips.select("trip_id"), on="trip_id", how="semi"))
    st = pl.concat(parts).with_columns(I.hms("departure_time").alias("dep"), pl.col("stop_sequence").cast(pl.Int32).alias("order"))
    if "shape_dist_traveled" not in st.columns:
        st = st.with_columns(pl.lit(None, pl.Utf8).alias("shape_dist_traveled"))
    first = st.group_by("trip_id").agg(pl.col("dep").min().alias("start_sec"))
    return (st.join(first, on="trip_id").join(trips, on="trip_id").join(stops, on="stop_id")
            .select(pl.col("route_id").alias("route"), "start_sec", "order",
                    (pl.col("dep") - pl.col("start_sec")).alias("sched"), pl.col("stop_code").cast(pl.Int32).alias("code"),
                    pl.col("shape_dist_traveled").cast(pl.Float64, strict=False).alias("dist"))
            .unique(subset=["route", "start_sec", "order"]))


class Day:
    """One service day's timetable and what the days before say: `plan` holds every stop of every run of the day, with
    the medians, for the stops ahead of a bus; `with_history` gives the day's recorded rows the same."""

    def __init__(self, day, files, gtfs=GTFS):
        self.day = day
        self.tables = medians(files, day)
        self.sched = schedule(gtfs, day).with_columns(pl.col("route").cast(pl.Int32))
        # Where each stop is along its route, for position_blend; the model's own inputs stay as the lab built them.
        self.road = road_of(self.sched, day)
        self.sched = self.sched.drop("dist")
        # The bus model's times go out for buses only: trains and light rail have models of their own.
        routes = pl.read_csv(io.BytesIO(zipfile.ZipFile(gtfs).read("routes.txt")), columns=["route_id", "agency_id", "route_type"],
                             infer_schema=False, quote_char=None)
        self.buses = set(routes.filter(pl.col("route_type") == "3")["route_id"].to_list())
        self.rail = {a: set(routes.filter(pl.col("agency_id") == a)["route_id"].to_list()) for a in OPERATORS}
        plan = I.with_calendar(self.sched.with_columns(pl.lit(day).alias("service")))
        self.plan = self.with_medians(plan).with_columns(pl.col("route").cast(pl.Utf8))

    def with_medians(self, rows):
        for table, on in self.tables:
            rows = rows.join(table, on=on, how="left")
        return rows

    def with_history(self, rows):
        """Recorded rows (route as text) with the day's calendar, medians and timetable, as the lab built them."""
        rows = I.with_calendar(rows.with_columns(pl.col("route").cast(pl.Int32)))
        rows = self.with_medians(rows).join(self.sched.drop("code"), on=["route", "start_sec", "order"], how="left")
        return rows.with_columns(pl.col("route").cast(pl.Utf8))


class Recorded:
    """The rows live.py's Recorder has written, read on each time from where the last read stopped. A day's file is
    compressed once it's over, and is then read whole once. known_at is when a row was first read, at most a round after
    the Recorder wrote it (when a report named the stop): the train and light-rail models go by when a report could be
    known, as their training did. Rows there already at the first read count as known when the stop was reached. Each
    row keeps its journey (ref) for each_stop_once()."""

    def __init__(self):
        self.read = {}  # path: (bytes read, rows)

    def rows(self, paths):
        frames = []
        for path in paths:
            done, have = self.read.get(path, (0, None))
            try:
                if path.endswith(".gz"):
                    if have is None:
                        have = I.load_history([path], keep=("ref",)).with_columns(pl.col("at").alias("known_at"))
                else:
                    size = os.path.getsize(path)
                    if size < done:
                        done, have = 0, None
                    if size > done:
                        with open(path, "rb") as f:
                            f.seek(done)
                            chunk = f.read(size - done)
                        # The Recorder may be writing the last line.
                        end = chunk.rfind(b"\n") + 1
                        if end:
                            stamp = int(time.time()) if done else 0
                            new = I.load_history([io.BytesIO(chunk[:end])], keep=("ref",)).with_columns(
                                pl.max_horizontal(pl.col("at"), pl.lit(stamp)).alias("known_at"))
                            have = new if have is None else pl.concat([have, new], rechunk=False)
                            done += end
            except OSError:
                continue  # put away between listing and reading: next time
            if have is not None:
                self.read[path] = (done, have)
                frames.append(have)
        for path in [p for p in self.read if p not in paths]:
            del self.read[path]
        return pl.concat(frames) if frames else None


class Model:
    """The network as numpy arrays (train_live.make_net): a line embedding, one for each of the two stops, and three
    hidden layers, giving the time less the history's as a share of ten minutes."""

    def __init__(self, path, fallback=None):
        z = np.load(path)
        meta = json.loads(str(z["meta"]))
        self.w = {k: z[k] for k in z.files if k != "meta"}
        self.routes = meta["routes"]
        self.codes = {int(k): v for k, v in meta["codes"].items()}
        self.groups = tuple(meta["groups"])
        # A motion model (the lab's live5) also takes each bus's motion (motion.py), and was trained on reports as
        # they became known (known_at), not as the stops were reached; where a bus's motion can't be told (its inputs
        # missing) the fallback network's times go out instead: there the motion network did worse than its control.
        self.motion = meta.get("variant") == "motion"
        self.fallback = fallback

    def estimate(self, ex):
        """Seconds from the moment asked until the bus reaches the stop, as train_live.estimate gives them."""
        out = self.network(ex)
        if self.motion and self.fallback is not None:
            unknown = ex["remaining_m"].is_null().to_numpy() if "remaining_m" in ex.columns else np.ones(ex.height, bool)
            if unknown.any():
                out[unknown] = self.fallback.estimate(ex.filter(pl.Series(unknown)))
        return out

    def network(self, ex):
        x, r, c1, c2 = I.matrix(ex, self.routes, self.codes, self.groups)
        if self.motion:
            x = np.column_stack([x, V.matrix(ex)])
        w = self.w
        h = np.concatenate([x, w["route.weight"][r], w["code.weight"][c1], w["code.weight"][c2]], 1)
        for i in (0, 2, 4):
            h = np.maximum(h @ w["mlp.%d.weight" % i].T + w["mlp.%d.bias" % i], 0)
        return (h @ w["mlp.6.weight"].T + w["mlp.6.bias"])[:, 0] * 600 + ex["base"].to_numpy()


def journeys(now):
    """The journeys on the road by route and first departure, as live.py lists them after every snapshot; None when
    it hasn't lately."""
    try:
        with open(os.path.join(REALTIME, "journeys.json")) as f:
            got = json.load(f)
    except (OSError, ValueError):
        return None
    if now - got.get("at", 0) > FRESH:
        return None
    return pl.DataFrame(got["journeys"], schema={"route": pl.Utf8, "first": pl.Int64}, orient="row")


def places(now):
    """Where each journey on the road is along its route and when it said so, as live.py lists them beside the journeys
    (DistanceFromStop, metres from where its run starts), and its reported speed (km/h) where it gave one: route, first,
    dist, recorded, velocity. None when not listed lately."""
    try:
        with open(os.path.join(REALTIME, "journeys.json")) as f:
            got = json.load(f)
    except (OSError, ValueError):
        return None
    if now - got.get("at", 0) > FRESH or not got.get("places"):
        return None
    rows = [(k.rsplit("_", 1)[0], int(k.rsplit("_", 1)[1]), float(v[0]), int(v[1]), None if len(v) < 3 or v[2] is None else float(v[2]))
            for k, v in got["places"].items()]
    return pl.DataFrame(rows, schema={"route": pl.Utf8, "first": pl.Int64, "dist": pl.Float64, "recorded": pl.Int64, "velocity": pl.Float64},
                        orient="row")


POSITION_SHARE = 0.3  # of the times from where the bus is on its road, in the times given out (position_blend)
POSITION_OLD = 120  # a place reported longer ago than this isn't used
# A stall push: the model this far over its history base, the bus at least this share along the stretch it's on and
# moving at least this fast (m/s): the time from its place gets STALL_SHARE instead (position_blend).
STALL_OVER, STALL_ALONG, STALL_MOVING, STALL_SHARE = 300, 0.5, 8.0, 0.5
SPEED_GAP = (45, 240)  # seconds between the two places a bus's speed is taken from, nearest two minutes (with_speed)


def with_speed(where, memory, gap=SPEED_GAP):
    """places() with each bus's speed along its road (m/s, column speed) from its place a minute or a few earlier,
    nearest two minutes before, which memory keeps from round to round ((route, first) -> [(recorded, dist)]); None
    where it has none. memory takes the new places and forgets what's older than the gap."""
    if where is None:
        return None
    lo, hi = gap
    speeds, newest = [], 0
    for route, first, dist, at in where.select("route", "first", "dist", "recorded").iter_rows():
        past = memory.setdefault((route, first), [])
        then = [(r, d) for r, d in past if lo <= at - r <= hi]
        if then:
            r, d = min(then, key=lambda x: abs(at - x[0] - 120))
            speeds.append((dist - d) / (at - r))
        else:
            speeds.append(None)
        if not past or past[-1][0] < at:
            past.append((at, dist))
        while past and at - past[0][0] > hi:
            past.pop(0)
        newest = max(newest, at)
    for key in [k for k, past in memory.items() if not past or newest - past[-1][0] > hi]:
        del memory[key]
    return where.with_columns(pl.Series("speed", speeds, dtype=pl.Float64))


def road_of(sched, day):
    """For position_blend, once a day: every run's stops with how far along its route each is (schedule's dist) and
    its time after the run sets out, and the stretches between them, for finding where on its run a bus is."""
    keys = [pl.col("route").cast(pl.Utf8), pl.lit(day).alias("service"), pl.col("start_sec").cast(pl.Int64)]
    stops = sched.filter(pl.col("dist").is_not_null()).select(keys + [pl.col("order").cast(pl.Int32), "sched", "dist"])
    seg = (stops.sort(I.RUN + ["order"])
           .with_columns(pl.col("dist").shift(-1).over(I.RUN).alias("d_n"), pl.col("sched").shift(-1).over(I.RUN).alias("s_n"))
           .filter(pl.col("d_n") > pl.col("dist"))
           .select(I.RUN + [pl.col("dist").alias("p"), pl.col("dist").alias("d_b"), pl.col("sched").alias("s_b"), "d_n", "s_n"]).sort("p"))
    return stops, seg


def position_blend(made, road, where, share=POSITION_SHARE):
    """The model's times (predict) blended with the time from where each bus is on its road: the model knows only the
    stop it last reached and how long ago, so a bus most of the way along a long stretch looks like one stuck at its
    start, and its times are pushed later. From the bus's place, the timetable's time still to go to each stop, scaled
    to the usual time from the stop last reached (the history's, as the model's base has it, else the timetable's),
    counted from when it reported. Where the model has pushed a bus moving along a long stretch (STALL_*: where's
    speed), the time from its place counts for half. The model's own time stays as eta_model."""
    if made is None:
        return None
    made = made.with_columns(pl.col("eta").alias("eta_model"))
    if where is None or where.height == 0:
        return made
    stops, seg = road
    keys = [pl.col("route").cast(pl.Utf8), pl.col("service"), pl.col("start_sec").cast(pl.Int64)]
    asked = made.with_row_index("_i").select(["_i"] + keys + ["first", pl.col("order").cast(pl.Int32), pl.col("order_j").cast(pl.Int32),
                                                             "t", "ahead_sched", "elapsed", "base", "eta"])
    asked = asked.join(where.rename({"dist": "p"}), on=["route", "first"])
    # only the runs asked about, not the day's every stop
    runs = asked.select(I.RUN).unique()
    stops, seg = stops.join(runs, on=I.RUN, how="semi"), seg.join(runs, on=I.RUN, how="semi").sort("p")
    m = (asked
         .join(stops.rename({"sched": "s_k", "dist": "d_k"}), on=I.RUN + ["order"])
         .join(stops.with_columns(pl.col("order") - 1).rename({"sched": "s_k1", "dist": "d_k1"}), on=I.RUN + ["order"])
         .join(stops.rename({"order": "order_j", "sched": "s_j", "dist": "d_j"}), on=I.RUN + ["order_j"])
         .filter((pl.col("t") - pl.col("recorded")).is_between(0, POSITION_OLD) & (pl.col("p") >= pl.col("d_k"))
                 & (pl.col("d_k1") >= pl.col("d_k")) & (pl.col("d_j") > pl.col("d_k")) & (pl.col("p") <= pl.col("d_j") + 100)
                 & (pl.col("s_j") > pl.col("s_k"))))
    if m.height == 0:
        return made
    m = m.sort("p").join_asof(seg, on="p", by=I.RUN, strategy="backward").filter(pl.col("s_b").is_not_null())
    s_bus = pl.min_horizontal(pl.col("s_b") + (pl.col("p") - pl.col("d_b")).clip(0, None) / (pl.col("d_n") - pl.col("d_b"))
                              * (pl.col("s_n") - pl.col("s_b")), pl.col("s_n"))
    usual = pl.when(pl.col("base") > 30.5).then(pl.col("base") + pl.col("elapsed")).otherwise(pl.col("ahead_sched").cast(pl.Float64))
    ratio = (usual / (pl.col("s_j") - pl.col("s_k"))).clip(0.5, 2.0)
    road_time = pl.max_horizontal(pl.col("t") + 30, pl.col("recorded") + ratio * (pl.col("s_j") - s_bus))
    w = pl.lit(share)
    if "speed" in m.columns:
        along = (pl.col("p") - pl.col("d_k")) / (pl.col("d_k1") - pl.col("d_k"))
        stall = ((pl.col("eta") - pl.col("t") - pl.col("base") >= STALL_OVER) & (pl.col("d_k1") > pl.col("d_k"))
                 & (along >= STALL_ALONG) & (pl.col("speed") >= STALL_MOVING))
        w = pl.when(stall).then(pl.lit(STALL_SHARE)).otherwise(pl.lit(share))
    m = m.select("_i", ((1 - w) * pl.col("eta") + w * road_time).round().cast(pl.Int64).alias("eta_road"))
    out = made.with_row_index("_i").join(m, on="_i", how="left")
    return out.with_columns(pl.coalesce("eta_road", "eta").alias("eta")).drop("_i", "eta_road")


def questions(rows, plan, now, on_road=None):
    """Every bus on its way now, about each stop of its run ahead of the last one it reached, within the hour the model
    looks ahead by the timetable. A bus on the road is one Kav's server still sees (on_road); without that list, one
    seen reaching a stop within GONE, which can be a bus whose run has ended."""
    on = (rows.filter(pl.col("at") <= now).group_by(I.RUN)
          .agg(pl.col("order").sort_by("at").last().alias("k"), pl.col("at").max().alias("last"),
               (pl.col("at") - pl.col("took")).first().alias("first"))
          .filter(pl.lit(now) - pl.col("last") <= GONE))
    if on_road is not None:
        on = on.join(on_road, on=["route", "first"], how="semi")
    k = plan.select(I.RUN + [pl.col("order").alias("k"), pl.col("sched").alias("sched_k")])
    return (on.join(k, on=I.RUN + ["k"]).join(plan.select(I.RUN + ["order", "sched"]), on=I.RUN)
            .filter((pl.col("order") > pl.col("k")) & (pl.col("sched") - pl.col("sched_k")).is_between(0, 3600))
            .select(I.RUN + ["first", pl.lit(now).cast(pl.Int64).alias("t"), pl.col("order").alias("order_j")]))


def each_stop_once(rows):
    """The recorded rows (with each one's journey, ref) less ref, a run that named a stop twice under one journey kept
    with its last naming of each stop, less a stop named before one earlier on its way: the model's inputs leave out a
    run with a stop twice as two buses on one trip, and Kav's times for the rest of its way would go with it. Most such
    runs named a stop early, then the stops before it and it again: a report jumping ahead, which the last naming leaves
    behind, and the stops named only then are dropped as the passings are judged. A run under two journeys stays as it
    is."""
    if "ref" not in rows.columns:
        return rows
    runs = (rows.group_by(I.RUN).agg((pl.len() > pl.col("order").n_unique()).alias("twice"), (pl.col("ref").n_unique() == 1).alias("one"))
            .filter(pl.col("twice") & pl.col("one")).select(I.RUN))
    if runs.height == 0:
        return rows.drop("ref")
    last = (rows.join(runs, on=I.RUN, how="semi").sort("at").group_by(I.RUN + ["order"], maintain_order=True).last()
            .sort(I.RUN + ["order"]).with_columns(pl.col("at").cum_max().over(I.RUN).alias("before"))
            .filter(pl.col("at") >= pl.col("before")).select(rows.columns))
    return pl.concat([rows.join(runs, on=I.RUN, how="anti"), last]).drop("ref")


UNPLANNED_NEAR = 1800  # seconds from a run the timetable doesn't have to the planned trip it's timed by (with_unplanned)


def with_unplanned(day, rows, near=UNPLANNED_NEAR):
    """day, or a copy of it whose plan also has the runs a bus has been seen on that its timetable doesn't, each the
    route's planned trip nearest in time whose stops are the ones the bus has named, moved to the run's own start, with
    the medians for that start: the ministry's buses can run trips some other timetable has (an election day), and the
    model times only the plan's."""
    planned = day.plan.select(I.RUN).unique()
    runs = rows.select(I.RUN).unique().join(planned, on=I.RUN, how="anti")
    if runs.height == 0:
        return day
    near_trips = (runs.join(planned.rename({"start_sec": "planned"}), on=["route", "service"])
                  .filter((pl.col("planned") - pl.col("start_sec")).abs() <= near))
    if near_trips.height == 0:
        return day
    named = rows.join(runs, on=I.RUN, how="semi").select(I.RUN + ["order", "code"]).unique()
    codes = day.plan.select("route", "service", pl.col("start_sec").alias("planned"), "order", pl.col("code").alias("planned_code"))
    best = (near_trips.join(named, on=I.RUN).join(codes, on=["route", "service", "planned", "order"], how="left")
            .group_by(I.RUN + ["planned"]).agg((pl.col("planned_code") == pl.col("code")).fill_null(False).all().alias("same"))
            .filter(pl.col("same")).with_columns((pl.col("planned") - pl.col("start_sec")).abs().alias("off"))
            .sort("off", "planned").group_by(I.RUN, maintain_order=True).first())
    if best.height == 0:
        return day
    types = day.sched.schema
    own = (day.sched.join(best.select(pl.col("route").cast(types["route"]), pl.col("planned").cast(types["start_sec"]).alias("start_sec"),
                                      pl.col("start_sec").cast(types["start_sec"]).alias("own")), on=["route", "start_sec"])
           .with_columns(pl.col("own").alias("start_sec")).select(day.sched.columns))
    plan = day.with_medians(I.with_calendar(own.with_columns(pl.lit(day.day).alias("service"))))
    view = copy.copy(day)
    view.plan = pl.concat([day.plan, plan.with_columns(pl.col("route").cast(pl.Utf8)).select(day.plan.columns)])
    view.sched = pl.concat([day.sched, own])
    return view


def predict(day, rows, model, now, on_road=None, motion=None):
    """Each bus's predicted arrival at each stop ahead: the inputs as the lab built them, asked live. live3_all was
    trained on rows taken as known when the stop was reached; a motion model on rows as they became known, with each
    bus's motion (motion: Tracks.state())."""
    rows = day.with_history(rows if model.motion else rows.drop("known_at", strict=False))
    q = questions(rows, day.plan, now, on_road)
    if q.height == 0:
        return None
    ex = I.base(I.examples(rows.filter(pl.col("at") <= now), q, day.plan)).filter(pl.col("base").is_not_null())
    if ex.height == 0:
        return None
    if model.motion:
        ex = V.add_motion(ex, motion if motion is not None else V.Tracks().state(), day.road[0])
    return ex.select(I.RUN + ["first", "order", "order_j", "code_j", "t", "stops_ahead", "ahead_sched", "elapsed", "base"]).with_columns(
        (pl.col("t") + pl.Series(model.estimate(ex)).round().cast(pl.Int64)).alias("eta"))


class RailModel:
    """A train or light-rail model (the lab's gbdt_nonbus.py, as score_moovit_rail.booster loads it): LightGBM trees
    correcting the history's time from now until the train reaches the station, blended with it by alpha."""

    def __init__(self, folder, agency):
        import lightgbm as lgb
        self.booster = lgb.Booster(model_file=os.path.join(folder, "gbdt-%s.txt" % agency))
        with open(os.path.join(folder, "gbdt-%s-categories.json" % agency)) as f:
            saved = json.load(f)
        self.categories = dict(route=saved["route"], code={int(k): v for k, v in saved["code"].items()},
                               code_j={int(k): v for k, v in saved["code_j"].items()})
        self.features = saved.get("features")
        # Learnt from every line's earlier journey between the stations and the extra inputs (extra_nonbus.py).
        self.every = saved.get("journeys") == "all"
        with open(os.path.join(folder, "gbdt-%s-report.json" % agency)) as f:
            self.alpha = json.load(f)["alpha"]

    def estimate(self, ex):
        """Seconds from the moment asked until the train reaches the station, as window_check.predict gives them."""
        history = ex["history"].to_numpy()
        trees = np.maximum(0, self.booster.predict(R.matrix(ex, self.categories, self.features)) + history)
        return self.alpha * trees + (1 - self.alpha) * history


def ministry_time(day, seconds):
    """When a run timetabled seconds into service day `day` sets out, as the ministry stamps it (live.py's
    ministry_time): by the clock, the hour the clocks go back taken the second time. A timetable counts from noon less
    twelve hours, which is the same except that night, when the ministry stamps the runs at 00:xx in summer time."""
    at = datetime.datetime.combine(day, datetime.time()) + datetime.timedelta(seconds=int(seconds))
    return int(at.replace(tzinfo=ISRAEL, fold=1).timestamp())


QUICKER_SINCE = 5400  # the route's buses that set out this long ago at most say how quickly it runs today (quicker())
QUICKER_LEAST = 6  # stops they've reached, at least
QUICKER_BELOW, QUICKER_MOST = 0.85, 0.6  # a share of the medians taken under this, and no lower than this


def quicker(day, rows, now, below=QUICKER_BELOW):
    """By route, the share of the history medians that its buses set out lately took to the stops they reached, where
    that's under `below` (QUICKER_BELOW unless asked), no lower than QUICKER_MOST: on a day they run quicker than the
    days the medians come from (a holiday, an election day), set_out()'s times would be far too late. Only stops within
    the hour of setting out, as set_out() times them."""
    seen = (rows.filter(pl.col("at") <= now).group_by(I.RUN + ["order"]).agg(pl.col("at").min(), pl.col("took").min())
            .filter(pl.col("at") - pl.col("took") >= now - QUICKER_SINCE))
    usual = day.plan.select(I.RUN + ["order", "sched", pl.coalesce(I.HIST).alias("usual")])
    return (seen.join(usual, on=I.RUN + ["order"])
            .filter((pl.col("order") > 1) & (pl.col("sched") <= 3600) & (pl.col("usual") >= 120))
            .group_by("route").agg((pl.col("took").sum() / pl.col("usual").sum()).alias("share"), pl.len().alias("seen"))
            .filter((pl.col("seen") >= QUICKER_LEAST) & (pl.col("share") < below))
            .select("route", pl.col("share").clip(QUICKER_MOST, None)))


def set_out(day, rows, on_road, now):
    """The buses on the road that haven't reached a stop in Kav's history yet, just set out, which the model can't
    time: each stop ahead within the hour at the timetable's first departure plus the usual time to it (the history
    medians), less on a day the route runs quicker (quicker()). For them that beats the ministry's own times at every
    distance."""
    if on_road is None:
        return None
    first = day.plan.filter(pl.col("route").is_in(sorted(day.buses)) & (pl.col("order") == 1)).select(I.RUN)
    stamps = {s: ministry_time(day.day, s) for s in first["start_sec"].unique().to_list()}
    runs = (first.with_columns(pl.col("start_sec").replace_strict(stamps, return_dtype=pl.Int64).alias("first"))
            .join(on_road, on=["route", "first"], how="semi").join(rows.select(I.RUN).unique(), on=I.RUN, how="anti"))
    if runs.height == 0:
        return None
    return (day.plan.join(runs, on=I.RUN).filter((pl.col("order") > 1) & (pl.col("sched") <= 3600))
            .join(quicker(day, rows, now), on="route", how="left")
            .with_columns((pl.col("first") + (pl.coalesce(I.HIST) * pl.col("share").fill_null(1.0)).round().cast(pl.Int64)).alias("eta"))
            .filter(pl.col("eta").is_not_null())
            .select(I.RUN + ["first", pl.col("order").alias("order_j"), pl.col("code").alias("code_j"),
                             pl.lit(now).cast(pl.Int64).alias("t"), "eta"]))


def special_days(path=SPECIAL_DAYS):
    """The days the planner's timetable has down as special (otp/predict.py: the ministry runs clearly fewer buses on
    them than on its other days of that kind), from its list; none without one."""
    try:
        with open(path) as f:
            words = f.read().split()
    except OSError:
        return set()
    days = set()
    for word in words:
        try:
            days.add(datetime.date.fromisoformat(word))
        except ValueError:
            pass
    return days


SOONER = 0.5  # the power of its route's share a bus's time still to go is taken by on a special day (sooner())


def sooner(made, shares, power=SOONER):
    """The model's times (after the position blend) on a special day: each bus's time still to go times its route's
    share of the medians (quicker(), any share under 1) to the power SOONER. The model and its medians learnt from the
    usual days, and on a special day the buses run quicker. The square root of the share keeps the times from going too
    early, as the whole share did."""
    if made is None or shares.height == 0:
        return made
    share = pl.col("share").fill_null(1.0) ** power
    return (made.join(shares, on="route", how="left")
            .with_columns((pl.col("t") + ((pl.col("eta") - pl.col("t")) * share).round().cast(pl.Int64)).alias("eta"))
            .drop("share"))


def usable(rows, now):
    """The trains' reports as the lab took them (nonbus_data.observed), known by now: a run with a station twice, or
    whose times go back, is left out."""
    rows = rows.filter(pl.len().over(R.RUN) == pl.col("order").n_unique().over(R.RUN))
    back = (rows.sort(R.RUN + ["order"]).with_columns(pl.col("at").diff().over(R.RUN).alias("step"))
            .filter(pl.col("step") < 0).select(R.RUN).unique())
    return rows.join(back, on=R.RUN, how="anti").filter(pl.col("known_at") <= now)


def predict_rail(day, rows, model, routes, now, on_road=None):
    """The predicted arrival of each of an operator's trains on its way at each station ahead, with its own model, from
    what was known by now, as the lab built its inputs."""
    rows = rows.filter(pl.col("route").is_in(sorted(routes)))
    if rows.height == 0:
        return None
    rows = usable(day.with_history(rows), now)
    plan = day.plan.filter(pl.col("route").is_in(sorted(routes)))
    q = questions(rows, plan, now, on_road)
    if q.height == 0:
        return None
    ex = R.inputs(rows, q, "all" if model.every else "asked", plan)
    if model.every:
        ex = R.extra(rows, ex, plan.select("route", "start_sec", "order", "code"))
    ex = ex.filter(pl.all_horizontal([pl.col(c).is_finite() for c in ("timetable", "keep_delay", "history")]))
    if ex.height == 0:
        return None
    ex = ex.join(q.select(R.RUN + ["t", "order_j", "first"]), on=R.RUN + ["t", "order_j"])
    return ex.select(R.RUN + ["first", "order", "order_j", "code_j", "t", "history", "keep_delay"]).with_columns(
        (pl.col("t") + pl.Series(model.estimate(ex)).round().cast(pl.Int64)).alias("eta"))


def mark(path, now):
    """How live.py knows Kav asked (live.signed)."""
    slot = int(now // 600)
    return "%d.%s" % (slot, hmac.new(APP_KEY, ("%d:%s" % (slot, path)).encode(), hashlib.sha256).hexdigest()[:32])


def ministry(codes, now):
    """What the ministry expects at these stops, through Kav's server: rows as live.py's arrivals gives them."""
    path = "/live/v1/stops/" + ",".join(str(c) for c in sorted(codes))
    req = urllib.request.Request(KAV_LIVE + path, headers={"X-Kav": mark(path, now), "User-Agent": "Kav eta"})
    with urllib.request.urlopen(req, timeout=20) as r:
        rows = json.load(r).get("a") or []
    # Kav's server answers with Kav's own times where it has them, and the ministry's own last.
    return pl.DataFrame([dict(stop=a[0], route=str(a[1]), first=a[4], aimed=a[5], expected=a[14] if len(a) > 14 else a[6],
                              recorded=a[9] or 0)
                         for a in rows if len(a) > 9],
                        schema={"stop": pl.Int64, "route": pl.Utf8, "first": pl.Int64, "aimed": pl.Int64, "expected": pl.Int64,
                                "recorded": pl.Int64})


def keep_log(now, made, rail=None, started=None):
    """Every prediction made now, and the ministry's own times then for some of the stops they're about: of the bus
    model's, and a batch of the train and light-rail models' stations."""
    local = datetime.datetime.fromtimestamp(now, ISRAEL)
    folder = os.path.join(OUT, "log", local.strftime("%Y-%m-%d"))
    os.makedirs(folder, exist_ok=True)
    # The hour the clocks go back comes twice: its second time, files of its own.
    name = local.strftime("%H%M") + ("b" if local.fold else "")
    made.write_parquet(os.path.join(folder, name + "-kav.parquet"))
    pick = random.Random(int(now))
    codes = made["code_j"].unique().to_list()
    asked = pick.sample(codes, min(ASKED * ASKS, len(codes)))
    if started is not None:
        started.write_parquet(os.path.join(folder, name + "-started.parquet"))
    if rail is not None:
        rail.write_parquet(os.path.join(folder, name + "-rail.parquet"))
        stations = rail["code_j"].unique().to_list()
        asked += pick.sample(stations, min(ASKED, len(stations)))
    got = []
    for i in range(0, len(asked), ASKED):
        try:
            got.append(ministry(asked[i:i + ASKED], now))
        except Exception as e:
            log("the ministry's times failed:", repr(e))
    if got:
        pl.concat(got).with_columns(pl.lit(int(now)).alias("t")).write_parquet(os.path.join(folder, name + "-mot.parquet"))


def hand_over(made, buses, now):
    """The buses' predictions for live.py, which gives them to the planner and to the app instead of the ministry's
    expected times: each journey's stops ahead by route and first departure, as [place on the trip, stop code, when]."""
    runs = {}
    for route, first, order, code, eta in (made.filter(pl.col("route").is_in(sorted(buses))).sort("route", "first", "order_j")
                                           .select("route", "first", "order_j", "code_j", "eta").iter_rows()):
        runs.setdefault("%s_%d" % (route, first), []).append([order, code, eta])
    path = os.path.join(OUT, "now.json")
    with open(path + ".part", "w") as f:
        json.dump({"at": int(now), "runs": runs}, f, separators=(",", ":"))
    os.replace(path + ".part", path)
    return len(runs)


def handing(made, started, rail_made, buses):
    """What goes to live.py: the bus model's times for buses only (it's asked about every run on the road, trains and
    light rail too, which have models of their own), the buses just set out, and the trains' and light rail's handed over
    (RAIL_HANDOVER). None with nothing to give."""
    cols = ["route", "service", "start_sec", "first", "order_j", "code_j", "t", "eta"]
    given = []
    if made is not None:
        given.append(made.filter(pl.col("route").is_in(sorted(buses))).select(cols))
    if started is not None:
        given.append(started.select(cols))
    if rail_made is not None:
        given.append(rail_made.filter(pl.col("agency").is_in(RAIL_HANDOVER)).select(cols))
    return pl.concat(given, how="vertical_relaxed") if given else None


def service_days(now):
    """The service days with buses on the road: today's, and until early morning yesterday's too."""
    local = datetime.datetime.fromtimestamp(now, ISRAEL)
    today = local.date()
    return ([today - datetime.timedelta(days=1)] if local.hour < RUNS_UNTIL else []) + [today]


def peak_gb():
    return round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e6, 2)


def main():
    os.makedirs(OUT, exist_ok=True)
    model = Model(MODEL, Model(MODEL_FALLBACK) if MODEL_FALLBACK else None)
    rail = {a: RailModel(RAIL, a) for a in OPERATORS if os.path.exists(os.path.join(RAIL, "gbdt-%s.txt" % a))}
    days, recorded, logged = {}, Recorded(), 0
    motion = {}  # each bus's recent places, for its speed (with_speed)
    special = set()  # the special days listed so far (special_days()); the list only grows
    tracks = V.Tracks()  # and a minute apart, for a motion model's inputs
    log("model", MODEL, len(model.routes), "routes", len(model.codes), "stops", "motion" if model.motion else "", MODEL_FALLBACK,
        "; rail models", sorted(rail))
    while True:
        start = time.time()
        status = {"at": int(start)}
        try:
            active = service_days(start)
            for day in active:
                if day not in days:
                    t = time.time()
                    days[day] = Day(day, history_files([HISTORY, BACKFILL]))
                    log("day", day, "ready in %.0f s:" % (time.time() - t), days[day].plan.height, "planned stops, peak",
                        peak_gb(), "GB")
            for day in [d for d in days if d not in active]:
                del days[day]
            special |= special_days()
            files = history_files([HISTORY])
            names = sorted({(d + datetime.timedelta(days=k)).isoformat() for d in active for k in (0, 1)})
            rows = recorded.rows([files[n] for n in names if n in files])
            on_road = journeys(start)
            where = with_speed(places(start), motion)
            tracks.feed(where, int(start))
            moving = tracks.state() if model.motion else None
            status["on the road"] = None if on_road is None else on_road.height
            status["placed"] = None if where is None else where.height
            made, rail_made, started = [], [], []
            if rows is not None:
                for day in active:
                    of_day = rows.filter(pl.col("service") == day)
                    # The bus model's: a run that named a stop twice under one journey kept, and the runs the timetable doesn't
                    # have timed by its nearest planned trip.
                    own = each_stop_once(of_day)
                    of_day = of_day.drop("ref", strict=False)
                    part = predict(with_unplanned(days[day], own), own, model, int(start), on_road, moving)
                    try:
                        part = position_blend(part, days[day].road, where)
                    except Exception:  # the model's own times rather than none
                        log("position blend failing:", traceback.format_exc())
                    if part is not None and day in special:
                        part = sooner(part, quicker(days[day], of_day, int(start), below=1.0))
                    if part is not None:
                        made.append(part)
                    # On a special day the buses just set out keep the ministry's own times, which beat set_out()'s
                    # there.
                    part = None if day in special else set_out(days[day], of_day, on_road, int(start))
                    if part is not None:
                        started.append(part)
                    for agency, rm in rail.items():
                        part = predict_rail(days[day], of_day, rm, days[day].rail[agency], int(start), on_road)
                        if part is not None:
                            rail_made.append(part.with_columns(pl.lit(agency).alias("agency")))
            made = pl.concat(made) if made else None
            started = pl.concat(started) if started else None
            rail_made = pl.concat(rail_made) if rail_made else None
            status["just set out"] = 0 if started is None else started.select(I.RUN).unique().height
            status["rail predictions"] = 0 if rail_made is None else rail_made.height
            buses = set().union(*(days[d].buses for d in active))
            given = handing(made, started, rail_made, buses)
            if given is not None:
                routes = buses.union(*(days[d].rail[a] for d in active for a in RAIL_HANDOVER))
                status["journeys handed over"] = hand_over(given, routes, start)
            status.update(rows=0 if rows is None else rows.height, predictions=0 if made is None else made.height,
                          buses=0 if made is None else made.select(I.RUN).unique().height, seconds=round(time.time() - start, 2),
                          peak_gb=peak_gb())
            if made is not None and LOG_EVERY and start - logged >= LOG_EVERY:
                keep_log(start, made, rail_made, started)
                logged = start
        except Exception as e:
            status["error"] = repr(e)
            log("failing:", traceback.format_exc())
        with open(os.path.join(OUT, "status.json.part"), "w") as f:
            json.dump(status, f)
        os.replace(os.path.join(OUT, "status.json.part"), os.path.join(OUT, "status.json"))
        time.sleep(max(1.0, EVERY - (time.time() - start)))


if __name__ == "__main__":
    sys.exit(main())
