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
                   "02-ref-t12", "02-ref-t46", "02-ref-final"]
got = [s["label"] for s in snaps]
print("labels ok:", got == expected_labels, got)

required = ["label","mission_id","mission_name","briefing","seed","wind_dir","wind_force",
            "objectives_readable","objectives_done","time_limit","tick","tick_limit","status",
            "failure_reason","halt_reason","firmware_bytes","terrain","fire","fire_cells",
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
print("ALL CHECKS PASSED")
