# Generates FIRELINE MISSION-screen mockups from the verified CORRIDOR
# tick-46 snapshot (engine constants from fireline-engine FirelineRules.kt).
# One run produces: one single-direction file per palette (P, W, F) plus a
# W-vs-F side-by-side comparison. Run: python gen_mockups.py

import os

GRID = 20

# --- CORRIDOR terrain (Missions.kt id=2) -----------------------------------
def corridor_rows():
    rows = [["."] * GRID for _ in range(GRID)]
    for x in range(GRID):
        if x != 2:
            rows[3][x] = "#"
    rows[2][3] = "#"                       # wall that kills the naive east run
    for x in (8, 9, 10):
        rows[9][x] = "#"
        rows[11][x] = "#"
    rows[10][8] = "W"
    rows[10][10] = "W"
    rows[10][11] = "E"
    return rows

ROVER = (8, 10)                            # parked on the refill firebreak, facing E
ROVER_DIR = "E"

PALETTES = {
    "P": {
        "name": "P - Phosphor Terminal",
        "tag": "house-first: Arena phosphor chrome, warm fire ramp, steel walls",
        "field": "#161718",
        "panel": "#1b2021",
        "panel2": "#202627",
        "border": "#2e3739",
        "gridline": "#232b2d",
        "text": "#34ec5b",          # chrome / system phosphor
        "text_bright": "#eaff93",   # emphasized values, success, nose
        "text_dim": "#2f9e46",      # secondary labels (CVD/contrast tuned)
        "empty": "#3a4a42",         # empty-cell dot (decorative)
        "wall": "#6f767a",          # steel gray, contrast-tuned
        "rover": "#34ec5b",
        "nose": "#eaff93",
        "fire": ["#f8d878", "#f8b800", "#fc9838", "#fc0040"],
        "water": "#00e8d8",
        "civ": "#fcfcfc",
        "exit": "#00e43c",
        "refill": "#00e8d8",
        "fuel": "#a07818",
        "prot": "#fc00bc",
        "btn_text": "#0c1310",
        "btn_run": "#34ec5b",
        "btn": "#232a2c",
        "btn_text_alt": "#cfe8d4",
    },
    "W": {
        "name": "W - Warm Industrial",
        "tag": "scene-first: ash-warm field, instrument-white chrome, green reserved for rover-nose and success",
        "field": "#161718",
        "panel": "#211d1a",
        "panel2": "#26211d",
        "border": "#453d35",
        "gridline": "#332d27",
        "text": "#e8e2d9",          # instrument white chrome
        "text_bright": "#ffffff",
        "text_dim": "#9a8f80",
        "empty": "#4a443c",
        "wall": "#7b6e5f",
        "rover": "#fcfcfc",
        "nose": "#34ec5b",          # green reserved: nose + success accents
        # ramp verified monotone in raw, protanopia, and deuteranopia luminance
        "fire": ["#7d6429", "#b06e17", "#dd5c22", "#f7ecc4"],
        "water": "#35d6d6",
        "civ": "#f2efe9",
        "exit": "#34ec5b",
        "refill": "#35d6d6",
        "fuel": "#8d7a22",
        "prot": "#c77dff",
        "btn_text": "#141010",
        "btn_run": "#e8b04c",       # the one sustained warm accent: RUN
        "btn": "#2c2622",
        "btn_text_alt": "#e0d7cb",
    },
    "F": {
        # Firecall Warm: turnout-gear colors on the same terminal structure as W.
        # Full audit 2026-11-07: all non-text tokens >=3.0 contrast on field and
        # panel, text tokens >=4.5, fire ramp monotone in raw, protanopia, and
        # deuteranopia luminance (grays 134/101/71/47, prot 171/138/106/71).
        "name": "F - Firecall Warm",
        "tag": "turnout-gear identity: cream chrome, turnout-yellow rover, yellow-to-red fire ramp, fire-engine red RUN",
        "field": "#161718",
        "panel": "#241a17",
        "panel2": "#2a1e1a",
        "border": "#63392f",
        "gridline": "#3a2822",
        "text": "#f2d9b0",          # warm cream chrome
        "text_bright": "#fff4dc",
        "text_dim": "#a87f5e",
        "empty": "#4a382e",
        "wall": "#8a6d5e",          # warm tan-gray, contrast-tuned
        "rover": "#ffd23f",         # turnout yellow
        "nose": "#34ec5b",          # reserved safety green
        "fire": ["#ffb02e", "#f2921e", "#e96a2a", "#e0303c"],
        "water": "#35d6d6",
        "civ": "#fff1e0",
        "exit": "#00e43c",
        "refill": "#35d6d6",
        "fuel": "#8d6a1e",
        "prot": "#c77dff",
        "btn_text": "#120a0a",
        "btn_run": "#e63946",       # fire-engine red RUN, dark label (cr 4.69)
        "btn": "#2a1e1a",
        "btn_text_alt": "#f2d9b0",
    },
}

