const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const fs   = require('fs');

/* ── Cloud service (Firebase) ───────────────────────────────────────── */
const cloudService = require('./server/cloudService');

/* ── Signing server ─────────────────────────────────────────────────── */
const signingServer = require('./server/server');
const SERVER_PORT   = 3456;
let   tunnelUrl     = null;
let   serverRunning = false;

async function startSigningServer() {
  try {
    await signingServer.start(SERVER_PORT);
    serverRunning = true;
    console.log(`[main] Signing server started on port ${SERVER_PORT}`);
  } catch (err) {
    serverRunning = false;
    console.error('[main] Failed to start signing server:', err.message);
  }
}

function estimateBase64Bytes(base64) {
  const raw = String(base64 || '');
  const padding = raw.endsWith('==') ? 2 : raw.endsWith('=') ? 1 : 0;
  return Math.max(0, Math.floor((raw.length * 3) / 4) - padding);
}

function resolveSafeDownloadPath(destPath, sessionId) {
  const downloadsDir = app.getPath('downloads');
  const fallbackName = `signed-${sessionId}.pdf`;
  const requestedName = path.basename(typeof destPath === 'string' && destPath.trim() ? destPath : fallbackName);
  const normalizedName = requestedName.toLowerCase().endsWith('.pdf') ? requestedName : `${requestedName}.pdf`;
  return path.join(downloadsDir, normalizedName);
}

/* ── IPC: server control ────────────────────────────────────────────── */

