"""Primitives for the documentation diagrams.

Each diagram is drawn once and written twice, in the light and dark palettes of Starlight's default theme, so it
matches the docs site and GitHub in either mode. ``Diagram.save`` refuses to write a diagram whose text overflows its
box, whose labels touch a line, whose lines pass behind a box they do not connect, or that leaves its canvas.
"""

from html import escape
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "public" / "diagrams"
MAX_WIDTH = 720

# Starlight's theme tokens (@astrojs/starlight/style/props.css) as hex: bg, gray-6, white, gray-3, gray-4, gray-5, accent.
PALETTES = {
    "light": {
        "paper": "#ffffff", "zone": "#f6f7f9", "ink": "#23262f", "muted": "#555962", "rule": "#888c96",
        "hairline": "#c1c3c8", "accent": "#3d50f5", "accent-text": "#3d50f5", "accent-tint": "#eff1fe",
    },
    "dark": {
        "paper": "#17181c", "zone": "#23262f", "ink": "#ffffff", "muted": "#a3a6ae", "rule": "#6b6f79",
        "hairline": "#40434d", "accent": "#3369ff", "accent-text": "#b2c7ff", "accent-tint": "#1b233c",
    },
}

SANS = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
MONO = "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace"

STYLE = """
text{{font-family:{sans}}} .mono{{font-family:{mono}}}
.name{{fill:{ink};font-size:14px;font-weight:600}}
.sub{{fill:{muted};font-size:11.5px}}
.lbl{{fill:{muted};font-size:10.5px;letter-spacing:.04em}}
.lbl-accent{{fill:{accent-text};font-size:10.5px;letter-spacing:.04em}}
.tag{{fill:{muted};font-size:8.5px;letter-spacing:.1em}}
.note{{fill:{muted};font-size:13px;font-style:italic}}
.bg,.mask{{fill:{paper}}} .mask-zone{{fill:{zone}}}
.box{{fill:{paper};stroke:{rule};stroke-width:1}}
.box-focal{{fill:{accent-tint};stroke:{accent};stroke-width:1.5}}
.box-ext{{fill:{paper};stroke:{rule};stroke-width:1;stroke-dasharray:4 3}}
.zone{{fill:{zone};stroke:{hairline};stroke-width:.8}}
.frame{{fill:none;stroke:{rule};stroke-width:.8}}
.chip{{fill:none;stroke:{hairline};stroke-width:.8}}
.w{{fill:none;stroke:{muted};stroke-width:1.2}}
.w-accent{{fill:none;stroke:{accent};stroke-width:1.6}}
.dash{{stroke-dasharray:5 4}}
.life{{stroke:{hairline};stroke-width:1;stroke-dasharray:3 3}}
.sep{{stroke:{hairline};stroke-width:.8}}
.m{{fill:{muted}}} .m-accent{{fill:{accent}}} .m-open{{fill:none;stroke:{muted};stroke-width:1.2}}
.dot{{fill:{ink}}}
"""

# Per-character advance budgets: font size, em per character, letter-spacing in em.
FONTS = {"name": (14, 0.60, 0), "sub": (11.5, 0.62, 0), "lbl": (10.5, 0.62, 0.04), "tag": (8.5, 0.62, 0.1),
         "note": (13, 0.56, 0)}


def width(text, role):
    size, advance, spacing = FONTS[role]
    return len(text) * size * (advance + spacing)


def rounded(points, r=8):
    """SVG path through axis-aligned points with rounded corners."""
    d = f"M{points[0][0]},{points[0][1]}"
    for i in range(1, len(points) - 1):
        (ax, ay), (bx, by), (cx, cy) = points[i - 1], points[i], points[i + 1]
        k = min(r, (abs(bx - ax) + abs(by - ay)) / 2, (abs(cx - bx) + abs(cy - by)) / 2)
        ux, uy = (bx > ax) - (bx < ax), (by > ay) - (by < ay)
        vx, vy = (cx > bx) - (cx < bx), (cy > by) - (cy < by)
        d += f" L{bx - ux * k},{by - uy * k} Q{bx},{by} {bx + vx * k},{by + vy * k}"
    return d + f" L{points[-1][0]},{points[-1][1]}"


