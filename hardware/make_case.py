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
    "motor": (21.0, 1.6),          # Grove Vibration Motor board: width (measured 21), PCB thickness
    "switch": (11.0, 6.0, 13.0),   # slide switch: body length without the ears, body depth, lever tip to pin tips (measured)
}
# Motor board: stands upright in a holder on the outside of the tray wall facing the wrist (-y),
# centred between the sensors, motor outwards; 2 mm behind it for the solder joints, its wires go
# into the tray through a notch at the top of that wall. Switch: inside the tray in the wrist-end
# corner, standing upright (body length along z, mounting ears cut off), against the -x wall (little
# finger side); its lever comes out through a window in that wall and slides up and down.
MOTOR_GAP = 2.0         # between the board and the wall, for solder joints
MOTOR_FLOOR = 0.8       # the board stands this high above the bottom, in a groove in the plate
SWITCH_TRAVEL = 3.0     # lever travel; the window is lever + travel + clearance long

# The multiplexer lies on the tray floor, the XIAO on it (2 mm foam tape between), long sides along
# the fingers (y), the USB socket towards the knuckles (+y). The flat battery (3 mm) lies on top of
# the tray like a lid: it is as wide as the tray, so the capsule stays narrow across the hand.
STACK_H = 11.0          # tray height: multiplexer + foam + XIAO with its USB socket, and the wires
WIRE_SIDE = 2.75        # room on each long side of the tray for solder joints and bent wires
WIRE_END = 1.5          # room at each short end of the tray
SENSOR_DISTANCE = 50.0  # centre to centre of the two chips; enter the same number in the app
STRAP_WIDTH = 20.0      # elastic band width: it runs in a channel between the base and the saddle
STRAP_THICK = 1.6       # channel depth (an elastic band is about 1-1.5 mm thick)
# The two parts are held by four printed screws with a cylindrical slotted head (flat screwdriver):
# from the skin side through the saddle into a threaded boss of the base; the head sits flush in a
# counterbore of the saddle. Coarse 45-degree thread so it prints without supports.
SCREW_XY = [(sx * 18.5, sy * 16.5) for sx in (-1, 1) for sy in (-1, 1)]   # beside the pockets, clear of the tray and the strap channel
SCREW_D = 6.0           # thread major diameter
SCREW_P = 1.5           # pitch
SCREW_DEPTH = 0.65      # thread depth (45-degree flanks)
THREAD_CLEAR = 0.2      # radial clearance of the thread in the base
HEAD_D, HEAD_H = 9.5, 2.2   # cylindrical head
SLOT_W, SLOT_DEPTH = 1.6, 1.1
BOSS_TOP = 8.0          # top of the threaded boss above the base bottom
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


def shared_lattice(X, Y):
    """The same Voronoi cells for the base and the saddle, so their bars line up when glued and you
    can see through both."""
    return voronoi_edges(X, Y, 6.0, 1.8, np.random.default_rng(SEED + 1), (HALF_L, MID_HY + 6.0))


def wall_pattern(u, z, rng_seed=SEED + 2):
    """Voronoi cell walls in the plane of a tray wall (u along the wall, z up)."""
    rng = np.random.default_rng(rng_seed)
    gu = np.arange(-60, 60, 4.2)
    gz = np.arange(-4, 30, 3.8)
    su, sz = np.meshgrid(gu, gz)
    seeds = np.c_[su.ravel(), sz.ravel()] + rng.uniform(-1.3, 1.3, (su.size, 2))
    d = np.sqrt((u[..., None] - seeds[:, 0]) ** 2 + (z - seeds[:, 1]) ** 2)
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
MB_W, MB_T = PARTS["motor"]
SW_L, SW_D, SW_H = PARTS["switch"]
PCB_SLOT = MB_T + 0.5
LIP = 1.2
HOLDER_D = MOTOR_GAP + PCB_SLOT + LIP           # how far the holder sticks out of the wrist-side wall
if SW_L > TRAY_H + 0.2:
    print(f"note: the switch ({SW_L} mm long) stands taller than the tray ({TRAY_H} mm)")
