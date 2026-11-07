package com.wangzan101.mika.settings

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 用 ChatGPT 登录期间的前台服务:Mika 退到后台、用户在浏览器里登录时撑住进程,系统不冻结它,
 * 本机回调才有人接、换令牌不被耽误。只挂一条安静的通知,上面能「取消」这次登录。
 * 不自己做事:登录跑在 [ChatGptSignIns] 里,这里看着它的 running,登录一结束(成功、失败、取消、超时)就停。
 */
class ChatGptSignInService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val signIns get() = (application as MikaApp).chatGptSignIn
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        // startForegroundService 之后必须尽快进前台,哪怕马上就要停;进不了前台就此停下,登录照常(只是少了这层保护)
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure { Log.w(TAG, "sign-in service could not enter the foreground", it) }.isSuccess
        if (intent?.action == ACTION_CANCEL) signIns.cancel()
        if (FeatureFlags.get(this).quitRequested()) signIns.cancel()
        if (!foreground || FeatureFlags.get(this).quitRequested()) {
            stopSelf()
            return START_NOT_STICKY
        }
        watch?.cancel()
        watch = scope.launch {
            signIns.running.first { !it }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // 进程没了登录也就没了,不用重建
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ChatGptSignInService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(signIns.noticeTitle ?: tr(R.string.chatgpt_signin_notif_title))
            .setContentText(signIns.noticeBody ?: tr(R.string.chatgpt_signin_notif_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(TaskService.openApp(this))
            .addAction(Notification.Action.Builder(null, tr(R.string.cancel), cancel).build())
            .build()
    }

    companion object {
        private const val TAG = "MikaChatGpt"
        private const val ACTION_CANCEL = "com.wangzan101.mika.chatgpt.CANCEL_SIGN_IN"
        /** 和任务、电脑连接的通知错开。 */
        private const val NOTIFICATION_ID = 30_002

        /** 登录开始时调(用户刚点了登录,Mika 在前台,可以开前台服务);开不了抛出,由调用方记下。 */
        fun start(context: Context) {
            if (FeatureFlags.get(context).quitRequested()) return
            context.startForegroundService(Intent(context, ChatGptSignInService::class.java))
        }
    }
}
