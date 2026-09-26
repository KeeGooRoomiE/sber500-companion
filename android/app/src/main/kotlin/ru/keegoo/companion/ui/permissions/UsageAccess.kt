package ru.keegoo.companion.ui.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.keegoo.companion.ui.theme.AppShapes

/*
 * Usage access for an APK installed outside a store.
 *
 * Android 13+ puts sideloaded apps under «restricted settings»: the Usage access switch shows
 * «Доступ для приложения запрещен» until the person allows it on the app's info screen
 * (⋮ → «Разрешить ограниченные настройки»; the menu appears only after one blocked attempt).
 * An app can't lift this itself — we can only lead the way: open our own switch directly,
 * and if it didn't work, open «О приложении» with the exact steps.
 */

/**
 * [direct] = our own row in «Доступ к истории использования» (Android 11+ on most phones), so the
 * person doesn't have to find us in the list. Callers fall back to direct = false on
 * ActivityNotFoundException (package visibility makes a resolveActivity check unreliable).
 */
fun Context.usageAccessIntent(direct: Boolean = true): Intent =
    if (direct) Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", packageName, null))
    else Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

/** «О приложении» — where «Разрешить ограниченные настройки» lives (⋮ in the top-right corner). */
fun Context.appInfoIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Steps for «Доступ для приложения запрещен». [content] is the text colour of the surface it sits on. */
@Composable
fun RestrictedSettingsSteps(
    modifier: Modifier = Modifier,
    content: Color = MaterialTheme.colorScheme.onSurface,
    accent: Color = MaterialTheme.colorScheme.primary,
    onOpenAppInfo: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Android пишет «Доступ запрещен»?",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
        Text(
            text = "Так бывает с приложениями не из магазина — это защита Android, разблокировать нужно один раз.",
            style = MaterialTheme.typography.bodySmall,
            color = content.copy(alpha = .8f),
        )
        Step(1, "Открой «О приложении» кнопкой ниже", content)
        Step(2, "Нажми ⋮ справа вверху → «Разрешить ограниченные настройки»", content)
        Step(3, "Вернись сюда и снова нажми «Дать доступ»", content)
        Box(
            Modifier
                .padding(top = 4.dp)
                .clip(AppShapes.button)
                .border(1.dp, accent.copy(alpha = .7f), AppShapes.button)
                .clickable(onClick = onOpenAppInfo)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text("Открыть «О приложении»", style = MaterialTheme.typography.labelLarge, color = accent)
        }
    }
}

@Composable
private fun Step(n: Int, text: String, content: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$n.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = content)
        Text(text, style = MaterialTheme.typography.bodySmall, color = content)
    }
}
