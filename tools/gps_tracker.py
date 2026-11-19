#!/usr/bin/env python3
"""
Reference implementation of the GPS-only tracker - the logic GpsTracker.kt runs in
the GPS build, for comparison against the TAC tracker. Keep in sync with GpsTracker.kt.

  tools/gps_tracker.py trace.jsonl [--origin Teynampet --dest Guindy]

Reads the GPS build's traces ({"type":"gps",...}) or the original telephony-log.txt
format ({"gps":{...}}).

Rules, deliberately mirroring TacTracker so the comparison is about the SENSOR:
  * A fix is trusted only if accuracy <= ACC_MAX_M and it doesn't imply an
    impossible jump from the last trusted fix (> MAX_SPEED_MPS). Ride A reported
    a 1407m jump in 8s with "20m accuracy" - the accuracy field alone lies.
  * Station = within STATION_R_M of the station's coordinates. Position only moves
    forward in the direction of travel.
  * Check-in: PROVISIONAL once trusted fixes near the origin show moving speed for
    PROV_MOVE_S; CONFIRMED on reaching the next station, or on getting CONFIRM_DIST_M
    away from the origin along the direction of travel.
  * Underground there are no trusted fixes: the tracker says so (GPS_LOST) and catches
    up when fixes return - stations passed while blind are counted late, not missed.
  * ETA uses the same measured leg times as the TAC tracker.
"""
import json, math, argparse, statistics, datetime as dt, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from tac_tracker import TacMap

ACC_MAX_M = 100
MAX_SPEED_MPS = 35         # ~126 km/h; metro tops out ~80 km/h
STATION_R_M = 250          # stations are >= 800m apart on this line
ORIGIN_R_M = 350
MOVING_MPS = 4.0           # ~14 km/h: faster than walking, slower than any train cruise
PROV_MOVE_S = 10
CONFIRM_DIST_M = 500
GPS_LOST_S = 30


def hav(a, b):
    R = 6371000; p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1; dl = math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


class GpsTracker:
    def __init__(self, tmap, origin, dest):
        self.m = tmap; self.O = origin; self.D = dest
        self.dir = 1 if dest >= origin else -1
        self.pos = [(s["lat"], s["lon"]) for s in tmap.stations]
        self.cur = origin; self.cur_at = None
        self.prov_at = None; self.prov_boarded = None; self.checkin_at = None; self.boarded = None
        self.pred_next = None; self.first = True; self.done = False; self.wrong_dir = False
        self.last = None            # last trusted fix (t, lat, lon)
        self.moving_since = None; self.still_last = None
        self.lost = False; self.log = []

    def ev(self, t, label, **kw): self.log.append((t, label, kw))

    def on_tick(self, t):
        """Called periodically so a silent GPS (underground) is noticed."""
        if self.last and not self.lost and t - self.last[0] > GPS_LOST_S:
            self.lost = True; self.ev(t, "GPS_LOST", last_fix=self.last[0])

    def on_fix(self, t, lat, lon, acc, speed=None):
        if acc is None or acc > ACC_MAX_M: return self.on_tick(t)
        p = (lat, lon)
        if self.last:
            dtm = t - self.last[0]
            if dtm <= 0: return
            jump = hav(self.last[1:], p) / dtm
            if jump > MAX_SPEED_MPS: return self.on_tick(t)       # teleport - distrust
            v = speed if speed is not None else jump
        else:
            v = speed if speed is not None else 0.0
        if self.lost:
            self.ev(t, "GPS_BACK", gap_s=round(t - self.last[0]))
            self.lost = False
        self.last = (t, lat, lon)

        # nearest station, forward-only
        d = [hav(p, s) for s in self.pos]
        i = min(range(len(d)), key=lambda k: d[k])
        if self.first:
            self.first = False
            if d[i] <= STATION_R_M and (i - self.O) * self.dir > 0:
                self.checkin_at = t; self.ev(t, "CHECKIN_COLDSTART", station=self.m.names[i])
                return self._arrive(i, t, cold=True)

        if self.done or self.wrong_dir: return
        # check-in
        if self.checkin_at is None:
            near_origin = d[self.O] <= ORIGIN_R_M
            if v >= MOVING_MPS:
                if self.moving_since is None: self.moving_since = t
            else:
                self.moving_since = None; self.still_last = t
            if self.prov_at is None and near_origin and self.moving_since is not None \
                    and t - self.moving_since >= PROV_MOVE_S:
                self.prov_at = t
                self.prov_boarded = self.still_last if self.still_last is not None else self.moving_since
                self.ev(t, "CHECKIN_PROVISIONAL", boarded=self.prov_boarded)
                leg, _ = self.m.leg_s(self.O, self.O + self.dir)
                self.pred_next = self.prov_boarded + leg - self.m.d["tac_to_departure_s"]
            behind = self.O - self.dir
            if 0 <= behind < len(self.pos) and d[behind] <= STATION_R_M:
                self.wrong_dir = True; self.ev(t, "WRONG_DIRECTION", towards=self.m.names[behind]); return
            # moved well away from the origin in the right direction without reaching a station
            ahead = self.O + self.dir
            if 0 <= ahead < len(self.pos) and d[self.O] >= CONFIRM_DIST_M and d[ahead] < hav(self.pos[self.O], self.pos[ahead]):
                self._confirm(t)
        if d[i] <= STATION_R_M and (i - self.cur) * self.dir > 0:
            self._arrive(i, t)

    def _confirm(self, t):
        if self.checkin_at is not None: return
        self.checkin_at = t
        self.boarded = self.prov_boarded if self.prov_boarded is not None else self.still_last
        self.ev(t, "CHECKIN_CONFIRMED", boarded=self.boarded, provisional_at=self.prov_at)

    def _arrive(self, i, t, cold=False):
        self._confirm(t)
        skipped = abs(i - self.cur) - 1
        err = None if self.pred_next is None or cold or skipped else t - self.pred_next
        self.cur, self.cur_at = i, t
        self.ev(t, "ARRIVING", station=self.m.names[i], stops=abs(i - self.O), eta_err=err,
                **({"skipped_while_blind": skipped} if skipped > 0 else {}))
        if i == self.D:
            self.done = True; self.pred_next = None
            self.ev(t, "ARRIVING_DESTINATION", station=self.m.names[i]); return
        if (i - self.D) * self.dir > 0:
            self.done = True; self.ev(t, "PASSED_DESTINATION"); return
        if i + self.dir == self.D:
            self.ev(t, "NEXT_STOP_IS_DESTINATION", station=self.m.names[self.D])
        leg, _ = self.m.leg_s(i, i + self.dir)
        self.pred_next = t + leg


