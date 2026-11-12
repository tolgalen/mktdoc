package com.tolgalen.mktdoc

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.tolgalen.mktdoc.work.ContentCheckWorker
import com.tolgalen.mktdoc.work.VersionCheckWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the background workers once on the device (they use the network, like they would when scheduled). */
class WorkersTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun dailyVersionCheckFinishes() = runBlocking {
        val r = TestListenableWorkerBuilder<VersionCheckWorker>(ctx).build().doWork()
        // success when online; retry only if the site could not be reached
        assertTrue(r is ListenableWorker.Result.Success || r is ListenableWorker.Result.Retry)
    }

    @Test fun weeklyContentCheckFinishes() = runBlocking {
        val r = TestListenableWorkerBuilder<ContentCheckWorker>(ctx).build().doWork()
        assertTrue(r is ListenableWorker.Result.Success || r is ListenableWorker.Result.Retry)
    }
}
