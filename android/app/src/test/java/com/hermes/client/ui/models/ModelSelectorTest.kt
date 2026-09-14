package com.hermes.client.ui.models

import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.favKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectorTest {
    private val providers = listOf(
        ModelProviderDto(slug = "openai-codex", name = null, isCurrent = true,
            models = listOf("gpt-5.5", "gpt-5.5-mini")),
        ModelProviderDto(slug = "OpenRouter", name = "OpenRouter", isCurrent = false,
            models = listOf("stepfun/step-3.7-flash:free")),
    )

    private fun rows(groups: List<ModelGroup>) = groups.flatMap { it.rows }
    private fun titles(groups: List<ModelGroup>) = groups.map { it.slug ?: "*favorites*" }

    @Test fun one_card_per_provider_in_input_order() {
        val groups = modelSelectorGroups(providers, emptySet(), null, null)
        assertEquals(listOf("openai-codex", "OpenRouter"), titles(groups))
        assertEquals(3, rows(groups).size)
    }

    @Test fun a_provider_card_carries_its_display_name_and_total() {
        val groups = modelSelectorGroups(providers, emptySet(), null, null)
        val or = groups.single { it.slug == "OpenRouter" }
        assertEquals("OpenRouter", or.title)
        assertEquals(1, or.count)
        // A provider with no display name falls back to its slug.
        assertEquals("openai-codex", groups.single { it.slug == "openai-codex" }.title)
    }

    @Test fun favorites_pinned_first_and_also_shown_in_group() {
        val favs = setOf(favKey("openai-codex", "gpt-5.5"))
        val groups = modelSelectorGroups(providers, favs, null, null)
        assertTrue(groups.first().isFavorites)
        assertEquals(null, groups.first().slug)
        // appears in the Favorites card AND its provider card, both flagged
        assertEquals(2, rows(groups).count { it.model == "gpt-5.5" && it.isFavorite })
    }

    @Test fun no_favorites_card_when_none_present() {
        assertTrue(modelSelectorGroups(providers, emptySet(), null, null).none { it.isFavorites })
    }

    @Test fun a_provider_with_no_models_gets_no_card() {
        val empty = providers + ModelProviderDto(slug = "silent", name = null, isCurrent = false, models = emptyList())
        assertTrue(modelSelectorGroups(empty, emptySet(), null, null).none { it.slug == "silent" })
    }

    @Test fun marks_exactly_the_current_row() {
        val groups = modelSelectorGroups(providers, emptySet(), "openai-codex", "gpt-5.5")
        val current = rows(groups).filter { it.isCurrent }
        assertEquals(1, current.size)
        assertEquals("gpt-5.5", current[0].model)
        assertEquals("openai-codex", current[0].provider)
    }

    @Test fun provider_card_marks_the_current_provider() {
        val groups = modelSelectorGroups(providers, emptySet(), null, null)
        assertTrue(groups.single { it.slug == "openai-codex" }.isCurrent)
        assertTrue(!groups.single { it.slug == "OpenRouter" }.isCurrent)
    }

    // ---- collapsible groups ----

    @Test fun null_expanded_set_keeps_every_group_expanded() {
        val groups = modelSelectorGroups(providers, emptySet(), null, null, expandedGroups = null)
        assertEquals(3, rows(groups).size)
        assertTrue(groups.all { it.expanded })
    }

    @Test fun collapsed_group_keeps_its_card_and_total_but_drops_its_rows() {
        val groups = modelSelectorGroups(providers, emptySet(), null, null, expandedGroups = setOf("OpenRouter"))
        // openai-codex is collapsed: card present, its 2 rows gone; OpenRouter's row remains.
        assertEquals(listOf("stepfun/step-3.7-flash:free"), rows(groups).map { it.model })
        val codex = groups.single { it.slug == "openai-codex" }
        assertTrue(!codex.expanded)
        assertEquals(2, codex.count)
        assertTrue(codex.rows.isEmpty())
        assertTrue(groups.single { it.slug == "OpenRouter" }.expanded)
    }

    @Test fun favorites_stay_pinned_even_when_their_group_is_collapsed() {
        val favs = setOf(favKey("openai-codex", "gpt-5.5"))
        val groups = modelSelectorGroups(providers, favs, null, null, expandedGroups = emptySet())
        // All groups collapsed: only the pinned favorites card still carries rows.
        assertEquals(listOf("gpt-5.5"), rows(groups).map { it.model })
        assertTrue(groups.first().isFavorites)
        assertTrue(groups.first().expanded)
    }

    // ---- provider resolution for the current model ----

    @Test fun resolveModelProvider_passes_known_provider_through() {
        assertEquals("x", resolveModelProvider(providers, "x", "gpt-5.5"))
    }

    @Test fun resolveModelProvider_finds_unique_owner() {
        assertEquals("OpenRouter", resolveModelProvider(providers, null, "stepfun/step-3.7-flash:free"))
    }

    @Test fun resolveModelProvider_prefers_current_provider_on_ambiguity() {
        val ambiguous = listOf(
            ModelProviderDto(slug = "a", name = null, isCurrent = false, models = listOf("shared")),
            ModelProviderDto(slug = "b", name = null, isCurrent = true, models = listOf("shared")),
        )
        assertEquals("b", resolveModelProvider(ambiguous, null, "shared"))
    }

    @Test fun resolveModelProvider_returns_null_for_unknown_model() {
        assertEquals(null, resolveModelProvider(providers, null, "nope"))
    }

    // ---- per-model reasoning presets ----

    @Test fun rows_carry_their_remembered_reasoning_preset() {
        val presets = mapOf(favKey("openai-codex", "gpt-5.5") to "high")
        val rows = rows(modelSelectorGroups(providers, emptySet(), null, null, presets = presets))
        assertEquals("high", rows.first { it.model == "gpt-5.5" }.presetEffort)
        assertEquals(null, rows.first { it.model == "gpt-5.5-mini" }.presetEffort)
    }

    @Test fun reasoning_labels_cover_all_levels_and_off() {
        (REASONING_LEVELS + REASONING_OFF).forEach { level ->
            val label = reasoningLabel(level)
            assertTrue("$level must have a label", label != null)
            assertTrue("$level zh label must be Chinese", label!!.zh.any { it.code > 0x4E00 })
            assertTrue("$level labels must differ per language", label.zh != label.en)
        }
        assertEquals("unknown/blank values show no label", null, reasoningLabel(""))
        assertEquals(null, reasoningLabel(null))
    }
}
