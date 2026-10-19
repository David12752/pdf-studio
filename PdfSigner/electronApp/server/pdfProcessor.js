'use strict';
/**
 * pdfProcessor.js
 * Detects signature fields in a PDF and embeds signature images.
 * Uses pdf-lib (already a project dependency).
 */

const { PDFDocument, rgb, StandardFonts, PDFName } = require('pdf-lib');

/** Keywords (regex) that indicate a signature field location in text content */
const SIG_PATTERNS = [
  /חתימה/i,
  /חתו[מן]/i,
  /לחתימה/i,
  /sign\s*here/i,
  /signature/i,
  /client\s*sign/i,
  /authorized\s*sign/i,
  /customer\s*sign/i,
  /approved\s*by/i,
  /signed\s*by/i,
  /undersigned/i,
];

/**
 * Detect signature fields in pdfBuffer.
 * Priority: AcroForm Signature fields → keyword text search → default placement.
 *
 * @param {Buffer} pdfBuffer
 * @returns {Promise<Array<{label:string, page:number, x:number, y:number, width:number, height:number, source:string}>>}
 */
async function detectSignatureFields(pdfBuffer) {
  const pdfDoc = await PDFDocument.load(pdfBuffer, { ignoreEncryption: true });
  const pages = pdfDoc.getPages();
  const fields = [];

  /* ── 1. AcroForm signature widgets ───────────────────────────────────── */
  try {
    const form = pdfDoc.getForm();
    const allFields = form.getFields();
    for (const field of allFields) {
      // PDFSignature has fieldType of /Sig
      const acro = field.acroField;
      const ft = acro.dict.get(PDFName.of('FT'));
      if (!ft || ft.encodedName !== '/Sig') continue;

      const widgets = acro.Widgets();
      for (const widget of widgets) {
        const rectArr = widget.Rect();
        if (!rectArr) continue;
        const [x1, y1, x2, y2] = rectArr.asArray().map((n) => n.asNumber());

        // Find which page this widget belongs to
        let pageIdx = 0;
        for (let i = 0; i < pages.length; i++) {
          if (pages[i].node.Annots()) {
            const annots = pages[i].node.Annots().asArray();
            if (annots.some((a) => a === widget || a.resolve() === widget)) {
              pageIdx = i;
              break;
            }
          }
        }

        const pw = Math.abs(x2 - x1) || 200;
        const ph = Math.abs(y2 - y1) || 60;
        fields.push({
          label: field.getName() || 'חתימה',
          page: pageIdx,
          x: Math.min(x1, x2),
          y: Math.min(y1, y2),
          width: pw,
          height: ph,
          source: 'acroform',
        });
      }
    }
  } catch (_) {
    // AcroForm not available – continue
  }

  if (fields.length > 0) return fields;

  /* ── 2. Text keyword search (heuristic) ──────────────────────────────── */
  // We can't easily iterate text positions with pdf-lib alone;
  // use a simple scan of the raw content stream for keyword byte patterns.
  try {
    for (let pi = 0; pi < pages.length; pi++) {
      const page = pages[pi];
      const { width, height } = page.getSize();
      const contentStream = page.node.Contents();
      if (!contentStream) continue;

      let rawText = '';
      try {
        const bytes = contentStream.getContents
          ? contentStream.getContents()
          : pdfBuffer; // fallback
        rawText = Buffer.from(bytes).toString('latin1');
      } catch (_) {
        continue;
      }

      const matched = SIG_PATTERNS.some((re) => re.test(rawText));
      if (matched) {
        // Place a signature box near the bottom of the page (common location)
        fields.push({
          label: 'חתימת לקוח',
          page: pi,
          x: width * 0.55,
          y: 80,
          width: width * 0.38,
          height: 65,
          source: 'keyword',
        });
        break; // one field per match is enough
      }
    }
  } catch (_) {
    // silently continue
  }

  if (fields.length > 0) return fields;

  /* ── 3. Default: bottom-right of the last page ────────────────────────── */
  const lastPage = pages[pages.length - 1];
  const { width, height } = lastPage.getSize();
  fields.push({
    label: 'חתימת לקוח',
    page: pages.length - 1,
    x: width * 0.53,
    y: 60,
    width: width * 0.40,
    height: 70,
    source: 'auto',
  });

  return fields;
}

/**
 * Embed signature PNG images into the PDF at field positions.
 *
 * @param {Buffer} pdfBuffer  Original PDF bytes
 * @param {Array}  fields     Field list from detectSignatureFields()
 * @param {Array}  signatures [{fieldIndex: number, imageBase64: string}]
 * @param {{signerName:string, signerEmail:string}} meta
 * @returns {Promise<Uint8Array>}
 */
async function embedSignatures(pdfBuffer, fields, signatures, meta) {
  const pdfDoc = await PDFDocument.load(pdfBuffer, { ignoreEncryption: true });
  const pages = pdfDoc.getPages();
  const font = await pdfDoc.embedFont(StandardFonts.Helvetica);

  const dateStr = new Date().toLocaleDateString('he-IL', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  });

  for (const sig of signatures) {
    const field = fields[sig.fieldIndex];
    if (!field) continue;

    const page = pages[field.page] || pages[0];
    const { height: pageHeight } = page.getSize();

    // pdf-lib uses bottom-left origin; the fields we store are already in PDF coords
    // (bottom-left) when source=acroform, but for auto/keyword we store them the same way.
    const x = field.x;
    const y = field.y;

    // Strip data-URL prefix if present
    const base64Clean = sig.imageBase64.replace(/^data:image\/png;base64,/, '');
    const imgBytes = Buffer.from(base64Clean, 'base64');
    const pngImage = await pdfDoc.embedPng(imgBytes);

    // White background rectangle to cover any placeholder text
    page.drawRectangle({
      x,
      y,
      width: field.width,
      height: field.height,
      color: rgb(1, 1, 1),
      opacity: 0.9,
    });

    // Signature image
    page.drawImage(pngImage, {
      x,
      y,
      width: field.width,
      height: field.height,
    });

    // Thin border around signature
    page.drawRectangle({
      x,
      y,
      width: field.width,
      height: field.height,
      borderColor: rgb(0.2, 0.4, 0.8),
      borderWidth: 0.8,
      opacity: 0,
    });

    // Signer metadata line below the box
    const metaText = `${meta.signerName}  |  ${dateStr}`;
    const fontSize = 7;
    page.drawText(metaText, {
      x,
      y: Math.max(0, y - 10),
      size: fontSize,
      font,
      color: rgb(0.45, 0.45, 0.55),
    });
  }

  return await pdfDoc.save();
}

module.exports = { detectSignatureFields, embedSignatures };
