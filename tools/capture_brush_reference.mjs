#!/usr/bin/env node
// Execute the original pure brush functions; do not reimplement their arithmetic.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const args = process.argv.slice(2);
if (args.length !== 2 || args[0] !== '--output') {
  throw new Error('Usage: node tools/capture_brush_reference.mjs --output build/brush-reference.json');
}
const filename = path.join(root, 'web', 'app.js');
const source = fs.readFileSync(filename, 'utf8');
function originalFunction(name) {
  const start = source.indexOf('  function ' + name + '(');
  if (start < 0) throw new Error('Missing original function: ' + name);
  const end = source.indexOf('\n  }', start);
  if (end < 0) throw new Error('Missing original function closing: ' + name);
  return source.slice(start, end + 4);
}
const names = ['seededRandom', 'decimateRenderPoints', 'pencilRenderPoints',
  'smoothPoints', 'segmentDirection', 'pointPressure', 'brushSegmentWidth',
  'pageCssWidthForDistance', 'screenToPageDistance', 'screenToolWidthToPage',
  'basePageCssWidthForTools', 'toolStrokeWidthToPage',
  'strokePathSegments', 'renderPencil', 'renderWidthForObject', 'renderStroke'];
const brush = source.match(/  const BRUSH_META = \{[\s\S]*?\n  \};/);
const clamp = source.match(/^  const clamp = .*;$/m);
const lerp = source.match(/^  const lerp = .*;$/m);
if (!brush || !clamp || !lerp) throw new Error('Original brush constants not found');
const context = vm.createContext({});
new vm.Script([
  "'use strict';", brush[0], clamp[0], lerp[0],
  'const state = {penSettings:{}, zoom:1};',
  'const PAGE_WIDTH = 1000, TEXT_REFERENCE_PAGE_CSS_WIDTH = 880;',
  'const $ = () => null;',
  'const pencilRenderCache = new WeakMap();',
  ...names.map(originalFunction),
  'globalThis.reference={'+names.join(',')+',BRUSH_META,state};'
].join('\n'), {filename: 'original-brush-reference.js'}).runInContext(context, {timeout: 5000});
const reference = context.reference;
const inputs = [
  {name:'pressure-zero-null-missing',points:[
    {x:1.123456789,y:2.987654321,p:0,azimuth:0,tx:0,ty:0},
    {x:5.5,y:10.25,p:null,azimuth:.4,tx:30,ty:-10},
    {x:9.125,y:3.25},
    {x:10.8,y:-2.4,p:1,azimuth:-.8,tx:45,ty:20}
  ]},
  {name:'tiny-dot',points:[{x:250,y:300,p:0}]},
  {name:'dense-pencil',points:Array.from({length:700},(_,i)=>({
    x:i*.43,y:120+Math.sin(i/7)*9,p:.2+(i%7)*.1,tx:i%45,ty:-(i%20),t:i*8
  }))},
];
const fixtures=[];
for(const brushName of [...Object.keys(reference.BRUSH_META),'highlighter']){
  for(const input of inputs){
    if(input.name==='dense-pencil'&&brushName!=='pencil')continue;
    for(const customized of [false,true]){
      const settings=customized?{pressure:0,taper:0,smoothing:0,grain:0,tiltShade:0}:null;
      reference.state.penSettings=settings?{[brushName]:settings}:{};
      const effective=settings||reference.BRUSH_META[brushName]||reference.BRUSH_META.fountain;
      const stroke={id:'reference-'+brushName,brush:brushName,width:4.25,points:input.points};
      const smoothed=reference.smoothPoints(input.points,effective.smoothing??.3);
      const renderPoints=brushName==='pencil'?reference.pencilRenderPoints(stroke,smoothed,effective):smoothed;
      const widths=renderPoints.map((point,i)=>reference.brushSegmentWidth(stroke,point,renderPoints[i+1],i,renderPoints.length));
      fixtures.push({name:brushName+'/'+input.name+'/'+(customized?'zero-settings':'defaults'),
        input:stroke,settings,smoothed,renderPoints,widths});
    }
  }
}
const randomFixtures=['ink','ascii-seed','한글-획','😀-astral',''].map(seed=>{
  const random=reference.seededRandom(seed);
  return {seed,values:Array.from({length:64},()=>random())};
});
const widthFixtures=[280,400,880,1000].flatMap(cssWidth=>[0,.5,4.2,30].map(width=>{
  const ctx={__inkforgePageCssWidth:cssWidth,__inkforgeBasePageCssWidth:cssWidth};
  return {cssWidth,width,screenWidth:reference.screenToolWidthToPage(0,width,ctx),
    toolWidth:reference.toolStrokeWidthToPage(0,width,ctx)};
}));
// Record the operations emitted by the actual JS renderer, including multiply,
// seeded pencil scatter and the scale-dependent particle budget. This records
// geometry / paint values; it does not claim raster or physical-device evidence.
const renderFixtures=[];
for(const fixture of fixtures){
  for(const scale of fixture.input.brush==='pencil'?[1,3]:[1]){
    const operations=[];
    const methods=new Set(['save','restore','beginPath','arc','fill','moveTo',
      'quadraticCurveTo','lineTo','stroke']);
    const canvas=new Proxy({__inkforgePageCssWidth:880,__inkforgeBasePageCssWidth:880},{
      get(target,key){
        if(key==='getTransform')return ()=>({a:scale});
        if(methods.has(key))return (...values)=>operations.push([key,...values]);
        return target[key];
      },
      set(target,key,value){target[key]=value;operations.push(['set',key,value]);return true;}
    });
    reference.state.penSettings=fixture.settings?{[fixture.input.brush]:fixture.settings}:{};
    reference.renderStroke(canvas,{...fixture.input,color:'#172033'},0);
    renderFixtures.push({name:fixture.name,scale,operations});
  }
}
const output=path.resolve(args[1]);fs.mkdirSync(path.dirname(output),{recursive:true});
fs.writeFileSync(output,JSON.stringify({
  schemaVersion:1,source:'web/app.js',sourceSha256:crypto.createHash('sha256').update(source).digest('hex'),
  purpose:'original arithmetic and drawing-command regression; not pixel/physical latency evidence',
  functions:names,fixtures,randomFixtures,widthFixtures,renderFixtures
},null,2)+'\n');
process.stdout.write(JSON.stringify({fixtures:fixtures.length,randomFixtures:randomFixtures.length,widthFixtures:widthFixtures.length,renderFixtures:renderFixtures.length,output})+'\n');
