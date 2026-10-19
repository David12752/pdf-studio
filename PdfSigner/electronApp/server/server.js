'use strict';
/**
 * PDF Studio – Remote Signing Server
 * Runs inside Electron main process (Node.js context).
 * Listens on localhost:3456 and optionally exposes via localtunnel.
 */

const express = require('express');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const multer = require('multer');
const pdfProcessor = require('./pdfProcessor');
const emailer = require('./emailer');

let safeStorage = null;
try {
  ({ safeStorage } = require('electron'));
} catch {
  safeStorage = null;
}

const SESSION_TTL_MS = 48 * 60 * 60 * 1000;
const SESSION_PURGE_GRACE_MS = 60 * 60 * 1000;
const MAX_SESSION_PDF_BYTES = 100 * 1024 * 1024;
const SESSION_STORE_DIR = path.join(os.homedir(), 'PDF Studio');
const SESSION_STORE_FILE = path.join(SESSION_STORE_DIR, 'remote_sessions_store.json');

const app = express();
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: MAX_SESSION_PDF_BYTES },
});

/** In-memory session store: Map<sessionId, Session> */
const sessions = new Map();

app.use(express.json({ limit: '100mb' }));
app.use(express.static(path.join(__dirname, 'public')));

function createSessionId() {
  return crypto.randomBytes(20).toString('hex').toUpperCase();
}

function createAccessToken() {
  return crypto.randomBytes(24).toString('hex');
}

function hashToken(token) {
  return crypto.createHash('sha256').update(String(token || ''), 'utf8').digest('hex');
}

function wrapSecretJson(value) {
  if (!value) return null;
  const serialized = JSON.stringify(value);
  if (safeStorage && safeStorage.isEncryptionAvailable()) {
    return {
      scheme: 'safeStorage',
      payload: safeStorage.encryptString(serialized).toString('base64'),
    };
  }
  return {
    scheme: 'base64',
    payload: Buffer.from(serialized, 'utf8').toString('base64'),
  };
}

function unwrapSecretJson(secret) {
  if (!secret) return null;
  if (secret.scheme === 'safeStorage') {
    if (!safeStorage || !safeStorage.isEncryptionAvailable()) {
      throw new Error('Secure storage unavailable for this session');
    }
    return JSON.parse(safeStorage.decryptString(Buffer.from(secret.payload, 'base64')));
  }
  if (secret.scheme === 'base64') {
    return JSON.parse(Buffer.from(secret.payload, 'base64').toString('utf8'));
  }
  throw new Error('Unsupported secret format');
}

function getEffectiveStatus(session, now = Date.now()) {
  if (!session) return 'UNKNOWN';
  if (now > session.expiresAt) return 'EXPIRED';
  if (session.status === 'SIGNED') return 'SIGNED';
  if (session.viewedAt) return 'VIEWED';
  return 'PENDING';
}

function markSessionViewed(session, now = Date.now()) {
  if (!session) return;
  if (getEffectiveStatus(session, now) === 'PENDING') {
    session.viewedAt = now;
    session.status = 'VIEWED';
    persistSessions();
  }
}

function pruneExpiredSessions(now = Date.now()) {
  let changed = false;
  for (const [sessionId, session] of sessions.entries()) {
    if (now > session.expiresAt + SESSION_PURGE_GRACE_MS) {
      sessions.delete(sessionId);
      changed = true;
    }
  }
  if (changed) persistSessions();
}

function serializeSession(session) {
  return {
    ...session,
    pdfBuffer: Buffer.from(session.pdfBuffer || []).toString('base64'),
    signedPdfBuffer: session.signedPdfBuffer
      ? Buffer.from(session.signedPdfBuffer).toString('base64')
      : null,
  };
}

function deserializeSession(record) {
  if (!record || !record.sessionId) return null;
  return {
    ...record,
    pdfBuffer: Buffer.from(record.pdfBuffer || '', 'base64'),
    signedPdfBuffer: record.signedPdfBuffer
      ? Buffer.from(record.signedPdfBuffer, 'base64')
      : null,
  };
}

