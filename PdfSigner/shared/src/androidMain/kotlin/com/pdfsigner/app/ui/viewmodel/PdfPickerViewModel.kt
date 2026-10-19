package com.pdfsigner.app.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfsigner.app.domain.service.PdfSignatureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class PdfPickerViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val pdfService = PdfSignatureService(context)

    private val _uiState = MutableStateFlow(PdfPickerUiState())
    val uiState: StateFlow<PdfPickerUiState> = _uiState.asStateFlow()

    /** Filtered list derived from uiState — exposed as a StateFlow for the screen. */
    val filteredFiles: StateFlow<List<DevicePdfFile>> = _uiState
        .map { state ->
            val q = state.query
            if (q.isBlank()) state.files
            else state.files.filter { it.name.contains(q, ignoreCase = true) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        loadFiles()
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query.trim()) }
        loadFiles()
    }

    // -----------------------------------------------------------------------
    // MediaStore scan
    // -----------------------------------------------------------------------

    fun loadFiles() {
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val query = _uiState.value.query
                val projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATA
                )
                val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?" +
                    if (query.isNotBlank()) " AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" else ""
                val selectionArgs = if (query.isNotBlank())
                    arrayOf("application/pdf", "%$query%")
                else
                    arrayOf("application/pdf")

                val files = mutableListOf<DevicePdfFile>()

                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    val sizeCol     = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val dataCol     = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)

                    while (cursor.moveToNext()) {
                        val id       = cursor.getLong(idCol)
                        val name     = cursor.getString(nameCol) ?: continue
                        val dateMs   = cursor.getLong(dateCol) * 1000L
                        val size     = cursor.getLong(sizeCol)
                        val data     = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""

                        val uri: Uri = Uri.withAppendedPath(
                            MediaStore.Files.getContentUri("external"),
                            id.toString()
                        )

                        // Quick thumbnail: render page 0 at low DPI
                        val thumbnail: Bitmap? = runCatching {
                            pdfService.renderPageToBitmap(uri, 0, 72f)
                        }.getOrNull()

                        files.add(
                            DevicePdfFile(
                                uri             = uri,
                                name            = name,
                                sizeBytes       = size,
                                dateModifiedSec = dateMs / 1000L,
                                thumbnail       = thumbnail
                            )
                        )
                    }
                }

                files.toList()
            }.onSuccess { files ->
                _uiState.update { it.copy(isLoading = false, files = files) }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun clearError() { }
}
