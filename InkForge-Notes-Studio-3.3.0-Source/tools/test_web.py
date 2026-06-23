#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import json
import pathlib
import re
import time
from typing import Any

from playwright.async_api import async_playwright


def inline_document(web: pathlib.Path) -> tuple[str, str, str, str, str, str]:
    html = (web / "index.html").read_text(encoding="utf-8")
    css = (web / "styles.css").read_text(encoding="utf-8")
    app = (web / "app.js").read_text(encoding="utf-8")
    recognition = (web / "recognition.js").read_text(encoding="utf-8")
    pdf_tools = (web / "pdf-tools.js").read_text(encoding="utf-8")
    upgrade = (web / "upgrade32.js").read_text(encoding="utf-8")
    native_bridge = (web / "native-bridge.js").read_text(encoding="utf-8")
    html = re.sub(r'<link[^>]+rel="manifest"[^>]*>', '', html)
    html = re.sub(r'<link[^>]+rel="stylesheet"[^>]*>', f'<style>{css}</style>', html)
    html = re.sub(r'<script\s+src="(?:app|recognition|pdf-tools|upgrade32|native-bridge)\.js"></script>', '', html)
    return html, app, recognition, pdf_tools, upgrade, native_bridge


STORAGE_SHIM = r"""
Object.defineProperty(window,'indexedDB',{value:undefined,configurable:true});
const __inkforgeLocalStorage={};
Object.defineProperty(window,'localStorage',{value:{
  getItem(k){return Object.prototype.hasOwnProperty.call(__inkforgeLocalStorage,k)?__inkforgeLocalStorage[k]:null},
  setItem(k,v){__inkforgeLocalStorage[k]=String(v)},
  removeItem(k){delete __inkforgeLocalStorage[k]},
  clear(){for(const k of Object.keys(__inkforgeLocalStorage))delete __inkforgeLocalStorage[k]},
  key(i){return Object.keys(__inkforgeLocalStorage)[i]||null},
  get length(){return Object.keys(__inkforgeLocalStorage).length}
},configurable:true});
if (!navigator.clipboard) Object.defineProperty(navigator,'clipboard',{value:{writeText:async()=>{}},configurable:true});
"""


async def install(page, html: str, app: str, recognition: str, pdf_tools: str, upgrade: str, native_bridge: str) -> None:
    await page.set_content(html, wait_until="domcontentloaded")
    await page.evaluate(STORAGE_SHIM)
    await page.add_script_tag(content=app)
    await page.wait_for_function("window.__inkforge?.ready === true", timeout=30_000)
    await page.add_script_tag(content=recognition)
    await page.wait_for_function("window.__inkforgeRecognitionReady === true", timeout=30_000)
    await page.add_script_tag(content=pdf_tools)
    await page.wait_for_function("window.__inkforgePdf?.ready === true", timeout=30_000)
    await page.add_script_tag(content=upgrade)
    await page.wait_for_function("window.__inkforge32?.ready === true", timeout=30_000)
    await page.add_script_tag(content=native_bridge)
    await page.wait_for_function("window.__inkforgeNativeBridge?.ready === true", timeout=30_000)


MATH_STROKES = [
    [{"x": 35, "y": 40, "p": .5, "t": 0}, {"x": 35, "y": 145, "p": .5, "t": 100}],
    [{"x": 88, "y": 70, "p": .5, "t": 0}, {"x": 88, "y": 130, "p": .5, "t": 80}],
    [{"x": 62, "y": 100, "p": .5, "t": 0}, {"x": 114, "y": 100, "p": .5, "t": 70}],
    [{"x": 145, "y": 62, "p": .5, "t": 0}, {"x": 155, "y": 44, "p": .5, "t": 20},
     {"x": 180, "y": 37, "p": .5, "t": 40}, {"x": 205, "y": 50, "p": .5, "t": 60},
     {"x": 201, "y": 70, "p": .5, "t": 80}, {"x": 143, "y": 145, "p": .5, "t": 140},
     {"x": 210, "y": 145, "p": .5, "t": 180}],
]

OCR_STROKES = [
    [{"x": 20, "y": 20, "p": .5}, {"x": 20, "y": 100, "p": .5}],
    [{"x": 70, "y": 35, "p": .5}, {"x": 80, "y": 18, "p": .5}, {"x": 105, "y": 12, "p": .5},
     {"x": 125, "y": 25, "p": .5}, {"x": 122, "y": 43, "p": .5}, {"x": 70, "y": 100, "p": .5},
     {"x": 130, "y": 100, "p": .5}],
]


