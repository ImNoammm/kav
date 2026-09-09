package uk.noammm.kav

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class TimetableRegressionTest {
    @Test
    fun testConcurrentConsumersShareOneTimetable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Loaded.clear()
        val a = async { loadNet(context) }
        val b = async { loadNet(context) }
        val first = a.await()
        assertSame(first, b.await())
        assertSame(first, Loaded.net)
        assertTrue(first.nStops > 0)
        assertTrue(first.nRoutes > 0)
    }
}
