# Generates the Opcode FIRELINE WEB CONSOLE mockups (docs/FIRELINE-WEB.md).
#
# Data sources (nothing invented):
#  - world states, registers, telemetry, events, fingerprints: real headless
#    engine snapshots from :rf8-core + :fireline-engine
#    (fixture: snapshots.json, produced by the W0 fixture harness).
#  - mission ids/names/briefings/parameters: fireline-engine Missions.kt.
#  - palette: live landing page tokens from https://opcode-fireline.appsystems.at/
#    plus locked direction F tokens from DESIGN-FIRELINE.md 2.2b (rover/wall/fire
#    ramp/exit which the landing page does not carry yet).
#
# Run: python gen_web_mockups.py  [fixture path optional as argv[1]]

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_FIXTURE = os.path.join(HERE, "fixtures", "snapshots.json")
EXTRA_FIXTURE = os.path.join(HERE, "fixtures", "snapshots.extra.json")

GRID = 20

# Landing page :root tokens (read from the live page 2026-11-19).
SITE = {
    "field": "#100b09", "screen": "#16100e", "panel": "#211512", "line": "#6c372c",
    "text": "#f2d9b0", "bright": "#fff4dc", "dim": "#b38362",
    "yellow": "#ffd23f", "orange": "#f2921e", "red": "#e63946",
    "cyan": "#35d6d6", "green": "#34ec5b",
}
# Direction F "Firecall Warm" tokens the landing page does not carry yet
# (DESIGN-FIRELINE.md 2.2b), needed to render the world on the web console.
F_EXTRA = {
    "wall": "#8a6d5e", "empty": "#4a382e", "fuel": "#8d6a1e", "civ": "#fff1e0",
    "prot": "#c77dff", "panel2": "#2a1e1a",
    "fire": ["#ffb02e", "#f2921e", "#e96a2a", "#e0303c"],
}

# Missions.kt authored parameters (id, name, briefing, seed, wind, water, limit).
MISSIONS = [
    (1, "NOZZLE CHECK", "One stationary fire straight ahead. Read DIST/ANGLE, shoot, watch HEAT.",
     1001, "WIND W 0", "WATER 100", 60),
    (2, "CORRIDOR", "The wall in front of you is not negotiable. Turn, walk, shoot what burns ahead, then reach the exit.",
     2002, "WIND W 0", "WATER 100", 120),
    (3, "FIRST RESPONSE", "Two civilians, small fires elsewhere. Contact rescues itself; use CIV_COUNT and the exit sensors.",
     3003, "WIND W 0", "WATER 100", 120),
    (4, "DOWNWIND", "Wind pushes fire into the fuel tiles. Check WIND_DIR/WIND_FORCE, hold the refill station, and drown the burning pair cell by cell.",
     4004, "WIND E 2", "WATER 100", 500),
    (5, "HOT ZONE", "A burning corridor, a refill pocket, a civilian and an asset to protect. Cross it shielded: two ticks to raise, five ticks of protection.",
     5005, "WIND N 0", "WATER 60", 200),
]

STARTER_SOURCE = """; RF-8: FIRELINE starter
; Approach the selected fire, avoid walls, spray when close.

main:  IN     FIRE_DIST
       JZ     done
       CMP    #3
       JLE    spray

       IN     AHEAD
       JZ     face
       TURN   R
       JMP    main

face:  IN     FIRE_ANGLE
       CMP    #255
       JE     main
       JZ     advance
       TURN   R
       JMP    main

advance:
       MOVE
       JMP    main

spray: IN     FIRE_ANGLE
       JZ     fire
       TURN   R
       JMP    spray

fire:  SHOOT
       JMP    main

done:  WAIT
       JMP    done"""

NEVER_SHOOTS_SOURCE = """loop:  WAIT
       JMP    loop"""


# ---------------------------------------------------------------- fixture ---
def load_fixture():
    """Load the committed engine snapshots (main set + bonus set) and merge them."""
    if len(sys.argv) > 1:
        paths = [sys.argv[1]]
    else:
        paths = [DEFAULT_FIXTURE, EXTRA_FIXTURE]
    snaps, loaded = [], paths[0]
    for path in paths:
        try:
            with open(path, encoding="utf-8") as fh:
                for s in json.load(fh).get("snapshots", []):
                    s.setdefault("timeLimit", s.get("time_limit", s.get("tick_limit")))
                    s.setdefault("windDir", s.get("wind_dir"))
                    s.setdefault("windForce", s.get("wind_force"))
                    snaps.append(s)
            loaded = path
        except FileNotFoundError:
            if path == DEFAULT_FIXTURE:
                print("WARNING: fixture %s not readable" % path)
    if not snaps:
        print("WARNING: no engine snapshot fixture loaded; mockups render without world state")
    return snaps, loaded


def by_label(snapshots, needle, mission_id=None):
    for s in snapshots:
        if needle in (s.get("label") or ""):
            if mission_id is None or s.get("mission_id") == mission_id:
                return s
    return None


# ------------------------------------------------------------------ world ---
def glyph_for(snap, x, y):
    """Pick the visible glyph. Layer order documented in the legend."""
    terrain = (snap.get("terrain") or [""] * GRID)[y]
    fire = (snap.get("fire") or [""] * GRID)[y]
    rover = snap.get("rover") or {}
    civs = {(c.get("x"), c.get("y")) for c in (snap.get("civilians") or [])
            if str(c.get("state", "")).upper() in ("", "WAITING", "ALIVE")}
    pkts = {(p.get("x"), p.get("y")) for p in (snap.get("packets") or [])}
    cell = (x, y)
    if rover.get("x") == x and rover.get("y") == y:
        return "R", "rover"
    if cell in civs:
        return "C", "civ"
    if cell in pkts:
        return "*", "packet"
    ch = fire[x]
    if ch in "1234":
        return ch, "fire%s" % ch
    ch = terrain[x]
    return {
        "#": ("#", "wall"), "F": ("F", "fuel"), "W": ("W", "refill"),
        "E": ("E", "exit"), "P": ("P", "prot"), "C": ("C", "civ"),
        "R": (".", "empty"),
    }.get(ch, (ch if ch != " " else ".", "empty"))


def grid_html(snap):
    dir_caret = {"E": "\u25b8", "S": "\u25be", "W": "\u25c2", "N": "\u25b4"}
    rover = snap.get("rover") or {}
    caret = dir_caret.get(str(rover.get("dir", "")).upper(), "")
    out = []
    out.append('<div class="grid">')
    out.append('<span class="corner"></span>')
    for x in range(GRID):
        out.append('<span class="ruler">%02d</span>' % x)
    for y in range(GRID):
        out.append('<span class="ruler">%02d</span>' % y)
        for x in range(GRID):
            glyph, cls = glyph_for(snap, x, y)
            style = ""
            if cls == "empty":
                style = ' style="font-size:8px;opacity:.75"'
            elif cls.startswith("fire"):
                style = ' style="font-weight:bold"'
            extra = ""
            if cls == "rover":
                extra = '<i class="nose">%s</i>' % caret
            out.append('<span class="cell %s"%s>%s%s</span>' % (cls, style, glyph, extra))
    out.append("</div>")
    return "".join(out)


LEGEND = """<div class="legend">
<b><span class="wall">&#x2588;</span> wall</b>
<b><span class="rover">R</span><span class="nose-txt">&#x25b8;</span> rover + facing</b>
<b><span class="fire1">1</span><span class="fire2">2</span><span class="fire3">3</span><span class="fire4">4</span> fire, engine display bands 1..24 / 25..49 / 50..74 / 75..100</b>
<b><span class="packet">*</span> water packet</b>
<b><span class="refill">W</span> refill</b>
<b><span class="exit">E</span> exit</b>
<b><span class="civ">C</span> civilian</b>
<b><span class="fuel">F</span> fuel</b>
<b><span class="prot">P</span> protected asset</b>
<b>layer order: rover &gt; civilian &gt; packet &gt; fire &gt; terrain</b>
</div>"""


