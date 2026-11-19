#!/usr/bin/env python3
"""Query OpenCellID correctly and compare it with the cells we actually recorded.

Two gotchas the plain curl hits:
  * BBOX is LATITUDE first: latmin,lonmin,latmax,lonmax. Longitude-first silently
    returns {"count":0,"cells":[]} because the box lands in the sea.
  * A box may cover at most 4,000,000 m2 (~2x2 km), so any real route must be tiled.

Key comes from $OPENCELLID_KEY (or --key); it is never written to disk by this script.

  export OPENCELLID_KEY=pk.xxxx
  tools/opencellid.py --stations                      # our 5 metro stations
  tools/opencellid.py --point 13.0368,80.246419 --km 2
  tools/opencellid.py --line 13.0368,80.246419 13.008669,80.212606 --km 1.5
  tools/opencellid.py --stations --compare            # + how many of OUR cells it knows
  tools/opencellid.py --lookup                        # ask /cell/get for every cell we recorded

Note: /cell/get finds cells that getInArea does not return for the same spot, so --lookup is
the honest coverage test (28/69 of our cells vs 2/69 via the area scan).
"""
import argparse, json, math, os, sys, time, urllib.parse, urllib.request, collections

API = "https://opencellid.org/cell/getInArea"
API_GET = "https://opencellid.org/cell/get"
MAX_SIDE_M = 1900.0          # keep each tile under the 4 km2 server limit
STATIONS = [("Teynampet", 13.0368, 80.246419), ("Nandanam", 13.031709, 80.241196),
            ("Saidapet", 13.02331, 80.223719), ("Little Mount", 13.014663, 80.224167),
            ("Guindy", 13.008669, 80.212606)]


def dist_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = (math.sin((p2 - p1) / 2) ** 2 +
         math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2)
    return 2 * r * math.asin(math.sqrt(a))


