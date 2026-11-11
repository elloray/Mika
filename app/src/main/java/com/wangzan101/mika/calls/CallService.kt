package com.wangzan101.mika.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Telecom keeps its bound InCallService alive; we need a cancellable notification, not an idle FGS. */
internal class CallNotifications(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun ready(): Boolean = runCatching {
        val notificationManager = manager ?: return false
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "AI 电话", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        notificationManager.areNotificationsEnabled() &&
            notificationManager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    fun show(callId: String, message: String, ringing: Boolean): Boolean = runCatching {
        if (!ready()) return false
        val takeOverIntent = Intent(context, CallNotificationAction::class.java)
            .setAction(ACTION).setData(Uri.fromParts("mika-call", callId, null))
        val takeOver = PendingIntent.getBroadcast(context, 0, takeOverIntent, PendingIntent.FLAG_IMMUTABLE)
        val openCalls = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_CALLS, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.app_name)).setContentText(message)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openCalls)
            .addAction(Notification.Action.Builder(null, if (ringing) "取消 AI 接听" else "手动接管", takeOver).build())
        manager?.notify(ID, builder.build()) ?: return false
        true
    }.getOrDefault(false)

    fun handoff() {
        runCatching {
            if (!ready()) return
            manager?.notify(ID, Notification.Builder(context, CHANNEL)
                .setContentTitle(context.getString(R.string.app_name)).setContentText("AI 已退出通话，请在系统电话界面接管")
                .setSmallIcon(android.R.drawable.sym_action_call).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setTimeoutAfter(12_000L).build())
        }
    }

    fun clear() { runCatching { manager?.cancel(ID) } }

    companion object {
        const val ACTION = "com.wangzan101.mika.calls.TAKEOVER"
        const val ACTION_CANCEL_LOCKED_DIAL = "com.wangzan101.mika.calls.CANCEL_LOCKED_DIAL"
        const val LOCKED_DIAL_SCHEME = "mika-locked-dial"
        const val CHANNEL = "mika_ai_calls"
        private const val ID = 90_117
    }
}

class CallNotificationAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val uri = intent.data ?: return
        if (!((action == CallNotifications.ACTION && uri.scheme == "mika-call") ||
                (action == CallNotifications.ACTION_CANCEL_LOCKED_DIAL && uri.scheme == CallNotifications.LOCKED_DIAL_SCHEME))) return
        val id = uri.schemeSpecificPart ?: return
        val app = context.applicationContext as? MikaApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (action == CallNotifications.ACTION_CANCEL_LOCKED_DIAL) app.calls.cancelLockedDial(id)
                else app.calls.takeover(id)
            } finally {
                pending.finish()
            }
        }
    }
}
