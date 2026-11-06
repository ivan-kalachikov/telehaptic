"""Analysis of capsule recordings (two magnetometers + IMU).

    python analysis/analyze.py data

Reads every *.zip in the folder (subfolders too, skips *_ABORTED), writes report/report.md
with figures. What is checked:

  - stream quality: rate, losses, saturation, clock sync;
  - recognition of the 12 keys with feature sets: sensor 1, sensor 2, both, difference;
    honest checks: within a recording (the test block is never in training), transfer to
    re-wearing (KEYS_table -> KEYS_table_rewear), transfer to another posture (table -> down);
  - calibration (CAL): each sensor calibrated, the two aligned; how much Earth field is left in
    the difference while the hand turns, compared with the distance between neighbouring keys;
  - recording without the magnet (NOMAG): recognition must fall to chance;
  - vibration motor: field at rest with and without a pulse;
  - accelerometer: is there a jolt at the moment of touch.

Numbers come only from the recordings. Where something cannot be computed, the report says so.
"""
import json
import math
import sys
import zipfile
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.discriminant_analysis import LinearDiscriminantAnalysis
from sklearn.linear_model import LogisticRegression
from sklearn.neighbors import KNeighborsClassifier
from sklearn.neural_network import MLPClassifier
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"]
POS = {k: divmod(i, 3) for i, k in enumerate(KEYS)}       # key -> (finger row, phalanx column)
TICKS = 32768.0

# Windows as on the phone stand (test-telehaptic/analyze.py), so results compare.
HOLD_FROM, HOLD_BEFORE_END = 0.8, 0.3
REST_FROM, REST_BEFORE_END = 0.8, 0.15

STAND = "phone stand (recording P1_r1): within a recording, LDA and kNN on the field at the touch 73–75%, 95% interval ≈ 59–85%"

FEATURES = {
    "s1": "sensor 1 (3 numbers)",
    "s2": "sensor 2 (3 numbers)",
    "both": "both sensors (6 numbers)",
    "diff": "difference 1 − 2 (3 numbers)",
}
MODELS = {
    "LDA": lambda: make_pipeline(StandardScaler(), LinearDiscriminantAnalysis(solver="lsqr", shrinkage="auto")),
    "kNN5": lambda: make_pipeline(StandardScaler(), KNeighborsClassifier(n_neighbors=5)),
    "LogReg": lambda: make_pipeline(StandardScaler(), LogisticRegression(max_iter=5000)),
    "MLP": lambda: make_pipeline(StandardScaler(), MLPClassifier(hidden_layer_sizes=(32,), alpha=1e-2, max_iter=4000, random_state=0)),
}
PRIMARY = ("LDA", "both")

INK, MUTED, GRID, BLUE, ORANGE = "#16181d", "#5b6170", "#dfe2e8", "#2c56c9", "#e08a1e"
plt.rcParams.update({"figure.dpi": 110, "savefig.bbox": "tight", "axes.edgecolor": GRID, "axes.labelcolor": MUTED,
                     "xtick.color": MUTED, "ytick.color": MUTED, "font.size": 9, "axes.spines.top": False,
                     "axes.spines.right": False})


# ---------------------------------------------------------------- loading

def refit_clock(packets):
    """Phone time (ns) of capsule ticks: line through per-second minima of (receive - capsule time)."""
    if packets is None or len(packets) < 10:
        return None
    t = packets["last_ticks"].to_numpy(dtype=float) / TICKS
    off = packets["t_rx_ns"].to_numpy(dtype=float) - t * 1e9
    sec = np.floor(t)
    df = pd.DataFrame({"sec": sec, "t": t, "off": off})
    mins = df.loc[df.groupby("sec")["off"].idxmin()]
    if len(mins) < 3:
        return None
    b, a = np.polyfit(mins["t"], mins["off"], 1)
    resid = mins["off"] - (a + b * mins["t"])
    return {"a": a, "b": b, "resid_ms": float(np.std(resid) / 1e6), "n_bins": len(mins)}


def load(path):
    with zipfile.ZipFile(path) as z:
        meta = json.loads(z.read("meta.json").decode("utf-8"))
        s = pd.read_csv(z.open("samples.csv"))
        ev = pd.read_csv(z.open("events.csv"), dtype={"key": str}, keep_default_na=False)
        names = z.namelist()
        packets = pd.read_csv(z.open("packets.csv")) if "packets.csv" in names else None
        sync = pd.read_csv(z.open("sync.csv")) if "sync.csv" in names else None
    t0 = meta["start_elapsed_ns"]
    mags = meta["capsule_at_start"]["magnetometers"]
    n = len(mags)
    if n == 0:
        raise ValueError("no magnetometers in this recording (the capsule found none)")
    s = s.dropna(subset=["idx", "t_xiao", "t_ns"]).reset_index(drop=True)   # a line cut off by a killed app
    # The capsule restarted during the recording (its sample index went back): its clock started
    # again too, so one line through the packets would mix two clocks. The live phone times are used then.
    restarts = int(np.sum(np.diff(s["idx"].to_numpy(dtype=np.int64)) < 0))
    clock = refit_clock(packets) if restarts == 0 else None
    if clock is not None:
        ts = s["t_xiao"].to_numpy(dtype=float) / TICKS
        t_ns = ts * 1e9 + clock["a"] + clock["b"] * ts
        clock["live_vs_refit_ms"] = float(np.median(np.abs(s["t_ns"].to_numpy(dtype=float) - t_ns)) / 1e6)
    else:
        t_ns = s["t_ns"].to_numpy(dtype=float)
    order = np.argsort(t_ns, kind="stable")
    if np.any(np.diff(order) < 0):
        s = s.iloc[order].reset_index(drop=True); t_ns = t_ns[order]
    rec = {"path": path, "meta": meta, "id": meta.get("id") or Path(path).stem, "kind": meta.get("kind"), "restarts": restarts,
           "posture": meta.get("posture"), "rewear": bool(meta.get("rewear")), "n_mags": n,
           "t": (t_ns - t0) / 1e9, "idx": s["idx"].to_numpy(), "flags": s["flags"].to_numpy(dtype=int),
           "clock": clock, "packets": packets, "sync": sync, "synthetic": bool(meta.get("synthetic"))}
    for i, m in enumerate(mags):
        k = 100.0 / float(m["lsb_per_gauss"])     # raw -> uT
        rec[f"m{i + 1}"] = s[[f"m{i + 1}x", f"m{i + 1}y", f"m{i + 1}z"]].to_numpy(dtype=float) * k
    sc = meta.get("imu_scale", {"acc_mg_per_lsb": 0.244, "gyr_mdps_per_lsb": 17.5})
    rec["acc"] = s[["ax", "ay", "az"]].to_numpy(dtype=float) * sc["acc_mg_per_lsb"] / 1000.0 * 9.80665   # m/s2
    rec["gyr"] = s[["gx", "gy", "gz"]].to_numpy(dtype=float) * sc["gyr_mdps_per_lsb"] / 1000.0            # deg/s
    rec["imu"] = bool(meta.get("capsule_at_start", {}).get("imu", True)) and bool(np.any(rec["acc"] != 0))
    e = ev.copy()
    e["t"] = (e["t_ns"] - t0) / 1e9
    rec["events"] = e
    rec["bad"] = bad_intervals(e, rec["t"][-1] if len(rec["t"]) else 0.0)
    return rec


