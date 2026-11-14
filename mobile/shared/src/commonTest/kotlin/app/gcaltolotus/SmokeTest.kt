package app.gcaltolotus

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun sharedVersionIsSet() {
        assertEquals("0.1.0", SHARED_VERSION)
    }
}
