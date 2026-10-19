'use strict';
/**
 * emailer.js
 * Sends the signed PDF to the document sender via SMTP (nodemailer).
 */

const nodemailer = require('nodemailer');

/**
 * @param {object} opts
 * @param {object} opts.smtpConfig  - nodemailer transport options
 * @param {string} opts.from        - sender address (SMTP account)
 * @param {string} opts.to          - recipient address (document sender)
 * @param {string} opts.senderName  - friendly name of document sender
 * @param {string} opts.signerName
 * @param {string} opts.signerEmail
 * @param {string} opts.fileName
 * @param {Buffer} opts.pdfBuffer   - signed PDF bytes
 */
async function sendSignedDoc({ smtpConfig, from, to, senderName, signerName, signerEmail, fileName, pdfBuffer }) {
  const transporter = nodemailer.createTransport({
    ...smtpConfig,
    pool: false,
  });

  await transporter.verify();

  const signedDate = new Date().toLocaleString('he-IL', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });

  await transporter.sendMail({
    from: `"${senderName || 'PDF Studio'}" <${from}>`,
    to,
    subject: `✅ "${fileName}" נחתם על ידי ${signerName}`,
    html: buildEmailHtml({ senderName, signerName, signerEmail, fileName, signedDate }),
    attachments: [
      {
        filename: `signed-${fileName}`,
        content: pdfBuffer,
        contentType: 'application/pdf',
      },
    ],
  });
}

function buildEmailHtml({ senderName, signerName, signerEmail, fileName, signedDate }) {
  return `<!DOCTYPE html>
<html dir="rtl" lang="he">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
</head>
<body style="margin:0;padding:0;background:#f0f4ff;font-family:Arial,Helvetica,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background:#f0f4ff;padding:40px 0;">
    <tr>
      <td align="center">
        <table width="580" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(37,99,235,.12);">
          <!-- Header -->
          <tr>
            <td style="background:linear-gradient(135deg,#1a3a6b 0%,#2563eb 100%);padding:32px 40px;text-align:center;">
              <div style="font-size:36px;margin-bottom:8px;">✅</div>
              <h1 style="color:#fff;margin:0;font-size:26px;font-weight:700;">המסמך נחתם בהצלחה!</h1>
            </td>
          </tr>
          <!-- Body -->
          <tr>
            <td style="padding:36px 40px;">
              <p style="font-size:16px;color:#1e293b;margin:0 0 16px;">שלום ${escHtml(senderName || '')},</p>
              <p style="font-size:16px;color:#334155;margin:0 0 24px;line-height:1.7;">
                <strong>${escHtml(signerName)}</strong>
                ${signerEmail ? `(${escHtml(signerEmail)})` : ''}
                חתם על המסמך
                <strong>"${escHtml(fileName)}"</strong>.
              </p>
              <!-- Detail card -->
              <table width="100%" cellpadding="0" cellspacing="0" style="background:#eff6ff;border-radius:10px;border:1px solid #bfdbfe;margin-bottom:24px;">
                <tr>
                  <td style="padding:20px 24px;">
                    <table width="100%">
                      <tr>
                        <td style="font-size:13px;color:#64748b;padding:4px 0;">📄 מסמך</td>
                        <td style="font-size:14px;color:#1e293b;font-weight:600;text-align:left;">${escHtml(fileName)}</td>
                      </tr>
                      <tr>
                        <td style="font-size:13px;color:#64748b;padding:4px 0;">✍️ חתם</td>
                        <td style="font-size:14px;color:#1e293b;text-align:left;">${escHtml(signerName)}</td>
                      </tr>
                      <tr>
                        <td style="font-size:13px;color:#64748b;padding:4px 0;">📅 תאריך</td>
                        <td style="font-size:14px;color:#1e293b;text-align:left;">${escHtml(signedDate)}</td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>
              <p style="font-size:14px;color:#475569;line-height:1.7;margin:0 0 8px;">
                המסמך המקורי עם החתימה המוטמעת מצורף לאימייל זה כקובץ PDF.
              </p>
            </td>
          </tr>
          <!-- Footer -->
          <tr>
            <td style="background:#f8faff;padding:18px 40px;border-top:1px solid #e0e7ff;text-align:center;">
              <p style="font-size:12px;color:#94a3b8;margin:0;">
                נשלח אוטומטית על ידי <strong>PDF Studio</strong>
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>`;
}

function escHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * Send an invitation email to the signer asking them to sign.
 * @param {object} opts
 * @param {object} opts.smtpConfig
 * @param {string} opts.from          - sender's SMTP address
 * @param {string} opts.signerEmail   - recipient (the signer)
 * @param {string} opts.senderName
 * @param {string} opts.fileName
 * @param {string} opts.message       - optional custom message
 * @param {string} opts.signUrl       - the signing link
 */
async function sendInviteEmail({ smtpConfig, from, signerEmail, senderName, fileName, message, signUrl }) {
  const transporter = nodemailer.createTransport({ ...smtpConfig, pool: false });
  await transporter.verify();
  await transporter.sendMail({
    from: `"${senderName || 'PDF Studio'}" <${from}>`,
    to:   signerEmail,
    subject: `נדרשת חתימתך על "${fileName}"`,
    html: buildInviteHtml({ senderName, fileName, message, signUrl }),
  });
}

function buildInviteHtml({ senderName, fileName, message, signUrl }) {
  return `<!DOCTYPE html>
<html dir="rtl" lang="he">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="margin:0;padding:0;background:#f8f7f4;font-family:Arial,Helvetica,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8f7f4;padding:40px 0;">
    <tr><td align="center">
      <table width="560" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:14px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.08);">
        <tr><td style="background:#1a1814;padding:28px 36px;text-align:center;">
          <h1 style="color:#eae6dc;margin:0;font-size:22px;font-weight:700;letter-spacing:-.03em;">PDF Studio</h1>
        </td></tr>
        <tr><td style="padding:32px 36px;">
          <p style="font-size:16px;color:#1a1814;margin:0 0 14px;">שלום,</p>
          <p style="font-size:15px;color:#3a3630;margin:0 0 20px;line-height:1.7;">
            <strong>${escHtml(senderName || 'מישהו')}</strong> שלח אליך את המסמך
            <strong>"${escHtml(fileName)}"</strong> לחתימה דיגיטלית.
          </p>
          ${message ? `<p style="font-size:14px;color:#5a5650;background:#f4f2ed;border-radius:8px;padding:12px 16px;margin:0 0 24px;line-height:1.6;">${escHtml(message)}</p>` : ''}
          <div style="text-align:center;margin:28px 0;">
            <a href="${encodeURI(signUrl)}" style="display:inline-block;background:#c2784e;color:#fff;text-decoration:none;padding:14px 36px;border-radius:8px;font-size:16px;font-weight:700;letter-spacing:-.01em;">חתום על המסמך</a>
          </div>
          <p style="font-size:12px;color:#94908a;text-align:center;margin:0;">הקישור תקף ל-48 שעות</p>
        </td></tr>
        <tr><td style="background:#f4f2ed;padding:14px 36px;border-top:1px solid #e8e4dc;text-align:center;">
          <p style="font-size:11px;color:#a09a90;margin:0;">נשלח אוטומטית על ידי <strong>PDF Studio</strong></p>
        </td></tr>
      </table>
    </td></tr>
  </table>
</body></html>`;
}

module.exports = { sendSignedDoc, sendInviteEmail };
