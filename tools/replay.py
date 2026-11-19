#!/usr/bin/env python3
"""
Replay a recorded trace through the same detection logic the app runs.

The app logs per-second `energy` (variance of |a|). Everything downstream of that
-- rolling floor, adaptive threshold, hysteresis, stop counting, check-in -- is
recomputed here, so thresholds can be retuned against recorded rides without
riding again. Keep this in sync with SensorEngine.kt.

usage:  replay.py trace.jsonl [--stop-hold 10] [--min-dwell 15] ...
"""
import json, sys, argparse
from collections import deque

def corroboration_timeline(cells, leg, smap):
    """Replays the service's cell-corroboration logic: +1 departed origin cells,
    -1 map covers this operator but origin never seen, 0 no coverage."""
    if not leg or not smap or leg["line"] not in smap: return []
    names, c2s = smap[leg["line"]]
    O = leg["originIndex"]
    matched = False; origin_seen_t = None; departed = False
    out = []
    for t, cid in cells:
        raw = c2s.get(cid)
        if raw is None: continue
        matched = True
        approaching = raw.startswith("~"); name = raw.lstrip("~")
        if name == names[O] and not approaching: origin_seen_t = t
        elif origin_seen_t is not None: departed = True
        # Presence at the origin is itself the evidence; it decays after 10 minutes.
        origin_recent = origin_seen_t is not None and (t - origin_seen_t) < 600
        c = 1 if (departed or origin_recent) else (-1 if (matched and origin_seen_t is None) else 0)
        out.append((t, c))
    return out


def corrob_at(timeline, t):
    c = 0
    for tt, v in timeline:
        if tt <= t: c = v
        else: break
    return c


def run(energies, steps_at, p, timeline=None, tacs=None):
    floor_buf = deque(maxlen=p.floor_window)
    moving_ref = 0.0
    stopped_run = moving_run = dwell = move_state = 0
    state = "UNKNOWN"; stops = 0; stops_rejected = 0; checked_in = False
    alighting = False; exited = False; alight_t = None; step_free_moving = 0
    check_in_t = boarded_t = None; moving_started = None
    last_step_t = None
    out = []

    # TAC verification gate for stop counting (SensorEngine.onTacObserved/gate):
    # IMU dwell alone measured 10 candidates on a real ride vs a true count of 4.
    # Requiring an independent TAC change since the last evaluated candidate - run
    # causally, no lookahead - reproduced exactly 4/4. The very first TAC reading
    # of a session must establish a baseline, not register as a "change" (that bug
    # cost one false-positive stop before it was caught).
    prev_tac = None; tac_changed_since_eval = False; any_tac_seen = False

    # Interleave ticks and TAC readings in real chronological order - this is the
    # causal replay: the gate only ever sees what has already happened by time t.
    events = [(t, "tick", e) for t, e in energies]
    if tacs:
        events += [(t, "tac", tac) for t, tac in tacs]
    events.sort(key=lambda x: x[0])

    for ev in events:
        if ev[1] == "tac":
            t, _, tac = ev
            if any_tac_seen and tac != prev_tac: tac_changed_since_eval = True
            any_tac_seen = True; prev_tac = tac
            continue
        t, _, e = ev
        if steps_at and t in steps_at:
            last_step_t = t
        floor_buf.append(e)
        floor = min(floor_buf)
        since_step_pre = (t - last_step_t) if last_step_t else 10**9
        # moving_ref must represent VEHICLE vibration. Walking is ~25x more energetic
        # than a train, so a walking window would pin the threshold far too high and
        # the train would read as stopped for the whole ride. Exclude windows with
        # recent steps, and clamp: no train vibrates as hard as a human walking.
        if since_step_pre > p.step_guard and floor < p.ref_clamp:
            moving_ref = max(moving_ref * p.ref_decay, floor)
        else:
            moving_ref = moving_ref * p.ref_decay
        thr = max(p.abs_min, moving_ref * p.ref_frac)

        raw = "MOVING" if floor > thr else "STOPPED"
        if raw == "MOVING": moving_run += 1; stopped_run = 0
        else: stopped_run += 1; moving_run = 0
        since_step = (t - last_step_t) if last_step_t else 10**9

        if state == "UNKNOWN":
            if moving_run >= p.move_hold: state, move_state, moving_started = "MOVING", 0, t
            elif stopped_run >= p.stop_hold: state, dwell = "STOPPED", stopped_run
        elif state == "STOPPED":
            dwell += 1
            if moving_run >= p.move_hold:
                if checked_in and dwell >= p.min_dwell:
                    if (not any_tac_seen) or tac_changed_since_eval:
                        stops += 1
                        out.append((t, f"STOP #{stops} (dwell {dwell}s) [TAC-verified]"))
                    else:
                        stops_rejected += 1
                        out.append((t, f"stop candidate REJECTED (dwell {dwell}s, no TAC change)"))
                    tac_changed_since_eval = False
                state = "MOVING"; move_state = 0; moving_started = t - moving_run; dwell = 0
        else:
            move_state += 1
            cor = corrob_at(timeline or [], t)
            need = p.checkin_fast if cor > 0 else (p.checkin_strict if cor < 0 else p.checkin_move)
            if not checked_in and move_state >= need and since_step >= need:
                checked_in = True; check_in_t = t
                # Standing on a platform and standing on a stationary train are
                # identical to the accelerometer, so the true boarding instant is not
                # recoverable. The last step is only a good proxy if it happened just
                # before departure (walked straight onto a waiting train); otherwise it
                # marks arrival on the platform, not boarding. Fall back to motion onset.
                gap = (moving_started - last_step_t) if last_step_t else 10**9
                boarded_t = last_step_t if gap <= p.board_lookback else moving_started
                how = {1: "cells corroborate", 0: "IMU only", -1: "CONTRADICTED by cells"}[cor]
                out.append((t, f"CHECK-IN after {need}s [{how}] "
                               f"(boarded ~t+{boarded_t}, backdated {t-boarded_t}s)"))
            if stopped_run >= p.stop_hold:
                state, dwell = "STOPPED", stopped_run

        # alight / exit: a train moves without you taking steps; you do not.
        if checked_in and not exited:
            steps_recent = since_step <= 10
            if not alighting:
                if state == "STOPPED" and steps_recent:
                    alighting = True; alight_t = t; step_free_moving = 0
                    out.append((t, "ALIGHT? (provisional - stopped + walking)"))
            else:
                if state == "MOVING" and not steps_recent:
                    step_free_moving += 1
                    if step_free_moving >= p.alight_cancel:
                        alighting = False
                        out.append((t, "  ...cancelled - train departed with us"))
                else:
                    step_free_moving = 0
                if alighting and t - alight_t >= p.exit_confirm:
                    exited = True
                    out.append((t, f"EXITED (alighted ~t+{alight_t})"))
    return dict(events=out, stops=stops, stops_rejected=stops_rejected, checked_in=checked_in,
                check_in_t=check_in_t, boarded_t=boarded_t,
                exited=exited, alight_t=alight_t)

