# Train Tracker — check-in & stop detection testbed

Minimal Android app to test whether train check-in and station counting work from
**accelerometer + cell identity only** — no GPS, offline, low power.

## The approach

`energy` = variance of accelerometer magnitude over a 1-second window. `|a|` is a
scalar so it is invariant to device orientation — no gravity estimation, no axis
projection, no FFT.

A moving train has a **floor** of vibration that never drops. A stopped train's floor
falls to near zero. A person handling the phone adds spikes *on top* of the floor but
cannot remove it — so the detector tracks the **rolling minimum** over 10 s, not the mean.

```
STOPPED   floor < threshold for >= 10s
MOVING    floor > threshold for >= 5s
stop event = MOVING -> STOPPED(>=15s) -> MOVING
CHECK-IN  = MOVING sustained >= 30s with no step events
```

The threshold adapts to the handset and how it's carried, learned from the observed
vibration floor. Two things it must *not* learn from: walking (≈25× more energetic than
a train — it will pin the threshold above train vibration and the whole ride reads as
stopped), and human handling. Hence the step guard and the clamp.

## Sampling rates

| Signal | Rate | Why |
|---|---|---|
| Accelerometer | 20 Hz, batched 5 s | Measuring an energy floor, not a spectrum. The sensor hub FIFO buffers, so the CPU wakes once per batch, not 20x/second. |
| Derived tick | 1 Hz | One `energy` value per second — this is what gets logged and replayed. |
| Step detector | event-driven | Hardware coprocessor. Near-free. |
| Telephony poll | 5 s | Heartbeat only. |
| Telephony push | on change | `TelephonyCallback.CellInfoListener` — the real source. |
| Fingerprint window | 60 s | Rolling set of cells seen. |

**Why push and not just polling:** in a real Chennai trace, 12 of 22 cell visits were
shorter than a 10 s poll interval — over half the fingerprint data would be missed by
polling alone. `getAllCellInfo()` also returns *cached* data on Android 10+, so polling
faster doesn't get you fresher readings. The callback fires on actual cell change, which
is exactly the event we want. The 5 s poll stays as a heartbeat for devices below API 31
or where the callback doesn't register.

File volume: ~1 line/s of ticks plus cell and event lines — roughly 400 KB for a 30-minute
ride.

## On-screen live log

Everything written to the trace file is also shown on screen as it happens: session start
(including whether accel, step detector and push registered), every serving-cell change
with its TAC and dBm, every ground-truth tap with the fingerprint size, check-in, each
numbered stop, and exit.

Where the bundled `stationmap.json` covers the leg, stops are **named**: pick your
boarding station from the dropdown and the feed shows `STOP #2 -> Little Mount`, with
`next: <station>` in the status block. Cells already in the map are annotated inline, so
you can see live whether a cell matches the survey.

That's the point of the screen — not to judge the result, but to see mid-ride whether the
detector's station guess matches the platform you're actually standing on.

## Build & install

