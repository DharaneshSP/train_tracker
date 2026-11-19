# Rail & Bus Live Tracking — Battery-First Plan (Chennai Metro, Suburban, Bus)

Status: draft for review · Date: 2026-09-14 (rev 7: + GPS repair mode)
Related: `APPROACH.md` (TAC tracker, source of truth for detection logic), `plan.md` (original sensor ideas)

---

## 1. Goal

Make live journey tracking in the consumer app (`multimodal/ny-react-native/consumer`) work
**underground, offline and with low battery**, for:

- **Chennai Metro** (underground + elevated) — automatic check-in, current station, ETA, "your stop is next".
- **Chennai Suburban / MRTS** (surface + elevated) — same outputs.
- **Bus** — same UX as today, far less GPS.

### Constraints

| Constraint | Target |
|---|---|
| Reliability | ≥ 80% correct check-in, ≥ 90% correct station on held-out rides |
| Offline | Tracking works with no internet |
| GPS | Not required for metro; used only on specific events for suburban/bus |
| Battery | Rail leg tracking ≤ ~3 mAh/h background (TAC tracker measured 2.2 mAh/h) |
| Platform | Android first; iOS degraded mode |

---

## 2. Evidence we already have (train_tracker test app)

| Finding | Data |
|---|---|
| TAC is stable per station (Airtel 404-40) | Identical on 4 rides (A–D), Teynampet ⇄ Guindy |
| One TAC per underground station | Tey 42144 · Nan 42142 · Said 45127 · LM 45129 · Gui 42133 (+42134) |
| Map-free counting works ("new TAC held 5 s = next station") | 4/4 stations on all 4 rides; only extra event = Guindy's 2nd TAC at the terminal end |
| TAC arrives before doors open | 6–43 s lead (ride C) |
| Check-in timing | ≈30 s after departure; departure estimate within ~3 s (ride C) |
| ETA error | ≈ ±20 s per leg same direction; first live northbound leg −74 s |
| Battery (background TAC + accel) | ≈ 2.2 mAh/h |
| Neighbour cells | Never carry a cell ID on this phone (0 of 992 entries) — serving cell only |
| **Site ID** = `(cellId >> 8) % 100000` (Airtel numbering; inferred) | Cold-start lookup from one survey ride: **98%** (618/631) vs eNodeB ID 54%, cell ID 31%; one dominant site per station on all rides |
| Serving RSRP | −44 dBm placeholder in 50 of 631 serving readings — not usable as-is |
| Not logged in any ride yet | PCI, EARFCN, timing advance, operator code (added in v0.10, no ride since) |

**Not yet verified:** other operators (Jio/Vi/BSNL), elevated-section TAC granularity, suburban anything,
long-term TAC stability (all rides within 10–11 Sep).

---

## 3. Current state in the consumer app (audit)

> Home-screen findings are from branch `live_journey_card_v2` (`bff19f62a6`); the revamped home is not on
> `origin/main` yet. Re-check before merge.

### 3.1 GPS consumers

| Surface | Mechanism | Rate | Notes |
|---|---|---|---|
| Live journey screens (metro/bus/train legs) | `useRiderLocation` → `getBestPossibleLocation()` | every 10 s | Only while screen focused; bus-only journeys still poll (only the POST is skipped) |
| **Revamped home (CUMTA)** | `useLiveJourneyCard` → `useJourneyOverview` → `useJourney` → `useRiderLocation` | every 10 s **×2** | Interval starts on focus **without checking `journeyId`** (`useRiderLocation.ts:355`); mounted in `HomeScreenRevamped.tsx:112` and `CumtaLiveTrackingHost` (`CumtaTabBar.tsx:489`) |
| **Bus tracking screen** | Native stream `startLocationUpdates()` (`BusTrackingScreen/Flow.tsx:194-199`) | 5 s / 2 s fastest, HIGH_ACCURACY, 10 m | Unconditional for all modes; stops only on unmount |
| Everything else on home | One-shot on focus / app active / recenter tap | on event | Nearby-bus and morning-trip polls are network-only (read stored coords) |

One-shot internals (`MapUtils.kt:73-115`): Fused `getCurrentLocation(HIGH_ACCURACY)` with a **5 s native
timeout → cancels and resolves with last-known location**. Weak signal ⇒ ~50% GPS duty at a 10 s poll,
returning stale fixes.

### 3.2 Metro / suburban tracking bottlenecks

