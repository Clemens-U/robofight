# Opcode Arena store artwork

## Selected base rendering

- `opcode-arena-icon-base-master.png` — selected master artwork (derived from v6).
- `opcode-arena-icon-base-512.png` — selected 512 × 512 Google Play base export.
- `opcode-arena-icon-circuit-master.png` — paid-tier master: amber robot in the
  unchanged green arena.
- `opcode-arena-icon-circuit-512.png` — 512 × 512 Google Play export for
  **Opcode Arena: The Circuit**.
- `opcode-arena-feature-graphic-1024x500.png` — Google Play feature graphic.
- `opcode-arena-circuit-feature-graphic-1024x500.png` — The Circuit feature
  graphic: amber hero robot in the shared green arena.
- `opcode-arena-phone-01-shell.png` — 1080 × 1920 SHELL-mode store screenshot.
- `opcode-arena-phone-02-arena.png` — 1080 × 1920 RUN-mode store screenshot.
- `generate_store_screenshots.py` — deterministic renderer for the phone screenshots.

The selected direction combines the terminal-faced robot and arena frame with a
simple robotic-vacuum-style moving base. Future icon refinements should start
from this rendering.

## Iterations

- `opcode-arena-icon-master.png` — generated master artwork.
- `opcode-arena-icon-512.png` — 512 × 512 Google Play export (32-bit PNG).
- `opcode-arena-icon-v2-master.png` — less-humanoid machine-module revision.
- `opcode-arena-icon-v2-512.png` — 512 × 512 Google Play export of v2.
- `opcode-arena-icon-v3-master.png` — mobile utility-robot revision.
- `opcode-arena-icon-v3-512.png` — 512 × 512 Google Play export of v3.
- `opcode-arena-icon-v4-master.png` — original three-wheel arena-rover revision.
- `opcode-arena-icon-v4-512.png` — 512 × 512 Google Play export of v4.
- `opcode-arena-icon-v5-master.png` — minimal one-wheel rover symbol.
- `opcode-arena-icon-v5-512.png` — 512 × 512 Google Play export of v5.
- `opcode-arena-icon-v6-master.png` — favorite terminal robot with a simple vacuum-style base.
- `opcode-arena-icon-v6-512.png` — 512 × 512 Google Play export of v6.

Concept: an 8-bit robot whose visor displays a command prompt, using the
game's phosphor-green terminal palette with amber status lights.

Tier rule: phosphor green identifies the shared system and the free app;
amber identifies paid-tier subjects and markers. The Circuit icon therefore
changes only the displayed robot to amber and keeps the arena, command prompt,
frame, grid, and background green.

## Release package

`release/free/` and `release/circuit/` are the upload-ready asset folders. Each
contains a 512×512 icon, a 1024×500 feature graphic, and five phone screenshots.
The Circuit screenshots were captured from the signed Pro flavor running in
the Android emulator at the Play-compliant 1080×2160 resolution; the free
screenshots are the current 1080×1920 device captures. Upload images are
24-bit RGB PNGs without alpha; store icons are 32-bit PNGs.
