from __future__ import annotations

import html
import re
from pathlib import Path

from reportlab.graphics.shapes import Drawing, Line, Polygon, Rect, String
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate, Frame, HRFlowable, KeepTogether, PageBreak, PageTemplate,
    Paragraph, Spacer, Table, TableStyle,
)

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "output" / "pdf"
OUTPUT.mkdir(parents=True, exist_ok=True)

INK = colors.HexColor("#122033")
MUTED = colors.HexColor("#53657b")
CYAN = colors.HexColor("#0086a8")
CYAN_DARK = colors.HexColor("#07556b")
ICE = colors.HexColor("#eaf4f8")
LINE = colors.HexColor("#b9cfda")
PAPER = colors.HexColor("#f8fbfc")
GOLD = colors.HexColor("#d89a1d")
RED = colors.HexColor("#b74242")


def register_fonts() -> tuple[str, str, str]:
    candidates = [
        ("AxiomSans", Path("C:/Windows/Fonts/arial.ttf"), Path("C:/Windows/Fonts/arialbd.ttf")),
        ("AxiomSans", Path("C:/Windows/Fonts/segoeui.ttf"), Path("C:/Windows/Fonts/seguisb.ttf")),
    ]
    for name, regular, bold in candidates:
        if regular.exists() and bold.exists():
            pdfmetrics.registerFont(TTFont(name, regular))
            pdfmetrics.registerFont(TTFont(name + "Bold", bold))
            mono = Path("C:/Windows/Fonts/consola.ttf")
            if mono.exists():
                pdfmetrics.registerFont(TTFont("AxiomMono", mono))
            else:
                return name, name + "Bold", "Courier"
            return name, name + "Bold", "AxiomMono"
    return "Helvetica", "Helvetica-Bold", "Courier"


FONT, FONT_BOLD, FONT_MONO = register_fonts()


def clean(value: str) -> str:
    replacements = {
        "\u2013": "-", "\u2014": "-", "\u2011": "-", "\u2212": "-",
        "\u2192": " to ", "\u2190": " from ", "\u2194": " with ", "\u00d7": " x ",
        "\u2265": ">=", "\u2264": "<=", "\u2260": "!=", "\u00f7": "/",
        "\u2026": "...", "\u2022": "*", "\u00b7": " - ", "\u2248": "about",
        "\u2018": "'", "\u2019": "'", "\u201c": '"', "\u201d": '"',
    }
    for source, target in replacements.items():
        value = value.replace(source, target)
    value = re.sub(r"[\U00010000-\U0010ffff]", "", value)
    return value.strip()


def inline(value: str) -> str:
    value = clean(value)
    value = re.sub(r"!\[[^]]*]\([^)]*\)", "", value)
    value = re.sub(r"\[([^]]+)]\([^)]*\)", r"\1", value)
    value = html.escape(value)
    value = re.sub(r"`([^`]+)`", rf'<font name="{FONT_MONO}" color="#07556b">\1</font>', value)
    value = re.sub(r"\*\*([^*]+)\*\*", rf'<font name="{FONT_BOLD}">\1</font>', value)
    # Single asterisks are also valid wildcard characters in connector event
    # names. Treating them as emphasis after code-token substitution can create
    # crossed markup, so the PDF renderer keeps them as literal text.
    return value


