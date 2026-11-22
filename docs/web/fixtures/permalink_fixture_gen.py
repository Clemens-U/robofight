#!/usr/bin/env python3
"""Permalink (FLW1-) fixture generator for :fireline-web (docs/FIRELINE-WEB.md 8, gate G4/G5 support).

Pipeline (same shape as parity_fixture_gen.py):
  1. record:   kotlinc `docs/web/fixtures/PermalinkSources.kt` over the live engine
               catalog -> `permalink_sources.tsv` (firmware sources as live engine text)
  2. pin:      this script builds each FLW1- link from that TSV with its own
               gzip/stored-deflate implementation, verifies every link with the
               INDEPENDENT Python `gzip` module (decode + payload layout check),
               and writes `permalink_fixtures.json` + the Kotlin pin file
               `fireline-web/src/wasmJsTest/.../W3PermalinkFixtures.kt`.
  3. check:    `--check` regenerates from the committed TSV and refuses if the
               committed JSON/Kotlin differ (byte-identical provenance gate).

Framing mirrors `fireline-web/src/wasmJsMain/.../Permalink.kt` exactly:
  payload : version(1) mission_id(4 BE) seed_override(4 BE) name_len(2 BE) name source
  gzip    : 1f 8b 08 00, MTIME 0 (4 BE), XFL 0, OS 3, stored deflate blocks
            (<=512 bytes each, LEN/NLEN little-endian) + empty final block,
            CRC32(payload) 4 BE, len(payload) 4 BE
  text    : "FLW1-" + base64url(no padding)
The cross-proof is the pair: Kotlin's encode() must equal this generator's link
string (asserted in wasmJsTest), and Python's gzip module must decompress it
(asserted here). A mismatch on either side is a determinism defect, not noise.
"""
import base64
import binascii
import gzip
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
TSV = HERE / "permalink_sources.tsv"
JSON_OUT = HERE / "permalink_fixtures.json"
KT_OUT = REPO / "fireline-web/src/wasmJsTest/kotlin/robofight/fireline/web/W3PermalinkFixtures.kt"

VERSION_BYTE = 1
STORED_BLOCK_MAX = 512
MAX_NAME_BYTES = 64
MAX_SOURCE_LINES = 1200
MAX_SOURCE_BYTES = 8192


def deflate_stored(payload: bytes) -> bytes:
    out = bytearray()
    pos = 0
    while pos < len(payload):
        n = min(STORED_BLOCK_MAX, len(payload) - pos)
        out.append(0)  # BFINAL 0, BTYPE 00
        out += n.to_bytes(2, "little")
        out += (0xFFFF - n).to_bytes(2, "little")  # NLEN: one's complement of LEN
        out += payload[pos:pos + n]
        pos += n
    out.append(1)  # BFINAL 1, empty final block
    out += (0).to_bytes(2, "little")
    out += (0xFFFF).to_bytes(2, "little")
    return bytes(out)


def build_link(mission_id: int, seed_override: int, name: str, source: str) -> str:
    name_b = name.encode("utf-8")
    src_b = source.encode("utf-8")
    assert 1 <= len(name_b) <= MAX_NAME_BYTES, "name ceiling"
    problems = source_problems(source)
    assert not problems, "source refused: %s" % problems
    payload = bytearray()
    payload += VERSION_BYTE.to_bytes(1, "big")
    payload += mission_id.to_bytes(4, "big")
    payload += seed_override.to_bytes(4, "big")
    payload += len(name_b).to_bytes(2, "big")
    payload += name_b
    payload += src_b
    body = bytes(payload)
    gz = bytearray(b"\x1f\x8b\x08\x00")
    gz += (0).to_bytes(4, "big")  # MTIME fixed 0
    gz.append(0)                  # XFL
    gz.append(3)                  # OS Unix
    gz += deflate_stored(body)
    # RFC 1952: CRC32 and ISIZE are little-endian (only the header's MTIME is big-endian).
    gz += (binascii.crc32(body) & 0xFFFFFFFF).to_bytes(4, "little")
    gz += (len(body) & 0xFFFFFFFF).to_bytes(4, "little")
    return "FLW1-" + base64.urlsafe_b64encode(bytes(gz)).decode("ascii").rstrip("=")


def source_problems(source: str) -> list:
    """Mirror of WebInputLimits.sourceProblems ceilings (lines/bytes/ASCII)."""
    problems = []
    lines = source.split("\n")
    if len(lines) > MAX_SOURCE_LINES:
        problems.append("source has %d lines (limit %d)" % (len(lines), MAX_SOURCE_LINES))
    if len(source.encode("utf-8")) > MAX_SOURCE_BYTES:
        problems.append("source exceeds %d bytes" % MAX_SOURCE_BYTES)
    if not source.strip():
        problems.append("source is blank")
    if any(ord(c) > 127 or (ord(c) < 32 and c != "\n" and c != "\t") for c in source):
        problems.append("source contains non-printable or non-ASCII characters")
    return problems


def independent_verify(link: str, mission_id: int, seed_override: int, name: str, source: str):
    """Second opinion: the stdlib gzip module must decompress our stream, and the
    payload must decode back to the exact scenario fields."""
    assert link.startswith("FLW1-")
    gz = base64.urlsafe_b64decode(link[len("FLW1-"):].encode("ascii") + b"===")
    payload = gzip.decompress(gz)
    pos = 0
    ver = payload[pos]; pos += 1
    mid = int.from_bytes(payload[pos:pos + 4], "big"); pos += 4
    seed = int.from_bytes(payload[pos:pos + 4], "big"); pos += 4
    nlen = int.from_bytes(payload[pos:pos + 2], "big"); pos += 2
    got_name = payload[pos:pos + nlen].decode("utf-8"); pos += nlen
    got_src = payload[pos:].decode("utf-8")
    assert ver == VERSION_BYTE and mid == mission_id and seed == seed_override, \
        "payload fields mismatch for %s" % link[:24]
    assert got_name == name and got_src == source, "payload text mismatch for %s" % link[:24]


