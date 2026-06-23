(() => {
  'use strict';

  const VERSION = '3.3.13';
  const PAGE_WIDTH = 1000;
  const PAGE_HEIGHT = 1414;
  const AUTO_MATH_DELAY = 1050;
  const AUTO_MATH_MAX_AGE = 6500;
  const SVG = {
    palette: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3a9 9 0 1 0 0 18h2a2 2 0 0 0 0-4h-1a2 2 0 0 1 0-4h3a5 5 0 0 0 5-5c0-3-4-5-9-5z"/><circle cx="7" cy="10" r="1" fill="currentColor" stroke="none"/><circle cx="9" cy="6.5" r="1" fill="currentColor" stroke="none"/><circle cx="14" cy="6" r="1" fill="currentColor" stroke="none"/></svg>',
    close: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m6 6 12 12M18 6 6 18"/></svg>',
    check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m5 12 4 4L19 6"/></svg>',
    pen: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m5 19 3.5-1 9.8-9.8a2 2 0 0 0 0-2.8l-.7-.7a2 2 0 0 0-2.8 0L5 14.5z"/><path d="m13.5 6 4.5 4.5M5 19l-1 2 2-1"/></svg>',
    math: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 5h6l-4 7 4 7H5M14 8h6M17 5v6M14 16h6"/></svg>',
    gesture: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M8 13V8a2 2 0 0 1 4 0v3-6a2 2 0 0 1 4 0v7M8 13l-2-2a2 2 0 0 0-3 3l4 5a6 6 0 0 0 5 2h2a6 6 0 0 0 6-6V9a2 2 0 0 0-4 0"/></svg>'
  };

  let api = null;
  let autoMathTimer = null;
  let autoMathBusy = false;
  let pendingMath = null;
  let observedStrokeIds = new Set();
  let colorTarget = 'pen';
  let colorState = { h: 214, s: .8, v: .82 };
  let stylusGesture = null;
  let barrelEraser = null;
  let lastStylusActionAt = 0;
  let collisionFrame = 0;

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
  const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  const now = () => Date.now();

  function toast(message) {
    api?.toast?.(message);
  }

  function currentDocument() {
    return api?.currentDocument?.();
  }

  function currentPage() {
    return api?.currentPage?.();
  }

  function objectBounds(object) {
    if (!object) return { x: 0, y: 0, w: 1, h: 1 };
    if (api?.computeBounds) return api.computeBounds(object);
    const points = object.points || [];
    if (points.length) {
      const xs = points.map((point) => point.x), ys = points.map((point) => point.y);
      return { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(1, Math.max(...xs) - Math.min(...xs)), h: Math.max(1, Math.max(...ys) - Math.min(...ys)) };
    }
    return { x: object.x || 0, y: object.y || 0, w: object.w || 1, h: object.h || 1 };
  }

  function unionBounds(objects) {
    const bounds = objects.map(objectBounds);
    if (!bounds.length) return { x: 0, y: 0, w: 1, h: 1 };
    const left = Math.min(...bounds.map((item) => item.x));
    const top = Math.min(...bounds.map((item) => item.y));
    const right = Math.max(...bounds.map((item) => item.x + item.w));
    const bottom = Math.max(...bounds.map((item) => item.y + item.h));
    return { x: left, y: top, w: right - left, h: bottom - top };
  }

  function seedObservedStrokes() {
    observedStrokeIds = new Set();
    for (const doc of api.state.documents || []) {
      for (const page of doc.pages || []) {
        for (const object of page.objects || []) if (object.type === 'stroke') observedStrokeIds.add(object.id);
      }
    }
  }

  function boxesNear(a, b) {
    const horizontalGap = Math.max(0, Math.max(a.x, b.x) - Math.min(a.x + a.w, b.x + b.w));
    const verticalGap = Math.max(0, Math.max(a.y, b.y) - Math.min(a.y + a.h, b.y + b.h));
    const centerY = Math.abs((a.y + a.h / 2) - (b.y + b.h / 2));
    return horizontalGap < Math.max(120, Math.min(a.h, b.h) * 2.2) && verticalGap < 90 && centerY < Math.max(150, Math.max(a.h, b.h) * 1.9);
  }

  function registerNewMathStrokes(pageIndex) {
    if (!api.state.settings.autoMath || autoMathBusy || api.state.tool !== 'pen') return;
    const page = currentDocument()?.pages?.[pageIndex];
    if (!page) return;
    const newStrokes = (page.objects || []).filter((object) => object.type === 'stroke' && object.brush !== 'highlighter' && object.points?.length && !observedStrokeIds.has(object.id));
    for (const stroke of newStrokes) {
      observedStrokeIds.add(stroke.id);
      const bounds = objectBounds(stroke);
      if (!pendingMath || pendingMath.pageIndex !== pageIndex || now() - pendingMath.startedAt > AUTO_MATH_MAX_AGE || !boxesNear(pendingMath.bounds, bounds)) {
        if (pendingMath?.strokes?.length >= 2) void processPendingMath(pendingMath);
        pendingMath = { pageIndex, strokes: [stroke], bounds, startedAt: now(), updatedAt: now() };
      } else {
        pendingMath.strokes.push(stroke);
        pendingMath.bounds = unionBounds(pendingMath.strokes);
        pendingMath.updatedAt = now();
      }
    }
    if (newStrokes.length) scheduleAutoMath();
  }

  function scheduleAutoMath() {
    clearTimeout(autoMathTimer);
    autoMathTimer = setTimeout(() => {
      if (pendingMath?.strokes?.length >= 2) void processPendingMath(pendingMath);
    }, AUTO_MATH_DELAY);
  }

  function normalizeExpression(raw) {
    let value = String(raw || '')
      .replace(/\\left|\\right/g, '')
      .replace(/\\times|\\cdot|\\ast/g, '*')
      .replace(/\\div/g, '/')
      .replace(/\\pi/g, 'pi')
      .replace(/\\sqrt\s*\{([^{}]+)\}/g, 'sqrt($1)')
      .replace(/\\frac\s*\{([^{}]+)\}\s*\{([^{}]+)\}/g, '($1)/($2)')
      .replace(/\{/g, '(')
      .replace(/\}/g, ')');
    value = value
      .replace(/\s+/g, '')
      .replace(/[−–—]/g, '-')
      .replace(/×/g, '*')
      .replace(/÷/g, '/')
      .replace(/，/g, ',')
      .replace(/。/g, '.')
      .replace(/[?？]+$/g, '');
    return value;
  }

  function mathCandidateExpressions(result) {
    const raw = [result?.expression, ...(result?.alternatives || [])].filter(Boolean);
    const output = [];
    for (const value of raw) {
      let expression = normalizeExpression(value);
      if (!expression) continue;
      const equalIndex = expression.indexOf('=');
      if (equalIndex >= 0) expression = expression.slice(0, equalIndex);
      expression = expression.replace(/=+$/g, '');
      if (!expression || output.includes(expression)) continue;
      output.push(expression);
    }
    return output;
  }

  function isLikelyCalculation(expression) {
    const operators = (expression.match(/[+\-*/^%]/g) || []).length;
    const numbers = (expression.match(/(?:\d+(?:\.\d+)?|pi|e)/g) || []).length;
    const functionCall = /(?:sqrt|sin|cos|tan|log|ln|abs)\(/.test(expression);
    if (expression.length > 44 || /[^0-9a-z()+\-*/^%.,]/i.test(expression)) return false;
    return (operators >= 1 && numbers >= 2) || (functionCall && numbers >= 1);
  }

  function formatResult(value) {
    if (!Number.isFinite(value)) return String(value);
    if (Math.abs(value) >= 1e10 || (Math.abs(value) > 0 && Math.abs(value) < 1e-6)) return value.toExponential(6).replace(/\.0+e/, 'e');
    return Number(value.toPrecision(12)).toString();
  }

  function setAutoMathStatus(message, tone = 'idle') {
    const node = $('#autoMathStatus');
    if (!node) return;
    node.dataset.tone = tone;
    node.innerHTML = `${SVG.math}<span>${message}</span>`;
    node.classList.add('is-visible');
    clearTimeout(node._hideTimer);
    node._hideTimer = setTimeout(() => node.classList.remove('is-visible'), tone === 'busy' ? 5000 : 2500);
  }

  async function recognizeAndEvaluate(strokes) {
    const recognizer = api.recognition?.recognizeMathStrokes;
    if (!recognizer) throw new Error('손글씨 인식기가 준비되지 않았습니다.');
    const result = await recognizer(strokes.map((object) => object.points), (message) => setAutoMathStatus(message, 'busy'));
    for (const expression of mathCandidateExpressions(result)) {
      if (!isLikelyCalculation(expression)) continue;
      try {
        const calculation = api.evaluateMath(expression, { degree: !!api.state.math?.degree });
        return { expression, result: formatResult(calculation.result), confidence: result.confidence || 0 };
      } catch {}
    }
    return null;
  }

  async function processPendingMath(group, options = {}) {
    if (!group || autoMathBusy || (!options.force && !api.state.settings.autoMath)) return false;
    if (pendingMath === group) pendingMath = null;
    clearTimeout(autoMathTimer);
    const doc = currentDocument();
    const page = doc?.pages?.[group.pageIndex];
    if (!page || group.strokes.some((stroke) => !page.objects.some((object) => object.id === stroke.id))) return false;
    autoMathBusy = true;
    setAutoMathStatus('손글씨 수식 인식 중…', 'busy');
    try {
      const calculation = await recognizeAndEvaluate(group.strokes);
      if (!calculation) {
        setAutoMathStatus('일반 필기로 유지했습니다.', 'idle');
        return false;
      }
      const bounds = unionBounds(group.strokes);
      const displayText = `${calculation.expression} = ${calculation.result}`;
      const width = clamp(76 + displayText.length * 15, 160, 560);
      let x = bounds.x + bounds.w + 18;
      let y = bounds.y + bounds.h / 2 - 36;
      if (x + width > PAGE_WIDTH - 24) { x = clamp(bounds.x, 24, PAGE_WIDTH - width - 24); y = bounds.y + bounds.h + 14; }
      y = clamp(y, 24, PAGE_HEIGHT - 92);
      api.checkpoint(options.force ? 'manual-handwritten-math' : 'auto-handwritten-math');
      const sourceIds = group.strokes.map((stroke) => stroke.id);
      page.objects = page.objects.filter((object) => !(object.type === 'math' && object.auto && object.sourceStrokeIds?.some((id) => sourceIds.includes(id))));
      page.objects.push({
        id: `math_auto_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`,
        type: 'math', auto: true, x, y, w: width, h: 72,
        expression: calculation.expression, result: calculation.result, showExpression: true,
        color: '#174f82', background: '#eaf4ff', fontSize: 26,
        sourceStrokeIds: sourceIds, sourceBounds: bounds,
        confidence: calculation.confidence, createdAt: new Date().toISOString()
      });
      await api.persistCurrent();
      api.renderPageCanvas(group.pageIndex);
      api.renderSidebar();
      api.renderDocumentSearch?.();
      setAutoMathStatus(`${calculation.expression} = ${calculation.result}`, 'success');
      return true;
    } catch (error) {
      console.warn('Auto math recognition failed', error);
      setAutoMathStatus('수식으로 판단하지 않았습니다.', 'idle');
      return false;
    } finally {
      autoMathBusy = false;
    }
  }

  function currentPageMathGroup() {
    const pageIndex = api.state.currentPageIndex;
    const page = currentDocument()?.pages?.[pageIndex];
    if (!page) return null;
    const usedSourceIds = new Set();
    for (const object of page.objects || []) {
      if (object.type === 'math' && Array.isArray(object.sourceStrokeIds)) {
        object.sourceStrokeIds.forEach((id) => usedSourceIds.add(id));
      }
    }
    const strokes = (page.objects || []).filter((object) =>
      object.type === 'stroke' &&
      object.brush !== 'highlighter' &&
      object.points?.length &&
      !object.autoShapeSource &&
      !usedSourceIds.has(object.id)
    );
    if (!strokes.length) return null;
    const selected = [];
    let bounds = null;
    for (let index = strokes.length - 1; index >= 0; index--) {
      const stroke = strokes[index];
      const strokeBox = objectBounds(stroke);
      if (!bounds || boxesNear(bounds, strokeBox)) {
        selected.unshift(stroke);
        bounds = unionBounds(selected);
      } else if (selected.length >= 2) {
        break;
      }
      if (selected.length >= 8) break;
    }
    return selected.length ? { pageIndex, strokes: selected, bounds: unionBounds(selected), startedAt: now(), updatedAt: now() } : null;
  }

  async function processCurrentPageMath() {
    const group = currentPageMathGroup();
    if (!group || group.strokes.length < 2) {
      setAutoMathStatus('계산할 손글씨 수식을 찾지 못했습니다.', 'idle');
      return false;
    }
    return processPendingMath(group, { force: true });
  }

  function pointerPageIndex(event) {
    const canvas = event.target.closest?.('.page-canvas');
    return canvas ? Number(canvas.dataset.pageIndex) : api.state.currentPageIndex;
  }

  function handlePostStroke(event) {
    if (event.defaultPrevented || event.pointerType === 'touch') return;
    const pageIndex = pointerPageIndex(event);
    setTimeout(() => registerNewMathStrokes(pageIndex), 0);
  }

  function hsvToRgb(h, s, v) {
    const c = v * s;
    const section = ((h % 360) + 360) % 360 / 60;
    const x = c * (1 - Math.abs(section % 2 - 1));
    const m = v - c;
    let rgb = [0, 0, 0];
    if (section < 1) rgb = [c, x, 0];
    else if (section < 2) rgb = [x, c, 0];
    else if (section < 3) rgb = [0, c, x];
    else if (section < 4) rgb = [0, x, c];
    else if (section < 5) rgb = [x, 0, c];
    else rgb = [c, 0, x];
    return rgb.map((value) => Math.round((value + m) * 255));
  }

  function rgbToHex(rgb) {
    return `#${rgb.map((value) => clamp(Math.round(value), 0, 255).toString(16).padStart(2, '0')).join('')}`;
  }

  function hexToHsv(hex) {
    const normalized = /^#[0-9a-f]{6}$/i.test(hex || '') ? hex.slice(1) : '147bd1';
    const [r, g, b] = [0, 2, 4].map((index) => parseInt(normalized.slice(index, index + 2), 16) / 255);
    const max = Math.max(r, g, b), min = Math.min(r, g, b), delta = max - min;
    let h = 0;
    if (delta) {
      if (max === r) h = 60 * (((g - b) / delta) % 6);
      else if (max === g) h = 60 * ((b - r) / delta + 2);
      else h = 60 * ((r - g) / delta + 4);
    }
    if (h < 0) h += 360;
    return { h, s: max ? delta / max : 0, v: max };
  }

  function selectedColor() {
    if (colorTarget === 'highlighter') return api.state.highlighterColor;
    if (colorTarget === 'sticky') return api.state.stickyColor;
    if (colorTarget === 'tape') return api.state.tapeColor;
    if (colorTarget === 'selection') {
      const objects = selectedObjects();
      return objects.find((object) => /^#[0-9a-f]{6}$/i.test(object.color || ''))?.color || api.state.color;
    }
    return api.state.color;
  }

  function currentMixedColor() {
    return rgbToHex(hsvToRgb(colorState.h, colorState.s, colorState.v));
  }

  function updateColorMixer() {
    const spectrum = $('#colorSpectrum');
    const selector = $('#colorSpectrumSelector');
    const preview = $('#colorMixerPreview');
    const hue = $('#colorHue');
    if (!spectrum || !selector || !preview || !hue) return;
    spectrum.style.setProperty('--hue-color', `hsl(${colorState.h} 100% 50%)`);
    selector.style.left = `${colorState.s * 100}%`;
    selector.style.top = `${(1 - colorState.v) * 100}%`;
    preview.style.background = currentMixedColor();
    hue.value = String(colorState.h);
    hue.style.setProperty('--hue-thumb', `hsl(${colorState.h} 100% 50%)`);
  }

  function setSpectrumPoint(event) {
    const spectrum = $('#colorSpectrum');
    if (!spectrum) return;
    const rect = spectrum.getBoundingClientRect();
    colorState.s = clamp((event.clientX - rect.left) / rect.width, 0, 1);
    colorState.v = clamp(1 - (event.clientY - rect.top) / rect.height, 0, 1);
    updateColorMixer();
  }

  function recentColors() {
    try { return JSON.parse(localStorage.getItem('inkforge-recent-colors') || '[]').filter((item) => /^#[0-9a-f]{6}$/i.test(item)).slice(0, 10); }
    catch { return []; }
  }

  function rememberColor(color) {
    const values = [color, ...recentColors().filter((item) => item.toLowerCase() !== color.toLowerCase())].slice(0, 10);
    try { localStorage.setItem('inkforge-recent-colors', JSON.stringify(values)); } catch {}
  }

  function renderPaletteTable() {
    const base = [
      ['#111827','#374151','#6b7280','#9ca3af','#d1d5db','#ffffff'],
      ['#7f1d1d','#dc2626','#f97316','#f59e0b','#eab308','#84cc16'],
      ['#166534','#16a34a','#10b981','#14b8a6','#06b6d4','#0ea5e9'],
      ['#1e40af','#2563eb','#4f46e5','#7c3aed','#9333ea','#c026d3'],
      ['#9d174d','#db2777','#f472b6','#fb7185','#fca5a5','#fecaca'],
      ['#713f12','#a16207','#ca8a04','#d97706','#92400e','#78350f']
    ].flat();
    const table = $('#colorPaletteTable');
    const recent = $('#recentColorRow');
    if (table) table.innerHTML = base.map((color) => `<button class="palette-cell" data-mixer-color="${color}" style="--cell:${color}" aria-label="색상 선택"></button>`).join('');
    if (recent) recent.innerHTML = recentColors().map((color) => `<button class="recent-color" data-mixer-color="${color}" style="--cell:${color}" aria-label="최근 색상"></button>`).join('') || '<span class="empty-recent">아직 저장된 색상이 없습니다.</span>';
  }

  function openColorMixer(target = null) {
    colorTarget = target || (api.state.tool === 'highlighter' ? 'highlighter' : api.state.tool === 'sticky' ? 'sticky' : api.state.tool === 'tape' ? 'tape' : 'pen');
    colorState = hexToHsv(selectedColor());
    renderPaletteTable();
    updateColorMixer();
    $('#modalBackdrop').hidden = false;
    $('#colorMixerSheet').hidden = false;
    document.body.classList.add('modal-open');
  }

  function closeColorMixer() {
    const sheet = $('#colorMixerSheet');
    if (sheet) sheet.hidden = true;
    const other = $$('.modal').some((node) => !node.hidden);
    if (!other) {
      $('#modalBackdrop').hidden = true;
      document.body.classList.remove('modal-open');
    }
  }

  function applyMixedColor() {
    const color = currentMixedColor();
    if (colorTarget === 'highlighter') api.state.highlighterColor = color;
    else if (colorTarget === 'sticky') api.state.stickyColor = color;
    else if (colorTarget === 'tape') api.state.tapeColor = color;
    else if (colorTarget === 'selection') applySelectionColor(color);
    else api.state.color = color;
    rememberColor(color);
    closeColorMixer();
    api.renderActiveToolMenu?.();
    toast('새 색상을 적용했습니다.');
  }

  function selectedObjects() {
    const doc = currentDocument();
    const selection = api?.state?.selection;
    const page = doc?.pages?.[selection?.pageIndex];
    if (!page || !selection?.ids?.length) return [];
    return page.objects.filter((object) => selection.ids.includes(object.id));
  }

  async function applySelectionColor(color) {
    const doc = currentDocument();
    const selection = api?.state?.selection;
    const objects = selectedObjects();
    if (!doc || !selection || !objects.length) return;
    api.checkpoint?.('selection-color');
    objects.forEach((object) => {
      if (!object.locked) object.color = color;
    });
    api.renderPageCanvas?.(selection.pageIndex);
    api.updateObjectMenu?.();
    try { await api.persistCurrent?.(); }
    catch { try { await api.storage?.putDocument?.(doc); } catch {} }
  }

  function injectColorMixer() {
    if ($('#colorMixerSheet')) return;
    const sheet = document.createElement('section');
    sheet.id = 'colorMixerSheet';
    sheet.className = 'sheet modal color-mixer-sheet';
    sheet.hidden = true;
    sheet.setAttribute('aria-label', '색상 조합기');
    sheet.innerHTML = `
      <header class="sheet-header"><div><span class="eyebrow">Color Mixer</span><h2>색상 조합기</h2></div><button class="icon-button" data-action32="close-color-mixer" aria-label="닫기">${SVG.close}</button></header>
      <div class="color-mixer-layout">
        <div class="color-spectrum-wrap">
          <div id="colorSpectrum" class="color-spectrum" role="slider" aria-label="채도와 밝기"><span id="colorSpectrumSelector" class="color-spectrum-selector"></span></div>
          <input id="colorHue" class="hue-slider" type="range" min="0" max="359" step="1" aria-label="색조" />
          <div class="current-color-row"><span id="colorMixerPreview" class="current-color-preview"></span><div><strong>현재 색상</strong><small>색상판을 누르거나 아래 표에서 고르세요.</small></div></div>
        </div>
        <div class="palette-panel"><strong>색상표</strong><div id="colorPaletteTable" class="color-palette-table"></div><strong>최근 사용</strong><div id="recentColorRow" class="recent-color-row"></div></div>
      </div>
      <div class="sheet-actions"><button class="secondary-button" data-action32="close-color-mixer">취소</button><button class="primary-button" data-action32="apply-color-mixer">${SVG.check}<span>적용</span></button></div>`;
    $('#app').appendChild(sheet);
    const spectrum = $('#colorSpectrum');
    let mixing = false;
    spectrum.addEventListener('pointerdown', (event) => { mixing = true; spectrum.setPointerCapture?.(event.pointerId); setSpectrumPoint(event); });
    spectrum.addEventListener('pointermove', (event) => { if (mixing) setSpectrumPoint(event); });
    spectrum.addEventListener('pointerup', () => { mixing = false; });
    spectrum.addEventListener('pointercancel', () => { mixing = false; });
    $('#colorHue').addEventListener('input', (event) => { colorState.h = Number(event.target.value); updateColorMixer(); });
  }

  function ensureCustomColorButton() {
    const menu = $('#activeToolMenu');
    if (!menu || !['sticky', 'tape'].includes(api.state.tool) || menu.querySelector('[data-action="custom-color"]')) return;
    const button = document.createElement('button');
    button.className = 'color-slot add-color';
    button.dataset.action = 'custom-color';
    button.dataset.colorTarget = api.state.tool;
    button.setAttribute('aria-label', '색상 조합기');
    button.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 5v14M5 12h14"/></svg>';
    const label = menu.querySelector('.active-label');
    menu.insertBefore(button, label || null);
  }

  function recentNativeStylus(event, maxAge = 260) {
    const detail = window.__inkforgeNativeBridge?.lastStylus || window.__inkforgeLastNativeStylus;
    const buttonState = Number(detail?.buttonState || 0);
    const barrelActive = !!(
      detail?.primaryButton ||
      detail?.secondaryButton ||
      detail?.barrelButton ||
      detail?.latchedBarrelButton ||
      window.__inkforgeNativeBridge?.barrelButtonActive ||
      (buttonState & 96) !== 0
    );
    const effectiveMaxAge = barrelActive ? Math.max(maxAge, 3500) : maxAge;
    if (!detail || performance.now() - Number(detail.receivedAt || 0) > effectiveMaxAge) return null;
    if (event && Number.isFinite(detail.x) && Number.isFinite(detail.y)) {
      const dx = Math.abs(Number(detail.x) - event.clientX);
      const dy = Math.abs(Number(detail.y) - event.clientY);
      if ((dx > 90 || dy > 90) && !barrelActive) return null;
    }
    return detail;
  }

  function isBarrelButton(event) {
    const nativeStylus = recentNativeStylus(event);
    const buttonState = Number(nativeStylus?.buttonState || 0);
    return event.pointerType === 'pen' && (
      event.button === 2 ||
      event.button === 5 ||
      (event.buttons & 2) !== 0 ||
      (event.buttons & 32) !== 0 ||
      (event.buttons & 64) !== 0 ||
      (buttonState & 32) !== 0 ||
      (buttonState & 64) !== 0 ||
      !!nativeStylus?.primaryButton ||
      !!nativeStylus?.secondaryButton ||
      !!nativeStylus?.barrelButton ||
      !!nativeStylus?.latchedBarrelButton ||
      !!window.__inkforgeNativeBridge?.barrelButtonActive ||
      !!nativeStylus?.eraser
    );
  }

  function showStylusHud(message, dx = 0, dy = 0) {
    const hud = $('#sPenGestureHud');
    if (!hud) return;
    hud.classList.add('is-visible');
    hud.querySelector('strong').textContent = message;
    hud.style.setProperty('--gesture-x', `${clamp(dx, -90, 90)}px`);
    hud.style.setProperty('--gesture-y', `${clamp(dy, -90, 90)}px`);
  }

  function hideStylusHud() {
    $('#sPenGestureHud')?.classList.remove('is-visible');
  }

  function beginStylusGesture(event) {
    stylusGesture = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, x: event.clientX, y: event.clientY, startedAt: performance.now() };
    showStylusHud('S Pen 버튼 제스처', 0, 0);
  }

  function beginBarrelEraser(event) {
    if (barrelEraser?.pointerId === event.pointerId) return;
    barrelEraser = {
      pointerId: event.pointerId,
      restoreTool: api.state.tool === 'eraser' ? null : api.state.tool,
      startedAt: performance.now()
    };
    if (api.state.tool !== 'eraser') api.setTool('eraser');
    showStylusHud('S Pen 버튼: 누르는 동안 지우개', 0, 0);
  }

  function updateBarrelEraser(event) {
    if (!barrelEraser || barrelEraser.pointerId !== event.pointerId) return;
    showStylusHud('S Pen 버튼: 지우개', 0, 0);
  }

  function finishBarrelEraser(event) {
    if (!barrelEraser || barrelEraser.pointerId !== event.pointerId) return;
    const restoreTool = barrelEraser.restoreTool;
    barrelEraser = null;
    hideStylusHud();
    if (restoreTool && api.state.tool === 'eraser') api.setTool(restoreTool);
  }

  function describeStylusDirection(dx, dy) {
    if (Math.max(Math.abs(dx), Math.abs(dy)) < 30) return '놓으면 펜 ↔ 지우개';
    if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? '다음 페이지' : '이전 페이지';
    return dy < 0 ? '실행 취소' : '다시 실행';
  }

  function performStylusGesture(dx, dy) {
    const absoluteX = Math.abs(dx), absoluteY = Math.abs(dy);
    if (Math.max(absoluteX, absoluteY) < 42) {
      const next = api.state.tool === 'eraser' ? (api.state.lastWritingTool || 'pen') : 'eraser';
      api.setTool(next);
      toast(next === 'eraser' ? 'S Pen 버튼: 지우개' : 'S Pen 버튼: 필기 도구');
      return;
    }
    if (absoluteX > absoluteY * 1.15) {
      const doc = currentDocument();
      if (!doc) return;
      const nextPage = clamp(api.state.currentPageIndex + (dx > 0 ? 1 : -1), 0, doc.pages.length - 1);
      api.scrollToPage?.(nextPage);
      toast(dx > 0 ? 'S Pen: 다음 페이지' : 'S Pen: 이전 페이지');
    } else if (dy < 0) {
      api.undo?.(); toast('S Pen: 실행 취소');
    } else {
      api.redo?.(); toast('S Pen: 다시 실행');
    }
  }

  function handleStylusCaptureDown(event) {
    if (!api.state.settings.sPenGestures || !isBarrelButton(event)) return;
    beginBarrelEraser(event);
    try { event.currentTarget.setPointerCapture?.(event.pointerId); } catch {}
  }

  function handleStylusCaptureMove(event) {
    if (!api.state.settings.sPenGestures) return;
    if (isBarrelButton(event)) {
      if (!barrelEraser) beginBarrelEraser(event);
      updateBarrelEraser(event);
      return;
    }
    finishBarrelEraser(event);
    if (!stylusGesture || stylusGesture.pointerId !== event.pointerId) return;
    stylusGesture.x = event.clientX; stylusGesture.y = event.clientY;
    const dx = stylusGesture.x - stylusGesture.startX, dy = stylusGesture.y - stylusGesture.startY;
    showStylusHud(describeStylusDirection(dx, dy), dx, dy);
  }

  function handleStylusCaptureUp(event) {
    if (barrelEraser?.pointerId === event.pointerId) {
      finishBarrelEraser(event);
      return;
    }
    if (!stylusGesture || stylusGesture.pointerId !== event.pointerId) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const dx = event.clientX - stylusGesture.startX, dy = event.clientY - stylusGesture.startY;
    stylusGesture = null;
    hideStylusHud();
    if (performance.now() - lastStylusActionAt < 120) return;
    lastStylusActionAt = performance.now();
    performStylusGesture(dx, dy);
  }

  function handleAirAction(action) {
    if (!api.state.settings.sPenGestures) return;
    const mapping = {
      left: () => api.scrollToPage?.(clamp(api.state.currentPageIndex - 1, 0, (currentDocument()?.pages.length || 1) - 1)),
      right: () => api.scrollToPage?.(clamp(api.state.currentPageIndex + 1, 0, (currentDocument()?.pages.length || 1) - 1)),
      up: () => api.undo?.(),
      down: () => api.redo?.(),
      click: () => api.setTool(api.state.tool === 'eraser' ? (api.state.lastWritingTool || 'pen') : 'eraser'),
      doubleClick: () => api.setTool('lasso')
    };
    mapping[action]?.();
  }

  function handleRemoteKey(event) {
    if (api.state.view !== 'editor' || !api.state.settings.sPenGestures) return;
    const map = {
      PageDown: 'right', MediaTrackNext: 'right', ArrowRight: event.altKey ? 'right' : null,
      PageUp: 'left', MediaTrackPrevious: 'left', ArrowLeft: event.altKey ? 'left' : null,
      MediaPlayPause: 'click'
    };
    const action = map[event.key];
    if (action) { event.preventDefault(); handleAirAction(action); }
  }

  function injectStylusHud() {
    if ($('#sPenGestureHud')) return;
    const hud = document.createElement('div');
    hud.id = 'sPenGestureHud';
    hud.className = 'spen-gesture-hud';
    hud.innerHTML = `${SVG.gesture}<strong>S Pen 버튼 제스처</strong><span class="spen-direction-dot"></span>`;
    $('#editorView').appendChild(hud);
  }

  function injectAutoMathStatus() {
    if ($('#autoMathStatus')) return;
    const status = document.createElement('div');
    status.id = 'autoMathStatus';
    status.className = 'auto-math-status';
    status.innerHTML = `${SVG.math}<span>손글씨 수식 자동 계산</span>`;
    $('#editorView').appendChild(status);
  }

  function injectSettings() {
    const list = $('#settingsSheet .settings-list');
    if (!list || $('#autoMathToggle')) return;
    const autoMath = document.createElement('label');
    autoMath.className = 'setting-row';
    autoMath.innerHTML = `<span><strong>손글씨 수식 자동 계산</strong><small>기본은 꺼져 있으며, 필요할 때 문서 옵션에서 현재 페이지 수식을 계산할 수 있습니다.</small></span><input id="autoMathToggle" type="checkbox" />`;
    const spen = document.createElement('label');
    spen.className = 'setting-row';
    spen.innerHTML = `<span><strong>S Pen 버튼 지우개</strong><small>펜 버튼을 누르는 동안 지우개로 쓰고, 놓으면 이전 도구로 돌아갑니다.</small></span><input id="sPenGesturesToggle" type="checkbox" />`;
    list.append(autoMath, spen);
    $('#autoMathToggle').checked = api.state.settings.autoMath === true;
    $('#sPenGesturesToggle').checked = api.state.settings.sPenGestures !== false;
    list.addEventListener('change', async (event) => {
      if (event.target.id === 'autoMathToggle') api.state.settings.autoMath = event.target.checked;
      if (event.target.id === 'sPenGesturesToggle') api.state.settings.sPenGestures = event.target.checked;
      if (['autoMathToggle', 'sPenGesturesToggle'].includes(event.target.id)) await api.storage.setSetting('preferences', api.state.settings);
    });
    document.addEventListener('click', (event) => {
      const settingsButton = event.target.closest?.('[data-action="open-settings"]');
      if (!settingsButton) return;
      setTimeout(() => {
        $('#autoMathToggle').checked = api.state.settings.autoMath === true;
        $('#sPenGesturesToggle').checked = api.state.settings.sPenGestures !== false;
      }, 0);
    }, true);
  }

  function rectanglesOverlap(a, b, margin = 8) {
    return a.left < b.right + margin && a.right + margin > b.left && a.top < b.bottom + margin && a.bottom + margin > b.top;
  }

  function resolveToolbarCollisions() {
    collisionFrame = 0;
    const editor = $('#editorView');
    const dock = $('#activeToolDock');
    const undo = $('#undoPill');
    if (!editor || !dock || !undo || api.state.view !== 'editor') return;
    editor.classList.remove('toolbar-collision');
    undo.classList.remove('is-relocated');
    const mobile = matchMedia('(max-width: 840px)').matches;
    if (mobile) { undo.classList.add('is-relocated'); return; }
    const dockRect = dock.getBoundingClientRect(), undoRect = undo.getBoundingClientRect();
    if (rectanglesOverlap(dockRect, undoRect, 12)) {
      editor.classList.add('toolbar-collision');
      undo.classList.add('is-relocated');
    }
  }

  function scheduleCollisionResolution() {
    if (collisionFrame) return;
    collisionFrame = requestAnimationFrame(resolveToolbarCollisions);
  }

  function handleCaptureClick(event) {
    const action32 = event.target.closest?.('[data-action32]')?.dataset.action32;
    if (action32) {
      event.preventDefault(); event.stopImmediatePropagation();
      if (action32 === 'close-color-mixer') closeColorMixer();
      else if (action32 === 'apply-color-mixer') applyMixedColor();
      return;
    }
    const palette = event.target.closest?.('[data-mixer-color]');
    if (palette) {
      event.preventDefault(); event.stopImmediatePropagation();
      colorState = hexToHsv(palette.dataset.mixerColor);
      updateColorMixer();
      return;
    }
    const custom = event.target.closest?.('[data-action="custom-color"]');
    if (custom) {
      event.preventDefault(); event.stopImmediatePropagation();
      openColorMixer(custom.dataset.colorTarget || null);
      return;
    }
    const oldMath = event.target.closest?.('[data-action="open-math"], [data-tool="math"]');
    if (oldMath) {
      event.preventDefault(); event.stopImmediatePropagation();
      toast('수식은 필기하면 자동으로 인식·계산됩니다.');
    }
  }

  function monitorUi() {
    const observer = new MutationObserver(() => {
      ensureCustomColorButton();
      scheduleCollisionResolution();
    });
    observer.observe($('#activeToolMenu'), { childList: true, subtree: true });
    observer.observe($('#editorView'), { attributes: true, attributeFilter: ['class'] });
    new ResizeObserver(scheduleCollisionResolution).observe($('#activeToolMenu'));
    window.addEventListener('resize', scheduleCollisionResolution, { passive: true });
  }

  function installEvents() {
    const stack = $('#pageStack');
    stack.addEventListener('pointerdown', handleStylusCaptureDown, true);
    stack.addEventListener('pointermove', handleStylusCaptureMove, true);
    stack.addEventListener('pointerup', handleStylusCaptureUp, true);
    stack.addEventListener('pointercancel', handleStylusCaptureUp, true);
    stack.addEventListener('pointerup', handlePostStroke, false);
    document.addEventListener('click', handleCaptureClick, true);
    document.addEventListener('keydown', handleRemoteKey, true);
    $('#modalBackdrop').addEventListener('click', () => { if (!$('#colorMixerSheet').hidden) closeColorMixer(); }, true);
  }

  async function initialize() {
    for (let attempt = 0; attempt < 200; attempt++) {
      if (window.__inkforge?.ready && window.__inkforgeRecognitionReady) { api = window.__inkforge; break; }
      await new Promise((resolve) => setTimeout(resolve, 40));
    }
    if (!api) return;
    api.state.settings.autoMath = api.state.settings.autoMath === true;
    api.state.settings.sPenGestures = api.state.settings.sPenGestures !== false;
    seedObservedStrokes();
    injectColorMixer();
    injectStylusHud();
    injectAutoMathStatus();
    injectSettings();
    installEvents();
    monitorUi();
    ensureCustomColorButton();
    scheduleCollisionResolution();
    window.InkForgeSPenBridge = { onAirAction: handleAirAction };
    window.__inkforge32 = {
      VERSION,
      openColorMixer,
      processMathStrokes: async (strokes, pageIndex = api.state.currentPageIndex) => processPendingMath({ pageIndex, strokes, bounds: unionBounds(strokes), startedAt: now(), updatedAt: now() }, { force: true }),
      processCurrentPageMath,
      recognizeAndEvaluate,
      handleAirAction,
      resolveToolbarCollisions,
      ready: true
    };
  }

  initialize().catch((error) => {
    console.error('InkForge 3.3 upgrade initialization failed', error);
    window.__inkforge32 = { VERSION, ready: false, error: String(error) };
  });
})();
