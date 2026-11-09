"""
The capsule's printed parts, as smooth organic shapes:

  capsule_base.stl    flat on top so both magnetometers stay parallel (the app subtracts their
                      readings to cancel the Earth field); sensor pockets, a tray for the
                      battery, the multiplexer and the XIAO, slots for the strap; a shallow
                      Voronoi relief on the wings.
  capsule_saddle.stl  glued under the base; its underside follows the convex back of the hand,
                      as an open Voronoi lattice with a solid rim and solid frames around the slots.

Both print flat side down, without supports (the saddle with its arc facing up).

    pip install numpy scikit-image trimesh fast-simplification matplotlib
    python hardware/make_case.py           # STLs
    python hardware/make_case.py --show    # and preview images

All sizes in mm. MEASURE YOUR PARTS and your hand and change the numbers below: module sizes differ
between sellers (GY-271 listings give 13.2 x 14.4, 14.8 x 13.5 and 13.5 x 18 mm).
Axes: x across the back of the hand (+x = index finger side, sensor 1), y along the fingers
(+y = towards the knuckles), z away from the skin.

The shapes are signed distance fields sampled on a grid and meshed with marching cubes, which is
what gives the rounded edges and the smooth joins.
"""
import sys
from pathlib import Path

import numpy as np

PARTS = {
    "gy271": (14.8, 13.5),         # magnetometer module, x by y as it lies in its pocket
    "xiao": (23.0, 17.8),          # XIAO nRF52840 Sense: length with the USB socket (measured 22.5-23), width
    "mux": (30.5, 21.0),           # TCA9548A module: length, width (measured)
    "battery": (42.0, 30.0, 3.0),  # Akyga LP303040 320 mAh: length, width, thickness (measured 42 x 30)
}
# The multiplexer lies on the tray floor, the XIAO on it (2 mm foam tape between), long sides along
# the fingers (y), the USB socket towards the knuckles (+y). The flat battery (3 mm) lies on top of
# the tray like a lid: it is as wide as the tray, so the capsule stays narrow across the hand.
STACK_H = 11.0          # tray height: multiplexer + foam + XIAO with its USB socket, and the wires
WIRE_SIDE = 2.75        # room on each long side of the tray for solder joints and bent wires
WIRE_END = 1.5          # room at each short end of the tray
SENSOR_DISTANCE = 50.0  # centre to centre of the two chips; enter the same number in the app
STRAP_WIDTH = 20.0      # elastic band width: it runs in a channel between the base and the saddle
STRAP_THICK = 1.6       # channel depth (an elastic band is about 1-1.5 mm thick)
# Back of the hand: hand relaxed, a straight ruler across the back of the hand where the capsule
# sits, touching the highest point; the gap under the ruler 40 mm left and right of that point.
ARCH_DROP_AT_40 = 5.0   # mm; 5 is a placeholder, not a measurement
SADDLE_MIN = 1.6        # saddle thickness in the middle, under the strap channel

CLEAR = 0.6             # gap around each part in its pocket (total)
WALL = 1.6
PLATE = 2.4             # base plate thickness
STOP_H = 3.0            # sensor corner stops, height above the plate
RES = 0.25              # grid step; smaller is smoother and slower

HERE = Path(__file__).parent
SEED = 7


# ---------------------------------------------------------------- distance field helpers

def rrect(x, y, hx, hy, r):
    """Rounded rectangle centred at 0: half sizes hx, hy, corner radius r."""
    qx, qy = np.abs(x) - hx + r, np.abs(y) - hy + r
    return np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - r


def rounded_and(a, b, r):
    """Intersection of two fields with the edge between them rounded by r (a, b already inset by r)."""
    return np.hypot(np.maximum(a, 0), np.maximum(b, 0)) + np.minimum(np.maximum(a, b), 0) - r


def smin(a, b, k):
    """Smooth union: a fillet of size about k where the shapes meet."""
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0, 1)
    return b * (1 - h) + a * h - k * h * (1 - h)


def voronoi_edges(x, y, spacing, jitter, rng, extent):
    """About half the gap between the nearest and second nearest seed: ~distance to a cell wall."""
    ex, ey = extent
    gx = np.arange(-ex - spacing, ex + spacing, spacing)
    gy = np.arange(-ey - spacing, ey + spacing, spacing * 0.87)
    sx, sy = np.meshgrid(gx, gy)
    sx = sx + (np.arange(sy.shape[0])[:, None] % 2) * spacing / 2   # hexagonal-ish start
    seeds = np.c_[sx.ravel(), sy.ravel()] + rng.uniform(-jitter, jitter, (sx.size, 2))
    d = np.sqrt((x[..., None] - seeds[:, 0]) ** 2 + (y[..., None] - seeds[:, 1]) ** 2)
    d.sort(axis=-1)
    return (d[..., 1] - d[..., 0]) / 2