if SW_D + 0.4 > TRAY_IY - PARTS["mux"][0]:
    print(f"note: the switch ({SW_D} mm deep) does not fit beside the multiplexer ({TRAY_IY - PARTS['mux'][0]:.1f} mm free)")
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
    o = np.maximum(np.abs(y) - hy, ax - HALF_L)
    for px, py in SCREW_XY:                              # rounded lobes around the screws
        o = smin(o, np.hypot(x - px, y - py) - (HEAD_D / 2 + 1.6), 4.0)
    return o


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
    ys = np.arange(-MID_HY - pad - HOLDER_D, MID_HY + pad, RES) + o
    zs = np.arange(-pad, zmax + pad, RES) + o
    return xs, ys, zs


# ---------------------------------------------------------------- base

def base_field(rng):
    xs, ys, zs = grid(PLATE + TRAY_H)
    X, Y = np.meshgrid(xs, ys, indexing="ij")
    O = outline(X, Y)
    V = shared_lattice(X, Y)
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
    # The plate is an open Voronoi lattice like the saddle. Solid where it must be: a rim along the
    # edge, a band under the tray walls, around the corner stops, under the arrows, the motor holder.
    keep = V - 0.75
    keep = smin(keep, -(O + 2.2), 0.6)                                   # rim
    keep = smin(keep, np.abs(T) - 2.4, 0.6)                              # under the tray walls
    keep = smin(keep, stops - 1.2, 0.6)
    for s in (-1, 1):
        keep = smin(keep, rrect(X - s * SENSOR_DISTANCE / 2, Y, 5.0, 1.8, 1.0), 0.6)   # arrow strip
    keep = smin(keep, rrect(X, Y + TY + HOLDER_D / 2, MB_W / 2 + 4.0, HOLDER_D / 2 + 2.5, 1.5), 0.6)
    SB = np.full_like(X, 1e3)                        # screw bosses
    for px, py in SCREW_XY:
        keep = smin(keep, np.hypot(X - px, Y - py) - (SCREW_D / 2 + 2.6), 0.6)
        SB = np.minimum(SB, np.hypot(X - px, Y - py) - (SCREW_D / 2 + 2.2))
    field = np.empty((len(xs), len(ys), len(zs)), dtype=np.float32)
    top = PLATE + TRAY_H
    on_xwall = (np.abs(X) > TX - WALL - 0.1) & (np.abs(Y) < TY - 4.5)
    on_ywall = (np.abs(Y) > TY - WALL - 0.1) & (np.abs(X) < TX - 4.5)
    for k, z in enumerate(zs):
        plate = rounded_and(O + 1.2, (z - PLATE) + 1.2, 1.2)
        tray = rounded_and(T + 0.9, (z - top) + 0.9, 0.9)
        st = rounded_and(stops + 0.5, (z - (PLATE + STOP_H)) + 0.5, 0.5)
        d = smin(plate, tray, 2.5)
        d = smin(d, st, 0.8)
        boss = rounded_and(SB + 0.8, (z - BOSS_TOP) + 0.8, 0.8)
        d = smin(d, boss, 1.5)
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
        # Motor holder: a shelf under it and two posts with grooves for the board's edges.
        yw = -TY                                       # outer face of the wrist-side wall
        y_slot0, y_slot1 = yw - MOTOR_GAP - PCB_SLOT, yw - MOTOR_GAP
        y_out = y_slot0 - LIP
        ex = MB_W / 2 + 0.3                            # board edge (with clearance)
        post_hx = 1.6 + 1.2                            # post: 1.2 mm engaged + 1.6 mm outside the edge
        shelf = rounded_and(rrect(X, Y - (y_out + yw) / 2 - 0.5, ex + 1.6, (yw - y_out) / 2 + 0.5, 1.2) + 1.0, (z - PLATE) + 1.0, 1.0)
        posts = np.minimum(*(rrect(X - sx * (ex - 1.2 + post_hx / 2), Y - (y_out + yw) / 2 - 0.3, post_hx / 2, (yw - y_out) / 2 + 0.3, 0.7) for sx in (-1, 1)))
        posts = rounded_and(posts + 0.6, (z - top) + 0.6, 0.6)
        d = smin(d, np.maximum(smin(shelf, posts, 1.0), -z), 1.2)
        groove_mb = np.maximum(np.maximum(np.abs(X) - ex, np.maximum(Y - y_slot1, y_slot0 - Y)), MOTOR_FLOOR - z)
        d = np.maximum(d, -groove_mb)
        # Notch at the top of the wrist-side wall for the motor wires.
        in_wwall = np.abs(Y - (-TY + WALL / 2)) - WALL
        wn = rrect(X, z - (top - 5.0 + 50.0), 4.0, 50.0, 2.0)
        d = np.maximum(d, -np.maximum(in_wwall, wn))
        # Switch: vertical window in the -x wall at the wrist end for the lever.
        in_sx = np.abs(X + (TX - WALL / 2)) - WALL
        y_sw = -TY + WALL + 0.4 + SW_D / 2             # switch centre along y
        z_sw = PLATE + TRAY_H / 2
        win = rrect(Y - y_sw, z - z_sw, 1.6, (2.0 + SWITCH_TRAVEL) / 2 + 0.6, 0.8)
        d = np.maximum(d, -np.maximum(in_sx, win))
        # Lattice holes through the plate.
        if z < PLATE + 1.0:
            d = np.maximum(d, keep)                  # keep > 0 inside a cell: an opening

        # Voronoi relief, 0.6 mm deep, on the outside of the tray walls.
        if PLATE + 1.4 < z < top - 1.4:
            near_out = (T > -0.6) & (T < 0.3)
            Wp = np.where(on_xwall, wall_pattern(Y, z), wall_pattern(X, z, SEED + 3))
            relief = near_out & (on_xwall | on_ywall) & (Wp < 0.35)
            d = np.where(relief, np.maximum(d, T + 0.6), d)
        # Engravings on the top of the plate.
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
    V = shared_lattice(X, Y)
    bars = V - 0.75                                  # bars about 1.5 mm wide
    rim = O + 2.4                                    # > 0 within 2.4 mm of the edge: the solid rim
    walls = np.abs(np.abs(Y) - CH_HY - 1.2) - 1.2   # solid strips along both sides of the channel (glue lines)
    lattice = smin(smin(bars, -rim, 0.8), walls, 0.8)
    RS = np.full_like(X, 1e3)                        # distance to the nearest screw axis
    for px, py in SCREW_XY:
        lattice = smin(lattice, np.hypot(X - px, Y - py) - (HEAD_D / 2 + 1.4), 0.6)   # solid around each screw
        RS = np.minimum(RS, np.hypot(X - px, Y - py))
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
        d = np.maximum(d, -(RS - (SCREW_D / 2 + 0.35)))          # clearance hole
        cb = np.maximum(RS - (HEAD_D / 2 + 0.3), head_floor() - z)
        d = np.maximum(d, -cb)                                    # counterbore: the head sits flush
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