| # | Bottleneck | Where |
|---|---|---|
| B1 | Tracking only while journey screen focused; no foreground service (manifest removes `FOREGROUND_SERVICE`) | `useRiderLocation.ts:355`, consumer `AndroidManifest.xml:19` |
| B2 | Check-in requires on-path GPS in strict mode (3 fixes ≤100 m + heading) → impossible underground | `common.ts:523`, `locationUtils.ts:170` |
| B3 | First `POSSIBLE` wins and `MetroWaitingStrategy` is listed first → rider stays "waiting" all underground ride; waiting only exits once `confirmedBoardingData` exists, which only the in-vehicle strategy creates | `journeyLogic.ts:117`, `metroWaitingStrategy.ts:13` |
| B4 | Underground position = timetable dead reckoning from a guessed boarding time; fixed 15 s dwell; 150 s default leg | `metroInVehicleStrategy.ts:62`, `common.ts:14`, `useJourneyWaypoints.ts:178` |
| B5 | Probable off-by-one: backend field is time *to next* stop, app treats it as time *from previous*; `calculateFullJourneyTime` drops the origin leg and adds the leg after destination | `FRFSTicketService.hs:262`, `journeyTrackingUtils.ts:921` — **verify with a real CMRL response** |
| B6 | GPS polled hardest where it cannot work (underground) | `location.ts:628`, `MapUtils.kt:73-115` |
| B7 | Fake-good locations: stale good fix reused up to 2 min; last-known with missing accuracy → `accuracy 0` (passes "super reliable") | `useRiderLocation.ts:163`, `location.ts:652,658` |
| B8 | Suburban (`SUBWAY`) is GPS nearest-stop only; 200 m on-path check can't separate parallel roads | `strategyManager.ts:38`, `trainInVehicleStrategy.ts` |
| B9 | No motion sensing anywhere | — |
| B10 | Rider location POSTed every 10 s during journeys (keeps cellular radio awake) | `useRiderLocation.ts` → `multimodal/{journeyId}/rider/location` |

Computation itself is light (O(route points) distance math per 10 s poll); the cost is **GPS radio,
network radio and re-renders**.

### 3.3 Existing pieces we can reuse

| Piece | Where | Gap |
|---|---|---|
| Native cell reader | `consumer/android/.../telephony/TelephonyModule.kt` | No MCC/MNC, no dual-SIM, one-shot (no listener), NR cell id as string |
| Tower upload | `useTelephonyInfo.ts` → `POST /storeTowerInfo` → Kafka `tower_info_data_v2` (`MultimodalConfirm.hs:3173`) | Only on BusOtp screen mount; no journey link; accuracy field wrong; 0,0 when no fix; no consumer in repo |
| Strategy interface | `IJourneyStrategy.verify/update` | Plug a new signal in |
| Versioned static data | `GET /publicTransport/data` (`ptcv`) | Channel to ship a cell map |
| Manual station confirm | `handleMetroStationConfirm` | Keep as fallback |

---

## 4. Target design

### 4.1 Principle: event-driven sampling

Cheap signals listen continuously; expensive readings (GPS, network) fire **only on events**
(departure, stop, exit).

| Signal | Cost | Use |
|---|---|---|
| Serving-cell / TAC change listener | Very low (push) | Station events underground, direction, check-in confirmation |
| Accelerometer, batched (20 Hz, 5 s batches) + step detector | Low | Moving/stopped, walking vs riding, dwell |
| GPS one-shot | Medium–high per fix | Suburban stop identity, check-in confirm on surface, exit handoff |
| GPS stream | High | Bus no-live-ETA fallback only |
| Network POST | Radio wake | Events only |

### 4.2 Metro

| Phase | Signal | GPS |
|---|---|---|
| Before station | One fix on screen focus | one-shot |
| Waiting at origin | TAC ∈ origin set + accel | off |
| Check-in | Provisional: fresh motion after last step (15 s); confirmed: first forward TAC held 5 s | off |
| Riding | Forward-only TAC station index; ETA = last TAC time + median leg time (per direction) | off |
| Elevated section | TAC (to be validated per operator) | off (optional 1 fix/station for validation builds only) |
| Destination | "Next stop is yours" one leg ahead; arriving on destination TAC | off |
| Exit | Destination TAC + steps resume; auto-stop | one fix only if next leg needs location |

