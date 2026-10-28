"""The train and light-rail models' inputs, built exactly as the lab built them to train them: copies of its functions
(.cache/planner/ml: nonbus_data.py, gbdt_nonbus.py's matrix), so a change there must be copied here. Asked live,
`inputs` takes the day's plan for the asked stop (see its docstring)."""
import numpy as np
import polars as pl

RUN = ["route", "service", "start_sec"]

HIST = ["h_trip", "h_bucket", "h_weekday", "h_recent"]


NUMERIC = ["timetable", "keep_delay", "history", "ahead_sched", "ahead_hist", "delay", "elapsed",
           "stops_ahead", "order", "weekday", "hour_sin", "hour_cos", "report_age",
           "pace", "pace_span", "previous_runtime", "previous_age", "history_count"]


CATEGORY = ["route", "code", "code_j"]


# Inputs the frozen models never saw: previous_excess from inputs(), the rest added to built examples by extra().
EXTRA = ["previous_excess", "ahead_transit", "ahead_gap", "ahead_delay", "ahead_count", "trend3"]


def inputs(rows, questions, journeys="asked", plan=None):
    """Return only input features and their provenance; no target outcomes read.

    Target static/history columns are allowed; target at/took/known_at are not. The earlier journey between the
    same stations is any line's with journeys="all"; "asked", as the frozen models were built, only finds those of
    lines asked about at the same stop numbers, so the batch of questions decides what it can see. Asked live, the
    asked stop hasn't been reached: `plan` (RUN, order, code, sched and the history medians with their counts, for
    every stop of the day's runs) gives its static fields.
    """
    # Ties in when reports arrived are settled by the furthest stop, so every rebuild picks the same one.
    events = rows.sort("known_at", "order")
    q = questions.sort("t").join_asof(events.select(RUN + ["order", "known_at"]).sort("known_at", "order"),
                                      left_on="t", right_on="known_at", by=RUN, strategy="backward")
    q = q.filter(pl.col("order").is_not_null() & (pl.col("order_j") > pl.col("order")))
    cols = ["code", "took", "at", "sched"] + HIST
    q = q.join(rows.select(RUN + ["order"] + cols), on=RUN + ["order"])
    static = ["code", "sched"] + HIST + ["n_" + h for h in HIST]
    q = q.join((rows if plan is None else plan).select(RUN + [pl.col("order").alias("order_j")] +
                                                       [pl.col(c).alias(c + "_j") for c in static]), on=RUN + ["order_j"])
    # Pace from the previous observed stop, only once both reports are available.
    previous = (rows.sort(RUN + ["order"]).with_columns(
        *[pl.col(c).shift(1).over(RUN).alias("prior_" + c) for c in ["took", "sched", "known_at"]])
        .select(RUN + ["order", "prior_took", "prior_sched", "prior_known_at"]))
    q = q.join(previous, on=RUN + ["order"])
    hist_gap = pl.coalesce([pl.col(h + "_j") - pl.col(h) for h in HIST])
    q = q.with_columns((pl.col("t") - pl.col("at")).alias("elapsed"),
                      (pl.col("took") - pl.col("sched")).alias("delay"),
                      (pl.col("sched_j") - pl.col("sched")).alias("ahead_sched"),
                      hist_gap.alias("ahead_hist"), (pl.col("order_j") - pl.col("order")).alias("stops_ahead"),
                      pl.col("service").dt.weekday().alias("weekday"),
                      (pl.col("t") - pl.col("known_at")).alias("report_age"),
                      pl.coalesce([pl.when(pl.col(h + "_j").is_not_null()).then(pl.col("n_" + h + "_j")) for h in HIST]).alias("history_count"))
    hour = (pl.col("start_sec") + pl.col("took") + pl.col("elapsed")) / 86400 * (2 * np.pi)
    q = q.with_columns(
        (pl.col("ahead_sched") - pl.col("delay") - pl.col("elapsed")).clip(0).alias("timetable"),
        (pl.col("ahead_sched") - pl.col("elapsed")).clip(0).alias("keep_delay"),
        (pl.coalesce([pl.col("ahead_hist"), pl.col("ahead_sched")]) - pl.col("elapsed")).clip(0).alias("history"),
        hour.sin().alias("hour_sin"), hour.cos().alias("hour_cos"),
        pl.when(pl.col("prior_known_at") <= pl.col("t"))
        .then(pl.col("took") - pl.col("prior_took") - (pl.col("sched") - pl.col("prior_sched"))).alias("pace"),
        pl.when(pl.col("prior_known_at") <= pl.col("t"))
        .then(pl.col("sched") - pl.col("prior_sched")).alias("pace_span"))
    # Completed traversals by earlier journeys between the same directed stations.
    a = rows.select(RUN + ["order", "code", "took", "sched", "known_at"])
    b = rows.select(RUN + [pl.col(c).alias(c + "_j") for c in ["order", "code", "took", "sched", "known_at"]])
    if journeys == "all":
        stations = q.select("code", "code_j").unique()
        done = (a.join(stations.select("code").unique(), on="code")
                .join(b.join(stations.select("code_j").unique(), on="code_j"), on=RUN)
                .filter(pl.col("order_j") > pl.col("order")).join(stations, on=["code", "code_j"]))
    else:
        pairs = q.select("route", "order", "order_j").unique()
        done = pairs.join(a, on=["route", "order"]).join(b, on=RUN + ["order_j"])
    # Journeys that became known in the same snapshot: the one that reached the far station last counts.
    # previous_excess: how much longer than its own timetable it took, comparable across lines that stop differently.
    done = (done.with_columns((pl.col("took_j") - pl.col("took")).alias("previous_runtime"),
                              (pl.col("took_j") - pl.col("took") - (pl.col("sched_j") - pl.col("sched"))).alias("previous_excess"),
                              pl.max_horizontal("known_at", "known_at_j").alias("previous_known"),
                              (pl.col("start_sec") + pl.col("took_j")).alias("reached_j"))
            .filter(pl.col("previous_runtime") >= 0)
            .select("code", "code_j", "previous_runtime", "previous_excess", "previous_known", "reached_j")
            .sort("previous_known", "reached_j", "previous_runtime", "previous_excess"))
    q = q.sort("t").join_asof(done, left_on="t", right_on="previous_known", by=["code", "code_j"], strategy="backward")
    q = q.with_columns((pl.col("t") - pl.col("previous_known")).alias("previous_age"))
    q = q.with_columns(*[pl.when(pl.col("previous_age") <= 3600).then(pl.col(c)).alias(c) for c in ("previous_runtime", "previous_excess")])
    return q.select(RUN + ["t", "order_j", "known_at", "prior_known_at", "previous_known"] + CATEGORY[1:] + NUMERIC + ["previous_excess"])


