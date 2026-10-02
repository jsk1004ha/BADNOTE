#!/usr/bin/env python3
"""Round-trip note backups, rendered multi-page PDFs and native save outcomes."""
from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

import fitz
from pypdf import PdfReader
from playwright.async_api import async_playwright

from test_web import inline_document, install


async def run(args):
    args.output.mkdir(parents=True, exist_ok=True)
    errors = []
    results = {}
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(executable_path=args.chromium)
        page = await browser.new_page(viewport={"width": 900, "height": 1000})
        page.on("pageerror", lambda error: errors.append(str(error)))
        sources = inline_document(args.web)
        await install(page, *sources)
        await page.evaluate(r"""async () => {
          const a=__inkforge; a.state.settings.language='ko';
          const doc=a.createDocument('내보내기 검증', 'grid');
          const text=(id,y,value)=>({id,type:'text',x:100,y,w:800,h:100,fontSize:34,color:'#172554',text:value});
          doc.pages[0].objects=[text('title',120,'한글 노트 · 내보내기 검증'),
            {id:'ink',type:'stroke',brush:'fountain',width:8,color:'#e11d48',opacity:1,
             points:[{x:100,y:350,p:.5},{x:450,y:420,p:.7},{x:750,y:320,p:.6}]}];
          const image=document.createElement('canvas');image.width=100;image.height=100;
          const ctx=image.getContext('2d');ctx.fillStyle='#b9dcff';ctx.fillRect(0,0,100,100);
          ctx.fillStyle='#1e40af';ctx.fillRect(5,5,25,25);
          const blob=await new Promise(resolve=>image.toBlob(resolve));
          await a.storage.putAsset({id:'export-background',blob});
          doc.pages.push({id:'pdf-page',template:'blank',backgroundAssetId:'export-background',
            pdfPointWidth:842,pdfPointHeight:595,objects:[text('pdf-label',600,'PDF 배경과 필기 보존')]});
          doc.pages.push({id:'image-page',template:'blank',objects:[text('last',150,'마지막 페이지와 삽입 이미지'),
            {id:'photo',type:'image',src:image.toDataURL(),x:150,y:400,w:400,h:400}]});
          doc.audio=[{id:'audio',src:'data:audio/webm;base64,AQIDBA==',mime:'audio/webm',duration:1}];
          a.state.documents.push(doc);await a.storage.putDocument(doc);a.openDocument(doc.id);
          window.exportTestDoc=doc;
        }""")
        async with page.expect_download() as download_info:
            assert await page.evaluate("__inkforge.exportIfnote(exportTestDoc)")
        download = await download_info.value
        note_path = args.output / "roundtrip.ifnote"
        await download.save_as(note_path)
        note = json.loads(note_path.read_text(encoding="utf-8"))
        results["portable_note"] = (len(note["pages"]) == 3 and
            note["pages"][1]["backgroundImage"].startswith("data:image/png;base64,") and
            "backgroundAssetId" not in note["pages"][1] and len(note["audio"]) == 1)
        # A fresh storage context must import the same complete note without any assets.
        imported = await browser.new_page()
        await install(imported, *sources)
        await imported.locator("#importInput").set_input_files(str(note_path))
        await imported.wait_for_function("__inkforge.state.documents.some(d=>d.title==='내보내기 검증')")
        restored = await imported.evaluate("__inkforge.state.documents.find(d=>d.title==='내보내기 검증')")
        results["import_roundtrip"] = restored["pages"] == note["pages"] and restored["audio"] == note["audio"]
        await imported.close()

        # At mobile widths the share toolbar is hidden; More must still expose both formats.
        await page.set_viewport_size({"width": 600, "height": 900})
        await page.locator('[data-action="editor-more"]').click()
        await page.locator('#menuSheet [data-action="share"]').click()
        results["mobile_export_menu"] = await page.locator('[data-action="export-ifnote"]').is_visible() and await page.locator('[data-action="export-pdf"]').is_visible()
        async with page.expect_download() as download_info:
            await page.locator('#menuSheet [data-action="export-pdf"]').click()
        download = await download_info.value
        pdf_path = args.output / "all-pages.pdf"
        await download.save_as(pdf_path)
        reader = PdfReader(pdf_path, strict=True)
        results["pdf_structure"] = len(reader.pages) == 3 and all(
            abs(float(p.mediabox.height) / float(p.mediabox.width) - 1.414) < .001 for p in reader.pages)
        pdf = fitz.open(pdf_path)
        for i, pdf_page in enumerate(pdf):
            pix = pdf_page.get_pixmap(matrix=fitz.Matrix(1,1))
            pix.save(str(args.output / f"page-{i+1}.png"))
        # The unmounted imported PDF background must not export as a blank page.
        pix = pdf[1].get_pixmap()
        r,g,b = pix.pixel(pix.width//2,pix.height//4)[:3]
        results["pdf_background_loaded"] = b > r + 30 and b > 220
        pdf.close()

        results["many_images_survive_cache_limit"] = await page.evaluate(r"""async () => {
          const objects=[];
          for(let i=0;i<36;i++){
            const c=document.createElement('canvas');c.width=c.height=20;
            const ctx=c.getContext('2d');ctx.fillStyle='#dc2626';ctx.fillRect(0,0,20,20);
            ctx.fillStyle=`rgb(${i},0,0)`;ctx.fillRect(0,0,1,1);
            objects.push({id:'image'+i,type:'image',src:c.toDataURL(),x:50+(i%6)*150,y:50+Math.floor(i/6)*150,w:100,h:100});
          }
          const output=await __inkforge.renderExportPage({template:'blank',objects});
          const ctx=output.getContext('2d');
          return objects.every(o=>{const p=ctx.getImageData((o.x+50)*1.8,(o.y+50)*1.8,1,1).data;return p[0]>180&&p[1]<80});
        }""")

        results["missing_asset_reports_failure"] = await page.evaluate(r"""async () => {
          const d=JSON.parse(JSON.stringify(exportTestDoc));d.pages[1].backgroundAssetId='missing';
          return !(await __inkforge.exportIfnote(d)) && !__inkforge.state.exportBusy;
        }""")
        # Exercise actual chunk transfer and asynchronous saved/cancelled/error callbacks.
        await page.evaluate(r"""() => {
          window.nativeSaveTest={chunks:[],mode:'saved',cancelled:false};
          window.InkForgeNative={
            beginFileExport(){nativeSaveTest.chunks=[];return 'token'},
            appendFileExport(token,chunk){nativeSaveTest.chunks.push(chunk);return nativeSaveTest.mode!=='append-error'},
            cancelFileExport(){nativeSaveTest.cancelled=true},
            finishFileExport(id,payload){setTimeout(()=>__inkforgeNativeCallbacks.resolve(id,
              nativeSaveTest.mode==='error'?{ok:false,error:'disk full'}:
              {ok:true,data:{saved:nativeSaveTest.mode==='saved',cancelled:nativeSaveTest.mode==='cancelled'}}),10)}
          };
          delete window.__inkforgeNativeCallbacks;
          delete window.__inkforgeNativeBridge;
        }""")
        await page.add_script_tag(content=sources[-1])
        await page.wait_for_function("window.__inkforgeNativeBridge?.ready")
        results["native_chunk_transfer"] = await page.evaluate(r"""async () => {
          const bytes=Uint8Array.from({length:500000},(_,i)=>i%251);
          const result=await __inkforgeNativeBridge.saveBlob(new Blob([bytes]),'large.ifnote');
          const roundtrip=nativeSaveTest.chunks.map(c=>atob(c)).join('');
          return result.saved&&nativeSaveTest.chunks.length===3&&roundtrip.length===bytes.length&&bytes.every((b,i)=>b===roundtrip.charCodeAt(i));
        }""")
        for outcome in ["cancelled", "error", "append-error"]:
            results[f"native_{outcome}"] = await page.evaluate(r"""async outcome => {
              nativeSaveTest.mode=outcome;
              const result=await __inkforge.exportIfnote(exportTestDoc);
              return !result&&!__inkforge.state.exportBusy&&(outcome!=='append-error'||nativeSaveTest.cancelled);
            }""", outcome)
        await browser.close()
    results["errors"] = errors
    results["passed"] = not errors and all(value for value in results.values() if isinstance(value, bool))
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--web", type=Path, default=Path("web"))
    parser.add_argument("--chromium", required=True)
    parser.add_argument("--output", type=Path, default=Path("build/tests/export-3.3.30"))
    args = parser.parse_args()
    results = asyncio.run(run(args))
    text = json.dumps(results, ensure_ascii=False, indent=2)
    (args.output / "results.json").write_text(text + "\n", encoding="utf-8")
    print(text)
    raise SystemExit(0 if results["passed"] else 1)
