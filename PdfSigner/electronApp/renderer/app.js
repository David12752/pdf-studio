(() => {
const { PDFDocument, rgb, StandardFonts, degrees } = window.PDFLib;
const pdfjsLib = window.pdfjsLib;
const nativeApi = window.nativeApi;
const {
  parsePageList,
  parseSessionIdFromDeepLink,
  computePdfPlacement,
  transitionRemoteStatus,
} = window.PdfStudioPure;
const { createRemoteFeature } = window.PdfStudioRemote;
const { createAuthFeature } = window.PdfStudioAuth;
const { createWizardFeature } = window.PdfStudioWizard;
const { createPdfToolsWorkerClient } = window.PdfStudioWorkers;
const MAX_TOOL_FILE_MB = 150;

pdfjsLib.GlobalWorkerOptions.workerSrc = '../node_modules/pdfjs-dist/build/pdf.worker.min.js';

function base64ToBytes(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function bytesToBase64(bytes) {
  return nativeApi.bytesToBase64(bytes);
}

const fs = {
  mkdirSync: (targetPath) => nativeApi.mkdirRecursive(targetPath),
  existsSync: (targetPath) => nativeApi.exists(targetPath),
  readFileSync: (filePath, encoding) => {
    if (encoding === 'utf8') return nativeApi.readText(filePath);
    return base64ToBytes(nativeApi.readFileBase64(filePath));
  },
  writeFileSync: (filePath, data, encoding) => {
    if (encoding === 'utf8') {
      nativeApi.writeText(filePath, data);
      return;
    }
    if (typeof data === 'string') {
      nativeApi.writeText(filePath, data);
      return;
    }
    nativeApi.writeFileBase64(filePath, bytesToBase64(data));
  },
};

const path = {
  join: (...parts) => nativeApi.joinPath(...parts),
  basename: (filePath, ext) => nativeApi.basename(filePath, ext),
};

const os = {
  homedir: () => nativeApi.homedir(),
  hostname: () => nativeApi.hostname(),
  platform: () => nativeApi.platform(),
  release: () => nativeApi.release(),
};

const state = {
  currentPdfPath: null,
  currentPdfBytes: null,
  currentPdfJsDoc: null,
  currentViewerPath: null,
  currentViewerKind: null,
  currentViewerEncoding: null,
  viewerZoom: 1.4,
  currentPage: 0,
  signatureDataUrl: null,
  signatureStrokes: [],
  textStamps: [],
  placement: { xRatio: 0.15, yRatio: 0.2, wRatio: 0.28, hRatio: 0.12, pageIndex: 0 },
  dragging: false,
  resizing: false,
  dragOffset: { x: 0, y: 0 },
  remoteSessions: [],
  // Signature ink
  inkColor: '#000000',
  inkThickness: 3,
  // Text stamp UI state
  textStampFontSize: 20,
  textStampColor: '#000000',
  watermarkColor: '#999999',
  // Reading mode
  readingMode: 'normal',
  // Sign step: 0=load,1=sign,2=place,3=export
  signStep: 0,
};

const pdfToolsWorker = createPdfToolsWorkerClient();

const $ = (id) => document.getElementById(id);
const toast = (message) => {
  const box = $('toast');
  box.textContent = message;
  box.style.display = 'block';
  setTimeout(() => (box.style.display = 'none'), 2500);
};

const setStatus = (message) => {
  const bar = $('statusBar');
  if (!message) {
    bar.style.display = 'none';
    bar.textContent = '';
    return;
  }
  bar.textContent = message;
  bar.style.display = 'block';
};

function assertFileSizeAllowed(file) {
  const maxBytes = MAX_TOOL_FILE_MB * 1024 * 1024;
  if (!file || typeof file.size !== 'number') return true;
  if (file.size <= maxBytes) return true;
  toast(`File too large (${MAX_TOOL_FILE_MB}MB limit)`);
  return false;
}

function isPdfPath(filePath) {
  return String(filePath || '').toLowerCase().endsWith('.pdf');
}

function isProtectedEnvelopePath(filePath) {
  return String(filePath || '').toLowerCase().endsWith('.pdfs');
}

function isImagePath(filePath) {
  return /\.(png|jpe?g|gif|bmp|webp|svg)$/i.test(String(filePath || ''));
}

function isTextLikePath(filePath) {
  return /\.(txt|md|json|csv|log|xml|ya?ml|ini|cfg|conf|html?|css|js|jsx|ts|tsx)$/i.test(String(filePath || ''));
}

function bytesToEditableHex(bytes) {
  const lines = [];
  for (let offset = 0; offset < bytes.length; offset += 16) {
    const slice = bytes.slice(offset, offset + 16);
    const hex = Array.from(slice).map((value) => value.toString(16).padStart(2, '0')).join(' ');
    lines.push(hex);
  }
  return lines.join('\n');
}

function editableHexToBytes(text) {
  const normalized = String(text || '').replace(/[^0-9a-f]/gi, '');
  if (!normalized.length || normalized.length % 2 !== 0) {
    throw new Error('Hex editor content must contain an even number of hex digits');
  }
  const bytes = new Uint8Array(normalized.length / 2);
  for (let i = 0; i < normalized.length; i += 2) {
    bytes[i / 2] = Number.parseInt(normalized.slice(i, i + 2), 16);
  }
  return bytes;
}

function isPdfBytes(bytes) {
  return bytes?.[0] === 0x25 && bytes?.[1] === 0x50 && bytes?.[2] === 0x44 && bytes?.[3] === 0x46;
}

function setViewerMode(mode) {
  const canvasWrap = $('viewerCanvasWrap');
  const textWrap = $('viewerTextWrap');
  const statsPanel = $('pdfStatsPanel');
  if (canvasWrap) canvasWrap.style.display = mode === 'text' ? 'none' : '';
  if (textWrap) textWrap.style.display = mode === 'text' ? 'flex' : 'none';
  if (statsPanel && mode !== 'pdf') statsPanel.style.display = 'none';
  state.currentViewerKind = mode;
}

async function renderViewerPdf(filePath, bytes) {
  const viewerCanvas = $('viewerCanvas');
  const viewerInfo = $('viewerInfo');
  const pdfjsDoc = await pdfjsLib.getDocument({ data: bytes }).promise;
  const page = await pdfjsDoc.getPage(1);
  const viewport = page.getViewport({ scale: state.viewerZoom });
  viewerCanvas.width = viewport.width;
  viewerCanvas.height = viewport.height;
  await page.render({ canvasContext: viewerCanvas.getContext('2d'), viewport }).promise;
  viewerCanvas._bytes = bytes;
  viewerCanvas._path = filePath;
  viewerInfo.textContent = `${path.basename(filePath)} (${pdfjsDoc.numPages} pages · ${Math.round(state.viewerZoom * 100)}% zoom)`;
}

function writeViewerBufferToFile(filePath, bytes) {
  nativeApi.writeFileBase64(filePath, bytesToBase64(bytes));
}

async function unlockProtectedEnvelope(filePath, password, shouldOpenViewer = true) {
  const raw = fs.readFileSync(filePath, 'utf8');
  const envelope = JSON.parse(raw);
  if (envelope?.type !== 'pdf-studio-protected-envelope') {
    throw new Error('Unsupported protected file format');
  }
  const decryptedBase64 = nativeApi.decryptTextAesGcm(envelope, password);
  const restoredBytes = base64ToBytes(decryptedBase64);
  if (!isPdfBytes(restoredBytes)) {
    throw new Error('Unlocked file is not a valid PDF');
  }
  const restoredName = String(envelope.docName || path.basename(filePath, '.pdfs')).replace(/[^a-z0-9._ -]/gi, '_');
  const out = path.join(exportDir, `${restoredName.replace(/\.pdf$/i, '')}_unlocked_${Date.now()}.pdf`);
  writeViewerBufferToFile(out, restoredBytes);
  if (shouldOpenViewer) {
    activateTab('viewer');
    await openFileInViewer(out);
  }
  toast(`Unlocked: ${path.basename(out)}`);
  return out;
}

const appDataDir = path.join(os.homedir(), 'PDF Studio');
const exportDir = path.join(appDataDir, 'Exports');
const sessionsFile = path.join(appDataDir, 'remote_sessions.json');
const settingsFile = path.join(appDataDir, 'settings.json');
const recentsFile = path.join(appDataDir, 'recents.json');
const savedSigsFile = path.join(appDataDir, 'saved_signatures.json');
fs.mkdirSync(exportDir, { recursive: true });

const saveJson = (file, data) => fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf8');
const loadJson = (file, fallback) => {
  try {
    if (!fs.existsSync(file)) return fallback;
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
};

/* ── Cloud-aware save/load wrappers ─────────────────────────────── */
let _authFeature = null;

function cloudSaveSettings(data) {
  saveJson(settingsFile, data);
  if (_authFeature && _authFeature.isLoggedIn()) {
    nativeApi.cloudSet('settings', data).catch(() => {});
  }
}

function cloudSaveSignatures(items) {
  saveJson(savedSigsFile, items);
  if (_authFeature && _authFeature.isLoggedIn()) {
    nativeApi.cloudSetCollection('signatures', items).catch(() => {});
  }
}

function cloudSaveRecents(items) {
  saveJson(recentsFile, items);
  if (_authFeature && _authFeature.isLoggedIn()) {
    nativeApi.cloudSetCollection('recents', items).catch(() => {});
  }
}

async function cloudSyncFromServer() {
  if (!_authFeature || !_authFeature.isLoggedIn()) return;
  try {
    const result = await nativeApi.cloudSyncDownload();
    if (!result.ok || !result.data) return;
    const d = result.data;
    if (d.settings) saveJson(settingsFile, d.settings);
    if (d.signatures && d.signatures.length) saveJson(savedSigsFile, d.signatures);
    if (d.recents && d.recents.length) saveJson(recentsFile, d.recents);
  } catch (_) { /* offline — use local data */ }
}

async function cloudSyncToServer() {
  if (!_authFeature || !_authFeature.isLoggedIn()) return;
  try {
    const settings   = loadJson(settingsFile, {});
    const signatures = loadJson(savedSigsFile, []);
    const recents    = loadJson(recentsFile, []);
    await nativeApi.cloudSyncUpload({ settings, signatures, recents });
  } catch (_) { /* offline — will sync later */ }
}

function wireTabs() {
  const buttons = [...document.querySelectorAll('.tab-btn')];
  buttons.forEach((btn) => {
    btn.addEventListener('click', () => {
      buttons.forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      const tab = btn.dataset.tab;
      [...document.querySelectorAll('.tab')].forEach((section) => {
        section.classList.toggle('active', section.id === tab);
      });
    });
  });
}

function activateTab(tabId) {
  const buttons = [...document.querySelectorAll('.tab-btn')];
  buttons.forEach((btn) => {
    const isActive = btn.dataset.tab === tabId;
    btn.classList.toggle('active', isActive);
  });
  [...document.querySelectorAll('.tab')].forEach((section) => {
    section.classList.toggle('active', section.id === tabId);
  });
}

function wireDeepLinkHandler(remoteFeature) {
  nativeApi.onDeepLink((link) => {
    const sessionId = parseSessionIdFromDeepLink(link);
    if (!sessionId) {
      toast('Invalid deep link');
      return;
    }
    activateTab('remote');
    if (remoteFeature && typeof remoteFeature.handleDeepLink === 'function') {
      remoteFeature.handleDeepLink(sessionId).catch((error) => {
        console.error('[deeplink] Failed to handle deep link:', error);
        toast(`Deep link opened for session ${sessionId}`);
      });
      return;
    }
    toast(`Deep link opened for session ${sessionId}`);
  });
}

async function extractTextWithPdfJs(bytes) {
  const doc = await pdfjsLib.getDocument({ data: bytes }).promise;
  const lines = [];
  for (let i = 1; i <= doc.numPages; i += 1) {
    const page = await doc.getPage(i);
    const textContent = await page.getTextContent();
    const text = textContent.items.map((item) => item.str || '').join(' ');
    lines.push(text);
  }
  return lines.join('\n\n');
}

async function loadPdfFromPath(filePath) {
  try {
    const bytes = fs.readFileSync(filePath);
    if (!isPdfBytes(bytes)) throw new Error('Selected file is not a valid PDF');
    state.currentPdfPath = filePath;
    state.currentPdfBytes = bytes;
    state.currentPdfJsDoc = await pdfjsLib.getDocument({ data: bytes }).promise;
    state.currentPage = 0;
    state.placement.pageIndex = 0;
    addRecent(filePath);
    await renderCurrentPage();
    setSignStep(1);
    const emptyState = $('pdfEmptyState');
    if (emptyState) emptyState.style.display = 'none';
    $('viewerInfo').textContent = `${path.basename(filePath)} (${state.currentPdfJsDoc.numPages} pages)`;
    toast('PDF loaded');
  } catch (error) {
    console.error('[viewer] Failed to load PDF:', error);
    toast(error?.message || 'Failed to load PDF');
  }
}

async function openFileInViewer(filePath) {
  if (!filePath) return;
  state.currentViewerPath = filePath;
  const viewerInfo = $('viewerInfo');
  const viewerCanvas = $('viewerCanvas');
  const viewerTextEditor = $('viewerTextEditor');
  const viewerTextMeta = $('viewerTextMeta');

  if (isProtectedEnvelopePath(filePath)) {
    const password = window.prompt('Enter the password for this protected PDF');
    if (!password) return;
    try {
      await unlockProtectedEnvelope(filePath, password, true);
    } catch (error) {
      toast(error?.message || 'Failed to unlock protected PDF');
    }
    return;
  }

  if (isPdfPath(filePath)) {
    setViewerMode('pdf');
    state.currentViewerEncoding = 'pdf';
    const bytes = fs.readFileSync(filePath);
    if (!isPdfBytes(bytes)) throw new Error('File extension says PDF, but content is invalid');
    await renderViewerPdf(filePath, bytes);
    return;
  }

  if (isImagePath(filePath)) {
    setViewerMode('image');
    state.currentViewerEncoding = 'image';
    await new Promise((resolve) => {
      const img = new Image();
      img.onload = () => {
        viewerCanvas.width = img.naturalWidth;
        viewerCanvas.height = img.naturalHeight;
        viewerCanvas.getContext('2d').clearRect(0, 0, viewerCanvas.width, viewerCanvas.height);
        viewerCanvas.getContext('2d').drawImage(img, 0, 0);
        resolve();
      };
      img.src = `file://${filePath.replace(/\\/g, '/')}`;
    });
    viewerInfo.textContent = `${path.basename(filePath)} (image preview)`;
    return;
  }

  if (isTextLikePath(filePath)) {
    setViewerMode('text');
    state.currentViewerEncoding = 'text';
    const text = fs.readFileSync(filePath, 'utf8');
    viewerTextEditor.value = text;
    viewerTextMeta.textContent = `${path.basename(filePath)} · editable text`;
    viewerInfo.textContent = `${path.basename(filePath)} (${text.length.toLocaleString()} chars)`;
    return;
  }

  setViewerMode('text');
  state.currentViewerEncoding = 'hex';
  const bytes = fs.readFileSync(filePath);
  viewerTextEditor.value = bytesToEditableHex(bytes);
  viewerTextMeta.textContent = `${path.basename(filePath)} · binary hex editor`;
  viewerInfo.textContent = `${path.basename(filePath)} (${bytes.length.toLocaleString()} bytes)`;
}

function addRecent(filePath) {
  const recents = loadJson(recentsFile, []);
  const filtered = recents.filter((item) => item.path !== filePath);
  filtered.unshift({ path: filePath, name: path.basename(filePath), at: Date.now() });
  const updated = filtered.slice(0, 15);
  cloudSaveRecents(updated);
  renderRecents();
}

function renderRecents() {
  const recents = loadJson(recentsFile, []);
  $('recentFiles').innerHTML = '';
  for (const item of recents) {
    const li = document.createElement('li');
    li.innerHTML = `<strong>${item.name}</strong><div class="small">${item.path}</div>`;
    li.addEventListener('click', async () => loadPdfFromPath(item.path));
    $('recentFiles').appendChild(li);
  }
}

async function renderCurrentPage() {
  if (!state.currentPdfJsDoc) return;
  const page = await state.currentPdfJsDoc.getPage(state.currentPage + 1);
  const viewport = page.getViewport({ scale: 1.4 });
  const pdfCanvas = $('pdfCanvas');
  const overlayCanvas = $('overlayCanvas');
  pdfCanvas.width = viewport.width;
  pdfCanvas.height = viewport.height;
  overlayCanvas.width = viewport.width;
  overlayCanvas.height = viewport.height;

  await page.render({ canvasContext: pdfCanvas.getContext('2d'), viewport }).promise;
  renderOverlay();

  $('pageLabel').textContent = `Page ${state.currentPage + 1} / ${state.currentPdfJsDoc.numPages}`;
}

function renderOverlay() {
  const overlay = $('overlayCanvas');
  const ctx = overlay.getContext('2d');
  ctx.clearRect(0, 0, overlay.width, overlay.height);

  const px = state.placement.xRatio * overlay.width;
  const py = state.placement.yRatio * overlay.height;
  const pw = state.placement.wRatio * overlay.width;
  const ph = state.placement.hRatio * overlay.height;

  ctx.strokeStyle = '#2f88ff';
  ctx.lineWidth = 2;
  ctx.strokeRect(px, py, pw, ph);

  if (state.signatureDataUrl) {
    const img = new Image();
    img.onload = () => ctx.drawImage(img, px, py, pw, ph);
    img.src = state.signatureDataUrl;
  }

  ctx.fillStyle = '#2f88ff';
  ctx.fillRect(px + pw - 8, py + ph - 8, 8, 8);

  state.textStamps
    .filter((t) => t.pageIndex === state.currentPage)
    .forEach((t) => {
      const size = t.size || 20;
      ctx.save();
      if (t.rotate) {
        const cx = t.xRatio * overlay.width  + size * 2;
        const cy = t.yRatio * overlay.height + size * 2;
        ctx.translate(cx, cy);
        ctx.rotate(-Math.PI / 5);
        ctx.translate(-cx, -cy);
      }
      ctx.font = `${t.bold ? 'bold ' : ''}${size}px Segoe UI`;
      ctx.fillStyle = t.color
        ? (t.opacity < 1 ? hexToRgba(t.color, t.opacity) : t.color)
        : `rgba(0,0,0,${t.opacity})`;
      ctx.fillText(t.text, t.xRatio * overlay.width, t.yRatio * overlay.height);
      ctx.restore();
    });
}

function hexToRgba(hex, alpha) {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r},${g},${b},${alpha})`;
}

function hexToRgbObject(hex) {
  return {
    r: parseInt(hex.slice(1, 3), 16) / 255,
    g: parseInt(hex.slice(3, 5), 16) / 255,
    b: parseInt(hex.slice(5, 7), 16) / 255,
  };
}

function wireOverlayDrag() {
  const overlay = $('overlayCanvas');
  const HANDLE_SIZE = 12;

  function getPlacementPx() {
    return {
      px: state.placement.xRatio * overlay.width,
      py: state.placement.yRatio * overlay.height,
      pw: state.placement.wRatio * overlay.width,
      ph: state.placement.hRatio * overlay.height,
    };
  }

  overlay.addEventListener('mousedown', (e) => {
    const rect = overlay.getBoundingClientRect();
    const x = (e.clientX - rect.left) * (overlay.width  / rect.width);
    const y = (e.clientY - rect.top ) * (overlay.height / rect.height);
    const { px, py, pw, ph } = getPlacementPx();

    // Resize handle — bottom-right corner
    if (Math.abs(x - (px + pw)) < HANDLE_SIZE && Math.abs(y - (py + ph)) < HANDLE_SIZE) {
      state.resizing   = true;
      state.dragOffset = { x, y };
      return;
    }
    // Move handle — inside box
    if (x >= px && x <= px + pw && y >= py && y <= py + ph) {
      state.dragging   = true;
      state.dragOffset = { x: x - px, y: y - py };
    }
  });

  overlay.addEventListener('mousemove', (e) => {
    const rect = overlay.getBoundingClientRect();
    const x = (e.clientX - rect.left) * (overlay.width  / rect.width);
    const y = (e.clientY - rect.top ) * (overlay.height / rect.height);
    const { px, py, pw, ph } = getPlacementPx();

    // Update cursor
    if (Math.abs(x - (px + pw)) < HANDLE_SIZE && Math.abs(y - (py + ph)) < HANDLE_SIZE) {
      overlay.style.cursor = 'se-resize';
    } else if (x >= px && x <= px + pw && y >= py && y <= py + ph) {
      overlay.style.cursor = 'move';
    } else {
      overlay.style.cursor = 'default';
    }

    if (state.resizing) {
      const dx = x - state.dragOffset.x;
      const dy = y - state.dragOffset.y;
      state.dragOffset = { x, y };
      const newW = Math.max(0.05, state.placement.wRatio + dx / overlay.width);
      // Maintain aspect ratio
      const aspectRatio = state.placement.hRatio / state.placement.wRatio;
      state.placement.wRatio = Math.min(1 - state.placement.xRatio, newW);
      state.placement.hRatio = Math.min(1 - state.placement.yRatio, state.placement.wRatio * aspectRatio);
      renderOverlay();
      return;
    }

    if (state.dragging) {
      const nx = x - state.dragOffset.x;
      const ny = y - state.dragOffset.y;
      state.placement.xRatio = Math.max(0, Math.min(1 - state.placement.wRatio, nx / overlay.width));
      state.placement.yRatio = Math.max(0, Math.min(1 - state.placement.hRatio, ny / overlay.height));
      state.placement.pageIndex = state.currentPage;
      renderOverlay();
    }
  });

  window.addEventListener('mouseup', () => {
    state.dragging = false;
    state.resizing = false;
    if ($('overlayCanvas')) $('overlayCanvas').style.cursor = 'default';
  });
}

function setSignStep(step) {
  state.signStep = step;
  const steps = ['stepLoad', 'stepSign', 'stepPlace', 'stepExport'];
  steps.forEach((id, i) => {
    const el = $(id);
    if (!el) return;
    el.classList.remove('active', 'done');
    if (i < step)  el.classList.add('done');
    if (i === step) el.classList.add('active');
  });
}

function updateThicknessPreview() {
  const c = $('thicknessPreview');
  if (!c) return;
  const ctx = c.getContext('2d');
  ctx.clearRect(0, 0, c.width, c.height);
  ctx.beginPath();
  ctx.arc(10, 10, state.inkThickness * 1.2, 0, Math.PI * 2);
  ctx.fillStyle = state.inkColor;
  ctx.fill();
}

function wireSignatureCanvas() {
  const canvas = $('sigCanvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = 'white';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  let drawing = false;
  let currentStroke = [];
  let lastPoint = null;
  let currentStrokeColor = state.inkColor;
  let currentStrokeThickness = state.inkThickness;

  function applyInkStyle() {
    ctx.strokeStyle = state.inkColor;
    ctx.lineWidth   = state.inkThickness;
    ctx.lineCap     = 'round';
    ctx.lineJoin    = 'round';
  }

  const pointOf = (e) => {
    const r = canvas.getBoundingClientRect();
    return {
      x: (e.clientX - r.left) * (canvas.width  / r.width),
      y: (e.clientY - r.top ) * (canvas.height / r.height),
    };
  };

  canvas.addEventListener('mousedown', (e) => {
    drawing = true;
    const p = pointOf(e);
    currentStroke = [p];
    lastPoint = p;
    applyInkStyle();
    ctx.beginPath();
    ctx.moveTo(p.x, p.y);
    // remember current ink for this stroke
    currentStrokeColor     = state.inkColor;
    currentStrokeThickness = state.inkThickness;
  });

  canvas.addEventListener('mousemove', (e) => {
    if (!drawing) return;
    const p = pointOf(e);
    currentStroke.push(p);
    // Bezier smoothing: use midpoint of last two points as control mid
    if (lastPoint) {
      const midX = (lastPoint.x + p.x) / 2;
      const midY = (lastPoint.y + p.y) / 2;
      ctx.quadraticCurveTo(lastPoint.x, lastPoint.y, midX, midY);
      ctx.stroke();
    }
    lastPoint = p;
  });

  window.addEventListener('mouseup', () => {
    if (!drawing) return;
    drawing = false;
    lastPoint = null;
    if (currentStroke.length > 1) state.signatureStrokes.push({ points: [...currentStroke], color: currentStrokeColor, thickness: currentStrokeThickness });
    state.signatureDataUrl = canvas.toDataURL('image/png');
    setSignStep(2);
  });

  // Color swatches
  const swatchContainer = $('sigColorSwatches');
  if (swatchContainer) {
    swatchContainer.querySelectorAll('.color-swatch').forEach((sw) => {
      sw.addEventListener('click', () => {
        swatchContainer.querySelectorAll('.color-swatch').forEach((s) => s.classList.remove('active'));
        sw.classList.add('active');
        state.inkColor = sw.dataset.color;
        updateThicknessPreview();
      });
    });
  }

  // Thickness slider
  const thicknessSlider = $('sigThickness');
  if (thicknessSlider) {
    thicknessSlider.addEventListener('input', () => {
      state.inkThickness = Number(thicknessSlider.value);
      updateThicknessPreview();
    });
  }
  updateThicknessPreview();

  // Draw/Saved tab switching
  document.querySelectorAll('.panel-tab').forEach((btn) => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.panel-tab').forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      const tab = btn.dataset.sigTab;
      const drawPanel  = $('sigDrawPanel');
      const savedPanel = $('sigSavedPanel');
      if (drawPanel)  drawPanel.style.display  = tab === 'draw'  ? '' : 'none';
      if (savedPanel) savedPanel.style.display  = tab === 'saved' ? '' : 'none';
    });
  });

  $('btnSigUndo').addEventListener('click', () => {
    state.signatureStrokes.pop();
    redrawSignatureFromStrokes(canvas, ctx);
  });

  $('btnSigClear').addEventListener('click', () => {
    state.signatureStrokes = [];
    redrawSignatureFromStrokes(canvas, ctx);
    state.signatureDataUrl = null;
    renderOverlay();
  });

  $('btnSigSave').addEventListener('click', () => {
    if (!state.signatureDataUrl) return toast('Draw a signature first');
    const items = loadJson(savedSigsFile, []);
    items.unshift({ id: nativeApi.randomUUID(), dataUrl: state.signatureDataUrl });
    cloudSaveSignatures(items.slice(0, 12));
    renderSavedSignatures();
    toast('Signature saved to library');
  });
}

function redrawSignatureFromStrokes(canvas, ctx) {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = 'white';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  for (const stroke of state.signatureStrokes) {
    const pts = stroke.points || stroke; // backwards compat
    ctx.strokeStyle = stroke.color || '#000000';
    ctx.lineWidth   = stroke.thickness || 3;
    ctx.lineCap     = 'round';
    ctx.lineJoin    = 'round';
    ctx.beginPath();
    for (let i = 0; i < pts.length; i++) {
      const p = pts[i];
      if (i === 0) {
        ctx.moveTo(p.x, p.y);
      } else {
        const prev = pts[i - 1];
        const midX = (prev.x + p.x) / 2;
        const midY = (prev.y + p.y) / 2;
        ctx.quadraticCurveTo(prev.x, prev.y, midX, midY);
      }
    }
    ctx.stroke();
  }
  state.signatureDataUrl = state.signatureStrokes.length ? canvas.toDataURL('image/png') : null;
  renderOverlay();
}

function renderSavedSignatures() {
  const container = $('savedSignatures');
  container.innerHTML = '';
  const saved = loadJson(savedSigsFile, []);
  for (const sig of saved) {
    const img = document.createElement('img');
    img.src = sig.dataUrl;
    img.addEventListener('click', () => {
      state.signatureDataUrl = sig.dataUrl;
      renderOverlay();
      toast('Saved signature loaded');
    });
    container.appendChild(img);
  }
}

async function exportSigned(format) {
  if (!state.currentPdfBytes || !state.signatureDataUrl) {
    toast('Load PDF and draw signature first');
    return;
  }

  const doc = await PDFDocument.load(state.currentPdfBytes);
  const pngImage = await doc.embedPng(state.signatureDataUrl);
  const pages = doc.getPages();
  const page = pages[state.placement.pageIndex];
  const { width, height } = page.getSize();

  const {
    x,
    y,
    drawWidth: w,
    drawHeight: h,
  } = computePdfPlacement(width, height, state.placement);

  page.drawImage(pngImage, { x, y, width: w, height: h });

  for (const t of state.textStamps) {
    if (t.pageIndex !== state.placement.pageIndex) continue;
    page.drawText(t.text, {
      x: t.xRatio * width,
      y: height - (t.yRatio * height),
      size: 20,
      color: rgb(0, 0, 0),
      opacity: t.opacity,
    });
  }

  const signedBytes = await doc.save();
  const base = path.basename(state.currentPdfPath || 'document.pdf', '.pdf');

  if (format === 'pdf') {
    const out = path.join(exportDir, `${base}_signed_${Date.now()}.pdf`);
    fs.writeFileSync(out, signedBytes);
    toast(`Exported: ${out}`);
    return;
  }

  const pdfjsDoc = await pdfjsLib.getDocument({ data: signedBytes }).promise;
  const p = await pdfjsDoc.getPage(state.placement.pageIndex + 1);
  const viewport = p.getViewport({ scale: 1.8 });
  const c = document.createElement('canvas');
  c.width = viewport.width;
  c.height = viewport.height;
  await p.render({ canvasContext: c.getContext('2d'), viewport }).promise;

  const mime = format === 'png' ? 'image/png' : 'image/jpeg';
  const dataUrl = c.toDataURL(mime, 0.92);
  const out = path.join(exportDir, `${base}_signed_${Date.now()}.${format}`);
  fs.writeFileSync(out, base64ToBytes(dataUrl.split(',')[1]));
  toast(`Exported: ${out}`);
}

async function runTools() {
  $('btnMerge').addEventListener('click', async () => {
    const files = [...$('mergeInput').files];
    if (files.length < 2) return toast('Select at least 2 PDFs');
    if (!files.every(assertFileSizeAllowed)) return;
    try {
      setStatus('Merging PDFs...');
      const filesBase64 = files.map((file) => nativeApi.readFileBase64(file.path));
      const { mergedBase64 } = await pdfToolsWorker.merge(filesBase64);
      const out = path.join(exportDir, `merged_${Date.now()}.pdf`);
      nativeApi.writeFileBase64(out, mergedBase64);
      toast(`Merged: ${out}`);
    } catch (error) {
      toast(error?.message || 'Merge failed');
    } finally {
      setStatus('');
    }
  });

  $('btnSplit').addEventListener('click', async () => {
    const file = $('splitInput').files[0];
    if (!file) return toast('Select PDF');
    if (!assertFileSizeAllowed(file)) return;
    try {
      setStatus('Splitting PDF...');
      const sourceBytes = fs.readFileSync(file.path);
      const src = await PDFDocument.load(sourceBytes);
      const picked = parsePageList($('splitPages').value, src.getPageCount());
      const sourceBase64 = bytesToBase64(sourceBytes);
      const { files: splitFiles } = await pdfToolsWorker.split(sourceBase64, picked);
      for (const item of splitFiles) {
        const out = path.join(exportDir, `split_p${item.pageNum}_${Date.now()}.pdf`);
        nativeApi.writeFileBase64(out, item.base64);
      }
      toast(`Split complete (${splitFiles.length} files)`);
    } catch (error) {
      toast(error?.message || 'Split failed');
    } finally {
      setStatus('');
    }
  });

  $('btnExtract').addEventListener('click', async () => {
    const file = $('extractInput').files[0];
    if (!file) return toast('Select PDF');
    if (!assertFileSizeAllowed(file)) return;
    try {
      setStatus('Extracting page...');
      const pageNum = Number($('extractPage').value || '1');
      const sourceBytes = fs.readFileSync(file.path);
      const src = await PDFDocument.load(sourceBytes);
      if (pageNum < 1 || pageNum > src.getPageCount()) {
        toast('Invalid page number');
        return;
      }
      const sourceBase64 = bytesToBase64(sourceBytes);
      const { extractedBase64 } = await pdfToolsWorker.extract(sourceBase64, pageNum);
      const out = path.join(exportDir, `extracted_page_${pageNum}_${Date.now()}.pdf`);
      nativeApi.writeFileBase64(out, extractedBase64);
      toast(`Extracted: ${out}`);
    } catch (error) {
      toast(error?.message || 'Extract failed');
    } finally {
      setStatus('');
    }
  });

  $('btnConvert').addEventListener('click', async () => {
    const file = $('convertInput').files[0];
    if (!file) return toast('Select PDF');
    const text = await extractTextWithPdfJs(fs.readFileSync(file.path));
    const out = path.join(exportDir, `extracted_${Date.now()}.txt`);
    fs.writeFileSync(out, text, 'utf8');
    toast(`Converted: ${out}`);
  });

  // Watermark preset chips
  document.querySelectorAll('.wm-preset').forEach((chip) => {
    chip.addEventListener('click', () => {
      $('watermarkText').value = chip.dataset.text;
    });
  });

  const watermarkColorSwatches = $('watermarkColorSwatches');
  if (watermarkColorSwatches) {
    watermarkColorSwatches.querySelectorAll('.color-swatch').forEach((swatch) => {
      swatch.addEventListener('click', () => {
        watermarkColorSwatches.querySelectorAll('.color-swatch').forEach((item) => item.classList.remove('active'));
        swatch.classList.add('active');
        state.watermarkColor = swatch.dataset.color || '#999999';
      });
    });
  }

  $('btnWatermark').addEventListener('click', async () => {
    const file = $('watermarkInput').files[0];
    if (!file) return toast('Select PDF');
    const text    = $('watermarkText').value || 'DRAFT';
    const opacity = Number($('watermarkOpacity').value || '0.3');
    const posEl   = $('watermarkPosition');
    const position = posEl ? posEl.value : 'center-diagonal';
    const doc  = await PDFDocument.load(fs.readFileSync(file.path));
    const font = await doc.embedFont(StandardFonts.HelveticaBold);
    const watermarkRgb = hexToRgbObject(state.watermarkColor || '#999999');
    for (const page of doc.getPages()) {
      const { width, height } = page.getSize();
      const fontSize = Math.max(26, width / 10);
      let x, y, rot;
      if (position === 'text' || position === 'center-diagonal') {
        x = width * 0.18; y = height * 0.45; rot = degrees(35);
      } else if (position === 'corner-tl' || position === 'top-left') {
        x = 20; y = height - 40; rot = degrees(0);
      } else if (position === 'corner-tr' || position === 'top-right') {
        x = width - fontSize * text.length * 0.6 - 20; y = height - 40; rot = degrees(0);
      } else if (position === 'corner-bl' || position === 'bottom-left') {
        x = 20; y = 30; rot = degrees(0);
      } else if (position === 'corner-br' || position === 'bottom-right') {
        x = width - fontSize * text.length * 0.6 - 20; y = 30; rot = degrees(0);
      } else {
        x = width * 0.18; y = height * 0.45; rot = degrees(35);
      }
      page.drawText(text, {
        x,
        y,
        size: fontSize,
        color: rgb(watermarkRgb.r, watermarkRgb.g, watermarkRgb.b),
        rotate: rot,
        opacity,
        font,
      });
    }
    const out = path.join(exportDir, `watermarked_${Date.now()}.pdf`);
    fs.writeFileSync(out, await doc.save());
    toast(`Watermarked: ${out}`);
  });

  // Compress quality slider
  const compressQualitySlider = $('compressQuality');
  const compressQualityLabel  = $('compressQualityLabel');
  if (compressQualitySlider && compressQualityLabel) {
    compressQualitySlider.addEventListener('input', (e) => {
      compressQualityLabel.textContent = `${e.target.value}%`;
    });
  }

  $('btnCompress').addEventListener('click', async () => {
    const file = $('compressInput').files[0];
    if (!file) return toast('Select PDF');
    if (!assertFileSizeAllowed(file)) return;
    const quality = compressQualitySlider ? Number(compressQualitySlider.value) / 100 : 0.6;
    try {
      setStatus('Compressing PDF...');
      const srcBytes = fs.readFileSync(file.path);
      const pdfjsDoc = await pdfjsLib.getDocument({ data: srcBytes }).promise;
      const outDoc = await PDFDocument.create();
      for (let i = 1; i <= pdfjsDoc.numPages; i++) {
        const p = await pdfjsDoc.getPage(i);
        const viewport = p.getViewport({ scale: 1.1 });
        const c = document.createElement('canvas');
        c.width = viewport.width;
        c.height = viewport.height;
        await p.render({ canvasContext: c.getContext('2d'), viewport }).promise;
        const jpg = c.toDataURL('image/jpeg', quality);
        const img = await outDoc.embedJpg(jpg);
        const page = outDoc.addPage([viewport.width, viewport.height]);
        page.drawImage(img, { x: 0, y: 0, width: viewport.width, height: viewport.height });
      }
      const out = path.join(exportDir, `compressed_${Date.now()}.pdf`);
      fs.writeFileSync(out, await outDoc.save());
      toast(`Compressed: ${out}`);
    } catch (error) {
      toast(error?.message || 'Compression failed');
    } finally {
      setStatus('');
    }
  });

  $('btnProtect').addEventListener('click', async () => {
    const file = $('protectInput').files[0];
    const password = $('protectPassword').value;
    if (!file || !password) return toast('Select PDF and password');
    const src = fs.readFileSync(file.path);
    const b64 = bytesToBase64(src);
    const encrypted = nativeApi.encryptTextAesGcm(b64, password);
    const envelope = {
      type: 'pdf-studio-protected-envelope',
      algorithm: 'aes-256-gcm',
      iv: encrypted.iv,
      salt: encrypted.salt,
      docName: path.basename(file.path),
      createdAt: new Date().toISOString(),
      payload: encrypted.payload,
      authTag: encrypted.authTag,
    };
    const out = path.join(exportDir, `protected_${Date.now()}.pdfs`);
    fs.writeFileSync(out, JSON.stringify(envelope, null, 2), 'utf8');
    toast(`Protected envelope: ${out}`);
  });

  $('btnUnprotect').addEventListener('click', async () => {
    const file = $('unprotectInput').files[0];
    const password = $('unprotectPassword').value;
    if (!file || !password) return toast('Select protected file and password');
    try {
      await unlockProtectedEnvelope(file.path, password, true);
    } catch (error) {
      toast(error?.message || 'Unlock failed');
    }
  });

  $('btnAudit').addEventListener('click', async () => {
    const file = $('auditInput').files[0];
    const signer = $('auditSigner').value.trim();
    if (!file || !signer) return toast('Select PDF and signer name');
    const doc = await PDFDocument.load(fs.readFileSync(file.path));
    const page = doc.addPage([595, 842]);
    const font = await doc.embedFont(StandardFonts.Courier);
    const lines = [
      'AUDIT TRAIL',
      `Document: ${path.basename(file.path)}`,
      `Pages: ${doc.getPageCount() - 1}`,
      `Signed by: ${signer}`,
      `Timestamp: ${new Date().toISOString()}`,
      `Device: ${os.hostname()} | ${os.platform()} ${os.release()}`,
      'Signature is visual only (not cryptographic).',
    ];
    let y = 800;
    for (const line of lines) {
      page.drawText(line, { x: 40, y, size: 12, font, color: rgb(0, 0, 0) });
      y -= 20;
    }
    const out = path.join(exportDir, `audit_${Date.now()}.pdf`);
    fs.writeFileSync(out, await doc.save());
    toast(`Audit added: ${out}`);
  });
}

function runSettings() {
  const settings = loadJson(settingsFile, {
    theme: 'system',
    language: 'en',
    fontScale: 0,
    highContrast: false,
  });

  $('themeSelect').value      = settings.theme;
  $('langSelect').value       = settings.language;
  $('fontScaleSelect').value  = String(settings.fontScale);

  // High contrast toggle
  const hcToggle = $('highContrastToggle');
  if (hcToggle) {
    hcToggle.checked = !!settings.highContrast;
    document.body.classList.toggle('high-contrast', !!settings.highContrast);
    hcToggle.addEventListener('change', () => {
      settings.highContrast = hcToggle.checked;
      document.body.classList.toggle('high-contrast', hcToggle.checked);
      cloudSaveSettings(settings);
      toast(hcToggle.checked ? 'High contrast ON' : 'High contrast OFF');
    });
  }

  // Open export folder button
  const btnOpenExportFolder = $('btnOpenExportFolder');
  if (btnOpenExportFolder) {
    btnOpenExportFolder.addEventListener('click', () => {
      nativeApi.openPath(exportDir);
    });
  }

  const save = () => {
    settings.theme     = $('themeSelect').value;
    settings.language  = $('langSelect').value;
    settings.fontScale = Number($('fontScaleSelect').value);
    cloudSaveSettings(settings);
    document.documentElement.style.fontSize = `${100 + settings.fontScale * 10}%`;
    document.body.dir = settings.language === 'he' ? 'rtl' : 'ltr';
    if (settings.theme === 'dark') document.body.classList.add('dark');
    else document.body.classList.remove('dark');
    toast('Settings saved');
  };

  $('themeSelect').addEventListener('change', save);
  $('langSelect').addEventListener('change', save);
  $('fontScaleSelect').addEventListener('change', save);
  save();
}

function wireViewer() {
  const btnSaveTextFile = $('btnSaveTextFile');
  if (btnSaveTextFile) {
    btnSaveTextFile.addEventListener('click', () => {
      if (!state.currentViewerPath || state.currentViewerKind !== 'text') {
        toast('No editable file open');
        return;
      }
      if (state.currentViewerEncoding === 'hex') {
        const bytes = editableHexToBytes($('viewerTextEditor').value);
        writeViewerBufferToFile(state.currentViewerPath, bytes);
      } else {
        fs.writeFileSync(state.currentViewerPath, $('viewerTextEditor').value, 'utf8');
      }
      $('viewerInfo').textContent = `${path.basename(state.currentViewerPath)} saved`;
      toast('File saved');
    });
  }

  const btnViewerZoomIn = $('btnViewerZoomIn');
  if (btnViewerZoomIn) {
    btnViewerZoomIn.addEventListener('click', async () => {
      if (state.currentViewerKind !== 'pdf' || !state.currentViewerPath) return;
      state.viewerZoom = Math.min(3, state.viewerZoom + 0.2);
      await renderViewerPdf(state.currentViewerPath, fs.readFileSync(state.currentViewerPath));
    });
  }

  const btnViewerZoomOut = $('btnViewerZoomOut');
  if (btnViewerZoomOut) {
    btnViewerZoomOut.addEventListener('click', async () => {
      if (state.currentViewerKind !== 'pdf' || !state.currentViewerPath) return;
      state.viewerZoom = Math.max(0.6, state.viewerZoom - 0.2);
      await renderViewerPdf(state.currentViewerPath, fs.readFileSync(state.currentViewerPath));
    });
  }

  // Open file button
  const btnOpenAnyViewer = $('btnOpenAnyViewer');
  if (btnOpenAnyViewer) {
    btnOpenAnyViewer.addEventListener('click', () => {
      const input = document.createElement('input');
      input.type = 'file';
      input.onchange = async () => {
        const file = input.files?.[0];
        if (!file?.path) return;
        await openFileInViewer(file.path);
        toast(`Viewing: ${path.basename(file.path)}`);
      };
      input.click();
    });
  }

  const content = document.querySelector('.content');
  if (content) {
    ['dragenter', 'dragover'].forEach((eventName) => {
      content.addEventListener(eventName, (event) => {
        event.preventDefault();
      });
    });
    content.addEventListener('drop', async (event) => {
      event.preventDefault();
      const file = event.dataTransfer?.files?.[0];
      if (!file?.path) return;
      activateTab('viewer');
      await openFileInViewer(file.path);
      toast(`Dropped: ${path.basename(file.path)}`);
    });
  }

  // Reading mode on viewer tab
  const viewerReadingMode = $('viewerReadingMode');
  if (viewerReadingMode) {
    viewerReadingMode.addEventListener('change', () => applyReadingMode(viewerReadingMode.value));
  }

  // PDF Stats
  const btnPdfStats = $('btnPdfStats');
  if (btnPdfStats) {
    btnPdfStats.addEventListener('click', async () => {
      const vc = $('viewerCanvas');
      const bytes = vc?._bytes || state.currentPdfBytes;
      if (!bytes) return toast('No PDF loaded');
      const pdfjsDoc = await pdfjsLib.getDocument({ data: bytes }).promise;
      let wordCount = 0;
      let charCount = 0;
      for (let i = 1; i <= pdfjsDoc.numPages; i++) {
        const page = await pdfjsDoc.getPage(i);
        const tc = await page.getTextContent();
        const txt = tc.items.map((it) => it.str || '').join(' ');
        charCount += txt.length;
        wordCount += txt.split(/\s+/).filter(Boolean).length;
      }
      const readMinutes = Math.ceil(wordCount / 200);
      const statsPanel = $('pdfStatsPanel');
      if (statsPanel) {
        statsPanel.style.display = '';
        statsPanel.innerHTML = `
          <div class="stat-card"><div class="stat-val">${pdfjsDoc.numPages}</div><div class="stat-lbl">Pages</div></div>
          <div class="stat-card"><div class="stat-val">${wordCount.toLocaleString()}</div><div class="stat-lbl">Words</div></div>
          <div class="stat-card"><div class="stat-val">${charCount.toLocaleString()}</div><div class="stat-lbl">Characters</div></div>
          <div class="stat-card"><div class="stat-val">${readMinutes} min</div><div class="stat-lbl">Read time</div></div>
          <div class="stat-card"><div class="stat-val">${(bytes.length / 1024).toFixed(1)} KB</div><div class="stat-lbl">File size</div></div>
        `;
      } else {
        toast(`${pdfjsDoc.numPages} pages · ${wordCount.toLocaleString()} words · ~${readMinutes} min read`);
      }
    });
  }

  // QR Stamp (appends a QR-verification page to the PDF)
  const btnQrStamp = $('btnQrStamp');
  if (btnQrStamp) {
    btnQrStamp.addEventListener('click', async () => {
      const bytes = state.currentPdfBytes;
      if (!bytes) return toast('No PDF loaded in Sign tab');
      try {
        // Compute SHA-256 fingerprint
        const hashBuf = await crypto.subtle.digest('SHA-256', bytes);
        const hashHex = Array.from(new Uint8Array(hashBuf)).map((b) => b.toString(16).padStart(2, '0')).join('');
        // Generate QR canvas
        const qrCanvas = document.createElement('canvas');
        const qrDataUrl = await nativeApi.qrcodeToDataURL(`sha256:${hashHex.slice(0, 32)}`, { width: 200, margin: 1 });
        const qrBytes = base64ToBytes(qrDataUrl.replace(/^data:image\/png;base64,/, ''));
        // Embed into new PDF page
        const doc  = await PDFDocument.load(bytes);
        const qrImg = await doc.embedPng(qrBytes);
        const page  = doc.addPage([595, 842]);
        const font  = await doc.embedFont(StandardFonts.Helvetica);
        page.drawImage(qrImg, { x: 197, y: 521, width: 200, height: 200 });
        page.drawText('Document Verification QR', { x: 160, y: 510, size: 14, font });
        page.drawText(`SHA-256: ${hashHex.slice(0, 32)}...`, { x: 40, y: 480, size: 8, font });
        const out = path.join(exportDir, `qrstamped_${Date.now()}.pdf`);
        fs.writeFileSync(out, await doc.save());
        toast(`QR stamp page added: ${path.basename(out)}`);
      } catch (err) {
        toast(err?.message || 'QR stamp failed');
      }
    });
  }
}

function wireHome() {
  document.querySelectorAll('[data-home-tab]').forEach((button) => {
    button.addEventListener('click', () => activateTab(button.dataset.homeTab));
  });

  const openPdfHandler = () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'application/pdf';
    input.onchange = async () => {
      if (input.files?.[0]?.path) await loadPdfFromPath(input.files[0].path);
    };
    input.click();
  };
  $('btnOpenPdf').addEventListener('click', openPdfHandler);
  // Also wire the Open PDF button inside the Sign tab
  const signOpenBtn = $('btnOpenPdfSign');
  if (signOpenBtn) signOpenBtn.addEventListener('click', openPdfHandler);

  $('btnOpenAny').addEventListener('click', () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.onchange = async () => {
      const file = input.files?.[0];
      if (!file?.path) return;
      if (file.type === 'application/pdf' || file.path.toLowerCase().endsWith('.pdf')) {
        await loadPdfFromPath(file.path);
      } else if (isImagePath(file.path) || isTextLikePath(file.path) || isProtectedEnvelopePath(file.path)) {
        activateTab('viewer');
        await openFileInViewer(file.path);
      } else {
        activateTab('viewer');
        await openFileInViewer(file.path);
      }
    };
    input.click();
  });

  $('btnScanImport').addEventListener('click', () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = async () => {
      const file = input.files?.[0];
      if (!file?.path) return;
      const img = new Image();
      img.onload = async () => {
        const c = document.createElement('canvas');
        c.width = img.width;
        c.height = img.height;
        const ctx = c.getContext('2d');
        ctx.filter = 'contrast(1.2) brightness(1.1)';
        ctx.drawImage(img, 0, 0);
        const enhanced = c.toDataURL('image/jpeg', 0.95);
        const doc = await PDFDocument.create();
        const jpg = await doc.embedJpg(enhanced);
        const page = doc.addPage([img.width, img.height]);
        page.drawImage(jpg, { x: 0, y: 0, width: img.width, height: img.height });
        const out = path.join(exportDir, `enhanced_scan_${Date.now()}.pdf`);
        fs.writeFileSync(out, await doc.save());
        await loadPdfFromPath(out);
        toast('Scan enhanced and loaded');
      };
      img.src = `file://${file.path.replace(/\\/g, '/')}`;
    };
    input.click();
  });
}