def kt(value) -> str:
    if isinstance(value, str):
        return json.dumps(value).replace("$", "\\$")
    return str(value)


def read_tsv():
    rows = []
    # Java on Windows writes CRLF; normalize before splitting; skip provenance comments.
    text = TSV.read_bytes().decode("utf-8").replace("\r\n", "\n")
    lines = [l for l in text.splitlines() if l.strip() and not l.startswith("#")]
    header = lines[0].split("\t")
    for line in lines[1:]:
        if not line.strip():
            continue
        rows.append(dict(zip(header, line.split("\t"))))
    return rows


def commit_of_tsv() -> str:
    for line in TSV.read_text(encoding="utf-8").splitlines():
        m = re.match(r"# commit ([0-9a-f]{40})", line)
        if m:
            return m.group(1)
    return ""


def build_rows():
    rows = []
    for r in read_tsv():
        mission_id = int(r["mission_id"])
        seed_override = int(r["seed_override"])
        name = r["firmware_name"]
        source = base64.b64decode(r["source_base64"]).decode("utf-8")
        link = build_link(mission_id, seed_override, name, source)
        independent_verify(link, mission_id, seed_override, name, source)
        rows.append({
            "scenario": r["scenario"],
            "mission_id": mission_id,
            "mission_name": r["mission_name"],
            "firmware_name": name,
            "seed_override": seed_override,
            "source_base64": r["source_base64"],
            "link": link,
            "link_length": len(link),
        })
    return rows


def write_outputs(rows, commit):
    payload = {
        "generator": "docs/web/fixtures/PermalinkSources.kt + docs/web/fixtures/permalink_fixture_gen.py",
        "codec": "fireline-web/src/wasmJsMain/kotlin/robofight/fireline/web/Permalink.kt (FLW1-)",
        "commit": commit,
        "scenarios": rows,
    }
    JSON_OUT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    lines = []
    lines.append("package robofight.fireline.web")
    lines.append("")
    lines.append("/**")
    lines.append(" * W3 permalink fixtures (docs/FIRELINE-WEB.md 8 share links, gate G4/G5 support).")
    lines.append(" *")
    lines.append(" * GENERATED - never typed by hand. `docs/web/fixtures/PermalinkSources.kt` recorded the")
    lines.append(" * live authored firmware sources (commit %s)," % commit)
    lines.append(" * `docs/web/fixtures/permalink_fixture_gen.py` built each FLW1- link with its own gzip")
    lines.append(" * framing AND verified it with Python's independent gzip module; the Kotlin codec in")
    lines.append(" * `Permalink.kt` must encode to these exact strings and decode them back (W3PermalinkTest).")
    lines.append(" * Raw form: `docs/web/fixtures/permalink_sources.tsv` + `permalink_fixtures.json`.")
    lines.append(" */")
    lines.append("")
    lines.append("internal const val PERMALINK_SOURCE_COMMIT: String = %s" % kt(commit))
    lines.append("internal const val PERMALINK_EXPECTED_SCENARIOS: Int = %d" % len(rows))
    lines.append("")
    lines.append("internal data class PinnedPermalink(")
    lines.append("    val scenario: String,")
    lines.append("    val link: String,")
    lines.append("    val missionId: Int,")
    lines.append("    val seedOverride: Int,")
    lines.append("    val firmwareName: String,")
    lines.append("    val sourceBase64: String,")
    lines.append(")")
    lines.append("")
    lines.append("internal val PINNED_PERMALINKS: List<PinnedPermalink> = listOf(")
    for r in rows:
        lines.append("    PinnedPermalink(%s, %s, %d, %d, %s, %s)," % (
            kt(r["scenario"]), kt(r["link"]), r["mission_id"], r["seed_override"],
            kt(r["firmware_name"]), kt(r["source_base64"])))
    lines.append(")")
    lines.append("")
    KT_OUT.write_text("\n".join(lines), encoding="utf-8")


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "--record"
    if not TSV.exists():
        sys.exit("missing %s (run the kotlinc record step, see docs/web/fixtures/README.md)" % TSV)
    commit = commit_of_tsv()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        sys.exit("permalink_sources.tsv carries no '# commit <sha>' provenance header")
    rows = build_rows()
    if mode == "--check":
        before_json = JSON_OUT.read_text(encoding="utf-8")
        before_kt = KT_OUT.read_text(encoding="utf-8")
        write_outputs(rows, commit)
        if JSON_OUT.read_text(encoding="utf-8") != before_json:
            sys.exit("permalink_fixtures.json is NOT reproducible from the committed TSV")
        if KT_OUT.read_text(encoding="utf-8") != before_kt:
            sys.exit("W3PermalinkFixtures.kt is NOT reproducible from the committed TSV")
        print("PERMALINK FIXTURES REPRODUCIBLE: %d scenarios, gzip cross-verified" % len(rows))
        return
    write_outputs(rows, commit)
    print("wrote %s and %s: %d scenarios, commit %s" % (JSON_OUT.name, KT_OUT.name, len(rows), commit))
    for r in rows:
        print("  %-24s mission %s seed %-3s link %d chars" % (
            r["scenario"], r["mission_id"], r["seed_override"], r["link_length"]))


if __name__ == "__main__":
    main()
