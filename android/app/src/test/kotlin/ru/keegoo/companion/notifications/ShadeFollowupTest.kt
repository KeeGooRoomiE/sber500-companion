package ru.keegoo.companion.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.keegoo.companion.data.api.model.FollowupQuestionDto

class ShadeFollowupTest {
    @Test fun `pairs ids with labels`() {
        assertEquals(
            listOf(FollowupQuestionDto("why", "Почему?"), FollowupQuestionDto("today", "Что сегодня?")),
            restChips(arrayOf("why", "today"), arrayOf("Почему?", "Что сегодня?")),
        )
    }

    @Test fun `null and mismatched arrays give what can be paired`() {
        assertEquals(emptyList<FollowupQuestionDto>(), restChips(null, null))
        assertEquals(listOf(FollowupQuestionDto("why", "Почему?")), restChips(arrayOf("why", "today"), arrayOf("Почему?")))
        assertEquals(emptyList<FollowupQuestionDto>(), restChips(arrayOf(""), arrayOf("Почему?")))
    }
}