# ---------------------------------------------------------------- shared geometry

gx_, gy_ = PARTS["gy271"]
PX, PY = gx_ + CLEAR, gy_ + CLEAR
mx_, my_ = PARTS["mux"]
xx_, xy_ = PARTS["xiao"]
bl_, bw_, bh_ = PARTS["battery"]
TRAY_IX = max(my_, xy_) + CLEAR + 2 * WIRE_SIDE
# Along the fingers the tray is as long as the battery, so the battery lid rests on it end to end.
TRAY_IY = max(max(mx_, xx_) + CLEAR + 2 * WIRE_END, bl_ + 0.4 - 2 * WALL)
TRAY_H = STACK_H
if bw_ > TRAY_IX + 2 * WALL + 4 or bl_ > TRAY_IY + 2 * WALL + 6:
    print(f"note: the battery ({bl_:.0f} x {bw_:.0f}) overhangs the tray ({TRAY_IY + 2 * WALL:.0f} x {TRAY_IX + 2 * WALL:.0f}) a lot")
TX, TY = TRAY_IX / 2 + WALL, TRAY_IY / 2 + WALL                # tray outer half sizes
HALF_L = SENSOR_DISTANCE / 2 + PX / 2 + WALL + 2.4              # half length: just past the pockets
CH_HY = (STRAP_WIDTH + 1) / 2                                   # strap channel half width
END_HY = max(CH_HY + 2.4, PY / 2 + WALL + 2.0)                  # half width at the ends
MID_HY = TY                                                     # half width at the tray

if TX > SENSOR_DISTANCE / 2 - PX / 2 - WALL - 0.4:
    raise SystemExit(f"The tray ({TRAY_IX:.1f} mm inside) does not fit between the sensors: raise "
                     f"SENSOR_DISTANCE to at least {2 * (TX + PX / 2 + WALL + 0.4):.0f} mm or use smaller parts.")


def outline(x, y):
    """Plan outline: wide at the tray, narrowing smoothly towards the sensors, round ends."""
    ax = np.abs(x)
    x0 = SENSOR_DISTANCE / 2
    t = np.clip(ax / x0, 0, 1)
    hy = MID_HY + (END_HY - MID_HY) * (3 * t ** 2 - 2 * t ** 3)      # smoothstep from middle to sensor
    hy = np.where(ax > x0, END_HY, hy)
    u = np.clip((ax - x0) / (HALF_L - x0), 0, 1)                    # quarter superellipse to the tip
    hy = hy * (1 - u ** 4) ** 0.25
    return np.maximum(np.abs(y) - hy, ax - HALF_L)


def arch_top(x):
    """Saddle thickness at x (printing orientation: flat face on the bed)."""
    d = max(ARCH_DROP_AT_40, 0.1)
    r = (40.0 ** 2 + d ** 2) / (2 * d)
    xx = np.minimum(np.abs(x), r * 0.95)
    return SADDLE_MIN + (r - np.sqrt(r * r - xx * xx)), r


def grid(zmax):
    pad = 2.0
    # Offset by a fraction of a step, so no flat face of the part lies exactly on grid points
    # (that makes marching cubes emit degenerate triangles).
    o = RES * 0.37
    xs = np.arange(-HALF_L - pad, HALF_L + pad, RES) + o
    ys = np.arange(-MID_HY - pad, MID_HY + pad, RES) + o
    zs = np.arange(-pad, zmax + pad, RES) + o
    return xs, ys, zs


# ---------------------------------------------------------------- base

