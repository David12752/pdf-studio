@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.model

actual abstract class PlatformContext {
    companion object {
        /** Singleton desktop context (no-op placeholder). */
        val INSTANCE: PlatformContext = object : PlatformContext() {}
        operator fun invoke(): PlatformContext = INSTANCE
    }
}