```bash
ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Zero dependencies beyond the Android SDK — no AndroidX, nothing to download.

## Riding with it

1. Pick **FROM** and **TO** from the two dropdowns. The app derives everything else:
   direction of travel (either way along the surveyed line), how many stops the leg
   should contain, and what each one is called.
2. **START RECORDING**, then put the phone where you'd normally carry it.
3. Tap the ground-truth buttons **as things physically happen**.

Once checked in, the status line counts `ON TRAIN · 2/4` and the detail line shows
`next: Little Mount (2 to go)`. Two alerts fire:

- **`NEXT STOP: OFF`** (amber) one stop before your destination — a full inter-station run
  of warning, typically 90-180 s on metro. It fires early on purpose: a premature alert
  costs the rider nothing, a missed stop costs them a lot.
- **`ALIGHT NOW`** (red) on arrival, also written to the trace as `DETECTED_ARRIVAL` so
  you can score it against your `DOORS_OPEN` / `STEPPED_OFF` taps afterwards.

Reverse legs work — pick Guindy → Teynampet and the map is walked backwards.

Those taps are the whole point. They are the only thing that cannot be reconstructed
afterwards, and without them another trace answers nothing. Tap generously — a missed
label costs more than a mistimed one.

The live readout (state, stop count, energy floor, threshold, serving cell) is for
sanity-checking mid-ride, not for judging the result. Judge offline.

## Pull and analyse

```bash
adb pull /sdcard/Android/data/com.nammayatri.traintracker/files/traces/ ./traces/
python3 tools/replay.py traces/trace-YYYYMMDD-HHMMSS.jsonl
```

`replay.py` re-runs the detector over a recorded trace and scores it against your taps —
check-in latency, backdate error, detected vs actual stop count. Every threshold is a
flag:

```bash
python3 tools/replay.py trace.jsonl --abs-min 0.01 --min-dwell 12 --checkin-move 25
```

It replays **both layers** — the IMU detector and the cell-anchoring on top — so a trace
tells you whether check-in was cell-confirmed at your origin, where the stop count got
snapped, and whether deboard landed at the destination.

**Tune here, never on the train.** The app logs per-second `energy`; everything
downstream — floor, threshold, hysteresis, stop counting — is recomputed at replay time,
so one afternoon of riding gives a corpus you can iterate against indefinitely. Keep
`replay.py` and `SensorEngine.kt` in sync when you change the logic.

## What's implemented

- Accelerometer at 20 Hz, hardware-batched (5 s report latency) — the sensor hub buffers,
  the CPU wakes rarely
- Step detector (coprocessor, near-free) — separates walking from riding
- 1 s energy → rolling floor → adaptive threshold → MOVING/STOPPED
- Check-in detection with backdating
- Stop counting (only after check-in, so the origin platform isn't counted)
- **Alight / exit detection.** A train moves without you taking steps; you do not. That
  asymmetry separates "the train left and I'm still aboard" from "I got off and walked".
  A stop plus walking raises a provisional alight; step-free MOVING cancels it; sixty
  seconds without that cancellation confirms the exit.
- Telephony polled every 10 s and **logged** — cell id, TAC, RSRP
- **Cell-set fingerprint on every ground-truth tap.** A phone hops between bands and
  carriers while standing still, so whichever cell happens to be serving is a noisy
  marker of place. Each tap therefore records every cell seen in the preceding 60 s.
  This is what builds the station map: tap `PASSED_STATION`, and the cell set at that
  instant *is* that station's fingerprint.
- Foreground service, ground-truth taps, JSONL traces
- Offline replay + scoring

## What's not implemented yet, deliberately

- **Telephony is logged but not used for detection.** Re-anchoring the stop count against
  known station cells needs a surveyed cell map, and that map comes from the first rides.
  Collect first, then build it.
- **Alight detection.** `STEPPED_OFF` is a label only.
- **Station index / destination awareness.** Stops are counted, not named.

## Cell anchoring — how a specific leg is verified

The IMU alone is leg-agnostic: sustained motion without steps looks the same whether you
boarded at Guindy or got into an auto. So the bundled map anchors it.

- **Check-in threshold moves with the cells.** Being demonstrably at the origin station
  is the evidence that separates "my train left Guindy" from "I'm sitting in an auto", so
  it changes how much sustained motion check-in demands:

  | Cell evidence | Motion required | Logged as |
  |---|---|---|
  | At / departed the origin station | **15 s** | `cells corroborate` |
  | Map doesn't cover this operator | 30 s | `IMU only` |
  | Map covers it, origin never seen | **60 s** | `CONTRADICTED by cells` |

  It **shifts the threshold, it never gates.** A hard gate would fail outright on a SIM
  the map doesn't cover — worse than the problem it solves. So a contradicted check-in
  still fires eventually, flagged, rather than being blocked.
- **Stop count** is snapped whenever a serving cell names a station, so drift from a
  missed or spurious stop is corrected rather than propagating to the end of the line.
- **Deboard** is cross-checked against the **destination** cells and logged as
  `DETECTED_DEBOARD` with `atDestination` true/false.

**Cells correct the IMU; they never gate it.** The map is one ride on one operator — a
strict gate would simply fail on a different SIM. No cell match means the IMU carries on
alone, flagged at lower confidence, rather than detection failing outright.

## Why the map can't come from GPS

Cell IDs are operator-internal and there is no public registry. Crowdsourced databases
are built from GPS-tagged observations, so underground cells are structurally absent —
the cells that matter most are the ones nobody can have.

Measured on a real Chennai trace: of 21 serving cells, 14 could be given an approximate
position from trustworthy GPS fixes and **7 never could** — no valid fix at any point
while they were serving. Those 7 are the underground ones, and two of them peak at
−44 dBm, which is what standing directly under a platform DAS antenna looks like.

So the survey is tap-driven, not GPS-driven. That is the only method that reaches those
7 cells, and it happens to need no GPS at all.

## Measured on synthetic rides (tools/replay.py)

Not real data — a fabricated ride with known ground truth, used to check the logic before
riding. Two bugs surfaced this way rather than on a train:

| | result |
|---|---|
| stop count, clean ride | 4 / 4 exact |
| check-in latency | +43 s (detect-late by design) |
| backdate error | +8 s |
| alight error | +0 s |
| stop count, rider walks in carriage during a dwell | **3 / 4 — one stop lost** |

That last row is the real weakness, now measured rather than assumed: walking inside the
carriage keeps the vibration floor high through a station dwell, so the stop never
registers. No false exit fired, which is the important safety property — but the count
drifts, and a single missed stop propagates to the end of the line. **This is precisely
what telephony re-anchoring is for.**

Both bugs the replay harness caught before any ride:
1. The adaptive threshold trained itself on *walking* (~25x more energetic than a train),
   pinning the threshold above train vibration so the whole ride read as stopped.
2. Backdating used "last step before motion onset", which on a platform marks when you
   *arrived at the platform*, not when you boarded — off by 76 s.

## Known limits

- Thresholds are guesses from physics, not measurements. The first real ride replaces them.
- Walking *inside* a moving carriage will mask a station dwell and lose a stop.
- Stop counting drifts — one missed or spurious stop propagates. This is what telephony
  re-anchoring is meant to bound.
- An emulator cannot validate any of this: no step detector, and a perfectly static
  accelerometer. Only a real ride tells you anything.
- Cell fingerprints are per-operator. A map built on one SIM does not transfer to another.
- GPS in your trace produced fixes implying 178 m/s while *reporting 20 m accuracy* — if
  you ever reintroduce GPS, gate it on plausible speed, not on the accuracy field.

## First ride checklist

1. Does the energy floor visibly separate station dwells from running? If no clear gap
   appears, this design fails — cheaply, and on day one.
2. Does the stop count match the real station count end to end?
3. Do the same cells recur at the same stations across runs? (Re-anchoring needs only
   rough recurrence, a much lower bar than full sequence matching.)

Ride the same stretch three times, at different times of day, before drawing conclusions.

## v0.9 — TAC tracker (primary logic)

Check-in, station tracking and arrival ETA now come from `TacTracker.kt`, driven by the
serving cell's TAC (Android `getTac()`, the same value older logs call `areaCode`) plus the
accelerometer. Across three real Blue Line rides every station had the same TAC; cell ID
repeated only 9%, so cell ID is no longer used for any decision.

- **Check-in**: *provisional* when motion starts after your last step while still at the
  origin (walk → stand → train moves you); *confirmed* when the next station's TAC appears.
  Boarding time is when the motion started, not your last step.
- **Stations**: position only moves forward in the direction of travel, after a new TAC has
  held for 5 s — this absorbed every TAC flip-flop seen so far. TAC changed 6–43 s before the
  doors opened, so "Arriving X" and "Next stop is yours" come early.
- **ETA**: measured leg times from `tacmap.json`, not distance ÷ speed — on this line an
  800 m leg took 135 s and a 2.1 km leg 126 s. Coordinates are only a fallback for unridden legs.
  Expect ±~20 s per leg in the same direction.
- Also handles: app opened mid-journey (one TAC reading places you), wrong direction (one alert).

The old accelerometer / TAC-set / cellId-set engines still run and log for comparison only.

Validate changes before shipping:
```bash
python3 tools/tac_tracker.py traces/real2/traces/trace-20260910-202819.jsonl --exclude-ride C
./gradlew :app:testReleaseUnitTest     # replays real rides through the Kotlin tracker
```

## v0.11 — any operator, dual SIM, nothing runs after close

- **Operator-aware.** `tacmap.json` now lists `operators` (`40440` = Airtel TN). On a
  surveyed operator the tracker runs in **MAP** mode as before. On any other operator
  (Jio, Vi, BSNL) it runs **MAP-FREE**: the first TAC is the origin; a TAC not yet seen on
  this ride, held 5 s, is the next station in planned order. Station names and leg times
  still come from the map. Lost in map-free: mid-ride cold start, wrong-direction alert.
  - A second area at the origin while standing/walking is merged into the origin.
  - A new area within half the expected leg of the last station is merged into that
    station (ride B met Guindy's 42134 only 28 s before Little Mount's TAC).
  - Replay: map-free gives the same stations and times as map mode on rides B, C, D.
- **Dual SIM.** Every SIM's registered cell is logged with its operator code. The tracker
  follows the operator on the map, else the mobile-data SIM, else the first registered one.
  The status panel shows `MAP` / `MAP-FREE`, the operator, and `[other SIM …]` rows.
- **Logs** add `enb` (LTE cell id >> 8) per cell, plus tracker events `OPERATOR_CHOSEN`,
  `TRACKER_MODE`, `ORIGIN_AREA_ADDED`, `AREA_MERGED`, `MAP_MISS`, `APP_CLOSED`.
- **Close = stop.** Service has `stopWithTask="true"`, stops in `onTaskRemoved`, and
  returns `START_NOT_STICKY` — swiping the app away ends recording and nothing restarts.
  Screen lock / home button keep recording (notification says so).
- **Map data:** ride D northbound leg times folded in.

Multi-operator ride: install on phones with different SIMs (or one dual-SIM phone), set the
same leg, ride together, pull `decisions-cell-towers-*` and `decisions-tac-tracker-*` from each.

## v0.12 — full live cell panel

The "cell towers" panel lists everything the phone reports at that moment (grows as needed):
- **SIMs:** slot, carrier, operator code, data network type, `DATA` SIM, roaming, `[tracked]`.
- **Every registered cell** (`* SERVING` = tracked SIM, `+ REGISTERED` = other SIM) and **every neighbour**, strongest first.
- **Per cell:** type, operator, cell id, eNodeB + site id (LTE), TAC (→ station when on map), PCI, channel,
  bands, bandwidth, measurement age, RSRP, RSRQ, RSSI, SNR, CQI, timing advance, and type extras
  (NR CSI RSRP/RSRQ/SINR, GSM bit error rate, WCDMA Ec/No).
- `?` marks values seen as placeholders on the test phone: RSRP `-44?`, timing advance `1282?`.

Logs: the same new fields go into every tower JSON (`rssi, cqi, bands, bwKhz, ageMs, extra`); a `SIMS`
event is written whenever SIM/network info changes.
