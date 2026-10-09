#!/usr/bin/env python3
"""Create an offline stroke-ID annotation page from an explicitly supplied ink file."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib

MAX_INPUT_BYTES = 16 * 1024 * 1024
TEMPLATE = r"""<!doctype html>
<html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BADNOTE OCR 정답 작성</title>
<style>
*{box-sizing:border-box}body{margin:0;font:16px system-ui;background:#edf1f5;color:#172033}
header{background:#305986;color:white;padding:16px}h1{font-size:20px;margin:0 0 8px}
main{display:grid;grid-template-columns:minmax(260px,1fr) 360px;gap:16px;max-width:1440px;margin:auto;padding:16px}
section,aside{background:white;border-radius:12px;padding:16px}canvas{width:100%;height:auto;border:1px solid #8996a7;touch-action:manipulation}
label{display:block;margin:10px 0 4px}input,textarea,select,button{font:inherit;padding:8px;border:1px solid #75859a;border-radius:6px}
input,textarea,select{width:100%}button{background:#305986;color:white;cursor:pointer;margin:4px 4px 4px 0}
button:disabled{opacity:.45;cursor:default}.secondary{background:#52677d}#regions{padding-left:20px}
li{margin:8px 0}li button{font-size:13px;padding:5px}#status{white-space:pre-wrap}p{line-height:1.5}.warn{color:#9e3416}
@media(max-width:760px){main{display:block}aside{margin-top:16px}}
</style>
<header><h1>BADNOTE OCR 정답 작성</h1><div>원본 파일을 수정하지 않습니다. 획을 선택해 실제로 쓴 내용과 읽기 순서를 기록하세요.</div></header>
<main><section><p id="summary"></p><canvas id="ink" aria-label="원본 필기 획 선택"></canvas>
<p>획을 누르면 선택·해제됩니다. 작은 점은 가까이 누르세요. 아래 ID 목록으로도 선택할 수 있습니다.</p>
<details><summary>획 ID로 선택</summary><div id="strokeList"></div></details>
<button id="clear" class="secondary">선택 해제</button><div id="status" role="status" aria-live="polite"></div></section>
<aside><label for="kind">원본 종류</label><select id="kind"><option value="real">실제 필기</option><option value="synthetic">합성 부하·회귀 입력</option></select>
<label for="writer">작성자 ID</label><input id="writer" autocomplete="off">
<label for="session">작성 세션 ID</label><input id="session" autocomplete="off">
<label for="device">기기 ID</label><input id="device" autocomplete="off">
<label for="language">언어 묶음</label><select id="language"><option value="ko">한글</option><option value="en">영어</option><option value="mixed">혼합</option><option value="complex">복잡 배치</option></select>
<label for="role">영역 종류</label><select id="role"><option value="text">읽을 수 있는 텍스트</option><option value="nonText">그림·표선 등</option><option value="unreadable">읽을 수 없는 필기</option></select>
<label for="text">정답 (실제로 쓴 내용)</label><textarea id="text" rows="4" spellcheck="false"></textarea>
<button id="add">선택한 획을 영역으로 저장</button>
<p>영역을 읽는 순서대로 추가하고 ↑↓로 조정하세요. 모든 유효 획을 한 영역에만 배정해야 저장할 수 있습니다.</p>
<ol id="regions"></ol><button id="save">정답 JSON 저장</button><p class="warn">합성 입력은 실제 필기 인식률의 근거가 될 수 없습니다.</p></aside></main>
<script id="fixture" type="application/json">__FIXTURE__</script>
<script>
"use strict";
const fixture=JSON.parse(document.getElementById("fixture").textContent);
const $=id=>document.getElementById(id), canvas=$("ink"), ctx=canvas.getContext("2d");
const displayScale=Math.min(1,1800/Math.max(fixture.width,fixture.height));
canvas.width=Math.ceil(fixture.width*displayScale);canvas.height=Math.ceil(fixture.height*displayScale);
ctx.setTransform(displayScale,0,0,displayScale,0,0);
const selected=new Set(), regions=fixture.truthRegions||[], strokes=fixture.strokes;
$("kind").value=fixture.provenance.kind;
for(const [element,key] of [["writer","writerId"],["session","sessionId"],["device","deviceId"],["language","language"]])
  if(fixture.provenance[key])$(element).value=fixture.provenance[key];
function assigned(){return new Set(regions.flatMap(r=>r.strokeIds))}
function repaint(){
  ctx.fillStyle="white";ctx.fillRect(0,0,fixture.width,fixture.height);
  const used=assigned();ctx.lineCap="round";ctx.lineJoin="round";
  for(const s of strokes){
    ctx.strokeStyle=selected.has(s.id)?"#ea580c":used.has(s.id)?"#19728a":"#172033";
    ctx.fillStyle=ctx.strokeStyle;ctx.lineWidth=Math.max(1,s.width||2);
    ctx.beginPath();s.points.forEach((p,i)=>i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y));ctx.stroke();
    if(s.points.length===1){ctx.beginPath();ctx.arc(s.points[0].x,s.points[0].y,2,0,Math.PI*2);ctx.fill()}
  }
  $("status").textContent="선택 "+selected.size+"획 · 배정 "+used.size+"/"+strokes.length+"획"+
    (fixture.invalidStrokeIds.length?"\n원본의 유효하지 않은 획 "+fixture.invalidStrokeIds.length+"개는 별도 기록됩니다.":"");
  $("summary").textContent=fixture.sampleId+" · "+strokes.length+"획 · 원본 digest "+fixture.pageDigest;
  $("regions").replaceChildren();
  regions.forEach((r,i)=>{
    const li=document.createElement("li");
    const label=document.createElement("span");label.textContent=r.role+" / "+r.strokeIds.length+"획: "+(r.text||"");
    li.append(label);
    for(const [caption,handler] of [
      ["↑",()=>{if(i){[regions[i-1],regions[i]]=[regions[i],regions[i-1]];repaint()}}],
      ["↓",()=>{if(i+1<regions.length){[regions[i+1],regions[i]]=[regions[i],regions[i+1]];repaint()}}],
      ["다시 작성",()=>{regions.splice(i,1);selected.clear();r.strokeIds.forEach(id=>selected.add(id));$("role").value=r.role;$("text").value=r.text||"";repaint()}]
    ]){const b=document.createElement("button");b.textContent=caption;b.onclick=handler;li.append(b)}
    $("regions").append(li);
  });
  for(const checkbox of $("strokeList").querySelectorAll("input"))checkbox.checked=selected.has(checkbox.value);
}
function distance(p,a,b){
  const dx=b.x-a.x,dy=b.y-a.y,den=dx*dx+dy*dy;
  const t=den?Math.max(0,Math.min(1,((p.x-a.x)*dx+(p.y-a.y)*dy)/den)):0;
  return Math.hypot(p.x-a.x-t*dx,p.y-a.y-t*dy);
}
canvas.addEventListener("click",event=>{
  const rect=canvas.getBoundingClientRect(),p={x:(event.clientX-rect.left)*fixture.width/rect.width,y:(event.clientY-rect.top)*fixture.height/rect.height};
  let closest=null,best=12*fixture.width/rect.width;const used=assigned();
  for(const s of strokes)if(!used.has(s.id)){
    const pts=s.points;
    let d=pts.length===1?Math.hypot(p.x-pts[0].x,p.y-pts[0].y):Infinity;
    for(let i=1;i<pts.length;i++)d=Math.min(d,distance(p,pts[i-1],pts[i]));
    if(d<best){best=d;closest=s.id}
  }
  if(closest){selected.has(closest)?selected.delete(closest):selected.add(closest);repaint()}
});
for(const stroke of strokes){
  const label=document.createElement("label"),box=document.createElement("input");box.type="checkbox";box.value=stroke.id;box.style.width="auto";
  box.onchange=()=>{if(assigned().has(stroke.id)){box.checked=false;return}box.checked?selected.add(stroke.id):selected.delete(stroke.id);repaint()};
  label.append(box,document.createTextNode(stroke.id));$("strokeList").append(label);
}
$("clear").onclick=()=>{selected.clear();repaint()};
$("add").onclick=()=>{
  if(!selected.size){$("status").textContent="영역에 속하는 획을 먼저 선택하세요.";return}
  regions.push({id:"truth-"+crypto.randomUUID(),role:$("role").value,strokeIds:[...selected],text:$("role").value==="text"?$("text").value:""});
  selected.clear();$("text").value="";repaint();
};
$("save").onclick=()=>{
  if(assigned().size!==strokes.length){$("status").textContent="아직 배정하지 않은 획이 있습니다. 텍스트·비텍스트·판독 불가로 모두 배정하세요.";return}
  const provenance={kind:$("kind").value,writerId:$("writer").value,sessionId:$("session").value,deviceId:$("device").value,language:$("language").value};
  if(provenance.kind==="real"&&(!provenance.writerId||!provenance.sessionId||!provenance.deviceId)){ $("status").textContent="실제 필기는 작성자·세션·기기 ID가 필요합니다.";return}
  const truth={schemaVersion:1,sampleId:fixture.sampleId,pageDigest:fixture.pageDigest,digestBasis:fixture.digestBasis,sourceFileSha256:fixture.sourceFileSha256,provenance,
    validStrokeIds:strokes.map(s=>s.id),invalidStrokeIds:fixture.invalidStrokeIds,truthRegions:regions,readingOrder:regions.map(r=>r.id)};
  const url=URL.createObjectURL(new Blob([JSON.stringify(truth,null,2)+"\n"],{type:"application/json"})),a=document.createElement("a");
  a.href=url;a.download=fixture.sampleId.replace(/[^A-Za-z0-9_.-]/g,"_")+".truth.json";a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  $("status").textContent="정답 JSON을 저장했습니다. 결과 JSON과 digest를 확인한 뒤 평가하세요.";
};
repaint();
</script></html>"""


def prepare(path: pathlib.Path, page_id: str | None = None) -> dict:
    if path.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError("Export a single page first; annotation input exceeds 16 MiB")
    raw = path.read_bytes()
    source = json.loads(raw.decode("utf-8-sig"))
    if not isinstance(source, dict):
        raise ValueError("Ink input must be a page or diagnostic object")
    page = source.get("sourcePage", source.get("page", source))
    if not isinstance(page, dict):
        raise ValueError("Diagnostic sourcePage must be a page object")
    if "pages" in source:
        pages = source["pages"]
        if page_id:
            page = next((p for p in pages if p.get("id") == page_id), None)
            if page is None:
                raise ValueError("Requested pageId was not found")
        elif len(pages) == 1:
            page = pages[0]
        else:
            raise ValueError("Supply --page-id for a multi-page file")
    strokes, invalid = [], []
    seen = set()
    for obj in page.get("objects", page.get("strokes", [])):
        if obj.get("type", "stroke") != "stroke":
            continue
        identifier = obj.get("id")
        if not isinstance(identifier, str) or not identifier or identifier in seen:
            raise ValueError("Every source stroke needs a unique non-empty ID")
        seen.add(identifier)
        points = obj.get("points", [])
        if not points or any(
            not isinstance(p, dict) or any(
                not isinstance(p.get(k), (int, float)) or isinstance(p.get(k), bool) or not math.isfinite(p[k])
                for k in ("x", "y")
            ) for p in points
        ):
            invalid.append(identifier)
            continue
        # Hidden/highlighter input is still annotatable as a non-text disposition.
        strokes.append({"id": identifier, "width": obj.get("width", 2), "points": points})
    digest = hashlib.sha256(raw).hexdigest()
    def dimension(key: str, fallback: int) -> int:
        value = page.get(key, fallback)
        if not isinstance(value, (int, float)) or not math.isfinite(value) or not 32 <= value <= 30000:
            raise ValueError("Invalid page dimensions")
        return round(value)
    return {
        "schemaVersion": 1, "sampleId": source.get("sampleId", page.get("id", path.stem)),
        "pageDigest": source.get("pageDigest", "source-sha256:" + digest),
        "digestBasis": source.get("digestBasis", "runtime" if source.get("pageDigest") else "source-file-bytes"),
        "sourceFileSha256": digest, "provenance": source.get("provenance", {"kind": "synthetic"}),
        "width": dimension("width", 1000), "height": dimension("height", 1414),
        "strokes": strokes, "invalidStrokeIds": invalid,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ink", type=pathlib.Path)
    parser.add_argument("--page-id")
    parser.add_argument("--truth", type=pathlib.Path, help="Resume a previously saved annotation")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    fixture = prepare(args.ink, args.page_id)
    if args.truth:
        from evaluate_ocr import validate_truth
        truth = json.loads(args.truth.read_text(encoding="utf-8-sig"))
        validate_truth(truth)
        if truth["pageDigest"] != fixture["pageDigest"]:
            raise ValueError("Annotation digest differs from the supplied source")
        if set(truth.get("validStrokeIds", [])) != {s["id"] for s in fixture["strokes"]}:
            raise ValueError("Annotation source stroke IDs differ")
        fixture.update({k: truth[k] for k in ("truthRegions", "provenance")})
        by_id = {r["id"]: r for r in fixture["truthRegions"]}
        fixture["truthRegions"] = [by_id[key] for key in truth["readingOrder"]]
    encoded = json.dumps(fixture, ensure_ascii=False).replace("<", "\\u003c")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(TEMPLATE.replace("__FIXTURE__", encoded), encoding="utf-8")
    print(json.dumps({"strokes": len(fixture["strokes"]), "invalid": len(fixture["invalidStrokeIds"]), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
