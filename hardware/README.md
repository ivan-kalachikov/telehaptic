# Printed capsule base

`capsule_base.stl`: one plate that holds both magnetometers at a fixed distance (46 mm centre to centre by default) and in the same orientation, a tray for the battery, the multiplexer and the XIAO between them, and two slots for a 25 mm elastic strap.

- Print flat side down, no supports. PLA or PETG, 0.2 mm layers, 3 walls, 20% infill.
- The arrow engraved in each sensor pocket points to the index-finger side (sensor 1). Lay both GY-271 modules so their X arrows point the same way.
- Sizes come from typical seller listings. **Measure your parts** and change `PARTS` and `SENSOR_DISTANCE` in `make_case.py`, then:

```
pip install manifold3d trimesh
python hardware/make_case.py
```