Map layers (see `APPROACH.md`):
1. **Map-free core** — count new TACs from origin (works on any operator, survives renumbering).
2. **Per-operator map** (keyed by MCC/MNC) — cold start mid-ride, wrong direction, ping-pong robustness.
3. **On-device learning** — clean ride ⇒ store TAC→station for that operator.
4. **Server sync** (optional) — share learned pairs, map-miss reports.

### 4.3 Suburban / MRTS

Surface cells are ordinary outdoor towers, so one TAC usually spans several stations. Suburban therefore uses the
**site sequence**: serving cell → site ID (per-operator rule, e.g. Airtel `(cellId >> 8) % 100000`) → ordered route
segment. Site IDs were far more stable than cell IDs on metro rides (98% vs 31% lookup); verify per operator.

**With cell data (primary)**

| Phase | Signal | GPS |
|---|---|---|
| Check-in | Accel provisional → confirmed when the serving cell moves forward along the route's cell sequence | off |
| Riding | Forward-only matcher: serving cell → candidate segments; pick the furthest-forward segment consistent with order and elapsed time | off |
| Station event | Accel dwell 15–60 s **and** current segment ends at a station | off |
| ETA | Last station event + median leg time (per direction) | off |
| Parallel-road guard | Must match the cell sequence **and** dwell at station spacing/timing | off |

**Without cell data (fallback):** stop-triggered GPS — 1 fix at check-in, 1 fix per detected dwell
(≈ 1 fix per 2–3 min instead of 6/min).

Data format: ordered segments from our own survey rides are usable directly. Tower data with only lat/lon
(OpenCellID-style) must be converted (project to route → segment) and validated on a ride.

### 4.4 Bus

Bus position comes from backend live data; rider GPS is only needed when the booked bus has **no live ETA**.

| Case | Location |
|---|---|
| PreBooking (route/stop search) | One fix on entry/focus |
| PostBooking, waiting | One fix on entry/focus; refresh on recenter |
| PostBooking, onboard, **live ETA** | No GPS; start relaxed stream only when ETA ≤ 1 stop to destination (keeps early End-Trip card, `busTrackingUtils.ts:1929`) |
| PostBooking, onboard, **no live ETA** (`useUserLocationFallback`, `useBusTripPhaseFlags.ts:53`) | Stream HIGH_ACCURACY, 15 s / 10 s fastest, 30–50 m displacement |
| Screen blurred | Stop (`useFocusEffect`), resume on focus |

### 4.5 Network

Rail legs: replace 10 s `rider/location` POSTs with **event posts** (check-in, station reached, exit)
+ slow heartbeat (e.g. 2–5 min) while online.

### 4.6 iOS (no cell API)

- Check-in: `CMMotionActivity` (automotive vs stationary/walking).
- Stations: timetable + region monitoring at origin/destination (low power).
- Surface: stop-triggered GPS (same as suburban).
- Underground: timetable only; server-side live positions from Android riders later.

---

### 4.7 Fallback ladder

The tracker uses the best level available **per leg, per moment**, and drops a level on a map-miss.
The UI shows confidence; alerts fire earlier at lower levels.

| Level | Available | Underground metro | Surface (suburban, elevated) | Lost vs L0 |
|---|---|---|---|---|
| **L0** | Fresh operator map (TAC + cell IDs) | TAC station index | Cell-ID sequence + dwell | — |
| **L1** | Map exists but misses (changed TAC/cells) | Map-free TAC counting from last good station | Partial cell match + dwell; stop-triggered GPS where cells unknown | Mid-ride cold start, wrong direction (until relearned) |
| **L2** | No map for this operator/line | Map-free TAC counting from origin | Stop-triggered GPS + dwell | Cold start, wrong direction |
| **L3** | No cell info (permission denied, iOS) | Accel dwell counting + leg times | Stop-triggered GPS (if location allowed) + dwell | Station certainty underground |
| **L4** | Nothing reliable | Timetable from check-in / manual station confirm | Same | Live correction |

**Dwell-assisted counting (L1–L3):** if no new TAC/cell progress arrives within ~1.6× the expected leg time
**and** a dwell ≥ 15 s was seen, advance one station. Covers merged TAC areas and missed readings.

### 4.8 When TAC or cell IDs change