def extra(rows, ex, schedule):
    """EXTRA inputs for examples built from the same rows: the journeys out on the asked run's stretch at t (left its
    last reported station, timetabled to call at the asked one, not reported there or beyond yet), the nearest of them (how
    long it has been out, how far ahead of this run it left, how late it was), and how much this run's delay changed
    over its last three stops. Only reports known by t and the day's timetable."""
    s = schedule.select("route", pl.col("start_sec").cast(rows.schema["start_sec"]), pl.col("order").cast(rows.schema["order"]),
                        pl.col("code").cast(rows.schema["code"]))
    # The stretch is named by the timetable's stations, so stops the feed spaced in without a code still count.
    mine = (ex.join(s.rename({"code": "from_code"}), on=["route", "start_sec", "order"], how="left")
            .join(s.rename({"order": "order_j", "code": "to_code"}).with_columns(pl.col("order_j").cast(ex.schema["order_j"])),
                  on=["route", "start_sec", "order_j"], how="left")
            .with_columns((pl.col("t") - pl.col("elapsed")).alias("left_at")))
    pairs = mine.select("from_code", "to_code").drop_nulls().unique()
    legs = (s.rename({"code": "from_code"})
            .join(s.rename({"order": "order_b", "code": "to_code"}), on=["route", "start_sec"])
            .filter(pl.col("order_b") > pl.col("order")).join(pairs, on=["from_code", "to_code"]))
    reports = rows.select(RUN + ["order", "at", "known_at", (pl.col("took") - pl.col("sched")).alias("late")])
    # A journey can pass the asked station between two reports without the feed naming it: any report at or beyond
    # it counts, from the first one known.
    passed = (rows.sort(RUN + ["order"]).with_columns(pl.col("known_at").cum_min(reverse=True).over(RUN).alias("there_known"))
              .select(RUN + [pl.col("order").alias("order_b"), "there_known"]).sort("order_b"))
    out = (legs.join(reports, on=["route", "start_sec", "order"]).sort("order_b")
           .join_asof(passed, on="order_b", by=RUN, strategy="forward")
           .select("from_code", "to_code", pl.col("route").alias("r_route"), pl.col("start_sec").alias("r_start"),
                   pl.col("at").alias("r_left"), pl.col("known_at").alias("r_known"), "there_known", pl.col("late").alias("r_late")))
    keys = RUN + ["t", "order_j"]
    near = (mine.select(keys + ["from_code", "to_code", "left_at"]).join(out, on=["from_code", "to_code"])
            .filter(((pl.col("r_route") != pl.col("route")) | (pl.col("r_start") != pl.col("start_sec")))
                    & (pl.col("r_known") <= pl.col("t")) & (pl.col("r_left") <= pl.col("left_at"))
                    & (pl.col("there_known").is_null() | (pl.col("there_known") > pl.col("t"))))
            .sort(keys + ["r_left", "r_route", "r_start"])
            .group_by(keys, maintain_order=True)
            .agg(pl.len().alias("ahead_count"), (pl.col("t") - pl.col("r_left")).last().alias("ahead_transit"),
                 (pl.col("left_at") - pl.col("r_left")).last().alias("ahead_gap"), pl.col("r_late").last().alias("ahead_delay")))
    back = (rows.sort(RUN + ["order"]).with_columns(
                (pl.col("took") - pl.col("sched")).shift(3).over(RUN).alias("late3"), pl.col("known_at").shift(3).over(RUN).alias("known3"))
            .select(RUN + ["order", "late3", "known3"]))
    return (ex.join(near, on=keys, how="left").join(back, on=RUN + ["order"], how="left")
            .with_columns(pl.col("ahead_count").fill_null(0),
                          pl.when(pl.col("known3") <= pl.col("t")).then(pl.col("delay") - pl.col("late3")).alias("trend3"))
            .drop("late3", "known3"))


def matrix(df, categories, features=None):
    x = df.select(features or NUMERIC).cast(pl.Float64).to_numpy()
    cats = [df[c].replace_strict(categories[c], default=0, return_dtype=pl.Int64).to_numpy() for c in CATEGORY]
    return np.column_stack([x] + cats)
