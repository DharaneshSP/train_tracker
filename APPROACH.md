# TAC-Based Metro Tracking — current approach (v0.10)

How the Train Tracker Android app detects **check-in**, **the current station** and
**arrival times** on Chennai Metro Blue Line, without GPS, and what the evidence behind
each decision is. Supersedes `DETECTION_PLAN.md` and the sensor-fusion ideas in `plan.md`.

Status (11 Sep 2026): v0.10 built and installed on the test phone (Xiaomi 2412DPC0AI,
Airtel). Logic validated by replaying four real rides; one ride run live on v0.9.

---

## 1. At a glance

| | |
|---|---|
| **Goal** | Automatic check-in, current station, "next stop is yours" and arrival times for a planned metro leg — ~80% reliable, low battery |
| **Platform** | Android 8.0+ (API 26), native Kotlin, no third-party libraries, no Play Services |
| **Constraints** | Works offline. **No GPS.** Station map bundled in the app |
| **Primary signal** | Serving cell's **TAC** (Tracking Area Code — Android `getTac()`, called `areaCode` in older logs) |
| **Supporting signals** | Accelerometer (motion, for the early check-in) and hardware step detector (walking vs not) |
| **Not used for decisions** | GPS, cell ID, barometer, gyroscope, Google Activity Recognition |
| **Tested on** | Teynampet ⇄ Guindy, 5 stations, both directions, 4 rides |

**The idea in one line:** each metro station sits in its own cell-tower tracking area (TAC).
When the phone's TAC changes to the next station's TAC, the train has reached that station —
typically 25 s before the doors open. The accelerometer adds an early "boarded?" signal before
the first TAC change.

---

## 2. The line and its TACs

```
 Teynampet ── Nandanam ── Saidapet ══ Little Mount ── Guindy
   42144        42142       45127        45129         42133 (+42134)
                                  ╰ underground: GPS lost ~150 s ╯
      800 m        2,111 m       963 m         1,419 m          (straight-line)
```

`tacmap.json` (bundled as `app/src/main/assets/tacmap.json`) holds, per station: name,
coordinates (OpenStreetMap) and TAC(s); per leg: distance and measured leg times per direction.

### Why TAC and not cell ID or the accelerometer

| Signal | What was measured | Verdict |
|---|---|---|
| **TAC** | Same TAC for every station on all 4 rides (A, B, C, D), both directions. Cold-start lookup: 205/205 readings matched | **Primary** |
| Cell ID | Only 4 of 45 cell IDs (9%) seen on all three of rides A–C. Cold-start lookup: 91/205 (44%). One tower site shows up as 2–4 different cell IDs (sector/band hopping) | Logged, never decides |
| Accelerometer floor | Running median 0.0372 vs stopped-at-platform 0.0045 (8×) — but the ranges overlap (running p10 0.0227 ≈ stopped p90 0.0228), and it misread a running train as stopped for 60 s on ride C | Only for the early "boarded?" signal |
| Step detector | Walking floor 0.98–5.04 vs riding ~0.03: 30–170× apart | Separates walking from riding |

### TAC changes before the doors open (ride C, southbound, with taps)

| Station | TAC changed | Doors opened (tap) | TAC ahead by |
|---|---|---|---|
| Nandanam | t+495 | t+501 | 6 s |
| Saidapet | t+621 | t+646 | 25 s |
| Little Mount | t+746 | t+789 | 43 s |
| Guindy | t+894 | t+920 | 26 s |

**Direction matters.** A TAC boundary is a fixed place between two stations, so the change
comes at a different point of the leg depending on the direction. Southbound, Nandanam's TAC
appears 6 s before its doors open; northbound, Teynampet's TAC appears right after leaving
Nandanam (≈100 s before arriving). All timings are therefore stored per direction.

---

## 3. How check-in works

```
 AT <origin> ──(motion that starts after your last step, held 15 s)──► BOARDED?  (provisional)
      │                                                                    │
      │                                          next station's TAC appears, held 5 s
      │                                                                    ▼
      └───────────(next station's TAC held 10 s, no motion seen)─────► CHECKED IN (confirmed)
```

1. **Provisional — "BOARDED?"**: the phone is still on the origin's TAC and the accelerometer
   has reported MOVING for ≥ 15 s, **and that motion began after the rider's last step**
   (walk → stand still → the train moves you). Estimated departure = when that motion began
   (MOVING state entry − 13 s, never earlier than the last step).
