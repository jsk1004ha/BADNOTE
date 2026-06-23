(() => {
  'use strict';

  const VERSION = '3.3.15';
  const PAGE_RENDER_SCALE_LIMIT = 4;
  const LEGACY_RASTER_PAGE_RENDER_SCALE_LIMIT = 2.15;
  const RASTER_PAGE_RENDER_SCALE_LIMIT = 2.55;
  const RASTER_PAGE_RENDER_PIXEL_LIMIT = 9500000;
  const HIGH_ZOOM_CANVAS_WINDOW = 1.65;
  const SHAPE_HOLD_MS = 650;
  const LIVE_SHAPE_HOLD_MS = 1000;
  const STROKE_SAMPLE_SCREEN_PX = 1.1;
  const STROKE_HOLD_RESET_SCREEN_PX = 3.5;
  const ZOOM_PAGE_SUPPRESS_MS = 320;
  const ZOOM_DRAG_LOCK_MS = 1000;
  const ZOOM_RENDER_DEBOUNCE_MS = 90;
  const RENDER_FRAME_BUDGET_MS = 7;
  const NORMAL_RENDER_FRAME_BUDGET_MS = 12;
  const LARGE_DOC_IMAGE_CACHE_LIMIT = 8;
  const NORMAL_IMAGE_CACHE_LIMIT = 28;
  const LARGE_DOC_DOM_WINDOW_RADIUS = 6;
  const MAX_PASSIVE_PAGE_DELTA = 2;
  const LARGE_DOC_PAGE_THRESHOLD = 120;
  const SIDEBAR_WINDOW_RADIUS = 40;
  const DB_NAME = 'inkforge-notes-studio';
  const DB_VERSION = 4;
  const PAGE_WIDTH = 1000;
  const PAGE_HEIGHT = 1414;
  const MAX_HISTORY = 18;

  const ICONS = {
    home: '<path d="M3 10.8 12 3l9 7.8v9.7a1.5 1.5 0 0 1-1.5 1.5H15v-7h-6v7H4.5A1.5 1.5 0 0 1 3 20.5z"/>',
    notebook: '<rect x="5" y="3" width="15" height="18" rx="2"/><path d="M9 3v18M3 7h4M3 11h4M3 15h4"/>',
    folder: '<path d="M3 6.5h7l2 2h9v10.5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M3 9h18"/>',
    star: '<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-2.9-5.6 2.9 1.1-6.2L3 9.6l6.2-.9z"/>',
    users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>',
    store: '<path d="M4 10v10h16V10M3 10l2-6h14l2 6"/><path d="M3 10a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0M9 20v-6h6v6"/>',
    trash: '<path d="M4 7h16M9 7V4h6v3M7 7l1 14h8l1-14M10 11v6M14 11v6"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21h-4v-.09A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3v-4h.09A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.09A1.7 1.7 0 0 0 15.4 4.6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.15.37.36.7.6 1 .28.3.67.4 1.1.4H21v4h-.09c-.43 0-.82.12-1.1.4-.24.3-.45.63-.6 1z"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    'plus-circle': '<circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/>',
    'notebook-plus': '<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 3v18M12 12h5M14.5 9.5v5"/>',
    'check-circle': '<circle cx="12" cy="12" r="9"/><path d="m8 12 2.5 2.5L16 9"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    'chevron-down': '<path d="m7 10 5 5 5-5"/>',
    'chevron-right': '<path d="m10 7 5 5-5 5"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    list: '<path d="M8 6h13M8 12h13M8 18h13"/><circle cx="4" cy="6" r="1"/><circle cx="4" cy="12" r="1"/><circle cx="4" cy="18" r="1"/>',
    sparkles: '<path d="m12 3 1.2 3.4L16.5 8l-3.3 1.6L12 13l-1.2-3.4L7.5 8l3.3-1.6zM19 13l.8 2.2L22 16l-2.2.8L19 19l-.8-2.2L16 16l2.2-.8zM5 14l.8 2.2L8 17l-2.2.8L5 20l-.8-2.2L2 17l2.2-.8z"/>',
    sidebar: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16M5.5 8h1M5.5 12h1M5.5 16h1"/>',
    lasso: '<path d="M8 5c5-3 12 0 12 5s-5 8-11 7c-4-.7-6-3-5-6 1-2 4-3 7-2 3 1 4 4 2 6-2 2-5 2-7 1" stroke-dasharray="3 2"/><path d="m15 18 4 4M18 18l-3 3"/>',
    pen: '<path d="m5 19 3.5-1 9.8-9.8a2 2 0 0 0 0-2.8l-.7-.7a2 2 0 0 0-2.8 0L5 14.5z"/><path d="m13.5 6 4.5 4.5M5 19l-1 2 2-1"/>',
    eraser: '<path d="m4 15 8.8-10a2 2 0 0 1 2.8-.2l3.6 3.1a2 2 0 0 1 .2 2.8L11 20H6l-2-2z"/><path d="m8 11 6 5M11 20h10"/>',
    text: '<rect x="4" y="5" width="16" height="14" rx="2"/><path d="M8 9V7h8v2M12 7v10M9 17h6"/>',
    sticky: '<path d="M4 4h16v12l-4 4H4z"/><path d="M16 20v-4h4M8 9h8M8 13h5"/>',
    sticker: '<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-2.9-5.6 2.9 1.1-6.2L3 9.6l6.2-.9z"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9" r="1.5"/><path d="m4 18 5-5 3 3 3-4 5 6"/>',
    ruler: '<path d="m4 17 13-13 3 3L7 20z"/><path d="m13 8 3 3M10 11l2 2M7 14l3 3"/>',
    mic: '<rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3M8 21h8"/>',
    math: '<path d="M5 5h6l-4 7 4 7H5M14 8h6M17 5v6M14 16h6"/>',
    'page-plus': '<path d="M6 3h9l4 4v14H6z"/><path d="M15 3v5h4M9 14h6M12 11v6"/>',
    share: '<path d="M12 16V3M7 8l5-5 5 5"/><path d="M5 12v8h14v-8"/>',
    more: '<circle cx="5" cy="12" r="1.5" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.5" fill="currentColor" stroke="none"/>',
    undo: '<path d="M9 7 4 12l5 5"/><path d="M5 12h8a6 6 0 0 1 6 6"/>',
    redo: '<path d="m15 7 5 5-5 5"/><path d="M19 12h-8a6 6 0 0 0-6 6"/>',
    fountain: '<path d="m12 3 6 6-6 12-6-12z"/><circle cx="12" cy="11" r="1.5"/><path d="M12 12.5V21"/>',
    ballpoint: '<path d="m7 18 2-6 7-7 3 3-7 7-5 3z"/><path d="m14 7 3 3M7 18l-2 2"/>',
    brush: '<path d="M14 4c2-2 5-1 6 1 1 2 0 4-2 6l-6 6-5-5z"/><path d="M7 12c-3 0-4 3-4 7 4 0 7-1 7-4"/>',
    pencil: '<path d="m4 18 1-5L15 3l6 6-10 10-5 1z"/><path d="m14 4 6 6M5 13l6 6M4 18l3-2 2 2-3 2"/>',
    highlighter: '<path d="m7 16 8-12 5 4-8 12H7z"/><path d="m7 16 5 4M4 21h16"/>',
    shape: '<rect x="4" y="5" width="12" height="12" rx="1"/><circle cx="16" cy="15" r="5"/>',
    line: '<path d="M4 20 20 4"/>',
    curve: '<path d="M4 18C9 4 15 20 20 6"/>',
    arrow: '<path d="M4 20 20 4M13 4h7v7"/>',
    rectangle: '<rect x="4" y="5" width="16" height="14" rx="1"/>',
    ellipse: '<ellipse cx="12" cy="12" rx="9" ry="7"/>',
    triangle: '<path d="m12 4 9 16H3z"/>',
    diamond: '<path d="m12 3 9 9-9 9-9-9z"/>',
    square: '<rect x="4" y="4" width="16" height="16" rx="1"/>',
    circle: '<circle cx="12" cy="12" r="8"/>',
    'rounded-rectangle': '<rect x="3" y="5" width="18" height="14" rx="4"/>',
    pentagon: '<path d="m12 3 9 6.5-3.4 10.5H6.4L3 9.5z"/>',
    hexagon: '<path d="m7 4 10 0 5 8-5 8H7l-5-8z"/>',
    starshape: '<path d="m12 3 2.7 5.5 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"/>',
    trapezoid: '<path d="M7 5h10l4 14H3z"/>',
    parallelogram: '<path d="M8 5h13l-5 14H3z"/>',
    heartshape: '<path d="M12 20S3 15 3 9a5 5 0 0 1 9-3 5 5 0 0 1 9 3c0 6-9 11-9 11z"/>',
    cloudshape: '<path d="M6 18h11a4 4 0 0 0 .6-8A6 6 0 0 0 6.3 8.2 5 5 0 0 0 6 18z"/>',
    speech: '<path d="M4 5h16v12H9l-5 4z"/>',
    arc: '<path d="M4 17a8 8 0 0 1 16 0"/>',
    'double-arrow': '<path d="M4 12h16M8 8l-4 4 4 4M16 8l4 4-4 4"/>',
    tape: '<rect x="3" y="8" width="18" height="8" rx="2"/><path d="m7 8-2 8M13 8l-2 8M19 8l-2 8"/>',
    laser: '<path d="m5 19 6-6M15 9l4-4M14 4l1 3M20 10l-3-1"/><circle cx="10" cy="14" r="3"/>',
    hand: '<path d="M7 11V7a2 2 0 0 1 4 0v4-6a2 2 0 0 1 4 0v6-4a2 2 0 0 1 4 0v7c0 5-3 8-8 8-3 0-5-2-7-5l-2-3a2 2 0 0 1 3-2z"/>',
    palette: '<path d="M12 3a9 9 0 1 0 0 18h2a2 2 0 0 0 0-4h-1a2 2 0 0 1 0-4h3a5 5 0 0 0 5-5c0-3-4-5-9-5z"/><circle cx="7" cy="10" r="1" fill="currentColor" stroke="none"/><circle cx="9" cy="6.5" r="1" fill="currentColor" stroke="none"/><circle cx="14" cy="6" r="1" fill="currentColor" stroke="none"/>',
    duplicate: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
    bookmark: '<path d="M6 3h12v18l-6-4-6 4z"/>',
    import: '<path d="M12 3v12M7 10l5 5 5-5"/><path d="M5 21h14"/>',
    export: '<path d="M12 16V4M7 9l5-5 5 5"/><path d="M5 20h14"/>',
    fit: '<path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"/>',
    read: '<path d="M4 5h6a3 3 0 0 1 3 3v12a3 3 0 0 0-3-3H4zM20 5h-6a3 3 0 0 0-3 3v12a3 3 0 0 1 3-3h6z"/>',
    audio: '<path d="M11 5 7 9H4v6h3l4 4z"/><path d="M15 9a5 5 0 0 1 0 6M18 6a9 9 0 0 1 0 12"/>',
    play: '<path d="m8 5 11 7-11 7z"/>',
    pause: '<path d="M8 5h3v14H8zM14 5h3v14h-3z"/>',
    equals: '<path d="M6 9h12M6 15h12"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
    rotate: '<path d="M20 11a8 8 0 1 0-2 5.5M20 4v7h-7"/>',
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M15 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h3"/>',
    cut: '<circle cx="6" cy="7" r="3"/><circle cx="6" cy="17" r="3"/><path d="m8.5 8.5 10 6M8.5 15.5l10-6"/>',
    folderPlus: '<path d="M3 7h7l2 2h9v10H3z"/><path d="M14 14h5M16.5 11.5v5"/>',
    calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M8 3v4M16 3v4M3 10h18"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>',
    gesture: '<path d="M8 13V8a2 2 0 0 1 4 0v3-6a2 2 0 0 1 4 0v7M8 13l-2-2a2 2 0 0 0-3 3l4 5a6 6 0 0 0 5 2h2a6 6 0 0 0 6-6V9a2 2 0 0 0-4 0"/>',
    eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    'eye-off': '<path d="m3 3 18 18M10.5 5.2A9.8 9.8 0 0 1 12 5c6 0 10 7 10 7a17.5 17.5 0 0 1-3 3.8M6.2 6.2C3.5 8.1 2 12 2 12s4 7 10 7a9.8 9.8 0 0 0 3.8-.8M9.8 9.8A3 3 0 0 0 14.2 14.2"/>',
    'arrow-up': '<path d="m12 19 0-14M6 11l6-6 6 6"/>',
    'arrow-down': '<path d="m12 5 0 14M6 13l6 6 6-6"/>'
  };

  function icon(name, className = '') {
    const body = ICONS[name] || ICONS.info;
    return `<svg class="${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
  }

  function injectIcons(root = document) {
    root.querySelectorAll('[data-icon]').forEach((el) => {
      if (!el.dataset.iconReady) {
        el.insertAdjacentHTML('afterbegin', icon(el.dataset.icon));
        el.dataset.iconReady = '1';
      }
    });
    root.querySelectorAll('[data-icon-host]').forEach((el) => {
      el.innerHTML = icon(el.dataset.iconHost);
    });
  }

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const uid = (prefix = 'id') => `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 9)}`;
  const now = () => new Date().toISOString();
  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
  const lerp = (a, b, t) => a + (b - a) * t;
  const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  const deepClone = (value) => typeof structuredClone === 'function' ? structuredClone(value) : JSON.parse(JSON.stringify(value));
  const formatDate = (iso) => {
    try { return new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'numeric', day: 'numeric' }).format(new Date(iso)); }
    catch { return ''; }
  };
  const formatTime = (seconds) => {
    const s = Math.max(0, Math.round(seconds || 0));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  };
  const escapeHtml = (text) => String(text ?? '').replace(/[&<>'"]/g, (ch) => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', "'":'&#39;', '"':'&quot;' }[ch]));
  const normalizeText = (value) => String(value || '').normalize('NFKC').toLocaleLowerCase('ko-KR');

  function recentNativeStylus(event, maxAge = 220) {
    const detail = window.__inkforgeNativeBridge?.lastStylus || window.__inkforgeLastNativeStylus;
    if (!detail || !Number.isFinite(detail.receivedAt)) return null;
    const nativeButtons = Number(detail.buttonState || 0);
    const barrelActive = !!(
      detail.primaryButton ||
      detail.secondaryButton ||
      detail.barrelButton ||
      detail.latchedBarrelButton ||
      window.__inkforgeNativeBridge?.barrelButtonActive ||
      (nativeButtons & 96) !== 0
    );
    const effectiveMaxAge = barrelActive ? Math.max(maxAge, 3500) : maxAge;
    if (performance.now() - detail.receivedAt > effectiveMaxAge) return null;
    if (event && Number.isFinite(detail.x) && Number.isFinite(detail.y)) {
      const dx = Math.abs(Number(event.clientX || 0) - detail.x);
      const dy = Math.abs(Number(event.clientY || 0) - detail.y);
      if (Math.hypot(dx, dy) > 96 && !detail.hover && !barrelActive) return null;
    }
    return detail;
  }

  function effectivePointerType(event) {
    if (event.pointerType === 'pen') return 'pen';
    return recentNativeStylus(event) ? 'pen' : (event.pointerType || 'mouse');
  }

  function hexToRgba(hex, alpha = 1) {
    const normalized = String(hex || '#111111').replace('#', '');
    const full = normalized.length === 3 ? normalized.split('').map((c) => c + c).join('') : normalized.padEnd(6, '0').slice(0, 6);
    const int = Number.parseInt(full, 16);
    return `rgba(${(int >> 16) & 255}, ${(int >> 8) & 255}, ${int & 255}, ${alpha})`;
  }

  function randomColor() {
    const colors = ['#2f7fb7', '#6f64c8', '#c95b76', '#2e9b79', '#d2883c', '#677381', '#8a6b4d'];
    return colors[Math.floor(Math.random() * colors.length)];
  }

  function normalizeFolders(folders) {
    const source = Array.isArray(folders) ? folders : DEFAULT_FOLDERS;
    const normalized = [deepClone(DEFAULT_FOLDERS[0])];
    const seen = new Set(['root']);
    for (const folder of source) {
      if (!folder || folder.id === 'root' || seen.has(folder.id)) continue;
      const title = String(folder.title || '').trim().slice(0, 40);
      if (!title) continue;
      normalized.push({
        id: String(folder.id),
        title,
        color: /^#[0-9a-f]{6}$/i.test(folder.color || '') ? folder.color : FOLDER_COLORS[normalized.length % FOLDER_COLORS.length]
      });
      seen.add(String(folder.id));
    }
    return normalized;
  }

  async function persistFolders() {
    await storage.setSetting('folders', normalizeFolders(state.folders));
  }

  class AppStorage {
    constructor() { this.db = null; this.fallback = false; }
    async init() {
      if (!('indexedDB' in window)) { this.fallback = true; return; }
      this.db = await new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (!db.objectStoreNames.contains('documents')) db.createObjectStore('documents', { keyPath: 'id' });
          if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings', { keyPath: 'key' });
          if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' });
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      }).catch(() => null);
      if (!this.db) this.fallback = true;
    }
    async allDocuments() {
      if (this.fallback) return JSON.parse(localStorage.getItem('inkforge_documents') || '[]');
      return new Promise((resolve, reject) => {
        const req = this.db.transaction('documents', 'readonly').objectStore('documents').getAll();
        req.onsuccess = () => resolve(req.result || []);
        req.onerror = () => reject(req.error);
      });
    }
    async putDocument(document) {
      if (this.fallback) {
        const docs = await this.allDocuments();
        const index = docs.findIndex((item) => item.id === document.id);
        if (index >= 0) docs[index] = document; else docs.push(document);
        localStorage.setItem('inkforge_documents', JSON.stringify(docs));
        return document;
      }
      return new Promise((resolve, reject) => {
        const req = this.db.transaction('documents', 'readwrite').objectStore('documents').put(document);
        req.onsuccess = () => resolve(document);
        req.onerror = () => reject(req.error);
      });
    }
    async deleteDocument(id) {
      if (this.fallback) {
        const docs = (await this.allDocuments()).filter((item) => item.id !== id);
        localStorage.setItem('inkforge_documents', JSON.stringify(docs));
        return;
      }
      return new Promise((resolve, reject) => {
        const req = this.db.transaction('documents', 'readwrite').objectStore('documents').delete(id);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
    }
    async putAsset(asset) {
      if (!asset?.id) throw new Error('asset id is required');
      if (this.fallback) {
        const stored = { ...asset };
        if (stored.blob instanceof Blob) stored.dataUrl = await new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result); reader.onerror = () => reject(reader.error); reader.readAsDataURL(stored.blob); });
        delete stored.blob;
        localStorage.setItem(`inkforge_asset_${asset.id}`, JSON.stringify(stored));
        return asset.id;
      }
      return new Promise((resolve, reject) => {
        const req = this.db.transaction('assets', 'readwrite').objectStore('assets').put(asset);
        req.onsuccess = () => resolve(asset.id);
        req.onerror = () => reject(req.error);
      });
    }
    async getAsset(id) {
      if (!id) return null;
      if (this.fallback) {
        const raw = localStorage.getItem(`inkforge_asset_${id}`);
        if (!raw) return null;
        const asset = JSON.parse(raw);
        if (asset.dataUrl) asset.blob = await (await fetch(asset.dataUrl)).blob();
        return asset;
      }
      return new Promise((resolve) => {
        const req = this.db.transaction('assets', 'readonly').objectStore('assets').get(id);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror = () => resolve(null);
      });
    }
    async deleteAsset(id) {
      if (!id) return;
      if (this.fallback) { localStorage.removeItem(`inkforge_asset_${id}`); return; }
      return new Promise((resolve, reject) => {
        const req = this.db.transaction('assets', 'readwrite').objectStore('assets').delete(id);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
    }
    async getSetting(key, fallbackValue) {
      if (this.fallback) {
        const raw = localStorage.getItem(`inkforge_setting_${key}`);
        return raw == null ? fallbackValue : JSON.parse(raw);
      }
      return new Promise((resolve) => {
        const req = this.db.transaction('settings', 'readonly').objectStore('settings').get(key);
        req.onsuccess = () => resolve(req.result ? req.result.value : fallbackValue);
        req.onerror = () => resolve(fallbackValue);
      });
    }
    async setSetting(key, value) {
      if (this.fallback) { localStorage.setItem(`inkforge_setting_${key}`, JSON.stringify(value)); return; }
      return new Promise((resolve, reject) => {
        const req = this.db.transaction('settings', 'readwrite').objectStore('settings').put({ key, value });
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
    }
  }

  const storage = new AppStorage();

  const TEMPLATE_META = {
    blank: { title: '무지', icon: 'notebook' },
    lined: { title: '줄 노트', icon: 'list' },
    grid: { title: '격자', icon: 'grid' },
    dotted: { title: '도트', icon: 'more' },
    cornell: { title: '코넬', icon: 'sidebar' },
    planner: { title: '플래너', icon: 'calendar' }
  };

  const BRUSH_META = {
    fountain: { title: '만년필', icon: 'fountain', pressure: .76, smoothing: .35, sharpness: .58, taper: .42, opacity: 1 },
    ballpoint: { title: '볼펜', icon: 'ballpoint', pressure: .08, smoothing: .48, sharpness: .1, taper: .12, opacity: 1 },
    gel: { title: '젤펜', icon: 'pen', pressure: .16, smoothing: .45, sharpness: .2, taper: .2, opacity: 1 },
    brush: { title: '브러시', icon: 'brush', pressure: .95, smoothing: .28, sharpness: .35, taper: .72, opacity: .96 },
    pencil: { title: '연필', icon: 'pencil', pressure: .7, smoothing: .26, hardness: .58, grain: .66, tiltShade: .82, opacity: .78 },
    fineliner: { title: '파인라이너', icon: 'pen', pressure: .02, smoothing: .6, sharpness: .85, taper: .06, opacity: .98 }
  };

  const DEFAULT_FOLDERS = [
    { id: 'root', title: '문서', color: '#1b68a6' },
    { id: 'study', title: '학교·공부', color: '#2b8bc5' },
    { id: 'work', title: '프로젝트', color: '#6d63c7' }
  ];
  const FOLDER_COLORS = ['#2b8bc5', '#6d63c7', '#2e9b79', '#d2883c', '#c95b76', '#677381', '#8a6b4d'];

  function blankPage(template = 'grid') {
    return {
      id: uid('page'),
      template,
      title: '',
      bookmarked: false,
      createdAt: now(),
      objects: []
    };
  }

  function createDocument(title = '새 노트', template = 'grid') {
    return {
      schema: 'com.inkforge.ifnote',
      version: 4,
      appVersion: VERSION,
      id: uid('doc'),
      title,
      folderId: 'root',
      favorite: false,
      shared: false,
      trashed: false,
      coverColor: randomColor(),
      createdAt: now(),
      updatedAt: now(),
      pages: [blankPage(template)],
      tags: [],
      outlines: [],
      audio: [],
      variables: {},
      settings: { pageMode: 'continuous' }
    };
  }

  const state = {
    documents: [],
    folders: deepClone(DEFAULT_FOLDERS),
    view: 'library',
    libraryFilter: 'all',
    folderId: 'root',
    sort: 'updated-desc',
    listMode: false,
    globalQuery: '',
    currentDocumentId: null,
    currentPageIndex: 0,
    openTabs: [],
    tool: 'lasso',
    lastWritingTool: 'pen',
    brush: 'fountain',
    color: '#111827',
    width: 4.2,
    highlighterColor: '#f5df39',
    highlighterWidth: 22,
    eraserMode: 'stroke',
    eraserRadius: 26,
    shape: 'line',
    stickyColor: '#ffe58d',
    tapeColor: '#4c91dd',
    sticker: '★',
    zoom: 1,
    pageMode: 'continuous',
    dock: 'top',
    sidebarOpen: false,
    sidebarTab: 'pages',
    searchOpen: false,
    readOnly: false,
    ruler: { visible: false, x: PAGE_WIDTH / 2, angle: 0, y: 500 },
    selection: null,
    clipboard: [],
    pendingInsert: null,
    history: { undo: [], redo: [] },
    settings: {
      stylusOnly: true,
      scribbleErase: true,
      drawHold: true,
      telemetry: false,
      continuous: true,
      autoMath: false,
      sPenGestures: true,
      autoOcr: true,
      nativeRecognition: true
    },
    penSettings: deepClone(BRUSH_META),
    math: { expression: '', result: null, error: null, degree: false },
    recording: null,
    audioStream: null,
    activePointers: new Map(),
    touchGesture: null,
    drawSession: null,
    saveTimer: null,
    imageCache: new Map(),
    assetUrlCache: new Map(),
    assetLoadQueue: new Map(),
    renderFrames: new Map(),
    renderQueue: new Set(),
    renderQueueFrame: 0,
    zoomRenderFrame: 0,
    zoomRenderTimer: 0,
    suppressPageUpdateUntil: 0,
    zoomDragLockUntil: 0,
    allowLargePageJumpUntil: 0,
    activePageWrapIndex: -1,
    virtualPageWindow: { start: -1, end: -1 },
    positionSaveTimer: 0,
    testReady: false
  };

  function currentDocument() {
    return state.documents.find((doc) => doc.id === state.currentDocumentId) || null;
  }
  function currentPage() {
    const doc = currentDocument();
    return doc?.pages?.[state.currentPageIndex] || null;
  }

  function notifyPageChanged(previousPageIndex, reason = 'page') {
    const doc = currentDocument();
    if (!doc || previousPageIndex === state.currentPageIndex) return;
    window.dispatchEvent(new CustomEvent('inkforge:page-changed', {
      detail: {
        documentId: doc.id,
        pageId: doc.pages[state.currentPageIndex]?.id,
        pageIndex: state.currentPageIndex,
        previousPageIndex,
        reason
      }
    }));
  }

  function rememberedPageIndex(doc) {
    if (!doc?.pages?.length) return 0;
    const byId = doc.lastPageId ? doc.pages.findIndex((page) => page.id === doc.lastPageId) : -1;
    if (byId >= 0) return byId;
    return clamp(Math.round(Number(doc.lastPageIndex) || 0), 0, doc.pages.length - 1);
  }

  async function rememberCurrentPage({ immediate = false } = {}) {
    const doc = currentDocument();
    if (!doc?.pages?.length) return;
    const index = clamp(state.currentPageIndex, 0, doc.pages.length - 1);
    doc.lastPageIndex = index;
    doc.lastPageId = doc.pages[index]?.id || null;
    clearTimeout(state.positionSaveTimer);
    const perform = async () => {
      try { await storage.putDocument(doc); }
      catch {}
    };
    if (immediate) await perform();
    else state.positionSaveTimer = setTimeout(perform, 240);
  }

  async function persistCurrent({ immediate = false } = {}) {
    const doc = currentDocument();
    if (!doc) return;
    doc.updatedAt = now();
    doc.appVersion = VERSION;
    if (doc.pages?.length) {
      const index = clamp(state.currentPageIndex, 0, doc.pages.length - 1);
      doc.lastPageIndex = index;
      doc.lastPageId = doc.pages[index]?.id || null;
    }
    clearTimeout(state.saveTimer);
    const perform = async () => {
      try { await storage.putDocument(doc); }
      catch (error) { toast(`저장 오류: ${error.message || error}`); }
    };
    if (immediate) await perform(); else state.saveTimer = setTimeout(perform, doc.pages.length > 40 ? 1000 : 520);
  }

  function isDocumentHistoryLabel(label) {
    return /^(page-|pdf-|document-|import|restore|clear-page|delete-document|create-document)/.test(label || '');
  }

  function historySnapshot(label = 'edit') {
    const doc = currentDocument();
    if (!doc) return null;
    if (isDocumentHistoryLabel(label)) return { scope: 'document', label, document: deepClone(doc), pageIndex: state.currentPageIndex };
    const page = currentPage();
    return page ? { scope: 'page', label, documentId: doc.id, pageId: page.id, pageIndex: state.currentPageIndex, page: deepClone(page) } : null;
  }

  function checkpoint(label = 'edit') {
    const snapshot = historySnapshot(label);
    if (!snapshot) return;
    state.history.undo.push(snapshot);
    const dynamicMax = currentDocument()?.pages?.length > 80 ? 7 : MAX_HISTORY;
    if (state.history.undo.length > dynamicMax) state.history.undo.shift();
    state.history.redo.length = 0;
    updateUndoButtons();
  }

  function applyHistorySnapshot(snapshot) {
    const doc = currentDocument();
    if (!doc || !snapshot) return;
    if (snapshot.scope === 'page') {
      let index = doc.pages.findIndex((page) => page.id === snapshot.pageId);
      if (index < 0) index = clamp(snapshot.pageIndex, 0, doc.pages.length - 1);
      doc.pages[index] = deepClone(snapshot.page);
      state.currentPageIndex = index;
    } else {
      const index = state.documents.findIndex((item) => item.id === doc.id);
      state.documents[index] = deepClone(snapshot.document);
      state.currentPageIndex = clamp(snapshot.pageIndex, 0, state.documents[index].pages.length - 1);
    }
  }

  function inverseSnapshot(scope, label) {
    const doc = currentDocument();
    if (!doc) return null;
    if (scope === 'document') return { scope: 'document', label, document: deepClone(doc), pageIndex: state.currentPageIndex };
    const page = currentPage();
    return page ? { scope: 'page', label, documentId: doc.id, pageId: page.id, pageIndex: state.currentPageIndex, page: deepClone(page) } : null;
  }

  function undo() {
    if (!state.history.undo.length) return;
    const snapshot = state.history.undo.pop();
    const inverse = inverseSnapshot(snapshot.scope, 'undo');
    if (inverse) state.history.redo.push(inverse);
    applyHistorySnapshot(snapshot);
    state.selection = null;
    persistCurrent(); renderEditorPages(); renderActiveToolMenu(); renderSidebar(); updateUndoButtons();
    toast('실행 취소');
  }

  function redo() {
    if (!state.history.redo.length) return;
    const snapshot = state.history.redo.pop();
    const inverse = inverseSnapshot(snapshot.scope, 'redo');
    if (inverse) state.history.undo.push(inverse);
    applyHistorySnapshot(snapshot);
    state.selection = null;
    persistCurrent(); renderEditorPages(); renderActiveToolMenu(); renderSidebar(); updateUndoButtons();
    toast('다시 실행');
  }

  function updateUndoButtons() {
    const undoButton = $('[data-action="undo"]');
    const redoButton = $('[data-action="redo"]');
    if (undoButton) undoButton.disabled = state.history.undo.length === 0;
    if (redoButton) redoButton.disabled = state.history.redo.length === 0;
  }

  class MathParser {
    constructor(input, variables = {}, degree = false) {
      this.input = String(input || '').replace(/[×·]/g, '*').replace(/÷/g, '/').replace(/−/g, '-').trim();
      this.variables = { pi: Math.PI, e: Math.E, ...variables };
      this.degree = degree;
      this.tokens = this.tokenize(this.input);
      this.index = 0;
    }
    tokenize(text) {
      const tokens = [];
      let i = 0;
      while (i < text.length) {
        const ch = text[i];
        if (/\s/.test(ch)) { i++; continue; }
        if (/[0-9.]/.test(ch)) {
          let value = '';
          let dots = 0;
          while (i < text.length && /[0-9.eE+-]/.test(text[i])) {
            const next = text[i];
            if (next === '.') dots++;
            if ((next === '+' || next === '-') && !/[eE]$/.test(value)) break;
            value += next; i++;
            if (dots > 1) break;
          }
          const number = Number(value);
          if (!Number.isFinite(number)) throw new Error(`숫자 형식이 올바르지 않습니다: ${value}`);
          tokens.push({ type: 'number', value: number });
          continue;
        }
        if (/[A-Za-z_가-힣]/.test(ch)) {
          let value = '';
          while (i < text.length && /[A-Za-z0-9_가-힣]/.test(text[i])) { value += text[i++]; }
          tokens.push({ type: 'identifier', value: value.toLowerCase() });
          continue;
        }
        if ('+-*/^%(),!'.includes(ch)) { tokens.push({ type: ch, value: ch }); i++; continue; }
        throw new Error(`지원하지 않는 문자입니다: ${ch}`);
      }
      return tokens;
    }
    peek(type) { return this.tokens[this.index]?.type === type; }
    consume(type) {
      const token = this.tokens[this.index];
      if (!token || (type && token.type !== type)) throw new Error(type === ')' ? '닫는 괄호가 필요합니다.' : '수식 형식을 확인하세요.');
      this.index++;
      return token;
    }
    parse() {
      if (!this.tokens.length) throw new Error('수식을 입력하세요.');
      const result = this.expression();
      if (this.index !== this.tokens.length) throw new Error('수식 뒤에 해석할 수 없는 내용이 있습니다.');
      if (!Number.isFinite(result)) throw new Error('계산 결과가 유한한 숫자가 아닙니다.');
      return result;
    }
    expression() {
      let value = this.term();
      while (this.peek('+') || this.peek('-')) {
        const op = this.consume().type;
        const right = this.term();
        value = op === '+' ? value + right : value - right;
      }
      return value;
    }
    term() {
      let value = this.power();
      while (this.peek('*') || this.peek('/') || this.peek('%')) {
        const op = this.consume().type;
        const right = this.power();
        if (op === '*') value *= right;
        else if (op === '/') {
          if (Math.abs(right) < Number.EPSILON) throw new Error('0으로 나눌 수 없습니다.');
          value /= right;
        } else value %= right;
      }
      return value;
    }
    power() {
      let value = this.unary();
      if (this.peek('^')) { this.consume('^'); value = Math.pow(value, this.power()); }
      return value;
    }
    unary() {
      if (this.peek('+')) { this.consume('+'); return this.unary(); }
      if (this.peek('-')) { this.consume('-'); return -this.unary(); }
      let value = this.primary();
      while (this.peek('!')) { this.consume('!'); value = this.factorial(value); }
      return value;
    }
    primary() {
      if (this.peek('number')) return this.consume('number').value;
      if (this.peek('(')) {
        this.consume('(');
        const value = this.expression();
        this.consume(')');
        return value;
      }
      if (this.peek('identifier')) {
        const name = this.consume('identifier').value;
        if (this.peek('(')) {
          this.consume('(');
          const args = [];
          if (!this.peek(')')) {
            args.push(this.expression());
            while (this.peek(',')) { this.consume(','); args.push(this.expression()); }
          }
          this.consume(')');
          return this.call(name, args);
        }
        if (Object.prototype.hasOwnProperty.call(this.variables, name)) return Number(this.variables[name]);
        throw new Error(`정의되지 않은 변수입니다: ${name}`);
      }
      throw new Error('숫자, 변수 또는 괄호가 필요합니다.');
    }
    factorial(value) {
      if (!Number.isInteger(value) || value < 0 || value > 170) throw new Error('팩토리얼은 0~170의 정수만 지원합니다.');
      let result = 1;
      for (let i = 2; i <= value; i++) result *= i;
      return result;
    }
    call(name, args) {
      const angle = (value) => this.degree ? value * Math.PI / 180 : value;
      const functions = {
        sqrt: (x) => Math.sqrt(x), abs: (x) => Math.abs(x),
        sin: (x) => Math.sin(angle(x)), cos: (x) => Math.cos(angle(x)), tan: (x) => Math.tan(angle(x)),
        asin: (x) => this.degree ? Math.asin(x) * 180 / Math.PI : Math.asin(x),
        acos: (x) => this.degree ? Math.acos(x) * 180 / Math.PI : Math.acos(x),
        atan: (x) => this.degree ? Math.atan(x) * 180 / Math.PI : Math.atan(x),
        log: (x) => Math.log10(x), ln: (x) => Math.log(x), exp: (x) => Math.exp(x),
        floor: (x) => Math.floor(x), ceil: (x) => Math.ceil(x), round: (x) => Math.round(x),
        min: (...values) => Math.min(...values), max: (...values) => Math.max(...values),
        pow: (x, y) => Math.pow(x, y)
      };
      if (!functions[name]) throw new Error(`지원하지 않는 함수입니다: ${name}`);
      const result = functions[name](...args);
      if (!Number.isFinite(result)) throw new Error(`${name} 함수의 입력 범위를 확인하세요.`);
      return result;
    }
  }

  function formatMathResult(value) {
    if (!Number.isFinite(value)) return String(value);
    if (Math.abs(value) >= 1e12 || (Math.abs(value) > 0 && Math.abs(value) < 1e-7)) return value.toExponential(8).replace(/\.0+e/, 'e');
    return Number(value.toFixed(10)).toLocaleString('en-US', { maximumFractionDigits: 10, useGrouping: false });
  }

  function evaluateMath(rawExpression, options = {}) {
    const doc = currentDocument();
    let expression = String(rawExpression || '').trim();
    if (!expression) throw new Error('수식을 입력하세요.');
    if (expression.endsWith('=')) expression = expression.slice(0, -1).trim();
    const assignment = expression.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$/);
    if (assignment) {
      const name = assignment[1].toLowerCase();
      const parser = new MathParser(assignment[2], doc?.variables || {}, options.degree);
      const value = parser.parse();
      if (doc) doc.variables[name] = value;
      return { expression, result: value, assignment: name };
    }
    const parser = new MathParser(expression, doc?.variables || {}, options.degree);
    return { expression, result: parser.parse(), assignment: null };
  }

  function documentSearchText(doc) {
    const parts = [doc.title, ...(doc.tags || [])];
    (doc.pages || []).forEach((page) => {
      if (page.title) parts.push(page.title);
      if (page.pdfText) parts.push(page.pdfText);
      (page.objects || []).forEach((object) => {
        if (object.text) parts.push(object.text);
        if (object.expression) parts.push(object.expression);
        if (object.result != null) parts.push(String(object.result));
      });
    });
    return normalizeText(parts.join(' '));
  }

  function visibleDocuments() {
    let docs = state.documents.filter((doc) => {
      if (state.libraryFilter === 'trash') return doc.trashed;
      if (doc.trashed) return false;
      if (state.libraryFilter === 'favorite' && !doc.favorite) return false;
      if (state.libraryFilter === 'shared' && !doc.shared) return false;
      if (state.libraryFilter === 'templates') return false;
      if (state.folderId !== 'root' && doc.folderId !== state.folderId) return false;
      if (state.globalQuery && !documentSearchText(doc).includes(normalizeText(state.globalQuery))) return false;
      return true;
    });
    const [field, direction] = state.sort.split('-');
    docs.sort((a, b) => {
      let av = field === 'title' ? normalizeText(a.title) : new Date(a.updatedAt).getTime();
      let bv = field === 'title' ? normalizeText(b.title) : new Date(b.updatedAt).getTime();
      if (av < bv) return direction === 'asc' ? -1 : 1;
      if (av > bv) return direction === 'asc' ? 1 : -1;
      return 0;
    });
    return docs;
  }

  function drawTemplate(ctx, template, width = PAGE_WIDTH, height = PAGE_HEIGHT) {
    ctx.save();
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, width, height);
    ctx.strokeStyle = '#dce2e9';
    ctx.fillStyle = '#cbd4de';
    ctx.lineWidth = Math.max(1, width / 1000);
    const sx = width / PAGE_WIDTH;
    const sy = height / PAGE_HEIGHT;
    if (template === 'lined' || template === 'cornell') {
      const spacing = 52 * sy;
      ctx.beginPath();
      for (let y = 112 * sy; y < height - 50 * sy; y += spacing) { ctx.moveTo(62 * sx, y); ctx.lineTo(width - 54 * sx, y); }
      ctx.stroke();
      if (template === 'cornell') {
        ctx.strokeStyle = '#bdc8d4';
        ctx.beginPath();
        ctx.moveTo(255 * sx, 55 * sy); ctx.lineTo(255 * sx, height - 55 * sy);
        ctx.moveTo(55 * sx, height - 265 * sy); ctx.lineTo(width - 55 * sx, height - 265 * sy);
        ctx.stroke();
      }
    } else if (template === 'grid') {
      const spacing = 42 * sx;
      ctx.beginPath();
      for (let x = spacing; x < width; x += spacing) { ctx.moveTo(x, 0); ctx.lineTo(x, height); }
      for (let y = spacing; y < height; y += spacing) { ctx.moveTo(0, y); ctx.lineTo(width, y); }
      ctx.stroke();
    } else if (template === 'dotted') {
      const spacing = 44 * sx;
      for (let x = spacing; x < width; x += spacing) {
        for (let y = spacing; y < height; y += spacing) {
          ctx.beginPath(); ctx.arc(x, y, Math.max(.8, width / 1000 * 1.5), 0, Math.PI * 2); ctx.fill();
        }
      }
    } else if (template === 'planner') {
      ctx.strokeStyle = '#cbd4de';
      ctx.lineWidth = 2 * sx;
      ctx.font = `700 ${28 * sx}px sans-serif`;
      ctx.fillStyle = '#536273';
      ctx.fillText('WEEKLY PLAN', 60 * sx, 80 * sy);
      ctx.font = `500 ${15 * sx}px sans-serif`;
      const days = ['MON','TUE','WED','THU','FRI','SAT','SUN'];
      const top = 125 * sy, colW = (width - 120 * sx) / 2, rowH = 155 * sy;
      days.forEach((day, index) => {
        const col = index % 2, row = Math.floor(index / 2);
        const x = 60 * sx + col * colW, y = top + row * rowH;
        ctx.strokeRect(x, y, colW - 18 * sx, rowH - 20 * sy);
        ctx.fillText(day, x + 14 * sx, y + 26 * sy);
      });
    }
    ctx.restore();
  }

  function computeBounds(object) {
    if (!object) return { x: 0, y: 0, w: 0, h: 0 };
    if (object.type === 'stroke') {
      const xs = object.points.map((p) => p.x), ys = object.points.map((p) => p.y);
      const margin = (object.width || 4) * 2;
      return { x: Math.min(...xs) - margin, y: Math.min(...ys) - margin, w: Math.max(...xs) - Math.min(...xs) + margin * 2, h: Math.max(...ys) - Math.min(...ys) + margin * 2 };
    }
    if (object.type === 'shape' || object.type === 'tape') {
      return { x: Math.min(object.x1, object.x2), y: Math.min(object.y1, object.y2), w: Math.abs(object.x2 - object.x1), h: Math.abs(object.y2 - object.y1) };
    }
    return { x: object.x || 0, y: object.y || 0, w: object.w || 240, h: object.h || 80 };
  }

  function selectionBounds(objects) {
    if (!objects.length) return null;
    const bounds = objects.map(computeBounds);
    const left = Math.min(...bounds.map((b) => b.x));
    const top = Math.min(...bounds.map((b) => b.y));
    const right = Math.max(...bounds.map((b) => b.x + b.w));
    const bottom = Math.max(...bounds.map((b) => b.y + b.h));
    return { x: left, y: top, w: right - left, h: bottom - top };
  }

  function pointInPolygon(point, polygon) {
    let inside = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const xi = polygon[i].x, yi = polygon[i].y, xj = polygon[j].x, yj = polygon[j].y;
      const intersect = ((yi > point.y) !== (yj > point.y)) && (point.x < (xj - xi) * (point.y - yi) / ((yj - yi) || .00001) + xi);
      if (intersect) inside = !inside;
    }
    return inside;
  }

  function seededRandom(seedText) {
    let seed = 2166136261;
    const text = String(seedText || 'ink');
    for (let i = 0; i < text.length; i++) { seed ^= text.charCodeAt(i); seed = Math.imul(seed, 16777619); }
    return () => {
      seed += 0x6D2B79F5;
      let t = seed;
      t = Math.imul(t ^ t >>> 15, t | 1);
      t ^= t + Math.imul(t ^ t >>> 7, t | 61);
      return ((t ^ t >>> 14) >>> 0) / 4294967296;
    };
  }

  function smoothPoints(points, amount = 0.35) {
    if (!Array.isArray(points) || points.length < 3 || amount <= 0) return points || [];
    const output = [points[0]];
    const factor = clamp(amount, 0, .92);
    for (let i = 1; i < points.length; i++) {
      const prev = output[output.length - 1];
      const current = points[i];
      output.push({
        ...current,
        x: lerp(current.x, (prev.x + current.x) * .5, factor),
        y: lerp(current.y, (prev.y + current.y) * .5, factor),
        p: lerp(current.p ?? .5, prev.p ?? .5, factor * .28)
      });
    }
    return output;
  }

  function segmentDirection(a, b) {
    return Math.atan2(b.y - a.y, b.x - a.x);
  }

  function pointPressure(point, fallback = .5) {
    const pressure = Number(point?.p);
    if (!Number.isFinite(pressure) || pressure <= 0) return fallback;
    return clamp(pressure, .02, 1);
  }

  function brushSegmentWidth(stroke, point, nextPoint, index, total) {
    const base = Math.max(.35, Number(stroke.width) || 4);
    const brush = stroke.brush || 'fountain';
    const settings = state.penSettings[brush] || BRUSH_META[brush] || BRUSH_META.fountain;
    const pressure = pointPressure(point, brush === 'ballpoint' || brush === 'fineliner' ? .54 : .42);
    const progress = total > 1 ? index / (total - 1) : 0;
    const startTaper = clamp(progress / .045, 0, 1);
    const endTaper = clamp((1 - progress) / .07, 0, 1);
    const taper = lerp(1, Math.min(startTaper, endTaper), settings.taper || 0);
    const direction = nextPoint ? segmentDirection(point, nextPoint) : 0;
    const stylusAngle = Number.isFinite(point.azimuth) ? point.azimuth : direction;
    const nib = .72 + .28 * Math.abs(Math.sin(direction - stylusAngle));
    if (brush === 'ballpoint') return base * lerp(.94, 1.08, pressure * (settings.pressure || .08)) * taper;
    if (brush === 'fineliner') return base * (.98 + pressure * .025) * taper;
    if (brush === 'gel') return base * (.88 + pressure * .24) * taper;
    if (brush === 'brush') return base * (.18 + pressure * 1.32 * (settings.pressure || .95)) * taper;
    if (brush === 'pencil') {
      const tilt = Math.hypot(point.tx || 0, point.ty || 0) / 90;
      return base * (.45 + pressure * .65 + tilt * (settings.tiltShade || .82) * 1.35);
    }
    if (brush === 'highlighter') return base;
    return base * (.28 + pressure * (1.04 + (settings.pressure || .76) * .48)) * nib * taper;
  }

  function strokePathSegments(ctx, points, stroke, options = {}) {
    if (!points.length) return;
    const opacity = options.opacity ?? stroke.opacity ?? 1;
    ctx.save();
    ctx.globalAlpha = opacity;
    ctx.strokeStyle = options.color || stroke.color || '#111827';
    ctx.fillStyle = options.color || stroke.color || '#111827';
    ctx.lineCap = options.lineCap || 'round';
    ctx.lineJoin = 'round';
    if (points.length === 1) {
      ctx.beginPath();
      ctx.arc(points[0].x, points[0].y, brushSegmentWidth(stroke, points[0], null, 0, 1) / 2, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      return;
    }
    for (let i = 1; i < points.length; i++) {
      const a = points[i - 1], b = points[i];
      ctx.lineWidth = Math.max(.25, (brushSegmentWidth(stroke, a, b, i - 1, points.length) + brushSegmentWidth(stroke, b, a, i, points.length)) / 2 * (options.widthScale || 1));
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      const midX = (a.x + b.x) / 2, midY = (a.y + b.y) / 2;
      ctx.quadraticCurveTo(a.x, a.y, midX, midY);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
    }
    ctx.restore();
  }

  function renderPencil(ctx, stroke, points) {
    const settings = state.penSettings.pencil || BRUSH_META.pencil;
    const random = seededRandom(stroke.id);
    const baseColor = stroke.color || '#30343a';
    ctx.save();
    ctx.globalCompositeOperation = 'multiply';
    strokePathSegments(ctx, points, stroke, { opacity: (stroke.opacity ?? settings.opacity ?? .78) * .42, color: baseColor, widthScale: .72 });
    const grain = clamp(settings.grain ?? .66, 0, 1);
    const layers = 4 + Math.round(grain * 7);
    for (let layer = 0; layer < layers; layer++) {
      const offsetPoints = points.map((point) => {
        const tiltX = (point.tx || 0) / 90;
        const tiltY = (point.ty || 0) / 90;
        const scatter = (.28 + grain * 1.8) * (stroke.width || 4) / 4;
        return {
          ...point,
          x: point.x + (random() - .5) * scatter + tiltX * (layer - layers / 2) * .25,
          y: point.y + (random() - .5) * scatter + tiltY * (layer - layers / 2) * .25
        };
      });
      strokePathSegments(ctx, offsetPoints, stroke, {
        opacity: (.035 + random() * .055) * (stroke.opacity ?? .8),
        color: baseColor,
        widthScale: .3 + random() * .38
      });
    }
    for (let i = 0; i < points.length; i += Math.max(1, Math.floor(points.length / 90))) {
      const point = points[i];
      const width = brushSegmentWidth(stroke, point, points[i + 1], i, points.length);
      const particles = 1 + Math.round(grain * 3);
      for (let p = 0; p < particles; p++) {
        ctx.globalAlpha = .04 + random() * .11;
        ctx.fillStyle = baseColor;
        ctx.beginPath();
        ctx.arc(point.x + (random() - .5) * width, point.y + (random() - .5) * width, .28 + random() * .55, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    ctx.restore();
  }

  function renderStroke(ctx, stroke) {
    if (!stroke?.points?.length) return;
    const settings = state.penSettings[stroke.brush] || BRUSH_META[stroke.brush] || BRUSH_META.fountain;
    const points = smoothPoints(stroke.points, settings.smoothing ?? .3);
    if (stroke.brush === 'pencil') {
      renderPencil(ctx, stroke, points);
      return;
    }
    if (stroke.brush === 'highlighter') {
      ctx.save();
      ctx.globalCompositeOperation = 'multiply';
      strokePathSegments(ctx, points, stroke, { opacity: stroke.opacity ?? .28, lineCap: 'butt' });
      ctx.restore();
      return;
    }
    if (stroke.brush === 'gel') {
      strokePathSegments(ctx, points, stroke, { opacity: stroke.opacity ?? .98, widthScale: 1.12 });
      strokePathSegments(ctx, points, stroke, { opacity: .38, color: '#ffffff', widthScale: .28 });
      return;
    }
    if (stroke.brush === 'fineliner') {
      strokePathSegments(ctx, points, stroke, { opacity: stroke.opacity ?? .98, lineCap: 'round' });
      return;
    }
    strokePathSegments(ctx, points, stroke, { opacity: stroke.opacity ?? settings.opacity ?? 1 });
  }

  function drawArrowHead(ctx, x1, y1, x2, y2, size) {
    const angle = Math.atan2(y2 - y1, x2 - x1);
    ctx.beginPath();
    ctx.moveTo(x2, y2);
    ctx.lineTo(x2 - Math.cos(angle - Math.PI / 6) * size, y2 - Math.sin(angle - Math.PI / 6) * size);
    ctx.moveTo(x2, y2);
    ctx.lineTo(x2 - Math.cos(angle + Math.PI / 6) * size, y2 - Math.sin(angle + Math.PI / 6) * size);
    ctx.stroke();
  }

  function regularPolygonPath(ctx, cx, cy, rx, ry, sides, rotation = -Math.PI / 2) {
    for (let index = 0; index < sides; index++) {
      const angle = rotation + index * Math.PI * 2 / sides;
      const px = cx + Math.cos(angle) * rx;
      const py = cy + Math.sin(angle) * ry;
      index ? ctx.lineTo(px, py) : ctx.moveTo(px, py);
    }
    ctx.closePath();
  }

  function starPath(ctx, cx, cy, rx, ry, points = 5, rotation = -Math.PI / 2) {
    for (let index = 0; index < points * 2; index++) {
      const radiusX = index % 2 ? rx * .43 : rx;
      const radiusY = index % 2 ? ry * .43 : ry;
      const angle = rotation + index * Math.PI / points;
      const px = cx + Math.cos(angle) * radiusX;
      const py = cy + Math.sin(angle) * radiusY;
      index ? ctx.lineTo(px, py) : ctx.moveTo(px, py);
    }
    ctx.closePath();
  }

  function renderShape(ctx, object) {
    const x = Math.min(object.x1, object.x2), y = Math.min(object.y1, object.y2);
    const w = Math.max(1, Math.abs(object.x2 - object.x1)), h = Math.max(1, Math.abs(object.y2 - object.y1));
    const cx = x + w / 2, cy = y + h / 2;
    ctx.save();
    ctx.strokeStyle = object.color || '#1f2937';
    ctx.fillStyle = object.fill || 'transparent';
    ctx.globalAlpha = object.opacity ?? 1;
    ctx.lineWidth = object.width || 4;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    switch (object.shape) {
      case 'arrow':
        ctx.moveTo(object.x1, object.y1); ctx.lineTo(object.x2, object.y2); ctx.stroke();
        drawArrowHead(ctx, object.x1, object.y1, object.x2, object.y2, Math.max(12, (object.width || 4) * 4));
        ctx.restore(); return;
      case 'double-arrow':
        ctx.moveTo(object.x1, object.y1); ctx.lineTo(object.x2, object.y2); ctx.stroke();
        drawArrowHead(ctx, object.x1, object.y1, object.x2, object.y2, Math.max(12, (object.width || 4) * 4));
        drawArrowHead(ctx, object.x2, object.y2, object.x1, object.y1, Math.max(12, (object.width || 4) * 4));
        ctx.restore(); return;
      case 'rectangle':
      case 'square':
        ctx.rect(x, y, w, h); break;
      case 'rounded-rectangle':
        ctx.roundRect ? ctx.roundRect(x, y, w, h, Math.min(24, w / 5, h / 5)) : ctx.rect(x, y, w, h); break;
      case 'ellipse':
      case 'circle':
        ctx.ellipse(cx, cy, Math.max(1, w / 2), Math.max(1, h / 2), 0, 0, Math.PI * 2); break;
      case 'triangle':
        regularPolygonPath(ctx, cx, cy, w / 2, h / 2, 3); break;
      case 'diamond':
        ctx.moveTo(cx, y); ctx.lineTo(x + w, cy); ctx.lineTo(cx, y + h); ctx.lineTo(x, cy); ctx.closePath(); break;
      case 'pentagon':
        regularPolygonPath(ctx, cx, cy, w / 2, h / 2, 5); break;
      case 'hexagon':
        regularPolygonPath(ctx, cx, cy, w / 2, h / 2, 6, 0); break;
      case 'starshape':
        starPath(ctx, cx, cy, w / 2, h / 2, 5); break;
      case 'trapezoid':
        ctx.moveTo(x + w * .22, y); ctx.lineTo(x + w * .78, y); ctx.lineTo(x + w, y + h); ctx.lineTo(x, y + h); ctx.closePath(); break;
      case 'parallelogram':
        ctx.moveTo(x + w * .22, y); ctx.lineTo(x + w, y); ctx.lineTo(x + w * .78, y + h); ctx.lineTo(x, y + h); ctx.closePath(); break;
      case 'heartshape':
        ctx.moveTo(cx, y + h);
        ctx.bezierCurveTo(x - w * .08, y + h * .58, x, y + h * .12, x + w * .25, y + h * .12);
        ctx.bezierCurveTo(x + w * .43, y + h * .12, cx, y + h * .3, cx, y + h * .3);
        ctx.bezierCurveTo(cx, y + h * .3, x + w * .57, y + h * .12, x + w * .75, y + h * .12);
        ctx.bezierCurveTo(x + w, y + h * .12, x + w * 1.08, y + h * .58, cx, y + h);
        ctx.closePath(); break;
      case 'cloudshape':
        ctx.moveTo(x + w * .18, y + h * .75);
        ctx.bezierCurveTo(x - w * .04, y + h * .72, x - w * .04, y + h * .4, x + w * .2, y + h * .38);
        ctx.bezierCurveTo(x + w * .2, y + h * .12, x + w * .48, y + h * .03, x + w * .64, y + h * .23);
        ctx.bezierCurveTo(x + w * .88, y + h * .12, x + w * 1.04, y + h * .38, x + w * .9, y + h * .58);
        ctx.bezierCurveTo(x + w * 1.05, y + h * .82, x + w * .79, y + h * .98, x + w * .62, y + h * .84);
        ctx.bezierCurveTo(x + w * .5, y + h * 1.04, x + w * .23, y + h * .98, x + w * .18, y + h * .75);
        ctx.closePath(); break;
      case 'speech':
        ctx.roundRect ? ctx.roundRect(x, y, w, h * .8, Math.min(18, w / 8, h / 8)) : ctx.rect(x, y, w, h * .8);
        ctx.moveTo(x + w * .25, y + h * .8); ctx.lineTo(x + w * .18, y + h); ctx.lineTo(x + w * .45, y + h * .8); break;
      case 'curve':
        ctx.moveTo(object.x1, object.y1);
        ctx.quadraticCurveTo(object.cx ?? cx, object.cy ?? (y - h * .25), object.x2, object.y2); break;
      case 'arc':
        ctx.ellipse(cx, y + h, w / 2, h, 0, Math.PI, Math.PI * 2); break;
      default:
        ctx.moveTo(object.x1, object.y1); ctx.lineTo(object.x2, object.y2); break;
    }
    if (object.fill && object.fill !== 'transparent' && !['line','arrow','double-arrow','arc','curve'].includes(object.shape)) ctx.fill();
    ctx.stroke();
    ctx.restore();
  }

  function wrapText(ctx, text, maxWidth) {
    const paragraphs = String(text || '').split(/\n/);
    const lines = [];
    for (const paragraph of paragraphs) {
      const tokens = paragraph.match(/\S+\s*|\s+/g) || [''];
      let line = '';
      for (const token of tokens) {
        const candidate = line + token;
        if (line && ctx.measureText(candidate).width > maxWidth) { lines.push(line.trimEnd()); line = token.trimStart(); }
        else line = candidate;
      }
      lines.push(line.trimEnd());
    }
    return lines;
  }

  function getCached(map, key) {
    if (!map.has(key)) return null;
    const value = map.get(key);
    map.delete(key);
    map.set(key, value);
    return value;
  }

  function currentImageCacheLimit() {
    const doc = currentDocument();
    const rasterDoc = documentHasRasterBackgrounds(doc);
    if (rasterDoc && state.zoom > HIGH_ZOOM_CANVAS_WINDOW) return 3;
    if (rasterDoc) return Math.min(6, NORMAL_IMAGE_CACHE_LIMIT);
    return doc?.pages?.length > LARGE_DOC_PAGE_THRESHOLD
      ? LARGE_DOC_IMAGE_CACHE_LIMIT
      : NORMAL_IMAGE_CACHE_LIMIT;
  }

  function pageHasRasterBackground(page) {
    return !!(page?.backgroundAssetId || page?.backgroundImage || page?.importedFromPdf);
  }

  function documentHasRasterBackgrounds(doc = currentDocument()) {
    return !!doc?.pages?.some(pageHasRasterBackground);
  }

  function renderScaleLimitForPage(page) {
    if (!pageHasRasterBackground(page)) return PAGE_RENDER_SCALE_LIMIT;
    const pixelScale = Math.sqrt(RASTER_PAGE_RENDER_PIXEL_LIMIT / (PAGE_WIDTH * PAGE_HEIGHT));
    const qualityScale = Number(page?.backgroundQualityScale || 0);
    const rasterLimit = qualityScale > 1
      ? Math.min(RASTER_PAGE_RENDER_SCALE_LIMIT, Math.max(1.35, qualityScale))
      : LEGACY_RASTER_PAGE_RENDER_SCALE_LIMIT;
    return Math.max(1, Math.min(rasterLimit, pixelScale));
  }

  function rememberImage(key, image) {
    state.imageCache.set(key, image);
    trimImageCache();
    return image;
  }

  function loadImageObject(object, pageIndex) {
    if (!object?.src) return null;
    let image = getCached(state.imageCache, object.src);
    if (!image) {
      image = new Image();
      image.decoding = 'async';
      image.onload = () => scheduleRenderPage(pageIndex);
      image.src = object.src;
      rememberImage(object.src, image);
    }
    return image;
  }

  function renderObject(ctx, object, pageIndex = 0) {
    if (!object || object.hidden) return;
    ctx.save();
    if (object.rotation) {
      const b = computeBounds(object);
      ctx.translate(b.x + b.w / 2, b.y + b.h / 2);
      ctx.rotate(object.rotation);
      ctx.translate(-(b.x + b.w / 2), -(b.y + b.h / 2));
    }
    if (object.type === 'stroke') renderStroke(ctx, object);
    else if (object.type === 'shape') renderShape(ctx, object);
    else if (object.type === 'tape') {
      const b = computeBounds(object);
      ctx.save();
      ctx.globalCompositeOperation = 'source-over';
      ctx.globalAlpha = 1;
      ctx.fillStyle = object.revealed ? hexToRgba(object.color || '#4c91dd', .12) : (object.color || '#4c91dd');
      ctx.strokeStyle = object.revealed ? hexToRgba(object.color || '#4c91dd', .72) : hexToRgba(object.color || '#4c91dd', 1);
      ctx.lineWidth = object.revealed ? 2 : 3;
      ctx.shadowColor = object.revealed ? 'transparent' : 'rgba(12,25,42,.18)';
      ctx.shadowBlur = object.revealed ? 0 : 5;
      ctx.shadowOffsetY = object.revealed ? 0 : 2;
      if (ctx.roundRect) { ctx.beginPath(); ctx.roundRect(b.x, b.y, b.w, b.h, 7); ctx.fill(); ctx.stroke(); }
      else { ctx.fillRect(b.x, b.y, b.w, b.h); ctx.strokeRect(b.x, b.y, b.w, b.h); }
      ctx.shadowColor = 'transparent';
      if (!object.revealed) {
        ctx.fillStyle = 'rgba(255,255,255,.24)';
        for (let stripeX = b.x + 9; stripeX < b.x + b.w; stripeX += 23) ctx.fillRect(stripeX, b.y + 3, 6, Math.max(0, b.h - 6));
        ctx.fillStyle = 'rgba(0,0,0,.1)';
        ctx.fillRect(b.x + 2, b.y + b.h - 4, Math.max(0, b.w - 4), 2);
      }
      ctx.restore();
    } else if (object.type === 'text') {
      const size = object.fontSize || 28;
      ctx.fillStyle = object.color || '#202733';
      ctx.globalAlpha = object.opacity ?? 1;
      ctx.font = `${object.fontStyle || 'normal'} ${object.fontWeight || 500} ${size}px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif`;
      ctx.textBaseline = 'top';
      const lines = wrapText(ctx, object.text, object.w || 720);
      const lineHeight = size * (object.lineHeight || 1.36);
      lines.forEach((line, index) => ctx.fillText(line, object.x, object.y + index * lineHeight));
    } else if (object.type === 'sticky') {
      const x = object.x || 100, y = object.y || 100, w = object.w || 310, h = object.h || 230;
      ctx.shadowColor = 'rgba(31,35,42,.2)'; ctx.shadowBlur = 18; ctx.shadowOffsetY = 7;
      ctx.fillStyle = object.color || '#ffe58d';
      if (ctx.roundRect) { ctx.beginPath(); ctx.roundRect(x, y, w, h, 10); ctx.fill(); }
      else ctx.fillRect(x, y, w, h);
      ctx.shadowColor = 'transparent';
      ctx.fillStyle = 'rgba(40,40,40,.82)';
      ctx.font = `600 ${object.fontSize || 25}px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif`;
      ctx.textBaseline = 'top';
      const lines = wrapText(ctx, object.text, w - 42);
      lines.slice(0, 8).forEach((line, index) => ctx.fillText(line, x + 21, y + 22 + index * (object.fontSize || 25) * 1.38));
      ctx.fillStyle = 'rgba(255,255,255,.28)';
      ctx.beginPath(); ctx.moveTo(x + w - 36, y + h); ctx.lineTo(x + w, y + h - 36); ctx.lineTo(x + w, y + h); ctx.closePath(); ctx.fill();
    } else if (object.type === 'sticker') {
      const x = object.x || 100, y = object.y || 100, w = object.w || 120, h = object.h || 120;
      ctx.globalAlpha = object.opacity ?? 1;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.font = `${object.fontSize || Math.min(w, h) * .72}px "Segoe UI Emoji","Apple Color Emoji","Noto Color Emoji",sans-serif`;
      ctx.fillText(object.text || '★', x + w / 2, y + h / 2, w);
    } else if (object.type === 'math') {
      const x = object.x || 100, y = object.y || 100, w = object.w || 520, h = object.h || 92;
      ctx.fillStyle = object.background || '#edf5fc';
      ctx.strokeStyle = '#c8dceb'; ctx.lineWidth = 2;
      if (ctx.roundRect) { ctx.beginPath(); ctx.roundRect(x, y, w, h, 16); ctx.fill(); ctx.stroke(); }
      else { ctx.fillRect(x, y, w, h); ctx.strokeRect(x, y, w, h); }
      ctx.fillStyle = object.color || '#225e9d';
      ctx.font = `600 ${object.fontSize || 27}px ui-monospace,SFMono-Regular,Menlo,monospace`;
      ctx.textBaseline = 'middle';
      const content = object.showExpression === false ? String(object.result) : `${object.expression} = ${object.result}`;
      ctx.fillText(content, x + 22, y + h / 2, w - 44);
    } else if (object.type === 'image') {
      const image = loadImageObject(object, pageIndex);
      if (image?.complete && image.naturalWidth) {
        ctx.globalAlpha = object.opacity ?? 1;
        ctx.drawImage(image, object.x, object.y, object.w, object.h);
      } else {
        ctx.fillStyle = '#eef1f5'; ctx.fillRect(object.x, object.y, object.w, object.h);
        ctx.strokeStyle = '#aeb7c2'; ctx.strokeRect(object.x, object.y, object.w, object.h);
      }
    }
    ctx.restore();
  }

  function renderSelection(ctx, pageIndex) {
    if (!state.selection || state.selection.pageIndex !== pageIndex) return;
    const page = currentDocument()?.pages?.[pageIndex];
    if (!page) return;
    const objects = page.objects.filter((object) => state.selection.ids.includes(object.id));
    const bounds = selectionBounds(objects);
    if (!bounds) return;
    ctx.save();
    ctx.strokeStyle = '#1397ed';
    ctx.fillStyle = 'rgba(19,151,237,.08)';
    ctx.lineWidth = 2;
    ctx.setLineDash([9, 7]);
    ctx.fillRect(bounds.x, bounds.y, bounds.w, bounds.h);
    ctx.strokeRect(bounds.x, bounds.y, bounds.w, bounds.h);
    ctx.setLineDash([]);
    for (const [x, y] of [[bounds.x,bounds.y],[bounds.x+bounds.w,bounds.y],[bounds.x,bounds.y+bounds.h],[bounds.x+bounds.w,bounds.y+bounds.h]]) {
      ctx.beginPath(); ctx.arc(x, y, 7, 0, Math.PI * 2); ctx.fillStyle = '#fff'; ctx.fill(); ctx.strokeStyle = '#1397ed'; ctx.stroke();
    }
    ctx.restore();
  }

  function renderRuler(ctx) {
    if (!state.ruler.visible) return;
    ctx.save();
    ctx.translate(state.ruler.x ?? PAGE_WIDTH / 2, state.ruler.y);
    ctx.rotate(state.ruler.angle);
    ctx.fillStyle = 'rgba(224,183,83,.22)';
    ctx.strokeStyle = 'rgba(126,92,12,.65)';
    ctx.lineWidth = 2;
    if (ctx.roundRect) { ctx.beginPath(); ctx.roundRect(-410, -28, 820, 56, 10); ctx.fill(); ctx.stroke(); }
    else { ctx.fillRect(-410, -28, 820, 56); ctx.strokeRect(-410, -28, 820, 56); }
    ctx.fillStyle = 'rgba(82,58,8,.6)';
    for (let x = -390; x <= 390; x += 20) ctx.fillRect(x, -28, 1.5, x % 100 === 0 ? 16 : 9);
    ctx.restore();
  }

  function renderTransient(ctx, pageIndex) {
    const session = state.drawSession;
    if (!session || session.pageIndex !== pageIndex) return;
    if (session.kind === 'stroke') renderStroke(ctx, session.object);
    else if (session.kind === 'shape' || session.kind === 'shape-adjust') renderShape(ctx, session.object);
    else if (session.kind === 'lasso') {
      ctx.save(); ctx.strokeStyle = '#1397ed'; ctx.fillStyle = 'rgba(19,151,237,.08)'; ctx.lineWidth = 2; ctx.setLineDash([7, 5]);
      ctx.beginPath();
      session.points.forEach((point, index) => index ? ctx.lineTo(point.x, point.y) : ctx.moveTo(point.x, point.y));
      if (session.closed) ctx.closePath();
      ctx.fill(); ctx.stroke(); ctx.restore();
    } else if (session.kind === 'laser') {
      const points = session.points || [];
      ctx.save(); ctx.shadowColor = '#f44336'; ctx.shadowBlur = 12;
      strokePathSegments(ctx, points, { width: 5, color: '#f44336', opacity: .85, brush: 'fineliner' });
      ctx.restore();
    } else if (session.kind === 'eraser' && session.point) {
      ctx.save();
      ctx.strokeStyle = 'rgba(20,123,209,.72)';
      ctx.fillStyle = 'rgba(20,123,209,.1)';
      ctx.lineWidth = 2;
      ctx.setLineDash([8, 6]);
      ctx.beginPath();
      ctx.arc(session.point.x, session.point.y, session.radius ?? screenToolWidthToPage(pageIndex, state.eraserRadius), 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.restore();
    }
  }

  function trimImageCache(limit = currentImageCacheLimit()) {
    while (state.imageCache.size > limit) {
      const key = state.imageCache.keys().next().value;
      state.imageCache.delete(key);
    }
    while (state.assetUrlCache.size > limit) {
      const [assetId, url] = state.assetUrlCache.entries().next().value;
      URL.revokeObjectURL(url);
      state.assetUrlCache.delete(assetId);
    }
  }

  function loadPageBackground(page, pageIndex = 0) {
    const source = page?.backgroundImage;
    if (source) {
      let image = getCached(state.imageCache, source);
      if (!image) {
        image = new Image(); image.decoding = 'async';
        image.onload = () => { if (state.view === 'editor') scheduleRenderPage(pageIndex); else renderLibrary(); };
        image.src = source; rememberImage(source, image);
      }
      return image;
    }
    const assetId = page?.backgroundAssetId;
    if (!assetId) return null;
    const cachedUrl = getCached(state.assetUrlCache, assetId);
    if (cachedUrl) {
      let image = getCached(state.imageCache, cachedUrl);
      if (!image) { image = new Image(); image.decoding = 'async'; image.onload = () => scheduleRenderPage(pageIndex); image.src = cachedUrl; rememberImage(cachedUrl, image); }
      return image;
    }
    if (!state.assetLoadQueue.has(assetId)) {
      const request = storage.getAsset(assetId).then((asset) => {
        if (!asset?.blob) return null;
        const url = URL.createObjectURL(asset.blob);
        state.assetUrlCache.set(assetId, url);
        const image = new Image(); image.decoding = 'async';
        image.onload = () => { scheduleRenderPage(pageIndex); if (state.view === 'library') renderLibrary(); };
        image.src = url; rememberImage(url, image);
        return image;
      }).finally(() => state.assetLoadQueue.delete(assetId));
      state.assetLoadQueue.set(assetId, request);
    }
    return null;
  }

  function renderPageBackground(ctx, page, pageIndex = 0) {
    drawTemplate(ctx, page.template || 'blank', PAGE_WIDTH, PAGE_HEIGHT);
    const image = loadPageBackground(page, pageIndex);
    if (image?.complete && image.naturalWidth) ctx.drawImage(image, 0, 0, PAGE_WIDTH, PAGE_HEIGHT);
  }

  function renderPageScene(ctx, page, pageIndex = 0) {
    renderPageBackground(ctx, page, pageIndex);
    for (const object of page.objects || []) renderObject(ctx, object, pageIndex);
    if (state.searchHighlight?.pageIndex === pageIndex) {
      const object = page.objects.find((item) => item.id === state.searchHighlight.objectId);
      if (object) {
        const b = computeBounds(object);
        ctx.save(); ctx.strokeStyle = '#f5a623'; ctx.fillStyle = 'rgba(255,219,92,.18)'; ctx.lineWidth = 5; ctx.fillRect(b.x - 8, b.y - 8, b.w + 16, b.h + 16); ctx.strokeRect(b.x - 8, b.y - 8, b.w + 16, b.h + 16); ctx.restore();
      }
    }
    renderSelection(ctx, pageIndex);
    if (state.currentPageIndex === pageIndex) renderRuler(ctx);
    renderTransient(ctx, pageIndex);
  }

  function processRenderQueue() {
    state.renderQueueFrame = 0;
    const doc = currentDocument();
    if (!doc || !state.renderQueue.size) return;
    const largeDoc = doc.pages.length > LARGE_DOC_PAGE_THRESHOLD;
    const budget = largeDoc ? RENDER_FRAME_BUDGET_MS : NORMAL_RENDER_FRAME_BUDGET_MS;
    const maxPages = largeDoc ? 1 : 2;
    const startedAt = performance.now();
    let rendered = 0;
    const pages = [...state.renderQueue].sort((a, b) =>
      Math.abs(a - state.currentPageIndex) - Math.abs(b - state.currentPageIndex)
    );
    for (const pageIndex of pages) {
      if (!state.renderQueue.has(pageIndex)) continue;
      state.renderQueue.delete(pageIndex);
      const canvas = $(`canvas.page-canvas[data-page-index="${pageIndex}"]`);
      if (!canvas?.isConnected) continue;
      renderPageCanvas(pageIndex);
      rendered++;
      if (rendered >= maxPages || performance.now() - startedAt >= budget) break;
    }
    if (state.renderQueue.size) {
      state.renderQueueFrame = requestAnimationFrame(processRenderQueue);
    }
  }

  function scheduleRenderPage(pageIndex) {
    const index = Number(pageIndex);
    if (!Number.isFinite(index)) return;
    state.renderQueue.add(index);
    if (!state.renderQueueFrame) {
      state.renderQueueFrame = requestAnimationFrame(processRenderQueue);
    }
  }

  function renderPageCanvas(pageIndex) {
    const doc = currentDocument();
    const page = doc?.pages?.[pageIndex];
    const canvas = $(`canvas.page-canvas[data-page-index="${pageIndex}"]`);
    if (!page || !canvas) return;
    const rect = canvas.getBoundingClientRect();
    const cssScale = rect.width > 0 ? rect.width / PAGE_WIDTH : state.zoom;
    const renderScale = clamp(cssScale * (window.devicePixelRatio || 1), 1, renderScaleLimitForPage(page));
    const targetWidth = Math.max(PAGE_WIDTH, Math.round(PAGE_WIDTH * renderScale));
    const targetHeight = Math.max(PAGE_HEIGHT, Math.round(PAGE_HEIGHT * renderScale));
    if (canvas.width !== targetWidth) canvas.width = targetWidth;
    if (canvas.height !== targetHeight) canvas.height = targetHeight;
    const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
    ctx.setTransform(renderScale, 0, 0, renderScale, 0, 0);
    ctx.clearRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
    renderPageScene(ctx, page, pageIndex);
  }

  function renderPageToCanvas(canvas, page, pageIndex = 0, width = 300, height = 424) {
    if (!canvas || !page) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.max(1, Math.round(width * dpr));
    canvas.height = Math.max(1, Math.round(height * dpr));
    const ctx = canvas.getContext('2d', { alpha: false });
    ctx.setTransform(canvas.width / PAGE_WIDTH, 0, 0, canvas.height / PAGE_HEIGHT, 0, 0);
    renderPageBackground(ctx, page, pageIndex);
    for (const object of page.objects || []) renderObject(ctx, object, pageIndex);
  }

  function renderDocumentThumbnail(canvas, doc) {
    if (!canvas || !doc?.pages?.[0]) return;
    const rect = canvas.getBoundingClientRect();
    renderPageToCanvas(canvas, doc.pages[0], 0, rect.width || 180, rect.height || 250);
  }

  function toast(message, duration = 2100) {
    const host = $('#toastHost');
    if (!host) return;
    const node = document.createElement('div');
    node.className = 'toast';
    node.textContent = String(message);
    host.appendChild(node);
    setTimeout(() => { node.style.opacity = '0'; node.style.transform = 'translateY(8px)'; }, Math.max(300, duration - 180));
    setTimeout(() => node.remove(), duration);
  }

  function setView(view) {
    state.view = view;
    $('#libraryView').classList.toggle('is-active', view === 'library');
    $('#editorView').classList.toggle('is-active', view === 'editor');
    document.body.classList.toggle('is-editor', view === 'editor');
  }

  function templateCardHtml(template) {
    const meta = TEMPLATE_META[template];
    return `<article class="document-card template-card" data-template-id="${template}" tabindex="0">
      <div class="document-cover template-cover"><canvas class="template-card-canvas" aria-hidden="true"></canvas><span class="cover-accent" style="--cover:#2f7fb7"></span></div>
      <div class="document-meta"><div class="document-title-row"><span class="document-title">${escapeHtml(meta.title)}</span><span class="document-menu">${icon('plus-circle')}</span></div><div class="document-subtitle"><span>새 노트 만들기</span></div></div>
    </article>`;
  }

  function renderLibrary() {
    setView('library');
    const titleMap = { all: '문서', favorite: '즐겨찾기', shared: '공유됨', templates: '템플릿', trash: '휴지통' };
    const activeFolder = state.folders.find((folder) => folder.id === state.folderId) || state.folders[0];
    $('#libraryTitle').textContent = state.libraryFilter === 'all' && state.folderId !== 'root' ? activeFolder.title : (titleMap[state.libraryFilter] || '문서');
    $('#sortLabel').textContent = state.sort.startsWith('title') ? '이름' : '날짜';
    $('#globalSearchInput').value = state.globalQuery;
    $('#globalSearchPanel').hidden = !state.globalQuery && $('#globalSearchPanel').dataset.open !== '1';
    $$('.rail-button[data-library-filter], .mobile-nav-button[data-library-filter]').forEach((button) => button.classList.toggle('is-active', button.dataset.libraryFilter === state.libraryFilter));
    const breadcrumb = $('#folderBreadcrumb');
    if (breadcrumb) {
      breadcrumb.innerHTML = state.libraryFilter === 'all' && state.folderId !== 'root'
        ? `<button class="breadcrumb" data-folder-id="root">문서</button><span class="breadcrumb-separator">/</span><button class="breadcrumb is-current" data-folder-id="${activeFolder.id}">${escapeHtml(activeFolder.title)}</button>`
        : '<button class="breadcrumb is-current" data-folder-id="root">문서</button>';
    }

    const folderStrip = $('#folderStrip');
    const showFolders = state.libraryFilter === 'all' && state.folderId === 'root' && !state.globalQuery;
    folderStrip.hidden = !showFolders;
    if (showFolders) {
      const folderCards = state.folders.filter((folder) => folder.id !== 'root').map((folder) => {
        const count = state.documents.filter((doc) => !doc.trashed && doc.folderId === folder.id).length;
        return `<button class="folder-chip" data-folder-id="${folder.id}"><span class="folder-glyph" style="background:${hexToRgba(folder.color,.28)};color:${folder.color}">${icon('folder')}</span><span class="folder-copy"><strong>${escapeHtml(folder.title)}</strong><small>${count}개 항목</small></span><span class="folder-menu">${icon('chevron-right')}</span></button>`;
      }).join('');
      folderStrip.innerHTML = `${folderCards}<button class="folder-chip folder-create-chip" data-action="create-folder"><span class="folder-glyph">${icon('folderPlus')}</span><span class="folder-copy"><strong>새 폴더</strong><small>노트 묶음 만들기</small></span><span class="folder-menu">${icon('plus')}</span></button>`;
    }

    const grid = $('#documentGrid');
    grid.classList.toggle('list-mode', state.listMode);
    if (state.libraryFilter === 'templates') {
      const templates = Object.keys(TEMPLATE_META);
      grid.innerHTML = templates.map(templateCardHtml).join('');
      $('#libraryCount').textContent = `${templates.length}개`;
      $('#libraryEmpty').hidden = true;
      injectIcons(grid);
      $$('.template-card-canvas', grid).forEach((canvas, index) => renderPageToCanvas(canvas, blankPage(templates[index]), 0, 180, 250));
      return;
    }

    const docs = visibleDocuments();
    $('#libraryCount').textContent = `${docs.length}개`;
    $('#libraryEmpty').hidden = docs.length !== 0;
    grid.hidden = docs.length === 0;
    grid.innerHTML = docs.map((doc) => `<article class="document-card" data-doc-id="${doc.id}" tabindex="0" aria-label="${escapeHtml(doc.title)} 열기">
      <div class="document-cover"><canvas class="document-thumbnail" data-doc-thumb="${doc.id}" aria-hidden="true"></canvas><span class="cover-accent" style="--cover:${doc.coverColor || '#2f7fb7'}"></span>
        <button class="favorite-toggle ${doc.favorite ? 'is-active' : ''}" data-action="toggle-favorite" data-doc-id="${doc.id}" aria-label="즐겨찾기">${icon('star')}</button>
      </div>
      <div class="document-meta"><div class="document-title-row"><span class="document-title">${escapeHtml(doc.title)}</span><button class="document-menu" data-action="document-menu" data-doc-id="${doc.id}" aria-label="문서 메뉴">${icon('chevron-down')}</button></div>
      <div class="document-subtitle"><span>${formatDate(doc.updatedAt)}</span><span>${doc.pages?.length || 0}쪽</span></div></div>
    </article>`).join('');
    injectIcons(grid);
    requestAnimationFrame(() => {
      for (const doc of docs) renderDocumentThumbnail($(`[data-doc-thumb="${doc.id}"]`), doc);
    });
  }

  function renderTemplatePicker(selected = 'grid') {
    const picker = $('#templatePicker');
    picker.innerHTML = Object.entries(TEMPLATE_META).map(([id, meta]) => `<button class="template-option ${id === selected ? 'is-active' : ''}" data-template-id="${id}"><canvas class="template-mini" width="144" height="196"></canvas><span>${escapeHtml(meta.title)}</span></button>`).join('');
    $$('.template-option', picker).forEach((button) => renderPageToCanvas($('canvas', button), blankPage(button.dataset.templateId), 0, 72, 98));
  }

  function openDocument(id, options = {}) {
    const doc = state.documents.find((item) => item.id === id && !item.trashed);
    if (!doc) { toast('문서를 찾을 수 없습니다.'); return; }
    state.currentDocumentId = id;
    const explicitPage = Object.prototype.hasOwnProperty.call(options, 'pageIndex');
    state.currentPageIndex = clamp(explicitPage ? options.pageIndex : rememberedPageIndex(doc), 0, Math.max(0, doc.pages.length - 1));
    state.pageMode = doc.settings?.pageMode || (state.settings.continuous ? 'continuous' : 'single');
    state.selection = null;
    state.searchHighlight = null;
    state.history.undo = [];
    state.history.redo = [];
    if (!state.openTabs.includes(id)) state.openTabs.push(id);
    if (state.openTabs.length > 6) state.openTabs.shift();
    setView('editor');
    renderTabs();
    renderActiveToolMenu();
    renderEditorPages();
    renderSidebar();
    renderDocumentSearch();
    updateUndoButtons();
    requestAnimationFrame(() => scrollToPage(state.currentPageIndex, false));
  }

  function closeTab(id) {
    const index = state.openTabs.indexOf(id);
    if (index >= 0) state.openTabs.splice(index, 1);
    if (state.currentDocumentId === id) {
      const next = state.openTabs[Math.max(0, index - 1)] || state.openTabs[0];
      if (next) openDocument(next); else { state.currentDocumentId = null; renderLibrary(); }
    } else renderTabs();
  }

  function renderTabs() {
    const list = $('#tabList');
    const docs = state.openTabs.map((id) => state.documents.find((doc) => doc.id === id)).filter(Boolean);
    list.innerHTML = docs.map((doc) => `<button class="document-tab ${doc.id === state.currentDocumentId ? 'is-active' : ''}" data-action="switch-tab" data-doc-id="${doc.id}"><span class="document-tab-title">${escapeHtml(doc.title)}</span><span class="document-tab-close" data-action="close-tab" data-doc-id="${doc.id}">${icon('close')}</span></button>`).join('');
  }

  function updatePageSizing({ render = true } = {}) {
    const viewport = $('#editorViewport');
    if (!viewport) return;
    const mobile = window.matchMedia('(max-width: 840px)').matches;
    const available = Math.max(280, viewport.clientWidth - (mobile ? 20 : 104));
    const base = Math.min(880, available);
    const width = Math.round(base * state.zoom);
    const stack = $('#pageStack');
    stack.style.setProperty('--zoom', String(state.zoom));
    stack.style.setProperty('--page-width', `${width}px`);
    stack.style.setProperty('--page-stack-width', `${Math.max(viewport.clientWidth, width + 128)}px`);
    $('#zoomIndicator').textContent = `${Math.round(state.zoom * 100)}%`;
    if (render) $$('.page-canvas').forEach((canvas) => scheduleRenderPage(Number(canvas.dataset.pageIndex)));
  }

  function scheduleZoomRender() {
    if (!state.zoomRenderFrame) {
      state.zoomRenderFrame = requestAnimationFrame(() => {
        state.zoomRenderFrame = 0;
        renderPageCanvas(state.currentPageIndex);
      });
    }
    clearTimeout(state.zoomRenderTimer);
    state.zoomRenderTimer = setTimeout(() => {
      $$('.page-canvas').forEach((canvas) => scheduleRenderPage(Number(canvas.dataset.pageIndex)));
    }, ZOOM_RENDER_DEBOUNCE_MS);
  }

  function pageLayoutMetrics() {
    const first = $('.page-wrap[data-page-index]');
    if (!first) return null;
    const firstIndex = Number(first.dataset.pageIndex) || 0;
    const second = first.nextElementSibling?.classList?.contains('page-wrap')
      ? first.nextElementSibling
      : null;
    const pageHeight = Math.max(1, first.offsetHeight);
    const gap = parseFloat(getComputedStyle($('#pageStack')).rowGap || '34') || 34;
    const step = second ? Math.max(1, second.offsetTop - first.offsetTop) : pageHeight + gap;
    const firstTop = first.offsetTop - firstIndex * step;
    return { firstTop, pageHeight, step };
  }

  function estimatePageIndexFromScroll() {
    const doc = currentDocument();
    const viewport = $('#editorViewport');
    const metrics = pageLayoutMetrics();
    if (!doc || !viewport || !metrics) return state.currentPageIndex;
    const center = viewport.scrollTop + viewport.clientHeight / 2;
    const raw = (center - metrics.firstTop - metrics.pageHeight / 2) / metrics.step;
    return clamp(Math.round(raw), 0, doc.pages.length - 1);
  }

  function bestVisiblePageIndex() {
    const doc = currentDocument();
    const viewport = $('#editorViewport');
    if (!doc || !viewport) return state.currentPageIndex;
    const estimated = estimatePageIndexFromScroll();
    const centerY = viewport.getBoundingClientRect().top + viewport.clientHeight / 2;
    let best = estimated, bestDistance = Infinity;
    const start = Math.max(0, estimated - 2);
    const end = Math.min(doc.pages.length - 1, estimated + 2);
    for (let index = start; index <= end; index++) {
      const wrap = $(`.page-wrap[data-page-index="${index}"]`);
      if (!wrap) continue;
      const rect = wrap.getBoundingClientRect();
      const currentDistance = Math.abs(rect.top + rect.height / 2 - centerY);
      if (currentDistance < bestDistance) { bestDistance = currentDistance; best = index; }
    }
    return clamp(best, 0, doc.pages.length - 1);
  }

  function visiblePageIndexes() {
    const doc = currentDocument();
    const viewport = $('#editorViewport');
    const metrics = pageLayoutMetrics();
    if (!doc || !viewport || !metrics) return [state.currentPageIndex];
    if (state.pageMode === 'single') return [state.currentPageIndex];
    const top = viewport.scrollTop;
    const bottom = top + viewport.clientHeight;
    const first = clamp(Math.floor((top - metrics.firstTop) / metrics.step) - 1, 0, doc.pages.length - 1);
    const last = clamp(Math.ceil((bottom - metrics.firstTop) / metrics.step) + 1, 0, doc.pages.length - 1);
    const indexes = [];
    for (let index = first; index <= last; index++) indexes.push(index);
    if (!indexes.includes(state.currentPageIndex)) indexes.unshift(state.currentPageIndex);
    return [...new Set(indexes)].sort((a, b) => Math.abs(a - state.currentPageIndex) - Math.abs(b - state.currentPageIndex));
  }

  function viewportClientPoint(anchor, viewport) {
    const rect = viewport.getBoundingClientRect();
    const fallbackX = rect.left + viewport.clientWidth / 2;
    const fallbackY = rect.top + viewport.clientHeight / 2;
    const rawX = Number.isFinite(anchor?.clientX) ? Number(anchor.clientX) : fallbackX;
    const rawY = Number.isFinite(anchor?.clientY) ? Number(anchor.clientY) : fallbackY;
    const clientX = clamp(rawX, rect.left, rect.right);
    const clientY = clamp(rawY, rect.top, rect.bottom);
    return { clientX, clientY, viewportX: clientX - rect.left, viewportY: clientY - rect.top };
  }

  function zoomAnchorCandidates(preferredIndex = state.currentPageIndex) {
    const doc = currentDocument();
    if (!doc) return [];
    const indices = new Set();
    const addNear = (index, radius = 2) => {
      if (!Number.isFinite(index)) return;
      for (let offset = -radius; offset <= radius; offset++) {
        const next = Math.round(index) + offset;
        if (next >= 0 && next < doc.pages.length) indices.add(next);
      }
    };
    addNear(preferredIndex);
    addNear(state.currentPageIndex);
    addNear(estimatePageIndexFromScroll());
    return [...indices];
  }

  function zoomAnchorWrapAt(clientX, clientY, preferredIndex = state.currentPageIndex) {
    const direct = document.elementFromPoint(clientX, clientY)?.closest?.('.page-wrap');
    if (direct?.dataset?.pageIndex != null) return direct;
    let best = null, bestScore = Infinity;
    zoomAnchorCandidates(preferredIndex).forEach((index) => {
      const wrap = $(`.page-wrap[data-page-index="${index}"]`);
      if (!wrap) return;
      const rect = wrap.getBoundingClientRect();
      const dx = clientX < rect.left ? rect.left - clientX : clientX > rect.right ? clientX - rect.right : 0;
      const dy = clientY < rect.top ? rect.top - clientY : clientY > rect.bottom ? clientY - rect.bottom : 0;
      const score = dy * dy + dx * dx * .35;
      if (score < bestScore) { bestScore = score; best = wrap; }
    });
    return best;
  }

  function captureZoomAnchor(anchor = null, preferredIndex = state.currentPageIndex) {
    const viewport = $('#editorViewport');
    if (!viewport) return null;
    const point = viewportClientPoint(anchor, viewport);
    const wrap = zoomAnchorWrapAt(point.clientX, point.clientY, preferredIndex);
    if (!wrap) return null;
    const rect = wrap.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return null;
    return {
      ...point,
      pageIndex: Number(wrap.dataset.pageIndex),
      ratioX: clamp((point.clientX - rect.left) / rect.width, 0, 1),
      ratioY: clamp((point.clientY - rect.top) / rect.height, 0, 1)
    };
  }

  function retargetZoomAnchor(snapshot, clientX, clientY) {
    const viewport = $('#editorViewport');
    if (!snapshot || !viewport) return null;
    return { ...snapshot, ...viewportClientPoint({ clientX, clientY }, viewport) };
  }

  function restoreZoomAnchor(snapshot) {
    const viewport = $('#editorViewport');
    const wrap = snapshot && $(`.page-wrap[data-page-index="${snapshot.pageIndex}"]`);
    if (!viewport || !wrap) return false;
    const maxLeft = Math.max(0, viewport.scrollWidth - viewport.clientWidth);
    const maxTop = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
    const nextLeft = wrap.offsetLeft + wrap.offsetWidth * snapshot.ratioX - snapshot.viewportX;
    const nextTop = wrap.offsetTop + wrap.offsetHeight * snapshot.ratioY - snapshot.viewportY;
    viewport.scrollLeft = clamp(nextLeft, 0, maxLeft);
    viewport.scrollTop = clamp(nextTop, 0, maxTop);
    return true;
  }

  function lockZoomAftermath() {
    const nowTime = performance.now();
    state.suppressPageUpdateUntil = Math.max(state.suppressPageUpdateUntil, nowTime + ZOOM_PAGE_SUPPRESS_MS);
    state.zoomDragLockUntil = Math.max(state.zoomDragLockUntil, nowTime + ZOOM_DRAG_LOCK_MS);
  }

  function mountPageCanvas(index) {
    const wrap = $(`.page-wrap[data-page-index="${index}"]`);
    if (!wrap || wrap.querySelector('.page-canvas')) return;
    const placeholder = wrap.querySelector('.page-placeholder');
    const canvas = document.createElement('canvas');
    canvas.className = 'page-canvas'; canvas.dataset.pageIndex = String(index);
    canvas.width = PAGE_WIDTH; canvas.height = PAGE_HEIGHT;
    canvas.setAttribute('aria-label', `${index + 1}페이지`);
    (placeholder || wrap.firstChild)?.replaceWith(canvas);
    scheduleRenderPage(index);
  }

  function unmountPageCanvas(index) {
    const wrap = $(`.page-wrap[data-page-index="${index}"]`);
    const canvas = wrap?.querySelector('.page-canvas');
    if (!wrap || !canvas || state.drawSession?.pageIndex === index || index === state.currentPageIndex) return;
    state.renderQueue.delete(index);
    const frame = state.renderFrames.get(index);
    if (frame) {
      cancelAnimationFrame(frame);
      state.renderFrames.delete(index);
    }
    const placeholder = document.createElement('div'); placeholder.className = 'page-placeholder';
    canvas.width = 1;
    canvas.height = 1;
    canvas.replaceWith(placeholder);
  }

  function updateVirtualPages() {
    const doc = currentDocument(), viewport = $('#editorViewport');
    if (!doc || !viewport) return;
    if (state.pageMode === 'single') { mountPageCanvas(state.currentPageIndex); return; }
    const center = bestVisiblePageIndex();
    const largeDoc = doc.pages.length > LARGE_DOC_PAGE_THRESHOLD;
    if (largeDoc) renderLargePageWindow(center);
    const highZoom = state.zoom > HIGH_ZOOM_CANVAS_WINDOW;
    const radius = highZoom ? 0 : doc.pages.length > LARGE_DOC_PAGE_THRESHOLD ? 1 : 4;
    const start = highZoom ? center : Math.max(0, Math.min(center, state.currentPageIndex) - radius);
    const end = highZoom ? center : Math.min(doc.pages.length - 1, Math.max(center, state.currentPageIndex) + radius);
    for (let index = start; index <= end; index++) mountPageCanvas(index);
    $$('.page-canvas').forEach((canvas) => {
      const index = Number(canvas.dataset.pageIndex);
      if ((index < start || index > end) && index !== state.currentPageIndex) unmountPageCanvas(index);
    });
  }

  function pageStackMetrics() {
    const stack = $('#pageStack');
    if (!stack) return { pageHeight: PAGE_HEIGHT, gap: 34, step: PAGE_HEIGHT + 34 };
    const style = getComputedStyle(stack);
    const widthValue = parseFloat(style.getPropertyValue('--page-width')) || parseFloat(style.width) || 880;
    const pageHeight = Math.max(1, widthValue * PAGE_HEIGHT / PAGE_WIDTH);
    const gap = parseFloat(style.rowGap || style.gap || '34') || 34;
    return { pageHeight, gap, step: pageHeight + gap };
  }

  function appendPageSpacer(fragment, className, missingPages) {
    if (missingPages <= 0) return;
    const { step, gap } = pageStackMetrics();
    const spacer = document.createElement('div');
    spacer.className = `page-window-spacer ${className}`;
    spacer.style.height = `${Math.max(0, missingPages * step - gap)}px`;
    spacer.setAttribute('aria-hidden', 'true');
    fragment.appendChild(spacer);
  }

  function renderLargePageWindow(centerIndex = state.currentPageIndex, force = false) {
    const doc = currentDocument();
    const stack = $('#pageStack');
    if (!doc || !stack || doc.pages.length <= LARGE_DOC_PAGE_THRESHOLD || state.pageMode === 'single') return false;
    const center = clamp(Math.round(centerIndex), 0, doc.pages.length - 1);
    const low = Math.min(center, state.currentPageIndex);
    const high = Math.max(center, state.currentPageIndex);
    const start = Math.max(0, low - LARGE_DOC_DOM_WINDOW_RADIUS);
    const end = Math.min(doc.pages.length - 1, high + LARGE_DOC_DOM_WINDOW_RADIUS);
    if (!force && state.virtualPageWindow.start === start && state.virtualPageWindow.end === end) return false;
    const fragment = document.createDocumentFragment();
    appendPageSpacer(fragment, 'top', start);
    for (let index = start; index <= end; index++) fragment.appendChild(createPageWrap(index, doc.pages.length, true));
    appendPageSpacer(fragment, 'bottom', doc.pages.length - end - 1);
    stack.replaceChildren(fragment);
    state.virtualPageWindow = { start, end };
    state.activePageWrapIndex = -1;
    return true;
  }

  function createPageWrap(index, pageCount, largeDoc) {
    const section = document.createElement('section');
    section.className = `page-wrap ${index === state.currentPageIndex ? 'is-active' : ''}`;
    section.dataset.pageIndex = String(index);
    const placeholder = document.createElement('div');
    placeholder.className = 'page-placeholder';
    placeholder.setAttribute('aria-label', `${index + 1}페이지 로딩`);
    const number = document.createElement('span');
    number.className = 'page-number-chip';
    number.textContent = String(index + 1);
    section.append(placeholder, number);
    if (!largeDoc) {
      const actions = document.createElement('div');
      actions.className = 'page-bottom-actions';
      actions.innerHTML = `<button class="next-page-button" data-action="insert-page-after" data-page-index="${index}">${icon('page-plus')}<span>${index === pageCount - 1 ? '다음 페이지 추가' : '이 페이지 뒤에 추가'}</span></button>`;
      section.appendChild(actions);
    }
    return section;
  }

  function renderEditorPages() {
    const doc = currentDocument();
    if (!doc) return;
    setView('editor');
    const stack = $('#pageStack');
    stack.classList.toggle('single-mode', state.pageMode === 'single');
    stack.dataset.tool = state.readOnly ? 'hand' : state.tool;
    const largeDoc = doc.pages.length > LARGE_DOC_PAGE_THRESHOLD;
    stack.dataset.largeDoc = largeDoc ? '1' : '0';
    updatePageSizing({ render: false });
    state.virtualPageWindow = { start: -1, end: -1 };
    if (largeDoc && state.pageMode !== 'single') {
      renderLargePageWindow(state.currentPageIndex, true);
    } else {
      const fragment = document.createDocumentFragment();
      doc.pages.forEach((_, index) => fragment.appendChild(createPageWrap(index, doc.pages.length, largeDoc)));
      stack.replaceChildren(fragment);
    }
    mountPageCanvas(state.currentPageIndex);
    for (let index = Math.max(0, state.currentPageIndex - 1); index <= Math.min(doc.pages.length - 1, state.currentPageIndex + 1); index++) mountPageCanvas(index);
    requestAnimationFrame(updateVirtualPages);
    updatePageIndicator(); updateObjectMenu();
  }

  function pageIndexFromRailEvent(event) {
    const doc = currentDocument();
    const track = $('#pageScrollTrack');
    if (!doc || !track) return state.currentPageIndex;
    const rect = track.getBoundingClientRect();
    const ratio = clamp((event.clientY - rect.top) / Math.max(1, rect.height), 0, 1);
    return Math.round(ratio * Math.max(0, doc.pages.length - 1));
  }

  function updatePageScrollRail() {
    const doc = currentDocument();
    const rail = $('#pageScrollRail');
    const thumb = $('#pageScrollThumb');
    const input = $('#pageJumpInput');
    if (!rail || !thumb || !input || !doc) return;
    rail.hidden = state.view !== 'editor' || doc.pages.length <= 1;
    const maxIndex = Math.max(1, doc.pages.length - 1);
    const thumbHeight = clamp(100 / Math.max(1, doc.pages.length), 6, 28);
    const progress = doc.pages.length <= 1 ? 0 : state.currentPageIndex / maxIndex;
    thumb.style.height = `${thumbHeight}%`;
    thumb.style.top = `${progress * (100 - thumbHeight)}%`;
    thumb.textContent = String(state.currentPageIndex + 1);
    input.max = String(doc.pages.length);
    if (document.activeElement !== input) input.value = String(state.currentPageIndex + 1);
  }

  function goToPageNumber() {
    const doc = currentDocument();
    const input = $('#pageJumpInput');
    if (!doc || !input) return;
    const pageNumber = clamp(Math.round(Number(input.value) || 1), 1, doc.pages.length);
    input.value = String(pageNumber);
    scrollToPage(pageNumber - 1);
  }

  function updatePageIndicator() {
    const doc = currentDocument();
    if (!doc) return;
    $('#pageIndicator').textContent = `${state.currentPageIndex + 1} / ${doc.pages.length}`;
    if (state.activePageWrapIndex !== state.currentPageIndex) {
      $(`.page-wrap[data-page-index="${state.activePageWrapIndex}"]`)?.classList.remove('is-active');
      $(`.page-wrap[data-page-index="${state.currentPageIndex}"]`)?.classList.add('is-active');
      state.activePageWrapIndex = state.currentPageIndex;
    }
    updatePageScrollRail();
  }

  function pageScrollTarget(index) {
    const viewport = $('#editorViewport');
    const stack = $('#pageStack');
    const metrics = pageLayoutMetrics();
    if (!viewport || !stack || !metrics) return null;
    const first = $('.page-wrap[data-page-index]');
    const pageTop = metrics.firstTop + metrics.step * index;
    const pageLeft = first ? first.offsetLeft : Math.max(0, (stack.scrollWidth - metrics.pageHeight * PAGE_WIDTH / PAGE_HEIGHT) / 2);
    const pageWidth = first?.offsetWidth || Math.round(PAGE_WIDTH * state.zoom);
    return {
      top: clamp(pageTop + metrics.pageHeight / 2 - viewport.clientHeight / 2, 0, Math.max(0, viewport.scrollHeight - viewport.clientHeight)),
      left: clamp(pageLeft + pageWidth / 2 - viewport.clientWidth / 2, 0, Math.max(0, viewport.scrollWidth - viewport.clientWidth))
    };
  }

  function scrollToPage(index, smooth = true) {
    const doc = currentDocument();
    if (!doc) return;
    const nextPageIndex = clamp(index, 0, doc.pages.length - 1);
    const viewport = $('#editorViewport');
    const target = state.pageMode !== 'single' ? pageScrollTarget(nextPageIndex) : null;
    if (nextPageIndex === state.currentPageIndex) {
      mountPageCanvas(nextPageIndex);
      if (target && viewport) {
        const closeEnough = Math.abs(viewport.scrollTop - target.top) < 2 && Math.abs(viewport.scrollLeft - target.left) < 2;
        if (!closeEnough) viewport.scrollTo({ top: target.top, left: target.left, behavior: smooth ? 'smooth' : 'auto' });
      }
      updateVirtualPages();
      updatePageIndicator();
      rememberCurrentPage();
      return;
    }
    const previousPageIndex = state.currentPageIndex;
    state.allowLargePageJumpUntil = performance.now() + 900;
    state.currentPageIndex = nextPageIndex;
    notifyPageChanged(previousPageIndex, 'scroll-to-page');
    if (doc.pages.length > LARGE_DOC_PAGE_THRESHOLD && state.pageMode !== 'single') {
      renderLargePageWindow(state.currentPageIndex);
    }
    mountPageCanvas(state.currentPageIndex);
    if (state.pageMode !== 'single') {
      if (target) {
        const closeEnough = Math.abs(viewport.scrollTop - target.top) < 2 && Math.abs(viewport.scrollLeft - target.left) < 2;
        if (!closeEnough) viewport.scrollTo({ top: target.top, left: target.left, behavior: smooth ? 'smooth' : 'auto' });
      } else {
        const wrap = $(`.page-wrap[data-page-index="${state.currentPageIndex}"]`);
        if (wrap) wrap.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'center', inline: 'center' });
      }
    }
    updatePageIndicator();
    renderActiveToolMenu();
    renderSidebar();
    updateObjectMenu();
    rememberCurrentPage();
  }

  function addPage(afterIndex = null, template = null) {
    const doc = currentDocument();
    if (!doc) return;
    checkpoint('page-add');
    const index = afterIndex == null ? doc.pages.length : clamp(Number(afterIndex) + 1, 0, doc.pages.length);
    const sourceTemplate = template || doc.pages[Math.max(0, Math.min(doc.pages.length - 1, index - 1))]?.template || 'grid';
    doc.pages.splice(index, 0, blankPage(sourceTemplate));
    state.currentPageIndex = index;
    persistCurrent();
    renderEditorPages();
    renderSidebar();
    requestAnimationFrame(() => scrollToPage(index));
    toast('새 페이지를 만들었습니다.');
  }

  function duplicatePage(index) {
    const doc = currentDocument();
    if (!doc?.pages?.[index]) return;
    checkpoint('page-duplicate');
    const copy = deepClone(doc.pages[index]);
    copy.id = uid('page');
    copy.createdAt = now();
    copy.objects.forEach((object) => { object.id = uid(object.type || 'object'); });
    doc.pages.splice(index + 1, 0, copy);
    state.currentPageIndex = index + 1;
    persistCurrent();
    renderEditorPages(); renderSidebar();
    requestAnimationFrame(() => scrollToPage(index + 1));
  }

  function deletePage(index) {
    const doc = currentDocument();
    if (!doc?.pages?.[index]) return;
    if (doc.pages.length === 1) { toast('노트에는 최소 한 페이지가 필요합니다.'); return; }
    checkpoint('page-delete');
    doc.pages.splice(index, 1);
    state.currentPageIndex = clamp(state.currentPageIndex, 0, doc.pages.length - 1);
    persistCurrent(); renderEditorPages(); renderSidebar();
  }

  function movePage(index, direction) {
    const doc = currentDocument();
    const target = index + direction;
    if (!doc || target < 0 || target >= doc.pages.length) return;
    checkpoint('page-move');
    const [page] = doc.pages.splice(index, 1);
    doc.pages.splice(target, 0, page);
    state.currentPageIndex = target;
    persistCurrent(); renderEditorPages(); renderSidebar();
  }

  function renderSidebar() {
    const sidebar = $('#pageSidebar');
    sidebar.classList.toggle('is-open', state.sidebarOpen);
    $$('.sidebar-tab').forEach((button) => button.classList.toggle('is-active', button.dataset.sidebarTab === state.sidebarTab));
    const content = $('#sidebarContent');
    const doc = currentDocument();
    if (!doc) { content.innerHTML = ''; return; }
    if (!state.sidebarOpen) return;
    if (state.sidebarTab === 'pages') {
      const largeDoc = doc.pages.length > LARGE_DOC_PAGE_THRESHOLD;
      const start = largeDoc ? Math.max(0, state.currentPageIndex - SIDEBAR_WINDOW_RADIUS) : 0;
      const end = largeDoc ? Math.min(doc.pages.length - 1, state.currentPageIndex + SIDEBAR_WINDOW_RADIUS) : doc.pages.length - 1;
      const rows = [];
      if (largeDoc && start > 0) rows.push(`<div class="sidebar-window-note">1-${start}페이지는 페이지 번호 입력 또는 스크롤 바로 이동</div>`);
      for (let index = start; index <= end; index++) {
        const page = doc.pages[index];
        rows.push(`<button class="page-thumb-item ${index === state.currentPageIndex ? 'is-active' : ''}" data-action="go-page" data-page-index="${index}"><canvas class="page-thumb-canvas" width="144" height="204"></canvas><span class="page-thumb-copy"><strong>${page.title ? escapeHtml(page.title) : `${index + 1}페이지`}</strong><small>${page.objects.length}개 항목${page.bookmarked ? ' · 북마크' : ''}</small></span><span class="page-thumb-menu" data-action="page-menu" data-page-index="${index}">${icon('more')}</span></button>`);
      }
      if (largeDoc && end < doc.pages.length - 1) rows.push(`<div class="sidebar-window-note">${end + 2}-${doc.pages.length}페이지는 페이지 번호 입력 또는 스크롤 바로 이동</div>`);
      content.innerHTML = rows.join('') + `<button class="sidebar-add-page" data-action="add-page">${icon('page-plus')}<span>페이지 추가</span></button>`;
      const thumbRows = $$('.page-thumb-item', content);
      const renderThumb = (row) => { const index = Number(row.dataset.pageIndex); const canvas = $('.page-thumb-canvas', row); if (!canvas || canvas.dataset.rendered) return; renderPageToCanvas(canvas, doc.pages[index], index, 72, 102); canvas.dataset.rendered = '1'; };
      if ('IntersectionObserver' in window) {
        const observer = new IntersectionObserver((entries) => entries.forEach((entry) => { if (entry.isIntersecting) { renderThumb(entry.target); observer.unobserve(entry.target); } }), { root: content, rootMargin: '260px' });
        thumbRows.forEach((row) => observer.observe(row));
      } else thumbRows.slice(0, 20).forEach(renderThumb);
    } else if (state.sidebarTab === 'outline') {
      const outlines = [];
      doc.pages.forEach((page, pageIndex) => {
        if (page.title) outlines.push({ title: page.title, pageIndex });
        page.objects.filter((object) => object.type === 'text' && (object.fontSize || 0) >= 30).forEach((object) => outlines.push({ title: object.text.split('\n')[0].slice(0, 60), pageIndex, objectId: object.id }));
      });
      content.innerHTML = outlines.length ? outlines.map((item) => `<button class="outline-row" data-action="go-page" data-page-index="${item.pageIndex}">${icon('bookmark')}<span>${escapeHtml(item.title)}</span><small>${item.pageIndex + 1}</small></button>`).join('') : `<div class="empty-sidebar">큰 텍스트 제목이나 페이지 제목이 목차에 표시됩니다.</div>`;
    } else {
      content.innerHTML = doc.audio?.length ? doc.audio.map((clip) => `<div class="audio-row"><button class="icon-button compact" data-action="play-audio" data-audio-id="${clip.id}">${icon('play')}</button><span>${escapeHtml(clip.title || '오디오 녹음')} · ${formatTime(clip.duration)}</span><button class="icon-button compact" data-action="delete-audio" data-audio-id="${clip.id}">${icon('trash')}</button></div>`).join('') : `<div class="empty-sidebar">마이크 아이콘을 눌러 필기와 함께 오디오를 녹음할 수 있습니다.</div>`;
    }
    injectIcons(content);
  }

  function activeColorPalette() {
    if (state.tool === 'highlighter') return ['#f5df39','#80e2ff','#ff9fc2','#a8ef82','#b9a3ff'];
    return ['#111827','#147bd1','#ffffff','#d93939','#2a9d66','#7a57c7'];
  }

  function widthButton(width, current, color = '#fff') {
    return `<button class="width-slot ${Math.abs(width - current) < .2 ? 'is-active' : ''}" data-action="set-width" data-width="${width}" aria-label="굵기 ${width}"><span class="width-swatch" style="height:${clamp(width / 2.2,2,13)}px;color:${color}"></span></button>`;
  }

  function widthControl(tool, current, options = {}) {
    const min = options.min ?? .5;
    const max = options.max ?? 160;
    const step = options.step ?? .5;
    const value = Math.round(current * 10) / 10;
    const progress = clamp((value - min) / Math.max(.001, max - min), 0, 1);
    return `<label class="width-slider" aria-label="굵기 조절" style="--progress:${progress}"><input type="range" min="${min}" max="${max}" step="${step}" value="${value}" data-width-input data-width-tool="${tool}" aria-label="굵기 조절" /><span>${value}px</span></label>`;
  }

  function setToolWidth(width, tool = state.tool, render = true) {
    const target = tool === 'eraser' ? 'eraser' : tool === 'highlighter' ? 'highlighter' : 'pen';
    const max = target === 'eraser' ? 180 : target === 'highlighter' ? 120 : 80;
    const value = clamp(Number(width) || 1, .5, max);
    if (target === 'eraser') state.eraserRadius = value;
    else if (target === 'highlighter') state.highlighterWidth = value;
    else state.width = value;
    if (render) renderActiveToolMenu();
    return value;
  }

  function colorButtons(colors, active) {
    return colors.map((color) => `<button class="color-slot ${normalizeText(color) === normalizeText(active) ? 'is-active' : ''}" style="--swatch:${color}" data-action="set-color" data-color="${color}" aria-label="색상 ${color}"></button>`).join('') + `<button class="color-slot add-color" data-action="custom-color" aria-label="사용자 색상">${icon('plus')}</button>`;
  }

  function renderActiveToolMenu() {
    const menu = $('#activeToolMenu');
    if (!menu) return;
    const tool = state.readOnly ? 'hand' : state.tool;
    $$('.tool-button').forEach((button) => button.classList.toggle('is-active', button.dataset.tool === tool));
    const stack = $('#pageStack');
    if (stack) stack.dataset.tool = tool;
    let html = '';
    if (tool === 'pen') {
      const brush = BRUSH_META[state.brush] || BRUSH_META.fountain;
      const widths = state.brush === 'pencil' ? [2.4, 5.2, 10] : state.brush === 'brush' ? [4, 9, 18] : [2, 4.2, 8.5];
      html = `<button class="tool-name-button" data-action="brush-menu">${icon(brush.icon)}<span>${escapeHtml(brush.title)}</span>${icon('chevron-down')}</button>
        <span class="active-divider"></span>${widths.map((width) => widthButton(width, state.width)).join('')}${widthControl('pen', state.width, { min:.5, max:80, step:.5 })}
        <button class="icon-button" data-action="pen-settings" aria-label="펜 설정">${icon('settings')}</button><span class="active-divider"></span>${colorButtons(activeColorPalette(), state.color)}`;
    } else if (tool === 'highlighter') {
      html = `<button class="tool-name-button" data-action="set-tool" data-tool="highlighter">${icon('highlighter')}<span>형광펜</span></button><span class="active-divider"></span>${[12,22,34].map((width) => widthButton(width, state.highlighterWidth)).join('')}${widthControl('highlighter', state.highlighterWidth, { min:2, max:120, step:1 })}<span class="active-divider"></span>${colorButtons(activeColorPalette(), state.highlighterColor)}`;
    } else if (tool === 'eraser') {
      const modes = [{ id:'stroke', icon:'eraser', title:'획' }, { id:'precision', icon:'lasso', title:'정밀' }, { id:'whole', icon:'trash', title:'전체' }];
      html = modes.map((mode) => `<button class="tool-name-button ${state.eraserMode === mode.id ? 'is-active' : ''}" data-action="set-eraser-mode" data-mode="${mode.id}">${icon(mode.icon)}<span>${mode.title}</span></button>`).join('') + `<span class="active-divider"></span>${[12,26,48].map((width) => widthButton(width, state.eraserRadius)).join('')}${widthControl('eraser', state.eraserRadius, { min:2, max:180, step:1 })}<button class="icon-button" data-action="clear-page" aria-label="페이지 지우기">${icon('trash')}</button>`;
    } else if (tool === 'lasso') {
      html = `<button class="tool-name-button is-active">${icon('lasso')}<span>자유형 선택</span></button><button class="icon-button" data-action="select-all" aria-label="전체 선택">${icon('check-circle')}</button><button class="icon-button" data-action="paste" aria-label="붙여넣기">${icon('copy')}</button><span class="active-divider"></span><span class="active-label">필기 · 텍스트 · 이미지</span>`;
    } else if (tool === 'shape') {
      const shapes = ['line','curve','arc','rectangle','rounded-rectangle','square','ellipse','circle','triangle','diamond','pentagon','hexagon','starshape','trapezoid','parallelogram','heartshape','cloudshape','speech','arrow','double-arrow'];
      html = shapes.map((shape) => `<button class="icon-button ${state.shape === shape ? 'is-active' : ''}" data-action="set-shape" data-shape="${shape}" aria-label="${shape}">${icon(shape)}</button>`).join('') + `<span class="active-divider"></span>${[2,4,8].map((width) => widthButton(width, state.width)).join('')}${widthControl('pen', state.width, { min:.5, max:80, step:.5 })}${colorButtons(activeColorPalette().slice(0,5), state.color)}`;
    } else if (tool === 'text') {
      html = `<button class="tool-name-button is-active" data-action="insert-text-now">${icon('text')}<span>텍스트 추가</span></button><span class="active-divider"></span><span class="active-label">페이지를 탭해 입력</span><button class="icon-button" data-action="document-search" aria-label="검색">${icon('search')}</button>`;
    } else if (tool === 'sticky') {
      html = `<button class="tool-name-button is-active">${icon('sticky')}<span>스티키 노트</span></button><span class="active-divider"></span>${['#ffe58d','#ffd2dc','#cfefff','#d8f5ce','#ddd2ff'].map((color) => `<button class="color-slot ${state.stickyColor === color ? 'is-active' : ''}" style="--swatch:${color}" data-action="set-sticky-color" data-color="${color}"></button>`).join('')}<span class="active-label">페이지를 탭해 추가</span>`;
    } else if (tool === 'tape') {
      html = `<button class="tool-name-button is-active">${icon('tape')}<span>암기 테이프</span></button><span class="active-divider"></span>${['#4c91dd','#cf5b74','#6f63c7','#2c9878','#d38a32'].map((color) => `<button class="color-slot ${state.tapeColor === color ? 'is-active' : ''}" style="--swatch:${color}" data-action="set-tape-color" data-color="${color}"></button>`).join('')}<span class="active-label">탭하면 정답 보기</span>`;
    } else if (tool === 'hand') {
      html = `<button class="tool-name-button is-active">${icon('hand')}<span>손 도구</span></button><button class="icon-button" data-action="zoom-out" aria-label="축소">${icon('close')}</button><span class="active-label">${Math.round(state.zoom * 100)}%</span><button class="icon-button" data-action="zoom-in" aria-label="확대">${icon('plus')}</button><button class="icon-button" data-action="fit-page" aria-label="페이지 맞춤">${icon('fit')}</button>`;
    } else if (tool === 'laser') {
      html = `<button class="tool-name-button is-active">${icon('laser')}<span>레이저 포인터</span></button><span class="active-divider"></span><span class="active-label">표시는 저장되지 않습니다</span>`;
    } else if (tool === 'math') {
      html = `<button class="tool-name-button is-active" data-action="open-math">${icon('math')}<span>수학 계산</span></button><span class="active-divider"></span><span class="active-label">페이지를 탭하거나 식 입력</span>`;
    } else if (tool === 'image') {
      html = `<button class="tool-name-button is-active" data-action="choose-image">${icon('image')}<span>이미지 선택</span></button><span class="active-divider"></span><span class="active-label">사진을 페이지에 삽입</span>`;
    }
    menu.innerHTML = html || `<span class="active-label">도구 설정</span>`;
    injectIcons(menu);
    const dock = $('#activeToolDock');
    dock.className = `active-tool-dock ${state.dock}`;
  }

  function renderDocumentSearch() {
    const drawer = $('#documentSearch');
    drawer.classList.toggle('is-open', state.searchOpen);
    const input = $('#documentSearchInput');
    const query = normalizeText(input?.value || '');
    const results = $('#documentSearchResults');
    const doc = currentDocument();
    if (!doc || !query) { results.innerHTML = `<div class="empty-sidebar">검색어를 입력하면 손글씨 OCR, PDF, 텍스트, 수식에서 찾습니다.</div>`; return; }
    const matches = [];
    doc.pages.forEach((page, pageIndex) => {
      if (normalizeText(page.title).includes(query)) matches.push({ pageIndex, title: page.title, snippet: '페이지 제목', objectId: null });
      if (page.pdfText && normalizeText(page.pdfText).includes(query)) matches.push({ pageIndex, title: `${pageIndex + 1}페이지 · PDF`, snippet: page.pdfText.slice(0, 220), objectId: null });
      page.objects.forEach((object) => {
        const text = object.text || (object.type === 'math' ? `${object.expression} = ${object.result}` : '');
        if (normalizeText(text).includes(query)) matches.push({ pageIndex, title: `${pageIndex + 1}페이지`, snippet: text.slice(0, 180), objectId: object.id });
      });
    });
    results.innerHTML = matches.length ? matches.map((match) => `<button class="search-result" data-action="search-result" data-page-index="${match.pageIndex}" data-object-id="${match.objectId || ''}"><strong>${escapeHtml(match.title)}</strong><p>${highlightText(match.snippet, input.value)}</p></button>`).join('') : `<div class="empty-sidebar">검색 결과가 없습니다.</div>`;
  }

  function highlightText(text, query) {
    const safe = escapeHtml(text);
    if (!query) return safe;
    const escapedQuery = String(query).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    try { return safe.replace(new RegExp(escapedQuery, 'gi'), (value) => `<mark>${value}</mark>`); }
    catch { return safe; }
  }

  function setTool(tool, options = {}) {
    if (state.readOnly && tool !== 'hand') state.readOnly = false;
    state.tool = tool;
    if (['pen','highlighter'].includes(tool)) state.lastWritingTool = tool;
    if (!options.keepSelection && tool !== 'lasso') state.selection = null;
    if (tool === 'image' && options.openPicker !== false) $('#imageInput').click();
    renderActiveToolMenu();
    $$('.page-canvas').forEach((canvas) => scheduleRenderPage(Number(canvas.dataset.pageIndex)));
    updateObjectMenu();
  }

  function setZoom(value, anchor = null) {
    const viewport = $('#editorViewport');
    if (!viewport) return;
    const oldZoom = state.zoom;
    const newZoom = clamp(value, .08, 8);
    if (Math.abs(newZoom - oldZoom) < .001) return;
    lockZoomAftermath();
    const point = viewportClientPoint(anchor, viewport);
    const zoomAnchor = captureZoomAnchor(anchor, state.currentPageIndex);
    const contentX = (viewport.scrollLeft + point.viewportX) / oldZoom;
    const contentY = (viewport.scrollTop + point.viewportY) / oldZoom;
    state.zoom = newZoom;
    updatePageSizing({ render: false });
    trimImageCache();
    if (!restoreZoomAnchor(zoomAnchor)) {
      viewport.scrollLeft = Math.max(0, contentX * newZoom - point.viewportX);
      viewport.scrollTop = Math.max(0, contentY * newZoom - point.viewportY);
    }
    scheduleZoomRender();
    renderActiveToolMenu();
    updateObjectMenu();
  }

  function fitPage() {
    state.zoom = 1;
    updatePageSizing();
    scrollToPage(state.currentPageIndex);
    renderActiveToolMenu();
  }

  function eventPoint(event, canvas) {
    const rect = canvas.getBoundingClientRect();
    const nativeStylus = recentNativeStylus(event);
    const pointerType = effectivePointerType(event);
    const pressure = Number.isFinite(nativeStylus?.pressure) ? nativeStylus.pressure : event.pressure;
    const tiltX = Number(event.tiltX || 0), tiltY = Number(event.tiltY || 0);
    return {
      x: clamp((event.clientX - rect.left) / Math.max(1, rect.width) * PAGE_WIDTH, 0, PAGE_WIDTH),
      y: clamp((event.clientY - rect.top) / Math.max(1, rect.height) * PAGE_HEIGHT, 0, PAGE_HEIGHT),
      p: pointerType === 'mouse' ? .56 : pointPressure({ p: pressure }, .42),
      tx: tiltX,
      ty: tiltY,
      azimuth: Number.isFinite(event.azimuthAngle) ? event.azimuthAngle : Math.atan2(tiltY, tiltX || .00001),
      altitude: Number.isFinite(event.altitudeAngle) ? event.altitudeAngle : Math.PI / 2 - Math.hypot(tiltX, tiltY) * Math.PI / 180,
      twist: Number(event.twist || 0),
      t: Number(event.timeStamp || performance.now()),
      predicted: false
    };
  }

  function eventScreenPoint(event) {
    return {
      x: Number.isFinite(event.clientX) ? event.clientX : 0,
      y: Number.isFinite(event.clientY) ? event.clientY : 0,
      t: Number(event.timeStamp || performance.now())
    };
  }

  function screenToPageDistance(pageIndex, pixels) {
    const canvas = $(`.page-canvas[data-page-index="${pageIndex}"]`);
    const rect = canvas?.getBoundingClientRect();
    if (rect?.width > 0) return pixels / rect.width * PAGE_WIDTH;
    return pixels / Math.max(.1, state.zoom || 1);
  }

  function screenToolWidthToPage(pageIndex, width) {
    return Math.max(.25, screenToPageDistance(pageIndex, Math.max(.5, Number(width) || 1)));
  }

  function constrainToRuler(point) {
    if (!state.ruler.visible) return point;
    const cx = state.ruler.x ?? PAGE_WIDTH / 2, cy = state.ruler.y;
    const dx = point.x - cx, dy = point.y - cy;
    const ux = Math.cos(state.ruler.angle), uy = Math.sin(state.ruler.angle);
    const projection = dx * ux + dy * uy;
    return { ...point, x: cx + projection * ux, y: cy + projection * uy };
  }

  function rulerHitTest(point) {
    if (!state.ruler.visible) return false;
    const cx = state.ruler.x ?? PAGE_WIDTH / 2, cy = state.ruler.y;
    const dx = point.x - cx, dy = point.y - cy;
    const cos = Math.cos(-state.ruler.angle), sin = Math.sin(-state.ruler.angle);
    const localX = dx * cos - dy * sin;
    const localY = dx * sin + dy * cos;
    return Math.abs(localX) <= 430 && Math.abs(localY) <= 36;
  }

  function isStylusEraser(event) {
    const nativeStylus = recentNativeStylus(event);
    const nativeButtons = Number(nativeStylus?.buttonState || 0);
    return effectivePointerType(event) === 'pen' && (
      event.button === 2 ||
      event.button === 5 ||
      (event.buttons & 2) !== 0 ||
      (event.buttons & 32) !== 0 ||
      (event.buttons & 64) !== 0 ||
      (nativeButtons & 32) !== 0 ||
      (nativeButtons & 64) !== 0 ||
      !!nativeStylus?.primaryButton ||
      !!nativeStylus?.secondaryButton ||
      !!nativeStylus?.barrelButton ||
      Number(nativeStylus?.toolType) === 4 ||
      !!nativeStylus?.eraser
    );
  }

  function selectedObjects() {
    const page = currentDocument()?.pages?.[state.selection?.pageIndex];
    if (!page || !state.selection) return [];
    return page.objects.filter((object) => state.selection.ids.includes(object.id));
  }

  function selectionContains(point) {
    const bounds = selectionBounds(selectedObjects());
    return !!bounds && point.x >= bounds.x && point.x <= bounds.x + bounds.w && point.y >= bounds.y && point.y <= bounds.y + bounds.h;
  }

  function selectionHandleAt(point) {
    const bounds = selectionBounds(selectedObjects());
    if (!bounds) return null;
    const handles = [
      ['nw', bounds.x, bounds.y],
      ['ne', bounds.x + bounds.w, bounds.y],
      ['sw', bounds.x, bounds.y + bounds.h],
      ['se', bounds.x + bounds.w, bounds.y + bounds.h]
    ];
    const radius = Math.max(16, 11 / Math.max(.55, state.zoom || 1));
    const match = handles.find(([, x, y]) => Math.hypot(point.x - x, point.y - y) <= radius);
    return match ? match[0] : null;
  }

  function normalizeResizeBounds(anchor, point) {
    const x = clamp(Math.min(anchor.x, point.x), -PAGE_WIDTH * .25, PAGE_WIDTH * 1.25);
    const y = clamp(Math.min(anchor.y, point.y), -PAGE_HEIGHT * .25, PAGE_HEIGHT * 1.25);
    const right = clamp(Math.max(anchor.x, point.x), -PAGE_WIDTH * .25, PAGE_WIDTH * 1.25);
    const bottom = clamp(Math.max(anchor.y, point.y), -PAGE_HEIGHT * .25, PAGE_HEIGHT * 1.25);
    return { x, y, w: Math.max(18, right - x), h: Math.max(18, bottom - y) };
  }

  function transformCoordinate(point, from, to) {
    const sx = to.w / Math.max(1, from.w);
    const sy = to.h / Math.max(1, from.h);
    return { ...point, x: to.x + (point.x - from.x) * sx, y: to.y + (point.y - from.y) * sy };
  }

  function transformObjectFromBounds(object, from, to) {
    if (object.locked) return;
    if (object.type === 'stroke') {
      object.points = object.points.map((point) => transformCoordinate(point, from, to));
      return;
    }
    if (object.type === 'shape' || object.type === 'tape') {
      const p1 = transformCoordinate({ x: object.x1, y: object.y1 }, from, to);
      const p2 = transformCoordinate({ x: object.x2, y: object.y2 }, from, to);
      object.x1 = p1.x; object.y1 = p1.y; object.x2 = p2.x; object.y2 = p2.y;
      if (Number.isFinite(object.cx) && Number.isFinite(object.cy)) {
        const control = transformCoordinate({ x: object.cx, y: object.cy }, from, to);
        object.cx = control.x; object.cy = control.y;
      }
      return;
    }
    const b = computeBounds(object);
    const next = transformCoordinate({ x: b.x, y: b.y }, from, to);
    const sx = to.w / Math.max(1, from.w);
    const sy = to.h / Math.max(1, from.h);
    object.x = next.x;
    object.y = next.y;
    object.w = Math.max(18, b.w * sx);
    object.h = Math.max(18, b.h * sy);
    if (object.type === 'sticker') object.fontSize = Math.max(18, (object.fontSize || 84) * Math.min(sx, sy));
  }

  function applySelectionResize(session, point) {
    const nextBounds = normalizeResizeBounds(session.anchor, point);
    session.objects.forEach((object) => {
      const live = currentDocument()?.pages?.[session.pageIndex]?.objects?.find((item) => item.id === object.id);
      if (!live) return;
      Object.assign(live, deepClone(object));
      transformObjectFromBounds(live, session.startBounds, nextBounds);
    });
    session.resized = true;
  }

  function distanceToSegment(point, a, b) {
    const dx = b.x - a.x, dy = b.y - a.y;
    if (Math.abs(dx) + Math.abs(dy) < .001) return distance(point, a);
    const t = clamp(((point.x - a.x) * dx + (point.y - a.y) * dy) / (dx * dx + dy * dy), 0, 1);
    return distance(point, { x: a.x + dx * t, y: a.y + dy * t });
  }

  function shapeVertices(object) {
    const x = Math.min(object.x1, object.x2), y = Math.min(object.y1, object.y2);
    const w = Math.abs(object.x2 - object.x1), h = Math.abs(object.y2 - object.y1);
    const cx = x + w / 2, cy = y + h / 2;
    if (object.shape === 'triangle') return [0, 1, 2].map((index) => {
      const angle = -Math.PI / 2 + index * Math.PI * 2 / 3;
      return { x: cx + Math.cos(angle) * w / 2, y: cy + Math.sin(angle) * h / 2 };
    });
    if (object.shape === 'diamond') return [{ x: cx, y }, { x: x + w, y: cy }, { x: cx, y: y + h }, { x, y: cy }];
    if (object.shape === 'pentagon' || object.shape === 'hexagon') {
      const sides = object.shape === 'pentagon' ? 5 : 6;
      return Array.from({ length: sides }, (_, index) => {
        const angle = (object.shape === 'hexagon' ? 0 : -Math.PI / 2) + index * Math.PI * 2 / sides;
        return { x: cx + Math.cos(angle) * w / 2, y: cy + Math.sin(angle) * h / 2 };
      });
    }
    return null;
  }

  function shapeIntersectsPoint(object, point, radius) {
    const bounds = computeBounds(object);
    const tolerance = radius + (object.width || 4) * 1.25;
    const filled = object.fill && object.fill !== 'transparent';
    if (filled && point.x >= bounds.x && point.x <= bounds.x + bounds.w && point.y >= bounds.y && point.y <= bounds.y + bounds.h) return true;
    if (['line', 'arrow', 'double-arrow', 'curve'].includes(object.shape)) {
      const control = Number.isFinite(object.cx) ? { x: object.cx, y: object.cy } : null;
      if (!control) return distanceToSegment(point, { x: object.x1, y: object.y1 }, { x: object.x2, y: object.y2 }) <= tolerance;
      return distanceToSegment(point, { x: object.x1, y: object.y1 }, control) <= tolerance ||
        distanceToSegment(point, control, { x: object.x2, y: object.y2 }) <= tolerance;
    }
    if (['rectangle', 'square', 'rounded-rectangle'].includes(object.shape)) {
      const left = Math.abs(point.x - bounds.x), right = Math.abs(point.x - (bounds.x + bounds.w));
      const top = Math.abs(point.y - bounds.y), bottom = Math.abs(point.y - (bounds.y + bounds.h));
      const inX = point.x >= bounds.x - tolerance && point.x <= bounds.x + bounds.w + tolerance;
      const inY = point.y >= bounds.y - tolerance && point.y <= bounds.y + bounds.h + tolerance;
      return (inY && Math.min(left, right) <= tolerance) || (inX && Math.min(top, bottom) <= tolerance);
    }
    if (['circle', 'ellipse'].includes(object.shape)) {
      const rx = Math.max(1, bounds.w / 2), ry = Math.max(1, bounds.h / 2);
      const cx = bounds.x + rx, cy = bounds.y + ry;
      const normalized = Math.hypot((point.x - cx) / rx, (point.y - cy) / ry);
      return Math.abs(normalized - 1) * Math.min(rx, ry) <= tolerance;
    }
    const vertices = shapeVertices(object);
    if (vertices?.length) {
      return vertices.some((vertex, index) => distanceToSegment(point, vertex, vertices[(index + 1) % vertices.length]) <= tolerance);
    }
    return point.x >= bounds.x - tolerance && point.x <= bounds.x + bounds.w + tolerance && point.y >= bounds.y - tolerance && point.y <= bounds.y + bounds.h + tolerance;
  }

  function translateObject(object, dx, dy) {
    if (object.locked) return;
    if (object.type === 'stroke') object.points.forEach((point) => { point.x += dx; point.y += dy; });
    else if (object.type === 'shape' || object.type === 'tape') { object.x1 += dx; object.x2 += dx; object.y1 += dy; object.y2 += dy; if (Number.isFinite(object.cx)) object.cx += dx; if (Number.isFinite(object.cy)) object.cy += dy; }
    else { object.x += dx; object.y += dy; }
  }

  function objectsIntersectingPoint(page, point, radius) {
    const candidates = [];
    for (const object of page.objects) {
      if (object.locked) continue;
      const bounds = computeBounds(object);
      if (point.x + radius < bounds.x || point.x - radius > bounds.x + bounds.w || point.y + radius < bounds.y || point.y - radius > bounds.y + bounds.h) continue;
      if (object.type === 'stroke') {
        if (object.points.some((sample) => distance(sample, point) <= radius + (object.width || 4))) candidates.push(object);
      } else if (object.type === 'shape') {
        if (shapeIntersectsPoint(object, point, radius)) candidates.push(object);
      } else candidates.push(object);
    }
    return candidates;
  }

  function splitStrokeByEraser(stroke, point, radius) {
    const runs = [];
    let run = [];
    for (const sample of stroke.points) {
      if (distance(sample, point) > radius + (stroke.width || 4) * .45) run.push(sample);
      else if (run.length) { if (run.length > 1) runs.push(run); run = []; }
    }
    if (run.length > 1) runs.push(run);
    return runs.map((points) => ({ ...deepClone(stroke), id: uid('stroke'), points }));
  }

  function eraseAt(pageIndex, point, overrideRadius = null) {
    const page = currentDocument()?.pages?.[pageIndex];
    if (!page) return false;
    if (state.eraserMode === 'whole') {
      if (!page.objects.length) return false;
      page.objects = [];
      return true;
    }
    const radius = Number.isFinite(overrideRadius) ? overrideRadius : screenToolWidthToPage(pageIndex, state.eraserRadius);
    const candidates = objectsIntersectingPoint(page, point, radius);
    if (!candidates.length) return false;
    if (state.eraserMode === 'stroke') {
      const ids = new Set(candidates.map((item) => item.id));
      page.objects = page.objects.filter((object) => !ids.has(object.id));
    } else {
      const candidateIds = new Set(candidates.map((item) => item.id));
      const next = [];
      for (const object of page.objects) {
        if (!candidateIds.has(object.id)) next.push(object);
        else if (object.type === 'stroke') next.push(...splitStrokeByEraser(object, point, radius));
      }
      page.objects = next;
    }
    state.selection = null;
    return true;
  }

  function strokeMetrics(points) {
    if (!points?.length) return { length: 0, bounds: { x: 0, y: 0, w: 0, h: 0 }, reversals: 0, axisReversals: 0, closure: 0 };
    const xs = points.map((point) => point.x), ys = points.map((point) => point.y);
    const bounds = { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) };
    let length = 0, reversals = 0, axisReversals = 0, previousAngle = null, previousXSign = 0, previousYSign = 0;
    for (let i = 1; i < points.length; i++) {
      const previous = points[i - 1], current = points[i];
      const dx = current.x - previous.x, dy = current.y - previous.y;
      length += Math.hypot(dx, dy);
      const angle = segmentDirection(previous, current);
      if (previousAngle != null) {
        let diff = Math.abs(angle - previousAngle);
        if (diff > Math.PI) diff = Math.PI * 2 - diff;
        if (diff > Math.PI * .72) reversals++;
      }
      const xSign = Math.abs(dx) >= 1.4 ? Math.sign(dx) : 0;
      const ySign = Math.abs(dy) >= 1.4 ? Math.sign(dy) : 0;
      if (xSign) {
        if (previousXSign && previousXSign !== xSign) axisReversals++;
        previousXSign = xSign;
      }
      if (ySign) {
        if (previousYSign && previousYSign !== ySign) axisReversals++;
        previousYSign = ySign;
      }
      previousAngle = angle;
    }
    return { length, bounds, reversals, axisReversals, closure: distance(points[0], points[points.length - 1]) };
  }

  function segmentOrientation(a, b, c) {
    return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
  }

  function segmentsCross(a, b, c, d) {
    const ab = distance(a, b), cd = distance(c, d);
    if (ab < .001 || cd < .001) return false;
    const o1 = segmentOrientation(a, b, c);
    const o2 = segmentOrientation(a, b, d);
    const o3 = segmentOrientation(c, d, a);
    const o4 = segmentOrientation(c, d, b);
    return o1 * o2 < 0 && o3 * o4 < 0;
  }

  function pathSelfIntersections(points, maxCount = 24) {
    if (!points || points.length < 5) return 0;
    let count = 0;
    for (let i = 1; i < points.length; i++) {
      const a = points[i - 1], b = points[i];
      for (let j = i + 2; j < points.length; j++) {
        if (i === 1 && j === points.length - 1) continue;
        const c = points[j - 1], d = points[j];
        if (segmentsCross(a, b, c, d) && ++count >= maxCount) return count;
      }
    }
    return count;
  }

  function pathRevisitProfile(points, cellSize = 18) {
    const seen = new Set();
    let revisits = 0, previous = '';
    for (const point of points || []) {
      const key = `${Math.floor(point.x / cellSize)}:${Math.floor(point.y / cellSize)}`;
      if (key === previous) continue;
      if (seen.has(key)) revisits++;
      seen.add(key);
      previous = key;
    }
    return { revisits, cells: seen.size };
  }

  function latinRLikeGesture(points, metrics, lengthRatio, revisits) {
    if (!points || points.length < 7) return false;
    const bounds = metrics.bounds;
    if (bounds.w < 12 || bounds.h < 18) return false;
    const aspect = bounds.h / Math.max(1, bounds.w);
    if (aspect < .78 || aspect > 3.4) return false;
    const leftLimit = bounds.x + bounds.w * .44;
    let verticalLength = 0, verticalMinY = Infinity, verticalMaxY = -Infinity;
    for (let i = 1; i < points.length; i++) {
      const a = points[i - 1], b = points[i];
      const dx = b.x - a.x, dy = b.y - a.y;
      const length = Math.hypot(dx, dy);
      if (length < 2) continue;
      const midX = (a.x + b.x) / 2;
      if (midX <= leftLimit && Math.abs(dy) > Math.abs(dx) * 1.55) {
        verticalLength += length;
        verticalMinY = Math.min(verticalMinY, a.y, b.y);
        verticalMaxY = Math.max(verticalMaxY, a.y, b.y);
      }
    }
    const verticalSpan = verticalMaxY - verticalMinY;
    const hasStem = verticalLength >= bounds.h * .48 && verticalSpan >= bounds.h * .58;
    if (!hasStem) return false;
    const upperRight = points.some((point) => point.x >= bounds.x + bounds.w * .48 && point.y <= bounds.y + bounds.h * .5);
    const midReturn = points.some((point) => point.x <= bounds.x + bounds.w * .62 && point.y >= bounds.y + bounds.h * .34 && point.y <= bounds.y + bounds.h * .72);
    const lowerLeg = points.slice(Math.floor(points.length * .42)).some((point) => point.x >= bounds.x + bounds.w * .56 && point.y >= bounds.y + bounds.h * .58);
    const end = points[points.length - 1];
    const endsLikeLeg = end.x >= bounds.x + bounds.w * .5 && end.y >= bounds.y + bounds.h * .55;
    const notHeavyScrub = lengthRatio < 5.6 && metrics.axisReversals <= 10 && revisits <= 12;
    return upperRight && midReturn && (lowerLeg || endsLikeLeg) && notHeavyScrub;
  }

  function scribbleGestureProfile(points) {
    const metrics = strokeMetrics(points);
    const diagonal = Math.hypot(metrics.bounds.w, metrics.bounds.h);
    const maxDimension = Math.max(metrics.bounds.w, metrics.bounds.h);
    const minDimension = Math.min(metrics.bounds.w, metrics.bounds.h);
    const lengthRatio = metrics.length / Math.max(1, diagonal);
    const intersections = pathSelfIntersections(points);
    const revisit = pathRevisitProfile(points, clamp(diagonal / 7, 12, 24));
    if (latinRLikeGesture(points, metrics, lengthRatio, revisit.revisits)) {
      return { metrics, diagonal, intersections, revisits: revisit.revisits, dense: false, local: false, glyphGuard: 'latin-r' };
    }
    const compact = maxDimension < 560 && minDimension > 8 && diagonal > 24;
    const localCompact = maxDimension <= 180 && minDimension >= 4 && diagonal >= 12;
    const openGesture = metrics.closure > diagonal * .46;
    const scrubbedBackAndForth = metrics.axisReversals >= 4 && metrics.reversals >= 2 && lengthRatio > 2.25;
    const crossedOver = intersections >= 2 && lengthRatio > 1.85;
    const repeatedArea = revisit.revisits >= 4 && metrics.reversals >= 2 && lengthRatio > 2.15;
    const loopedScrub = metrics.axisReversals >= 6 && revisit.revisits >= 5 && lengthRatio > 3;
    const broadDense = compact && metrics.length > Math.max(128, diagonal * 2.45) && (scrubbedBackAndForth || crossedOver || repeatedArea || loopedScrub);
    const localDense = localCompact && metrics.length > Math.max(58, diagonal * 2.05) && (scrubbedBackAndForth || crossedOver || repeatedArea || loopedScrub) && (openGesture || intersections || revisit.revisits >= 3);
    return { metrics, diagonal, intersections, revisits: revisit.revisits, dense: broadDense || localDense, local: localDense && !broadDense };
  }

  function pointNearPath(point, path, radius) {
    if (!path?.length) return false;
    if (path.some((sample) => distance(point, sample) <= radius)) return true;
    for (let index = 1; index < path.length; index++) {
      if (distanceToSegment(point, path[index - 1], path[index]) <= radius) return true;
    }
    return false;
  }

  function pathNearPath(a, b, radius) {
    return a?.some((point) => pointNearPath(point, b, radius)) || b?.some((point) => pointNearPath(point, a, radius));
  }

  function objectIntersectsScribble(object, points, expanded, hitRadius) {
    const bounds = computeBounds(object);
    const overlapsBox = bounds.x < expanded.x + expanded.w && bounds.x + bounds.w > expanded.x && bounds.y < expanded.y + expanded.h && bounds.y + bounds.h > expanded.y;
    if (!overlapsBox) return false;
    if (object.type === 'stroke') {
      return pathNearPath(object.points || [], points, hitRadius + (object.width || 4) * .55);
    }
    if (object.type === 'shape') return points.some((point) => shapeIntersectsPoint(object, point, hitRadius));
    return points.some((point) => point.x >= bounds.x - hitRadius && point.x <= bounds.x + bounds.w + hitRadius && point.y >= bounds.y - hitRadius && point.y <= bounds.y + bounds.h + hitRadius);
  }

  function maybeScribbleErase(pageIndex, points, screenPoints = points) {
    if (!state.settings.scribbleErase || points.length < 8) return false;
    const gesture = scribbleGestureProfile(screenPoints);
    if (!gesture.dense) return false;
    const page = currentDocument()?.pages?.[pageIndex];
    if (!page) return false;
    const metrics = strokeMetrics(points);
    const hitRadius = Math.max(6, screenToPageDistance(pageIndex, gesture.local ? 14 : 10));
    const margin = hitRadius + Math.max(4, screenToPageDistance(pageIndex, 8));
    const expanded = { x: metrics.bounds.x - margin, y: metrics.bounds.y - margin, w: metrics.bounds.w + margin * 2, h: metrics.bounds.h + margin * 2 };
    const ids = page.objects.filter((object) => objectIntersectsScribble(object, points, expanded, hitRadius)).map((object) => object.id);
    if (!ids.length) return false;
    checkpoint('scribble-erase');
    page.objects = page.objects.filter((object) => !ids.includes(object.id));
    state.selection = null;
    toast(`${ids.length}개 항목을 지웠습니다.`);
    return true;
  }

  function perpendicularDistance(point, lineStart, lineEnd) {
    const dx = lineEnd.x - lineStart.x, dy = lineEnd.y - lineStart.y;
    if (Math.abs(dx) + Math.abs(dy) < .001) return distance(point, lineStart);
    const t = clamp(((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy) / (dx * dx + dy * dy), 0, 1);
    return distance(point, { x: lineStart.x + dx * t, y: lineStart.y + dy * t });
  }

  function simplifyPath(points, epsilon) {
    if (!points || points.length < 3) return points ? [...points] : [];
    let maxDistance = 0, index = 0;
    for (let i = 1; i < points.length - 1; i++) {
      const value = perpendicularDistance(points[i], points[0], points[points.length - 1]);
      if (value > maxDistance) { maxDistance = value; index = i; }
    }
    if (maxDistance <= epsilon) return [points[0], points[points.length - 1]];
    const left = simplifyPath(points.slice(0, index + 1), epsilon);
    const right = simplifyPath(points.slice(index), epsilon);
    return left.slice(0, -1).concat(right);
  }

  function polygonVertices(points, diagonal) {
    if (!points?.length) return [];
    const metrics = strokeMetrics(points);
    const cx = metrics.bounds.x + metrics.bounds.w / 2, cy = metrics.bounds.y + metrics.bounds.h / 2;
    let startIndex = 0, farthest = -1;
    points.forEach((point, index) => {
      const value = Math.hypot(point.x - cx, point.y - cy);
      if (value > farthest) { farthest = value; startIndex = index; }
    });
    const rotated = points.slice(startIndex).concat(points.slice(0, startIndex));
    const loop = rotated.concat([{ ...rotated[0] }]);
    const simplified = simplifyPath(loop, Math.max(5, diagonal * .035));
    const vertices = simplified.slice(0, -1);
    return vertices.filter((point, index) => index === 0 || distance(point, vertices[index - 1]) > diagonal * .08);
  }

  function angleAt(a, b, c) {
    const ab = Math.atan2(a.y - b.y, a.x - b.x), cb = Math.atan2(c.y - b.y, c.x - b.x);
    let value = Math.abs(ab - cb);
    if (value > Math.PI) value = Math.PI * 2 - value;
    return value;
  }

  function rectangleLikeShape(points, metrics, diagonal, vertices) {
    if (!points?.length || diagonal < 28 || metrics.closure > diagonal * .42) return null;
    if (vertices.length < 4 || vertices.length > 7) return null;
    const left = metrics.bounds.x, right = metrics.bounds.x + metrics.bounds.w;
    const top = metrics.bounds.y, bottom = metrics.bounds.y + metrics.bounds.h;
    if (metrics.bounds.w < diagonal * .18 || metrics.bounds.h < diagonal * .18) return null;
    const tolerance = Math.max(7, diagonal * .05);
    const sideHits = { left: 0, right: 0, top: 0, bottom: 0 };
    let totalEdgeDistance = 0;
    for (const point of points) {
      const distances = [
        ['left', Math.abs(point.x - left)],
        ['right', Math.abs(point.x - right)],
        ['top', Math.abs(point.y - top)],
        ['bottom', Math.abs(point.y - bottom)]
      ].sort((a, b) => a[1] - b[1]);
      totalEdgeDistance += distances[0][1];
      if (distances[0][1] <= tolerance) sideHits[distances[0][0]]++;
    }
    const averageEdgeDistance = totalEdgeDistance / Math.max(1, points.length);
    const minimumSideHits = Math.max(2, Math.floor(points.length * .055));
    const allSidesPresent = Object.values(sideHits).every((count) => count >= minimumSideHits);
    if (!allSidesPresent || averageEdgeDistance > Math.max(5.5, diagonal * .038)) return null;
    const angles = vertices.map((vertex, index) => angleAt(vertices[(index + vertices.length - 1) % vertices.length], vertex, vertices[(index + 1) % vertices.length]));
    const rightish = angles.filter((value) => Math.abs(value - Math.PI / 2) < .62).length;
    if (vertices.length <= 5 && rightish < 3) return null;
    const aspect = metrics.bounds.w / Math.max(1, metrics.bounds.h);
    return aspect > .82 && aspect < 1.22 ? 'square' : 'rectangle';
  }

  function regularPolygonLike(vertices, sides, metrics, diagonal, radialDeviation, loopRatio) {
    if (vertices.length !== sides || diagonal < 70) return false;
    if (metrics.closure > diagonal * .24 || loopRatio < 2.15 || loopRatio > 5.3) return false;
    const cx = metrics.bounds.x + metrics.bounds.w / 2, cy = metrics.bounds.y + metrics.bounds.h / 2;
    const lengths = vertices.map((point, index) => distance(point, vertices[(index + 1) % vertices.length]));
    const meanLength = lengths.reduce((sum, value) => sum + value, 0) / lengths.length;
    const sideSpread = Math.max(...lengths.map((value) => Math.abs(value - meanLength))) / Math.max(1, meanLength);
    const expectedAngle = (sides - 2) * Math.PI / sides;
    const angles = vertices.map((vertex, index) => angleAt(vertices[(index + vertices.length - 1) % sides], vertex, vertices[(index + 1) % sides]));
    const angleSpread = Math.max(...angles.map((value) => Math.abs(value - expectedAngle)));
    const radii = vertices.map((point) => Math.hypot(point.x - cx, point.y - cy));
    const meanRadius = radii.reduce((sum, value) => sum + value, 0) / radii.length;
    const radiusSpread = Math.max(...radii.map((value) => Math.abs(value - meanRadius))) / Math.max(1, meanRadius);
    return sideSpread < .32 && angleSpread < .58 && radiusSpread < .3 && radialDeviation < .46;
  }

  function looksLikeArrow(points, diagonal) {
    const simplified = simplifyPath(points, Math.max(5, diagonal * .025));
    if (simplified.length < 5 || simplified.length > 8) return null;
    for (let i = 1; i < simplified.length - 2; i++) {
      const head = simplified[i];
      const repeated = simplified.slice(i + 1, -1).findIndex((point) => distance(point, head) < diagonal * .11);
      if (repeated < 0) continue;
      const repeatIndex = i + 1 + repeated;
      const wingA = simplified[i + 1], wingB = simplified[repeatIndex + 1];
      if (!wingB) continue;
      const shaft = distance(simplified[0], head);
      const wing1 = distance(head, wingA), wing2 = distance(head, wingB);
      if (shaft > Math.max(wing1, wing2) * 2.2 && wing1 > diagonal * .08 && wing2 > diagonal * .08) {
        return { shape: 'arrow', x1: simplified[0].x, y1: simplified[0].y, x2: head.x, y2: head.y };
      }
    }
    return null;
  }

  function classifyClosedShape(points, metrics, diagonal) {
    const cx = metrics.bounds.x + metrics.bounds.w / 2, cy = metrics.bounds.y + metrics.bounds.h / 2;
    const radial = points.map((point) => Math.hypot((point.x - cx) / Math.max(1, metrics.bounds.w), (point.y - cy) / Math.max(1, metrics.bounds.h)));
    const radialMean = radial.reduce((sum, value) => sum + value, 0) / Math.max(1, radial.length);
    const radialVariance = radial.reduce((sum, value) => sum + (value - radialMean) ** 2, 0) / Math.max(1, radial.length);
    const radialDeviation = Math.sqrt(radialVariance) / Math.max(.001, radialMean);
    const vertices = polygonVertices(points, diagonal);
    const aspect = metrics.bounds.w / Math.max(1, metrics.bounds.h);
    const loopRatio = metrics.length / diagonal;
    const ellipseLike = radialDeviation < .16 && loopRatio > 2.35 && loopRatio < 5.15;
    const strongEllipse = radialDeviation < .13 && loopRatio > 2.45 && loopRatio < 4.65;
    if (regularPolygonLike(vertices, 5, metrics, diagonal, radialDeviation, loopRatio)) return 'pentagon';
    if (regularPolygonLike(vertices, 6, metrics, diagonal, radialDeviation, loopRatio)) return 'hexagon';
    const rectangleLike = rectangleLikeShape(points, metrics, diagonal, vertices);
    if (rectangleLike) return rectangleLike;

    if (vertices.length >= 8 && vertices.length <= 13 && radialDeviation > .18) return 'starshape';
    if (strongEllipse && vertices.length >= 5) return aspect > .82 && aspect < 1.22 ? 'circle' : 'ellipse';
    if (ellipseLike && vertices.length > 5) return aspect > .82 && aspect < 1.22 ? 'circle' : 'ellipse';
    // Classify clear polygon corners before radial ellipse tests. A wide rectangle can
    // otherwise look deceptively circular after x/y normalization.
    if (vertices.length === 3) return 'triangle';
    if (vertices.length === 4) {
      const angles = vertices.map((vertex, index) => angleAt(vertices[(index + vertices.length - 1) % vertices.length], vertex, vertices[(index + 1) % vertices.length]));
      const rightish = angles.filter((value) => Math.abs(value - Math.PI / 2) < .42).length;
      const top = vertices.reduce((best, point) => point.y < best.y ? point : best, vertices[0]);
      const bottom = vertices.reduce((best, point) => point.y > best.y ? point : best, vertices[0]);
      const left = vertices.reduce((best, point) => point.x < best.x ? point : best, vertices[0]);
      const right = vertices.reduce((best, point) => point.x > best.x ? point : best, vertices[0]);
      const axisDiamond = Math.abs(top.x - cx) < metrics.bounds.w * .22 && Math.abs(bottom.x - cx) < metrics.bounds.w * .22 && Math.abs(left.y - cy) < metrics.bounds.h * .22 && Math.abs(right.y - cy) < metrics.bounds.h * .22;
      if ((ellipseLike || radialDeviation < .18) && rightish < 3) return aspect > .82 && aspect < 1.22 ? 'circle' : 'ellipse';
      if (axisDiamond && rightish < 3) return 'diamond';
      return aspect > .82 && aspect < 1.22 ? 'square' : 'rectangle';
    }
    if (radialDeviation < .19 && loopRatio > 2.35 && loopRatio < 4.9) return aspect > .82 && aspect < 1.22 ? 'circle' : 'ellipse';
    return radialDeviation < .3 ? (aspect > .82 && aspect < 1.22 ? 'circle' : 'ellipse') : null;
  }

  function maybeShapeFromStroke(points, duration, holdDuration = 0, screenPoints = points) {
    if (!state.settings.drawHold || points.length < 4) return null;
    const screenMetrics = strokeMetrics(screenPoints);
    const screenDiagonal = Math.max(1, Math.hypot(screenMetrics.bounds.w, screenMetrics.bounds.h));
    if (screenDiagonal < 18 || scribbleGestureProfile(screenPoints).dense) return null;
    const metrics = strokeMetrics(points);
    const diagonal = Math.max(1, Math.hypot(metrics.bounds.w, metrics.bounds.h));
    if (diagonal < 18) return null;
    const maxPressure = points.reduce((best, point) => Math.max(best, Number(point.p || 0)), 0);
    const requiredHold = maxPressure >= .72 ? Math.max(540, SHAPE_HOLD_MS * .66) : SHAPE_HOLD_MS;
    const highConfidence = holdDuration >= requiredHold;
    if (!highConfidence) return null;
    const direct = distance(points[0], points[points.length - 1]);
    const straightness = direct / Math.max(1, metrics.length);
    const obviousStroke = duration > 80 || screenDiagonal > 42;
    if (straightness > (highConfidence ? .84 : obviousStroke ? .92 : .965)) return { shape: 'line', x1: points[0].x, y1: points[0].y, x2: points[points.length - 1].x, y2: points[points.length - 1].y, autoConfidence: straightness };
    if (metrics.closure < diagonal * (highConfidence ? .34 : obviousStroke ? .24 : .15) && metrics.length / diagonal > 1.85 && metrics.length / diagonal < 9.1) {
      const shape = classifyClosedShape(points, metrics, diagonal);
      if (shape) return { shape, x1: metrics.bounds.x, y1: metrics.bounds.y, x2: metrics.bounds.x + metrics.bounds.w, y2: metrics.bounds.y + metrics.bounds.h, autoConfidence: highConfidence ? .94 : .84 };
    }
    if ((highConfidence || (duration > 120 && diagonal > 54)) && metrics.closure > diagonal * .26 && straightness < .91) {
      const simplified = simplifyPath(points, Math.max(4, diagonal * .018));
      if (simplified.length >= 3 && simplified.length <= 11) {
        const first = points[0], last = points[points.length - 1], mid = points[Math.floor(points.length / 2)];
        const chordMid = { x: (first.x + last.x) / 2, y: (first.y + last.y) / 2 };
        if (distance(mid, chordMid) > diagonal * .08) return { shape: 'curve', x1: first.x, y1: first.y, x2: last.x, y2: last.y, cx: mid.x, cy: mid.y, autoConfidence: .8 };
      }
    }
    return null;
  }

  function aspectLockedShape(shape) {
    return ['square', 'circle', 'triangle', 'diamond', 'pentagon', 'hexagon', 'starshape'].includes(shape);
  }

  function ensureAspectLock(session) {
    const object = session?.object;
    if (!object || !aspectLockedShape(object.shape)) return null;
    if (!session.aspectLock) {
      const bounds = computeBounds(object);
      const ratio = bounds.w > 4 && bounds.h > 4 ? bounds.w / Math.max(1, bounds.h) : 1;
      session.aspectLock = {
        ratio: clamp(ratio, .18, 5.5),
        unlocked: false
      };
    }
    return session.aspectLock;
  }

  function aspectLockedPoint(session, point) {
    const lock = ensureAspectLock(session);
    if (!lock?.ratio || lock.unlocked) return point;
    const anchor = session.resizeAnchor || { x: session.object.x1, y: session.object.y1 };
    const dx = point.x - anchor.x, dy = point.y - anchor.y;
    const absDx = Math.abs(dx), absDy = Math.abs(dy);
    if (Math.max(absDx, absDy) < 12) return point;
    const rawRatio = absDx / Math.max(1, absDy);
    const ratioIntent = Math.abs(Math.log(rawRatio / lock.ratio));
    const unlockDistance = Math.max(48, screenToPageDistance(session.pageIndex, 58));
    if (ratioIntent > .42 && Math.hypot(dx, dy) > unlockDistance) {
      lock.unlocked = true;
      return point;
    }
    let width = absDx, height = absDy;
    if (rawRatio > lock.ratio) height = width / lock.ratio;
    else width = height * lock.ratio;
    return {
      ...point,
      x: anchor.x + (dx < 0 ? -width : width),
      y: anchor.y + (dy < 0 ? -height : height)
    };
  }

  function resizeLiveShape(session, point) {
    const object = session.object;
    if (!object) return;
    if (['line', 'arrow', 'double-arrow', 'curve'].includes(object.shape)) {
      object.x2 = point.x;
      object.y2 = point.y;
      if (object.shape === 'curve') updateLiveCurveControl(session);
      return;
    }
    const anchor = session.resizeAnchor || { x: object.x1, y: object.y1 };
    const next = aspectLockedPoint(session, point);
    object.x1 = anchor.x;
    object.y1 = anchor.y;
    object.x2 = next.x;
    object.y2 = next.y;
  }

  function updateLiveCurveControl(session) {
    const object = session.object;
    const dx = object.x2 - object.x1;
    const dy = object.y2 - object.y1;
    const length = Math.hypot(dx, dy);
    const midX = (object.x1 + object.x2) / 2;
    const midY = (object.y1 + object.y2) / 2;
    if (length < 2) {
      object.cx = midX;
      object.cy = midY;
      return;
    }
    if (!Number.isFinite(session.curveBend)) {
      const existing = Number.isFinite(object.cx) && Number.isFinite(object.cy)
        ? ((object.cx - midX) * (-dy / length) + (object.cy - midY) * (dx / length)) / length
        : .22;
      session.curveBend = clamp(Math.abs(existing) > .04 ? existing : .22, -.5, .5);
    }
    const bow = clamp(Math.abs(session.curveBend) * length, 30, 190) * Math.sign(session.curveBend || .22);
    object.cx = midX - dy / length * bow;
    object.cy = midY + dx / length * bow;
  }

  function convertStrokeSessionToLiveShape(session) {
    if (!session || session.kind !== 'stroke' || session.convertedShape || session.object.brush === 'highlighter') return false;
    const points = session.object.points;
    const screenPoints = session.screenPoints || points;
    const duration = performance.now() - session.startedAt;
    const holdDuration = performance.now() - (session.lastMovedAt || session.startedAt);
    const shape = maybeShapeFromStroke(points, duration, Math.max(holdDuration, LIVE_SHAPE_HOLD_MS), screenPoints);
    if (!shape) return false;
    const object = { id: uid('shape'), type: 'shape', ...shape, color: session.object.color, width: session.object.width, createdAt: now() };
    const bounds = computeBounds(object);
    const last = points[points.length - 1] || { x: object.x2, y: object.y2 };
    session.kind = 'shape-adjust';
    session.object = object;
    session.convertedShape = true;
    session.resizeAnchor = {
      x: last.x >= bounds.x + bounds.w / 2 ? bounds.x : bounds.x + bounds.w,
      y: last.y >= bounds.y + bounds.h / 2 ? bounds.y : bounds.y + bounds.h
    };
    ensureAspectLock(session);
    scheduleRenderPage(session.pageIndex);
    return true;
  }

  function scheduleLiveShapeHold(session) {
    if (!session || session.kind !== 'stroke') return;
    clearTimeout(session.holdTimer);
    session.holdTimer = setTimeout(() => {
      if (state.drawSession === session) convertStrokeSessionToLiveShape(session);
    }, LIVE_SHAPE_HOLD_MS);
  }

  function updateStylusTelemetry(event) {
    const node = $('#stylusTelemetry');
    if (!node) return;
    node.hidden = !state.settings.telemetry;
    if (!state.settings.telemetry) return;
    node.textContent = `${event.pointerType || '입력'} · 압력 ${Number(event.pressure || 0).toFixed(2)} · 기울기 ${Math.round(Math.hypot(event.tiltX || 0, event.tiltY || 0))}° · 회전 ${Math.round(event.twist || 0)}°`;
  }

  function touchPointers() {
    return [...state.activePointers.values()].filter((pointer) => pointer.pointerType === 'touch');
  }

  function shouldTouchPan() {
    return state.settings.stylusOnly || state.tool === 'hand' || state.zoom > 1.02;
  }

  function startTouchGesture(event) {
    const viewport = $('#editorViewport');
    const touches = touchPointers();
    if (!viewport || !touches.length) return;
    const center = touches.reduce((acc, item) => ({ x: acc.x + item.clientX / touches.length, y: acc.y + item.clientY / touches.length }), { x: 0, y: 0 });
    const distanceValue = touches.length >= 2 ? distance(touches[0], touches[1]) : 0;
    state.touchGesture = {
      startedAt: performance.now(),
      maxPointers: touches.length,
      startCenter: center,
      lastCenter: center,
      initialDistance: distanceValue,
      initialZoom: state.zoom,
      initialScrollLeft: viewport.scrollLeft,
      initialScrollTop: viewport.scrollTop,
      zoomAnchor: captureZoomAnchor({ clientX: center.x, clientY: center.y }, state.currentPageIndex),
      pinched: false,
      moved: false
    };
  }

  function updateTouchGesture(event) {
    const gesture = state.touchGesture;
    const viewport = $('#editorViewport');
    const touches = touchPointers();
    if (!gesture || !viewport || !touches.length) return;
    gesture.maxPointers = Math.max(gesture.maxPointers, touches.length);
    const center = touches.reduce((acc, item) => ({ x: acc.x + item.clientX / touches.length, y: acc.y + item.clientY / touches.length }), { x: 0, y: 0 });
    const deltaX = center.x - gesture.startCenter.x, deltaY = center.y - gesture.startCenter.y;
    if (Math.hypot(deltaX, deltaY) > 7) gesture.moved = true;
    if (touches.length >= 2) {
      const currentDistance = distance(touches[0], touches[1]);
      if (gesture.initialDistance > 0 && Math.abs(currentDistance - gesture.initialDistance) > 3) {
        const nextZoom = clamp(gesture.initialZoom * currentDistance / gesture.initialDistance, .08, 8);
        gesture.pinched = true;
        lockZoomAftermath();
        const rect = viewport.getBoundingClientRect();
        const anchorX = center.x - rect.left, anchorY = center.y - rect.top;
        const contentX = (gesture.initialScrollLeft + (gesture.startCenter.x - rect.left)) / gesture.initialZoom;
        const contentY = (gesture.initialScrollTop + (gesture.startCenter.y - rect.top)) / gesture.initialZoom;
        const zoomAnchor = retargetZoomAnchor(gesture.zoomAnchor, center.x, center.y);
        state.zoom = nextZoom;
        updatePageSizing({ render: false });
        if (!restoreZoomAnchor(zoomAnchor)) {
          viewport.scrollLeft = Math.max(0, contentX * nextZoom - anchorX);
          viewport.scrollTop = Math.max(0, contentY * nextZoom - anchorY);
        }
        scheduleZoomRender();
      } else if (!gesture.pinched && performance.now() >= state.zoomDragLockUntil) {
        viewport.scrollLeft = gesture.initialScrollLeft - deltaX;
        viewport.scrollTop = gesture.initialScrollTop - deltaY;
      }
    } else if (!gesture.pinched && performance.now() >= state.zoomDragLockUntil && shouldTouchPan()) {
      viewport.scrollLeft = gesture.initialScrollLeft - deltaX;
      viewport.scrollTop = gesture.initialScrollTop - deltaY;
    }
    gesture.lastCenter = center;
  }

  function finishTouchGesture() {
    const gesture = state.touchGesture;
    if (!gesture || touchPointers().length) return;
    const duration = performance.now() - gesture.startedAt;
    const dx = gesture.lastCenter.x - gesture.startCenter.x;
    const dy = gesture.lastCenter.y - gesture.startCenter.y;
    if (!gesture.moved && duration < 320) {
      if (gesture.maxPointers === 2) undo();
      else if (gesture.maxPointers >= 3) redo();
    } else if (!gesture.pinched && performance.now() > state.suppressPageUpdateUntil && gesture.maxPointers >= 2 && Math.abs(dx) > 82 && Math.abs(dx) > Math.abs(dy) * 1.6) {
      scrollToPage(state.currentPageIndex + (dx < 0 ? 1 : -1));
    }
    state.touchGesture = null;
    renderActiveToolMenu();
  }

  function switchStrokeSessionToEraser(session, point) {
    if (!session || session.kind !== 'stroke') return null;
    if (session.holdTimer) clearTimeout(session.holdTimer);
    checkpoint('erase');
    const radius = screenToolWidthToPage(session.pageIndex, state.eraserRadius);
    const nextSession = {
      kind: 'eraser',
      pageIndex: session.pageIndex,
      pointerId: session.pointerId,
      point,
      radius,
      changed: eraseAt(session.pageIndex, point, radius),
      fromStylusButton: true
    };
    state.drawSession = nextSession;
    scheduleRenderPage(session.pageIndex);
    return nextSession;
  }

  function handlePointerDown(event) {
    const canvas = event.target.closest?.('.page-canvas');
    if (!canvas) return;
    event.preventDefault();
    updateStylusTelemetry(event);
    const pointerType = effectivePointerType(event);
    const pageIndex = Number(canvas.dataset.pageIndex);
    if (!Number.isInteger(pageIndex)) return;
    const previousPageIndex = state.currentPageIndex;
    state.currentPageIndex = pageIndex;
    if (previousPageIndex !== state.currentPageIndex) notifyPageChanged(previousPageIndex, 'pointer');
    rememberCurrentPage();
    updatePageIndicator();
    try { canvas.setPointerCapture(event.pointerId); } catch {}
    const pointerRecord = { pointerId: event.pointerId, pointerType, clientX: event.clientX, clientY: event.clientY, x: event.clientX, y: event.clientY, startX: event.clientX, startY: event.clientY };
    state.activePointers.set(event.pointerId, pointerRecord);

    if (pointerType === 'touch') {
      if (!state.touchGesture) startTouchGesture(event);
      else {
        const touches = touchPointers();
        state.touchGesture.maxPointers = Math.max(state.touchGesture.maxPointers, touches.length);
        if (touches.length >= 2 && state.touchGesture.initialDistance === 0) {
          state.touchGesture.initialDistance = distance(touches[0], touches[1]);
          state.touchGesture.initialZoom = state.zoom;
          state.touchGesture.startCenter = { x: (touches[0].clientX + touches[1].clientX) / 2, y: (touches[0].clientY + touches[1].clientY) / 2 };
          state.touchGesture.lastCenter = { ...state.touchGesture.startCenter };
          const viewport = $('#editorViewport');
          state.touchGesture.initialScrollLeft = viewport.scrollLeft;
          state.touchGesture.initialScrollTop = viewport.scrollTop;
          state.touchGesture.zoomAnchor = captureZoomAnchor({ clientX: state.touchGesture.startCenter.x, clientY: state.touchGesture.startCenter.y }, pageIndex);
        }
        if (state.drawSession?.kind === 'stroke') { state.drawSession = null; renderPageCanvas(pageIndex); }
      }
      if (state.settings.stylusOnly || touchPointers().length > 1 || state.tool === 'hand' || state.zoom > 1.02) return;
    }

    const point = eventPoint(event, canvas);
    const effectiveTool = isStylusEraser(event) ? 'eraser' : (state.readOnly ? 'hand' : state.tool);
    const page = currentDocument()?.pages?.[pageIndex];
    if (!page) return;

    if (state.ruler.visible && rulerHitTest(point) && (effectiveTool === 'hand' || pointerType !== 'pen')) {
      state.drawSession = {
        kind: 'move-ruler',
        pageIndex,
        pointerId: event.pointerId,
        startPoint: point,
        startRuler: { x: state.ruler.x ?? PAGE_WIDTH / 2, y: state.ruler.y }
      };
      return;
    }

    if (effectiveTool === 'lasso' && state.selection?.pageIndex === pageIndex) {
      const handle = selectionHandleAt(point);
      if (handle) {
        const startBounds = selectionBounds(selectedObjects());
        if (!startBounds) return;
        const anchor = {
          x: handle.includes('e') ? startBounds.x : startBounds.x + startBounds.w,
          y: handle.includes('s') ? startBounds.y : startBounds.y + startBounds.h
        };
        checkpoint('resize-selection');
        state.drawSession = { kind: 'resize-selection', pageIndex, pointerId: event.pointerId, startBounds, anchor, objects: deepClone(selectedObjects()), resized: false };
        return;
      }
      if (selectionContains(point)) {
        checkpoint('move-selection');
        state.drawSession = { kind: 'move-selection', pageIndex, pointerId: event.pointerId, lastPoint: point, moved: false };
        return;
      }
    }
    if (effectiveTool === 'pen' || effectiveTool === 'highlighter') {
      const brush = effectiveTool === 'highlighter' ? 'highlighter' : state.brush;
      const first = state.ruler.visible ? constrainToRuler(point) : point;
      const screen = eventScreenPoint(event);
      const displayWidth = brush === 'highlighter' ? state.highlighterWidth : state.width;
      const pageWidth = screenToolWidthToPage(pageIndex, displayWidth);
      state.drawSession = {
        kind: 'stroke', pageIndex, startedAt: performance.now(), lastMovedAt: performance.now(), pointerId: event.pointerId,
        screenPoints: [screen], holdScreenPoint: screen,
        object: { id: uid('stroke'), type: 'stroke', brush, color: brush === 'highlighter' ? state.highlighterColor : state.color, width: pageWidth, screenWidth: displayWidth, opacity: brush === 'highlighter' ? .3 : 1, createdAt: now(), points: [first] }
      };
      scheduleLiveShapeHold(state.drawSession);
    } else if (effectiveTool === 'eraser') {
      const radius = screenToolWidthToPage(pageIndex, state.eraserRadius);
      checkpoint('erase');
      state.drawSession = { kind: 'eraser', pageIndex, pointerId: event.pointerId, point, radius, changed: eraseAt(pageIndex, point, radius) };
      renderPageCanvas(pageIndex);
    } else if (effectiveTool === 'lasso') {
      state.selection = null;
      state.drawSession = { kind: 'lasso', pageIndex, pointerId: event.pointerId, points: [point], closed: false };
      renderPageCanvas(pageIndex); updateObjectMenu();
    } else if (effectiveTool === 'shape') {
      state.drawSession = { kind: 'shape', pageIndex, pointerId: event.pointerId, resizeAnchor: { x: point.x, y: point.y }, object: { id: uid('shape'), type: 'shape', shape: state.shape, color: state.color, width: screenToolWidthToPage(pageIndex, state.width), screenWidth: state.width, x1: point.x, y1: point.y, x2: point.x, y2: point.y } };
      ensureAspectLock(state.drawSession);
    } else if (effectiveTool === 'tape') {
      state.drawSession = { kind: 'tape', pageIndex, pointerId: event.pointerId, start: point, object: { id: uid('tape'), type: 'tape', color: state.tapeColor, x1: point.x, y1: point.y, x2: point.x, y2: point.y + 54, revealed: false } };
    } else if (effectiveTool === 'laser') {
      state.drawSession = { kind: 'laser', pageIndex, pointerId: event.pointerId, points: [point] };
    } else if (effectiveTool === 'hand') {
      if (performance.now() < state.zoomDragLockUntil) return;
      const viewport = $('#editorViewport');
      state.drawSession = { kind: 'pan', pageIndex, pointerId: event.pointerId, startClient: { x: event.clientX, y: event.clientY }, scroll: { x: viewport.scrollLeft, y: viewport.scrollTop } };
    } else if (effectiveTool === 'text' || effectiveTool === 'sticky') {
      state.pendingInsert = { pageIndex, x: point.x, y: point.y, type: effectiveTool };
      openTextSheet(effectiveTool);
    } else if (effectiveTool === 'math') {
      state.pendingInsert = { pageIndex, x: point.x, y: point.y, type: 'math' };
      openMathSheet();
    } else if (effectiveTool === 'image') {
      state.pendingInsert = { pageIndex, x: point.x, y: point.y, type: 'image' };
      $('#imageInput').click();
    }
  }

  function handlePointerMove(event) {
    const canvas = event.target.closest?.('.page-canvas') || $(`.page-canvas[data-page-index="${state.drawSession?.pageIndex}"]`);
    if (!canvas) return;
    event.preventDefault();
    updateStylusTelemetry(event);
    const pointer = state.activePointers.get(event.pointerId);
    if (pointer) { pointer.clientX = event.clientX; pointer.clientY = event.clientY; pointer.x = event.clientX; pointer.y = event.clientY; }
    const pointerType = pointer?.pointerType || effectivePointerType(event);
    if (pointerType === 'touch' && state.touchGesture) { updateTouchGesture(event); if (state.settings.stylusOnly || touchPointers().length > 1 || !state.drawSession) return; }
    const session = state.drawSession;
    if (!session || session.pointerId !== event.pointerId) return;
    const pageIndex = session.pageIndex;
    const point = eventPoint(event, canvas);
    if (session.kind === 'stroke') {
      if (isStylusEraser(event)) {
        switchStrokeSessionToEraser(session, point);
        return;
      }
      const samples = typeof event.getCoalescedEvents === 'function' ? event.getCoalescedEvents() : [event];
      for (const sample of samples) {
        let next = eventPoint(sample, canvas);
        const nextScreen = eventScreenPoint(sample);
        if (state.ruler.visible) next = constrainToRuler(next);
        const lastScreen = session.screenPoints?.[session.screenPoints.length - 1];
        const movedDistance = lastScreen ? distance(lastScreen, nextScreen) : Infinity;
        if (!lastScreen || movedDistance > STROKE_SAMPLE_SCREEN_PX) {
          session.object.points.push(next);
          if (!session.screenPoints) session.screenPoints = [];
          session.screenPoints.push(nextScreen);
          const holdDistance = session.holdScreenPoint ? distance(session.holdScreenPoint, nextScreen) : movedDistance;
          if (holdDistance > STROKE_HOLD_RESET_SCREEN_PX) {
            session.lastMovedAt = performance.now();
            session.holdScreenPoint = nextScreen;
            scheduleLiveShapeHold(session);
          }
        }
      }
      scheduleRenderPage(pageIndex);
    } else if (session.kind === 'shape-adjust') {
      resizeLiveShape(session, point);
      scheduleRenderPage(pageIndex);
    } else if (session.kind === 'eraser') {
      session.point = point;
      session.changed = eraseAt(pageIndex, point, session.radius ?? screenToolWidthToPage(pageIndex, state.eraserRadius)) || session.changed;
      scheduleRenderPage(pageIndex);
    } else if (session.kind === 'lasso') {
      const last = session.points[session.points.length - 1];
      if (!last || distance(last, point) > 3) session.points.push(point);
      scheduleRenderPage(pageIndex);
    } else if (session.kind === 'shape') {
      resizeLiveShape(session, point); scheduleRenderPage(pageIndex);
    } else if (session.kind === 'tape') {
      session.object.x2 = point.x; session.object.y2 = Math.max(session.start.y + 34, point.y); scheduleRenderPage(pageIndex);
    } else if (session.kind === 'laser') {
      session.points.push(point); if (session.points.length > 90) session.points.shift(); scheduleRenderPage(pageIndex);
    } else if (session.kind === 'move-selection') {
      const dx = point.x - session.lastPoint.x, dy = point.y - session.lastPoint.y;
      selectedObjects().forEach((object) => translateObject(object, dx, dy));
      session.lastPoint = point; session.moved = true;
      scheduleRenderPage(pageIndex); updateObjectMenu();
    } else if (session.kind === 'resize-selection') {
      applySelectionResize(session, point);
      scheduleRenderPage(pageIndex); updateObjectMenu();
    } else if (session.kind === 'move-ruler') {
      state.ruler.x = clamp(session.startRuler.x + point.x - session.startPoint.x, 70, PAGE_WIDTH - 70);
      state.ruler.y = clamp(session.startRuler.y + point.y - session.startPoint.y, 40, PAGE_HEIGHT - 40);
      scheduleRenderPage(pageIndex);
    } else if (session.kind === 'pan') {
      if (performance.now() < state.zoomDragLockUntil) return;
      const viewport = $('#editorViewport');
      viewport.scrollLeft = session.scroll.x - (event.clientX - session.startClient.x);
      viewport.scrollTop = session.scroll.y - (event.clientY - session.startClient.y);
    }
  }

  function handlePointerUp(event) {
    const canvas = event.target.closest?.('.page-canvas') || $(`.page-canvas[data-page-index="${state.drawSession?.pageIndex}"]`);
    const session = state.drawSession;
    const pointerType = state.activePointers.get(event.pointerId)?.pointerType || effectivePointerType(event);
    if (pointerType === 'touch') {
      state.activePointers.delete(event.pointerId);
      finishTouchGesture();
      if (!session || session.pointerId !== event.pointerId) return;
    } else state.activePointers.delete(event.pointerId);
    if (!session || session.pointerId !== event.pointerId) return;
    const page = currentDocument()?.pages?.[session.pageIndex];
    if (!page) { state.drawSession = null; return; }
    if (session.holdTimer) clearTimeout(session.holdTimer);
    if (session.kind === 'stroke') {
      const points = session.object.points;
      const screenPoints = session.screenPoints || points;
      const duration = performance.now() - session.startedAt;
      const holdDuration = performance.now() - (session.lastMovedAt || session.startedAt);
      const allowScribbleErase = holdDuration < Math.max(260, SHAPE_HOLD_MS * .65);
      const scribbleErased = allowScribbleErase && maybeScribbleErase(session.pageIndex, points, screenPoints);
      const shape = !scribbleErased && session.object.brush !== 'highlighter' ? maybeShapeFromStroke(points, duration, holdDuration, screenPoints) : null;
      if (points.length > 1 && !scribbleErased) {
        checkpoint(shape ? 'draw-shape' : 'draw-stroke');
        let committedObject;
        if (shape) { committedObject = { id: uid('shape'), type: 'shape', ...shape, color: session.object.color, width: session.object.width, createdAt: now() }; page.objects.push(committedObject); }
        else { committedObject = session.object; page.objects.push(committedObject); }
        window.dispatchEvent(new CustomEvent('inkforge:stroke-committed', { detail: { pageIndex: session.pageIndex, documentId: currentDocument()?.id, objectId: committedObject.id, object: committedObject, wasShape: !!shape, holdDuration, duration } }));
      }
      persistCurrent();
    } else if (session.kind === 'shape') {
      if (distance({ x: session.object.x1, y: session.object.y1 }, { x: session.object.x2, y: session.object.y2 }) > 5) { checkpoint('shape'); page.objects.push(session.object); persistCurrent(); }
    } else if (session.kind === 'shape-adjust') {
      if (distance({ x: session.object.x1, y: session.object.y1 }, { x: session.object.x2, y: session.object.y2 }) > 5) {
        checkpoint('draw-shape');
        page.objects.push(session.object);
        persistCurrent();
        window.dispatchEvent(new CustomEvent('inkforge:stroke-committed', { detail: { pageIndex: session.pageIndex, documentId: currentDocument()?.id, objectId: session.object.id, object: session.object, wasShape: true, holdDuration: performance.now() - (session.lastMovedAt || session.startedAt), duration: performance.now() - session.startedAt } }));
      }
    } else if (session.kind === 'tape') {
      const b = computeBounds(session.object);
      if (b.w > 12 && b.h > 12) { checkpoint('tape'); page.objects.push(session.object); persistCurrent(); }
    } else if (session.kind === 'lasso') {
      session.closed = true;
      const polygon = session.points;
      const ids = page.objects.filter((object) => {
        if (object.locked) return false;
        const b = computeBounds(object);
        return pointInPolygon({ x: b.x + b.w / 2, y: b.y + b.h / 2 }, polygon) || polygon.some((point) => point.x >= b.x && point.x <= b.x + b.w && point.y >= b.y && point.y <= b.y + b.h);
      }).map((object) => object.id);
      state.selection = ids.length ? { pageIndex: session.pageIndex, ids, polygon } : null;
    } else if (session.kind === 'eraser' && session.changed) persistCurrent();
    else if ((session.kind === 'move-selection' && session.moved) || (session.kind === 'resize-selection' && session.resized)) persistCurrent();
    else if (session.kind === 'move-ruler') renderPageCanvas(session.pageIndex);
    state.drawSession = null;
    renderPageCanvas(session.pageIndex);
    renderSidebar();
    updateObjectMenu();
  }

  function handlePointerCancel(event) {
    state.activePointers.delete(event.pointerId);
    if (state.drawSession?.pointerId === event.pointerId) {
      const pageIndex = state.drawSession.pageIndex;
      if (state.drawSession.holdTimer) clearTimeout(state.drawSession.holdTimer);
      state.drawSession = null;
      renderPageCanvas(pageIndex);
    }
    finishTouchGesture();
  }

  function handleDoubleClick(event) {
    if (event.target.closest('.page-canvas')) fitPage();
  }

  function updateObjectMenu() {
    const menu = $('#objectMenu');
    const objects = selectedObjects();
    if (!state.selection || !objects.length || state.view !== 'editor') { menu.hidden = true; return; }
    const pageCanvas = $(`.page-canvas[data-page-index="${state.selection.pageIndex}"]`);
    const bounds = selectionBounds(objects);
    if (!pageCanvas || !bounds) { menu.hidden = true; return; }
    const rect = pageCanvas.getBoundingClientRect();
    const left = rect.left + bounds.x / PAGE_WIDTH * rect.width;
    const top = rect.top + bounds.y / PAGE_HEIGHT * rect.height;
    const width = bounds.w / PAGE_WIDTH * rect.width;
    menu.innerHTML = `<button data-action="selection-cut">${icon('cut')}<span>잘라내기</span></button><button data-action="selection-copy">${icon('copy')}<span>복사</span></button><button data-action="selection-duplicate">${icon('duplicate')}<span>복제</span></button><button data-action="selection-color">${icon('palette')}<span>색상</span></button><button data-action="selection-lock">${icon('lock')}<span>${objects.every((item) => item.locked) ? '잠금 해제' : '잠금'}</span></button><button data-action="selection-front">${icon('arrow-up')}<span>앞으로</span></button><button class="danger" data-action="selection-delete">${icon('trash')}<span>삭제</span></button>`;
    menu.hidden = false;
    requestAnimationFrame(() => {
      const menuRect = menu.getBoundingClientRect();
      menu.style.left = `${clamp(left + width / 2 - menuRect.width / 2, 7, window.innerWidth - menuRect.width - 7)}px`;
      menu.style.top = `${clamp(top - menuRect.height - 10, 7, window.innerHeight - menuRect.height - 7)}px`;
    });
  }

  function copySelection(remove = false) {
    const page = currentDocument()?.pages?.[state.selection?.pageIndex];
    const objects = selectedObjects();
    if (!page || !objects.length) return;
    state.clipboard = deepClone(objects);
    if (remove) {
      checkpoint('cut');
      const ids = new Set(state.selection.ids);
      page.objects = page.objects.filter((object) => !ids.has(object.id));
      state.selection = null;
      persistCurrent(); renderPageCanvas(state.currentPageIndex); updateObjectMenu(); renderSidebar();
    }
    toast(remove ? '잘라냈습니다.' : '복사했습니다.');
  }

  function pasteSelection() {
    const page = currentPage();
    if (!page || !state.clipboard.length) { toast('붙여넣을 항목이 없습니다.'); return; }
    checkpoint('paste');
    const copies = deepClone(state.clipboard);
    copies.forEach((object) => { object.id = uid(object.type || 'object'); translateObject(object, 34, 34); });
    page.objects.push(...copies);
    state.selection = { pageIndex: state.currentPageIndex, ids: copies.map((object) => object.id) };
    persistCurrent(); renderPageCanvas(state.currentPageIndex); updateObjectMenu(); renderSidebar();
  }

  function duplicateSelection() {
    const objects = selectedObjects();
    const page = currentDocument()?.pages?.[state.selection?.pageIndex];
    if (!page || !objects.length) return;
    checkpoint('duplicate');
    const copies = deepClone(objects);
    copies.forEach((object) => { object.id = uid(object.type || 'object'); translateObject(object, 28, 28); });
    page.objects.push(...copies);
    state.selection.ids = copies.map((object) => object.id);
    persistCurrent(); renderPageCanvas(state.selection.pageIndex); updateObjectMenu(); renderSidebar();
  }

  function deleteSelection() {
    const page = currentDocument()?.pages?.[state.selection?.pageIndex];
    if (!page || !state.selection) return;
    checkpoint('selection-delete');
    const ids = new Set(state.selection.ids);
    page.objects = page.objects.filter((object) => !ids.has(object.id));
    const pageIndex = state.selection.pageIndex;
    state.selection = null;
    persistCurrent(); renderPageCanvas(pageIndex); updateObjectMenu(); renderSidebar();
  }

  function cycleSelectionColor() {
    const objects = selectedObjects();
    if (!objects.length) return;
    const colors = ['#111827','#147bd1','#d93939','#2a9d66','#7a57c7','#d28b2f'];
    checkpoint('selection-color');
    objects.forEach((object) => {
      const current = object.color || '#111827';
      object.color = colors[(colors.indexOf(current) + 1 + colors.length) % colors.length];
    });
    persistCurrent(); renderPageCanvas(state.selection.pageIndex); updateObjectMenu();
  }

  function toggleSelectionLock() {
    const objects = selectedObjects();
    if (!objects.length) return;
    checkpoint('selection-lock');
    const next = !objects.every((object) => object.locked);
    objects.forEach((object) => { object.locked = next; });
    persistCurrent(); renderPageCanvas(state.selection.pageIndex); updateObjectMenu();
  }

  function bringSelectionFront() {
    const page = currentDocument()?.pages?.[state.selection?.pageIndex];
    if (!page || !state.selection) return;
    checkpoint('selection-front');
    const ids = new Set(state.selection.ids);
    const selected = page.objects.filter((object) => ids.has(object.id));
    page.objects = page.objects.filter((object) => !ids.has(object.id)).concat(selected);
    persistCurrent(); renderPageCanvas(state.selection.pageIndex);
  }

  function selectAll() {
    const page = currentPage();
    if (!page) return;
    const ids = page.objects.filter((object) => !object.locked).map((object) => object.id);
    state.selection = ids.length ? { pageIndex: state.currentPageIndex, ids } : null;
    renderPageCanvas(state.currentPageIndex); updateObjectMenu();
  }

  function openModal(id) {
    $('#modalBackdrop').hidden = false;
    $$('.modal').forEach((modal) => { modal.hidden = modal.id !== id; });
    const modal = $(`#${id}`);
    if (modal) modal.hidden = false;
  }

  function closeModal() {
    $('#modalBackdrop').hidden = true;
    $$('.modal').forEach((modal) => { modal.hidden = true; });
  }

  function showMenu(title, eyebrow, items) {
    $('#menuTitle').textContent = title;
    $('#menuEyebrow').textContent = eyebrow || '메뉴';
    $('#menuItems').innerHTML = items.map((item) => `<button class="menu-item ${item.danger ? 'danger' : ''}" data-action="${item.action}" ${item.attrs || ''}><span class="menu-item-icon">${icon(item.icon || 'info')}</span><span class="menu-item-copy"><strong>${escapeHtml(item.title)}</strong>${item.description ? `<small>${escapeHtml(item.description)}</small>` : ''}</span><span class="menu-item-chevron">${item.trailing || icon('chevron-right')}</span></button>`).join('');
    openModal('menuSheet');
  }

  function openTextSheet(type = 'text') {
    const isSticky = type === 'sticky';
    $('#textSheetEyebrow').textContent = isSticky ? 'Sticky Note' : 'Text';
    $('#textSheetTitle').textContent = isSticky ? '스티키 노트 내용' : '텍스트 입력';
    $('#textObjectInput').placeholder = isSticky ? '암기할 내용이나 메모를 입력하세요.' : '페이지에 넣을 내용을 입력하세요. 식 끝에 =를 붙이면 자동 계산합니다.';
    $('#textObjectInput').value = '';
    openModal('textSheet');
    setTimeout(() => $('#textObjectInput').focus(), 80);
  }

  function insertTextObject() {
    const pending = state.pendingInsert || { pageIndex: state.currentPageIndex, x: 120, y: 180, type: state.tool === 'sticky' ? 'sticky' : 'text' };
    const page = currentDocument()?.pages?.[pending.pageIndex];
    const text = $('#textObjectInput').value.trim();
    if (!page || !text) { toast('내용을 입력하세요.'); return; }
    checkpoint(pending.type === 'sticky' ? 'sticky' : 'text');
    if (pending.type === 'sticky') {
      page.objects.push({ id: uid('sticky'), type: 'sticky', x: pending.x, y: pending.y, w: 330, h: 240, text, color: state.stickyColor, fontSize: 25 });
    } else {
      const mathCandidate = text.endsWith('=') ? text.slice(0, -1).trim() : '';
      if (mathCandidate) {
        try {
          const calculation = evaluateMath(mathCandidate, { degree: state.math.degree });
          page.objects.push({ id: uid('math'), type: 'math', x: pending.x, y: pending.y, w: 600, h: 96, expression: calculation.expression, result: formatMathResult(calculation.result), showExpression: true, color: '#225e9d', fontSize: 27 });
          toast('수식을 자동 계산해 삽입했습니다.');
        } catch {
          page.objects.push({ id: uid('text'), type: 'text', x: pending.x, y: pending.y, w: 720, h: 120, text, color: state.color, fontSize: 29, fontWeight: 500 });
        }
      } else page.objects.push({ id: uid('text'), type: 'text', x: pending.x, y: pending.y, w: 720, h: 120, text, color: state.color, fontSize: 29, fontWeight: 500 });
    }
    state.currentPageIndex = pending.pageIndex;
    state.pendingInsert = null;
    persistCurrent(); closeModal(); renderEditorPages(); renderSidebar();
  }

  function insertSticker(text = state.sticker || '★') {
    const pageIndex = state.currentPageIndex;
    const page = currentDocument()?.pages?.[pageIndex];
    if (!page) return;
    checkpoint('sticker');
    const sticker = {
      id: uid('sticker'),
      type: 'sticker',
      x: PAGE_WIDTH / 2 - 70,
      y: Math.max(90, PAGE_HEIGHT * .28),
      w: 140,
      h: 140,
      text,
      fontSize: 92,
      color: state.color,
      createdAt: now()
    };
    page.objects.push(sticker);
    state.selection = { pageIndex, ids: [sticker.id] };
    persistCurrent();
    renderPageCanvas(pageIndex);
    renderSidebar();
    updateObjectMenu();
  }

  function openMathSheet() {
    $('#mathExpressionInput').value = state.math.expression || '';
    $('#mathDegreeMode').checked = !!state.math.degree;
    renderMathResult();
    openModal('mathSheet');
    setTimeout(() => $('#mathExpressionInput').focus(), 80);
  }

  function renderMathKeypad() {
    const keys = ['7','8','9','÷','sqrt(','(', '4','5','6','×','^',')', '1','2','3','-','pi','!', '0','.','%','+','e','⌫', 'sin(','cos(','tan(','log(','ln(','C'];
    $('#mathKeypad').innerHTML = keys.map((key) => `<button class="math-key" data-action="math-key" data-key="${escapeHtml(key)}">${escapeHtml(key)}</button>`).join('');
  }

  function calculateMath() {
    const input = $('#mathExpressionInput');
    state.math.expression = input.value;
    state.math.degree = $('#mathDegreeMode').checked;
    try {
      const calculated = evaluateMath(input.value, { degree: state.math.degree });
      state.math.result = formatMathResult(calculated.result);
      state.math.error = null;
      if (calculated.assignment) persistCurrent();
    } catch (error) {
      state.math.result = null;
      state.math.error = error.message || String(error);
    }
    renderMathResult();
  }

  function renderMathResult() {
    const card = $('#mathResultCard');
    if (!card) return;
    card.classList.toggle('has-error', !!state.math.error);
    $('#mathResultValue').textContent = state.math.error || state.math.result || '식을 입력하세요';
    $('#mathResultDetail').textContent = state.math.error ? '수식 형식을 확인하세요.' : state.math.result ? `기기 내 계산 · ${state.math.degree ? '도' : '라디안'} 모드` : '예: (12+8)*3, sqrt(144), x=24';
  }

  function insertMathObject() {
    calculateMath();
    if (state.math.error || state.math.result == null) return;
    const pending = state.pendingInsert || { pageIndex: state.currentPageIndex, x: 120, y: 190, type: 'math' };
    const page = currentDocument()?.pages?.[pending.pageIndex];
    if (!page) return;
    checkpoint('math');
    page.objects.push({ id: uid('math'), type: 'math', x: pending.x, y: pending.y, w: 620, h: 96, expression: state.math.expression.replace(/=$/, '').trim(), result: state.math.result, showExpression: $('#mathInsertBoth').checked, color: '#225e9d', fontSize: 27 });
    state.currentPageIndex = pending.pageIndex;
    state.pendingInsert = null;
    persistCurrent(); closeModal(); renderEditorPages(); renderSidebar();
    toast('계산 결과를 페이지에 삽입했습니다.');
  }

  function renderPenSettings() {
    const settings = state.penSettings[state.brush] || BRUSH_META[state.brush];
    const fields = [];
    const ranges = state.brush === 'pencil' ? [
      ['hardness','펜촉 경도',0,1,.01],['grain','흑연 입자',0,1,.01],['tiltShade','기울기 음영',0,1,.01],['pressure','압력 민감도',0,1,.01],['smoothing','획 안정화',0,.9,.01],['opacity','농도',.2,1,.01]
    ] : [
      ['pressure','압력 민감도',0,1,.01],['sharpness','펜촉 선명도',0,1,.01],['smoothing','획 안정화',0,.9,.01],['taper','시작·끝 테이퍼',0,1,.01],['opacity','불투명도',.2,1,.01]
    ];
    for (const [key, label, min, max, step] of ranges) fields.push(`<div class="range-field"><label for="pen-${key}">${label}</label><output id="pen-${key}-out">${Math.round((settings[key] ?? 0) * 100)}%</output><input id="pen-${key}" data-pen-setting="${key}" type="range" min="${min}" max="${max}" step="${step}" value="${settings[key] ?? 0}"></div>`);
    $('#penSettingsFields').innerHTML = fields.join('');
    renderPenPreview();
  }

  function renderPenPreview() {
    const canvas = $('#penPreviewCanvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#f9f7f2'; ctx.fillRect(0, 0, canvas.width, canvas.height);
    const points = [];
    for (let x = 40; x <= 720; x += 8) {
      const progress = (x - 40) / 680;
      points.push({ x, y: 76 + Math.sin(progress * Math.PI * 3) * 24, p: .12 + Math.sin(progress * Math.PI) * .83, tx: progress * 56, ty: 18, t: progress * 600 });
    }
    ctx.save(); ctx.scale(canvas.width / 760, canvas.height / 150);
    renderStroke(ctx, { id: `preview-${state.brush}`, type: 'stroke', brush: state.brush, color: state.color, width: state.width * 2, opacity: 1, points });
    ctx.restore();
  }

  async function toggleAudioRecording() {
    const dot = $('#recordDot');
    if (state.recording) {
      state.recording.stop();
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') { toast('이 기기에서는 오디오 녹음을 사용할 수 없습니다.'); return; }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const chunks = [];
      const recorder = new MediaRecorder(stream);
      const startedAt = performance.now();
      recorder.ondataavailable = (event) => { if (event.data?.size) chunks.push(event.data); };
      recorder.onstop = async () => {
        const blob = new Blob(chunks, { type: recorder.mimeType || 'audio/webm' });
        const src = await blobToDataURL(blob);
        const doc = currentDocument();
        if (doc) {
          checkpoint('audio');
          doc.audio = doc.audio || [];
          doc.audio.push({ id: uid('audio'), title: `녹음 ${doc.audio.length + 1}`, src, mime: blob.type, duration: (performance.now() - startedAt) / 1000, pageIndex: state.currentPageIndex, createdAt: now() });
          await persistCurrent({ immediate: true });
          renderSidebar();
        }
        stream.getTracks().forEach((track) => track.stop());
        state.recording = null; state.audioStream = null;
        dot.classList.remove('is-active');
        toast('오디오 녹음을 저장했습니다.');
      };
      recorder.start(300);
      state.recording = recorder; state.audioStream = stream;
      dot.classList.add('is-active');
      toast('녹음을 시작했습니다. 다시 누르면 저장됩니다.');
    } catch (error) { toast(`마이크를 사용할 수 없습니다: ${error.message || error}`); }
  }

  function blobToDataURL(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(blob);
    });
  }

  function playAudio(id) {
    const clip = currentDocument()?.audio?.find((item) => item.id === id);
    if (!clip) return;
    const audio = new Audio(clip.src);
    audio.play().catch((error) => toast(`재생 오류: ${error.message || error}`));
  }

  function deleteAudio(id) {
    const doc = currentDocument();
    if (!doc) return;
    checkpoint('audio-delete');
    doc.audio = (doc.audio || []).filter((clip) => clip.id !== id);
    persistCurrent(); renderSidebar();
  }

  async function insertImageFile(file) {
    if (!file || !file.type.startsWith('image/')) return;
    const src = await blobToDataURL(file);
    const image = new Image();
    image.onload = () => {
      const pending = state.pendingInsert || { pageIndex: state.currentPageIndex, x: 120, y: 180 };
      const page = currentDocument()?.pages?.[pending.pageIndex];
      if (!page) return;
      const maxWidth = 650, maxHeight = 720;
      const scale = Math.min(maxWidth / image.naturalWidth, maxHeight / image.naturalHeight, 1);
      checkpoint('image');
      page.objects.push({ id: uid('image'), type: 'image', x: pending.x, y: pending.y, w: image.naturalWidth * scale, h: image.naturalHeight * scale, src, opacity: 1 });
      state.pendingInsert = null;
      state.currentPageIndex = pending.pageIndex;
      persistCurrent(); renderEditorPages(); renderSidebar();
    };
    image.onerror = () => toast('이미지를 읽지 못했습니다.');
    image.src = src;
  }

  function downloadBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url; anchor.download = filename; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
  }

  function safeFilename(value) {
    return String(value || 'bad note').replace(/[\\/:*?"<>|]+/g, '_').trim().slice(0, 100) || 'bad note';
  }

  function exportIfnote() {
    const doc = currentDocument();
    if (!doc) return;
    const payload = { ...deepClone(doc), exportedAt: now(), appVersion: VERSION };
    downloadBlob(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }), `${safeFilename(doc.title)}.ifnote`);
    toast('편집 가능한 노트 파일을 내보냈습니다.');
  }

  function exportPng() {
    const doc = currentDocument();
    const page = currentPage();
    if (!doc || !page) return;
    const canvas = document.createElement('canvas');
    canvas.width = PAGE_WIDTH; canvas.height = PAGE_HEIGHT;
    const ctx = canvas.getContext('2d', { alpha: false });
    renderPageBackground(ctx, page, state.currentPageIndex);
    page.objects.forEach((object) => renderObject(ctx, object, state.currentPageIndex));
    canvas.toBlob((blob) => blob && downloadBlob(blob, `${safeFilename(doc.title)}-${state.currentPageIndex + 1}.png`), 'image/png');
  }

  async function importIfnote(file) {
    try {
      const raw = await file.text();
      const data = JSON.parse(raw);
      if (!data || !Array.isArray(data.pages)) throw new Error('지원하는 노트 형식이 아닙니다.');
      data.id = uid('doc');
      data.title = `${data.title || '가져온 노트'}`;
      data.appVersion = VERSION;
      data.updatedAt = now();
      data.createdAt = data.createdAt || now();
      data.trashed = false;
      data.pages.forEach((page) => { page.id = page.id || uid('page'); page.objects = Array.isArray(page.objects) ? page.objects : []; });
      state.documents.push(data);
      await storage.putDocument(deepClone(data));
      renderLibrary();
      toast('노트를 가져왔습니다.');
    } catch (error) { toast(`가져오기 실패: ${error.message || error}`); }
  }

  async function shareDocument() {
    const doc = currentDocument();
    if (!doc) return;
    const blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' });
    const file = new File([blob], `${safeFilename(doc.title)}.ifnote`, { type: 'application/json' });
    if (navigator.share && navigator.canShare?.({ files: [file] })) {
      try { await navigator.share({ title: doc.title, text: 'bad note 노트', files: [file] }); }
      catch (error) { if (error.name !== 'AbortError') toast(`공유 오류: ${error.message || error}`); }
    } else exportIfnote();
  }

  function openNewNoteSheet(template = 'grid') {
    $('#newNoteTitle').value = '새 노트';
    renderTemplatePicker(template);
    $('#newNoteSheet').dataset.template = template;
    openModal('newNoteSheet');
  }

  async function createNewNote() {
    const template = $('#newNoteSheet').dataset.template || $('.template-option.is-active')?.dataset.templateId || 'grid';
    const title = $('#newNoteTitle').value.trim() || '새 노트';
    const doc = createDocument(title, template);
    doc.folderId = state.folderId === 'root' ? 'root' : state.folderId;
    state.documents.push(doc);
    await storage.putDocument(deepClone(doc));
    closeModal();
    openDocument(doc.id);
  }

  async function createFolder(title = null) {
    const requested = title == null ? prompt('새 폴더 이름', '새 폴더') : title;
    const name = String(requested || '').trim().replace(/\s+/g, ' ').slice(0, 40);
    if (!name) return null;
    const duplicate = state.folders.find((folder) => normalizeText(folder.title) === normalizeText(name));
    if (duplicate) {
      state.libraryFilter = 'all';
      state.folderId = duplicate.id;
      state.globalQuery = '';
      renderLibrary();
      toast(`${duplicate.title} 폴더로 이동했습니다.`);
      return duplicate;
    }
    const folder = {
      id: uid('folder'),
      title: name,
      color: FOLDER_COLORS[(state.folders.length - 1) % FOLDER_COLORS.length]
    };
    state.folders.push(folder);
    state.libraryFilter = 'all';
    state.folderId = folder.id;
    state.globalQuery = '';
    await persistFolders();
    renderLibrary();
    toast(`${folder.title} 폴더를 만들었습니다.`);
    return folder;
  }

  function duplicateDocument(id) {
    const source = state.documents.find((doc) => doc.id === id);
    if (!source) return;
    const copy = deepClone(source);
    copy.id = uid('doc'); copy.title = `${source.title} 복사본`; copy.createdAt = now(); copy.updatedAt = now(); copy.favorite = false;
    copy.pages.forEach((page) => { page.id = uid('page'); page.objects.forEach((object) => { object.id = uid(object.type || 'object'); }); });
    state.documents.push(copy);
    storage.putDocument(deepClone(copy)); renderLibrary(); toast('문서를 복제했습니다.');
  }

  function renameDocument(id) {
    const doc = state.documents.find((item) => item.id === id);
    if (!doc) return;
    const title = prompt('새 문서 이름', doc.title);
    if (!title?.trim()) return;
    doc.title = title.trim(); doc.updatedAt = now();
    storage.putDocument(deepClone(doc)); renderLibrary(); renderTabs();
  }

  function moveDocumentToFolder(id) {
    const doc = state.documents.find((item) => item.id === id);
    if (!doc) return;
    const folder = state.folders.find((item) => item.id !== doc.folderId && item.id !== 'root') || state.folders[0];
    doc.folderId = doc.folderId === 'root' ? folder.id : 'root';
    doc.updatedAt = now(); storage.putDocument(deepClone(doc)); renderLibrary(); toast(doc.folderId === 'root' ? '문서로 이동했습니다.' : `${folder.title} 폴더로 이동했습니다.`);
  }

  function trashDocument(id) {
    const doc = state.documents.find((item) => item.id === id);
    if (!doc) return;
    if (state.libraryFilter === 'trash') {
      state.documents = state.documents.filter((item) => item.id !== id);
      storage.deleteDocument(id); toast('문서를 완전히 삭제했습니다.');
    } else {
      doc.trashed = true; doc.updatedAt = now(); storage.putDocument(deepClone(doc)); toast('휴지통으로 이동했습니다.');
    }
    renderLibrary();
  }

  function restoreDocument(id) {
    const doc = state.documents.find((item) => item.id === id);
    if (!doc) return;
    doc.trashed = false; doc.updatedAt = now(); storage.putDocument(deepClone(doc)); renderLibrary(); toast('문서를 복원했습니다.');
  }

  function documentMenu(id) {
    const doc = state.documents.find((item) => item.id === id);
    if (!doc) return;
    if (doc.trashed) {
      showMenu(doc.title, '휴지통', [
        { action:'restore-document', attrs:`data-doc-id="${id}"`, icon:'undo', title:'복원', description:'문서 목록으로 되돌립니다.' },
        { action:'trash-document', attrs:`data-doc-id="${id}"`, icon:'trash', title:'영구 삭제', description:'이 작업은 되돌릴 수 없습니다.', danger:true }
      ]);
      return;
    }
    showMenu(doc.title, '문서 메뉴', [
      { action:'open-document-menu', attrs:`data-doc-id="${id}"`, icon:'notebook', title:'열기', description:`${doc.pages.length}페이지` },
      { action:'rename-document', attrs:`data-doc-id="${id}"`, icon:'text', title:'이름 변경' },
      { action:'toggle-favorite-menu', attrs:`data-doc-id="${id}"`, icon:'star', title:doc.favorite ? '즐겨찾기 해제' : '즐겨찾기에 추가' },
      { action:'duplicate-document', attrs:`data-doc-id="${id}"`, icon:'duplicate', title:'복제' },
      { action:'move-document', attrs:`data-doc-id="${id}"`, icon:'folder', title:'폴더 이동' },
      { action:'trash-document', attrs:`data-doc-id="${id}"`, icon:'trash', title:'휴지통으로 이동', danger:true }
    ]);
  }

  function pageMenu(index) {
    const page = currentDocument()?.pages?.[index];
    if (!page) return;
    showMenu(`${index + 1}페이지`, '페이지 레이아웃', [
      { action:'go-page-menu', attrs:`data-page-index="${index}"`, icon:'eye', title:'이 페이지로 이동' },
      { action:'bookmark-page', attrs:`data-page-index="${index}"`, icon:'bookmark', title:page.bookmarked ? '북마크 해제' : '북마크' },
      { action:'duplicate-page', attrs:`data-page-index="${index}"`, icon:'duplicate', title:'페이지 복제' },
      { action:'move-page-up', attrs:`data-page-index="${index}"`, icon:'arrow-up', title:'앞으로 이동' },
      { action:'move-page-down', attrs:`data-page-index="${index}"`, icon:'arrow-down', title:'뒤로 이동' },
      { action:'delete-page', attrs:`data-page-index="${index}"`, icon:'trash', title:'페이지 삭제', danger:true }
    ]);
  }

  function openToolsOverflow() {
    showMenu('추가 도구', '도구막대', [
      { action:'select-tool-menu', attrs:'data-tool="highlighter"', icon:'highlighter', title:'형광펜', description:'반투명 마커로 중요한 부분을 표시합니다.' },
      { action:'select-tool-menu', attrs:'data-tool="shape"', icon:'shape', title:'도형', description:'선, 화살표, 다각형, 별, 말풍선 등 다양한 도형을 만듭니다.' },
      { action:'select-tool-menu', attrs:'data-tool="tape"', icon:'tape', title:'암기 테이프', description:'내용을 가렸다가 탭해 정답을 확인합니다.' },
      { action:'sticker-menu', icon:'sticker', title:'스티커', description:'페이지 가운데에 선택한 스티커를 추가합니다.' },
      { action:'select-tool-menu', attrs:'data-tool="laser"', icon:'laser', title:'레이저 포인터', description:'저장되지 않는 발표용 포인터입니다.' },
      { action:'select-tool-menu', attrs:'data-tool="hand"', icon:'hand', title:'손 도구', description:'확대·이동과 읽기에 사용합니다.' }
    ]);
  }

  function openStickerMenu() {
    const stickers = ['★','✓','!','?','❤','⚑','→','○','□','△'];
    showMenu('스티커', 'Sticker', stickers.map((sticker) => ({
      action: 'select-sticker',
      attrs: `data-sticker="${escapeHtml(sticker)}"`,
      icon: 'sticker',
      title: sticker,
      description: '선택한 스티커를 현재 페이지에 추가합니다.'
    })));
  }

  function openEditorMore() {
    const doc = currentDocument();
    if (!doc) return;
    showMenu('문서 옵션', doc.title, [
      { action:'document-search', icon:'search', title:'문서 검색', description:'입력한 텍스트, 수식, 제목을 찾습니다.' },
      { action:'go-page-number-menu', icon:'arrow', title:'페이지 번호로 이동', description:'오른쪽 페이지 바에서 원하는 페이지 번호를 입력합니다.' },
      { action:'calculate-page-math', icon:'math', title:'손글씨 수식 계산', description:'현재 화면의 필기 수식을 한 번 인식해 결과를 표시합니다.' },
      { action:'toggle-read-mode', icon:state.readOnly ? 'eye-off' : 'read', title:state.readOnly ? '편집 모드로 전환' : '읽기 모드', description:'실수로 필기되지 않도록 편집을 잠급니다.' },
      { action:'toggle-page-mode', icon:'sidebar', title:state.pageMode === 'continuous' ? '한 페이지 보기' : '연속 페이지 보기' },
      { action:'fit-page', icon:'fit', title:'페이지 맞춤' },
      { action:'import-note', icon:'import', title:'노트 가져오기', description:'.ifnote 파일을 추가합니다.' },
      { action:'import-pdf', icon:'page-plus', title:'PDF 가져오기', description:'PDF 각 페이지를 노트 배경으로 추가하고 텍스트를 검색합니다.' },
      { action:'open-settings', icon:'settings', title:'설정' }
    ]);
  }

  function openShareMenu() {
    showMenu('공유 및 내보내기', 'Export', [
      { action:'share-native', icon:'share', title:'기기 공유', description:'지원되는 앱으로 편집 가능한 노트를 공유합니다.' },
      { action:'export-ifnote', icon:'export', title:'편집 가능한 .ifnote', description:'모든 획과 페이지를 보존합니다.' },
      { action:'export-png', icon:'image', title:'현재 페이지 PNG' },
      { action:'print-pdf', icon:'page-plus', title:'PDF로 인쇄', description:'시스템 인쇄 화면에서 PDF로 저장합니다.' }
    ]);
  }

  function showGestureGuide() {
    showMenu('제스처 사용법', 'Quick Guide', [
      { action:'close-modal', icon:'gesture', title:'두 손가락 핀치', description:'페이지 중심을 유지하며 8%~800% 확대·축소합니다.' },
      { action:'close-modal', icon:'undo', title:'두 손가락 탭', description:'실행 취소합니다.' },
      { action:'close-modal', icon:'redo', title:'세 손가락 탭', description:'다시 실행합니다.' },
      { action:'close-modal', icon:'arrow', title:'두 손가락 좌우 쓸기', description:'이전 또는 다음 페이지로 이동합니다.' },
      { action:'close-modal', icon:'eraser', title:'낙서해서 지우기', description:'필기 위를 빠르게 지그재그로 문질러 지웁니다.' },
      { action:'close-modal', icon:'shape', title:'그린 뒤 길게 유지', description:'직선이나 닫힌 원을 정돈된 도형으로 변환합니다.' }
    ]);
  }

  function localStudyAssistant() {
    const page = currentPage();
    const text = (page?.objects || []).map((object) => object.text || (object.type === 'math' ? `${object.expression} = ${object.result}` : '')).filter(Boolean).join(' ');
    const words = text.match(/[가-힣A-Za-z0-9]{2,}/g) || [];
    const frequency = new Map();
    words.forEach((word) => frequency.set(word, (frequency.get(word) || 0) + 1));
    const keywords = [...frequency.entries()].sort((a,b) => b[1] - a[1] || b[0].length - a[0].length).slice(0, 6).map(([word]) => word);
    showMenu('학습 도우미', 'On-device', [
      { action:'assistant-keywords', icon:'sparkles', title:'현재 페이지 핵심어', description:keywords.length ? keywords.join(' · ') : '입력한 텍스트가 있는 페이지에서 사용할 수 있습니다.' },
      { action:'assistant-quiz', icon:'check-circle', title:'빠른 복습 문제', description:keywords.length ? `${keywords[0]}의 정의나 핵심 내용을 말해 보세요.` : '텍스트를 먼저 추가하세요.' },
      { action:'select-tool-menu', attrs:'data-tool="tape"', icon:'tape', title:'암기 테이프 시작', description:'답을 완전히 가리고 눌러 확인합니다.' },
      { action:'open-handwriting-ocr', icon:'search', title:'손글씨 OCR', description:'현재 페이지 필기를 텍스트로 인식하고 검색 색인에 저장합니다.' }
    ]);
  }

  function openBrushMenu() {
    showMenu('펜 종류', 'Writing Tool', Object.entries(BRUSH_META).filter(([id]) => id !== 'highlighter').map(([id, meta]) => ({
      action:'select-brush', attrs:`data-brush="${id}"`, icon:meta.icon, title:meta.title,
      description: id === 'pencil' ? '흑연 입자, 압력 농도, 기울기 음영을 사용하는 새 연필 엔진' : id === 'fountain' ? '필압과 진행 방향에 반응하는 만년필' : id === 'brush' ? '큰 굵기 변화와 테이퍼를 가진 브러시' : id === 'ballpoint' ? '압력 변화가 적은 균일한 볼펜' : id === 'gel' ? '선명한 잉크 코어를 가진 젤펜' : '일정하고 가는 파인라이너'
    })));
  }

  function openSortMenu() {
    showMenu('정렬', 'Library', [
      { action:'set-sort', attrs:'data-sort="updated-desc"', icon:'calendar', title:'최근 수정순' },
      { action:'set-sort', attrs:'data-sort="updated-asc"', icon:'calendar', title:'오래된 수정순' },
      { action:'set-sort', attrs:'data-sort="title-asc"', icon:'text', title:'이름 오름차순' },
      { action:'set-sort', attrs:'data-sort="title-desc"', icon:'text', title:'이름 내림차순' }
    ]);
  }

  function openSettings() {
    $('#stylusOnlyToggle').checked = state.settings.stylusOnly;
    $('#scribbleEraseToggle').checked = state.settings.scribbleErase;
    $('#drawHoldToggle').checked = state.settings.drawHold;
    $('#telemetryToggle').checked = state.settings.telemetry;
    $('#continuousToggle').checked = state.settings.continuous;
    const nativeAuto = $('#nativeAutoOcrToggle');
    if (nativeAuto) nativeAuto.checked = state.settings.autoOcr !== false;
    openModal('settingsSheet');
  }

  async function saveSettingsFromControls() {
    state.settings.stylusOnly = $('#stylusOnlyToggle').checked;
    state.settings.scribbleErase = $('#scribbleEraseToggle').checked;
    state.settings.drawHold = $('#drawHoldToggle').checked;
    state.settings.telemetry = $('#telemetryToggle').checked;
    state.settings.continuous = $('#continuousToggle').checked;
    $('#stylusTelemetry').hidden = !state.settings.telemetry;
    await storage.setSetting('preferences', state.settings);
  }

  function handleLibraryCardClick(event) {
    const card = event.target.closest('.document-card');
    if (!card || event.target.closest('button')) return false;
    if (card.dataset.docId) { openDocument(card.dataset.docId); return true; }
    if (card.dataset.templateId) { openNewNoteSheet(card.dataset.templateId); return true; }
    return false;
  }

  async function handleAction(action, target, event) {
    switch (action) {
      case 'library-home': state.libraryFilter = 'all'; state.folderId = 'root'; renderLibrary(); break;
      case 'toggle-global-search': {
        const panel = $('#globalSearchPanel'); panel.dataset.open = panel.hidden ? '1' : '0'; panel.hidden = !panel.hidden; if (!panel.hidden) setTimeout(() => $('#globalSearchInput').focus(), 40); break;
      }
      case 'clear-global-search': state.globalQuery = ''; $('#globalSearchInput').value = ''; renderLibrary(); break;
      case 'new-note': openNewNoteSheet(); break;
      case 'new-note-pdf': closeModal(); window.__inkforgePdf?.openPicker({ createNew: true }); break;
      case 'create-folder': await createFolder(); break;
      case 'create-note': await createNewNote(); break;
      case 'close-modal': closeModal(); break;
      case 'sort-menu': openSortMenu(); break;
      case 'set-sort': state.sort = target.dataset.sort; closeModal(); renderLibrary(); break;
      case 'toggle-view': state.listMode = !state.listMode; renderLibrary(); break;
      case 'selection-mode': toast('문서 카드의 메뉴에서 복제·이동·삭제할 수 있습니다.'); break;
      case 'show-gesture-guide': showGestureGuide(); break;
      case 'open-settings': openSettings(); break;
      case 'back-library': await persistCurrent({ immediate: true }); renderLibrary(); break;
      case 'toggle-pages': state.sidebarOpen = !state.sidebarOpen; renderSidebar(); break;
      case 'close-pages': state.sidebarOpen = false; renderSidebar(); break;
      case 'open-assistant': localStudyAssistant(); break;
      case 'tools-overflow': openToolsOverflow(); break;
      case 'toggle-ruler': state.ruler.visible = !state.ruler.visible; renderPageCanvas(state.currentPageIndex); toast(state.ruler.visible ? '자를 표시했습니다.' : '자를 숨겼습니다.'); break;
      case 'toggle-audio': await toggleAudioRecording(); break;
      case 'open-math': state.pendingInsert = state.pendingInsert || { pageIndex: state.currentPageIndex, x: 130, y: 190, type: 'math' }; openMathSheet(); break;
      case 'add-page': addPage(); break;
      case 'insert-page-after': addPage(Number(target.dataset.pageIndex)); break;
      case 'share': openShareMenu(); break;
      case 'editor-more': openEditorMore(); break;
      case 'sticker-menu': openStickerMenu(); break;
      case 'select-sticker': state.sticker = target.dataset.sticker || state.sticker; closeModal(); insertSticker(state.sticker); break;
      case 'undo': undo(); break;
      case 'redo': redo(); break;
      case 'cycle-dock': {
        const positions = ['top','right','bottom','left']; state.dock = positions[(positions.indexOf(state.dock) + 1) % positions.length]; renderActiveToolMenu(); break;
      }
      case 'switch-tab': if (target.dataset.docId) openDocument(target.dataset.docId); break;
      case 'close-tab': event.stopPropagation(); closeTab(target.dataset.docId); break;
      case 'go-page': scrollToPage(Number(target.dataset.pageIndex)); break;
      case 'page-menu': event.stopPropagation(); pageMenu(Number(target.dataset.pageIndex)); break;
      case 'go-page-menu': closeModal(); scrollToPage(Number(target.dataset.pageIndex)); break;
      case 'go-page-number': goToPageNumber(); break;
      case 'go-page-number-menu': {
        closeModal();
        const input = $('#pageJumpInput');
        if (input) setTimeout(() => { input.focus(); input.select(); }, 60);
        break;
      }
      case 'calculate-page-math': closeModal(); window.__inkforge32?.processCurrentPageMath?.(); break;
      case 'bookmark-page': {
        const page = currentDocument()?.pages?.[Number(target.dataset.pageIndex)]; if (page) { checkpoint('bookmark'); page.bookmarked = !page.bookmarked; persistCurrent(); closeModal(); renderSidebar(); } break;
      }
      case 'duplicate-page': closeModal(); duplicatePage(Number(target.dataset.pageIndex)); break;
      case 'delete-page': closeModal(); deletePage(Number(target.dataset.pageIndex)); break;
      case 'move-page-up': closeModal(); movePage(Number(target.dataset.pageIndex), -1); break;
      case 'move-page-down': closeModal(); movePage(Number(target.dataset.pageIndex), 1); break;
      case 'play-audio': playAudio(target.dataset.audioId); break;
      case 'delete-audio': deleteAudio(target.dataset.audioId); break;
      case 'set-tool': setTool(target.dataset.tool); break;
      case 'select-tool-menu': closeModal(); setTool(target.dataset.tool); break;
      case 'brush-menu': openBrushMenu(); break;
      case 'select-brush': state.brush = target.dataset.brush; state.width = state.brush === 'pencil' ? 5.2 : state.brush === 'brush' ? 9 : 4.2; closeModal(); setTool('pen'); break;
      case 'set-width': {
        setToolWidth(target.dataset.width);
        break;
      }
      case 'set-color': if (state.tool === 'highlighter') state.highlighterColor = target.dataset.color; else state.color = target.dataset.color; renderActiveToolMenu(); break;
      case 'custom-color': {
        if (window.__inkforge32?.openColorMixer) window.__inkforge32.openColorMixer(target.dataset.colorTarget || null);
        else toast('색상 조합기를 준비하는 중입니다.');
        break;
      }
      case 'pen-settings': renderPenSettings(); openModal('penSettingsSheet'); break;
      case 'reset-pen-settings': state.penSettings[state.brush] = deepClone(BRUSH_META[state.brush]); renderPenSettings(); break;
      case 'set-eraser-mode': state.eraserMode = target.dataset.mode; renderActiveToolMenu(); break;
      case 'clear-page': {
        const page = currentPage(); if (page?.objects.length) { checkpoint('clear-page'); page.objects = []; state.selection = null; persistCurrent(); renderPageCanvas(state.currentPageIndex); renderSidebar(); updateObjectMenu(); } break;
      }
      case 'select-all': selectAll(); break;
      case 'paste': pasteSelection(); break;
      case 'set-shape': state.shape = target.dataset.shape; renderActiveToolMenu(); break;
      case 'set-sticky-color': state.stickyColor = target.dataset.color; renderActiveToolMenu(); break;
      case 'set-tape-color': state.tapeColor = target.dataset.color; renderActiveToolMenu(); break;
      case 'insert-text-now': state.pendingInsert = { pageIndex: state.currentPageIndex, x: 120, y: 190, type: 'text' }; openTextSheet('text'); break;
      case 'document-search': closeModal(); state.searchOpen = true; renderDocumentSearch(); setTimeout(() => $('#documentSearchInput').focus(), 80); break;
      case 'close-document-search': state.searchOpen = false; renderDocumentSearch(); break;
      case 'search-result': {
        state.searchHighlight = { pageIndex: Number(target.dataset.pageIndex), objectId: target.dataset.objectId || null }; scrollToPage(state.searchHighlight.pageIndex); renderPageCanvas(state.searchHighlight.pageIndex); break;
      }
      case 'choose-image': $('#imageInput').click(); break;
      case 'zoom-in': setZoom(state.zoom * 1.25); break;
      case 'zoom-out': setZoom(state.zoom / 1.25); break;
      case 'fit-page': closeModal(); fitPage(); break;
      case 'toggle-read-mode': state.readOnly = !state.readOnly; closeModal(); setTool(state.readOnly ? 'hand' : state.lastWritingTool); toast(state.readOnly ? '읽기 모드' : '편집 모드'); break;
      case 'toggle-page-mode': {
        state.pageMode = state.pageMode === 'continuous' ? 'single' : 'continuous'; const doc = currentDocument(); if (doc) { doc.settings = doc.settings || {}; doc.settings.pageMode = state.pageMode; persistCurrent(); } closeModal(); renderEditorPages(); break;
      }
      case 'import-note': closeModal(); $('#importInput').click(); break;
      case 'import-pdf': closeModal(); window.__inkforgePdf?.openPicker(); break;
      case 'share-native': closeModal(); await shareDocument(); break;
      case 'export-ifnote': closeModal(); exportIfnote(); break;
      case 'export-png': closeModal(); exportPng(); break;
      case 'print-pdf': closeModal(); window.print(); break;
      case 'insert-text-object': insertTextObject(); break;
      case 'calculate-math': calculateMath(); break;
      case 'insert-math': insertMathObject(); break;
      case 'math-key': {
        const input = $('#mathExpressionInput'); const key = target.dataset.key;
        if (key === 'C') input.value = ''; else if (key === '⌫') input.value = input.value.slice(0, -1); else input.value += key;
        state.math.expression = input.value; if (key !== 'C' && key !== '⌫') { try { calculateMath(); } catch {} } else renderMathResult(); input.focus(); break;
      }
      case 'toggle-favorite': {
        event.stopPropagation(); const doc = state.documents.find((item) => item.id === target.dataset.docId); if (doc) { doc.favorite = !doc.favorite; doc.updatedAt = now(); storage.putDocument(deepClone(doc)); renderLibrary(); } break;
      }
      case 'document-menu': event.stopPropagation(); documentMenu(target.dataset.docId); break;
      case 'open-document-menu': closeModal(); openDocument(target.dataset.docId); break;
      case 'rename-document': closeModal(); renameDocument(target.dataset.docId); break;
      case 'toggle-favorite-menu': {
        const doc = state.documents.find((item) => item.id === target.dataset.docId); if (doc) { doc.favorite = !doc.favorite; storage.putDocument(deepClone(doc)); closeModal(); renderLibrary(); } break;
      }
      case 'duplicate-document': closeModal(); duplicateDocument(target.dataset.docId); break;
      case 'move-document': closeModal(); moveDocumentToFolder(target.dataset.docId); break;
      case 'trash-document': closeModal(); trashDocument(target.dataset.docId); break;
      case 'restore-document': closeModal(); restoreDocument(target.dataset.docId); break;
      case 'selection-cut': copySelection(true); break;
      case 'selection-copy': copySelection(false); break;
      case 'selection-duplicate': duplicateSelection(); break;
      case 'selection-color':
        if (window.__inkforge32?.openColorMixer) window.__inkforge32.openColorMixer('selection');
        else cycleSelectionColor();
        break;
      case 'selection-lock': toggleSelectionLock(); break;
      case 'selection-front': bringSelectionFront(); break;
      case 'selection-delete': deleteSelection(); break;
      case 'assistant-keywords': case 'assistant-quiz': toast($('.menu-item-copy small', target)?.textContent || '학습 도우미'); break;
      default: break;
    }
  }

  function handleClick(event) {
    if (handleLibraryCardClick(event)) return;
    const templateOption = event.target.closest('.template-option');
    if (templateOption) {
      $$('.template-option').forEach((button) => button.classList.toggle('is-active', button === templateOption));
      $('#newNoteSheet').dataset.template = templateOption.dataset.templateId;
      return;
    }
    const folderButton = event.target.closest('[data-folder-id]');
    if (folderButton) {
      state.folderId = folderButton.dataset.folderId || 'root'; state.libraryFilter = 'all'; renderLibrary(); return;
    }
    const filterButton = event.target.closest('[data-library-filter]');
    if (filterButton) { state.libraryFilter = filterButton.dataset.libraryFilter; state.folderId = 'root'; renderLibrary(); return; }
    const toolButton = event.target.closest('button[data-tool]');
    if (toolButton && !toolButton.dataset.action) {
      const tool = toolButton.dataset.tool;
      if (tool === state.tool && tool === 'pen') { renderPenSettings(); openModal('penSettingsSheet'); }
      else setTool(tool);
      return;
    }
    const actionTarget = event.target.closest('[data-action]');
    if (actionTarget) handleAction(actionTarget.dataset.action, actionTarget, event).catch((error) => toast(error.message || String(error)));
  }

  function handleActiveToolMenuClick(event) {
    const actionTarget = event.target.closest?.('[data-action]');
    if (!actionTarget || !$('#activeToolMenu')?.contains(actionTarget)) return;
    if (actionTarget.dataset.action !== 'set-eraser-mode') return;
    event.preventDefault();
    event.stopPropagation();
    state.eraserMode = actionTarget.dataset.mode || state.eraserMode;
    renderActiveToolMenu();
  }

  function handleActiveToolWidthInput(event) {
    const input = event.target.closest?.('[data-width-input]');
    if (!input || !$('#activeToolMenu')?.contains(input)) return;
    const value = setToolWidth(input.value, input.dataset.widthTool || state.tool, event.type === 'change');
    const label = input.closest('.width-slider');
    if (label) {
      const min = Number(input.min || 0);
      const max = Number(input.max || 100);
      const rounded = Math.round(value * 10) / 10;
      label.style.setProperty('--progress', String(clamp((value - min) / Math.max(.001, max - min), 0, 1)));
      const output = $('span', label);
      if (output) output.textContent = `${rounded}px`;
      if (event.type === 'change') input.value = String(rounded);
    }
  }

  function handleCanvasTap(event) {
    const canvas = event.target.closest('.page-canvas');
    if (!canvas || state.tool !== 'tape' || state.drawSession) return;
    const point = eventPoint(event, canvas);
    const pageIndex = Number(canvas.dataset.pageIndex);
    const page = currentDocument()?.pages?.[pageIndex];
    const tape = [...(page?.objects || [])].reverse().find((object) => object.type === 'tape' && (() => { const b = computeBounds(object); return point.x >= b.x && point.x <= b.x + b.w && point.y >= b.y && point.y <= b.y + b.h; })());
    if (tape) { tape.revealed = !tape.revealed; persistCurrent(); renderPageCanvas(pageIndex); }
  }

  function handleKeydown(event) {
    const editing = /INPUT|TEXTAREA/.test(document.activeElement?.tagName || '');
    if (event.key === 'Escape') { closeModal(); state.selection = null; updateObjectMenu(); if (state.currentDocumentId) renderPageCanvas(state.currentPageIndex); return; }
    if (editing) return;
    const modifier = event.ctrlKey || event.metaKey;
    if (modifier && event.key.toLowerCase() === 'z') { event.preventDefault(); event.shiftKey ? redo() : undo(); }
    else if (modifier && event.key.toLowerCase() === 'y') { event.preventDefault(); redo(); }
    else if (modifier && event.key.toLowerCase() === 'f') { event.preventDefault(); if (state.view === 'editor') { state.searchOpen = true; renderDocumentSearch(); $('#documentSearchInput').focus(); } else { $('#globalSearchPanel').hidden = false; $('#globalSearchInput').focus(); } }
    else if (modifier && event.key.toLowerCase() === 'c' && state.selection) copySelection(false);
    else if (modifier && event.key.toLowerCase() === 'x' && state.selection) copySelection(true);
    else if (modifier && event.key.toLowerCase() === 'v') pasteSelection();
    else if (event.key === 'Delete' || event.key === 'Backspace') { if (state.selection) deleteSelection(); }
    else if (event.key.toLowerCase() === 'p') setTool('pen');
    else if (event.key.toLowerCase() === 'e') setTool('eraser');
    else if (event.key.toLowerCase() === 'l') setTool('lasso');
    else if (event.key.toLowerCase() === 'h') setTool('highlighter');
  }

  function handleWheel(event) {
    if (state.view !== 'editor') return;
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault();
      setZoom(state.zoom * Math.exp(-event.deltaY * .002), { clientX: event.clientX, clientY: event.clientY });
      return;
    }
    const viewport = $('#editorViewport');
    const horizontalDelta = Math.abs(event.deltaX) > .1 ? event.deltaX : event.shiftKey ? event.deltaY : 0;
    if (viewport && state.zoom > 1.02 && Math.abs(horizontalDelta) > .1) {
      const before = viewport.scrollLeft;
      viewport.scrollLeft += horizontalDelta;
      if (Math.abs(viewport.scrollLeft - before) > .1 || Math.abs(event.deltaX) > Math.abs(event.deltaY)) event.preventDefault();
    }
  }

  let scrollFrame = 0;
  function handleEditorScroll() {
    if (scrollFrame || state.pageMode === 'single') return;
    scrollFrame = requestAnimationFrame(() => {
      scrollFrame = 0;
      if (performance.now() < state.suppressPageUpdateUntil) {
        updateVirtualPages();
        return;
      }
      const best = bestVisiblePageIndex();
      const jump = Math.abs(best - state.currentPageIndex);
      if (jump > MAX_PASSIVE_PAGE_DELTA && performance.now() > state.allowLargePageJumpUntil) {
        updateVirtualPages();
        return;
      }
      if (best !== state.currentPageIndex) {
        const previousPageIndex = state.currentPageIndex;
        state.currentPageIndex = best;
        notifyPageChanged(previousPageIndex, 'continuous-scroll');
        updatePageIndicator(); renderSidebar(); updateObjectMenu();
        rememberCurrentPage();
      }
      updateVirtualPages();
    });
  }

  function bindEvents() {
    $('#activeToolMenu')?.addEventListener('click', handleActiveToolMenuClick, true);
    $('#activeToolMenu')?.addEventListener('input', handleActiveToolWidthInput);
    $('#activeToolMenu')?.addEventListener('change', handleActiveToolWidthInput);
    document.addEventListener('click', handleClick);
    document.addEventListener('keydown', handleKeydown);
    $('#modalBackdrop').addEventListener('click', closeModal);
    $('#pageStack').addEventListener('pointerdown', handlePointerDown, { passive: false });
    $('#pageStack').addEventListener('pointermove', handlePointerMove, { passive: false });
    $('#pageStack').addEventListener('pointerup', handlePointerUp, { passive: false });
    $('#pageStack').addEventListener('pointercancel', handlePointerCancel, { passive: false });
    $('#pageStack').addEventListener('click', handleCanvasTap);
    $('#pageStack').addEventListener('dblclick', handleDoubleClick);
    $('#editorViewport').addEventListener('scroll', handleEditorScroll, { passive: true });
    $('#editorViewport').addEventListener('wheel', handleWheel, { passive: false });
    const railTrack = $('#pageScrollTrack');
    const railThumb = $('#pageScrollThumb');
    const railInput = $('#pageJumpInput');
    if (railTrack && railThumb && railInput) {
      let draggingRail = false;
      const railMove = (event) => {
        if (!draggingRail) return;
        event.preventDefault();
        scrollToPage(pageIndexFromRailEvent(event), false);
      };
      const railUp = () => {
        draggingRail = false;
        document.removeEventListener('pointermove', railMove, true);
        document.removeEventListener('pointerup', railUp, true);
        document.removeEventListener('pointercancel', railUp, true);
      };
      railTrack.addEventListener('pointerdown', (event) => {
        event.preventDefault();
        draggingRail = true;
        scrollToPage(pageIndexFromRailEvent(event), false);
        try { railTrack.setPointerCapture?.(event.pointerId); } catch {}
        document.addEventListener('pointermove', railMove, true);
        document.addEventListener('pointerup', railUp, true);
        document.addEventListener('pointercancel', railUp, true);
      }, { passive: false });
      railInput.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') { event.preventDefault(); goToPageNumber(); }
      });
      railInput.addEventListener('change', goToPageNumber);
    }

    $('#globalSearchInput').addEventListener('input', (event) => { state.globalQuery = event.target.value; renderLibrary(); });
    $('#documentSearchInput').addEventListener('input', renderDocumentSearch);
    $('#mathExpressionInput').addEventListener('input', (event) => {
      state.math.expression = event.target.value;
      if (event.target.value.trim().endsWith('=')) calculateMath();
    });
    $('#mathExpressionInput').addEventListener('keydown', (event) => { if (event.key === 'Enter') { event.preventDefault(); calculateMath(); } });
    $('#mathDegreeMode').addEventListener('change', calculateMath);
    $('#imageInput').addEventListener('change', (event) => { const [file] = event.target.files || []; if (file) insertImageFile(file); event.target.value = ''; });
    $('#importInput').addEventListener('change', (event) => { const [file] = event.target.files || []; if (file) importIfnote(file); event.target.value = ''; });
    $('#settingsSheet').addEventListener('change', saveSettingsFromControls);
    $('#penSettingsSheet').addEventListener('input', (event) => {
      const key = event.target.dataset.penSetting;
      if (!key) return;
      const value = Number(event.target.value);
      state.penSettings[state.brush][key] = value;
      const output = $(`#pen-${key}-out`);
      if (output) output.textContent = `${Math.round(value * 100)}%`;
      renderPenPreview();
    });
    window.addEventListener('resize', () => { if (state.view === 'editor') { updatePageSizing(); updateObjectMenu(); } });
    window.addEventListener('beforeunload', () => {
      const doc = currentDocument();
      if (!doc) return;
      if (doc.pages?.length) {
        const index = clamp(state.currentPageIndex, 0, doc.pages.length - 1);
        doc.lastPageIndex = index;
        doc.lastPageId = doc.pages[index]?.id || null;
      }
      storage.putDocument(doc);
    });
  }

  async function initialize() {
    injectIcons();
    renderMathKeypad();
    bindEvents();
    await storage.init();
    const legacySettings = await storage.getSetting('appSettings', {});
    const savedPreferences = await storage.getSetting('preferences', {});
    state.settings = { ...state.settings, ...(legacySettings || {}), ...(savedPreferences || {}) };
    await storage.setSetting('preferences', state.settings);
    const savedFolders = await storage.getSetting('folders', null);
    state.folders = Array.isArray(savedFolders) ? normalizeFolders(savedFolders) : deepClone(DEFAULT_FOLDERS);
    await persistFolders();
    let docs = await storage.allDocuments();
    if (!Array.isArray(docs)) docs = [];
    state.documents = docs.map((doc) => ({
      ...doc,
      schema: doc.schema || 'com.inkforge.ifnote',
      appVersion: VERSION,
      pages: Array.isArray(doc.pages) && doc.pages.length ? doc.pages : [blankPage('grid')],
      audio: Array.isArray(doc.audio) ? doc.audio : [],
      variables: doc.variables || {},
      settings: doc.settings || { pageMode: 'continuous' }
    }));
    renderLibrary();
    updateUndoButtons();
    state.testReady = true;
    window.__inkforge = {
      VERSION,
      state,
      storage,
      evaluateMath,
      openDocument,
      addPage,
      setTool,
      setZoom,
      exportIfnote,
      renderPageCanvas,
      renderEditorPages,
      renderLibrary,
      updateVirtualPages,
      visiblePageIndexes,
      mountPageCanvas,
      scheduleRenderPage,
      renderSidebar,
      renderDocumentSearch,
      updateObjectMenu,
      checkpoint,
      persistCurrent,
      currentDocument,
      currentPage,
      computeBounds,
      toast,
      createDocument,
      createFolder,
      persistFolders,
      blankPage,
      maybeShapeFromStroke,
      renderActiveToolMenu,
      scrollToPage,
      undo,
      redo,
      ready: true
    };
  }

  initialize().catch((error) => {
    console.error(error);
    toast(`초기화 오류: ${error.message || error}`, 5000);
    window.__inkforge = { VERSION, state, ready: false, error: String(error) };
  });
})();
