#!/usr/bin/env python3
"""MATH-01: exports the glyphs and OpenType MATH data the equation typesetter needs from STIX Two Math.

STIX Two Math is licensed under the SIL Open Font License 1.1 (see engine/mathtype/src/main/resources/math/OFL.txt).
The engine draws every glyph as a vector path from this data, so no font file is loaded on the device and
equations stay crisp at any zoom and in PDF export.

Usage: python3 tools/math/export_math_font.py [STIXTwoMath.otf] [out.gz]
  default font: /System/Library/Fonts/Supplemental/STIXTwoMath.otf (macOS ships it), or download the
  release from https://github.com/stipub/stixfonts (static_otf/STIXTwoMath-Regular.otf).
Needs fontTools (pip install fonttools).

Format (UTF-8 text, gzip): one record per line, fields separated by a single space.
  H <unitsPerEm> <minConnectorOverlap> <version>
  K <MathConstant> <value>
  G <id> <advance> <xMin> <yMin> <xMax> <yMax> <italicCorrection> <topAccentX|-> <path>
      path: M/L/Q/C/Z commands with integer font units, y up, e.g. "M10 0L20 0Z"
  U <codepoint hex> <id>
  V <id> <variantId>:<advance>,...          vertical size variants (delimiters, radical, big operators)
  W <id> <variantId>:<advance>,...          horizontal size variants (wide accents)
  A <id> <italic> <partId>:<start>:<end>:<full>:<extender 0|1>,...   vertical assembly, bottom to top
  B <id> <italic> <partId>:<start>:<end>:<full>:<extender 0|1>,...   horizontal assembly, left to right
"""
import gzip
import sys

from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.ttLib import TTFont

RANGES = [
    (0x20, 0x7E), (0xA0, 0xFF), (0x131, 0x131), (0x237, 0x237), (0x300, 0x36F), (0x391, 0x3C9), (0x3D0, 0x3F6),
    (0x2000, 0x206F), (0x20D0, 0x20FF), (0x2100, 0x214F), (0x2190, 0x21FF), (0x2200, 0x22FF), (0x2300, 0x23FF),
    (0x25A0, 0x25FF), (0x2605, 0x2606), (0x27C0, 0x27FF), (0x2900, 0x297F), (0x2980, 0x29FF), (0x2A00, 0x2AFF),
    (0x1D400, 0x1D4CF),  # bold, italic, bold italic, script
    (0x1D504, 0x1D56B),  # fraktur, double-struck
    (0x1D6A4, 0x1D6A5),  # dotless i/j
    (0x1D6A8, 0x1D755),  # bold, italic, bold italic Greek
    (0x1D7CE, 0x1D7E1),  # bold and double-struck digits
]

