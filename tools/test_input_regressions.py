#!/usr/bin/env python3
"""Behavior regressions for accidental erase, viewport resets and held S Pen buttons."""
from __future__ import annotations

import argparse
import asyncio
import json
import pathlib

from playwright.async_api import async_playwright
from test_web import inline_document, install


SETUP = r"""
async () => {
  const api = window.__inkforge;
  const doc = api.createDocument('입력 회귀 테스트', 'blank');
  api.state.documents.push(doc);
  await api.storage.putDocument(doc);
  api.openDocument(doc.id);
  api.state.settings.drawHold = false;
  api.state.settings.scribbleErase = true;
  api.state.settings.sPenGestures = true;
  api.state.settings.stylusOnly = true;
  api.setTool('pen');
  api.setZoom(1);
  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  const canvas = () => document.querySelector('.page-canvas[data-page-index="0"]');
  const client = (x, y) => {
    const rect = canvas().getBoundingClientRect();
    return { clientX: rect.left + x * rect.width / 1000, clientY: rect.top + y * rect.height / 1414 };
  };
  const send = (type, x, y, buttons = 1, button = -1, pointerId = 5100, pointerType = 'pen') => {
    canvas().dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true,
      pointerId, pointerType, isPrimary: true, buttons, button,
      pressure: type === 'pointerup' ? 0 : .55, ...client(x, y) }));
  };
  const native = (action, buttons = 0, extra = {}) => window.dispatchEvent(new CustomEvent('inkforge:native-stylus', {
    detail: { action, buttonState: buttons, rawButtonState: buttons, buttonsAuthoritative: true,
      toolType: 2, pointerId: 0, x: client(500,500).clientX, y: client(500,500).clientY, pressure: .55, ...extra }
  }));
  const key = action => window.dispatchEvent(new CustomEvent('inkforge:native-stylus-key', { detail: { action, keyCode: 308, stylus: true } }));
  const stroke = (id, y = 500, extra = {}) => ({ id, type: 'stroke', brush: 'fountain', width: 3,
    opacity: 1, color: '#111827', points: [{x:450,y,p:.55},{x:500,y,p:.55},{x:550,y,p:.55}], ...extra });
  const interpolate = anchors => anchors.flatMap((p,i) => i === anchors.length - 1 ? [p] :
    Array.from({length:8}, (_,j) => ({x:p.x+(anchors[i+1].x-p.x)*j/8, y:p.y+(anchors[i+1].y-p.y)*j/8})));
  const scratch = interpolate([{x:435,y:489},{x:565,y:511},{x:437,y:491},{x:563,y:509},{x:439,y:493},{x:561,y:507}]);
  const draw = (points, buttons = 1) => {
    send('pointerdown', points[0].x, points[0].y, buttons, 0);
    points.slice(1).forEach(p => send('pointermove', p.x, p.y, buttons));
    send('pointerup', points.at(-1).x, points.at(-1).y, 0, 0);
  };
  const reset = () => {
    native(3); api.state.activePointers.clear(); api.state.drawSession = null;
    api.state.nativeStylusInputEnabled = false; api.state.nativeStylusContact = null;
    api.setTool('pen'); api.state.settings.language = 'ko';
    doc.pages[0].objects = [];
  };
  window.inputTest = { api, doc, canvas, client, send, native, key, stroke, interpolate, scratch, draw, reset };
}
"""


