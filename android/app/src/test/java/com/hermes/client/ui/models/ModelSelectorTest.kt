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

    private fun rows(items: List<ModelListItem>) =
        items.filterIsInstance<ModelListItem.Row>().map { it.row }
    private fun headers(items: List<ModelListItem>) =
        items.filterIsInstance<ModelListItem.Header>().map { it.title }

    @Test fun groups_by_provider_with_headers_in_input_order() {
        val items = modelSelectorRows(providers, emptySet(), "", null, null)
        assertEquals(listOf("openai-codex", "OpenRouter"), headers(items))
        assertEquals(3, rows(items).size)
    }

    @Test fun favorites_pinned_first_and_also_shown_in_group() {
        val favs = setOf(favKey("openai-codex", "gpt-5.5"))
        val items = modelSelectorRows(providers, favs, "", null, null)
        assertEquals("Favorites", (items.first() as ModelListItem.Header).title)
        // appears in the Favorites section AND its provider group, both flagged
        assertEquals(2, rows(items).count { it.model == "gpt-5.5" && it.isFavorite })
    }

    @Test fun no_favorites_header_when_none_present() {
        val items = modelSelectorRows(providers, emptySet(), "", null, null)
        assertTrue(headers(items).none { it == "Favorites" })
    }

    @Test fun query_filters_model_and_provider_case_insensitively() {
        assertEquals(
            listOf("stepfun/step-3.7-flash:free"),
            rows(modelSelectorRows(providers, emptySet(), "STEP", null, null)).map { it.model },
        )
        assertEquals(2, rows(modelSelectorRows(providers, emptySet(), "codex", null, null)).size)
        assertTrue(modelSelectorRows(providers, emptySet(), "zzz", null, null).isEmpty())
    }

    @Test fun marks_exactly_the_current_row() {
        val items = modelSelectorRows(providers, emptySet(), "", "openai-codex", "gpt-5.5")
        val current = rows(items).filter { it.isCurrent }
        assertEquals(1, current.size)
        assertEquals("gpt-5.5", current[0].model)
        assertEquals("openai-codex", current[0].provider)
    }

    @Test fun provider_header_marks_the_current_provider() {
        val items = modelSelectorRows(providers, emptySet(), "", null, null)
        val codex = items.filterIsInstance<ModelListItem.Header>().first { it.title == "openai-codex" }
        assertTrue(codex.isCurrent)
    }

    @Test fun non_current_provider_header_is_not_current() {
        val items = modelSelectorRows(providers, emptySet(), "", null, null)
        val openRouter = items.filterIsInstance<ModelListItem.Header>().first { it.title == "OpenRouter" }
        assertTrue(!openRouter.isCurrent)
    }

    @Test fun favorite_filtered_out_by_query_produces_no_favorites_header() {
        val favs = setOf(favKey("openai-codex", "gpt-5.5"))
        // "step" matches only the OpenRouter model, not the favorited gpt-5.5 — so the favorite
        // is filtered out and no "Favorites" header should appear.
        val items = modelSelectorRows(providers, favs, "step", null, null)
        assertTrue(headers(items).none { it == "Favorites" })
    }

    // ---- collapsible groups ----

    @Test fun null_expanded_set_keeps_every_group_expanded() {
        val items = modelSelectorRows(providers, emptySet(), "", null, null, expandedGroups = null)
        assertEquals(3, rows(items).size)
        assertTrue(items.filterIsInstance<ModelListItem.Header>().all { it.expanded })
    }

    @Test fun collapsed_group_contributes_header_only_with_total_count() {
        val items = modelSelectorRows(providers, emptySet(), "", null, null, expandedGroups = setOf("OpenRouter"))
        // openai-codex is collapsed: header present, its 2 rows gone; OpenRouter's row remains.
        assertEquals(listOf("stepfun/step-3.7-flash:free"), rows(items).map { it.model })
        val codex = items.filterIsInstance<ModelListItem.Header>().first { it.slug == "openai-codex" }
        assertTrue(!codex.expanded)
        assertEquals(2, codex.count)
        val openRouter = items.filterIsInstance<ModelListItem.Header>().first { it.slug == "OpenRouter" }
        assertTrue(openRouter.expanded)
    }

    @Test fun favorites_stay_pinned_even_when_their_group_is_collapsed() {
        val favs = setOf(favKey("openai-codex", "gpt-5.5"))
        val items = modelSelectorRows(providers, favs, "", null, null, expandedGroups = emptySet())
        // All groups collapsed: only the pinned favorites row survives as a row.
        assertEquals(listOf("gpt-5.5"), rows(items).map { it.model })
        assertTrue((items.first() as ModelListItem.Header).isFavorites)
    }

    @Test fun query_suspends_collapse_and_reports_hit_counts() {
        val items = modelSelectorRows(providers, emptySet(), "gpt", null, null, expandedGroups = emptySet())
        // Collapsed set is ignored during search; only matching groups appear, auto-expanded.
        val headers = items.filterIsInstance<ModelListItem.Header>()
        assertEquals(listOf("openai-codex"), headers.map { it.slug })
        assertTrue(headers.single().searchHits)
        assertTrue(headers.single().expanded)
        assertEquals(2, headers.single().count)
        assertEquals(2, rows(items).size)
    }

    @Test fun query_matches_provider_display_name() {
        val named = listOf(
            ModelProviderDto(slug = "or", name = "Open Router Inc", isCurrent = false, models = listOf("m1")),
        )
        assertEquals(1, rows(modelSelectorRows(named, emptySet(), "router", null, null)).size)
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
        val rows = rows(modelSelectorRows(providers, emptySet(), "", null, null, presets = presets))
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