function renderTextStampList() {
  const list = $('textStampList');
  if (!list) return;
  list.innerHTML = '';
  state.textStamps.forEach((t, i) => {
    const item = document.createElement('div');
    item.className = 'ts-item';
    const dot = document.createElement('span');
    dot.style.cssText = `width:10px;height:10px;border-radius:50%;background:${t.color || '#000'};display:inline-block;flex-shrink:0;margin-left:6px;`;
    const label = document.createElement('span');
    label.style.cssText = 'flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
    label.textContent = `p${t.pageIndex + 1}: ${t.text} (${t.size || 20}px)`;
    const del = document.createElement('button');
    del.className = 'ts-item-del';
    del.textContent = '×';
    del.addEventListener('click', () => {
      state.textStamps.splice(i, 1);
      renderTextStampList();
      renderOverlay();
    });
    item.appendChild(dot);
    item.appendChild(label);
    item.appendChild(del);
    list.appendChild(item);
  });
}

function applyReadingMode(mode) {
  state.readingMode = mode;
  // Update chips active state
  document.querySelectorAll('[data-mode]').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.mode === mode);
  });
  const pdfCont = $('pdfContainer');
  if (pdfCont) {
    pdfCont.style.filter = '';
    if (mode === 'sepia')  pdfCont.style.filter = 'sepia(0.85) brightness(0.95)';
    if (mode === 'warm')   pdfCont.style.filter = 'sepia(0.4) hue-rotate(-20deg) brightness(1.05)';
    if (mode === 'cool')   pdfCont.style.filter = 'hue-rotate(180deg) saturate(0.4) brightness(0.95)';
    if (mode === 'night')  pdfCont.style.filter = 'invert(0.85) hue-rotate(180deg)';
  }
  // Also update viewer reading overlay
  const viewerOverlay = $('viewerReadingOverlay');
  if (viewerOverlay) {
    viewerOverlay.className = '';
    viewerOverlay.style.opacity = '0';
    if (mode !== 'normal') {
      viewerOverlay.classList.add(`reading-${mode}`);
      viewerOverlay.style.opacity = '1';
    }
  }
}

