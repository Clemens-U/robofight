#!/usr/bin/env python3
"""W2 fixture pipeline: turn the generator's recorded TSVs into (a) docs-side JSON
fixtures a browser container can check itself against, and (b) the pinned Kotlin
fixtures that `:fireline-engine` commonTest asserts on jvm AND wasmJs.

Order of work (full recipe in docs/web/fixtures/README.md):

  1. docs/web/fixtures/ParityMatrix.kt runs against the engine on the JVM and records
     parity_matrix.tsv, parity_traces.txt, parity_arith.tsv, limit_probe.tsv.
  2. This script reads those TSVs, refuses to write if they disagree with each other,
     and emits:
       - docs/web/fixtures/parity_matrix.json / parity_arith.json / parity_traces.json
       - fireline-engine/src/commonTest/kotlin/robofight/fireline/W2ParityFixtures.kt

It never invents a value: everything comes from the TSVs, and every golden trace
hash in parity_traces.txt must equal the trace_hash column of parity_matrix.tsv.

Since PLAN-EDGE-WALLS.md §7 step E2 the matrix also carries the wall fixture rows:
the wall-carrying layouts recorded as canonical mission text. Those rows add the
mission rules the fingerprint depends on, the recorded layout and firmware text,
and the measured canonical text round trip. The wall glyph count recounted here
from the recorded layout text must equal the engine's own edgeCount column.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
KT_OUT = os.path.join(
    REPO, "fireline-engine", "src", "commonTest", "kotlin", "robofight", "fireline",
    "W2ParityFixtures.kt",
)
PROBE_TICK = 12
EXPECTED_MISSION_ROWS = 12  # 6 authored missions x (reference + failing)
EXPECTED_WALL_ROWS = 4  # PLAN-EDGE-WALLS.md §12: three walled layouts plus the wall removed
EXPECTED_ROWS = EXPECTED_MISSION_ROWS + EXPECTED_WALL_ROWS
# Step E5 (PLAN-EDGE-WALLS.md §13) was superseded by PLAN-RECONCILE-MAIN-01.md
# §10 (content decision): every authored mission in Missions.kt now carries thin
# edge walls (2, 25, 11, 18, 40 and 13 segments for missions 01..06, measured in
# parity_matrix.tsv). The isolation gate stays, inverted: every authored mission
# row must record wall_count >= 1, so no authored row can silently lose its wall
# state.
WALL_CARRYING_MISSION_SCENARIOS = {
    "1-reference", "1-failing", "2-reference", "2-failing", "3-reference", "3-failing",
    "4-reference", "4-failing", "5-reference", "5-failing", "6-reference", "6-failing",
}
WALL_SCENARIOS = ["wall-lane-move", "wall-nozzle-seal", "wall-lane-suppress", "wall-open-lane"]
WALL_KINDS = ("wall", "wall-open")
REQUIRED_COLUMNS = [
    "scenario", "mission", "name", "kind", "firmware_label", "probe_tick", "probe_hash",
    "trace_hash", "final_tick", "status", "failure_reason", "final_hash", "asm_ok",
    "asm_bytes", "asm_insns", "seed", "objectives", "time_limit", "survive_until",
    "starting_water", "wind_dir", "wind_force", "max_ticks", "wall_count",
    "layout_source", "firmware_source", "roundtrip",
]
# Canonical mission text section shapes, `Layout.toCanonicalText` (PLAN-EDGE-WALLS.md §4).
LAYOUT_SECTIONS = [("TERRAIN", 20, 20), ("FIRE", 20, 20), ("WALL_VERT", 20, 19), ("WALL_HORIZ", 19, 20)]
SAFE_ESCAPES = {"n", "t", "\\"}


def read_tsv(name):
    with open(os.path.join(HERE, name), encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    meta = [l for l in lines if l.startswith("#")]
    rows = [l.split("\t") for l in lines if l.strip() and not l.startswith("#")]
    return meta, rows[0], rows[1:]


def meta_tag(names, prefix):
    for name in names:
        with open(os.path.join(HERE, name), encoding="utf-8") as fh:
            for line in fh:
                if line.startswith(prefix):
                    return line.strip()[len(prefix):]
    return None


def parse_traces():
    traces, cur = {}, None
    with open(os.path.join(HERE, "parity_traces.txt"), encoding="utf-8") as fh:
        for line in fh.read().splitlines():
            if line.startswith("# "):
                cur = line[2:].split()[0]
                probe = re.search(r"probe=(\d+)", line)
                digest = re.search(r"trace_hash=([0-9a-f]{16})", line)
                if not probe or not digest:
                    raise SystemExit("malformed trace header: " + line)
                traces[cur] = {"probeTick": int(probe.group(1)),
                               "traceHash": digest.group(1), "lines": []}
            elif line.strip() and cur:
                traces[cur]["lines"].append(line)
    return traces


def ks(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def unesc(s):
    """Undo `ParityMatrix.kt`'s esc(): newline, tab and backslash escapes."""
    out, i = [], 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s) and s[i + 1] in SAFE_ESCAPES:
            out.append({"n": "\n", "t": "\t", "\\": "\\"}[s[i + 1]])
            i += 2
            continue
        out.append(s[i])
        i += 1
    return "".join(out)


