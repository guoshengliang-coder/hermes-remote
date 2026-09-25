package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class UserUrlTokensTest {
    @Test fun urlTokenBecomesShortClickableLabelWithoutChangingSource() {
        val source = "查看 @url:`https://example.com/a/b` 后继续"
        val rendered = renderUserUrlTokens(source)
        assertEquals("查看 链接 · example.com 后继续", rendered.text)
        assertEquals(listOf(UserUrlLink(3, 19, "https://example.com/a/b")), rendered.links)
        assertEquals("查看 @url:`https://example.com/a/b` 后继续", source)
    }

    @Test fun invalidAndUnsafeTokensStayLiteral() {
        listOf("@url:`file:///etc/passwd`", "@url:`javascript:alert(1)`", "@url:`https://`", "@url:`https://example.com bad`")
            .forEach { source ->
                assertEquals(source, renderUserUrlTokens(source).text)
                assertEquals(emptyList<UserUrlLink>(), renderUserUrlTokens(source).links)
            }
    }

    @Test fun fencedCodeStaysLiteral() {
        val source = "```text\n@url:`https://example.com`\n```\n@url:`https://safe.example`"
        val rendered = renderUserUrlTokens(source)
        assertEquals("```text\n@url:`https://example.com`\n```\n链接 · safe.example", rendered.text)
        assertEquals(1, rendered.links.size)
    }
}