function wireSignActions() {
  $('btnPrevPage').addEventListener('click', async () => {
    if (!state.currentPdfJsDoc) return;
    state.currentPage = Math.max(0, state.currentPage - 1);
    state.placement.pageIndex = state.currentPage;
    await renderCurrentPage();
  });
  $('btnNextPage').addEventListener('click', async () => {
    if (!state.currentPdfJsDoc) return;
    state.currentPage = Math.min(state.currentPdfJsDoc.numPages - 1, state.currentPage + 1);
    state.placement.pageIndex = state.currentPage;
    await renderCurrentPage();
  });

  // Text stamp size chips
  document.querySelectorAll('.ts-size-chip').forEach((chip) => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('.ts-size-chip').forEach((c) => c.classList.remove('active'));
      chip.classList.add('active');
      state.textStampFontSize = Number(chip.dataset.size);
    });
  });

  // Text stamp color swatches
  const tsSwatches = $('tsColorSwatches');
  if (tsSwatches) {
    tsSwatches.querySelectorAll('.color-swatch').forEach((sw) => {
      sw.addEventListener('click', () => {
        tsSwatches.querySelectorAll('.color-swatch').forEach((s) => s.classList.remove('active'));
        sw.classList.add('active');
        state.textStampColor = sw.dataset.color;
      });
    });
  }

  $('btnAddTextStamp').addEventListener('click', () => {
    const text = $('textStampInput').value.trim();
    if (!text) return;
    state.textStamps.push({
      text,
      xRatio:    0.1,
      yRatio:    0.88,
      opacity:   0.9,
      pageIndex: state.currentPage,
      size:      state.textStampFontSize,
      color:     state.textStampColor,
    });
    $('textStampInput').value = '';
    renderTextStampList();
    renderOverlay();
    toast('Text stamp added');
  });

  // Auto-date
  const btnAutoDate = $('btnAutoDate');
  if (btnAutoDate) {
    btnAutoDate.addEventListener('click', () => {
      const today = new Date().toLocaleDateString('he-IL', { day: '2-digit', month: '2-digit', year: 'numeric' });
      state.textStamps.push({
        text:      today,
        xRatio:    0.68,
        yRatio:    0.93,
        opacity:   0.9,
        pageIndex: state.currentPage,
        size:      16,
        color:     '#1a3a6b',
      });
      renderTextStampList();
      renderOverlay();
      toast('Auto-date added');
    });
  }

  // Visual stamp presets toggle
  const btnVisualStamp = $('btnVisualStamp');
  const stampPanel     = $('visualStampPanel');
  if (btnVisualStamp && stampPanel) {
    btnVisualStamp.addEventListener('click', () => {
      const visible = stampPanel.style.display !== 'none';
      stampPanel.style.display = visible ? 'none' : '';
    });
    stampPanel.querySelectorAll('.stamp-preset').forEach((btn) => {
      btn.addEventListener('click', () => {
        const overlay = $('overlayCanvas');
        if (!overlay) return;
        state.textStamps.push({
          text:      btn.dataset.text,
          xRatio:    0.28,
          yRatio:    0.48,
          opacity:   0.75,
          pageIndex: state.currentPage,
          size:      48,
          color:     btn.dataset.color,
          bold:      true,
          rotate:    true,
        });
        stampPanel.style.display = 'none';
        renderTextStampList();
        renderOverlay();
        toast(`Stamp "${btn.dataset.text}" added`);
      });
    });
  }

  // Reading mode chips (sign tab)
  document.querySelectorAll('[data-mode]').forEach((chip) => {
    chip.addEventListener('click', () => applyReadingMode(chip.dataset.mode));
  });

  $('btnExportPdf').addEventListener('click', () => exportSigned('pdf'));
  $('btnExportPng').addEventListener('click', () => exportSigned('png'));
  $('btnExportJpg').addEventListener('click', () => exportSigned('jpg'));
}

