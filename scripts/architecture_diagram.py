"""Generates the architecture diagram. Usage: python scripts/architecture_diagram.py docs/architecture.svg"""
import sys

W, H = 1240, 905
out = []
add = out.append

BOX_FILL, BOX_STROKE, BOX_TEXT = "#E8F2FD", "#B9D5F5", "#0F4C8A"
GROUP_FILL, GROUP_STROKE = "#F4F5F7", "#DADDE2"
LINE, NEW = "#8A9099", "#D9620B"
SUB = "#5B6470"


def group(x, y, w, h, label):
    add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="6" fill="{GROUP_FILL}" stroke="{GROUP_STROKE}"/>')
    add(f'<text x="{x + 12}" y="{y + 18}" class="group">{label}</text>')


def box(x, y, w, h, title, sub=None, accent=False):
    stroke = NEW if accent else BOX_STROKE
    dash = ' stroke-dasharray="5 3"' if accent else ""
    add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="9" fill="{BOX_FILL}" stroke="{stroke}"{dash}/>')
    cx = x + w / 2
    if sub:
        add(f'<text x="{cx}" y="{y + h / 2 - 3}" class="title">{title}</text>')
        add(f'<text x="{cx}" y="{y + h / 2 + 11}" class="sub">{sub}</text>')
    else:
        add(f'<text x="{cx}" y="{y + h / 2 + 4}" class="title">{title}</text>')


def path(points, dashed=False, new=False, arrow=True):
    d = "M" + " L".join(f"{x} {y}" for x, y in points)
    color = NEW if new else LINE
    attrs = f'fill="none" stroke="{color}" stroke-width="{1.8 if new else 1.3}"'
    if dashed:
        attrs += ' stroke-dasharray="6 4"'
    if arrow:
        attrs += f' marker-end="url(#{"an" if new else "a"})"'
    add(f'<path d="{d}" {attrs}/>')


def label(x, y, text, new=False, anchor="start"):
    add(f'<text x="{x}" y="{y}" class="edge{" new" if new else ""}" text-anchor="{anchor}">{text}</text>')


add(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
    'font-family="Segoe UI, Roboto, Helvetica, Arial, sans-serif">')
add('<title>Tune2AI architecture (revised)</title>')
add(f'''<defs>
<marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="{LINE}"/></marker>
<marker id="an" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="{NEW}"/></marker>
<style>
.title {{ font-size: 13px; font-weight: 600; fill: {BOX_TEXT}; text-anchor: middle; }}
.sub {{ font-size: 10.5px; fill: {SUB}; text-anchor: middle; }}
.group {{ font-size: 13px; font-weight: 600; fill: #2B3038; }}
.edge {{ font-size: 10.5px; fill: {SUB}; }}
.edge.new {{ fill: {NEW}; font-weight: 600; }}
.h1 {{ font-size: 18px; font-weight: 700; fill: #1D2129; }}
.legend {{ font-size: 11.5px; fill: #3A414B; }}
</style>
</defs>''')
add(f'<rect width="{W}" height="{H}" fill="#FFFFFF"/>')
add('<text x="20" y="32" class="h1">Tune2AI architecture — revised</text>')

# Legend
lx = 640
path([(lx, 27), (lx + 34, 27)])
add(f'<text x="{lx + 40}" y="31" class="legend">request / data flow</text>')
path([(lx + 170, 27), (lx + 204, 27)], dashed=True)
add(f'<text x="{lx + 210}" y="31" class="legend">control / signals</text>')
path([(lx + 330, 27), (lx + 364, 27)], new=True)
add(f'<text x="{lx + 370}" y="31" class="legend" fill="{NEW}">changed from original</text>')

# --- Application + SDK ---------------------------------------------------
box(520, 52, 200, 40, "Application", "built on the Tune2AI SDK")
group(20, 110, 1200, 150, "Developer SDK Layer · on device")
box(520, 135, 200, 40, "Unified AI API")
path([(620, 92), (620, 135)])
apis = [("Chat API", 220), ("Agent API", 410), ("Workflow API", 600), ("Model API", 940)]
path([(620, 175), (620, 190)], arrow=False)
path([(300, 190), (1020, 190)], arrow=False)
for name, x in apis:
    box(x, 205, 160, 40, name)
    path([(x + 80, 190), (x + 80, 205)])

