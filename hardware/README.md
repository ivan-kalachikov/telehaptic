# Printed capsule base

`capsule_base.stl`: one plate that holds both magnetometers at a fixed distance (53 mm centre to centre by default) and in the same orientation, a tray for the battery, the multiplexer and the XIAO between them, and two slots for a 25 mm elastic strap.

- Print flat side down, no supports. PLA or PETG, 0.2 mm layers, 3 walls, 20% infill.
- Each sensor pocket is four corner stops, open on all sides, so the wires can leave on whichever edge the module has its pins. Put the module on 1 mm foam tape: the solder of through-hole pins sticks out underneath. The arrow engraved in each pocket points to the index-finger side (sensor 1); lay both GY-271 modules so their X arrows point the same way.
- The tray leaves 3.5 mm on each long side for solder joints and bent wires (the pins of the multiplexer and the XIAO face those walls) and room for 2 mm foam tape between the stacked boards.
- Sizes come from typical seller listings. **Measure your parts** and change `PARTS` and `SENSOR_DISTANCE` in `make_case.py`, then:

```
pip install manifold3d trimesh
python hardware/make_case.py
```
