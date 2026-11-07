package ai.abbas.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptSettingsTest {

    @Test
    fun `empty settings produce an empty prompt and are not configured`() {
        val settings = SystemPromptSettings()

        assertEquals("", settings.buildPrompt())
        assertFalse(settings.isConfigured)
    }

    @Test
    fun `persona alone is included without section headers`() {
        val settings = SystemPromptSettings(personaId = "coach")

        val prompt = settings.buildPrompt()

        assertTrue(prompt.startsWith("You are a supportive life coach."))
        assertFalse(prompt.contains("Style and form of speech:"))
        assertFalse(prompt.contains("Always follow these rules:"))
        assertTrue(settings.isConfigured)
    }

    @Test
    fun `styles render as a labelled bullet list in catalogue order`() {
        val settings = SystemPromptSettings(styleIds = setOf("plain", "concise"))

        val prompt = settings.buildPrompt()

        assertTrue(prompt.contains("Style and form of speech:"))
        // "concise" is declared before "plain" in the catalogue, so it must come first.
        assertTrue(prompt.indexOf("Be concise.") < prompt.indexOf("plain language"))
        assertTrue(prompt.contains("- Be concise."))
    }

    @Test
    fun `options and custom instructions both appear`() {
        val settings = SystemPromptSettings(
            customInstructions = "Quiz me at the end.",
            optionIds = setOf("no_emoji")
        )

        val prompt = settings.buildPrompt()

        assertTrue(prompt.contains("Always follow these rules:"))
        assertTrue(prompt.contains("- Never use emoji."))
        assertTrue(prompt.contains("Additional instructions from the user: Quiz me at the end."))
    }

    @Test
    fun `unknown ids are ignored rather than rendered`() {
        val settings = SystemPromptSettings(
            personaId = "does_not_exist",
            styleIds = setOf("nope"),
            optionIds = setOf("also_nope")
        )

        assertEquals("", settings.buildPrompt())
        assertFalse(settings.isConfigured)
    }

    @Test
    fun `catalogue ids are unique across every section`() {
        val allIds = PromptSuggestions.personas.map { it.id } +
            PromptSuggestions.speechStyles.map { it.id } +
            PromptSuggestions.extraOptions.map { it.id }

        assertEquals(allIds.size, allIds.toSet().size)
    }
}