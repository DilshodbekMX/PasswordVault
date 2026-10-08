#!/usr/bin/env python3
"""Makes every resource of the launcher icon and of the app's logo from one drawing, icon.svg.

    python3 docs/design/looks/icon/make-icon.py           write the resources below
    python3 docs/design/looks/icon/make-icon.py --check   only check that they are what was written last

Replacing the icon is one step: put the new drawing in icon.svg and run this script. Nothing else
names the icon's shape or colour. (The app's name is the one resource `app_name`; the application ID
never changes. See docs/design/looks/README.md, "The icon and the name".)

What it writes, under app/src/main/res/:

    drawable/ic_launcher_background.xml        the adaptive icon's background, one colour
    drawable/ic_launcher_foreground.xml        the drawing, as a vector
    drawable/ic_launcher_monochrome.xml        the drawing in one colour, which Android 13 tints
    drawable/ic_app_logo.xml                   the drawing alone in a tight square, for the Welcome
                                               and lock screens (white; a screen tints it)
    mipmap-anydpi-v26/ic_launcher.xml          the adaptive icon (Android 8 and later)
    mipmap-anydpi-v26/ic_launcher_round.xml    the same
    mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png         bitmaps for Android 7: a rounded square of
                                                        48, 72, 96, 144 and 192 pixels
    mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png   the same in a circle

and next to this script generated.sha256, the SHA-256 of icon.svg and of each file written, which
`--check`, `sha256sum -c docs/design/looks/icon/generated.sha256` (from the project root) and the
app's IconResourcesTest read: a resource changed by hand, or a drawing changed without running this
script, fails them. Bitmaps of the same names in another format (the template's .webp) are removed.

What icon.svg may hold. The vectors are written from the drawing's own numbers, so the drawing is
kept simple; anything else is refused with a message that says what.

    <svg viewBox="0 0 108 108">          an adaptive icon's canvas: a launcher shows the middle
                                         72 x 72 under its own mask, so what must be seen lies
                                         within 33 units of the middle
      <rect id="background" fill="#RRGGBB" .../>    one flat colour over the whole canvas
      <path id="foreground" fill="#RRGGBB" d="..."/>
                                         or <g id="foreground"> holding such paths. Only <path>, with
                                         `d`, `fill` and, if wanted, fill-rule="evenodd"; no stroke,
                                         no transform, no gradient. A hole is a real hole in the path
                                         (the one-colour layer has no second colour to draw it with).

What it needs: Python 3 with PyGObject and librsvg's typelib (Ubuntu: python3-gi, gir1.2-rsvg-2.0),
which draw the bitmaps from the same file. Checked on this project's machine with librsvg 2.58.
"""

import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SOURCE = os.path.join(HERE, "icon.svg")
MANIFEST = os.path.join(HERE, "generated.sha256")

SVG = "http://www.w3.org/2000/svg"
CANVAS = 108.0  # an adaptive icon's canvas, in dp
SHOWN = 72.0  # the part of it a launcher shows
SAFE = 33.0  # how far from the middle a launcher's mask may cut
BITMAP = 48  # a launcher icon before Android 8, in dp
BODY = 44.0  # the icon's own shape in that bitmap: 2 dp of air around it
CORNER = 10.0  # the rounded square's corner
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ONE_COLOUR = "#FF000000"  # the one-colour layer: only its shape is used
LOGO_COLOUR = "#FFFFFFFF"  # the logo: a screen tints it


def fail(message):
    sys.exit("make-icon.py: " + message)


def colour(text, where):
    """'#RRGGBB' (or '#RGB') as '#RRGGBB' in capitals."""
    text = (text or "").strip()
    if re.fullmatch(r"#[0-9a-fA-F]{3}", text):
        text = "#" + "".join(ch * 2 for ch in text[1:])
    if not re.fullmatch(r"#[0-9a-fA-F]{6}", text):
        fail("%s needs a fill of the form #RRGGBB, not %r" % (where, text))
    return text.upper()