# ----------------------------------------------------------------- pieces ---
def source_block(source, pc_line=None, errors=None):
    lines = source.split("\n")
    out = ['<pre class="source">']
    for i, line in enumerate(lines, 1):
        cls = ""
        mark = ""
        if pc_line is not None and i == pc_line:
            cls = ' class="now"'
            mark = "&gt;"
        out.append('<span%s><u>%02d</u> %s%s</span>' % (cls, i, mark, escape(line)))
    out.append("</pre>")
    if errors:
        out.append('<div class="asmerr">%s</div>' % errors)
    return "".join(out)


def escape(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace('"', "&quot;"))


def kv_rows(pairs):
    out = ['<table class="kv">']
    for k, v in pairs:
        out.append('<tr><td class="k">%s</td><td class="v">%s</td></tr>' % (escape(k), escape(str(v))))
    out.append("</table>")
    return "".join(out)


def telemetry_html(snap, full=True):
    """full=True: every readout port (used by the phone mockup, where the
    telemetry block stands alone). full=False: the readout that belongs next to
    the world: FIRELINE ports 16-30 plus the rover vitals. The remaining base
    ports stay reachable in the RF-8 STATE block and the vitals strip."""
    rover = snap.get("rover") or {}
    ports = snap.get("ports") or {}
    vitals = [("HP", ports.get("HP", rover.get("hp", "-"))),
              ("HEAT", ports.get("HEAT", rover.get("heat", "-"))),
              ("WATER", ports.get("WATER", rover.get("water", "-"))),
              ("SHIELD", ports.get("SHIELD", rover.get("shield_ticks") or "-"))]
    order = ["FIRE_DIST", "FIRE_ANGLE", "FIRE_LEVEL", "CIV_COUNT", "CIV_ANGLE",
             "EXIT_DIST", "EXIT_ANGLE", "WIND_DIR", "WIND_FORCE", "MISSION"]
    pairs = list(vitals)
    seen = {"HP", "HEAT", "WATER", "SHIELD"}
    for key in order:
        if key in ports and key not in seen:
            pairs.append((key, ports[key]))
            seen.add(key)
    if not full:
        return kv_rows(pairs)
    for key in sorted(ports):
        if key not in seen:
            pairs.append((key, ports[key]))
    return kv_rows(pairs)


def stats_html(snap):
    st = snap.get("stats") or {}
    return kv_rows([
        ("SHOOTS FIRED", st.get("shots", "-")),
        ("LOCKED-OUT SHOTS", st.get("dry_shots", "-")),
        ("MOVES", st.get("moves", "-")),
        ("SHIELD ACTIVATIONS", st.get("shields", "-")),
        ("WATER USED", st.get("water_used", "-")),
        ("BURNING CELLS", st.get("burning_cells", "-")),
        ("CIVILIANS RESCUED / WAITING / LOST", "%s / %s / %s" % (
            st.get("civilians_rescued", "-"), st.get("civilians_waiting", "-"),
            st.get("civilians_lost", "-"))),
        ("TOTAL FIRE INTENSITY", snap.get("fire_intensity_total", "-")),
    ])


def vm_html(snap):
    vm = snap.get("vm") or {}
    reg = vm.get("registers") or {}
    flags = vm.get("flags") or {}
    lit = lambda name: ("on" if flags.get(name) else "off")  # noqa: E731
    pc = vm.get("pc", "-")
    pc_text = pc if str(pc).startswith("$") else "$%s" % pc
    rows = [
        ("A", reg.get("a", "-")), ("X", reg.get("x", "-")),
        ("Y", reg.get("y", "-")), ("SP", reg.get("sp", "-")),
        ("PC", "%s (%s)" % (pc_text, vm.get("pc_label", "entry")) if vm.get("pc_label") else pc_text),
        ("OP", vm.get("instruction", "-")),
    ]
    if vm.get("instruction_ticks_remaining") not in (None, 0):
        rows.append(("OP TICKS LEFT", vm["instruction_ticks_remaining"]))
    flags_line = "Z=%d N=%d C=%d" % (
        1 if flags.get("z") else 0, 1 if flags.get("n") else 0, 1 if flags.get("c") else 0)
    return ('<div class="cols"><div class="col"><h4>RF-8 STATE</h4>%s'
            '<div class="flags">FLAGS %s</div></div>'
            '<div class="col"><h4>EXECUTED SOURCE LINE</h4>%s</div></div>') % (
        kv_rows(rows), flags_line,
        source_block(vm.get("source") or STARTER_SOURCE, vm.get("pc_line_number")))


def events_html(snap):
    events = snap.get("events") or []
    if not events:
        # Fall back to the fixture's last-six-tick window, newest first.
        for entry in reversed(snap.get("recent_events") or []):
            for text in reversed(entry.get("events") or []):
                events.append({"tick": entry.get("tick"), "text": text})
            if events:
                break
    out = ['<ul class="events">']
    for i, ev in enumerate(events):
        if isinstance(ev, dict):
            tick, text = ev.get("tick", ""), ev.get("text", "")
        else:
            tick, text = "", ev
        out.append('<li class="%s"><u>%s</u>%s</li>' %
                   ("newest" if i == 0 else "", escape(str(tick)), escape(text)))
    if not events:
        out.append('<li class="newest"><u>%s</u>no event on this tick</li>' % snap.get("tick", ""))
    out.append("</ul>")
    return "".join(out)


def objectives_html(snap):
    done = snap.get("objectives_done") or []
    names = snap.get("objectives_readable") or []
    status = str(snap.get("status", "RUNNING")).upper()
    out = []
    for name in names:
        if name in done:
            out.append('<span class="ok">%s done</span>' % name)
        elif status == "FAILURE":
            out.append('<span class="bad">%s failed</span>' % name)
        else:
            out.append('<span class="pend">%s pending</span>' % name)
    out.append('<span class="pend">tick %s/%s</span>' % (
        snap.get("tick", 0), snap.get("timeLimit", snap.get("tick_limit", "-"))))
    return '<div class="objline">' + "&nbsp;&nbsp;".join(out) + "</div>"


def mission_header(snap, firmware_name, extra=""):
    mid = snap.get("mission_id", 1)
    name = snap.get("mission_name", "")
    briefing = snap.get("briefing") or next((m[2] for m in MISSIONS if m[0] == mid), "")
    return """<div class="mhead">
 <div><span class="mid">MISSION %02d / %s</span><div class="brief">%s</div></div>
 <div class="mparams">%s&nbsp;&nbsp;%s&nbsp;&nbsp;%s&nbsp;&nbsp;seed %s&nbsp;&nbsp;%s%s</div>
</div>""" % (mid, escape(name), escape(briefing),
            escape(firmware_name), str(snap.get("seed", "-")),
            str(snap.get("windDir", snap.get("wind_dir", "-"))) + " " + str(snap.get("windForce", "-")),
            str(snap.get("seed", "-")), str(snap.get("timeLimit", "-")), extra)


def chips_html(snapshots_used):
    items = []
    for label, hexv in [("field", SITE["field"]), ("screen", SITE["screen"]),
                        ("panel", SITE["panel"]), ("line", SITE["line"]),
                        ("chrome", SITE["text"]), ("value", SITE["bright"]),
                        ("dim", SITE["dim"]), ("rover", SITE["yellow"]),
                        ("RUN", SITE["red"]), ("water", SITE["cyan"]),
                        ("exit", SITE["green"]), ("wall", F_EXTRA["wall"]),
                        ("fuel", F_EXTRA["fuel"]), ("asset", F_EXTRA["prot"])]:
        items.append('<span class="chip"><i style="background:%s"></i>%s %s</span>' % (hexv, label, hexv))
    ramp = "".join('<i style="background:%s"></i>' % c for c in F_EXTRA["fire"])
    items.append('<span class="chip">%s<span style="margin-left:4px">fire 1-4 (dir. F)</span></span>' % ramp)
    src = "engine snapshots: " + ", ".join(snapshots_used) if snapshots_used else "no engine snapshot attached"
    return '<footer>%s<div class="provenance">%s</div></footer>' % (" ".join(items), escape(src))