EVENTS = [
    ("46", "FIRE -25 at 9,10 -> 0"),
    ("45", "REFILL +10"),
    ("44", "REFILL +10"),
    ("43", "PACKET SPENT WALL 12,10"),
]

def grid_html(p):
    out = []
    rows = corridor_rows()
    for y in range(GRID):
        cells = []
        for x in range(GRID):
            t = rows[y][x]
            glyph, cls, color = "\u00b7", "cell e", p["empty"]
            if t == "#":
                glyph, cls, color = "\u2588", "cell w", p["wall"]
            elif t == "W":
                glyph, cls, color = "W", "cell r", p["refill"]
            elif t == "E":
                glyph, cls, color = "E", "cell x", p["exit"]
            if (x, y) == ROVER:
                glyph, cls, color = "R", "cell rover nose-%s" % ROVER_DIR.lower(), p["rover"]
            cells.append('<span class="%s" style="color:%s">%s</span>' % (cls, color, glyph))
        out.append("".join(cells) + "\n")
    return "".join(out)

def chips(p):
    items = [
        ("field", p["field"]), ("panel", p["panel"]), ("border", p["border"]),
        ("chrome", p["text"]), ("value", p["text_bright"]), ("dim", p["text_dim"]),
        ("wall", p["wall"]), ("rover", p["rover"]),
        ("fire 1-4", None), ("water", p["water"]), ("fuel", p["fuel"]),
        ("asset", p["prot"]), ("exit", p["exit"]),
    ]
    out = []
    for name, hexv in items:
        if name == "fire 1-4":
            swatch = "".join('<i style="background:%s"></i>' % c for c in p["fire"])
            out.append('<span class="chip">%s<span style="margin-left:4px">fire 1-4</span></span>' % swatch)
        else:
            out.append('<span class="chip"><i style="background:%s"></i>%s %s</span>' % (hexv, name, hexv))
    return " ".join(out)

def wrapper_vars(p):
    return ("--field:%(field)s; --panel:%(panel)s; --panel2:%(panel2)s;"
            " --border:%(border)s; --gridline:%(gridline)s; --text:%(text)s;"
            " --bright:%(text_bright)s; --dim:%(text_dim)s; --exit:%(exit)s;"
            " --nose:%(nose)s; --wall:%(wall)s; --rover:%(rover)s;"
            " --water:%(water)s; --refill:%(refill)s; --civ:%(civ)s;"
            " --fuel:%(fuel)s; --prot:%(prot)s; --btn-run:%(btn_run)s;"
            " --btn:%(btn)s; --btn_text:%(btn_text)s; --btn_text_alt:%(btn_text_alt)s;") % p

