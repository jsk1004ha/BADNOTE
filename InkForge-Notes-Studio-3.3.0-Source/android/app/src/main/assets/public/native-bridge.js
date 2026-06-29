(() => {
  'use strict';

  const VERSION = '3.3.19';
  const PAGE_WIDTH = 1000;
  const PAGE_HEIGHT = 1414;
  const HANDWRITING_OCR_DWELL_MS = 2800;
  const PDF_OCR_CURRENT_DWELL_MS = 3600;
  const OCR_ACTIVITY_GRACE_MS = 1400;
  const IDLE_OCR_TIMEOUT_MS = 2400;
  const SHAPE_HOLD_MS = 650;
  const BARREL_BUTTON_LATCH_MS = 3500;
  const RELEASE_NOTES = [
    '오른쪽 페이지 스크롤 바는 문서를 조금 스크롤한 뒤 잠시만 표시됩니다.',
    '필기 중에는 페이지 스크롤 바가 숨겨지고 포인터 입력을 받지 않습니다.',
    'S Pen이나 손바닥 터치가 스크롤 바에 닿아 페이지가 이동하는 문제를 줄였습니다.'
  ];
  const RELEASE_NOTES_LAST_VERSION_KEY = 'badnote.releaseNotes.lastVersion';
  const nativeApi = window.InkForgeNative;
  const pending = new Map();
  const ocrTimers = new Map();
  const pdfOcrQueue = [];
  const pdfQueuedKeys = new Set();
  let pdfOcrBusy = false;
  let api = null;
  let localTextRecognizer = null;
  let localMathRecognizer = null;
  let lastUserActivityAt = Date.now();
  let activePageDwell = { documentId: null, pageId: null, pageIndex: -1, enteredAt: Date.now(), timer: 0 };
  let lastStylusEventAt = 0;
  let lastStylusDetail = null;
  let lastStylusChipShownAt = 0;
  let stylusGesture = null;
  let eraserRestoreTool = null;
  let pullState = null;
  let pullIndicator = null;
  let modelStatusNode = null;
  let recognitionReadyChipShown = false;
  let barrelRestoreTool = null;
  let barrelButtonLatchUntil = 0;
  let updateSheet = null;
  let updateCheckManual = false;
  let updateState = { status: 'idle', release: null, progress: 0 };

  const uid = (prefix = 'id') => `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 9)}`;
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const hasHangul = (text) => /[\u3131-\u318e\uac00-\ud7a3]/.test(String(text || ''));
  const hasLatin = (text) => /[A-Za-z]/.test(String(text || ''));
  const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));

  function releaseNotesSeenKey(version = VERSION) {
    return `badnote.releaseNotes.seen.${version}`;
  }

  function safeLocalStorageGet(key) {
    try { return window.localStorage?.getItem(key); }
    catch { return null; }
  }

  function safeLocalStorageSet(key, value) {
    try { window.localStorage?.setItem(key, value); }
    catch { /* ignored */ }
  }

  function formatBytes(value) {
    const bytes = Number(value) || 0;
    if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${Math.max(0, Math.round(bytes))} B`;
  }

  function markUserActivity() {
    lastUserActivityAt = Date.now();
  }

  function requestIdleSlot(timeout = IDLE_OCR_TIMEOUT_MS) {
    return new Promise((resolve) => {
      if (typeof window.requestIdleCallback === 'function') {
        window.requestIdleCallback(resolve, { timeout });
      } else {
        setTimeout(resolve, Math.min(timeout, 900));
      }
    });
  }

  async function waitForQuietWindow(timeout = IDLE_OCR_TIMEOUT_MS) {
    for (let attempt = 0; attempt < 8; attempt++) {
      await requestIdleSlot(timeout);
      const quietFor = Date.now() - lastUserActivityAt;
      if (!document.hidden && !api?.state.drawSession && quietFor >= OCR_ACTIVITY_GRACE_MS) return true;
      await delay(320);
    }
    return !document.hidden && !api?.state.drawSession;
  }

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
    const items = (strokes || []).map((stroke, order) => ({ stroke, order, bounds: strokeBounds(stroke) }))
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
      strokes: line.items.sort((a, b) => a.order - b.order).map((item) => item.stroke)
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

  function textCandidateScore(text, languageTag, mode = 'auto') {
    const value = String(text || '').normalize('NFKC').trim();
    if (!value) return -100;
    const hangul = (value.match(/[\u3131-\u318e\uac00-\ud7a3]/g) || []).length;
    const latin = (value.match(/[A-Za-z]/g) || []).length;
    const digits = (value.match(/\d/g) || []).length;
    const usefulSymbols = (value.match(/[+\-*/=.,:;()[\]{}%₩$#@]/g) || []).length;
    const bad = (value.match(/[□�]/g) || []).length;
    let score = value.replace(/\s/g, '').length + hangul * 3 + latin * 2 + digits * 2 + usefulSymbols - bad * 10;
    if (mode === 'ko' && languageTag === 'ko') score += hangul ? 10 : 0;
    if (mode === 'en' && languageTag === 'en-US') score += latin || digits ? 10 : 0;
    if (mode === 'auto') {
      if (languageTag === 'ko' && hangul) score += 12;
      if (languageTag === 'en-US' && (latin || digits) && !hangul) score += 12;
      if (hangul && latin) score += 10;
    }
    return score;
  }

  async function recognizeLineBest(line, mode, preContext, progress, index, total) {
    const languages = mode === 'en' ? ['en-US'] : mode === 'ko' ? ['ko'] : ['ko', 'en-US'];
    const results = [];
    for (const languageTag of languages) {
      progress(`네이티브 ${languageTag === 'ko' ? '한글' : '영문'} 인식 ${index}/${total}`);
      try {
        const result = await recognizeNativeInk(line.strokes, languageTag, {
          preContext: preContext.slice(-20),
          maxCandidates: mode === 'auto' ? 10 : 8
        });
        const choices = candidateTexts(result);
        const ranked = choices
          .map((text) => ({ text, score: textCandidateScore(text, languageTag, mode) }))
          .sort((a, b) => b.score - a.score);
        if (ranked.length) {
          results.push({
            languageTag,
            result,
            choices: ranked.map((item) => item.text),
            score: ranked[0].score
          });
        }
      } catch (error) {
        console.warn('Native line recognition failed', languageTag, error);
      }
    }
    results.sort((a, b) => b.score - a.score);
    const best = results[0];
    if (!best) throw new Error('인식 결과가 비어 있습니다.');
    const alternatives = [];
    for (const item of results) {
      for (const text of item.choices) {
        if (text !== best.choices[0] && !alternatives.includes(text)) alternatives.push(text);
      }
    }
    return { text: best.choices[0], alternatives, languageTag: best.languageTag, result: best.result };
  }

  async function nativeTextRecognizer(strokes, mode = 'auto', progress = () => {}) {
    if (!nativeAvailable()) return localTextRecognizer(strokes, mode, progress);
    try {
      const lines = groupStrokeLines(strokes);
      const output = [];
      const alternatives = [];
      let preContext = '';
      for (let index = 0; index < lines.length; index++) {
        const best = await recognizeLineBest(lines[index], mode, preContext, progress, index + 1, lines.length);
        output.push(best.text || '');
        alternatives.push(best.alternatives.slice(0, 8));
        preContext += `${best.text || ''}\n`;
      }
      const text = output.join('\n').trim();
      if (!text) throw new Error('인식 결과가 비어 있습니다.');
      return {
        text,
        confidence: clamp(.9 - Math.max(0, lines.length - 6) * .015, .55, .94),
        alternatives,
        details: [],
        lines: lines.length,
        bounds: unionBounds(strokes),
        engine: mode === 'auto' ? 'mlkit-digital-ink-ko+en' : 'mlkit-digital-ink'
      };
    } catch (error) {
      console.warn('Native text recognition fallback', error);
      return localTextRecognizer(strokes, mode, progress);
    }
  }

  function normalizeMathBase(raw) {
    let value = String(raw || '').normalize('NFKC').trim();
    value = value
      .replace(/\\left|\\right/g, '')
      .replace(/\\times|\\cdot|\\ast/g, '*')
      .replace(/\\div/g, '/')
      .replace(/\\pi/g, 'pi')
      .replace(/\\sqrt\s*\{([^{}]+)\}/g, 'sqrt($1)')
      .replace(/\\frac\s*\{([^{}]+)\}\s*\{([^{}]+)\}/g, '($1)/($2)')
      .replace(/\{/g, '(')
      .replace(/\}/g, ')')
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
    const pageKey = detail?.pageId ?? detail?.pageIndex ?? 0;
    return `${detail?.documentId || api?.state?.currentDocumentId || 'doc'}:${pageKey}`;
  }

  function updateActivePageDwell(reason = 'page') {
    const doc = api?.currentDocument?.();
    const pageIndex = api?.state?.currentPageIndex ?? -1;
    const page = doc?.pages?.[pageIndex];
    if (!doc || !page) return;
    if (activePageDwell.documentId === doc.id && activePageDwell.pageId === page.id && activePageDwell.pageIndex === pageIndex) return;
    clearTimeout(activePageDwell.timer);
    activePageDwell = {
      documentId: doc.id,
      pageId: page.id,
      pageIndex,
      enteredAt: Date.now(),
      reason,
      timer: setTimeout(() => scanCurrentPageSoon(true), Math.min(HANDWRITING_OCR_DWELL_MS, PDF_OCR_CURRENT_DWELL_MS))
    };
  }

  function isCurrentPageDetail(detail) {
    const { doc, page, index } = pageByDetail(detail);
    return !!doc && !!page && doc.id === api.state.currentDocumentId && index === api.state.currentPageIndex;
  }

  function visiblePageIndexes(doc) {
    if (!doc) return [];
    if (typeof api?.visiblePageIndexes === 'function') {
      const fastIndexes = api.visiblePageIndexes().filter((index) => Number.isInteger(index) && doc.pages[index]);
      if (fastIndexes.length) return fastIndexes;
    }
    const viewport = document.getElementById('editorViewport');
    const wraps = Array.from(document.querySelectorAll('.page-wrap[data-page-index]'));
    if (!viewport || !wraps.length) return [api.state.currentPageIndex];
    const viewportRect = viewport.getBoundingClientRect();
    const indexes = [];
    for (const wrap of wraps) {
      const index = Number(wrap.dataset.pageIndex);
      if (!Number.isInteger(index) || !doc.pages[index]) continue;
      const rect = wrap.getBoundingClientRect();
      const visibleHeight = Math.min(rect.bottom, viewportRect.bottom) - Math.max(rect.top, viewportRect.top);
      const visibleWidth = Math.min(rect.right, viewportRect.right) - Math.max(rect.left, viewportRect.left);
      if (visibleHeight > 48 && visibleWidth > 48) indexes.push(index);
    }
    if (!indexes.includes(api.state.currentPageIndex)) indexes.unshift(api.state.currentPageIndex);
    return [...new Set(indexes)].sort((a, b) => Math.abs(a - api.state.currentPageIndex) - Math.abs(b - api.state.currentPageIndex));
  }

  function dwellRemaining(detail, requiredMs) {
    if (!isCurrentPageDetail(detail)) return 0;
    const { page } = pageByDetail(detail);
    if (activePageDwell.pageId !== page?.id) updateActivePageDwell('dwell-check');
    return Math.max(0, requiredMs - (Date.now() - activePageDwell.enteredAt));
  }

  async function waitForOcrTurn(detail, currentPageMs) {
    const remaining = dwellRemaining(detail, currentPageMs);
    if (remaining > 0) await delay(remaining + 80);
    await waitForQuietWindow();
  }

  async function autoIndexPage(detail, force = false) {
    if (!api?.state.settings.autoOcr) return;
    const { doc, page, index } = pageByDetail(detail);
    if (!doc || !page) return;
    if (!force) {
      const remaining = dwellRemaining(detail, HANDWRITING_OCR_DWELL_MS);
      if (remaining > 0) {
        scheduleAutoOcr(detail, remaining);
        return;
      }
      if (!await waitForQuietWindow(1600)) {
        scheduleAutoOcr(detail, 900);
        return;
      }
    }
    const objects = (page.objects || []).filter((object) => object.type === 'stroke' && object.brush !== 'highlighter' && object.points?.length && !object.autoShapeSource);
    if (!objects.length) return;
    const hash = sourceHash(objects);
    const existing = page.objects.find((object) => object.type === 'ocrIndex' && object.source === 'auto-native');
    if (!force && existing?.sourceHash === hash) return;
    try {
      const result = await nativeTextRecognizer(objects.map((object) => object.points), 'auto', () => {});
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

  function scheduleAutoOcr(detail, delayMs = HANDWRITING_OCR_DWELL_MS) {
    if (!api?.state.settings.autoOcr) return;
    const pageKey = detail?.pageId ?? detail?.pageIndex ?? api.state.currentPageIndex;
    const key = `${detail?.documentId || api.state.currentDocumentId}:${pageKey}`;
    clearTimeout(ocrTimers.get(key));
    const waitMs = Math.max(700, delayMs, dwellRemaining(detail, HANDWRITING_OCR_DWELL_MS));
    ocrTimers.set(key, setTimeout(() => {
      ocrTimers.delete(key);
      void autoIndexPage(detail);
    }, waitMs));
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
    const methodAvailable = nativeApi && (typeof nativeApi.recognizeImageText === 'function' || typeof nativeApi.recognizeKoreanImage === 'function');
    if (!detail?.needsImageOcr || !methodAvailable) return;
    const key = pdfQueueKey(detail);
    if (pdfQueuedKeys.has(key)) return;
    pdfQueuedKeys.add(key);
    const item = { detail, options: { ...options } };
    if (options.priority) pdfOcrQueue.unshift(item);
    else pdfOcrQueue.push(item);
    void processPdfOcrQueue();
  }

  async function processPdfOcrQueue() {
    if (pdfOcrBusy || !pdfOcrQueue.length) return;
    pdfOcrBusy = true;
    while (pdfOcrQueue.length) {
      const item = pdfOcrQueue.shift();
      const detail = item.detail;
      pdfQueuedKeys.delete(pdfQueueKey(detail));
      const { doc, page } = pageByDetail(detail);
      if (!doc || !page?.backgroundAssetId || page.objects?.some((object) => object.type === 'ocrIndex' && object.source === 'pdf-image')) continue;
      try {
        await waitForOcrTurn(detail, item.options?.priority ? PDF_OCR_CURRENT_DWELL_MS : 0);
        setRecognitionChip(`PDF 한/영 OCR ${page.pdfPageNumber || ''}`, 'busy');
        const asset = await api.storage.getAsset(page.backgroundAssetId);
        if (!asset?.blob) continue;
        const dataUrl = await blobToDataUrl(asset.blob);
        const method = typeof nativeApi.recognizeImageText === 'function' ? 'recognizeImageText' : 'recognizeKoreanImage';
        const largeDocument = (doc.pages?.length || 0) > 24;
        const result = await callNative(method, {
          dataUrl,
          languages: ['ko', 'en'],
          mode: item.options?.priority ? 'quality' : 'balanced',
          allowTesseract: false,
          maxEdge: largeDocument ? 1500 : 1900
        }, item.options?.priority ? 180000 : 150000);
        const text = String(result?.text || '').trim();
        if (text) {
          page.objects.push({
            id: uid('ocr'), type: 'ocrIndex', source: 'pdf-image', hidden: true, locked: true,
            text, x: 0, y: 0, w: PAGE_WIDTH, h: PAGE_HEIGHT,
            createdAt: new Date().toISOString(), engine: result?.engine || 'mlkit-korean+latin'
          });
          page.needsImageOcr = false;
          await api.storage.putDocument(doc);
          if (doc.id === api.state.currentDocumentId) api.renderDocumentSearch?.();
        }
      } catch (error) {
        console.warn('PDF image OCR failed', error);
      }
      await delay((api.currentDocument?.()?.pages?.length || 0) > 24 ? 900 : 240);
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
    if ((detail.holdDuration || 0) < SHAPE_HOLD_MS) return;
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
    const message = String(text || '');
    const suppressAutoStatus = /OCR 자동 색인|OCR 대기|OCR 준비|네이티브 OCR 준비|PDF 한\/영 OCR|기본 OCR 사용/.test(message) || (tone === 'ready' && /OCR|인식|준비/.test(message));
    if (suppressAutoStatus) {
      recognitionReadyChipShown = recognitionReadyChipShown || /준비/.test(message);
      if (chip) chip.hidden = true;
      return;
    }
    const readyNotice = tone === 'ready' && /준비/.test(String(text || ''));
    if (readyNotice && recognitionReadyChipShown && chip) {
      chip.hidden = true;
      return;
    }
    if (!chip) {
      chip = document.createElement('button');
      chip.id = 'nativeRecognitionChip';
      chip.type = 'button';
      chip.className = 'native-status-chip';
      chip.dataset.action = 'native-recognition-status';
      chip.innerHTML = '<span class="native-status-dot"></span><span class="native-status-text"></span>';
      document.body.appendChild(chip);
    }
    if (readyNotice) recognitionReadyChipShown = true;
    chip.hidden = false;
    chip.dataset.tone = tone;
    chip.querySelector('.native-status-text').textContent = text;
    clearTimeout(chip._hideTimer);
    if (tone === 'ready' || tone === 'idle') {
      chip._hideTimer = setTimeout(() => { chip.hidden = true; }, readyNotice ? 1600 : 2200);
    }
  }

  function setStylusChip(detail) {
    lastStylusEventAt = Date.now();
    const buttonsActive = !!(detail?.primaryButton || detail?.secondaryButton || detail?.barrelButton || detail?.eraser);
    if (!buttonsActive && lastStylusEventAt - lastStylusChipShownAt < 10000) return;
    lastStylusChipShownAt = lastStylusEventAt;
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
    chip._hideTimer = setTimeout(() => chip.classList.remove('is-visible'), buttonsActive ? 900 : 1200);
  }

  function rememberNativeStylus(detail) {
    const action = Number(detail?.action);
    const nowTime = performance.now();
    const buttonState = Number(detail?.buttonState || 0);
    const rawButtonState = Number(detail?.rawButtonState ?? buttonState);
    const rawPrimary = !!detail?.primaryButton || (buttonState & 32) !== 0 || (rawButtonState & 32) !== 0;
    const rawSecondary = !!detail?.secondaryButton || (buttonState & 64) !== 0 || (rawButtonState & 64) !== 0;
    const rawBarrel = !!detail?.barrelButton || rawPrimary || rawSecondary || (buttonState & 96) !== 0 || (rawButtonState & 96) !== 0;
    if (rawBarrel && action !== 1 && action !== 3 && action !== 12) barrelButtonLatchUntil = nowTime + BARREL_BUTTON_LATCH_MS;
    else if (action === 1 || action === 3 || action === 12) barrelButtonLatchUntil = 0;
    const latchedBarrel = nowTime < barrelButtonLatchUntil;
    lastStylusDetail = {
      ...detail,
      x: Number(detail?.x),
      y: Number(detail?.y),
      pressure: Number(detail?.pressure),
      toolType: Number(detail?.toolType),
      buttonState,
      rawButtonState,
      primaryButton: rawPrimary || (latchedBarrel && !rawSecondary),
      secondaryButton: rawSecondary,
      barrelButton: rawBarrel || latchedBarrel,
      latchedBarrelButton: latchedBarrel && !rawBarrel,
      eraser: Number(detail?.toolType) === 4,
      receivedAt: nowTime
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
    const button = !!(lastStylusDetail?.primaryButton || lastStylusDetail?.secondaryButton || lastStylusDetail?.barrelButton);
    const toolType = Number(detail.toolType);
    const action = Number(detail.action);
    if (button && action !== 1 && action !== 3 && !barrelRestoreTool && api.state.tool !== 'eraser') {
      barrelRestoreTool = api.state.tool;
      api.setTool('eraser');
      api.toast?.('S Pen 버튼: 누르는 동안 지우개');
    } else if ((!button || action === 1 || action === 3) && barrelRestoreTool) {
      if (api.state.tool === 'eraser') api.setTool(barrelRestoreTool);
      barrelRestoreTool = null;
    }
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

  function releaseBodyToHtml(body, fallback = RELEASE_NOTES) {
    const lines = String(body || '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    const selected = (lines.length ? lines : fallback).slice(0, 18);
    if (!selected.length) return '<p>이번 버전의 변경 내역이 없습니다.</p>';
    const items = selected.map((line) => {
      const cleaned = line.replace(/^[-*]\s+/, '').replace(/^#{1,6}\s*/, '');
      return `<li>${escapeHtml(cleaned)}</li>`;
    }).join('');
    return `<ul>${items}</ul>`;
  }

  function ensureUpdateSheet() {
    if (updateSheet) return updateSheet;
    updateSheet = document.createElement('section');
    updateSheet.id = 'nativeUpdateSheet';
    updateSheet.className = 'sheet modal update-sheet';
    updateSheet.hidden = true;
    updateSheet.setAttribute('aria-label', '앱 업데이트');
    updateSheet.innerHTML = `
      <header class="sheet-header">
        <div>
          <span id="nativeUpdateEyebrow" class="eyebrow">앱 업데이트</span>
          <h2 id="nativeUpdateTitle">업데이트 확인</h2>
        </div>
        <button class="icon-button" data-update-action="close" aria-label="닫기">×</button>
      </header>
      <p id="nativeUpdateSummary" class="sheet-description">GitHub Releases에서 최신 버전을 확인합니다.</p>
      <div id="nativeUpdateVersionRow" class="update-version-row">
        <span><strong id="nativeUpdateVersion">-</strong><small id="nativeUpdateVariant">-</small></span>
        <a id="nativeUpdateReleaseLink" class="update-release-link" href="#" target="_blank" rel="noopener">릴리즈</a>
      </div>
      <div id="nativeUpdateNotes" class="update-notes"></div>
      <div id="nativeUpdateProgressBlock" class="update-progress-block">
        <div id="nativeUpdateProgressBar" class="update-progress-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
          <span id="nativeUpdateProgressFill"></span>
        </div>
        <small id="nativeUpdateProgressText">대기 중</small>
      </div>
      <div class="sheet-actions update-actions">
        <button id="nativeUpdateSecondary" class="secondary-button" data-update-action="close">나중에</button>
        <button id="nativeUpdatePrimary" class="primary-button" data-update-action="check">확인</button>
      </div>`;
    const anchor = document.getElementById('toastHost');
    (document.getElementById('app') || document.body).insertBefore(updateSheet, anchor || null);
    return updateSheet;
  }

  function showUpdateSheet() {
    const sheet = ensureUpdateSheet();
    document.getElementById('modalBackdrop')?.removeAttribute('hidden');
    sheet.hidden = false;
    document.body.classList.add('modal-open');
  }

  function markReleaseNotesSeen() {
    safeLocalStorageSet(releaseNotesSeenKey(VERSION), '1');
    safeLocalStorageSet(RELEASE_NOTES_LAST_VERSION_KEY, VERSION);
  }

  function closeUpdateSheet() {
    if (updateState.status === 'release-notes') markReleaseNotesSeen();
    if (updateSheet) updateSheet.hidden = true;
    const otherOpen = Array.from(document.querySelectorAll('.modal')).some((node) => !node.hidden);
    if (!otherOpen) {
      const backdrop = document.getElementById('modalBackdrop');
      if (backdrop) backdrop.hidden = true;
      document.body.classList.remove('modal-open');
    }
  }

  function renderUpdateSheet() {
    const sheet = ensureUpdateSheet();
    const status = updateState.status || 'idle';
    const release = updateState.release || {};
    const isNotes = status === 'release-notes';
    const title = isNotes ? `${VERSION} 업데이트 내역` :
      status === 'available' ? `새 버전 ${release.version || ''}` :
      status === 'downloaded' ? '업데이트 다운로드 완료' :
      status === 'permission-required' ? '설치 권한 필요' :
      status === 'installing' ? '설치 화면 열림' :
      status === 'current' ? '최신 버전입니다' :
      status === 'error' ? '업데이트 확인 실패' : '업데이트 확인';
    const summary = isNotes ? '이번 버전에서 바뀐 내용을 확인하세요.' :
      status === 'checking' ? 'GitHub Releases에서 최신 APK를 확인하고 있습니다.' :
      status === 'available' ? '다운로드 후 Android 설치 화면에서 업데이트를 완료할 수 있습니다.' :
      status === 'downloading' ? 'APK를 다운로드하는 중입니다. 화면을 닫아도 다운로드는 계속 진행됩니다.' :
      status === 'downloaded' ? '다운로드가 끝났습니다. 설치를 눌러 Android 설치 화면으로 이동하세요.' :
      status === 'permission-required' ? '설정에서 이 앱의 APK 설치를 허용한 뒤 설치를 다시 누르세요.' :
      status === 'installing' ? 'Android 설치 화면에서 업데이트를 승인하세요.' :
      status === 'current' ? `현재 ${VERSION} 버전을 사용 중입니다.` :
      release.message || '네트워크 상태를 확인한 뒤 다시 시도하세요.';
    sheet.dataset.status = status;
    sheet.querySelector('#nativeUpdateEyebrow').textContent = isNotes ? '변경 내역' : 'GitHub Releases';
    sheet.querySelector('#nativeUpdateTitle').textContent = title;
    sheet.querySelector('#nativeUpdateSummary').textContent = summary;
    sheet.querySelector('#nativeUpdateVersion').textContent = isNotes ? `bad note ${VERSION}` : `${release.currentVersion || VERSION} → ${release.version || VERSION}`;
    sheet.querySelector('#nativeUpdateVariant').textContent = release.variant || 'Android APK';
    const link = sheet.querySelector('#nativeUpdateReleaseLink');
    link.hidden = !release.htmlUrl || isNotes;
    link.href = release.htmlUrl || '#';
    sheet.querySelector('#nativeUpdateNotes').innerHTML = releaseBodyToHtml(isNotes ? RELEASE_NOTES.join('\n') : release.body);
    const progressBlock = sheet.querySelector('#nativeUpdateProgressBlock');
    const progress = clamp(Number(updateState.progress ?? release.progress ?? 0), 0, 100);
    progressBlock.hidden = isNotes || status === 'current' || status === 'error' || status === 'checking';
    const progressBar = sheet.querySelector('#nativeUpdateProgressBar');
    progressBar.setAttribute('aria-valuenow', String(progress));
    sheet.querySelector('#nativeUpdateProgressFill').style.width = `${progress}%`;
    const downloaded = release.bytesDownloaded || release.fileSize || 0;
    const total = release.totalBytes || release.assetSize || 0;
    sheet.querySelector('#nativeUpdateProgressText').textContent = status === 'downloaded'
      ? `다운로드 완료 · ${formatBytes(release.fileSize || downloaded || total)}`
      : status === 'available'
        ? `APK 크기 ${formatBytes(total)}`
        : `${progress}% · ${formatBytes(downloaded)} / ${formatBytes(total)}`;
    const primary = sheet.querySelector('#nativeUpdatePrimary');
    const secondary = sheet.querySelector('#nativeUpdateSecondary');
    secondary.hidden = isNotes || status === 'current';
    primary.disabled = status === 'checking' || status === 'downloading' || status === 'installing';
    if (isNotes) {
      primary.textContent = '확인';
      primary.dataset.updateAction = 'ack-notes';
    } else if (status === 'available') {
      primary.textContent = '다운로드';
      primary.dataset.updateAction = 'download';
    } else if (status === 'downloaded' || status === 'permission-required') {
      primary.textContent = status === 'permission-required' ? '설치 재시도' : '설치';
      primary.dataset.updateAction = 'install';
    } else if (status === 'current') {
      primary.textContent = '닫기';
      primary.dataset.updateAction = 'close';
    } else {
      primary.textContent = status === 'checking' ? '확인 중' : '다시 확인';
      primary.dataset.updateAction = 'check';
    }
  }

  function applyNativeUpdateState(detail = {}) {
    const release = { ...(updateState.release || {}), ...detail };
    const status = detail.status || updateState.status || 'idle';
    const progress = Number.isFinite(Number(detail.progress)) ? Number(detail.progress) : updateState.progress || 0;
    const manual = updateCheckManual;
    updateState = { status, release, progress };
    renderUpdateSheet();
    const shouldOpen = manual || ['available', 'downloading', 'downloaded', 'permission-required', 'installing'].includes(status) || (manual && status === 'error');
    if (shouldOpen) showUpdateSheet();
    if (status === 'current' && manual) {
      showUpdateSheet();
      setTimeout(closeUpdateSheet, 1600);
    }
    if (status !== 'checking') updateCheckManual = false;
  }

  function requestUpdateCheck(manual = false) {
    if (!nativeApi || typeof nativeApi.checkForUpdate !== 'function') {
      if (manual) api?.toast?.('Android 앱에서만 업데이트 확인을 사용할 수 있습니다.');
      return false;
    }
    updateCheckManual = manual;
    if (manual) {
      updateState = { status: 'checking', release: { currentVersion: VERSION }, progress: 0 };
      renderUpdateSheet();
      showUpdateSheet();
    }
    try {
      nativeApi.checkForUpdate();
      return true;
    } catch (error) {
      applyNativeUpdateState({ status: 'error', message: error.message || String(error) });
      return false;
    }
  }

  function requestUpdateDownload() {
    if (!nativeApi || typeof nativeApi.downloadUpdate !== 'function') return;
    updateState = { ...updateState, status: 'downloading', progress: 0 };
    renderUpdateSheet();
    try { nativeApi.downloadUpdate(); }
    catch (error) { applyNativeUpdateState({ status: 'error', message: error.message || String(error) }); }
  }

  function requestUpdateInstall() {
    if (!nativeApi || typeof nativeApi.installDownloadedUpdate !== 'function') return;
    try { nativeApi.installDownloadedUpdate(); }
    catch (error) { applyNativeUpdateState({ status: 'error', message: error.message || String(error) }); }
  }

  function handleUpdateAction(action) {
    if (action === 'close') closeUpdateSheet();
    else if (action === 'ack-notes') closeUpdateSheet();
    else if (action === 'check') requestUpdateCheck(true);
    else if (action === 'download') requestUpdateDownload();
    else if (action === 'install') requestUpdateInstall();
  }

  function handleNativeUpdate(event) {
    applyNativeUpdateState(event.detail || {});
  }

  function showInstalledReleaseNotesOnce(force = false) {
    if (!force && !nativeAvailable()) return false;
    const seenKey = releaseNotesSeenKey(VERSION);
    const seen = safeLocalStorageGet(seenKey) === '1';
    const lastVersion = safeLocalStorageGet(RELEASE_NOTES_LAST_VERSION_KEY);
    if (seen || lastVersion === VERSION) {
      safeLocalStorageSet(RELEASE_NOTES_LAST_VERSION_KEY, VERSION);
      return false;
    }
    updateState = {
      status: 'release-notes',
      release: {
        version: VERSION,
        releaseName: `bad note ${VERSION}`,
        body: RELEASE_NOTES.join('\n'),
        variant: '설치됨'
      },
      progress: 0
    };
    renderUpdateSheet();
    showUpdateSheet();
    return true;
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
    const updateRow = document.createElement('div');
    updateRow.className = 'setting-row update-setting-row';
    updateRow.innerHTML = '<span><strong>앱 자동 업데이트</strong><small>앱 시작 시 GitHub Releases를 확인하고, 새 APK가 있으면 업데이트 화면을 표시합니다.</small></span><button class="secondary-button compact-action" data-action="check-app-update" type="button">확인</button>';
    list.appendChild(updateRow);
    const autoRow = document.createElement('label');
    autoRow.className = 'setting-row';
    autoRow.innerHTML = '<span><strong>손글씨 OCR 자동 등록</strong><small>화면에 보이는 페이지에 머문 뒤 한글·영문 OCR 검색 색인을 유휴 상태에서 갱신합니다.</small></span><input id="nativeAutoOcrToggle" type="checkbox" />';
    list.appendChild(autoRow);
    const toggle = autoRow.querySelector('input');
    toggle.checked = api.state.settings.autoOcr !== false;
    toggle.addEventListener('change', () => {
      api.state.settings.autoOcr = toggle.checked;
      api.storage.setSetting('preferences', api.state.settings);
      if (toggle.checked) scheduleAutoOcr({ documentId: api.state.currentDocumentId, pageIndex: api.state.currentPageIndex }, HANDWRITING_OCR_DWELL_MS);
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
    const label = detail.languageTag === 'ko' ? '한글 OCR' : detail.languageTag === 'en-US' ? '영문·숫자' : '도형';
    const status = detail.status === 'ready' ? '준비됨' : detail.status === 'downloading' ? '다운로드 중' : '오류';
    if (modelStatusNode) modelStatusNode.textContent = `${label}: ${status}`;
    setRecognitionChip(`${label} ${status}`, detail.status === 'ready' ? 'ready' : detail.status === 'downloading' ? 'busy' : 'error');
  }

  function bindGlobalActions() {
    document.addEventListener('click', (event) => {
      const updateTarget = event.target.closest('[data-update-action]');
      if (updateTarget) {
        event.preventDefault();
        event.stopImmediatePropagation();
        handleUpdateAction(updateTarget.dataset.updateAction);
        return;
      }
      const target = event.target.closest('[data-action]');
      if (!target) return;
      if (target.dataset.action === 'new-note-pdf') {
        event.preventDefault();
        event.stopImmediatePropagation();
        window.__inkforgePdf?.openPicker({ createNew: true });
      } else if (target.dataset.action === 'native-recognition-status') {
        event.preventDefault();
        document.querySelector('[data-action="open-settings"]')?.click();
      } else if (target.dataset.action === 'check-app-update') {
        event.preventDefault();
        event.stopImmediatePropagation();
        requestUpdateCheck(true);
      }
    }, true);
  }

  function scanCurrentPageSoon(dwellReady = false) {
    const doc = api.currentDocument?.();
    if (!doc) return;
    updateActivePageDwell('scan');
    const visibleIndexes = visiblePageIndexes(doc);
    for (const index of visibleIndexes) {
      const page = doc.pages[index];
      if (!page?.objects?.some((object) => object.type === 'stroke')) continue;
      scheduleAutoOcr(
        { documentId: doc.id, pageId: page.id, pageIndex: index },
        index === api.state.currentPageIndex && dwellReady ? 700 : HANDWRITING_OCR_DWELL_MS + (index === api.state.currentPageIndex ? 0 : 900)
      );
    }
    for (const index of visibleIndexes) {
      const item = doc.pages[index];
      if (item?.needsImageOcr) {
        const current = index === api.state.currentPageIndex;
        enqueuePdfImageOcr(
          { documentId: doc.id, pageId: item.id, pageIndex: index, needsImageOcr: true },
          { priority: current, nearby: true }
        );
      }
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
    ['pointerdown', 'pointermove', 'wheel', 'keydown', 'touchstart'].forEach((name) => {
      window.addEventListener(name, markUserActivity, { passive: true, capture: true });
    });
    window.addEventListener('inkforge:stroke-committed', (event) => {
      markUserActivity();
      scheduleAutoOcr(event.detail);
      void maybeConvertNativeShape(event.detail);
    });
    window.addEventListener('inkforge:pdf-page-imported', (event) => enqueuePdfImageOcr(event.detail));
    window.addEventListener('inkforge:page-changed', () => {
      markUserActivity();
      updateActivePageDwell('page-changed');
      scanCurrentPageSoon(false);
    });
    window.addEventListener('inkforge:native-stylus', handleNativeStylus);
    window.addEventListener('inkforge:native-stylus-key', handleNativeStylusKey);
    window.addEventListener('inkforge:native-model-status', handleModelStatus);
    window.addEventListener('inkforge:native-update', handleNativeUpdate);

    const stack = document.getElementById('pageStack');
    if (stack) new MutationObserver(() => scanCurrentPageSoon()).observe(stack, { childList: true });
    const viewport = document.getElementById('editorViewport');
    if (viewport) {
      let scanTimer = 0;
      viewport.addEventListener('scroll', () => {
        markUserActivity();
        clearTimeout(scanTimer);
        scanTimer = setTimeout(() => scanCurrentPageSoon(false), 540);
      }, { passive: true });
    }
    updateActivePageDwell('initialize');
    scanCurrentPageSoon();
    setTimeout(() => showInstalledReleaseNotesOnce(false), 600);
    setTimeout(() => requestUpdateCheck(false), 1700);
    window.__inkforgeNativeBridge = {
      version: VERSION,
      ready: true,
      autoIndexPage,
      recognizeNativeInk,
      isRecentStylusEvent,
      checkForUpdate: () => requestUpdateCheck(true),
      applyUpdateState: applyNativeUpdateState,
      showReleaseNotesOnce: () => showInstalledReleaseNotesOnce(true),
      get lastStylus() { return lastStylusDetail; },
      get barrelButtonActive() { return performance.now() < barrelButtonLatchUntil; }
    };
  }

  initialize().catch((error) => {
    console.error('InkForge native bridge initialization failed', error);
    setRecognitionChip('네이티브 엔진 오류', 'error');
  });
})();