def statusbar(snap, permalink):
    """Vitals strip under the run surface. Deliberately NOT a control row any
    more: RUN/STEP/RESET, SPEED, PANIC, STATE and LINK live in the single
    control block under the firmware column, so no second run bar exists."""
    rover = snap.get("rover") or {}
    fp = str(snap.get("fingerprint", "-")).lstrip("$")
    return """<div class="statusbar">
 <div class="vitals"><b>VITALS</b> HP %s&nbsp;&nbsp;WATER %s&nbsp;&nbsp;HEAT %s&nbsp;&nbsp;SHIELD %s</div>
 <div class="finger"><b>STATE</b> tick %s&nbsp;&nbsp;fingerprint $%s</div>
</div>""" % (escape(str(rover.get("hp", "-"))), escape(str(rover.get("water", "-"))),
            escape(str(rover.get("heat", "-"))), escape(str(rover.get("shield_ticks", 0) or 0)),
            escape(str(snap.get("tick", 0))), escape(fp))


# ------------------------------------------------------------------- CSS ----
CSS = """
*{box-sizing:border-box}
body{margin:0;padding:18px;background:#0b0806;color:#f2d9b0;
  font:13px/1.55 "Courier New",Courier,monospace;text-shadow:0 0 7px rgba(242,146,30,.12)}
body:before{content:"";position:fixed;inset:0;z-index:20;pointer-events:none;
  background:repeating-linear-gradient(0deg,rgba(0,0,0,.16) 0,rgba(0,0,0,.16) 1px,transparent 1px,transparent 4px)}
a{color:#35d6d6;text-decoration:none}
.meta{max-width:1312px;margin:0 auto 10px;color:#b38362;font-size:12px}
.meta b{color:#fff4dc;font-weight:normal}
.page{--field:#100b09;--screen:#16100e;--panel:#211512;--panel2:#2a1e1a;--line:#6c372c;
  --text:#f2d9b0;--bright:#fff4dc;--dim:#b38362;--yellow:#ffd23f;--orange:#f2921e;--red:#e63946;
  --cyan:#35d6d6;--green:#34ec5b;--wall:#8a6d5e;--empty:#4a382e;--fuel:#8d6a1e;--civ:#fff1e0;
  --prot:#c77dff;--f1:#ffb02e;--f2:#f2921e;--f3:#e96a2a;--f4:#e0303c;
  width:min(1312px,100%);margin:0 auto;padding:1.2rem 1.2rem 2rem;background:var(--field);color:var(--text)}
.page.narrow{width:min(426px,100%)}
.topline{color:var(--yellow);font-size:13px}
.right{float:right}
.rule{border:0;border-top:1px solid var(--line);margin:1rem 0}
.nav{display:flex;flex-wrap:wrap;gap:.4rem 1.2rem;margin:.8rem 0 1.6rem;font-size:13px}
.nav a{color:var(--dim)}
.nav a:before{content:"["}.nav a:after{content:"]"}
.nav a.on{color:var(--bright)}
.nav a.on:before{content:">["}.nav a.on:after{content:"]<"}
.eyebrow{color:var(--orange);letter-spacing:.12em;font-size:12px}
h1{margin:.3rem 0 .8rem;color:var(--bright);font-size:44px;line-height:.95;letter-spacing:-.04em;font-weight:normal}
h1 span{display:block;color:var(--yellow);font-size:.34em;letter-spacing:.02em;margin-top:.6rem}
h2{margin:0 0 .5rem;color:var(--bright);font-size:20px;letter-spacing:.02em;font-weight:normal}
h4{margin:0 0 .4rem;color:var(--dim);font-size:11px;letter-spacing:.14em;font-weight:normal}
.lede{color:var(--text);max-width:60ch}
.prompt{color:var(--yellow);margin:1rem 0}
.command{display:inline-block;border:1px solid var(--line);padding:.5rem .8rem;color:var(--bright);
  background:var(--panel);margin:.3rem .4rem .3rem 0;font-size:13px}
.command.primary{background:var(--red);border-color:var(--red);color:#120a0a}
.command.secondary{color:var(--text)}
.panel{border:1px solid var(--line);background:var(--panel);padding:1rem 1.1rem;margin:0 0 1rem}
.row{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:1rem}
.row.wide-left{grid-template-columns:minmax(0,1.25fr) minmax(320px,.75fr)}
.console{border:1px solid var(--line);background:var(--screen);margin:0 0 1rem}
.titlebar{display:flex;justify-content:space-between;gap:1rem;border-bottom:1px solid var(--line);
  padding:.45rem .8rem;color:var(--dim);font-size:12px;letter-spacing:.06em}
.titlebar b{color:var(--bright);font-weight:normal}
.consolebody{padding:.8rem}
.mhead{display:flex;justify-content:space-between;flex-wrap:wrap;gap:.4rem 1rem;
  border-bottom:1px solid var(--line);padding:0 0 .5rem;margin-bottom:.6rem}
.mid{color:var(--bright);letter-spacing:.06em}
.brief{color:var(--dim);font-size:12px;max-width:62ch;margin-top:.25rem}
.mparams{color:var(--dim);font-size:12px;text-align:right}
.objline{font-size:12px;letter-spacing:.04em;margin:.4rem 0 .2rem}
.ok{color:var(--green)}.pend{color:var(--dim)}.bad{color:var(--red)}
.wind{color:var(--cyan);font-size:12px;margin-bottom:.35rem}
.grid{display:grid;grid-template-columns:30px repeat(20,15px);grid-auto-rows:15px;
  background:var(--panel2);border:1px solid var(--line);width:max-content}
.cell{display:flex;align-items:center;justify-content:center;font-size:12px;
  border:1px solid rgba(108,55,44,.35);position:relative}
.ruler,.corner{color:var(--dim);font-size:9px;display:flex;align-items:center;justify-content:center}
.corner:before{content:"y/x"}
.cell.rover{color:var(--yellow);font-weight:bold;font-size:13px}
.cell.rover .nose{position:absolute;font-size:8px;font-style:normal;color:var(--green)}
.cell.wall{color:var(--wall);font-size:14px}
.cell.refill{color:var(--cyan)}.cell.exit{color:var(--green)}.cell.fuel{color:var(--fuel)}
.cell.civ{color:var(--civ)}.cell.prot{color:var(--prot)}
.cell.packet{color:var(--cyan)}
.cell.fire1{color:var(--f1)}.cell.fire2{color:var(--f2)}.cell.fire3{color:var(--f3)}.cell.fire4{color:var(--f4)}
.cell.empty{color:var(--empty)}
.legend{display:flex;flex-wrap:wrap;gap:.2rem 1rem;font-size:11px;color:var(--dim);margin-top:.6rem}
.legend b{font-weight:normal}
.wall{color:var(--wall)}.rover{color:var(--yellow)}.nose-txt{color:var(--green)}
.refill,.packet{color:var(--cyan)}.exit{color:var(--green)}.civ{color:var(--civ)}
.fuel{color:var(--fuel)}.prot{color:var(--prot)}
.fire1{color:var(--f1)}.fire2{color:var(--f2)}.fire3{color:var(--f3)}.fire4{color:var(--f4)}
.cols{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:1rem;margin-top:.8rem}
.kv{border-collapse:collapse;width:100%;font-size:12px}
.kv td{padding:1px 4px}
.kv td.k{color:var(--dim);white-space:nowrap}
.kv td.v{color:var(--bright);text-align:right;font-variant-numeric:tabular-nums}
pre.source{margin:0;font-family:"Courier New",Courier,monospace;font-size:12px;line-height:1.5;
  background:var(--panel);border:1px solid var(--line);padding:.5rem .6rem;overflow-x:auto}
pre.source span{display:block;white-space:pre}
pre.source u{color:var(--dim);text-decoration:none;margin-right:.5rem}
pre.source span.now{color:var(--bright);background:var(--panel2)}
pre.source span.now u{color:var(--red)}
.asmerr{color:var(--red);font-size:12px;margin-top:.4rem}
.flags{color:var(--dim);font-size:12px;margin-top:.4rem}
ul.events{margin:0;padding:0;list-style:none}
ul.events li{padding:2px 0 2px 8px;border-left:2px solid transparent;font-size:12px;color:var(--dim)}
ul.events li u{color:var(--dim);opacity:.7;margin-right:.6rem;text-decoration:none}
ul.events li.newest{color:var(--bright);border-left-color:var(--red)}
.statusbar{border:1px solid var(--line);background:var(--panel);padding:.6rem .8rem;margin:0 0 1rem;
  display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1fr);gap:.6rem 1rem;align-items:center}
.statusbar .vitals,.statusbar .finger{font-size:12px;color:var(--dim)}
.statusbar b{color:var(--yellow);font-weight:normal}
.controls{display:flex;gap:.5rem}
.btn{border:1px solid var(--line);background:var(--panel2);color:var(--text);
  font:inherit;font-size:13px;letter-spacing:.12em;padding:.85rem 1.3rem;min-width:74px}
.btn.run{background:var(--red);border-color:var(--red);color:#120a0a}
code{color:var(--bright);font-family:inherit;word-break:break-all}
.mini{font-size:12px;margin-left:.5rem}
.finger{font-variant-numeric:tabular-nums}
.missions{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:1rem}
.mcard{border:1px solid var(--line);background:var(--panel);padding:.8rem .9rem}
.mcard h3{margin:0 0 .35rem;font-size:15px;color:var(--bright);font-weight:normal;letter-spacing:.05em}
.mcard p{margin:0 0 .5rem;color:var(--text);font-size:12px}
.mcard .meta2{font-size:11px;color:var(--dim);letter-spacing:.04em;margin-bottom:.6rem}
.badge{font-size:11px;letter-spacing:.1em;padding:.15rem .45rem;border:1px solid var(--line)}
.badge.done{color:var(--green);border-color:var(--green)}
.badge.fail{color:var(--red);border-color:var(--red)}
.badge.new{color:var(--yellow);border-color:var(--yellow)}
.banner{border:1px solid var(--line);padding:.8rem 1rem;margin-bottom:1rem;font-size:18px;letter-spacing:.05em}
.banner.fail{border-color:var(--red);color:var(--bright);background:rgba(230,57,70,.12)}
.banner.ok{border-color:var(--green);color:var(--bright);background:rgba(52,236,91,.10)}
.status4{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));border:1px solid var(--line);margin:1rem 0}
.status4 div{padding:.6rem .8rem;font-size:12px;color:var(--text);border-right:1px solid var(--line)}
.status4 div:last-child{border-right:0}
.status4 b{display:block;color:var(--yellow);font-weight:normal;letter-spacing:.1em;font-size:11px}
.cta{border:1px solid var(--line);background:var(--panel);padding:.9rem 1.1rem;margin:1rem 0}
.cta strong{color:var(--bright);font-weight:normal;display:block;margin:.25rem 0}
footer{border-top:1px solid var(--line);padding-top:.7rem;margin-top:1rem;font-size:11px;color:var(--dim)}
.chip{display:inline-flex;align-items:center;gap:5px;margin-right:2px}
.chip i{width:10px;height:10px;display:inline-block;border:1px solid var(--line)}
.provenance{margin-top:.5rem;font-size:11px}
.privacy{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(0,1fr);gap:1rem}
.privacy ul{margin:0;padding-left:1.1rem;color:var(--dim);font-size:12px}
.small{color:var(--dim);font-size:12px}
/* paired run surface: world on the left, firmware you wrote on the right */
.pair{display:grid;grid-template-columns:minmax(0,1fr) minmax(320px,1.1fr);gap:1.2rem;
  align-items:stretch;margin-top:.5rem}
.pairworld h4,.pairfirm h4{margin-bottom:.5rem}
.pairfirm pre.source{max-height:none;height:var(--src-height,none);overflow:auto}
.pairfirm .asmnote{margin-top:.5rem}
.fcell em{font-style:normal;opacity:.7}
/* full-width horizontal readout fields: one bordered band, label + value per cell */
.fields{border:1px solid var(--line);background:var(--panel);padding:.45rem .6rem;margin:0 0 .8rem}
.fields h4{margin-bottom:.4rem}
.frow{display:flex;flex-wrap:wrap;gap:4px}
.fcell{flex:1 1 118px;border:1px solid var(--line);background:var(--field);
  padding:3px 6px;display:flex;flex-direction:column;gap:1px;min-width:0}
.fcell span{font-size:9.5px;letter-spacing:.05em;color:var(--dim);white-space:nowrap;
  overflow:hidden;text-overflow:ellipsis}
.fcell b{font-size:13px;font-weight:normal;color:var(--bright);font-variant-numeric:tabular-nums;
  word-break:break-word}
.fields .legend{margin-top:.4rem}
/* every control needed to run a firmware, in one block under the firmware column:
   a run row (commands) and a machine row (speed, state, share/download) */
.consolecontrols{margin-top:.7rem}
.consolecontrols .runrow{display:flex;flex-wrap:wrap;gap:.5rem}
.consolecontrols .runrow .btn{font-size:14px;padding:0 14px;min-height:49px}
.consolecontrols .inforow{display:flex;flex-wrap:wrap;gap:.5rem 1.2rem;align-items:center;
  margin-top:.5rem;font-size:12px;color:var(--dim)}
.consolecontrols .inforow b{color:var(--yellow);font-weight:normal}
.consolecontrols .chip.on{background:var(--yellow);color:#1a1005;border-color:var(--yellow)}
.consolecontrols code{font-size:11.5px;word-break:break-all}
.consolecontrols .inforow > .link{flex:1 1 100%}
@media(max-width:900px){.pair{grid-template-columns:minmax(0,1fr)}
  .pairfirm pre.source{height:calc(21 * 11px + 1px)}}
@media(max-width:640px){.fcell{flex:1 1 100px}}
@media(max-width:900px){.row,.row.wide-left,.cols,.privacy{grid-template-columns:minmax(0,1fr)}
  .statusbar{grid-template-columns:minmax(0,1fr)}
  .grid{grid-template-columns:24px repeat(20,11px);grid-auto-rows:11px}
  .cell{font-size:10px}.cell.rover{font-size:11px}.cell.wall{font-size:11px}
  .status4{grid-template-columns:1fr 1fr}}
"""


