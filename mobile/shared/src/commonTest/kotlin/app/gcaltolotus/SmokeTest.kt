package app.gcaltolotus

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun sharedVersionIsSet() {
        assertEquals("0.1.2", SHARED_VERSION)
    }
}