| Change | Example | Effect |
|---|---|---|
| Renumbering | Same areas, new TAC values | Map misses; map-free counting still correct |
| Re-planning | Two stations merged into one TAC, or split | Map-free under/over-counts → dwell-assist corrects |
| New radio layer | Operator moves to 5G standalone → new NR cells/TACs | Every entry misses for that operator |
| Cell churn | Tower added, sector re-IDed (more frequent than TAC changes) | Gaps in the suburban cell matcher |

**Detect** (on the phone, during a ride): a *map-miss* = serving TAC/cell not in the operator map while motion
is MOVING and time since the last station is within leg bounds; or a known TAC arriving out of order.

**Handle:**
1. Drop to L1 for the rest of the leg, anchored at the last good station.
2. After a clean ride (expected stop count, leg times in range), store the new TAC/cell → station for that
   operator on the phone; keep old entries as aliases until unseen for N rides.
3. Queue a small map-miss report (operator, value, time, station guess) for when online.
4. App opened mid-ride with a changed map: ask "which station are you at?" (existing manual confirm) or wait
   for the next station event.

**Result:** a TAC change costs one ride's cold-start and wrong-direction features; station tracking keeps working.

### 4.9 Knowing when data is stale

A single phone only sees its own rides, so it can fall back but cannot tell "stale map" from "odd ride".
Staleness is decided **across riders**, from small reports sent after rides (Phase 6a).

**Signals per ride** (only from confirmed, correctly directed rides; only for the portion the app was open):

| Signal | Meaning | Catches |
|---|---|---|
| **Unknown value** | Serving TAC/cell not in the map for this operator on segment *i → i+1* | Renumbering, new cells, 5G standalone |
| **Missing value** | A value the map expects on a segment was not seen while passing it | Renumbering, removed cells |
| **Out of order** | Known TAC/cell appears at the wrong segment or direction | Re-planning (areas merged/split/reassigned) |
| **Map vs map-free disagree** | Map says station *j*, map-free count says *k* | Any map error |
| **Leg-time residual** | Actual station-event time − predicted | Stale leg times (timetable change, speed limits) |
| **Rider corrections** | "Which station?" answer contradicts the tracker | Any of the above |

**Report after each ride** (tiny, no person id in aggregates):
`mapVersion, appVersion, operator (MCC/MNC), rat, line, direction, level used,
per segment: {unknown values, missing values, out-of-order values, leg-time residual}`.

**Noise to filter before counting:** other SIM on dual-SIM phones, roaming, 2G/3G fallback (different area
codes), invalid readings, unconfirmed or wrong-direction rides, gap portions after a reopen.

**Server rules, per line × direction × segment × operator, weekly** (starting thresholds, tune in pilot):

| State | Rule | Action |
|---|---|---|
| Healthy | Unknown/missing on < 2% of rides; residual median within ±30 s | — |
| Watch | 2–5% of rides, or the same unknown value from ≥ 2 riders | Flag on dashboard |
| **Stale** | ≥ 5% for 2 weeks, **or** same unknown value from ≥ 3 distinct riders on the same segment, **or** an expected value missing on ≥ 10 consecutive rides | Accept the learned value into the next map version, or schedule a resurvey |
| **Stale leg times** | \|median residual\| > 30 s over ≥ 20 rides | Recompute leg times from recent rides |

Renumbering shows up as a matched pair — a new unknown value **and** the old value missing on the same segment —
the strongest and easiest case to auto-correct.

**Proactive triggers (no rides needed):**
- Transit data version (`ptcv`) changes: new station, route or timetable → mark affected segments "unverified".
- Operator network events (e.g. 5G standalone launch in Chennai) → expect misses for that operator.
- **Canary rides:** low-traffic lines get too few reports — one test ride per line per operator per month with
  the 4-SIM phones.

**Per map entry:** `value, operator, line, direction, segment, source (survey/learned), firstSeen, lastSeen,
confirmations`. An entry not seen for ≥ 60 days on a line with traffic becomes "unverified".

### 4.9a GPS repair mode (stale or missing map on surface/elevated sections)

When the phone sees the map is wrong for the segment it is on, it can **temporarily** use GPS to relabel cells —
only where GPS works (surface, elevated). Underground it keeps map-free TAC counting (GPS is useless there:
ride A had 93–500 m accuracy and fixes up to 83 s stale in tunnels).

