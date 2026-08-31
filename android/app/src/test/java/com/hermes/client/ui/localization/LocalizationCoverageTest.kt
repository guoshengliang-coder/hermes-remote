package com.hermes.client.ui.localization

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationCoverageTest {
    @Test fun productUiDoesNotAddRawVisibleStrings() {
        val sourceRoot = sequenceOf(
            Path.of("src/main/java"),
            Path.of("app/src/main/java"),
            Path.of("android/app/src/main/java"),
        ).firstOrNull(Path::isDirectory) ?: error("Android source root not found")

        val visibleLiteral = Regex(
            """(?<![A-Za-z])Text\(\s*\"|contentDescription\s*=\s*\"|\btitle\s*=\s*\"""",
        )
        val offenders = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .flatMap { file ->
                    Files.readAllLines(file).mapIndexedNotNull { index, line ->
                        if (visibleLiteral.containsMatchIn(line) && "l10n-allow:" !in line) {
                            "${sourceRoot.relativize(file)}:${index + 1}: ${line.trim()}"
                        } else null
                    }.stream()
                }
                .toList()
        }

        assertTrue(
            "Raw user-visible strings must use localized()/l10n()/LocalizedText, or carry a reviewed l10n-allow reason:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