def circle_points(cx=200, cy=200, rx=100, ry=100, count=72):
    import math
    return [{"x": cx + math.cos(i * math.tau / count) * rx, "y": cy + math.sin(i * math.tau / count) * ry} for i in range(count + 1)]


def polygon_points(vertices, samples=10):
    output = []
    for index, start in enumerate(vertices):
        end = vertices[(index + 1) % len(vertices)]
        for step in range(samples):
            t = step / samples
            output.append({"x": start[0] + (end[0] - start[0]) * t, "y": start[1] + (end[1] - start[1]) * t})
    output.append({"x": vertices[0][0], "y": vertices[0][1]})
    return output


async def overlap_metrics(page) -> dict[str, Any]:
    return await page.evaluate("""
      () => {
        window.__inkforge32.resolveToolbarCollisions();
        const dock=document.getElementById('activeToolDock').getBoundingClientRect();
        const undo=document.getElementById('undoPill').getBoundingClientRect();
        const overlaps = dock.left < undo.right + 4 && dock.right + 4 > undo.left && dock.top < undo.bottom + 4 && dock.bottom + 4 > undo.top;
        return {overlaps, dock:{x:dock.x,y:dock.y,w:dock.width,h:dock.height}, undo:{x:undo.x,y:undo.y,w:undo.width,h:undo.height}, scrollWidth:document.documentElement.scrollWidth, clientWidth:document.documentElement.clientWidth};
      }
    """)


