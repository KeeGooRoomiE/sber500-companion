package ru.keegoo.companion.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.data.prefs.dismissUpdate
import ru.keegoo.companion.data.prefs.isUpdateDismissed
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.ui.theme.AppShapes
import javax.inject.Inject

/** A newer APK is out: its version and where to get it. */
data class UpdateOffer(val version: String, val url: String)

/** After closing, the toast comes back for the same version in this many days. */
private const val DISMISS_DAYS = 3L

/**
 * Compares the app's own versionName with the newest one the server knows about.
 * "0.6.0" vs "0.5.2" → newer. Numeric parts are compared one by one (so "0.10.0" > "0.9.1");
 * anything unparsable falls back to plain string inequality.
 */
fun isNewerVersion(latest: String, current: String): Boolean {
    val a = latest.trim().removePrefix("v")
    val b = current.trim().removePrefix("v").substringBefore('-') // "0.6.0-debug" → "0.6.0"
    if (a.isEmpty() || a == b) return false
    val pa = a.split('.').map { it.toIntOrNull() }
    val pb = b.split('.').map { it.toIntOrNull() }
    if (pa.any { it == null } || pb.any { it == null }) return a != b
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrNull(i) ?: 0
        val y = pb.getOrNull(i) ?: 0
        if (x != y) return x > y
    }
    return false
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CompanionRepository,
) : ViewModel() {

    private val _offer = MutableStateFlow<UpdateOffer?>(null)
    val offer: StateFlow<UpdateOffer?> = _offer

    init {
        viewModelScope.launch {
            val v = repository.latestVersion().getOrNull() ?: return@launch
            if (!isNewerVersion(v.latest, BuildConfig.VERSION_NAME)) return@launch
            if (context.isUpdateDismissed(v.latest, DISMISS_DAYS)) return@launch
            delay(1_500) // let the screen settle before sliding in
            _offer.value = UpdateOffer(v.latest, v.downloadUrl)
        }
    }

    fun dismiss() {
        val offer = _offer.value ?: return
        _offer.value = null
        viewModelScope.launch { context.dismissUpdate(offer.version) }
    }
}

/** «Меня можно обновить» — a small closable toast at the bottom; tap opens the download page. */
@Composable
fun UpdateToast(modifier: Modifier = Modifier, vm: UpdateViewModel = hiltViewModel()) {
    val offer by vm.offer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    // Keep the last offer while sliding out
    var shown by remember { mutableStateOf<UpdateOffer?>(null) }
    if (offer != null) shown = offer
    AnimatedVisibility(
        visible = offer != null,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        enter = slideInVertically(tween(460, easing = decelerate)) { it } + fadeIn(tween(300)),
        exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
    ) {
        val o = shown ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(12.dp, AppShapes.card)
                .clip(AppShapes.card)
                .background(MaterialTheme.colorScheme.inverseSurface)
                .clickable { context.openUrl(o.url) }
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Меня можно обновить",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Text(
                    text = "Версия ${o.version} уже на сайте — нажми, чтобы скачать",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = .75f),
                )
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(AppShapes.circle)
                    .clickable(onClick = vm::dismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", fontSize = 20.sp, color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // no browser — nothing else to open
    }
}