2. **Confirmed**: the next station's TAC appears and holds (5 s after a provisional, 10 s
   without one). Boarding time = the provisional's departure estimate, if there was one.
3. **Opened mid-journey (cold start)**: if the first TAC reading belongs to a station already
   past the origin, check-in is confirmed immediately at that station (boarding time unknown).
4. **Cancelled**: a provisional with no TAC change within 6 minutes is withdrawn.
5. **Wrong direction**: if, before check-in, the TAC changes to the station on the *other* side
   of the origin and holds 10 s, one "WRONG DIRECTION" alert is raised.

**Why "motion must start after the last step".** On ride C the rider walked into the station
and stopped; the motion state stayed MOVING for several seconds afterwards (it needs 10 still
seconds to flip), which fired a false check-in 4 s after entering the station. Requiring the
motion to begin after the last step removed it.

**Why the boarding time is not the last step.** On ride C the rider stood on the platform for
about 165 s. "Last step" would have been three minutes early; motion onset was within ~3 s of
the departure seen in the vibration data (t+363 vs ≈t+360).

### Constants (`TacTracker.kt`)

| Name | Value | Purpose |
|---|---|---|
| `PERSIST_MS` | 5 s | a new TAC must hold this long to count (absorbs TAC flip-flop) |
| `CONFIRM_MS` | 10 s | …this long when there was no provisional |
| `PROV_MOVE_MS` | 15 s | sustained motion needed for "BOARDED?" |
| `MOVING_LAG_MS` | 13 s | MOVING is detected ~13 s after the real onset (10 s window + 5 s hold) |
| `PROV_TIMEOUT_MS` | 6 min | provisional with no TAC change is cancelled |

### How the accelerometer decides MOVING / STOPPED (`SensorEngine.kt`)

- 20 samples/s; every second, `energy` = variance of |acceleration| (orientation-independent).
- `floor` = minimum `energy` over the last 10 s — a moving train never goes quiet, while
  handling the phone only adds spikes.
- Threshold = max(0.02, 0.15 × learned train vibration). The learned level ignores
  windows with recent steps (walking is 30–170× stronger and would push the threshold up).
- MOVING after 5 s above the threshold, STOPPED after 10 s below.

---

## 4. How stop detection works

Each TAC maps to a station index. On every TAC reading:

- **Station ahead** in the direction of travel, held 5 s → "Arriving <station> (n/N)".
- **Station behind, or the same one** → ignored. This absorbed every TAC flip-flop seen so far
  (`45127↔45129` near Saidapet/Little Mount, `42133↔42134` at Guindy).
- **TAC not on the map** → ignored.

Then:

| Condition | Alert |
|---|---|
| Arrived at the station before the destination | "Next stop is yours: <destination>" — one full leg of warning |
| Arrived at the destination | "ARRIVING <destination> — get ready" (≈25 s before the doors, southbound) |
| Arrived past the destination | "Passed <destination> — missed your stop" |

The accelerometer plays **no part** in counting stops. (The old accelerometer stop counter
still runs and logs, for comparison only.)

---

## 5. Arrival time (ETA)

```
next station  = time we reached the current station's TAC + measured leg time (same direction)
destination   = next station + measured times of the remaining legs
first leg     = departure time from "BOARDED?" + measured departure → next-TAC time
```

**Why measured leg times and not distance ÷ speed:** on this line leg time barely depends on
length — short hops spend most of their time accelerating and braking.

| Leg | Distance | Time (TAC → TAC) |
|---|---|---|
| Teynampet → Nandanam | 800 m | 135 s (departure → TAC) |
| Nandanam → Saidapet | 2,111 m | 142 s, 125 s |
| Saidapet → Little Mount | 963 m | 128 s, 125 s |
| Little Mount → Guindy | 1,419 m | 136 s, 148 s |

A least-squares fit over nine measured legs gives `116 s + 16 s/km` — used only for legs that
have never been ridden. Station coordinates exist for that fallback and for future use.

