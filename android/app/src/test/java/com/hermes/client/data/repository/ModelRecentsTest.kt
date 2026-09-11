package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRecentsTest {
    private fun key(model: String) = favKey("openai", model)

    @Test fun push_moves_to_front_dedupes_and_caps() {
        var list = emptyList<String>()
        for (i in 1..8) list = pushRecentModel(list, key("m$i"))
        assertEquals(MODEL_RECENTS_LIMIT, list.size)
        assertEquals(key("m8"), list.first())
        assertEquals(key("m4"), list.last())

        list = pushRecentModel(list, key("m5"))
        assertEquals(key("m5"), list.first())
        assertEquals(MODEL_RECENTS_LIMIT, list.size)
        assertEquals(1, list.count { it == key("m5") })
    }

    @Test fun re_pushing_the_head_changes_nothing() {
        val list = listOf(key("a"), key("b"))
        assertEquals(list, pushRecentModel(list, key("a")))
    }

    /**
     * The chip row splits these keys back into (provider, model), so the separator inside a key
     * must never be the one the list itself is joined on: favKey uses U+0000, the store U+0001.
     * Model names carry '/', ':', '.', '-' and spaces, which is why neither is a printable char.
     */
    @Test fun a_key_never_contains_the_list_separator() {
        val k = favKey("OpenRouter", "stepfun/step-3.7-flash:free")
        assertEquals(false, k.contains('\u0001'))
        assertEquals(listOf("OpenRouter", "stepfun/step-3.7-flash:free"), k.split('\u0000'))
    }
}
