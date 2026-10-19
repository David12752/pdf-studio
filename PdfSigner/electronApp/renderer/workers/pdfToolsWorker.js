/* global self, importScripts, PDFLib */

importScripts('../../node_modules/pdf-lib/dist/pdf-lib.min.js');

const { PDFDocument } = PDFLib;

function base64ToBytes(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function bytesToBase64(bytes) {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}

async function mergePdfs(filesBase64) {
  const merged = await PDFDocument.create();
  for (const sourceBase64 of filesBase64) {
    const source = await PDFDocument.load(base64ToBytes(sourceBase64));
    const pages = await merged.copyPages(source, source.getPageIndices());
    pages.forEach((page) => merged.addPage(page));
  }
  return { mergedBase64: bytesToBase64(await merged.save()) };
}

async function splitPdf(sourceBase64, pages) {
  const source = await PDFDocument.load(base64ToBytes(sourceBase64));
  const selected = pages?.length ? pages : source.getPageIndices().map((i) => i + 1);
  const files = [];
  for (const pageNum of selected) {
    const outDoc = await PDFDocument.create();
    const [page] = await outDoc.copyPages(source, [pageNum - 1]);
    outDoc.addPage(page);
    files.push({
      pageNum,
      base64: bytesToBase64(await outDoc.save()),
    });
  }
  return { files };
}

async function extractPage(sourceBase64, pageNum) {
  const source = await PDFDocument.load(base64ToBytes(sourceBase64));
  const outDoc = await PDFDocument.create();
  const [page] = await outDoc.copyPages(source, [pageNum - 1]);
  outDoc.addPage(page);
  return { extractedBase64: bytesToBase64(await outDoc.save()) };
}

self.onmessage = async (event) => {
  const { id, task, payload } = event.data || {};
  try {
    let result;
    if (task === 'merge') result = await mergePdfs(payload.filesBase64);
    else if (task === 'split') result = await splitPdf(payload.sourceBase64, payload.pages);
    else if (task === 'extract') result = await extractPage(payload.sourceBase64, payload.pageNum);
    else throw new Error(`Unknown task: ${task}`);

    self.postMessage({ id, ok: true, result });
  } catch (error) {
    self.postMessage({ id, ok: false, error: error?.message || 'Worker task failed' });
  }
};