# --- Orchestration core ----------------------------------------------------
group(20, 290, 600, 220, "AI Orchestration Core")
box(200, 318, 240, 42, "Request Controller", "validation · lifecycle · cancellation")
# Chat, Agent and Workflow APIs share one entry path (Workflow API used to bypass it).
path([(300, 245), (300, 272)], arrow=False)
path([(490, 245), (490, 272)], arrow=False)
path([(680, 245), (680, 272)], new=True, arrow=False)
path([(300, 272), (490, 272)], arrow=False)
path([(490, 272), (680, 272)], new=True, arrow=False)
path([(320, 272), (320, 318)])
label(690, 268, "Workflow API joins the shared path", new=True)

box(40, 395, 170, 42, "Model &amp; Task Router")
box(235, 395, 170, 42, "Agent Engine", "model steps via controller")
box(430, 395, 170, 42, "Workflow Engine", "model steps via controller")
path([(320, 360), (320, 378)], arrow=False)
path([(125, 378), (515, 378)], arrow=False)
for cx in (125, 320, 515):
    path([(cx, 378), (cx, 395)])
box(330, 458, 180, 36, "Tool Calling Engine")
path([(320, 437), (320, 447), (420, 447), (420, 458)])
path([(515, 437), (515, 447), (420, 447)], arrow=False)

# --- AI services -------------------------------------------------------------
group(640, 290, 580, 220, "AI Services Layer")
box(800, 318, 200, 42, "Context Manager", "single owner of context limits")
path([(440, 339), (800, 339)])
label(450, 333, "assemble context")
for name, x in (("Prompt Manager", 660), ("RAG Engine", 830), ("Memory Engine", 1000)):
    box(x, 395, 150, 42, name)
path([(900, 360), (900, 378)], arrow=False)
path([(735, 378), (1075, 378)], arrow=False)
for cx in (735, 905, 1075):
    path([(cx, 378), (cx, 395)])
box(825, 458, 160, 36, "Embedding &amp; Reranking")
path([(905, 437), (905, 458)])
box(1010, 455, 180, 42, "Model Manager", "catalog · availability · load state", accent=True)
# Model API -> Model Manager (lifecycle, no request controller)
path([(1100, 225), (1205, 225), (1205, 476), (1190, 476)])
label(1110, 219, "lifecycle")

# --- Inference runtime -------------------------------------------------------
group(20, 530, 660, 160, "Inference Runtime Layer")
box(40, 558, 240, 42, "Inference Abstraction", "load · generate · stream · cancel")
# Router -> Inference Abstraction (generate)
path([(190, 437), (190, 558)])
label(196, 545, "generate")
# Router -> Model Manager (select model) — was missing
path([(210, 425), (222, 425), (222, 520), (1040, 520), (1040, 497)], dashed=True, new=True)
label(560, 515, "select compatible model", new=True)
# Model Manager -> Inference Abstraction (load/unload)
path([(1070, 497), (1070, 579), (280, 579)], dashed=True, new=True)
label(700, 574, "load / unload", new=True)

backends = [
    ("llama.cpp", None, False), ("ONNX Runtime", None, False), ("Core ML", "iOS only", False),
    ("Remote API", "optional · HTTPS/SSE", True), ("Future", "extension point", False),
]
path([(160, 600), (160, 614)], arrow=False)
path([(94, 614), (606, 614)], arrow=False)
for i, (name, sub, accent) in enumerate(backends):
    x = 36 + i * 128
    box(x, 628, 116, 44, name, sub, accent=accent)
    path([(x + 58, 614), (x + 58, 628)], new=accent)
label(548, 700, "remote = one more backend, not a separate stack", new=True, anchor="middle")

# --- Native platform -------------------------------------------------------
group(20, 720, 1200, 170, "Native Platform Layer")
path([(300, 690), (300, 735)])
label(306, 714, "native interop (JNI / Swift)")
path([(170, 735), (430, 735)], arrow=False)
box(60, 750, 220, 42, "Android Adapter", "Kotlin / JNI")
box(320, 750, 220, 42, "iOS Adapter", "Swift")
path([(170, 735), (170, 750)])
path([(430, 735), (430, 750)])
box(720, 750, 200, 42, "Local Storage", "memory · RAG index · checkpoints")
box(980, 750, 200, 42, "Device Resource Monitor", "memory · battery · thermal")
# each adapter implements storage + resource signals
path([(170, 792), (170, 840), (1080, 840), (1080, 792)])
path([(430, 792), (430, 840)], arrow=False)
path([(820, 840), (820, 792)])
label(560, 858, "each adapter implements storage and resource signals", anchor="middle")
# Resource Monitor -> Model Manager — was missing
path([(1120, 750), (1120, 497)], dashed=True, new=True)
label(1126, 620, "resource", new=True)
label(1126, 633, "pressure", new=True)

add("</svg>")
open(sys.argv[1], "w", encoding="utf-8").write("\n".join(out))
