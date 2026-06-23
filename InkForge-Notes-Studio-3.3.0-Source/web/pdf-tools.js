(() => {
  'use strict';

  let api = null;
  let pdfjs = null;
  let busy = false;
  let pickerMode = 'append';
  const PAGE_WIDTH = 1000;
  const PAGE_HEIGHT = 1414;
  const uid = (prefix = 'id') => `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 9)}`;
  const idle = () => new Promise((resolve) => {
    if ('requestIdleCallback' in window) requestIdleCallback(() => resolve(), { timeout: 60 });
    else setTimeout(resolve, 0);
  });

  function showProgress(text, fraction = 0, detail = '') {
    let node = document.getElementById('pdfImportProgress');
    if (!node) {
      node = document.createElement('div');
      node.id = 'pdfImportProgress';
      node.className = 'pdf-import-progress';
      node.innerHTML = '<div class="pdf-progress-title"></div><div class="pdf-progress-detail"></div><div class="pdf-progress-track"><span></span></div>';
      document.body.appendChild(node);
    }
    node.querySelector('.pdf-progress-title').textContent = text;
    node.querySelector('.pdf-progress-detail').textContent = detail;
    node.querySelector('.pdf-progress-track span').style.width = `${Math.max(0, Math.min(100, fraction * 100))}%`;
    node.hidden = false;
  }

  function hideProgress() {
    const node = document.getElementById('pdfImportProgress');
    if (node) node.hidden = true;
  }

  async function ensurePdfJs() {
    if (pdfjs) return pdfjs;
    showProgress('PDF 엔진을 준비하는 중…', .02);
    pdfjs = await import('./vendor/pdf.mjs');
    pdfjs.GlobalWorkerOptions.workerSrc = new URL('./vendor/pdf.worker.mjs', window.location.href).href;
    return pdfjs;
  }

  function composePage(renderedCanvas) {
    const output = document.createElement('canvas');
    output.width = PAGE_WIDTH;
    output.height = PAGE_HEIGHT;
    const ctx = output.getContext('2d', { alpha: false, desynchronized: true });
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
    const scale = Math.min(PAGE_WIDTH / renderedCanvas.width, PAGE_HEIGHT / renderedCanvas.height);
    const width = renderedCanvas.width * scale;
    const height = renderedCanvas.height * scale;
    ctx.drawImage(renderedCanvas, (PAGE_WIDTH - width) / 2, (PAGE_HEIGHT - height) / 2, width, height);
    return output;
  }

  function canvasToBlob(canvas, quality = .82) {
    return new Promise((resolve, reject) => canvas.toBlob((blob) => {
      if (blob) resolve(blob);
      else reject(new Error('PDF 페이지 이미지를 압축하지 못했습니다.'));
    }, 'image/jpeg', quality));
  }

  function baseName(file) {
    return String(file?.name || 'PDF 노트').replace(/\.pdf$/i, '').trim() || 'PDF 노트';
  }

  async function createTargetDocument(file, mode) {
    if (mode !== 'new') {
      const existing = api?.currentDocument?.();
      if (!existing) throw new Error('먼저 노트를 열거나 “PDF로 새 노트”를 선택하세요.');
      return {
        doc: existing,
        insertAt: Math.min(existing.pages.length, (api.state.currentPageIndex || 0) + 1),
        isNew: false
      };
    }

    const doc = api.createDocument(baseName(file), 'blank');
    doc.pages = [];
    doc.coverColor = '#2f7fb7';
    doc.pdfSourceName = file.name;
    api.state.documents.unshift(doc);
    api.state.currentDocumentId = doc.id;
    api.state.currentPageIndex = 0;
    await api.storage.putDocument(doc);
    api.openDocument(doc.id, { pageIndex: 0 });
    document.getElementById('newNoteSheet')?.setAttribute('hidden', '');
    const backdrop = document.getElementById('modalBackdrop');
    if (backdrop) backdrop.hidden = true;
    document.body.classList.remove('modal-open');
    return { doc, insertAt: 0, isNew: true };
  }

  async function importPdf(file, options = {}) {
    if (busy) return;
    if (!file || !/\.pdf$/i.test(file.name || '') && file.type !== 'application/pdf') {
      api?.toast?.('PDF 파일을 선택하세요.');
      return;
    }
    const mode = options.createNew || pickerMode === 'new' ? 'new' : 'append';
    busy = true;
    let pdf = null;
    try {
      const lib = await ensurePdfJs();
      showProgress('PDF 파일을 읽는 중…', .04, `${Math.round(file.size / 1024 / 1024 * 10) / 10} MB`);
      const data = new Uint8Array(await file.arrayBuffer());
      const task = lib.getDocument({
        data,
        useWorkerFetch: false,
        isEvalSupported: false,
        disableAutoFetch: true,
        disableStream: false
      });
      pdf = await task.promise;
      const { doc, insertAt, isNew } = await createTargetDocument(file, mode);
      const importedPages = [];
      if (!isNew) api.checkpoint?.('pdf-import');

      for (let number = 1; number <= pdf.numPages; number++) {
        const fraction = .06 + .9 * ((number - 1) / Math.max(1, pdf.numPages));
        showProgress(
          `PDF ${number} / ${pdf.numPages}페이지 가져오는 중`,
          fraction,
          '페이지 자산을 분리 저장하고 보이는 페이지만 렌더링합니다.'
        );
        const pdfPage = await pdf.getPage(number);
        const base = pdfPage.getViewport({ scale: 1 });
        const targetWidth = Math.min(1180, Math.max(820, base.width * 1.45));
        const viewport = pdfPage.getViewport({ scale: targetWidth / Math.max(1, base.width) });
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.ceil(viewport.width));
        canvas.height = Math.max(1, Math.ceil(viewport.height));
        const context = canvas.getContext('2d', { alpha: false, desynchronized: true });
        await pdfPage.render({ canvasContext: context, viewport, background: '#ffffff' }).promise;

        let pdfText = '';
        try {
          const content = await pdfPage.getTextContent({ includeMarkedContent: false, disableNormalization: false });
          pdfText = content.items.map((item) => item.str || '').join(' ').replace(/\s+/g, ' ').trim();
        } catch {}

        const composed = composePage(canvas);
        const assetId = uid('pdfasset');
        const quality = pdf.numPages > 120 ? .70 : pdf.numPages > 60 ? .75 : .82;
        const blob = await canvasToBlob(composed, quality);
        await api.storage.putAsset({
          id: assetId,
          kind: 'pdf-page-jpeg',
          blob,
          sourceName: file.name,
          pageNumber: number,
          width: PAGE_WIDTH,
          height: PAGE_HEIGHT,
          createdAt: new Date().toISOString()
        });

        const page = {
          id: uid('page'),
          template: 'blank',
          title: `${baseName(file)} ${number}`,
          bookmarked: false,
          createdAt: new Date().toISOString(),
          objects: [],
          backgroundAssetId: assetId,
          pdfText,
          pdfSourceName: file.name,
          pdfPageNumber: number,
          needsImageOcr: !pdfText,
          importedFromPdf: true
        };
        importedPages.push(page);
        doc.pages.splice(insertAt + importedPages.length - 1, 0, page);
        doc.updatedAt = new Date().toISOString();

        pdfPage.cleanup();
        canvas.width = canvas.height = composed.width = composed.height = 1;
        if (number % 6 === 0 || number === pdf.numPages) await api.storage.putDocument(doc);
        window.dispatchEvent(new CustomEvent('inkforge:pdf-page-imported', {
          detail: {
            documentId: doc.id,
            pageId: page.id,
            pageIndex: insertAt + importedPages.length - 1,
            needsImageOcr: page.needsImageOcr
          }
        }));
        await idle();
      }

      if (!doc.pages.length) doc.pages.push(api.blankPage('blank'));
      api.state.currentDocumentId = doc.id;
      api.state.currentPageIndex = insertAt;
      await api.storage.putDocument(doc);
      api.renderEditorPages?.();
      api.renderSidebar?.();
      api.renderDocumentSearch?.();
      requestAnimationFrame(() => api.scrollToPage?.(insertAt, false));
      showProgress(
        `${importedPages.length}페이지 가져오기 완료`,
        1,
        '스캔 페이지의 한글·영문 OCR은 현재 페이지 체류 후 또는 유휴 상태에서 검색 색인에 자동 등록됩니다.'
      );
      api.toast?.(`PDF ${importedPages.length}페이지를 ${isNew ? '새 노트로 만들었습니다.' : '추가했습니다.'}`, 3600);
      setTimeout(hideProgress, 1600);
    } catch (error) {
      console.error('PDF import failed', error);
      hideProgress();
      api?.toast?.(`PDF 가져오기 실패: ${error?.message || error}`, 5200);
    } finally {
      try { await pdf?.destroy?.(); } catch {}
      busy = false;
      pickerMode = 'append';
      const input = document.getElementById('pdfInput');
      if (input) input.value = '';
    }
  }

  function openPicker(options = {}) {
    if (busy) return;
    pickerMode = options.createNew ? 'new' : 'append';
    document.getElementById('pdfInput')?.click();
  }

  async function initialize() {
    for (let attempt = 0; attempt < 160; attempt++) {
      if (window.__inkforge?.ready) {
        api = window.__inkforge;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    if (!api) return;
    const input = document.getElementById('pdfInput');
    input?.addEventListener('change', () => {
      const file = input.files?.[0];
      if (file) importPdf(file, { createNew: pickerMode === 'new' });
    });
    window.__inkforgePdf = {
      openPicker,
      importPdf,
      get busy() { return busy; },
      ready: true
    };
  }

  initialize().catch((error) => console.error('InkForge PDF tools initialization failed', error));
})();
