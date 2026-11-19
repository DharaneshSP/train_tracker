> **Superseded (11 Sep 2026) — see [`APPROACH.md`](APPROACH.md).** This plan predates the TAC tracker
> (v0.9+), the ride-C ground truth and the battery measurements; kept for history.

# Train check-in & stop detection — final plan

Supersedes `plan.md` for the detection mechanism. `plan.md`'s framing (IMU-heavy,
GPS-assisted, barometer-centric) predates this session's field data; every claim below
is either measured against a real ride or explicitly marked as unverified. No GPS, no
barometer, no gyroscope — offline, lightweight, ~80% target.

## 1. The architecture in one line

Two cheap, independent, GPS-free signals do the whole job:

```
step detector (hardware coprocessor)   ->  walking or not
TAC / areaCode (cell tower)            ->  which regional zone, and did it change
```

Accelerometer floor stays for **stop-dwell counting only** — it was measured to fail as
a check-in gate (below) and must not gate check-in.

## 2. Signal reliability — measured, not assumed

| Signal | Reliability | Evidence |
|---|---|---|
| Step detector | 30–170× separation, walking vs riding | real device ride |
| **TAC** (= `areaCode`, same field, two logger names) | **100%** cross-ride repeat match (6/6 TACs); **100%** cold-start lookup success (205/205 readings) | two independent real rides, same corridor, opposite directions |
| cellId | 24% cross-ride match (8/33); 44% cold-start lookup success | same two rides |
| Accelerometer floor alone, as a MOVING/STOPPED check-in gate | **0% separation** between "riding" and "stopped" in the actual boarding window; produced a check-in 584s late, driven by threshold decay, not motion | real ride, old build |

**Decision: TAC is the position/zone signal. cellId is optional decoration only, never
the primary key — it fails more than half the time on a single lookup.**

## 3. Detection logic

### 3.1 Check-in (app running before departure)
```
CHECK-IN = walking stopped (sustained, step-detector based)
           AND a TAC appears outside the frozen origin-TAC-set
           AND persists (debounce ~15s, against ping-pong)
BACKDATE = timestamp of the last step before motion onset
```
Map-free: the origin set is *whatever TACs were seen while standing still*, not a
bundled table. Rejects escalators for free (TAC doesn't change on an escalator).
Does **not** reject an auto ride away from the station — known, accepted gap (§6).

### 3.2 Cold start (app opened mid-journey — genuinely different problem)
```
on journey arm: one-shot read of current serving TAC
  TAC matches a DIFFERENT known station (not origin) -> high confidence check-in,
      seed stop count = (matched index - origin index) * direction
  TAC matches ORIGIN                                  -> ambiguous, fall to normal ARMED flow
  TAC not in the map                                  -> no anchor, flag "possible missed boarding"
boardedAtMs: left unset or approximated from timetable, explicitly flagged as a guess
```
Verified against a real ride: a simulated cold start at t+450s correctly placed the
rider in the Saidapet/Little Mount window using one cell reading.

### 3.3 Direction
Falls out of the TAC zone sequence for free — the reverse ride produced the exact
reverse zone order (verified). Wrong-direction boarding = the next TAC doesn't match
the expected next zone toward the destination.

### 3.4 Stop / station counting (still the weakest part)
- Base: accelerometer decelerate→dwell→accelerate state machine
- Correction: reject an IMU stop candidate unless a TAC/cellId change independently
  brackets it — tested on real data, cut false stops from 10 candidates to 6 (true
  count was 4; **not fully solved**)
- `minDwellSec` is currently a near-no-op due to a measured ~14s rolling-min flush lag
  that inflates every dwell reading — needs re-tuning against tapped ground truth

### 3.5 Deboard
- Provisional: stop + walking resumes
- Confirmed: no step-free MOVING follows within ~60s (train left without you)
- TAC cross-check: destination TAC reached = corroboration, never a gate

## 4. Implemented vs not yet (the app is behind this plan)

**In the app today (v0.5):** accelerometer engine + step detector, foreground service,
TAC/cellId via `TelephonyCallback` (push+poll), cellId-keyed map anchoring, FROM/TO
pickers, ground-truth taps, JSONL traces, offline replay tool.

**Designed and verified here, not yet ported to Kotlin:**
- TAC-set check-in trigger (app still runs the old accel-threshold gate)
- Cold-start one-shot lookup
- Cell-change gate for stop-count rejection
- Direction / wrong-direction check
- `station_tac_map.json` (TAC-keyed) is not wired in — the bundled asset is still the
  old cellId-keyed `stationmap.json`

## 5. Build tasks, next session

1. Replace bundled `stationmap.json` with `station_tac_map.json` (TAC-keyed) as the app asset
2. Rewrite check-in in `SensorEngine`/`TrackingService`: remove accel-threshold gating
   from the check-in path entirely; accelerometer stays only for stop-counting
3. Add cold-start one-shot TAC lookup on journey arm
4. Add the cell-change gate to stop counting
5. Add direction check against expected TAC zone order
6. Re-tune `minDwellSec`/`stopHoldSec` for the measured flush-lag bias
7. Keep cellId as opportunistic secondary refinement only

## 6. Known unsolved / accepted limitations

- **Auto/road-vehicle vs train**: TAC-based check-in can't tell "sat in an auto,
  TAC changed" from boarding. No fix within current constraints (rail-vs-road needs
  vibration/yaw, explicitly excluded from this design). Accepted risk.
- **Origin and destination sharing one TAC**: check-in/deboard can't fire at all on
  such a leg. Needs a time-based fallback or a manual tap.
- **Map coverage is n=2**: two rides, one operator (Airtel), one corridor (Blue Line
  south, 5 stations). No claim beyond that scope.
- **iOS has no TAC equivalent** — `CTCarrier` deprecated, no replacement. Needs a
  different, unverified approach (`CMMotionActivity` + significant-location-change) and
  likely keeps a manual check-in tap.
- **Ground truth is thin**: only one real ride has been analyzed, and it had no taps
  pressed. Every "4 stops expected" claim is inferred from station count, not measured
  against `DOORS_OPEN`/`TRAIN_MOVED` taps.

## 7. What the next ride needs to answer

1. **Press every ground-truth button this time** — no real ride so far has taps
2. Does TAC-set check-in fire close to the actual `TRAIN_MOVED` tap?
3. Does the stop count match tapped `PASSED_STATION` count?
4. Cold start: open the app for the first time mid-journey, check inferred position
5. Deliberately board the wrong direction — confirm it's flagged
6. Ride an escalator into the station — confirm no false check-in
7. Take an auto instead of the train — confirm it *does* false-positive, to quantify
   how often (known gap, not yet fixed)