def esc_problems(raw):
    """The TSV form of a recorded text is a valid Kotlin string body only if every
    backslash in it is one of the three escapes `ParityMatrix.kt` writes."""
    i = 0
    while i < len(raw):
        if raw[i] == '"':
            return ["the recorded text contains a double quote"]
        if raw[i] == "\\":
            if i + 1 >= len(raw) or raw[i + 1] not in SAFE_ESCAPES:
                return ["the recorded text carries an escape this generator cannot pin"]
            i += 2
            continue
        i += 1
    return []


def ktext(raw):
    """Pin one recorded text verbatim: the TSV escape form is the Kotlin escape form."""
    return '"' + raw + '"'


def layout_structure(text):
    """(problems, wall glyph count) for one recorded canonical mission text, counted
    here without the engine so a wall row cannot silently lose or gain a wall."""
    lines = text.split("\n")
    if not lines or not lines[0].startswith("FIRELINE LAYOUT "):
        return ["first line is not the canonical layout header"], 0
    problems, idx, walls = [], 1, 0
    for name, rows, cols in LAYOUT_SECTIONS:
        if idx >= len(lines) or lines[idx] != name:
            return ["section %s missing at line %d" % (name, idx + 1)], 0
        idx += 1
        for r in range(rows):
            if idx + r >= len(lines):
                return ["section %s runs off the end of the recorded text" % name], 0
            if len(lines[idx + r]) != cols:
                problems.append("section %s row %d is %d chars, expected %d"
                                % (name, r, len(lines[idx + r]), cols))
            if name in ("WALL_VERT", "WALL_HORIZ"):
                walls += lines[idx + r].count("#")
        idx += rows
    if idx >= len(lines) or not lines[idx].startswith("ROVER "):
        problems.append("ROVER item missing after the wall sections")
    return problems, walls


