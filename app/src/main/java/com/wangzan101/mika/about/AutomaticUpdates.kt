package com.wangzan101.mika.about

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** 自动下载复用更新任务；进程存活时离开前台仍继续，回来再看结果。 */
internal class AutomaticUpdates(context: Context, private val updater: ApkUpdater) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val prefs = context.getSharedPreferences("automatic-updates", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled = _enabled.asStateFlow()
    private val _ready = MutableStateFlow<ReleaseInfo?>(null)
    val ready = _ready.asStateFlow()
    private var dismissedUrl: String? = null

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
        _enabled.value = enabled
        if (!enabled) _ready.value = null
    }

    fun dismiss(release: ReleaseInfo) {
        dismissedUrl = release.apkUrl
        if (_ready.value?.apkUrl == dismissedUrl) _ready.value = null
    }

    suspend fun watch(updates: StateFlow<UpdateState>) {
        combine(enabled, updates, wifiNetworks()) { on, state, wifi ->
            state.update?.takeIf { on && it.apkUrl != null } to wifi
        }.distinctUntilChanged().collectLatest { (release, wifi) ->
            if (_ready.value?.apkUrl != release?.apkUrl) _ready.value = null
            if (release == null || release.apkUrl == dismissedUrl) return@collectLatest
            try {
                if (wifi != null) {
                    withContext(Dispatchers.IO) { updater.download(release, wifi) }
                }
                while (true) {
                    when (withContext(Dispatchers.IO) { updater.state(release).phase }) {
                        DownloadPhase.Ready -> {
                            if (enabled.value && release.apkUrl != dismissedUrl) _ready.value = release
                            break
                        }
                        DownloadPhase.Downloading, DownloadPhase.Paused -> delay(1000)
                        DownloadPhase.Idle, DownloadPhase.Failed -> break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MikaAutoUpdate", "Could not download update ${release.version}", e)
            }
        }
    }

    private fun wifiNetworks() = callbackFlow<Network?> {
        fun activeWifi(): Network? = connectivity.activeNetwork?.takeIf {
            connectivity.getNetworkCapabilities(it)?.isPhysicalWifi() == true
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(network.takeIf { capabilities.isPhysicalWifi() })
            }

            override fun onLost(network: Network) {
                trySend(activeWifi())
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        trySend(activeWifi())
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun NetworkCapabilities.isPhysicalWifi(): Boolean =
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
}