def fetch(key, lat, lon, side_m, **filters):
    """One tile centred on lat/lon. Returns the cell list (empty on error)."""
    dlat = (side_m / 2) / 111320.0
    dlon = (side_m / 2) / (111320.0 * math.cos(math.radians(lat)))
    p = {"key": key, "format": "json", "limit": 1000,
         "BBOX": f"{lat-dlat:.6f},{lon-dlon:.6f},{lat+dlat:.6f},{lon+dlon:.6f}"}
    p.update({k: v for k, v in filters.items() if v})
    req = urllib.request.Request(API + "?" + urllib.parse.urlencode(p), headers={"User-Agent": "curl/8"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            body = json.loads(r.read().decode())
    except Exception as e:
        body = {"error": str(e)}
    if "cells" not in body:
        print(f"    ! {body}", file=sys.stderr)
        return []
    return body["cells"]


def lookup(key, mcc, mnc, lac, cellid):
    """Ask OpenCellID about ONE cell. Finds records getInArea misses."""
    p = {"key": key, "format": "json", "mcc": mcc, "mnc": mnc, "lac": lac, "cellid": cellid}
    req = urllib.request.Request(API_GET + "?" + urllib.parse.urlencode(p), headers={"User-Agent": "curl/8"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        try:
            return json.loads(e.read().decode())
        except Exception:
            return {"error": str(e)}


def tile_points(a, b, step_m):
    """Centres covering the straight line a->b."""
    n = max(1, int(math.ceil(dist_m(*a, *b) / step_m)))
    return [(a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n) for i in range(n + 1)]


def our_cells(root="."):
    """(tac, cellId) we recorded, with the station we saw them at."""
    tacn = {42144: "Teynampet", 42142: "Nandanam", 45127: "Saidapet",
            45129: "Little Mount", 42133: "Guindy", 42134: "Guindy"}
    out = collections.Counter()

    def add(tac, cid):
        if tac and cid and cid > 0 and cid != 268435455:
            out[(tac, cid, tacn.get(tac, "?"))] += 1

    for p in [os.path.expanduser("~/Downloads/telephony-log.txt"),
              os.path.expanduser("~/Downloads/telephony-log (1).txt")]:
        if not os.path.exists(p):
            continue
        for line in open(p):
            if not line.strip():
                continue
            for c in json.loads(line)["towers"]:
                if c.get("isRegistered") and c.get("cellType") == "LTE":
                    add(c["areaCode"], int(c["cellId"]))
    for p in ["traces/real/trace-20260910-115936.jsonl",
              "traces/real2/traces/trace-20260910-202819.jsonl",
              "traces/real3/decisions-cell-towers-20260911-121524.jsonl",
              "traces/real4/trace-20260915-120347.jsonl"]:
        p = os.path.join(root, p)
        if not os.path.exists(p):
            continue
        for line in open(p):
            if not line.strip():
                continue
            r = json.loads(line)
            if not (r.get("type") == "cells" or r.get("label") == "TOWER_READ"):
                continue
            s = next((c for c in r["towers"] if c.get("reg") and c.get("tac") is not None), None)
            if s:
                add(s["tac"], s["cid"])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", default=os.environ.get("OPENCELLID_KEY"))
    ap.add_argument("--stations", action="store_true", help="scan our 5 metro stations")
    ap.add_argument("--point", help="lat,lon")
    ap.add_argument("--line", nargs=2, metavar=("LAT,LON", "LAT,LON"), help="scan along a line")
    ap.add_argument("--km", type=float, default=2.0, help="tile side in km (clamped to 1.9)")
    ap.add_argument("--mcc", type=int, default=404)
    ap.add_argument("--mnc", type=int, help="40=Airtel Chennai; omit for all operators")
    ap.add_argument("--radio", default="LTE", help="LTE/GSM/UMTS/NR, or '' for all")
    ap.add_argument("--compare", action="store_true", help="compare with cells we recorded")
    ap.add_argument("--lookup", action="store_true", help="ask /cell/get for every cell we recorded")
    ap.add_argument("--out", help="write the cells to this JSON file")
    a = ap.parse_args()
    if not a.key:
        sys.exit("no key: export OPENCELLID_KEY=pk.xxx (or pass --key)")

    if a.lookup:
        ours = our_cells()
        st = {s[0]: (s[1], s[2]) for s in STATIONS}
        found, missing = [], 0
        for tac, cid, stn in sorted({(t, c, s) for t, c, s in ours}):
            r = lookup(a.key, a.mcc, a.mnc or 40, tac, cid)
            if "lat" in r:
                d = dist_m(r["lat"], r["lon"], *st[stn]) if stn in st else float("nan")
                found.append((tac, cid, stn, r, d))
                print(f"  tac {tac} cid {cid:10} -> {r['lat']:.4f},{r['lon']:.4f} "
                      f"range {r.get('range')}m samples {r.get('samples')}  {d:.0f} m from {stn}")
            else:
                missing += 1
            time.sleep(0.35)
        known = [f for f in found if f[2] in st]
        errs = sorted(f[4] for f in known)
        print(f"\n  known to OpenCellID: {len(found)}/{len(set((t,c) for t,c,_ in ours))}"
              f"   not found: {missing}")
        if errs:
            print(f"  distance from the station we saw them at: min {errs[0]:.0f} m, "
                  f"median {errs[len(errs)//2]:.0f} m, max {errs[-1]:.0f} m")
        return

    side = min(a.km * 1000, MAX_SIDE_M)
    pts = []
    if a.stations:
        pts += [(s[1], s[2]) for s in STATIONS]
    if a.point:
        lat, lon = map(float, a.point.split(","))
        pts.append((lat, lon))
    if a.line:
        p1 = tuple(map(float, a.line[0].split(",")))
        p2 = tuple(map(float, a.line[1].split(",")))
        pts += tile_points(p1, p2, side * 0.8)      # overlap so boundaries aren't missed
    if not pts:
        sys.exit("give --stations, --point or --line")

    cells = {}
    for i, (lat, lon) in enumerate(pts, 1):
        got = fetch(a.key, lat, lon, side, mcc=a.mcc, mnc=a.mnc, radio=a.radio)
        for c in got:
            cells[(c.get("lac"), c.get("cellid"))] = c
        print(f"  tile {i}/{len(pts)} {lat:.5f},{lon:.5f} -> {len(got)} cells (total {len(cells)})")
        time.sleep(1)                                # be polite; free keys are rate limited

    print(f"\n{len(cells)} distinct cells")
    tacs = collections.Counter(k[0] for k in cells)
    print("TACs:", dict(tacs.most_common(12)))
    samples = collections.Counter(c.get("samples") for c in cells.values())
    print("samples per cell:", dict(samples.most_common(5)),
          " ranges:", sorted({c.get("range") for c in cells.values()})[:8])

    if a.compare:
        ours = our_cells()
        keys = {(t, c) for t, c, _ in ours}
        found = [k for k in keys if k in cells]
        print(f"\nour recorded cells: {len(keys)}   known to OpenCellID: {len(found)}")
        st = {s[0]: (s[1], s[2]) for s in STATIONS}
        for tac, cid in sorted(found):
            c = cells[(tac, cid)]
            stn = next((s for t, i, s in ours if (t, i) == (tac, cid)), "?")
            d = dist_m(c["lat"], c["lon"], *st[stn]) if stn in st else float("nan")
            print(f"  tac {tac} cid {cid:10} -> OpenCellID {c['lat']:.5f},{c['lon']:.5f} "
                  f"range {c.get('range')}m samples {c.get('samples')}  = {d:.0f} m from {stn}")

    if a.out:
        json.dump(list(cells.values()), open(a.out, "w"), indent=1)
        print(f"\nwrote {a.out}")


if __name__ == "__main__":
    main()
