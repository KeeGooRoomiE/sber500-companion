package ru.keegoo.companion.analytics

import io.appmetrica.analytics.AppMetrica
import ru.keegoo.companion.BuildConfig

/**
 * Product events for AppMetrica, in one place so the event names stay stable and the privacy
 * promise is checkable by reading a single file.
 *
 * What may be sent: which step was reached, how a permission request ended, that a forecast was
 * shown. What may never be sent: anything from the person's data — screen minutes, sleep, steps,
 * check-ins, profile answers, the forecast text. The privacy policy says in so many words that
 * summaries do not reach analytics, and every call below has to keep that true.
 *
 * Without a key AppMetrica is never activated, so every call here is a no-op — local and CI
 * builds without the secret behave exactly as before.
 */
object Events {

    private val enabled get() = BuildConfig.APPMETRICA_KEY.isNotEmpty()

    /** Outcome of a permission request, as the funnel sees it. */
    enum class PermissionResult {
        Granted,
        Denied,
        /** The person walked past it: «Пропустить» on a blocking step. */
        Skipped,
        /** Nothing to ask — e.g. Health Connect is not installed on this phone. */
        Unavailable,
    }

    private fun report(event: String, params: Map<String, Any> = emptyMap()) {
        if (!enabled) return
        runCatching {
            if (params.isEmpty()) AppMetrica.reportEvent(event)
            else AppMetrica.reportEvent(event, params)
        }
    }

    // ─── Onboarding funnel ───────────────────────────────────────────────────

    /** A step came on screen. [step] is the index, [name] a stable slug for readability. */
    fun onboardingStep(step: Int, name: String) =
        report("onboarding_step", mapOf("step" to step, "name" to name))

    /**
     * How one permission request ended. [permission] is a stable slug:
     * usage_access | health | notifications | battery.
     */
    fun onboardingPermission(permission: String, result: PermissionResult) =
        report(
            "onboarding_permission",
            mapOf("permission" to permission, "result" to result.name.lowercase()),
        )

    /** Onboarding is over and Home is about to appear. */
    fun onboardingFinished() = report("onboarding_finished")

    // ─── Activation ──────────────────────────────────────────────────────────

    /**
     * The first forecast this person ever saw. Reported once per install — the end of the
     * funnel, and the moment the app has delivered on what it promised.
     */
    fun firstForecastShown(local: Boolean) =
        report("first_forecast_shown", mapOf("source" to if (local) "local" else "server"))

    // ─── Daily use ───────────────────────────────────────────────────────────

    /**
     * The server's morning forecast was on screen. Callers send it once per day; carries no
     * text and nothing from the person's data.
     */
    fun morningForecastView() = report("morning_forecast_view")

    /**
     * An evening check-in was recorded. [source] is where it came from (notification | app);
     * the answer itself and the tags are never sent. Callers send it once per day.
     */
    fun checkInDone(source: String) = report("checkin_done", mapOf("source" to source))

    // ─── Opens and notifications ─────────────────────────────────────────────
    //
    // The same three signs of life that the on-device activity marker counts (open, swipe,
    // button), reported so that «DAU» can be read from the app side too. Nothing from the
    // person's data travels with them.

    /**
     * The app came to the foreground and it starts a new session (30 minutes since the last
     * open). [source] is launcher | morning | checkin | review | update | link.
     */
    fun appOpen(source: String) = report("app_open", mapOf("source" to source))

    /** A notification was drawn. [kind] is morning | checkin | review | update | midday. */
    fun notificationShown(kind: String) = report("notification_shown", mapOf("kind" to kind))

    /** A notification was swiped away without opening the app. */
    fun notificationDismissed(kind: String) = report("notification_dismissed", mapOf("kind" to kind))

    /** A button inside a notification was pressed (check-in answer, «Совпало / Не совсем»). */
    fun notificationAction(kind: String) = report("notification_action", mapOf("kind" to kind))

    // ─── Engagement with the mechanics ───────────────────────────────────────
    //
    // Unlike the daily markers above these are not capped at one a day: how often a mechanic
    // is reached is the whole signal. «Спросить о себе» was opened by 5 people out of 35 in a
    // week, and the question these answer is whether that is lack of interest or of discovery.
    // Nothing from the person's data travels with them — only which mechanic, and day vs week.

    /** «Разбор дня» was opened. */
    fun dayReviewOpen() = report("day_review_open")

    /** «Итоги недели» was opened. */
    fun weeklySummaryView() = report("weekly_summary_view")

    /**
     * A follow-up chip under a review was tapped. [kind] is day | week — never the question
     * itself, which is generated from the person's own data.
     */
    fun followupClick(kind: String) = report("followup_click", mapOf("kind" to kind))

    /** «Хочу ещё» was opened — the moment the explore mechanic is discovered at all. */
    fun exploreMore() = report("explore_more")
}