def load(path):
    energies, steps, labels, cells, tacs, leg = [], set(), [], [], [], None
    t0 = None
    for ln in open(path):
        ln = ln.strip()
        if not ln: continue
        r = json.loads(ln)
        ts = r.get("ts", 0) // 1000
        if t0 is None: t0 = ts
        if r["type"] == "tick":
            energies.append((ts - t0, r["energy"]))
            if r.get("steps", 0) > 0: steps.add(ts - t0)
        elif r["type"] == "cells":
            srv = next((c for c in r["towers"]
                        if c.get("reg") and c.get("cid", 0) > 0 and c["cid"] != 268435455), None)
            if srv:
                cells.append((ts - t0, srv["cid"]))
                if "tac" in srv: tacs.append((ts - t0, srv["tac"]))
        elif r["type"] == "event":
            labels.append((ts - t0, r["label"]))
            if r["label"] == "SESSION_START" and "origin" in r:
                leg = {k: r.get(k) for k in
                       ("line", "originIndex", "destIndex", "expectedStops", "origin", "dest")}
    return energies, steps, labels, cells, tacs, leg


def load_map(path):
    """Bundled survey: cellId -> station name ('~X' = in the run into X)."""
    try: m = json.load(open(path))
    except Exception: return None
    out = {}
    for line, obj in m.items():
        names = [s["name"] for s in obj["stations"]]
        c2s = {}
        for st in obj["stations"]:
            for c in st.get("cells", []): c2s[c] = st["name"]
        for k, v in obj.get("segments", {}).items():
            to = k.split("->")[1]
            for c in v.get("cells", []): c2s.setdefault(c["cid"], "~" + to)
        out[line] = (names, c2s)
    return out