def bad_intervals(ev, t_end):
    """Times without trustworthy data: capsule lost, data stalled, paused by the person or the app."""
    pairs = (("link_down", "link_up"), ("data_stalled", "data_back"), ("pause", "resume"))
    out = []
    for a, b in pairs:
        starts = ev.loc[ev["event"] == a, "t"].to_numpy()
        ends = ev.loc[ev["event"] == b, "t"].to_numpy()
        for t0 in starts:
            later = ends[ends > t0]
            out.append((float(t0), float(later[0]) if len(later) else float(t_end)))
    return out


def overlaps_bad(rec, a, b):
    return any(x < b and y > a for x, y in rec.get("bad", []))


def window(rec, a, b):
    i, j = np.searchsorted(rec["t"], [a, b])
    return slice(i, j)


# ---------------------------------------------------------------- stream quality

def quality(rec):
    idx = rec["idx"].astype(np.int64)
    gaps = np.diff(idx)
    lost = int(np.sum(gaps[gaps > 1] - 1))
    dur = rec["t"][-1] - rec["t"][0] if len(rec["t"]) > 1 else 0
    f = rec["flags"]
    sat = {f"m{i + 1}": int(np.sum((f >> (3 * i)) & 7 != 0)) for i in range(rec["n_mags"])}
    rtt = np.nan
    if rec["sync"] is not None and len(rec["sync"]):
        rtt = float(np.median(rec["sync"]["t_recv_ns"] - rec["sync"]["t_send_ns"]) / 1e6)
    c = rec["clock"] or {}
    return {"recording": rec["id"], "samples": len(idx), "rate, Hz": len(idx) / dur if dur > 0 else np.nan,
            "lost, %": 100 * lost / max(1, lost + len(idx)), "saturated (samples)": ", ".join(f"{k}: {v}" for k, v in sat.items()),
            "no new data, %": 100 * np.mean(f & (1 << 11) != 0), "I2C errors": int(np.sum(f & (1 << 13) != 0)),
            "RTT median, ms": rtt, "clock: spread, ms": c.get("resid_ms", np.nan),
            "clock: live vs refit, ms": c.get("live_vs_refit_ms", np.nan)}


# ---------------------------------------------------------------- trials

