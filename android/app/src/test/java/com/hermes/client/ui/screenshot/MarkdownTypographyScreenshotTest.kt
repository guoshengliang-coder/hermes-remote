package com.hermes.client.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden renders of the assistant markdown body (DESIGN.md §5.4). These exist so a change to the
 * spacing/type scale is visible in review instead of only on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-420dpi")
class MarkdownTypographyScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val general = """
把主要问题分成三类，下面逐条说明。第一段正文用来观察段落之间的间距是否足够。

## 一、间距问题

段落文字在这里继续，用来对照标题上方与下方的留白。

1. **列表项一** 这是一条比较长的列表内容，用来观察换行之后的行距，以及与下一条之间的间隔是否足够。
2. **列表项二** 第二条同样是长文本，如果两条之间没有明显的空白，读起来就会变成一堵信息墙。
3. **列表项三** 第三条收尾。

- 无序项一，同样需要检查间隔
- 无序项二
  - 嵌套项，检查缩进层级
  - 嵌套项二

### 二、链接与行内元素

正文中的链接 [Hermes 文档](https://example.com/docs) 现在有颜色和图标，另外还有 `inline code` 与 **加粗** 与 *斜体*。

> 引用块用来检查左侧竖线与内边距。

| 列 A | 列 B |
| --- | --- |
| 1 | 2 |

```kotlin
fun main() { println("hi") }
```

最后一段正文，用来收尾。
""".trimIndent()

    private val edgeCases = """
# H1 一级标题
正文紧跟一级标题。

#### H4 四级标题
正文紧跟四级标题，检查 h4/h5 与正文的区分度。

##### H5 五级标题
正文紧跟五级标题。

任务清单：

- [ ] 未完成的任务项
- [x] 已完成的任务项

裸链接自动识别：https://example.com/a/very/long/path/that/wraps 以及 www.example.com。

编号对齐（两位数）：

8. 第八项
9. 第九项
10. 第十项，检查两位数编号是否让文字左缘偏移

---

多层嵌套：

1. 第一层
   1. 第二层
      - 第三层
        1. 第四层

链接密集段落：见 [文档 A](https://a.example.com)、[文档 B](https://b.example.com) 和 [文档 C](https://c.example.com)。
""".trimIndent()

    @Test fun general() = shot("markdown-body", general, false)
    @Test fun generalDark() = shot("markdown-body-dark", general, true)
    @Test fun edges() = shot("markdown-body-edge-cases", edgeCases, false)

    /**
     * Reading surfaces follow the system font scale (DESIGN.md §3.1). The link glyph is sized in
     * sp and the list bullet is centred on the item's measured line height, so both must grow with
     * the text here rather than staying pinned to a dp value.
     */
    @Test fun largeFont() = shot("markdown-body-large-font", general, false, fontScale = 1.5f)

    private fun shot(name: String, text: String, dark: Boolean, fontScale: Float? = null) {
        compose.setContent {
            com.hermes.client.ui.theme.HermesTheme(darkTheme = dark) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        density.density,
                        fontScale ?: density.fontScale,
                    ),
                ) {
                androidx.compose.material3.Surface {
                    com.hermes.client.ui.chat.AssistantTurn(
                        msg = com.hermes.client.domain.ChatMessage(
                            id = "a", role = com.hermes.client.domain.Role.ASSISTANT,
                            text = text, isStreaming = false,
                        ),
                        canRegenerate = false, showActions = false,
                        onRegenerate = {}, onRetryWithModel = {}, onOpenTableFullscreen = {},
                        isSpeaking = false, onReadAloud = {}, onStopReading = {},
                        onImageSave = {}, onImageSaveAs = {}, onImageShare = {},
                        savingImageId = null, onFileOpen = {}, onFileShare = {},
                    )
                }
                }
            }
        }
        Thread.sleep(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "screenshots/$name.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
        )
    }
}