def styles():
    sample = getSampleStyleSheet()
    return {
        "title": ParagraphStyle("title", parent=sample["Title"], fontName=FONT_BOLD, fontSize=30,
                                leading=35, textColor=INK, spaceAfter=10),
        "subtitle": ParagraphStyle("subtitle", parent=sample["Normal"], fontName=FONT, fontSize=12,
                                   leading=18, textColor=MUTED, spaceAfter=16),
        "h1": ParagraphStyle("h1", parent=sample["Heading1"], fontName=FONT_BOLD, fontSize=20,
                             leading=25, textColor=INK, spaceBefore=14, spaceAfter=9),
        "h2": ParagraphStyle("h2", parent=sample["Heading2"], fontName=FONT_BOLD, fontSize=14,
                             leading=18, textColor=CYAN_DARK, spaceBefore=12, spaceAfter=7,
                             keepWithNext=True),
        "h3": ParagraphStyle("h3", parent=sample["Heading3"], fontName=FONT_BOLD, fontSize=11,
                             leading=14, textColor=INK, spaceBefore=9, spaceAfter=5, keepWithNext=True),
        "body": ParagraphStyle("body", parent=sample["BodyText"], fontName=FONT, fontSize=8.4,
                               leading=12.2, textColor=INK, spaceAfter=6),
        "bullet": ParagraphStyle("bullet", parent=sample["BodyText"], fontName=FONT, fontSize=8.2,
                                 leading=11.8, textColor=INK, leftIndent=12, firstLineIndent=-7,
                                 bulletIndent=2, spaceAfter=3),
        "small": ParagraphStyle("small", parent=sample["BodyText"], fontName=FONT, fontSize=7,
                                leading=9.5, textColor=MUTED),
        "code": ParagraphStyle("code", parent=sample["Code"], fontName=FONT_MONO, fontSize=6.7,
                               leading=9, textColor=CYAN_DARK, backColor=ICE, borderPadding=7,
                               spaceAfter=8),
        "toc": ParagraphStyle("toc", parent=sample["BodyText"], fontName=FONT, fontSize=9,
                              leading=13, textColor=INK, leftIndent=8, spaceAfter=2),
    }


STYLES = styles()


class AxiomDocTemplate(BaseDocTemplate):
    def __init__(self, filename: Path, page_size, title: str):
        self.document_title = title
        self.page_width, self.page_height = page_size
        super().__init__(str(filename), pagesize=page_size,
                         leftMargin=16 * mm, rightMargin=16 * mm,
                         topMargin=18 * mm, bottomMargin=16 * mm,
                         title=title, author="Axiom CRM")
        frame = Frame(self.leftMargin, self.bottomMargin, self.width, self.height,
                      leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0)
        self.addPageTemplates(PageTemplate(id="content", frames=[frame], onPage=self.decorate))

    def decorate(self, canvas, doc):
        canvas.saveState()
        if doc.page == 1:
            canvas.setFillColor(CYAN)
            canvas.rect(0, self.page_height - 7 * mm, self.page_width, 7 * mm, fill=1, stroke=0)
        else:
            canvas.setStrokeColor(LINE)
            canvas.line(16 * mm, self.page_height - 12 * mm, self.page_width - 16 * mm, self.page_height - 12 * mm)
            canvas.setFont(FONT_BOLD, 6.5)
            canvas.setFillColor(CYAN_DARK)
            canvas.drawString(16 * mm, self.page_height - 9.5 * mm, "AXIOM CRM 1.0")
            canvas.setFont(FONT, 6.5)
            canvas.setFillColor(MUTED)
            canvas.drawRightString(self.page_width - 16 * mm, self.page_height - 9.5 * mm, self.document_title)
        canvas.setStrokeColor(LINE)
        canvas.line(16 * mm, 11 * mm, self.page_width - 16 * mm, 11 * mm)
        canvas.setFont(FONT, 6.5)
        canvas.setFillColor(MUTED)
        canvas.drawString(16 * mm, 7.5 * mm, "Governed product documentation")
        canvas.drawRightString(self.page_width - 16 * mm, 7.5 * mm, f"Page {doc.page}")
        canvas.restoreState()


def cover(title: str, subtitle: str, label: str):
    return [
        Spacer(1, 26 * mm),
        Paragraph("AXIOM CRM <font color='#0086a8'>1.0</font>", STYLES["h3"]),
        Spacer(1, 12 * mm),
        Paragraph(inline(title), STYLES["title"]),
        Paragraph(inline(subtitle), STYLES["subtitle"]),
        HRFlowable(width="45%", thickness=2, color=CYAN, hAlign="LEFT", spaceAfter=12 * mm),
        Table([["DOCUMENT", label], ["AUDIENCE", "Operators, administrators, auditors and delivery teams"],
               ["VERSION", "1.0"], ["STATUS", "Controlled reference generated from repository documentation"]],
              colWidths=[30 * mm, 120 * mm], style=TableStyle([
                  ("FONT", (0, 0), (-1, -1), FONT, 8),
                  ("FONT", (0, 0), (0, -1), FONT_BOLD, 7),
                  ("TEXTCOLOR", (0, 0), (0, -1), CYAN_DARK),
                  ("TEXTCOLOR", (1, 0), (-1, -1), INK),
                  ("BACKGROUND", (0, 0), (-1, -1), PAPER),
                  ("GRID", (0, 0), (-1, -1), .5, LINE),
                  ("VALIGN", (0, 0), (-1, -1), "TOP"),
                  ("PADDING", (0, 0), (-1, -1), 7),
              ])),
        Spacer(1, 18 * mm),
        Paragraph("This document explains the product in plain language while preserving the exact governance, formula and data-flow contracts implemented by the system.", STYLES["subtitle"]),
        PageBreak(),
    ]


