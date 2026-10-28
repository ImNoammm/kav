"""The bus arrival model's inputs, built exactly as the lab built them to train it: these are copies of its functions
(.cache/planner/ml: dataset.py, features.py, features_live.py, train.py, train_live.py), so a change there must be
copied here. Kav's server asks live, so `examples` takes the day's plan for the stops ahead (see its docstring)."""
import datetime
import math

import numpy as np
import polars as pl


TZ = "Asia/Jerusalem"


def load_history(paths, keep=()):
    """The Recorder's rows as the lab read them; `keep` adds columns of its own (eta.py keeps "ref", the journey)."""
    frames = []
    for path in paths:
        df = pl.read_csv(path, separator="\t", has_header=False, infer_schema=False, quote_char=None,
                         new_columns=["route", "ref", "service", "start", "order", "code", "at"])
        frames.append(df)
    df = pl.concat(frames)
    start = pl.col("start").str.to_datetime("%Y-%m-%dT%H:%M:%S%:z", time_zone="UTC")
    local = start.dt.convert_time_zone(TZ)
    service = pl.col("service").str.to_date()
    return (df.filter(pl.col("code") != "")
            .with_columns(service.alias("service"), start.dt.epoch("s").alias("start_epoch"),
                          ((local.dt.date() - service).dt.total_days() * 86400 + local.dt.hour().cast(pl.Int32) * 3600 +
                           local.dt.minute().cast(pl.Int32) * 60 + local.dt.second().cast(pl.Int32)).alias("start_sec"),
                          pl.col("order").cast(pl.Int32), pl.col("code").cast(pl.Int32), pl.col("at").cast(pl.Int64))
            .with_columns((pl.col("at") - pl.col("start_epoch")).cast(pl.Int32).alias("took"))
            .filter(pl.col("took").is_between(-600, 4 * 3600))
            .select(["route", "service", "start_sec", "order", "code", "took", "at"] + list(keep)))


def hms(col):
    parts = pl.col(col).str.split(":")
    return (parts.list.get(0).cast(pl.Int32) * 3600 + parts.list.get(1).cast(pl.Int32) * 60 + parts.list.get(2).cast(pl.Int32))


# Days on holiday timetables (Hebcal's Israel calendar): eves, holidays, the days between Sukkot's and Pesach's
# first and last, Yom HaZikaron (its evening) and Yom HaAtzmaut, through 2035: each stretch's first day and length.
HOLIDAYS = {datetime.date.fromisoformat(first) + datetime.timedelta(days=k) for first, days in (
    ("2025-10-01", 2), ("2025-10-06", 9), ("2026-04-01", 8), ("2026-04-21", 2), ("2026-05-21", 2), ("2026-09-11", 3),
    ("2026-09-20", 2), ("2026-09-25", 9), ("2027-04-21", 8), ("2027-05-11", 2), ("2027-06-10", 2), ("2027-10-01", 3),
    ("2027-10-10", 2), ("2027-10-15", 9), ("2028-04-10", 8), ("2028-05-01", 2), ("2028-05-30", 2), ("2028-09-20", 3),
    ("2028-09-29", 2), ("2028-10-04", 9), ("2029-03-30", 8), ("2029-04-18", 2), ("2029-05-19", 2), ("2029-09-09", 3),
    ("2029-09-18", 2), ("2029-09-23", 9), ("2030-04-17", 8), ("2030-05-07", 2), ("2030-06-06", 2), ("2030-09-27", 3),
    ("2030-10-06", 2), ("2030-10-11", 9), ("2031-04-07", 8), ("2031-04-28", 2), ("2031-05-27", 2), ("2031-09-17", 3),
    ("2031-09-26", 2), ("2031-10-01", 9), ("2032-03-26", 8), ("2032-04-14", 2), ("2032-05-15", 2), ("2032-09-05", 3),
    ("2032-09-14", 2), ("2032-09-19", 9), ("2033-04-13", 8), ("2033-05-03", 2), ("2033-06-02", 2), ("2033-09-23", 3),
    ("2033-10-02", 2), ("2033-10-07", 9), ("2034-04-03", 8), ("2034-04-24", 2), ("2034-05-23", 2), ("2034-09-13", 3),
    ("2034-09-22", 2), ("2034-09-27", 9), ("2035-04-23", 8), ("2035-05-14", 2), ("2035-06-12", 2), ("2035-10-03", 3),
    ("2035-10-12", 2), ("2035-10-17", 9)
) for k in range(days)}


