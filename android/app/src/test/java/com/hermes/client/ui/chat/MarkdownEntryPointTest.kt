package com.hermes.client.ui.chat

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assistant Markdown is rendered by five surfaces — the conversation, the component gallery, the
 * fullscreen table, the table PNG export and the shared long image — and for a long time each one
 * called the library's `Markdown(...)` directly with its own argument list. Only the conversation
 * passed the annotator and the inline-content map, so the other four silently lost the
 * external-link glyph; worse, only the conversation installed the guarded `UriHandler`, so links
 * elsewhere opened through Compose's default one, which takes any scheme it is handed and crashes
 * where no activity can handle it. Table cells are model output; that was the wrong surface to
 * leave open.
 *
 * A per-surface argument list cannot be kept in sync by care alone — the evidence is that it was
 * not. This is the same shape of guard as [com.hermes.client.ui.localization.LocalizationCoverageTest]:
 * the rule lives where it can fail the build, not in a review comment.
 */
class MarkdownEntryPointTest {
    @Test fun assistantMarkdownIsOnlyEverRenderedThroughTheSharedEntryPoint() {
        val sourceRoot = sequenceOf(
            Path.of("src/main/java"),
            Path.of("app/src/main/java"),
            Path.of("android/app/src/main/java"),
        ).firstOrNull(Path::isDirectory) ?: error("Android source root not found")

        // The entry point is the one place allowed to call the library directly.
        val entryPoint = "com/hermes/client/ui/chat/HermesMarkdown.kt"
        val callsLibraryDirectly = Regex("""(?<![A-Za-z])Markdown\s*\(""")

        val offenders = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .filter { !it.toString().endsWith(entryPoint) }
                .flatMap { file ->
                    Files.readAllLines(file).mapIndexedNotNull { index, line ->
                        val code = line.trim()
                        // Prose may name Markdown() while explaining it; only code counts.
                        val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                        if (!isComment && callsLibraryDirectly.containsMatchIn(line) && "markdown-entry-allow:" !in line) {
                            "${sourceRoot.relativize(file)}:${index + 1}: ${line.trim()}"
                        } else null
                    }.stream()
                }
                .toList()
        }

        assertTrue(
            "Render assistant Markdown through HermesMarkdown(surface = …) so every surface gets the\n" +
                "same annotator, inline content, image transformer and guarded UriHandler. If a call\n" +
                "genuinely must bypass it, mark the line with a reviewed `markdown-entry-allow:` reason.\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
