#!/usr/bin/env python3
"""tools/emu/layout/screens.py - the pictures of docs/screens/ from a run of tools/emu/layout/walk.sh.

    python3 tools/emu/layout/screens.py SHOTS-DIR OUT-DIR

SHOTS-DIR is walk.sh's picture directory (default build/emu-layout/SERIAL/), holding app-en-XA/,
app-ar-XB/ and states/. OUT-DIR (docs/screens/) gets one picture per screen in en-XA, named after its
route (Welcome.png, Locked.png), in OUT-DIR/en-XA/, and the screens whose layout differs in ar-XB (the
mirrored ones with rows, fields and the keypad) in OUT-DIR/ar-XB/. Each is scaled to a third of its
width and reduced to an adaptive palette, which keeps the text sharp and the files small. Needs Pillow
(installed on this machine for python3). Prints what it wrote; exit 1 when a picture is missing.
"""
import os
import sys

from PIL import Image

# Route key (nav/Route.kt) -> the walk's picture of it: app-<locale>/<name> or states/<name>.
EN_XA = {
    "Welcome": "app-en-XA/Welcome",
    "Language": "app-en-XA/Language",
    "Restore": "app-en-XA/Restore",
    "CreateMasterPassword": "app-en-XA/CreateMasterPassword",
    "WriteDown": "app-en-XA/WriteDown",
    "TypeAgain": "app-en-XA/TypeAgain",
    "CreatePin": "app-en-XA/CreatePin",
    "ConfirmPin": "app-en-XA/ConfirmPin",
    "FingerprintOffer": "app-en-XA/FingerprintOffer",
    "BackupsExplained": "app-en-XA/BackupsExplained",
    "Locked": "app-en-XA/Locked-pad",
    "Passwords": "app-en-XA/Passwords",
    "RecentlyDeleted": "app-en-XA/RecentlyDeleted",
    "Entry": "app-en-XA/Entry",
    "BigLetters": "app-en-XA/BigLetters",
    "Edit": "app-en-XA/Edit-new",
    "Settings": "app-en-XA/Settings",
    "ChangePin": "app-en-XA/ChangePin",
    "Theme": "app-en-XA/Theme",
    "ChangeMasterPassword": "app-en-XA/ChangeMasterPassword",
    "NewMasterPassword": "app-en-XA/NewMasterPassword",
    "Help": "app-en-XA/Help-PROTECTION",
    "Licences": "app-en-XA/Licences",
    "Backup": "app-en-XA/Backup",
    "OldPasswords": "states/backup-old-list-en-XA",
    "Export": "app-en-XA/Export",
    "Import": "app-en-XA/Import",
    "ColumnMapping": "states/import-mapping-en-XA",
    "Security": "app-en-XA/Security",
    "Group": "app-en-XA/Group",
    "GuidedChange": "app-en-XA/GuidedChange",
    "FirstCopyTip": "app-en-XA/FirstCopyTip",
    # A few states of a screen that look unlike its first one.
    "Locked-master-password": "app-en-XA/Locked-masterPassword",
    "Locked-fingerprint-only": "states/lock-FingerprintOnly-2-en-XA",
    "Locked-older-password": "states/lock-OlderPassword-3-en-XA",
    "Locked-older-set": "states/lock-OpenOlderSet-5-en-XA",
    "Locked-cannot-open": "states/lock-CannotOpen-7-en-XA",
    "Locked-unreadable": "states/lock-Unreadable-9-en-XA",
    "Edit-generator": "app-en-XA/Generator",
    "Passwords-empty": "app-en-XA/Passwords-empty",
}

# The screens whose layout differs in ar-XB: mirrored rows, fields, the bars and the keypad's page.
AR_XB = {
    "Welcome": "app-ar-XB/Welcome",
    "CreatePin": "app-ar-XB/CreatePin",
    "Locked": "app-ar-XB/Locked-pad",
    "Passwords": "app-ar-XB/Passwords",
    "Entry": "app-ar-XB/Entry",
    "Edit": "app-ar-XB/Edit-new",
    "Settings": "app-ar-XB/Settings",
    "Security": "app-ar-XB/Security",
    "Group": "app-ar-XB/Group",
    "Locked-cannot-open": "states/lock-CannotOpen-7-ar-XB",
}

SCALE = 3
COLOURS = 128


def write(src, dst):
    with Image.open(src) as image:
        image = image.convert("RGB")
        small = image.resize((image.width // SCALE, image.height // SCALE), Image.LANCZOS)
        small.quantize(colors=COLOURS, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(dst, optimize=True)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    shots, out = sys.argv[1], sys.argv[2]
    missing = 0
    for locale, table in (("en-XA", EN_XA), ("ar-XB", AR_XB)):
        os.makedirs(os.path.join(out, locale), exist_ok=True)
        for route, name in table.items():
            src = os.path.join(shots, name + ".png")
            dst = os.path.join(out, locale, route + ".png")
            if not os.path.exists(src):
                print(f"MISSING {locale} {route}: {src}")
                missing += 1
                continue
            write(src, dst)
            print(f"{dst} ({os.path.getsize(dst) // 1024} KB)")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
