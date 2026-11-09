"""
Printed base for the capsule: one rigid plate that holds both magnetometers at a fixed distance
and in the same orientation, a tray for the electronics between them, and slots for the strap.

    python hardware/make_case.py            -> hardware/capsule_base.stl
    python hardware/make_case.py --show     -> also a preview PNG

All sizes in mm. MEASURE YOUR PARTS and change the numbers in PARTS below: module sizes differ
between sellers (GY-271 listings give 13.2 x 14.4, 14.8 x 13.5 and 13.5 x 18 mm).
Axes: x across the back of the hand (+x = index finger side, sensor 1), y along the fingers
(+y = towards the knuckles), z away from the skin.
Needs: pip install manifold3d trimesh
"""
import sys
from pathlib import Path

import numpy as np
from manifold3d import Manifold, Mesh

PARTS = {
    "gy271": (14.8, 13.5),       # magnetometer module, x by y as it lies in its pocket
    "xiao": (21.0, 17.8),        # XIAO nRF52840 Sense: length (USB end to the other end), width
    "mux": (30.5, 21.0),         # TCA9548A module: length, width (purple generic boards are about this; measure)
    "battery": (30.0, 20.0, 6.0),  # LiPo 320 mAh: length, width, thickness (measure)
}
# In the tray the parts lie in a stack, long side along the fingers (y): battery at the bottom,
# the multiplexer on it, the XIAO on top with its USB socket towards the knuckles (+y).
STACK_H = 13.0           # what the multiplexer and the XIAO add above the battery: boards, parts on them,
                         # 2 mm foam tape between the layers so the solder joints and wires under the XIAO fit
WIRE_SIDE = 3.5          # room on each long side of the tray for solder joints and bent wires (the pins of the
                         # multiplexer and of the XIAO are along their long edges, which face these walls)
WIRE_END = 1.5           # room at each short end of the tray
SENSOR_DISTANCE = 53.0   # centre to centre of the two chips; enter the same number in the app
# The back of the hand is convex across the knuckles, so a separate saddle goes under the flat base:
# its underside is an arc that follows the hand. Measure it: hand relaxed, lay a straight ruler across
# the back of the hand where the capsule will sit, touching the highest point; measure the gap under
# the ruler 40 mm to the left and to the right of that point and enter the average here.
ARCH_DROP_AT_40 = 5.0    # mm; 5 is a placeholder, not a measurement
SADDLE_MIN = 1.6         # saddle thickness at its thinnest (the middle)
STRAP_WIDTH = 25.0       # elastic band width; slots are made 1 mm wider
CLEAR = 0.6              # gap around each part in its pocket (total, both sides)
WALL = 1.6
PLATE = 2.0              # base plate thickness
POCKET_H = 3.0           # sensor pocket wall height above the plate
TRAY_H = None            # tray wall height above the plate: battery + STACK_H when None

out = Path(__file__).with_name("capsule_base.stl")
out_saddle = Path(__file__).with_name("capsule_saddle.stl")


def box(x0, y0, z0, x1, y1, z1):
    return Manifold.cube([x1 - x0, y1 - y0, z1 - z0]).translate([x0, y0, z0])


def build():
    gx, gy = PARTS["gy271"]
    px, py = gx + CLEAR, gy + CLEAR                     # sensor pocket inside
    mx, my = PARTS["mux"]
    xx, xy = PARTS["xiao"]
    bl, bw, bh = PARTS["battery"]
    # Tray inside: the stack, long sides along y.
    tray_ix = max(my, xy, bw) + CLEAR + 2 * WIRE_SIDE
    tray_iy = max(mx, xx, bl) + CLEAR + 2 * WIRE_END
    half_gap = SENSOR_DISTANCE / 2 - px / 2 - WALL      # inner edge of a sensor pocket wall
    if tray_ix / 2 + WALL > half_gap - 0.5:
        raise SystemExit(f"Tray ({tray_ix:.1f} mm inside) does not fit between the sensors: "
                         f"raise SENSOR_DISTANCE to at least {tray_ix + 2 * WALL + px + 2 * WALL + 1:.0f} mm "
                         "or use a smaller part.")
    width = max(tray_iy + 2 * WALL, STRAP_WIDTH + 1 + 2 * 3.0)
    slot_w = 3.5
    end = SENSOR_DISTANCE / 2 + px / 2 + WALL + 1.5 + slot_w + 2.5   # half length of the plate
    plate = box(-end, -width / 2, 0, end, width / 2, PLATE)
    # Strap slots at both ends.
    for s in (-1, 1):
        cx = s * (end - 2.5 - slot_w / 2)
        plate -= box(cx - slot_w / 2, -(STRAP_WIDTH + 1) / 2, -1, cx + slot_w / 2, (STRAP_WIDTH + 1) / 2, PLATE + 1)
    global FOOTPRINT
    FOOTPRINT = (end, width, slot_w, [s * (end - 2.5 - slot_w / 2) for s in (-1, 1)])
    parts = plate
    # Sensor pockets: sensor 1 at +x (index side), sensor 2 at -x. Four L-shaped corner stops hold
    # the module in place and in its orientation; the sides stay open, so the wires can leave on
    # whichever edge the module's pins are.
    leg = 4.0
    for s in (-1, 1):
        cx = s * SENSOR_DISTANCE / 2
        for sx in (-1, 1):
            for sy in (-1, 1):
                x_in, y_in = cx + sx * px / 2, sy * py / 2          # inner corner
                x_out, y_out = x_in + sx * WALL, y_in + sy * WALL
                a = box(min(x_in - sx * leg, x_out), min(y_in, y_out), PLATE, max(x_in - sx * leg, x_out), max(y_in, y_out), PLATE + POCKET_H)
                b = box(min(x_in, x_out), min(y_in - sy * leg, y_out), PLATE, max(x_in, x_out), max(y_in - sy * leg, y_out), PLATE + POCKET_H)
                parts = parts + a + b
        # Orientation mark: an arrow engraved in the pocket floor along +x, the same for both sensors.
        ax = box(cx - 4, -0.6, PLATE - 0.4, cx + 2, 0.6, PLATE + 0.1)
        head = Manifold.extrude(_tri(cx + 2, 0.0, 2.5), 0.5).translate([0, 0, PLATE - 0.4])
        parts = parts - ax - head
    # Tray between the sensors.
    tx, ty = tray_ix / 2, tray_iy / 2
    h = TRAY_H if TRAY_H else bh + STACK_H
    tray = box(-tx - WALL, -ty - WALL, PLATE, tx + WALL, ty + WALL, PLATE + h)
    tray -= box(-tx, -ty, PLATE - 0.01, tx, ty, PLATE + h + 1)
    # Wire openings to the sensors (both x walls), USB opening open from the top (+y wall).
    for s in (-1, 1):
        tray -= box(s * (tx + WALL / 2) - WALL, -5, PLATE + 2.0, s * (tx + WALL / 2) + WALL, 5, PLATE + h + 1)
    tray -= box(-6.5, ty - 0.5, PLATE + h - 8.0, 6.5, ty + WALL + 0.5, PLATE + h + 1)
    parts = parts + tray
    return parts