function persistSessions() {
  try {
    fs.mkdirSync(SESSION_STORE_DIR, { recursive: true });
    fs.writeFileSync(
      SESSION_STORE_FILE,
      JSON.stringify(Array.from(sessions.values()).map(serializeSession), null, 2),
      'utf8'
    );
  } catch (error) {
    console.error('[server] Failed to persist sessions:', error.message);
  }
}

function restoreSessions() {
  try {
    if (!fs.existsSync(SESSION_STORE_FILE)) return;
    const records = JSON.parse(fs.readFileSync(SESSION_STORE_FILE, 'utf8'));
    if (!Array.isArray(records)) return;
    sessions.clear();
    records
      .map(deserializeSession)
      .filter(Boolean)
      .forEach((session) => sessions.set(session.sessionId, session));
    pruneExpiredSessions();
  } catch (error) {
    console.error('[server] Failed to restore sessions:', error.message);
  }
}

function getProvidedToken(req) {
  const headerToken = req.headers['x-session-token'];
  if (typeof headerToken === 'string' && headerToken.trim()) return headerToken.trim();

  const queryToken = req.query?.token;
  if (typeof queryToken === 'string' && queryToken.trim()) return queryToken.trim();

  const bodyToken = req.body?.token;
  if (typeof bodyToken === 'string' && bodyToken.trim()) return bodyToken.trim();

  return null;
}

function hasValidSessionToken(session, token) {
  if (!session || !session.tokenHash || !token) return false;
  const expected = Buffer.from(session.tokenHash, 'hex');
  const actual = Buffer.from(hashToken(token), 'hex');
  if (expected.length !== actual.length) return false;
  return crypto.timingSafeEqual(expected, actual);
}

function isLoopbackRequest(req) {
  const candidates = [
    req.ip,
    req.socket?.remoteAddress,
    req.connection?.remoteAddress,
    req.headers['x-forwarded-for'],
  ].filter(Boolean);

  return candidates.some((value) => {
    const text = String(Array.isArray(value) ? value[0] : value);
    return text.includes('127.0.0.1') || text.includes('::1') || text.includes('localhost');
  });
}

function requireValidSession(req, res) {
  pruneExpiredSessions();
  const session = sessions.get(req.params.sessionId);
  if (!session) {
    res.status(404).json({ error: 'Session not found' });
    return null;
  }

  if (Date.now() > session.expiresAt) {
    res.status(410).json({ error: 'Session expired' });
    return null;
  }

  if (!hasValidSessionToken(session, getProvidedToken(req))) {
    res.status(401).json({ error: 'Invalid session token' });
    return null;
  }

  return session;
}

function listSessions(now = Date.now()) {
  pruneExpiredSessions(now);
  return Array.from(sessions.values()).map((session) => ({
    sessionId: session.sessionId,
    fileName: session.fileName,
    signerEmail: session.signerEmail,
    senderEmail: session.senderEmail,
    status: getEffectiveStatus(session, now),
    createdAt: session.createdAt,
    viewedAt: session.viewedAt,
    signedAt: session.signedAt,
    expiresAt: session.expiresAt,
  }));
}