def main():
    _, header, rows = read_tsv("parity_matrix.tsv")
    _, _, arith = read_tsv("parity_arith.tsv")
    _, _, limits = read_tsv("limit_probe.tsv")
    traces = parse_traces()
    col = {name: n for n, name in enumerate(header)}
    commit = meta_tag(["parity_matrix.tsv", "parity_arith.tsv", "limit_probe.tsv"], "# commit ")
    engine_tag = meta_tag(["parity_matrix.tsv"], "# engine v")
    engine_version = int(str(engine_tag).lstrip("v")) if engine_tag else 0

    # ---- consistency gate: nothing is written while the recorded data disagrees --
    bad = []
    for name in REQUIRED_COLUMNS:
        if name not in col:
            bad.append("matrix TSV has no %s column" % name)
    if len(rows) != EXPECTED_ROWS:
        bad.append("matrix has %d rows, expected %d (%d mission + %d wall)"
                   % (len(rows), EXPECTED_ROWS, EXPECTED_MISSION_ROWS, EXPECTED_WALL_ROWS))
    if len(traces) != len(rows):
        bad.append("trace scenarios %d != matrix rows %d" % (len(traces), len(rows)))
    mission_rows = [r for r in rows if r[col["kind"]] not in WALL_KINDS]
    wall_rows = [r for r in rows if r[col["kind"]] in WALL_KINDS]
    if len(mission_rows) != EXPECTED_MISSION_ROWS:
        bad.append("%d mission rows, expected %d" % (len(mission_rows), EXPECTED_MISSION_ROWS))
    if len(wall_rows) != EXPECTED_WALL_ROWS:
        bad.append("%d wall rows, expected %d" % (len(wall_rows), EXPECTED_WALL_ROWS))
    if sorted(r[col["scenario"]] for r in wall_rows) != sorted(WALL_SCENARIOS):
        bad.append("wall scenarios are %s, expected %s"
                   % (sorted(r[col["scenario"]] for r in wall_rows), sorted(WALL_SCENARIOS)))
    carried = sorted(r[col["scenario"]] for r in mission_rows if int(r[col["wall_count"]]) > 0)
    if carried != sorted(WALL_CARRYING_MISSION_SCENARIOS):
        bad.append("authored rows carrying edge walls are %s, expected %s (step E5, PLAN-EDGE-WALLS.md §13)"
                   % (carried, sorted(WALL_CARRYING_MISSION_SCENARIOS)))
    for r in rows:
        sc = r[col["scenario"]]
        t = traces.get(sc)
        if t is None:
            bad.append("%s: no trace block" % sc)
            continue
        if t["traceHash"] != r[col["trace_hash"]]:
            bad.append("%s: trace hash %s != matrix %s" % (sc, t["traceHash"], r[col["trace_hash"]]))
        if len(t["lines"]) != PROBE_TICK:
            bad.append("%s: %d trace lines, expected %d" % (sc, len(t["lines"]), PROBE_TICK))
        if t["probeTick"] != int(r[col["probe_tick"]]):
            bad.append("%s: probe tick %d != matrix %s" % (sc, t["probeTick"], r[col["probe_tick"]]))
        for name in ("probe_hash", "trace_hash", "final_hash"):
            if not re.fullmatch(r"[0-9a-f]{16}", r[col[name]]):
                bad.append("%s: %s is not 16 hex digits" % (sc, name))
        if r[col["asm_ok"]] != "true":
            bad.append("%s: firmware did not assemble" % sc)
        if r[col["roundtrip"]] != "true":
            bad.append("%s: the canonical mission text round trip was not clean" % sc)
        if int(r[col["final_tick"]]) > int(r[col["max_ticks"]]):
            bad.append("%s: final tick %s exceeds the guard %s"
                       % (sc, r[col["final_tick"]], r[col["max_ticks"]]))
        if r[col["kind"]] not in WALL_KINDS:
            if r[col["layout_source"]] != "-":
                bad.append("%s: authored mission row carries pinned layout text" % sc)
            walls = int(r[col["wall_count"]])
            if sc in WALL_CARRYING_MISSION_SCENARIOS:
                if walls < 1:
                    bad.append("%s: the step E5 teaching mission must carry edge walls" % sc)
            elif walls != 0:
                bad.append("%s: authored mission row carries wall state" % sc)
            continue
        raw_layout, raw_firmware = r[col["layout_source"]], r[col["firmware_source"]]
        if raw_layout in ("", "-"):
            bad.append("%s: wall row carries no recorded layout text" % sc)
            continue
        if raw_firmware in ("", "-"):
            bad.append("%s: wall row carries no recorded firmware text" % sc)
            continue
        for raw in (raw_layout, raw_firmware):
            for p in esc_problems(raw):
                bad.append("%s: %s" % (sc, p))
        problems, wall_glyphs = layout_structure(unesc(raw_layout))
        for p in problems:
            bad.append("%s: %s" % (sc, p))
        if wall_glyphs != int(r[col["wall_count"]]):
            bad.append("%s: the recorded layout text carries %d wall glyphs, the engine edgeCount says %s"
                       % (sc, wall_glyphs, r[col["wall_count"]]))
    if not commit or not re.fullmatch(r"[0-9a-f]{40}", commit or ""):
        bad.append("commit tag is not a sha: %r" % commit)
    if engine_version < 1:
        bad.append("engine version tag missing")
    if bad:
        print("REFUSED - recorded fixtures are inconsistent:")
        for b in bad:
            print("  " + b)
        return 1

    def write_json(name, payload):
        with open(os.path.join(HERE, name), "w", encoding="utf-8") as fh:
            fh.write(json.dumps(payload, indent=2) + "\n")

    def json_row(r):
        row = dict(zip(header, r))
        if r[col["kind"]] in WALL_KINDS:
            row["layoutText"] = unesc(r[col["layout_source"]])
            row["firmwareText"] = unesc(r[col["firmware_source"]])
        return row

    write_json("parity_matrix.json", {
        "engineVersion": engine_version,
        "sourceCommit": commit,
        "probeTick": PROBE_TICK,
        "generator": "docs/web/fixtures/ParityMatrix.kt",
        "columns": header,
        "wallScenarios": WALL_SCENARIOS,
        "rows": [json_row(r) for r in rows],
    })
    write_json("parity_arith.json", {
        "engineVersion": engine_version,
        "sourceCommit": commit,
        "probes": {n: v for n, v in arith},
    })
    write_json("parity_traces.json", {
        "engineVersion": engine_version,
        "sourceCommit": commit,
        "traces": traces,
    })

    header_doc = [
        "package robofight.fireline",
        "",
        "/**",
        " * W2 parity fixtures (docs/FIRELINE-WEB.md §9 W2, gates G2/G3/G6) plus the edge",
        " * wall fixtures of `PLAN-EDGE-WALLS.md` §7 step E2: the recorded fingerprint",
        " * matrix, golden event traces, 64/32-bit arithmetic vectors and the web",
        " * input-limit probe, asserted identically by `:fireline-engine` on `jvm` and `wasmJs`.",
        " *",
        " * GENERATED - never typed by hand. `docs/web/fixtures/ParityMatrix.kt` records every",
        " * value from live engine state on the JVM (engine v%d, commit %s)," % (engine_version, commit),
        " * `docs/web/fixtures/parity_fixture_gen.py` wrote this file from that output.",
        " * Raw form of the same data: `parity_matrix.tsv`, `parity_traces.txt`,",
        " * `parity_arith.tsv`, `limit_probe.tsv`; JSON form for a browser container:",
        " * `parity_matrix.json`, `parity_traces.json`, `parity_arith.json`. Recipe:",
        " * `docs/web/fixtures/README.md`.",
        " *",
        " * A fingerprint here that moves without a `FIRELINE_ENGINE_VERSION` bump is a",
        " * determinism defect; the same row moving on one target only is an arithmetic or",
        " * container defect (§10 risk: Long/Int drift on wasmJs).",
        " */",
        "",
        "internal const val PARITY_GENERATOR: String = %s" % ks("docs/web/fixtures/ParityMatrix.kt"),
        "internal const val PARITY_ENGINE_VERSION: Int = %d" % engine_version,
        "internal const val PARITY_SOURCE_COMMIT: String = %s" % ks(commit),
        "internal const val PARITY_PROBE_TICK: Int = %d" % PROBE_TICK,
        "internal const val PARITY_EXPECTED_ROWS: Int = %d" % len(rows),
        "internal const val PARITY_EXPECTED_MISSION_ROWS: Int = %d" % EXPECTED_MISSION_ROWS,
        "internal const val PARITY_EXPECTED_WALL_ROWS: Int = %d" % EXPECTED_WALL_ROWS,
        "internal val PARITY_WALL_SCENARIOS: List<String> = listOf(%s)"
        % ", ".join(ks(s) for s in WALL_SCENARIOS),
        "",
        "/**",
        " * One matrix row: one authored mission x one firmware variant, or one E2 wall",
        " * fixture layout x its firmware. The rule columns are what a fingerprint depends",
        " * on; `wallCount` is the engine's own `Layout.edgeCount` and `roundtrip` is the",
        " * measured canonical mission text round trip. Wall layout and firmware text are",
        " * pinned in PARITY_WALL_LAYOUT_TEXTS / PARITY_WALL_FIRMWARE_TEXTS below.",
        " */",
        "internal data class ParityFixtureRow(",
        *[
            "    val %s," % f for f in [
                "scenario: String", "missionId: Int", "missionName: String", "kind: String",
                "firmwareLabel: String", "probeTick: Int", "probeHash: String", "traceHash: String",
                "finalTick: Int", "status: String", "failureReason: String", "finalHash: String",
                "asmBytes: Int", "asmInsns: Int",
                "seed: Int", "objectives: Int", "timeLimit: Int?", "surviveUntil: Int?",
                "startingWater: Int", "windDir: Int", "windForce: Int", "maxTicks: Int",
                "wallCount: Int", "roundtrip: Boolean",
            ]
        ],
        ")",
        "",
        "internal val PARITY_FIXTURE_ROWS: List<ParityFixtureRow> = listOf(",
    ]
    body = []
    for r in rows:
        args = [
            ks(r[col["scenario"]]), r[col["mission"]], ks(r[col["name"]]), ks(r[col["kind"]]),
            ks(r[col["firmware_label"]]), r[col["probe_tick"]], ks(r[col["probe_hash"]]),
            ks(r[col["trace_hash"]]), r[col["final_tick"]], ks(r[col["status"]]),
            ks(r[col["failure_reason"]]), ks(r[col["final_hash"]]),
            r[col["asm_bytes"]], r[col["asm_insns"]],
            r[col["seed"]], r[col["objectives"]],
            "null" if r[col["time_limit"]] == "-" else r[col["time_limit"]],
            "null" if r[col["survive_until"]] == "-" else r[col["survive_until"]],
            r[col["starting_water"]], r[col["wind_dir"]], r[col["wind_force"]],
            r[col["max_ticks"]], r[col["wall_count"]], r[col["roundtrip"]],
        ]
        body.append("    ParityFixtureRow(%s)," % ", ".join(args))
    layout_block = [
        ")",
        "",
        "/**",
        " * E2 wall fixtures: the canonical mission text of each wall-carrying layout,",
        " * pinned exactly as the engine emitted it (TERRAIN 20x20, FIRE 20x20,",
        " * WALL_VERT 20x19, WALL_HORIZ 19x20, ROVER). No wall layout is re-typed here.",
        " */",
        "internal val PARITY_WALL_LAYOUT_TEXTS: Map<String, String> = mapOf(",
    ]
    for r in wall_rows:
        sc = r[col["scenario"]]
        layout_block.append("    %s to %s," % (ks(sc), ktext(r[col["layout_source"]])))
    layout_block.append(")")
    layout_block += [
        "",
        "/** The firmware source each wall fixture row ran, as recorded. */",
        "internal val PARITY_WALL_FIRMWARE_TEXTS: Map<String, String> = mapOf(",
    ]
    for r in wall_rows:
        sc = r[col["scenario"]]
        layout_block.append("    %s to %s," % (ks(sc), ktext(r[col["firmware_source"]])))
    layout_block.append(")")
    traces_block = [
        "",
        "/** Gate G2: `FirelineRun.eventTrace(descriptor, probeTick)` verbatim, per scenario. */",
        "internal val PARITY_GOLDEN_TRACES: Map<String, List<String>> = mapOf(",
    ]
    for r in rows:
        sc = r[col["scenario"]]
        traces_block.append("    %s to listOf(" % ks(sc))
        traces_block += ["        " + ks(line) + "," for line in traces[sc]["lines"]]
        traces_block.append("    ),")
    traces_block.append(")")
    arith_block = [
        "",
        "/** Gate G4: 64/32-bit behavior vectors every container must reproduce (JVM values). */",
        "internal val PARITY_ARITH_VECTORS: Map<String, String> = mapOf(",
        *["    %s to %s," % (ks(n), ks(v)) for n, v in arith],
        ")",
    ]
    limit_block = [
        "",
        "/** Gate G6: `WebInputLimits` probe outcomes, recorded on the JVM. */",
        "internal val PARITY_LIMIT_PROBE: Map<String, String> = mapOf(",
        *["    %s to %s," % (ks(l[0]), ks(" | ".join(l[1:]))) for l in limits],
        ")",
        "",
    ]
    text = "\n".join(header_doc + body + layout_block + traces_block + arith_block + limit_block)
    with open(KT_OUT, "w", encoding="utf-8") as fh:
        fh.write(text)

    print("wrote:")
    print("  docs/web/fixtures/parity_matrix.json, parity_arith.json, parity_traces.json")
    print("  " + os.path.relpath(KT_OUT, REPO).replace(os.sep, "/"))
    print("engine v%d, commit %s, rows %d (%d mission + %d wall), arith %d, limits %d, trace lines %d" % (
        engine_version, commit, len(rows), len(mission_rows), len(wall_rows), len(arith), len(limits),
        sum(len(t["lines"]) for t in traces.values())))
    for r in rows:
        print("  %-18s probe %s trace %s final %s/%s/%s %s (%s B / %s insn, walls %s, roundtrip %s)" % (
            r[col["scenario"]], r[col["probe_hash"]], r[col["trace_hash"]], r[col["final_tick"]],
            r[col["status"]], r[col["failure_reason"]], r[col["final_hash"]],
            r[col["asm_bytes"]], r[col["asm_insns"]], r[col["wall_count"]], r[col["roundtrip"]]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
