"""The bus model's motion inputs, as the lab built them to train it (.cache/planner/ml/motion_live.py: track,
add_motion, motion_matrix; copies, so a change there must be copied here). The lab took each bus's reports from
Hasadna's archive of the ministry's snapshots, one a minute; Kav's server sees them every 15-30 seconds, so the reports
are kept a minute apart (Tracks) before the same speeds and stopped time are worked out from them."""
import numpy as np
import polars as pl

RUN = ["route", "service", "start_sec"]
MOTION = ["report_age", "snapshot_age", "reported_velocity", "speed1", "speed3", "speed5",
          "stationary", "progress_since_stop", "remaining_m", "next_stop_m", "fraction_next",
          "motion_hist_remaining", "motion_speed_remaining"]
SCALES = [300, 300, 100, 20, 20, 20, 300, 1000, 10000, 1000, 1, 3600, 3600]
SPACING = 50  # seconds a kept report comes after the one before: the archive's snapshots came a minute apart
KEEP = 600  # seconds of a journey's reports kept, enough for its speed over five minutes
FORGET = 1800  # a journey not heard from this long has ended its run


class Tracks:
    """Each journey's reports a minute apart, by route and first departure ([seen, pos, velocity, available]), and when
    it last moved (motion_live.track: 15+ m since the report before, or 3+ minutes between them)."""

    def __init__(self):
        # (route, first) -> {"reports": [[seen, pos, velocity, available]] a minute apart, "moved": seen, "latest": the
        # freshest report}
        self.runs = {}

    def feed(self, where, available):
        """where: the places of the journeys on the road (eta.places: route, first, dist, recorded, velocity); available:
        when Kav had them."""
        if where is None:
            return
        velocity = where["velocity"] if "velocity" in where.columns else pl.Series([None] * where.height, dtype=pl.Float64)
        for (route, first, pos, seen), v in zip(where.select("route", "first", "dist", "recorded").iter_rows(), velocity):
            if pos is None or seen is None:
                continue
            run = self.runs.setdefault((route, first), {"reports": [], "moved": seen, "latest": None})
            if run["latest"] is None or seen > run["latest"][0]:
                run["latest"] = [seen, float(pos), v, int(available)]
            reports = run["reports"]
            if reports:
                last_seen, last_pos = reports[-1][0], reports[-1][1]
                if seen - last_seen < SPACING:
                    continue
                if pos - last_pos > 15 or seen - last_seen > 180:
                    run["moved"] = seen
            reports.append([seen, float(pos), v, int(available)])
            while reports and seen - reports[0][0] > KEEP:
                reports.pop(0)
        newest = max((run["reports"][-1][0] for run in self.runs.values() if run["reports"]), default=0)
        for key in [k for k, run in self.runs.items() if not run["reports"] or newest - run["reports"][-1][0] > FORGET]:
            del self.runs[key]

    def state(self):
        """Each journey's speeds and how long it hasn't moved as motion_live.track has them, from its reports a minute
        apart, and where it is by its freshest report (Kav's server has it every 15-30 s, where the archive's minute
        files lagged; on the held-out days the motion network did best on the freshest places): route, first,
        available, seen, pos, reported_velocity, speed1, speed3, speed5, stationary."""
        rows = []
        for (route, first), run in self.runs.items():
            reports = run["reports"]
            if not reports:
                continue
            seen, pos, velocity, available = reports[-1]
            speed1 = None
            if len(reports) > 1:
                dt, dp = seen - reports[-2][0], pos - reports[-2][1]
                if 15 <= dt <= 180 and dp >= 0 and dp / dt <= 45:
                    speed1 = dp / dt
            speeds = []
            for minutes in (3, 5):
                old = [(s, p) for s, p, _, _ in reports if s <= seen - minutes * 60]
                value = None
                if old:
                    s, p = old[-1]
                    dt, dp = seen - s, pos - p
                    if minutes * 60 <= dt <= minutes * 60 + 180 and dp >= 0 and dp / dt <= 45:
                        value = dp / dt
                speeds.append(value)
            stationary = float(min(max(seen - run["moved"], 0), 1800))
            seen, pos, velocity, available = run["latest"] or reports[-1]
            rows.append((route, first, available, seen, pos, velocity, speed1, speeds[0], speeds[1], stationary))
        return pl.DataFrame(rows, schema={"route": pl.Utf8, "first": pl.Int64, "available": pl.Int64, "seen": pl.Int64,
                                          "pos": pl.Float64, "reported_velocity": pl.Float64, "speed1": pl.Float64,
                                          "speed3": pl.Float64, "speed5": pl.Float64, "stationary": pl.Float64}, orient="row")


def add_motion(ex, state, stops):
    """motion_live.add_motion for the questions asked live: ex (eta.predict's examples, with first), state
    (Tracks.state), stops (Day.road's stops: RUN, order, sched, dist). Every motion input, None where it can't be told."""
    ex = ex.with_columns(pl.col("route").cast(pl.Utf8), pl.col("start_sec").cast(pl.Int64))
    ex = ex.join(state, on=["route", "first"], how="left")
    at = stops.select(RUN + ["order", "dist"]).join(ex.select(RUN).unique(), on=RUN, how="semi")  # the runs asked about only
    ex = (ex.join(at.rename({"dist": "stop_m"}), on=RUN + ["order"], how="left")
          .join(at.rename({"order": "order_j", "dist": "target_m"}), on=RUN + ["order_j"], how="left")
          .join(at.with_columns(pl.col("order") - 1).rename({"dist": "next_m"}), on=RUN + ["order"], how="left"))
    valid = ((pl.col("pos") >= pl.col("stop_m") - 150) & (pl.col("pos") <= pl.col("target_m") + 150) & (pl.col("t") - pl.col("seen") <= 300))
    ex = ex.with_columns(
        (pl.col("t") - pl.col("seen")).alias("report_age"),
        (pl.col("t") - pl.col("available")).alias("snapshot_age"),
        pl.when(valid).then(pl.col("pos") - pl.col("stop_m")).alias("progress_since_stop"),
        pl.when(valid).then((pl.col("target_m") - pl.col("pos")).clip(0)).alias("remaining_m"),
        pl.when(valid).then((pl.col("next_m") - pl.col("pos")).clip(0)).alias("next_stop_m"),
        pl.when(valid & (pl.col("next_m") > pl.col("stop_m") + 20))
        .then(((pl.col("pos") - pl.col("stop_m")) / (pl.col("next_m") - pl.col("stop_m"))).clip(0, 1))
        .alias("fraction_next"))
    return ex.with_columns(
        (pl.col("ahead_hist") - pl.col("fraction_next") * pl.col("next_usual")).alias("motion_hist_remaining"),
        (pl.col("remaining_m") / pl.col("speed3").clip(2, 40)).alias("motion_speed_remaining"))


def matrix(df):
    """motion_live.motion_matrix: each input scaled, and whether it's missing."""
    cols = []
    for c, scale in zip(MOTION, SCALES):
        x = df[c].cast(pl.Float64).to_numpy() if c in df.columns else np.full(df.height, np.nan)
        good = np.isfinite(x)
        cols.extend([np.where(good, np.clip(x / scale, -10, 10), 0), (~good).astype(float)])
    return np.column_stack(cols).astype(np.float32)
