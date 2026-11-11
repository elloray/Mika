package com.wangzan101.mika.calls

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp

/** Non-UI companion service; the user's dialer owns the native call screens and ring tone. */
class MikaInCallService : InCallService() {
    private val runtime get() = (application as MikaApp).calls
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = runtime.onCallChanged(this@MikaInCallService, call)
        override fun onDetailsChanged(call: Call, details: Call.Details) = runtime.onCallChanged(this@MikaInCallService, call)
    }
    private val attached = mutableSetOf<Call>()
    private val muteState = TelecomMuteState(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    internal val telecomMuted: Boolean? get() = muteState.value
    private var muteDebugEvents = 0

    @Suppress("DEPRECATION")
    internal fun tracks(call: Call): Boolean = call in attached &&
        calls.filter { it.state != Call.STATE_DISCONNECTED }.singleOrNull() === call

    @Suppress("DEPRECATION")
    internal fun requestMute(call: Call, muted: Boolean): Boolean =
        (call.state == Call.STATE_ACTIVE || (!muted && call.state == Call.STATE_HOLDING)) &&
            tracks(call) && runCatching {
                debugMute("request", muted)
                setMuted(muted)
            }.isSuccess

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        reportMute(TelecomMuteState.Source.LEGACY_AUDIO, audioState.isMuted)
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        reportMute(TelecomMuteState.Source.MUTE_CALLBACK, isMuted)
    }

    private fun reportMute(source: TelecomMuteState.Source, muted: Boolean) {
        val update = muteState.update(source, muted)
        val debugSource = when {
            update == TelecomMuteState.Update.IGNORED_LEGACY -> "legacy_ignored"
            source == TelecomMuteState.Source.LEGACY_AUDIO -> "legacy_audio"
            else -> "mute_callback"
        }
        debugMute(debugSource, muted)
        if (update == TelecomMuteState.Update.CHANGED) runtime.onTelecomMuteChanged(this, muted)
    }

    private fun debugMute(source: String, muted: Boolean) {
        if (BuildConfig.DEBUG && muteDebugEvents < 24) {
            muteDebugEvents++
            Log.d("MikaCallMute", "source=$source muted=$muted")
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        attached.add(call)
        call.registerCallback(callback)
        runtime.onCallAdded(this, call)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        attached.remove(call)
        runtime.onCallRemoved(this, call)
        super.onCallRemoved(call)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearCalls()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearCalls()
        super.onDestroy()
    }

    private fun clearCalls() {
        val removing = attached.toList()
        attached.clear()
        muteState.reset()
        muteDebugEvents = 0
        removing.forEach { call ->
            call.unregisterCallback(callback)
            runtime.onCallRemoved(this, call)
        }
    }
}
