(() => {
  'use strict';

  const VERSION = '3.3.2';
  const PAGE_WIDTH = 1000;
  const PAGE_HEIGHT = 1414;
  const nativeApi = window.InkForgeNative;
  const pending = new Map();
  const ocrTimers = new Map();
  const pdfOcrQueue = [];
  const pdfQueuedKeys = new Set();
  let pdfOcrBusy = false;
  let api = null;
  let localTextRecognizer = null;
  let localMathRecognizer = null;
  let lastStylusEventAt = 0;
  let lastStylusDetail = null;
  let stylusGesture = null;
  let eraserRestoreTool = null;
  let pullState = null;
  let pullIndicator = null;
  let modelStatusNode = null;

  const uid = (prefix = 'id') => `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 9)}`;
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  window.__inkforgeNativeCallbacks = window.__inkforgeNativeCallbacks || {
    resolve(requestId, envelope) {
      const request = pending.get(requestId);
      if (!request) return;
      pending.delete(requestId);
      clearTimeout(request.timer);
      const parsed = typeof envelope === 'string' ? JSON.parse(envelope) : envelope;
      if (parsed?.ok) request.resolve(parsed.data);
      else request.reject(new Error(parsed?.error || '네이티브 인식 오류'));
    }
  };

  function nativeAvailable() {
    return !!nativeApi && typeof nativeApi.recognizeInk === 'function';
  }

  function callNative(method, payload, timeout = 90000) {
    if (!nativeApi || typeof nativeApi[method] !== 'function') return Promise.reject(new Error('네이티브 인식 엔진을 사용할 수 없습니다.'));
    const requestId = uid('native');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(requestId);
        reject(new Error('인식 시간이 초과되었습니다.'));
      }, timeout);
      pending.set(requestId, { resolve, reject, timer });
      try {
        if (method === 'downloadInkModel') nativeApi[method](requestId, String(payload));
        else nativeApi[method](requestId, JSON.stringify(payload));
      } catch (error) {
        clearTimeout(timer);
        pending.delete(requestId);
        reject(error);
      }
    });
  }

  function nativeCapabilities() {
    if (!nativeApi || typeof nativeApi.capabilities !== 'function') return null;
    try {
      const value = nativeApi.capabilities();
      return typeof value === 'string' ? JSON.parse(value) : value;
    } catch {
      return null;
    }
  }

  function pointTime(point, fallback) {
    const value = Number(point?.t);
    return Number.isFinite(value) ? Math.max(0, Math.round(value)) : fallback;
  }

  function normalizeStrokes(strokes) {
    let time = Date.now();
    return (strokes || []).map((stroke) => {
      const points = (stroke?.points || stroke || []).filter((point) => Number.isFinite(point?.x) && Number.isFinite(point?.y));
      const output = points.map((point, index) => ({
        x: Number(point.x),
        y: Number(point.y),
        t: pointTime(point, time + index * 8)
      }));
      time += Math.max(24, output.length * 8 + 16);
      return output;
    }).filter((stroke) => stroke.length);
  }

  function strokeBounds(stroke) {
    const points = stroke?.points || stroke || [];
    if (!points.length) return { x: 0, y: 0, w: 1, h: 1 };
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const point of points) {
      minX = Math.min(minX, point.x); minY = Math.min(minY, point.y);
      maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y);
    }
    return { x: minX, y: minY, w: Math.max(1, maxX - minX), h: Math.max(1, maxY - minY) };
  }

  function unionBounds(strokes) {
    const boxes = (strokes || []).map(strokeBounds);
    if (!boxes.length) return { x: 0, y: 0, w: 1, h: 1 };
    const minX = Math.min(...boxes.map((box) => box.x));
    const minY = Math.min(...boxes.map((box) => box.y));
    const maxX = Math.max(...boxes.map((box) => box.x + box.w));
    const maxY = Math.max(...boxes.map((box) => box.y + box.h));
    return { x: minX, y: minY, w: Math.max(1, maxX - minX), h: Math.max(1, maxY - minY) };
  }

  function verticalOverlap(a, b) {
    const overlap = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
    return overlap / Math.max(1, Math.min(a.h, b.h));
  }

  function groupStrokeLines(strokes) {
    const items = (strokes || []).map((stroke) => ({ stroke, bounds: strokeBounds(stroke) }))
      .sort((a, b) => (a.bounds.y + a.bounds.h / 2) - (b.bounds.y + b.bounds.h / 2));
    const lines = [];
    for (const item of items) {
      const centerY = item.bounds.y + item.bounds.h / 2;
      let best = null;
      let bestDistance = Infinity;
      for (const line of lines) {
        const distance = Math.abs(centerY - line.centerY);
        const threshold = Math.max(24, Math.max(item.bounds.h, line.height) * .72);
        if ((verticalOverlap(item.bounds, line.bounds) > .18 || distance < threshold) && distance < bestDistance) {
          best = line;
          bestDistance = distance;
        }
      }
      if (!best) {
        best = { items: [], bounds: { ...item.bounds }, centerY, height: item.bounds.h };
        lines.push(best);
      }
      best.items.push(item);
      const minX = Math.min(best.bounds.x, item.bounds.x);
      const minY = Math.min(best.bounds.y, item.bounds.y);
      const maxX = Math.max(best.bounds.x + best.bounds.w, item.bounds.x + item.bounds.w);
      const maxY = Math.max(best.bounds.y + best.bounds.h, item.bounds.y + item.bounds.h);
      best.bounds = { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
      best.centerY = minY + (maxY - minY) / 2;
      best.height = maxY - minY;
    }
    return lines.sort((a, b) => a.bounds.y - b.bounds.y).map((line) => ({
      bounds: line.bounds,
      strokes: line.items.sort((a, b) => a.bounds.x - b.bounds.x).map((item) => item.stroke)
    }));
  }

  async function recognizeNativeInk(strokes, languageTag, options = {}) {
    const normalized = normalizeStrokes(strokes);
    if (!normalized.length) throw new Error('인식할 필기 획이 없습니다.');
    const bounds = unionBounds(normalized);
    const shifted = normalized.map((stroke) => stroke.map((point) => ({
      x: point.x - bounds.x + 12,
      y: point.y - bounds.y + 12,
      t: point.t
    })));
    return callNative('recognizeInk', {
      languageTag,
      strokes: shifted,
      width: Math.max(48, bounds.w + 24),
      height: Math.max(48, bounds.h + 24),
      preContext: options.preContext || '',
      maxCandidates: options.maxCandidates || 10
    }, options.timeout || 90000);
  }

  function candidateTexts(result) {
    const output = [];
    for (const value of [result?.text, ...(result?.candidates || []).map((item) => item?.text)]) {
      const text = String(value || '').trim();
      if (text && !output.includes(text)) output.push(text);
    }
    return output;
  }

  async function nativeTextRecognizer(strokes, mode = 'auto', progress = () => {}) {
    if (!nativeAvailable()) return localTextRecognizer(strokes, mode, progress);
    const languageTag = mode === 'en' ? 'en-US' : 'ko';
    try {
      const lines = groupStrokeLines(strokes);
      const output = [];
      const alternatives = [];
      let preContext = '';
      for (let index = 0; index < lines.length; index++) {
        progress(`네이티브 ${languageTag === 'ko' ? '한글' : '영문'} 인식 ${index + 1}/${lines.length}`);
        const result = await recognizeNativeInk(lines[index].strokes, languageTag, {
          preContext: preContext.slice(-120),
          maxCandidates: 8
        });
        const choices = candidateTexts(result);
        output.push(choices[0] || '');
        alternatives.push(choices.slice(1));
        preContext += `${choices[0] || ''}\n`;
      }
      const text = output.join('\n').trim();
      if (!text) throw new Error('인식 결과가 비어 있습니다.');
      return {
        text,
        confidence: clamp(.88 - Math.max(0, lines.length - 6) * .015, .55, .93),
        alternatives,
        details: [],
        lines: lines.length,
        bounds: unionBounds(strokes),
        engine: 'mlkit-digital-ink'
      };
    } catch (error) {
      console.warn('Native text recognition fallback', error);
      return localTextRecognizer(strokes, mode, progress);
    }
  }

  function normalizeMathBase(raw) {
    let value = String(raw || '').normalize('NFKC').trim();
    value = value
      .replace(/\s+/g, '')
      .replace(/[−–—﹣]/g, '-')
      .replace(/[×✕✖·∙]/g, '*')
      .replace(/[÷∕]/g, '/')
      .replace(/[［【]/g, '[').replace(/[］】]/g, ']')
      .replace(/\[/g, '(').replace(/\]/g, ')')
      .replace(/[，]/g, '.').replace(/[。]/g, '.')
      .replace(/√(?=\d|\()/g, 'sqrt')
      .replace(/π/g, 'pi')
      .replace(/[?？]+$/g, '')
      .replace(/=+$/g, '');
    const equal = value.indexOf('=');
    if (equal > 0) value = value.slice(0, equal);
    return value;
  }

  function mathVariants(raw) {
    const base = normalizeMathBase(raw);
    const output = new Set();
    const add = (value) => {
      value = String(value || '').replace(/\*{2}/g, '^').replace(/\.{2,}/g, '.');
      if (value) output.add(value);
    };
    add(base);
    add(base.replace(/[xX]/g, '*'));
    add(base.replace(/[oO]/g, '0'));
    add(base.replace(/[lI|]/g, '1'));
    add(base.replace(/[sS]/g, '5').replace(/[bB]/g, '8'));
    add(base.replace(/:/g, '/'));
    add(base.replace(/,/g, '.'));
    add(base.replace(/([0-9)])(?=\()/g, '$1*'));
    add(base.replace(/([0-9)])(?=[a-z])/gi, '$1*'));
    const repaired = base
      .replace(/(?<=\d)[oO](?=\d|[+\-*/^)])/g, '0')
      .replace(/(?<=[(=+\-*/^])[oO](?=\d)/g, '0')
      .replace(/(?<=\d)[lI|](?=\d|[+\-*/^)])/g, '1')
      .replace(/[xX](?=\d|\()/g, '*');
    add(repaired);
    const opens = (base.match(/\(/g) || []).length;
    const closes = (base.match(/\)/g) || []).length;
    if (opens > closes) add(base + ')'.repeat(opens - closes));
    return [...output].slice(0, 24);
  }

  function evaluateCandidate(expression) {
    try {
      const result = api.evaluateMath(expression, { degree: !!api.state.math?.degree });
      if (!Number.isFinite(result?.result)) return null;
      return result;
    } catch {
      return null;
    }
  }

  async function nativeMathRecognizer(strokes, progress = () => {}) {
    const localPromise = localMathRecognizer ? localMathRecognizer(strokes, progress).catch(() => null) : Promise.resolve(null);
    if (!nativeAvailable()) return localPromise;
    progress('네이티브 숫자·기호 인식 중…');
    const nativePromise = recognizeNativeInk(strokes, 'en-US', { maxCandidates: 16 }).catch(() => null);
    const [nativeResult, localResult] = await Promise.all([nativePromise, localPromise]);
    const rawCandidates = [
      ...candidateTexts(nativeResult),
      localResult?.expression,
      ...(localResult?.alternatives || [])
    ].filter(Boolean);
    const candidates = [];
    for (const raw of rawCandidates) {
      for (const expression of mathVariants(raw)) {
        if (!candidates.includes(expression)) candidates.push(expression);
      }
    }
    const valid = candidates.map((expression) => ({ expression, calculation: evaluateCandidate(expression) }))
      .filter((item) => item.calculation);
    if (!valid.length) {
      if (localResult?.expression) return localResult;
      return {
        expression: normalizeMathBase(rawCandidates[0] || ''),
        alternatives: candidates.slice(0, 8),
        confidence: .28,
        details: [],
        engine: 'mlkit-digital-ink'
      };
    }
    const best = valid[0];
    return {
      expression: best.expression,
      alternatives: valid.slice(0, 8).map((item) => item.expression),
      confidence: nativeResult ? .86 : .68,
      details: [],
      engine: nativeResult ? 'mlkit-digital-ink+math-parser' : 'local-math-parser'
    };
  }

  function pageByDetail(detail) {
    const doc = api.state.documents.find((item) => item.id === detail?.documentId) || api.currentDocument();
    if (!doc) return { doc: null, page: null, index: -1 };
    let index = Number.isFinite(detail?.pageIndex) ? Number(detail.pageIndex) : -1;
    if (detail?.pageId) index = doc.pages.findIndex((page) => page.id === detail.pageId);
    if (index < 0) index = api.state.currentPageIndex;
    return { doc, page: doc.pages[index] || null, index };
  }

  function sourceHash(objects) {
    return objects.map((object) => object.id).join('|');
  }

  function pdfQueueKey(detail) {
    return `${detail?.documentId || api?.state?.currentDocumentId || 'doc'}:${detail?.pageId || detail?.pageIndex || 0}`;
  }

  function shouldEagerPdfOcr(detail) {
    const { doc, index } = pageByDetail(detail);
    if (!doc) return true;
    if ((doc.pages?.length || 0) <= 24) return true;
    return Math.abs(index - api.state.currentPageIndex) <= 2;
  }

  async function autoIndexPage(detail, force = false) {
    if (!api?.state.settings.autoOcr) return;
    const { doc, page, index } = pageByDetail(detail);
    if (!doc || !page) return;
    const objects = (page.objects || []).filter((object) => object.type === 'stroke' && object.brush !== 'highlighter' && object.points?.length && !object.autoShapeSource);
    if (!objects.length) return;
    const hash = sourceHash(objects);
    const existing = page.objects.find((object) => object.type === 'ocrIndex' && object.source === 'auto-native');
    if (!force && existing?.sourceHash === hash) return;
    try {
      const result = await nativeTextRecognizer(objects.map((object) => object.points), 'ko', () => {});
      const text = String(result?.text || '').trim();
      if (!text) return;
      const bounds = unionBounds(objects.map((object) => object.points));
      if (existing) {
        Object.assign(existing, {
          text,
          x: bounds.x, y: bounds.y, w: bounds.w, h: bounds.h,
          sourceHash: hash,
          sourceStrokeIds: objects.map((object) => object.id),
          updatedAt: new Date().toISOString(),
          engine: result.engine || 'mlkit-digital-ink'
        });
      } else {
        page.objects.push({
          id: uid('ocr'), type: 'ocrIndex', source: 'auto-native', hidden: true, locked: true,
          text, x: bounds.x, y: bounds.y, w: bounds.w, h: bounds.h,
          sourceHash: hash,
          sourceStrokeIds: objects.map((object) => object.id),
          createdAt: new Date().toISOString(),
          engine: result.engine || 'mlkit-digital-ink'
        });
      }
      doc.updatedAt = new Date().toISOString();
      await api.storage.putDocument(doc);
      if (doc.id === api.state.currentDocumentId) api.renderDocumentSearch?.();
      setRecognitionChip('OCR 자동 색인 완료', 'ready');
    } catch (error) {
      console.warn('Auto OCR failed', error);
      setRecognitionChip('OCR 대기', 'idle');
    }
  }

  function scheduleAutoOcr(detail, delayMs = 1300) {
    if (!api?.state.settings.autoOcr) return;
    const key = `${detail?.documentId || api.state.currentDocumentId}:${detail?.pageId || detail?.pageIndex || api.state.currentPageIndex}`;
    clearTimeout(ocrTimers.get(key));
    ocrTimers.set(key, setTimeout(() => {
      ocrTimers.delete(key);
      void autoIndexPage(detail);
    }, delayMs));
    setRecognitionChip('OCR 자동 색인 대기', 'busy');
  }

  function blobToDataUrl(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(blob);
    });
  }

  function enqueuePdfImageOcr(detail, options = {}) {
    if (!detail?.needsImageOcr || !nativeApi || typeof nativeApi.recognizeKoreanImage !== 'function') return;
    if (!options.force && !shouldEagerPdfOcr(detail)) return;
    const key = pdfQueueKey(detail);
    if (pdfQueuedKeys.has(key)) return;
    pdfQueuedKeys.add(key);
    if (options.priority) pdfOcrQueue.unshift(detail);
    else pdfOcrQueue.push(detail);
    void processPdfOcrQueue();
  }

  async function processPdfOcrQueue() {
    if (pdfOcrBusy || !pdfOcrQueue.length) return;
    pdfOcrBusy = true;
    while (pdfOcrQueue.length) {
      const detail = pdfOcrQueue.shift();
      pdfQueuedKeys.delete(pdfQueueKey(detail));
      const { doc, page } = pageByDetail(detail);
      if (!doc || !page?.backgroundAssetId || page.objects?.some((object) => object.type === 'ocrIndex' && object.source === 'pdf-image')) continue;
      try {
        setRecognitionChip(`PDF OCR ${page.pdfPageNumber || ''}`, 'busy');
        const asset = await api.storage.getAsset(page.backgroundAssetId);
        if (!asset?.blob) continue;
        const dataUrl = await blobToDataUrl(asset.blob);
        const result = await callNative('recognizeKoreanImage', { dataUrl }, 120000);
        const text = String(result?.text || '').trim();
        if (text) {
          page.objects.push({
            id: uid('ocr'), type: 'ocrIndex', source: 'pdf-image', hidden: true, locked: true,
            text, x: 0, y: 0, w: PAGE_WIDTH, h: PAGE_HEIGHT,
            createdAt: new Date().toISOString(), engine: 'mlkit-korean-image-ocr'
          });
          page.needsImageOcr = false;
          await api.storage.putDocument(doc);
          if (doc.id === api.state.currentDocumentId) api.renderDocumentSearch?.();
        }
      } catch (error) {
        console.warn('PDF image OCR failed', error);
      }
      await delay((api.currentDocument?.()?.pages?.length || 0) > 24 ? 260 : 90);
    }
    pdfOcrBusy = false;
    setRecognitionChip('OCR 준비됨', 'ready');
  }

  function shapeLabel(result) {
    const labels = candidateTexts(result).map((text) => text.toUpperCase().replace(/[^A-Z]/g, ''));
    for (const label of labels) {
      if (label.includes('RECTANGLE') || label.includes('SQUARE')) return 'rectangle';
      if (label.includes('ELLIPSE') || label.includes('CIRCLE') || label.includes('OVAL')) return 'ellipse';
      if (label.includes('TRIANGLE')) return 'triangle';
      if (label.includes('ARROW')) return 'arrow';
    }
    return null;
  }

  async function maybeConvertNativeShape(detail) {
    if (!nativeAvailable() || detail?.wasShape || !detail?.object || detail.object.type !== 'stroke') return;
    const object = detail.object;
    if (object.brush === 'highlighter' || !object.points?.length) return;
    const bounds = strokeBounds(object.points);
    const diagonal = Math.hypot(bounds.w, bounds.h);
    const first = object.points[0], last = object.points[object.points.length - 1];
    const closure = Math.hypot(first.x - last.x, first.y - last.y);
    const likelyClosed = closure < diagonal * .33 && object.points.length > 10;
    if ((detail.holdDuration || 0) < 140 && !likelyClosed) return;
    try {
      const result = await recognizeNativeInk([object.points], 'zxx-Zsym-x-shapes', { maxCandidates: 6 });
      const kind = shapeLabel(result);
      if (!kind) return;
      const { doc, page, index } = pageByDetail(detail);
      if (!doc || !page) return;
      const objectIndex = page.objects.findIndex((item) => item.id === object.id && item.type === 'stroke');
      if (objectIndex < 0) return;
      let shape = kind;
      if (kind === 'rectangle' && Math.abs(bounds.w - bounds.h) / Math.max(bounds.w, bounds.h) < .16) shape = 'square';
      if (kind === 'ellipse' && Math.abs(bounds.w - bounds.h) / Math.max(bounds.w, bounds.h) < .16) shape = 'circle';
      const replacement = {
        id: object.id,
        type: 'shape',
        shape,
        x1: kind === 'arrow' ? first.x : bounds.x,
        y1: kind === 'arrow' ? first.y : bounds.y,
        x2: kind === 'arrow' ? last.x : bounds.x + bounds.w,
        y2: kind === 'arrow' ? last.y : bounds.y + bounds.h,
        color: object.color,
        width: object.width,
        opacity: object.opacity,
        createdAt: object.createdAt || new Date().toISOString(),
        autoShapeSource: object.points
      };
      page.objects[objectIndex] = replacement;
      await api.persistCurrent();
      if (doc.id === api.state.currentDocumentId) api.scheduleRenderPage?.(index);
      api.renderSidebar?.();
      api.toast?.(`${shape === 'ellipse' || shape === 'circle' ? '원' : shape === 'rectangle' || shape === 'square' ? '네모' : shape === 'triangle' ? '삼각형' : '화살표'} 도형으로 변환했습니다.`, 1700);
    } catch (error) {
      console.warn('Native shape recognition failed', error);
    }
  }

  function setRecognitionChip(text, tone = 'idle') {
    let chip = document.getElementById('nativeRecognitionChip');
    if (!chip) {
      chip = document.createElement('button');
      chip.id = 'nativeRecognitionChip';
      chip.type = 'button';
      chip.className = 'native-status-chip';
      chip.dataset.action = 'native-recognition-status';
      chip.innerHTML = '<span class="native-status-dot"></span><span class="native-status-text"></span>';
      document.body.appendChild(chip);
    }
    chip.dataset.tone = tone;
    chip.querySelector('.native-status-text').textContent = text;
  }

  function setStylusChip(detail) {
    lastStylusEventAt = Date.now();
    let chip = document.getElementById('nativeStylusChip');
    if (!chip) {
      chip = document.createElement('div');
      chip.id = 'nativeStylusChip';
      chip.className = 'native-stylus-chip';
      chip.innerHTML = '<span class="stylus-icon">✎</span><span class="stylus-label">펜 연결됨</span>';
      document.body.appendChild(chip);
    }
    const device = String(detail?.device || '스타일러스');
    chip.querySelector('.stylus-label').textContent = /samsung|s pen/i.test(device) ? 'S Pen 연결됨' : '스타일러스 연결됨';
    chip.classList.add('is-visible');
    clearTimeout(chip._hideTimer);
    chip._hideTimer = setTimeout(() => chip.classList.remove('is-visible'), 2600);
  }

  function rememberNativeStylus(detail) {
    lastStylusDetail = {
      ...detail,
      x: Number(detail?.x),
      y: Number(detail?.y),
      pressure: Number(detail?.pressure),
      toolType: Number(detail?.toolType),
      eraser: Number(detail?.toolType) === 4,
      receivedAt: performance.now()
    };
    window.__inkforgeLastNativeStylus = lastStylusDetail;
  }

  function isRecentStylusEvent(event, maxAge = 220) {
    const detail = lastStylusDetail;
    if (!detail || performance.now() - detail.receivedAt > maxAge) return false;
    if (!event || !Number.isFinite(detail.x) || !Number.isFinite(detail.y)) return true;
    return Math.hypot(Number(event.clientX || 0) - detail.x, Number(event.clientY || 0) - detail.y) <= 96 || !!detail.hover;
  }

  function performStylusGesture(dx, dy) {
    if (!api?.state.settings.sPenGestures) return;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < 38) {
      const next = api.state.tool === 'eraser' ? (api.state.lastWritingTool || 'pen') : 'eraser';
      api.setTool(next);
      api.toast?.(next === 'eraser' ? 'S Pen 버튼: 지우개' : 'S Pen 버튼: 펜');
      return;
    }
    if (Math.abs(dx) > Math.abs(dy)) {
      if (dx < 0) api.undo?.(); else api.redo?.();
      return;
    }
    const doc = api.currentDocument?.();
    if (!doc) return;
    const nextIndex = clamp(api.state.currentPageIndex + (dy < 0 ? -1 : 1), 0, doc.pages.length - 1);
    api.scrollToPage?.(nextIndex);
  }

  function handleNativeStylus(event) {
    const detail = event.detail || {};
    rememberNativeStylus(detail);
    setStylusChip(detail);
    const button = !!(detail.primaryButton || detail.secondaryButton);
    if (button && !stylusGesture) {
      stylusGesture = { x: Number(detail.x || 0), y: Number(detail.y || 0), lastX: Number(detail.x || 0), lastY: Number(detail.y || 0) };
    } else if (button && stylusGesture) {
      stylusGesture.lastX = Number(detail.x || stylusGesture.lastX);
      stylusGesture.lastY = Number(detail.y || stylusGesture.lastY);
    } else if (!button && stylusGesture) {
      const gesture = stylusGesture;
      stylusGesture = null;
      performStylusGesture(gesture.lastX - gesture.x, gesture.lastY - gesture.y);
    }

    const toolType = Number(detail.toolType);
    const action = Number(detail.action);
    if (toolType === 4 && action === 0 && api.state.tool !== 'eraser') {
      eraserRestoreTool = api.state.tool;
      api.setTool('eraser');
    } else if (eraserRestoreTool && (action === 1 || action === 3)) {
      api.setTool(eraserRestoreTool);
      eraserRestoreTool = null;
    }
  }

  function handleNativeStylusKey(event) {
    const detail = event.detail || {};
    if (Number(detail.action) !== 1) return;
    const keyCode = Number(detail.keyCode);
    if ([21, 88, 89].includes(keyCode)) api.undo?.();
    else if ([22, 87, 90].includes(keyCode)) api.redo?.();
    else if ([19, 24].includes(keyCode)) {
      const doc = api.currentDocument?.();
      if (doc) api.scrollToPage?.(clamp(api.state.currentPageIndex - 1, 0, doc.pages.length - 1));
    } else if ([20, 25].includes(keyCode)) {
      const doc = api.currentDocument?.();
      if (doc) api.scrollToPage?.(clamp(api.state.currentPageIndex + 1, 0, doc.pages.length - 1));
    } else if ([211, 212].includes(keyCode)) {
      api.setTool(api.state.tool === 'eraser' ? (api.state.lastWritingTool || 'pen') : 'eraser');
    }
  }

  function ensurePullIndicator() {
    if (pullIndicator) return pullIndicator;
    pullIndicator = document.createElement('div');
    pullIndicator.id = 'pullToAddIndicator';
    pullIndicator.className = 'pull-to-add-indicator';
    pullIndicator.innerHTML = '<span class="pull-icon">↓</span><span class="pull-text">더 당기면 새 페이지</span>';
    document.getElementById('editorViewport')?.appendChild(pullIndicator);
    return pullIndicator;
  }

  function resetPullIndicator() {
    if (pullIndicator) {
      pullIndicator.classList.remove('is-visible', 'is-ready');
      pullIndicator.style.setProperty('--pull-progress', '0');
    }
    pullState = null;
  }

  function bindPullToAdd() {
    const viewport = document.getElementById('editorViewport');
    if (!viewport) return;
    ensurePullIndicator();
    viewport.addEventListener('touchstart', (event) => {
      if (event.touches.length !== 1 || api.state.pageMode !== 'continuous') return;
      const doc = api.currentDocument?.();
      if (!doc || api.state.currentPageIndex !== doc.pages.length - 1) return;
      const atBottom = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight < 32;
      if (!atBottom) return;
      pullState = { startY: event.touches[0].clientY, distance: 0, ready: false };
    }, { passive: true, capture: true });
    viewport.addEventListener('touchmove', (event) => {
      if (!pullState || event.touches.length !== 1) return;
      const distance = Math.max(0, pullState.startY - event.touches[0].clientY);
      pullState.distance = distance;
      pullState.ready = distance >= 92;
      const indicator = ensurePullIndicator();
      indicator.classList.add('is-visible');
      indicator.classList.toggle('is-ready', pullState.ready);
      indicator.style.setProperty('--pull-progress', String(clamp(distance / 92, 0, 1)));
      indicator.querySelector('.pull-text').textContent = pullState.ready ? '놓으면 새 페이지 추가' : '더 당기면 새 페이지';
    }, { passive: true, capture: true });
    viewport.addEventListener('touchend', () => {
      if (pullState?.ready) {
        const doc = api.currentDocument?.();
        if (doc) api.addPage(doc.pages.length - 1);
      }
      resetPullIndicator();
    }, { passive: true, capture: true });
    viewport.addEventListener('touchcancel', resetPullIndicator, { passive: true, capture: true });
  }

  function injectSettings() {
    const list = document.querySelector('#settingsSheet .settings-list');
    if (!list || document.getElementById('nativeAutoOcrToggle')) return;
    const autoRow = document.createElement('label');
    autoRow.className = 'setting-row';
    autoRow.innerHTML = '<span><strong>손글씨 OCR 자동 등록</strong><small>필기를 멈추면 한글 OCR 검색 색인을 자동 갱신합니다.</small></span><input id="nativeAutoOcrToggle" type="checkbox" />';
    list.appendChild(autoRow);
    const toggle = autoRow.querySelector('input');
    toggle.checked = api.state.settings.autoOcr !== false;
    toggle.addEventListener('change', () => {
      api.state.settings.autoOcr = toggle.checked;
      api.storage.setSetting('preferences', api.state.settings);
      if (toggle.checked) scheduleAutoOcr({ documentId: api.state.currentDocumentId, pageIndex: api.state.currentPageIndex }, 200);
    });
    const status = document.createElement('div');
    status.id = 'nativeModelStatus';
    status.className = 'native-model-status';
    status.innerHTML = '<strong>네이티브 인식 엔진</strong><span>모델 상태 확인 중…</span>';
    list.appendChild(status);
    modelStatusNode = status.querySelector('span');
  }

  function handleModelStatus(event) {
    const detail = event.detail || {};
    const label = detail.languageTag === 'ko' ? '한글 OCR' : detail.languageTag === 'en-US' ? '숫자·기호' : '도형';
    const status = detail.status === 'ready' ? '준비됨' : detail.status === 'downloading' ? '다운로드 중' : '오류';
    if (modelStatusNode) modelStatusNode.textContent = `${label}: ${status}`;
    setRecognitionChip(`${label} ${status}`, detail.status === 'ready' ? 'ready' : detail.status === 'downloading' ? 'busy' : 'error');
  }

  function bindGlobalActions() {
    document.addEventListener('click', (event) => {
      const target = event.target.closest('[data-action]');
      if (!target) return;
      if (target.dataset.action === 'new-note-pdf') {
        event.preventDefault();
        event.stopImmediatePropagation();
        window.__inkforgePdf?.openPicker({ createNew: true });
      } else if (target.dataset.action === 'native-recognition-status') {
        event.preventDefault();
        document.querySelector('[data-action="open-settings"]')?.click();
      }
    }, true);
  }

  function scanCurrentPageSoon() {
    const doc = api.currentDocument?.();
    if (!doc) return;
    const page = doc.pages[api.state.currentPageIndex];
    if (page?.objects?.some((object) => object.type === 'stroke')) {
      scheduleAutoOcr({ documentId: doc.id, pageId: page.id, pageIndex: api.state.currentPageIndex }, 700);
    }
    const radius = doc.pages.length > 24 ? 2 : doc.pages.length;
    const start = Math.max(0, api.state.currentPageIndex - radius);
    const end = Math.min(doc.pages.length - 1, api.state.currentPageIndex + radius);
    for (let index = start; index <= end; index++) {
      const item = doc.pages[index];
      if (item?.needsImageOcr) enqueuePdfImageOcr({ documentId: doc.id, pageId: item.id, pageIndex: index, needsImageOcr: true }, { priority: Math.abs(index - api.state.currentPageIndex) <= 1 });
    }
  }

  async function initialize() {
    for (let attempt = 0; attempt < 200; attempt++) {
      if (window.__inkforge?.ready && window.__inkforgeRecognitionReady) {
        api = window.__inkforge;
        break;
      }
      await delay(50);
    }
    if (!api?.recognition) return;
    localTextRecognizer = api.recognition.recognizeTextStrokes.bind(api.recognition);
    localMathRecognizer = api.recognition.recognizeMathStrokes.bind(api.recognition);
    api.recognition.recognizeTextStrokes = nativeTextRecognizer;
    api.recognition.recognizeMathStrokes = nativeMathRecognizer;
    api.recognition.native = {
      version: VERSION,
      capabilities: nativeCapabilities(),
      available: nativeAvailable(),
      recognizeInk: recognizeNativeInk,
      autoIndexPage
    };

    setRecognitionChip(nativeAvailable() ? '네이티브 OCR 준비 중' : '기본 OCR 사용', nativeAvailable() ? 'busy' : 'idle');
    injectSettings();
    bindGlobalActions();
    bindPullToAdd();
    window.addEventListener('inkforge:stroke-committed', (event) => {
      scheduleAutoOcr(event.detail);
      void maybeConvertNativeShape(event.detail);
    });
    window.addEventListener('inkforge:pdf-page-imported', (event) => enqueuePdfImageOcr(event.detail));
    window.addEventListener('inkforge:native-stylus', handleNativeStylus);
    window.addEventListener('inkforge:native-stylus-key', handleNativeStylusKey);
    window.addEventListener('inkforge:native-model-status', handleModelStatus);

    const stack = document.getElementById('pageStack');
    if (stack) new MutationObserver(() => scanCurrentPageSoon()).observe(stack, { childList: true });
    const viewport = document.getElementById('editorViewport');
    if (viewport) {
      let scanTimer = 0;
      viewport.addEventListener('scroll', () => {
        clearTimeout(scanTimer);
        scanTimer = setTimeout(scanCurrentPageSoon, 360);
      }, { passive: true });
    }
    scanCurrentPageSoon();
    window.__inkforgeNativeBridge = {
      version: VERSION,
      ready: true,
      autoIndexPage,
      recognizeNativeInk,
      isRecentStylusEvent,
      get lastStylus() { return lastStylusDetail; }
    };
  }

  initialize().catch((error) => {
    console.error('InkForge native bridge initialization failed', error);
    setRecognitionChip('네이티브 엔진 오류', 'error');
  });
})();
