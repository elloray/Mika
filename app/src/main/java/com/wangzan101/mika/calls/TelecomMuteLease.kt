package com.wangzan101.mika.calls

/** Main-thread policy only. Runtime binds the lease to a single Call and its InCallService. */
internal class TelecomMuteLease private constructor(private val originalMuted: Boolean) {
    enum class Release {
        PRESERVE, VERIFY_OVERRIDE, REQUEST_UNMUTE, VERIFY_UNMUTED;

        fun confirmed(sameCall: Boolean, telecomMuted: Boolean?, hardwareMuted: Boolean?): Boolean = when (this) {
            PRESERVE, VERIFY_OVERRIDE -> !sameCall || telecomMuted != null && telecomMuted == hardwareMuted
            REQUEST_UNMUTE, VERIFY_UNMUTED -> hardwareMuted == false && (!sameCall || telecomMuted == false)
        }
    }

    val needsMuteRequest: Boolean get() = !originalMuted
    private var muteRequested = false
    private var telecomAcknowledged = originalMuted
    private var relinquished = false
    private var releasing = false

    fun markMuteRequested() {
        check(needsMuteRequest && !muteRequested && !releasing)
        muteRequested = true
    }

    /** False after an acknowledged mute means Telecom or the user took back control. */
    fun observeTelecomMute(muted: Boolean): Boolean {
        if (releasing || relinquished) return false
        if (muted) {
            telecomAcknowledged = true
        } else if (telecomAcknowledged) {
            relinquished = true
            return true
        }
        return false
    }

    fun isConfirmed(telecomMuted: Boolean?, hardwareMuted: Boolean?): Boolean =
        !releasing && !relinquished && telecomAcknowledged && telecomMuted == true && hardwareMuted == true

    fun beginRelease(sameCall: Boolean): Release {
        val alreadyReleasing = releasing
        releasing = true
        return when {
            relinquished -> Release.VERIFY_OVERRIDE
            !muteRequested -> Release.PRESERVE
            sameCall && !alreadyReleasing -> Release.REQUEST_UNMUTE
            else -> Release.VERIFY_UNMUTED
        }
    }

    companion object {
        fun fromBaseline(telecomMuted: Boolean?, hardwareMuted: Boolean?): TelecomMuteLease? =
            telecomMuted?.takeIf { it == hardwareMuted }?.let(::TelecomMuteLease)
    }
}

/** Android 14+ sends the legacy audio state on bind, then has a dedicated mute callback. */
internal class TelecomMuteState(private val dedicatedMuteCallback: Boolean) {
    enum class Source { LEGACY_AUDIO, MUTE_CALLBACK }
    enum class Update { CHANGED, DUPLICATE, IGNORED_LEGACY }

    var value: Boolean? = null
        private set

    fun update(source: Source, muted: Boolean): Update {
        // A later route/audio snapshot is not authoritative for mute on Android 14+.
        if (dedicatedMuteCallback && source == Source.LEGACY_AUDIO && value != null) return Update.IGNORED_LEGACY
        if (value == muted) return Update.DUPLICATE
        value = muted
        return Update.CHANGED
    }

    fun reset() {
        value = null
    }
}

/** An allowlist maps known shell outcomes to fixed diagnostics; no raw shell string leaves here. */
internal fun shellAudioStopDiagnostic(reason: String?): String? = when (val code = reason?.trim()) {
    "Handset microphone was unmuted." -> "设备麦克风静音已解除，AI 已交还通话"
    "Cellular audio mode ended.", "Cellular audio ended." -> "运营商通话音频模式已结束，电话由系统处理"
    "Downlink consumer stopped draining audio." -> "运营商下行音频传输阻塞，电话由系统处理"
    "Android silenced the cellular audio capture." -> "安卓停止向 AI 提供通话声音，已交还系统"
    "Uplink playback stopped accepting audio." -> "运营商上行音频播放无进展，电话由系统处理"
    "Helper released.", "Call audio owner died." -> "内置助手已释放电话音频，电话由系统处理"
    else -> when {
        code == null -> null
        code.startsWith("Downlink read failed (") -> "设备无法读取运营商下行音频，电话由系统处理"
        code.startsWith("Uplink write failed (") -> "设备无法写入运营商上行音频，电话由系统处理"
        code.startsWith("Uplink flush failed:") -> "设备无法清空运营商上行音频，电话由系统处理"
        code.startsWith("Uplink did not route to the telephony device (") ||
            code.startsWith("Uplink unexpectedly routed to device type ") -> "设备上行未路由至运营商通话，电话由系统处理"
        code.startsWith("Downlink unexpectedly routed to device type ") -> "设备下行未路由至运营商通话，电话由系统处理"
        else -> null
    }
}
