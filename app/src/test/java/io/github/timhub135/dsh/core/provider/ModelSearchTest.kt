package io.github.timhub135.dsh.core.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #58: model lists get a search field once long; it matches id, display name or provider. */
class ModelSearchTest {
    @Test fun searchFieldOnlyForLongLists() {
        assertFalse(ModelSearch.shown(2))
        assertFalse(ModelSearch.shown(ModelSearch.THRESHOLD))
        assertTrue(ModelSearch.shown(ModelSearch.THRESHOLD + 1))
    }

    @Test fun blankQueryMatchesEverything() {
        assertTrue(ModelSearch.matches("", "gpt-4o"))
        assertTrue(ModelSearch.matches("   ", "gpt-4o", null))
    }

    @Test fun matchesAnyFieldIgnoringCase() {
        assertTrue(ModelSearch.matches("GPT", "openai/gpt-4o", "OpenRouter"))
        assertTrue(ModelSearch.matches("router", "openai/gpt-4o", "OpenRouter"))
        assertTrue(ModelSearch.matches("flash", "deepseek-flash", "DeepSeek-V4-Flash", "DeepSeek"))
        assertFalse(ModelSearch.matches("claude", "openai/gpt-4o", "OpenRouter"))
    }

    @Test fun everyWordMustMatchSomeField() {
        assertTrue(ModelSearch.matches("openrouter gpt", "openai/gpt-4o", "OpenRouter"))
        assertFalse(ModelSearch.matches("openrouter claude", "openai/gpt-4o", "OpenRouter"))
    }
}