def trials(rec):
    meta, ev = rec["meta"], rec["events"]
    rest_s = meta["settings"].get("rest_s", 2.0)
    press = ev[ev["event"] == "press"].set_index("trial")
    release = ev[ev["event"] == "release"].set_index("trial")
    rows = []
    rec["dropped"] = 0
    for tr, p in press.iterrows():
        if tr not in release.index:
            continue
        tp, tr_ = p["t"], release.loc[tr, "t"]
        if overlaps_bad(rec, tp - rest_s, tr_):
            rec["dropped"] += 1     # the capsule was lost, data stalled or the recording paused around this trial
            continue
        h = window(rec, tp + HOLD_FROM, tr_ - HOLD_BEFORE_END)
        r = window(rec, tp - rest_s + REST_FROM, tp - REST_BEFORE_END)
        if h.stop - h.start < 3 or r.stop - r.start < 3:
            continue
        row = {"rec": rec["id"], "kind": rec["kind"], "posture": rec["posture"], "rewear": rec["rewear"],
               "trial": int(tr), "block": int(tr) // 12, "key": p["key"], "t": tp}
        for i in range(rec["n_mags"]):
            m = rec[f"m{i + 1}"]
            for a, c in enumerate("xyz"):
                row[f"h{i + 1}{c}"] = float(np.median(m[h, a]))
                row[f"r{i + 1}{c}"] = float(np.median(m[r, a]))
                row[f"sd{i + 1}{c}"] = float(np.std(m[h, a]))
        g = np.median(rec["acc"][h], axis=0)
        for a, c in enumerate("xyz"):
            row[f"g{c}"] = float(g[a])
        rows.append(row)
    return pd.DataFrame(rows)


def feats(df, name):
    h1 = df[["h1x", "h1y", "h1z"]].to_numpy()
    if name == "s1":
        return h1
    if "h2x" not in df:
        return None
    h2 = df[["h2x", "h2y", "h2z"]].to_numpy()
    if name == "s2":
        return h2
    if name == "both":
        return np.hstack([h1, h2])
    if name == "diff":
        return h1 - h2
    return None


def wilson(k, n, z=1.96):
    if n == 0:
        return (np.nan, np.nan)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    w = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (c - w, c + w)


def evaluate(train, test, feat, model):
    Xtr, Xte = feats(train, feat), feats(test, feat)
    if Xtr is None or len(train) < 13 or len(test) == 0 or train["key"].nunique() < 2:
        return None
    m = MODELS[model]()
    m.fit(Xtr, train["key"])
    return m.predict(Xte)


def scheme_within(df, feat, model):
    """Each block of each recording in turn is the test, the other blocks of that recording train."""
    yt, yp = [], []
    for rec, d in df.groupby("rec"):
        for b in sorted(d["block"].unique()):
            tr, te = d[d["block"] != b], d[d["block"] == b]
            p = evaluate(tr, te, feat, model)
            if p is not None:
                yt += list(te["key"]); yp += list(p)
    return yt, yp


def scheme_transfer(train, test, feat, model):
    p = evaluate(train, test, feat, model)
    return (list(test["key"]), list(p)) if p is not None else ([], [])


def acc_row(name, yt, yp):
    n = len(yt)
    k = int(sum(a == b for a, b in zip(yt, yp)))
    lo, hi = wilson(k, n)
    return {"check": name, "correct": k, "total": n, "accuracy, %": 100 * k / n if n else np.nan,
            "95% interval": f"{100 * lo:.0f}–{100 * hi:.0f}%" if n else "—"}


def neighbour_distances(df, feat):
    """Distance between centres of keys next to each other on the keypad (same finger or same phalanx)."""
    X = feats(df, feat)
    if X is None:
        return None
    cen = {k: X[df["key"].to_numpy() == k].mean(axis=0) for k in KEYS if (df["key"] == k).any()}
    out = []
    for a in cen:
        for b in cen:
            if a < b:
                (ra, ca), (rb, cb) = POS[a], POS[b]
                if abs(ra - rb) + abs(ca - cb) == 1:
                    out.append(np.linalg.norm(cen[a] - cen[b]))
    return np.array(out)


def block_drift(df, feat):
    """How far a key's centre moves from block to block (median over keys)."""
    X = feats(df, feat)
    if X is None:
        return np.nan
    d = []
    for k in KEYS:
        sel = df["key"].to_numpy() == k
        if sel.sum() < 2:
            continue
        cents = [X[sel & (df["block"].to_numpy() == b)].mean(axis=0) for b in sorted(df.loc[sel, "block"].unique())]
        cents = [c for c in cents if np.all(np.isfinite(c))]
        if len(cents) > 1:
            d.append(np.mean([np.linalg.norm(c - np.mean(cents, axis=0)) for c in cents]))
    return float(np.median(d)) if d else np.nan


# ---------------------------------------------------------------- calibration

def ellipsoid_fit(p):
    """General quadric through points; returns (centre, A) with |A (p - c)| ~ 1."""
    x, y, z = p[:, 0], p[:, 1], p[:, 2]
    D = np.column_stack([x * x, y * y, z * z, 2 * x * y, 2 * x * z, 2 * y * z, 2 * x, 2 * y, 2 * z])
    v, *_ = np.linalg.lstsq(D, np.ones(len(p)), rcond=None)
    Q = np.array([[v[0], v[3], v[4]], [v[3], v[1], v[5]], [v[4], v[5], v[2]]])
    c = -np.linalg.solve(Q, v[6:9])
    k = 1 + c @ Q @ c
    M = Q / k
    w, V = np.linalg.eigh(M)
    if np.any(w <= 0):
        return None
    A = V @ np.diag(np.sqrt(w)) @ V.T
    return c, A


def sphere_fit(p):
    A = np.hstack([2 * p, np.ones((len(p), 1))])
    b = np.sum(p * p, axis=1)
    sol, *_ = np.linalg.lstsq(A, b, rcond=None)
    c = sol[:3]
    return c, math.sqrt(max(sol[3] + c @ c, 0))


def kabsch(P, Q):
    """Rotation R minimising |P - Q R^T| (rows are vectors)."""
    Pc, Qc = P - P.mean(0), Q - Q.mean(0)
    U, _, Vt = np.linalg.svd(Qc.T @ Pc)
    d = np.sign(np.linalg.det(U @ Vt))
    D = np.diag([1, 1, d])
    return (U @ D @ Vt).T


def calibration(rec, figdir):
    ev = rec["events"]
    a = ev.loc[ev["event"] == "calib_start", "t"]
    b = ev.loc[ev["event"] == "calib_end", "t"]
    if a.empty or b.empty or rec["n_mags"] < 1:
        return None
    w = window(rec, float(a.iloc[0]), float(b.iloc[0]))
    motor = (rec["flags"][w] & (1 << 9)) != 0
    P = [rec[f"m{i + 1}"][w][~motor] for i in range(rec["n_mags"])]
    n = len(P[0])
    if n < 200:
        return None
    half = n // 2
    out = {"rec": rec["id"], "n": n}
    cal = []
    # One field magnitude for both sensors: separate scales would leave a gain mismatch that
    # shows up in the difference as Earth field.
    common_radius = float(np.mean([sphere_fit(p[:half])[1] for p in P]))
    for i, p in enumerate(P):
        fit = ellipsoid_fit(p[:half])
        if fit is None:
            c, r = sphere_fit(p[:half])
            fit = (c, np.eye(3) / r)
        c, A = fit                                   # (p - c) A^T lies on a unit sphere
        _, radius = sphere_fit(p[:half])             # this sensor's own estimate, reported
        cp = (p - c) @ A.T * common_radius
        mod = np.linalg.norm(cp[half:], axis=1)
        out[f"m{i + 1}_offset"] = c
        out[f"m{i + 1}_radius"] = radius
        out[f"m{i + 1}_mod_sd"] = float(np.std(mod))
        out[f"m{i + 1}_span"] = float(np.ptp(np.linalg.norm(p - np.median(p, axis=0), axis=1)))
        cal.append(cp)
        # single sensor: how much the field changes with the hand turning (test half)
        dev = np.linalg.norm(p[half:] - np.median(p[half:], axis=0), axis=1)
        out[f"m{i + 1}_turn_p95"] = float(np.percentile(dev, 95))
    if rec["n_mags"] >= 2:
        raw_d = P[0] - P[1]
        dev_raw = np.linalg.norm(raw_d[half:] - np.median(raw_d[half:], axis=0), axis=1)
        R = kabsch(cal[0][:half], cal[1][:half])
        d = cal[0] - cal[1] @ R.T
        dev = np.linalg.norm(d[half:] - np.median(d[half:], axis=0), axis=1)
        out.update({"raw_diff_rms": float(np.sqrt(np.mean(dev_raw ** 2))), "raw_diff_p95": float(np.percentile(dev_raw, 95)),
                    "cal_diff_rms": float(np.sqrt(np.mean(dev ** 2))), "cal_diff_p95": float(np.percentile(dev, 95)),
                    "rot_deg": float(np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1))))})
        fig, ax = plt.subplots(figsize=(7, 2.6))
        tt = rec["t"][w][~motor]
        ax.plot(tt, np.linalg.norm(P[0] - np.median(P[0], 0), axis=1), color=MUTED, lw=0.7, label="sensor 1, deviation")
        ax.plot(tt, np.linalg.norm(raw_d - np.median(raw_d, 0), axis=1), color=ORANGE, lw=0.7, label="difference, uncalibrated")
        ax.plot(tt, np.linalg.norm(d - np.median(d, 0), axis=1), color=BLUE, lw=0.9, label="difference, calibrated")
        ax.axvline(tt[half], color=GRID, lw=1)
        ax.set_xlabel("time, s"); ax.set_ylabel("µT"); ax.legend(frameon=False, fontsize=8)
        ax.set_title(f"{rec['id']}: hand turning without the magnet (left of the line: fit, right: check)", fontsize=9)
        fig.savefig(figdir / f"cal_{rec['id']}.png"); plt.close(fig)
    return out


