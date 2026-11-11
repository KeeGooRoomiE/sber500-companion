package ru.keegoo.companion.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import ru.keegoo.companion.analytics.Events
import ru.keegoo.companion.data.api.model.FollowupQuestionDto
import ru.keegoo.companion.data.prefs.ActivityMarker

/** What the notification falls back to when the answer could not be fetched. */
const val SHADE_FOLLOWUP_FAILED = "Не получилось ответить. Попробуй ещё раз"
const val SHADE_FOLLOWUP_THINKING = "Думаю над ответом…"

/**
 * A follow-up question tapped on the morning forecast in the shade (plan 1.1.0, item 2).
 *
 * The tap is the person's own action, so the server logs the call as user-initiated, the same as
 * a chip on Home. The LLM answer takes longer than a receiver may live, so the receiver only
 * shows «думаю» on the same notification and hands the request to a worker.
 */
class ShadeFollowupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHADE_FOLLOWUP) return
        val date = intent.getStringExtra(EXTRA_FU_DATE) ?: return
        val id = intent.getStringExtra(EXTRA_FU_ID) ?: return
        val title = intent.getStringExtra(EXTRA_FU_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_FU_TEXT).orEmpty()

        ActivityMarker.mark(context, ActivityMarker.Source.Action)
        Events.notificationAction("followup")
        Events.followupClick("morning_shade")

        // Same text, no buttons while the answer is on its way: a second tap would ask twice.
        showMorningNotification(context, NotificationCopy(title, text), note = SHADE_FOLLOWUP_THINKING, alert = false)

        val input = Data.Builder()
            .putString(EXTRA_FU_DATE, date)
            .putString(EXTRA_FU_ID, id)
            .putString(EXTRA_FU_LABEL, intent.getStringExtra(EXTRA_FU_LABEL).orEmpty())
            .putString(EXTRA_FU_TITLE, title)
            .putString(EXTRA_FU_TEXT, text)
            .putStringArray(EXTRA_FU_REST_IDS, intent.getStringArrayExtra(EXTRA_FU_REST_IDS) ?: emptyArray())
            .putStringArray(EXTRA_FU_REST_LABELS, intent.getStringArrayExtra(EXTRA_FU_REST_LABELS) ?: emptyArray())
            .build()
        val request = OneTimeWorkRequestBuilder<ShadeFollowupWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("shade_followup", ExistingWorkPolicy.REPLACE, request)
    }
}

class ShadeFollowupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val date = inputData.getString(EXTRA_FU_DATE) ?: return Result.success()
        val id = inputData.getString(EXTRA_FU_ID) ?: return Result.success()
        val label = inputData.getString(EXTRA_FU_LABEL).orEmpty()
        val title = inputData.getString(EXTRA_FU_TITLE).orEmpty()
        val text = inputData.getString(EXTRA_FU_TEXT).orEmpty()
        val rest = restChips(inputData.getStringArray(EXTRA_FU_REST_IDS), inputData.getStringArray(EXTRA_FU_REST_LABELS))

        val repo = EntryPointAccessors.fromApplication(ctx, CheckInEntryPoint::class.java).repository()
        val answer = repo.followup("morning", date, id).getOrNull()?.text?.trim().orEmpty()

        if (answer.isBlank()) {
            // Put everything back, the asked question included, so the person can try again.
            val all = listOf(FollowupQuestionDto(id, label)) + rest
            showMorningNotification(ctx, NotificationCopy(title, text), date, all, note = SHADE_FOLLOWUP_FAILED, alert = false)
            return Result.success()
        }
        Events.shadeFollowupAnswered()
        // The question becomes the title, the answer the body; what is still unasked stays as buttons.
        showMorningNotification(ctx, NotificationCopy(label.ifBlank { title }, answer), date, rest, alert = false)
        return Result.success()
    }
}

/** Two parallel arrays back into chips; a length mismatch or blanks drop the broken pairs. */
internal fun restChips(ids: Array<String>?, labels: Array<String>?): List<FollowupQuestionDto> =
    ids.orEmpty().zip(labels.orEmpty())
        .filter { (i, l) -> i.isNotBlank() && l.isNotBlank() }
        .map { (i, l) -> FollowupQuestionDto(i, l) }
