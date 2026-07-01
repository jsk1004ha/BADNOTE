#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import json
import pathlib
import sys
import time
from typing import Any

from playwright.async_api import async_playwright

from test_web import inline_document, install


async def wait_frames(page, count: int = 2) -> None:
    await page.evaluate(
        """count => new Promise(resolve => {
          let left = count;
          const step = () => (--left <= 0 ? resolve() : requestAnimationFrame(step));
          requestAnimationFrame(step);
        })""",
        count,
    )


async def run(args: argparse.Namespace) -> dict[str, Any]:
    html, app, recognition, pdf_tools, upgrade, native_bridge = inline_document(args.web)
    errors: list[str] = []
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(
            headless=True,
            executable_path=args.chromium,
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        context = await browser.new_context(viewport={"width": 1365, "height": 900}, device_scale_factor=1)
        page = await context.new_page()
        page.on("pageerror", lambda error: errors.append(f"pageerror: {error}"))
        page.on("console", lambda message: errors.append(f"console {message.type}: {message.text}") if message.type == "error" else None)
        await install(page, html, app, recognition, pdf_tools, upgrade, native_bridge)

        setup = await page.evaluate(
            """
            async ({pages, strokesPerPage, pointsPerStroke}) => {
              const api = window.__inkforge;
              const doc = api.createDocument('대용량 성능 테스트', 'grid');
              doc.pages = Array.from({length: pages}, (_, pageIndex) => {
                const page = api.blankPage(pageIndex % 5 === 0 ? 'lined' : 'grid');
                page.title = `${pageIndex + 1}`;
                page.objects = [];
                for (let strokeIndex = 0; strokeIndex < strokesPerPage; strokeIndex++) {
                  const baseY = 90 + strokeIndex * 34;
                  const points = Array.from({length: pointsPerStroke}, (_, pointIndex) => ({
                    x: 80 + pointIndex * 20,
                    y: baseY + Math.sin((pointIndex + pageIndex) * .7) * 9,
                    p: .45 + (pointIndex % 4) * .06,
                    t: pointIndex * 8
                  }));
                  page.objects.push({
                    id: `perf_${pageIndex}_${strokeIndex}`,
                    type: 'stroke',
                    brush: strokeIndex % 6 === 0 ? 'highlighter' : 'fountain',
                    color: strokeIndex % 6 === 0 ? '#ffe066' : '#172033',
                    width: strokeIndex % 6 === 0 ? 18 : 4,
                    opacity: strokeIndex % 6 === 0 ? .32 : 1,
                    points
                  });
                }
                return page;
              });
              api.state.documents.push(doc);
              await api.storage.putDocument(doc);
              return {documentId: doc.id, pages: doc.pages.length, objects: doc.pages.reduce((sum, item) => sum + item.objects.length, 0)};
            }
            """,
            {"pages": args.pages, "strokesPerPage": args.strokes_per_page, "pointsPerStroke": args.points_per_stroke},
        )

        open_start = time.perf_counter()
        await page.evaluate("id => window.__inkforge.openDocument(id)", setup["documentId"])
        await wait_frames(page, 3)
        open_ms = (time.perf_counter() - open_start) * 1000

        scroll_samples: list[float] = []
        mounted_samples: list[int] = []
        active_samples: list[int] = []
        targets = [0, args.pages // 5, args.pages // 2, args.pages * 4 // 5, args.pages - 1, args.pages // 3, args.pages * 2 // 3]
        for target in targets:
            start = time.perf_counter()
            sample = await page.evaluate(
                """
                async target => {
                  const api = window.__inkforge;
                  api.scrollToPage(target, false);
                  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                  api.updateVirtualPages();
                  await new Promise(resolve => requestAnimationFrame(resolve));
                  return {
                    current: api.state.currentPageIndex,
                    mounted: document.querySelectorAll('.page-canvas').length,
                    wraps: document.querySelectorAll('.page-wrap').length
                  };
                }
                """,
                target,
            )
            scroll_samples.append((time.perf_counter() - start) * 1000)
            mounted_samples.append(int(sample["mounted"]))
            active_samples.append(int(sample["current"]))

        zoom_start = time.perf_counter()
        zoom_sample = await page.evaluate(
            """
            async () => {
              const api = window.__inkforge;
              api.setZoom(2.2);
              await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
              api.updateVirtualPages();
              const canvas = document.querySelector(`.page-canvas[data-page-index="${api.state.currentPageIndex}"]`);
              return {
                zoom: api.state.zoom,
                mounted: document.querySelectorAll('.page-canvas').length,
                backingWidth: canvas?.width || 0,
                cssWidth: canvas?.clientWidth || 0
              };
            }
            """
        )
        zoom_ms = (time.perf_counter() - zoom_start) * 1000

        await context.close()
        await browser.close()

    max_scroll_ms = max(scroll_samples) if scroll_samples else 0
    max_mounted = max([*mounted_samples, int(zoom_sample["mounted"])]) if mounted_samples else int(zoom_sample["mounted"])
    passed = (
        not errors
        and open_ms <= args.max_open_ms
        and max_scroll_ms <= args.max_scroll_ms
        and zoom_ms <= args.max_zoom_ms
        and max_mounted <= args.max_mounted_canvases
        and zoom_sample["backingWidth"] >= zoom_sample["cssWidth"]
    )
    return {
        "passed": passed,
        "errors": errors,
        "document": setup,
        "thresholds": {
            "maxOpenMs": args.max_open_ms,
            "maxScrollMs": args.max_scroll_ms,
            "maxZoomMs": args.max_zoom_ms,
            "maxMountedCanvases": args.max_mounted_canvases,
        },
        "metrics": {
            "openMs": round(open_ms, 1),
            "scrollMs": [round(value, 1) for value in scroll_samples],
            "maxScrollMs": round(max_scroll_ms, 1),
            "zoomMs": round(zoom_ms, 1),
            "mountedCanvases": mounted_samples,
            "maxMountedCanvases": max_mounted,
            "activePages": active_samples,
            "zoomSample": zoom_sample,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--web", type=pathlib.Path, required=True)
    parser.add_argument("--chromium", default="/usr/bin/chromium")
    parser.add_argument("--pages", type=int, default=220)
    parser.add_argument("--strokes-per-page", type=int, default=12)
    parser.add_argument("--points-per-stroke", type=int, default=18)
    parser.add_argument("--max-open-ms", type=float, default=1800)
    parser.add_argument("--max-scroll-ms", type=float, default=520)
    parser.add_argument("--max-zoom-ms", type=float, default=700)
    parser.add_argument("--max-mounted-canvases", type=int, default=7)
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
