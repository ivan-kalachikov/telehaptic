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

STAND = "стенд (телефон, P1_r1): внутри записи LDA и kNN по полю при касании 73–75%, 95% интервал ≈ 59–85%"

FEATURES = {
    "s1": "датчик 1 (3 числа)",
    "s2": "датчик 2 (3 числа)",
    "both": "оба датчика (6 чисел)",
    "diff": "разность датчиков 1 − 2 (3 числа)",
    "both+g": "оба датчика + направление гравитации",
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
    clock = refit_clock(packets)
    if clock is not None:
        ts = s["t_xiao"].to_numpy(dtype=float) / TICKS
        t_ns = ts * 1e9 + clock["a"] + clock["b"] * ts
        clock["live_vs_refit_ms"] = float(np.median(np.abs(s["t_ns"].to_numpy(dtype=float) - t_ns)) / 1e6)
    else:
        t_ns = s["t_ns"].to_numpy(dtype=float)
    rec = {"path": path, "meta": meta, "id": meta.get("id") or Path(path).stem, "kind": meta.get("kind"),
           "posture": meta.get("posture"), "rewear": bool(meta.get("rewear")), "n_mags": n,
           "t": (t_ns - t0) / 1e9, "idx": s["idx"].to_numpy(), "flags": s["flags"].to_numpy(dtype=int),
           "clock": clock, "packets": packets, "sync": sync, "synthetic": bool(meta.get("synthetic"))}
    for i, m in enumerate(mags):
        k = 100.0 / float(m["lsb_per_gauss"])     # raw -> uT
        rec[f"m{i + 1}"] = s[[f"m{i + 1}x", f"m{i + 1}y", f"m{i + 1}z"]].to_numpy(dtype=float) * k
    sc = meta.get("imu_scale", {"acc_mg_per_lsb": 0.244, "gyr_mdps_per_lsb": 17.5})
    rec["acc"] = s[["ax", "ay", "az"]].to_numpy(dtype=float) * sc["acc_mg_per_lsb"] / 1000.0 * 9.80665   # m/s2
    rec["gyr"] = s[["gx", "gy", "gz"]].to_numpy(dtype=float) * sc["gyr_mdps_per_lsb"] / 1000.0            # deg/s
    e = ev.copy()
    e["t"] = (e["t_ns"] - t0) / 1e9
    rec["events"] = e
    return rec


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
    return {"запись": rec["id"], "отсчётов": len(idx), "частота, Гц": len(idx) / dur if dur > 0 else np.nan,
            "потери, %": 100 * lost / max(1, lost + len(idx)), "насыщение (отсчёты)": ", ".join(f"{k}: {v}" for k, v in sat.items()),
            "нет новых данных, %": 100 * np.mean(f & (1 << 11) != 0), "ошибки I2C": int(np.sum(f & (1 << 13) != 0)),
            "RTT медиана, мс": rtt, "часы: разброс, мс": c.get("resid_ms", np.nan),
            "часы: живые vs пересчёт, мс": c.get("live_vs_refit_ms", np.nan)}


# ---------------------------------------------------------------- trials

def trials(rec):
    meta, ev = rec["meta"], rec["events"]
    rest_s = meta["settings"].get("rest_s", 2.0)
    press = ev[ev["event"] == "press"].set_index("trial")
    release = ev[ev["event"] == "release"].set_index("trial")
    rows = []
    for tr, p in press.iterrows():
        if tr not in release.index:
            continue
        tp, tr_ = p["t"], release.loc[tr, "t"]
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
    if name == "both+g":
        g = df[["gx", "gy", "gz"]].to_numpy()
        return np.hstack([h1, h2, g / np.linalg.norm(g, axis=1, keepdims=True)])
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
    return {"проверка": name, "верно": k, "всего": n, "точность, %": 100 * k / n if n else np.nan,
            "95% интервал": f"{100 * lo:.0f}–{100 * hi:.0f}%" if n else "—"}


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
    for i, p in enumerate(P):
        fit = ellipsoid_fit(p[:half])
        if fit is None:
            c, r = sphere_fit(p[:half])
            fit = (c, np.eye(3) / r)
        c, A = fit                                   # (p - c) A^T lies on a unit sphere
        _, radius = sphere_fit(p[:half])             # field magnitude in uT for the scale
        cp = (p - c) @ A.T * radius
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
        ax.plot(tt, np.linalg.norm(P[0] - np.median(P[0], 0), axis=1), color=MUTED, lw=0.7, label="датчик 1, отклонение")
        ax.plot(tt, np.linalg.norm(raw_d - np.median(raw_d, 0), axis=1), color=ORANGE, lw=0.7, label="разность без калибровки")
        ax.plot(tt, np.linalg.norm(d - np.median(d, 0), axis=1), color=BLUE, lw=0.9, label="разность после калибровки")
        ax.axvline(tt[half], color=GRID, lw=1)
        ax.set_xlabel("время, с"); ax.set_ylabel("мкТл"); ax.legend(frameon=False, fontsize=8)
        ax.set_title(f"{rec['id']}: вращение кисти без магнита (слева от линии — подгонка, справа — проверка)", fontsize=9)
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
        return {"rec": rec["id"], "note": "в покое калибровки нет отсчётов с флагом мотора"}
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
        rows.append({"датчик": i + 1, "сдвиг поля при импульсе, мкТл": shift,
                     "шум в покое, мкТл (СКО)": float(np.linalg.norm(sd_off)),
                     "шум во время импульса, мкТл (СКО)": float(np.linalg.norm(np.std(m[on], axis=0))),
                     "возврат после импульса, с (медиана)": float(np.median(rec_times)) if rec_times else np.nan,
                     "импульсов": len(ends)})
    return {"rec": rec["id"], "rows": rows}


# ---------------------------------------------------------------- accelerometer at the touch

def accel_at_touch(rec):
    """High-passed acceleration peak at the touch onset (from the magnet) vs during the hold and at rest."""
    if rec["n_mags"] < 1:
        return []
    ev = rec["events"]
    rest_s = rec["meta"]["settings"].get("rest_s", 2.0)
    t, A = rec["t"], rec["acc"]
    k = max(3, int(round(0.04 * len(t) / max(1e-9, t[-1] - t[0]))))
    hp = A - np.column_stack([np.convolve(A[:, i], np.ones(k) / k, "same") for i in range(3)])
    e = np.linalg.norm(hp, axis=1)
    sig = rec["m1"] - rec["m2"] if rec["n_mags"] >= 2 else rec["m1"]
    press = ev[ev["event"] == "press"].set_index("trial")
    release = ev[ev["event"] == "release"].set_index("trial")
    rows = []
    for tr, p in press.iterrows():
        if tr not in release.index:
            continue
        tp, trl = p["t"], release.loc[tr, "t"]
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
        rows.append({"key": p["key"], "on": e[window(rec, on - 0.15, on + 0.1)].max(),
                     "hold": e[hw].max(), "rest": e[rw].max()})
    return rows


# ---------------------------------------------------------------- figures

def plot_confusion(yt, yp, title, path):
    m = np.zeros((12, 12), int)
    for a, b in zip(yt, yp):
        m[KEYS.index(a), KEYS.index(b)] += 1
    fig, ax = plt.subplots(figsize=(4.4, 4))
    ax.imshow(m, cmap="Blues")
    ax.set_xticks(range(12), KEYS); ax.set_yticks(range(12), KEYS)
    ax.set_xlabel("распознано"); ax.set_ylabel("на самом деле")
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
    files = sorted(p for p in root.rglob("*.zip") if "_ABORTED" not in p.name)
    recs = []
    for p in files:
        try:
            with zipfile.ZipFile(p) as z:
                if json.loads(z.read("meta.json")).get("format") != "telehaptic/1":
                    continue
            recs.append(load(p))
        except Exception as e:  # noqa: BLE001
            print(f"пропускаю {p.name}: {e}")
    md = ["# Анализ записей капсулы", ""]
    if not recs:
        md.append(f"В `{root}` нет записей капсулы (format telehaptic/1).")
        (out / "report.md").write_text("\n".join(md), encoding="utf-8")
        print("нет записей")
        return
    if any(r["synthetic"] for r in recs):
        md += ["**ВНИМАНИЕ: среди записей есть синтетические (tools/make_fake_data.py). Числа ниже ничего не говорят о капсуле.**", ""]

    md += ["## Записи и качество потока", "", md_table(pd.DataFrame([quality(r) for r in recs]), "{:.2f}"), ""]

    keyrecs = [r for r in recs if r["kind"] in ("KEYS", "NOMAG")]
    df = pd.concat([trials(r) for r in keyrecs], ignore_index=True) if keyrecs else pd.DataFrame()
    two = all(r["n_mags"] >= 2 for r in keyrecs) if keyrecs else False
    feat_names = list(FEATURES) if two else ["s1"]

    # ---- recognition
    md += ["## Распознавание 12 клавиш", "", f"Сравнение: {STAND}. Случайное угадывание — 8%.", ""]
    keys_df = df[df["kind"] == "KEYS"] if len(df) else df
    table_plain = keys_df[(keys_df["posture"] == "table") & (~keys_df["rewear"])] if len(keys_df) else keys_df
    table_rew = keys_df[(keys_df["posture"] == "table") & (keys_df["rewear"])] if len(keys_df) else keys_df
    down = keys_df[keys_df["posture"] == "down"] if len(keys_df) else keys_df
    rows, best = [], {}
    for feat in feat_names:
        for model in MODELS:
            r = {"признаки": FEATURES[feat], "модель": model}
            yt, yp = scheme_within(keys_df, feat, model) if len(keys_df) else ([], [])
            a = acc_row("", yt, yp); r["внутри записи, %"] = a["точность, %"]; r["внутри: интервал"] = a["95% интервал"]
            best[(model, feat, "within")] = (yt, yp)
            yt, yp = scheme_transfer(table_plain, table_rew, feat, model) if len(table_plain) and len(table_rew) else ([], [])
            a = acc_row("", yt, yp); r["повторное надевание, %"] = a["точность, %"]; r["надевание: интервал"] = a["95% интервал"]
            best[(model, feat, "rewear")] = (yt, yp)
            yt, yp = scheme_transfer(table_plain, down, feat, model) if len(table_plain) and len(down) else ([], [])
            a = acc_row("", yt, yp); r["другая поза, %"] = a["точность, %"]; r["поза: интервал"] = a["95% интервал"]
            best[(model, feat, "posture")] = (yt, yp)
            rows.append(r)
    md += ["Внутри записи: обучение на трёх блоках, проверка на четвёртом, по всем записям «Клавиши».",
           "Повторное надевание: обучение на «Клавиши, на столе», проверка на «Клавиши, на столе, после повторного надевания».",
           "Другая поза: обучение на «Клавиши, на столе», проверка на «Клавиши, рука вдоль тела».", "",
           md_table(pd.DataFrame(rows)), "",
           "Признаки с направлением гравитации привязаны к позе руки, поэтому на другую позу они переносятся плохо по самой своей природе.", ""]
    if not len(table_rew):
        md.append("Записи после повторного надевания нет — перенос на надевание не посчитан.")
    if not len(down):
        md.append("Записи «рука вдоль тела» нет — перенос на позу не посчитан.")
    for scheme, title in (("within", "внутри записи"), ("rewear", "повторное надевание"), ("posture", "другая поза")):
        yt, yp = best.get((PRIMARY[0], PRIMARY[1] if two else "s1", scheme), ([], []))
        if yt:
            name = f"confusion_{scheme}.png"
            plot_confusion(yt, yp, f"{PRIMARY[0]}, {FEATURES[PRIMARY[1] if two else 's1']}: {title}", figs / name)
            md.append(f"![{title}](fig/{name})")
    md.append("")

    # ---- geometry vs noise
    if len(keys_df):
        md += ["## Разнос клавиш и уплывание", ""]
        g = []
        for feat in feat_names:
            nd = neighbour_distances(table_plain if len(table_plain) else keys_df, feat)
            if nd is None or not len(nd):
                continue
            sd_cols = [f"sd1{c}" for c in "xyz"]
            g.append({"признаки": FEATURES[feat], "соседние клавиши, мкТл (медиана)": float(np.median(nd)),
                      "соседние клавиши, мкТл (минимум)": float(np.min(nd)),
                      "уплывание центра клавиши от блока к блоку, мкТл": block_drift(table_plain if len(table_plain) else keys_df, feat),
                      "шум при удержании, мкТл (датчик 1)": float(np.median(np.linalg.norm(keys_df[sd_cols].to_numpy(), axis=1)))})
        md += [md_table(pd.DataFrame(g)), "", "Стенд для сравнения: соседние клавиши 10–12 мкТл, уплывание ≈ 13 мкТл, шум 0,4 мкТл.", ""]

    # ---- calibration
    cals = [c for c in (calibration(r, figs) for r in recs if r["kind"] == "CAL") if c]
    md += ["## Калибровка и поле Земли в разности", ""]
    if not cals:
        md.append("Записи калибровки нет или в ней слишком мало данных.")
    for c in cals:
        lines = [f"**{c['rec']}**, отсчётов вращения без мотора: {c['n']}. Подгонка на первой половине, проверка на второй.", ""]
        for i in (1, 2):
            if f"m{i}_radius" in c:
                lines.append(f"- Датчик {i}: смещение (жёсткое железо) {np.round(c[f'm{i}_offset'], 1)} мкТл, "
                             f"модуль поля после калибровки {c[f'm{i}_radius']:.1f} мкТл (для Португалии ожидается около 44), "
                             f"разброс модуля {c[f'm{i}_mod_sd']:.2f} мкТл; при вращении поле одного датчика уходит до "
                             f"{c[f'm{i}_turn_p95']:.1f} мкТл (95-й процентиль).")
        if "cal_diff_rms" in c:
            lines += [f"- Взаимный поворот осей датчиков после калибровки: {c['rot_deg']:.1f}°.",
                      f"- **Поле Земли в разности при вращении: {c['cal_diff_rms']:.2f} мкТл СКО, {c['cal_diff_p95']:.2f} мкТл 95-й процентиль** "
                      f"(без калибровки: {c['raw_diff_rms']:.2f} / {c['raw_diff_p95']:.2f}).",
                      "- Сравнение: соседние клавиши на стенде разнесены на 10–12 мкТл; у капсулы — см. таблицу выше.",
                      f"![калибровка](fig/cal_{c['rec']}.png)"]
        md += lines + [""]

    # ---- no magnet
    nomag = df[df["kind"] == "NOMAG"] if len(df) else df
    md += ["## Без магнита", ""]
    if len(nomag):
        r = []
        for feat in feat_names:
            yt, yp = scheme_within(nomag, feat, "LDA")
            r.append({"признаки": FEATURES[feat], **{k: v for k, v in acc_row("LDA внутри записи", yt, yp).items() if k != "проверка"}})
        md += [md_table(pd.DataFrame(r)), "", "Должно быть около 8% (случайное угадывание). Если заметно больше — сигнал идёт от позы руки, а не от магнита.", ""]
    else:
        md.append("Записи без магнита пока нет.")

    # ---- motor
    md += ["## Вибромотор", ""]
    mots = [m for m in (motor_effect(r) for r in recs if r["kind"] == "CAL") if m]
    if not mots:
        md.append("Нет записи калибровки с импульсами мотора.")
    for m in mots:
        if "rows" in m:
            md += [f"**{m['rec']}**, покой в начале калибровки:", "", md_table(pd.DataFrame(m["rows"]), "{:.2f}"), ""]
        else:
            md.append(f"{m['rec']}: {m['note']}")
    flagged = [(r["id"], int(np.sum(r["flags"] & (1 << 9) != 0))) for r in recs if r["kind"] != "CAL"]
    if any(n for _, n in flagged):
        md.append("Отсчёты с включённым мотором в остальных записях: " + ", ".join(f"{i}: {n}" for i, n in flagged if n))
    md.append("")

    # ---- accelerometer
    md += ["## Акселерометр в момент касания", ""]
    acc = [x for r in keyrecs if r["kind"] == "KEYS" for x in accel_at_touch(r)]
    if acc:
        a = pd.DataFrame(acc)
        q = a[["on", "hold", "rest"]].quantile([0.1, 0.5, 0.9]).T
        q.columns = ["10%", "медиана", "90%"]
        q.index = ["момент касания", "удержание", "покой перед касанием"]
        md += [f"Пик ускорения после фильтра верхних частот (м/с²), касаний с чётким началом: {len(a)}.", "",
               md_table(q.reset_index().rename(columns={"index": "окно"}), "{:.3f}"), "",
               f"Доля касаний, где пик в момент касания больше удвоенного пика при удержании: {100 * np.mean(a['on'] > 2 * a['hold']):.0f}%.",
               "На стенде: медиана 0,10 в момент касания и 0,10 при удержании, такой всплеск — в 25% касаний.", ""]
    else:
        md.append("Нет касаний с чётким началом.")

    (out / "report.md").write_text("\n".join(md), encoding="utf-8")
    print(f"отчёт: {out / 'report.md'}")


if __name__ == "__main__":
    main()