CSS = """
  * { box-sizing: border-box; }
  body {
    margin:0; padding:18px; background:#0f1011; color:#e8e2d9;
    font-family:"IBM Plex Mono","JetBrains Mono",ui-monospace,Consolas,monospace;
    font-size:12px; line-height:1.45;
  }
  .col { width:426px; padding:18px 18px 12px; background:var(--field); color:var(--text); }
  .px { font-family:"Press Start 2P","IBM Plex Mono",ui-monospace,monospace; }
  .caption { color:var(--dim); font-size:10px; margin-bottom:10px; letter-spacing:.04em; }
  header { display:flex; justify-content:space-between; align-items:baseline;
    border-bottom:1px solid var(--border); padding-bottom:8px; }
  .wordmark { font-size:13px; letter-spacing:.08em; color:var(--bright); }
  .tagline { color:var(--dim); font-size:9px; letter-spacing:.06em; }
  nav { display:flex; gap:14px; margin:8px 0 12px; font-size:10px; letter-spacing:.12em; }
  nav a { color:var(--dim); text-decoration:none; }
  nav a.on { color:var(--text); border-bottom:2px solid var(--btn-run); padding-bottom:3px; }
  section { border:1px solid var(--border); background:var(--panel);
    padding:10px 12px; margin-bottom:10px; }
  .mh { display:flex; justify-content:space-between; align-items:baseline; }
  .mh .id { font-size:11px; color:var(--bright); letter-spacing:.06em; }
  .mh .fw { color:var(--dim); font-size:10px; }
  .brief { color:var(--dim); font-size:10px; margin-top:4px; max-width:390px; }
  .objline { margin-top:6px; font-size:10px; letter-spacing:.05em; }
  .ok { color:var(--exit); } .pend { color:var(--dim); }
  .grid { display:grid; grid-template-columns:repeat(20, 18px); grid-auto-rows:18px;
    background:var(--panel2); border:1px solid var(--gridline); justify-content:center;
    margin-top:2px; }
  .cell { display:flex; align-items:center; justify-content:center;
    font-size:11px; border:1px solid var(--gridline); }
  .cell.e { font-size:8px; opacity:.8; }
  .cell.w { font-size:16px; line-height:1; }
  .cell.rover { font-weight:bold; font-size:13px; position:relative; }
  .cell.rover::after { position:absolute; font-size:8px; line-height:1; }
  .cell.rover.nose-e::after { content:"\\25B8"; right:1px; top:50%; transform:translateY(-50%); color:var(--nose); }
  .legend { display:flex; flex-wrap:wrap; gap:4px 12px; font-size:9px;
    color:var(--dim); margin-top:8px; }
  .legend b { font-weight:normal; }
  .cols { display:flex; gap:10px; }
  .cols section { flex:1; margin-bottom:0; }
  h4 { margin:0 0 6px; font-size:9px; letter-spacing:.14em; color:var(--dim);
    font-weight:normal; }
  table { border-collapse:collapse; width:100%; font-size:11px; }
  td { padding:1px 4px; }
  td.k { color:var(--dim); } td.v { color:var(--bright); text-align:right;
    font-variant-numeric:tabular-nums; }
  .vm td { font-family:"IBM Plex Mono",ui-monospace,monospace; }
  .vm tr.now td { color:var(--bright); background:var(--panel2); }
  .vm tr.now td.pc::before { content:">"; color:var(--btn-run); }
  .flags { font-size:10px; color:var(--dim); margin-top:6px; }
  .events li { list-style:none; padding:2px 0 2px 8px; font-size:10px; color:var(--dim);
    border-left:2px solid transparent; }
  .events li.newest { color:var(--bright); border-left-color:var(--btn-run); }
  .events li .t { color:var(--dim); opacity:.65; margin-right:8px; }
  .controls { display:flex; gap:8px; }
  .btn { flex:1; text-align:center; padding:14px 0; font-size:11px;
    letter-spacing:.14em; border:1px solid var(--border); background:var(--btn);
    color:var(--btn_text_alt); }
  .btn.run { background:var(--btn_run); color:var(--btn_text); border-color:var(--btn_run);
    font-weight:bold; }
  footer { display:flex; flex-wrap:wrap; gap:4px 10px; font-size:9px;
    color:var(--dim); border-top:1px solid var(--border); padding-top:8px; }
  .chip { display:inline-flex; align-items:center; gap:5px; }
  .chip i { width:10px; height:10px; display:inline-block; border:1px solid var(--border); }
"""