def read_drawing():
    """The background colour and the foreground's paths, as (fill, d, evenodd) each."""
    try:
        root = ET.parse(SOURCE).getroot()
    except (OSError, ET.ParseError) as e:
        fail("cannot read %s: %s" % (SOURCE, e))
    if root.tag != "{%s}svg" % SVG:
        fail("icon.svg is not an SVG file")
    box = (root.get("viewBox") or "").replace(",", " ").split()
    if [float(v) for v in box] != [0.0, 0.0, CANVAS, CANVAS]:
        fail('the <svg> needs viewBox="0 0 108 108", an adaptive icon\'s canvas; it has %r' % root.get("viewBox"))
    by_id = {e.get("id"): e for e in root.iter() if e.get("id")}

    background = by_id.get("background")
    if background is None or background.tag != "{%s}rect" % SVG:
        fail('no <rect id="background">')
    size = [float(background.get(k, "0")) for k in ("x", "y", "width", "height")]
    if size != [0.0, 0.0, CANVAS, CANVAS]:
        fail("the background must cover the whole canvas (width and height 108)")
    background_colour = colour(background.get("fill"), "the background")

    foreground = by_id.get("foreground")
    if foreground is None:
        fail('no element with id="foreground"')
    if foreground.tag == "{%s}path" % SVG:
        elements = [foreground]
    elif foreground.tag == "{%s}g" % SVG:
        elements = list(foreground)
        if foreground.get("transform") or foreground.get("stroke"):
            fail("the foreground group may have no transform and no stroke")
    else:
        fail("the foreground must be a <path> or a <g> of paths")
    if not elements:
        fail("the foreground is empty")
    paths = []
    for e in elements:
        if e.tag != "{%s}path" % SVG:
            fail("the foreground may hold only <path> elements, not <%s>" % e.tag.split("}")[-1])
        for unwanted in ("transform", "stroke", "style", "opacity", "fill-opacity", "clip-path", "mask", "filter"):
            if e.get(unwanted) and not (unwanted == "stroke" and e.get(unwanted) == "none"):
                fail('a foreground path has %s="%s"; only d, fill and fill-rule are understood' % (unwanted, e.get(unwanted)))
        d = " ".join((e.get("d") or "").split())
        if not d:
            fail("a foreground path has no d")
        rule = e.get("fill-rule", "nonzero")
        if rule not in ("nonzero", "evenodd"):
            fail("fill-rule %r is not known" % rule)
        paths.append((colour(e.get("fill") or foreground.get("fill"), "a foreground path"), d, rule == "evenodd"))
    return background_colour, paths


def load_rsvg():
    try:
        import gi

        gi.require_version("Rsvg", "2.0")
        from gi.repository import Rsvg
    except (ImportError, ValueError) as e:
        fail("the bitmaps need PyGObject and librsvg's typelib (Ubuntu: python3-gi, gir1.2-rsvg-2.0): %s" % e)
    return Rsvg


def foreground_box(Rsvg):
    """The foreground's bounding box in canvas units: (left, top, width, height)."""
    handle = Rsvg.Handle.new_from_file(SOURCE)
    found, ink, _logical = handle.get_geometry_for_layer("#foreground", Rsvg.Rectangle())
    if not found:
        fail("librsvg does not find the foreground")
    # librsvg answers in the pixels of the file's own size (its width and height, or its viewBox).
    has_size, width, _height = handle.get_intrinsic_size_in_pixels()
    scale = width / CANVAS if has_size and width > 0 else 1.0
    return ink.x / scale, ink.y / scale, ink.width / scale, ink.height / scale


def foreground_reach(Rsvg):
    """How far from the canvas's middle the foreground is drawn, in canvas units (to a quarter of one)."""
    source = open(SOURCE, encoding="utf-8").read()
    start = source.index("<svg")
    drawing = source[source.index(">", start) + 1 : source.rindex("</svg>")]
    per_unit = 4
    pixels = int(CANVAS * per_unit)
    page = '<svg xmlns="%s" width="%d" height="%d" viewBox="0 0 108 108"><style>#background{display:none}</style>%s</svg>' % (
        SVG, pixels, pixels, drawing)
    picture = Rsvg.Handle.new_from_data(page.encode("utf-8")).get_pixbuf()
    data, stride, channels = picture.get_pixels(), picture.get_rowstride(), picture.get_n_channels()
    if channels != 4:
        fail("librsvg drew the foreground without transparency")
    middle = pixels / 2
    farthest = 0.0
    for y in range(pixels):
        row = y * stride
        for x in range(pixels):
            if data[row + x * 4 + 3]:
                farthest = max(farthest, (x + 0.5 - middle) ** 2 + (y + 0.5 - middle) ** 2)
    return farthest ** 0.5 / per_unit


