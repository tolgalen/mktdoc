package com.tolgalen.mktdoc

import android.app.Application
import com.tolgalen.mktdoc.data.Library
import com.tolgalen.mktdoc.data.Prefs
import com.tolgalen.mktdoc.net.Http
import com.tolgalen.mktdoc.work.Jobs
import com.tolgalen.mktdoc.work.ContentCheckWorker
import com.tolgalen.mktdoc.work.VersionCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MktdocApp : Application() {
    lateinit var prefs: Prefs
    lateinit var library: Library

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        library = Library(this)
        Jobs.ensureChannels(this)
        Http.maxRps = prefs.maxRps.value.toDouble()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { prefs.maxRps.collect { Http.maxRps = it.toDouble() } }
        scope.launch { prefs.backgroundContentCheck.collect { ContentCheckWorker.schedule(this@MktdocApp, it) } }
        scope.launch { prefs.periodicCheck.collect { VersionCheckWorker.schedule(this@MktdocApp, it) } }
    }
}
