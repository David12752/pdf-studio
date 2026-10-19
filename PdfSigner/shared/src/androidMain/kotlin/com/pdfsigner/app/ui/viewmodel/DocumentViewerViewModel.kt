package com.pdfsigner.app.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.pdfsigner.app.domain.service.MediaStoreExporter
import com.pdfsigner.app.domain.service.PdfSignatureService
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class DocumentViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val pdfService = PdfSignatureService(context)

    private val _uiState = MutableStateFlow(DocumentViewerUiState())
    val uiState: StateFlow<DocumentViewerUiState> = _uiState.asStateFlow()

    // LruCache: page index → rendered Bitmap (keeps up to 5 pages in memory)
    private val pageCache = LruCache<Int, Bitmap>(5)
    private var cachedUri: Uri? = null
    private var prefetchJob: Job? = null

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun loadDocument(uri: Uri) {
        pageCache.evictAll()
        cachedUri = null
        _uiState.update { DocumentViewerUiState(isLoading = true, uri = uri) }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: ""
                val name = resolveFileName(uri)
                val docType = classifyDocument(mime, name)
                _uiState.update { it.copy(fileName = name, mimeType = mime, docType = docType) }

                when (docType) {
                    DocType.PDF -> loadPdf(uri, password = null)
                    DocType.IMAGE -> loadImage(uri)
                    DocType.TEXT -> loadText(uri)
                    DocType.WORD, DocType.EXCEL, DocType.POWERPOINT -> loadOfficeDoc(uri, docType)
                    DocType.UNKNOWN -> loadGenericText(uri)
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load document") }
            }
        }
    }

    fun unlockWithPassword(password: String) {
        val uri = _uiState.value.uri ?: return
        _uiState.update { it.copy(isLoading = true, requiresPassword = false, passwordError = false) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                loadPdf(uri, password = password)
            }.onFailure {
                _uiState.update { it.copy(isLoading = false, requiresPassword = true, passwordError = true) }
            }
        }
    }

    fun navigateToPage(page: Int) {
        val state = _uiState.value
        if (state.docType != DocType.PDF) return
        val uri = state.uri ?: return
        if (page < 0 || page >= state.pageCount) return
        if (page == state.currentPage) return

        val cached = pageCache.get(page)
        if (cached != null) {
            val tinted = applyReadingMode(cached, state.readingMode)
            _uiState.update { it.copy(currentPage = page, pageBitmap = tinted) }
            prefetchAround(page)
            return
        }

        _uiState.update { it.copy(isLoading = true, currentPage = page) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { renderAndCache(uri, page) }
                .onSuccess { bmp ->
                    val tinted = applyReadingMode(bmp, _uiState.value.readingMode)
                    _uiState.update { it.copy(isLoading = false, pageBitmap = tinted) }
                    prefetchAround(page)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    private fun prefetchAround(page: Int) {
        prefetchJob?.cancel()
        val uri = cachedUri ?: return
        val total = _uiState.value.pageCount
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            listOf(page + 1, page - 1).filter { it in 0 until total }
                .forEach { p -> runCatching { renderAndCache(uri, p) } }
        }
    }

    // -----------------------------------------------------------------------
    // Reading mode
    // -----------------------------------------------------------------------

    private fun applyReadingMode(src: Bitmap, mode: ReadingMode): Bitmap {
        if (mode == ReadingMode.NORMAL) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            color = when (mode) {
                ReadingMode.SEPIA -> AColor.argb(60, 112, 66, 20)
                ReadingMode.WARM  -> AColor.argb(40, 255, 180, 50)
                ReadingMode.COOL  -> AColor.argb(35, 50, 120, 255)
                ReadingMode.NIGHT -> AColor.argb(140, 20, 20, 20)
                else -> return src
            }
        }
        canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
        return out
    }

    // -----------------------------------------------------------------------
    // PDF Stats
    // -----------------------------------------------------------------------

    private fun computePdfStats(uri: Uri, pageCount: Int): PdfStats {
        val fileSizeBytes = runCatching {
            context.contentResolver.query(uri,
                arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                c.moveToFirst()
                c.getLong(0)
            } ?: 0L
        }.getOrDefault(0L)

        val text = runCatching {
            openInputStream(uri).use { stream ->
                PDDocument.load(stream).use { doc -> PDFTextStripper().getText(doc) }
            }
        }.getOrDefault("")

        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val chars = text.count { !it.isWhitespace() }
        val readingTime = (words / 200.0).toInt().coerceAtLeast(if (words > 0) 1 else 0)

        return PdfStats(pageCount, words, chars, readingTime, fileSizeBytes)
    }

    // -----------------------------------------------------------------------
    // QR stamp helpers
    // -----------------------------------------------------------------------

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openInputStream(uri).use { stream ->
            val buf = ByteArray(8192)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun generateQrBitmap(text: String, sizePx: Int): Bitmap {
        val hints: Map<EncodeHintType, Any> = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) AColor.BLACK else AColor.WHITE)
            }
        }
        return bmp
    }

    private fun appendQrPage(sourceUri: Uri, hash: String, qrBmp: Bitmap, outputFile: File) {
        openInputStream(sourceUri).use { stream ->
            PDDocument.load(stream).use { doc ->
                val pageRect = PDRectangle(595f, 842f)
                val qrPage = PDPage(pageRect)
                doc.addPage(qrPage)

                val labelBmp = buildQrPageBitmap(hash, qrBmp)
                val pdImage = LosslessFactory.createFromImage(doc, labelBmp)

                PDPageContentStream(doc, qrPage).use { cs ->
                    cs.drawImage(pdImage, 0f, 0f, pageRect.width, pageRect.height)
                }
                labelBmp.recycle()

                FileOutputStream(outputFile).use { doc.save(it) }
            }
        }
    }

    private fun buildQrPageBitmap(hash: String, qrBmp: Bitmap): Bitmap {
        val w = 595 * 2; val h = 842 * 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(AColor.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 52f; color = AColor.BLACK
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f; color = AColor.DKGRAY
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f; color = AColor.BLACK
        }

        var y = 120f
        canvas.drawText("Document Integrity Verification", 80f, y, titlePaint); y += 80f
        canvas.drawText("Scan the QR code to verify this document's authenticity.", 80f, y, subtitlePaint); y += 60f
        canvas.drawLine(80f, y, (w - 80).toFloat(), y, bodyPaint.apply { strokeWidth = 3f }); y += 50f
        canvas.drawText("SHA-256 Fingerprint:", 80f, y, subtitlePaint); y += 44f
        canvas.drawText(hash.take(32), 80f, y, bodyPaint); y += 38f
        canvas.drawText(hash.drop(32), 80f, y, bodyPaint); y += 60f
        canvas.drawText("Verified with PDF Signer - private, no cloud.", 80f, y, bodyPaint.apply { color = AColor.GRAY }); y += 80f

        val qrScaled = Bitmap.createScaledBitmap(qrBmp, 320, 320, false)
        canvas.drawBitmap(qrScaled, (w - 320) / 2f, y, null)
        qrScaled.recycle()

        return out
    }

    // -----------------------------------------------------------------------
    // Office text extraction
    // -----------------------------------------------------------------------

    private fun extractOfficeText(file: File, docType: DocType): String {
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val sb = StringBuilder()
                when (docType) {
                    DocType.WORD -> {
                        zip.getEntry("word/document.xml")?.let {
                            sb.append(stripXmlTags(zip.getInputStream(it).bufferedReader().readText()))
                        }
                    }
                    DocType.EXCEL -> {
                        zip.getEntry("xl/sharedStrings.xml")?.let {
                            sb.append(stripXmlTags(zip.getInputStream(it).bufferedReader().readText()))
                        }
                    }
                    DocType.POWERPOINT -> {
                        zip.entries().asSequence()
                            .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                            .sortedBy { it.name }
                            .forEach { entry ->
                                val num = entry.name.removePrefix("ppt/slides/slide").removeSuffix(".xml")
                                sb.appendLine("-- Slide $num --")
                                sb.appendLine(stripXmlTags(zip.getInputStream(entry).bufferedReader().readText()))
                                sb.appendLine()
                            }
                    }
                    else -> {}
                }
                sb.toString().trim()
            }
        } catch (_: Exception) {
            "(Binary Office format - tap Edit to open in an external editor)"
        }
    }

    private fun stripXmlTags(xml: String) = xml
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .trim()

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private fun openInputStream(uri: Uri): java.io.InputStream =
        if (uri.scheme == "file") java.io.FileInputStream(java.io.File(uri.path!!))
        else context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open $uri")

    private fun resolveFileName(uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            c.moveToFirst(); if (i >= 0) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "document"

    private fun classifyDocument(mime: String, name: String): DocType {
        val m = mime.lowercase(); val n = name.lowercase()
        return when {
            m == "application/pdf" || n.endsWith(".pdf") -> DocType.PDF
            m.contains("word") || m.contains("msword") || m == "application/rtf" ||
                m.contains("opendocument.text") || n.endsWith(".doc") || n.endsWith(".docx") ||
                n.endsWith(".odt") || n.endsWith(".rtf") -> DocType.WORD
            m.contains("excel") || m.contains("spreadsheet") || m == "text/csv" ||
                n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".ods") || n.endsWith(".csv") -> DocType.EXCEL
            m.contains("powerpoint") || m.contains("presentation") ||
                n.endsWith(".ppt") || n.endsWith(".pptx") || n.endsWith(".odp") -> DocType.POWERPOINT
            m.startsWith("image/") || n.endsWith(".png") || n.endsWith(".jpg") ||
                n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp") -> DocType.IMAGE
            m.startsWith("text/") || m == "application/json" || n.endsWith(".txt") ||
                n.endsWith(".md") || n.endsWith(".json") || n.endsWith(".xml") ||
                n.endsWith(".html") || n.endsWith(".log") -> DocType.TEXT
            else -> DocType.UNKNOWN
        }
    }

    fun setReadingMode(mode: ReadingMode) {
        _uiState.update { it.copy(readingMode = mode) }
        val uri = cachedUri ?: return
        val pageCount = _uiState.value.pageCount
        viewModelScope.launch(Dispatchers.IO) {
            for (i in 0 until pageCount) {
                val raw = pageCache.get(i) ?: continue
                val tinted = applyReadingMode(raw, mode)
                _uiState.update { s ->
                    val updated = s.renderedPages.toMutableList()
                    if (i < updated.size) updated[i] = tinted
                    s.copy(
                        renderedPages = updated,
                        pageBitmap    = if (i == s.currentPage) tinted else s.pageBitmap
                    )
                }
            }
        }
    }

    private fun loadPdf(uri: Uri, password: String?) {
        val pageCount = try {
            openInputStream(uri).use { stream ->
                val doc = if (password != null) PDDocument.load(stream, password)
                          else PDDocument.load(stream)
                doc.use { it.numberOfPages }
            }
        } catch (e: Exception) {
            if (e.message?.contains("encrypted", ignoreCase = true) == true ||
                e.message?.contains("password",  ignoreCase = true) == true) {
                _uiState.update { it.copy(isLoading = false, requiresPassword = true, passwordError = password != null) }
                return
            }
            throw e
        }

        cachedUri = uri

        _uiState.update {
            it.copy(
                isLoading        = false,
                pageCount        = pageCount,
                currentPage      = 0,
                renderedPages    = List(pageCount) { null },
                requiresPassword = false
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            for (i in 0 until pageCount) {
                runCatching {
                    val bmp    = renderAndCache(uri, i)
                    val tinted = applyReadingMode(bmp, _uiState.value.readingMode)
                    _uiState.update { s ->
                        val updated = s.renderedPages.toMutableList()
                        updated[i] = tinted
                        s.copy(
                            renderedPages = updated,
                            pageBitmap    = if (i == 0) tinted else s.pageBitmap
                        )
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val stats = computePdfStats(uri, pageCount)
            _uiState.update { it.copy(pdfStats = stats) }
        }
    }

    private fun loadImage(uri: Uri) {
        val bitmap = openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Could not decode image")
        _uiState.update { it.copy(isLoading = false, imageBitmap = bitmap) }
    }

    private fun loadText(uri: Uri) {
        val text = openInputStream(uri).bufferedReader().use { it.readText() }
        _uiState.update { it.copy(isLoading = false, textContent = text) }
    }

    private fun loadOfficeDoc(uri: Uri, docType: DocType) {
        val ext = when (docType) {
            DocType.WORD -> ".docx"; DocType.EXCEL -> ".xlsx"
            DocType.POWERPOINT -> ".pptx"; else -> ".bin"
        }
        val cacheFile = File(context.cacheDir, "viewer_${System.currentTimeMillis()}$ext")
        openInputStream(uri).use { it.copyTo(cacheFile.outputStream()) }
        val text = runCatching { extractOfficeText(cacheFile, docType) }.getOrNull()
        _uiState.update { it.copy(isLoading = false, textContent = text, cachedFilePath = cacheFile.absolutePath) }
    }

    private fun loadGenericText(uri: Uri) {
        val text = runCatching { openInputStream(uri).bufferedReader().use { it.readText() } }.getOrNull()
        _uiState.update { it.copy(isLoading = false, textContent = text) }
    }

    // -----------------------------------------------------------------------
    // Render cache
    // -----------------------------------------------------------------------

    private fun renderAndCache(uri: Uri, page: Int): Bitmap {
        val existing = pageCache.get(page)
        if (existing != null) return existing
        val bmp = pdfService.renderPageToBitmap(uri, page, 200f)
        pageCache.put(page, bmp)
        return bmp
    }

    // -----------------------------------------------------------------------
    // QR stamp
    // -----------------------------------------------------------------------

    fun stampQrCode() {
        val uri = _uiState.value.uri ?: return
        if (_uiState.value.docType != DocType.PDF) return
        _uiState.update { it.copy(qrStampInProgress = true, qrStampDone = false) }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val hash = sha256(uri)
                val qrText = "PDF-HASH:$hash"
                val qrBmp = generateQrBitmap(qrText, 200)
                val stampedFile = File(context.cacheDir, "qrstamped_${System.currentTimeMillis()}.pdf")
                appendQrPage(uri, hash, qrBmp, stampedFile)
                val exporter = MediaStoreExporter(context)
                exporter.savePdf(stampedFile, "PDF Studio/QR", "qr_verified_${System.currentTimeMillis()}.pdf")
            }.onSuccess {
                _uiState.update { it.copy(qrStampInProgress = false, qrStampDone = true) }
            }.onFailure { e ->
                _uiState.update { it.copy(qrStampInProgress = false, error = "QR stamp failed: ${e.message}") }
            }
        }
    }

    fun dismissQrDone() { _uiState.update { it.copy(qrStampDone = false) } }
    fun clearError()    { _uiState.update { it.copy(error = null) } }

    fun clear() {
        pageCache.evictAll()
        cachedUri = null
    }
}
