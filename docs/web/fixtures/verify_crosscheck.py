"""Cross-check snapshots.json against a CrossCheck.kt TSV dump (README.md).

Usage: python docs/web/fixtures/verify_crosscheck.py [path/to/crosscheck.tsv]
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SCR = sys.argv[1] if len(sys.argv) > 1 else HERE
doc = json.load(open(os.path.join(SCR, "snapshots.json"), encoding="utf-8"))
n = {s["label"]: s for s in doc["snapshots"]}
try:
    bonus = json.load(open(os.path.join(SCR, "snapshots.extra.json"), encoding="utf-8"))
    n.update({s["label"]: s for s in bonus["snapshots"]})
except FileNotFoundError:
    pass
rows = [l.split("\t") for l in open(os.path.join(SCR, "crosscheck.tsv"), encoding="utf-8").read().strip().split("\n")]

fails = []
for parts in rows:
    kind, label = parts[0], parts[1]
    if kind == "ACTIVITY":
        print(f"ACTIVITY {label:14} {parts[2]}")
        continue
    if kind == "WALL":
        # E4: the engine's wall glyph rows ('#' blocked, '.' open), vert 20x19
        # then horiz 19x20, comma-separated on one TSV line.
        rows = parts[2].split(",")
        assert len(rows) == 39, (label, len(rows))
        s = n[label]
        assert rows[:20] == s["wall_vert"], (label, "wall_vert")
        assert rows[20:] == s["wall_horiz"], (label, "wall_horiz")
        assert sum(r.count("#") for r in rows) == s["wall_count"], (label, "wall_count")
        print(f"WALL  {label:14} cross-checked 39 wall rows: OK")
        continue
    s = n[label]
    if kind == "PROBE":
        tick, status, reason, x, y, hp, heat, water, fire, pkts, shots, moves, h, wv, wh, wt = parts[2:]
        want = [
            ("tick", int(tick), s["tick"]),
            ("status", status, s["status"]),
            ("failure_reason", None if reason == "-" else reason, s["failure_reason"]),
            ("rover.x", int(x), s["rover"]["x"]),
            ("rover.y", int(y), s["rover"]["y"]),
            ("rover.hp", int(hp), s["rover"]["hp"]),
            ("rover.heat", int(heat), s["rover"]["heat"]),
            ("rover.water", int(water), s["rover"]["water"]),
            ("fire_intensity_total", int(fire), s["fire_intensity_total"]),
            ("packets", int(pkts), len(s["packets"])),
            ("stats.shots", int(shots), s["stats"]["shots"]),
            ("stats.moves", int(moves), s["stats"]["moves"]),
            ("fingerprint", h, s["fingerprint"]),
            ("wall_vert '#'", int(wv), "".join(s["wall_vert"]).count("#")),
            ("wall_horiz '#'", int(wh), "".join(s["wall_horiz"]).count("#")),
            ("wall_count", int(wt), s["wall_count"]),
        ]
    else:  # FINAL, produced by FirelineRun.runWithGuard (headless authority path)
        tick, status, reason, hp, heat, water, fire, burning, h, identical, wv, wh, wt = parts[2:]
        assert identical.startswith("runner==stepByStep:true"), (label, identical)
        want = [
            ("tick", int(tick), s["tick"]),
            ("status", status, s["status"]),
            ("failure_reason", None if reason == "-" else reason, s["failure_reason"]),
            ("rover.hp", int(hp), s["rover"]["hp"]),
            ("rover.heat", int(heat), s["rover"]["heat"]),
            ("rover.water", int(water), s["rover"]["water"]),
            ("fire_intensity_total", int(fire), s["fire_intensity_total"]),
            ("stats.burning_cells", int(burning), s["stats"]["burning_cells"]),
            ("fingerprint", h, s["fingerprint"]),
            ("wall_vert '#'", int(wv), "".join(s["wall_vert"]).count("#")),
            ("wall_horiz '#'", int(wh), "".join(s["wall_horiz"]).count("#")),
            ("wall_count", int(wt), s["wall_count"]),
        ]
    for name, a, b in want:
        if a != b:
            fails.append((label, kind, name, a, b))
    print(f"{kind:5} {label:14} cross-checked {len(want)} fields: {'OK' if all(a == b for _, a, b in want) else 'MISMATCH'}")

print("CROSSCHECK", "PASSED" if not fails else f"FAILED {fails}")
