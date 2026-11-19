#!/usr/bin/env python3
"""
Reference implementation of the TAC tracker - the logic TacTracker.kt runs live.
Replays a recorded trace causally (events in time order, no lookahead) and scores
station arrivals, check-in and ETA against the rider's taps. Keep in sync with
TacTracker.kt.

  tools/tac_tracker.py trace.jsonl [--map tacmap.json] [--exclude-ride A|B|C]

Design, all of it measured on three real Blue Line rides (9-10 Sep):
  * Station = TAC. Every station's TAC was identical across all three rides;
    cellId repeated only 9%. TAC changed 6-43s (mean 25s) BEFORE the doors opened.
  * Position only moves forward in the direction of travel, and only after a new
    TAC holds for PERSIST_S. That alone absorbed every TAC ping-pong seen so far.
  * Check-in: PROVISIONAL when the accelerometer shows sustained motion with no
    steps while still in the origin's TAC; CONFIRMED at the first forward TAC
    change. Boarding time = motion onset, not last step (the rider can stand on
    the platform for minutes - ride C: 165s).
  * ETA: measured leg times (TAC->TAC), because on this line leg time does not
    scale with distance (800m took 135s, 2111m took 126s). Coordinates are only a
    fallback for legs never ridden.
"""
import json, argparse, statistics

PERSIST_S = 5          # new TAC must hold this long to advance (after check-in)
CONFIRM_S = 10         # ...and this long to confirm check-in with no IMU provisional
PROV_MOVE_S = 15       # sustained MOVING for provisional check-in
MOVING_LAG_S = 13      # MOVING state is entered ~13s after real onset (10s floor window + 5s hold - 2)
PROV_TIMEOUT_S = 360   # provisional with no TAC change for 6 min -> cancelled (escalator, auto...)


class TacMap:
    def __init__(self, path, exclude_ride=None):
        m = json.load(open(path))
        self.stations = m["stations"]
        self.names = [s["name"] for s in self.stations]
        self.tac2idx = {t: i for i, s in enumerate(self.stations) for t in s["tacs"]}
        self.d = m["defaults"]
        self.segs = m["segments"]
        # leave-one-ride-out support: ride A contributed fwd[0], C fwd[1], B rev
        self.exclude = exclude_ride

    def leg_s(self, a, b):
        """Expected TAC->TAC seconds from station a to adjacent station b."""
        i = min(a, b); fwd = b > a
        seg = self.segs[i]
        own = list(seg["tac_to_tac_s"]["fwd" if fwd else "rev"])
        other = list(seg["tac_to_tac_s"]["rev" if fwd else "fwd"])
        if self.exclude == "A" and fwd and own: own = own[1:]
        if self.exclude == "C" and fwd and len(own) > 1: own = own[:1]
        if self.exclude == "C" and not fwd and len(other) > 1: other = other[:1]
        if self.exclude == "A" and not fwd and other: other = other[1:]
        if self.exclude == "B":
            if fwd: other = []
            else: own = []
        if own: return statistics.median(own), "measured"
        if other: return statistics.median(other), "measured-other-dir"
        return self.d["fallback_base_s"] + self.d["fallback_per_km_s"] * seg["dist_m"] / 1000, "distance"