def thread(r_major, depth, pitch, length, clearance=0.0, segments=96):
    """A threaded rod as a real helix: a cross-section r(angle) extruded with a twist of one turn per
    pitch. 45-degree flanks, small flats at crest and root."""
    from manifold3d import Manifold, CrossSection
    t = np.linspace(0, 1, segments, endpoint=False)
    u = t                                                 # position within the pitch at z = 0
    flank = depth / pitch                                 # 45 degrees: flank length along z = depth
    c = np.abs(u - 0.5)
    f = np.clip((0.5 - c - 0.05) / flank, 0, 1)            # 0 at the root, 1 at the crest
    r = r_major - depth + clearance + depth * f
    pts = np.c_[r * np.cos(2 * np.pi * t), r * np.sin(2 * np.pi * t)]
    turns = length / pitch
    return Manifold.extrude(CrossSection([pts.tolist()]), length, int(turns * segments / 2) + 1, -360.0 * turns)


def to_trimesh(m):
    import trimesh
    mesh = m.to_mesh()
    return trimesh.Trimesh(np.asarray(mesh.vert_properties)[:, :3], np.asarray(mesh.tri_verts))


def to_manifold(t):
    from manifold3d import Manifold, Mesh
    return Manifold(Mesh(vert_properties=np.asarray(t.vertices, dtype=np.float32), tri_verts=np.asarray(t.faces, dtype=np.uint32)))