def motor_effect(rec):
    """Still part of the calibration: field with the motor on vs off."""
    ev = rec["events"]
    a = ev.loc[ev["event"] == "still_start", "t"]
    b = ev.loc[ev["event"] == "calib_start", "t"]
    if a.empty or b.empty:
        return None
    w = window(rec, float(a.iloc[0]) + 0.3, float(b.iloc[0]))
    on = (rec["flags"][w] & (1 << 9)) != 0
    t = rec["t"][w]
    if on.sum() < 3:
        return {"rec": rec["id"], "note": "no samples with the motor flag in the still part of the calibration"}
    # "off" = still samples away from pulses by more than 0.5 s
    on_t = t[on]
    far = np.array([np.min(np.abs(on_t - x)) > 0.5 for x in t])
    rows = []
    for i in range(rec["n_mags"]):
        m = rec[f"m{i + 1}"][w]
        base = np.median(m[far], axis=0)
        sd_off = np.std(m[far], axis=0)
        shift = np.linalg.norm(np.median(m[on], axis=0) - base)
        # recovery: after each pulse end, time until within 3 sd of rest
        rec_times = []
        ends = np.where(np.diff(on.astype(int)) == -1)[0]
        for e in ends:
            dev = np.linalg.norm((m[e + 1:] - base) / np.maximum(sd_off, 0.05), axis=1) / math.sqrt(3)
            k = np.argmax(dev < 3) if (dev < 3).any() else None
            if k is not None:
                rec_times.append(t[e + 1 + k] - t[e])
        rows.append({"sensor": i + 1, "field shift during a pulse, µT": shift,
                     "noise at rest, µT (SD)": float(np.linalg.norm(sd_off)),
                     "noise during a pulse, µT (SD)": float(np.linalg.norm(np.std(m[on], axis=0))),
                     "recovery after a pulse, s (median)": float(np.median(rec_times)) if rec_times else np.nan,
                     "pulses": len(ends)})
    # The same for the IMU: the motor shakes the capsule, which the accelerometer and gyroscope feel.
    acc = hp_accel(rec, mask_motor=False)[w]
    gyr = np.linalg.norm(rec["gyr"][w] - np.median(rec["gyr"][w][far], axis=0), axis=1)
    imu = []
    for name, v, unit in ((("accelerometer, high-passed", acc, "m/s²"), ("gyroscope", gyr, "°/s")) if rec["imu"] else ()):
        imu.append({"sensor": name, "unit": unit, "RMS at rest": float(np.sqrt(np.mean(v[far] ** 2))),
                    "RMS during a pulse": float(np.sqrt(np.mean(v[on] ** 2)))})
    return {"rec": rec["id"], "rows": rows, "imu": imu}


# ---------------------------------------------------------------- accelerometer at the touch

def accel_at_touch(rec):
    """High-passed acceleration peak at the touch onset (from the magnet) vs during the hold and at rest."""
    if rec["n_mags"] < 1 or not rec["imu"] or len(rec["t"]) < 10:
        return []
    ev = rec["events"]
    rest_s = rec["meta"]["settings"].get("rest_s", 2.0)
    t = rec["t"]
    e = hp_accel(rec)
    sig = rec["m1"] - rec["m2"] if rec["n_mags"] >= 2 else rec["m1"]
    press = ev[ev["event"] == "press"].set_index("trial")
    release = ev[ev["event"] == "release"].set_index("trial")
    rows = []
    for tr, p in press.iterrows():
        if tr not in release.index:
            continue
        tp, trl = p["t"], release.loc[tr, "t"]
        if overlaps_bad(rec, tp - rest_s, trl):
            continue
        rw, hw = window(rec, tp - rest_s + REST_FROM, tp - REST_BEFORE_END), window(rec, tp + HOLD_FROM, trl - HOLD_BEFORE_END)
        if rw.stop - rw.start < 3 or hw.stop - hw.start < 3:
            continue
        rest, hold = np.median(sig[rw], 0), np.median(sig[hw], 0)
        d = np.linalg.norm(hold - rest)
        ww = window(rec, tp - 0.2, tp + 1.5)
        dd = np.linalg.norm(sig[ww] - rest, axis=1)
        if d < 5 or not (dd > 0.5 * d).any():
            continue
        on = t[ww][np.argmax(dd > 0.5 * d)]
        ow = window(rec, on - 0.15, on + 0.1)
        n = max(1, ow.stop - ow.start)
        if not np.isfinite(e[ow]).any():
            continue   # the motor was on at the touch
        rows.append({"key": p["key"], "on": float(np.nanmax(e[ow])), "hold": peak_median(e, hw, n), "rest": peak_median(e, rw, n)})
    return rows


def peak_median(e, sl, n):
    """Median over consecutive n-sample chunks of the chunk maximum: comparable with a single
    n-sample window, whatever the length of the slice."""
    v = e[sl]
    if len(v) < n:
        return float(np.nanmax(v)) if np.isfinite(v).any() else np.nan
    k = len(v) // n
    with np.errstate(all="ignore"):
        chunks = v[:k * n].reshape(k, n)
        ok = np.isfinite(chunks).any(axis=1)
        return float(np.median(np.nanmax(chunks[ok], axis=1))) if ok.any() else np.nan


