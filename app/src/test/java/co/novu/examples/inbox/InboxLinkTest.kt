package co.novu.examples.inbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxLinkTest {
    @Test
    fun inboxLinkMatchesThePushDeeplink() {
        assertTrue(isInboxLink(INBOX_LINK))
        assertFalse(isInboxLink("https://novu.co"))
        assertFalse(isInboxLink(null))
    }
}
