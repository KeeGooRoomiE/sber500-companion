package ru.keegoo.companion.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.keegoo.companion.push.diagnosticsText

class DiagnosticsTextTest {
    @Test fun `one fact per line`() {
        assertEquals("Версия: 1.1.0\nПуши RuStore: доступны", diagnosticsText(listOf("Версия" to "1.1.0", "Пуши RuStore" to "доступны")))
    }
}