def head_floor():
    """Flat floor of the head pocket. The skin is curved and lowest at the pocket's inner edge, so
    the head sinks until its top is 0.2 mm under that edge: never proud, and it cannot rock."""
    x_in = abs(SCREW_XY[0][0]) - (HEAD_D / 2 + 0.3)
    zt, _ = arch_top(np.array([x_in]))
    return float(zt[0]) + STRAP_THICK - HEAD_H - 0.2


def screw_length():
    zt, _ = arch_top(np.array([abs(SCREW_XY[0][0])]))
    saddle = float(zt[0]) + STRAP_THICK                    # saddle thickness at the screws
    return head_floor() + BOSS_TOP - 0.6, saddle   # stops 0.6 mm short of the boss top


def build_screws():
    """Four screws, printed standing on their heads (slot on the bed face: a 1.6 mm bridge)."""
    from manifold3d import Manifold
    L, _ = screw_length()
    head = Manifold.cylinder(HEAD_H, HEAD_D / 2, HEAD_D / 2, 96)
    slot = Manifold.cube([HEAD_D + 2, SLOT_W, SLOT_DEPTH]).translate([-(HEAD_D + 2) / 2, -SLOT_W / 2, -0.01])
    rod = thread(SCREW_D / 2, SCREW_DEPTH, SCREW_P, L).translate([0, 0, HEAD_H - 0.01])
    tip = Manifold.cylinder(1.0, SCREW_D / 2, SCREW_D / 2 - SCREW_DEPTH - 0.2, 96).translate([0, 0, HEAD_H + L - 1.0])
    cut = Manifold.cylinder(1.0, SCREW_D, SCREW_D, 8).translate([0, 0, HEAD_H + L - 1.0]) - tip   # chamfer the tip
    one = head - slot + rod - cut
    gap = HEAD_D + 3
    return (one + one.translate([gap, 0, 0]) + one.translate([0, gap, 0]) + one.translate([gap, gap, 0])), L


def tap_base(t):
    """Cuts the threads into the bosses of the base."""
    from manifold3d import Manifold
    m = to_manifold(t)
    for px, py in SCREW_XY:
        rod = thread(SCREW_D / 2, SCREW_DEPTH, SCREW_P, BOSS_TOP + 2, THREAD_CLEAR).translate([px, py, -1])
        lead = Manifold.cylinder(1.2, SCREW_D / 2 + 0.6, SCREW_D / 2 - SCREW_DEPTH, 96).translate([px, py, -0.01])   # entry chamfer
        m = m - rod - lead
    return to_trimesh(m)


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
    tb = tap_base(tb)
    tb.export(out_dir / "capsule_base.stl")
    print(f"  threads cut: base watertight={tb.is_watertight}")
    sm, L = build_screws()
    tsc = to_trimesh(sm)
    tsc.export(out_dir / "capsule_screws.stl")
    e = tsc.bounds[1] - tsc.bounds[0]
    print(f"capsule_screws.stl: 4 screws M{SCREW_D:g} x {SCREW_P} pitch, thread {L:.1f} mm, set {e[0]:.1f} x {e[1]:.1f} x {e[2]:.1f} mm, watertight={tsc.is_watertight}")
    # For looking at, not for printing: the saddle turned arc-down with the base on it.
    import trimesh
    h = ts.bounds[1][2]
    s2 = ts.copy(); s2.apply_transform(trimesh.transformations.reflection_matrix([0, 0, h / 2], [0, 0, 1]))
    b2 = tb.copy(); b2.apply_translation([0, 0, h])
    asm = trimesh.util.concatenate([s2, b2])
    asm.export(out_dir / "capsule_assembly_preview.stl")
    print("capsule_assembly_preview.stl: both parts together, to look at (print the two parts separately)")
    if "--show" in sys.argv:
        preview(tb, out_dir / "preview_base.png", [(38, -58), (90, -90)])
        preview(ts, out_dir / "preview_saddle.png", [(45, -58), (12, -90)])