def markdown_table(lines: list[str], available_width: float):
    rows = []
    for line in lines:
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells):
            continue
        rows.append(cells)
    if not rows:
        return Spacer(1, 1)
    columns = max(len(row) for row in rows)
    rows = [row + [""] * (columns - len(row)) for row in rows]
    weights = []
    for column in range(columns):
        width = max(8, min(34, max(len(clean(row[column])) for row in rows)))
        weights.append(width)
    total = sum(weights)
    widths = [available_width * value / total for value in weights]
    data = [[Paragraph(inline(cell), STYLES["small"]) for cell in row] for row in rows]
    table = Table(data, colWidths=widths, repeatRows=1, hAlign="LEFT")
    table.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, 0), FONT_BOLD, 6.6),
        ("BACKGROUND", (0, 0), (-1, 0), CYAN_DARK),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("BACKGROUND", (0, 1), (-1, -1), colors.white),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, PAPER]),
        ("GRID", (0, 0), (-1, -1), .35, LINE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    return table


def arrow(drawing: Drawing, x1, y1, x2, y2, color=CYAN):
    drawing.add(Line(x1, y1, x2, y2, strokeColor=color, strokeWidth=1.2))
    angle = __import__("math").atan2(y2 - y1, x2 - x1)
    size = 5
    import math
    points = [x2, y2,
              x2 - size * math.cos(angle - .55), y2 - size * math.sin(angle - .55),
              x2 - size * math.cos(angle + .55), y2 - size * math.sin(angle + .55)]
    drawing.add(Polygon(points, fillColor=color, strokeColor=color))


def box(drawing: Drawing, x, y, w, h, title, subtitle="", fill=PAPER, border=CYAN_DARK):
    drawing.add(Rect(x, y, w, h, rx=4, ry=4, fillColor=fill, strokeColor=border, strokeWidth=.8))
    drawing.add(String(x + w / 2, y + h - 14, clean(title), textAnchor="middle",
                       fontName=FONT_BOLD, fontSize=7, fillColor=INK))
    if subtitle:
        words = clean(subtitle).split()
        lines, current = [], ""
        for word in words:
            candidate = (current + " " + word).strip()
            if len(candidate) > max(14, int(w / 4.5)):
                lines.append(current); current = word
            else: current = candidate
        if current: lines.append(current)
        for idx, line in enumerate(lines[:3]):
            drawing.add(String(x + w / 2, y + h - 27 - idx * 9, line, textAnchor="middle",
                               fontName=FONT, fontSize=5.8, fillColor=MUTED))


def diagram(index: int, width: float):
    specs = {
        1: ("System Context", [
            ("Operator", "Web or Electron", 10, 105), ("React UI", "Accessible client", 115, 105),
            ("REST API", "Auth + domain", 220, 105), ("PostgreSQL", "Source of truth", 325, 145),
            ("Outbox", "Atomic events", 325, 65), ("Kafka", "Event backbone", 430, 65),
            ("Analytics", "Read model", 535, 105), ("Jasper", "PDF/Excel/Word", 640, 105)],
            [(0,1),(1,2),(2,3),(2,4),(4,5),(5,6),(6,7),(7,1)], 205),
        2: ("Authorized Request", [
            ("1. User action", "Load or command", 10, 105), ("2. Session", "Token + tenant", 125, 105),
            ("3. Authorization", "Screen, record, field", 240, 105), ("4. Workflow", "Validation + gates", 355, 105),
            ("5. Transaction", "Row + audit + outbox", 470, 105), ("6. Response", "Result or guidance", 585, 105)],
            [(0,1),(1,2),(2,3),(3,4),(4,5)], 185),
        3: ("Core Entity Relationship Map", [
            ("Tenant", "1", 15, 175), ("User", "many", 135, 175), ("Account", "customer", 255, 175),
            ("Contact", "person", 375, 175), ("Lead", "demand", 15, 90), ("Opportunity", "deal", 135, 90),
            ("Quote", "offer", 255, 90), ("Contract", "agreement", 375, 90),
            ("Activity", "engagement", 495, 175), ("Case", "service", 495, 90),
            ("Audit Event", "immutable", 615, 175), ("Outbox Event", "integration", 615, 90)],
            [(0,1),(0,2),(2,3),(4,2),(4,3),(4,5),(2,5),(5,6),(6,7),(2,8),(2,9),(5,8),(2,10),(5,10),(2,11),(5,11)], 260),
        4: ("Create To Report", [
            ("CRM command", "create/update", 10, 105), ("Gate", "permission + process", 120, 105),
            ("Atomic commit", "row + audit + event", 230, 105), ("Projection", "idempotent consumer", 340, 105),
            ("Governed query", "formula + access", 450, 105), ("Grid", "100-row pages", 560, 145),
            ("Jasper", "same dataset", 560, 65), ("Reconcile", "zero drift", 670, 105)],
            [(0,1),(1,2),(2,3),(3,4),(4,5),(4,6),(3,7)], 205),
        5: ("Workflow Activity", [
            ("Draft", "capture", 20, 110), ("Validated", "requirements met", 145, 110),
            ("Approval", "when required", 270, 155), ("Ready", "permitted next step", 395, 110),
            ("Executed", "audited outcome", 520, 110), ("Blocked", "missing prerequisite", 270, 45),
            ("Rejected", "reason retained", 520, 45)],
            [(0,1),(1,2),(1,3),(2,3),(3,4),(0,5),(5,0),(2,6)], 230),
        6: ("Event And Recovery", [
            ("Transaction", "business change", 10, 105), ("Outbox", "durable event", 120, 105),
            ("Relay", "publish", 230, 105), ("Kafka", "ordered partition", 340, 105),
            ("Consumers", "analytics/automation", 450, 145), ("Dispatch", "vendor boundary", 450, 65),
            ("Retry", "backoff", 560, 65), ("Dead letter", "operator recovery", 670, 65)],
            [(0,1),(1,2),(2,3),(3,4),(3,5),(5,6),(6,7)], 205),
        7: ("Migration", [
            ("Discover", "read only", 10, 105), ("Map", "versioned fields", 115, 105),
            ("Dry run", "zero writes", 220, 105), ("Import", "chunk + ledger", 325, 105),
            ("Reconcile", "source vs target", 430, 105), ("Delta", "checkpoint", 535, 145),
            ("Rollback", "owned records only", 535, 65), ("Recover", "retry/cancel", 640, 105)],
            [(0,1),(1,2),(2,3),(3,4),(4,5),(3,6),(5,7),(6,7)], 205),
        8: ("Offline Synchronization", [
            ("Issue package", "authorized snapshot", 10, 105), ("Offline work", "local changes", 125, 105),
            ("Reconnect", "submit delta", 240, 105), ("Revalidate", "session + package", 355, 145),
            ("Version check", "optimistic lock", 355, 65), ("Apply", "normal gates", 470, 145),
            ("Conflict", "local vs server", 470, 65), ("Audit/event", "traceable result", 585, 105)],
            [(0,1),(1,2),(2,3),(3,4),(4,5),(4,6),(5,7),(6,7)], 205),
    }
    title, nodes, edges, height = specs[index]
    base_width = 760
    scale = min(1, width / base_width)
    drawing = Drawing(base_width * scale, height * scale)
    drawing.add(Rect(0, 0, base_width * scale, height * scale, fillColor=colors.white, strokeColor=LINE))
    drawing.add(String(12 * scale, (height - 17) * scale, title.upper(), fontName=FONT_BOLD,
                       fontSize=7 * scale, fillColor=CYAN_DARK))
    node_w, node_h = 92, 52
    centers = []
    for node_title, subtitle, x, y in nodes:
        sx, sy = x * scale, y * scale
        box(drawing, sx, sy, node_w * scale, node_h * scale, node_title, subtitle)
        centers.append((sx + node_w * scale / 2, sy + node_h * scale / 2))
    for source, target in edges:
        x1, y1 = centers[source]; x2, y2 = centers[target]
        dx, dy = x2 - x1, y2 - y1
        length = max((dx * dx + dy * dy) ** .5, 1)
        inset_x, inset_y = dx / length * node_w * scale * .48, dy / length * node_h * scale * .48
        arrow(drawing, x1 + inset_x, y1 + inset_y, x2 - inset_x, y2 - inset_y)
    return drawing


def parse_markdown(path: Path, available_width: float, include_diagrams=False):
    lines = path.read_text(encoding="utf-8").splitlines()
    story = []
    paragraph = []
    diagram_index = 0

    def flush():
        if paragraph:
            story.append(Paragraph(inline(" ".join(paragraph)), STYLES["body"]))
            paragraph.clear()

    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if stripped.startswith("```mermaid"):
            flush(); i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                i += 1
            diagram_index += 1
            if include_diagrams:
                story.append(KeepTogether([diagram(diagram_index, available_width), Spacer(1, 8)]))
        elif stripped.startswith("```"):
            flush(); language = stripped[3:].strip(); code = [] ; i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code.append(lines[i]); i += 1
            story.append(Paragraph(inline("\n".join(code)).replace("\n", "<br/>"), STYLES["code"]))
        elif stripped.startswith("# "):
            flush()
            if story: story.append(PageBreak())
            story.append(Paragraph(inline(stripped[2:]), STYLES["h1"]))
        elif stripped.startswith("## "):
            flush(); story.append(Paragraph(inline(stripped[3:]), STYLES["h2"]))
        elif stripped.startswith("### "):
            flush(); story.append(Paragraph(inline(stripped[4:]), STYLES["h3"]))
        elif stripped.startswith("| "):
            flush(); table_lines = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                table_lines.append(lines[i]); i += 1
            story.append(markdown_table(table_lines, available_width)); story.append(Spacer(1, 7)); i -= 1
        elif re.match(r"^[-*] ", stripped):
            flush(); story.append(Paragraph("* " + inline(stripped[2:]), STYLES["bullet"]))
        elif re.match(r"^\d+\. ", stripped):
            flush(); match = re.match(r"^(\d+)\.\s+(.*)", stripped)
            story.append(Paragraph(f"{match.group(1)}. " + inline(match.group(2)), STYLES["bullet"]))
        elif stripped == "---":
            flush(); story.append(HRFlowable(width="100%", thickness=.6, color=LINE, spaceBefore=5, spaceAfter=7))
        elif not stripped:
            flush()
        elif stripped.startswith(">"):
            flush(); story.append(Table([[Paragraph(inline(stripped.lstrip("> ")), STYLES["body"])]],
                                        colWidths=[available_width], style=TableStyle([
                                            ("BACKGROUND", (0,0), (-1,-1), ICE),
                                            ("BOX", (0,0), (-1,-1), .8, CYAN),
                                            ("LEFTPADDING", (0,0), (-1,-1), 9),
                                            ("RIGHTPADDING", (0,0), (-1,-1), 9),
                                            ("TOPPADDING", (0,0), (-1,-1), 7),
                                            ("BOTTOMPADDING", (0,0), (-1,-1), 2),
                                        ])))
        else:
            paragraph.append(stripped)
        i += 1
    flush()
    return story


def build_pdf(source: Path, output: Path, title: str, subtitle: str, label: str,
              page_size=A4, include_diagrams=False):
    doc = AxiomDocTemplate(output, page_size, title)
    story = cover(title, subtitle, label)
    story.extend(parse_markdown(source, doc.width, include_diagrams=include_diagrams)[1:])
    doc.build(story)


def main():
    build_pdf(
        ROOT / "docs" / "manual" / "user-guide.md",
        OUTPUT / "axiom-crm-user-manual-v1.0.pdf",
        "Axiom CRM User Manual",
        "Complete operator guide, workflow handbook, field/formula dictionary and module impact reference",
        "USER MANUAL",
        A4,
        False,
    )
    build_pdf(
        ROOT / "docs" / "architecture" / "technical-architecture-and-data-flow.md",
        OUTPUT / "axiom-crm-technical-architecture-v1.0.pdf",
        "Axiom CRM Technical Architecture",
        "System architecture, entity relationships, event flows, activity diagrams and impact analysis in plain language",
        "TECHNICAL ARCHITECTURE",
        landscape(A4),
        True,
    )
    print(OUTPUT / "axiom-crm-user-manual-v1.0.pdf")
    print(OUTPUT / "axiom-crm-technical-architecture-v1.0.pdf")


if __name__ == "__main__":
    main()
