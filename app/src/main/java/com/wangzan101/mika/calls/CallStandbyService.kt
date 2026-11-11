package com.wangzan101.mika.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps an explicitly enabled phone standby visible. Telecom still owns the actual call. */
class CallStandbyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: CallRuntime? = null
    private var statusJob: Job? = null
    private var promoted = false
    internal var stickyAutoAnswer = false
        private set

    internal fun revokeIncomingAuthorization() {
        stickyAutoAnswer = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = (application as? MikaApp)?.calls
        runtime = calls
        val explicit = intent?.action == ACTION_START && calls?.isStandbyStartRequested(intent.getStringExtra(EXTRA_REQUEST_ID)) == true
        // Always acknowledge the OS start before rejecting a stale or cancelled request. Android may
        // crash the process if we stop before acknowledging a startForegroundService delivery.
        val notificationAllowed = notificationsAvailable(this, create = true)
        if (!promoted) promoted = runCatching {
            val notification = notification(Notice("正在检查后台电话条件", null))
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
        if (!promoted) {
            if (explicit || intent == null) calls?.onStandbyStartFailed("Android 未允许启动后台待命，请在应用中重试")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (calls == null || (intent != null && !explicit)) {
            if (calls != null && stickyAutoAnswer && calls.hasAuthorizedIncomingStandby()) return START_STICKY
            if (calls?.hasLockedDialForegroundWork() != true || statusJob == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val incoming = calls.hasAuthorizedIncomingStandby() && (intent == null || calls.isIncomingStandbyStartRequested() || stickyAutoAnswer)
        if (!notificationAllowed || (!incoming && (intent == null || !calls.hasLockedDialForegroundWork()))) {
            calls.onStandbyStartFailed(if (notificationAllowed) "后台待命尚未开启，或手机重启后尚未解锁" else "请允许后台待命通知")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stickyAutoAnswer = incoming
        calls.onStandbyStarted(this)
        if (statusJob == null) statusJob = scope.launch {
            calls.state.map { state ->
                val lockedDial = calls.hasLockedDialForegroundWork()
                val receive = stickyAutoAnswer && calls.hasAuthorizedIncomingStandby()
                if ((!receive && !lockedDial) || !notificationsAvailable(this@CallStandbyService)) return@map null
                Notice(when {
                    state.lockedDialPreparing -> "正在准备锁屏拨出 · 剩余 ${state.lockedDialRemainingSeconds ?: 0} 秒"
                    state.lockedDialRequestId != null -> "等待锁屏拨出 · 剩余 ${state.lockedDialRemainingSeconds ?: 0} 秒"
                    lockedDial -> "正在处理锁屏拨出"
                    state.autoAnswerStage == CallAutoAnswerStage.PAUSED -> "自动接听已暂停"
                    state.backgroundStandbyBlockedReason != null -> "后台待命暂不可用，打开查看"
                    state.aiActive -> "后台待命已开启，AI 正在通话"
                    else -> "后台待命已开启，可在电话设置中关闭"
                }, state.lockedDialRequestId)
            }.distinctUntilChanged().collect { notice ->
                if (notice == null) stopSelf()
                else if (runCatching {
                    checkNotNull(getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification(notice))
                }.isFailure) stopSelf()
            }
        }
        // A null Intent is only accepted when Android restarts this already opted-in service.
        // Our other recovery path runs only with the unlocked app visible and an existing grant.
        return if (incoming) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runtime?.onStandbyStopped(this)
        runtime = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private data class Notice(val summary: String, val requestId: String?)

    private fun notification(notice: Notice): Notification {
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_CALLS, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notice.summary)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(if (notice.requestId == null) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                notice.requestId?.let { id ->
                    val cancel = PendingIntent.getBroadcast(this@CallStandbyService, 0,
                        Intent(this@CallStandbyService, CallNotificationAction::class.java)
                            .setAction(CallNotifications.ACTION_CANCEL_LOCKED_DIAL)
                            .setData(Uri.fromParts(CallNotifications.LOCKED_DIAL_SCHEME, id, null)), PendingIntent.FLAG_IMMUTABLE)
                    addAction(Notification.Action.Builder(null, "取消锁屏拨出", cancel).build())
                }
            }
            .build()
    }

    companion object {
        const val CHANNEL = "mika_call_standby"
        private const val ACTION_START = "com.wangzan101.mika.calls.START_STANDBY"
        private const val EXTRA_REQUEST_ID = "standby_start_id"
        private const val NOTIFICATION_ID = 90_118

        internal fun startIntent(context: Context, requestId: String? = null): Intent =
            Intent(context, CallStandbyService::class.java).setAction(ACTION_START).putExtra(EXTRA_REQUEST_ID, requestId)

        internal fun notificationsAvailable(context: Context, create: Boolean = false): Boolean = runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            if (create) manager.createNotificationChannel(NotificationChannel(CHANNEL, "电话后台待命", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true
        }.getOrDefault(false)
    }
}

internal object CallStandbyPolicy {
    fun optedIn(config: CallConfig, userUnlocked: Boolean): Boolean =
        userUnlocked && config.enabled && config.autoAnswer && config.audioVerified && config.credentialsConfigured

    fun interactiveForeground(visible: Boolean, interactive: Boolean, keyguardShowing: Boolean): Boolean =
        visible && interactive && !keyguardShowing

    fun allowsAutomaticAnswer(userUnlocked: Boolean, interactiveForeground: Boolean, serviceRunning: Boolean, notificationsAllowed: Boolean): Boolean =
        userUnlocked && (interactiveForeground || serviceRunning && notificationsAllowed)

    fun activeWakeBudgetMs(maxCallSeconds: Int): Long = maxCallSeconds.coerceIn(15, CallPolicy.MAX_SECONDS) * 1_000L + 10_000L
}

/** A failed automatic restore must not spin; user retry is always available. */
internal class CallStandbyResumeGate {
    private var attemptedWhileVisible = false

    fun take(visible: Boolean, eligible: Boolean): Boolean {
        if (!visible) attemptedWhileVisible = false
        if (!visible || !eligible || attemptedWhileVisible) return false
        attemptedWhileVisible = true
        return true
    }
}
