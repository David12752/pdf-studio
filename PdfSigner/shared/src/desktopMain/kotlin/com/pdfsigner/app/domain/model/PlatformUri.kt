@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.model

import java.io.File

actual abstract class PlatformUri {
    actual abstract override fun toString(): String
    /** The underlying [File] this URI wraps (desktop-only). */
    abstract val file: File
}

/** Factory function — creates a concrete [PlatformUri] from a [File]. */
fun PlatformUri(f: File): PlatformUri = object : PlatformUri() {
    override val file: File = f
    override fun toString(): String = f.absolutePath
}

actual fun PlatformUri.asString(): String = file.absolutePath