class TacTracker:
    def __init__(self, tmap, origin, dest):
        self.m = tmap; self.O = origin; self.D = dest
        self.dir = 1 if dest >= origin else -1
        self.cur = origin; self.cur_at = None
        self.cand = None; self.cand_since = None
        self.prov_at = None; self.prov_boarded = None
        self.checkin_at = None; self.boarded = None
        self.pred_next = None          # predicted TAC-arrival time at cur+dir
        self.first_tac = True
        self.last_step = None; self.moving_since = None; self.state = None
        self.last_onset = None
        self.done = False
        self.wrong_dir = False
        self.log = []

    def ev(self, t, label, **kw):
        self.log.append((t, label, kw))

    # ---- IMU: provisional check-in -------------------------------------------
    def on_tick(self, t, state, steps):
        if steps > 0:
            self.last_step = t
        if state == "MOVING" and self.state != "MOVING":
            self.moving_since = t
            onset = t - MOVING_LAG_S
            if self.last_step is not None: onset = max(onset, self.last_step)
            self.last_onset = onset
        if state != "MOVING":
            self.moving_since = None
        self.state = state

        if self.checkin_at is None and self.prov_at is None and self.cur == self.O:
            # The MOVING must have STARTED after the last step: walk -> stand -> the
            # train moves you. MOVING carried over from walking (the state machine
            # needs 10 still seconds to flip) is the rider's own momentum fading,
            # not a train - that exact hangover fired a false provisional on ride C.
            fresh = self.moving_since is not None and \
                    (self.last_step is None or self.moving_since > self.last_step)
            if fresh and t - self.moving_since >= PROV_MOVE_S:
                self.prov_at = t; self.prov_boarded = self.last_onset
                self.ev(t, "CHECKIN_PROVISIONAL", boarded=self.prov_boarded)
                self._predict_from_departure(self.prov_boarded)

        if self.prov_at is not None and self.checkin_at is None and t - self.prov_at > PROV_TIMEOUT_S:
            self.ev(t, "CHECKIN_PROVISIONAL_CANCELLED", reason="no TAC change")
            self.prov_at = None; self.prov_boarded = None; self.pred_next = None

    # ---- TAC: position, confirmation, cold start ------------------------------
    def on_tac(self, t, tac):
        i = self.m.tac2idx.get(tac)
        if self.first_tac:
            self.first_tac = False
            if i is not None and (i - self.O) * self.dir > 0:      # cold start mid-journey
                self.checkin_at = t; self.boarded = None
                self.ev(t, "CHECKIN_COLDSTART", station=self.m.names[i])
                self._arrive(i, t, cold=True)
                return
        if i is None or self.done:
            return
        ahead = (i - self.cur) * self.dir
        if ahead > 0:
            if self.cand != i: self.cand, self.cand_since = i, t
            need = PERSIST_S if (self.checkin_at or self.prov_at) else CONFIRM_S
            if t - self.cand_since >= need:
                self._arrive(i, self.cand_since)
        else:
            if self.checkin_at is None and ahead < 0 and self.cur == self.O:
                if self.cand != i: self.cand, self.cand_since = i, t
                if t - self.cand_since >= CONFIRM_S and not self.wrong_dir:
                    self.wrong_dir = True           # alert once, not every reading
                    self.ev(t, "WRONG_DIRECTION", towards=self.m.names[i])
                return
            self.cand = None   # behind us or same station: ping-pong, ignore

    def _arrive(self, i, t, cold=False):
        if self.checkin_at is None:
            self.checkin_at = t
            self.boarded = self.prov_boarded if self.prov_boarded is not None else self.last_onset
            self.ev(t, "CHECKIN_CONFIRMED", boarded=self.boarded,
                    provisional_at=self.prov_at)
        err = None if self.pred_next is None or cold else t - self.pred_next
        self.cur, self.cur_at, self.cand = i, t, None
        self.ev(t, "ARRIVING", station=self.m.names[i], stops=abs(i - self.O), eta_err=err)
        if i == self.D:
            self.ev(t, "ARRIVING_DESTINATION", station=self.m.names[i]); self.done = True
            self.pred_next = None; return
        if (i - self.D) * self.dir > 0:
            self.ev(t, "PASSED_DESTINATION"); self.done = True; return
        if i + self.dir == self.D:
            self.ev(t, "NEXT_STOP_IS_DESTINATION", station=self.m.names[self.D])
        leg, _ = self.m.leg_s(i, i + self.dir)
        self.pred_next = t + leg

    def _predict_from_departure(self, dep):
        a, b = self.O, self.O + self.dir
        seg = self.m.segs[min(a, b)].get("dep_to_tac_s", {})
        own = seg.get("fwd" if b > a else "rev", [])
        if own and self.m.exclude != "C":          # ride C is the only source so far
            self.pred_next = dep + statistics.median(own)
        else:
            leg, _ = self.m.leg_s(a, b)
            self.pred_next = dep + leg - self.m.d["tac_to_departure_s"]

    def eta(self, now):
        """(seconds to next station, seconds to destination) or None."""
        if self.pred_next is None or self.done: return None
        nxt = max(0, self.pred_next - now)
        dst = nxt; k = self.cur + self.dir
        while k != self.D:
            dst += self.m.leg_s(k, k + self.dir)[0]; k += self.dir
        return nxt, dst


def load(path):
    rows = [json.loads(l) for l in open(path) if l.strip()]
    t0 = rows[0]["ts"]; T = lambda ms: (ms - t0) / 1000
    ev, taps, leg = [], [], None
    for r in rows:
        if r["type"] == "tick":
            ev.append((T(r["ts"]), 1, "tick", r["state"], r.get("steps", 0)))
        elif r["type"] == "cells":
            s = next((c for c in r["towers"] if c.get("reg") and c["cid"] > 0
                      and c["cid"] != 268435455), None)
            if s: ev.append((T(r["ts"]), 0, "tac", s["tac"]))
        elif r["type"] == "event":
            if r["label"] == "SESSION_START": leg = r
            elif r["label"] in ("ENTERED_STATION", "ON_PLATFORM", "DOORS_CLOSED", "TRAIN_MOVED",
                                "PASSED_STATION", "DOORS_OPEN", "STEPPED_OFF"):
                taps.append((T(r["ts"]), r["label"]))
    ev.sort(key=lambda e: (e[0], e[1]))
    return ev, taps, leg


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("trace"); ap.add_argument("--map", default="tacmap.json")
    ap.add_argument("--exclude-ride"); ap.add_argument("--cold-start-at", type=float)
    a = ap.parse_args()
    m = TacMap(a.map, a.exclude_ride)
    ev, taps, leg = load(a.trace)
    O = m.names.index(leg["origin"]); D = m.names.index(leg["dest"])
    tr = TacTracker(m, O, D)
    for e in ev:
        if a.cold_start_at is not None and e[0] < a.cold_start_at: continue
        if e[2] == "tick": tr.on_tick(e[0], e[3], e[4])
        else: tr.on_tac(e[0], e[3])
    print(f"{leg['origin']} -> {leg['dest']}" + (f"   (cold start at t+{a.cold_start_at:.0f})" if a.cold_start_at else ""))
    rows = [(t, "TAP", l, {}) for t, l in taps] + [(t, "TRACKER", l, kw) for t, l, kw in tr.log]
    for t, src, l, kw in sorted(rows, key=lambda r: r[0]):
        extra = "  ".join(f"{k}={('t+%.0f' % v) if k in ('boarded','provisional_at') and v is not None else v}"
                          for k, v in kw.items())
        print(f"  t+{t:6.0f}  {src:<8} {l:<28} {extra}")
