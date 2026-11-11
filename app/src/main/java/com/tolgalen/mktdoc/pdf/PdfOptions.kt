package com.tolgalen.mktdoc.pdf

import android.net.Uri

enum class PdfLayout { SECTION, SINGLE }
enum class ImageQuality { BALANCED, SMALL, ORIGINAL }
enum class TextSize(val zoom: Double) { XSMALL(0.707), SMALL(0.85), NORMAL(1.0) }
enum class PageBreaks { CONTINUOUS, SECTION, SUBSECTION, PAGE }

/** The same options as the desktop version. */
data class PdfOptions(
    val layout: PdfLayout = PdfLayout.SINGLE,
    val coverTitle: String = "",
    val coverImage: Uri? = null,
    val imageQuality: ImageQuality = ImageQuality.BALANCED,
    val textSize: TextSize = TextSize.NORMAL,
    val breaks: PageBreaks = PageBreaks.CONTINUOUS,
    val lang: String = "es",
)