def body_html(p):
    return """<div class="col" style="{vars}">
<div class="caption px">{n}&nbsp;&nbsp;MISSION run screen&nbsp;&nbsp;tick 46 of 120&nbsp;&nbsp;({t})</div>
<header>
  <div>
    <div class="wordmark px">OPCODE FIRELINE</div>
    <div class="tagline px">THE SAME LITTLE COMPUTER. A DIFFERENT JOB.</div>
  </div>
  <div class="px" style="font-size:9px; color:var(--dim);">RF-8</div>
</header>
<nav class="px"><a>FILES</a><a class="on">MISSION</a><a>RESULT</a></nav>

<section>
  <div class="mh"><span class="id px">MISSION 02 / CORRIDOR</span>
  <span class="fw px">CORRIDOR-REF.ASM&nbsp;&nbsp;seed 2002&nbsp;&nbsp;wind W 0</span></div>
  <div class="brief">"The wall in front of you is not negotiable. Turn, walk, shoot
    what burns ahead, then reach the exit."</div>
  <div class="objline px"><span class="ok">EXTINGUISH_ALL done</span>&nbsp;&nbsp;
    <span class="pend">REACH_EXIT pending</span>&nbsp;&nbsp;<span class="pend">tick 46/120</span></div>
</section>

<section>
  <div class="grid px">{grid}</div>
  <div class="legend px">
    <b><span style="color:var(--wall)">&#x2588;</span> wall</b>
    <b><span style="color:var(--rover);font-weight:bold">R</span><span style="color:var(--nose)">&#x25B8;</span> rover + facing (A-1 caret when facing cell occupied)</b>
    <b><span style="color:{fire1}">1</span><span style="color:{fire2}">2</span><span style="color:{fire3}">3</span><span style="color:{fire4}">4</span> fire intensity</b>
    <b><span style="color:var(--water)">*</span> packet</b>
    <b><span style="color:var(--refill)">W</span> refill</b>
    <b><span style="color:var(--exit)">E</span> exit</b>
    <b><span style="color:var(--civ)">C</span> civ</b>
    <b><span style="color:var(--fuel)">F</span> fuel</b>
    <b><span style="color:var(--prot)">P</span> asset</b>
  </div>
</section>

<div class="cols">
  <section>
    <h4 class="px">RF-8 STATE</h4>
    <table class="vm">
      <tr><td class="k">A</td><td class="v">0</td><td class="k">SP</td><td class="v">00</td></tr>
      <tr><td class="k">X</td><td class="v">08</td><td class="k">PC</td><td class="v">0014</td></tr>
      <tr><td class="k">Y</td><td class="v">10</td><td class="k">FLAGS</td><td class="v">Z=1 N=0 C=0</td></tr>
    </table>
    <table class="vm" style="margin-top:6px">
      <tr><td class="pc">0013</td><td>IN&nbsp;&nbsp;&nbsp;FIRE_LEVEL</td></tr>
      <tr class="now"><td class="pc">0014</td><td>JZ&nbsp;&nbsp;&nbsp;&nbsp;exit_</td></tr>
      <tr><td class="pc">0015</td><td>SHOOT</td></tr>
      <tr><td class="pc">0016</td><td>...</td></tr>
    </table>
    <div class="flags px">JZ taken: A=0, no fire target remains</div>
  </section>
  <section>
    <h4 class="px">TELEMETRY (PORTS 16-30)</h4>
    <table>
      <tr><td class="k px">FIRE_DIST</td><td class="v">0</td></tr>
      <tr><td class="k px">FIRE_LEVEL</td><td class="v">0</td></tr>
      <tr><td class="k px">CIV_COUNT</td><td class="v">0</td></tr>
      <tr><td class="k px">WATER / HP / HEAT</td><td class="v">90 / 100 / 20</td></tr>
      <tr><td class="k px">SHIELD</td><td class="v">-</td></tr>
      <tr><td class="k px">WIND</td><td class="v">W 0</td></tr>
      <tr><td class="k px">MISSION</td><td class="v">$02</td></tr>
    </table>
  </section>
</div>

<section style="margin-top:10px">
  <h4 class="px">EVENTS</h4>
  <ul class="events" style="margin:0;padding:0">
    {events}
  </ul>
</section>

<div class="controls" style="margin-top:10px">
  <div class="btn run px">RUN</div>
  <div class="btn px">STEP</div>
  <div class="btn px">RESET</div>
</div>

<footer>
  {chips}
</footer>
</div>""".format(vars=wrapper_vars(p), n=p["name"], t=p["tag"], grid=grid_html(p),
                 events="\n".join(
                     '<li class="%s"><span class="t">%s</span>%s</li>' % ("newest" if i == 0 else "", t, e)
                     for i, (t, e) in enumerate(EVENTS)),
                 chips=chips(p),
                 fire1=p["fire"][0], fire2=p["fire"][1], fire3=p["fire"][2], fire4=p["fire"][3])

HEAD = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{title}</title>
<style>{css}</style>
</head>
<body>
"""

here = os.path.dirname(os.path.abspath(__file__))

def write_mockup(name, title, inner):
    path = os.path.join(here, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(HEAD.format(title=title, css=CSS) + inner + "\n</body>\n</html>\n")
    print(path)

for key, p in PALETTES.items():
    write_mockup("fireline-mission-%s.html" % key,
                 "FIRELINE mockup %s - MISSION 02 / CORRIDOR tick 46" % key,
                 body_html(p))

compare = ('<div style="display:flex; gap:18px; align-items:flex-start;">'
           + body_html(PALETTES["W"]) + body_html(PALETTES["F"]) + "</div>")
write_mockup("fireline-mission-WF-compare.html",
             "FIRELINE mockup comparison - W Warm Industrial vs F Firecall Warm",
             compare)