**Enter repair mode (per segment)** — any of:
- map-miss on the current segment while moving (unknown TAC/site for ≥ 2 readings or ≥ 20 s);
- a known value arrives out of order, or map and map-free counts disagree;
- the downloaded map marks the segment `stale` / `unverified` (server staleness rules, §4.9) — known before the ride;
- no map for this operator/line on a surface segment (bootstrap, e.g. a new suburban line).

**While in repair mode:**
- One GPS fix **per serving-site change** and **per detected dwell**, capped at 1 fix / 30 s and N minutes per ride.
- Accept a fix only if accuracy ≤ 50 m, age ≤ 10 s, and ≤ 100 m from the route polyline (rejects stale/last-known
  fixes — the consumer-app bug B7).
- Snap to route chainage → label the serving TAC/site/cell with that segment. Upload chainage + segment, not raw coordinates.
- Tracking itself continues from TAC/map-free; GPS only produces labels (and can correct the station index on surface).

**Exit repair mode:** k consecutive readings match the map again, the segment ends, the budget is used, or the
environment turns underground.

**Server:** GPS-labelled observations (`source: gps`) weigh more than inferred labels but still need ≥ 2 distinct
riders before changing the published map.

| Environment | Stale map fallback |
|---|---|
| Underground | Map-free TAC counting + rider confirmation (no GPS) |
| Elevated / surface | GPS repair mode (bounded) |
| Location off / permission denied | Map-free + rider confirmation |
| iOS | Not applicable (no cell data to relabel) |

### 4.10 App closed and reopened mid-journey

**Decision: nothing runs after the app is closed.** No restart after a kill, no boot restart, no alarms.
Tracking lives only while the app's task is alive; closing the app ends it, and reopening **reconstructs the gap**.

Assumption: "closed" = swiped from recents, force-stopped, or killed by the OS. An app that is merely
backgrounded or has the screen locked is still open, and keeps tracking via the foreground service
(tied to the task). See open decision 8 if backgrounded should also stop.

| What happens | Tracking | On reopen |
|---|---|---|
| Screen locked / home button (app still open) | Continues (foreground service, notification visible) | JS reads current state |
| Swiped from recents | **Stops** (`stopWithTask="true"` + `onTaskRemoved` → stop) | Restore + reconstruct |
| OS / OEM kill | **Stops**, no restart (`START_NOT_STICKY`) | Restore + reconstruct |
| Force stop | Stops | Restore + reconstruct |
| Reboot | Stops, no boot receiver | Restore + reconstruct |
| Location permission revoked | Drop to L3 | Prompt |

**Consequence to show the rider:** alerts ("next stop is yours") only fire while the app is open. The ongoing
notification says so ("Tracking stops if you close the app").

**Persisted state** (written on every tracker event + a 30 s heartbeat, local only):
`journeyId, legOrder, mode, origin, destination, operator, level (L0–L4), checkInState, departureEstimateMs,
stationIndex, lastStationEventMs, lastTac/cellId, lastHeartbeatMs, pending event queue`.

**Reconstruction on reopen** (gap = `now − lastHeartbeatMs`):
1. **Leg still valid?** If `now` > expected arrival + buffer (e.g. 2× remaining leg time) **and** the current
   cell is not on the route → "trip probably ended", ask to confirm, clear state.
2. **Read the serving cell immediately** (one read):
   - **L0 map:** current TAC/cell → station directly (existing cold-start path), forward-only from the
     persisted index.
   - **Map-free / no map:** TAC changes during the gap were not seen, so estimate
     `index = persisted index + stations fitting in elapsed time` (leg times + dwell), mark low confidence,
     snap on the next TAC change; if the estimate spans > 1 station, ask "which station are you at?".
   - **Suburban without cell data:** one GPS fix on reopen.
3. **Check-in during the gap:** if the rider was "waiting at origin" and the current cell is ahead of origin →
   confirmed check-in, departure time estimated from elapsed time and leg times.
4. **Flush the event queue** with original event timestamps.
5. **Missed the destination during the gap:** current cell past destination → "You may have passed <station>".

Because closing is now a normal path, **the L0 map matters more**: with a map, reopen lands on the exact
station from one cell read; map-free can only estimate.

**Current behaviour to replace:**
- Consumer app: after a reopen, metro position continues from the cached boarding time
  (`setConfirmedBoardingDataInCache`) with timetable dead reckoning; `rider_location_history` from MMKV is reused
  and can be stale (B7).
- Test app: `TrackingService` returns `START_STICKY` (restarts after a kill) and keeps origin/destination only in
  memory — change to stop-with-task + persisted state.

