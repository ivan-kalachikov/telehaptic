# Build day

Order: check the parts with a multimeter, assemble, check the power, flash, connect the app, record the first series. Each failure has its own section below.

`telehaptic.uf2` and `telehaptic.apk` are in the release [latest](https://github.com/ivan-kalachikov/telehaptic/releases/tag/latest).

## 1. Before soldering: each part on its own

Multimeter on resistance. Parts unpowered.

| What | Where | Expected | If not |
|---|---|---|---|
| No short in a GY-271 module | VCC to GND | hundreds of Ω or more, not zero | faulty module, use the spare |
| Pull-ups on the GY-271 | SDA to VCC and SCL to VCC | a few kΩ; sources mention 2.2 and 4.7 kΩ | open (OL): no pull-ups; add 4.7 kΩ from SDA and from SCL to 3.3 V on that channel |
| Pull-up on the multiplexer RST | RST to VIN | about 10 kΩ | open: no pull-up; wire RST to VIN |
| Multiplexer address | pads A0, A1, A2 | not bridged (address 0x70) | — |
| Battery | red to black wire | 3.7–4.2 V | below 3.5 V: charge it from the XIAO over USB first |
| Switch | in the battery plus line | on: 0 Ω, off: open | — |

Take a photo of the chip marking on each GY-271. If the firmware identifies an unexpected chip, the photo helps.

## 2. After soldering, no battery

| Check | Expected |
|---|---|
| 3V3 to GND on the XIAO | not zero (a few Ω or less is a short: do not power) |
| D4 → multiplexer SDA, D5 → SCL | 0 Ω |
| SD0/SC0 → SDA/SCL of sensor 1 (index side), SD1/SC1 → sensor 2 (little finger side) | 0 Ω, SDA not swapped with SCL |
| D2 → motor SIG | 0 Ω |
| 3V3 → multiplexer VIN, VCC of both GY-271, motor VCC; all GND together | 0 Ω |

Both magnetometers must have the same axis orientation: the X/Y arrows on the boards point the same way.

The distance between the sensors is from the centre of one chip to the centre of the other, in mm. The app asks for it.

## 3. First power-up over USB, battery disconnected

Connect the XIAO by cable to a computer or a charger.

| Where (to GND) | Expected |
|---|---|
| 3V3 on the XIAO | ≈ 3.3 V |
| multiplexer VIN, sensors' VCC, motor VCC | ≈ 3.3 V |
| SDA and SCL (D4, D5) idle | ≈ 3.3 V. About 0 V: a short or swapped wires |
| SD0, SC0, SD1, SC1 idle | 3.0–3.3 V. About 0 V: no pull-ups or a short on that channel |

If a GY-271 module has its own regulator, its output will be a bit below 3.3 V. The QMC5883P works from 2.5 V. The module schematic was not found, so measure this rather than assume it.

## 4. Flashing

1. On a computer, download `telehaptic.uf2` from the release.
2. Connect the XIAO with a USB-C data cable (not a charge-only one).
3. Double-tap the tiny reset button quickly. The `XIAO-SENSE` drive appears.
4. Copy `telehaptic.uf2` onto it. The drive disappears by itself: the board restarted with the new firmware.

If the drive does not appear:
- tap faster, two taps within half a second;
- try another cable.

**Check without the phone, optional.** Open the XIAO USB port in any serial terminal at 115200, for example `screen /dev/ttyACM0 115200` on Linux. Expected lines:

```
FW 0.1.<build number>
BOOT reason=power_on
SCAN begin mux=yes
SCAN ch0 0x2C QMC5883P mag1
SCAN ch1 0x2C QMC5883P mag2
SCAN ch2 EMPTY HINT_PULLUPS
SCAN end mags=2
IMU ok LSM6DS3TR-C
CFG rate=200 range=30 osr=8/8 n=2 imu=1 mag1=QMC5883P@ch0,1000 mag2=QMC5883P@ch1,1000
LIVE mag1_uT=… mag2_uT=… bat_mv=…
```

`SCAN ch2 EMPTY` is normal: channel 2 is the spare.

If the scan ran before the terminal opened, press reset once. The scan repeats, and a single press does not enter the flashing mode.

## 5. The phone

1. On the phone, open the release page, download `telehaptic.apk` and allow installing from this source.
2. Open the app and tap Allow: it needs Nearby devices and notifications. Then "Find and connect the capsule".
3. The main screen should show:
   - Connected;
   - Both sensors in place;
   - an approximate battery level when the battery is connected.
4. Menu ⋮ → **Diagnostics**:
   - **Bus scan.** Multiplexer found, QMC5883P on channels 0 and 1, channel 2 empty.
   - **Field.** Without the magnet each sensor's |B| is tens of µT: the Earth field plus the sensor's own offset. Bring the magnet close: the values change, the nearer sensor more. SATURATED means the magnet is too close for the chosen range.
   - **Stream.** Measured rate about 200 Hz, loss over 10 s about 0%.
   - **Motor.** Tap "100 ms": the capsule vibrates and the field shifts during the pulse. If the capsule reconnects after a pulse and the last start reason changed, the motor pulls the supply down. Tell me.
5. **Battery.** Connect the battery, switch it on, unplug USB. Compare the battery voltage on the multimeter with Battery in Diagnostics. Send me any difference over 0.05 V: it checks the 1 MΩ / 510 kΩ divider from the schematic.
6. Menu ⋮ → **Settings**:
   - magnet and distance between the sensors;
   - GitHub token;
   - Check access.

**GitHub token:** github.com → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token.
- Repository access: Only select repositories → `ivan-kalachikov/telehaptic`.
- Permissions → Repository permissions → Contents: Read and write.

Paste it into Settings once.

## 6. If a sensor is not found

The app says what is wrong. What to do for each message:

| Message | What to check |
|---|---|
| "I2C bus is not working: SDA (SCL) is held low" | D4/D5 shorted to GND, swapped wires, a module connected the wrong way. The firmware sends nothing on the bus then, so the board does not hang |
| "No multiplexer, looking for sensors on the main bus" | Multiplexer VIN, GND, SDA, SCL. Wire RST to VIN. Pads A0–A2 must not be bridged |
| "Channel N: sensor does not answer. Check the SDN/SCN wires and the 4.7 kΩ pull-ups" | The wires of that channel: SDA to SDA, SCL to SCL; module VCC and GND; pull-ups (section 1). Swap the two modules: if the error moves, it is the module; if it stays on the channel, it is the wiring. The spare module can go on channel 2 |
| "Channel N: unknown chip at 0x…" or "HMC5883L" | Send me a photo of the chip and the scan line from Logs; I will add support |
| "Channel N: QMC5883L" | Nothing to do: the firmware works with it too, only the range is limited to ±8 G |
| "Capsule not found" | The board is powered (USB or the switch), the phone's Bluetooth is on, the capsule is close. Press reset and tap Connect again |
| "Connected, but no data" | Switch the capsule off and on. If that does not help: menu ⋮ → Logs → "Send to the repository" and tell me |

After fixing the wiring, tap "Rescan the sensors" in Diagnostics. No need to restart the board.

If Diagnostics shows "last start: restarted by software" without a fresh flash, sampling probably stopped for 2 s and the stall monitor restarted the board. Usually a loose I2C wire; check the wiring of the channel that was lost.

## 7. First series of recordings

Menu ⋮ → **Experiments**. Before each recording the app checks that the capsule is connected and both sensors are in place.

1. **Calibration.** No magnet. 10 s with the hand still while the capsule vibrates five times, then 40 s of slowly turning the hand in all directions.
2. **Keys**, "Hand on the table, on its edge". Magnet on the nail. About 4 minutes.
3. Take the capsule off and put it on again. **Keys**, "Hand on the table, on its edge", with "After putting the capsule on again".
4. **Keys**, "Arm hanging along the body".

The recording without the magnet comes later, if the first results are good.

With the token set, each recording goes to `data/` in the repository by itself, and I run the analysis.

**Rate.** 200 Hz by default: twice as many accelerometer points at the moment of touch, to see whether a firm touch gives a jolt. 100 Hz, as on the phone stand, can be set in Diagnostics → Sensor settings; it lasts until the capsule restarts.
