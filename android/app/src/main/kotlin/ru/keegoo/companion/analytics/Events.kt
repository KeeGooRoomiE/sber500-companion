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
}