def touches(box, point):
    x, y, w, h, _ = box
    px, py = point
    return x - 2 <= px <= x + w + 2 and y - 2 <= py <= y + h + 2


def crosses(box, ax, ay, bx, by):
    """True if the segment passes through the inside of the box, not just along or up to its edge."""
    x, y, w, h, _ = box
    return min(ax, bx) < x + w - 1 and max(ax, bx) > x + 1 and min(ay, by) < y + h - 1 and max(ay, by) > y + 1


class Diagram:
    """One diagram: zones, then wires, then labels, then nodes, so lines run behind boxes."""

    def __init__(self, slug, w, h, title, desc):
        self.slug, self.w, self.h, self.title, self.desc = slug, w, h, title, desc
        self.layers = {"zones": [], "wires": [], "labels": [], "nodes": []}
        self.boxes, self.masks, self.segments, self.wires, self.errors = [], [], [], [], []

    def _bound(self, x, y, w, h, what):
        if x < 0 or y < 0 or x + w > self.w or y + h > self.h:
            self.errors.append(f"{what} leaves the canvas")

    def _fit(self, text, role, room, what):
        if width(text, role) > room:
            self.errors.append(f"'{text}' is {width(text, role):.0f}px wide, {what} has {room:.0f}px")

    def zone(self, x, y, w, h, label):
        self.layers["zones"].append(f'<rect class="zone" x="{x}" y="{y}" width="{w}" height="{h}" rx="8"/>')
        self.label(x + 12, y + 16, label, anchor="start", on="zone", role="tag")
        self._bound(x, y, w, h, f"zone {label}")

    def node(self, x, y, w, h, name, sub=(), tag=None, kind="box", shape="rect"):
        """A box (rect, oval or diamond) with a bold name, mono sublabels and an optional type tag."""
        sub = [sub] if isinstance(sub, str) else list(sub)
        out = self.layers["nodes"]
        if shape == "diamond":
            pts = f"{x + w / 2},{y} {x + w},{y + h / 2} {x + w / 2},{y + h} {x},{y + h / 2}"
            out.append(f'<polygon class="mask" points="{pts}"/><polygon class="{kind}" points="{pts}"/>')
        else:
            rx = h / 2 if shape == "oval" else 6
            out.append(f'<rect class="mask" x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}"/>'
                       f'<rect class="{kind}" x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}"/>')
        top = y
        if tag:
            tw = width(tag, "tag") + 10
            out.append(f'<rect class="chip" x="{x + 8}" y="{y + 8}" width="{tw:.0f}" height="12" rx="2"/>'
                       f'<text class="tag mono" x="{x + 13}" y="{y + 17.5}">{escape(tag)}</text>')
            top = y + 20
            self._fit(tag, "tag", w - 26, f"tag of {name}")
        base = top + (y + h - top - (11 + 16 * len(sub))) / 2 + 11
        lines = [(name, "name", base)] + [(s, "sub", base + 16 * (i + 1)) for i, s in enumerate(sub)]
        for text, role, by in lines:
            cls = "name" if role == "name" else "sub mono"
            out.append(f'<text class="{cls}" x="{x + w / 2}" y="{by:.1f}" text-anchor="middle">{escape(text)}</text>')
            room = w - 16
            if shape == "diamond":
                room = w * (1 - 2 * (abs(by - 4 - (y + h / 2)) + 6) / h) - 8
            self._fit(text, role, room, f"node {name}")
        self.boxes.append((x, y, w, h, name))
        self._bound(x, y, w, h, f"node {name}")
        return x, y, w, h

    def wire(self, *points, accent=False, dashed=False, head="arrow"):
        """An orthogonal connector through the given points, arrowhead at the last one."""
        for (ax, ay), (bx, by) in zip(points, points[1:]):
            if ax != bx and ay != by:
                self.errors.append(f"diagonal segment {ax},{ay} -> {bx},{by}")
            self.segments.append((ax, ay, bx, by))
        self.wires.append(points)
        cls = ("w-accent" if accent else "w") + (" dash" if dashed else "")
        marker = {"arrow": "url(#a-accent)" if accent else "url(#a)", "open": "url(#a-open)", None: None}[head]
        end = f' marker-end="{marker}"' if marker else ""
        self.layers["wires"].append(f'<path class="{cls}" d="{rounded(points)}"{end}/>')

    def line(self, x1, y1, x2, y2, cls="life"):
        """A plain line that is not a connector (lifeline, separator); labels keep clear of it too."""
        self.layers["wires"].append(f'<line class="{cls}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}"/>')
        self.segments.append((x1, y1, x2, y2))

    def label(self, x, y, text, anchor="middle", on="paper", role="lbl", accent=False):
        """Masked mono text; y is the baseline."""
        w = width(text, role)
        left = {"start": x, "middle": x - w / 2, "end": x - w}[anchor]
        size = FONTS[role][0]
        mask = (left - 3, y - size * 0.8, w + 6, size * 1.1)
        cls = ("lbl-accent" if accent else role) + " mono"
        self.layers["labels"].append(
            f'<rect class="{"mask-zone" if on == "zone" else "mask"}" x="{mask[0]:.1f}" y="{mask[1]:.1f}" '
            f'width="{mask[2]:.1f}" height="{mask[3]:.1f}" rx="2"/>'
            f'<text class="{cls}" x="{x}" y="{y}" text-anchor="{anchor}">{escape(text)}</text>')
        self.masks.append((*mask, text))
        self._bound(*mask, f"label {text}")

    def text(self, x, y, text, cls="note", anchor="start"):
        """Unmasked free text such as a callout; it is checked against the canvas only."""
        self.layers["labels"].append(f'<text class="{cls}" x="{x}" y="{y}" text-anchor="{anchor}">{escape(text)}</text>')
        w = width(text, "note")
        self._bound({"start": x, "middle": x - w / 2, "end": x - w}[anchor], y - 10, w, 12, f"text {text}")

    def frame(self, x, y, w, h, op, guard=None):
        """A sequence-diagram fragment (loop, opt, alt) with its operator tab and guard."""
        self.layers["zones"].append(
            f'<rect class="frame" x="{x}" y="{y}" width="{w}" height="{h}" rx="4"/>'
            f'<path class="frame" d="M{x},{y + 16} H{x + 40} L{x + 46},{y + 10} V{y}"/>'
            f'<text class="tag mono" x="{x + 8}" y="{y + 12}">{escape(op)}</text>')
        if guard:
            # Masked so lifelines break around it; it sits on the frame's top band, never on a message.
            gw = width(guard, "lbl")
            self.layers["labels"].append(
                f'<rect class="mask" x="{x + 51}" y="{y + 3}" width="{gw + 6:.1f}" height="12" rx="2"/>'
                f'<text class="lbl mono" x="{x + 54}" y="{y + 12}">{escape(guard)}</text>')
        self._bound(x, y, w, h, f"frame {op}")

    def legend(self, y, items):
        """A bottom strip of (sample, text) pairs; sample is a box class, 'wire', 'dash' or 'accent'."""
        self.line(24, y - 16, self.w - 24, y - 16, cls="sep")
        x = 24
        for sample, text in items:
            if sample in ("wire", "dash", "accent"):
                cls = {"wire": "w", "dash": "w dash", "accent": "w-accent"}[sample]
                self.layers["labels"].append(f'<line class="{cls}" x1="{x}" y1="{y - 3}" x2="{x + 24}" y2="{y - 3}"/>')
            else:
                self.layers["labels"].append(
                    f'<rect class="{sample}" x="{x}" y="{y - 9}" width="24" height="12" rx="2"/>')
            self.layers["labels"].append(f'<text class="lbl mono" x="{x + 32}" y="{y}">{escape(text)}</text>')
            x += 32 + width(text, "lbl") + 28
        if x - 28 > self.w - 24:
            self.errors.append("legend is wider than the canvas")

    def check(self):
        """Geometry rules: labels clear of every line and box edge, lines clear of boxes they do not connect."""
        for mx, my, mw, mh, text in self.masks:
            for x1, y1, x2, y2 in self.segments:
                gap_x = max(min(x1, x2) - (mx + mw), mx - max(x1, x2), 0)
                gap_y = max(min(y1, y2) - (my + mh), my - max(y1, y2), 0)
                if max(gap_x, gap_y) < 4:
                    self.errors.append(f"label '{text}' touches the line {x1},{y1} -> {x2},{y2}")
            for bx, by, bw, bh, name in self.boxes:
                inside = mx >= bx and my >= by and mx + mw <= bx + bw and my + mh <= by + bh
                apart = mx + mw <= bx or bx + bw <= mx or my + mh <= by or by + bh <= my
                if not inside and not apart:
                    self.errors.append(f"label '{text}' overlaps node {name}")
        for points in self.wires:
            ends = [b for b in self.boxes if any(touches(b, p) for p in (points[0], points[-1]))]
            for (ax, ay), (bx, by) in zip(points, points[1:]):
                for box in self.boxes:
                    if box not in ends and crosses(box, ax, ay, bx, by):
                        self.errors.append(f"wire {points[0]} -> {points[-1]} runs behind node {box[4]}")

    def svg(self, theme):
        style = STYLE.format_map({"sans": SANS, "mono": MONO, **PALETTES[theme]})
        markers = ('<marker id="a" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto" markerUnits="userSpaceOnUse">'
                   '<polygon class="m" points="0 0, 10 3.5, 0 7"/></marker>'
                   '<marker id="a-accent" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto" markerUnits="userSpaceOnUse">'
                   '<polygon class="m-accent" points="0 0, 10 3.5, 0 7"/></marker>'
                   '<marker id="a-open" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto" markerUnits="userSpaceOnUse">'
                   '<polyline class="m-open" points="0 0, 10 3.5, 0 7"/></marker>')
        body = "".join("".join(self.layers[k]) for k in ("zones", "wires", "labels", "nodes"))
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" width="{self.w}" '
                f'height="{self.h}" role="img" aria-labelledby="{self.slug}-title {self.slug}-desc">'
                f'<title id="{self.slug}-title">{escape(self.title)}</title>'
                f'<desc id="{self.slug}-desc">{escape(self.desc)}</desc>'
                f'<defs><style>{style}</style>{markers}</defs>'
                f'<rect class="bg" width="100%" height="100%"/>{body}</svg>\n')

    def save(self):
        self.check()
        # Starlight's content column is about 610-720px; a wider canvas shrinks the text below reading size.
        if self.w > MAX_WIDTH:
            self.errors.append(f"canvas is {self.w}px wide, the docs column fits {MAX_WIDTH}px")
        if self.errors:
            raise SystemExit(f"{self.slug}:\n  " + "\n  ".join(self.errors))
        OUT.mkdir(parents=True, exist_ok=True)
        for theme in PALETTES:
            (OUT / f"{self.slug}-{theme}.svg").write_text(self.svg(theme))


