"""W3 record 19 visual probe: build a zoomed static fixture from the REAL shell CSS.

Takes `fireline-web/console.html`'s own <style> (the shipped rules, no copies) and lays out a grid
with a 3x-sized `--cell` so a screenshot can judge the two fixes at record 19:
the rover's facing caret and the thick wall block. Written to
docs/web/w3/probe19_fixture.html (scratch output, not a golden master).

Usage: python make_probe19_fixture.py [--cell 78]
"""
import argparse
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
SHELL = REPO / "fireline-web" / "console.html"
OUT = HERE / "probe19_fixture.html"

GRID = 20
ROWS = 21
TRACKS = 22
BLOCK_GLYPH = "\u2588"
CARETS = {"e": "\u25b8", "w": "\u25bf", "n": "\u25b4", "s": "\u25be"}
# Mission 02 / CORRIDOR wall runs (row-major, 0-based world coordinates), plus the rover cell.
WALLS = ([(x, 3) for x in list(range(0, 2)) + list(range(4, 20))]
         + [(x, 4) for x in range(4, 20)]
         + [(x, 2) for x in (3,)]
         + [(x, y) for x in range(10, 13) for y in range(9, 12)])
ROVER = (3, 5)


def style_block() -> str:
    text = SHELL.read_text(encoding="utf-8")
    start = text.index("<style>")
    end = text.index("</style>") + len("</style>")
    return text[start:end]


def grid_html(cell: int, dirs: list) -> str:
    cells = []
    for y in range(ROWS):
        for x in range(TRACKS):
            if x == 0 and y == 0:
                cells.append('<span class="corner"></span>')
                continue
            if x == 0:
                cells.append('<span class="ruler">%02d</span>' % (y - 1))
                continue
            if y == 0:
                cells.append('<span class="ruler">%02d</span>' % (x - 1))
                continue
            cx, cy = x - 1, y - 1
            if (cx, cy) == ROVER:
                d = dirs[0]
                cells.append('<span class="cell rover">R<i class="nose nose-%s">%s</i></span>' % (d, CARETS[d]))
                continue
            if (cx, cy) in WALLS:
                cells.append('<span class="cell wall"><span>%s</span></span>' % BLOCK_GLYPH)
                continue
            if (cx, cy) == (10, 10):
                cells.append('<span class="cell packet">W</span>')
                continue
            if (cx, cy) == (11, 10):
                cells.append('<span class="cell fire2">2</span>')
                continue
            if (cx, cy) == (12, 10):
                cells.append('<span class="cell packet">W</span>')
                continue
            if (cx, cy) == (13, 10):
                cells.append('<span class="cell exit">E</span>')
                continue
            cells.append('<span class="cell empty">\u00b7</span>')
    scale = round(cell / 26 * 4)
    return ('<div class="pair" style="--cell:%dpx;--grid-h:%dpx;--wall-stroke:%dpx;">'
            '<div class="pairworld"><div class="grid">%s</div></div></div>'
            % (cell, ROWS * cell + 2, scale, "\n".join(cells)))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cell", type=int, default=78, help="cell edge in px (26 is the desktop scale)")
    args = ap.parse_args()
    grids = "\n".join(grid_html(args.cell, [d]) for d in ("e", "w", "n", "s"))
    html = ("<!doctype html>\n<html><head><meta charset=\"utf-8\">"
            "<title>W3 record 19 probe fixture (real shell CSS, %dpx cells)</title>\n%s\n"
            "</head><body><div class=\"page\">\n%s\n</div></body></html>\n" % (args.cell, style_block(), grids))
    OUT.write_text(html, encoding="utf-8")
    print("wrote %s (%d chars, 4 grids at --cell %dpx)" % (OUT, len(html), args.cell))


if __name__ == "__main__":
    main()