def nav(active):
    items = ["HOME", "MISSION", "THREE CALLS", "TOOLS", "PLAY", "PRIVACY", "ARENA"]
    return '<nav class="nav">' + "".join(
        '<a%s>%s</a>' % (' class="on"' if it == active else "", it) for it in items) + "</nav>"


def nav_console(active):
    items = ["MISSION", "FIRMWARE", "RUN", "RESULT", "PRIVACY"]
    return '<nav class="nav">' + "".join(
        '<a%s>%s</a>' % (' class="on"' if it == active else "", it) for it in items) + "</nav>"


def shell(inner, active, narrow=False, tagline="THE SAME LITTLE COMPUTER. A DIFFERENT JOB.",
          right="RF-8 / LIVE"):
    return ('<div class="page%s"><div class="topline">OPCODE FIRELINE WEB CONSOLE'
            ' <span class="right">%s</span></div><hr class="rule">%s%s</div>'
            % (" narrow" if narrow else "", right, nav_console(active), inner))


def fields_band(title, pairs, note=""):
    """One full-width bordered band of horizontal readout cells. Every cell
    carries its own label, so the row can wrap without losing meaning. The label
    goes above the value; an optional second label (tag) sits next to it."""
    cells = []
    for item in pairs:
        label, value = item[0], item[1]
        tag = (' <em>%s</em>' % escape(str(item[2]))) if len(item) > 2 and item[2] else ""
        cells.append('<div class="fcell"><span>%s%s</span><b>%s</b></div>'
                     % (escape(str(label)), tag, escape(str(value))))
    return ('<div class="fields"><h4>%s</h4><div class="frow">%s</div>%s</div>'
            % (escape(title), "".join(cells), note))


