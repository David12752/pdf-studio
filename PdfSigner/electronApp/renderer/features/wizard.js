/**
 * First-Time Setup Wizard — PDF Studio Desktop
 * Multi-step onboarding flow for new users.
 *
 * Steps:
 *   1. Welcome — overview of features
 *   2. Profile — name, company
 *   3. Signature — draw first signature
 *   4. SMTP — optional email config
 *   5. Done — summary
 */
function createWizardFeature({ $, toast, nativeApi }) {
  let currentStep = 0;
  const totalSteps = 5;
  let onComplete = null;
  let sigCanvas = null;
  let sigCtx = null;
  let drawing = false;
  let lastPoint = null;
  let signatureDataUrl = null;

  const wizardData = {
    displayName: '',
    company: '',
    signatureDataUrl: null,
    smtp: null,
  };

  function createOverlay() {
    const overlay = document.createElement('div');
    overlay.id = 'wizardOverlay';
    overlay.className = 'wizard-overlay';
    overlay.innerHTML = `
      <div class="wizard-card">
        <div class="wizard-step-indicator" id="wizStepIndicator"></div>
        <div id="wizContent"></div>
        <div class="wizard-actions" id="wizActions"></div>
      </div>
    `;
    document.body.appendChild(overlay);
    return overlay;
  }

  function renderStepIndicator() {
    const el = document.getElementById('wizStepIndicator');
    if (!el) return;
    let html = '';
    for (let i = 0; i < totalSteps; i++) {
      const cls = i < currentStep ? 'done' : i === currentStep ? 'active' : '';
      html += `<div class="wizard-step-dot ${cls}"></div>`;
      if (i < totalSteps - 1) {
        const lineCls = i < currentStep ? 'done' : '';
        html += `<div class="wizard-step-line ${lineCls}"></div>`;
      }
    }
    el.innerHTML = html;
  }

  function renderStep() {
    renderStepIndicator();
    const content = document.getElementById('wizContent');
    const actions = document.getElementById('wizActions');
    if (!content || !actions) return;

    switch (currentStep) {
      case 0: renderWelcome(content, actions); break;
      case 1: renderProfile(content, actions); break;
      case 2: renderSignature(content, actions); break;
      case 3: renderSmtp(content, actions); break;
      case 4: renderDone(content, actions); break;
    }
    // Retrigger fade-in animation on each step
    content.style.animation = 'none';
    // eslint-disable-next-line no-unused-expressions
    content.offsetHeight; // reflow
    content.style.animation = '';
  }

  function renderWelcome(content, actions) {
    content.innerHTML = `
      <h2 class="wizard-title">Welcome to PDF Studio</h2>
      <p class="wizard-desc">Your professional PDF signing and document management toolkit. Let's set up your workspace in a few quick steps.</p>
      <div class="wizard-feature-grid">
        <div class="wizard-feature-item">
          <div class="wf-icon">✍️</div>
          <div class="wf-label">Digital Signatures</div>
          <div class="wf-desc">Draw and place signatures on PDFs</div>
        </div>
        <div class="wizard-feature-item">
          <div class="wf-icon">🔧</div>
          <div class="wf-label">PDF Tools</div>
          <div class="wf-desc">Merge, split, watermark, protect</div>
        </div>
        <div class="wizard-feature-item">
          <div class="wf-icon">📡</div>
          <div class="wf-label">Remote Signing</div>
          <div class="wf-desc">Send documents for remote signing</div>
        </div>
        <div class="wizard-feature-item">
          <div class="wf-icon">☁️</div>
          <div class="wf-label">Cloud Sync</div>
          <div class="wf-desc">Your data synced across devices</div>
        </div>
      </div>
    `;
    actions.innerHTML = `<button class="btn-primary" id="wizNext">Get Started →</button>`;
    document.getElementById('wizNext').addEventListener('click', nextStep);
  }

  function renderProfile(content, actions) {
    content.innerHTML = `
      <h2 class="wizard-title">Set Up Your Profile</h2>
      <p class="wizard-desc">This information appears on documents you sign and send.</p>
      <div class="wizard-form">
        <div>
          <label class="field-label">Full Name *</label>
          <input id="wizName" type="text" placeholder="Your full name" style="width:100%;margin-top:4px;" value="${wizardData.displayName}" />
        </div>
        <div>
          <label class="field-label">Company (optional)</label>
          <input id="wizCompany" type="text" placeholder="Company or organization" style="width:100%;margin-top:4px;" value="${wizardData.company}" />
        </div>
      </div>
    `;
    actions.innerHTML = `
      <button id="wizBack">Back</button>
      <button class="btn-primary" id="wizNext">Next →</button>
    `;
    document.getElementById('wizBack').addEventListener('click', prevStep);
    document.getElementById('wizNext').addEventListener('click', () => {
      const name = document.getElementById('wizName')?.value?.trim();
      if (!name) { toast('Please enter your name'); return; }
      wizardData.displayName = name;
      wizardData.company = document.getElementById('wizCompany')?.value?.trim() || '';
      nextStep();
    });
  }

  function renderSignature(content, actions) {
    content.innerHTML = `
      <h2 class="wizard-title">Draw Your Signature</h2>
      <p class="wizard-desc">This will be saved as your default signature. You can always create more later.</p>
      <canvas id="wizSigCanvas" class="wizard-sig-canvas" width="460" height="140"></canvas>
      <div style="display:flex;gap:8px;margin-top:4px;">
        <button id="wizSigClear" class="btn-small">Clear</button>
      </div>
    `;
    actions.innerHTML = `
      <button id="wizBack">Back</button>
      <button id="wizSkipSig">Skip</button>
      <button class="btn-primary" id="wizNext">Next →</button>
    `;

    sigCanvas = document.getElementById('wizSigCanvas');
    sigCtx = sigCanvas.getContext('2d');
    sigCtx.fillStyle = 'white';
    sigCtx.fillRect(0, 0, sigCanvas.width, sigCanvas.height);
    signatureDataUrl = null;
    wireSignatureDrawing();

    document.getElementById('wizSigClear').addEventListener('click', () => {
      sigCtx.fillStyle = 'white';
      sigCtx.fillRect(0, 0, sigCanvas.width, sigCanvas.height);
      signatureDataUrl = null;
    });

    document.getElementById('wizBack').addEventListener('click', prevStep);
    document.getElementById('wizSkipSig').addEventListener('click', () => {
      wizardData.signatureDataUrl = null;
      nextStep();
    });
    document.getElementById('wizNext').addEventListener('click', () => {
      if (signatureDataUrl) {
        wizardData.signatureDataUrl = signatureDataUrl;
      }
      nextStep();
    });
  }

  function wireSignatureDrawing() {
    if (!sigCanvas) return;

    sigCanvas.addEventListener('mousedown', (e) => {
      drawing = true;
      const r = sigCanvas.getBoundingClientRect();
      lastPoint = {
        x: (e.clientX - r.left) * (sigCanvas.width / r.width),
        y: (e.clientY - r.top) * (sigCanvas.height / r.height),
      };
      sigCtx.beginPath();
      sigCtx.moveTo(lastPoint.x, lastPoint.y);
      sigCtx.strokeStyle = '#000000';
      sigCtx.lineWidth = 3;
      sigCtx.lineCap = 'round';
      sigCtx.lineJoin = 'round';
    });

    sigCanvas.addEventListener('mousemove', (e) => {
      if (!drawing) return;
      const r = sigCanvas.getBoundingClientRect();
      const p = {
        x: (e.clientX - r.left) * (sigCanvas.width / r.width),
        y: (e.clientY - r.top) * (sigCanvas.height / r.height),
      };
      if (lastPoint) {
        const midX = (lastPoint.x + p.x) / 2;
        const midY = (lastPoint.y + p.y) / 2;
        sigCtx.quadraticCurveTo(lastPoint.x, lastPoint.y, midX, midY);
        sigCtx.stroke();
      }
      lastPoint = p;
    });

    const stopDraw = () => {
      if (!drawing) return;
      drawing = false;
      lastPoint = null;
      signatureDataUrl = sigCanvas.toDataURL('image/png');
    };

    sigCanvas.addEventListener('mouseup', stopDraw);
    window.addEventListener('mouseup', stopDraw);
  }

  function renderSmtp(content, actions) {
    content.innerHTML = `
      <h2 class="wizard-title">📧 Email Setup (Optional)</h2>
      <p class="wizard-desc">PDF Studio can send signing invitations automatically by email.<br>For this, it needs your SMTP credentials — the email server settings used to send on your behalf.</p>

      <div class="wizard-info-box">
        <div class="wib-icon">💡</div>
        <div class="wib-body">
          <strong>Why SMTP?</strong><br>
          SMTP (Simple Mail Transfer Protocol) is the standard protocol for sending emails.
          You provide your email credentials once, and PDF Studio uses them to dispatch
          signing requests to your clients — without any 3rd-party service.<br><br>
          <strong>Using Gmail?</strong> You need an
          <a href="#" class="wiz-ext-link" data-href="https://support.google.com/accounts/answer/185833">App Password</a>
          (not your regular password). Go to
          <a href="#" class="wiz-ext-link" data-href="https://myaccount.google.com/apppasswords">Google Account → App Passwords</a>
          and create one for "Mail".<br><br>
          <strong>Using Outlook/Office 365?</strong> Use
          <a href="#" class="wiz-ext-link" data-href="https://support.microsoft.com/en-us/office/pop-imap-and-smtp-settings-8361e398-8af4-4e97-b147-6c6c4ac95353">smtp.office365.com</a>, port 587, TLS.
        </div>
      </div>

      <div class="wizard-form">
        <div style="display:grid;grid-template-columns:1fr auto;gap:8px;align-items:end;">
          <div>
            <label class="field-label">SMTP Host</label>
            <input id="wizSmtpHost" type="text" placeholder="smtp.gmail.com" style="width:100%;margin-top:4px;" />
          </div>
          <div>
            <label class="field-label">Port</label>
            <input id="wizSmtpPort" type="number" placeholder="465" value="465" style="width:80px;margin-top:4px;" />
          </div>
        </div>
        <label style="font-size:.8rem;color:var(--text-2);display:flex;align-items:center;gap:6px;cursor:pointer;">
          <input id="wizSmtpSecure" type="checkbox" checked />
          Use SSL/TLS (recommended — port 465)
        </label>
        <div>
          <label class="field-label">Your Email Address</label>
          <input id="wizSmtpUser" type="email" placeholder="you@gmail.com" style="width:100%;margin-top:4px;" />
        </div>
        <div>
          <label class="field-label">App Password <span style="color:var(--muted);font-weight:400;text-transform:none;letter-spacing:0;">(not your regular password)</span></label>
          <input id="wizSmtpPass" type="password" placeholder="16-character app password" style="width:100%;margin-top:4px;" />
        </div>
      </div>
    `;
    actions.innerHTML = `
      <button id="wizBack">← Back</button>
      <button id="wizSkipSmtp">Skip for now</button>
      <button class="btn-primary" id="wizNext">Next →</button>
    `;

    // Wire external help links
    content.querySelectorAll('.wiz-ext-link').forEach(a => {
      a.addEventListener('click', (e) => {
        e.preventDefault();
        if (typeof nativeApi !== 'undefined' && nativeApi.openExternal) {
          nativeApi.openExternal(a.dataset.href);
        }
      });
    });

    document.getElementById('wizBack').addEventListener('click', prevStep);
    document.getElementById('wizSkipSmtp').addEventListener('click', () => {
      wizardData.smtp = null;
      nextStep();
    });
    document.getElementById('wizNext').addEventListener('click', () => {
      const host = document.getElementById('wizSmtpHost')?.value?.trim();
      const user = document.getElementById('wizSmtpUser')?.value?.trim();
      if (host && user) {
        wizardData.smtp = {
          host,
          port: Number(document.getElementById('wizSmtpPort')?.value || 465),
          secure: document.getElementById('wizSmtpSecure')?.checked ?? true,
          user,
          pass: document.getElementById('wizSmtpPass')?.value || '',
        };
      } else {
        wizardData.smtp = null;
      }
      nextStep();
    });
  }

  function renderDone(content, actions) {
    const items = [];
    if (wizardData.displayName)     items.push(`<li><strong>Name:</strong> ${wizardData.displayName}</li>`);
    if (wizardData.company)         items.push(`<li><strong>Company:</strong> ${wizardData.company}</li>`);
    if (wizardData.signatureDataUrl) items.push(`<li><strong>Signature:</strong> ✅ Saved</li>`);
    else                            items.push(`<li><strong>Signature:</strong> Skipped</li>`);
    if (wizardData.smtp)            items.push(`<li><strong>Email:</strong> ${wizardData.smtp.user}</li>`);
    else                            items.push(`<li><strong>Email:</strong> Skipped</li>`);

    content.innerHTML = `
      <h2 class="wizard-title">You're All Set! 🎉</h2>
      <p class="wizard-desc">Here's a summary of your setup:</p>
      <ul style="list-style:none;padding:0;display:flex;flex-direction:column;gap:6px;font-size:.88rem;color:var(--text-2);">
        ${items.join('')}
      </ul>
      <p class="wizard-desc" style="margin-top:8px;">You can change any of these in Settings at any time.</p>
    `;
    actions.innerHTML = `
      <button id="wizBack">Back</button>
      <button class="btn-primary" id="wizFinish">Start Using PDF Studio →</button>
    `;

    document.getElementById('wizBack').addEventListener('click', prevStep);
    document.getElementById('wizFinish').addEventListener('click', finish);
  }

  function nextStep() {
    if (currentStep < totalSteps - 1) {
      currentStep++;
      renderStep();
    }
  }

  function prevStep() {
    if (currentStep > 0) {
      currentStep--;
      renderStep();
    }
  }

  function finish() {
    const overlay = document.getElementById('wizardOverlay');
    if (overlay) overlay.remove();
    if (onComplete) onComplete(wizardData);
  }

  function init(completionCallback) {
    onComplete = completionCallback;
    currentStep = 0;
    createOverlay();
    renderStep();
  }

  return { init };
}

window.PdfStudioWizard = { createWizardFeature };