def build_saddle():
    """Flat on top (glued under the base), arc underneath. Returned upside down: the flat face on the
    print bed, so the arc needs no supports."""
    end, width, slot_w, slots = FOOTPRINT
    d = max(ARCH_DROP_AT_40, 0.1)
    r = (40.0 ** 2 + d ** 2) / (2 * d)                 # radius of the hand's arc from the measured drop
    drop_end = r - (r * r - end * end) ** 0.5          # how far the hand falls away at the saddle's ends
    h = SADDLE_MIN + drop_end
    block = box(-end, -width / 2, 0, end, width / 2, h)
    # Hand as a cylinder along y: its top touches the saddle's middle at height h - SADDLE_MIN... measured
    # from the flat face. Built with the flat face at z = h (top) first, then flipped.
    hand = Manifold.cylinder(width + 2, r, r, 256).rotate([90, 0, 0]).translate([0, (width + 2) / 2, -r + (h - SADDLE_MIN)])
    saddle = block - hand
    for cx in slots:
        saddle -= box(cx - slot_w / 2, -(STRAP_WIDTH + 1) / 2, -1, cx + slot_w / 2, (STRAP_WIDTH + 1) / 2, h + 1)
    # Flip: flat face down for printing.
    return saddle.mirror([0, 0, 1]).translate([0, 0, h]), r, drop_end


def _tri(x, y, s):
    from manifold3d import CrossSection
    return CrossSection([[(x, y - s / 2), (x + s, y), (x, y + s / 2)]])


def save(m, path):
    mesh = m.to_mesh()
    import trimesh
    t = trimesh.Trimesh(vertices=np.asarray(mesh.vert_properties)[:, :3], faces=np.asarray(mesh.tri_verts))
    t.export(path)
    return t


if __name__ == "__main__":
    m = build()
    t = save(m, out)
    ext = t.bounds[1] - t.bounds[0]
    print(f"{out.name}: {ext[0]:.1f} x {ext[1]:.1f} x {ext[2]:.1f} mm, watertight={t.is_watertight}, volume {t.volume / 1000:.1f} cm3")
    sd, r, drop_end = build_saddle()
    ts = save(sd, out_saddle)
    e2 = ts.bounds[1] - ts.bounds[0]
    print(f"{out_saddle.name}: {e2[0]:.1f} x {e2[1]:.1f} x {e2[2]:.1f} mm, watertight={ts.is_watertight}; "
          f"hand arc radius {r:.0f} mm, {drop_end:.1f} mm lower at the ends than in the middle")
    if "--show" in sys.argv:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from mpl_toolkits.mplot3d.art3d import Poly3DCollection
        fig = plt.figure(figsize=(9, 5))
        ax = fig.add_subplot(111, projection="3d")
        ax.add_collection3d(Poly3DCollection(t.triangles, facecolor="#9fb3e6", edgecolor="#2c56c9", linewidths=0.08))
        lim = ext.max() / 2
        c = (t.bounds[0] + t.bounds[1]) / 2
        ax.set_xlim(c[0] - lim, c[0] + lim); ax.set_ylim(c[1] - lim, c[1] + lim); ax.set_zlim(0, 2 * lim)
        ax.view_init(elev=35, azim=-60)
        fig.savefig(out.with_suffix(".png"), dpi=130)