async def run(args):
    results = {}
    errors = []
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(executable_path=args.chromium)
        page = await browser.new_page(viewport={"width": 1200, "height": 900})
        page.on("pageerror", lambda error: errors.append(str(error)))
        await install(page, *inline_document(args.web))
        await page.evaluate(SETUP)
        results["ordinary_writing_preserved"] = await page.evaluate(r"""() => {
          const t = inputTest, results = {};
          const paths = {
            loops: Array.from({length:97},(_,i)=>({x:500+62*Math.cos(i*Math.PI/16),y:500+22*Math.sin(i*Math.PI/16)})),
            cursive: Array.from({length:65},(_,i)=>({x:435+i*2,y:500+Math.sin(i/2)*19})),
            zigzag: Array.from({length:18},(_,i)=>({x:435+i*8,y:500+(i%2?-18:18)})),
            hangul_rieul: t.interpolate([{x:460,y:475},{x:540,y:475},{x:540,y:500},{x:460,y:500},{x:460,y:525},{x:540,y:525}]),
            letter_w: t.interpolate([{x:440,y:475},{x:470,y:525},{x:500,y:485},{x:530,y:525},{x:560,y:475}])
          };
          for (const language of ['ko','en','pt','ja','zh']) for (const [name,path] of Object.entries(paths)) {
            t.reset(); t.api.state.settings.language = language;
            t.doc.pages[0].objects.push(t.stroke('original'));
            t.draw(path);
            results[`${language}_${name}`] = t.doc.pages[0].objects.some(o=>o.id==='original');
          }
          return {cases:results, passed:Object.values(results).every(Boolean)};
        }""")
        results["deliberate_scratch_scope_and_undo"] = await page.evaluate(r"""() => {
          const t = inputTest; t.reset();
          const objects = t.doc.pages[0].objects;
          objects.push(t.stroke('target'),t.stroke('nearby',540),t.stroke('highlight',500,{brush:'highlighter'}),
            t.stroke('locked',500,{locked:true}),
            {id:'shape',type:'shape',shape:'line',width:3,color:'#111827',x1:450,y1:500,x2:550,y2:500},
            {id:'text',type:'text',x:460,y:480,w:100,h:30,text:'보존',fontSize:20});
          const profile=t.api.scribbleGestureProfile(t.scratch);
          t.draw(t.scratch);
          const ids=t.doc.pages[0].objects.map(o=>o.id);
          const erased=!ids.includes('target');
          const others=['nearby','highlight','locked','shape','text'].every(id=>ids.includes(id));
          t.api.undo(); const undo=t.doc.pages[0].objects.some(o=>o.id==='target');
          t.api.redo(); const redo=!t.doc.pages[0].objects.some(o=>o.id==='target');
          return {erased,others,undo,redo,dense:profile.dense,passed:erased&&others&&undo&&redo};
        }""")
        results["highlighter_never_scribble_erases"] = await page.evaluate(r"""() => {
          const t = inputTest; t.reset(); t.doc.pages[0].objects.push(t.stroke('original'));
          t.api.setTool('highlighter'); t.draw(t.scratch);
          return {passed:t.doc.pages[0].objects.some(o=>o.id==='original')};
        }""")
        results["scratch_directions_and_zoom"] = await page.evaluate(r"""() => {
          const t=inputTest, cases=[];
          for (const zoom of [.5,1,3.8]) for (const angle of [0,Math.PI/4,Math.PI/2]) {
            t.reset(); t.api.setZoom(zoom);
            const rotate=p=>({x:500+(p.x-500)*Math.cos(angle)-(p.y-500)*Math.sin(angle),
              y:500+(p.x-500)*Math.sin(angle)+(p.y-500)*Math.cos(angle)});
            t.doc.pages[0].objects.push(t.stroke('target',500,{points:[{x:450,y:500},{x:550,y:500}].map(rotate)}));
            t.draw(t.scratch.map(rotate));
            cases.push({zoom,angle,erased:!t.doc.pages[0].objects.some(o=>o.id==='target')});
          }
          t.api.setZoom(1);
          return {cases,passed:cases.every(c=>c.erased)};
        }""")
        results["disabled_scribble_preserves_ink"] = await page.evaluate(r"""() => {
          const t=inputTest; t.reset(); t.api.state.settings.scribbleErase=false;
          t.doc.pages[0].objects.push(t.stroke('original')); t.draw(t.scratch);
          const passed=t.doc.pages[0].objects.some(o=>o.id==='original');
          t.api.state.settings.scribbleErase=true; return {passed};
        }""")
        results["native_release_resumes_same_contact"] = await page.evaluate(r"""() => {
          const t=inputTest; t.reset(); t.doc.pages[0].objects.push(t.stroke('target'));
          t.native(0); t.send('pointerdown',200,500,1,0); t.send('pointermove',250,500);
          t.native(2,32); const pressed=t.api.state.drawSession?.kind==='eraser';
          t.send('pointermove',500,500); const erased=!t.doc.pages[0].objects.some(o=>o.id==='target');
          t.native(2,0); const released=t.api.state.drawSession?.kind==='stroke'&&t.api.state.tool==='pen';
          t.doc.pages[0].objects.push(t.stroke('after-release',600));
          t.send('pointermove',500,600); t.send('pointermove',550,600); t.send('pointerup',550,600,0,0);
          const protectedAfterRelease=t.doc.pages[0].objects.some(o=>o.id==='after-release');
          const resumedInk=t.doc.pages[0].objects.some(o=>!['after-release','target'].includes(o.id)&&o.points?.some(p=>p.y>550));
          return {pressed,erased,released,protectedAfterRelease,resumedInk,passed:pressed&&erased&&released&&protectedAfterRelease&&resumedInk};
        }""")
        results["pointer_release_button_is_not_press"] = await page.evaluate(r"""() => {
          const t=inputTest; t.reset();
          t.send('pointerdown',200,500,1,0); t.send('pointermove',250,500);
          t.send('pointermove',400,500,3,2); const pressed=t.api.state.drawSession?.kind==='eraser';
          // Pointer Events uses button=2 on both press and release; buttons=1 is authoritative.
          t.send('pointermove',450,500,1,2); const released=t.api.state.drawSession?.kind==='stroke'&&t.api.state.tool==='pen';
          t.send('pointermove',500,600,1); t.send('pointerup',500,600,0,0);
          return {pressed,released,passed:pressed&&released};
        }""")
        results["key_hold_survives_motion_and_tip_lift"] = await page.evaluate(r"""() => {
          const t=inputTest; t.reset();
          t.send('pointerdown',250,500,1,0); t.send('pointermove',300,500);
          t.key(0); const pressed=t.api.state.drawSession?.kind==='eraser';
          t.native(2,0); const held=t.api.state.tool==='eraser';
          t.send('pointerup',300,500,0,0); t.native(1,0);
          const heldAfterLift=t.api.state.tool==='eraser';
          t.key(1); const restored=t.api.state.tool==='pen';
          return {pressed,held,heldAfterLift,restored,passed:pressed&&held&&heldAfterLift&&restored};
        }""")
        results["native_secondary_release_and_hover_reset"] = await page.evaluate(r"""() => {
          const t=inputTest; t.reset();
          t.native(11,96,{hover:true});
          t.native(12,64,{hover:true,actionButton:32});
          const secondaryHeld=t.api.state.tool==='eraser';
          t.native(12,0,{hover:true,actionButton:64});
          const released=t.api.state.tool==='pen';
          t.native(11,32,{hover:true}); t.native(10,0,{hover:true});
          const exited=t.api.state.tool==='pen';
          return {secondaryHeld,released,exited,passed:secondaryHeld&&released&&exited};
        }""")
        results["button_does_not_latch_after_blur"] = await page.evaluate(r"""() => {
          const t=inputTest; t.reset();
          t.send('pointerdown',250,500,1,0); t.native(2,32);
          window.dispatchEvent(new Event('blur'));
          const passed=t.api.state.tool==='pen' && t.api.state.drawSession===null
            && t.api.state.activePointers.size===0 && !window.__inkforgeNativeBridge.barrelButtonActive;
          return {passed};
        }""")
        await page.evaluate("inputTest.reset(); inputTest.api.setZoom(2.2)")
        original_width = await page.evaluate("inputTest.canvas().getBoundingClientRect().width")
        await page.evaluate("inputTest.canvas().dispatchEvent(new MouseEvent('dblclick',{bubbles:true,detail:2}))")
        after_double_click = await page.evaluate("inputTest.api.state.zoom")
        await page.set_viewport_size({"width": 750, "height": 650})
        await page.wait_for_timeout(150)
        after_resize = await page.evaluate("inputTest.canvas().getBoundingClientRect().width")
        await page.evaluate("inputTest.api.state.sidebarOpen=true; inputTest.api.renderSidebar()")
        await page.wait_for_timeout(150)
        after_sidebar = await page.evaluate("inputTest.canvas().getBoundingClientRect().width")
        results["manual_zoom_retained"] = {
            "zoom_after_double_click": after_double_click, "widths": [original_width, after_resize, after_sidebar],
            "passed": after_double_click == 2.2 and abs(original_width - after_resize) < 1 and abs(original_width - after_sidebar) < 1,
        }
        await page.evaluate("document.querySelector('[data-action=\"fit-page\"]')?.click()")
        # Explicit fit is always available through the Hand tool toolbar.
        await page.evaluate("inputTest.api.setTool('hand')")
        await page.evaluate("document.querySelector('#activeToolMenu [data-action=\"fit-page\"]').click()")
        results["explicit_fit_still_works"] = await page.evaluate("({passed:inputTest.api.state.zoom===1 && inputTest.canvas().getBoundingClientRect().width < 750})")
        results["sparse_stroke_eraser_hits_segments"] = await page.evaluate(r"""() => {
          const t=inputTest; t.reset();
          const sparse={...t.stroke('sparse'),points:[{x:300,y:500,p:.55},{x:700,y:500,p:.55}]};
          t.doc.pages[0].objects=[sparse]; t.api.setTool('eraser');
          t.send('pointerdown',500,500,1,0); t.send('pointerup',500,500,0,0);
          const whole=t.doc.pages[0].objects.length===0;
          t.api.undo(); const undo=t.doc.pages[0].objects.some(o=>o.id==='sparse');
          t.api.state.eraserMode='precision';
          t.send('pointerdown',500,500,1,0); t.send('pointerup',500,500,0,0);
          const pieces=t.doc.pages[0].objects;
          const precise=pieces.length===2&&pieces[0].points.at(-1).x<500&&pieces[1].points[0].x>500;
          t.api.state.eraserMode='stroke';
          return {whole,undo,precise,passed:whole&&undo&&precise};
        }""")
        results["native_contact_without_dom_events"] = await page.evaluate(r"""() => {
          const t = inputTest; t.reset(); t.api.setZoom(1);
          t.doc.pages[0].objects = [t.stroke('first', 500), t.stroke('second', 650)];
          const at = (action, x, y, buttons, contact) => {
            const p = t.client(x,y);
            t.native(action, buttons, {x:p.clientX,y:p.clientY,contact,hover:!contact});
          };
          at(7,500,500,32,false);
          const hoverSafe = t.doc.pages[0].objects.length === 2;
          at(0,500,500,32,true);
          const erasedOnDown = !t.doc.pages[0].objects.some(o=>o.id==='first');
          at(2,500,650,32,true);
          const erasedOnMove = !t.doc.pages[0].objects.some(o=>o.id==='second');
          at(2,600,700,0,true);
          const resumed = t.api.state.tool === 'pen' && t.api.state.drawSession?.kind === 'stroke';
          at(2,700,720,0,true);
          at(1,700,720,0,false);
          const ink = t.doc.pages[0].objects.filter(o=>o.type==='stroke');
          return {hoverSafe,erasedOnDown,erasedOnMove,resumed,inkCount:ink.length,
            passed:hoverSafe&&erasedOnDown&&erasedOnMove&&resumed&&ink.length===1&&!t.api.state.drawSession};
        }""")
        results["native_mid_contact_button_and_dom_cancel"] = await page.evaluate(r"""async () => {
          const t = inputTest; t.reset(); t.doc.pages[0].objects = [t.stroke('target',500)];
          // Native DOWN uses elementFromPoint; send it only after the prior case's
          // render/scroll has settled and both intended ink positions hit this canvas.
          const viewport = document.getElementById('editorViewport');
          const deadline = performance.now() + 3000;
          let previous = null, stableFrames = 0;
          while (performance.now() < deadline && stableFrames < 6) {
            await new Promise(resolve => requestAnimationFrame(resolve));
            const canvas = t.canvas(), rect = canvas?.getBoundingClientRect();
            const signature = rect && [rect.x,rect.y,rect.width,rect.height,viewport.scrollTop].join(',');
            const hitsCanvas = canvas && rect && [[300,350],[500,500]].every(([x,y]) => {
              const p = t.client(x,y);
              return document.elementFromPoint(p.clientX,p.clientY)?.closest('.page-canvas') === canvas;
            });
            const ready = rect?.width > 0 && rect.height > 0 && hitsCanvas &&
              t.api.state.drawSession === null && t.api.state.nativeStylusContact === null &&
              t.api.state.activePointers.size === 0 && t.api.state.tool === 'pen';
            stableFrames = ready && signature === previous ? stableFrames + 1 : 0;
            previous = signature;
          }
          if (stableFrames < 6) throw new Error('Native contact fixture did not become ready');
          const at = (action,x,y,buttons=0,contact=true) => {
            const p=t.client(x,y); t.native(action,buttons,{x:p.clientX,y:p.clientY,contact});
          };
          at(0,300,350); at(2,400,350);
          at(11,500,500,32);
          t.send('pointercancel',500,500,0,0);
          const stillErasing=t.api.state.drawSession?.kind==='eraser';
          at(2,550,500,32); at(1,550,500,32,false); at(7,550,500,0,false);
          const kept=t.doc.pages[0].objects.some(o=>Math.abs((o.points?.[0]?.y || 0)-350)<.01);
          const erased=!t.doc.pages[0].objects.some(o=>o.id==='target');
          return {stillErasing,kept,erased,passed:stillErasing&&kept&&erased&&t.api.state.tool==='pen'&&!t.api.state.drawSession};
        }""")
        results["native_read_only_protected"] = await page.evaluate(r"""() => {
          const t=inputTest; t.reset(); t.doc.pages[0].objects=[t.stroke('safe')];
          t.api.state.readOnly=true;
          t.native(0,32,{contact:true}); t.native(2,32,{contact:true}); t.native(1,0,{contact:false});
          const passed=t.api.state.readOnly&&t.doc.pages[0].objects.some(o=>o.id==='safe');
          t.api.state.readOnly=false;
          return {passed};
        }""")
        await browser.close()
    results["errors"] = errors
    results["passed"] = not errors and all(value["passed"] for value in results.values() if isinstance(value, dict))
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--web", type=pathlib.Path, default=pathlib.Path("web"))
    parser.add_argument("--chromium", default=None)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    result = asyncio.run(run(args))
    output = json.dumps(result, ensure_ascii=False, indent=2)
    print(output)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output + "\n", encoding="utf-8")
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
