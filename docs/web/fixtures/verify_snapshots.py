"""Verify the shape of the engine snapshot fixtures (README.md).

Usage: python docs/web/fixtures/verify_snapshots.py [path/to/snapshots.json]
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
p = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "snapshots.json")
raw = open(p, encoding="utf-8").read()
doc = json.loads(raw)
snaps = doc["snapshots"]
print("bytes:", len(raw), "keys:", list(doc.keys()), "snapshots:", len(snaps))

expected_labels = ["01-ref-t0", "01-ref-t12", "01-ref-final", "01-never-t30", "01-never-final",
                   "02-ref-t12", "02-ref-t46", "02-ref-final",
                   "06-ref-t0", "06-ref-t12", "06-ref-t24", "06-ref-final", "06-fail-final"]
got = [s["label"] for s in snaps]
print("labels ok:", got == expected_labels, got)

required = ["label","mission_id","mission_name","briefing","seed","wind_dir","wind_force",
            "objectives_readable","objectives_done","time_limit","tick","tick_limit","status",
            "failure_reason","halt_reason","firmware_bytes","terrain","wall_vert","wall_horiz",
            "wall_count","fire","fire_cells",
            "fire_intensity_total","packets","rover","stats","vm","ports","mission_bits",
            "civilians","events","recent_events","fingerprint"]
for s in snaps:
    missing = [k for k in required if k not in s]
    assert not missing, (s["label"], missing)
    for key in ("terrain", "fire"):
        g = s[key]
        assert len(g) == 20, (s["label"], key, len(g))
        bad = [(i, len(r)) for i, r in enumerate(g) if len(r) != 20]
        assert not bad, (s["label"], key, bad)
    tg = set("".join(s["terrain"]))
    assert tg <= set(".#FWEPRC"), (s["label"], tg)
    # E4 (PLAN-EDGE-WALLS.md §13.7 item 4): the thin-wall rows. WALL_VERT is 20
    # rows x 19 glyphs (the edge RIGHT of each cell), WALL_HORIZ 19 x 20 (the
    # edge BELOW each cell); a blocked edge is '#', an open one '.'.
    wv = s["wall_vert"]
    assert len(wv) == 20, (s["label"], "wall_vert", len(wv))
    bad = [(i, len(r)) for i, r in enumerate(wv) if len(r) != 19]
    assert not bad, (s["label"], "wall_vert", bad)
    assert set("".join(wv)) <= set(".#"), (s["label"], "wall_vert", set("".join(wv)))
    wh = s["wall_horiz"]
    assert len(wh) == 19, (s["label"], "wall_horiz", len(wh))
    bad = [(i, len(r)) for i, r in enumerate(wh) if len(r) != 20]
    assert not bad, (s["label"], "wall_horiz", bad)
    assert set("".join(wh)) <= set(".#"), (s["label"], "wall_horiz", set("".join(wh)))
    assert s["wall_count"] == "".join(wv).count("#") + "".join(wh).count("#"), \
        (s["label"], "wall_count vs '#'-glyph count", s["wall_count"])
    fg = set("".join(s["fire"]))
    assert fg <= set(".1234"), (s["label"], fg)
    vm = s["vm"]
    for k in ("registers","flags","pc","pc_hex","pc_insn","pc_line_number","pc_label",
              "instruction","last_op","instruction_ticks_remaining","source"):
        assert k in vm, (s["label"], k)
    for k in ("x","y","dir","hp","heat","water","shield_ticks"):
        assert k in s["rover"], (s["label"], k)
    assert len(s["fingerprint"]) == 16 and all(c in "0123456789abcdef" for c in s["fingerprint"])
    for port in ("FIRE_DIST","FIRE_ANGLE","FIRE_LEVEL","CIV_COUNT","EXIT_DIST","EXIT_ANGLE",
                 "WIND_DIR","WIND_FORCE","MISSION","WATER","LOCAL_FIRE","SMOKE","TILE"):
        assert port in s["ports"], (s["label"], port)
    assert "RAND" not in s["ports"]
    print(f'{s["label"]:14} tick={s["tick"]:>3}/{s["tick_limit"]:<4} {s["status"]:7} '
          f'reason={str(s["failure_reason"]):12} rover=({s["rover"]["x"]},{s["rover"]["y"]},{s["rover"]["dir"]}) '
          f'hp={s["rover"]["hp"]} heat={s["rover"]["heat"]} water={s["rover"]["water"]} '
          f'fire={s["fire_intensity_total"]:>3} burning={s["stats"]["burning_cells"]} '
          f'walls={s["wall_count"]} '
          f'pkts={len(s["packets"])} insn={vm["instruction"]!r} pc={vm["pc_hex"]} line={vm["pc_line_number"]} '
          f'label={vm["pc_label"]} obj_done={s["objectives_done"]} hash={s["fingerprint"]}')

# engine-consistency spot checks
n = {s["label"]: s for s in snaps}
assert n["01-never-final"]["status"] == "FAILURE" and n["01-never-final"]["failure_reason"] == "TIME_EXPIRED"
assert n["01-never-final"]["tick"] == n["01-never-final"]["time_limit"] == 60
assert n["01-ref-final"]["status"] == "SUCCESS" and n["01-ref-final"]["fire_intensity_total"] == 0
assert n["02-ref-final"]["status"] == "SUCCESS" and n["02-ref-final"]["fire_intensity_total"] == 0
assert "ON_EXIT" in n["02-ref-final"]["mission_bits"]["readable"]
assert n["01-ref-t0"]["tick"] == 0 and n["01-ref-t0"]["events"] == []
# E4: the thin-wall mission rows. Static layout state: every mission-06 snapshot
# carries the same three edge walls, the same 20x19 / 19x20 row glyphs, and the
# wall rows are all-open on the five wall-free missions. Mission 06 walls as
# authored in Missions.kt (PLAN-EDGE-WALLS.md §13.1): the lane gate on the edge
# right of (5,2) plus the spawn cell's north and south edges.
for s in snaps:
    if str(s["mission_id"]) == "6":
        assert s["wall_count"] == 3, (s["label"], s["wall_count"])
    else:
        assert s["wall_count"] == 0, (s["label"], s["wall_count"])
w6 = {k: n[k] for k in ("06-ref-t0", "06-ref-t12", "06-ref-t24", "06-ref-final", "06-fail-final")}
assert all(v["wall_vert"] == w6["06-ref-t0"]["wall_vert"] and v["wall_horiz"] == w6["06-ref-t0"]["wall_horiz"] for v in w6.values()), \
    "wall rows are layout state: identical across ticks and firmwares"
assert w6["06-ref-t0"]["wall_vert"][2][5] == "#", "lane gate on the edge right of (5,2)"
assert w6["06-ref-t0"]["wall_horiz"][1][2] == "#", "spawn cell's north edge"
assert w6["06-ref-t0"]["wall_horiz"][2][2] == "#", "spawn cell's south edge"
w6count = sum(row.count("#") for row in w6["06-ref-t0"]["wall_vert"] + w6["06-ref-t0"]["wall_horiz"])
assert w6count == 3, w6count
assert n["06-ref-final"]["status"] == "SUCCESS" and n["06-ref-final"]["tick"] == 59
assert n["06-fail-final"]["status"] == "FAILURE" and n["06-fail-final"]["failure_reason"] == "ROVER_DESTROYED"
print("ALL CHECKS PASSED")