def telemetry_fields_html(snap):
    """TELEMETRY as a horizontal field using the whole width. Only FIRELINE's
    own readout ports: the base RF-8 vitals are already on the status line, so a
    VITALS row here would be redundant. Keys come from the engine snapshot."""
    ports = snap.get("ports") or {}
    base = {"HP", "HEAT", "WATER", "DIR", "RX", "RY", "DIST", "ANGLE", "FIRE", "SHIELD",
            "ENEMY_HP", "AHEAD", "PROFILE", "PROFILE_REV", "CAPS", "TILE", "RAND", "TICK",
            "MISSION"}
    order = ["FIRE_DIST", "FIRE_ANGLE", "FIRE_LEVEL", "LOCAL_FIRE", "SMOKE",
             "CIV_DIST", "CIV_ANGLE", "CIV_COUNT",
             "EXIT_DIST", "EXIT_ANGLE", "WIND_DIR", "WIND_FORCE"]
    keys = [k for k in order if k in ports and k not in base]
    keys += [k for k in sorted(ports) if k not in base and k not in keys]
    pairs = [(k, ports[k], "") for k in keys]
    return fields_band("TELEMETRY (FIRELINE READOUT PORTS)", pairs)


def vm_fields_html(snap):
    """RF-8 STATE as a horizontal field using the whole width: registers, the
    instruction the monitor is on, the flags, and the run identity."""
    vm = snap.get("vm") or {}
    reg = vm.get("registers") or {}
    flags = vm.get("flags") or {}
    pc = vm.get("pc", "-")
    pc_text = pc if str(pc).startswith("$") else "$%s" % pc
    if vm.get("pc_label"):
        pc_text = "%s (%s)" % (pc_text, vm["pc_label"])
    pairs = [("A", reg.get("a", "-")), ("X", reg.get("x", "-")),
             ("Y", reg.get("y", "-")), ("SP", reg.get("sp", "-")),
             ("PC", pc_text), ("LAST OP", vm.get("last_op", "-")),
             ("FLAGS Z", 1 if flags.get("z") else 0),
             ("FLAGS N", 1 if flags.get("n") else 0),
             ("FLAGS C", 1 if flags.get("c") else 0),
             ("OP TICKS LEFT", vm.get("instruction_ticks_remaining") or 0),
             ("VM FAULT", "no" if vm.get("fault") in (False, None) else "yes")]
    pairs.append(("TICK", snap.get("tick", 0)))
    # no symbol legend and no state fingerprint here: the fingerprint is already on
    # the status line directly below, and the legend belongs to the world grid
    return fields_band("RF-8 STATE (REGISTERS, FLAGS, RUN IDENTITY)", pairs)


def ports_meaning_html():
    """Port semantics, always label + meaning. The owner rejected a run of bare
    values without labels, so nothing in the readout area may drop its label."""
    return kv_rows([
        ("16 FIRE_DIST", "cells to the nearest fire along the heading, 0 when nothing burns in range"),
        ("17 FIRE_ANGLE", "fire offset in 10-degree steps -180..180, 255 when outside the +-90 cone"),
        ("18 FIRE_LEVEL", "intensity of the fire at your current heading, 0 when nothing burns"),
        ("19 TARGET_KIND", "1 FIRE, 2 CIVILIAN, 3 REFILL, 4 EXIT"),
        ("20 TARGET_DIST", "cells to that target"),
        ("21 TARGET_ANGLE", "its offset in 10-degree steps"),
        ("22 TARGET_LEVEL", "its level: fire intensity, civilian health, refill amount"),
        ("24 EXIT_DIST", "cells to the exit, through walls"),
        ("25 EXIT_ANGLE", "direction to the exit in 10-degree steps"),
        ("26 WIND_DIR", "0-7 compass direction the wind blows toward"),
        ("27 WIND_FORCE", "0-4, how hard the wind pushes fire and smoke"),
        ("28 CIV_COUNT", "civilians still waiting in the building"),
        ("29 CIV_ANGLE", "direction to the nearest waiting civilian"),
        ("30 MISSION", "objective bits: fire out, civilians, water, exit"),
    ])


def run_controls(snap, permalink, with_link=True):
    """Everything needed to run a firmware, as one block under the firmware
    column: a run row with the three commands, then a machine row carrying the
    tick speed, the state fingerprint and the share/export link. Command height
    >= 49px, the same gate as Android's 48dp."""
    fp = str(snap.get("fingerprint", "-")).lstrip("$")
    row = ['<div class="consolecontrols">',
           '<div class="runrow"><a class="btn run">RUN</a><a class="btn step">STEP</a>'
           '<a class="btn reset">RESET</a></div>',
           '<div class="inforow"><span><b>SPEED</b> 1 / 5 / 20 / 50 ticks per second '
           '<span class="chip on">5 SELECTED</span>&nbsp;&nbsp;'
           '<b>PANIC</b> uncapped, no speed limit</span>']
    if with_link and permalink:
        row.append('<span class="link"><b>LINK</b> <code>%s</code> <a class="mini">COPY</a>'
                   '<a class="mini">DOWNLOAD .ASM</a></span>' % escape(permalink))
    row.append("</div>")
    return "".join(row)


def source_pane(source, pc_line=None, height_px=None):
    """One single <pre> for the firmware pane. (An earlier attempt wrapped
    source_block() in a second <pre> to set the pane height, which nested two
    scrollable pres and rendered two scrollbars.)"""
    style = ' style="--src-height:%dpx"' % height_px if height_px else ""
    out = ['<pre class="source"%s>' % style]
    for i, line in enumerate(source.split("\n"), 1):
        cls, mark = "", ""
        if pc_line is not None and i == pc_line:
            cls, mark = ' class="now"', "&gt;"
        out.append('<span%s><u>%02d</u> %s%s</span>' % (cls, i, mark, escape(line)))
    out.append("</pre>")
    return "".join(out)


def console_pair(snap, firmware_name, include_header=True, with_link=True):
    """The run surface the product is built around: the world on the left with
    its horizontal telemetry strip, the firmware you wrote on the right with
    every control needed to run it, always side by side (stacks only at phone
    width). Shared by the console mockup and the playable landing page."""
    mid = snap.get("mission_id", 1)
    tick = str(snap.get("tick", 0))
    source = (snap.get("vm") or {}).get("source") or STARTER_SOURCE
    permalink = ("https://opcode-fireline.appsystems.at/play/#run="
                 "FLW1-02-2002-m4VxQ9pK2sT7nYb0cR6d")
    parts = ['<div class="console"><div class="titlebar"><span>MISSION %02d://%s</span>'
             '<span>T+0000%s</span></div><div class="consolebody">'
             % (mid, escape(snap.get("mission_name", "")), tick.zfill(4))]
    if include_header:
        parts.append(mission_header(snap, firmware_name))
    parts.append(objectives_html(snap))
    wind = "%s&nbsp;&nbsp;FORCE %s" % (snap.get("windDir", snap.get("wind_dir", "-")),
                                        snap.get("windForce", "-"))
    parts.append('<div class="pair">')
    parts.append('<div class="pairworld"><div class="wind">WIND %s</div>%s%s'
                 '<h4 style="margin-top:.7rem">EVENT LOG</h4>%s</div>'
                 % (wind, grid_html(snap), LEGEND, events_html(snap)))
    parts.append('<div class="pairfirm"><h4>FIRMWARE: %s</h4>%s%s'
                 '<div class="flags asmnote">assembler: 0 errors, %s bytes, %d source lines. '
                 'the &gt; marker is the instruction the execution monitor is on; '
                 'the source pane scrolls while it runs. '
                 'cheat sheet: base ports 0-11 | FIRELINE ports 16-30 | '
                 'no-target sentinels DIST 0 / ANGLE 255 / LEVEL 0</div></div>'
                 % (escape(firmware_name),
                    source_pane(source, (snap.get("vm") or {}).get("pc_line_number"), 21 * 15 + 1),
                    run_controls(snap, permalink, with_link),
                    str(snap.get("firmware_bytes", "-")), len(source.split("\n"))))
    parts.append("</div>")            # .pair
    parts.append("</div></div>")      # .consolebody / .console
    return "".join(parts)


