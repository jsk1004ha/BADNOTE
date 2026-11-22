(() => {
  'use strict';

  function blobDataUrl(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(new Error('파일 데이터를 읽지 못했습니다.'));
      reader.readAsDataURL(blob);
    });
  }

  async function portableSource(src) {
    if (!src || src.startsWith('data:')) return src;
    const response = await fetch(src);
    if (!response.ok) throw new Error('첨부 이미지를 읽지 못했습니다.');
    return blobDataUrl(await response.blob());
  }

  // Keep the existing .ifnote schema, embedding assets so another device never
  // depends on the exporting device's IndexedDB identifiers.
  async function packDocument(document, storage, version) {
    const copy = JSON.parse(JSON.stringify(document));
    copy.exportedAt = new Date().toISOString();
    copy.appVersion = version;
    for (const page of copy.pages) {
      if (page.backgroundAssetId && !page.backgroundImage) {
        const asset = await storage.getAsset(page.backgroundAssetId);
        if (!asset?.blob) throw new Error('PDF 배경 원본을 읽지 못했습니다. 내보내기를 중단했습니다.');
        page.backgroundImage = await blobDataUrl(asset.blob);
      }
      if (page.backgroundImage) page.backgroundImage = await portableSource(page.backgroundImage);
      delete page.backgroundAssetId;
      for (const object of page.objects || []) {
        if (object.src) object.src = await portableSource(object.src);
      }
    }
    for (const clip of copy.audio || []) {
      if (clip.src) clip.src = await portableSource(clip.src);
    }
    return copy;
  }

  // Use the editor renderer to preserve Korean text, templates, PDF backgrounds
  // and ink. Blob parts avoid base64 copies of the entire PDF in memory.
  async function buildPdf(document, renderPage, onProgress = () => {}) {
    if (!document.pages?.length) throw new Error('내보낼 페이지가 없습니다.');
    const parts = [];
    const offsets = [0];
    let position = 0;
    const append = part => {
      const blob = part instanceof Blob ? part : new Blob([part]);
      parts.push(blob); position += blob.size;
    };
    const object = (id, body) => {
      offsets[id] = position;
      append(`${id} 0 obj\n${body}\nendobj\n`);
    };
    append('%PDF-1.4\n');
    object(1, '<< /Type /Catalog /Pages 2 0 R >>');
    const count = document.pages.length;
    object(2, `<< /Type /Pages /Count ${count} /Kids [${document.pages.map((_, i) => `${3 + i * 3} 0 R`).join(' ')}] >>`);
    for (let i = 0; i < count; i++) {
      onProgress(i + 1, count);
      const page = document.pages[i];
      const canvas = await renderPage(page, i);
      const jpeg = await new Promise((resolve, reject) => canvas.toBlob(
        blob => blob ? resolve(blob) : reject(new Error('PDF 페이지를 만들지 못했습니다.')), 'image/jpeg', .95));
      // Notes use a fixed paper coordinate system even for imported PDFs.
      // Match that paper aspect ratio instead of stretching ink/text to source dimensions.
      const width = 595.28;
      const height = width * canvas.height / canvas.width;
      const id = 3 + i * 3;
      object(id, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${width} ${height}] /Resources << /XObject << /Im0 ${id + 1} 0 R >> >> /Contents ${id + 2} 0 R >>`);
      offsets[id + 1] = position;
      append(`${id + 1} 0 obj\n<< /Type /XObject /Subtype /Image /Width ${canvas.width} /Height ${canvas.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n`);
      append(jpeg);
      append('\nendstream\nendobj\n');
      const commands = `q\n${width} 0 0 ${height} 0 0 cm\n/Im0 Do\nQ\n`;
      object(id + 2, `<< /Length ${commands.length} >>\nstream\n${commands}endstream`);
      canvas.width = canvas.height = 1;
      await new Promise(resolve => setTimeout(resolve, 0));
    }
    const xref = position;
    append(`xref\n0 ${offsets.length}\n0000000000 65535 f \n`);
    for (let i = 1; i < offsets.length; i++) append(`${String(offsets[i]).padStart(10, '0')} 00000 n \n`);
    append(`trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
    return new Blob(parts, { type: 'application/pdf' });
  }

  window.InkForgeFileExport = { blobDataUrl, packDocument, buildPdf };
})();
