package com.wangzan101.mika.calls

/** A target in the shell AudioTrack's current flush epoch, excluding its priming silence. */
internal data class CallFarewellFence(val epoch: Long, val dataBytes: Long, val explicit: Boolean) {
    fun reached(
        playedEpoch: Long, acceptedBytes: Long, primeFrames: Long, playedFrames: Long,
        fresh: Boolean, routed: Boolean,
    ): Boolean = fresh && routed && playedEpoch == epoch && acceptedBytes == dataBytes &&
        dataBytes > 0L && dataBytes % 2L == 0L && primeFrames > 0L &&
        playedFrames >= primeFrames && playedFrames - primeFrames >= dataBytes / 2L
}

/** Pairs a complete caller turn with a short, decisive closing in the next spoken reply. */
internal class CallFarewellPlayback {
    private var epoch = 0L
    private var totalBytes = 0L
    private var haveCallerTurn = false
    private var explicit = false
    private var responseStartBytes: Long? = null
    private var audioEnded = false
    private var goodbyeSpoken = false

    // Includes queued and in-flight data, so a blocked writer never makes an epoch appear empty.
    val hasPlaybackSinceFlush: Boolean get() = totalBytes > 0L

    fun afterPairedFlush() {
        epoch += 2
        totalBytes = 0
        cancelCandidate()
    }

    fun callerFinal(text: String, complete: Boolean) {
        cancelCandidate()
        haveCallerTurn = complete && text.isNotBlank()
        explicit = haveCallerTurn && CallFarewellPolicy.isExplicitCallerFarewell(text)
    }

    fun audioStarted() {
        if (responseStartBytes != null) cancelCandidate()
        if (haveCallerTurn) responseStartBytes = totalBytes
    }

    fun audio(bytes: Int) { totalBytes += bytes }

    fun audioEnded() {
        audioEnded = responseStartBytes?.let { totalBytes > it } == true
    }

    fun assistantFinal(text: String) {
        if (haveCallerTurn && CallFarewellPolicy.isAssistantClosing(text)) goodbyeSpoken = true
        else cancelCandidate()
    }

    fun takeFence(): CallFarewellFence? {
        if (!haveCallerTurn || !audioEnded || !goodbyeSpoken) return null
        val fence = CallFarewellFence(epoch, totalBytes, explicit)
        cancelCandidate()
        return fence
    }

    fun cancelCandidate() {
        haveCallerTurn = false
        explicit = false
        responseStartBytes = null
        audioEnded = false
        goodbyeSpoken = false
    }
}
