package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.i18n.tr

/** Text and exported images have independent receipts; an uncertain image is never resent on recovery. */
internal object ResultDelivery {
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

    suspend fun prepare(outbox: Outbox, channel: Channel, peer: ChannelPeer, result: Delivery.Result, files: List<SharedFile>) {
        val images = if (!channel.supportsImages) emptyList() else files
            .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .distinctBy { it.uri }
            .mapIndexed { index, file ->
                Delivery.Image("${result.id}:image:$index", result.threadId, file.uri, file.name, file.size, file.sha256)
            }
        outbox.prepare(peer, listOf(result) + images)
    }

    suspend fun send(outbox: Outbox, channel: Channel, peer: ChannelPeer, result: Delivery.Result): DeliveryState {
        val saved = outbox.get(result.id)
        val text = saved?.delivery as? Delivery.Result ?: result
        val images = outbox.records().filter { it.peer == peer && it.delivery is Delivery.Image &&
            it.delivery.id.startsWith("${result.id}:image:") }
            .sortedBy { it.delivery.id.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE }
        val states = (listOf(text) + images.map { it.delivery }).map { outbox.deliver(channel, peer, it).state }
        val state = when {
            states.any { it == DeliveryState.Uncertain || it == DeliveryState.Attempting } -> DeliveryState.Uncertain
            states.any { it == DeliveryState.Failed } -> DeliveryState.Failed
            states.all { it == DeliveryState.Delivered } -> DeliveryState.Delivered
            else -> DeliveryState.Pending
        }
        if (state != DeliveryState.Delivered) {
            val failed = state == DeliveryState.Failed
            outbox.deliver(channel, peer, Delivery.Notice(
                "${if (failed) "failed" else "uncertain"}:${result.id}", peer.threadId,
                tr(if (failed) R.string.recover_delivery_failed else R.string.recover_maybe_delivered)))
        }
        return state
    }
}