def base_field(rng):
    xs, ys, zs = grid(PLATE + TRAY_H)
    X, Y = np.meshgrid(xs, ys, indexing="ij")
    O = outline(X, Y)
    # Voronoi relief on the wings: between the tray and the pockets and around them.
    V = voronoi_edges(X, Y, 5.5, 1.6, rng, (HALF_L, MID_HY))
    wing = (np.abs(X) > TX + 2.0) & (O < -1.8)
    for s in (-1, 1):
        wing &= rrect(X - s * SENSOR_DISTANCE / 2, Y, PX / 2 + WALL + 1.2, PY / 2 + WALL + 1.2, 1.5) > 0
    groove = wing & (V < 0.45)
    # Pocket floor arrows (+x), engraved 0.4 mm.
    arrow = np.zeros_like(X, dtype=bool)
    for s in (-1, 1):
        cx = s * SENSOR_DISTANCE / 2
        u, v = X - cx, Y
        shaft = (u > -4) & (u < 1.5) & (np.abs(v) < 0.6)
        head = (u >= 1.5) & (u < 4) & (np.abs(v) < (4 - u) * 0.9)
        arrow |= shaft | head
    T = rrect(X, Y, TX, TY, 4.5)                     # tray outer
    Ti = rrect(X, Y, TX - WALL, TY - WALL, 2.8)      # tray inner
    # Corner stops: L-shapes at the four corners of each pocket.
    stops = np.full_like(X, 1e3)
    leg = 3.6
    for s in (-1, 1):
        cx = s * SENSOR_DISTANCE / 2
        for sx in (-1, 1):
            for sy in (-1, 1):
                ix, iy = cx + sx * PX / 2, sy * PY / 2
                a = rrect(X - (ix - sx * leg / 2 + sx * WALL / 2), Y - (iy + sy * WALL / 2), (leg + WALL) / 2, WALL / 2, 0.6)
                b = rrect(X - (ix + sx * WALL / 2), Y - (iy - sy * leg / 2 + sy * WALL / 2), WALL / 2, (leg + WALL) / 2, 0.6)
                stops = np.minimum(stops, np.minimum(a, b))
    field = np.empty((len(xs), len(ys), len(zs)), dtype=np.float32)
    top = PLATE + TRAY_H
    for k, z in enumerate(zs):
        plate = rounded_and(O + 1.2, (z - PLATE) + 1.2, 1.2)
        tray = rounded_and(T + 0.9, (z - top) + 0.9, 0.9)
        st = rounded_and(stops + 0.5, (z - (PLATE + STOP_H)) + 0.5, 0.5)
        d = smin(plate, tray, 2.5)
        d = smin(d, st, 0.8)
        d = np.maximum(d, -z)                                     # flat bottom
        # Tray cavity from the plate up.
        cav = np.maximum(Ti, PLATE - z) if z > PLATE - 0.01 else np.full_like(X, 1e3)
        d = np.maximum(d, -cav)
        # Wire openings in both x walls: a U from z = PLATE + 2 up, 10 mm wide, round bottom.
        in_xwall = np.abs(np.abs(X) - (TX - WALL / 2)) - WALL
        u = rrect(Y, z - (PLATE + 2.0 + 50.0), 5.0, 50.0, 5.0)
        d = np.maximum(d, -np.maximum(in_xwall, u))
        # USB opening in the +y wall: a U 13 mm wide, 8 mm deep from the top.
        in_ywall = np.abs(Y - (TY - WALL / 2)) - WALL
        uu = rrect(X, z - (top - 8.0 + 50.0), 6.5, 50.0, 3.0)
        d = np.maximum(d, -np.maximum(in_ywall, uu))
        # Engravings on the top of the plate.
        if PLATE - 0.45 < z < PLATE + 0.6:
            d = np.where(groove & (O < -0.1), np.maximum(d, (PLATE - 0.45) - z), d)
        if PLATE - 0.4 < z < PLATE + 0.6:
            d = np.where(arrow, np.maximum(d, (PLATE - 0.4) - z), d)
        field[:, :, k] = d
    return field, (xs, ys, zs)


# ---------------------------------------------------------------- saddle

def saddle_field(rng):
    top_x, r = arch_top(np.array([HALF_L]))
    xs, ys, zs = grid(float(top_x[0]) + 0.5)
    X, Y = np.meshgrid(xs, ys, indexing="ij")
    O = outline(X, Y)
    Z_TOP, _ = arch_top(X)
    Z_TOP = Z_TOP + STRAP_THICK                      # the channel sits on top of the minimum thickness
    V = voronoi_edges(X, Y, 6.0, 1.8, rng, (HALF_L, MID_HY))
    bars = V - 0.75                                  # bars about 1.5 mm wide
    rim = O + 2.4                                    # > 0 within 2.4 mm of the edge: the solid rim
    walls = np.abs(np.abs(Y) - CH_HY - 1.2) - 1.2   # solid strips along both sides of the channel (glue lines)
    lattice = smin(smin(bars, -rim, 0.8), walls, 0.8)
    lattice = np.maximum(lattice, O)                 # stay inside the outline
    field = np.empty((len(xs), len(ys), len(zs)), dtype=np.float32)
    for k, z in enumerate(zs):
        d = rounded_and(lattice + 0.6, (z - Z_TOP) + 0.6, 0.6)   # rounded skin-side edges of the bars
        outer = rounded_and(O + 2.0, (z - Z_TOP) + 2.0, 2.0)    # rounded outer skin-side edge
        d = np.maximum(d, outer)
        d = np.maximum(d, -z)                                     # flat face on the bed
        # Strap channel in the flat face, the full length: the band runs between saddle and base.
        ch = np.maximum(np.abs(Y) - CH_HY, z - STRAP_THICK)
        d = np.maximum(d, -ch)
        field[:, :, k] = d
    return field, (xs, ys, zs), r


