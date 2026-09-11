package com.hermes.client.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProjectIconsTest {
    /**
     * The keys are upstream's codicon names, not ours: Hermes stores the string and the desktop
     * renders it as a codicon, so a rename here would silently break the icon over there.
     */
    @Test fun the_offered_names_are_upstream_codicon_names() {
        assertEquals(
            listOf("folder-library", "repo", "rocket", "beaker", "star-full", "terminal", "globe", "package"),
            PROJECT_ICONS,
        )
    }

    @Test fun every_offered_name_resolves_to_its_own_glyph() {
        val glyphs = PROJECT_ICONS.map { projectIconFor(it) }
        assertEquals(PROJECT_ICONS.size, glyphs.map { it.name }.distinct().size)
    }

    /** An icon set on the desktop from outside our subset must still render something. */
    @Test fun unknown_and_absent_names_fall_back_to_the_folder() {
        assertSame(FolderStrokeIcon, projectIconFor(null))
        assertSame(FolderStrokeIcon, projectIconFor("telescope"))
        assertSame(FolderStrokeIcon, projectIconFor(""))
    }

    /** The default entry is the folder itself, so an unstyled project looks like a project. */
    @Test fun the_first_choice_is_the_plain_folder() {
        assertSame(FolderStrokeIcon, projectIconFor(PROJECT_ICONS.first()))
        assertNotEquals(FolderStrokeIcon.name, projectIconFor("rocket").name)
    }
}