def number(value):
    """A number for a vector file: at most three decimals, no trailing zeros."""
    text = ("%.3f" % value).rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


GENERATED = "<!-- Made by docs/design/looks/icon/make-icon.py from icon.svg. Do not edit: change the drawing and run the script. -->\n"


def vector(size_dp, viewport, paths, fill=None, origin=(0.0, 0.0), what=""):
    """A vector drawable of the foreground's paths, all in `fill` if given, seen from `origin`."""
    lines = ['<?xml version="1.0" encoding="utf-8"?>\n', GENERATED]
    if what:
        lines.append("<!-- %s -->\n" % what)
    lines.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n')
    lines.append('    android:width="%sdp"\n    android:height="%sdp"\n' % (number(size_dp), number(size_dp)))
    lines.append('    android:viewportWidth="%s"\n    android:viewportHeight="%s">\n' % (number(viewport), number(viewport)))
    moved = origin != (0.0, 0.0)
    indent = "    "
    if moved:
        lines.append('    <group\n        android:translateX="%s"\n        android:translateY="%s">\n' % (number(-origin[0]), number(-origin[1])))
        indent = "        "
    for own_fill, d, evenodd in paths:
        argb = fill or "#FF" + own_fill[1:]
        lines.append("%s<path\n%s    android:fillColor=\"%s\"\n" % (indent, indent, argb))
        if evenodd:
            lines.append('%s    android:fillType="evenOdd"\n' % indent)
        lines.append('%s    android:pathData="%s" />\n' % (indent, d))
    if moved:
        lines.append("    </group>\n")
    lines.append("</vector>\n")
    return "".join(lines)


def adaptive_icon():
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n' + GENERATED
        + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )


def background_shape(background_colour):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n' + GENERATED
        + '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:shape="rectangle">\n'
        '    <solid android:color="%s" />\n'
        "</shape>\n" % background_colour
    )


def bitmap(Rsvg, pixels, round_shape):
    """The icon as launchers before Android 8 show it: the middle of the canvas in a rounded square or a circle."""
    source = open(SOURCE, encoding="utf-8").read()
    start = source.index("<svg")
    drawing = source[source.index(">", start) + 1 : source.rindex("</svg>")]
    air = (BITMAP - BODY) / 2
    if round_shape:
        mask = '<circle cx="%s" cy="%s" r="%s"/>' % (BITMAP / 2, BITMAP / 2, BODY / 2)
    else:
        mask = '<rect x="%s" y="%s" width="%s" height="%s" rx="%s"/>' % (air, air, BODY, BODY, CORNER)
    shown_from = (CANVAS - SHOWN) / 2
    page = (
        '<svg xmlns="%s" width="%d" height="%d" viewBox="0 0 %d %d">'
        '<defs><clipPath id="make-icon-mask">%s</clipPath></defs>'
        '<g clip-path="url(#make-icon-mask)">'
        '<svg x="%s" y="%s" width="%s" height="%s" viewBox="%s %s %s %s">%s</svg>'
        "</g></svg>"
    ) % (SVG, pixels, pixels, BITMAP, BITMAP, mask, air, air, BODY, BODY, shown_from, shown_from, SHOWN, SHOWN, drawing)
    picture = Rsvg.Handle.new_from_data(page.encode("utf-8")).get_pixbuf()
    if picture is None or picture.get_width() != pixels or picture.get_height() != pixels:
        fail("librsvg did not draw the %d-pixel bitmap" % pixels)
    saved, data = picture.save_to_bufferv("png", ["compression"], ["9"])
    if not saved:
        fail("the %d-pixel bitmap could not be written as PNG" % pixels)
    return bytes(data)


