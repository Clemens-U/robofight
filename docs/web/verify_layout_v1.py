"""Gate check for the FROZEN web v1 layout (docs/FIRELINE-WEB.md §15).

Run after every change to gen_web_mockups.py or its output:

    python docs/web/verify_layout_v1.py

Checks the parts of §15 that are verifiable in the generated HTML: region
order, band cell lists, the single-occurrence rule, flush-pairing height,
single scrollable pane, the 400-cell grid and the house no-em/en-dash copy rule.
Geometry gates that need a laid-out page (15.3 within 2px at 1312px, 15.5
breakpoints, 15.6 overflow checks) are measured in the browser as described in
§14.1 / §15.6.
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
GOLDEN = HERE / "fl-web-5-playable-landing.html"

# §15.2 frozen cell lists
VM_CELLS = ["A", "X", "Y", "SP", "PC", "LAST OP", "FLAGS Z", "FLAGS N",
            "FLAGS C", "OP TICKS LEFT", "VM FAULT", "TICK"]
TELEMETRY_CELLS = ["FIRE_DIST", "FIRE_ANGLE", "FIRE_LEVEL", "LOCAL_FIRE", "SMOKE",
                   "CIV_DIST", "CIV_ANGLE", "CIV_COUNT", "EXIT_DIST", "EXIT_ANGLE",
                   "WIND_DIR", "WIND_FORCE"]

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))


def main():
    if not GOLDEN.exists():
        print("FAIL: golden master missing:", GOLDEN)
        print("run: python docs/web/gen_web_mockups.py")
        return 1
    h = GOLDEN.read_text(encoding="utf-8")

    # 1. paired surface exists once, world column then firmware column
    pair = re.search(r'<div class="pair">(.*?)</div>\s*</div>', h, re.S)
    check("15.2 paired run surface present", bool(pair))
    if not pair:
        return report()

    world = pair.group(1).split('<div class="pairfirm">')[0]
    firm = pair.group(1).split('<div class="pairfirm">')[1]

    # 2. world column order: wind -> grid -> legend -> event log
    order = [h2 for h2 in ('class="wind"', 'class="grid"', 'class="legend"',
                           '<h4 style="margin-top:.7rem">EVENT LOG') if h2 in world]
    check("15.2 world column order wind/grid/legend/event log",
          len(order) == 4, "%d of 4 found" % len(order))
    check("15.6 grid renders 400 cells", len(re.findall(r'class="cell ', world)) == 400)
    check("15.4 no legend inside the firmware column", 'class="legend"' not in firm)

    # 3. firmware column: one source pane, then the single control block
    pres = re.findall(r'<pre class="source"', firm)
    nested = re.findall(r'<pre class="source"[^>]*>.*?<pre class="source"', firm, re.S)
    check("15.7 exactly one source pane (no nested pre -> no double scrollbar)",
          len(pres) == 1 and not nested, "pres=%d nested=%d" % (len(pres), len(nested)))
    check("15.3 flush pane height inline (21*15+1 = 316px)",
          '--src-height:316px' in firm)
    check("15.4 single control block under the pane",
          firm.index('class="consolecontrols"') > firm.index('<pre class="source"'))
    check("15.4 control block holds RUN/STEP/RESET once",
          len(re.findall(r'>RUN<', firm)) == 1
          and len(re.findall(r'>STEP<', firm)) == 1
          and len(re.findall(r'>RESET<', firm)) == 1)
    check("15.4 SPEED/PANIC/share link only in the control block",
          firm.count("SPEED") == 1 and firm.count("PANIC") == 1
          and h.count("DOWNLOAD .ASM") == h.count("<b>LINK</b>") == 1)

    # 4. bands, in frozen order, with the frozen cell lists
    bands = re.split(r'<div class="fields">', h)[1:]
    bands = [b.split('<div class="statusbar">')[0].split('<div class="cols">')[0]
             for b in bands]
    titles = [re.search(r"<h4>([^<]+)</h4>", b).group(1) for b in bands if "<h4>" in b]
    check("15.2 two readout bands in order RF-8 STATE then TELEMETRY",
          len(titles) == 2 and titles[0].startswith("RF-8 STATE")
          and titles[1].startswith("TELEMETRY"), str(titles))
    for i, want in enumerate((VM_CELLS, TELEMETRY_CELLS)):
        got = re.findall(r'<span>([^<]+)</span><b>', bands[i]) if i < len(bands) else []
        check("15.2 band %d cell list" % (i + 1), got == want,
              "got %d cells" % len(got) if got != want else "")
    for i, b in enumerate(bands):
        head = b.split('<div class="frow">')[0] + b.split("</div></div>")[0]
        check("15.4 band %d carries no legend and no fingerprint" % (i + 1),
              'class="legend"' not in b and "FINGERPRINT" not in b)
        check("15.2 band %d has no PORT-number prefix" % (i + 1),
              not re.search(r'<span>PORT \d', b))

    # 5. status line: read-only, one fingerprint, directly after the bands
    sb_txt = re.search(r'<div class="statusbar">(.{0,600}?)<div class="cols">', h, re.S)
    sb_block = sb_txt.group(1) if sb_txt else ""
    check("15.2 status block found after the bands", bool(sb_txt))
    check("15.4 status line carries the single state fingerprint",
          "fingerprint" in sb_block and h.count("fingerprint") == 1,
          "occurrences=%d" % h.count("fingerprint"))
    check("15.2 status line sits directly after the TELEMETRY band",
          h.index('class="statusbar"') > h.rindex('class="fields"'))
    check("15.4 status line is read-only (no buttons)",
          not re.search(r'class="btn|<button', sb_block))

    # 6. no VITALS duplication inside the TELEMETRY band
    if len(bands) > 1:
        check("15.4 no VITALS row inside TELEMETRY",
              not re.search(r'<span>VITALS', bands[1]))

    # 7. house copy rule
    dashes = re.findall(r"[\u2014\u2013]", h)
    check("copy: no em dash or en dash", not dashes, "%d found" % len(dashes))

    # 8. dated shell deviation 15.2a items 7-8 (docs/WEB-W3-RECORD.md 11 g): the live
    #    console harness, not the frozen mockups, is measured against these rules.
    shell_css_gate_for_15_2a()

    return report()


SHELL = HERE.parent.parent / "fireline-web" / "console.html"


def shell_css_gate_for_15_2a():
    """Static gate for the wasm console shell's CSS (15.2a 7 and 8)."""
    if not SHELL.exists():
        check("15.2a 7 shell harness present", False, str(SHELL))
        return
    c = SHELL.read_text(encoding="utf-8")

    def rule(sel):
        i = c.find(sel)
        return c[i:c.find("}", i)] if i >= 0 else ""

    pair = rule(".pair{")
    check("15.2a 7 pair grid is two equal half columns",
          "grid-template-columns:minmax(0,1fr) minmax(0,1fr)" in pair, pair[:80])
    check("15.2a 7 pair column is half the body content width",
          "--col:max(320px, calc((100vw - 74.56px - 19.2px) / 2))" in pair)
    check("15.2a 7 grid cells derive from the anchor width",
          "repeat(20, var(--cell, 15px))" in rule(".grid{") and
          "grid-auto-rows:var(--cell, 15px)" in rule(".grid{"))
    check("15.2a 7 pane height keeps the frozen 21-cell ratio",
          "height:var(--pane-h,316px)" in rule(".pairfirm pre.source{") and
          "--grid-h:calc(21 * var(--cell) + 2px)" in pair and
          "--cell:max(11px, min(34px, calc((var(--col) - 5px) / 21)))" in pair and
          "--pane-h:max(180px, calc(var(--grid-h) - var(--firm-chrome)))" in pair and
          "max-width:var(--grid-h,317px)" in rule(".grid{"))
    check("15.2a 7 shell panes carry no inline height",
          "--src-height" not in c or "var(--src-height" in c)

    pacing = rule(".pacing .chip.btn{")
    check("15.2a 8 pacing row renders without a panel box",
          ".pacing .chip.btn{" in c and "fields pacing" not in c)
    check("15.2a 8 pacing chips are 28px on desktop", "min-height:28px" in pacing, pacing[:80])
    check("15.2a 8 run buttons are 34px on desktop",
          "min-height:34px" in rule(".consolecontrols .runrow .btn{"))
    coarse = c[c.find("@media (pointer: coarse)"):]
    check("15.4 49dp gate keeps holding for touch pointers",
          ".pacing .chip.btn{min-height:49px}" in coarse or
          ".pacing .chip.btn{min-height:49px}" in coarse.replace(" ", ""))


def report():
    bad = 0
    for name, ok, detail in results:
        print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                           (" (" + detail + ")") if detail else ""))
        bad += 0 if ok else 1
    print("---- %d checks, %d failed" % (len(results), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