function showBootError(fn, err) {
  const el = document.createElement('div');
  el.style.cssText = 'position:fixed;top:0;left:0;right:0;background:#b91c1c;color:white;z-index:99999;padding:10px 16px;font-size:13px;font-family:monospace;';
  el.textContent = `BOOT ERROR in ${fn}(): ${err?.message || err}`;
  document.body.prepend(el);
  console.error(`BOOT ERROR in ${fn}():`, err);
}

function boot() {
  const authFeature   = createAuthFeature({ $, toast, nativeApi });
  const remoteFeature = createRemoteFeature({ $, toast, nativeApi, loadJson, settingsFile });
  const wizardFeature = createWizardFeature({ $, toast, nativeApi });
  _authFeature = authFeature;

  // Auth init: show login overlay, then boot app when ready
  authFeature.init(async (user, isNewUser) => {
    // Sync from cloud if logged in
    if (authFeature.isLoggedIn()) {
      await cloudSyncFromServer();
    }

    const steps = [
      ['wireTabs',              wireTabs],
      ['wireDeepLinkHandler',   () => wireDeepLinkHandler(remoteFeature)],
      ['wireHome',              wireHome],
      ['wireViewer',            wireViewer],
      ['wireOverlayDrag',       wireOverlayDrag],
      ['wireSignatureCanvas',   wireSignatureCanvas],
      ['wireSignActions',       wireSignActions],
      ['runTools',              runTools],
      ['remoteFeature.init',    () => remoteFeature.init()],
      ['runSettings',           runSettings],
      ['renderRecents',         renderRecents],
      ['renderSavedSignatures', renderSavedSignatures],
    ];
    for (const [name, fn] of steps) {
      try { fn(); }
      catch (err) { showBootError(name, err); }
    }

    // Determine if wizard should show: LOCAL flag takes priority
    const localSettings = loadJson(settingsFile, {});
    const localWizardDone = localSettings.wizardCompleted === true;

    if (!localWizardDone) {
      // Wizard not completed locally — show it for any user (guest or logged in)
      wizardFeature.init(async (wizardData) => {
        // Mark wizard as completed locally
        const settings = loadJson(settingsFile, {});
        settings.wizardCompleted = true;
        settings.displayName = wizardData.displayName || '';
        settings.company = wizardData.company || '';
        saveJson(settingsFile, settings);

        // Save to cloud if logged in
        if (authFeature.isLoggedIn()) {
          await nativeApi.cloudSet('profile', {
            displayName: wizardData.displayName || '',
            company: wizardData.company || '',
            wizardCompleted: true,
          });
          if (wizardData.smtp) {
            await nativeApi.cloudSet('smtp', wizardData.smtp);
          }
        }
        // Save SMTP locally too
        if (wizardData.smtp) {
          const smtpSettings = loadJson(settingsFile, {});
          smtpSettings.smtp = wizardData.smtp;
          saveJson(settingsFile, smtpSettings);
        }
        if (wizardData.signatureDataUrl) {
          const items = loadJson(savedSigsFile, []);
          items.unshift({ id: nativeApi.randomUUID(), dataUrl: wizardData.signatureDataUrl });
          cloudSaveSignatures(items.slice(0, 12));
          renderSavedSignatures();
        }
        toast('Setup complete! Welcome to PDF Studio');
      });
    }
  });
}

boot();

})();
