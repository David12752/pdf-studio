const test = require('node:test');
const assert = require('node:assert/strict');

const signingServer = require('../server/server');

test.afterEach(() => {
  signingServer.sessions.clear();
  signingServer.persistSessions();
  signingServer.stop();
});

test.beforeEach(() => {
  signingServer.sessions.clear();
  signingServer.persistSessions();
});

test('createSession issues strong identifiers and stores only token hash', async () => {
  const { session, accessToken } = await signingServer.createSession({
    pdfBuffer: Buffer.from('%PDF-1.7'),
    fileName: 'contract.pdf',
    signerEmail: 'client@example.com',
    senderEmail: 'sender@example.com',
    senderName: 'PDF Studio',
    message: 'Please sign',
    smtpConfig: { host: 'smtp.example.com', auth: { user: 'u', pass: 'p' } },
    fields: [],
  });

  assert.match(session.sessionId, /^[A-F0-9]{40}$/);
  assert.match(accessToken, /^[a-f0-9]{48}$/);
  assert.equal(session.tokenHash.length, 64);
  assert.notEqual(session.tokenHash, accessToken);
  assert.equal(signingServer.__test.hasValidSessionToken(session, accessToken), true);
  assert.equal(signingServer.__test.hasValidSessionToken(session, `${accessToken}x`), false);
  assert.deepEqual(signingServer.__test.unwrapSecretJson(session.encryptedSmtpConfig), {
    host: 'smtp.example.com',
    auth: { user: 'u', pass: 'p' },
  });
});

test('createSession rejects oversized PDFs', async () => {
  await assert.rejects(
    signingServer.createSession({
      pdfBuffer: Buffer.alloc(signingServer.MAX_SESSION_PDF_BYTES + 1),
      fileName: 'huge.pdf',
      signerEmail: 'client@example.com',
      fields: [],
    }),
    /PDF too large/i
  );
});

test('session status transitions from pending to viewed to expired', () => {
  const now = Date.now();
  const session = {
    status: 'PENDING',
    viewedAt: null,
    expiresAt: now + 5000,
  };

  assert.equal(signingServer.__test.getEffectiveStatus(session, now), 'PENDING');
  signingServer.__test.markSessionViewed(session, now + 1000);
  assert.equal(signingServer.__test.getEffectiveStatus(session, now + 1000), 'VIEWED');
  assert.equal(signingServer.__test.getEffectiveStatus(session, now + 6000), 'EXPIRED');
});

test('pruneExpiredSessions removes stale sessions after grace window', () => {
  const now = Date.now();
  signingServer.sessions.set('KEEP', {
    expiresAt: now - signingServer.__test.SESSION_PURGE_GRACE_MS + 1000,
  });
  signingServer.sessions.set('DROP', {
    expiresAt: now - signingServer.__test.SESSION_PURGE_GRACE_MS - 1000,
  });

  signingServer.__test.pruneExpiredSessions(now);

  assert.equal(signingServer.sessions.has('KEEP'), true);
  assert.equal(signingServer.sessions.has('DROP'), false);
});

test('session serialization preserves buffers across persistence boundaries', () => {
  const original = {
    sessionId: 'ABC123',
    pdfBuffer: Buffer.from('pdf-bytes'),
    signedPdfBuffer: Buffer.from('signed-bytes'),
    status: 'SIGNED',
  };

  const serialized = signingServer.__test.serializeSession(original);
  const restored = signingServer.__test.deserializeSession(serialized);

  assert.equal(serialized.pdfBuffer, Buffer.from('pdf-bytes').toString('base64'));
  assert.equal(restored.pdfBuffer.equals(Buffer.from('pdf-bytes')), true);
  assert.equal(restored.signedPdfBuffer.equals(Buffer.from('signed-bytes')), true);
  assert.equal(restored.status, 'SIGNED');
});