async function createSession({
  pdfBuffer,
  fileName,
  signerEmail,
  senderEmail,
  senderName,
  message,
  smtpConfig,
  fields,
}) {
  const normalizedPdfBuffer = Buffer.isBuffer(pdfBuffer) ? pdfBuffer : Buffer.from(pdfBuffer || []);
  if (!normalizedPdfBuffer.length) throw new Error('PDF file required');
  if (normalizedPdfBuffer.length > MAX_SESSION_PDF_BYTES) {
    throw new Error(`PDF too large (${Math.ceil(MAX_SESSION_PDF_BYTES / (1024 * 1024))}MB limit)`);
  }
  if (!signerEmail) throw new Error('signerEmail required');

  const detectedFields = Array.isArray(fields)
    ? fields
    : await pdfProcessor.detectSignatureFields(normalizedPdfBuffer);

  const now = Date.now();
  const sessionId = createSessionId();
  const accessToken = createAccessToken();
  const session = {
    sessionId,
    tokenHash: hashToken(accessToken),
    pdfBuffer: normalizedPdfBuffer,
    fileName: fileName || 'document.pdf',
    signerEmail,
    senderEmail,
    senderName,
    message,
    encryptedSmtpConfig: wrapSecretJson(smtpConfig || null),
    fields: detectedFields,
    status: 'PENDING',
    signing: false,
    createdAt: now,
    viewedAt: null,
    expiresAt: now + SESSION_TTL_MS,
    signedPdfBuffer: null,
    signedAt: null,
  };

  sessions.set(sessionId, session);
  persistSessions();
  return { session, accessToken };
}

/* ─── Routes ─────────────────────────────────────────────────────────────── */

/**
 * POST /api/sessions
 * Body: multipart – pdf file + meta JSON string
 */
app.post('/api/sessions', upload.single('pdf'), async (req, res) => {
  try {
    const meta = JSON.parse(req.body.meta || '{}');
    const { signerEmail, senderEmail, senderName, message, smtpConfig } = meta;

    if (!req.file) return res.status(400).json({ error: 'PDF file required' });

    const { session, accessToken } = await createSession({
      pdfBuffer: req.file.buffer,
      fileName: req.file.originalname,
      signerEmail,
      senderEmail,
      senderName,
      message,
      smtpConfig,
    });

    res.json({
      ok: true,
      sessionId: session.sessionId,
      accessToken,
      fieldCount: session.fields.length,
    });
  } catch (err) {
    console.error('[server] create-session error:', err);
    res.status(500).json({ ok: false, error: err.message });
  }
});

/**
 * GET /api/sessions/:sessionId
 * Public metadata (no binary PDF)
 */
app.get('/api/sessions/:sessionId', (req, res) => {
  const session = requireValidSession(req, res);
  if (!session) return;

  markSessionViewed(session);
  res.json({
    sessionId: session.sessionId,
    fileName: session.fileName,
    senderName: session.senderName,
    message: session.message,
    fields: session.fields,
    status: getEffectiveStatus(session),
  });
});

/**
 * GET /api/sessions/:sessionId/document
 * Serves raw PDF bytes
 */
app.get('/api/sessions/:sessionId/document', (req, res) => {
  const session = requireValidSession(req, res);
  if (!session) return;

  markSessionViewed(session);
  res.setHeader('Content-Type', 'application/pdf');
  res.setHeader('Content-Disposition', `inline; filename="${session.fileName}"`);
  res.end(session.pdfBuffer);
});

/**
 * POST /api/sessions/:sessionId/sign
 * Body JSON: { signerName, signerEmail, signatures: [{fieldIndex, imageBase64}], token }
 */
app.post('/api/sessions/:sessionId/sign', async (req, res) => {
  const session = requireValidSession(req, res);
  if (!session) return;

  try {
    const status = getEffectiveStatus(session);
    if (status === 'SIGNED') return res.status(409).json({ error: 'Already signed' });
    if (status === 'EXPIRED') return res.status(410).json({ error: 'Session expired' });
    if (session.signing) return res.status(409).json({ error: 'Signing already in progress' });

    const { signerName, signerEmail, signatures } = req.body;
    if (!signerName) return res.status(400).json({ error: 'signerName required' });
    if (!Array.isArray(signatures) || signatures.length === 0) {
      return res.status(400).json({ error: 'signatures required' });
    }

    session.signing = true;

    const signedPdfBytes = await pdfProcessor.embedSignatures(
      session.pdfBuffer,
      session.fields,
      signatures,
      { signerName, signerEmail: signerEmail || session.signerEmail }
    );

    session.status = 'SIGNED';
    session.signedAt = Date.now();
    session.signedPdfBuffer = signedPdfBytes;
    persistSessions();

    const smtpConfig = unwrapSecretJson(session.encryptedSmtpConfig);
    if (smtpConfig && session.senderEmail) {
      try {
        await emailer.sendSignedDoc({
          smtpConfig,
          from: session.senderEmail,
          to: session.senderEmail,
          senderName: session.senderName,
          signerName,
          signerEmail: session.signerEmail,
          fileName: session.fileName,
          pdfBuffer: Buffer.from(signedPdfBytes),
        });
      } catch (mailErr) {
        console.error('[server] email send failed:', mailErr.message);
      }
    }

    res.json({ ok: true });
  } catch (err) {
    console.error('[server] sign error:', err);
    res.status(500).json({ ok: false, error: err.message });
  } finally {
    session.signing = false;
  }
});