class Sequence:
    """Lifelines for a sequence diagram: actors across the top, messages added top to bottom."""

    def __init__(self, d, actors, top=20, box_w=148, box_h=52, bottom=None):
        self.d, self.xs = d, [a[0] for a in actors]
        for cx, name, sub, kind in actors:
            d.node(cx - box_w / 2, top, box_w, box_h, name, sub, kind=kind)
            d.line(cx, top + box_h, cx, bottom or d.h - 16)

    def msg(self, a, b, y, text, kind="call", at=None):
        """Message from actor a to b at height y: call, return (dashed), async (open head) or accent.

        ``at`` moves the label off the midpoint, for messages that pass another lifeline.
        """
        xa, xb = self.xs[a], self.xs[b]
        self.d.wire((xa, y), (xb, y), accent=kind == "accent", dashed=kind in ("return", "async"),
                    head="open" if kind == "async" else "arrow")
        self.d.label(at or (xa + xb) / 2, y - 9, text, accent=kind == "accent")

    def self_msg(self, a, y, text, h=20):
        x = self.xs[a]
        self.d.wire((x, y), (x + 28, y), (x + 28, y + h), (x + 2, y + h))
        self.d.label(x + 38, y + h / 2 + 3, text, anchor="start")

    def divider(self, y, text, at=None):
        """A full-width pause in time, such as 'access token expires'; ``at`` keeps the label off lifelines."""
        x, half = at or self.d.w / 2, width(text, "lbl") / 2 + 12
        self.d.line(24, y, x - half, y, cls="sep")
        self.d.line(x + half, y, self.d.w - 24, y, cls="sep")
        self.d.label(x, y + 3, text)
