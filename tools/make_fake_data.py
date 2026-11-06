"""Synthetic recordings in the app's format, only to test analysis/analyze.py.

The numbers are invented: a dipole magnet on the thumb, two sensors on the back of the hand,
the Earth's field turning with the hand, sensor offsets, noise, motor pulses.
Never mix these files with real recordings.

    python tools/make_fake_data.py OUTDIR
"""
import io
import json
import sys
import zipfile
from pathlib import Path

import numpy as np

KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"]
RATE = 100.0
LSB = 1000.0            # per gauss, +-30 G; uT = raw / 10
EARTH = np.array([18.0, 0.0, -40.0])   # uT, ~44 uT
MU0_4PI = 1e-7


def rot(ax, deg):
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    if ax == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if ax == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


class Hand:
    def __init__(self, rng, dist_mm=38, moment=0.106):
        self.rng = rng
        self.s = np.array([[-dist_mm / 2, 0, 0], [dist_mm / 2, 0, 0]]) / 1000.0     # sensors, m
        self.m = moment                                                         # A m^2 (N45 8x2)
        # key positions under the hand, m: finger = row (x), phalanx = column (y)
        self.keys = {k: np.array([(-24 + 16 * (i // 3)) / 1000, (-10 - 13 * (i % 3)) / 1000, -0.028]) for i, k in enumerate(KEYS)}
        self.away = np.array([0.0, 0.03, -0.065])
        self.hard = np.array([[12.0, -8.0, 30.0], [-6.0, 15.0, 22.0]])          # sensor offsets, uT
        self.soft = [np.eye(3) + rng.normal(0, 0.03, (3, 3)) for _ in range(2)]

    def dipole(self, p):
        """Field of the magnet at p (m) at both sensors, uT; moment along -z (nail up)."""
        out = []
        mvec = np.array([0, 0, -self.m])
        for s in self.s:
            r = s - p
            d = np.linalg.norm(r)
            out.append(MU0_4PI * (3 * r * (mvec @ r) / d ** 5 - mvec / d ** 3) * 1e6)
        return np.array(out)

    def reading(self, p, R, motor, magnet=True):
        b = self.dipole(p) if magnet else np.zeros((2, 3))
        e = R.T @ EARTH
        out = []
        for i in range(2):
            v = self.soft[i] @ (e + b[i]) + self.hard[i] + self.rng.normal(0, 0.35, 3)
            if motor:
                v = v + np.array([4.0, -2.5, 6.0]) * (1.0 if i == 0 else 0.4) + self.rng.normal(0, 1.2, 3)
            out.append(v)
        return np.array(out)


def write_zip(path, meta, samples, events, packets, sync):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("meta.json", json.dumps(meta, indent=2, ensure_ascii=False))
        z.writestr("samples.csv", samples)
        z.writestr("events.csv", events)
        z.writestr("packets.csv", packets)
        z.writestr("sync.csv", sync)
        z.writestr("info.csv", "t_rx_ns,line\n")


def simulate(rng, hand, kind, posture, rewear, start_ns, seconds, schedule, motor_at=(), rotate=None, magnet=True):
    """schedule(t) -> magnet position; returns csv strings and counts."""
    n = int(seconds * RATE)
    base_R = {"table": rot("x", 90), "down": rot("y", 90) @ rot("x", 180)}.get(posture, np.eye(3))
    ticks0 = int(rng.integers(1_000_000, 5_000_000))
    drift = 1 + 25e-6
    lines = ["idx,t_xiao,t_ns,m1x,m1y,m1z,m2x,m2y,m2z,gx,gy,gz,ax,ay,az,flags"]
    packets = ["t_rx_ns,seq,first_idx,n,last_ticks"]
    prev_R = base_R
    idx0 = int(rng.integers(0, 50000))
    for i in range(n):
        t = i / RATE
        wob = rot("z", 2 * np.sin(0.7 * t)) @ rot("y", 1.5 * np.sin(0.43 * t + 1))
        R = base_R @ wob if rotate is None else rotate(t)
        motor = any(a <= t < a + d for a, d in motor_at)
        p = schedule(t)
        b = hand.reading(p, R, motor, magnet)
        raw = np.round(b * LSB / 100.0).astype(int)
        acc = (R.T @ np.array([0, 0, 9.81])) / 9.81 * 1000 / 0.244 + rng.normal(0, 20, 3)
        w = (R @ prev_R.T)
        ang = np.degrees(np.arccos(np.clip((np.trace(w) - 1) / 2, -1, 1))) * RATE
        gyr = np.array([ang, 0, 0]) * 1000 / 17.5 + rng.normal(0, 30, 3)
        prev_R = R
        flags = (1 << 9) if motor else 0
        ticks = ticks0 + int(t * 32768)
        tns = start_ns + int(t * 1e9 * drift) + 3_000_000
        lines.append(",".join(map(str, [idx0 + i, ticks, tns, *raw[0], *raw[1], *gyr.astype(int), *acc.astype(int), flags])))
        if i % 4 == 3:
            rx = tns + 2_000_000 + int(rng.exponential(5e6))
            packets.append(f"{rx},{i // 4},{idx0 + i - 3},4,{ticks}")
    return "\n".join(lines) + "\n", "\n".join(packets) + "\n", n, idx0


def meta_for(kind, posture, rewear, magnet, start_ns, seq, n, settings, dist):
    mags = [{"index": 1, "chip": "QMC5883P", "where": "ch0", "lsb_per_gauss": LSB},
            {"index": 2, "chip": "QMC5883P", "where": "ch1", "lsb_per_gauss": LSB}]
    cap = {"firmware": "0.1.0-fake", "boot_reason": "power_on", "mux_present": True, "scan": [], "config_line": "",
           "rate_hz": 100, "range_gauss": 30, "osr": "8/8", "imu": True, "magnetometers": mags, "battery_mv": 3950}
    return {"format": "telehaptic/1", "app_version": "fake", "synthetic": True, "id": "", "kind": kind, "posture": posture,
            "rewear": rewear, "magnet": magnet, "magnet_selected": "8x2", "sensor_distance_mm": dist, "note": "SYNTHETIC",
            "aborted": False, "start_wall_time": "", "start_elapsed_ns": start_ns, "seed": 0, "sequence": seq,
            "settings": settings, "capsule_at_start": cap, "capsule_at_end": cap,
            "imu_scale": {"acc_mg_per_lsb": 0.244, "gyr_mdps_per_lsb": 17.5}, "stats": {"samples": n}}


def keys_recording(rng, hand, out, name, posture, rewear, magnet=True):
    lead, hold, rest = 6.0, 2.5, 2.0
    seq = []
    for _ in range(4):
        while True:
            b = list(rng.permutation(KEYS))
            if not seq or b[0] != seq[-1]:
                break
        seq += b
    # per-block placement drift and per-touch scatter, m
    block_shift = [rng.normal(0, 0.0012, 3) for _ in range(4)]
    touch_jit = [rng.normal(0, 0.0009, 3) for _ in seq]
    if rewear:
        hand.hard = hand.hard + rng.normal(0, 3, (2, 3))
        hand.s = hand.s + rng.normal(0, 0.0015, (2, 3))

    def schedule(t):
        if t < lead:
            return hand.away
        i = int((t - lead) // (hold + rest))
        if i >= len(seq):
            return hand.away
        ph = (t - lead) - i * (hold + rest)
        target = hand.keys[seq[i]] + block_shift[i // 12] + touch_jit[i]
        if ph < 0.35:      # reaction + approach
            a = max(0.0, (ph - 0.25) / 0.1)
            return hand.away + (target - hand.away) * a
        if ph < hold:
            return target
        return hand.away
    start_ns = int(rng.integers(10**12, 10**13))
    total = lead + len(seq) * (hold + rest) + 0.5
    samples, packets, n, _ = simulate(rng, hand, "KEYS", posture, rewear, start_ns, total, schedule, magnet=magnet)
    ev = ["t_ns,event,trial,key", f"{start_ns},start,-1,"]
    for i, k in enumerate(seq):
        p = lead + i * (hold + rest)
        ev.append(f"{start_ns + int((p - rest + 0.3) * 1e9)},announce,{i},{k}")
        ev.append(f"{start_ns + int(p * 1e9)},press,{i},{k}")
        ev.append(f"{start_ns + int((p + hold) * 1e9)},release,{i},{k}")
    ev.append(f"{start_ns + int((total - 0.5) * 1e9)},end,-1,")
    kind = "KEYS" if magnet else "NOMAG"
    settings = {"lead_s": lead, "hold_s": hold, "rest_s": rest, "blocks": 4}
    meta = meta_for(kind, posture, rewear, "8x2" if magnet else "none", start_ns, seq, n, settings, 38)
    meta["id"] = name
    write_zip(out / f"{name}.zip", meta, samples, "\n".join(ev) + "\n", packets, "t_send_ns,t_recv_ns,token,t_xiao\n")


def calibration(rng, hand, out, name):
    lead, still, rot_s = 6.0, 10.0, 40.0
    pulses = [(lead + still * (p + 1) / 6, 0.3) for p in range(5)]
    axes = rng.normal(0, 1, (6, 3))

    def rotate(t):
        if t < lead + still:
            return rot("x", 90)
        u = t - lead - still
        R = rot("x", 90)
        for j, a in enumerate(axes):
            R = R @ rot("xyz"[j % 3], 70 * np.sin(0.25 * (j + 1) * u + a[0]))
        return R
    start_ns = int(rng.integers(10**12, 10**13))
    total = lead + still + rot_s + 0.5
    samples, packets, n, _ = simulate(rng, hand, "CAL", None, False, start_ns, total, lambda t: hand.away, motor_at=pulses,
                                      rotate=rotate, magnet=False)
    ev = ["t_ns,event,trial,key", f"{start_ns},start,-1,", f"{start_ns + int(lead * 1e9)},still_start,-1,"]
    for j, (a, d) in enumerate(pulses):
        ev.append(f"{start_ns + int(a * 1e9)},motor_cmd,{j},300")
    ev.append(f"{start_ns + int((lead + still) * 1e9)},calib_start,-1,")
    ev.append(f"{start_ns + int((lead + still + rot_s) * 1e9)},calib_end,-1,")
    settings = {"lead_s": lead, "still_s": still, "rotate_s": rot_s, "motor_pulses": 5, "motor_pulse_ms": 300}
    meta = meta_for("CAL", None, False, "none", start_ns, [], n, settings, 38)
    meta["id"] = name
    write_zip(out / f"{name}.zip", meta, samples, "\n".join(ev) + "\n", packets, "t_send_ns,t_recv_ns,token,t_xiao\n")


def training(rng, hand, out, name):
    """Holds as in the app's training (announce, +1 s press, 1.5 s hold, 1.5 s to the next announce),
    then quick taps at one's own pace with a short plateau."""
    lead = 2.5
    holds = [k for b in range(2) for k in rng.permutation(KEYS)]
    taps = [k for b in range(2) for k in rng.permutation(KEYS)]
    t_holds_end = lead + len(holds) * 4.0 + 2.5
    plan = []                       # (start, rise_end, plateau_end, release_end, key)
    t = t_holds_end + 0.5
    for k in taps:
        rise, plat, rel = 0.06, rng.uniform(0.12, 0.3), 0.08
        plan.append((t, t + rise, t + rise + plat, t + rise + plat + rel, k))
        t += rise + plat + rel + rng.uniform(0.5, 0.9)
    total = t + 1.0

    def schedule(tt):
        if lead <= tt < t_holds_end - 2.5:
            i = int((tt - lead) // 4.0)
            ph = (tt - lead) - i * 4.0
            target = hand.keys[holds[i]]
            if 1.0 <= ph < 1.1:
                return hand.away + (target - hand.away) * (ph - 1.0) / 0.1
            if 1.1 <= ph < 2.5:
                return target
            return hand.away
        for (a, b, c, d, k) in plan:
            if a <= tt < d:
                target = hand.keys[k]
                if tt < b:
                    return hand.away + (target - hand.away) * (tt - a) / (b - a)
                if tt < c:
                    return target
                return target + (hand.away - target) * (tt - c) / (d - c)
        return hand.away
    start_ns = int(rng.integers(10**12, 10**13))
    samples, packets, n, _ = simulate(rng, hand, "TRAIN", "table", False, start_ns, total, schedule)
    ev = ["t_ns,event,trial,key", f"{start_ns},start,-1,"]
    for i, k in enumerate(holds):
        a = lead + i * 4.0
        ev += [f"{start_ns + int(a * 1e9)},announce,{i},{k}", f"{start_ns + int((a + 1.0) * 1e9)},press,{i},{k}",
               f"{start_ns + int((a + 2.5) * 1e9)},release,{i},{k}"]
    ev.append(f"{start_ns + int((t_holds_end - 0.1) * 1e9)},detector,-1,on_ut=8.00;stable_ut=1.50;window_ms=80;hold_ms=40;gyro_max_dps=60;rest=")
    for i, (a, b, c, d, k) in enumerate(plan):
        ev += [f"{start_ns + int((a - 0.45) * 1e9)},tap_cue,{i},{k}", f"{start_ns + int(b * 1e9)},tap_plateau,{i},{k}",
               f"{start_ns + int((b + 0.12) * 1e9)},tap,{i},{k}"]
    ev.append(f"{start_ns + int((total - 0.5) * 1e9)},end,-1,")
    settings = {"lead_s": lead, "hold_s": 1.5, "rest_s": 2.5, "blocks": 2}
    meta = meta_for("TRAIN", None, False, "8x2", start_ns, [], n, settings, 38)
    meta["id"] = name
    write_zip(out / f"{name}.zip", meta, samples, "\n".join(ev) + "\n", packets, "t_send_ns,t_recv_ns,token,t_xiao\n")


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "fake_data")
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(1)
    hand = Hand(rng)
    calibration(rng, hand, out, "CAL_20261111-150000")
    keys_recording(rng, hand, out, "KEYS_table_20261111-151000", "table", False)
    keys_recording(rng, hand, out, "KEYS_table_rewear_20261111-152000", "table", True)
    keys_recording(rng, hand, out, "KEYS_down_20261111-153000", "down", False)
    keys_recording(rng, hand, out, "NOMAG_table_20261111-154000", "table", False, magnet=False)
    training(rng, hand, out, "TRAIN_20261111-155000")
    print(f"synthetic recordings in {out}/")


if __name__ == "__main__":
    main()
