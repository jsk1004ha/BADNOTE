(() => {
  'use strict';

  const MODULE_VERSION = '1.1.0';
  const PAGE_WIDTH = 1000;
  const PAGE_HEIGHT = 1414;
  const HANGUL_START = 0xAC00;
  const HANGUL_END = 0xD7A3;
  const BASE_LATIN = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,!?;:\'"-+×÷=()[]{}%/^√π';
  const MATH_CHARS = '0123456789+-×÷=().,%!^√πxyabcdeinostlg';
  const OCR_INDEX_TYPE = 'ocrIndex';

  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
  const median = (values) => {
    if (!values.length) return 0;
    const sorted = [...values].sort((a, b) => a - b);
    const middle = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  };
  const uid = (prefix = 'id') => `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 9)}`;
  const deepClone = (value) => typeof structuredClone === 'function' ? structuredClone(value) : JSON.parse(JSON.stringify(value));
  const tick = () => new Promise((resolve) => requestAnimationFrame(() => resolve()));
  const escapeHtml = (text) => String(text ?? '').replace(/[&<>'"]/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[ch]));

  function icon(path, className = '') {
    return `<svg class="${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
  }

  const OCR_ICON = '<path d="M4 7V4h3M17 4h3v3M20 17v3h-3M7 20H4v-3"/><path d="M7 9h10M7 12h7M7 15h9"/>';
  const INK_MATH_ICON = '<path d="M4 18 14 8l2 2L6 20H4z"/><path d="M15 5h6M18 2v6M14 16h7M14 20h7"/>';
  const UNDO_ICON = '<path d="M9 7 4 12l5 5"/><path d="M4 12h9a7 7 0 0 1 7 7"/>';
  const TRASH_ICON = '<path d="M4 7h16M9 7V4h6v3M7 7l1 14h8l1-14"/>';
  const CHECK_ICON = '<path d="m5 12 4 4L19 6"/>';
  const TEXT_ICON = '<rect x="4" y="5" width="16" height="14" rx="2"/><path d="M8 9V7h8v2M12 7v10M9 17h6"/>';
  const SEARCH_ICON = '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>';
  const COPY_ICON = '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M15 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h3"/>';

  let api = null;
  let mathPad = null;
  let ocrPreviewPad = null;
  let lastOcrResult = null;
  let mathMode = 'keyboard';
  let recognitionBusy = false;
  let hangulIndex = null;
  let hangulBuildPromise = null;
  const glyphCache = new Map();
  const vectorTemplateCache = new Map();

  function currentDoc() {
    return api?.currentDocument ? api.currentDocument() : api?.state?.documents?.find((doc) => doc.id === api.state.currentDocumentId) || null;
  }

  function currentPage() {
    return api?.currentPage ? api.currentPage() : currentDoc()?.pages?.[api?.state?.currentPageIndex || 0] || null;
  }

  function toast(message, duration = 2600) {
    if (api?.toast) return api.toast(message, duration);
    const host = document.getElementById('toastHost');
    if (!host) return;
    const node = document.createElement('div');
    node.className = 'toast';
    node.textContent = message;
    host.appendChild(node);
    requestAnimationFrame(() => node.classList.add('is-visible'));
    setTimeout(() => { node.classList.remove('is-visible'); setTimeout(() => node.remove(), 240); }, duration);
  }

  async function persist(label = 'recognition') {
    const doc = currentDoc();
    if (!doc) return;
    doc.updatedAt = new Date().toISOString();
    doc.appVersion = api.VERSION || '3.1.0';
    if (api.persistCurrent) await api.persistCurrent({ immediate: true });
    else await api.storage.putDocument(deepClone(doc));
    if (api.renderSidebar) api.renderSidebar();
    if (api.renderDocumentSearch && api.state.searchOpen) api.renderDocumentSearch();
  }

  function checkpoint(label) {
    if (api?.checkpoint) api.checkpoint(label);
  }

  function setBusy(isBusy, text = '') {
    recognitionBusy = isBusy;
    document.querySelectorAll('[data-recognition-run]').forEach((button) => { button.disabled = isBusy; });
    const status = document.getElementById('ocrStatus');
    if (status && text) status.textContent = text;
    document.body.classList.toggle('recognition-busy', isBusy);
  }

  class InkPad {
    constructor(canvas, options = {}) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d', { alpha: true, desynchronized: true });
      this.strokes = [];
      this.current = null;
      this.pointerId = null;
      this.readOnly = !!options.readOnly;
      this.onChange = options.onChange || (() => {});
      this.background = options.background || '#ffffff';
      this.inkColor = options.inkColor || '#152235';
      this.lineWidth = options.lineWidth || 3.2;
      this.resizeObserver = new ResizeObserver(() => this.resize());
      this.resizeObserver.observe(canvas);
      this.bind();
      this.resize();
    }

    bind() {
      if (this.readOnly) return;
      this.canvas.addEventListener('pointerdown', (event) => this.pointerDown(event), { passive: false });
      this.canvas.addEventListener('pointermove', (event) => this.pointerMove(event), { passive: false });
      this.canvas.addEventListener('pointerup', (event) => this.pointerUp(event), { passive: false });
      this.canvas.addEventListener('pointercancel', (event) => this.pointerUp(event), { passive: false });
      this.canvas.addEventListener('contextmenu', (event) => event.preventDefault());
    }

    resize() {
      const rect = this.canvas.getBoundingClientRect();
      if (!rect.width || !rect.height) return;
      const ratio = Math.min(3, window.devicePixelRatio || 1);
      const width = Math.max(1, Math.round(rect.width * ratio));
      const height = Math.max(1, Math.round(rect.height * ratio));
      if (this.canvas.width !== width || this.canvas.height !== height) {
        this.canvas.width = width;
        this.canvas.height = height;
        this.ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
        this.draw();
      }
    }

    point(event) {
      const rect = this.canvas.getBoundingClientRect();
      return {
        x: clamp(event.clientX - rect.left, 0, rect.width),
        y: clamp(event.clientY - rect.top, 0, rect.height),
        p: event.pointerType === 'mouse' ? .55 : clamp(Number(event.pressure || .45), .03, 1),
        tx: Number(event.tiltX || 0),
        ty: Number(event.tiltY || 0),
        t: performance.now()
      };
    }

    pointerDown(event) {
      if (this.pointerId != null || (event.pointerType === 'touch' && event.isPrimary === false)) return;
      event.preventDefault();
      this.pointerId = event.pointerId;
      try { this.canvas.setPointerCapture(event.pointerId); } catch {}
      this.current = [this.point(event)];
      this.strokes.push(this.current);
      this.draw();
    }

    pointerMove(event) {
      if (event.pointerId !== this.pointerId || !this.current) return;
      event.preventDefault();
      const samples = typeof event.getCoalescedEvents === 'function' ? event.getCoalescedEvents() : [event];
      for (const sample of samples) {
        const point = this.point(sample);
        const previous = this.current[this.current.length - 1];
        if (!previous || Math.hypot(point.x - previous.x, point.y - previous.y) > .45) this.current.push(point);
      }
      this.draw();
    }

    pointerUp(event) {
      if (event.pointerId !== this.pointerId) return;
      event.preventDefault();
      if (this.current) {
        const point = this.point(event);
        const previous = this.current[this.current.length - 1];
        if (!previous || Math.hypot(point.x - previous.x, point.y - previous.y) > .25) this.current.push(point);
      }
      this.current = null;
      this.pointerId = null;
      this.draw();
      this.onChange(this.strokes);
    }

    setStrokes(strokes, fit = false) {
      this.strokes = deepClone(strokes || []);
      if (fit && this.strokes.length) this.strokes = fitStrokesToRect(this.strokes, this.canvas.clientWidth || 800, this.canvas.clientHeight || 260, 24);
      this.draw();
    }

    clear() {
      this.strokes = [];
      this.current = null;
      this.draw();
      this.onChange(this.strokes);
    }

    undo() {
      this.strokes.pop();
      this.draw();
      this.onChange(this.strokes);
    }

    draw() {
      const rect = this.canvas.getBoundingClientRect();
      const width = rect.width || this.canvas.width;
      const height = rect.height || this.canvas.height;
      this.ctx.clearRect(0, 0, width, height);
      this.ctx.fillStyle = this.background;
      this.ctx.fillRect(0, 0, width, height);
      this.ctx.save();
      this.ctx.lineCap = 'round';
      this.ctx.lineJoin = 'round';
      this.ctx.strokeStyle = this.inkColor;
      this.ctx.fillStyle = this.inkColor;
      for (const stroke of this.strokes) drawStroke(this.ctx, stroke, this.lineWidth);
      this.ctx.restore();
      if (!this.strokes.length && !this.readOnly) {
        this.ctx.fillStyle = '#8a97a8';
        this.ctx.font = '500 15px system-ui, sans-serif';
        this.ctx.textAlign = 'center';
        this.ctx.textBaseline = 'middle';
        this.ctx.fillText('펜으로 또박또박 작성하세요', width / 2, height / 2);
      }
    }
  }

  function drawStroke(ctx, stroke, baseWidth = 3) {
    if (!stroke?.length) return;
    if (stroke.length === 1) {
      ctx.beginPath();
      ctx.arc(stroke[0].x, stroke[0].y, Math.max(1.7, baseWidth * .55), 0, Math.PI * 2);
      ctx.fill();
      return;
    }
    for (let i = 1; i < stroke.length; i++) {
      const a = stroke[i - 1], b = stroke[i];
      ctx.beginPath();
      ctx.lineWidth = baseWidth * (.72 + ((a.p ?? .5) + (b.p ?? .5)) * .28);
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
    }
  }

  function boundsOfStrokes(strokes) {
    const points = strokes.flat();
    if (!points.length) return { x: 0, y: 0, w: 1, h: 1 };
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const point of points) {
      minX = Math.min(minX, point.x); minY = Math.min(minY, point.y);
      maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y);
    }
    return { x: minX, y: minY, w: Math.max(1, maxX - minX), h: Math.max(1, maxY - minY) };
  }

  function fitStrokesToRect(strokes, width, height, margin = 16) {
    const bounds = boundsOfStrokes(strokes);
    const scale = Math.min((width - margin * 2) / bounds.w, (height - margin * 2) / bounds.h);
    const offsetX = (width - bounds.w * scale) / 2 - bounds.x * scale;
    const offsetY = (height - bounds.h * scale) / 2 - bounds.y * scale;
    return strokes.map((stroke) => stroke.map((point) => ({ ...point, x: point.x * scale + offsetX, y: point.y * scale + offsetY })));
  }

  function makeCanvas(width, height = width) {
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    return canvas;
  }

  function rasterizeStrokes(strokes, size = 32) {
    const canvas = makeCanvas(size);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    const bounds = boundsOfStrokes(strokes);
    const margin = Math.max(2.5, size * .085);
    const scale = Math.min((size - margin * 2) / Math.max(1, bounds.w), (size - margin * 2) / Math.max(1, bounds.h));
    const offsetX = (size - bounds.w * scale) / 2 - bounds.x * scale;
    const offsetY = (size - bounds.h * scale) / 2 - bounds.y * scale;
    ctx.clearRect(0, 0, size, size);
    ctx.strokeStyle = '#000';
    ctx.fillStyle = '#000';
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    const normalizedWidth = clamp(1.8 + Math.min(bounds.w, bounds.h) / Math.max(bounds.w, bounds.h) * 1.15, 1.8, 3.2);
    for (const stroke of strokes) {
      const mapped = stroke.map((point) => ({ ...point, x: point.x * scale + offsetX, y: point.y * scale + offsetY, p: .55 }));
      drawStroke(ctx, mapped, normalizedWidth);
    }
    const image = ctx.getImageData(0, 0, size, size).data;
    const alpha = new Uint8Array(size * size);
    for (let i = 0; i < alpha.length; i++) alpha[i] = image[i * 4 + 3];
    return {
      alpha,
      size,
      aspect: bounds.w / Math.max(1, bounds.h),
      bounds,
      strokes: strokes.length,
      holes: countHoles(alpha, size)
    };
  }

  function renderGlyph(char, size = 32, normalized = true) {
    const key = `${char}|${size}|${normalized ? 1 : 0}`;
    if (glyphCache.has(key)) return glyphCache.get(key);
    const sourceSize = size * 2;
    const source = makeCanvas(sourceSize);
    const sourceCtx = source.getContext('2d', { willReadFrequently: true });
    sourceCtx.clearRect(0, 0, sourceSize, sourceSize);
    sourceCtx.fillStyle = '#000';
    sourceCtx.font = `600 ${Math.round(sourceSize * .72)}px system-ui, -apple-system, "Noto Sans CJK KR", sans-serif`;
    sourceCtx.textAlign = 'center';
    sourceCtx.textBaseline = 'middle';
    sourceCtx.fillText(char, sourceSize / 2, sourceSize / 2 + sourceSize * .015);
    const data = sourceCtx.getImageData(0, 0, sourceSize, sourceSize).data;
    let minX = sourceSize, minY = sourceSize, maxX = -1, maxY = -1;
    for (let y = 0; y < sourceSize; y++) {
      for (let x = 0; x < sourceSize; x++) {
        if (data[(y * sourceSize + x) * 4 + 3] > 20) {
          minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
      }
    }
    const canvas = makeCanvas(size);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (maxX >= minX) {
      const w = maxX - minX + 1, h = maxY - minY + 1;
      if (normalized) {
        const margin = size * .08;
        const scale = Math.min((size - margin * 2) / w, (size - margin * 2) / h);
        const dw = w * scale, dh = h * scale;
        ctx.drawImage(source, minX, minY, w, h, (size - dw) / 2, (size - dh) / 2, dw, dh);
      } else ctx.drawImage(source, 0, 0, sourceSize, sourceSize, 0, 0, size, size);
    }
    const pixels = ctx.getImageData(0, 0, size, size).data;
    const alpha = new Uint8Array(size * size);
    for (let i = 0; i < alpha.length; i++) alpha[i] = pixels[i * 4 + 3];
    const result = {
      alpha,
      size,
      aspect: maxX >= minX ? (maxX - minX + 1) / Math.max(1, maxY - minY + 1) : 1,
      holes: countHoles(alpha, size)
    };
    glyphCache.set(key, result);
    return result;
  }

  function downsample(alpha, size, target = 8) {
    const output = new Uint8Array(target * target);
    const step = size / target;
    for (let ty = 0; ty < target; ty++) {
      for (let tx = 0; tx < target; tx++) {
        const x0 = Math.floor(tx * step), x1 = Math.max(x0 + 1, Math.floor((tx + 1) * step));
        const y0 = Math.floor(ty * step), y1 = Math.max(y0 + 1, Math.floor((ty + 1) * step));
        let sum = 0, count = 0;
        for (let y = y0; y < y1 && y < size; y++) {
          for (let x = x0; x < x1 && x < size; x++) { sum += alpha[y * size + x]; count++; }
        }
        output[ty * target + tx] = Math.round(sum / Math.max(1, count));
      }
    }
    return output;
  }

  function distanceTransform(alpha, size) {
    const max = size * 2;
    const dist = new Float32Array(size * size);
    for (let i = 0; i < dist.length; i++) dist[i] = alpha[i] > 34 ? 0 : max;
    const diag = 1.4142;
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        const i = y * size + x;
        let d = dist[i];
        if (x > 0) d = Math.min(d, dist[i - 1] + 1);
        if (y > 0) d = Math.min(d, dist[i - size] + 1);
        if (x > 0 && y > 0) d = Math.min(d, dist[i - size - 1] + diag);
        if (x + 1 < size && y > 0) d = Math.min(d, dist[i - size + 1] + diag);
        dist[i] = d;
      }
    }
    for (let y = size - 1; y >= 0; y--) {
      for (let x = size - 1; x >= 0; x--) {
        const i = y * size + x;
        let d = dist[i];
        if (x + 1 < size) d = Math.min(d, dist[i + 1] + 1);
        if (y + 1 < size) d = Math.min(d, dist[i + size] + 1);
        if (x + 1 < size && y + 1 < size) d = Math.min(d, dist[i + size + 1] + diag);
        if (x > 0 && y + 1 < size) d = Math.min(d, dist[i + size - 1] + diag);
        dist[i] = d;
      }
    }
    return dist;
  }

  function countHoles(alpha, size) {
    const visited = new Uint8Array(size * size);
    const queueX = new Int16Array(size * size);
    const queueY = new Int16Array(size * size);
    let regions = 0;
    for (let sy = 0; sy < size; sy++) {
      for (let sx = 0; sx < size; sx++) {
        const start = sy * size + sx;
        if (visited[start] || alpha[start] > 40) continue;
        let head = 0, tail = 0, touchesEdge = false;
        queueX[tail] = sx; queueY[tail++] = sy; visited[start] = 1;
        while (head < tail) {
          const x = queueX[head], y = queueY[head++];
          if (x === 0 || y === 0 || x === size - 1 || y === size - 1) touchesEdge = true;
          const neighbors = [[x - 1, y], [x + 1, y], [x, y - 1], [x, y + 1]];
          for (const [nx, ny] of neighbors) {
            if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue;
            const index = ny * size + nx;
            if (!visited[index] && alpha[index] <= 40) { visited[index] = 1; queueX[tail] = nx; queueY[tail++] = ny; }
          }
        }
        if (!touchesEdge && tail > 3) regions++;
      }
    }
    return regions;
  }

  function projection(alpha, size, axis) {
    const output = new Float32Array(size);
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) output[axis === 'x' ? x : y] += alpha[y * size + x] / 255;
    }
    let max = 0;
    for (const value of output) max = Math.max(max, value);
    if (max > 0) for (let i = 0; i < output.length; i++) output[i] /= max;
    return output;
  }

  function rasterScore(input, candidate) {
    const size = input.size;
    if (!input._dist) input._dist = distanceTransform(input.alpha, size);
    if (!candidate._dist) candidate._dist = distanceTransform(candidate.alpha, size);
    let inputInk = 0, candidateInk = 0, forward = 0, reverse = 0;
    for (let i = 0; i < input.alpha.length; i++) {
      if (input.alpha[i] > 38) { forward += candidate._dist[i]; inputInk++; }
      if (candidate.alpha[i] > 38) { reverse += input._dist[i]; candidateInk++; }
    }
    if (!inputInk || !candidateInk) return 9;
    const chamfer = ((forward / inputInk) + (reverse / candidateInk)) / (size * 2);
    if (!input._px) { input._px = projection(input.alpha, size, 'x'); input._py = projection(input.alpha, size, 'y'); }
    if (!candidate._px) { candidate._px = projection(candidate.alpha, size, 'x'); candidate._py = projection(candidate.alpha, size, 'y'); }
    let projectionError = 0;
    for (let i = 0; i < size; i++) projectionError += Math.abs(input._px[i] - candidate._px[i]) + Math.abs(input._py[i] - candidate._py[i]);
    projectionError /= size * 2;
    const aspectError = Math.min(1.4, Math.abs(Math.log(Math.max(.05, input.aspect) / Math.max(.05, candidate.aspect)))) * .13;
    const holeError = Math.min(2, Math.abs((input.holes || 0) - (candidate.holes || 0))) * .065;
    const densityInput = inputInk / input.alpha.length, densityCandidate = candidateInk / candidate.alpha.length;
    const densityError = Math.abs(densityInput - densityCandidate) * .32;
    return chamfer * .72 + projectionError * .17 + aspectError + holeError + densityError;
  }

  function line(x1, y1, x2, y2, count = 10) {
    return Array.from({ length: count }, (_, index) => {
      const t = count === 1 ? 0 : index / (count - 1);
      return { x: x1 + (x2 - x1) * t, y: y1 + (y2 - y1) * t, p: .55 };
    });
  }

  function polyline(points, perSegment = 8) {
    const output = [];
    for (let i = 1; i < points.length; i++) {
      const segment = line(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1], perSegment);
      if (output.length) segment.shift();
      output.push(...segment);
    }
    return output;
  }

  function ellipse(cx, cy, rx, ry, start = 0, end = Math.PI * 2, count = 42) {
    return Array.from({ length: count }, (_, index) => {
      const t = start + (end - start) * index / Math.max(1, count - 1);
      return { x: cx + Math.cos(t) * rx, y: cy + Math.sin(t) * ry, p: .55 };
    });
  }

  const VECTOR_TEMPLATES = (() => {
    const t = {};
    const add = (label, strokes) => { (t[label] ||= []).push(strokes); };
    add('0', [ellipse(50, 50, 29, 40)]);
    add('1', [polyline([[38, 25], [50, 15], [50, 88]])]);
    add('1', [line(50, 14, 50, 88)]);
    add('2', [polyline([[22, 31], [31, 18], [54, 13], [75, 24], [72, 41], [20, 88], [78, 88]], 7)]);
    add('3', [polyline([[24, 18], [54, 12], [74, 25], [67, 46], [49, 51], [69, 55], [78, 74], [64, 88], [28, 84]], 6)]);
    add('4', [polyline([[68, 88], [68, 12]]), polyline([[68, 12], [22, 64], [82, 64]])]);
    add('5', [polyline([[75, 14], [29, 14], [25, 49], [55, 46], [76, 58], [74, 79], [58, 90], [28, 83]], 7)]);
    add('6', [polyline([[70, 18], [48, 12], [29, 29], [21, 56], [28, 82], [51, 91], [72, 78], [72, 59], [55, 47], [29, 54]], 7)]);
    add('7', [polyline([[20, 16], [80, 16], [43, 90]])]);
    add('8', [ellipse(50, 33, 22, 22), ellipse(50, 70, 25, 24)]);
    add('9', [polyline([[29, 80], [50, 90], [70, 72], [76, 43], [70, 18], [48, 9], [27, 22], [26, 43], [43, 55], [70, 47]], 7)]);
    add('+', [line(18, 50, 82, 50), line(50, 18, 50, 82)]);
    add('-', [line(15, 50, 85, 50)]);
    add('×', [line(20, 20, 80, 80), line(80, 20, 20, 80)]);
    add('=', [line(16, 37, 84, 37), line(16, 64, 84, 64)]);
    add('÷', [[{ x: 50, y: 18, p: .55 }], line(16, 50, 84, 50), [{ x: 50, y: 82, p: .55 }]]);
    add('(', [ellipse(61, 50, 30, 44, Math.PI * .58, Math.PI * 1.42, 34)]);
    add(')', [ellipse(39, 50, 30, 44, -Math.PI * .42, Math.PI * .42, 34)]);
    add('.', [[{ x: 50, y: 80, p: .55 }]]);
    add(',', [polyline([[50, 73], [46, 90]])]);
    add('^', [polyline([[24, 60], [50, 27], [76, 60]])]);
    add('√', [polyline([[14, 55], [28, 76], [43, 28], [88, 28]])]);
    add('%', [ellipse(29, 28, 13, 13), line(22, 82, 78, 18), ellipse(71, 72, 13, 13)]);
    add('!', [line(50, 15, 50, 65), [{ x: 50, y: 87, p: .55 }]]);
    add('x', [line(22, 25, 78, 80), line(77, 25, 23, 80)]);
    add('y', [polyline([[20, 22], [49, 55], [78, 21]]), line(49, 55, 38, 92)]);
    return t;
  })();

  function getVectorRasters(label) {
    if (vectorTemplateCache.has(label)) return vectorTemplateCache.get(label);
    const variants = (VECTOR_TEMPLATES[label] || []).map((strokes) => rasterizeStrokes(strokes, 32));
    vectorTemplateCache.set(label, variants);
    return variants;
  }

  function baseCandidates(raster, chars) {
    const unique = [...new Set(chars)];
    const results = [];
    for (const char of unique) {
      let best = rasterScore(raster, renderGlyph(char, 32, true));
      for (const variant of getVectorRasters(char)) best = Math.min(best, rasterScore(raster, variant));
      results.push({ text: char, score: best });
    }
    return results.sort((a, b) => a.score - b.score);
  }

  async function ensureHangulIndex(progress = () => {}) {
    if (hangulIndex) return hangulIndex;
    if (hangulBuildPromise) return hangulBuildPromise;
    hangulBuildPromise = (async () => {
      const count = HANGUL_END - HANGUL_START + 1;
      const features = new Uint8Array(count * 64);
      const cell = 24, columns = 32, rows = 16, batchSize = columns * rows;
      const atlas = makeCanvas(cell * columns, cell * rows);
      const ctx = atlas.getContext('2d', { willReadFrequently: true });
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillStyle = '#000';
      ctx.font = '600 20px system-ui, -apple-system, "Noto Sans CJK KR", sans-serif';
      for (let start = 0; start < count; start += batchSize) {
        ctx.clearRect(0, 0, atlas.width, atlas.height);
        const end = Math.min(count, start + batchSize);
        for (let index = start; index < end; index++) {
          const local = index - start, col = local % columns, row = Math.floor(local / columns);
          ctx.fillText(String.fromCharCode(HANGUL_START + index), col * cell + cell / 2, row * cell + cell / 2 + .5);
        }
        const image = ctx.getImageData(0, 0, atlas.width, atlas.height).data;
        for (let index = start; index < end; index++) {
          const local = index - start, col = local % columns, row = Math.floor(local / columns);
          for (let fy = 0; fy < 8; fy++) {
            for (let fx = 0; fx < 8; fx++) {
              let sum = 0;
              for (let py = 0; py < 3; py++) {
                for (let px = 0; px < 3; px++) {
                  const x = col * cell + fx * 3 + px;
                  const y = row * cell + fy * 3 + py;
                  sum += image[(y * atlas.width + x) * 4 + 3];
                }
              }
              features[index * 64 + fy * 8 + fx] = Math.round(sum / 9);
            }
          }
        }
        progress(Math.round(end / count * 100));
        await tick();
      }
      hangulIndex = { features, count };
      return hangulIndex;
    })().finally(() => { hangulBuildPromise = null; });
    return hangulBuildPromise;
  }

  class MaxHeap {
    constructor(limit) { this.limit = limit; this.items = []; }
    push(item) {
      if (this.items.length < this.limit) { this.items.push(item); this.up(this.items.length - 1); return; }
      if (item.score >= this.items[0].score) return;
      this.items[0] = item; this.down(0);
    }
    up(index) {
      while (index > 0) {
        const parent = Math.floor((index - 1) / 2);
        if (this.items[parent].score >= this.items[index].score) break;
        [this.items[parent], this.items[index]] = [this.items[index], this.items[parent]]; index = parent;
      }
    }
    down(index) {
      while (true) {
        let largest = index, left = index * 2 + 1, right = left + 1;
        if (left < this.items.length && this.items[left].score > this.items[largest].score) largest = left;
        if (right < this.items.length && this.items[right].score > this.items[largest].score) largest = right;
        if (largest === index) break;
        [this.items[largest], this.items[index]] = [this.items[index], this.items[largest]]; index = largest;
      }
    }
    sorted() { return [...this.items].sort((a, b) => a.score - b.score); }
  }

  async function hangulCandidates(raster, limit = 5, progress = () => {}) {
    const index = await ensureHangulIndex(progress);
    const feature = downsample(raster.alpha, raster.size, 8);
    const heap = new MaxHeap(140);
    for (let charIndex = 0; charIndex < index.count; charIndex++) {
      let score = 0;
      const offset = charIndex * 64;
      for (let i = 0; i < 64; i++) {
        const diff = feature[i] - index.features[offset + i];
        score += diff * diff;
      }
      heap.push({ charIndex, score });
    }
    const refined = [];
    for (const item of heap.sorted()) {
      const char = String.fromCharCode(HANGUL_START + item.charIndex);
      refined.push({ text: char, score: rasterScore(raster, renderGlyph(char, 32, true)) });
    }
    return refined.sort((a, b) => a.score - b.score).slice(0, limit);
  }

  function strokeAngle(stroke) {
    const first = stroke[0], last = stroke[stroke.length - 1];
    return Math.atan2((last?.y || 0) - (first?.y || 0), (last?.x || 0) - (first?.x || 0));
  }

  function angleDistance(angle, target) {
    let value = Math.abs(angle - target);
    while (value > Math.PI) value = Math.abs(value - Math.PI * 2);
    return value;
  }

  function mathGestureCandidates(strokes, bounds) {
    if (!strokes?.length) return [];
    const candidates = [];
    const push = (text, score) => {
      if (!candidates.some((item) => item.text === text)) candidates.push({ text, score });
    };
    const box = bounds || boundsOfStrokes(strokes);
    if (Math.max(box.w, box.h) < 12) return candidates;
    const descriptors = strokes.map((stroke) => {
      const b = strokeBounds(stroke);
      const angle = strokeAngle(stroke);
      const horizontal = Math.min(angleDistance(angle, 0), angleDistance(angle, Math.PI)) < .28 && b.w > b.h * 2.5;
      const vertical = angleDistance(Math.abs(angle), Math.PI / 2) < .34 && b.h > b.w * 2.2;
      const diagonal = !horizontal && !vertical && b.w > 7 && b.h > 7 && Math.min(b.w, b.h) / Math.max(b.w, b.h) > .35;
      const sign = Math.sign(((stroke[stroke.length - 1]?.x || 0) - (stroke[0]?.x || 0)) * ((stroke[stroke.length - 1]?.y || 0) - (stroke[0]?.y || 0)));
      return { stroke, bounds: b, horizontal, vertical, diagonal, sign };
    });
    const horizontal = descriptors.filter((item) => item.horizontal);
    const vertical = descriptors.filter((item) => item.vertical);
    const diagonal = descriptors.filter((item) => item.diagonal);
    const intersects = (a, b) => strokesCross(a.stroke, b.stroke);

    if (strokes.length === 1 && horizontal.length === 1 && box.w > 16 && box.h < Math.max(10, box.w * .22)) push('-', .01);
    if (horizontal.length >= 2 && box.w > 18) push('=', .008);
    if (horizontal.length && vertical.length && horizontal.some((h) => vertical.some((v) => intersects(h, v)))) push('+', .006);
    if (diagonal.length >= 2 && diagonal.some((a) => diagonal.some((b) => a !== b && a.sign !== b.sign && intersects(a, b)))) push('*', .01);
    if (strokes.length === 1 && diagonal.length === 1 && box.h > box.w * .55 && box.w > 10) push('/', .035);
    return candidates;
  }

  async function classifyComponent(strokes, mode = 'auto', progress = () => {}) {
    const raster = rasterizeStrokes(strokes, 32);
    let candidates = baseCandidates(raster, mode === 'math' ? MATH_CHARS : BASE_LATIN).slice(0, 10);
    if (mode === 'math') {
      const gestures = mathGestureCandidates(strokes, raster.bounds);
      if (gestures.length) {
        const seen = new Set(gestures.map((item) => item.text));
        candidates = [...gestures, ...candidates.filter((item) => !seen.has(item.text))].slice(0, 10);
      }
    }
    const shouldTryHangul = mode === 'ko' || (mode === 'auto' && raster.aspect > .32 && raster.aspect < 2.25 && candidates[0]?.score > .105);
    if (shouldTryHangul) {
      const korean = await hangulCandidates(raster, 6, progress);
      candidates = [...candidates, ...korean].sort((a, b) => a.score - b.score).slice(0, 8);
    }
    return candidates.map((candidate) => ({ ...candidate, confidence: clamp(Math.exp(-candidate.score * 5.4), .01, .995) }));
  }

  function strokeBounds(stroke) {
    return boundsOfStrokes([stroke]);
  }

  function boxDistance(a, b) {
    const dx = Math.max(0, Math.max(a.x, b.x) - Math.min(a.x + a.w, b.x + b.w));
    const dy = Math.max(0, Math.max(a.y, b.y) - Math.min(a.y + a.h, b.y + b.h));
    return { dx, dy, distance: Math.hypot(dx, dy) };
  }

  function overlapRatio(a0, a1, b0, b1) {
    return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0)) / Math.max(1, Math.min(a1 - a0, b1 - b0));
  }

  function segmentIntersects(a, b, c, d) {
    const cross = (p, q, r) => (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x);
    const c1 = cross(a, b, c), c2 = cross(a, b, d), c3 = cross(c, d, a), c4 = cross(c, d, b);
    return ((c1 <= 0 && c2 >= 0) || (c1 >= 0 && c2 <= 0)) && ((c3 <= 0 && c4 >= 0) || (c3 >= 0 && c4 <= 0));
  }

  function strokesCross(first, second) {
    const stepA = Math.max(1, Math.floor(first.length / 20));
    const stepB = Math.max(1, Math.floor(second.length / 20));
    for (let i = stepA; i < first.length; i += stepA) {
      for (let j = stepB; j < second.length; j += stepB) {
        if (segmentIntersects(first[i - stepA], first[i], second[j - stepB], second[j])) return true;
      }
    }
    return false;
  }

  function groupStrokes(strokes, mode = 'auto') {
    if (!strokes.length) return [];
    const boxes = strokes.map(strokeBounds);
    const substantial = boxes.map((box) => Math.max(box.w, box.h)).filter((value) => value > 4);
    const scale = clamp(median(substantial) || 48, 18, 150);
    const parent = strokes.map((_, index) => index);
    const find = (index) => parent[index] === index ? index : (parent[index] = find(parent[index]));
    const join = (a, b) => { a = find(a); b = find(b); if (a !== b) parent[b] = a; };
    for (let i = 0; i < strokes.length; i++) {
      for (let j = i + 1; j < strokes.length; j++) {
        const a = boxes[i], b = boxes[j];
        const { dx, dy, distance } = boxDistance(a, b);
        const xOverlap = overlapRatio(a.x, a.x + a.w, b.x, b.x + b.w);
        const yOverlap = overlapRatio(a.y, a.y + a.h, b.y, b.y + b.h);
        const centerDistance = Math.hypot(a.x + a.w / 2 - b.x - b.w / 2, a.y + a.h / 2 - b.y - b.h / 2);
        const timeA = strokes[i][strokes[i].length - 1]?.t || 0;
        const timeB = strokes[j][0]?.t || 0;
        const timeGap = Math.abs(timeB - timeA);
        const intersects = dx === 0 && dy === 0;
        const crossing = intersects && strokesCross(strokes[i], strokes[j]);
        const closeVertical = xOverlap > .28 && dy < scale * (mode === 'ko' ? .46 : .34);
        const closeHorizontal = yOverlap > .48 && dx < scale * (mode === 'ko' ? .18 : .085);
        const closeDot = xOverlap > .45 && dy < scale * .62 && Math.min(a.w * a.h, b.w * b.h) < scale * scale * .12;
        const temporal = timeGap > 0 && timeGap < 330 && distance < scale * .24;
        if (crossing || (intersects && centerDistance < scale * .95) || closeVertical || closeHorizontal || closeDot || temporal) join(i, j);
      }
    }
    const groups = new Map();
    for (let index = 0; index < strokes.length; index++) {
      const root = find(index);
      if (!groups.has(root)) groups.set(root, []);
      groups.get(root).push(strokes[index]);
    }
    return [...groups.values()].map((group) => ({ strokes: group, bounds: boundsOfStrokes(group) }));
  }

  function splitComponent(component, mode = 'auto') {
    const strokes = component.strokes;
    if (strokes.length < 2) return [component];
    const boxes = strokes.map(strokeBounds).sort((a, b) => a.x - b.x);
    const total = component.bounds;
    if (total.w / Math.max(1, total.h) < (mode === 'ko' ? 2.1 : 1.55)) return [component];
    const threshold = total.h * (mode === 'ko' ? .28 : .17);
    let cursor = boxes[0].x + boxes[0].w;
    const cuts = [];
    for (let i = 1; i < boxes.length; i++) {
      const gap = boxes[i].x - cursor;
      if (gap > threshold) cuts.push((cursor + boxes[i].x) / 2);
      cursor = Math.max(cursor, boxes[i].x + boxes[i].w);
    }
    if (!cuts.length) return [component];
    const buckets = Array.from({ length: cuts.length + 1 }, () => []);
    for (const stroke of strokes) {
      const box = strokeBounds(stroke), center = box.x + box.w / 2;
      let bucket = 0;
      while (bucket < cuts.length && center > cuts[bucket]) bucket++;
      buckets[bucket].push(stroke);
    }
    return buckets.filter((bucket) => bucket.length).map((bucket) => ({ strokes: bucket, bounds: boundsOfStrokes(bucket) }));
  }

  function organizeLines(components) {
    const lines = [];
    const ordered = [...components].sort((a, b) => (a.bounds.y + a.bounds.h / 2) - (b.bounds.y + b.bounds.h / 2));
    for (const component of ordered) {
      const center = component.bounds.y + component.bounds.h / 2;
      let best = null, bestDistance = Infinity;
      for (const line of lines) {
        const lineCenter = line.center;
        const threshold = Math.max(line.height, component.bounds.h) * .68;
        const overlap = overlapRatio(line.top, line.bottom, component.bounds.y, component.bounds.y + component.bounds.h);
        const distance = Math.abs(center - lineCenter);
        if ((overlap > .18 || distance < threshold) && distance < bestDistance) { best = line; bestDistance = distance; }
      }
      if (!best) {
        best = { components: [], top: component.bounds.y, bottom: component.bounds.y + component.bounds.h, center, height: component.bounds.h };
        lines.push(best);
      }
      best.components.push(component);
      best.top = Math.min(best.top, component.bounds.y);
      best.bottom = Math.max(best.bottom, component.bounds.y + component.bounds.h);
      best.height = best.bottom - best.top;
      best.center = (best.top + best.bottom) / 2;
    }
    return lines.sort((a, b) => a.top - b.top).map((line) => ({ ...line, components: line.components.sort((a, b) => a.bounds.x - b.bounds.x) }));
  }

  async function recognizeTextStrokes(strokes, mode = 'auto', progress = () => {}) {
    if (!strokes.length) throw new Error('인식할 필기 획이 없습니다.');
    let components = groupStrokes(strokes, mode).flatMap((component) => splitComponent(component, mode));
    const lines = organizeLines(components);
    const outputLines = [];
    const details = [];
    let processed = 0;
    const total = Math.max(1, components.length);
    for (const line of lines) {
      const widths = line.components.map((component) => component.bounds.w).filter((value) => value > 2);
      const typicalWidth = median(widths) || line.height * .65;
      let text = '';
      let previous = null;
      for (const component of line.components) {
        if (previous) {
          const gap = component.bounds.x - (previous.bounds.x + previous.bounds.w);
          if (gap > Math.max(typicalWidth * .78, line.height * .42)) text += ' ';
        }
        const candidates = await classifyComponent(component.strokes, mode, (percent) => progress(`한글 모델 준비 ${percent}%`));
        const best = candidates[0] || { text: '□', confidence: 0, score: 1 };
        text += best.text;
        details.push({ bounds: component.bounds, candidates, selected: best.text });
        previous = component;
        processed++;
        progress(`문자 인식 ${processed}/${total}`);
        if (processed % 3 === 0) await tick();
      }
      outputLines.push(text.trimEnd());
    }
    const confidence = details.length ? details.reduce((sum, item) => sum + (item.candidates[0]?.confidence || 0), 0) / details.length : 0;
    return { text: outputLines.join('\n'), confidence, details, lines: outputLines.length, bounds: boundsOfStrokes(strokes) };
  }

  function componentIsFractionBar(component, typicalHeight) {
    const b = component.bounds;
    return b.w > typicalHeight * 1.05 && b.h < Math.max(9, typicalHeight * .18) && b.w / Math.max(1, b.h) > 4;
  }

  function normalizeMathToken(token) {
    if (token === '×' || token === 'x') return '*';
    if (token === '÷') return '/';
    if (token === 'π') return 'pi';
    return token;
  }

  function addImplicitMultiplication(expression) {
    return expression
      .replace(/(\d|\)|pi)(?=\()/g, '$1*')
      .replace(/(\d|\))(?=(pi|[a-z]))/g, '$1*')
      .replace(/(pi|[a-z]|\))(?=\d)/g, '$1*');
  }

  async function recognizeMathStrokes(strokes, progress = () => {}, depth = 0) {
    if (!strokes.length) return { expression: '', confidence: 0, details: [] };
    let components = groupStrokes(strokes, 'math').flatMap((component) => splitComponent(component, 'math'));
    components.sort((a, b) => a.bounds.x - b.bounds.x);
    const typicalHeight = median(components.map((component) => component.bounds.h).filter((value) => value > 5)) || 50;

    if (depth < 2) {
      for (const bar of components.filter((component) => componentIsFractionBar(component, typicalHeight))) {
        const left = bar.bounds.x - typicalHeight * .1, right = bar.bounds.x + bar.bounds.w + typicalHeight * .1;
        const above = strokes.filter((stroke) => {
          const b = strokeBounds(stroke), cx = b.x + b.w / 2;
          return cx >= left && cx <= right && b.y + b.h < bar.bounds.y + typicalHeight * .12;
        });
        const below = strokes.filter((stroke) => {
          const b = strokeBounds(stroke), cx = b.x + b.w / 2;
          return cx >= left && cx <= right && b.y > bar.bounds.y + bar.bounds.h - typicalHeight * .12;
        });
        if (above.length && below.length) {
          const numerator = await recognizeMathStrokes(above, progress, depth + 1);
          const denominator = await recognizeMathStrokes(below, progress, depth + 1);
          if (numerator.expression && denominator.expression) {
            return {
              expression: `(${numerator.expression})/(${denominator.expression})`,
              confidence: Math.min(numerator.confidence, denominator.confidence),
              details: [...numerator.details, ...denominator.details]
            };
          }
        }
      }
    }

    const baselineComponents = components.filter((component) => !componentIsFractionBar(component, typicalHeight));
    const centers = baselineComponents.map((component) => component.bounds.y + component.bounds.h / 2);
    const baseline = median(centers) || typicalHeight;
    const classified = [];
    for (let i = 0; i < baselineComponents.length; i++) {
      progress(`수식 기호 인식 ${i + 1}/${baselineComponents.length}`);
      const candidates = await classifyComponent(baselineComponents[i].strokes, 'math');
      classified.push({ component: baselineComponents[i], candidates: candidates.slice(0, 3) });
      if (i % 3 === 2) await tick();
    }

    let beams = [{ expression: '', cost: 0, previous: null }];
    for (const item of classified) {
      const next = [];
      for (const beam of beams) {
        for (const candidate of item.candidates) {
          let token = normalizeMathToken(candidate.text);
          const superscript = item.component.bounds.y + item.component.bounds.h < baseline - typicalHeight * .08 && item.component.bounds.h < typicalHeight * .8 && beam.expression;
          let expression = beam.expression;
          if (token === '√') token = 'sqrt';
          if (superscript && /^[0-9a-z]$/.test(token)) expression += `^${token}`;
          else if (token === 'sqrt') expression += 'sqrt(';
          else expression += token;
          next.push({ expression, cost: beam.cost + candidate.score, previous: token });
        }
      }
      beams = next.sort((a, b) => a.cost - b.cost).slice(0, 18);
    }

    const scored = [];
    for (const beam of beams) {
      let expression = beam.expression;
      const opens = (expression.match(/\(/g) || []).length, closes = (expression.match(/\)/g) || []).length;
      if (opens > closes) expression += ')'.repeat(opens - closes);
      expression = addImplicitMultiplication(expression);
      let valid = false;
      try { api.evaluateMath(expression, { degree: !!api.state.math.degree }); valid = true; } catch {}
      scored.push({ ...beam, expression, valid, totalCost: beam.cost + (valid ? -.18 : .22) });
    }
    scored.sort((a, b) => a.totalCost - b.totalCost);
    const best = scored[0] || { expression: '', cost: 1 };
    const confidence = clamp(Math.exp(-(best.cost / Math.max(1, classified.length)) * 5), .01, .99);
    return { expression: best.expression, confidence, alternatives: scored.slice(0, 5).map((item) => item.expression), details: classified };
  }

  function pageSourceStrokes() {
    const page = currentPage();
    if (!page) return { strokes: [], source: 'none', objects: [] };
    const selection = api.state.selection;
    const selectedIds = selection?.pageIndex === api.state.currentPageIndex ? new Set(selection.ids || []) : null;
    let objects = (page.objects || []).filter((object) => object.type === 'stroke' && object.brush !== 'highlighter' && object.points?.length);
    if (selectedIds) {
      const selected = objects.filter((object) => selectedIds.has(object.id));
      if (selected.length) objects = selected;
    }
    return { strokes: objects.map((object) => object.points), source: selectedIds && objects.length ? 'selection' : 'page', objects };
  }

  function renderSourcePreview() {
    const source = pageSourceStrokes();
    if (!ocrPreviewPad) return;
    ocrPreviewPad.setStrokes(source.strokes, true);
    const label = document.getElementById('ocrSourceLabel');
    if (label) label.textContent = source.source === 'selection' ? `선택한 필기 ${source.strokes.length}획` : `현재 페이지 필기 ${source.strokes.length}획`;
    const runButton = document.querySelector('[data-action="run-page-ocr"]');
    if (runButton) runButton.disabled = !source.strokes.length;
  }

  function setOcrResult(result) {
    lastOcrResult = result;
    const input = document.getElementById('ocrResultText');
    if (input) input.value = result?.text || '';
    const confidence = document.getElementById('ocrConfidence');
    if (confidence) confidence.textContent = result ? `평균 신뢰도 ${Math.round(result.confidence * 100)}% · ${result.lines}줄` : '아직 인식하지 않았습니다.';
    const status = document.getElementById('ocrStatus');
    if (status) status.textContent = result ? '인식 완료 · 결과를 직접 교정할 수 있습니다.' : '선택 영역이 있으면 선택 필기만, 없으면 현재 페이지 전체를 인식합니다.';
    const candidates = document.getElementById('ocrCandidateList');
    if (candidates) {
      const uncertain = (result?.details || []).filter((detail) => (detail.candidates[0]?.confidence || 0) < .72).slice(0, 8);
      candidates.innerHTML = uncertain.length ? uncertain.map((detail, index) => {
        const options = detail.candidates.slice(0, 4).map((candidate) => `<button class="recognition-chip" data-action="ocr-replace-candidate" data-detail-index="${result.details.indexOf(detail)}" data-candidate="${escapeHtml(candidate.text)}">${escapeHtml(candidate.text)} <small>${Math.round(candidate.confidence * 100)}%</small></button>`).join('');
        return `<div class="uncertain-row"><span>낮은 신뢰도</span><div>${options}</div></div>`;
      }).join('') : '<span class="recognition-all-clear">인식 후보가 안정적입니다.</span>';
    }
  }

  async function runPageOcr() {
    if (recognitionBusy) return;
    const source = pageSourceStrokes();
    if (!source.strokes.length) { toast('현재 페이지에 인식할 필기가 없습니다.'); return; }
    const mode = document.getElementById('ocrLanguage')?.value || 'auto';
    setBusy(true, '필기 구조 분석 중…');
    try {
      const result = mode === 'math'
        ? await recognizeMathStrokes(source.strokes, (message) => setBusy(true, message)).then((math) => ({ text: math.expression, confidence: math.confidence, details: math.details.flatMap((item) => item.candidates ? [{ bounds: item.component.bounds, candidates: item.candidates }] : []), lines: 1, bounds: boundsOfStrokes(source.strokes), math }))
        : await recognizeTextStrokes(source.strokes, mode, (message) => setBusy(true, message));
      setOcrResult(result);
    } catch (error) {
      setOcrResult(null);
      toast(`손글씨 인식 실패: ${error.message || error}`, 4200);
    } finally { setBusy(false, lastOcrResult ? '인식 완료 · 결과를 직접 교정할 수 있습니다.' : '인식 대기'); }
  }

  function replaceCandidate(detailIndex, candidate) {
    if (!lastOcrResult?.details?.[detailIndex]) return;
    lastOcrResult.details[detailIndex].selected = candidate;
    const lines = organizeLines(lastOcrResult.details.map((detail) => ({ strokes: [], bounds: detail.bounds, selected: detail.selected })));
    const output = [];
    for (const line of lines) {
      const widths = line.components.map((component) => component.bounds.w);
      const typical = median(widths) || line.height * .65;
      let text = '', previous = null;
      for (const component of line.components) {
        if (previous) {
          const gap = component.bounds.x - (previous.bounds.x + previous.bounds.w);
          if (gap > Math.max(typical * .78, line.height * .42)) text += ' ';
        }
        text += component.selected || '□';
        previous = component;
      }
      output.push(text);
    }
    lastOcrResult.text = output.join('\n');
    document.getElementById('ocrResultText').value = lastOcrResult.text;
  }

  async function saveOcrIndex(insertVisible = false) {
    const page = currentPage();
    const doc = currentDoc();
    const text = document.getElementById('ocrResultText')?.value.trim();
    if (!page || !doc || !text) { toast('저장할 인식 결과가 없습니다.'); return; }
    checkpoint(insertVisible ? 'ocr-insert' : 'ocr-index');
    const bounds = lastOcrResult?.bounds || { x: 80, y: 100, w: 700, h: 100 };
    const existing = page.objects.find((object) => object.type === OCR_INDEX_TYPE && object.source === (api.state.selection ? 'selection' : 'page'));
    if (existing) {
      existing.text = text; existing.updatedAt = new Date().toISOString(); existing.x = bounds.x; existing.y = bounds.y; existing.w = bounds.w; existing.h = bounds.h;
    } else {
      page.objects.push({ id: uid('ocr'), type: OCR_INDEX_TYPE, source: api.state.selection ? 'selection' : 'page', text, x: bounds.x, y: bounds.y, w: bounds.w, h: bounds.h, hidden: true, locked: true, createdAt: new Date().toISOString() });
    }
    if (insertVisible) {
      page.objects.push({ id: uid('text'), type: 'text', x: clamp(bounds.x, 40, PAGE_WIDTH - 600), y: clamp(bounds.y + bounds.h + 22, 40, PAGE_HEIGHT - 160), w: 600, h: Math.max(90, text.split('\n').length * 40 + 32), text, color: '#1f2937', fontSize: 28, background: 'transparent' });
    }
    await persist(insertVisible ? 'ocr-insert' : 'ocr-index');
    api.renderPageCanvas(api.state.currentPageIndex);
    if (api.renderDocumentSearch) api.renderDocumentSearch();
    toast(insertVisible ? '인식 결과를 텍스트로 삽입했습니다.' : '손글씨 검색 색인을 저장했습니다.');
  }

  async function copyOcrText() {
    const text = document.getElementById('ocrResultText')?.value || '';
    if (!text) return;
    try { await navigator.clipboard.writeText(text); toast('인식 결과를 복사했습니다.'); }
    catch { toast('클립보드에 복사하지 못했습니다.'); }
  }

  async function recognizeMathPad() {
    if (recognitionBusy || !mathPad?.strokes.length) { if (!mathPad?.strokes.length) toast('수식을 손글씨로 먼저 작성하세요.'); return; }
    const status = document.getElementById('mathInkStatus');
    setBusy(true);
    if (status) status.textContent = '수식 구조 분석 중…';
    try {
      const result = await recognizeMathStrokes(mathPad.strokes, (message) => { if (status) status.textContent = message; });
      const input = document.getElementById('mathExpressionInput');
      input.value = result.expression;
      api.state.math.expression = result.expression;
      const alternatives = document.getElementById('mathInkAlternatives');
      alternatives.innerHTML = (result.alternatives || []).filter(Boolean).slice(0, 5).map((expression, index) => `<button class="recognition-chip ${index === 0 ? 'is-primary' : ''}" data-action="math-use-alternative" data-expression="${escapeHtml(expression)}">${escapeHtml(expression)}</button>`).join('');
      if (status) status.textContent = `인식 신뢰도 ${Math.round(result.confidence * 100)}% · 후보를 누르거나 식을 직접 교정하세요.`;
      document.querySelector('[data-action="calculate-math"]')?.click();
    } catch (error) {
      if (status) status.textContent = `인식 오류: ${error.message || error}`;
      toast(`손글씨 수식 인식 실패: ${error.message || error}`);
    } finally { setBusy(false); }
  }

  function setMathMode(mode) {
    mathMode = mode;
    document.querySelectorAll('[data-math-mode]').forEach((button) => button.classList.toggle('is-active', button.dataset.mathMode === mode));
    document.getElementById('mathKeyboardPanel').hidden = mode !== 'keyboard';
    document.getElementById('mathInkPanel').hidden = mode !== 'ink';
    if (mode === 'ink') setTimeout(() => mathPad?.resize(), 60);
  }

  function openOcrSheet() {
    if (!currentDoc()) { toast('먼저 노트를 여세요.'); return; }
    document.getElementById('modalBackdrop').hidden = false;
    document.getElementById('ocrSheet').hidden = false;
    document.body.classList.add('modal-open');
    renderSourcePreview();
    setOcrResult(null);
    setTimeout(() => ocrPreviewPad?.resize(), 60);
  }

  function closeOcrSheet() {
    document.getElementById('ocrSheet').hidden = true;
    const otherOpen = [...document.querySelectorAll('.modal')].some((node) => !node.hidden);
    if (!otherOpen) {
      document.getElementById('modalBackdrop').hidden = true;
      document.body.classList.remove('modal-open');
    }
  }

  function injectToolbarButton() {
    const toolbar = document.querySelector('.editor-navbar .nav-right') || document.getElementById('mainToolbar');
    if (!toolbar || document.getElementById('ocrToolbarButton')) return;
    const anchor = toolbar.querySelector('[data-action="editor-more"]');
    const button = document.createElement('button');
    button.id = 'ocrToolbarButton';
    button.className = 'icon-button toolbar-button';
    button.type = 'button';
    button.dataset.action = 'open-handwriting-ocr';
    button.setAttribute('aria-label', '손글씨 OCR·검색');
    button.title = '손글씨 OCR·검색';
    button.innerHTML = icon(OCR_ICON);
    toolbar.insertBefore(button, anchor || null);
  }

  function injectMathInkUi() {
    const sheet = document.getElementById('mathSheet');
    if (!sheet || document.getElementById('mathModeTabs')) return;
    const description = sheet.querySelector('.sheet-description');
    const tabs = document.createElement('div');
    tabs.id = 'mathModeTabs';
    tabs.className = 'recognition-tabs';
    tabs.innerHTML = `<button class="recognition-tab is-active" data-math-mode="keyboard">키보드</button><button class="recognition-tab" data-math-mode="ink">${icon(INK_MATH_ICON)} 손글씨 수식</button>`;
    description.insertAdjacentElement('afterend', tabs);
    const inputRow = sheet.querySelector('.math-input-row');
    const keypad = sheet.querySelector('.math-keypad');
    const keyboardPanel = document.createElement('div');
    keyboardPanel.id = 'mathKeyboardPanel';
    keyboardPanel.className = 'math-keyboard-panel';
    inputRow.parentNode.insertBefore(keyboardPanel, inputRow);
    keyboardPanel.appendChild(inputRow);
    keyboardPanel.appendChild(keypad);
    const inkPanel = document.createElement('div');
    inkPanel.id = 'mathInkPanel';
    inkPanel.className = 'math-ink-panel';
    inkPanel.hidden = true;
    inkPanel.innerHTML = `
      <div class="ink-canvas-shell math-ink-shell"><canvas id="mathInkCanvas"></canvas><span class="ink-corner-label">필압·S Pen 지원</span></div>
      <div class="ink-action-row">
        <button class="secondary-button compact-action" data-action="math-ink-undo">${icon(UNDO_ICON)}<span>한 획 취소</span></button>
        <button class="secondary-button compact-action" data-action="math-ink-clear">${icon(TRASH_ICON)}<span>지우기</span></button>
        <button class="primary-button" data-action="recognize-math-ink" data-recognition-run>${icon(INK_MATH_ICON)}<span>인식·계산</span></button>
      </div>
      <div id="mathInkStatus" class="recognition-status">숫자와 + − × ÷ = ( ) √ 거듭제곱, 간단한 분수를 인식합니다.</div>
      <div id="mathInkAlternatives" class="recognition-chip-row"></div>`;
    keyboardPanel.insertAdjacentElement('afterend', inkPanel);
    mathPad = new InkPad(document.getElementById('mathInkCanvas'), { lineWidth: 3.5, onChange: () => { const status = document.getElementById('mathInkStatus'); if (status) status.textContent = '작성 완료 후 인식·계산을 누르세요.'; } });
  }

  function injectOcrSheet() {
    if (document.getElementById('ocrSheet')) return;
    const sheet = document.createElement('section');
    sheet.id = 'ocrSheet';
    sheet.className = 'sheet modal ocr-sheet';
    sheet.hidden = true;
    sheet.setAttribute('aria-label', '손글씨 OCR');
    sheet.innerHTML = `
      <header class="sheet-header">
        <div><span class="eyebrow">Offline Digital Ink</span><h2>손글씨 OCR·검색</h2></div>
        <button class="icon-button" data-action="close-ocr" aria-label="닫기"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m6 6 12 12M18 6 6 18"/></svg></button>
      </header>
      <div class="ocr-toolbar-row">
        <span id="ocrSourceLabel" class="source-pill">현재 페이지</span>
        <label class="recognition-select-label"><span>언어</span><select id="ocrLanguage" class="recognition-select"><option value="auto">한글+영문 자동</option><option value="ko">한글 우선</option><option value="en">영문·숫자</option><option value="math">수식</option></select></label>
        <button class="secondary-button compact-action" data-action="ocr-refresh-source">${icon(SEARCH_ICON)}<span>영역 새로고침</span></button>
      </div>
      <div class="ink-canvas-shell ocr-preview-shell"><canvas id="ocrPreviewCanvas"></canvas><span class="ink-corner-label">선택 영역 우선</span></div>
      <div class="ink-action-row">
        <button class="primary-button" data-action="run-page-ocr" data-recognition-run>${icon(OCR_ICON)}<span>필기 인식</span></button>
        <span id="ocrConfidence" class="recognition-confidence">아직 인식하지 않았습니다.</span>
      </div>
      <div id="ocrStatus" class="recognition-status">선택 영역이 있으면 선택 필기만, 없으면 현재 페이지 전체를 인식합니다.</div>
      <label class="field-label" for="ocrResultText">인식 결과 · 직접 교정 가능</label>
      <textarea id="ocrResultText" class="text-area ocr-result-area" rows="5" placeholder="인식 결과가 여기에 표시됩니다."></textarea>
      <div id="ocrCandidateList" class="ocr-candidate-list"></div>
      <div class="sheet-actions ocr-sheet-actions">
        <button class="secondary-button" data-action="copy-ocr">${icon(COPY_ICON)}<span>복사</span></button>
        <button class="secondary-button" data-action="save-ocr-index">${icon(SEARCH_ICON)}<span>검색 색인 저장</span></button>
        <button class="primary-button" data-action="insert-ocr-text">${icon(TEXT_ICON)}<span>텍스트로 삽입</span></button>
      </div>
      <p class="recognition-footnote">기기 내부에서 동작하며 네트워크로 필기를 전송하지 않습니다. 또박또박 쓴 한글·영문·숫자와 수식에 최적화되어 있습니다.</p>`;
    const settings = document.getElementById('settingsSheet');
    settings.parentNode.insertBefore(sheet, settings);
    ocrPreviewPad = new InkPad(document.getElementById('ocrPreviewCanvas'), { readOnly: true, lineWidth: 2.8 });
  }

  function injectSettingsRow() {
    const list = document.querySelector('#settingsSheet .settings-list');
    if (!list || document.getElementById('ocrAutoIndexToggle')) return;
    const row = document.createElement('label');
    row.className = 'setting-row';
    row.innerHTML = '<span><strong>손글씨 검색 색인 알림</strong><small>필기 획이 많은 페이지에서 OCR 색인 생성을 제안합니다.</small></span><input id="ocrAutoIndexToggle" type="checkbox" checked />';
    list.appendChild(row);
  }

  function handleAction(event) {
    const target = event.target.closest('[data-action], [data-math-mode]');
    if (!target) return;
    const action = target.dataset.action;
    const handled = [
      'open-handwriting-ocr', 'close-ocr', 'ocr-refresh-source', 'run-page-ocr', 'save-ocr-index', 'insert-ocr-text', 'copy-ocr',
      'ocr-replace-candidate', 'math-ink-undo', 'math-ink-clear', 'recognize-math-ink', 'math-use-alternative'
    ].includes(action) || !!target.dataset.mathMode;
    if (!handled) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (target.dataset.mathMode) { setMathMode(target.dataset.mathMode); return; }
    switch (action) {
      case 'open-handwriting-ocr': openOcrSheet(); break;
      case 'close-ocr': closeOcrSheet(); break;
      case 'ocr-refresh-source': renderSourcePreview(); toast('현재 선택 영역을 반영했습니다.'); break;
      case 'run-page-ocr': runPageOcr(); break;
      case 'save-ocr-index': saveOcrIndex(false); break;
      case 'insert-ocr-text': saveOcrIndex(true); break;
      case 'copy-ocr': copyOcrText(); break;
      case 'ocr-replace-candidate': replaceCandidate(Number(target.dataset.detailIndex), target.dataset.candidate); break;
      case 'math-ink-undo': mathPad?.undo(); break;
      case 'math-ink-clear': mathPad?.clear(); document.getElementById('mathInkAlternatives').innerHTML = ''; break;
      case 'recognize-math-ink': recognizeMathPad(); break;
      case 'math-use-alternative': {
        const input = document.getElementById('mathExpressionInput');
        input.value = target.dataset.expression || '';
        api.state.math.expression = input.value;
        document.querySelector('[data-action="calculate-math"]')?.click();
        break;
      }
    }
  }

  function handleBackdrop(event) {
    if (event.target.id === 'modalBackdrop' && !document.getElementById('ocrSheet').hidden) closeOcrSheet();
  }

  function monitorPageChanges() {
    const stack = document.getElementById('pageStack');
    if (!stack) return;
    const observer = new MutationObserver(() => {
      if (!document.getElementById('ocrSheet')?.hidden) renderSourcePreview();
      const page = currentPage();
      const strokeCount = page?.objects?.filter((object) => object.type === 'stroke').length || 0;
      const hasIndex = page?.objects?.some((object) => object.type === OCR_INDEX_TYPE);
      if (strokeCount > 24 && !hasIndex && document.getElementById('ocrAutoIndexToggle')?.checked) {
        const button = document.getElementById('ocrToolbarButton');
        button?.classList.add('has-attention');
      } else document.getElementById('ocrToolbarButton')?.classList.remove('has-attention');
    });
    observer.observe(stack, { childList: true, subtree: true, attributes: true });
  }

  async function initialize() {
    for (let attempt = 0; attempt < 160; attempt++) {
      if (window.__inkforge?.ready) { api = window.__inkforge; break; }
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    if (!api) return;
    injectToolbarButton();
    injectMathInkUi();
    injectOcrSheet();
    injectSettingsRow();
    document.addEventListener('click', handleAction, true);
    document.getElementById('modalBackdrop')?.addEventListener('click', handleBackdrop, true);
    monitorPageChanges();
    api.recognition = {
      version: MODULE_VERSION,
      recognizeTextStrokes,
      recognizeMathStrokes,
      classifyComponent,
      ensureHangulIndex,
      openOcrSheet,
      get mathPad() { return mathPad; },
      get lastOcrResult() { return lastOcrResult; }
    };
    window.__inkforgeRecognitionReady = true;
  }

  initialize().catch((error) => {
    console.error('InkForge recognition initialization failed', error);
    window.__inkforgeRecognitionReady = false;
  });
})();