async def run(args: argparse.Namespace) -> dict[str, Any]:
    web = args.web.resolve()
    html, app, recognition, pdf_tools, upgrade, native_bridge = inline_document(web)
    errors: list[str] = []
    dialogs: list[str] = []
    results: dict[str, Any] = {}

    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True, executable_path=args.chromium, args=["--no-sandbox", "--disable-dev-shm-usage"])
        context = await browser.new_context(viewport={"width": 1365, "height": 900}, device_scale_factor=1)
        page = await context.new_page()
        page.on("pageerror", lambda error: errors.append(f"pageerror: {error}"))
        page.on("console", lambda message: errors.append(f"console {message.type}: {message.text}") if message.type == "error" else None)
        page.on("dialog", lambda dialog: (dialogs.append(dialog.message), asyncio.create_task(dialog.dismiss())))
        await install(page, html, app, recognition, pdf_tools, upgrade, native_bridge)

        results["version"] = await page.evaluate("window.__inkforge.VERSION")
        results["upgrade_version"] = await page.evaluate("window.__inkforge32.VERSION")
        results["seed_documents"] = await page.evaluate("window.__inkforge.state.documents.length")
        results["old_math_ui_removed"] = {
            "toolbar_count": await page.locator('[data-action="open-math"]').count(),
            "legacy_hidden": await page.locator('#mathSheet').evaluate("node => getComputedStyle(node).display === 'none'"),
            "passed": await page.locator('[data-action="open-math"]').count() == 0 and await page.locator('#mathSheet').evaluate("node => getComputedStyle(node).display === 'none'"),
        }
        results["ocr_toolbar"] = await page.locator("#ocrToolbarButton").count() == 1
        results["pdf_tools_ready"] = await page.evaluate("window.__inkforgePdf.ready")
        if args.screenshots:
            args.screenshots.mkdir(parents=True, exist_ok=True)
            await page.screenshot(path=str(args.screenshots / "inkforge-3.2-library.png"), full_page=True)

        await page.evaluate("window.__inkforge.openDocument(window.__inkforge.state.documents[0].id)")
        await page.wait_for_timeout(350)
        await page.evaluate("window.__inkforge.setTool('pen')")
        await page.wait_for_timeout(150)
        results["editor_visible"] = await page.locator("#editorView").is_visible()
        results["desktop_toolbar_collision"] = await overlap_metrics(page)
        results["desktop_toolbar_collision"]["passed"] = not results["desktop_toolbar_collision"]["overlaps"]

        before_pages = await page.evaluate("window.__inkforge.currentDocument().pages.length")
        await page.evaluate("window.__inkforge.addPage()")
        after_pages = await page.evaluate("window.__inkforge.currentDocument().pages.length")
        results["page_add"] = {"before": before_pages, "after": after_pages, "passed": after_pages == before_pages + 1}
        await page.evaluate("window.__inkforge.setZoom(1.5)")
        results["zoom"] = await page.evaluate("window.__inkforge.state.zoom")
        results["math_engine"] = await page.evaluate("window.__inkforge.evaluateMath('(12+8)*3',{}).result")
        results["auto_math_default_off"] = await page.evaluate("window.__inkforge.state.settings.autoMath === false")
        results["page_scroll_rail"] = await page.evaluate("""
          () => {
            const api = window.__inkforge;
            while (api.currentDocument().pages.length < 3) api.addPage();
            const rail = document.getElementById('pageScrollRail');
            const input = document.getElementById('pageJumpInput');
            input.value = '2';
            document.querySelector('[data-action="go-page-number"]').click();
            return {
              exists: !!rail,
              visible: rail && getComputedStyle(rail).display !== 'none',
              value: input.value,
              pageIndex: api.state.currentPageIndex
            };
          }
        """)
        results["page_scroll_rail"]["passed"] = bool(results["page_scroll_rail"].get("exists") and results["page_scroll_rail"].get("visible") and results["page_scroll_rail"].get("value") == "2" and results["page_scroll_rail"].get("pageIndex") == 1)
        await page.wait_for_timeout(700)
        results["zoom_render_scale"] = await page.evaluate("""
          () => {
            const api = window.__inkforge;
            api.setZoom(2.25);
            api.renderPageCanvas(api.state.currentPageIndex);
            const canvas = document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`);
            return {
              cssWidth: canvas.clientWidth,
              backingWidth: canvas.width,
              zoom: api.state.zoom,
              passed: canvas.width >= canvas.clientWidth && canvas.width >= 1000
            };
          }
        """)

        # Automatic handwritten equation recognition from normal page strokes.
        auto_math_result = await page.evaluate("""
          async (strokes) => {
            const api=window.__inkforge;
            const page=api.currentPage();
            const objs=strokes.map((points,index)=>({id:`test_math_${index}`,type:'stroke',brush:'fountain',color:'#111827',width:4,opacity:1,points}));
            page.objects.push(...objs);
            api.renderPageCanvas(api.state.currentPageIndex);
            const recognized=await api.recognition.recognizeMathStrokes(strokes);
            const inserted=await window.__inkforge32.processMathStrokes(objs,api.state.currentPageIndex);
            const math=[...page.objects].reverse().find(o=>o.type==='math'&&o.auto);
            return {recognized:recognized.expression,alternatives:recognized.alternatives,inserted,expression:math?.expression,result:math?.result,showExpression:math?.showExpression,sourceCount:math?.sourceStrokeIds?.length||0};
          }
        """, MATH_STROKES)
        auto_math_result["passed"] = bool(auto_math_result.get("inserted") and auto_math_result.get("expression") == "1+2" and str(auto_math_result.get("result")) == "3" and auto_math_result.get("showExpression") is True)
        results["automatic_handwritten_math"] = auto_math_result
        manual_math_result = await page.evaluate("""
          async (strokes) => {
            const api=window.__inkforge;
            const page=api.currentPage();
            page.objects = page.objects.filter(object => !object.id.startsWith('test_math_') && !object.id.startsWith('manual_math_') && object.type !== 'math');
            const objs=strokes.map((points,index)=>({id:`manual_math_${index}`,type:'stroke',brush:'fountain',color:'#111827',width:4,opacity:1,points}));
            page.objects.push(...objs);
            const inserted=await window.__inkforge32.processCurrentPageMath();
            const math=[...page.objects].reverse().find(o=>o.type==='math'&&o.auto);
            return {inserted, expression: math?.expression, result: math?.result, showExpression: math?.showExpression};
          }
        """, MATH_STROKES)
        manual_math_result["passed"] = bool(manual_math_result.get("inserted") and manual_math_result.get("showExpression") is True and str(manual_math_result.get("result")) == "3")
        results["manual_handwritten_math"] = manual_math_result

        # OCR and search indexing remain available.
        start = time.monotonic()
        ocr_result = await page.evaluate("strokes => window.__inkforge.recognition.recognizeTextStrokes(strokes,'en')", OCR_STROKES)
        results["handwriting_ocr"] = {
            "text": ocr_result["text"],
            "confidence": round(ocr_result["confidence"], 4),
            "seconds": round(time.monotonic() - start, 3),
            "passed": ocr_result["text"].replace(" ", "") == "12",
        }
        hangul = await page.evaluate("async()=>{const x=await window.__inkforge.recognition.ensureHangulIndex(()=>{});return {count:x.count,featureLength:x.features.length}}")
        results["hangul_index"] = {**hangul, "passed": hangul["count"] == 11172}
        await page.evaluate("document.querySelectorAll('.modal').forEach(node=>node.hidden=true); document.getElementById('modalBackdrop').hidden=true")
        await page.locator("#ocrToolbarButton").click()
        await page.locator("#ocrResultText").fill("손글씨 OCR 검색 검증")
        await page.locator('[data-action="save-ocr-index"]').click()
        await page.wait_for_timeout(150)
        await page.evaluate("window.__inkforge.state.searchOpen=true; document.getElementById('documentSearchInput').value='OCR'; window.__inkforge.renderDocumentSearch()")
        search_matches = await page.locator("#documentSearchResults .search-result").count()
        results["ocr_search_index"] = {"matches": search_matches, "passed": search_matches > 0}
        await page.evaluate("document.querySelectorAll('.modal').forEach(node=>node.hidden=true); document.getElementById('modalBackdrop').hidden=true")

        # Color mixer: visual palette only, no prompt/dialog.
        await page.evaluate("window.__inkforge.setTool('pen')")
        await page.locator('[data-action="custom-color"]').first.click()
        palette_count = await page.locator("#colorPaletteTable .palette-cell").count()
        await page.locator("#colorPaletteTable .palette-cell").nth(8).click()
        await page.locator('[data-action32="apply-color-mixer"]').click()
        chosen_color = await page.evaluate("window.__inkforge.state.color")
        results["visual_color_mixer"] = {"palette_count": palette_count, "chosen_color": chosen_color, "dialogs": list(dialogs), "passed": palette_count >= 30 and not dialogs and chosen_color.startswith('#')}

        # Unrevealed tape must fully overwrite the pixel beneath it.
        tape_pixel = await page.evaluate("""
          () => {
            const api=window.__inkforge, page=api.currentPage();
            page.objects.push({id:'under_tape',type:'shape',shape:'rectangle',x1:100,y1:100,x2:360,y2:200,color:'#ff0000',fill:'#ff0000',width:1});
            page.objects.push({id:'opaque_tape',type:'tape',x1:110,y1:110,x2:350,y2:190,color:'#4c91dd',revealed:false});
            api.renderPageCanvas(api.state.currentPageIndex);
            const canvas=document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`);
            const scale=canvas.width/1000;
            const d=canvas.getContext('2d').getImageData(Math.round(128*scale),Math.round(128*scale),1,1).data;
            return Array.from(d);
          }
        """)
        results["opaque_study_tape"] = {"pixel": tape_pixel, "expected": [76, 145, 221, 255], "passed": tape_pixel[3] == 255 and abs(tape_pixel[0]-76) <= 3 and abs(tape_pixel[1]-145) <= 3 and abs(tape_pixel[2]-221) <= 3}

        # Geometry recognition and manual shape breadth.
        shapes = {
            "line": [{"x": 20 + i * 5, "y": 20 + i * 2} for i in range(30)],
            "circle": circle_points(),
            "ellipse": circle_points(rx=130, ry=70),
            "triangle": polygon_points([(200,80),(80,300),(330,300)]),
            "rectangle": polygon_points([(70,90),(350,90),(350,260),(70,260)]),
            "pentagon": polygon_points([(210,60),(350,160),(300,320),(120,320),(70,160)]),
            "hexagon": polygon_points([(120,70),(300,70),(380,200),(300,330),(120,330),(40,200)]),
        }
        recognized_shapes = await page.evaluate("""
          shapes => Object.fromEntries(Object.entries(shapes).map(([key,points])=>[key,window.__inkforge.maybeShapeFromStroke(points,1100,650)?.shape||null]))
        """, shapes)
        await page.evaluate("window.__inkforge.setTool('shape')")
        shape_button_count = await page.locator('#activeToolMenu [data-shape]').count()
        expected_ok = recognized_shapes.get("line") == "line" and recognized_shapes.get("circle") in ("circle", "ellipse") and recognized_shapes.get("ellipse") == "ellipse" and recognized_shapes.get("triangle") == "triangle" and recognized_shapes.get("rectangle") in ("rectangle", "square") and recognized_shapes.get("pentagon") == "pentagon" and recognized_shapes.get("hexagon") == "hexagon"
        results["shape_recognition"] = {"recognized": recognized_shapes, "manual_shape_buttons": shape_button_count, "passed": expected_ok and shape_button_count >= 16}

        # S Pen gesture mapping (bridge path and on-screen barrel gesture share actions).
        spen = await page.evaluate("""
          () => {
            const api=window.__inkforge;
            const start=api.state.currentPageIndex;
            window.__inkforge32.handleAirAction('left');
            const afterLeft=api.state.currentPageIndex;
            window.__inkforge32.handleAirAction('right');
            const afterRight=api.state.currentPageIndex;
            api.setTool('pen');
            window.__inkforge32.handleAirAction('click');
            const afterClick=api.state.tool;
            window.__inkforge32.handleAirAction('doubleClick');
            return {start,afterLeft,afterRight,afterClick,afterDouble:api.state.tool,bridge:typeof window.InkForgeSPenBridge?.onAirAction==='function'};
          }
        """)
        spen["passed"] = spen["bridge"] and spen["afterClick"] == "eraser" and spen["afterDouble"] == "lasso"
        results["s_pen_gestures"] = spen

        if args.screenshots:
            await page.evaluate("window.__inkforge.setTool('pen')")
            await page.wait_for_timeout(100)
            await page.screenshot(path=str(args.screenshots / "inkforge-3.2-editor.png"), full_page=True)
            await page.locator('[data-action="custom-color"]').first.click()
            await page.wait_for_timeout(80)
            await page.screenshot(path=str(args.screenshots / "inkforge-3.2-color-mixer.png"), full_page=True)
            await page.evaluate("document.querySelectorAll('.modal').forEach(node=>node.hidden=true);document.getElementById('modalBackdrop').hidden=true")

        # Very wide/short landscape reproduces the reported collision scenario.
        await page.set_viewport_size({"width": 1536, "height": 425})
        await page.evaluate("window.__inkforge.setTool('pen')")
        await page.wait_for_timeout(150)
        results["landscape_toolbar_collision"] = await overlap_metrics(page)
        results["landscape_toolbar_collision"]["passed"] = not results["landscape_toolbar_collision"]["overlaps"] and results["landscape_toolbar_collision"]["scrollWidth"] == results["landscape_toolbar_collision"]["clientWidth"]
        if args.screenshots:
            await page.screenshot(path=str(args.screenshots / "inkforge-3.2-landscape.png"), full_page=True)

        await context.close()
        mobile_context = await browser.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=1)
        mobile = await mobile_context.new_page()
        mobile.on("pageerror", lambda error: errors.append(f"mobile pageerror: {error}"))
        mobile.on("console", lambda message: errors.append(f"mobile console {message.type}: {message.text}") if message.type == "error" else None)
        await install(mobile, html, app, recognition, pdf_tools, upgrade, native_bridge)
        await mobile.evaluate("window.__inkforge.openDocument(window.__inkforge.state.documents[0].id);window.__inkforge.setTool('pen')")
        await mobile.wait_for_timeout(250)
        mobile_metrics = await overlap_metrics(mobile)
        mobile_metrics["ocr_visible"] = await mobile.locator("#ocrToolbarButton").is_visible()
        mobile_metrics["passed"] = mobile_metrics["scrollWidth"] == mobile_metrics["clientWidth"] and not mobile_metrics["overlaps"] and mobile_metrics["ocr_visible"]
        results["mobile"] = mobile_metrics
        if args.screenshots:
            await mobile.screenshot(path=str(args.screenshots / "inkforge-3.2-mobile-editor.png"), full_page=True)
        await mobile_context.close()
        await browser.close()

    results["dialogs"] = dialogs
    results["console_errors"] = errors
    required_scalars = results.get("version") == "3.3.4" and results.get("upgrade_version") == "3.3.4" and results.get("math_engine") == 60 and results.get("editor_visible") is True and results.get("ocr_toolbar") is True and results.get("pdf_tools_ready") is True and results.get("auto_math_default_off") is True
    results["passed"] = required_scalars and not errors and not dialogs and all(value.get("passed", True) if isinstance(value, dict) else True for key, value in results.items() if key not in {"console_errors", "dialogs"})
    return results


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--web", type=pathlib.Path, required=True)
    parser.add_argument("--chromium", default="/usr/bin/chromium")
    parser.add_argument("--screenshots", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    result = asyncio.run(run(args))
    payload = json.dumps(result, ensure_ascii=False, indent=2)
    print(payload)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload + "\n", encoding="utf-8")
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
