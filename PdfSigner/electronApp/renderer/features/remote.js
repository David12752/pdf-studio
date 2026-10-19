'use strict';
/**
 * features/remote.js
 * Full remote signing flow:
 *   1. Create session → upload PDF to local server → receive HTTP signing URL
 *   2. Optionally start localtunnel for public access
 *   3. Show QR code + copy-link
 *   4. Poll sessions and display status (PENDING / SIGNED)
 *   5. SIGNED → allow downloading the signed PDF
 */

(function (globalScope) {
  function createRemoteFeature(deps) {
    const { $, toast, nativeApi, loadJson, settingsFile } = deps;
    const MAX_REMOTE_FILE_BYTES = 100 * 1024 * 1024;

    /* ── helpers ────────────────────────────────────────────────── */
    let pollTimer = null;
    let highlightedSessionId = null;

    function safeVal(id) {
      const el = $(id);
      return el ? el.value.trim() : '';
    }

    function setServerStatus(running, tunnelUrl) {
      const dot  = $('serverDot');
      const text = $('serverStatusText');
      if (!dot || !text) return;
      dot.classList.remove('online');
      if (tunnelUrl) {
        dot.style.background = '#10b981';
        dot.classList.add('online');
        text.textContent     = `✅ פעיל | ${tunnelUrl}`;
      } else if (running) {
        dot.style.background = '#f59e0b';
        text.textContent     = '🟡 שרת מקומי פעיל (ללא גישה ציבורית)';
      } else {
        dot.style.background = '#ef4444';
        text.textContent     = '❌ שרת לא פעיל — נסה להפעיל מחדש';
      }
    }

    /* ── SMTP config reader ─────────────────────────────────────── */
    function getSmtpConfig() {
      const host   = safeVal('smtpHost');
      const port   = parseInt(safeVal('smtpPort') || '465', 10);
      const secure = $('smtpSecure') ? $('smtpSecure').checked : true;
      const user   = safeVal('smtpUser');
      const pass   = safeVal('smtpPass');
      if (!host || !user || !pass) return null;
      return { host, port, secure, auth: { user, pass } };
    }

    function getActiveChannel() {
      const activeTab = $('deliveryTabs') && $('deliveryTabs').querySelector('.panel-tab.active');
      return activeTab ? activeTab.dataset.channel : 'email';
    }

    function updateDeliveryUi() {
      const channel = getActiveChannel();
      const phoneInput = $('remotePhone');
      const hint = $('remoteDeliveryHint');
      if (phoneInput) {
        const needsPhone = channel === 'whatsapp' || channel === 'sms';
        phoneInput.style.display = needsPhone ? '' : 'none';
      }
      if (hint) {
        hint.textContent = {
          email: 'מסירת מייל אוטומטית דורשת SMTP פעיל וקישור ציבורי.',
          whatsapp: 'WhatsApp ייפתח אוטומטית עם הודעה מוכנה וקישור ציבורי.',
          sms: 'SMS ייפתח אוטומטית עם הודעה מוכנה וקישור ציבורי.',
          none: 'במצב ידני תוכל להעתיק את הקישור או לפתוח את ה־QR לאחר יצירת הבקשה.',
        }[channel] || '';
      }
    }

    async function ensurePublicTunnel(channel) {
      if (channel === 'none') return;
      const existingTunnel = await nativeApi.serverGetTunnelUrl();
      if (existingTunnel) {
        setServerStatus(true, existingTunnel);
        return;
      }
      toast('מפעיל גישה ציבורית לקישור החתימה…');
      const res = await nativeApi.serverStartTunnel();
      if (!res.ok || !res.tunnelUrl) {
        throw new Error(res.error || 'לא ניתן להפעיל גישה ציבורית');
      }
      setServerStatus(true, res.tunnelUrl);
      const btnStart = $('btnStartTunnel');
      const btnStop = $('btnStopTunnel');
      if (btnStart) btnStart.style.display = 'none';
      if (btnStop) btnStop.style.display = '';
    }

    /* ── Session history ────────────────────────────────────────── */
    async function renderSessions() {
      const container = $('sessionHistory');
      if (!container) return;
      const sessions = await nativeApi.serverGetSessions();
      if (!sessions || sessions.length === 0) {
        container.innerHTML = '<div style="color:var(--muted);font-size:.85rem;text-align:center;padding:20px;">אין בקשות חתימה עדיין.</div>';
        return;
      }
      container.innerHTML = '';
      for (const s of sessions) {
        const div     = document.createElement('div');
        div.className = 'session-item';
        div.dataset.sessionId = s.sessionId;
        const age     = formatAge(s.createdAt);
        const status  = s.status || 'PENDING';
        const statusLabel = {
          PENDING: '⏳ ממתין',
          VIEWED: '👁 נצפה',
          SIGNED: '✅ נחתם',
          EXPIRED: '🔒 פג תוקף',
        }[status] || status;
        div.innerHTML = `
          <div class="session-item-info">
            <div class="session-item-name" title="${escHtml(s.fileName)}">${escHtml(s.fileName)}</div>
            <div class="session-item-meta">${escHtml(s.signerEmail)} · ${age}</div>
          </div>
          <span class="session-badge ${status}">${statusLabel}</span>
          ${s.status === 'SIGNED'
            ? `<button class="session-download-btn" data-id="${escHtml(s.sessionId)}">⬇ הורד</button>`
            : ''}
        `;
        if (highlightedSessionId === s.sessionId) {
          div.style.borderColor = 'var(--accent, #2563eb)';
          div.style.boxShadow = '0 0 0 2px rgba(37,99,235,.18)';
          div.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        }
        container.appendChild(div);
      }
      container.querySelectorAll('.session-download-btn').forEach((btn) => {
        btn.addEventListener('click', async () => {
          const sid      = btn.dataset.id;
          const destPath = nativeApi.joinPath(nativeApi.homedir(), 'Downloads', `signed-${sid}.pdf`);
          const res      = await nativeApi.serverDownloadSigned({ sessionId: sid, destPath });
          if (res.ok) { toast(`✅ נשמר: ${res.destPath}`); nativeApi.openPath(res.destPath); }
          else         toast(`שגיאה: ${res.error}`);
        });
      });
    }

    function startPoll() {
      if (pollTimer) return;
      pollTimer = setInterval(renderSessions, 6000);
    }

    /* ── SMTP auto-populate from saved settings ─────────────────── */
    function populateSmtpFromSettings() {
      try {
        if (typeof loadJson !== 'function' || !settingsFile) return;
        const cfg = loadJson(settingsFile, {}).smtp;
        if (!cfg) return;
        const set = (id, val) => { const el = $(id); if (el && !el.value) el.value = val || ''; };
        const check = (id, val) => { const el = $(id); if (el && val !== undefined) el.checked = !!val; };
        set('smtpHost', cfg.host);
        set('smtpPort', String(cfg.port || ''));
        set('smtpUser', cfg.auth?.user);
        set('smtpPass', cfg.auth?.pass);
        check('smtpSecure', cfg.secure !== false);
      } catch (e) {
        console.warn('[remote] SMTP populate failed:', e.message);
      }
    }

    /* ── Init ───────────────────────────────────────────────────── */
    async function init() {
      // Real server status check
      try {
        const status = await nativeApi.serverIsRunning();
        setServerStatus(status.running, status.tunnelUrl || null);
        if (status.tunnelUrl) {
          const btnStart = $('btnStartTunnel');
          const btnStop  = $('btnStopTunnel');
          if (btnStart) btnStart.style.display = 'none';
          if (btnStop)  btnStop.style.display = '';
        }
      } catch {
        setServerStatus(false, null);
      }

      populateSmtpFromSettings();
      updateDeliveryUi();
      renderSessions();
      startPoll();

      const btnStart = $('btnStartTunnel');
      const btnStop  = $('btnStopTunnel');

      if (btnStart) {
        btnStart.addEventListener('click', async () => {
          btnStart.disabled = true;
          btnStart.textContent = '⏳ מתחבר…';
          toast('מפעיל גישה ציבורית… (עד 15 שניות)');
          const res = await nativeApi.serverStartTunnel();
          if (res.ok) {
            setServerStatus(true, res.tunnelUrl);
            toast('✅ גישה ציבורית פעילה');
            btnStart.style.display = 'none';
            if (btnStop) btnStop.style.display = '';
          } else {
            toast(`❌ שגיאה: ${res.error}`);
            btnStart.disabled    = false;
            btnStart.textContent = '🌐 הפעל גישה ציבורית';
          }
        });
      }

      if (btnStop) {
        btnStop.addEventListener('click', async () => {
          await nativeApi.serverStopTunnel();
          setServerStatus(true, null);
          btnStop.style.display = 'none';
          if (btnStart) { btnStart.style.display = ''; btnStart.disabled = false; btnStart.textContent = '🌐 הפעל גישה ציבורית'; }
          toast('מנהרה הופסקה.');
        });
      }

      const btnCopy = $('btnCopyLink');
      if (btnCopy) {
        btnCopy.addEventListener('click', () => {
          const link = $('remoteLink') && $('remoteLink').textContent;
          if (link) { navigator.clipboard.writeText(link); toast('✅ הקישור הועתק!'); }
        });
      }

      const btnOpenLink = $('btnOpenLink');
      if (btnOpenLink) {
        btnOpenLink.addEventListener('click', () => {
          const link = $('remoteLink') && $('remoteLink').textContent;
          if (link) nativeApi.openExternal(link);
        });
      }

      const btnRefresh = $('btnRefreshSessions');
      if (btnRefresh) btnRefresh.addEventListener('click', renderSessions);

      const btnCreate = $('btnCreateSession');
      if (btnCreate) {
        // wire delivery-channel tab switcher
        const deliveryTabs = $('deliveryTabs');
        if (deliveryTabs) {
          deliveryTabs.addEventListener('click', (e) => {
            const tab = e.target.closest('[data-channel]');
            if (!tab) return;
            deliveryTabs.querySelectorAll('.panel-tab').forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            updateDeliveryUi();
          });
        }

        btnCreate.addEventListener('click', async () => {
          const file        = $('remoteDoc').files[0];
          const signerEmail = safeVal('remoteEmail');
          const channel     = getActiveChannel();
          const smtpConfig  = getSmtpConfig();
          const signerPhone = safeVal('remotePhone');

          if (!file)                    return toast('⚠️ בחר קובץ PDF');
          if (!signerEmail.includes('@')) return toast('⚠️ אימייל חותם לא תקין');
          if (file.size > MAX_REMOTE_FILE_BYTES) return toast('⚠️ הקובץ גדול מדי (100MB max)');
          if (channel === 'email' && !smtpConfig) return toast('⚠️ כדי לשלוח מייל אוטומטי יש למלא SMTP');
          if ((channel === 'whatsapp' || channel === 'sms') && !signerPhone) return toast('⚠️ מספר טלפון נדרש לערוץ זה');

          btnCreate.disabled    = true;
          btnCreate.textContent = '⏳ יוצר בקשה…';
          try {
            await ensurePublicTunnel(channel);

            // file.path is undefined in modern Electron — use FileReader instead
            const pdfBase64 = await new Promise((resolve, reject) => {
              const reader = new FileReader();
              reader.onload  = (e) => {
                const bytes = new Uint8Array(e.target.result);
                let bin = '';
                for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
                resolve(btoa(bin));
              };
              reader.onerror = () => reject(new Error('שגיאה בקריאת הקובץ'));
              reader.readAsArrayBuffer(file);
            });
            const senderEmail = safeVal('remoteSenderEmail') || smtpConfig?.auth?.user || signerEmail;

            const result = await nativeApi.serverCreateSession({
              pdfBase64,
              fileName:    file.name,
              signerEmail,
              senderEmail,
              senderName:  safeVal('remoteSenderName')  || 'PDF Studio',
              message:     safeVal('remoteMessage'),
              smtpConfig,
            });

            if (!result.ok) throw new Error(result.error || 'שגיאה לא ידועה');

            const { signUrl, fieldCount } = result;

            // ── auto-send invitation ─────────────────────────────────
            if (channel !== 'none') {
              try {
                const inviteResult = await nativeApi.serverSendInvite({
                  channel,
                  signUrl,
                  signerEmail,
                  signerPhone,
                  senderName:  safeVal('remoteSenderName') || 'PDF Studio',
                  senderEmail,
                  fileName:    file.name,
                  message:     safeVal('remoteMessage'),
                  smtpConfig,
                });
                if (inviteResult.ok && inviteResult.openUrl) {
                  nativeApi.openExternal(inviteResult.openUrl);
                } else if (!inviteResult.ok) {
                  toast(`⚠️ שליחה נכשלה: ${inviteResult.error}`);
                }
              } catch (invErr) {
                toast(`⚠️ שגיאה בשליחה: ${invErr.message}`);
              }
            }
            const resultCard = $('sessionResultCard');
            if (resultCard) resultCard.style.display = '';
            if ($('remoteLink'))        $('remoteLink').textContent         = signUrl;
            if ($('remoteFieldCount'))  $('remoteFieldCount').textContent   = fieldCount;

            const qrCanvas = $('remoteQr');
            if (qrCanvas) {
              const qrDataUrl = await nativeApi.qrcodeToDataURL(signUrl, { width: 180, margin: 1 });
              await new Promise((res) => {
                const img = new Image();
                img.onload = () => {
                  qrCanvas.width  = img.naturalWidth;
                  qrCanvas.height = img.naturalHeight;
                  qrCanvas.getContext('2d').drawImage(img, 0, 0);
                  res();
                };
                img.src = qrDataUrl;
              });
            }

            toast(`✅ בקשה נוצרה! ${fieldCount} שד${fieldCount === 1 ? 'ה' : 'ות'} חתימה`);
            renderSessions();
          } catch (err) {
            toast(`❌ ${err.message}`);
          } finally {
            btnCreate.disabled    = false;
            btnCreate.textContent = 'צור קישור חתימה';
          }
        });
      }
    }

    async function handleDeepLink(sessionId) {
      highlightedSessionId = sessionId;
      const resultCard = $('sessionResultCard');
      if (resultCard) resultCard.style.display = '';
      await renderSessions();
      const container = $('sessionHistory');
      const target = container && container.querySelector(`[data-session-id="${CSS.escape(sessionId)}"]`);
      if (target) {
        target.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
      toast(`Deep link opened for session ${sessionId}`);
    }

    return { init, renderSessions, handleDeepLink };
  }

  function formatAge(ms) {
    const diff = Date.now() - ms;
    const m = Math.floor(diff / 60000);
    if (m < 1)  return 'עכשיו';
    if (m < 60) return `לפני ${m} דק'`;
    const h = Math.floor(m / 60);
    if (h < 24) return `לפני ${h} שע'`;
    return `לפני ${Math.floor(h / 24)} ימים`;
  }

  function escHtml(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  const api = { createRemoteFeature };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  globalScope.PdfStudioRemote = api;
})(typeof window !== 'undefined' ? window : globalThis);