def mission_console(snap, firmware_name, permalink, active="RUN"):
    body = [console_pair(snap, firmware_name),
            vm_fields_html(snap), telemetry_fields_html(snap),
            statusbar(snap, permalink)]
    body.append('<div class="cols"><div class="col"><h4>WHAT THE PORTS MEAN</h4>%s</div>'
                '<div class="col"><h4>RUN STATS</h4>%s</div></div>'
                % (ports_meaning_html(), stats_html(snap)))
    return shell("".join(body), active)


def vm_tables_html(snap):
    vm = snap.get("vm") or {}
    reg = vm.get("registers") or {}
    flags = vm.get("flags") or {}
    pc = vm.get("pc", "-")
    pc_text = pc if str(pc).startswith("$") else "$%s" % pc
    rows = [("A", reg.get("a", "-")), ("X", reg.get("x", "-")),
            ("Y", reg.get("y", "-")), ("SP", reg.get("sp", "-")),
            ("PC", "%s (%s)" % (pc_text, vm.get("pc_label", "entry")) if vm.get("pc_label") else pc_text),
            ("LAST OP", vm.get("last_op", "-")),
            ("VM FAULT", "no" if vm.get("fault") in (False, None) else "yes")]
    if vm.get("instruction_ticks_remaining") not in (None, 0):
        rows.append(("OP TICKS LEFT", vm["instruction_ticks_remaining"]))
    flags_line = "Z=%d N=%d C=%d" % (
        1 if flags.get("z") else 0, 1 if flags.get("n") else 0, 1 if flags.get("c") else 0)
    return '%s<div class="flags">FLAGS %s</div>' % (kv_rows(rows), flags_line)


# ---------------------------------------------------------------- mockups ---
def m1_play_panel(snaps):
    s_mid = (by_label(snaps, "ref-t15", 1) or by_label(snaps, "ref-t12", 1)
             or by_label(snaps, "ref-t0", 1) or by_label(snaps, "nozzle", 1))
    cards = []
    # Best results shown in the mockup come from real engine fingerprints, read
    # from the committed fixture labels `01-ref-final` and `02-ref-final`, never
    # typed here: a fingerprint bump moves the mockup with the fixture.
    best = {}
    for mid in (1, 2):
        ref_final = by_label(snaps, "final", mid)
        if ref_final and ref_final.get("mission_id") == mid and ref_final.get("fingerprint"):
            best[mid] = ("$" + str(ref_final.get("fingerprint", "")).lstrip("$"), "done")
    for mid, name, brief, seed, wind, water, limit in MISSIONS:
        badge, badge_cls = ("NOT RUN", "new")
        if mid in best:
            badge, badge_cls = best[mid][0], "done"
        cards.append(
            '<article class="mcard"><h3>MISSION %02d / %s</h3><p>%s</p>'
            '<div class="meta2">seed %d&nbsp;&nbsp;%s&nbsp;&nbsp;%s&nbsp;&nbsp;limit %d ticks</div>'
            '<span class="badge %s">%s</span> <a class="mini">OPEN IN CONSOLE</a></article>'
            % (mid, escape(name), escape(brief), seed, wind, water, limit, badge_cls,
               ("BEST RUN " + badge if badge_cls == "done" else badge)))
    console = ""
    if s_mid:
        tick = str(s_mid.get("tick", 0))
        console = ('<div class="console"><div class="titlebar"><span>MISSION://NOZZLE CHECK</span>'
                   '<span>T+0000%s</span></div><div class="consolebody"><div class="wind">WIND W&nbsp;&nbsp;FORCE 0</div>%s%s'
                   '<div class="small" style="margin-top:.5rem">live console once the RF-8 engine '
                   'binary is loaded: editor, RUN, STEP one world tick, RESET, telemetry, event log, '
                   '.asm import/export.</div></div></div>'
                   % (tick.zfill(4), grid_html(s_mid), LEGEND))
    else:
        console = ('<div class="console"><div class="titlebar"><span>MISSION://NOZZLE CHECK</span>'
                   '<span>T+000000</span></div><div class="consolebody"><div class="small">'
                   'engine snapshot fixture not attached to this mockup run</div></div></div>')
    inner = """<div class="eyebrow">LIVE CONSOLE // RUNS IN YOUR BROWSER</div>
<h1>PROGRAM THE RESPONSE</h1>
<p class="lede">SAME PAGE, SAME LITTLE COMPUTER. WRITE RF-8 ASSEMBLY HERE, DEPLOY IT TO THE
ROVER, AND WATCH YOUR CODE EXECUTE ONE WORLD TICK AT A TIME. NO INSTALL, NO ACCOUNT.
THE SAME ENGINE THAT RUNS THE ANDROID APP RUNS IN THIS TAB.</p>
<div class="prompt">FL:&gt; SELECT A CALL</div>
<div class="missions">%s</div>
<div class="row wide-left" style="margin-top:1rem">
  <div>%s</div>
  <div class="panel"><h4>STARTER FIRMWARE // FIRELINE-START.ASM</h4>%s
    <div class="flags">deploy, watch it fail somewhere, then edit it. that is the whole game.</div>
    <div style="margin-top:.7rem"><a class="command primary">RUN IN CONSOLE</a>
      <a class="command secondary">DOWNLOAD .ASM</a></div></div>
</div>
<div class="status4">
  <div><b>ENGINE</b>SAME :rf8-core + :fireline-engine</div>
  <div><b>DETERMINISM</b>STATE FINGERPRINT PER RUN</div>
  <div><b>STORAGE</b>YOUR BROWSER ONLY</div>
  <div><b>NETWORK</b>ONE PAGE LOAD, THEN LOCAL</div>
</div>
<div class="cta"><p>FL:&gt; RELEASE STATUS</p>
<strong>OPCODE FIRELINE 0.1 // ANDROID PLAY + BROWSER CONSOLE</strong>
<p>FREE. YOUR CODE ON THE NOZZLE. PLAY HERE, INSTALL IF YOU WANT IT IN YOUR POCKET.</p></div>
<div class="privacy"><div><h2>PRIVATE BY DESIGN, ALSO IN THE BROWSER</h2>
<p class="small">THE PAGE AND THE ENGINE BINARY LOAD ONCE OVER HTTPS. AFTER THAT EVERY RUN,
EVERY FIRMWARE AND EVERY RESULT STAYS IN THIS TAB'S LOCAL STORAGE. NO ACCOUNT, NO ADS,
NO ANALYTICS, NO THIRD-PARTY SCRIPTS, NO SERVER-SIDE EXECUTION OF YOUR CODE.</p>
<a class="command secondary">READ WEB PRIVACY STATUS</a></div>
<div><ul>
<li>FIRMWARE AND RESULTS LIVE IN BROWSER LOCAL STORAGE ONLY.</li>
<li>SHARE LINKS CARRY YOUR CODE IN THE URL FRAGMENT, NOT TO OUR SERVER.</li>
<li>EXPORT ANY FIRMWARE AS PLAIN UTF-8 .ASM AND OPEN IT IN THE ANDROID APP.</li>
<li>CLEARING SITE DATA OR THE IN-PAGE DELETE REMOVES EVERYTHING.</li>
</ul></div></div>""" % ("".join(cards), console, source_block(STARTER_SOURCE))
    return ('<div class="page"><div class="topline">OPCODE FIRELINE RESPONSE SYSTEM'
            ' <span class="right">RF-8 / STANDBY</span></div><hr class="rule">%s%s</div>'
            % (nav("PLAY"), inner))