/**
 * GET /api/sessions/:sessionId/signed
 * Download the signed PDF.
 */
app.get('/api/sessions/:sessionId/signed', (req, res) => {
  const session = requireValidSession(req, res);
  if (!session) return;
  if (!session.signedPdfBuffer) return res.status(404).json({ error: 'Signed PDF not found' });

  res.setHeader('Content-Type', 'application/pdf');
  res.setHeader('Content-Disposition', `attachment; filename="signed-${session.fileName}"`);
  res.end(Buffer.from(session.signedPdfBuffer));
});

/**
 * GET /api/sessions  (loopback only)
 * List all sessions meta
 */
app.get('/api/sessions', (req, res) => {
  if (!isLoopbackRequest(req)) {
    return res.status(403).json({ error: 'Forbidden' });
  }
  res.json(listSessions());
});

/**
 * GET /sign/:sessionId  →  serves the signing landing page
 */
app.get('/sign/:sessionId', (_req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'sign.html'));
});

/* ─── Server lifecycle ────────────────────────────────────────────────────── */

let httpServer = null;
let tunnel = null;
let cleanupTimer = null;

/**
 * Start Express on given port.
 * @param {number} port
 * @returns {Promise<number>}
 */
function start(port = 3456) {
  if (httpServer) return Promise.resolve(port);
  return new Promise((resolve, reject) => {
    restoreSessions();
    httpServer = app.listen(port, '127.0.0.1', () => {
      if (!cleanupTimer) {
        cleanupTimer = setInterval(() => pruneExpiredSessions(), 15 * 60 * 1000);
      }
      resolve(port);
    });
    httpServer.on('error', reject);
  });
}

/**
 * Start localtunnel so the signing page is reachable from the internet.
 * Returns the public URL.
 * @param {number} port
 * @returns {Promise<string>}
 */
async function startTunnel(port = 3456) {
  if (tunnel) return tunnel.url;
  const lt = require('localtunnel');
  tunnel = await lt({ port, allow_invalid_cert: true });
  tunnel.on('error', (err) => console.error('[tunnel] error:', err));
  tunnel.on('close', () => { tunnel = null; });
  return tunnel.url;
}

function stopTunnel() {
  if (tunnel) {
    tunnel.close();
    tunnel = null;
  }
}

function stop() {
  stopTunnel();
  if (cleanupTimer) {
    clearInterval(cleanupTimer);
    cleanupTimer = null;
  }
  if (httpServer) {
    httpServer.close();
    httpServer = null;
  }
}

module.exports = {
  start,
  stop,
  startTunnel,
  stopTunnel,
  sessions,
  createSession,
  listSessions,
  MAX_SESSION_PDF_BYTES,
  persistSessions,
  restoreSessions,
  __test: {
    SESSION_TTL_MS,
    SESSION_PURGE_GRACE_MS,
    createSessionId,
    createAccessToken,
    hashToken,
    wrapSecretJson,
    unwrapSecretJson,
    getEffectiveStatus,
    markSessionViewed,
    pruneExpiredSessions,
    hasValidSessionToken,
    isLoopbackRequest,
    serializeSession,
    deserializeSession,
  },
};
