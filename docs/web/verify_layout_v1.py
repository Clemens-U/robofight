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
    shell_css_gate_for_15_2b()

    # 9. dated amendment 15.8 (PLAN-EDGE-WALLS.md step E4): the thin edge wall
    #    overlay exists in the shell CSS and the legend, and it never touches a cell.
    shell_css_gate_for_15_8()

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
          "--col:max(320px, calc((var(--cw) - 19.2px) / 2))" in pair)
    check("15.2a 7 grid cells derive from the anchor width",
          "repeat(20, var(--cell, 11px))" in rule(".grid{") and
          "grid-template-rows:repeat(21, var(--cell, 11px))" in rule(".grid{"))
    # W3 record 16: one token, one term. Tracks, rows and the box are all built from
    # --cell, and --cell itself is the measured value the render layer writes inline on
    # .pair (WorldGrid.cellStyleAttr), so the grid box can no longer disagree with its
    # tracks. The old `max-width:var(--grid-h,317px)` clamp is dropped: with a scaled
    # cell (27px at viewport 1370) the box is 596px wide but 569px tall, and clamping
    # width to the height is exactly the box/track disagreement that caused the bug.
    check("15.2a 7 grid box is the same cell term as its tracks (record 16)",
          "width:calc(22 * var(--cell, 11px) + 2px)" in rule(".grid{") and
          "height:calc(21 * var(--cell, 11px) + 2px)" in rule(".grid{") and
          "max-width:var(--grid-h" not in rule(".grid{"))
    check("15.2a 7 pane height keeps the frozen 21-cell ratio",
          "height:var(--pane-h,316px)" in rule(".pairfirm pre.source{") and
          "--grid-h:calc(21 * var(--cell) + 2px)" in pair and
          "--cell:11px;" in pair and          # fallback; the real cell is inline from the render layer
          "--row-offset:120px" in pair and
          "--pane-h:max(180px, calc(var(--grid-h) - var(--row-offset)))" in pair)
    # W3 record 16 root cause: clamping the grid WIDTH to a height-derived token made the box and
    # its tracks disagree, which is what pushed cells across the column edge.
    check("15.2a 7 no height-derived clamp on the grid width (record 16)",
          "max-width:var(--grid-h" not in rule(".grid{"))
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

    row = rule(".pacing .inforow{")
    labels = rule(".pacing .speedlabel,")
    check("15.2a 9 pacing row never wraps a choice to a second line",
          "flex-wrap:nowrap" in row and "white-space:nowrap" in labels and
          "white-space:nowrap" in pacing and
          "@media(max-width:700px){.pacing .inforow{flex-wrap:wrap}" not in c, row[:90])
    check("15.2a 9 the four choice cells are equal width",
          "flex:1 1 42px" in pacing and "max-width:64px" in pacing, pacing[:90])
    check("15.2a 9 SELECTED wording stays in the markup but is not painted",
          ".pacing .chip.btn small{" in c and "clip-path:inset(50%)" in rule(".pacing .chip.btn small{"))
    check("15.2a 11 PANIC is its own row under the SPEED row",
          ".pacing .inforow.pacingpanic{margin-top:var(--gap-related)}" in c)
    check("15.2a 11 the shell renders no titlebar",
          ".titlebar{" not in c, "titlebar rule present" if ".titlebar{" in c else "")
    check("15.2a 10 one declared vertical rhythm for the shell",
          "--gap-major:12.8px" in c and "--gap-related:8px" in c and
          c.count("var(--gap-major)") >= 5 and c.count("var(--gap-related)") >= 2,
          "gap vars: %d major / %d related" % (c.count("var(--gap-major)"), c.count("var(--gap-related)")))