def load(path):
    """Yields (t_seconds, lat, lon, acc, speed) from either trace format, plus taps."""
    fixes, taps, leg, t0 = [], [], None, None
    for line in open(path):
        if not line.strip(): continue
        r = json.loads(line)
        if "gps" in r and isinstance(r["gps"], dict):             # telephony-log.txt format
            ts = dt.datetime.fromisoformat(r["ts"].replace("Z", "+00:00")).timestamp() * 1000
            g = r["gps"]
            if t0 is None: t0 = ts
            fixes.append(((ts - t0) / 1000, g["lat"], g["lng"], g["accuracy"], None, g["ts"]))
            continue
        if t0 is None: t0 = r["ts"]
        t = (r["ts"] - t0) / 1000
        if r.get("type") == "gps":
            fixes.append((t, r["lat"], r["lon"], r.get("acc"), r.get("speed"), r.get("fixTime")))
        elif r.get("type") == "event":
            if r["label"] == "SESSION_START": leg = r
            elif r["label"] in ("TRAIN_MOVED", "DOORS_OPEN", "DOORS_CLOSED", "STEPPED_OFF", "ENTERED_STATION"):
                taps.append((t, r["label"]))
    # drop repeated (stale) fixes: same fix timestamp reported again
    out, seen = [], set()
    for f in fixes:
        if f[5] is not None and f[5] in seen: continue
        if f[5] is not None: seen.add(f[5])
        out.append(f)
    return out, taps, leg


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("trace"); ap.add_argument("--map", default="tacmap.json")
    ap.add_argument("--origin"); ap.add_argument("--dest")
    a = ap.parse_args()
    m = TacMap(a.map)
    fixes, taps, leg = load(a.trace)
    o = a.origin or (leg or {}).get("origin"); d = a.dest or (leg or {}).get("dest")
    tr = GpsTracker(m, m.names.index(o), m.names.index(d))
    for t, lat, lon, acc, spd, _ in fixes:
        tr.on_fix(t, lat, lon, acc, spd)
    trusted = sum(1 for f in fixes if f[3] is not None and f[3] <= ACC_MAX_M)
    print(f"{o} -> {d}: {len(fixes)} fixes, {trusted} with accuracy <= {ACC_MAX_M}m")
    rows = [(t, "TAP", l, {}) for t, l in taps] + [(t, "GPS-TRK", l, kw) for t, l, kw in tr.log]
    for t, src, l, kw in sorted(rows, key=lambda r: r[0]):
        extra = "  ".join(f"{k}={('t+%.0f' % v) if k in ('boarded', 'provisional_at', 'last_fix') and v is not None else v}"
                          for k, v in kw.items())
        print(f"  t+{t:6.0f}  {src:<8} {l:<26} {extra}")
