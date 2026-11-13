package com.tolgalen.mktdoc.data

import androidx.annotation.StringRes
import com.tolgalen.mktdoc.R

enum class LicenseGroup { LIBRARY, SERVICE }

/**
 * A third-party component the app includes or uses. [text] is the asset with the full license text,
 * when the license requires it to accompany the software.
 */
data class LicenseEntry(
    val group: LicenseGroup,
    val name: String,
    val license: String,
    val copyright: String,
    val url: String,
    @StringRes val what: Int,
    val text: String? = null,
)

object Licenses {
    const val APACHE = "licenses/apache-2.0.txt"
    const val MIT = "licenses/mit.txt"
    const val MKTDOC = "licenses/mktdoc-mit.txt"

    val all = listOf(
        LicenseEntry(LicenseGroup.LIBRARY, "Jetpack Compose · Material 3 · iconos", "Apache License 2.0", "© The Android Open Source Project",
            "https://developer.android.com/jetpack/compose", R.string.lic_compose, APACHE),
        LicenseEntry(LicenseGroup.LIBRARY, "AndroidX (core, activity, lifecycle, appcompat, WorkManager)", "Apache License 2.0", "© The Android Open Source Project",
            "https://developer.android.com/jetpack/androidx", R.string.lic_androidx, APACHE),
        LicenseEntry(LicenseGroup.LIBRARY, "Kotlin · kotlinx.coroutines", "Apache License 2.0", "© JetBrains s.r.o. and Kotlin Programming Language contributors",
            "https://kotlinlang.org", R.string.lic_kotlin, APACHE),
        LicenseEntry(LicenseGroup.LIBRARY, "OkHttp 5.5.1 · Okio", "Apache License 2.0", "© Square, Inc.",
            "https://square.github.io/okhttp/", R.string.lic_okhttp, APACHE),
        LicenseEntry(LicenseGroup.LIBRARY, "jsoup 1.21.2", "MIT License", "© 2009-2025 Jonathan Hedley",
            "https://jsoup.org", R.string.lic_jsoup, MIT),
        LicenseEntry(LicenseGroup.LIBRARY, "PDFBox-Android 2.0.27.0", "Apache License 2.0", "© The Apache Software Foundation · © Tom Roush (port a Android)",
            "https://github.com/TomRoush/PdfBox-Android", R.string.lic_pdfbox, APACHE),
        LicenseEntry(LicenseGroup.LIBRARY, "ML Kit Translation 17.0.3", "Android SDK Terms of Service · ML Kit Terms", "© Google LLC",
            "https://developers.google.com/ml-kit/language/translation", R.string.lic_mlkit),
        LicenseEntry(LicenseGroup.SERVICE, "manual.mikrotik.com", "Contenido de MikroTik", "© MikroTik (SIA Mikrotikls)",
            "https://manual.mikrotik.com", R.string.lic_mikrotik),
    )
}