# ---------------------------------------------------------------- training: quick taps

def hp_accel(rec, mask_motor=True):
    """High-passed acceleration magnitude. Samples with the motor on (and 0.1 s after it) are set
    to NaN: the motor's own vibration would otherwise pass for the jolt of a touch."""
    t, A = rec["t"], rec["acc"]
    if not rec.get("imu", True) or len(t) < 10:
        return np.full(len(t), np.nan)
    k = max(3, int(round(0.04 * sample_rate(t))))
    hp = A - np.column_stack([np.convolve(A[:, i], np.ones(k) / k, "same") for i in range(3)])
    e = np.linalg.norm(hp, axis=1)
    if mask_motor:
        m = motor_mask(rec)
        if m.any():
            e = e.copy()
            e[m] = np.nan
    return e


def sample_rate(t):
    """From the median sample spacing: robust to gaps and to a capsule restart."""
    d = np.diff(t)
    d = d[d > 0]
    return 1.0 / float(np.median(d)) if len(d) else 100.0


def motor_mask(rec, tail_s=0.1):
    """Samples with the motor on and tail_s after it (the motor rings on; the app's detector waits 0.1 s too)."""
    motor = (rec["flags"] & (1 << 9)) != 0
    if not motor.any():
        return motor
    tail = max(1, int(round(tail_s * sample_rate(rec["t"]))))
    return np.convolve(motor.astype(float), np.ones(tail + 1), "full")[:len(motor)] > 0


