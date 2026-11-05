# Recordings

The app puts recordings here with "Send to the repository"; they can also be copied by hand. One recording is one zip:

| File | Contents |
|---|---|
| `samples.csv` | `idx, t_xiao, t_ns`, raw magnetometer values `m1x..m2z`, gyroscope `gx..gz`, accelerometer `ax..az`, flags |
| `events.csv` | cues and keys: `announce`, `press`, `release`, `tap`, `motor_cmd`, … on the phone clock |
| `packets.csv` | receive time of every packet, to refit the clock and count losses |
| `sync.csv` | pings: round trip time |
| `info.csv` | capsule text lines: scan, settings, battery, motor |
| `meta.json` | kind, posture, re-wearing, magnet, distance between the sensors, chips, range, rate, versions |

Names:
- `CAL_…`: calibration.
- `KEYS_table_…` / `KEYS_down_…`: keys with the hand on the table / the arm along the body.
- `_rewear`: after putting the capsule on again.
- `NOMAG_…`: without the magnet.
- `TRAIN_…` / `RETRAIN_…`: training and retraining in the app.
- `_ABORTED`: stopped recordings; the analysis skips them.

Analysis: `pip install -r analysis/requirements.txt`, then `python analysis/analyze.py data` → `report/report.md`.

Testing the analysis without real data: `python tools/make_fake_data.py /tmp/fake && python analysis/analyze.py /tmp/fake`. Those recordings are synthetic; never put them into `data/`.

## Log

| File | Notes |
|---|---|
