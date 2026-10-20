"""Build the app's bundled fonts (app/src/main/res/font/*.ttf) from open-licensed Chinese fonts.

    python tools/build_phase_font.py <source.ttf> --family "Phase Sans SC"  --out app/src/main/res/font/phase_sans_sc.ttf    [--wght 300:700]
    python tools/build_phase_font.py <source.ttf> --family "Phase Serif SC" --out app/src/main/res/font/phase_serif_sc.ttf   [--wght 300:700]
    python tools/build_phase_font.py <source.ttf> --family "Phase Display SC" --out app/src/main/res/font/phase_display_sc.ttf

Sources used so far (all SIL OFL 1.1, see licenses/):
    Noto Sans SC  variable (--wght 300:700)  -> phase_sans_sc.ttf
    Noto Serif SC variable (--wght 300:700)  -> phase_serif_sc.ttf
    Smiley Sans Oblique v2.0.1 (static)      -> phase_display_sc.ttf

The output is a Modified Version under OFL: it keeps ASCII, Latin-1, punctuation, GB2312 level-1 hanzi and every
character found in the app's Kotlin / XML sources, and it is renamed (--family) so no Reserved Font Name is reused.
The middle dot (U+00B7) is narrowed: in Chinese typography it is a full-width glyph and leaves a wide hole in
labels such as "5 个周期 · 7 小时 55 分".
"""
import argparse
import pathlib
import tempfile

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

DOT_ADVANCE = 380  # units per em 1000
RESERVED_WORDS = ("Source", "Smiley", "得意黑")
KEEP_NAME_IDS = {0, 13, 14}  # copyright and licence notices stay verbatim


def source_characters(repo: pathlib.Path) -> set[str]:
    chars: set[str] = set()
    for pattern in ("app/src/main/**/*.kt", "core/src/main/**/*.kt", "app/src/main/res/**/*.xml"):
        for path in repo.glob(pattern):
            if "build" not in path.parts:
                chars |= set(path.read_text(encoding="utf-8", errors="ignore"))
    return {c for c in chars if ord(c) > 0x7F}


def gb2312_level1() -> set[str]:
    chars = set()
    for hi in range(0xB0, 0xD8):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode("gb2312"))
            except UnicodeDecodeError:
                pass
    return chars


def narrow_middle_dot(font: TTFont) -> None:
    glyph_name = font.getBestCmap().get(0xB7)
    if glyph_name is None:
        return
    old_advance, _ = font["hmtx"][glyph_name]
    if old_advance <= DOT_ADVANCE + 50:
        return
    glyf = font["glyf"]
    glyph = glyf[glyph_name]
    glyph.coordinates.translate(((DOT_ADVANCE - old_advance) / 2, 0))
    glyph.recalcBounds(glyf)
    font["hmtx"][glyph_name] = (DOT_ADVANCE, glyph.xMin)


def rename(font: TTFont, family: str) -> None:
    """Give the Modified Version its own name and drop name records that repeat a Reserved Font Name."""
    name = font["name"]
    name.names = [r for r in name.names if r.nameID in KEEP_NAME_IDS or not any(w in r.toUnicode() for w in RESERVED_WORDS)]
    ps = family.replace(" ", "")
    for platform, enc, lang in ((3, 1, 0x409), (1, 0, 0)):
        for name_id, value in ((1, family), (2, "Regular"), (3, f"{ps}-Regular;Phase"), (4, f"{family} Regular"),
                               (6, f"{ps}-Regular")):
            name.setName(value, name_id, platform, enc, lang)
    for name_id in (16, 17, 21, 22, 25):
        name.removeNames(nameID=name_id)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--family", required=True)
    parser.add_argument("--wght", help="limit a variable font's weight axis, e.g. 300:700")
    parser.add_argument("--chars-from", type=pathlib.Path, default=pathlib.Path("."))
    args = parser.parse_args()

    codepoints = {ord(c) for c in source_characters(args.chars_from) | gb2312_level1()}
    codepoints |= set(range(0x20, 0x7F)) | set(range(0xA0, 0x100)) | set(range(0x2010, 0x2028))
    codepoints |= set(range(0x2190, 0x2194)) | set(range(0x3000, 0x3040))
    codepoints |= {0xFF01, 0xFF08, 0xFF09, 0xFF0C, 0xFF0E, 0xFF1A, 0xFF1B, 0xFF1F, 0x2713, 0x00D7, 0x2103, 0x2248}

    font = TTFont(args.source)
    if args.wght and "fvar" in font:
        low, high = (float(x) for x in args.wght.split(":"))
        font = instancer.instantiateVariableFont(font, {"wght": (low, high)})
    options = subset.Options()
    options.layout_features = ["kern", "ccmp", "locl", "tnum", "pnum"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.hinting = False
    options.glyph_names = True  # keep names until the dot is patched
    options.drop_tables += ["DSIG"]
    with tempfile.TemporaryDirectory() as scratch:
        limited = pathlib.Path(scratch) / "limited.ttf"
        font.save(limited)
        work = subset.load_font(str(limited), options)
        subsetter = subset.Subsetter(options)
        subsetter.populate(unicodes=sorted(codepoints))
        subsetter.subset(work)
        narrow_middle_dot(work)
        rename(work, args.family)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        work.save(args.out)
        work.close()
    final = TTFont(args.out)
    cmap = final.getBestCmap()
    missing = sorted(c for c in codepoints if c not in cmap and c > 0x2FF)
    print(f"{args.out}: {args.out.stat().st_size // 1024} KB, {final['maxp'].numGlyphs} glyphs; "
          f"requested characters this font lacks: {len(missing)}")


if __name__ == "__main__":
    main()