ipcMain.handle('server:is-running', () => ({ running: serverRunning, tunnelUrl }));
/** Start localtunnel and return the public URL */
ipcMain.handle('server:start-tunnel', async () => {
  try {
    tunnelUrl = await signingServer.startTunnel(SERVER_PORT);
    return { ok: true, tunnelUrl };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('server:stop-tunnel', () => {
  signingServer.stopTunnel();
  tunnelUrl = null;
  return { ok: true };
});

/** Create a signing session. Renderer sends { pdfBase64, fileName, signerEmail, senderEmail, senderName, message, smtpConfig } */
ipcMain.handle('server:create-session', async (_event, payload) => {
  try {
    const { pdfBase64, fileName, signerEmail, senderEmail, senderName, message, smtpConfig } = payload;

    const estimatedBytes = estimateBase64Bytes(pdfBase64);
    if (estimatedBytes > signingServer.MAX_SESSION_PDF_BYTES) {
      return { ok: false, error: `PDF too large (${Math.ceil(signingServer.MAX_SESSION_PDF_BYTES / (1024 * 1024))}MB limit)` };
    }

    const pdfBuffer = Buffer.from(pdfBase64, 'base64');

    const { session, accessToken } = await signingServer.createSession({
      pdfBuffer,
      fileName,
      signerEmail,
      senderEmail,
      senderName,
      message,
      smtpConfig,
    });

    const base = tunnelUrl || `http://localhost:${SERVER_PORT}`;
    const signUrl = `${base}/sign/${session.sessionId}?token=${encodeURIComponent(accessToken)}`;
    return { ok: true, sessionId: session.sessionId, signUrl, fieldCount: session.fields.length };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

/** Get all sessions for the sidebar list */
ipcMain.handle('server:get-sessions', () => {
  return signingServer.listSessions();
});

/** Download the signed PDF to a local path */
ipcMain.handle('server:download-signed', async (_event, { sessionId, destPath }) => {
  try {
    const session = signingServer.sessions.get(sessionId);
    if (!session || !session.signedPdfBuffer) return { ok: false, error: 'Not found' };
    const safeDestPath = resolveSafeDownloadPath(destPath, sessionId);
    fs.writeFileSync(safeDestPath, Buffer.from(session.signedPdfBuffer));
    return { ok: true, destPath: safeDestPath };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

/** Current public tunnel URL (or null) */
ipcMain.handle('server:get-tunnel-url', () => tunnelUrl);

/* ── IPC: Cloud / Auth ──────────────────────────────────────────────── */

ipcMain.handle('cloud:is-configured', () => cloudService.isConfigured());

ipcMain.handle('cloud:register', async (_event, { email, password }) => {
  try {
    const user = await cloudService.register(email, password);
    return { ok: true, user };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cloud:login', async (_event, { email, password }) => {
  try {
    const user = await cloudService.login(email, password);
    return { ok: true, user };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cloud:login-google', async (_event, { idToken }) => {
  try {
    const user = await cloudService.loginWithGoogleToken(idToken);
    return { ok: true, user };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cloud:logout', async () => {
  try {
    await cloudService.logout();
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cloud:get-user', () => cloudService.getUser());

ipcMain.handle('cloud:get', async (_event, { docName }) => {
  try {
    const data = await cloudService.cloudGet(docName);
    return { ok: true, data };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cloud:set', async (_event, { docName, data }) => {
  try {
    await cloudService.cloudSet(docName, data);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cloud:list-collection', async (_event, { collectionName }) => {
  try {
    const items = await cloudService.cloudListCollection(collectionName);
    return { ok: true, items };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cloud:set-collection', async (_event, { collectionName, items }) => {
  try {
    await cloudService.cloudSetCollection(collectionName, items);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cloud:sync-upload', async (_event, localData) => {
  try {
    return await cloudService.syncUpload(localData);
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cloud:sync-download', async () => {
  try {
    return await cloudService.syncDownload();
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

/**
 * Send a signing invitation via the chosen channel.
 * channel: 'email' | 'whatsapp' | 'sms'
 */
ipcMain.handle('server:send-invite', async (_event, { channel, signUrl, signerEmail, signerPhone, senderName, senderEmail, fileName, message, smtpConfig }) => {
  try {
    if (channel === 'email') {
      const emailer = require('./server/emailer');
      await emailer.sendInviteEmail({
        smtpConfig,
        from:        senderEmail,
        signerEmail,
        senderName:  senderName || 'PDF Studio',
        fileName,
        message,
        signUrl,
      });
      return { ok: true };
    }
    // WhatsApp / SMS — return the URL; renderer will open it
    const body = encodeURIComponent(`שלום, ${senderName || 'PDF Studio'} שלח לך את המסמך "${fileName}" לחתימה דיגיטלית:\n${signUrl}`);
    const phone = (signerPhone || '').replace(/[^\d+]/g, '');
    if (channel === 'whatsapp') {
      const url = phone ? `https://wa.me/${phone}?text=${body}` : `https://wa.me/?text=${body}`;
      return { ok: true, openUrl: url };
    }
    if (channel === 'sms') {
      const target = phone ? `sms:${phone}` : 'sms:';
      const url = `${target}${process.platform === 'win32' ? '?body=' : '?&body='}${body}`;
      return { ok: true, openUrl: url };
    }
    return { ok: false, error: 'Unknown channel' };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

let mainWindow = null;
let pendingDeepLink = null;

const DEEP_LINK_PREFIX = 'pdfsigner://';

function extractDeepLink(argv = []) {
  return argv.find((arg) => typeof arg === 'string' && arg.toLowerCase().startsWith(DEEP_LINK_PREFIX)) || null;
}

function sendDeepLinkToRenderer(link) {
  if (!mainWindow || !link) return;
  if (mainWindow.webContents.isLoading()) {
    mainWindow.webContents.once('did-finish-load', () => {
      mainWindow.webContents.send('deeplink', link);
    });
    return;
  }
  mainWindow.webContents.send('deeplink', link);
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1480,
    height: 940,
    minWidth: 1200,
    minHeight: 760,
    title: 'PDF Studio',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: false,
      webSecurity: true,
    },
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  mainWindow.webContents.on('did-finish-load', () => {
    if (pendingDeepLink) {
      sendDeepLinkToRenderer(pendingDeepLink);
      pendingDeepLink = null;
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

if (process.defaultApp) {
  if (process.argv.length >= 2) {
    app.setAsDefaultProtocolClient('pdfsigner', process.execPath, [path.resolve(process.argv[1])]);
  }
} else {
  app.setAsDefaultProtocolClient('pdfsigner');
}

const singleInstanceLock = app.requestSingleInstanceLock();
if (!singleInstanceLock) {
  app.quit();
} else {
  app.on('second-instance', (_event, argv) => {
    const deepLink = extractDeepLink(argv);
    if (deepLink) pendingDeepLink = deepLink;

    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
      if (pendingDeepLink) {
        sendDeepLinkToRenderer(pendingDeepLink);
        pendingDeepLink = null;
      }
    }
  });
}

app.whenReady().then(async () => {
  const startupDeepLink = extractDeepLink(process.argv);
  if (startupDeepLink) pendingDeepLink = startupDeepLink;

  await startSigningServer();

  // Init Firebase if configured
  if (cloudService.isConfigured()) {
    try { cloudService.init(); } catch (e) { console.error('[main] Firebase init error:', e.message); }
  }

  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  signingServer.stop();
  if (process.platform !== 'darwin') app.quit();
});
