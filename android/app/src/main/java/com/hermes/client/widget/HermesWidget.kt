package com.hermes.client.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized
import kotlinx.coroutines.flow.first

class HermesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val language = SettingsStore(context).appLanguage.first()
        provideContent { Content(context, language) }
    }

    @Composable
    private fun Content(context: Context, language: AppLanguage) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                // Brand blue (§2.1). This was still Mint40 #087A5C: the widget was missed by the
                // 0.1.61 icon-blue swap that retired mint, and the miss only became obvious next to
                // the warm re-skin. Glance sits outside MaterialTheme, so the literal is unavoidable
                // — keep it equal to the light `primary`, on which white text clears AA at 7.51:1.
                .background(ColorProvider(Color(0xFF004AC6)))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Item(context, localized(language, "新建会话", "New chat"), "hermes://new")
            Item(context, localized(language, "会话", "Chats"), "hermes://tab/sessions")
        }
    }

    @Composable
    private fun Item(context: Context, label: String, uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName)
        Text(
            text = label,
            modifier = GlanceModifier.fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable(actionStartActivity(intent)),
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, textAlign = TextAlign.Center),
        )
    }
}