---

## 5. Architecture

### 5.1 RailSense (Android native)

```
Foreground service (type specialUse) — ONLY during an active rail leg AND while the app task is alive
 ├─ CellListener      TelephonyCallback.CellInfoListener + 5 s poll; data-SIM only; MCC/MNC, TAC, cellId, PCI, EARFCN, RSRP
 ├─ MotionEngine      accelerometer 20 Hz batched 5 s, step detector; moving/stopped/walking
 ├─ TacTracker        port of train_tracker TacTracker.kt (check-in, station index, ETA, alerts)
 ├─ StopFixer         suburban: one GPS fix per detected dwell
 ├─ MapStore          per-operator cell map (bundled seed + downloaded + learned)
 ├─ StateStore        persisted leg state + heartbeat + pending event queue (§4.10)
 └─ Emitter           events → JS (DeviceEventEmitter) + local notifications; ride summary for upload
Lifecycle: start when rail leg becomes current (WAITING at origin); stop on exit / auto-stop / 2× expected duration
Close:     stopWithTask="true", onTaskRemoved → stop, START_NOT_STICKY; no boot receiver, no alarms
Reopen:    app open → if StateStore has a valid leg, reconstruct gap (§4.10), then resume
```

### 5.2 JS integration

- New `railSense` field in `StrategyContext` (`checkIn`, `stationIndex`, `confidence`, `etaSec`, `underground`).
- `MetroInVehicleStrategy.verify` → `CONFIRMED` when RailSense confirmed; `update` uses `stationIndex` instead of the time loop.
- `MetroWaitingStrategy.verify` → `IMPOSSIBLE` once RailSense reports departure.
- `useRiderLocation` does **not** poll while RailSense is active for the current leg.
- Manual station confirm stays as fallback.
- On mount/focus, JS asks RailSense for a snapshot (`getState()`) instead of recomputing from cached boarding time.

### 5.3 Crowdsourcing — do we need it?

**Not to launch**, if survey data covers metro (TAC) and suburban (cell IDs). It is needed to keep that data
fresh and complete:

| Need | Survey only | + on-device learning + map-miss reports | + full crowdsourcing |
|---|---|---|---|
| Launch on surveyed lines/operators | ✅ | ✅ | ✅ |
| Know when data went stale | ❌ | ✅ | ✅ |
| Heal a TAC renumbering | ❌ | Per phone; tells us where to resurvey | All phones automatically |
| Suburban cell-ID churn (more frequent) | Frequent resurveys | Resurvey when reports spike | Continuous |
| New operator / line / 5G standalone | New survey | New survey | Automatic |

**Recommendation:** launch with **survey + on-device learning + map-miss reports** (tiny payload, no
aggregation). Build the full pipeline below only when the map-miss rate stays above an agreed threshold
(e.g. > 5% of rides per week on a line) or when expanding to new lines/cities. Suburban will likely need it
first, because cell IDs change more often than TACs.

Full pipeline (when built):

```
Phone: ride summary (change events only, ~5 KB) uploaded after leg ends
  → POST /multimodal/{journeyId}/order/{legOrder}/railObservation   (route/stations authoritative from server)
  → Kafka rail_cell_observation_v1 → raw table (90-day retention)
  → daily aggregation per (city, route, direction, operator):
      vote TAC/cell → station segment (≥3 riders, ≥80% agreement, clean rides), recency weighting,
      leg-time medians, map-miss rate
  → GET /publicTransport/cellMap?city&plmn&version  → cached on device, works offline
Survey rides use the same pipeline (source=survey, higher weight).
```

OpenCellID: internal planning aid only (e.g. estimate suburban TAC granularity); not shipped
(CC BY-SA share-alike, GPS-derived positions miss tunnel cells).

---

## 6. Phased plan

### Phase 0 — Baseline measurement (1–2 days)

- Test build in `train_tracker` with GPS modes: A poll 10 s/5 s timeout · B stream 5 s/2 s · C stream 15 s/30 m · D 1 fix/60 s.
- 20–30 min each, window + indoor; `dumpsys batterystats` (per-UID mAh, GPS time), `dumpsys location`.
- Consumer app baseline: Teynampet ⇄ Guindy ride with today's build; same metrics.

**Exit:** numbers for each mode and the current consumer app ride.

### Phase 1 — JS quick wins in consumer (≈1 week, no native work)

