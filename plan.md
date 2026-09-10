For rail, boarding detection is mostly a fusion of cheap sensors with a retrospective confirmation — and the most important design decision is that you don't try to catch the instant it happens.

Detect it late, then backdate

The definitive signal that someone boarded a train is the first departure acceleration, which arrives 30–90 seconds after they physically stepped aboard. So the architecture is:

1. A cheap trigger says "possible boarding" and starts a ring buffer of sensor data
2. The departure signature confirms it
3. You backdate the trip start to the buffered boarding moment

That's far more robust than trying to nail the moment live, and it means the expensive sensors only spin up after something cheap has fired.

The escalation ladder

┌──────┬────────────────────────────────────────────┬────────────────────┬─────────────────────────────────┐
│ Tier │                  Sensors                   │        Cost        │               Job               │
├──────┼────────────────────────────────────────────┼────────────────────┼─────────────────────────────────┤
│ 1    │ Station geofences, OS activity transitions │ ~free, coprocessor │ Wake everything else            │
├──────┼────────────────────────────────────────────┼────────────────────┼─────────────────────────────────┤
│ 2    │ Barometer at ~1 Hz, accelerometer bursts   │ very low           │ Confirm station entry           │
├──────┼────────────────────────────────────────────┼────────────────────┼─────────────────────────────────┤
│ 3    │ Accelerometer + gyro at 50 Hz              │ moderate           │ Classify mode, detect departure │
├──────┼────────────────────────────────────────────┼────────────────────┼─────────────────────────────────┤
│ 4    │ GPS at 15–30 s                             │ expensive          │ Track position                  │
└──────┴────────────────────────────────────────────┴────────────────────┴─────────────────────────────────┘

Geofencing station entrances is the single best trigger. Metro stations are the easy part of your network — you'll have them accurately from week one. Note the platform caps: iOS monitors 20 regions per app, Android 100, so on a 40-station network you re-register the nearest ~20 as the user moves. Standard technique, but plan for it.

What each phase looks like

Entering an underground station. The barometer is the star here and it's underused. Descending 15–20 m raises pressure by roughly 2 hPa — far above typical MEMS sensor noise of 0.01–0.06 hPa. Pair that with GPS degrading (satellite count falling, then fix lost entirely) and you have a very distinctive combined signature: pressure rises, satellites vanish. Nothing else in daily life does that.

Absolute pressure is useless — weather moves it. The relative change over a few minutes is the signal. iOS gives you this through CMAltimeter.startRelativeAltitudeUpdates, Android through Sensor.TYPE_PRESSURE.

On the platform. Stationary or slow walking, pressure stable at the new level. The transition sequence WALKING → STILL → IN_VEHICLE within a short window near a known station is the boarding signature — and the STILL phase is what distinguishes boarding a train from getting into a car, where walking flows straight into in-vehicle. It's evidence rather than proof, though; if the train's already at the platform you board immediately and the still phase vanishes.

Departure — the confirmation. Smooth sustained acceleration around 0.6–1.0 m/s² for 10–30 seconds, and crucially very low vertical vibration and near-zero yaw. Steel wheel on steel rail is nothing like a bus on an Indian road, and a train never changes lanes.

The gotcha in the OS APIs                                                                                                                                                                                                                           
Neither platform has a rail activity type. iOS CMMotionActivity offers stationary, walking, running, automotive, cycling, unknown; Android DetectedActivity offers IN_VEHICLE, ON_FOOT, STILL, TILTING and friends. A train reports as automotive / IN_VEHICLE — and because the ride is so smooth, sometimes as unknown or even stationary.

So the OS gets you "in a vehicle" for free, and mode classification is yours to write. That's the honest posit

Discriminating rail from bus

┌────────────────────┬────────────────────────────────────┬───────────────────┬───────────────────┐
│       Signal       │               Metro                │   Suburban rail   │        Bus        │
├────────────────────┼────────────────────────────────────┼───────────────────┼───────────────────┤
│ GPS                │ Lost underground                   │ Retained          │ Retained          │
├────────────────────┼────────────────────────────────────┼───────────────────┼───────────────────┤
│ Barometer at entry │ ~2 hPa rise (or fall, if elevated) │ Little change     │ None              │
├────────────────────┼────────────────────────────────────┼───────────────────┼───────────────────┤
│ Vertical vibration │ Very low                           │ Low–moderate      │ High              │
├────────────────────┼────────────────────────────────────┼───────────────────┼───────────────────┤
│ Yaw rate           │ ~0 except gradual curves           │ ~0                │ Frequent          │
├────────────────────┼────────────────────────────────────┼───────────────────┼───────────────────┤
│ Dwell              │ 20–40 s, regular                   │ 30–90 s, variable │ 5–60 s, irregular │
├────────────────────┼────────────────────────────────────┼───────────────────┼───────────────────┤
│ Stop spacing       │ ~1 km, even                        │ 1–8 km, clustered │ 300–700 m, dense  │
└────────────────────┴────────────────────────────────────┴───────────────────┴───────────────────┘

Vertical-vibration RMS plus yaw variance is the cheapest pair that separates rail from road, and both come froampling.

Once aboard and underground

Location sharing tracks nothing in a tunnel, so position comes from stop-event counting: decelerate ~1 m/s² foon for the 20–40 s dwell → accelerate. Count those against the known station order and you hold "betweenstations 4 and 5, 38 seconds in" with no GPS at all. A tunnel is a line with a known sequence, which makes this much easier than it sounds.

Two speculative signals worth a cheap experiment rather than a design commitment: doors opening in an air-conditioned coach should produce a small pressure equalisation transient, and tunnel piston effect produces pressure pulses as trains push air. Both are real physics; neither is something I'd build on before measuring.

Where this breaks

- Barometer absence. A real fraction of budget Android devices don't have one, and that's a meaningful share ohing above needs a no-barometer fallback path.
- Elevated sections behave like surface rail — no descent, GPS retained. Bengaluru Metro has both.
- Interchanges and people walking through stations without boarding will produce false positives.
- A train sitting at a platform with doors open is indistinguishable from standing on the platform.

What this means for your first corridor

Chennai suburban rail doesn't need most of this. It runs at surface and elevated level, so GPS works throughout — station geofence, the walk-still-vehicle transition, then confirm against speed profile along known line geometry. The barometer work
and stop-event dead reckoning are a Bengaluru Metro problem, which under the current plan is a later phase.

That's a useful sequencing gift: you get to ship boarding detection in its easy form, on real riders, before w

And the cheap backstop for everything above — when the sensors are ambiguous, ask. One tap, at the moment theyver-engineering the classifier.

Want me to fold this into the plan as a section under Engine?