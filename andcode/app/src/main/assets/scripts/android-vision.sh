#!/bin/sh




set -e


if ! command -v adb >/dev/null 2>&1; then
    echo "android: installing adb once from the package mirrors (android-tools-adb)" >&2
    apk add --no-cache android-tools-adb >&2 || {
        echo "android: could not install android-tools-adb. Open the rescue terminal and run:" >&2
        echo "         apk add android-tools-adb" >&2
        exit 1
    }
fi
adb get-state >/dev/null 2>&1 || {
    echo "android: adb has no connected device. Enable wireless debugging and connect via OpenCode -> Settings -> Local runtime -> ADB." >&2
    exit 1
}
OUTDIR="${1:-/tmp/android-vision}"
mkdir -p "$OUTDIR"
SHOT="$OUTDIR/screenshot.png"
XML="$OUTDIR/ui.xml"
ANNOTATED="$OUTDIR/annotated.png"


adb shell screencap -p /sdcard/_oc_shot.png 2>/dev/null
adb pull /sdcard/_oc_shot.png "$SHOT" >/dev/null 2>&1
adb shell rm /sdcard/_oc_shot.png >/dev/null 2>&1

adb shell uiautomator dump /sdcard/_oc_ui.xml 2>/dev/null
adb pull /sdcard/_oc_ui.xml "$XML" >/dev/null 2>&1
adb shell rm /sdcard/_oc_ui.xml >/dev/null 2>&1









if ! command -v python3 >/dev/null 2>&1 || ! python3 -c "import PIL" >/dev/null 2>&1; then
    echo "android: installing the image annotator once (python3, py3-pillow) from the package mirrors" >&2
    apk add --no-cache python3 py3-pillow >&2 || {
        echo "android: could not install python3/py3-pillow. Open the rescue terminal and run:" >&2
        echo "         apk add python3 py3-pillow" >&2
        echo "         then run android-vision again." >&2
        exit 1
    }
fi


exec python3 - "$SHOT" "$XML" "$ANNOTATED" <<'PYEOF'
import sys, json, re, xml.etree.ElementTree as ET
from PIL import Image, ImageDraw, ImageFont

shot_path, xml_path, annotated_path = sys.argv[1], sys.argv[2], sys.argv[3]

img = Image.open(shot_path)
draw = ImageDraw.Draw(img)
W, H = img.size
try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", max(14, W // 40))
except Exception:
    font = ImageFont.load_default()

elements = []
for node in ET.parse(xml_path).iter("node"):
    bounds_str = node.get("bounds", "")
    clickable = node.get("clickable", "false") == "true"
    focusable = node.get("focusable", "false") == "true"
    scrollable = node.get("scrollable", "false") == "true"
    if not (clickable or focusable or scrollable):
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str)
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    if x2 <= x1 or y2 <= y1 or x1 < 0 or y1 < 0 or x2 > W or y2 > H:
        continue
    idx = len(elements) + 1
    elements.append({
        "label": idx,
        "bounds": [x1, y1, x2, y2],
        "center": [cx, cy],
        "class": node.get("class", ""),
        "text": node.get("text", ""),
        "resource_id": node.get("resource-id", ""),
        "content_desc": node.get("content-desc", ""),
        "clickable": clickable,
        "focusable": focusable,
        "scrollable": scrollable,
    })
    draw.rectangle([x1, y1, x2, y2], outline="red", width=max(2, W // 400))
    label = str(idx)
    bbox = draw.textbbox((cx, cy), label, font=font)
    lw, lh = bbox[2] - bbox[0] + 8, bbox[3] - bbox[1] + 4
    draw.rectangle([cx - lw//2, cy - lh//2, cx + lw//2, cy + lh//2], fill="red")
    draw.text((cx - lw//2 + 4, cy - lh//2 + 2), label, fill="white", font=font)

img.save(annotated_path)
print(json.dumps({"screenshot": shot_path, "annotated": annotated_path, "elements": elements, "width": W, "height": H}, indent=2))
PYEOF