| # | Change | Fixes |
|---|---|---|
| 1.1 | Gate `useRiderLocation` interval on active journey/leg; single instance for home card + tab bar host | Home ×2 ungated polling |
| 1.2 | Rail legs: back off 10 s → 60 s after 2–3 failed/inaccurate fixes; restore on good fix | B6 |
| 1.3 | Missing accuracy ⇒ unreliable; stop reusing stale good fix as current | B7 |
| 1.4 | Bus screen: listener only for journeyless entries + no-live-ETA fallback + last stop; one-shot otherwise; stop on blur; relaxed params | Bus streaming |
| 1.5 | Verify and fix `timeTakenToTravelUpcomingStop` semantics in `calculateFullJourneyTime` / `calculateTravelTimeFromStops` | B5 |
| 1.6 | Rail legs: event posts + slow heartbeat instead of 10 s POST | B10 |

Unit tests per consumer `CLAUDE.md` rules (hooks/utils).
**Exit:** replayed Phase 0 ride shows lower GPS time and mAh; no regression in bus/metro UX.

### Phase 2 — Test-app readiness (≈1 week, train_tracker)

- Operator-keyed `tacmap.json`; dual-SIM (data SIM only).
- Map-free core + on-device learning.
- Auto-stop after arrival; destination-start handling; escalator false "BOARDED?" fix.
- Fold ride D northbound leg times.
- Persist tracker state; restore after process restart (§4.10).
- Replay tests on rides A–D, plus **gap replays**: drop all events between t1 and t2 (before check-in, mid-ride,
  at pre-destination, after destination), resume from persisted state, compare with the uninterrupted run.

**Exit:** all replay tests pass; gap replays land on the correct station at or before the next TAC change.

### Phase 3 — Operator & full metro survey

- Tey ⇄ Guindy with Airtel, Jio, Vi, BSNL **on the same ride** (v0.10 logging: TAC, cell, PCI, EARFCN, timing advance, operator).
  Check per operator: one TAC per station? site-ID rule? Across operators: do area changes happen at the same
  places/times? Any shared value (none expected in identifiers)?
- Blue + Green lines, both directions, 4 SIMs, 2 rides/direction (build on one, validate on other); tag segments underground/elevated.

**Exit (held-out ride, per operator):** check-in ≥ 80% · station ≥ 90% · ETA ±30 s/leg · "next stop" alert ≥ 60 s before doors open.

### Phase 4 — RailSense in consumer (metro)

- Native module + foreground service (needs FGS decision + Play declaration).
- Strategy integration (§5.2); seed map via bundled asset.
- Field test with `FieldTest` customer tag (existing CSV logger).
- Close/reopen tests on stock Android + Xiaomi: screen lock, home button, swipe from recents, `adb shell am kill`,
  force stop, reboot — at waiting, mid-ride and pre-destination. Verify nothing runs after close
  (`dumpsys activity services`, `dumpsys location`, `dumpsys alarm`).

**Exit:** Phase 3 targets met in consumer app; rail leg ≤ ~3 mAh/h; GPS time ≈ 0 underground; correct station
after reopen (exact with L0 map; within one station change map-free); zero app services/location requests/alarms
after close.

### Phase 5 — Suburban

- MRTS Beach ⇄ Velachery first (elevated, short), then Beach ⇄ Tambaram (parallel-road case). GPS on as ground truth in survey logs.
- Measure TAC granularity, cell-ID stability, cold-start coverage per operator.
- Build the cell-ID sequence matcher (primary, using suburban cell data) + dwell-pattern guard; stop-triggered GPS as fallback (L1/L2).

**Exit:** Phase 3 targets in replay; bus-on-parallel-road false check-in = 0 in test rides.

### Phase 6 — Map freshness

- **6a (ships with pilot):** per-ride reports + weekly staleness rules + dashboard (§4.9); canary rides for low-traffic lines.
- **6b (only if 6a crosses the threshold, or on expansion):** observation API + Kafka + raw table; aggregation job; `cellMap` endpoint.

**Exit 6a:** stale data on any line/operator is visible within a week.
**Exit 6b:** map for a new operator/line learned from rides without manual survey.

### Phase 7 — Pilot

- 20–50 riders, mixed operators/brands (incl. Xiaomi).
- Metrics: false check-ins/day, accuracy per operator, mAh/h, map-miss rate.

---

## 7. Metrics