# ---------------------------------------------------------------- mesh

def mesh(field, axes, path, target_faces=180_000):
    from skimage.measure import marching_cubes
    import trimesh
    xs, ys, zs = axes
    v, f, _, _ = marching_cubes(field, 0.0, spacing=(RES, RES, RES))
    v += np.array([xs[0], ys[0], zs[0]])
    t = trimesh.Trimesh(v, f[:, ::-1], process=True)
    bodies = t.split(only_watertight=False)
    if len(bodies) > 1:   # drop specks: anything far smaller than the part itself
        keep = [b for b in bodies if len(b.faces) > 0.002 * len(t.faces)]
        t = trimesh.util.concatenate(keep)
    print(f"  marching cubes: {len(t.faces)} faces, watertight={t.is_watertight}, bodies kept {len(t.split(only_watertight=False))} of {len(bodies)}")
    if len(t.faces) > target_faces:
        import fast_simplification
        vv, ff = fast_simplification.simplify(t.vertices, t.faces, 1 - target_faces / len(t.faces))
        small = trimesh.Trimesh(vv, ff, process=True)
        if small.is_watertight:   # a slicer needs a closed mesh: keep the full one otherwise
            t = small
    t.fix_normals()
    t.export(path)
    return t


def preview(t, path, views, title=""):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection
    light = np.array([0.35, -0.55, 0.75]); light /= np.linalg.norm(light)
    sh = np.clip(t.face_normals @ light, 0, 1) * 0.65 + 0.3
    cols = np.c_[0.60 * sh, 0.70 * sh, 0.92 * sh, np.ones(len(sh))]
    fig = plt.figure(figsize=(6 * len(views), 4.6))
    for k, (el, az) in enumerate(views):
        ax = fig.add_subplot(1, len(views), k + 1, projection="3d")
        ax.add_collection3d(Poly3DCollection(t.triangles, facecolors=cols, edgecolors="none"))
        c = (t.bounds[0] + t.bounds[1]) / 2
        L = (t.bounds[1] - t.bounds[0]).max() / 2
        ax.set_xlim(c[0] - L, c[0] + L); ax.set_ylim(c[1] - L, c[1] + L); ax.set_zlim(0, L)
        ax.set_box_aspect((2, 2, 1)); ax.view_init(elev=el, azim=az); ax.set_axis_off()
    if title:
        fig.suptitle(title, fontsize=10)
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    plt.close(fig)


if __name__ == "__main__":
    out_dir = Path(sys.argv[sys.argv.index("--out") + 1]) if "--out" in sys.argv else HERE
    rng = np.random.default_rng(SEED)
    fb, ab = base_field(rng)
    tb = mesh(fb, ab, out_dir / "capsule_base.stl")
    e = tb.bounds[1] - tb.bounds[0]
    print(f"capsule_base.stl: {e[0]:.1f} x {e[1]:.1f} x {e[2]:.1f} mm, {len(tb.faces)} faces, watertight={tb.is_watertight}")
    fs, as_, r = saddle_field(np.random.default_rng(SEED + 1))
    ts = mesh(fs, as_, out_dir / "capsule_saddle.stl")
    e = ts.bounds[1] - ts.bounds[0]
    print(f"capsule_saddle.stl: {e[0]:.1f} x {e[1]:.1f} x {e[2]:.1f} mm, {len(ts.faces)} faces, watertight={ts.is_watertight}, hand arc radius {r:.0f} mm")
    if "--show" in sys.argv:
        preview(tb, out_dir / "preview_base.png", [(38, -58), (90, -90)])
        preview(ts, out_dir / "preview_saddle.png", [(45, -58), (12, -90)])
