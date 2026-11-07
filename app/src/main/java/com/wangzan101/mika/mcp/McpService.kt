package com.wangzan101.mika.mcp

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService

/**
 * 电脑连接打开期间的前台服务:只为让进程在后台活着、端口一直开着;收到的任务照旧交给 [TaskService] 去跑。
 * 任务服务没任务时会自己退出,所以监听不放在它里面。常驻通知上能直接关掉电脑连接(两种都关)。
 * 被系统回收后会重建(START_STICKY);开关都关了就当场退出。
 *
 * 电脑经 USB 调试打开、关掉 USB 方式也是起这个服务:清单里它对外开放,但要求调用方持有
 * INTERACT_ACROSS_USERS_FULL。这个权限 shell 有,普通 App 拿不到(只给系统签名和安装器,adb 也授不了),
 * 所以只有 `adb shell am start-foreground-service` 这样以 shell 身份发起的才起得了;Mika 自己起不受限。
 * 以前台服务的方式起:安卓 12 起后台的 App 一般不能自己开前台服务,但由 shell 发起的启动不在此限。
 */
class McpService : Service() {
    private val runtime get() = (application as MikaApp).mcp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        val quitting = FeatureFlags.get(this).quitRequested()
        val applied = !quitting && runCatching { McpModes.apply(intent?.action, runtime.settings, runtime.usb, runtime.relaySettings) }
            .onFailure { Log.w("MikaMcp", "could not update computer access settings", it) }.isSuccess
        // startForegroundService 之后必须尽快进前台,哪怕马上就要退出;系统不让进前台时就此退出,不让进程崩
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure { Log.w("MikaMcp", "computer access service could not enter the foreground", it) }.isSuccess
        val ready = foreground && !FeatureFlags.get(this).quitRequested() && applied && runCatching { runtime.sync() }
            .onFailure { Log.w("MikaMcp", "could not start computer access", it) }.getOrDefault(false)
        if (!ready) {
            runtime.stopServer()
            runtime.usb.unpublish()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.stopServer()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val port = runtime.settings.port
        val body = when {
            runtime.relaySettings.config.value.enabled -> R.string.relay_notif_body
            runtime.settings.enabled.value && runtime.usb.enabled.value -> R.string.mcp_notif_body_both
            runtime.usb.enabled.value -> R.string.mcp_notif_body_usb
            else -> R.string.mcp_notif_body
        }
        val open = TaskService.openApp(this)
        val off = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(McpModes.ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.mcp_notif_title))
            .setContentText(tr(body, port))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, tr(R.string.mcp_turn_off), off).build())
            .build()
    }

    companion object {
        /** 和任务通知(槽位编号、结果编号段)错开。 */
        private const val NOTIFICATION_ID = 30_001
    }
}
