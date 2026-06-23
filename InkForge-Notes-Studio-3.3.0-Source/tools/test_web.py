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
        seed_count = await page.evaluate("window.__inkforge.state.documents.length")
        results["seed_documents"] = {"count": seed_count, "passed": seed_count == 0}
        results["old_math_ui_removed"] = {
            "toolbar_count": await page.locator('[data-action="open-math"]').count(),
            "legacy_hidden": await page.locator('#mathSheet').evaluate("node => getComputedStyle(node).display === 'none'"),
            "passed": await page.locator('[data-action="open-math"]').count() == 0 and await page.locator('#mathSheet').evaluate("node => getComputedStyle(node).display === 'none'"),
        }
        results["ocr_toolbar"] = await page.locator("#ocrToolbarButton").count() == 1
        results["pdf_tools_ready"] = await page.evaluate("window.__inkforgePdf.ready")
        update_ui = await page.evaluate("""
          () => {
            const bridge = window.__inkforgeNativeBridge;
            bridge.applyUpdateState({
              status: 'available',
              currentVersion: '3.3.7',
              version: '9.9.0',
              variant: '업데이트용',
              assetSize: 10485760,
              body: '- 자동 업데이트\\n- 진행률 표시'
            });
            const sheet = document.getElementById('nativeUpdateSheet');
            const availableVisible = sheet && !sheet.hidden && sheet.dataset.status === 'available' && sheet.textContent.includes('9.9.0');
            bridge.applyUpdateState({
              status: 'downloading',
              progress: 42,
              bytesDownloaded: 420,
              totalBytes: 1000
            });
            const progress = document.getElementById('nativeUpdateProgressBar')?.getAttribute('aria-valuenow');
            const progressWidth = document.getElementById('nativeUpdateProgressFill')?.style.width;
            document.querySelectorAll('.modal').forEach(node => node.hidden = true);
            document.getElementById('modalBackdrop').hidden = true;
            localStorage.removeItem('badnote.releaseNotes.seen.3.3.16');
            localStorage.removeItem('badnote.releaseNotes.lastVersion');
            const first = bridge.showReleaseNotesOnce();
            const notesVisible = !document.getElementById('nativeUpdateSheet').hidden && document.getElementById('nativeUpdateSheet').dataset.status === 'release-notes';
            document.querySelector('[data-update-action="ack-notes"]').click();
            const second = bridge.showReleaseNotesOnce();
            document.querySelectorAll('.modal').forEach(node => node.hidden = true);
            document.getElementById('modalBackdrop').hidden = true;
            return {
              availableVisible,
              progress,
              progressWidth,
              notesVisible,
              first,
              second,
              passed: availableVisible && progress === '42' && progressWidth === '42%' && notesVisible && first === true && second === false
            };
          }
        """)
        results["native_update_ui"] = update_ui
        results["folder_creation"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const before = api.state.folders.length;
            const folder = await api.createFolder('수학 풀이');
            const stored = await api.storage.getSetting('folders', []);
            const doc = api.createDocument('폴더 테스트 노트', 'grid');
            doc.folderId = folder.id;
            api.state.documents.push(doc);
            await api.storage.putDocument(doc);
            api.renderLibrary();
            const breadcrumb = document.getElementById('folderBreadcrumb')?.textContent || '';
            const folderVisible = Array.from(document.querySelectorAll('[data-doc-id]')).some(node => node.dataset.docId === doc.id);
            const createButtons = document.querySelectorAll('[data-action="create-folder"]').length;
            api.state.folderId = 'root';
            api.renderLibrary();
            return {
              before,
              after: api.state.folders.length,
              storedCount: Array.isArray(stored) ? stored.length : 0,
              folderId: folder.id,
              breadcrumb,
              folderVisible,
              createButtons,
              passed: api.state.folders.length === before + 1 && stored.some(item => item.id === folder.id) && breadcrumb.includes('수학 풀이') && folderVisible && createButtons >= 1
            };
          }
        """)
        if args.screenshots:
            args.screenshots.mkdir(parents=True, exist_ok=True)
            await page.screenshot(path=str(args.screenshots / "inkforge-3.2-library.png"), full_page=True)

        await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const doc = api.createDocument('테스트 노트', 'grid');
            api.state.documents.push(doc);
            await api.storage.putDocument(doc);
            api.openDocument(doc.id);
          }
        """)
        await page.wait_for_timeout(350)
        await page.evaluate("window.__inkforge.setTool('pen')")
        await page.wait_for_timeout(150)
        results["editor_visible"] = await page.locator("#editorView").is_visible()
        results["desktop_toolbar_collision"] = await overlap_metrics(page)
        results["desktop_toolbar_collision"]["passed"] = not results["desktop_toolbar_collision"]["overlaps"]
        results["screen_pixel_width_slider"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const page = api.currentPage();
            api.setTool('pen');
            api.setZoom(1);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const input = document.querySelector('#activeToolMenu [data-width-input][data-width-tool="pen"]');
            if (!input) return { passed: false, reason: 'missing slider' };
            input.value = '12';
            input.dispatchEvent(new Event('input', { bubbles: true }));
            input.dispatchEvent(new Event('change', { bubbles: true }));
            const labelText = input.closest('.width-slider')?.textContent || '';
            const drawStroke = async (pointerId, y) => {
              const canvas = document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`);
              const rect = canvas.getBoundingClientRect();
              const clientFor = (x, pageY) => ({ x: rect.left + x / 1000 * rect.width, y: rect.top + pageY / 1414 * rect.height });
              const send = (type, point) => canvas.dispatchEvent(new PointerEvent(type, {
                bubbles: true,
                cancelable: true,
                pointerId,
                pointerType: 'pen',
                isPrimary: true,
                button: 0,
                buttons: type === 'pointerup' ? 0 : 1,
                pressure: type === 'pointerup' ? 0 : .55,
                clientX: point.x,
                clientY: point.y
              }));
              send('pointerdown', clientFor(220, y));
              send('pointermove', clientFor(300, y));
              send('pointerup', clientFor(300, y));
              await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
              const object = page.objects[page.objects.length - 1];
              const nextRect = document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`).getBoundingClientRect();
              return { width: object.width, screenWidth: object.width * nextRect.width / 1000, storedScreenWidth: object.screenWidth, rectWidth: nextRect.width };
            };
            const first = await drawStroke(7711, 230);
            const anchorCanvas = document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`);
            const anchorRect = anchorCanvas.getBoundingClientRect();
            api.setZoom(3, { clientX: anchorRect.left + anchorRect.width / 2, clientY: anchorRect.top + anchorRect.height / 2 });
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const second = await drawStroke(7712, 280);
            return {
              stateWidth: api.state.width,
              labelText,
              first,
              second,
              passed: Math.abs(api.state.width - 12) < .01 &&
                labelText.includes('12') &&
                Math.abs(first.screenWidth - 12) < 1.4 &&
                Math.abs(second.screenWidth - 12) < 1.4 &&
                second.width < first.width * .55 &&
                first.storedScreenWidth === 12 &&
                second.storedScreenWidth === 12
            };
          }
        """)

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
        results["last_page_restore"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const doc = api.currentDocument();
            while (doc.pages.length < 4) api.addPage();
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            api.scrollToPage(3, false);
            await new Promise(resolve => setTimeout(resolve, 360));
            const stored = (await api.storage.allDocuments()).find(item => item.id === doc.id);
            api.state.currentPageIndex = 0;
            api.openDocument(doc.id);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            return {
              storedIndex: stored?.lastPageIndex,
              storedPageId: stored?.lastPageId,
              openedIndex: api.state.currentPageIndex,
              passed: stored?.lastPageIndex === 3 && !!stored?.lastPageId && api.state.currentPageIndex === 3
            };
          }
        """)
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
        results["zoom_anchor_stability"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const doc = api.currentDocument();
            while (doc.pages.length < 4) doc.pages.push(api.blankPage('grid'));
            api.renderEditorPages();
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            api.scrollToPage(2, false);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const viewport = document.getElementById('editorViewport');
            const wrap = document.querySelector('.page-wrap[data-page-index="2"]');
            const ratio = { x: .62, y: .54 };
            const clientForRatio = () => {
              const rect = wrap.getBoundingClientRect();
              return { clientX: rect.left + rect.width * ratio.x, clientY: rect.top + rect.height * ratio.y };
            };
            const driftFrom = (client) => {
              const rect = wrap.getBoundingClientRect();
              return {
                dx: rect.left + rect.width * ratio.x - client.clientX,
                dy: rect.top + rect.height * ratio.y - client.clientY
              };
            };
            const anchor = clientForRatio();
            api.setZoom(2.4, anchor);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const zoomIn = driftFrom(anchor);
            api.setZoom(1.1, anchor);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const zoomOut = driftFrom(anchor);
            const maxVerticalDrift = Math.max(Math.abs(zoomIn.dy), Math.abs(zoomOut.dy));
            const maxHorizontalDrift = Math.max(Math.abs(zoomIn.dx), Math.abs(zoomOut.dx));
            api.setZoom(1.5, anchor);
            return {
              pageIndex: api.state.currentPageIndex,
              scrollTop: viewport.scrollTop,
              zoomIn,
              zoomOut,
              maxVerticalDrift,
              maxHorizontalDrift,
              passed: api.state.currentPageIndex === 2 && maxVerticalDrift <= 3 && Math.abs(zoomIn.dx) <= 3
            };
          }
        """)
        results["zoom_drag_lock"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            api.setTool('hand');
            const viewport = document.getElementById('editorViewport');
            const canvas = document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`);
            const point = () => {
              const rect = canvas.getBoundingClientRect();
              return { x: rect.left + rect.width * .5, y: rect.top + rect.height * .52 };
            };
            const send = (type, pointerId, x, y) => canvas.dispatchEvent(new PointerEvent(type, {
              bubbles: true,
              cancelable: true,
              pointerId,
              pointerType: 'mouse',
              isPrimary: true,
              button: 0,
              buttons: type === 'pointerup' ? 0 : 1,
              clientX: x,
              clientY: y
            }));
            const anchor = point();
            api.setZoom(api.state.zoom * 1.04, { clientX: anchor.x, clientY: anchor.y });
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const start = viewport.scrollTop;
            const locked = api.state.zoomDragLockUntil - performance.now();
            const lockedPoint = point();
            send('pointerdown', 9001, lockedPoint.x, lockedPoint.y);
            send('pointermove', 9001, lockedPoint.x, lockedPoint.y + 120);
            send('pointerup', 9001, lockedPoint.x, lockedPoint.y + 120);
            await new Promise(resolve => requestAnimationFrame(resolve));
            const afterLockedDrag = viewport.scrollTop;
            api.state.zoomDragLockUntil = 0;
            const freePoint = point();
            send('pointerdown', 9002, freePoint.x, freePoint.y);
            send('pointermove', 9002, freePoint.x, freePoint.y + 120);
            send('pointerup', 9002, freePoint.x, freePoint.y + 120);
            await new Promise(resolve => requestAnimationFrame(resolve));
            const afterUnlockedDrag = viewport.scrollTop;
            const lockedDelta = Math.abs(afterLockedDrag - start);
            const unlockedDelta = Math.abs(afterUnlockedDrag - afterLockedDrag);
            return {
              lockedMsRemaining: locked,
              start,
              afterLockedDrag,
              afterUnlockedDrag,
              lockedDelta,
              unlockedDelta,
              passed: locked > 650 && lockedDelta <= 2 && unlockedDelta >= 40
            };
          }
        """)
        results["zoom_horizontal_scroll"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const viewport = document.getElementById('editorViewport');
            const canvas = document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`);
            const viewportRect = viewport.getBoundingClientRect();
            const anchor = { clientX: viewportRect.left + viewport.clientWidth / 2, clientY: viewportRect.top + viewport.clientHeight / 2 };
            api.setZoom(3.2, anchor);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            api.state.zoomDragLockUntil = 0;
            const maxScrollLeft = viewport.scrollWidth - viewport.clientWidth;
            const send = (type, pointerId, pointerType, x, y) => canvas.dispatchEvent(new PointerEvent(type, {
              bubbles: true,
              cancelable: true,
              pointerId,
              pointerType,
              isPrimary: true,
              button: 0,
              buttons: type === 'pointerup' ? 0 : 1,
              pressure: pointerType === 'pen' ? .55 : .5,
              clientX: x,
              clientY: y
            }));
            const gesturePoint = () => {
              const rect = viewport.getBoundingClientRect();
              return { x: rect.left + rect.width * .52, y: rect.top + rect.height * .52 };
            };
            const startLeft = Math.max(0, Math.min(maxScrollLeft - 180, maxScrollLeft / 2));
            viewport.scrollLeft = startLeft;
            api.setTool('hand');
            let point = gesturePoint();
            send('pointerdown', 9811, 'mouse', point.x, point.y);
            send('pointermove', 9811, 'mouse', point.x - 140, point.y);
            send('pointerup', 9811, 'mouse', point.x - 140, point.y);
            await new Promise(resolve => requestAnimationFrame(resolve));
            const handDelta = viewport.scrollLeft - startLeft;
            viewport.scrollLeft = startLeft;
            api.setTool('pen');
            api.state.zoomDragLockUntil = 0;
            point = gesturePoint();
            send('pointerdown', 9812, 'touch', point.x, point.y);
            send('pointermove', 9812, 'touch', point.x - 140, point.y);
            send('pointerup', 9812, 'touch', point.x - 140, point.y);
            await new Promise(resolve => requestAnimationFrame(resolve));
            const touchDelta = viewport.scrollLeft - startLeft;
            return {
              maxScrollLeft,
              startLeft,
              handDelta,
              touchDelta,
              passed: maxScrollLeft > 180 && handDelta > 70 && touchDelta > 70
            };
          }
        """)
        results["pdf_import_quality_plan"] = await page.evaluate("""
          () => {
            const small = window.__inkforgePdf.planImportQuality(12, 8 * 1024 * 1024);
            const huge = window.__inkforgePdf.planImportQuality(220, 420 * 1024 * 1024);
            return {
              small,
              huge,
              passed: small.scale >= 2.35 &&
                small.width >= 2350 &&
                small.height >= 3300 &&
                small.jpegQuality >= .88 &&
                huge.scale <= 1.7 &&
                huge.pixels <= 9200000
            };
          }
        """)
        results["pdf_zoom_canvas_budget"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const doc = api.currentDocument();
            const pageIndex = api.state.currentPageIndex;
            const page = doc.pages[pageIndex];
            const source = document.createElement('canvas');
            source.width = 2400;
            source.height = 3394;
            const ctx = source.getContext('2d', { alpha: false });
            ctx.fillStyle = '#ffffff';
            ctx.fillRect(0, 0, source.width, source.height);
            ctx.fillStyle = '#e5edf7';
            for (let y = 190; y < source.height; y += 288) ctx.fillRect(190, y, 2020, 68);
            ctx.fillStyle = '#111827';
            ctx.font = '96px sans-serif';
            ctx.fillText('High resolution PDF sample', 190, 420);
            const blob = await new Promise(resolve => source.toBlob(resolve, 'image/jpeg', .9));
            const assetId = 'pdf_zoom_budget_asset';
            await api.storage.putAsset({ id: assetId, kind: 'pdf-page-jpeg', blob, width: 2400, height: 3394, qualityScale: 2.4, pageNumber: 1, createdAt: new Date().toISOString() });
            page.backgroundAssetId = assetId;
            page.importedFromPdf = true;
            page.backgroundQualityScale = 2.4;
            page.backgroundPixelWidth = 2400;
            page.backgroundPixelHeight = 3394;
            page.needsImageOcr = false;
            api.renderEditorPages();
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const viewport = document.getElementById('editorViewport');
            const rect = viewport.getBoundingClientRect();
            api.setZoom(3.0, { clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2 });
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            api.updateVirtualPages();
            api.renderPageCanvas(pageIndex);
            await new Promise(resolve => requestAnimationFrame(resolve));
            const canvas = document.querySelector(`.page-canvas[data-page-index="${pageIndex}"]`);
            const pixels = (canvas?.width || 0) * (canvas?.height || 0);
            const mounted = document.querySelectorAll('.page-canvas').length;
            api.setZoom(1.5, { clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2 });
            return {
              zoom: 3,
              backingWidth: canvas?.width || 0,
              backingHeight: canvas?.height || 0,
              clientWidth: canvas?.clientWidth || 0,
              pixels,
              mounted,
              imageCacheSize: api.state.imageCache.size,
              assetUrlCacheSize: api.state.assetUrlCache.size,
              passed: pixels > 0 && pixels <= 9500000 && (canvas?.width || 0) >= 2350 && mounted <= 2 && api.state.imageCache.size <= 3 && api.state.assetUrlCache.size <= 3
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
            "wobbly_rectangle": polygon_points([(72,92),(352,84),(365,250),(80,268)], samples=12),
            "pentagon": polygon_points([(210,60),(350,160),(300,320),(120,320),(70,160)]),
            "hexagon": polygon_points([(120,70),(300,70),(380,200),(300,330),(120,330),(40,200)]),
            "rough_pentagon": polygon_points([(210,60),(390,150),(285,300),(155,255),(70,155)]),
            "arrow": [{"x": 60, "y": 210}, {"x": 130, "y": 210}, {"x": 210, "y": 210}, {"x": 185, "y": 185}, {"x": 210, "y": 210}, {"x": 185, "y": 235}],
        }
        recognized_shapes = await page.evaluate("""
          shapes => Object.fromEntries(Object.entries(shapes).map(([key,points])=>[key,window.__inkforge.maybeShapeFromStroke(points,1100,650)?.shape||null]))
        """, shapes)
        await page.evaluate("window.__inkforge.setTool('shape')")
        shape_button_count = await page.locator('#activeToolMenu [data-shape]').count()
        curve_button_count = await page.locator('#activeToolMenu [data-shape="curve"]').count()
        expected_ok = recognized_shapes.get("line") == "line" and recognized_shapes.get("circle") in ("circle", "ellipse") and recognized_shapes.get("ellipse") == "ellipse" and recognized_shapes.get("triangle") == "triangle" and recognized_shapes.get("rectangle") in ("rectangle", "square") and recognized_shapes.get("wobbly_rectangle") in ("rectangle", "square") and recognized_shapes.get("pentagon") == "pentagon" and recognized_shapes.get("hexagon") == "hexagon" and recognized_shapes.get("rough_pentagon") != "pentagon" and recognized_shapes.get("arrow") != "arrow"
        results["shape_recognition"] = {"recognized": recognized_shapes, "manual_shape_buttons": shape_button_count, "curve_button_count": curve_button_count, "passed": expected_ok and shape_button_count >= 17 and curve_button_count == 1}

        results["zoom_screen_gesture_space"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const pageIndex = api.state.currentPageIndex;
            const page = api.currentPage();
            api.setTool('pen');
            api.state.settings.scribbleErase = true;
            api.state.settings.drawHold = true;
            page.objects = page.objects.filter(object => object.id !== 'zoom_scribble_target');
            page.objects.push({
              id: 'zoom_scribble_target',
              type: 'stroke',
              brush: 'fountain',
              color: '#111827',
              width: 8,
              opacity: 1,
              points: [
                { x: 380, y: 430, p: .6 },
                { x: 500, y: 430, p: .6 },
                { x: 620, y: 430, p: .6 }
              ]
            });
            const shapesBefore = page.objects.filter(object => object.type === 'shape').length;
            api.renderPageCanvas(pageIndex);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const canvas = document.querySelector(`.page-canvas[data-page-index="${pageIndex}"]`);
            const clientFor = (x, y) => {
              const rect = canvas.getBoundingClientRect();
              return { x: rect.left + x / 1000 * rect.width, y: rect.top + y / 1414 * rect.height };
            };
            const anchor = clientFor(500, 430);
            api.setZoom(3.8, { clientX: anchor.x, clientY: anchor.y });
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const zoomedCanvas = document.querySelector(`.page-canvas[data-page-index="${pageIndex}"]`);
            const center = (() => {
              const rect = zoomedCanvas.getBoundingClientRect();
              return { x: rect.left + 500 / 1000 * rect.width, y: rect.top + 430 / 1414 * rect.height };
            })();
            const send = (type, point) => zoomedCanvas.dispatchEvent(new PointerEvent(type, {
              bubbles: true,
              cancelable: true,
              pointerId: 8810,
              pointerType: 'pen',
              isPrimary: true,
              button: 0,
              buttons: type === 'pointerup' ? 0 : 1,
              pressure: type === 'pointerup' ? 0 : .55,
              clientX: point.x,
              clientY: point.y
            }));
            const path = Array.from({ length: 18 }, (_, index) => ({
              x: center.x - 126 + index * 14,
              y: center.y + (index % 2 ? -18 : 18)
            }));
            send('pointerdown', path[0]);
            path.slice(1).forEach(point => send('pointermove', point));
            send('pointerup', path[path.length - 1]);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const targetErased = !page.objects.some(object => object.id === 'zoom_scribble_target');
            const shapesAfter = page.objects.filter(object => object.type === 'shape').length;
            api.setZoom(1.5, { clientX: center.x, clientY: center.y });
            return { targetErased, shapesBefore, shapesAfter, zoom: api.state.zoom, passed: targetErased && shapesAfter === shapesBefore };
          }
        """)
        results["local_scribble_erase"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const pageIndex = api.state.currentPageIndex;
            const page = api.currentPage();
            api.setZoom(1);
            api.setTool('pen');
            api.state.settings.scribbleErase = true;
            page.objects = page.objects.filter(object => object.id !== 'local_scribble_target');
            page.objects.push({
              id: 'local_scribble_target',
              type: 'stroke',
              brush: 'fountain',
              color: '#111827',
              width: 8,
              opacity: 1,
              points: [
                { x: 500, y: 520, p: .6 },
                { x: 560, y: 520, p: .6 },
                { x: 620, y: 520, p: .6 }
              ]
            });
            const beforeCount = page.objects.length;
            api.renderPageCanvas(pageIndex);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const canvas = document.querySelector(`.page-canvas[data-page-index="${pageIndex}"]`);
            const clientFor = (x, y) => {
              const rect = canvas.getBoundingClientRect();
              return { x: rect.left + x / 1000 * rect.width, y: rect.top + y / 1414 * rect.height };
            };
            const send = (type, point) => canvas.dispatchEvent(new PointerEvent(type, {
              bubbles: true,
              cancelable: true,
              pointerId: 8811,
              pointerType: 'pen',
              isPrimary: true,
              button: 0,
              buttons: type === 'pointerup' ? 0 : 1,
              pressure: type === 'pointerup' ? 0 : .55,
              clientX: point.x,
              clientY: point.y
            }));
            const center = clientFor(560, 520);
            const path = Array.from({ length: 10 }, (_, index) => ({
              x: center.x - 16 + index * 3.6,
              y: center.y + (index % 2 ? -7 : 7)
            }));
            send('pointerdown', path[0]);
            path.slice(1).forEach(point => send('pointermove', point));
            send('pointerup', path[path.length - 1]);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const erased = !page.objects.some(object => object.id === 'local_scribble_target');
            return {
              erased,
              beforeCount,
              afterCount: page.objects.length,
              passed: erased && page.objects.length <= beforeCount - 1
            };
          }
        """)
        results["local_scribble_touch_only"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const pageIndex = api.state.currentPageIndex;
            const page = api.currentPage();
            api.setZoom(1);
            api.setTool('pen');
            api.state.settings.scribbleErase = true;
            page.objects = page.objects.filter(object => object.id !== 'micro_scribble_target' && object.id !== 'micro_scribble_line_guard' && object.id !== 'micro_scribble_close_guard');
            page.objects.push({
              id: 'micro_scribble_target',
              type: 'stroke',
              brush: 'fountain',
              color: '#111827',
              width: 8,
              opacity: 1,
              points: [
                { x: 510, y: 590, p: .6 },
                { x: 560, y: 590, p: .6 },
                { x: 610, y: 590, p: .6 }
              ]
            });
            page.objects.push({
              id: 'micro_scribble_line_guard',
              type: 'stroke',
              brush: 'fountain',
              color: '#111827',
              width: 8,
              opacity: 1,
              points: [
                { x: 510, y: 650, p: .6 },
                { x: 560, y: 650, p: .6 },
                { x: 610, y: 650, p: .6 }
              ]
            });
            page.objects.push({
              id: 'micro_scribble_close_guard',
              type: 'stroke',
              brush: 'fountain',
              color: '#111827',
              width: 8,
              opacity: 1,
              points: [
                { x: 510, y: 610, p: .6 },
                { x: 560, y: 610, p: .6 },
                { x: 610, y: 610, p: .6 }
              ]
            });
            api.renderPageCanvas(pageIndex);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const canvas = document.querySelector(`.page-canvas[data-page-index="${pageIndex}"]`);
            const clientFor = (x, y) => {
              const rect = canvas.getBoundingClientRect();
              return { x: rect.left + x / 1000 * rect.width, y: rect.top + y / 1414 * rect.height };
            };
            const sendPath = (pointerId, path) => {
              const send = (type, point) => canvas.dispatchEvent(new PointerEvent(type, {
                bubbles: true,
                cancelable: true,
                pointerId,
                pointerType: 'pen',
                isPrimary: true,
                button: 0,
                buttons: type === 'pointerup' ? 0 : 1,
                pressure: type === 'pointerup' ? 0 : .55,
                clientX: point.x,
                clientY: point.y
              }));
              send('pointerdown', path[0]);
              path.slice(1).forEach(point => send('pointermove', point));
              send('pointerup', path[path.length - 1]);
            };
            const c = clientFor(560, 590);
            const localPath = Array.from({ length: 10 }, (_, index) => ({
              x: c.x - 16 + index * 3.6,
              y: c.y + (index % 2 ? -7 : 7)
            }));
            sendPath(8812, localPath);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const erased = !page.objects.some(object => object.id === 'micro_scribble_target');
            const closeGuardStillPresent = page.objects.some(object => object.id === 'micro_scribble_close_guard');
            const guardCenter = clientFor(560, 650);
            const linePath = [
              { x: guardCenter.x - 14, y: guardCenter.y },
              { x: guardCenter.x - 6, y: guardCenter.y },
              { x: guardCenter.x + 2, y: guardCenter.y },
              { x: guardCenter.x + 10, y: guardCenter.y }
            ];
            sendPath(8813, linePath);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const guardStillPresent = page.objects.some(object => object.id === 'micro_scribble_line_guard');
            return {
              erased,
              closeGuardStillPresent,
              guardStillPresent,
              passed: erased && closeGuardStillPresent && guardStillPresent
            };
          }
        """)
        results["stylus_only_blocks_non_stylus_marks"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const pageIndex = api.state.currentPageIndex;
            const page = api.currentPage();
            api.setZoom(1);
            api.setTool('pen');
            api.state.settings.stylusOnly = true;
            page.objects = page.objects.filter(object => !String(object.id).startsWith('stylus_only_regression'));
            api.renderPageCanvas(pageIndex);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const canvas = document.querySelector(`.page-canvas[data-page-index="${pageIndex}"]`);
            const clientFor = (x, y) => {
              const rect = canvas.getBoundingClientRect();
              return { x: rect.left + x / 1000 * rect.width, y: rect.top + y / 1414 * rect.height };
            };
            const dispatchNative = (client) => window.dispatchEvent(new CustomEvent('inkforge:native-stylus', { detail: {
              action: 2,
              hover: false,
              toolType: 2,
              buttonState: 0,
              x: client.x,
              y: client.y,
              pressure: .52,
              device: 'Samsung S Pen'
            } }));
            const sendPath = (pointerId, pointerType, path) => {
              const send = (type, point) => canvas.dispatchEvent(new PointerEvent(type, {
                bubbles: true,
                cancelable: true,
                pointerId,
                pointerType,
                isPrimary: true,
                button: 0,
                buttons: type === 'pointerup' ? 0 : 1,
                pressure: type === 'pointerup' ? 0 : .55,
                clientX: point.x,
                clientY: point.y
              }));
              send('pointerdown', path[0]);
              path.slice(1).forEach(point => send('pointermove', point));
              send('pointerup', path[path.length - 1]);
            };
            const beforeCount = page.objects.length;
            const touchStart = clientFor(180, 740);
            dispatchNative(touchStart);
            sendPath(8815, 'touch', [
              touchStart,
              clientFor(220, 750),
              clientFor(260, 760)
            ]);
            sendPath(8816, 'mouse', [
              clientFor(180, 900),
              clientFor(220, 910),
              clientFor(260, 920)
            ]);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const afterCount = page.objects.length;
            const leakedStroke = page.objects.some(object => String(object.id).startsWith('stylus_only_regression'));
            return {
              beforeCount,
              afterCount,
              leakedStroke,
              activeSession: !!api.state.drawSession,
              passed: afterCount === beforeCount && !leakedStroke && !api.state.drawSession
            };
          }
        """)
        results["pencil_render_budget"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const pageIndex = api.state.currentPageIndex;
            const page = api.currentPage();
            api.setZoom(3.2);
            page.objects = page.objects.filter(object => object.id !== 'pencil_perf_stroke');
            page.objects.push({
              id: 'pencil_perf_stroke',
              type: 'stroke',
              brush: 'pencil',
              color: '#2d3340',
              width: 5.2,
              screenWidth: 5.2,
              opacity: .78,
              points: Array.from({ length: 1200 }, (_, index) => ({
                x: 80 + index * .7,
                y: 930 + Math.sin(index / 18) * 24,
                p: .35 + (index % 11) / 30,
                tx: index % 5,
                ty: index % 7,
                t: index
              }))
            });
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const startedAt = performance.now();
            for (let index = 0; index < 3; index++) api.renderPageCanvas(pageIndex);
            const elapsed = performance.now() - startedAt;
            const canvas = document.querySelector(`.page-canvas[data-page-index="${pageIndex}"]`);
            const scale = canvas.width / 1000;
            const pixel = Array.from(canvas.getContext('2d').getImageData(Math.round(360 * scale), Math.round(930 * scale), 1, 1).data);
            page.objects = page.objects.filter(object => object.id !== 'pencil_perf_stroke');
            api.setZoom(1);
            return {
              elapsed,
              pixel,
              passed: elapsed < 850 && pixel[3] === 255
            };
          }
        """)
        results["scribble_letter_r_guard"] = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            const pageIndex = api.state.currentPageIndex;
            const page = api.currentPage();
            api.setZoom(1);
            api.setTool('pen');
            api.state.settings.scribbleErase = true;
            page.objects = page.objects.filter(object => !String(object.id).startsWith('letter_r_guard'));
            page.objects.push({
              id: 'letter_r_guard_target',
              type: 'stroke',
              brush: 'fountain',
              color: '#111827',
              width: 7,
              opacity: 1,
              points: [
                { x: 640, y: 592, p: .6 },
                { x: 690, y: 592, p: .6 },
                { x: 735, y: 592, p: .6 }
              ]
            });
            api.renderPageCanvas(pageIndex);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const canvas = document.querySelector(`.page-canvas[data-page-index="${pageIndex}"]`);
            const clientFor = (x, y) => {
              const rect = canvas.getBoundingClientRect();
              return { x: rect.left + x / 1000 * rect.width, y: rect.top + y / 1414 * rect.height };
            };
            const send = (type, point) => canvas.dispatchEvent(new PointerEvent(type, {
              bubbles: true,
              cancelable: true,
              pointerId: 8814,
              pointerType: 'pen',
              isPrimary: true,
              button: 0,
              buttons: type === 'pointerup' ? 0 : 1,
              pressure: type === 'pointerup' ? 0 : .55,
              clientX: point.x,
              clientY: point.y
            }));
            const path = [
              clientFor(648, 674),
              clientFor(650, 646),
              clientFor(650, 615),
              clientFor(649, 583),
              clientFor(650, 552),
              clientFor(651, 526),
              clientFor(674, 522),
              clientFor(704, 526),
              clientFor(728, 540),
              clientFor(740, 562),
              clientFor(736, 586),
              clientFor(718, 604),
              clientFor(688, 611),
              clientFor(658, 606),
              clientFor(681, 618),
              clientFor(706, 638),
              clientFor(728, 660),
              clientFor(744, 680)
            ];
            send('pointerdown', path[0]);
            path.slice(1).forEach(point => send('pointermove', point));
            send('pointerup', path[path.length - 1]);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const targetStillPresent = page.objects.some(object => object.id === 'letter_r_guard_target');
            const letterStrokeCommitted = page.objects.some(object => object.id !== 'letter_r_guard_target' && object.type === 'stroke' && object.points?.length >= 8);
            return {
              targetStillPresent,
              letterStrokeCommitted,
              passed: targetStillPresent && letterStrokeCommitted
            };
          }
        """)

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
        barrel_eraser = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            api.state.settings.sPenGestures = true;
            api.state.eraserMode = 'stroke';
            api.setTool('pen');
            const page = api.currentPage();
            page.objects = page.objects.filter(object => object.id !== 'spen_barrel_target');
            page.objects.push({
              id: 'spen_barrel_target',
              type: 'stroke',
              brush: 'fountain',
              color: '#111827',
              width: 8,
              opacity: 1,
              points: [
                { x: 320, y: 360, p: .6 },
                { x: 380, y: 360, p: .6 },
                { x: 450, y: 360, p: .6 }
              ]
            });
            api.renderPageCanvas(api.state.currentPageIndex);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const canvas = document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`);
            const clientFor = (x, y) => {
              const rect = canvas.getBoundingClientRect();
              return { x: rect.left + x / 1000 * rect.width, y: rect.top + y / 1414 * rect.height };
            };
            const dispatchNative = (detail) => window.dispatchEvent(new CustomEvent('inkforge:native-stylus', { detail }));
            const sendPointer = (type, pointerId, client) => canvas.dispatchEvent(new PointerEvent(type, {
              bubbles: true,
              cancelable: true,
              pointerId,
              pointerType: 'pen',
              isPrimary: true,
              button: 0,
              buttons: type === 'pointerup' ? 0 : 1,
              pressure: type === 'pointerup' ? 0 : .55,
              clientX: client.x,
              clientY: client.y
            }));
            const client = clientFor(390, 360);
            dispatchNative({
              action: 11,
              hover: true,
              toolType: 2,
              buttonState: 32,
              primaryButton: true,
              barrelButton: true,
              x: client.x,
              y: client.y,
              pressure: 0,
              device: 'Samsung S Pen'
            });
            await new Promise(resolve => setTimeout(resolve, 320));
            dispatchNative({
              action: 0,
              hover: false,
              toolType: 2,
              buttonState: 0,
              rawButtonState: 0,
              x: client.x,
              y: client.y,
              pressure: .55,
              device: 'Samsung S Pen'
            });
            const latchedBeforeDown = !!window.__inkforgeNativeBridge.lastStylus?.barrelButton;
            sendPointer('pointerdown', 9301, clientFor(390, 360));
            sendPointer('pointermove', 9301, clientFor(410, 360));
            sendPointer('pointerup', 9301, clientFor(410, 360));
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            dispatchNative({
              action: 12,
              hover: true,
              toolType: 2,
              buttonState: 0,
              rawButtonState: 0,
              x: client.x,
              y: client.y,
              pressure: 0,
              device: 'Samsung S Pen'
            });
            const erased = !page.objects.some(object => object.id === 'spen_barrel_target');
            const restoredTool = api.state.tool;
            return {
              latchedBeforeDown,
              erased,
              restoredTool,
              objectCount: page.objects.length,
              passed: latchedBeforeDown && erased && restoredTool !== 'eraser'
            };
          }
        """)
        results["s_pen_barrel_button_eraser"] = barrel_eraser
        barrel_mid_contact = await page.evaluate("""
          async () => {
            const api = window.__inkforge;
            api.state.settings.sPenGestures = true;
            api.state.eraserMode = 'stroke';
            api.setTool('pen');
            const page = api.currentPage();
            page.objects = page.objects.filter(object => object.id !== 'spen_barrel_mid_contact_target');
            page.objects.push({
              id: 'spen_barrel_mid_contact_target',
              type: 'stroke',
              brush: 'fountain',
              color: '#111827',
              width: 8,
              opacity: 1,
              points: [
                { x: 320, y: 430, p: .6 },
                { x: 390, y: 430, p: .6 },
                { x: 460, y: 430, p: .6 }
              ]
            });
            const beforeCount = page.objects.length;
            api.renderPageCanvas(api.state.currentPageIndex);
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const canvas = document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`);
            const clientFor = (x, y) => {
              const rect = canvas.getBoundingClientRect();
              return { x: rect.left + x / 1000 * rect.width, y: rect.top + y / 1414 * rect.height };
            };
            const dispatchNative = (detail) => window.dispatchEvent(new CustomEvent('inkforge:native-stylus', { detail }));
            const sendPointer = (type, pointerId, client) => canvas.dispatchEvent(new PointerEvent(type, {
              bubbles: true,
              cancelable: true,
              pointerId,
              pointerType: 'pen',
              isPrimary: true,
              button: 0,
              buttons: type === 'pointerup' ? 0 : 1,
              pressure: type === 'pointerup' ? 0 : .55,
              clientX: client.x,
              clientY: client.y
            }));
            sendPointer('pointerdown', 9302, clientFor(390, 430));
            dispatchNative({
              action: 2,
              hover: false,
              toolType: 2,
              buttonState: 32,
              rawButtonState: 32,
              primaryButton: true,
              barrelButton: true,
              x: clientFor(390, 430).x,
              y: clientFor(390, 430).y,
              pressure: .55,
              device: 'Samsung S Pen'
            });
            sendPointer('pointermove', 9302, clientFor(410, 430));
            sendPointer('pointerup', 9302, clientFor(410, 430));
            dispatchNative({
              action: 12,
              hover: true,
              toolType: 2,
              buttonState: 0,
              rawButtonState: 0,
              x: clientFor(410, 430).x,
              y: clientFor(410, 430).y,
              pressure: 0,
              device: 'Samsung S Pen'
            });
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const erased = !page.objects.some(object => object.id === 'spen_barrel_mid_contact_target');
            const noExtraStroke = page.objects.length <= beforeCount - 1;
            return {
              erased,
              noExtraStroke,
              restoredTool: api.state.tool,
              objectCount: page.objects.length,
              beforeCount,
              passed: erased && noExtraStroke && api.state.tool !== 'eraser'
            };
          }
        """)
        results["s_pen_barrel_mid_contact_eraser"] = barrel_mid_contact

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
        await mobile.evaluate("""
          async () => {
            const api = window.__inkforge;
            const doc = api.createDocument('모바일 테스트 노트', 'grid');
            api.state.documents.push(doc);
            await api.storage.putDocument(doc);
            api.openDocument(doc.id);
            api.setTool('pen');
          }
        """)
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
    required_scalars = results.get("version") == "3.3.16" and results.get("upgrade_version") == "3.3.16" and results.get("math_engine") == 60 and results.get("editor_visible") is True and results.get("ocr_toolbar") is True and results.get("pdf_tools_ready") is True and results.get("auto_math_default_off") is True
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