def m5_combined(snap):
    """Playable landing page: web-1's framing and content with web-2's paired
    run surface (world left, firmware right) as the main panel."""
    permalink = ("https://opcode-fireline.appsystems.at/play/#run="
                 "FLW1-02-2002-m4VxQ9pK2sT7nYb0cR6d")
    mid = snap.get("mission_id", 2)
    tick = snap.get("tick", 0)
    inner = []
    inner.append('<div class="eyebrow">LIVE CONSOLE // RUNS IN YOUR BROWSER</div>')
    inner.append('<h1>PROGRAM THE RESPONSE<span>THE SAME LITTLE COMPUTER. A DIFFERENT JOB.</span></h1>')
    inner.append('<p class="lede">WRITE RF-8 ASSEMBLY ON THE RIGHT. THE ROVER ON THE LEFT RUNS IT, '
                 'ONE WORLD TICK AT A TIME, WITH THE SAME ENGINE AND THE SAME MISSIONS THE ANDROID '
                 'APP USES. NO INSTALL, NO ACCOUNT, NO SAVED PROGRESS ON OUR SIDE.</p>')
    inner.append('<div class="prompt">FL:&gt; %s CALL IN PROGRESS // TICK %s OF %s</div>'
                 % (escape(str(snap.get("mission_name", ""))), tick,
                    snap.get("timeLimit", snap.get("time_limit", "-"))))
    inner.append('<a class="command primary">DOWNLOAD THE FIRMWARE (.ASM)</a>'
                 '<a class="command secondary">READ HOW A RUN WORKS</a>'
                 '<a class="command secondary">OPEN IN FULL SCREEN</a>')
    inner.append('<div class="prompt" style="margin-top:1.4rem">FL:&gt; LIVE MONITOR // '
                 'MISSION %02d / %s // TICK %s</div>'
                 % (mid, escape(str(snap.get("mission_name", ""))), tick))
    inner.append(console_pair(snap, "CORRIDOR-REF.ASM"))
    inner.append(vm_fields_html(snap))
    inner.append(telemetry_fields_html(snap))
    inner.append(statusbar(snap, permalink))
    inner.append('<div class="cols"><div class="col"><h4>WHAT THE PORTS MEAN</h4>%s</div>'
                 '<div class="col"><h4>RUN STATS</h4>%s</div></div>'
                 % (ports_meaning_html(), stats_html(snap)))
    inner.append('<div class="prompt" style="margin-top:1.6rem">FL:&gt; SELECT A CALL</div>')
    inner.append(choose_calls_html())
    inner.append('<div class="cta"><p>FL:&gt; RELEASE STATUS</p>'
                 '<strong>OPCODE FIRELINE 0.1 // ANDROID PLAY + BROWSER CONSOLE</strong>'
                 '<p>FREE. YOUR CODE ON THE NOZZLE. PLAY HERE, INSTALL IF YOU WANT IT IN YOUR POCKET.</p></div>')
    inner.append('<div class="status4">'
                 '<div><b>ENGINE</b>SAME :rf8-core + :fireline-engine</div>'
                 '<div><b>DETERMINISM</b>STATE FINGERPRINT PER RUN</div>'
                 '<div><b>STORAGE</b>YOUR BROWSER ONLY</div>'
                 '<div><b>NETWORK</b>ONE PAGE LOAD, THEN LOCAL</div></div>')
    inner.append('<div class="privacy"><div><h2>PRIVATE BY DESIGN, ALSO IN THE BROWSER</h2>'
                 '<p class="small">THE PAGE AND THE ENGINE BINARY LOAD ONCE OVER HTTPS. AFTER THAT '
                 'EVERY RUN, EVERY FIRMWARE AND EVERY RESULT STAYS IN THIS TAB\'S LOCAL STORAGE. '
                 'NO ACCOUNT, NO ADS, NO ANALYTICS, NO THIRD-PARTY SCRIPTS, NO SERVER-SIDE '
                 'EXECUTION OF YOUR CODE.</p>'
                 '<a class="command secondary">READ WEB PRIVACY STATUS</a></div>'
                 '<div><ul><li>FIRMWARE AND RESULTS LIVE IN BROWSER LOCAL STORAGE ONLY.</li>'
                 '<li>SHARE LINKS CARRY YOUR CODE IN THE URL FRAGMENT, NOT TO OUR SERVER.</li>'
                 '<li>EXPORT ANY FIRMWARE AS PLAIN UTF-8 .ASM AND OPEN IT IN THE ANDROID APP.</li>'
                 '<li>CLEARING SITE DATA OR THE IN-PAGE DELETE REMOVES EVERYTHING.</li></ul></div></div>')
    inner.append(chips_html(SNAPS_USED[:4]))
    return ('<div class="page"><div class="topline">OPCODE FIRELINE RESPONSE SYSTEM'
            ' <span class="right">RF-8 / LIVE IN BROWSER</span></div><hr class="rule>%s%s</div>'
            % (nav("PLAY"), "".join(inner)))


def choose_calls_html():
    cards = []
    for mid, name, brief, seed, wind, water, limit in MISSIONS:
        cards.append('<article class="mcard"><h3>MISSION %02d / %s</h3><p>%s</p>'
                     '<div class="meta2">seed %d&nbsp;&nbsp;%s&nbsp;&nbsp;%s&nbsp;&nbsp;limit %d ticks</div>'
                     '<a class="mini">OPEN IN CONSOLE</a></article>'
                     % (mid, escape(name), escape(brief), seed, wind, water, limit))
    return '<div class="missions">' + "".join(cards) + "</div>"


def m2_console(snap):
    permalink = ("https://opcode-fireline.appsystems.at/play/#run="
                 "FLW1-02-2002-m4VxQ9pK2sT7nYb0cR6d")
    return mission_console(snap, "CORRIDOR-REF.ASM", permalink) + (
        '<div class="panel"><h4>CONSOLE CONTRACT</h4><p class="small">RUN, STEP AND RESET call the '
        'same authoritative world path as the Android app: STEP advances exactly one world tick, '
        'RESET rebuilds the world from mission tuple plus firmware, editing firmware never mutates '
        'a run in progress. No tap, key or drag can move, turn, spray or shield the rover.</p></div>')


