package app.sterna.dav

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sterna.MainActivity
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.dav.PlannedAlarm
import app.sterna.core.jmap.JmapType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

private const val TAG = "Dav"

/** Syncs calendars, contacts and files of every JMAP account, sends the writes queued on the phone, and re-plans reminders. */
object Dav {
    private const val UNIQUE = "dav-sync"

    /** Needs network; runs at once when it has it. Appended, not replaced: a running pass is never cancelled mid-write. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<DavWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** One pass; true when something should be tried again. Per account, so one failure costs the others nothing. */
    suspend fun run(app: Application): Boolean {
        val container = app.container
        val dav = container.davRepository
        var retry = false
        for (stored in container.accountStore.accounts()) {
            val credentials = container.accountStore.credentials(stored.id) ?: continue
            runCatching {
                val sent = dav.drain(credentials)
                if (sent.retryLater) retry = true
                dav.sync(credentials)
                // The completion list is read from the same cards: refresh it when a card was written.
                if (JmapType.CONTACT_CARD.name in sent.touched) container.mailRepository.accounts.syncServerContacts(credentials)
            }.onFailure { Log.w(TAG, "DAV pass failed for account ${stored.id}", it); retry = true }
        }
        runCatching { Reminders.replan(app) }.onFailure { Log.w(TAG, "Reminder plan failed", it) }
        return retry
    }
}

class DavWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (Dav.run(applicationContext as Application)) Result.retry() else Result.success()
}

/**
 * Reminders: the next [HORIZON_MS] of alarms of every account, one `AlarmManager` alarm each, replaced
 * wholesale on every plan so an edit, a deletion or a changed time zone cannot leave a stale one.
 * ponytail: exact alarms when the user allows them, else inexact (minutes late); no per-account switch,
 * the notification channel is the user's off switch.
 */
object Reminders {
    private const val HORIZON_MS = 48 * 3_600_000L
    private const val MAX_ALARMS = 60
    private const val PREFS = "dav_reminders"
    private const val SCHEDULED = "scheduled"
    const val CHANNEL = "calendar_reminders"
    const val ACTION = "app.sterna.REMINDER"

    suspend fun replan(app: Application) {
        val container = app.container
        val now = System.currentTimeMillis()
        val plan = container.accountStore.accounts()
            .flatMap { container.davRepository.plannedAlarms(it.id, now, HORIZON_MS) }
            .sortedBy { it.atMs }
            .take(MAX_ALARMS)
        val manager = app.getSystemService(AlarmManager::class.java)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(SCHEDULED, emptySet()).orEmpty().forEach { manager.cancel(pending(app, it, null)) }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        plan.forEach { alarm ->
            val op = pending(app, alarm.key, alarm)
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.atMs, op)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.atMs, op)
        }
        prefs.edit().putStringSet(SCHEDULED, plan.mapTo(mutableSetOf()) { it.key }).apply()
    }

    /** One `PendingIntent` per alarm, named by its key in the data Uri so it can be rebuilt to cancel it. */
    private fun pending(context: Context, key: String, alarm: PlannedAlarm?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION).setData(Uri.fromParts("sterna-reminder", key, null))
        if (alarm != null) intent.putExtra("title", alarm.title).putExtra("start", alarm.startMs)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(Reminders.CHANNEL, context.getString(R.string.dav_reminders_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val title = intent.getStringExtra("title").orEmpty().ifBlank { context.getString(R.string.dav_untitled) }
        val start = intent.getLongExtra("start", 0L)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            intent.data.hashCode(),
            NotificationCompat.Builder(context, Reminders.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setContentTitle(title)
                .setContentText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(start)))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }
}

/** A reboot, an app update, or a clock or zone change leaves every alarm wrong or gone: plan again, from Room, no network. */
class DavBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? Application ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Reminders.replan(app)
            } catch (t: Throwable) {
                Log.w(TAG, "Reminder plan failed after ${intent.action}", t)
            } finally {
                pending.finish()
            }
        }
    }
}