def training_taps(rec):
    """Quick taps of a TRAIN / RETRAIN recording.

    Per tap: amplitude (plateau vs rest, sensor difference), rise time (from half way to the
    plateau start the detector reported), plateau length (until the field leaves the plateau),
    reaction (cue to plateau), accelerometer peak around the contact.
    """
    ev = rec["events"]
    t = rec["t"]
    y = rec["m1"] - rec["m2"] if rec["n_mags"] >= 2 else rec["m1"]
    rate = sample_rate(t)
    acc = hp_accel(rec)
    quiet = motor_mask(rec)   # the click on each found tap shifts the field: not part of the plateau
    # rest: before each hold press, after the previous release
    presses = ev[ev["event"] == "press"]
    det_t = ev.loc[ev["event"] == "detector", "t"]
    hold_end = float(det_t.iloc[0]) if len(det_t) else np.inf
    # "Redo last" during the holds takes back a stored hold: the repeat event carries that hold's
    # trial number. Holds paused or interrupted by a lost capsule were not stored by the app either.
    taken = set(ev.loc[(ev["event"] == "repeat") & (ev["t"] < hold_end), "trial"])
    presses = presses[~presses["trial"].isin(taken)]
    press = presses.set_index("trial")
    release = ev[ev["event"] == "release"].set_index("trial")
    rests, hold_acc, holds = [], [], []
    for tr, p in press.iterrows():
        rw = window(rec, p["t"] - 1.2, p["t"] - 0.15)
        if rw.stop - rw.start > 3:
            rests.append(np.median(y[rw], axis=0))
        if tr in release.index and not overlaps_bad(rec, p["t"], release.loc[tr, "t"]):
            hw = window(rec, p["t"] + 0.5, release.loc[tr, "t"] - 0.2)
            if hw.stop - hw.start > 3:
                hold_acc.append((acc, hw))
                row = {"key": p["key"]}
                for i in range(rec["n_mags"]):
                    for a, c in enumerate("xyz"):
                        row[f"h{i + 1}{c}"] = float(np.median(rec[f"m{i + 1}"][hw, a]))
                holds.append(row)
    if not rests:
        rw = window(rec, t[0], t[0] + 2.0)
        rests.append(np.median(y[rw], axis=0))
    rest = np.median(np.array(rests), axis=0)
    det = ev[ev["event"] == "detector"]
    on_ut = np.nan
    if len(det):
        for part in str(det["key"].iloc[-1]).split(";"):
            if part.startswith("on_ut="):
                on_ut = float(part[6:])
    plat = ev[ev["event"] == "tap_plateau"]
    if plat.empty:
        plat = ev[ev["event"] == "tap"]
    # "Redo last" takes a tap back and asks for it again with the same trial number: keep the last one.
    plat = plat.drop_duplicates("trial", keep="last")
    cues = ev[ev["event"] == "tap_cue"].drop_duplicates("trial", keep="last").set_index("trial")
    n_win = max(4, int(0.08 * rate))
    # A re-fit on a few keys cycles through those keys: a block is one round of them.
    n_keys = max(1, ev.loc[ev["event"] == "tap_cue", "key"].nunique())
    rows, taps = [], []
    for _, e in plat.iterrows():
        i0 = int(np.searchsorted(t, e["t"]))
        if i0 + n_win >= len(t) or i0 < int(0.6 * rate):
            continue
        p0 = np.median(y[i0:i0 + n_win], axis=0)
        amp = float(np.linalg.norm(p0 - rest))
        tol = max(2.0, 0.3 * amp)
        # plateau end: first of 3 consecutive samples farther than tol from the plateau value
        j = i0 + n_win
        far = 0
        while j < min(len(t), i0 + int(2.0 * rate)):
            if quiet[j]:
                j += 1
                continue
            far = far + 1 if np.linalg.norm(y[j] - p0) > tol else 0
            if far >= 3:
                j -= 2
                break
            j += 1
        j = min(j, len(t) - 1)   # the recording may end while the thumb is still down
        plateau = t[j] - t[i0]
        # rise: back from the plateau start to where the field was half way from rest
        k = i0
        while k > i0 - int(0.6 * rate) and np.linalg.norm(y[k] - rest) > 0.5 * amp:
            k -= 1
        rise = t[i0] - t[k]
        contact = window(rec, t[k] - 0.05, t[i0] + 0.05)
        n_contact = max(1, contact.stop - contact.start)
        cue = cues.loc[e["trial"], "t"] if e["trial"] in cues.index else np.nan
        rows.append({"amp": amp, "rise": rise, "plateau": plateau, "reaction": e["t"] - cue,
                     "acc_contact": float(np.nanmax(acc[contact])) if np.isfinite(acc[contact]).any() else np.nan,
                     "n_contact": n_contact})
        trow = {"key": e["key"], "block": int(e["trial"]) // n_keys}
        for i in range(rec["n_mags"]):
            for a, c in enumerate("xyz"):
                trow[f"h{i + 1}{c}"] = float(np.median(rec[f"m{i + 1}"][i0:i0 + n_win, a]))
        taps.append(trow)
    d = pd.DataFrame(rows)
    n = int(np.median(d["n_contact"])) if len(d) else 1
    hold_peaks = np.array([peak_median(a, hw, n) for a, hw in hold_acc])   # same window length as at contact
    cued = ev[ev["event"] == "tap_cue"]["trial"].nunique()
    found = plat["trial"].nunique()
    return {"rows": d, "holds": pd.DataFrame(holds), "taps": pd.DataFrame(taps),
            "hold_acc": hold_peaks, "on_ut": on_ut, "cued": cued, "found": found, "n_keys": n_keys}


# ---------------------------------------------------------------- figures

def plot_confusion(yt, yp, title, path):
    m = np.zeros((12, 12), int)
    for a, b in zip(yt, yp):
        m[KEYS.index(a), KEYS.index(b)] += 1
    fig, ax = plt.subplots(figsize=(4.4, 4))
    ax.imshow(m, cmap="Blues")
    ax.set_xticks(range(12), KEYS); ax.set_yticks(range(12), KEYS)
    ax.set_xlabel("recognised"); ax.set_ylabel("actual")
    for i in range(12):
        for j in range(12):
            if m[i, j]:
                ax.text(j, i, m[i, j], ha="center", va="center", fontsize=7, color="white" if m[i, j] > m.max() / 2 else INK)
    ax.set_title(title, fontsize=9)
    fig.savefig(path); plt.close(fig)


def md_table(df, fmt="{:.1f}"):
    cols = list(df.columns)
    out = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for i in range(len(df)):
        cells = []
        for c in cols:
            v = df[c].iloc[i]
            if isinstance(v, (int, np.integer)) or (isinstance(v, (float, np.floating)) and np.isfinite(v) and float(v).is_integer() and pd.api.types.is_integer_dtype(df[c])):
                cells.append(str(int(v)))
            elif isinstance(v, (float, np.floating)):
                cells.append("—" if not np.isfinite(v) else fmt.format(v))
            else:
                cells.append(str(v))
        out.append("| " + " | ".join(cells) + " |")
    return "\n".join(out)


# ---------------------------------------------------------------- main

def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "data")
    out = Path("report")
    figs = out / "fig"
    figs.mkdir(parents=True, exist_ok=True)
    files = sorted(root.rglob("*.zip"))
    recs, skipped, seen = [], [], set()
    for p in files:
        try:
            with zipfile.ZipFile(p) as z:
                meta = json.loads(z.read("meta.json"))
            if meta.get("format") != "telehaptic/1":
                continue
            # Stopped recordings (by name or by meta; a provisional meta means the app was killed) are left out.
            if "_ABORTED" in p.name or meta.get("aborted") or meta.get("provisional"):
                skipped.append((p.name, "stopped before the end"))
                continue
            rid = meta.get("id") or p.stem
            if rid in seen:
                skipped.append((p.name, "the same recording again (another copy)"))
                continue
            seen.add(rid)
            recs.append(load(p))
        except Exception as e:  # noqa: BLE001
            skipped.append((p.name, str(e)))
            print(f"skipping {p.name}: {e}")
    md = ["# Capsule recordings: analysis", ""]
    if not recs:
        md.append(f"No capsule recordings (format telehaptic/1) in `{root}`.")
        (out / "report.md").write_text("\n".join(md), encoding="utf-8")
        print("no recordings")
        return
    if any(r["synthetic"] for r in recs):
        md += ["**WARNING: some recordings are synthetic (tools/make_fake_data.py). The numbers below say nothing about the capsule.**", ""]

    md += ["## Recordings and stream quality", "", md_table(pd.DataFrame([quality(r) for r in recs]), "{:.2f}"), ""]
    if skipped:
        md += ["Left out: " + "; ".join(f"{n} ({why})" for n, why in skipped) + ".", ""]
    for r in recs:
        if r["restarts"]:
            md.append(f"{r['id']}: the capsule restarted {r['restarts']} time(s) during the recording; times come from the live phone clock.")
        if not r["imu"]:
            md.append(f"{r['id']}: no IMU data; the accelerometer sections leave it out.")

    # Wearings: every recording flagged "capsule just put on again" starts a new one (in time order).
    # Training and testing the re-wearing transfer must come from different wearings.
    def stamp(r):
        return r["meta"].get("start_wall_time") or r["id"][-15:]
    wearing = 0
    for r in sorted(recs, key=stamp):
        if r["rewear"]:
            wearing += 1
        r["wearing"] = wearing

    keyrecs = [r for r in recs if r["kind"] in ("KEYS", "NOMAG")]
    parts = []
    for r in keyrecs:
        try:
            parts.append(trials(r).assign(wearing=r["wearing"]))
        except Exception as e:  # noqa: BLE001
            md.append(f"{r['id']}: could not cut the trials ({e}); left out.")
    df = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()
    dropped = [(r["id"], r.get("dropped", 0)) for r in keyrecs if r.get("dropped")]
    if dropped:
        md.append("Trials left out because the capsule was lost, data stalled or the recording paused around them: "
                  + ", ".join(f"{i}: {n}" for i, n in dropped) + ".")
    md.append("")
    two = all(r["n_mags"] >= 2 for r in keyrecs) if keyrecs else False
    feat_names = list(FEATURES) if two else ["s1"]
    if keyrecs and not two:
        md += ["Only sensor 1 is compared, because these recordings have one sensor: "
               + ", ".join(r["id"] for r in keyrecs if r["n_mags"] < 2) + ".", ""]

    # ---- recognition
    md += ["## Recognition of the 12 keys", "", f"Reference: {STAND}. Chance is 8%.", ""]
    keys_df = df[df["kind"] == "KEYS"] if len(df) else df
    first = keys_df["wearing"].min() if len(keys_df) else 0
    table_plain = keys_df[(keys_df["posture"] == "table") & (keys_df["wearing"] == first)] if len(keys_df) else keys_df
    table_rew = keys_df[(keys_df["posture"] == "table") & (keys_df["wearing"] != first)] if len(keys_df) else keys_df
    down = keys_df[keys_df["posture"] == "down"] if len(keys_df) else keys_df
    rows, best = [], {}
    for feat in feat_names:
        for model in MODELS:
            r = {"features": FEATURES[feat], "model": model}
            yt, yp = scheme_within(keys_df, feat, model) if len(keys_df) else ([], [])
            a = acc_row("", yt, yp); r["within a recording, %"] = a["accuracy, %"]; r["within: interval"] = a["95% interval"]
            best[(model, feat, "within")] = (yt, yp)
            yt, yp = scheme_transfer(table_plain, table_rew, feat, model) if len(table_plain) and len(table_rew) else ([], [])
            a = acc_row("", yt, yp); r["after re-wearing, %"] = a["accuracy, %"]; r["re-wearing: interval"] = a["95% interval"]
            best[(model, feat, "rewear")] = (yt, yp)
            yt, yp = scheme_transfer(table_plain, down, feat, model) if len(table_plain) and len(down) else ([], [])
            a = acc_row("", yt, yp); r["other posture, %"] = a["accuracy, %"]; r["posture: interval"] = a["95% interval"]
            best[(model, feat, "posture")] = (yt, yp)
            rows.append(r)
    n_rew = table_rew["rec"].nunique() if len(table_rew) else 0
    n_down = down["rec"].nunique() if len(down) else 0
    md += ["Within a recording: train on three blocks, test on the fourth, over all Keys recordings.",
           "After re-wearing: train on Keys, table, first wearing; test on Keys, table, in later wearings "
           f"({n_rew} recording(s)).",
           f"Other posture: train on Keys, table, first wearing; test on Keys, arm along the body ({n_down} recording(s)).", "",
           md_table(pd.DataFrame(rows)), "",
           "The intervals count touches as independent. For the two transfer checks the touches all come from one "
           "re-wearing or one posture recording, so the real uncertainty is larger than shown: one more recording can move them a lot.", ""]
    if n_down and (down["wearing"] != first).any():
        md += ["The arm-along-the-body recording comes from a later wearing, so that check mixes the posture with re-wearing.", ""]
    if not len(table_rew):
        md.append("No recording after re-wearing: the re-wearing transfer is not computed.")
    if not len(down):
        md.append("No arm-along-the-body recording: the posture transfer is not computed.")
    for scheme, title in (("within", "within a recording"), ("rewear", "after re-wearing"), ("posture", "other posture")):
        yt, yp = best.get((PRIMARY[0], PRIMARY[1] if two else "s1", scheme), ([], []))
        if yt:
            name = f"confusion_{scheme}.png"
            plot_confusion(yt, yp, f"{PRIMARY[0]}, {FEATURES[PRIMARY[1] if two else 's1']}: {title}", figs / name)
            md.append(f"![{title}](fig/{name})")
    md.append("")

    # ---- geometry vs noise
    if len(keys_df):
        md += ["## Key spacing and drift", ""]
        g = []
        for feat in feat_names:
            nd = neighbour_distances(table_plain if len(table_plain) else keys_df, feat)
            if nd is None or not len(nd):
                continue
            sd_cols = [f"sd1{c}" for c in "xyz"]
            g.append({"features": FEATURES[feat], "neighbouring keys, µT (median)": float(np.median(nd)),
                      "neighbouring keys, µT (minimum)": float(np.min(nd)),
                      "key centre drift block to block, µT": block_drift(table_plain if len(table_plain) else keys_df, feat),
                      "noise during a hold, µT (sensor 1)": float(np.median(np.linalg.norm(keys_df[sd_cols].to_numpy(), axis=1)))})
        md += [md_table(pd.DataFrame(g)), "", "Phone stand for reference: neighbouring keys 10–12 µT apart, drift ≈ 13 µT, noise 0.4 µT.", ""]

    # ---- calibration
    cals = [c for c in (calibration(r, figs) for r in recs if r["kind"] == "CAL") if c]
    md += ["## Calibration and the Earth field left in the difference", ""]
    if not cals:
        md.append("No calibration recording, or too little data in it.")
    for c in cals:
        lines = [f"**{c['rec']}**, turning samples without the motor: {c['n']}. Fit on the first half, check on the second.", ""]
        for i in (1, 2):
            if f"m{i}_radius" in c:
                lines.append(f"- Sensor {i}: offset (hard iron) {np.round(c[f'm{i}_offset'], 1)} µT, "
                             f"fitted field magnitude {c[f'm{i}_radius']:.1f} µT (about 44 expected in Portugal), "
                             f"magnitude spread {c[f'm{i}_mod_sd']:.2f} µT; while the hand turns, one sensor alone moves by up to "
                             f"{c[f'm{i}_turn_p95']:.1f} µT (95th percentile).")
        if "cal_diff_rms" in c:
            lines += [f"- Rotation between the two sensors' axes after calibration: {c['rot_deg']:.1f}°.",
                      f"- **Earth field left in the difference while the hand turns: {c['cal_diff_rms']:.2f} µT RMS, {c['cal_diff_p95']:.2f} µT 95th percentile** "
                      f"(uncalibrated: {c['raw_diff_rms']:.2f} / {c['raw_diff_p95']:.2f}).",
                      "- The recognition above uses the uncalibrated difference: compare the uncalibrated number with the key spacing. "
                      "The calibrated one shows what a calibration would give; it is not applied to the features yet. "
                      "Reference: neighbouring keys were 10–12 µT apart on the phone stand.",
                      f"![calibration](fig/cal_{c['rec']}.png)"]
        md += lines + [""]

    # ---- no magnet
    nomag = df[df["kind"] == "NOMAG"] if len(df) else df
    md += ["## Without the magnet", ""]
    if len(nomag):
        r = []
        for feat in feat_names:
            yt, yp = scheme_within(nomag, feat, "LDA")
            r.append({"features": FEATURES[feat], **{k: v for k, v in acc_row("LDA within a recording", yt, yp).items() if k != "check"}})
        md += [md_table(pd.DataFrame(r)), "", "Chance is 8.3%. Without the magnet the result should not be above it."]
        for feat in feat_names:
            yt, yp = scheme_within(nomag, feat, "LDA")
            k = int(sum(a == b for a, b in zip(yt, yp)))
            lo, _ = wilson(k, len(yt))
            if len(yt):
                md.append(f"- {FEATURES[feat]}: " + (f"above chance (the lower end of the interval, {100 * lo:.0f}%, is above 8.3%): "
                          "part of the signal comes from the hand posture, not from the magnet."
                          if lo > 1 / 12 else "not distinguishable from chance."))
        md.append("")
    else:
        md.append("No recording without the magnet yet.")

    # ---- motor
    md += ["## Vibration motor", ""]
    mots = [m for m in (motor_effect(r) for r in recs if r["kind"] == "CAL") if m]
    if not mots:
        md.append("No calibration recording with motor pulses.")
    for m in mots:
        if "rows" in m:
            md += [f"**{m['rec']}**, still part at the start of the calibration:", "", md_table(pd.DataFrame(m["rows"]), "{:.2f}"), "",
                   "The motor also shakes the IMU. Samples with the motor on (and 0.1 s after) are left out of every accelerometer jolt metric below.", "",
                   md_table(pd.DataFrame(m["imu"]), "{:.3f}"), ""]
        else:
            md.append(f"{m['rec']}: {m['note']}")
    flagged = [(r["id"], int(np.sum(r["flags"] & (1 << 9) != 0))) for r in recs if r["kind"] != "CAL"]
    if any(n for _, n in flagged):
        md.append("Samples with the motor on in the other recordings: " + ", ".join(f"{i}: {n}" for i, n in flagged if n))
    md.append("")

    # ---- accelerometer
    md += ["## Accelerometer at the moment of touch", ""]
    acc = [x for r in keyrecs if r["kind"] == "KEYS" for x in accel_at_touch(r)]
    if acc:
        a = pd.DataFrame(acc)
        q = a[["on", "hold", "rest"]].quantile([0.1, 0.5, 0.9]).T
        q.columns = ["10%", "median", "90%"]
        q.index = ["touch onset", "hold", "rest before the touch"]
        md += [f"High-passed acceleration peak (m/s²), touches with a clear onset: {len(a)}.", "",
               md_table(q.reset_index().rename(columns={"index": "window"}), "{:.3f}"), "",
               f"Share of touches where the onset peak is more than twice the hold peak: {100 * np.mean(a['on'] > 2 * a['hold']):.0f}% (of {len(a)}).",
               "Phone stand: median 0.10 at the onset and 0.10 during the hold, such a peak in 25% of touches. "
               "But there the phone was held against the hand by the other hand and the touches were very gentle, so there is no direct comparison.", ""]
    elif keyrecs and not any(r["imu"] for r in keyrecs):
        md.append("No IMU data in the Keys recordings.")
    else:
        md.append("No touches with a clear onset.")

    # ---- training recordings: quick taps
    md += ["## Quick taps in training", ""]
    trs = [r for r in recs if r["kind"] in ("TRAIN", "RETRAIN")]
    if not trs:
        md.append("No training recordings yet.")
    for r in trs:
        try:
            res = training_taps(r)
        except Exception as e:  # noqa: BLE001
            md += [f"{r['id']}: could not analyse the quick taps ({e}).", ""]
            continue
        d = res["rows"]
        if d.empty:
            md.append(f"{r['id']}: no quick taps found.")
            continue
        q = d.drop(columns=["n_contact"]).quantile([0.1, 0.5, 0.9]).T
        q.columns = ["10%", "median", "90%"]
        q.index = ["amplitude vs rest, µT (difference)", "rise to the plateau, s", "plateau length, s",
                   "cue to plateau, s", "accelerometer peak at contact, m/s²"]
        md += [f"**{r['id']}**: {len(d)} quick taps; detector threshold on_ut = {res['on_ut']:.1f} µT. "
               f"Cued {res['cued']}, detected {res['found']}; cues without a detected tap (skipped or missed): {res['cued'] - res['found']}.", "",
               md_table(q.reset_index().rename(columns={"index": "per tap"}), "{:.3f}"), ""]
        if len(res["hold_acc"]) and r["imu"]:
            ha = np.median(res["hold_acc"])
            md.append(f"Accelerometer peak during a still hold, same window length (median): {ha:.3f} m/s². "
                      f"Taps whose contact peak is more than twice that: {100 * np.mean(d['acc_contact'] > 2 * ha):.0f}%.")
        md.append(f"Taps weaker than the detector threshold: {int(np.sum(d['amp'] < res['on_ut']))} of {len(d)}. "
                  f"Plateaus shorter than the detector needs (window + hold, 0.12 s): {int(np.sum(d['plateau'] < 0.12))}.")
        hd, tp = res["holds"], res["taps"]
        feat = "both" if r["n_mags"] >= 2 else "s1"
        if len(hd) >= 13 and len(tp):
            yt, yp = scheme_transfer(hd, tp, feat, "LDA")
            a = acc_row("", yt, yp)
            md.append(f"LDA trained on the holds, tested on the quick taps ({FEATURES[feat]}): "
                      f"{a['correct']} of {a['total']} ({a['accuracy, %']:.0f}%, {a['95% interval']}).")
        if len(tp) and tp["block"].nunique() > 1:
            yt, yp = scheme_within(tp.assign(rec=r["id"]), feat, "LDA")
            if yt:
                a = acc_row("", yt, yp)
                md.append(f"LDA on the quick taps only, one block left out: {a['correct']} of {a['total']} ({a['95% interval']}); "
                          f"chance with {res['n_keys']} keys: {100 / res['n_keys']:.0f}%.")
            else:
                md.append("LDA on the quick taps only: not computed, one block of 12 taps is too little to train on.")
        md.append("")

    (out / "report.md").write_text("\n".join(md), encoding="utf-8")
    print(f"report: {out / 'report.md'}")


if __name__ == "__main__":
    main()