def m3_result(snap):
    mid = snap.get("mission_id", 1)
    tick = snap.get("tick", 0)
    limit = snap.get("tick_limit", snap.get("timeLimit", "-"))
    rover = snap.get("rover") or {}
    vm = snap.get("vm") or {}
    fire_cells = sum(row.count(c) for row in (snap.get("fire") or []) for c in "1234")
    inner = """<div class="banner fail">FAILURE // TIME_EXPIRED
<div class="small">MISSION %02d / %s ended at tick %s of %s. Objectives are mandatory:
EXTINGUISH_ALL was not met.</div></div>
<div class="cols" style="margin-top:0">
 <div class="col"><h4>RESULT FIELDS</h4>%s</div>
 <div class="col"><h4>WHAT THE ROVER DID</h4>%s%s</div>
</div>
<div class="panel"><h4>DEPLOYED FIRMWARE // NEVER-SHOOTS.ASM</h4>%s</div>
<div class="statusbar"><div class="finger"><b>STATE</b> tick %s&nbsp;&nbsp;fingerprint $%s</div>
<div class="controls"><button class="btn run">RETRY</button><button class="btn">EDIT FIRMWARE</button>
<button class="btn">MISSION LIST</button></div>
<div class="link"><b>VERIFY</b> <a class="mini">COPY RUN LINK</a><a class="mini">DOWNLOAD .ASM FOR ANDROID</a></div></div>
<div class="panel"><h4>WHY THIS RUN ENDED</h4><p class="small">THE FIRMWARE NEVER READ A SENSOR AND
NEVER FIRED THE NOZZLE, SO WATER AND HEAT NEVER MOVED. THE FIRE STAYED AT ITS AUTHORED INTENSITY AND
THE TIME LIMIT CLOSED THE CALL. READ FIRE_DIST AND FIRE_LEVEL, AIM WITH FIRE_ANGLE, THEN SPEND WATER
ON PURPOSE: EACH SHOOT COSTS 10 WATER AND 20 HEAT.</p></div>""" % (
        mid, escape(snap.get("mission_name", "")), tick, limit,
        kv_rows([
            ("STATUS", "FAILURE"),
            ("FAILURE REASON", str(snap.get("failure_reason", snap.get("failureReason", "TIME_EXPIRED"))),),
            ("TICKS", "%s / %s" % (tick, limit)),
            ("CIVILIANS RESCUED / LOST", "0 / 0"),
            ("REMAINING FIRE CELLS", "%d" % fire_cells),
            ("TOTAL FIRE INTENSITY", str(snap.get("fire_intensity_total", "-"))),
            ("ROVER HP / HEAT", "%s / %s" % (rover.get("hp", "-"), rover.get("heat", "-"))),
            ("WATER USED / REMAINING", "0 / %s" % rover.get("water", "-")),
            ("FIRMWARE", "%d source lines, %s bytes" % (
                len((vm.get("source") or NEVER_SHOOTS_SOURCE).split("\n")),
                snap.get("firmware_bytes", "-"))),
        ]),
        kv_rows([
            ("SHOOTS FIRED", snap.get("stats", {}).get("shots", 0) if snap.get("stats") else 0),
            ("MOVES", 0),
            ("TURNS", 0),
            ("SHIELD ACTIVATIONS", 0),
            ("LAST EXECUTED INSTRUCTION", str(vm.get("instruction", "WAIT"))),
            ("VM HALT", str(vm.get("halt_reason", snap.get("halt_reason", "-")))),
        ]),
        events_html(snap),
        source_block(NEVER_SHOOTS_SOURCE),
        tick, str(snap.get("fingerprint", "-")).lstrip("$"))
    return shell(inner, "RESULT")


def m4_mobile(snap):
    permalink = "https://opcode-fireline.appsystems.at/play/#run=FLW1-01-1001-..."
    mid = snap.get("mission_id", 1)
    tick = str(snap.get("tick", 0))
    inner = []
    inner.append('<div class="console"><div class="titlebar"><span>MISSION %02d</span>'
                 '<span>T+0000%s</span></div><div class="consolebody">' % (mid, tick.zfill(4)))
    inner.append('<div class="mhead"><div><span class="mid">NOZZLE CHECK</span></div>'
                 '<div class="mparams">seed 1001&nbsp;&nbsp;WIND W 0&nbsp;&nbsp;tick %s/60</div></div>' % tick)
    inner.append('<div class="objline"><span class="pend">EXTINGUISH_ALL pending</span></div>')
    inner.append('<div class="wind">WIND W&nbsp;&nbsp;FORCE 0</div>')
    inner.append(grid_html(snap))
    inner.append(LEGEND)
    inner.append('<div class="cols"><div class="col"><h4>RF-8 STATE</h4>%s</div>'
                 '<div class="col"><h4>TELEMETRY</h4>%s</div></div>'
                 % (kv_rows([("A", (snap.get("vm") or {}).get("registers", {}).get("a", "-")),
                             ("X", (snap.get("vm") or {}).get("registers", {}).get("x", "-")),
                             ("Y", (snap.get("vm") or {}).get("registers", {}).get("y", "-")),
                             ("SP", (snap.get("vm") or {}).get("registers", {}).get("sp", "-")),
                             ("PC", "$%s" % (snap.get("vm") or {}).get("pc", "-")),
                             ("OP", (snap.get("vm") or {}).get("instruction", "-"))]),
                    telemetry_html(snap)))
    inner.append('<div class="col" style="margin-top:.8rem"><h4>EVENTS</h4>%s</div>' % events_html(snap))
    inner.append("</div></div>")
    inner.append('<div class="statusbar"><div class="controls">'
                 '<button class="btn run">RUN</button><button class="btn">STEP</button>'
                 '<button class="btn">RESET</button></div>'
                 '<div class="speed"><b>SPEED</b> 1 / 5 / 20 ticks per second</div>'
                 '<div class="finger"><b>STATE</b> tick %s&nbsp;&nbsp;fingerprint $%s</div></div>'
                 % (tick, str(snap.get("fingerprint", "-")).lstrip("$")))
    inner.append('<div class="panel"><h4>FIRMWARE</h4><p class="small">tap FIRMWARE to open the editor '
                 'full width. the editor is where a phone is worse than a desktop, so the web console '
                 'keeps the run screen primary and the editor secondary on small screens.</p></div>')
    return shell("".join(inner), "RUN", narrow=True, right="RF-8 / LIVE")


# ------------------------------------------------------------------- main ---
HEAD = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>%s</title>
<style>%s</style>
</head>
<body>
<div class="meta"><b>%s</b> &nbsp; %s</div>
"""

SNAPS_USED = []


def write(name, title, note, inner):
    path = os.path.join(HERE, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write((HEAD % (title, CSS, name, note)) + inner + "\n</body>\n</html>\n")
    print(path)


def main():
    snaps, fixture_path = load_fixture()
    print("fixture:", fixture_path, "snapshots:", len(snaps))
    for s in snaps:
        SNAPS_USED.append("%s (tick %s, %s)" % (s.get("label"), s.get("tick"),
                                                str(s.get("fingerprint", "-"))))
    provenance = ("engine snapshots: " + ", ".join(SNAPS_USED[:5]) +
                  (" (+%d more)" % (len(SNAPS_USED) - 5) if len(SNAPS_USED) > 5 else "")
                  ) if SNAPS_USED else "no engine snapshot fixture attached"

    m1_snaps = snaps
    write("fl-web-1-play-panel.html",
          "FIRELINE WEB mockup 1 - landing page #play panel",
          "landing page tokens (live site) + direction F world tokens; " + provenance,
          m1_play_panel(m1_snaps))

    m2 = (by_label(snaps, "corridor-ref-t46", 2) or by_label(snaps, "corridor", 2)
          or by_label(snaps, "ref-t46", 2))
    if m2:
        write("fl-web-2-console-midrun.html",
              "FIRELINE WEB mockup 2 - web console mid-run (mission 02 / CORRIDOR, tick 46)",
              "console shell, engine snapshot: " + provenance,
              m2_console(m2))
    else:
        print("skipped fl-web-2 (no engine snapshot for a mid-run mission)")

    m3 = by_label(snaps, "never-t30", 1) or by_label(snaps, "never", 1)
    if m3:
        write("fl-web-3-result-failure.html",
              "FIRELINE WEB mockup 3 - RESULT panel, TIME_EXPIRED",
              "engine snapshot of the documented failing firmware: " + provenance,
              m3_result(m3))
    else:
        print("skipped fl-web-3 (no failing-firmware engine snapshot)")

    m4 = (by_label(snaps, "ref-t12", 1) or by_label(snaps, "ref-t15", 1)
          or by_label(snaps, "ref-t0", 1) or by_label(snaps, "nozzle", 1))
    if m4:
        write("fl-web-4-mobile.html",
              "FIRELINE WEB mockup 4 - console at phone width",
              "engine snapshot: " + provenance,
              m4_mobile(m4))
    else:
        print("skipped fl-web-4 (no engine snapshot)")

    m5 = (by_label(snaps, "corridor-ref-t46", 2) or by_label(snaps, "corridor", 2)
          or by_label(snaps, "ref-t46", 2))
    if m5:
        write("fl-web-5-playable-landing.html",
              "FIRELINE WEB mockup 5 - playable landing page (web-1 + web-2 combined)",
              "landing page framing + paired run surface; " + provenance,
              m5_combined(m5))
    else:
        print("skipped fl-web-5 (no engine snapshot)")


if __name__ == "__main__":
    main()
