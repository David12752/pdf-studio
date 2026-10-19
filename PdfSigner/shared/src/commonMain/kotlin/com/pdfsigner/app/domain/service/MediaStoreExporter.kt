@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import com.pdfsigner.app.domain.model.ExportFormat
import com.pdfsigner.app.domain.model.PlatformBitmap
import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.PlatformUri

expect class MediaStoreExporter(context: PlatformContext) {
    fun savePdf(sourceUri: PlatformUri, folderName: String, fileName: String): PlatformUri
    fun saveBitmap(bitmap: PlatformBitmap, fileName: String, format: ExportFormat): PlatformUri
}