CONSTANTS = [
    "AxisHeight", "AccentBaseHeight", "FlattenedAccentBaseHeight", "DelimitedSubFormulaMinHeight", "DisplayOperatorMinHeight",
    "MathLeading", "ScriptPercentScaleDown", "ScriptScriptPercentScaleDown",
    "SubscriptShiftDown", "SubscriptTopMax", "SubscriptBaselineDropMin", "SuperscriptShiftUp", "SuperscriptShiftUpCramped",
    "SuperscriptBottomMin", "SuperscriptBaselineDropMax", "SubSuperscriptGapMin", "SuperscriptBottomMaxWithSubscript", "SpaceAfterScript",
    "UpperLimitGapMin", "UpperLimitBaselineRiseMin", "LowerLimitGapMin", "LowerLimitBaselineDropMin",
    "StackTopShiftUp", "StackTopDisplayStyleShiftUp", "StackBottomShiftDown", "StackBottomDisplayStyleShiftDown", "StackGapMin", "StackDisplayStyleGapMin",
    "FractionNumeratorShiftUp", "FractionNumeratorDisplayStyleShiftUp", "FractionDenominatorShiftDown", "FractionDenominatorDisplayStyleShiftDown",
    "FractionNumeratorGapMin", "FractionNumDisplayStyleGapMin", "FractionRuleThickness", "FractionDenominatorGapMin", "FractionDenomDisplayStyleGapMin",
    "OverbarVerticalGap", "OverbarRuleThickness", "OverbarExtraAscender", "UnderbarVerticalGap", "UnderbarRuleThickness", "UnderbarExtraDescender",
    "RadicalVerticalGap", "RadicalDisplayStyleVerticalGap", "RadicalRuleThickness", "RadicalExtraAscender",
    "RadicalKernBeforeDegree", "RadicalKernAfterDegree", "RadicalDegreeBottomRaisePercent",
]


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "/System/Library/Fonts/Supplemental/STIXTwoMath.otf"
    out = sys.argv[2] if len(sys.argv) > 2 else "engine/mathtype/src/main/resources/math/stix-two-math.txt.gz"
    font = TTFont(src)
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    math = font["MATH"].table
    hmtx = font["hmtx"]
    version = next(r.toUnicode() for r in font["name"].names if r.nameID == 5 and r.platformID == 3)

    italics = {}
    ic = math.MathGlyphInfo.MathItalicsCorrectionInfo
    for g, v in zip(ic.Coverage.glyphs, ic.ItalicsCorrection):
        italics[g] = v.Value
    accents = {}
    ta = math.MathGlyphInfo.MathTopAccentAttachment
    for g, v in zip(ta.TopAccentCoverage.glyphs, ta.TopAccentAttachment):
        accents[g] = v.Value

    variants = math.MathVariants
    vert = dict(zip(variants.VertGlyphCoverage.glyphs, variants.VertGlyphConstruction))
    horiz = dict(zip(variants.HorizGlyphCoverage.glyphs, variants.HorizGlyphConstruction))

    order = []
    ids = {}

    def use(name):
        if name not in ids:
            ids[name] = len(order)
            order.append(name)
        return ids[name]

    mapped = []
    for lo, hi in RANGES:
        for cp in range(lo, hi + 1):
            if cp in cmap:
                mapped.append((cp, use(cmap[cp])))
    # Variants and assembly parts of every mapped glyph.
    for name in list(order):
        for table in (vert, horiz):
            c = table.get(name)
            if c is None:
                continue
            for r in c.MathGlyphVariantRecord:
                use(r.VariantGlyph)
            if c.GlyphAssembly:
                for p in c.GlyphAssembly.PartRecords:
                    use(p.glyph)

    lines = [f"H {font['head'].unitsPerEm} {variants.MinConnectorOverlap} {version.replace(' ', '_')}"]
    mc = math.MathConstants
    for k in CONSTANTS:
        v = getattr(mc, k)
        lines.append(f"K {k} {getattr(v, 'Value', v)}")
    for i, name in enumerate(order):
        pen = DecomposingRecordingPen(glyphs)
        glyphs[name].draw(pen)
        path = []
        xs, ys = [], []
        for op, pts in pen.value:
            cmd = {"moveTo": "M", "lineTo": "L", "qCurveTo": "Q", "curveTo": "C", "closePath": "Z", "endPath": ""}[op]
            if op == "qCurveTo" and len(pts) != 2:
                raise SystemExit(f"{name}: implied quadratic points are not supported")
            coords = []
            for x, y in pts:
                x, y = round(x), round(y)
                xs.append(x)
                ys.append(y)
                coords.append(f"{x} {y}")
            if cmd:
                path.append(cmd + " ".join(coords))
        adv = hmtx[name][0]
        bbox = (min(xs), min(ys), max(xs), max(ys)) if xs else (0, 0, 0, 0)
        acc = accents.get(name)
        lines.append(f"G {i} {adv} {bbox[0]} {bbox[1]} {bbox[2]} {bbox[3]} {italics.get(name, 0)} {'-' if acc is None else acc} {''.join(path) or '-'}")
    for cp, gid in mapped:
        lines.append(f"U {cp:X} {gid}")
    for tag, atag, table in (("V", "A", vert), ("W", "B", horiz)):
        for name, c in table.items():
            if name not in ids:
                continue
            recs = ",".join(f"{ids[r.VariantGlyph]}:{r.AdvanceMeasurement}" for r in c.MathGlyphVariantRecord)
            if recs:
                lines.append(f"{tag} {ids[name]} {recs}")
            a = c.GlyphAssembly
            if a:
                parts = ",".join(f"{ids[p.glyph]}:{p.StartConnectorLength}:{p.EndConnectorLength}:{p.FullAdvance}:{p.PartFlags & 1}" for p in a.PartRecords)
                lines.append(f"{atag} {ids[name]} {a.ItalicsCorrection.Value} {parts}")
    data = ("\n".join(lines) + "\n").encode("utf-8")
    with gzip.GzipFile(out, "wb", mtime=0) as f:
        f.write(data)
    print(f"{len(order)} glyphs, {len(mapped)} code points, {len(data)} bytes -> {out}")


if __name__ == "__main__":
    main()
