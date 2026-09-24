package ru.keegoo.companion.data.collector

import android.content.Context
import android.content.pm.PackageManager

// Same names the backend prompt uses; the system label is the fallback.
private val KnownApps = mapOf(
    "com.instagram.android" to "Instagram",
    "com.google.android.youtube" to "YouTube",
    "org.telegram.messenger" to "Telegram",
    "org.telegram.messenger.web" to "Telegram",
    "com.vkontakte.android" to "VK",
    "ru.vk.superapp" to "VK",
    "com.whatsapp" to "WhatsApp",
    "com.zhiliaoapp.musically" to "TikTok",
    "com.tiktok.android" to "TikTok",
    "com.twitter.android" to "X",
    "com.android.chrome" to "Chrome",
    "com.google.android.gm" to "Gmail",
    "com.spotify.music" to "Spotify",
    "ru.yandex.music" to "Яндекс Музыка",
    "ru.yandex.searchplugin" to "Яндекс",
    "com.yandex.browser" to "Яндекс Браузер",
    "ru.sberbankmobile" to "СберБанк",
    "ru.ozon.app.android" to "Ozon",
    "com.wildberries.ru" to "Wildberries",
    "com.netflix.mediaclient" to "Netflix",
    "ru.kinopoisk" to "Кинопоиск",
)

fun Context.appLabel(pkg: String): String {
    KnownApps[pkg]?.let { return it }
    return try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