| Metric | Target | Source |
|---|---|---|
| Check-in recall | ≥ 80% | taps / survey ground truth |
| False check-in | ≈ 0 per non-travel day | pilot |
| Station index accuracy | ≥ 90% | replay + rides |
| ETA error | ±30 s per leg | doors-open taps |
| "Next stop" alert lead | ≥ 60 s before doors open | taps |
| Rail leg battery | ≤ ~3 mAh/h | batterystats per UID |
| GPS on-time underground | ≈ 0 | batterystats |
| Bus trip GPS on-time (live ETA available) | near 0 except last stop | batterystats |
| Map-miss rate | < 2% of rides per segment × operator per week (healthy) | per-ride reports (§4.9) |
| Leg-time drift | \|median residual\| ≤ 30 s per segment | per-ride reports |
| Time to detect a stale segment | ≤ 1 week on lines with traffic; ≤ 1 month via canary rides | dashboard |

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| Operator uses one TAC across several stations | Cell ID for that operator; accel + leg-time fallback |
| TAC renumbering / network re-plan | Map-free core + on-device learning + map-miss reports |
| OEM kills the app while open (Xiaomi etc.) | Treated like a close: persisted state + reconstruction on reopen; battery-optimisation prompt; test 3+ brands |
| Rider closes app mid-ride → no alerts | Notification text says tracking stops on close; reopen reconstructs; L0 map gives exact station |
| Location permission denied (cell info needs it) | Accel + timetable mode, clearly labelled |
| FGS / Play policy rejection | `specialUse` justification; rail-leg-only lifetime; fallback to Phase 1 behaviour |
| Suburban parallel road confusion | Dwell pattern + per-stop fix must match stations |
| Privacy (DPDP Act 2023) | Collect only during active rail leg, events only, drop person id in aggregates, 90-day raw retention |

---

## 9. Open decisions

1. Foreground service in consumer app — owner and Play policy sign-off.
2. SIMs for Jio / Vi / BSNL rides.
3. First suburban line: MRTS (easy) or Beach–Tambaram (hard).
4. Where `tower_info_data_v2` lands today; sink for rail observations (ClickHouse/PG).
5. Aggregation location: rider-app scheduler vs analytics ETL.
6. Map-miss threshold that triggers resurvey vs full crowdsourcing (6b).
7. Bus last-stop behaviour: option A (no GPS, ETA-based End Trip) vs B (GPS within last stop) — recommended B.
8. While the app is open but backgrounded / screen locked: keep tracking (current assumption) or stop too?
   Stopping means no alerts with the phone in a pocket.

---

## 10. Code references

| Area | File |
|---|---|
| Rider location poll | `consumer/src-v2/multimodal/hooks/useRiderLocation.ts` |
| One-shot location | `consumer/src/typescript/utils/location.ts`, `consumer/android/.../MapUtils.kt` |
| Stream listener | `consumer/src/typescript/hooks/useLocationListener.tsx` |
| Strategy engine | `consumer/src-v2/multimodal/rules/journeyLogic.ts`, `strategyManager.ts` |
| Metro strategies | `rules/strategies/common.ts`, `waiting/metroWaitingStrategy.ts`, `invehicle/metroInVehicleStrategy.ts` |
| Suburban strategy | `rules/strategies/invehicle/trainInVehicleStrategy.ts` |
| Bus tracking | `src-v2/multimodal/screens/BusTrackingScreen/Flow.tsx`, `hooks/useBusTripPhaseFlags.ts`, `utils/busTrackingUtils.ts` |
| Home live card | `src-v2/screens/HomeScreenRevamped/hooks/useLiveJourneyCard.ts`, `CumtaTabBar.tsx`, `CumtaLiveTrackingHost.tsx` |
| Cell reader / upload | `consumer/android/.../telephony/TelephonyModule.kt`, `src-v2/multimodal/hooks/useTelephonyInfo.ts` |
| Backend tower ingest | `nammayatri/Backend/app/rider-platform/rider-app/Main/src/Domain/Action/UI/MultimodalConfirm.hs` (`postStoreTowerInfo`) |
| Leg time field | `.../Domain/Action/UI/FRFSTicketService.hs` (`timeTakenToTravelUpcomingStop`) |
| TAC tracker (reference impl) | `train_tracker/app/src/main/java/com/nammayatri/traintracker/TacTracker.kt`, `TacMap.kt`, `tools/tac_tracker.py` |