def anchor(cells, leg, smap, checkin_t, stop_events):
    """Replay the cell-anchoring layer: does the map confirm this specific leg?"""
    if not leg or not smap or leg["line"] not in smap:
        return None
    names, c2s = smap[leg["line"]]
    O, D = leg["originIndex"], leg["destIndex"]
    direction = 1 if D >= O else -1
    expected = abs(D - O)
    origin_seen = None; dest_seen = None; hint = None; snapped = []
    stops = 0
    for t, cid in cells:
        raw = c2s.get(cid)
        if raw is None: continue
        approaching = raw.startswith("~"); name = raw.lstrip("~")
        hint = name
        # Only sightings at or before check-in count: the app evaluates this live, so
        # it can only ever have seen the origin in the past. Taking the last sighting
        # overall would let a later pass-through of the origin cell leak backwards.
        if name == names[O] and (checkin_t is None or t <= checkin_t): origin_seen = t
        if name == names[D] and not approaching and dest_seen is None: dest_seen = t
        if not approaching and checkin_t is not None and t >= checkin_t:
            n = (names.index(name) - O) * direction
            if 0 <= n <= expected and n > stops:
                stops = n; snapped.append((t, name, n))
    confirmed = origin_seen is not None and checkin_t is not None and \
                0 <= checkin_t - origin_seen <= 600
    return dict(expected=expected, origin_seen=origin_seen, dest_seen=dest_seen,
                hint=hint, snapped=snapped, stops=stops, checkin_confirmed=confirmed,
                origin=names[O], dest=names[D])

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--floor-window", type=int, default=10)
    ap.add_argument("--stop-hold", type=int, default=10)
    ap.add_argument("--move-hold", type=int, default=5)
    ap.add_argument("--min-dwell", type=int, default=15)
    ap.add_argument("--checkin-move", type=int, default=30)
    ap.add_argument("--abs-min", type=float, default=0.02)
    ap.add_argument("--ref-frac", type=float, default=0.15)
    ap.add_argument("--ref-clamp", type=float, default=2.0)
    ap.add_argument("--ref-decay", type=float, default=0.995)
    ap.add_argument("--step-guard", type=int, default=5)
    ap.add_argument("--board-lookback", type=int, default=60)
    ap.add_argument("--alight-cancel", type=int, default=20)
    ap.add_argument("--exit-confirm", type=int, default=60)
    ap.add_argument("--map", default="stationmap.json")
    ap.add_argument("--checkin-fast", type=int, default=15)
    ap.add_argument("--checkin-strict", type=int, default=60)
    a = ap.parse_args()

    energies, steps, labels, cells, tacs, leg = load(a.trace)
    if not energies:
        print("no ticks in trace"); sys.exit(1)
    smap_early = load_map(a.map)
    timeline = corroboration_timeline(cells, leg, smap_early)
    r = run(energies, steps, a, timeline, tacs)

    print(f"trace: {a.trace}   {len(energies)}s of data, {len(steps)}s with steps, "
          f"{len(cells)} cell reads, {len(tacs)} TAC reads")
    if leg: print(f"leg:   {leg['origin']} -> {leg['dest']}  ({leg['expectedStops']} stops)")
    print(f"\nGROUND TRUTH (rider taps)          DETECTED")
    print(f"{'-'*36}   {'-'*36}")
    for t, l in labels: print(f"  t+{t:5d}s  {l}")
    print()
    for t, l in r["events"]: print(f"                                       t+{t:5d}s  {l}")
    print(f"\nresult: checked_in={r['checked_in']}  stops_counted={r['stops']}"
          f"  stops_rejected={r['stops_rejected']}  exited={r['exited']}")
    truth_off=[t for t,l in labels if l=="STEPPED_OFF"]
    if truth_off and r['alight_t'] is not None:
        print(f"alight error:     {r['alight_t'] - truth_off[0]:+d}s vs STEPPED_OFF tap")

    # score against taps
    truth_stops = sum(1 for _, l in labels if l == "PASSED_STATION")
    truth_board = [t for t, l in labels if l == "TRAIN_MOVED"]
    if truth_board and r["check_in_t"] is not None:
        print(f"check-in latency: {r['check_in_t'] - truth_board[0]:+d}s vs TRAIN_MOVED tap")
        print(f"backdate error:   {r['boarded_t'] - truth_board[0]:+d}s")
    if truth_stops:
        print(f"stop count: detected {r['stops']} vs tapped {truth_stops}")

    smap = smap_early
    an = anchor(cells, leg, smap, r["check_in_t"], None)
    if an is None:
        print("\ncell anchoring: no leg info or no map coverage for this trace")
    else:
        print(f"\nCELL ANCHORING  ({an['origin']} -> {an['dest']})")
        print(f"  origin cell seen:  {'t+%ds' % an['origin_seen'] if an['origin_seen'] is not None else 'NEVER'}")
        print(f"  check-in verdict:  {'CELL-CONFIRMED' if an['checkin_confirmed'] else 'SUSPECT (no origin cell before departure)'}")
        for t, nm, n in an["snapped"]: print(f"    t+{t:5d}s  {nm:<14} -> stop {n}")
        print(f"  dest cell seen:    {'t+%ds' % an['dest_seen'] if an['dest_seen'] is not None else 'NEVER'}")
        print(f"  deboard verdict:   {'AT DESTINATION' if an['dest_seen'] is not None else 'NOT at ' + an['dest']}")
        print(f"  anchored stops:    {an['stops']}/{an['expected']}   (IMU alone said {r['stops']})")
