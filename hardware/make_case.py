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
STACK_H = 10.0           # what the multiplexer and the XIAO add above the battery, with foam tape
SENSOR_DISTANCE = 46.0   # centre to centre of the two chips; enter the same number in the app
STRAP_WIDTH = 25.0       # elastic band width; slots are made 1 mm wider
CLEAR = 0.6              # gap around each part in its pocket (total, both sides)
WALL = 1.6
PLATE = 2.0              # base plate thickness
POCKET_H = 3.0           # sensor pocket wall height above the plate
TRAY_H = None            # tray wall height above the plate: battery + STACK_H when None

out = Path(__file__).with_name("capsule_base.stl")


def box(x0, y0, z0, x1, y1, z1):
    return Manifold.cube([x1 - x0, y1 - y0, z1 - z0]).translate([x0, y0, z0])


def build():
    gx, gy = PARTS["gy271"]
    px, py = gx + CLEAR, gy + CLEAR                     # sensor pocket inside
    mx, my = PARTS["mux"]
    xx, xy = PARTS["xiao"]
    bl, bw, bh = PARTS["battery"]
    # Tray inside: the stack, long sides along y.
    tray_ix = max(my, xy, bw) + CLEAR
    tray_iy = max(mx, xx, bl) + CLEAR
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
    parts = plate
    # Sensor pockets: sensor 1 at +x (index side), sensor 2 at -x.
    for s in (-1, 1):
        cx = s * SENSOR_DISTANCE / 2
        outer = box(cx - px / 2 - WALL, -py / 2 - WALL, PLATE, cx + px / 2 + WALL, py / 2 + WALL, PLATE + POCKET_H)
        inner = box(cx - px / 2, -py / 2, PLATE - 0.01, cx + px / 2, py / 2, PLATE + POCKET_H + 1)
        # Notch towards the tray for the wires.
        notch_x = cx - s * (px / 2 + WALL / 2)
        notch = box(notch_x - WALL, -4, PLATE + 0.8, notch_x + WALL, 4, PLATE + POCKET_H + 1)
        parts = parts + (outer - inner - notch)
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
