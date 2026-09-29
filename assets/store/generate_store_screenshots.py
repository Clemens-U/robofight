"""Generate deterministic Google Play phone screenshots for Opcode Arena."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "assets" / "store"
FONT_PATH = ROOT / "app" / "src" / "main" / "assets" / "fonts" / "PressStart2P-Regular.ttf"

W, H = 1080, 1920
BG = "#010703"
BLACK = "#000301"
PANEL = "#020d06"
DARK = "#021208"
GREEN_DARK = "#145c2a"
BORDER = "#24843d"
DIM = "#308243"
GREEN = "#34ec5b"
BRIGHT = "#b4ffbe"
YELLOW = "#f8b800"
CYAN = "#00e8d8"
RED = "#fc5838"


def font(size):
    return ImageFont.truetype(str(FONT_PATH), size)


def text(draw, xy, value, size=24, fill=GREEN, anchor=None, spacing=10):
    draw.multiline_text(xy, value, font=font(size), fill=fill, anchor=anchor, spacing=spacing)


def box(draw, bounds, fill=BLACK, outline=BORDER, width=3):
    draw.rectangle(bounds, fill=fill, outline=outline, width=width)


def button(draw, bounds, label, active=False, size=21):
    fill = GREEN if active else BLACK
    color = BLACK if active else BRIGHT
    box(draw, bounds, fill=fill, outline=GREEN if active else GREEN_DARK, width=3)
    x1, y1, x2, y2 = bounds
    text(draw, ((x1 + x2) // 2, (y1 + y2) // 2 + 2), label, size=size, fill=color, anchor="mm")


def frame():
    image = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(image)
    text(draw, (48, 58), "OPCODE ARENA", 34, BRIGHT)
    text(draw, (1032, 63), "RF-8 ONLINE", 20, DIM, anchor="ra")
    draw.rectangle((48, 116, 1032, 121), fill=GREEN)
    button(draw, (48, 146, 524, 222), "SHELL", active=False, size=23)
    button(draw, (556, 146, 1032, 222), "RUN", active=False, size=23)
    return image, draw


def scanlines(draw):
    for y in range(0, H, 8):
        draw.line((0, y, W, y), fill="#000000", width=1)


def shell_screenshot():
    image, draw = frame()
    button(draw, (48, 146, 524, 222), "SHELL", active=True, size=23)
    text(draw, (48, 270), "WORKSPACE / MYBOT", 20, DIM)
    box(draw, (48, 312, 1032, 1496), PANEL, BORDER, 4)

    lines = [
        ("OPCODE ARENA OS 0.4", BRIGHT),
        ("RF-8 COMBAT SYSTEM", DIM),
        ("MEM 64K  GRID 20x20  LINK READY", GREEN),
        ("TYPE HELP FOR AVAILABLE COMMANDS", GREEN),
        ("", GREEN),
        ("RF:\\> DIR", BRIGHT),
        ("  NAME             SIZE", DIM),
        ("  > MYBOT            286", BRIGHT),
        ("  > HUNTER.asm       214", GREEN),
        ("  > TURTLE.asm       248", GREEN),
        ("  > SNAKE.asm        231", GREEN),
        ("  > CHAOS.asm        196", GREEN),
        ("  > WALKER.asm       174", GREEN),
        ("", GREEN),
        ("  // TAP A FILE TO EDIT", DIM),
        ("", GREEN),
        ("RF:\\> EDIT MYBOT", BRIGHT),
        ("OPENING MYBOT ...", GREEN),
    ]
    y = 358
    for line, color in lines:
        text(draw, (82, y), line, 22, color)
        y += 58

    button(draw, (48, 1530, 280, 1620), "DIR")
    button(draw, (298, 1530, 530, 1620), "HELP")
    button(draw, (548, 1530, 780, 1620), "EDIT")
    button(draw, (798, 1530, 1032, 1620), "RUN", active=True)
    text(draw, (48, 1670), "COMMAND", 18, DIM)
    box(draw, (48, 1705, 1032, 1792), BLACK, GREEN_DARK, 3)
    text(draw, (78, 1734), "RF:\\> _", 24, BRIGHT)
    text(draw, (48, 1842), "CODE. SAVE. FIGHT.", 20, GREEN)
    scanlines(draw)
    image.save(OUT / "opcode-arena-phone-01-shell.png", optimize=True)


def arena_grid(draw, bounds):
    x1, y1, x2, y2 = bounds
    box(draw, bounds, "#000502", GREEN, 4)
    cell = (x2 - x1) / 20
    for i in range(1, 20):
        x = round(x1 + i * cell)
        y = round(y1 + i * cell)
        draw.line((x, y1, x, y2), fill="#0b2b14", width=2)
        draw.line((x1, y, x2, y), fill="#0b2b14", width=2)
    dot_font = font(12)
    for gy in range(20):
        for gx in range(20):
            cx = x1 + (gx + 0.5) * cell
            cy = y1 + (gy + 0.55) * cell
            draw.text((cx, cy), "·", font=dot_font, fill="#174525", anchor="mm")
    actors = [
        (3, 5, "M>", YELLOW),
        (15, 12, "H<", GREEN),
        (8, 5, "*", BRIGHT),
        (11, 10, "*", BRIGHT),
        (13, 12, "*", BRIGHT),
    ]
    for gx, gy, glyph, color in actors:
        cx = x1 + (gx + 0.5) * cell
        cy = y1 + (gy + 0.55) * cell
        draw.text((cx, cy), glyph, font=font(18 if len(glyph) > 1 else 20), fill=color, anchor="mm")
    # A small impact burst communicates action without obscuring the UI.
    bx = x1 + 14.5 * cell
    by = y1 + 12.5 * cell
    for dx, dy in [(-22, 0), (22, 0), (0, -22), (0, 22), (-16, -16), (16, 16)]:
        draw.rectangle((bx + dx - 4, by + dy - 4, bx + dx + 4, by + dy + 4), fill=RED)


def run_screenshot():
    image, draw = frame()
    button(draw, (556, 146, 1032, 222), "RUN", active=True, size=23)
    text(draw, (48, 270), "ARENA / LIVE EXECUTION", 20, DIM)
    arena_grid(draw, (72, 320, 1008, 1256))
    button(draw, (48, 1298, 524, 1386), "A: MYBOT", active=True, size=20)
    button(draw, (556, 1298, 1032, 1386), "B: HUNTER", size=20)
    box(draw, (48, 1412, 524, 1512), DARK, GREEN_DARK, 3)
    box(draw, (556, 1412, 1032, 1512), DARK, GREEN_DARK, 3)
    text(draw, (286, 1446), "MYBOT  HP 80\nSH 0   HEAT 2", 18, YELLOW, anchor="ma", spacing=14)
    text(draw, (794, 1446), "HUNTER HP 60\nSH 0   HEAT 4", 18, GREEN, anchor="ma", spacing=14)
    button(draw, (48, 1542, 280, 1632), "RUN", active=True)
    button(draw, (298, 1542, 530, 1632), "STEP")
    button(draw, (548, 1542, 780, 1632), "RESET")
    button(draw, (798, 1542, 1032, 1632), "SND ON")
    box(draw, (48, 1670, 1032, 1780), PANEL, BORDER, 3)
    text(draw, (78, 1705), "T:48  FIGHT RUNNING", 22, BRIGHT)
    text(draw, (78, 1745), "ONE INSTRUCTION PER TICK", 17, DIM)
    text(draw, (48, 1842), "YOUR CODE IS THE WEAPON.", 20, GREEN)
    scanlines(draw)
    image.save(OUT / "opcode-arena-phone-02-arena.png", optimize=True)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    shell_screenshot()
    run_screenshot()
    for path in sorted(OUT.glob("opcode-arena-phone-*.png")):
        with Image.open(path) as image:
            print(f"{path.name}: {image.size[0]}x{image.size[1]}, {path.stat().st_size} bytes")
