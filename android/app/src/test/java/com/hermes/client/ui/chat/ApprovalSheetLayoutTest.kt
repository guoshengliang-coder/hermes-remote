package com.hermes.client.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** HG-239: test the real modal window, including its viewport, insets and gesture interception. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-notnight-320dpi")
class ApprovalSheetLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val longRequest = ApprovalRequest(
        command = (1..100).joinToString("\n") { "printf 'review line $it before approving this deliberately long command'" },
        description = (1..20).joinToString("\n") { "Review the operation and its destination before approving. $it" },
        patternKeys = listOf("test:write_system_file", "test:long_grant_name"),
        allowPermanent = true,
        smartDenied = true,
    )
    private val decisions = listOf("Allow once", "Allow this run", "Always allow", "Deny")

    private fun show(
        request: ApprovalRequest = longRequest,
        dark: Boolean = false,
        scale: Float = 1.3f,
        language: AppLanguage = AppLanguage.EN,
        onRespond: (ApprovalChoice) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale),
                    LocalAppLanguage provides language,
                ) {
                    ApprovalSheet(request, onRespond, onDismiss)
                }
            }
        }
        compose.waitForIdle()
    }

    // This exact assertion fails on the old layout: even Allow once is outside the viewport.
    @Test fun longCommandKeepsAllDecisionsVisible() {
        show()
        decisions.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun longDescriptionAndGrantsCannotDisplaceActions() {
        show(longRequest.copy(command = "true", patternKeys = List(30) { "test:grant_$it" }))
        decisions.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Owner override — approve only this one operation.").performScrollTo().assertIsDisplayed()
        decisions.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun detailsScrollBothWaysWithoutMovingActionsOrResponding() {
        val responses = mutableListOf<ApprovalChoice>()
        var dismissals = 0
        show(onRespond = { responses += it }, onDismiss = { dismissals++ })
        val before = decisions.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        val command = compose.onNodeWithTag("approval-command")
        val horizontal = command.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("The full command must remain horizontally reachable", horizontal.maxValue() > 0f)
        command.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(horizontal.value() > 0f) }
        val details = compose.onNodeWithTag("approval-details")
        details.performTouchInput { swipeUp() }
        val vertical = details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(vertical.value() > 0f)
        details.performTouchInput { swipeDown() }
        compose.onNodeWithText("Owner override — approve only this one operation.").performScrollTo().assertIsDisplayed()
        val after = decisions.map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertEquals(before, after)
        assertTrue(responses.isEmpty())
        assertEquals(0, dismissals)
    }

    @Test fun standardButtonsSubmitTheirExactScopeOncePerTap() {
        val responses = mutableListOf<ApprovalChoice>()
        show(onRespond = { responses += it })
        decisions.forEach { compose.onNodeWithText(it).performTouchInput { click() } }
        assertEquals(listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.ALWAYS, ApprovalChoice.DENY), responses)
    }

    @Test fun elevatedApprovalRequiresSlideAndUsesSelectedScope() {
        val responses = mutableListOf<ApprovalChoice>()
        show(longRequest.copy(allowPermanent = false), onRespond = { responses += it })
        compose.onNodeWithText("Deny").assertIsDisplayed()
        compose.onNodeWithText("Always allow").assertDoesNotExist()
        compose.onNodeWithContentDescription("Toggle allow scope").assertIsDisplayed().performClick()
        val slider = compose.onNodeWithContentDescription("Slide to allow").assertIsDisplayed()
        slider.performTouchInput { click() }
        assertTrue(responses.isEmpty())
        val thumbCenter = 28f * compose.density.density
        slider.performTouchInput { swipe(Offset(thumbCenter, centerY), Offset(width - thumbCenter, centerY), 400) }
        assertEquals(listOf(ApprovalChoice.SESSION), responses)
    }

    @Test fun elevatedDenyRemainsImmediatelyReachable() {
        val responses = mutableListOf<ApprovalChoice>()
        show(longRequest.copy(allowPermanent = false), onRespond = { responses += it })
        compose.onNodeWithText("Deny").assertIsDisplayed().performTouchInput { click() }
        assertEquals(listOf(ApprovalChoice.DENY), responses)
    }

    @Test fun shortRequestsStillWrapContent() {
        show(longRequest.copy(command = "true", description = "", patternKeys = emptyList(), smartDenied = false), scale = 1f)
        val details = compose.onNodeWithTag("approval-details").fetchSemanticsNode()
        assertEquals(0f, details.config[SemanticsProperties.VerticalScrollAxisRange].maxValue())
        assertTrue("Short content should not fill the window", details.boundsInRoot.top > 100f * compose.density.density)
        decisions.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test @Config(qualifiers = "w640dp-h360dp-notnight-320dpi")
    fun landscapeLargeTextKeepsActionsVisible() {
        show()
        decisions.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        assertTrue(compose.onNodeWithTag("approval-details").fetchSemanticsNode().boundsInRoot.height > 0f)
    }

    @Test fun lightChineseScreenshot() {
        show(language = AppLanguage.ZH, scale = 1f)
        compose.onNodeWithText("仅允许一次").assertIsDisplayed()
        compose.onNodeWithText("拒绝").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("screenshots/approval-fixed-light-zh.png")
    }

    @Test fun darkLargeTextScreenshotWithLightSystem() {
        show(dark = true)
        decisions.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNode(isDialog()).captureRoboImage("screenshots/approval-fixed-dark-en-fs13.png")
    }

    @Test fun elevatedLargeTextScreenshot() {
        show(longRequest.copy(allowPermanent = false), language = AppLanguage.ZH)
        compose.onNodeWithText("拒绝").assertIsDisplayed()
        compose.onNodeWithContentDescription("滑动以允许").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("screenshots/approval-elevated-light-zh-fs13.png")
    }
}
