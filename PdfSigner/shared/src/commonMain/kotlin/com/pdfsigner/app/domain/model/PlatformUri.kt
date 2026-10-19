@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.model

expect abstract class PlatformUri {
    abstract override fun toString(): String
}

expect fun PlatformUri.asString(): String
