package app.gcaltolotus.background

import app.gcaltolotus.ui.OpenRequest
import app.gcaltolotus.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRoutesTest {
    @Test
    fun questionsOpenSyncWithPreview() {
        assertEquals(OpenRequest(Screen.SYNC, preview = true), openRequestFor(SyncNotifications.DESTINATION_QUESTIONS))
    }

    @Test
    fun lotusAndGoogleOpenConnections() {
        assertEquals(OpenRequest(Screen.CONNECTIONS), openRequestFor(SyncNotifications.DESTINATION_DOMINO))
        assertEquals(OpenRequest(Screen.CONNECTIONS), openRequestFor(SyncNotifications.DESTINATION_GOOGLE))
    }

    @Test
    fun summaryOpensStatus() {
        assertEquals(OpenRequest(Screen.STATUS), openRequestFor(SyncNotifications.DESTINATION_STATUS))
    }

    @Test
    fun unknownOrMissingDestinationOpensNothing() {
        assertNull(openRequestFor(null))
        assertNull(openRequestFor("calendar"))
    }
}
