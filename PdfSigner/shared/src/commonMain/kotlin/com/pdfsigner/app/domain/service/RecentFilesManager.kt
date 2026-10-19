@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.PlatformUri
import com.pdfsigner.app.domain.model.RecentFile

expect class RecentFilesManager(context: PlatformContext) {
    fun getRecents(): List<RecentFile>
    fun addRecent(uri: PlatformUri, fileName: String, pageCount: Int)
    fun removeRecent(uriString: String)
}
