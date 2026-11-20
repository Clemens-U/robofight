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
EXPECTED_ROWS = 10  # 5 authored missions x (reference + failing)


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
    if len(rows) != EXPECTED_ROWS:
        bad.append("matrix has %d rows, expected %d" % (len(rows), EXPECTED_ROWS))
    if len(traces) != len(rows):
        bad.append("trace scenarios %d != matrix rows %d" % (len(traces), len(rows)))
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

    write_json("parity_matrix.json", {
        "engineVersion": engine_version,
        "sourceCommit": commit,
        "probeTick": PROBE_TICK,
        "generator": "docs/web/fixtures/ParityMatrix.kt",
        "columns": header,
        "rows": [dict(zip(header, r)) for r in rows],
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
        " * W2 parity fixtures (docs/FIRELINE-WEB.md §9 W2, gates G2/G3/G6): the recorded",
        " * fingerprint matrix, golden event traces, 64/32-bit arithmetic vectors and the web",
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
        "",
        "/** One matrix row: one authored mission x one firmware variant. */",
        "internal data class ParityFixtureRow(",
        *[
            "    val %s," % f for f in [
                "scenario: String", "missionId: Int", "missionName: String", "kind: String",
                "firmwareLabel: String", "probeTick: Int", "probeHash: String", "traceHash: String",
                "finalTick: Int", "status: String", "failureReason: String", "finalHash: String",
                "asmBytes: Int", "asmInsns: Int",
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
        ]
        body.append("    ParityFixtureRow(%s)," % ", ".join(args))
    traces_block = [
        ")",
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
    text = "\n".join(header_doc + body + traces_block + arith_block + limit_block)
    with open(KT_OUT, "w", encoding="utf-8") as fh:
        fh.write(text)

    print("wrote:")
    print("  docs/web/fixtures/parity_matrix.json, parity_arith.json, parity_traces.json")
    print("  " + os.path.relpath(KT_OUT, REPO).replace(os.sep, "/"))
    print("engine v%d, commit %s, rows %d, arith %d, limits %d, trace lines %d" % (
        engine_version, commit, len(rows), len(arith), len(limits),
        sum(len(t["lines"]) for t in traces.values())))
    for r in rows:
        print("  %-12s probe %s trace %s final %s/%s/%s %s (%s B / %s insn)" % (
            r[col["scenario"]], r[col["probe_hash"]], r[col["trace_hash"]], r[col["final_tick"]],
            r[col["status"]], r[col["failure_reason"]], r[col["final_hash"]],
            r[col["asm_bytes"]], r[col["asm_insns"]]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