**Accuracy** (each ride scored using only the *other* rides' timings):

| | Errors per leg |
|---|---|
| Ride C, southbound | −17 s, −3 s, +12 s |
| Ride B, northbound (only southbound timings available then) | +42 s, +45 s, −25 s |
| Ride D, northbound, live on v0.9 | −74 s (first leg: no northbound departure timing yet), −31 s, +30 s, −2 s |

Expect roughly ±20 s per leg in a direction with measured timings.

---

## 6. Rides so far

| Ride | Date | Leg | App | Taps | What it showed |
|---|---|---|---|---|---|
| A | 9 Sep | Teynampet → Guindy | original logger (cells + GPS) | none | TAC per station; 7 of 21 cells never got a GPS fix (underground) |
| B | 10 Sep | Guindy → Teynampet | v0.4 | none | Reverse direction; same TACs; cell ID repeat only 24% |
| C | 10 Sep | Teynampet → Guindy | v0.8 | ENTERED_STATION, DOORS_OPEN ×4, DOORS_CLOSED | TAC leads doors by 6–43 s; accelerometer 8× separation; old check-in ~270 s after departure |
| D | 11 Sep | Guindy → Teynampet | **v0.9 live** | STEPPED_OFF | All 4 stations right; false "BOARDED?" while waiting (cancelled); ETA −74/−31/+30/−2 s |

### Ride C replayed through the current logic

| Time | Event |
|---|---|
| t+164 | tap ENTERED_STATION — status `AT Teynampet` |
| ≈t+360 | train leaves (vibration jumps) |
| t+391 | `BOARDED?` — departure estimated t+363 |
| t+495 | `CHECKED IN`, `Arriving Nandanam 1/4` (doors t+501) |
| t+621 | `Arriving Saidapet 2/4` (doors t+646) |
| t+746 | `Arriving Little Mount 3/4`, `Next stop is yours: Guindy` (doors t+789) |
| t+894 | `ARRIVING Guindy` (doors t+920) |

Also verified by replay: opening the app mid-ride at t+650 places the rider at Saidapet
immediately; choosing the wrong direction raises one alert 10 s after the wrong TAC appears.

---

## 7. App architecture

```
 SensorManager ── accelerometer (20 Hz, 5 s batches) ──► SensorEngine ── Tick (1/s: floor, state, steps)
               └─ step detector (events) ─────────────────┘                    │
                                                                               ▼
 TelephonyManager ── cell-change push + 5 s poll ──► TelephonyReader ── TAC ──► TacTracker ──► alerts, ETA
                                                          │                        ▲
                                                          │                  TacMap (tacmap.json)
                                                          ▼
                                   TraceLogger / DecisionLog (JSONL files) · MainActivity (screen)
```

| File | Role |
|---|---|
| `TrackingService.kt` | Foreground service: registers sensors and the cell callback, feeds everything, writes logs |
| `TacTracker.kt` | **Check-in, stations, ETA** (primary logic) |
| `TacMap.kt` + `assets/tacmap.json` | TAC-keyed stations, coordinates, leg times |
| `SensorEngine.kt` | Accelerometer → MOVING/STOPPED; also the old accelerometer check-in/stop counter (comparison) |
| `TelephonyReader.kt` | Serving + neighbour cells: TAC, cell ID, PCI, channel, RSRP, RSRQ, SNR, timing advance, operator |
| `ZoneCheckinEngine.kt` | TAC-set and cell-ID-set check-in engines (comparison only) |
| `MainActivity.kt` | FROM/TO pickers, status, live cell-tower panel, ground-truth tap buttons, live log |
| `TraceLogger.kt`, `DecisionLog.kt` | JSONL logs |
| `tools/tac_tracker.py` | Python twin of `TacTracker` — replays recorded rides |
| `app/src/test/…/TacTrackerReplayTest.kt` | Runs the Kotlin tracker over real rides (4 scenarios) |

### Sampling

| What | Rate |
|---|---|
| Accelerometer | 20 samples/s, delivered in 5 s batches (the processor wakes every 5 s) |
| Motion analysis | 1 s windows (arithmetic only — changing it to 5 s saves no battery; tested: +2 s on check-in) |
| Step detector | events, on the motion chip |
| TAC / cells | push on every cell change (API 31+) + 5 s backup poll |
| Screen | 1 s refresh while visible; cell panel every 5 s; nothing while hidden |

### Android APIs and permissions

- `SensorManager` (`TYPE_ACCELEROMETER`, `TYPE_STEP_DETECTOR`), `TelephonyManager.getAllCellInfo()`,
  `registerTelephonyCallback(CellInfoListener)`, `CellIdentityLte.getTac()` / `CellIdentityNr.getTac()`,
  foreground service `specialUse`, `getExternalFilesDir()`.
- `ACCESS_FINE_LOCATION` — Android only returns cell/TAC data with it; **GPS is never used**.
  `READ_PHONE_STATE` (cell callback), `ACTIVITY_RECOGNITION` (step detector — not Google's API),
  `FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS`. `WAKE_LOCK` is declared but unused.
- The service type is `specialUse`, not `location`: a `location` service crashes the whole app
  if location permission is denied (fixed in v0.6).

### Logs per session (`Android/data/com.nammayatri.traintracker/files/traces/`)

| File | Contents |
|---|---|
| `trace-<t>.jsonl` | 1 tick/s (energy, floor, state, steps), every cell reading, taps |
| `decisions-tac-tracker-<t>.jsonl` | check-in, arrivals (with ETA error), alerts |
| `decisions-cell-towers-<t>.jsonl` | raw tower readings only |
| `decisions-old-accel / tac-set / cellid-set-<t>.jsonl` | comparison engines |

---

## 8. Battery

Measured with `dumpsys batterystats` on the test phone, 11 Sep (v0.9, two sessions):

| State | Time | Used | Rate |
|---|---|---|---|
| App open on screen | 23 min | 51.3 mAh | ~130 mAh/h |
| Tracking in the background | 1 h 5 min | 2.2 mAh | ~2 mAh/h |

The on-screen cost was a **bug**: every service update started another never-ending 1 s screen
refresh, so after 23 minutes about 1,400 refresh loops ran at once — measured live at up to
533% of one CPU core. **Fixed in v0.10** (one refresh loop, only while visible, redraw only
when text changes). A fair on-screen retest on the phone is still pending.

The tracking itself is cheap (~2 mAh/h, about 0.03% of the 6,550 mAh battery per hour).
Further savings, not yet done: status in the notification (so the screen can stay off),
automatic stop after arrival, accelerometer off between check-in and the last leg, batched
log writes, and removing the comparison engines once testing is over.

---

## 9. Validating a change

```bash
# replay a recorded ride through the Python twin (leave that ride's own timings out)
python3 tools/tac_tracker.py traces/real2/traces/trace-20260910-202819.jsonl --exclude-ride C

# replay real rides through the Kotlin tracker the app ships
./gradlew :app:testReleaseUnitTest

# build
ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:assembleRelease
```

Rule followed so far: change the Python twin, check it against all recorded rides, port to
Kotlin, confirm the replay tests match, then build.

---

## 10. Known limitations and open issues

| Issue | Seen on | Status |
|---|---|---|
| **False "BOARDED?" while waiting** — steady vibration with no steps (likely the escalator up to Guindy's elevated platform, or handling the phone). The threshold was still at its 0.02 minimum because no train vibration had been learned yet | ride D, t+135 | Cancels itself after 6 min; needs a fix |
| **Session started at the destination** declares "arrived" at t+0 | ride D (2nd session) | Should suggest swapping FROM/TO |
| **No automatic stop** — a forgotten session kept recording 73 min | ride D (2nd session) | Planned |
| An **auto/road vehicle** leaving the station looks like boarding | design | Needs a rail-vs-road signal |
| Check-in is only **confirmed at the first station** (the TAC doesn't change on departure) | design | "BOARDED?" is the early signal |
| Two stations sharing one TAC could not be told apart | none on this stretch | — |
| **Map covers one corridor on one operator** (Airtel 40440) | design | Other lines/SIMs need a survey ride |
| Ride D's northbound leg times not yet in `tacmap.json` | ride D | To fold in |
| No TAC API on **iOS** (`CTCarrier` deprecated, no replacement) | — | iOS needs a different approach |

## 11. Next steps

1. Fair on-screen CPU retest of v0.10 on the phone.
2. Fix the false "BOARDED?" at the origin, the start-at-destination case, and add auto-stop.
3. Fold ride D's northbound timings into `tacmap.json`.
4. **GPS comparison build** — a second app (separate package, runs side by side) using GPS
   only, to compare accuracy and battery on the same ride. Python prototype started
   (`tools/gps_tracker.py`).
5. Evaluate timing advance (now logged) as a distance-to-tower signal.
6. Next rides: tap **TRAIN_MOVED** and **STEPPED_OFF** so departure and alighting are measured,
   not inferred.
