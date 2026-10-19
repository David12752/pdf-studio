const { contextBridge, ipcRenderer, shell } = require('electron');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');

function toBase64(value) {
  if (typeof value === 'string') {
    return Buffer.from(value, 'utf8').toString('base64');
  }
  if (value instanceof Uint8Array) {
    return Buffer.from(value).toString('base64');
  }
  return Buffer.from(value).toString('base64');
}

function fromBase64(base64) {
  return Buffer.from(base64, 'base64');
}

contextBridge.exposeInMainWorld('nativeApi', {
  joinPath: (...parts) => path.join(...parts),
  basename: (filePath, ext) => path.basename(filePath, ext),
  homedir: () => os.homedir(),
  hostname: () => os.hostname(),
  platform: () => os.platform(),
  release: () => os.release(),

  exists: (targetPath) => fs.existsSync(targetPath),
  mkdirRecursive: (targetPath) => fs.mkdirSync(targetPath, { recursive: true }),

  readText: (filePath) => fs.readFileSync(filePath, 'utf8'),
  writeText: (filePath, text) => fs.writeFileSync(filePath, text, 'utf8'),
  readFileBase64: (filePath) => fs.readFileSync(filePath).toString('base64'),
  writeFileBase64: (filePath, base64) => fs.writeFileSync(filePath, fromBase64(base64)),

  bytesToBase64: (bytesLike) => toBase64(bytesLike),

  randomUUID: () => crypto.randomUUID(),
  sha256FromBase64: (base64) => crypto.createHash('sha256').update(fromBase64(base64)).digest('hex'),
  encryptTextAesGcm: (plainText, password) => {
    const iv = crypto.randomBytes(12);
    const salt = crypto.randomBytes(16);
    const key = crypto.scryptSync(password, salt, 32);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    const encrypted = Buffer.concat([cipher.update(plainText, 'utf8'), cipher.final()]);

    return {
      iv: iv.toString('base64'),
      salt: salt.toString('base64'),
      payload: encrypted.toString('base64'),
      authTag: cipher.getAuthTag().toString('base64'),
    };
  },
  decryptTextAesGcm: ({ iv, salt, payload, authTag }, password) => {
    const key = crypto.scryptSync(password, fromBase64(salt), 32);
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, fromBase64(iv));
    decipher.setAuthTag(fromBase64(authTag));
    const decrypted = Buffer.concat([
      decipher.update(fromBase64(payload)),
      decipher.final(),
    ]);
    return decrypted.toString('utf8');
  },

  openPath: async (targetPath) => shell.openPath(targetPath),
  openExternal: (url) => shell.openExternal(url),

  onDeepLink: (callback) => {
    if (typeof callback !== 'function') return () => {};
    const listener = (_event, link) => callback(link);
    ipcRenderer.on('deeplink', listener);
    return () => ipcRenderer.removeListener('deeplink', listener);
  },

  /** Convert text to a QR code PNG data URL */
  qrcodeToDataURL: (text, opts) => {
    const QRCodeLib = require('qrcode');
    return QRCodeLib.toDataURL(text, opts || {});
  },

  /* ── Signing server API ─────────────────────────────────────────── */

  /** Start localtunnel, returns { ok, tunnelUrl } or { ok:false, error } */
  serverIsRunning: () => ipcRenderer.invoke('server:is-running'),
  serverStartTunnel: () => ipcRenderer.invoke('server:start-tunnel'),

  /** Stop localtunnel */
  serverStopTunnel: () => ipcRenderer.invoke('server:stop-tunnel'),

  /**
   * Create a remote signing session.
   * @param {{ pdfBase64, fileName, signerEmail, senderEmail, senderName, message, smtpConfig }} payload
   * @returns {Promise<{ ok, sessionId, signUrl, fieldCount }|{ ok:false, error }>}
   */
  serverCreateSession: (payload) => ipcRenderer.invoke('server:create-session', payload),

  /** List all sessions */
  serverGetSessions: () => ipcRenderer.invoke('server:get-sessions'),

  /** Download signed PDF to destPath */
  serverDownloadSigned: (opts) => ipcRenderer.invoke('server:download-signed', opts),

  /** Send a signing-invitation via email / WhatsApp / SMS */
  serverSendInvite: (payload) => ipcRenderer.invoke('server:send-invite', payload),

  /** Current public tunnel URL (or null) */
  serverGetTunnelUrl: () => ipcRenderer.invoke('server:get-tunnel-url'),

  /* ── Cloud / Auth API ───────────────────────────────────────────── */

  /** Check if Firebase is configured */
  cloudIsConfigured: () => ipcRenderer.invoke('cloud:is-configured'),

  /** Register with email + password. Returns { ok, user } or { ok:false, error } */
  cloudRegister: (email, password) => ipcRenderer.invoke('cloud:register', { email, password }),

  /** Login with email + password */
  cloudLogin: (email, password) => ipcRenderer.invoke('cloud:login', { email, password }),

  /** Login with Google ID token */
  cloudLoginGoogle: (idToken) => ipcRenderer.invoke('cloud:login-google', { idToken }),

  /** Logout current user */
  cloudLogout: () => ipcRenderer.invoke('cloud:logout'),

  /** Get current user or null */
  cloudGetUser: () => ipcRenderer.invoke('cloud:get-user'),

  /** Get a cloud document (profile, settings, smtp) */
  cloudGet: (docName) => ipcRenderer.invoke('cloud:get', { docName }),

  /** Set/merge a cloud document */
  cloudSet: (docName, data) => ipcRenderer.invoke('cloud:set', { docName, data }),

  /** List items in a cloud collection (signatures, recents) */
  cloudListCollection: (collectionName) => ipcRenderer.invoke('cloud:list-collection', { collectionName }),

  /** Replace entire cloud collection */
  cloudSetCollection: (collectionName, items) => ipcRenderer.invoke('cloud:set-collection', { collectionName, items }),

  /** Upload all local data to cloud */
  cloudSyncUpload: (localData) => ipcRenderer.invoke('cloud:sync-upload', localData),

  /** Download all cloud data to local */
  cloudSyncDownload: () => ipcRenderer.invoke('cloud:sync-download'),
});
