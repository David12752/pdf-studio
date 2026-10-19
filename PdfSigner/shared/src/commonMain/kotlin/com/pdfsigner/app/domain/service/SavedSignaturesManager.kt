@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import com.pdfsigner.app.domain.model.PlatformBitmap
import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.SavedSignature

expect class SavedSignaturesManager(context: PlatformContext) {
    fun getAll(): List<SavedSignature>
    fun save(bitmap: PlatformBitmap): String
    fun delete(id: String)
}
