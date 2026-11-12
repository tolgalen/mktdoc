package com.tolgalen.mktdoc.data

/** Language of the progress messages (the job log). Set from the app language before each operation. */
object Msg {
    @Volatile var spanish = false

    fun t(en: String, es: String) = if (spanish) es else en
}
