package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Serializable
data class PendingWechatMessage(
    val messageId: String,
    val peer: ChannelPeer,
    val text: String,
    val replyToAskId: String? = null,
)

@Serializable
private data class WechatState(
    val enabled: Boolean = false,
    val cursor: String = "",
    val threads: Map<String, String> = emptyMap(),
    val contextTokens: Map<String, String> = emptyMap(),
    val pending: List<PendingWechatMessage> = emptyList(),
    /** 按确认先后保留最近一段，游标回退或进程恢复时也不会再次登记同一消息。 */
    val acknowledgedMessages: List<String> = emptyList(),
)

/** 凭证与运行状态分开保存：令牌加密，游标和未交任务的消息原子落盘。 */
class WechatStore(private val dir: File, private val cipher: KeyCipher) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val credentials = File(dir, "account.enc")
    private val stateFile = File(dir, "state.json")
    private var state = readState()
    @Volatile private var account: WechatAccount? = readAccount()

    @Synchronized fun account(): WechatAccount? = account

    @Synchronized fun saveAccount(value: WechatAccount) {
        val sameAccount = account?.let { it.ownerId == value.ownerId && it.botId == value.botId } == true
        if (!sameAccount) {
            // 先清旧账号的游标与账本；即使进程在换绑中途退出，也不会把旧消息交给新账号。
            state = WechatState()
            saveState()
        }
        writeAtomic(credentials, cipher.encrypt(json.encodeToString(WechatAccount.serializer(), value).toByteArray()))
        account = value
    }

    @Synchronized fun clear() {
        account = null
        credentials.delete()
        state = WechatState()
        saveState()
    }

    @Synchronized fun enabled() = state.enabled
    @Synchronized fun setEnabled(value: Boolean) { state = state.copy(enabled = value); saveState() }
    @Synchronized fun cursor() = state.cursor
    @Synchronized fun saveCursor(value: String) { state = state.copy(cursor = value); saveState() }
    @Synchronized fun contextToken(peerId: String) = state.contextTokens[peerId]

    @Synchronized fun rememberContext(peerId: String, token: String?) {
        if (token.isNullOrBlank() || state.contextTokens[peerId] == token) return
        state = state.copy(contextTokens = state.contextTokens + (peerId to token))
        saveState()
    }

    @Synchronized fun peer(peerId: String): ChannelPeer {
        val thread = state.threads[peerId] ?: "wechat-${UUID.randomUUID()}".also {
            state = state.copy(threads = state.threads + (peerId to it))
            saveState()
        }
        return ChannelPeer(WechatChannel.ID, peerId, thread)
    }

    /** 同一消息只入站一次；未确认的会在下次启动由 [pending] 补交。 */
    @Synchronized fun journal(messageId: String, peer: ChannelPeer, text: String, replyToAskId: String? = null): Boolean {
        if (messageId in state.acknowledgedMessages || state.pending.any { it.messageId == messageId }) return false
        state = state.copy(pending = state.pending + PendingWechatMessage(messageId, peer, text, replyToAskId))
        saveState()
        return true
    }

    @Synchronized fun pending(): List<PendingWechatMessage> = state.pending

    @Synchronized fun acknowledge(messageId: String) {
        if (messageId in state.acknowledgedMessages && state.pending.none { it.messageId == messageId }) return
        state = state.copy(
            pending = state.pending.filterNot { it.messageId == messageId },
            acknowledgedMessages = (state.acknowledgedMessages.filterNot { it == messageId } + messageId)
                .takeLast(MAX_ACKNOWLEDGED_MESSAGES),
        )
        saveState()
    }

    private fun readState(): WechatState = if (stateFile.isFile) runCatching {
        json.decodeFromString(WechatState.serializer(), stateFile.readText())
    }.getOrElse { WechatState() } else WechatState()

    private fun readAccount(): WechatAccount? = if (credentials.isFile) runCatching {
        json.decodeFromString(WechatAccount.serializer(), cipher.decrypt(credentials.readBytes()).decodeToString())
    }.getOrNull() else null

    private fun saveState() = writeAtomic(stateFile, json.encodeToString(WechatState.serializer(), state).toByteArray())

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "WeChat state rename failed" }
        }
    }

    companion object {
        internal const val MAX_ACKNOWLEDGED_MESSAGES = 512
    }
}
