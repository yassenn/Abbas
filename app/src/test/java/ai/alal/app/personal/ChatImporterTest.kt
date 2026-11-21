package ai.alal.app.personal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ChatImporterTest {

    @Test
    fun parsesAndroidWhatsAppExportWithMultilineMessages() {
        val text = """
            31/12/2023, 21:41 - Messages and calls are end-to-end encrypted.
            31/12/2023, 21:42 - Ana: Are we still on for tomorrow?
            Bring the charger
            31/12/2023, 21:45 - Issa: <Media omitted>
            01/01/2024, 09:05 - Issa: Yes, 10am
        """.trimIndent()
        val out = mutableListOf<ChatImporter.ChatLine>()
        ChatImporter.parseWhatsApp("Ana", text, out)
        assertEquals(2, out.size)
        assertEquals("Ana", out[0].sender)
        assertEquals("Are we still on for tomorrow? Bring the charger", out[0].text)
        val c = Calendar.getInstance().apply { timeInMillis = out[1].time }
        assertEquals(1, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.JANUARY, c.get(Calendar.MONTH))
    }

    @Test
    fun parsesIosWhatsAppExportWith12HourClock() {
        val text = "[12/31/23, 9:41:05 PM] Ana: Happy new year!"
        val out = mutableListOf<ChatImporter.ChatLine>()
        ChatImporter.parseWhatsApp("Ana", text, out)
        val c = Calendar.getInstance().apply { timeInMillis = out.single().time }
        assertEquals(21, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(Calendar.DECEMBER, c.get(Calendar.MONTH))
    }
}
