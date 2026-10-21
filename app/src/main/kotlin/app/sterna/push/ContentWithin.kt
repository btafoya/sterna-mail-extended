package app.sterna.push

import app.sterna.core.data.settings.NotificationContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The setting's first value, or [NotificationContent.NONE] when it fails or has not answered
 * within [budgetMs]: a broadcast held past its `goAsync` budget gets the process killed.
 *
 * `NotificationActionReceiver.CONTENT_BUDGET_MS` is the leash the banner's own buttons use. Shorter,
 * and an ordinary cold start would re-post the summary without the account's name; longer, and the
 * server write that ran first has already spent part of the broadcast's budget.
 */
internal suspend fun readContentWithin(content: Flow<NotificationContent>, budgetMs: Long): NotificationContent =
    runCatching { withTimeoutOrNull(budgetMs) { content.first() } }.getOrNull() ?: NotificationContent.NONE
