package ru.keegoo.companion.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseCopyTest {
    @Test fun `title and body split on the first bar`() {
        assertEquals(NotificationCopy("Вечер", "Как сегодня? | одно касание"), parseCopy(" Вечер | Как сегодня? | одно касание "))
    }

    @Test fun `no bar or an empty half is rejected`() {
        assertNull(parseCopy("Просто текст"))
        assertNull(parseCopy(" | текст"))
        assertNull(parseCopy("Заголовок | "))
    }
}