def with_calendar(df, off=HOLIDAYS):
    wd = pl.col("service").dt.weekday()  # 1 Monday .. 7 Sunday
    return df.with_columns(
        pl.when(wd == 5).then(pl.lit("F")).when(wd == 6).then(pl.lit("S")).otherwise(pl.lit("W")).alias("kind"),
        wd.alias("weekday"), (pl.col("start_sec") // 1800).alias("bucket"),
        pl.col("service").is_in(sorted(off)).alias("holiday"))


RUN = ["route", "service", "start_sec"]


HIST = ["h_trip", "h_bucket", "h_weekday", "h_recent"]


RECENT = 1800  # other buses count for the road ahead when they drove it this recently


LINE_WINDOW, COUNTRY_WINDOW = 1800, 900


def gap(a, b):
    """History time between two stops (column suffixes a, b), from the most specific source that has both."""
    return pl.coalesce([pl.col(h + b) - pl.col(h + a) for h in HIST])


def stops(rows, suffix, shift=0):
    """Each run's stops with every column renamed by `suffix`, its order moved by `shift` so it joins onto another."""
    cols = ["code", "took", "at", "sched"] + (["known_at"] if "known_at" in rows.columns else []) + HIST + (["weekday", "holiday", "headway", "sched_headway"] if suffix == "" else [])
    return rows.select(RUN + [(pl.col("order") + shift).alias("order")] + [pl.col(c).alias(c + suffix) for c in cols])


def examples(rows, questions, plan=None):
    """For questions (RUN, t, order_j, anything else kept): the stop k the bus had last reached by t, the time from t
    until it reached j (target), and every input. Questions about a bus that hadn't started or had already passed j
    by t are dropped. Asked live, nothing is known yet of the stops ahead: `plan` (RUN, order, code, sched and the
    history medians of every stop of the day's runs) gives j and the stop after k, and there is no target."""
    # A trip recorded with two vehicles has two passings of some stops; it can't say which bus is which, so it's left out.
    rows = rows.with_columns(pl.col("at").cast(pl.Int64)).filter(pl.len().over(RUN) == pl.col("order").n_unique().over(RUN))
    received = "known_at" in rows.columns
    if not received:
        rows = rows.with_columns(pl.col("at").alias("known_at"))
    # The bus of the line that passed each stop before this one: how long before, and how far apart they were meant to be.
    stop = ["route", "service", "order"]
    clock = "known_at" if received else "at"
    rows = rows.sort(clock).with_columns((pl.col("at") - pl.col("at").shift(1).over(stop)).alias("headway"),
                                        (pl.col("start_sec") - pl.col("start_sec").shift(1).over(stop)).alias("sched_headway"))
    k = rows.select(RUN + ["order", clock]).sort(clock)
    q = questions.sort("t").join_asof(k, left_on="t", right_on=clock, by=RUN, strategy="backward").drop(clock)
    q = q.filter(pl.col("order").is_not_null() & (pl.col("order_j") > pl.col("order")))
    ahead = rows if plan is None else plan.with_columns(pl.lit(None, pl.Int32).alias("took"), pl.lit(None, pl.Int64).alias("at"))
    ex = (q.join(stops(rows, ""), on=RUN + ["order"])
          .join(stops(ahead, "_j").rename({"order": "order_j"}), on=RUN + ["order_j"])
          .join(stops(ahead, "_n", -1).select(RUN + ["order"] + [h + "_n" for h in HIST]), on=RUN + ["order"], how="left"))
    for n in (3, 6, 10):
        ex = ex.join(stops(rows, "_b%d" % n, n).select(RUN + ["order", "took_b%d" % n, "sched_b%d" % n, "known_at_b%d" % n] + [h + "_b%d" % n for h in HIST]),
                     on=RUN + ["order"], how="left")
        if received:
            ex = ex.with_columns(pl.when(pl.col("known_at_b%d" % n) <= pl.col("t"))
                                 .then(pl.col("took_b%d" % n)).alias("took_b%d" % n))
    ex = ex.with_columns(
        (pl.col("at_j") - pl.col("t")).alias("target"), (pl.col("t") - pl.col("at")).alias("elapsed"),
        (pl.col("order_j") - pl.col("order")).alias("stops_ahead"), (pl.col("sched_j") - pl.col("sched")).alias("ahead_sched"),
        gap("", "_j").alias("ahead_hist"), gap("", "_n").alias("next_usual"), (pl.col("took") - pl.col("sched")).alias("delay"),
        ((pl.col("took") - pl.col("sched")) - (pl.col("took_b3") - pl.col("sched_b3"))).alias("delay_trend"),
        *[((pl.col("took") - pl.col("took_b%d" % n)) - gap("_b%d" % n, "")).alias("pace%d" % n) for n in (3, 6, 10)])
    ex = ex.filter(pl.col("ahead_sched").is_between(0, 3600) & (pl.lit(True) if plan is not None else pl.col("target").is_between(1, 3 * 3600)))
    ex = ex.drop([c for c in ex.columns if c.endswith(("_n", "_b3", "_b6", "_b10")) or c in HIST or c[:-2] in HIST])
    return traffic(rows, previous(rows, ex, received), received, plan)


def previous(rows, ex, received=False):
    """The last three other buses of the line that drove from k to j before t: how much slower than usual, how long ago."""
    pairs = ex.select("route", "service", "order", "order_j").unique()
    a = stops(rows, "_a").select(RUN + ["order", "took_a", "sched_a", "known_at_a"] + [h + "_a" for h in HIST])
    b = stops(rows, "_b").select(RUN + [pl.col("order").alias("order_j"), "took_b", "known_at_b", "sched_b"] + [h + "_b" for h in HIST])
    done = (pairs.join(a, on=["route", "service", "order"]).join(b, on=RUN + ["order_j"])
            .with_columns(((pl.col("took_b") - pl.col("took_a")) -
                           pl.coalesce([gap("_a", "_b"), pl.col("sched_b") - pl.col("sched_a")])).alias("ex1"),
                          (pl.max_horizontal("known_at_a", "known_at_b") if received else pl.col("known_at_b")).alias("known_at_b"))
            .sort("known_at_b"))
    key = ["route", "service", "order", "order_j"]
    done = done.select(key + [pl.col("known_at_b").alias("at1"), "ex1"]).with_columns(
        pl.col("ex1").shift(1).over(key).alias("ex2"), pl.col("at1").shift(1).over(key).alias("at2"),
        pl.col("ex1").shift(2).over(key).alias("ex3"), pl.col("at1").shift(2).over(key).alias("at3"))
    ex = ex.sort("t").join_asof(done.sort("at1"), left_on="t", right_on="at1", by=key, strategy="backward")
    return ex.with_columns(*[(pl.col("t") - pl.col("at%d" % i)).alias("age%d" % i) for i in (1, 2, 3)]).drop("at1", "at2", "at3")


def traffic(rows, ex, received=False, plan=None):
    """How much slower than usual buses of any line drove the stretches from k to j in the last half hour, and the line
    and the whole country lately: rates of extra time to usual time."""
    seg = (rows.sort(RUN + ["order"]).with_columns(
        *[pl.col(c).shift(-1).over(RUN).alias(c + "_n") for c in ["order", "code", "took", "at", "known_at"] + HIST])
        .filter(pl.col("order_n") == pl.col("order") + 1)
        .with_columns(gap("", "_n").alias("usual"))
        .filter(pl.col("usual").is_not_null() & (pl.col("usual") > 0))
        .select("route", "order", pl.col("code").alias("code_a"), pl.col("code_n").alias("code_b"),
                (pl.max_horizontal("known_at", "known_at_n") if received else pl.col("at_n")).alias("seen"),
                (pl.col("took_n") - pl.col("took") - pl.col("usual")).alias("extra"), "usual"))
    # The line's path and its usual time on each stretch: from the day's buses, or asked live, where they haven't all
    # been yet, from the timetable.
    usual = seg if plan is None else (
        plan.sort(RUN + ["order"]).with_columns(*[pl.col(c).shift(-1).over(RUN).alias(c + "_n") for c in ["order"] + HIST])
        .filter(pl.col("order_n") == pl.col("order") + 1)
        .with_columns(gap("", "_n").alias("usual"))
        .filter(pl.col("usual").is_not_null() & (pl.col("usual") > 0)))
    # The road ahead: each stretch of the line's path, the latest bus of any line that drove it.
    path = (rows if plan is None else plan).group_by("route", "order").agg(pl.col("code").mode().first())
    mo = ex.select(RUN + ["order", "t"]).unique().with_row_index("m").join(
        ex.group_by(RUN + ["order", "t"]).agg(pl.col("stops_ahead").max().alias("far")), on=RUN + ["order", "t"])
    legs = (mo.with_columns(pl.int_ranges(0, pl.col("far")).alias("step")).explode("step")
            .with_columns((pl.col("order") + pl.col("step")).alias("at_order"))
            .join(path.rename({"order": "at_order", "code": "code_a"}), on=["route", "at_order"], how="left")
            .join(path.rename({"order": "at_order", "code": "code_b"}).with_columns(pl.col("at_order") - 1),
                  on=["route", "at_order"], how="left")
            .filter(pl.col("code_a").is_not_null() & pl.col("code_b").is_not_null()))
    legs = (legs.sort("t").join_asof(seg.select("code_a", "code_b", "seen", "extra").sort("seen"), left_on="t", right_on="seen",
                                     by=["code_a", "code_b"], strategy="backward")
            .with_columns(pl.when(pl.col("t") - pl.col("seen") <= RECENT).then(pl.col("extra")).alias("extra"),
                          pl.when(pl.col("t") - pl.col("seen") <= RECENT).then(pl.col("t") - pl.col("seen")).alias("old"))
            .drop("seen"))
    # Steadier: every bus of any line on each stretch in the last half hour, as a share of its usual time, applied to
    # the line's usual time there.
    pair = ["code_a", "code_b"]
    cum = (seg.sort("seen").with_columns((pl.col("extra") / pl.max_horizontal("usual", pl.lit(20))).clip(-0.8, 3).alias("rel"))
           .with_columns(pl.col("rel").cum_sum().over(pair).alias("cr"), pl.int_range(1, pl.len() + 1).over(pair).alias("cn"))
           .select(pair + ["seen", "cr", "cn"]))
    legs = (legs.join_asof(cum, left_on="t", right_on="seen", by=pair, strategy="backward").drop("seen")
            .with_columns((pl.col("t") - RECENT).alias("t0")).sort("t0")
            .join_asof(cum.rename({"cr": "cr0", "cn": "cn0"}), left_on="t0", right_on="seen", by=pair, strategy="backward")
            .with_columns((pl.col("cn").fill_null(0) - pl.col("cn0").fill_null(0)).alias("nw"))
            .with_columns(pl.when(pl.col("nw") > 0).then((pl.col("cr") - pl.col("cr0").fill_null(0)) / pl.col("nw")).alias("rel"))
            .join(usual.group_by("route", "order").agg(pl.col("usual").median().alias("leg_usual"))
                  .rename({"order": "at_order"}), on=["route", "at_order"], how="left"))
    legs = (legs.sort("m", "step")
            .with_columns(pl.col("extra").fill_null(0).cum_sum().over("m").alias("road_extra"),
                          pl.col("extra").is_not_null().cum_sum().over("m").alias("road_seen"),
                          pl.col("old").fill_null(0).cum_sum().over("m").alias("road_old"),
                          (pl.col("rel") * pl.col("leg_usual")).fill_null(0).cum_sum().over("m").alias("road2_extra"),
                          pl.when(pl.col("rel").is_not_null()).then(pl.col("leg_usual")).fill_null(0).cum_sum().over("m").alias("u_seen"),
                          pl.col("leg_usual").fill_null(0).cum_sum().over("m").alias("u_all"))
            .select("m", (pl.col("step") + 1).alias("stops_ahead"), "road_extra", "road_seen", "road_old", "road2_extra",
                    (pl.col("u_seen") / pl.col("u_all")).alias("road2_cover")))
    ex = (ex.join(mo.select(RUN + ["order", "t", "m"]), on=RUN + ["order", "t"])
          .join(legs, on=["m", "stops_ahead"], how="left")
          .with_columns((pl.col("road_seen") / pl.col("stops_ahead")).alias("road_cover"),
                        (pl.col("road_old") / pl.col("road_seen")).alias("road_age"))
          .drop("m", "road_seen", "road_old"))
    # The line, and the whole country, lately
    s = seg.sort("seen")
    line = s.with_columns(pl.col("extra").cum_sum().over("route").alias("cx"), pl.col("usual").cum_sum().over("route").alias("cu"))
    land = s.with_columns(pl.col("extra").cum_sum().alias("cx"), pl.col("usual").cum_sum().alias("cu"))

    def rate(frame, window, by, name):
        f = frame.select(([by] if by else []) + ["seen", "cx", "cu"])
        now = ex.select(RUN + ["order", "t"]).unique().sort("t")
        a = now.join_asof(f, left_on="t", right_on="seen", by=by, strategy="backward")
        b = now.with_columns((pl.col("t") - window).alias("t0")).sort("t0").join_asof(
            f.rename({"cx": "cx0", "cu": "cu0"}), left_on="t0", right_on="seen", by=by, strategy="backward")
        r = a.join(b.select(RUN + ["order", "t", "cx0", "cu0"]), on=RUN + ["order", "t"], how="left").with_columns(
            ((pl.col("cx") - pl.col("cx0").fill_null(0)) / (pl.col("cu") - pl.col("cu0").fill_null(0))).alias(name))
        return r.select(RUN + ["order", "t", pl.when(pl.col(name).is_finite()).then(pl.col(name)).alias(name)])

    ex = ex.join(rate(line, LINE_WINDOW, "route", "line_rate"), on=RUN + ["order", "t"], how="left")
    return ex.join(rate(land, COUNTRY_WINDOW, None, "country_rate"), on=RUN + ["order", "t"], how="left")


def ids(col, known):
    """Each value's number in `known`, 0 for one never seen in training."""
    return col.replace_strict(known, default=0, return_dtype=pl.Int64).to_numpy()


# old: what the first live model knew; now: how long since its last stop; pace: its own last stops against usual;
# line: the line's last three buses and how slow the line runs; road: every line on the road ahead, and the country;
# gap: how long after the line's bus before it this one passed its last stop, against the timetable's spacing;
# road2: the road ahead from every bus on each stretch in the last half hour, not only the latest
GROUPS = ("old", "now", "pace", "line", "road", "gap", "road2")


def base(df):
    """The history's time from the last stop to j, less the time already spent since it, at least 30 s."""
    return df.with_columns(pl.max_horizontal(pl.coalesce([pl.col("ahead_hist"), pl.col("ahead_sched")]).cast(pl.Float64)
                                             - pl.col("elapsed"), pl.lit(30.0)).alias("base"))


def matrix(df, route_ids, code_ids, groups=GROUPS):
    f = lambda c: df[c].cast(pl.Float64).fill_null(float("nan")).to_numpy()
    b = df["base"].to_numpy()
    cols = []

    def add(v, scale, centre=0):
        miss = np.isnan(v)
        cols.extend([(np.where(miss, 0, v - centre) / scale).astype(np.float32), miss.astype(np.float32)])

    def age(c):
        v = f(c)
        cols.append(np.where(np.isnan(v), 1, np.minimum(v, 7200) / 7200).astype(np.float32))

    for c in ("ahead_hist", "ahead_sched"):
        add(f(c), 600, b)
    add(f("delay"), 600)
    add(f("delay_trend"), 600)
    add(f("ex1"), 600)
    age("age1")
    cols += [b / 3600, df["stops_ahead"].to_numpy() / 40, df["order"].to_numpy() / 50]
    t = (df["start_sec"].to_numpy() + np.nan_to_num(f("took")) + np.nan_to_num(f("elapsed"))) / 86400 * 2 * math.pi
    cols += [np.sin(t), np.cos(t)]
    wd = df["weekday"].to_numpy()
    cols += [(wd == k).astype(np.float64) for k in range(1, 8)]
    cols.append(df["holiday"].cast(pl.Float64).to_numpy())
    if "now" in groups:
        elapsed = np.minimum(f("elapsed"), 3600)
        add(elapsed, 600)
        add(f("next_usual"), 600)
        add(elapsed - f("next_usual"), 600)
    if "pace" in groups:
        for c in ("pace3", "pace6", "pace10"):
            add(f(c), 600)
    if "line" in groups:
        for i in (2, 3):
            add(f("ex%d" % i), 600)
            age("age%d" % i)
        add(np.clip(f("line_rate"), -1, 3), 1)
    if "road" in groups:
        add(f("road_extra"), 600)
        cols.append(np.nan_to_num(f("road_cover")))
        age("road_age")
        add(np.clip(f("country_rate"), -1, 3), 1)
    if "gap" in groups:
        add(np.clip(f("headway"), 0, 7200), 600)
        add(np.clip(f("sched_headway"), -3600, 7200), 600)
        add(np.clip(f("headway") - f("sched_headway"), -3600, 3600), 600)
    if "road2" in groups:
        add(f("road2_extra"), 600)
        cols.append(np.nan_to_num(f("road2_cover")).astype(np.float32))
    x = np.stack([np.asarray(c, dtype=np.float32) for c in cols], 1)
    r, c1, c2 = ids(df["route"], route_ids), ids(df["code"], code_ids), ids(df["code_j"], code_ids)
    return x, r, c1, c2