def outputs():
    """Every file to write, as (path under the project root, bytes)."""
    background_colour, paths = read_drawing()
    Rsvg = load_rsvg()
    left, top, width, height = foreground_box(Rsvg)
    if left < 0 or top < 0 or left + width > CANVAS or top + height > CANVAS:
        fail("the foreground leaves the canvas")
    reach = foreground_reach(Rsvg)
    if reach > SAFE:
        print("make-icon.py: WARNING: the foreground is drawn up to %.1f units from the middle; a launcher's mask may cut what is beyond %.0f" % (reach, SAFE))
    else:
        print("the foreground is drawn up to %.1f units from the middle (a launcher's mask may cut beyond %.0f)" % (reach, SAFE))
    side = max(width, height)
    logo_origin = (left + width / 2 - side / 2, top + height / 2 - side / 2)

    res = os.path.relpath(RES, ROOT)
    files = [
        (os.path.join(res, "drawable", "ic_launcher_background.xml"), background_shape(background_colour).encode()),
        (os.path.join(res, "drawable", "ic_launcher_foreground.xml"), vector(CANVAS, CANVAS, paths).encode()),
        (
            os.path.join(res, "drawable", "ic_launcher_monochrome.xml"),
            vector(CANVAS, CANVAS, paths, fill=ONE_COLOUR, what="The launcher's one-colour layer: Android 13 and later tint its shape.").encode(),
        ),
        (
            os.path.join(res, "drawable", "ic_app_logo.xml"),
            vector(24, side, paths, fill=LOGO_COLOUR, origin=logo_origin, what="The app's logo: the icon's drawing alone. White; a screen tints it and gives it its size.").encode(),
        ),
        (os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml"), adaptive_icon().encode()),
        (os.path.join(res, "mipmap-anydpi-v26", "ic_launcher_round.xml"), adaptive_icon().encode()),
    ]
    for density, factor in DENSITIES.items():
        pixels = int(round(BITMAP * factor))
        files.append((os.path.join(res, "mipmap-" + density, "ic_launcher.png"), bitmap(Rsvg, pixels, round_shape=False)))
        files.append((os.path.join(res, "mipmap-" + density, "ic_launcher_round.png"), bitmap(Rsvg, pixels, round_shape=True)))
    return files


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    with open(os.path.join(ROOT, path), "rb") as f:
        return f.read()


def write_all():
    files = outputs()
    for path, data in files:
        full = os.path.join(ROOT, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as f:
            f.write(data)
        print("wrote %s (%d bytes)" % (path, len(data)))
    # A bitmap of the same name in another format would be a second resource of that name.
    for density in DENSITIES:
        folder = os.path.join(RES, "mipmap-" + density)
        for name in sorted(os.listdir(folder)):
            stem, ext = os.path.splitext(name)
            if stem in ("ic_launcher", "ic_launcher_round") and ext != ".png":
                os.remove(os.path.join(folder, name))
                print("removed %s" % os.path.relpath(os.path.join(folder, name), ROOT))
    source = os.path.relpath(SOURCE, ROOT)
    lines = ["%s  %s\n" % (sha256(read(source)), source)] + ["%s  %s\n" % (sha256(data), path) for path, data in files]
    with open(MANIFEST, "w", encoding="utf-8") as f:
        f.writelines(lines)
    print("wrote %s" % os.path.relpath(MANIFEST, ROOT))


def check():
    """Whether the drawing and every resource are those of the last run."""
    try:
        lines = open(MANIFEST, encoding="utf-8").read().splitlines()
    except OSError as e:
        fail("no %s: run the script without --check first (%s)" % (os.path.relpath(MANIFEST, ROOT), e))
    wrong = []
    for line in lines:
        digest, path = line.split("  ", 1)
        try:
            if sha256(read(path)) != digest:
                wrong.append(path + " changed")
        except OSError:
            wrong.append(path + " is missing")
    if wrong:
        fail("not what the script wrote last:\n  " + "\n  ".join(wrong) + "\nRun the script again (without --check).")
    print("%d files are those of the last run" % len(lines))


if __name__ == "__main__":
    arguments = sys.argv[1:]
    if arguments == ["--check"]:
        check()
    elif not arguments:
        write_all()
    else:
        sys.exit(__doc__)
