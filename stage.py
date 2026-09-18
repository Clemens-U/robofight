"""Inject classes.dex + assets/MYBOT.asm into the aapt2 base.apk.

aapt2 link produces a valid base APK (AndroidManifest.xml + resources.arsc).
We then append the dex and the editable firmware asset. Run from the repo root.
"""
import shutil
import zipfile

BASE = "build/base.apk"
STAGED = "build/staged.apk"
DEX = "build/classes.dex"
ASSET_SRC = "app/src/main/assets/MYBOT.asm"
ASSET_DST = "assets/MYBOT.asm"

shutil.copyfile(BASE, STAGED)
with zipfile.ZipFile(STAGED, "a") as z:
    z.write(DEX, "classes.dex")
    z.write(ASSET_SRC, ASSET_DST)

names = zipfile.ZipFile(STAGED).namelist()
print("staged.apk entries:", names)
assert "classes.dex" in names and ASSET_DST in names, "missing required entries"
