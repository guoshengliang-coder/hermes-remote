package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageViewerItemsTest {
    private fun image(id: String, path: String? = "/cache/$id.png") =
        ChatImage(id = id, localPath = path)

    private fun assistant(id: String, vararg images: ChatImage) =
        ChatMessage(id, Role.ASSISTANT, "answer", images = images.toList())

    @Test fun imageInALaterAssistantRecordOpensFromTheDisplayedTurn() {
        val photo = image("chart")
        val messages = listOf(assistant("first"), assistant("later", photo))
        val displayed = messages.organizedConversationTurns().single()
        assertEquals(listOf(photo.id), transcriptViewerItems(messages, displayed.id, photo.id).map { it.id })
    }

    @Test fun pagesIncludeTheWholeDisplayedAnswerInOrderWithoutDuplicates() {
        val one = image("one")
        val two = image("two")
        val messages = listOf(
            assistant("first", one),
            assistant("later", one, two),
            ChatMessage("next-user", Role.USER, "next", images = listOf(image("other"))),
            assistant("next-answer", image("next")),
        )
        assertEquals(listOf("one", "two"), transcriptViewerItems(messages, "first", "two").map { it.id })
    }

    @Test fun historyReplacingMessageIdsRetainsTheSelectedImageAndItsTurn() {
        val photo = image("chart")
        val messages = listOf(assistant("history-first"), assistant("history-later", photo, image("second")))
        assertEquals(listOf("chart", "second"), transcriptViewerItems(messages, "live-first", photo.id).map { it.id })
    }

    @Test fun hydrationAndOriginalReplacementUseTheLatestImageFile() {
        val preview = image("chart", "/cache/preview.png")
        val messages = listOf(assistant("first"), assistant("later", preview))
        val original = preview.copy(localPath = "/cache/original.png")
        val updated = messages.dropLast(1) + assistant("later", original)
        assertEquals(preview, transcriptViewerItems(messages, "first", preview.id).single().export)
        assertEquals(original, transcriptViewerItems(updated, "first", preview.id).single().export)
        assertEquals(ImageSource.Path("/cache/original.png"), transcriptViewerItems(updated, "first", preview.id).single().source)
    }

    @Test fun userImagesStayWithinTheirOwnMessageEvenWhenAnAssistantReusesTheImage() {
        val photo = image("shared")
        val user = ChatMessage("user", Role.USER, "", images = listOf(photo))
        assertEquals(listOf("shared"), transcriptViewerItems(
            listOf(user, assistant("answer", photo, image("generated"))), user.id, photo.id,
        ).map { it.id })
    }

    @Test fun deletedOrUncachedImagesLeaveNoEmptyViewerPage() {
        assertTrue(transcriptViewerItems(listOf(assistant("first")), "first", "removed").isEmpty())
        assertTrue(transcriptViewerItems(listOf(assistant("first", image("waiting", null))), "first", "waiting").isEmpty())
        assertTrue(transcriptViewerItems(emptyList(), "deleted-owner", "removed").isEmpty())
    }

    @Test fun prependedHistoryKeepsTheSelectedImageInItsExpandedTurn() {
        val messages = listOf(assistant("older", image("older")), assistant("live", image("selected")))
        assertEquals(listOf("older", "selected"), transcriptViewerItems(messages, "live", "selected").map { it.id })
    }

    @Test fun missingOwnerDoesNotGuessBetweenTurnsReusingTheSameImage() {
        val messages = listOf(
            ChatMessage("user", Role.USER, "", images = listOf(image("shared"))),
            assistant("answer", image("shared")),
        )
        assertTrue(transcriptViewerItems(messages, "removed-owner", "shared").isEmpty())
    }
}