def shell_css_gate_for_15_2b():
    # W3 record 17 (2026-11-24, owner-directed): the console shell drops the 15.2 item 8
    # RELEASE STATUS block and compacts the firmware library controls on desktop, keeping the
    # 49dp pointer:coarse gate over every library control.
    c = SHELL.read_text(encoding="utf-8")

    def rule(sel):
        i = c.find(sel)
        return c[i:c.find("}", i)] if i >= 0 else ""

    check("15.2b 1 the shell renders no RELEASE STATUS block",
          "FL:&gt; RELEASE STATUS" not in c and 'class="cta"' not in c)
    check("15.2b 1 no .cta styling left in the shell CSS",
          ".cta{" not in c and ".cta a" not in c)
    chips = rule(".librarycontrols .chip.btn,")
    check("15.2b 2 library chips are 30px at 12px font on desktop",
          "min-height:30px" in chips and "font-size:12px" in chips, chips[:80])
    hidden = rule(".importlabel input[type=file]{")
    check("15.2b 2 the OS-language file button is hidden behind the IMPORT .ASM chip",
          "clip-path:inset(50%)" in hidden and ".librarycontrols .importlabel.file{" in c,
          hidden[:80])
    mini = rule(".libentry .mini{")
    check("15.2b 2 library entry buttons are 26px at 12px font on desktop",
          "min-height:26px" in mini and "font-size:12px" in mini, mini[:80])
    coarse = c[c.find("@media (pointer: coarse)"):]
    check("15.2b 2 the 49dp touch gate covers the library controls",
          ".librarycontrols .chip.btn" in coarse and ".libentry .mini" in coarse and
          "min-height:49px" in coarse)


def shell_css_gate_for_15_8():
    """E4 (PLAN-EDGE-WALLS.md step E4, docs/FIRELINE-WEB.md 15.8): the thin edge
    wall overlay. A wall is a line ON a cell border: an absolutely positioned
    child of the grid, never a cell node, sized from the same --cell token."""
    c = SHELL.read_text(encoding="utf-8")

    def rule(sel):
        i = c.find(sel)
        return c[i:c.find("}", i)] if i >= 0 else ""

    check("15.8 the grid is the position context for the overlay",
          ".grid{display:grid;position:relative;" in c, "no position:relative on .grid")
    seg = rule(".wallseg{")
    check("15.8 a wall segment is an absolute overlay child, unclickable",
          "position:absolute" in seg and "pointer-events:none" in seg and "var(--wall)" in seg, seg[:90])
    check("15.8 a vertical segment is stroke-wide and one cell tall",
          "width:var(--wall-stroke, 2px)" in rule(".wallseg.v{") and "height:var(--cell, 11px)" in rule(".wallseg.v{")
          and "transform:translate(-50%,0)" in rule(".wallseg.v{"), rule(".wallseg.v{")[:90])
    check("15.8 a horizontal segment is one cell wide and stroke tall",
          "height:var(--wall-stroke, 2px)" in rule(".wallseg.h{") and "width:var(--cell, 11px)" in rule(".wallseg.h{")
          and          "transform:translate(0,-50%)" in rule(".wallseg.h{"), rule(".wallseg.h{")[:90])
    check("15.8 the stroke is a render-layer token handed over by the shell",
          "--wall-stroke" in c and "var(--wall-stroke" in rule(".wallseg.v{"))
    coarse = c[c.find("@media (pointer: coarse)"):]
    cc = coarse.replace(" ", "").replace(".legend .edgebar", ".legend.edgebar")
    check("15.8 the touch gate raises the stroke floor to 4px",
          ".pair{--wall-stroke-touch:4px}" in cc
          and ".wallseg{--wall-stroke:var(--wall-stroke-touch,var(--wall-stroke))}" in cc
          and ".legend.edgebar{height:max(4px,calc(var(--cell,11px)*0.185))}" in cc,
          cc[cc.find(".pair{--wall-stroke-touch"):cc.find(".pair{--wall-stroke-touch") + 170])
    check("15.8 the legend keys the thin wall as a bar",
          ".legend .edgebar{" in c and "background:var(--wall)" in rule(".legend .edgebar{"), rule(".legend .edgebar{")[:80])

    # The frozen mockups: the 15.8 legend item joined the frozen LEGEND (11 items),
    # and it is a bar plus wording, never a 12th glyph. GOLDEN = fl-web-5-playable-landing.html.
    g = GOLDEN.read_text(encoding="utf-8")
    check("15.8 the frozen legend carries the thin-wall item",
          g.count('<b><span class="edgebar"></span> thin wall, blocks the way</b>') == 1
          and g.count('class="edgebar"') == 1)
    check("15.8 the mockup CSS keys the bar",
          ".legend .edgebar{" in g, "no .legend .edgebar rule in the generated mockup")
    item = '<b><span class="edgebar"></span> thin wall, blocks the way</b>'
    gleg = g[g.find('<div class="legend">'):]
    gleg = gleg[:gleg.find("</div>")]
    check("15.8 the thin wall gets a bar, not a new glyph",
          item in g and 'class="wall"' not in item and item.count("<span") == 1
          and gleg.count("<b>") == 11, "legend items: %d" % gleg.count("<b>"))


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
